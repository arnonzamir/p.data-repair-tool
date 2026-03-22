import React, { useState } from 'react';
import type { UnifiedChargeEvent, ChargeServiceAttemptStatus, NotificationSummary, SupportTicket } from '../../types/domain';
import RelatedCommsTooltip, { findRelatedComms } from '../common/RelatedCommsTooltip';

function formatAmount(v: number | null | undefined): string {
  if (v == null) return '-';
  return '$' + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDateTime(v: string | null | undefined): string {
  if (!v) return '-';
  return v.substring(0, 19).replace('T', ' ');
}

function getLatestStatus(statuses: ChargeServiceAttemptStatus[]): ChargeServiceAttemptStatus | null {
  if (statuses.length === 0) return null;
  const sorted = [...statuses].sort((a, b) =>
    (b.creationTime || '').localeCompare(a.creationTime || '')
  );
  return sorted[0];
}

function getStatusBadgeClass(status: string | null | undefined): string {
  if (!status) return '';
  const s = status.toUpperCase().replace(/[\s-]/g, '_');
  if (s === 'CAPTURED' || s === 'AUTHORIZED') return 'badge-status-captured';
  if (s === 'DECLINED') return 'badge-status-declined';
  if (s === 'DECLINED_NO_RETRY') return 'badge-status-declined-no-retry';
  if (s === 'PENDING') return 'badge-status-pending';
  return '';
}

function getMatchInfo(event: UnifiedChargeEvent): { label: string; badge: string; tooltip: string } {
  const ct = event.chargeTransaction;
  const csa = event.chargeServiceAttempt;

  if (event.matchQuality === 'EXACT') {
    return {
      label: 'Exact',
      badge: 'badge-match-exact',
      tooltip: `Matched by processor transaction ID (${csa?.processorTxId || '?'}).\n` +
        `purchase.charge_transactions: id=${ct?.id}, type=${ct?.typeName || ct?.type}, amount=$${ct?.amount}\n` +
        `charge.payment_attempts: id=${csa?.id}, processor=${csa?.paymentProcessor}, status=${csa?.status === 0 ? 'SUCCESS' : 'FAIL'}`,
    };
  }

  if (event.matchQuality === 'AMOUNT_MATCH') {
    return {
      label: 'Amount match',
      badge: 'badge-match-amount-match',
      tooltip: `Matched by amount ($${ct?.amount}) and external ID (${csa?.externalId || '?'}).\n` +
        `purchase.charge_transactions: id=${ct?.id}, type=${ct?.typeName || ct?.type}, time=${formatDateTime(ct?.chargeTime)}\n` +
        `charge.payment_attempts: id=${csa?.id}, processor=${csa?.paymentProcessor}, time=${formatDateTime(csa?.dateTime)}`,
    };
  }

  // UNMATCHED
  if (!ct && csa) {
    return {
      label: 'Charge-only',
      badge: 'badge-match-unmatched',
      tooltip: `Processor record exists but no matching purchase.charge_transactions record.\n` +
        `charge.payment_attempts: id=${csa.id}, external_id=${csa.externalId}, ` +
        `processor=${csa.paymentProcessor}, amount=$${csa.amount}, ` +
        `status=${csa.status === 0 ? 'SUCCESS' : 'FAIL'}\n` +
        `purchase.charge_transactions: none`,
    };
  }

  if (ct && !csa) {
    return {
      label: 'Purchase-only',
      badge: 'badge-match-unmatched badge-match-purchase-only',
      tooltip: `Purchase record exists but no matching charge.payment_attempts record.\n` +
        `This typically means the charge predates the charge-service migration.\n` +
        `purchase.charge_transactions: id=${ct.id}, type=${ct.typeName || ct.type}, ` +
        `amount=$${ct.amount}, time=${formatDateTime(ct.chargeTime)}\n` +
        `charge.payment_attempts: none`,
    };
  }

  return { label: 'Unmatched', badge: 'badge-match-unmatched', tooltip: 'No data from either schema' };
}

function getRowClass(event: UnifiedChargeEvent): string {
  if (event.matchQuality === 'UNMATCHED') {
    if (!event.chargeTransaction) return 'row-processor-only';
    if (!event.chargeServiceAttempt) return 'row-pre-migration';
  }
  return '';
}

interface UnifiedChargeEventsTabProps {
  events: UnifiedChargeEvent[];
  notifications?: NotificationSummary;
  tickets?: SupportTicket[];
  checkoutActions?: Record<string, any>[];
  onNavigateTab?: (tab: string) => void;
  purchaseId?: number;
}

const UnifiedChargeEventsTab: React.FC<UnifiedChargeEventsTabProps> = ({ events, notifications, tickets, checkoutActions = [], onNavigateTab, purchaseId }) => {
  const [expandedIndex, setExpandedIndex] = useState<number | null>(null);
  const [showOnlyUnmatched, setShowOnlyUnmatched] = useState(false);

  const toggleExpand = (index: number) => {
    setExpandedIndex(expandedIndex === index ? null : index);
  };

  // Build a set of payment IDs that have matching charge events
  const matchedPaymentIds = new Set<number>();
  for (const e of events) {
    if (e.paymentId) matchedPaymentIds.add(e.paymentId);
  }

  // Build merged list: charge events + checkout actions, sorted by timestamp
  type MergedRow = { type: 'charge'; event: UnifiedChargeEvent; index: number } | { type: 'checkout'; action: Record<string, any> };
  const merged: MergedRow[] = [];

  for (let i = 0; i < events.length; i++) {
    merged.push({ type: 'charge', event: events[i], index: i });
  }
  for (const ca of checkoutActions) {
    merged.push({ type: 'checkout', action: ca });
  }
  merged.sort((a, b) => {
    const tsA = a.type === 'charge' ? (a.event.timestamp || '') : (a.action.action_date || '');
    const tsB = b.type === 'charge' ? (b.event.timestamp || '') : (b.action.action_date || '');
    return tsA.localeCompare(tsB);
  });

  // Count unmatched checkout actions
  const unmatchedCount = checkoutActions.filter(ca => !matchedPaymentIds.has(Number(ca.payment_id))).length;

  return (
    <div className="charge-events-table">
      <div className="table-controls">
        <span className="table-count">
          {events.length} charge events
          {checkoutActions.length > 0 && ` + ${checkoutActions.length} Checkout.com actions`}
          {unmatchedCount > 0 && (
            <span style={{ color: '#c62828', marginLeft: 8 }}>
              ({unmatchedCount} unmatched)
            </span>
          )}
        </span>
        {checkoutActions.length > 0 && (
          <label className="filter-toggle" style={{ marginLeft: 12 }}>
            <input type="checkbox" checked={showOnlyUnmatched} onChange={e => setShowOnlyUnmatched(e.target.checked)} />
            Show only unmatched
          </label>
        )}
      </div>

      <table className="table" role="table">
        <thead>
          <tr>
            <th>Time</th>
            <th>Payment</th>
            <th>Amount</th>
            <th>Type</th>
            <th>Origin</th>
            <th>Processor</th>
            <th>Status</th>
            <th>Reason</th>
            <th>Match</th>
            <th>Interest</th>
            <th>Balance</th>
          </tr>
        </thead>
        <tbody>
          {merged.filter(row => {
            if (!showOnlyUnmatched) return true;
            if (row.type === 'checkout') return !matchedPaymentIds.has(Number(row.action.payment_id));
            return false;
          }).map((row, mergedIdx) => {
            // Checkout.com inline row
            if (row.type === 'checkout') {
              const ca = row.action;
              const isUnmatched = !matchedPaymentIds.has(Number(ca.payment_id));
              return (
                <tr key={`co-${mergedIdx}`} style={{ background: isUnmatched ? '#ffebee' : '#e3f2fd', fontSize: 11 }}>
                  <td className="mono">{ca.action_date || '-'}</td>
                  <td className="mono">{ca.payment_id || '-'}</td>
                  <td>{'$' + Number(ca.amount || 0).toFixed(2)}</td>
                  <td colSpan={2} style={{ fontWeight: 600, color: ca.action_type === 'Refund' ? '#2e7d32' : '#1565c0' }}>
                    Checkout.com {ca.action_type}
                    {isUnmatched && <span style={{ color: '#c62828', marginLeft: 6 }}>(UNMATCHED)</span>}
                  </td>
                  <td>{ca.source || '-'}</td>
                  <td colSpan={3}></td>
                </tr>
              );
            }

            const event = row.event;
            const index = row.index;
            const latest = getLatestStatus(event.chargeServiceStatuses);
            const isExpanded = expandedIndex === index;
            const rowClass = [
              getRowClass(event),
              isExpanded ? 'row-expanded' : '',
              'clickable-row',
            ].filter(Boolean).join(' ');

            return (
              <React.Fragment key={index}>
                <tr className={rowClass} onClick={() => toggleExpand(index)}>
                  <td className="mono">{formatDateTime(event.timestamp)}</td>
                  <td className="mono">{event.paymentId != null ? event.paymentId : '-'}</td>
                  <td>{formatAmount(event.amount)}</td>
                  <td>{(() => {
                    const typeLabel = event.chargeTransaction?.typeName || (event.chargeTransaction ? String(event.chargeTransaction.type) : 'Processor only');
                    const related = notifications && tickets
                      ? findRelatedComms(event.timestamp, notifications, tickets, 48, purchaseId)
                      : [];
                    if (related.length > 0 && onNavigateTab) {
                      return (
                        <RelatedCommsTooltip items={related} onNavigate={(tab) => onNavigateTab(tab)}>
                          {typeLabel}
                        </RelatedCommsTooltip>
                      );
                    }
                    return typeLabel;
                  })()}</td>
                  <td>{(() => {
                    const SOURCE_NAMES: Record<number, string> = {
                      0: 'Auto (job)', 1: 'Call center', 2: 'Customer (web)',
                      5: 'Customer (phone)', 6: 'Customer (app)', 8: 'Payment link', 9: 'Chat',
                    };
                    const src = event.purchaseAttempt?.source;
                    return src != null ? (SOURCE_NAMES[src] || `Source ${src}`) : '-';
                  })()}</td>
                  <td>{event.chargeServiceAttempt?.paymentProcessor || '-'}</td>
                  <td>
                    {latest?.chargeStatus ? (
                      <span className={`badge ${getStatusBadgeClass(latest.chargeStatus)}`}>
                        {latest.chargeStatus}
                      </span>
                    ) : '-'}
                  </td>
                  <td>{latest?.chargeStatusReason || '-'}</td>
                  <td>
                    {(() => {
                      const m = getMatchInfo(event);
                      return <span className={`match-label ${m.badge}`} title={m.tooltip}>{m.label}</span>;
                    })()}
                  </td>
                  <td>{formatAmount(event.loanTransaction?.interestAmount)}</td>
                  <td>{formatAmount(event.loanTransaction?.principalBalance)}</td>
                </tr>
                {isExpanded && (
                  <tr className="detail-row">
                    <td colSpan={11}>
                      <EventDetailPanel event={event} />
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
          {events.length === 0 && (
            <tr>
              <td colSpan={11} className="empty-row">No charge events to display</td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
};

function EventDetailPanel({ event }: { event: UnifiedChargeEvent }) {
  const ct = event.chargeTransaction;
  const lt = event.loanTransaction;
  const pa = event.purchaseAttempt;
  const csa = event.chargeServiceAttempt;
  const statuses = [...event.chargeServiceStatuses].sort((a, b) =>
    (a.creationTime || '').localeCompare(b.creationTime || '')
  );

  return (
    <div className="payment-detail-panel">
      {/* Purchase Charge Transaction */}
      {ct && (
        <div className="panel-section">
          <h5>Purchase Charge Transaction</h5>
          <div className="raw-fields">
            <span>id: {ct.id}</span>
            <span>purchaseId: {ct.purchaseId}</span>
            <span>type: {ct.type}</span>
            <span>typeName: {ct.typeName || '-'}</span>
            <span>amount: {formatAmount(ct.amount)}</span>
            <span>chargeTime: {formatDateTime(ct.chargeTime)}</span>
            <span>chargeback: {ct.chargeback ? 'Yes' : '-'}</span>
            <span>chargebackEnhancement: {ct.chargebackEnhancement ? 'Yes' : '-'}</span>
            <span>parentId: {ct.parentId ?? '-'}</span>
            <span>paymentProfileId: {ct.paymentProfileId ?? '-'}</span>
            <span>manualAdjustment: {ct.manualAdjustment ? 'Yes' : '-'}</span>
          </div>
        </div>
      )}

      {/* Loan Transaction */}
      {lt && (
        <div className="panel-section">
          <h5>Loan Transaction</h5>
          <div className="raw-fields">
            <span>id: {lt.id}</span>
            <span>paymentId: {lt.paymentId}</span>
            <span>chargeTransactionId: {lt.chargeTransactionId}</span>
            <span>amount: {formatAmount(lt.amount)}</span>
            <span>effectiveDate: {formatDateTime(lt.effectiveDate)}</span>
            <span>interestAmount: {formatAmount(lt.interestAmount)}</span>
            <span>interestCharge: {formatAmount(lt.interestCharge)}</span>
            <span>principalBalance: {formatAmount(lt.principalBalance)}</span>
          </div>
        </div>
      )}

      {/* Purchase Attempt */}
      {pa && (
        <div className="panel-section">
          <h5>Purchase Attempt</h5>
          <div className="raw-fields">
            <span>id: {pa.id}</span>
            <span>paymentId: {pa.paymentId}</span>
            <span>source: {pa.sourceName || (pa.source != null ? String(pa.source) : '-')}</span>
            <span>dateTime: {formatDateTime(pa.dateTime)}</span>
            <span>status: {pa.status != null ? String(pa.status) : '-'}</span>
            <span>processorTxId: {pa.processorTxId || '-'}</span>
            <span>holdTransactionId: {pa.holdTransactionId || '-'}</span>
            <span>failMessage: {pa.failMessage || '-'}</span>
          </div>
        </div>
      )}

      {/* Processor Attempt */}
      {csa && (
        <div className="panel-section">
          <h5>Processor Attempt</h5>
          <div className="raw-fields">
            <span>id: {csa.id}</span>
            <span>externalId: {csa.externalId}</span>
            <span>paymentProcessor: {csa.paymentProcessor || '-'}</span>
            <span>processorTxId: {csa.processorTxId || '-'}</span>
            <span>amount: {formatAmount(csa.amount)}</span>
            <span>status: {csa.status}</span>
            <span>type: {csa.type}</span>
            <span>dateTime: {formatDateTime(csa.dateTime)}</span>
            <span>lastUpdateTime: {formatDateTime(csa.lastUpdateTime)}</span>
            <span>failMessage: {csa.failMessage || '-'}</span>
            <span>origin: {csa.origin || '-'}</span>
            <span>paymentProfileId: {csa.paymentProfileId ?? '-'}</span>
          </div>
        </div>
      )}

      {/* Status History */}
      {statuses.length > 0 && (
        <div className="panel-section">
          <h5>Status History</h5>
          <div className="status-history-list">
            {statuses.map((s) => (
              <div key={s.id} className="status-history-entry">
                <span className="mono">{formatDateTime(s.creationTime)}</span>
                <span className={`badge ${getStatusBadgeClass(s.chargeStatus)}`}>
                  {s.chargeStatus || '-'}
                </span>
                <span>{s.chargeStatusReason || '-'}</span>
                <span className="text-muted">{s.summary || ''}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {!ct && !lt && !pa && !csa && statuses.length === 0 && (
        <p className="text-muted">No linked records found for this charge event.</p>
      )}
    </div>
  );
}

export default UnifiedChargeEventsTab;
