package com.sunbit.repair.loader

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.sql.*
import java.util.*
import java.util.logging.Logger as JULLogger
import javax.sql.DataSource

/**
 * When snowflake.proxy-url is set, creates a fake DataSource that routes all SQL
 * through the HTTP proxy (snowflake-proxy.py on the host). This replaces direct
 * JDBC connections to Snowflake, avoiding the SSO browser requirement in Docker.
 *
 * When proxy-url is NOT set, the normal SnowflakeConfig creates the real JDBC DataSource.
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'\${snowflake.proxy-url:}' != ''")
class SnowflakeProxyConfig {

    private val log = LoggerFactory.getLogger(SnowflakeProxyConfig::class.java)

    @Bean("snowflakeDataSource")
    @Primary
    fun proxyDataSource(
        @Value("\${snowflake.proxy-url}") proxyUrl: String,
    ): DataSource {
        log.info("[SnowflakeProxyConfig] Using Snowflake HTTP proxy at {}", proxyUrl)
        return ProxyDataSource(proxyUrl)
    }

    @Bean("snowflakeJdbcTemplate")
    @Primary
    fun proxyJdbcTemplate(
        @Qualifier("snowflakeDataSource") dataSource: DataSource,
    ): JdbcTemplate {
        val template = JdbcTemplate(dataSource)
        template.queryTimeout = 120
        return template
    }
}

class ProxyDataSource(private val proxyUrl: String) : DataSource {
    override fun getConnection(): Connection = ProxyConnection(proxyUrl)
    override fun getConnection(username: String?, password: String?): Connection = getConnection()
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) {}
    override fun setLoginTimeout(seconds: Int) {}
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): JULLogger = JULLogger.getLogger("ProxyDataSource")
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

class ProxyConnection(private val proxyUrl: String) : ConnectionStub() {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    override fun prepareStatement(sql: String): PreparedStatement = ProxyPreparedStatement(proxyUrl, sql, mapper)
    override fun createStatement(): Statement = ProxyStatement(proxyUrl, mapper)
    override fun close() {}
    override fun isClosed(): Boolean = false
}

class ProxyPreparedStatement(
    private val proxyUrl: String,
    private val sql: String,
    private val mapper: ObjectMapper,
) : PreparedStatementStub() {

    private val params = mutableMapOf<Int, Any?>()

    override fun setString(parameterIndex: Int, x: String?) { params[parameterIndex] = x }
    override fun setLong(parameterIndex: Int, x: Long) { params[parameterIndex] = x }
    override fun setInt(parameterIndex: Int, x: Int) { params[parameterIndex] = x }
    override fun setObject(parameterIndex: Int, x: Any?) { params[parameterIndex] = x }
    override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int) { params[parameterIndex] = x }
    override fun setNull(parameterIndex: Int, sqlType: Int) { params[parameterIndex] = null }

    override fun executeQuery(): ResultSet {
        val interpolated = interpolateSql()
        org.slf4j.LoggerFactory.getLogger("ProxyPS").info("[ProxyPS] params={} sql_preview={}", params, interpolated.take(100))
        return executeViaProxy(proxyUrl, interpolated, mapper)
    }

    override fun executeUpdate(): Int {
        val interpolated = interpolateSql()
        executeViaProxy(proxyUrl, interpolated, mapper)
        return 0
    }

    override fun close() {}

    private fun interpolateSql(): String {
        var result = sql
        // Replace ? placeholders with param values, in order
        for (i in 1..params.size) {
            val param = params[i]
            val replacement = when (param) {
                is String -> "'${param.replace("'", "''")}'"
                is Long, is Int -> param.toString()
                null -> "NULL"
                else -> "'${param.toString().replace("'", "''")}'"
            }
            result = result.replaceFirst("?", replacement)
        }
        return result
    }
}

class ProxyStatement(
    private val proxyUrl: String,
    private val mapper: ObjectMapper,
) : StatementStub() {
    override fun executeQuery(sql: String): ResultSet = executeViaProxy(proxyUrl, sql, mapper)
    override fun close() {}
}

private fun executeViaProxy(proxyUrl: String, sql: String, mapper: ObjectMapper): ResultSet {
    val url = URL("$proxyUrl/query")
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    conn.connectTimeout = 10_000
    conn.readTimeout = 120_000

    val body = mapper.writeValueAsString(mapOf("sql" to sql))
    OutputStreamWriter(conn.outputStream).use { it.write(body); it.flush() }

    if (conn.responseCode != 200) {
        val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
        throw SQLException("Snowflake proxy error (HTTP ${conn.responseCode}): $error")
    }

    val responseBody = conn.inputStream.bufferedReader().readText()
    val result: ProxyQueryResult = mapper.readValue(responseBody)

    if (result.error != null) {
        throw SQLException("Snowflake proxy query error: ${result.error}")
    }

    return ProxyResultSet(result.columns, result.rows)
}

data class ProxyQueryResult(
    val rows: List<Map<String, Any?>> = emptyList(),
    val columns: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * ResultSet backed by a list of Maps from the proxy response.
 */
