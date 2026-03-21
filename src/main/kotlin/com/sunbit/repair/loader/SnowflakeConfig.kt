package com.sunbit.repair.loader

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Properties
import javax.sql.DataSource

@Configuration
class SnowflakeConfig {

    private val log = LoggerFactory.getLogger(SnowflakeConfig::class.java)

    @Bean
    @ConfigurationProperties(prefix = "snowflake")
    fun snowflakeProperties(): SnowflakeProperties = SnowflakeProperties()

    @Bean("snowflakeDataSource")
    fun snowflakeDataSource(props: SnowflakeProperties): DataSource {
        if (props.user.isBlank()) {
            log.warn("[SnowflakeConfig][snowflakeDataSource] SNOWFLAKE_USER not set. " +
                "Snowflake queries will fail until credentials are provided.")
        }

        val host = "${props.account}.snowflakecomputing.com"
        val jdbcUrl = "jdbc:snowflake://$host/"

        log.info("[SnowflakeConfig][snowflakeDataSource] Configuring Snowflake: account={} user={} warehouse={} role={} authenticator={}",
            props.account, props.user, props.warehouse, props.role, props.authenticator)

        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "net.snowflake.client.jdbc.SnowflakeDriver"
            username = props.user

            // Snowflake connection properties
            addDataSourceProperty("user", props.user)
            addDataSourceProperty("warehouse", props.warehouse)
            addDataSourceProperty("role", props.role)
            addDataSourceProperty("authenticator", props.authenticator)
            if (props.password.isNotBlank()) {
                password = props.password
                addDataSourceProperty("password", props.password)
            }
            addDataSourceProperty("db", "BRONZE")
            // Use JSON result format to avoid Arrow --add-opens requirement on Java 17+
            addDataSourceProperty("JDBC_QUERY_RESULT_FORMAT", "JSON")
            // Cache the SSO token so the browser only opens once
            addDataSourceProperty("CLIENT_SESSION_KEEP_ALIVE", "true")
            addDataSourceProperty("CLIENT_STORE_TEMPORARY_CREDENTIAL", "true")

            // Pool settings: keep one connection alive, reuse it for all queries.
            // This means the Okta browser login happens exactly once on first query.
            minimumIdle = 0
            maximumPoolSize = 3
            initializationFailTimeout = -1  // Don't connect at startup (lazy, first query triggers SSO)
            connectionTimeout = 120_000  // 2 min -- SSO login takes time
            idleTimeout = 600_000        // 10 min
            maxLifetime = 3_600_000      // 1 hour
            poolName = "snowflake-pool"
        }

        return HikariDataSource(hikariConfig)
    }

    @Bean("snowflakeJdbcTemplate")
    fun snowflakeJdbcTemplate(
        @Qualifier("snowflakeDataSource") dataSource: DataSource
    ): JdbcTemplate {
        val template = JdbcTemplate(dataSource)
        template.queryTimeout = 120
        return template
    }
}

class SnowflakeProperties {
    var account: String = ""
    var user: String = ""
    var password: String = ""
    var warehouse: String = ""
    var role: String = ""
    var authenticator: String = "externalbrowser"
}
