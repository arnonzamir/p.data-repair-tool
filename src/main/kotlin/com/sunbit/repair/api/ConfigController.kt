package com.sunbit.repair.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/config")
@CrossOrigin(origins = ["*"])
class ConfigController(
    @Value("\${call-center.prod}") private val callCenterProd: String,
    @Value("\${call-center.staging}") private val callCenterStaging: String,
    @Value("\${call-center.local}") private val callCenterLocal: String,
) {
    @GetMapping
    fun getConfig(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "callCenter" to mapOf(
                "PROD" to callCenterProd,
                "STAGING" to callCenterStaging,
                "LOCAL" to callCenterLocal,
            ),
        ))
    }
}
