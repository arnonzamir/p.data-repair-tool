package com.sunbit.repair

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude = [DataSourceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableScheduling
class PurchaseRepairToolApplication

fun main(args: Array<String>) {
    runApplication<PurchaseRepairToolApplication>(*args)
}
