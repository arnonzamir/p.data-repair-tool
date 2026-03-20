import React from 'react';
import type { BalanceCheck, MoneyMovement, CrossSchemaReconciliation, UnifiedChargeEvent } from '../../types/domain';

interface MoneySummaryProps {
  balanceCheck: BalanceCheck;
  moneyMovement: MoneyMovement;
  crossSchema: CrossSchemaReconciliation;
  unifiedChargeEvents?: UnifiedChargeEvent[];
}

function formatAmount(value: number | null | undefined): string {
  if (value == null) return '-';
  return '$' + value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function verdictClass(verdict: string): string {
  return verdict === 'PASS' ? 'text-pass' : 'text-fail';
}

function gapVerdict(gap: number): string {
  return gap === 0 ? 'PASS' : 'FAIL';
}

const MoneySummary: React.FC<MoneySummaryProps> = ({ balanceCheck, moneyMovement, crossSchema, unifiedChargeEvents = [] }) => {
  return (
    <div className="money-summary">
      {/* Balance Check */}
      <section className="money-section">
        <h3>Balance Check</h3>
        <div className="balance-grid">
          <div className="balance-item">
            <span className="balance-label">Plan Total</span>
            <span className="balance-value">{formatAmount(balanceCheck.planTotal)}</span>
          </div>
          <div className="balance-item">
            <span className="balance-label">Paid Installments</span>
            <span className="balance-value">{formatAmount(balanceCheck.paidInstallments)}</span>
          </div>
          <div className="balance-item">
            <span className="balance-label">Unpaid Installments</span>
            <span className="balance-value">{formatAmount(balanceCheck.unpaidInstallments)}</span>
          </div>
          <div className="balance-item">
            <span className="balance-label">Down Payment</span>
            <span className="balance-value">{formatAmount(balanceCheck.downPayment)}</span>
          </div>
          <div className="balance-item">
            <span className="balance-label">Net Collected</span>
            <span className="balance-value">{formatAmount(balanceCheck.netCollected)}</span>
          </div>
          <div className="balance-item">
            <span className="balance-label">Schedule Total</span>
            <span className="balance-value">{formatAmount(balanceCheck.scheduleTotal)}</span>
          </div>
          <div className="balance-item">
            <span className="balance-label">Schedule Gap</span>
            <span className={`balance-value ${verdictClass(gapVerdict(balanceCheck.scheduleGap))}`}>
              {formatAmount(balanceCheck.scheduleGap)} ({gapVerdict(balanceCheck.scheduleGap)})
            </span>
          </div>
          <div className="balance-item">
            <span className="balance-label">Money Gap</span>
            <span className={`balance-value ${verdictClass(gapVerdict(balanceCheck.moneyGap))}`}>
              {formatAmount(balanceCheck.moneyGap)} ({gapVerdict(balanceCheck.moneyGap)})
            </span>
          </div>
        </div>
      </section>

      {/* Money Movement */}
      <section className="money-section">
        <h3>Money Movement</h3>
        <div className="money-totals">
          <span>Total Collected: {formatAmount(moneyMovement.totalCollected)}</span>
          <span>Total Returned: {formatAmount(moneyMovement.totalReturned)}</span>
          <span>Net: {formatAmount(moneyMovement.net)}</span>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>Type</th>
              <th>Count</th>
              <th>Total Amount</th>
              <th>Direction</th>
            </tr>
          </thead>
          <tbody>
            {moneyMovement.byType.map((entry) => (
              <tr key={entry.type}>
                <td>{entry.typeName}</td>
                <td>{entry.count}</td>
                <td>{formatAmount(entry.totalAmount)}</td>
                <td>{entry.totalAmount >= 0 ? 'IN' : 'OUT'}</td>
              </tr>
            ))}
            {moneyMovement.byType.length === 0 && (
              <tr>
                <td colSpan={4} className="empty-row">No money movement records</td>
              </tr>
            )}
          </tbody>
        </table>
      </section>

      {/* Charge Matching Summary (from unified events) */}
      <section className="money-section">
        <h3>Charge Matching Summary</h3>
        {unifiedChargeEvents.length > 0 ? (() => {
          const exact = unifiedChargeEvents.filter(e => e.matchQuality === 'EXACT');
          const amountMatch = unifiedChargeEvents.filter(e => e.matchQuality === 'AMOUNT_MATCH');
          const purchaseOnly = unifiedChargeEvents.filter(e => e.matchQuality === 'UNMATCHED' && e.chargeTransaction && !e.chargeServiceAttempt);
          const chargeOnly = unifiedChargeEvents.filter(e => e.matchQuality === 'UNMATCHED' && !e.chargeTransaction && e.chargeServiceAttempt);
          const chargeOnlySuccess = chargeOnly.filter(e => e.chargeServiceAttempt?.status === 0);
          const chargeOnlyFail = chargeOnly.filter(e => e.chargeServiceAttempt?.status !== 0);
          return (
            <>
              <table className="table">
                <thead>
                  <tr><th>Match Quality</th><th>Count</th><th>Total Amount</th><th>Description</th></tr>
                </thead>
                <tbody>
                  <tr>
                    <td style={{color: '#2e7d32'}}>Exact match</td>
                    <td>{exact.length}</td>
                    <td>{formatAmount(exact.reduce((s, e) => s + e.amount, 0))}</td>
                    <td>Matched by processor transaction ID</td>
                  </tr>
                  <tr>
                    <td style={{color: '#f57f17'}}>Amount match</td>
                    <td>{amountMatch.length}</td>
                    <td>{formatAmount(amountMatch.reduce((s, e) => s + e.amount, 0))}</td>
                    <td>Matched by amount + external ID</td>
                  </tr>
                  <tr>
                    <td style={{color: '#e65100'}}>Purchase-only</td>
                    <td>{purchaseOnly.length}</td>
                    <td>{formatAmount(purchaseOnly.reduce((s, e) => s + e.amount, 0))}</td>
                    <td>Pre-migration charges (no processor record)</td>
                  </tr>
                  {chargeOnlySuccess.length > 0 && (
                    <tr style={{background: '#fbe9e7'}}>
                      <td style={{color: '#b71c1c'}}>Charge-only (success)</td>
                      <td>{chargeOnlySuccess.length}</td>
                      <td>{formatAmount(chargeOnlySuccess.reduce((s, e) => s + e.amount, 0))}</td>
                      <td>Processor charged but purchase-service has no record</td>
                    </tr>
                  )}
                  {chargeOnlyFail.length > 0 && (
                    <tr>
                      <td style={{color: '#757575'}}>Charge-only (failed)</td>
                      <td>{chargeOnlyFail.length}</td>
                      <td>{formatAmount(chargeOnlyFail.reduce((s, e) => s + e.amount, 0))}</td>
                      <td>Failed processor attempts (retries, declines)</td>
                    </tr>
                  )}
                </tbody>
              </table>
              <div style={{marginTop: 8, fontSize: 13, color: '#546e7a'}}>
                Total events: {unifiedChargeEvents.length}.
                See Charges tab for full detail.
              </div>
            </>
          );
        })() : (
          <p className="text-muted">No unified charge events loaded.</p>
        )}
      </section>
    </div>
  );
};

export default MoneySummary;
