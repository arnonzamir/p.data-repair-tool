package com.sunbit.repair.api

import com.sunbit.repair.analyzer.engine.RuleRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/rules")
@CrossOrigin(origins = ["*"])
class RulesController(
    private val ruleRegistry: RuleRegistry,
) {
    private val log = LoggerFactory.getLogger(RulesController::class.java)

    /**
     * List all registered rules with their enabled/disabled status.
     */
    @GetMapping
    fun listRules(): ResponseEntity<List<RuleInfoDto>> {
        log.info("[RulesController][listRules]")
        val rules = ruleRegistry.getRuleInfo().map { info ->
            RuleInfoDto(
                ruleId = info.id,
                ruleName = info.name,
                description = info.description,
                enabled = info.enabled,
            )
        }
        return ResponseEntity.ok(rules)
    }

    /**
     * Enable a rule by ID.
     */
    @PutMapping("/{ruleId}/enable")
    fun enableRule(@PathVariable ruleId: String): ResponseEntity<Map<String, Any>> {
        log.info("[RulesController][enableRule] ruleId={}", ruleId)
        ruleRegistry.enableRule(ruleId)
        return ResponseEntity.ok(mapOf("ruleId" to ruleId, "enabled" to true))
    }

    /**
     * Disable a rule by ID.
     */
    @PutMapping("/{ruleId}/disable")
    fun disableRule(@PathVariable ruleId: String): ResponseEntity<Map<String, Any>> {
        log.info("[RulesController][disableRule] ruleId={}", ruleId)
        ruleRegistry.disableRule(ruleId)
        return ResponseEntity.ok(mapOf("ruleId" to ruleId, "enabled" to false))
    }
}

data class RuleInfoDto(
    val ruleId: String,
    val ruleName: String,
    val description: String,
    val enabled: Boolean,
)
