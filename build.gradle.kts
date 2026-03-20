plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.sunbit"
description = "Purchase Repair Tool"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Snowflake JDBC
    implementation("net.snowflake:snowflake-jdbc:3.16.1")

    // JNA -- required by Snowflake driver to cache SSO tokens in the OS keychain,
    // so the browser login only happens once instead of per-connection.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // HikariCP connection pool (Spring Boot includes it, but we need it for the
    // Snowflake DataSource which is separate from the auto-configured one)
    implementation("com.zaxxer:HikariCP:5.1.0")

    // MySQL
    implementation("com.mysql:mysql-connector-j:8.3.0")

    // SQLite for local file-based cache
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    // HTTP client for Admin API
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Coroutines for parallel rule execution
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.8.1")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // Required by Snowflake JDBC Arrow result format on Java 17+.
    // We default to JSON format (see SnowflakeConfig) but keep this as a safety net.
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