class ProxyResultSet(
    private val columns: List<String>,
    private val rows: List<Map<String, Any?>>,
) : ResultSetStub() {

    private var cursor = -1
    private var wasNullFlag = false

    override fun next(): Boolean {
        cursor++
        return cursor < rows.size
    }

    private fun currentRow(): Map<String, Any?> = rows[cursor]

    override fun getString(columnLabel: String): String? {
        val v = currentRow()[columnLabel.uppercase()]
        wasNullFlag = v == null
        return v?.toString()
    }

    override fun getLong(columnLabel: String): Long {
        val v = currentRow()[columnLabel.uppercase()]
        wasNullFlag = v == null
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            null -> 0L
            else -> 0L
        }
    }

    override fun getInt(columnLabel: String): Int {
        val v = currentRow()[columnLabel.uppercase()]
        wasNullFlag = v == null
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            null -> 0
            else -> 0
        }
    }

    override fun getBoolean(columnLabel: String): Boolean {
        val v = currentRow()[columnLabel.uppercase()]
        wasNullFlag = v == null
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true) || v == "1"
            null -> false
            else -> false
        }
    }

    override fun getBigDecimal(columnLabel: String): java.math.BigDecimal? {
        val v = currentRow()[columnLabel.uppercase()]
        wasNullFlag = v == null
        return when (v) {
            is Number -> java.math.BigDecimal(v.toString())
            is String -> v.toBigDecimalOrNull()
            null -> null
            else -> null
        }
    }

    override fun getTimestamp(columnLabel: String): Timestamp? {
        val v = currentRow()[columnLabel.uppercase()]?.toString()
        wasNullFlag = v == null
        if (v == null) return null
        return try {
            Timestamp.valueOf(v.replace("T", " ").take(19))
        } catch (_: Exception) {
            null
        }
    }

    override fun getDate(columnLabel: String): java.sql.Date? {
        val v = currentRow()[columnLabel.uppercase()]?.toString()
        wasNullFlag = v == null
        if (v == null) return null
        return try {
            java.sql.Date.valueOf(v.take(10))
        } catch (_: Exception) {
            null
        }
    }

    override fun wasNull(): Boolean = wasNullFlag
    override fun close() {}
    override fun isClosed(): Boolean = false
    override fun getMetaData(): ResultSetMetaData? = null
}

// Stub classes that throw UnsupportedOperationException for all methods.
// The proxy implementations override only what's needed.

