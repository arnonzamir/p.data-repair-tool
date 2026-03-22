package com.sunbit.repair.loader

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URI
import java.sql.*
import java.util.*
import java.util.logging.Logger as JULLogger
import javax.sql.DataSource

/**
 * Decides how to connect to Snowflake:
 * - If SNOWFLAKE_PROXY_URL is set: routes SQL through the HTTP proxy
 * - Otherwise: lets SnowflakeConfig create the normal JDBC DataSource
 *
 * This bean is @Primary so it wins over SnowflakeConfig's snowflakeDataSource.
 * When proxy is not configured, it delegates to the real JDBC DataSource.
 */
@Configuration
class SnowflakeProxyConfig {

    private val log = LoggerFactory.getLogger(SnowflakeProxyConfig::class.java)

    @Bean("snowflakeProxyDataSource")
    fun proxyDataSource(
        @Value("\${snowflake.proxy-url:}") proxyUrl: String,
    ): DataSource? {
        if (proxyUrl.isBlank()) return null
        log.info("[SnowflakeProxyConfig] Using Snowflake HTTP proxy at {}", proxyUrl)
        return ProxyDataSource(proxyUrl)
    }

    @Bean("snowflakeJdbcTemplate")
    @Primary
    fun proxyOrDirectJdbcTemplate(
        @Value("\${snowflake.proxy-url:}") proxyUrl: String,
        snowflakeProperties: SnowflakeProperties,
    ): JdbcTemplate {
        val dataSource: DataSource = if (proxyUrl.isNotBlank()) {
            log.info("[SnowflakeProxyConfig] JdbcTemplate using HTTP proxy at {}", proxyUrl)
            ProxyDataSource(proxyUrl)
        } else {
            log.info("[SnowflakeProxyConfig] JdbcTemplate using direct JDBC (no proxy)")
            // Create the real Snowflake DataSource inline
            createDirectDataSource(snowflakeProperties)
        }
        val template = JdbcTemplate(dataSource)
        template.queryTimeout = 120
        return template
    }

