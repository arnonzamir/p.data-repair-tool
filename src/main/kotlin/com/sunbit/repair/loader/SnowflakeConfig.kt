package com.sunbit.repair.loader

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides the SnowflakeProperties bean (account, user, warehouse, role, etc.)
 * The actual DataSource and JdbcTemplate are created by SnowflakeProxyConfig,
 * which decides whether to use the HTTP proxy or direct JDBC based on
 * the snowflake.proxy-url property.
 */
@Configuration
class SnowflakeConfig {

    @Bean
    @ConfigurationProperties(prefix = "snowflake")
    fun snowflakeProperties(): SnowflakeProperties = SnowflakeProperties()
}

class SnowflakeProperties {
    var account: String = ""
    var user: String = ""
    var password: String = ""
    var warehouse: String = ""
    var role: String = ""
    var authenticator: String = "externalbrowser"
}
