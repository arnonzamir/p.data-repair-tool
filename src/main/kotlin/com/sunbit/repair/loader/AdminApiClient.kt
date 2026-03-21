package com.sunbit.repair.loader

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@ConfigurationProperties(prefix = "admin-api")
data class AdminApiProperties(
    val timeoutSeconds: Long = 30,
    val targets: Map<String, AdminApiTarget> = emptyMap(),
)

data class AdminApiTarget(
    val baseUrl: String,
)

@Service
class AdminApiClient(
    private val properties: AdminApiProperties,
) {

    private val log = LoggerFactory.getLogger(AdminApiClient::class.java)
    private val timeout: Duration = Duration.ofSeconds(properties.timeoutSeconds)

    private val clients = ConcurrentHashMap<String, WebClient>()

    private fun clientFor(target: String): WebClient {
        return clients.computeIfAbsent(target.uppercase()) { key ->
            val apiTarget = properties.targets[key.lowercase()]
                ?: throw IllegalArgumentException(
                    "[AdminApiClient] Unknown target '$key'. Known targets: ${properties.targets.keys}"
                )
            log.info("[AdminApiClient][clientFor] Creating WebClient for target {} -> {}", key, apiTarget.baseUrl)
            WebClient.builder()
                .baseUrl(apiTarget.baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build()
        }
    }

    /** Default client for backwards compatibility (uses LOCAL). */
    private fun defaultClient(): WebClient = clientFor("LOCAL")

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    fun getPurchaseDetails(purchaseId: Long, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][getPurchaseDetails] Fetching purchase details for {} from {}", purchaseId, target)
        return clientFor(target).get()
            .uri("/api/v1/admin/purchases/{id}", purchaseId)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Preview / calculation endpoints
    // -----------------------------------------------------------------------

    fun previewAmountChange(purchaseId: Long, newAmount: BigDecimal, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][previewAmountChange] Previewing amount change for purchase {} to {} on {}", purchaseId, newAmount, target)
        return clientFor(target).put()
            .uri("/api/v1/admin/purchases/{id}/amount-calculation", purchaseId)
            .bodyValue(mapOf("amount" to newAmount))
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Unpay
    // -----------------------------------------------------------------------

    fun unpayWithRefund(purchaseId: Long, paymentId: Long, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][unpayWithRefund] Unpaying payment {} on purchase {} with refund on {}", paymentId, purchaseId, target)
        return clientFor(target).put()
            .uri { builder ->
                builder.path("/admin/purchases/{id}/payments/set-as-unpaid")
                    .queryParam("paymentId", paymentId)
                    .queryParam("refund", true)
                    .build(purchaseId)
            }
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    fun unpayWithoutRefund(purchaseId: Long, paymentId: Long, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][unpayWithoutRefund] Unpaying payment {} on purchase {} without refund on {}", paymentId, purchaseId, target)
        return clientFor(target).put()
            .uri { builder ->
                builder.path("/admin/purchases/{id}/payments/set-as-unpaid")
                    .queryParam("paymentId", paymentId)
                    .queryParam("refund", false)
                    .build(purchaseId)
            }
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Amount change
    // -----------------------------------------------------------------------

    fun changeAmount(purchaseId: Long, newAmount: BigDecimal, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][changeAmount] Changing amount for purchase {} to {} on {}", purchaseId, newAmount, target)
        return clientFor(target).put()
            .uri("/api/v1/admin/purchases/{id}/amount", purchaseId)
            .bodyValue(mapOf("amount" to newAmount))
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Reversal of adjustments
    // -----------------------------------------------------------------------

    fun reversalOfAdjustment(purchaseId: Long, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][reversalOfAdjustment] Reversing adjustments for purchase {} on {}", purchaseId, target)
        return clientFor(target).post()
            .uri("/api/v1/admin/purchases/{id}/reversal-of-adjustments", purchaseId)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // APR change
    // -----------------------------------------------------------------------

    fun changeApr(purchaseId: Long, newApr: BigDecimal, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][changeApr] Changing APR for purchase {} to {} on {}", purchaseId, newApr, target)
        return clientFor(target).put()
            .uri("/api/v1/admin/purchases/{id}/apr", purchaseId)
            .bodyValue(mapOf("apr" to newApr))
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Workout
    // -----------------------------------------------------------------------

    fun createWorkout(purchaseId: Long, params: Map<String, Any>, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][createWorkout] Creating workout for purchase {} on {}", purchaseId, target)
        return clientFor(target).put()
            .uri("/api/v1/admin/purchases/{id}/workout", purchaseId)
            .bodyValue(params)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Settlement
    // -----------------------------------------------------------------------

    fun createSettlement(purchaseId: Long, amount: BigDecimal, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][createSettlement] Creating settlement for purchase {} with amount {} on {}", purchaseId, amount, target)
        return clientFor(target).post()
            .uri("/api/v1/admin/settlements/purchases/{purchaseId}/plan", purchaseId)
            .bodyValue(mapOf("amount" to amount))
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Cancellation
    // -----------------------------------------------------------------------

    fun cancelPurchase(purchaseId: Long, reason: String, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][cancelPurchase] Cancelling purchase {} with reason: {} on {}", purchaseId, reason, target)
        return clientFor(target).put()
            .uri("/api/v1/admin/purchases/{id}/cancel", purchaseId)
            .bodyValue(mapOf("reason" to reason))
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    fun restoreCancellation(purchaseId: Long, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][restoreCancellation] Restoring cancellation for purchase {} on {}", purchaseId, target)
        return clientFor(target).put()
            .uri("/api/v1/admin/purchases/{id}/restore-cancellation", purchaseId)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Chargeback reversal
    // -----------------------------------------------------------------------

    fun reverseChargeback(purchaseId: Long, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][reverseChargeback] Reversing chargeback for purchase {} on {}", purchaseId, target)
        return clientFor(target).put()
            .uri("/api/v1/admin/purchases/{id}/reverse-chargeback", purchaseId)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Waive payment
    // -----------------------------------------------------------------------

    fun waivePayment(paymentId: Long, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][waivePayment] Waiving payment {} on {}", paymentId, target)
        return clientFor(target).put()
            .uri("/api/v1/admin/purchases/payments/{paymentId}/waive", paymentId)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Update payment fields
    // -----------------------------------------------------------------------

    fun updatePaymentAmount(paymentId: Long, amount: BigDecimal, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][updatePaymentAmount] Updating payment {} amount to {} on {}", paymentId, amount, target)
        return clientFor(target).put()
            .uri("/internal/v1/payments/{paymentId}", paymentId)
            .bodyValue(mapOf("amount" to amount, "isActive" to true))
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // CPP status recalculation
    // -----------------------------------------------------------------------

    fun recalculateCppStatus(purchaseId: Long, target: String = "LOCAL"): Map<String, Any> {
        log.info("[AdminApiClient][recalculateCppStatus] Recalculating CPP status for purchase {} on {}", purchaseId, target)
        return clientFor(target).put()
            .uri("/api/v1/admin/purchases/{id}/payments/onschedule", purchaseId)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    companion object {
        private val MAP_TYPE = object : org.springframework.core.ParameterizedTypeReference<Map<String, Any>>() {}
    }
}