    private fun createDirectDataSource(props: SnowflakeProperties): DataSource {
        val host = "${props.account}.snowflakecomputing.com"
        val jdbcUrl = "jdbc:snowflake://$host/"

        val hikariConfig = com.zaxxer.hikari.HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "net.snowflake.client.jdbc.SnowflakeDriver"
            username = props.user
            addDataSourceProperty("user", props.user)
            addDataSourceProperty("warehouse", props.warehouse)
            addDataSourceProperty("role", props.role)
            addDataSourceProperty("authenticator", props.authenticator)
            if (props.password.isNotBlank()) {
                password = props.password
                addDataSourceProperty("password", props.password)
            }
            addDataSourceProperty("db", "BRONZE")
            addDataSourceProperty("JDBC_QUERY_RESULT_FORMAT", "JSON")
            addDataSourceProperty("CLIENT_SESSION_KEEP_ALIVE", "true")
            addDataSourceProperty("CLIENT_STORE_TEMPORARY_CREDENTIAL", "true")
            minimumIdle = 0
            maximumPoolSize = 3
            initializationFailTimeout = -1
            connectionTimeout = 120_000
            idleTimeout = 600_000
            maxLifetime = 3_600_000
            poolName = "snowflake-pool"
        }
        return com.zaxxer.hikari.HikariDataSource(hikariConfig)
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
    val url = URI(proxyUrl + "/query").toURL()
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

class ProxyResultSet(
    private val columns: List<String>,
    private val rows: List<Map<String, Any?>>,
) : ResultSetStub() {

    private var cursor = -1
    private var wasNullFlag = false

    override fun next(): Boolean { cursor++; return cursor < rows.size }
    private fun currentRow(): Map<String, Any?> = rows[cursor]

    override fun getString(columnLabel: String): String? {
        val v = currentRow()[columnLabel.uppercase()]; wasNullFlag = v == null; return v?.toString()
    }
    override fun getString(columnIndex: Int): String? {
        if (columnIndex < 1 || columnIndex > columns.size) return null
        return getString(columns[columnIndex - 1])
    }
    override fun getObject(columnIndex: Int): Any? {
        if (columnIndex < 1 || columnIndex > columns.size) return null
        val col = columns[columnIndex - 1]
        val v = currentRow()[col]; wasNullFlag = v == null; return v
    }
    override fun getObject(columnLabel: String): Any? {
        val v = currentRow()[columnLabel.uppercase()]; wasNullFlag = v == null; return v
    }
    override fun getLong(columnLabel: String): Long {
        val v = currentRow()[columnLabel.uppercase()]; wasNullFlag = v == null
        return when (v) { is Number -> v.toLong(); is String -> v.toLongOrNull() ?: 0L; else -> 0L }
    }
    override fun getInt(columnLabel: String): Int {
        val v = currentRow()[columnLabel.uppercase()]; wasNullFlag = v == null
        return when (v) { is Number -> v.toInt(); is String -> v.toIntOrNull() ?: 0; else -> 0 }
    }
    override fun getBoolean(columnLabel: String): Boolean {
        val v = currentRow()[columnLabel.uppercase()]; wasNullFlag = v == null
        return when (v) { is Boolean -> v; is Number -> v.toInt() != 0; is String -> v.equals("true", true) || v == "1"; else -> false }
    }
    override fun getBigDecimal(columnLabel: String): java.math.BigDecimal? {
        val v = currentRow()[columnLabel.uppercase()]; wasNullFlag = v == null
        return when (v) { is Number -> java.math.BigDecimal(v.toString()); is String -> v.toBigDecimalOrNull(); else -> null }
    }
    override fun getTimestamp(columnLabel: String): Timestamp? {
        val v = currentRow()[columnLabel.uppercase()]?.toString(); wasNullFlag = v == null; if (v == null) return null
        return try { Timestamp.valueOf(v.replace("T", " ").take(19)) } catch (_: Exception) { null }
    }
    override fun getDate(columnLabel: String): java.sql.Date? {
        val v = currentRow()[columnLabel.uppercase()]?.toString(); wasNullFlag = v == null; if (v == null) return null
        return try { java.sql.Date.valueOf(v.take(10)) } catch (_: Exception) { null }
    }
    override fun wasNull(): Boolean = wasNullFlag
    override fun close() {}
    override fun isClosed(): Boolean = false
    override fun getMetaData(): ResultSetMetaData = ProxyResultSetMetaData(columns)
}

class ProxyResultSetMetaData(private val columns: List<String>) : ResultSetMetaData {
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column - 1]
    override fun getColumnLabel(column: Int): String = columns[column - 1]
    override fun getColumnTypeName(column: Int): String = "VARCHAR"
    override fun getColumnType(column: Int): Int = java.sql.Types.VARCHAR
    override fun isAutoIncrement(column: Int) = false
    override fun isCaseSensitive(column: Int) = false
    override fun isSearchable(column: Int) = true
    override fun isCurrency(column: Int) = false
    override fun isNullable(column: Int) = ResultSetMetaData.columnNullable
    override fun isSigned(column: Int) = false
    override fun getColumnDisplaySize(column: Int) = 255
    override fun getSchemaName(column: Int) = ""
    override fun getTableName(column: Int) = ""
    override fun getCatalogName(column: Int) = ""
    override fun getPrecision(column: Int) = 0
    override fun getScale(column: Int) = 0
    override fun getColumnClassName(column: Int) = "java.lang.String"
    override fun isReadOnly(column: Int) = true
    override fun isWritable(column: Int) = false
    override fun isDefinitelyWritable(column: Int) = false
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
    override fun isWrapperFor(iface: Class<*>?) = false
}