abstract class ConnectionStub : Connection {
    override fun createStatement(): Statement = throw UnsupportedOperationException()
    override fun prepareStatement(sql: String): PreparedStatement = throw UnsupportedOperationException()
    override fun prepareCall(sql: String): CallableStatement = throw UnsupportedOperationException()
    override fun nativeSQL(sql: String): String = sql
    override fun setAutoCommit(autoCommit: Boolean) {}
    override fun getAutoCommit(): Boolean = true
    override fun commit() {}
    override fun rollback() {}
    override fun close() {}
    override fun isClosed(): Boolean = false
    override fun getMetaData(): DatabaseMetaData? = null
    override fun setReadOnly(readOnly: Boolean) {}
    override fun isReadOnly(): Boolean = true
    override fun setCatalog(catalog: String?) {}
    override fun getCatalog(): String? = null
    override fun setTransactionIsolation(level: Int) {}
    override fun getTransactionIsolation(): Int = Connection.TRANSACTION_READ_COMMITTED
    override fun getWarnings(): SQLWarning? = null
    override fun clearWarnings() {}
    override fun createStatement(resultSetType: Int, resultSetConcurrency: Int): Statement = createStatement()
    override fun prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement = prepareStatement(sql)
    override fun prepareCall(sql: String, resultSetType: Int, resultSetConcurrency: Int): CallableStatement = throw UnsupportedOperationException()
    override fun getTypeMap(): MutableMap<String, Class<*>>? = null
    override fun setTypeMap(map: MutableMap<String, Class<*>>?) {}
    override fun setHoldability(holdability: Int) {}
    override fun getHoldability(): Int = ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun setSavepoint(): Savepoint = throw UnsupportedOperationException()
    override fun setSavepoint(name: String): Savepoint = throw UnsupportedOperationException()
    override fun rollback(savepoint: Savepoint) {}
    override fun releaseSavepoint(savepoint: Savepoint) {}
    override fun createStatement(resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): Statement = createStatement()
    override fun prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): PreparedStatement = prepareStatement(sql)
    override fun prepareCall(sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): CallableStatement = throw UnsupportedOperationException()
    override fun prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement = prepareStatement(sql)
    override fun prepareStatement(sql: String, columnIndexes: IntArray): PreparedStatement = prepareStatement(sql)
    override fun prepareStatement(sql: String, columnNames: Array<out String>): PreparedStatement = prepareStatement(sql)
    override fun createClob(): Clob = throw UnsupportedOperationException()
    override fun createBlob(): Blob = throw UnsupportedOperationException()
    override fun createNClob(): NClob = throw UnsupportedOperationException()
    override fun createSQLXML(): SQLXML = throw UnsupportedOperationException()
    override fun isValid(timeout: Int): Boolean = true
    override fun setClientInfo(name: String?, value: String?) {}
    override fun setClientInfo(properties: Properties?) {}
    override fun getClientInfo(name: String?): String? = null
    override fun getClientInfo(): Properties = Properties()
    override fun createArrayOf(typeName: String?, elements: Array<out Any>?): java.sql.Array = throw UnsupportedOperationException()
    override fun createStruct(typeName: String?, attributes: Array<out Any>?): Struct = throw UnsupportedOperationException()
    override fun setSchema(schema: String?) {}
    override fun getSchema(): String? = null
    override fun abort(executor: java.util.concurrent.Executor?) {}
    override fun setNetworkTimeout(executor: java.util.concurrent.Executor?, milliseconds: Int) {}
    override fun getNetworkTimeout(): Int = 0
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

