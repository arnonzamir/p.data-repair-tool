package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Verifies that each loan_transaction's financial breakdown matches the
 * corresponding payment's fields. A mismatch means the payment was mutated
 * after being charged, and the current schedule no longer reflects what was
 * actually collected.
 */
@Component
class LoanTransactionMismatchRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(LoanTransactionMismatchRule::class.java)

    override val ruleId = "loan-transaction-mismatch"
    override val ruleName = "Loan Transaction vs Payment Mismatch"
    override val description = "Verifies that loan_transaction financial breakdown matches the corresponding payment fields"
    override val detailedDescription = """
        When a payment is charged, the system creates a loan_transaction that records the exact financial breakdown at charge time (amount, interest, principal balance). If the payment is later modified (e.g., by a rebalance or amount change), its current fields may differ from what was actually charged. This rule compares each loan_transaction against its corresponding payment and flags mismatches. Negative loan_transactions (reversals/refunds) are excluded. Principal balance mismatches on rebalanced payments (CI != 0) are excluded since rebalancing intentionally recalculates the running balance.

        Detection: Joins loan_transactions to payments by paymentId, compares amount, interestAmount, and principalBalance fields with ${'$'}0.01 tolerance.
    """.trimIndent()

    private val TOLERANCE = BigDecimal("0.01")

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[LoanTransactionMismatchRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        if (snapshot.loanTransactions.isEmpty()) return emptyList()

        val paymentsById = snapshot.payments.associateBy { it.id }
        val findings = mutableListOf<Finding>()

        for (lt in snapshot.loanTransactions) {
            val payment = paymentsById[lt.paymentId] ?: continue

            // Skip reversal/refund loan_transactions (negative amounts).
            // When a payment is unpaid, the loan_tx records the reversal with negative values
            // while the payment keeps its original positive amounts. This is expected.
            if (lt.amount < BigDecimal.ZERO) continue

            val mismatches = mutableListOf<String>()

            // Compare amount
            if ((lt.amount - payment.amount).abs() > TOLERANCE) {
                mismatches.add("amount: loan_tx=${lt.amount} vs payment=${payment.amount}")
            }

            // Compare interest
            if (lt.interestAmount != null && payment.interestAmount != null) {
                if ((lt.interestAmount - payment.interestAmount).abs() > TOLERANCE) {
                    mismatches.add("interest: loan_tx=${lt.interestAmount} vs payment=${payment.interestAmount}")
                }
            }

            // Compare principal balance -- but skip if payment was rebalanced (CI != 0),
            // since rebalancing recalculates the running balance while the loan_tx preserves
            // the original value at charge time. This is expected, not a real mismatch.
            if (lt.principalBalance != null && payment.principalBalance != null && payment.changeIndicator == 0) {
                if ((lt.principalBalance - payment.principalBalance).abs() > TOLERANCE) {
                    mismatches.add("principalBalance: loan_tx=${lt.principalBalance} vs payment=${payment.principalBalance}")
                }
            }

            if (mismatches.isNotEmpty()) {
                // Amount/interest mismatches are more serious than principal-only
                val severity = if (mismatches.any { it.startsWith("amount:") || it.startsWith("interest:") }) {
                    Severity.HIGH
                } else {
                    Severity.MEDIUM
                }

                findings.add(Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = severity,
                    affectedPaymentIds = listOf(payment.id),
                    description = "Payment ${payment.id} was modified after being charged. " +
                        "Loan transaction (what was actually collected from the customer at charge time) differs from " +
                        "the payment record (current schedule): " + mismatches.joinToString("; ") + ". " +
                        "The loan_transaction records what was actually charged to the customer at the time of the charge. " +
                        "If the payment record now shows different amounts, it means the payment was modified after being " +
                        "charged (e.g., by a rebalance or amount change). The loan system's current schedule no longer " +
                        "reflects what was really collected, which can cause incorrect balance calculations, wrong " +
                        "interest accrual, and misleading reports.",
                    evidence = mapOf(
                        "paymentId" to payment.id,
                        "loanTransactionId" to lt.id,
                        "chargeTransactionId" to lt.chargeTransactionId,
                        "mismatches" to mismatches,
                        "loanTx" to mapOf(
                            "amount" to lt.amount,
                            "interestAmount" to lt.interestAmount,
                            "principalBalance" to lt.principalBalance,
                        ),
                        "payment" to mapOf(
                            "amount" to payment.amount,
                            "interestAmount" to payment.interestAmount,
                            "principalBalance" to payment.principalBalance,
                            "isActive" to payment.isActive,
                            "changeIndicator" to payment.changeIndicator,
                        ),
                    ),
                    suggestedRepairs = emptyList(),
                ))
            }
        }

        return findings
    }
}