// === Stub classes ===

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
    override fun createStatement(a: Int, b: Int) = createStatement()
    override fun prepareStatement(sql: String, a: Int, b: Int) = prepareStatement(sql)
    override fun prepareCall(sql: String, a: Int, b: Int): CallableStatement = throw UnsupportedOperationException()
    override fun getTypeMap(): MutableMap<String, Class<*>>? = null
    override fun setTypeMap(map: MutableMap<String, Class<*>>?) {}
    override fun setHoldability(h: Int) {}
    override fun getHoldability() = ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun setSavepoint(): Savepoint = throw UnsupportedOperationException()
    override fun setSavepoint(n: String): Savepoint = throw UnsupportedOperationException()
    override fun rollback(s: Savepoint) {}
    override fun releaseSavepoint(s: Savepoint) {}
    override fun createStatement(a: Int, b: Int, c: Int) = createStatement()
    override fun prepareStatement(sql: String, a: Int, b: Int, c: Int) = prepareStatement(sql)
    override fun prepareCall(sql: String, a: Int, b: Int, c: Int): CallableStatement = throw UnsupportedOperationException()
    override fun prepareStatement(sql: String, a: Int) = prepareStatement(sql)
    override fun prepareStatement(sql: String, a: IntArray) = prepareStatement(sql)
    override fun prepareStatement(sql: String, a: Array<out String>) = prepareStatement(sql)
    override fun createClob(): Clob = throw UnsupportedOperationException()
    override fun createBlob(): Blob = throw UnsupportedOperationException()
    override fun createNClob(): NClob = throw UnsupportedOperationException()
    override fun createSQLXML(): SQLXML = throw UnsupportedOperationException()
    override fun isValid(t: Int) = true
    override fun setClientInfo(n: String?, v: String?) {}
    override fun setClientInfo(p: Properties?) {}
    override fun getClientInfo(n: String?): String? = null
    override fun getClientInfo() = Properties()
    override fun createArrayOf(t: String?, e: Array<out Any>?): java.sql.Array = throw UnsupportedOperationException()
    override fun createStruct(t: String?, a: Array<out Any>?): Struct = throw UnsupportedOperationException()
    override fun setSchema(s: String?) {}
    override fun getSchema(): String? = null
    override fun abort(e: java.util.concurrent.Executor?) {}
    override fun setNetworkTimeout(e: java.util.concurrent.Executor?, m: Int) {}
    override fun getNetworkTimeout() = 0
    override fun <T : Any?> unwrap(i: Class<T>?): T = throw UnsupportedOperationException()
    override fun isWrapperFor(i: Class<*>?) = false
}

abstract class StatementStub : Statement {
    override fun executeQuery(sql: String): ResultSet = throw UnsupportedOperationException()
    override fun executeUpdate(sql: String) = 0
    override fun close() {}
    override fun getMaxFieldSize() = 0; override fun setMaxFieldSize(m: Int) {}
    override fun getMaxRows() = 0; override fun setMaxRows(m: Int) {}
    override fun setEscapeProcessing(e: Boolean) {}
    override fun getQueryTimeout() = 0; override fun setQueryTimeout(s: Int) {}
    override fun cancel() {}
    override fun getWarnings(): SQLWarning? = null; override fun clearWarnings() {}
    override fun setCursorName(n: String?) {}
    override fun execute(sql: String) = false
    override fun getResultSet(): ResultSet? = null; override fun getUpdateCount() = -1
    override fun getMoreResults() = false
    override fun setFetchDirection(d: Int) {}; override fun getFetchDirection() = ResultSet.FETCH_FORWARD
    override fun setFetchSize(r: Int) {}; override fun getFetchSize() = 0
    override fun getResultSetConcurrency() = ResultSet.CONCUR_READ_ONLY
    override fun getResultSetType() = ResultSet.TYPE_FORWARD_ONLY
    override fun addBatch(sql: String?) {}; override fun clearBatch() {}; override fun executeBatch() = intArrayOf()
    override fun getConnection(): Connection? = null
    override fun getMoreResults(c: Int) = false; override fun getGeneratedKeys(): ResultSet? = null
    override fun executeUpdate(sql: String, a: Int) = 0
    override fun executeUpdate(sql: String, a: IntArray?) = 0
    override fun executeUpdate(sql: String, a: Array<out String>?) = 0
    override fun execute(sql: String, a: Int) = false
    override fun execute(sql: String, a: IntArray?) = false
    override fun execute(sql: String, a: Array<out String>?) = false
    override fun getResultSetHoldability() = ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun isClosed() = false; override fun setPoolable(p: Boolean) {}; override fun isPoolable() = false
    override fun closeOnCompletion() {}; override fun isCloseOnCompletion() = false
    override fun <T : Any?> unwrap(i: Class<T>?): T = throw UnsupportedOperationException()
    override fun isWrapperFor(i: Class<*>?) = false
}

