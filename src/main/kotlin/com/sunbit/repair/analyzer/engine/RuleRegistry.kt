package com.sunbit.repair.analyzer.engine

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime registry of all analysis rules. Auto-discovers AnalysisRule beans
 * and maintains per-rule enabled/disabled state that can be toggled at runtime.
 */
@Component
class RuleRegistry(
    rules: List<AnalysisRule>,
    @Value("\${analyzer.disabled-rules:}") disabledRulesCsv: String,
) {
    private val log = LoggerFactory.getLogger(RuleRegistry::class.java)

    private val rulesById: Map<String, AnalysisRule> =
        rules.associateBy { it.ruleId }

    private val enabledState: ConcurrentHashMap<String, Boolean> =
        ConcurrentHashMap<String, Boolean>().also { map ->
            val disabledSet = disabledRulesCsv
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            rules.forEach { rule ->
                val enabled = rule.defaultEnabled && rule.ruleId !in disabledSet
                map[rule.ruleId] = enabled
            }

            log.info(
                "[RuleRegistry][init] Registered {} rules, {} enabled, disabled-by-config={}",
                rules.size,
                map.values.count { it },
                disabledSet,
            )
        }

    fun enableRule(ruleId: String) {
        requireKnown(ruleId)
        enabledState[ruleId] = true
        log.info("[RuleRegistry][enableRule] Rule '{}' enabled", ruleId)
    }

    fun disableRule(ruleId: String) {
        requireKnown(ruleId)
        enabledState[ruleId] = false
        log.info("[RuleRegistry][disableRule] Rule '{}' disabled", ruleId)
    }

    fun isEnabled(ruleId: String): Boolean {
        requireKnown(ruleId)
        return enabledState[ruleId] ?: false
    }

    fun getEnabledRules(): List<AnalysisRule> =
        rulesById.values.filter { enabledState[it.ruleId] == true }

    fun getAllRules(): List<AnalysisRule> =
        rulesById.values.toList()

    fun getRuleInfo(): List<RuleInfo> =
        rulesById.values.map { rule ->
            RuleInfo(
                id = rule.ruleId,
                name = rule.ruleName,
                description = rule.description,
                detailedDescription = rule.detailedDescription,
                enabled = enabledState[rule.ruleId] ?: false,
            )
        }.sortedBy { it.id }

    private fun requireKnown(ruleId: String) {
        require(ruleId in rulesById) {
            "Unknown ruleId '$ruleId'. Known rules: ${rulesById.keys.sorted()}"
        }
    }
}

data class RuleInfo(
    val id: String,
    val name: String,
    val description: String,
    val detailedDescription: String,
    val enabled: Boolean,
)
