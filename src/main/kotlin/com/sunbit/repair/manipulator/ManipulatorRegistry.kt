package com.sunbit.repair.manipulator

import com.sunbit.repair.domain.ManipulatorCategory
import com.sunbit.repair.domain.ParamSpec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime registry of all loan manipulators. Auto-discovers LoanManipulator beans
 * and maintains per-manipulator enabled/disabled state.
 */
@Component
class ManipulatorRegistry(
    manipulators: List<LoanManipulator>,
    @Value("\${manipulator.disabled:}") disabledCsv: String,
) {
    private val log = LoggerFactory.getLogger(ManipulatorRegistry::class.java)

    private val byId: Map<String, LoanManipulator> =
        manipulators.associateBy { it.manipulatorId }

    private val enabledState: ConcurrentHashMap<String, Boolean> =
        ConcurrentHashMap<String, Boolean>().also { map ->
            val disabledSet = disabledCsv
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            manipulators.forEach { m ->
                val enabled = m.defaultEnabled && m.manipulatorId !in disabledSet
                map[m.manipulatorId] = enabled
            }

            log.info(
                "[ManipulatorRegistry][init] Registered {} manipulators, {} enabled, disabled-by-config={}",
                manipulators.size,
                map.values.count { it },
                disabledSet,
            )
        }

    fun getById(id: String): LoanManipulator {
        return byId[id]
            ?: throw IllegalArgumentException("Unknown manipulatorId '$id'. Known: ${byId.keys.sorted()}")
    }

    fun getEnabled(): List<LoanManipulator> =
        byId.values.filter { enabledState[it.manipulatorId] == true }

    fun getAll(): List<LoanManipulator> =
        byId.values.toList()

    fun enable(id: String) {
        requireKnown(id)
        enabledState[id] = true
        log.info("[ManipulatorRegistry][enable] Manipulator '{}' enabled", id)
    }

    fun disable(id: String) {
        requireKnown(id)
        enabledState[id] = false
        log.info("[ManipulatorRegistry][disable] Manipulator '{}' disabled", id)
    }

    fun isEnabled(id: String): Boolean {
        requireKnown(id)
        return enabledState[id] ?: false
    }

    fun getInfo(): List<ManipulatorInfo> =
        byId.values.map { m ->
            ManipulatorInfo(
                id = m.manipulatorId,
                name = m.name,
                description = m.description,
                detailedDescription = m.detailedDescription,
                category = m.category,
                requiredParams = m.requiredParams,
                enabled = enabledState[m.manipulatorId] ?: false,
            )
        }.sortedBy { it.id }

    private fun requireKnown(id: String) {
        require(id in byId) {
            "Unknown manipulatorId '$id'. Known: ${byId.keys.sorted()}"
        }
    }
}

data class ManipulatorInfo(
    val id: String,
    val name: String,
    val description: String,
    val detailedDescription: String,
    val category: ManipulatorCategory,
    val requiredParams: List<ParamSpec>,
    val enabled: Boolean,
)