abstract class PreparedStatementStub : StatementStub(), PreparedStatement {
    override fun executeQuery(): ResultSet = throw UnsupportedOperationException()
    override fun executeUpdate() = 0
    override fun setNull(i: Int, t: Int) {}; override fun setBoolean(i: Int, x: Boolean) {}
    override fun setByte(i: Int, x: Byte) {}; override fun setShort(i: Int, x: Short) {}
    override fun setInt(i: Int, x: Int) {}; override fun setLong(i: Int, x: Long) {}
    override fun setFloat(i: Int, x: Float) {}; override fun setDouble(i: Int, x: Double) {}
    override fun setBigDecimal(i: Int, x: java.math.BigDecimal?) {}
    override fun setString(i: Int, x: String?) {}; override fun setBytes(i: Int, x: ByteArray?) {}
    override fun setDate(i: Int, x: java.sql.Date?) {}; override fun setTime(i: Int, x: java.sql.Time?) {}
    override fun setTimestamp(i: Int, x: Timestamp?) {}
    override fun setAsciiStream(i: Int, x: java.io.InputStream?, l: Int) {}
    @Deprecated("") override fun setUnicodeStream(i: Int, x: java.io.InputStream?, l: Int) {}
    override fun setBinaryStream(i: Int, x: java.io.InputStream?, l: Int) {}
    override fun clearParameters() {}
    override fun setObject(i: Int, x: Any?, t: Int) {}; override fun setObject(i: Int, x: Any?) {}
    override fun execute() = false; override fun addBatch() {}
    override fun setCharacterStream(i: Int, r: java.io.Reader?, l: Int) {}
    override fun setRef(i: Int, x: Ref?) {}; override fun setBlob(i: Int, x: Blob?) {}
    override fun setClob(i: Int, x: Clob?) {}; override fun setArray(i: Int, x: java.sql.Array?) {}
    override fun getMetaData(): ResultSetMetaData? = null
    override fun setDate(i: Int, x: java.sql.Date?, c: Calendar?) {}
    override fun setTime(i: Int, x: java.sql.Time?, c: Calendar?) {}
    override fun setTimestamp(i: Int, x: Timestamp?, c: Calendar?) {}
    override fun setNull(i: Int, t: Int, n: String?) {}; override fun setURL(i: Int, x: java.net.URL?) {}
    override fun getParameterMetaData(): ParameterMetaData? = null
    override fun setRowId(i: Int, x: RowId?) {}; override fun setNString(i: Int, v: String?) {}
    override fun setNCharacterStream(i: Int, v: java.io.Reader?, l: Long) {}
    override fun setNClob(i: Int, v: NClob?) {}; override fun setClob(i: Int, r: java.io.Reader?, l: Long) {}
    override fun setBlob(i: Int, s: java.io.InputStream?, l: Long) {}
    override fun setNClob(i: Int, r: java.io.Reader?, l: Long) {}
    override fun setSQLXML(i: Int, x: SQLXML?) {}
    override fun setObject(i: Int, x: Any?, t: Int, s: Int) {}
    override fun setAsciiStream(i: Int, x: java.io.InputStream?, l: Long) {}
    override fun setBinaryStream(i: Int, x: java.io.InputStream?, l: Long) {}
    override fun setCharacterStream(i: Int, r: java.io.Reader?, l: Long) {}
    override fun setAsciiStream(i: Int, x: java.io.InputStream?) {}
    override fun setBinaryStream(i: Int, x: java.io.InputStream?) {}
    override fun setCharacterStream(i: Int, r: java.io.Reader?) {}
    override fun setNCharacterStream(i: Int, v: java.io.Reader?) {}
    override fun setClob(i: Int, r: java.io.Reader?) {}; override fun setBlob(i: Int, s: java.io.InputStream?) {}
    override fun setNClob(i: Int, r: java.io.Reader?) {}
}

