package com.sunbit.repair.loader

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

@Service
class AdminApiClient(
    @Value("\${admin-api.base-url}") private val baseUrl: String,
    @Value("\${admin-api.timeout-seconds}") private val timeoutSeconds: Long,
) {

    private val log = LoggerFactory.getLogger(AdminApiClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    private val timeout: Duration = Duration.ofSeconds(timeoutSeconds)

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    fun getPurchaseDetails(purchaseId: Long): Map<String, Any> {
        log.info("[AdminApiClient][getPurchaseDetails] Fetching purchase details for {}", purchaseId)
        return webClient.get()
            .uri("/api/v1/admin/purchases/{id}", purchaseId)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Preview / calculation endpoints
    // -----------------------------------------------------------------------

    fun previewAmountChange(purchaseId: Long, newAmount: BigDecimal): Map<String, Any> {
        log.info("[AdminApiClient][previewAmountChange] Previewing amount change for purchase {} to {}", purchaseId, newAmount)
        return webClient.put()
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

    fun unpayWithRefund(purchaseId: Long, paymentId: Long): Map<String, Any> {
        log.info("[AdminApiClient][unpayWithRefund] Unpaying payment {} on purchase {} with refund", paymentId, purchaseId)
        return webClient.put()
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

    fun unpayWithoutRefund(purchaseId: Long, paymentId: Long): Map<String, Any> {
        log.info("[AdminApiClient][unpayWithoutRefund] Unpaying payment {} on purchase {} without refund", paymentId, purchaseId)
        return webClient.put()
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

    fun changeAmount(purchaseId: Long, newAmount: BigDecimal): Map<String, Any> {
        log.info("[AdminApiClient][changeAmount] Changing amount for purchase {} to {}", purchaseId, newAmount)
        return webClient.put()
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

    fun reversalOfAdjustment(purchaseId: Long): Map<String, Any> {
        log.info("[AdminApiClient][reversalOfAdjustment] Reversing adjustments for purchase {}", purchaseId)
        return webClient.post()
            .uri("/api/v1/admin/purchases/{id}/reversal-of-adjustments", purchaseId)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // APR change
    // -----------------------------------------------------------------------

    fun changeApr(purchaseId: Long, newApr: BigDecimal): Map<String, Any> {
        log.info("[AdminApiClient][changeApr] Changing APR for purchase {} to {}", purchaseId, newApr)
        return webClient.put()
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

    fun createWorkout(purchaseId: Long, params: Map<String, Any>): Map<String, Any> {
        log.info("[AdminApiClient][createWorkout] Creating workout for purchase {}", purchaseId)
        return webClient.put()
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

    fun createSettlement(purchaseId: Long, amount: BigDecimal): Map<String, Any> {
        log.info("[AdminApiClient][createSettlement] Creating settlement for purchase {} with amount {}", purchaseId, amount)
        return webClient.post()
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

    fun cancelPurchase(purchaseId: Long, reason: String): Map<String, Any> {
        log.info("[AdminApiClient][cancelPurchase] Cancelling purchase {} with reason: {}", purchaseId, reason)
        return webClient.put()
            .uri("/api/v1/admin/purchases/{id}/cancel", purchaseId)
            .bodyValue(mapOf("reason" to reason))
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    fun restoreCancellation(purchaseId: Long): Map<String, Any> {
        log.info("[AdminApiClient][restoreCancellation] Restoring cancellation for purchase {}", purchaseId)
        return webClient.put()
            .uri("/api/v1/admin/purchases/{id}/restore-cancellation", purchaseId)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    // -----------------------------------------------------------------------
    // Chargeback reversal
    // -----------------------------------------------------------------------

    fun reverseChargeback(purchaseId: Long): Map<String, Any> {
        log.info("[AdminApiClient][reverseChargeback] Reversing chargeback for purchase {}", purchaseId)
        return webClient.put()
            .uri("/api/v1/admin/purchases/{id}/reverse-chargeback", purchaseId)
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .block(timeout)
            ?: emptyMap()
    }

    companion object {
        private val MAP_TYPE = object : org.springframework.core.ParameterizedTypeReference<Map<String, Any>>() {}
    }
}