abstract class StatementStub : Statement {
    override fun executeQuery(sql: String): ResultSet = throw UnsupportedOperationException()
    override fun executeUpdate(sql: String): Int = throw UnsupportedOperationException()
    override fun close() {}
    override fun getMaxFieldSize(): Int = 0
    override fun setMaxFieldSize(max: Int) {}
    override fun getMaxRows(): Int = 0
    override fun setMaxRows(max: Int) {}
    override fun setEscapeProcessing(enable: Boolean) {}
    override fun getQueryTimeout(): Int = 0
    override fun setQueryTimeout(seconds: Int) {}
    override fun cancel() {}
    override fun getWarnings(): SQLWarning? = null
    override fun clearWarnings() {}
    override fun setCursorName(name: String?) {}
    override fun execute(sql: String): Boolean = false
    override fun getResultSet(): ResultSet? = null
    override fun getUpdateCount(): Int = -1
    override fun getMoreResults(): Boolean = false
    override fun setFetchDirection(direction: Int) {}
    override fun getFetchDirection(): Int = ResultSet.FETCH_FORWARD
    override fun setFetchSize(rows: Int) {}
    override fun getFetchSize(): Int = 0
    override fun getResultSetConcurrency(): Int = ResultSet.CONCUR_READ_ONLY
    override fun getResultSetType(): Int = ResultSet.TYPE_FORWARD_ONLY
    override fun addBatch(sql: String?) {}
    override fun clearBatch() {}
    override fun executeBatch(): IntArray = intArrayOf()
    override fun getConnection(): Connection? = null
    override fun getMoreResults(current: Int): Boolean = false
    override fun getGeneratedKeys(): ResultSet? = null
    override fun executeUpdate(sql: String, autoGeneratedKeys: Int): Int = 0
    override fun executeUpdate(sql: String, columnIndexes: IntArray?): Int = 0
    override fun executeUpdate(sql: String, columnNames: Array<out String>?): Int = 0
    override fun execute(sql: String, autoGeneratedKeys: Int): Boolean = false
    override fun execute(sql: String, columnIndexes: IntArray?): Boolean = false
    override fun execute(sql: String, columnNames: Array<out String>?): Boolean = false
    override fun getResultSetHoldability(): Int = ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun isClosed(): Boolean = false
    override fun setPoolable(poolable: Boolean) {}
    override fun isPoolable(): Boolean = false
    override fun closeOnCompletion() {}
    override fun isCloseOnCompletion(): Boolean = false
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

abstract class PreparedStatementStub : StatementStub(), PreparedStatement {
    override fun executeQuery(): ResultSet = throw UnsupportedOperationException()
    override fun executeUpdate(): Int = throw UnsupportedOperationException()
    override fun setNull(parameterIndex: Int, sqlType: Int) {}
    override fun setBoolean(parameterIndex: Int, x: Boolean) {}
    override fun setByte(parameterIndex: Int, x: Byte) {}
    override fun setShort(parameterIndex: Int, x: Short) {}
    override fun setInt(parameterIndex: Int, x: Int) {}
    override fun setLong(parameterIndex: Int, x: Long) {}
    override fun setFloat(parameterIndex: Int, x: Float) {}
    override fun setDouble(parameterIndex: Int, x: Double) {}
    override fun setBigDecimal(parameterIndex: Int, x: java.math.BigDecimal?) {}
    override fun setString(parameterIndex: Int, x: String?) {}
    override fun setBytes(parameterIndex: Int, x: ByteArray?) {}
    override fun setDate(parameterIndex: Int, x: java.sql.Date?) {}
    override fun setTime(parameterIndex: Int, x: java.sql.Time?) {}
    override fun setTimestamp(parameterIndex: Int, x: Timestamp?) {}
    override fun setAsciiStream(parameterIndex: Int, x: java.io.InputStream?, length: Int) {}
    @Deprecated("") override fun setUnicodeStream(parameterIndex: Int, x: java.io.InputStream?, length: Int) {}
    override fun setBinaryStream(parameterIndex: Int, x: java.io.InputStream?, length: Int) {}
    override fun clearParameters() {}
    override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int) {}
    override fun setObject(parameterIndex: Int, x: Any?) {}
    override fun execute(): Boolean = false
    override fun addBatch() {}
    override fun setCharacterStream(parameterIndex: Int, reader: java.io.Reader?, length: Int) {}
    override fun setRef(parameterIndex: Int, x: Ref?) {}
    override fun setBlob(parameterIndex: Int, x: Blob?) {}
    override fun setClob(parameterIndex: Int, x: Clob?) {}
    override fun setArray(parameterIndex: Int, x: java.sql.Array?) {}
    override fun getMetaData(): ResultSetMetaData? = null
    override fun setDate(parameterIndex: Int, x: java.sql.Date?, cal: Calendar?) {}
    override fun setTime(parameterIndex: Int, x: java.sql.Time?, cal: Calendar?) {}
    override fun setTimestamp(parameterIndex: Int, x: Timestamp?, cal: Calendar?) {}
    override fun setNull(parameterIndex: Int, sqlType: Int, typeName: String?) {}
    override fun setURL(parameterIndex: Int, x: URL?) {}
    override fun getParameterMetaData(): ParameterMetaData? = null
    override fun setRowId(parameterIndex: Int, x: RowId?) {}
    override fun setNString(parameterIndex: Int, value: String?) {}
    override fun setNCharacterStream(parameterIndex: Int, value: java.io.Reader?, length: Long) {}
    override fun setNClob(parameterIndex: Int, value: NClob?) {}
    override fun setClob(parameterIndex: Int, reader: java.io.Reader?, length: Long) {}
    override fun setBlob(parameterIndex: Int, inputStream: java.io.InputStream?, length: Long) {}
    override fun setNClob(parameterIndex: Int, reader: java.io.Reader?, length: Long) {}
    override fun setSQLXML(parameterIndex: Int, xmlObject: SQLXML?) {}
    override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int, scaleOrLength: Int) {}
    override fun setAsciiStream(parameterIndex: Int, x: java.io.InputStream?, length: Long) {}
    override fun setBinaryStream(parameterIndex: Int, x: java.io.InputStream?, length: Long) {}
    override fun setCharacterStream(parameterIndex: Int, reader: java.io.Reader?, length: Long) {}
    override fun setAsciiStream(parameterIndex: Int, x: java.io.InputStream?) {}
    override fun setBinaryStream(parameterIndex: Int, x: java.io.InputStream?) {}
    override fun setCharacterStream(parameterIndex: Int, reader: java.io.Reader?) {}
    override fun setNCharacterStream(parameterIndex: Int, value: java.io.Reader?) {}
    override fun setClob(parameterIndex: Int, reader: java.io.Reader?) {}
    override fun setBlob(parameterIndex: Int, inputStream: java.io.InputStream?) {}
    override fun setNClob(parameterIndex: Int, reader: java.io.Reader?) {}
}