abstract class ResultSetStub : ResultSet {
    override fun next() = false; override fun close() {}; override fun wasNull() = false
    override fun getString(c: String): String? = null; override fun getString(c: Int): String? = null
    override fun getBoolean(c: String) = false; override fun getBoolean(c: Int) = false
    override fun getByte(c: String): Byte = 0; override fun getByte(c: Int): Byte = 0
    override fun getShort(c: String): Short = 0; override fun getShort(c: Int): Short = 0
    override fun getInt(c: String) = 0; override fun getInt(c: Int) = 0
    override fun getLong(c: String) = 0L; override fun getLong(c: Int) = 0L
    override fun getFloat(c: String) = 0f; override fun getFloat(c: Int) = 0f
    override fun getDouble(c: String) = 0.0; override fun getDouble(c: Int) = 0.0
    override fun getBigDecimal(c: String, s: Int): java.math.BigDecimal? = null; override fun getBigDecimal(c: Int, s: Int): java.math.BigDecimal? = null
    override fun getBigDecimal(c: String): java.math.BigDecimal? = null; override fun getBigDecimal(c: Int): java.math.BigDecimal? = null
    override fun getBytes(c: String): ByteArray? = null; override fun getBytes(c: Int): ByteArray? = null
    override fun getDate(c: String): java.sql.Date? = null; override fun getDate(c: Int): java.sql.Date? = null
    override fun getTime(c: String): java.sql.Time? = null; override fun getTime(c: Int): java.sql.Time? = null
    override fun getTimestamp(c: String): Timestamp? = null; override fun getTimestamp(c: Int): Timestamp? = null
    override fun getAsciiStream(c: String): java.io.InputStream? = null; override fun getAsciiStream(c: Int): java.io.InputStream? = null
    @Deprecated("") override fun getUnicodeStream(c: String): java.io.InputStream? = null; @Deprecated("") override fun getUnicodeStream(c: Int): java.io.InputStream? = null
    override fun getBinaryStream(c: String): java.io.InputStream? = null; override fun getBinaryStream(c: Int): java.io.InputStream? = null
    override fun getWarnings(): SQLWarning? = null; override fun clearWarnings() {}
    override fun getCursorName(): String? = null; override fun getMetaData(): ResultSetMetaData? = null
    override fun getObject(c: Int): Any? = null; override fun getObject(c: String): Any? = null
    override fun findColumn(c: String) = 0
    override fun getCharacterStream(c: Int): java.io.Reader? = null; override fun getCharacterStream(c: String): java.io.Reader? = null
    override fun isBeforeFirst() = false; override fun isAfterLast() = false; override fun isFirst() = false; override fun isLast() = false
    override fun beforeFirst() {}; override fun afterLast() {}; override fun first() = false; override fun last() = false
    override fun getRow() = 0; override fun absolute(r: Int) = false; override fun relative(r: Int) = false; override fun previous() = false
    override fun setFetchDirection(d: Int) {}; override fun getFetchDirection() = ResultSet.FETCH_FORWARD
    override fun setFetchSize(r: Int) {}; override fun getFetchSize() = 0
    override fun getType() = ResultSet.TYPE_FORWARD_ONLY; override fun getConcurrency() = ResultSet.CONCUR_READ_ONLY
    override fun rowUpdated() = false; override fun rowInserted() = false; override fun rowDeleted() = false
    override fun updateNull(c: Int) {}; override fun updateBoolean(c: Int, x: Boolean) {}
    override fun updateByte(c: Int, x: Byte) {}; override fun updateShort(c: Int, x: Short) {}
    override fun updateInt(c: Int, x: Int) {}; override fun updateLong(c: Int, x: Long) {}
    override fun updateFloat(c: Int, x: Float) {}; override fun updateDouble(c: Int, x: Double) {}
    override fun updateBigDecimal(c: Int, x: java.math.BigDecimal?) {}; override fun updateString(c: Int, x: String?) {}
    override fun updateBytes(c: Int, x: ByteArray?) {}; override fun updateDate(c: Int, x: java.sql.Date?) {}
    override fun updateTime(c: Int, x: java.sql.Time?) {}; override fun updateTimestamp(c: Int, x: Timestamp?) {}
    override fun updateAsciiStream(c: Int, x: java.io.InputStream?, l: Int) {}; override fun updateBinaryStream(c: Int, x: java.io.InputStream?, l: Int) {}
    override fun updateCharacterStream(c: Int, x: java.io.Reader?, l: Int) {}
    override fun updateObject(c: Int, x: Any?, s: Int) {}; override fun updateObject(c: Int, x: Any?) {}
    override fun updateNull(c: String) {}; override fun updateBoolean(c: String, x: Boolean) {}
    override fun updateByte(c: String, x: Byte) {}; override fun updateShort(c: String, x: Short) {}
    override fun updateInt(c: String, x: Int) {}; override fun updateLong(c: String, x: Long) {}
    override fun updateFloat(c: String, x: Float) {}; override fun updateDouble(c: String, x: Double) {}
    override fun updateBigDecimal(c: String, x: java.math.BigDecimal?) {}; override fun updateString(c: String, x: String?) {}
    override fun updateBytes(c: String, x: ByteArray?) {}; override fun updateDate(c: String, x: java.sql.Date?) {}
    override fun updateTime(c: String, x: java.sql.Time?) {}; override fun updateTimestamp(c: String, x: Timestamp?) {}
    override fun updateAsciiStream(c: String, x: java.io.InputStream?, l: Int) {}; override fun updateBinaryStream(c: String, x: java.io.InputStream?, l: Int) {}
    override fun updateCharacterStream(c: String, x: java.io.Reader?, l: Int) {}
    override fun updateObject(c: String, x: Any?, s: Int) {}; override fun updateObject(c: String, x: Any?) {}
    override fun insertRow() {}; override fun updateRow() {}; override fun deleteRow() {}
    override fun refreshRow() {}; override fun cancelRowUpdates() {}
    override fun moveToInsertRow() {}; override fun moveToCurrentRow() {}
    override fun getStatement(): Statement? = null
    override fun getObject(c: Int, m: MutableMap<String, Class<*>>?): Any? = null; override fun getObject(c: String, m: MutableMap<String, Class<*>>?): Any? = null
    override fun getRef(c: Int): Ref? = null; override fun getRef(c: String): Ref? = null
    override fun getBlob(c: Int): Blob? = null; override fun getBlob(c: String): Blob? = null
    override fun getClob(c: Int): Clob? = null; override fun getClob(c: String): Clob? = null
    override fun getArray(c: Int): java.sql.Array? = null; override fun getArray(c: String): java.sql.Array? = null
    override fun getDate(c: Int, cal: Calendar?): java.sql.Date? = null; override fun getDate(c: String, cal: Calendar?): java.sql.Date? = null
    override fun getTime(c: Int, cal: Calendar?): java.sql.Time? = null; override fun getTime(c: String, cal: Calendar?): java.sql.Time? = null
    override fun getTimestamp(c: Int, cal: Calendar?): Timestamp? = null; override fun getTimestamp(c: String, cal: Calendar?): Timestamp? = null
    override fun getURL(c: Int): java.net.URL? = null; override fun getURL(c: String): java.net.URL? = null
    override fun updateRef(c: Int, x: Ref?) {}; override fun updateRef(c: String, x: Ref?) {}
    override fun updateBlob(c: Int, x: Blob?) {}; override fun updateBlob(c: String, x: Blob?) {}
    override fun updateClob(c: Int, x: Clob?) {}; override fun updateClob(c: String, x: Clob?) {}
    override fun updateArray(c: Int, x: java.sql.Array?) {}; override fun updateArray(c: String, x: java.sql.Array?) {}
    override fun getRowId(c: Int): RowId? = null; override fun getRowId(c: String): RowId? = null
    override fun updateRowId(c: Int, x: RowId?) {}; override fun updateRowId(c: String, x: RowId?) {}
    override fun getHoldability() = ResultSet.HOLD_CURSORS_OVER_COMMIT; override fun isClosed() = false
    override fun updateNString(c: Int, x: String?) {}; override fun updateNString(c: String, x: String?) {}
    override fun updateNClob(c: Int, x: NClob?) {}; override fun updateNClob(c: String, x: NClob?) {}
    override fun getNClob(c: Int): NClob? = null; override fun getNClob(c: String): NClob? = null
    override fun getSQLXML(c: Int): SQLXML? = null; override fun getSQLXML(c: String): SQLXML? = null
    override fun updateSQLXML(c: Int, x: SQLXML?) {}; override fun updateSQLXML(c: String, x: SQLXML?) {}
    override fun getNString(c: Int): String? = null; override fun getNString(c: String): String? = null
    override fun getNCharacterStream(c: Int): java.io.Reader? = null; override fun getNCharacterStream(c: String): java.io.Reader? = null
    override fun updateNCharacterStream(c: Int, x: java.io.Reader?, l: Long) {}; override fun updateNCharacterStream(c: String, x: java.io.Reader?, l: Long) {}
    override fun updateAsciiStream(c: Int, x: java.io.InputStream?, l: Long) {}; override fun updateBinaryStream(c: Int, x: java.io.InputStream?, l: Long) {}
    override fun updateCharacterStream(c: Int, x: java.io.Reader?, l: Long) {}
    override fun updateAsciiStream(c: String, x: java.io.InputStream?, l: Long) {}; override fun updateBinaryStream(c: String, x: java.io.InputStream?, l: Long) {}
    override fun updateCharacterStream(c: String, x: java.io.Reader?, l: Long) {}
    override fun updateBlob(c: Int, x: java.io.InputStream?, l: Long) {}; override fun updateBlob(c: String, x: java.io.InputStream?, l: Long) {}
    override fun updateClob(c: Int, x: java.io.Reader?, l: Long) {}; override fun updateClob(c: String, x: java.io.Reader?, l: Long) {}
    override fun updateNClob(c: Int, x: java.io.Reader?, l: Long) {}; override fun updateNClob(c: String, x: java.io.Reader?, l: Long) {}
    override fun updateNCharacterStream(c: Int, x: java.io.Reader?) {}; override fun updateNCharacterStream(c: String, x: java.io.Reader?) {}
    override fun updateAsciiStream(c: Int, x: java.io.InputStream?) {}; override fun updateBinaryStream(c: Int, x: java.io.InputStream?) {}
    override fun updateCharacterStream(c: Int, x: java.io.Reader?) {}
    override fun updateAsciiStream(c: String, x: java.io.InputStream?) {}; override fun updateBinaryStream(c: String, x: java.io.InputStream?) {}
    override fun updateCharacterStream(c: String, x: java.io.Reader?) {}
    override fun updateBlob(c: Int, x: java.io.InputStream?) {}; override fun updateBlob(c: String, x: java.io.InputStream?) {}
    override fun updateClob(c: Int, x: java.io.Reader?) {}; override fun updateClob(c: String, x: java.io.Reader?) {}
    override fun updateNClob(c: Int, x: java.io.Reader?) {}; override fun updateNClob(c: String, x: java.io.Reader?) {}
    override fun <T : Any?> getObject(c: Int, t: Class<T>?): T? = null; override fun <T : Any?> getObject(c: String, t: Class<T>?): T? = null
    override fun <T : Any?> unwrap(i: Class<T>?): T? = null; override fun isWrapperFor(i: Class<*>?) = false
}
