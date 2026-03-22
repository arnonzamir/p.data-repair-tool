package com.sunbit.repair.api

import com.sunbit.repair.loader.AdminApiClient
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/remediation")
@CrossOrigin(origins = ["*"])
class RemediationController(
    private val adminApiClient: AdminApiClient,
) {
    private val log = LoggerFactory.getLogger(RemediationController::class.java)

    @PostMapping("/snapshot")
    fun snapshot(
        @RequestBody body: Map<String, Any>,
        @RequestHeader("X-Target", defaultValue = "LOCAL") target: String,
    ): Map<String, Any> {
        val purchaseId = (body["purchaseId"] as Number).toLong()
        log.info("[RemediationController][snapshot] purchase={} target={}", purchaseId, target)
        return adminApiClient.remediationSnapshot(purchaseId, target)
    }

    @PostMapping("/simulate")
    fun simulate(
        @RequestBody body: Map<String, Any>,
        @RequestHeader("X-Target", defaultValue = "LOCAL") target: String,
    ): Map<String, Any> {
        val purchaseId = (body["purchaseId"] as Number).toLong()
        @Suppress("UNCHECKED_CAST")
        val operations = body["operations"] as List<Map<String, Any>>
        log.info("[RemediationController][simulate] purchase={} ops={} target={}", purchaseId, operations.size, target)
        return adminApiClient.remediationSimulate(purchaseId, operations, target)
    }

    @PostMapping("/execute")
    fun execute(
        @RequestBody body: Map<String, Any>,
        @RequestHeader("X-Target", defaultValue = "LOCAL") target: String,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): Map<String, Any> {
        val purchaseId = (body["purchaseId"] as Number).toLong()
        @Suppress("UNCHECKED_CAST")
        val operations = body["operations"] as List<Map<String, Any>>
        val reason = body["reason"] as? String ?: "Remediation by $operator"
        val dryRun = body["dryRun"] as? Boolean ?: true
        val skipTicket = body["skipTicket"] as? Boolean ?: false
        log.info("[RemediationController][execute] purchase={} ops={} target={} dryRun={} operator={}", purchaseId, operations.size, target, dryRun, operator)
        return adminApiClient.remediationExecute(purchaseId, operations, reason, dryRun, skipTicket, target)
    }
}