abstract class ResultSetStub : ResultSet {
    override fun next(): Boolean = false
    override fun close() {}
    override fun wasNull(): Boolean = false
    override fun getString(columnLabel: String): String? = null
    override fun getBoolean(columnLabel: String): Boolean = false
    override fun getInt(columnLabel: String): Int = 0
    override fun getLong(columnLabel: String): Long = 0
    override fun getBigDecimal(columnLabel: String): java.math.BigDecimal? = null
    override fun getDate(columnLabel: String): java.sql.Date? = null
    override fun getTimestamp(columnLabel: String): Timestamp? = null
    override fun getString(columnIndex: Int): String? = null
    override fun getBoolean(columnIndex: Int): Boolean = false
    override fun getByte(columnIndex: Int): Byte = 0
    override fun getShort(columnIndex: Int): Short = 0
    override fun getInt(columnIndex: Int): Int = 0
    override fun getLong(columnIndex: Int): Long = 0
    override fun getFloat(columnIndex: Int): Float = 0f
    override fun getDouble(columnIndex: Int): Double = 0.0
    override fun getBigDecimal(columnIndex: Int, scale: Int): java.math.BigDecimal? = null
    override fun getBytes(columnIndex: Int): ByteArray? = null
    override fun getDate(columnIndex: Int): java.sql.Date? = null
    override fun getTime(columnIndex: Int): java.sql.Time? = null
    override fun getTimestamp(columnIndex: Int): Timestamp? = null
    override fun getAsciiStream(columnIndex: Int): java.io.InputStream? = null
    @Deprecated("") override fun getUnicodeStream(columnIndex: Int): java.io.InputStream? = null
    override fun getBinaryStream(columnIndex: Int): java.io.InputStream? = null
    override fun getByte(columnLabel: String): Byte = 0
    override fun getShort(columnLabel: String): Short = 0
    override fun getFloat(columnLabel: String): Float = 0f
    override fun getDouble(columnLabel: String): Double = 0.0
    override fun getBigDecimal(columnLabel: String, scale: Int): java.math.BigDecimal? = null
    override fun getBytes(columnLabel: String): ByteArray? = null
    override fun getTime(columnLabel: String): java.sql.Time? = null
    override fun getAsciiStream(columnLabel: String): java.io.InputStream? = null
    @Deprecated("") override fun getUnicodeStream(columnLabel: String): java.io.InputStream? = null
    override fun getBinaryStream(columnLabel: String): java.io.InputStream? = null
    override fun getWarnings(): SQLWarning? = null
    override fun clearWarnings() {}
    override fun getCursorName(): String? = null
    override fun getMetaData(): ResultSetMetaData? = null
    override fun getObject(columnIndex: Int): Any? = null
    override fun getObject(columnLabel: String): Any? = null
    override fun findColumn(columnLabel: String): Int = 0
    override fun getCharacterStream(columnIndex: Int): java.io.Reader? = null
    override fun getCharacterStream(columnLabel: String): java.io.Reader? = null
    override fun getBigDecimal(columnIndex: Int): java.math.BigDecimal? = null
    override fun isBeforeFirst(): Boolean = false
    override fun isAfterLast(): Boolean = false
    override fun isFirst(): Boolean = false
    override fun isLast(): Boolean = false
    override fun beforeFirst() {}
    override fun afterLast() {}
    override fun first(): Boolean = false
    override fun last(): Boolean = false
    override fun getRow(): Int = 0
    override fun absolute(row: Int): Boolean = false
    override fun relative(rows: Int): Boolean = false
    override fun previous(): Boolean = false
    override fun setFetchDirection(direction: Int) {}
    override fun getFetchDirection(): Int = ResultSet.FETCH_FORWARD
    override fun setFetchSize(rows: Int) {}
    override fun getFetchSize(): Int = 0
    override fun getType(): Int = ResultSet.TYPE_FORWARD_ONLY
    override fun getConcurrency(): Int = ResultSet.CONCUR_READ_ONLY
    override fun rowUpdated(): Boolean = false
    override fun rowInserted(): Boolean = false
    override fun rowDeleted(): Boolean = false
    override fun updateNull(columnIndex: Int) {}
    override fun updateBoolean(columnIndex: Int, x: Boolean) {}
    override fun updateByte(columnIndex: Int, x: Byte) {}
    override fun updateShort(columnIndex: Int, x: Short) {}
    override fun updateInt(columnIndex: Int, x: Int) {}
    override fun updateLong(columnIndex: Int, x: Long) {}
    override fun updateFloat(columnIndex: Int, x: Float) {}
    override fun updateDouble(columnIndex: Int, x: Double) {}
    override fun updateBigDecimal(columnIndex: Int, x: java.math.BigDecimal?) {}
    override fun updateString(columnIndex: Int, x: String?) {}
    override fun updateBytes(columnIndex: Int, x: ByteArray?) {}
    override fun updateDate(columnIndex: Int, x: java.sql.Date?) {}
    override fun updateTime(columnIndex: Int, x: java.sql.Time?) {}
    override fun updateTimestamp(columnIndex: Int, x: Timestamp?) {}
    override fun updateAsciiStream(columnIndex: Int, x: java.io.InputStream?, length: Int) {}
    override fun updateBinaryStream(columnIndex: Int, x: java.io.InputStream?, length: Int) {}
    override fun updateCharacterStream(columnIndex: Int, x: java.io.Reader?, length: Int) {}
    override fun updateObject(columnIndex: Int, x: Any?, scaleOrLength: Int) {}
    override fun updateObject(columnIndex: Int, x: Any?) {}
    override fun updateNull(columnLabel: String) {}
    override fun updateBoolean(columnLabel: String, x: Boolean) {}
    override fun updateByte(columnLabel: String, x: Byte) {}
    override fun updateShort(columnLabel: String, x: Short) {}
    override fun updateInt(columnLabel: String, x: Int) {}
    override fun updateLong(columnLabel: String, x: Long) {}
    override fun updateFloat(columnLabel: String, x: Float) {}
    override fun updateDouble(columnLabel: String, x: Double) {}
    override fun updateBigDecimal(columnLabel: String, x: java.math.BigDecimal?) {}
    override fun updateString(columnLabel: String, x: String?) {}
    override fun updateBytes(columnLabel: String, x: ByteArray?) {}
    override fun updateDate(columnLabel: String, x: java.sql.Date?) {}
    override fun updateTime(columnLabel: String, x: java.sql.Time?) {}
    override fun updateTimestamp(columnLabel: String, x: Timestamp?) {}
    override fun updateAsciiStream(columnLabel: String, x: java.io.InputStream?, length: Int) {}
    override fun updateBinaryStream(columnLabel: String, x: java.io.InputStream?, length: Int) {}
    override fun updateCharacterStream(columnLabel: String, x: java.io.Reader?, length: Int) {}
    override fun updateObject(columnLabel: String, x: Any?, scaleOrLength: Int) {}
    override fun updateObject(columnLabel: String, x: Any?) {}
    override fun insertRow() {}
    override fun updateRow() {}
    override fun deleteRow() {}
    override fun refreshRow() {}
    override fun cancelRowUpdates() {}
    override fun moveToInsertRow() {}
    override fun moveToCurrentRow() {}
    override fun getStatement(): Statement? = null
    override fun getObject(columnIndex: Int, map: MutableMap<String, Class<*>>?): Any? = null
    override fun getRef(columnIndex: Int): Ref? = null
    override fun getBlob(columnIndex: Int): Blob? = null
    override fun getClob(columnIndex: Int): Clob? = null
    override fun getArray(columnIndex: Int): java.sql.Array? = null
    override fun getObject(columnLabel: String, map: MutableMap<String, Class<*>>?): Any? = null
    override fun getRef(columnLabel: String): Ref? = null
    override fun getBlob(columnLabel: String): Blob? = null
    override fun getClob(columnLabel: String): Clob? = null
    override fun getArray(columnLabel: String): java.sql.Array? = null
    override fun getDate(columnIndex: Int, cal: Calendar?): java.sql.Date? = null
    override fun getDate(columnLabel: String, cal: Calendar?): java.sql.Date? = null
    override fun getTime(columnIndex: Int, cal: Calendar?): java.sql.Time? = null
    override fun getTime(columnLabel: String, cal: Calendar?): java.sql.Time? = null
    override fun getTimestamp(columnIndex: Int, cal: Calendar?): Timestamp? = null
    override fun getTimestamp(columnLabel: String, cal: Calendar?): Timestamp? = null
    override fun getURL(columnIndex: Int): URL? = null
    override fun getURL(columnLabel: String): URL? = null
    override fun updateRef(columnIndex: Int, x: Ref?) {}
    override fun updateRef(columnLabel: String, x: Ref?) {}
    override fun updateBlob(columnIndex: Int, x: Blob?) {}
    override fun updateBlob(columnLabel: String, x: Blob?) {}
    override fun updateClob(columnIndex: Int, x: Clob?) {}
    override fun updateClob(columnLabel: String, x: Clob?) {}
    override fun updateArray(columnIndex: Int, x: java.sql.Array?) {}
    override fun updateArray(columnLabel: String, x: java.sql.Array?) {}
    override fun getRowId(columnIndex: Int): RowId? = null
    override fun getRowId(columnLabel: String): RowId? = null
    override fun updateRowId(columnIndex: Int, x: RowId?) {}
    override fun updateRowId(columnLabel: String, x: RowId?) {}
    override fun getHoldability(): Int = ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun isClosed(): Boolean = false
    override fun updateNString(columnIndex: Int, nString: String?) {}
    override fun updateNString(columnLabel: String, nString: String?) {}
    override fun updateNClob(columnIndex: Int, nClob: NClob?) {}
    override fun updateNClob(columnLabel: String, nClob: NClob?) {}
    override fun getNClob(columnIndex: Int): NClob? = null
    override fun getNClob(columnLabel: String): NClob? = null
    override fun getSQLXML(columnIndex: Int): SQLXML? = null
    override fun getSQLXML(columnLabel: String): SQLXML? = null
    override fun updateSQLXML(columnIndex: Int, xmlObject: SQLXML?) {}
    override fun updateSQLXML(columnLabel: String, xmlObject: SQLXML?) {}
    override fun getNString(columnIndex: Int): String? = null
    override fun getNString(columnLabel: String): String? = null
    override fun getNCharacterStream(columnIndex: Int): java.io.Reader? = null
    override fun getNCharacterStream(columnLabel: String): java.io.Reader? = null
    override fun updateNCharacterStream(columnIndex: Int, x: java.io.Reader?, length: Long) {}
    override fun updateNCharacterStream(columnLabel: String, x: java.io.Reader?, length: Long) {}
    override fun updateAsciiStream(columnIndex: Int, x: java.io.InputStream?, length: Long) {}
    override fun updateBinaryStream(columnIndex: Int, x: java.io.InputStream?, length: Long) {}
    override fun updateCharacterStream(columnIndex: Int, x: java.io.Reader?, length: Long) {}
    override fun updateAsciiStream(columnLabel: String, x: java.io.InputStream?, length: Long) {}
    override fun updateBinaryStream(columnLabel: String, x: java.io.InputStream?, length: Long) {}
    override fun updateCharacterStream(columnLabel: String, x: java.io.Reader?, length: Long) {}
    override fun updateBlob(columnIndex: Int, inputStream: java.io.InputStream?, length: Long) {}
    override fun updateBlob(columnLabel: String, inputStream: java.io.InputStream?, length: Long) {}
    override fun updateClob(columnIndex: Int, reader: java.io.Reader?, length: Long) {}
    override fun updateClob(columnLabel: String, reader: java.io.Reader?, length: Long) {}
    override fun updateNClob(columnIndex: Int, reader: java.io.Reader?, length: Long) {}
    override fun updateNClob(columnLabel: String, reader: java.io.Reader?, length: Long) {}
    override fun updateNCharacterStream(columnIndex: Int, x: java.io.Reader?) {}
    override fun updateNCharacterStream(columnLabel: String, x: java.io.Reader?) {}
    override fun updateAsciiStream(columnIndex: Int, x: java.io.InputStream?) {}
    override fun updateBinaryStream(columnIndex: Int, x: java.io.InputStream?) {}
    override fun updateCharacterStream(columnIndex: Int, x: java.io.Reader?) {}
    override fun updateAsciiStream(columnLabel: String, x: java.io.InputStream?) {}
    override fun updateBinaryStream(columnLabel: String, x: java.io.InputStream?) {}
    override fun updateCharacterStream(columnLabel: String, x: java.io.Reader?) {}
    override fun updateBlob(columnIndex: Int, inputStream: java.io.InputStream?) {}
    override fun updateBlob(columnLabel: String, inputStream: java.io.InputStream?) {}
    override fun updateClob(columnIndex: Int, reader: java.io.Reader?) {}
    override fun updateClob(columnLabel: String, reader: java.io.Reader?) {}
    override fun updateNClob(columnIndex: Int, reader: java.io.Reader?) {}
    override fun updateNClob(columnLabel: String, reader: java.io.Reader?) {}
    override fun <T : Any?> getObject(columnIndex: Int, type: Class<T>?): T? = null
    override fun <T : Any?> getObject(columnLabel: String, type: Class<T>?): T? = null
    override fun <T : Any?> unwrap(iface: Class<T>?): T? = null
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
