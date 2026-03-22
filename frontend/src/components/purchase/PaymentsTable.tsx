import React, { useState, useMemo } from 'react';
import type { Payment, PaymentAttempt, ChargeTransaction, NotificationSummary, SupportTicket, Finding } from '../../types/domain';
import RelatedCommsTooltip, { findRelatedComms } from '../common/RelatedCommsTooltip';

interface PaymentsTableProps {
  payments: Payment[];
  paymentAttempts?: PaymentAttempt[];
  chargeTransactions?: ChargeTransaction[];
  highlightIds?: number[];
  findings?: Finding[];
  checkoutActions?: Record<string, any>[];
  notifications?: NotificationSummary;
  tickets?: SupportTicket[];
  onNavigateTab?: (tab: string) => void;
  purchaseId?: number;
}

type SortKey = 'dueDate' | 'id';

function formatAmount(value: number | null | undefined): string {
  if (value == null) return '-';
  return '$' + value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDate(value: string | null | undefined): string {
  if (!value) return '-';
  return value.substring(0, 10);
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-';
  return value.substring(0, 19).replace('T', ' ');
}

function getStatusClass(status: string): string {
  switch (status.toUpperCase()) {
    case 'PAID': return 'status-paid';
    case 'UNPAID': return 'status-unpaid';
    case 'INACTIVE': return 'status-inactive';
    case 'CHARGEBACK': return 'status-chargeback';
    case 'DISPUTED': return 'status-disputed';
    default: return '';
  }
}

/**
 * Resolve the charge source for a payment based on its attempts.
 * For unscheduled payments with CI=PAY_NOW: distinguishes EARLY_CHARGE (JOB) vs PAY_NOW.
 * For all other paid payments: shows the attempt source (JOB, ADMIN, SELF_SERVICE, etc.)
 */
function resolveChargeSource(
  payment: Payment,
  attemptsByPaymentId: Map<number, PaymentAttempt[]>,
): string | null {
  const attempts = attemptsByPaymentId.get(payment.id) || [];
  if (attempts.length === 0) return null;

  const isUnscheduled = payment.type === 10 || payment.type === 20;
  const isPayNow = payment.changeIndicator === 8;

  // For unscheduled/pay-now: early charge vs pay-now distinction
  if (isUnscheduled || isPayNow) {
    const hasJobSource = attempts.some((a) => a.source === 0);
    return hasJobSource ? 'Early charge (auto)' : 'Pay-now';
  }

  // For all paid payments: show the source of the successful attempt
  if (payment.paidOffDate) {
    const successAttempt = attempts.find((a) => a.status === 0);
    if (successAttempt?.source != null) {
      const SOURCES: Record<number, string> = {
        0: 'Auto (job)', 1: 'Call center', 2: 'Customer (web)', 5: 'Customer (phone)', 6: 'Customer (app)', 8: 'Payment link', 9: 'Chat',
      };
      return SOURCES[successAttempt.source] || `SRC_${successAttempt.source}`;
    }
  }

  return null;
}

const ATTEMPT_STATUS: Record<number, string> = { 0: 'SUCCESS', 1: 'FAIL' };
const ATTEMPT_SOURCE: Record<number, string> = {
  0: 'Auto (job)', 1: 'Call center', 2: 'Customer (web)', 5: 'Customer (phone)', 6: 'Customer (app)', 8: 'Payment link', 9: 'Chat',
};

/** Expanded detail panel for a single payment */
function PaymentDetailPanel({
  payment,
  allPayments,
  attempts,
  transactions,
  checkoutActions = [],
}: {
  payment: Payment;
  allPayments: Payment[];
  attempts: PaymentAttempt[];
  transactions: ChargeTransaction[];
  checkoutActions?: Record<string, any>[];
}) {
  // Find children (payments whose directParentId or originalPaymentId points here)
  const children = allPayments.filter(
    (p) => p.directParentId === payment.id || p.originalPaymentId === payment.id
  );

  // Find parent
  const parent = payment.directParentId
    ? allPayments.find((p) => p.id === payment.directParentId)
    : null;

  // Find split siblings
  const splitSiblings = payment.splitFrom
    ? allPayments.filter((p) => p.splitFrom === payment.splitFrom && p.id !== payment.id)
    : [];

  return (
    <div className="payment-detail-panel">
      {/* Payment attempts */}
      {attempts.length > 0 && (
        <div className="panel-section">
          <h5>Payment Attempts ({attempts.length})</h5>
          <table className="table table-compact">
            <thead>
              <tr>
                <th>Time</th>
                <th>Source</th>
                <th>Status</th>
                <th>Processor TX</th>
                <th>Fail Message</th>
              </tr>
            </thead>
            <tbody>
              {attempts.map((a) => (
                <tr key={a.id} className={a.status === 1 ? 'row-fail' : ''}>
                  <td className="mono">{formatDateTime(a.dateTime)}</td>
                  <td>{a.source != null ? (ATTEMPT_SOURCE[a.source] || a.source) : '-'}</td>
                  <td>{a.status != null ? (ATTEMPT_STATUS[a.status] || a.status) : '-'}</td>
                  <td className="mono">{a.processorTxId || '-'}</td>
                  <td>{a.failMessage || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Charge transactions */}
      {transactions.length > 0 && (
        <div className="panel-section">
          <h5>Charge Transactions ({transactions.length})</h5>
          <table className="table table-compact">
            <thead>
              <tr>
                <th>ID</th>
                <th>Type</th>
                <th>Amount</th>
                <th>Time</th>
                <th>Chargeback</th>
                <th>Manual</th>
              </tr>
            </thead>
            <tbody>
              {transactions.map((ct) => (
                <tr key={ct.id}>
                  <td className="mono">{ct.id}</td>
                  <td>{ct.typeName || ct.type}</td>
                  <td>{formatAmount(ct.amount)}</td>
                  <td className="mono">{formatDateTime(ct.chargeTime)}</td>
                  <td>{ct.chargeback ? 'Yes' : '-'}</td>
                  <td>{ct.manualAdjustment ? 'Yes' : '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Parent payment */}
      {parent && (
        <div className="panel-section">
          <h5>Parent Payment</h5>
          <table className="table table-compact">
            <thead>
              <tr><th>ID</th><th>Due Date</th><th>Amount</th><th>Type</th><th>CI</th><th>Status</th><th>Active</th></tr>
            </thead>
            <tbody>
              <tr>
                <td className="mono">{parent.id}</td>
                <td>{formatDate(parent.dueDate)}</td>
                <td>{formatAmount(parent.amount)}</td>
                <td>{parent.typeName || parent.type}</td>
                <td>{parent.changeIndicatorName || parent.changeIndicator}</td>
                <td><span className={`badge ${getStatusClass(parent.computedStatus)}`}>{parent.computedStatus}</span></td>
                <td>{parent.isActive ? 'Yes' : 'No'}</td>
              </tr>
            </tbody>
          </table>
        </div>
      )}

      {/* Child payments */}
      {children.length > 0 && (
        <div className="panel-section">
          <h5>Child Payments ({children.length})</h5>
          <table className="table table-compact">
            <thead>
              <tr><th>ID</th><th>Due Date</th><th>Amount</th><th>Type</th><th>CI</th><th>Status</th><th>Active</th></tr>
            </thead>
            <tbody>
              {children.map((c) => (
                <tr key={c.id}>
                  <td className="mono">{c.id}</td>
                  <td>{formatDate(c.dueDate)}</td>
                  <td>{formatAmount(c.amount)}</td>
                  <td>{c.typeName || c.type}</td>
                  <td>{c.changeIndicatorName || c.changeIndicator}</td>
                  <td><span className={`badge ${getStatusClass(c.computedStatus)}`}>{c.computedStatus}</span></td>
                  <td>{c.isActive ? 'Yes' : 'No'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Split siblings */}
      {splitSiblings.length > 0 && (
        <div className="panel-section">
          <h5>Split Siblings ({splitSiblings.length})</h5>
          <table className="table table-compact">
            <thead>
              <tr><th>ID</th><th>Due Date</th><th>Amount</th><th>Type</th><th>Status</th><th>Active</th></tr>
            </thead>
            <tbody>
              {splitSiblings.map((s) => (
                <tr key={s.id}>
                  <td className="mono">{s.id}</td>
                  <td>{formatDate(s.dueDate)}</td>
                  <td>{formatAmount(s.amount)}</td>
                  <td>{s.typeName || s.type}</td>
                  <td><span className={`badge ${getStatusClass(s.computedStatus)}`}>{s.computedStatus}</span></td>
                  <td>{s.isActive ? 'Yes' : 'No'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Checkout.com actions */}
      {checkoutActions.length > 0 && (
        <div className="panel-section">
          <h5>Checkout.com Actions ({checkoutActions.length})</h5>
          <table className="table table-compact">
            <thead>
              <tr><th>Date</th><th>Action</th><th>Amount</th><th>Source</th></tr>
            </thead>
            <tbody>
              {checkoutActions.map((ca, i) => (
                <tr key={i}>
                  <td className="mono">{ca.action_date || '-'}</td>
                  <td style={{ color: ca.action_type === 'Refund' ? '#2e7d32' : '#1565c0', fontWeight: 600 }}>{ca.action_type}</td>
                  <td>{'$' + Number(ca.amount || 0).toFixed(2)}</td>
                  <td>{ca.source || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Raw fields */}
      <div className="panel-section">
        <h5>Raw Fields</h5>
        <div className="raw-fields">
          <span>paymentActionId: {payment.paymentActionId ?? '-'}</span>
          <span>splitFrom: {payment.splitFrom ?? '-'}</span>
          <span>originalPaymentId: {payment.originalPaymentId ?? '-'}</span>
          <span>directParentId: {payment.directParentId ?? '-'}</span>
          <span>manualUntil: {formatDateTime(payment.manualUntil)}</span>
          <span>chargeBack: {payment.chargeBack ?? '-'}</span>
          <span>dispute: {payment.dispute ?? '-'}</span>
          <span>creationDate: {formatDateTime(payment.creationDate)}</span>
        </div>
      </div>

      {attempts.length === 0 && transactions.length === 0 && !parent && children.length === 0 && (
        <p className="text-muted">No linked records found for this payment.</p>
      )}
    </div>
  );
}

const PaymentsTable: React.FC<PaymentsTableProps> = ({ payments, paymentAttempts, chargeTransactions, highlightIds, findings = [], checkoutActions = [], notifications, tickets, onNavigateTab, purchaseId }) => {
  const [sortKey, setSortKey] = useState<SortKey>('dueDate');
  const [sortAsc, setSortAsc] = useState(true);
  const [showInactive, setShowInactive] = useState(false);
  const [expandedId, setExpandedId] = useState<number | null>(null);

  const highlightSet = useMemo(() => new Set(highlightIds || []), [highlightIds]);

  // Map checkout actions by payment ID
  const checkoutByPaymentId = useMemo(() => {
    const map = new Map<number, Record<string, any>[]>();
    for (const ca of checkoutActions) {
      if (ca.payment_id) {
        const pid = Number(ca.payment_id);
        const existing = map.get(pid) || [];
        existing.push(ca);
        map.set(pid, existing);
      }
    }
    return map;
  }, [checkoutActions]);

  // Map each payment ID to its findings (for inline display)
  const findingsByPaymentId = useMemo(() => {
    const map = new Map<number, Finding[]>();
    for (const f of findings) {
      for (const pid of f.affectedPaymentIds) {
        const existing = map.get(pid) || [];
        existing.push(f);
        map.set(pid, existing);
      }
    }
    return map;
  }, [findings]);

  const attemptsByPaymentId = useMemo(() => {
    const map = new Map<number, PaymentAttempt[]>();
    for (const a of (paymentAttempts || [])) {
      const existing = map.get(a.paymentId) || [];
      existing.push(a);
      map.set(a.paymentId, existing);
    }
    return map;
  }, [paymentAttempts]);

  // Build a rough mapping: payment -> charge transactions.
  // Charge transactions don't have a direct paymentId FK, but payment_attempts link them.
  // We map via: attempt.chargeTransactionId -> chargeTransaction.id
  const txByPaymentId = useMemo(() => {
    const ctById = new Map<string, ChargeTransaction>();
    for (const ct of (chargeTransactions || [])) {
      ctById.set(String(ct.id), ct);
    }
    const map = new Map<number, ChargeTransaction[]>();
    attemptsByPaymentId.forEach((attempts, paymentId) => {
      const txs: ChargeTransaction[] = [];
      for (const a of attempts) {
        if (a.chargeTransactionId) {
          const ct = ctById.get(a.chargeTransactionId);
          if (ct) txs.push(ct);
        }
      }
      if (txs.length > 0) {
        map.set(paymentId, txs);
      }
    });
    return map;
  }, [chargeTransactions, attemptsByPaymentId]);

  const filtered = useMemo(() => {
    let result = payments;
    if (!showInactive) {
      result = result.filter((p) => p.isActive);
    }
    return result;
  }, [payments, showInactive]);

  const sorted = useMemo(() => {
    const arr = [...filtered];
    arr.sort((a, b) => {
      let cmp: number;
      if (sortKey === 'dueDate') {
        cmp = (a.dueDate || '').localeCompare(b.dueDate || '');
      } else {
        cmp = a.id - b.id;
      }
      return sortAsc ? cmp : -cmp;
    });
    return arr;
  }, [filtered, sortKey, sortAsc]);

  const handleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortAsc(!sortAsc);
    } else {
      setSortKey(key);
      setSortAsc(true);
    }
  };

  const sortIndicator = (key: SortKey) => {
    if (sortKey !== key) return '';
    return sortAsc ? ' [asc]' : ' [desc]';
  };

  const toggleExpand = (id: number) => {
    setExpandedId(expandedId === id ? null : id);
  };

  return (
    <div className="payments-table-container">
      <div className="table-controls">
        <label className="filter-toggle">
          <input
            type="checkbox"
            checked={showInactive}
            onChange={(e) => setShowInactive(e.target.checked)}
          />
          Show inactive
        </label>
        <span className="table-count">{sorted.length} payments</span>
      </div>

      <table className="table" role="table">
        <thead>
          <tr>
            <th className="sortable" onClick={() => handleSort('id')}>
              ID{sortIndicator('id')}
            </th>
            <th className="sortable" onClick={() => handleSort('dueDate')}>
              Due Date{sortIndicator('dueDate')}
            </th>
            <th>Paid Off</th>
            <th>Amount</th>
            <th>Interest</th>
            <th>Remaining Principal</th>
            <th>Type</th>
            <th>Source</th>
            <th>Change Indicator</th>
            <th>Status</th>
            <th>Issues</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((p) => {
            const isHighlighted = highlightSet.has(p.id);
            const isInactive = !p.isActive;
            const isExpanded = expandedId === p.id;
            const chargeSource = resolveChargeSource(p, attemptsByPaymentId);
            const isDownPayment = p.type === 30;
            const sevOrder: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
            const paymentFindings = [...(findingsByPaymentId.get(p.id) || [])].sort((a, b) => (sevOrder[a.severity] ?? 9) - (sevOrder[b.severity] ?? 9));
            const hasFinding = paymentFindings.length > 0;
            const worstSeverity = hasFinding
              ? (['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].find(s => paymentFindings.some(f => f.severity === s)) || '')
              : '';
            const isHighSeverityFinding = worstSeverity === 'CRITICAL' || worstSeverity === 'HIGH';
            const rowClass = [
              isHighSeverityFinding ? 'row-finding' : (isHighlighted ? 'row-highlight' : ''),
              isInactive ? 'row-inactive' : '',
              isExpanded ? 'row-expanded' : '',
              isDownPayment ? 'row-down-payment' : '',
              'clickable-row',
            ].filter(Boolean).join(' ');

            return (
              <React.Fragment key={p.id}>
                <tr className={rowClass} onClick={() => toggleExpand(p.id)}>
                  <td>{p.id}</td>
                  <td>{formatDate(p.dueDate)}</td>
                  <td className="mono">{(() => {
                    const dateStr = formatDate(p.paidOffDate);
                    if (dateStr === '-' || !notifications || !tickets) return dateStr;
                    const related = findRelatedComms(p.paidOffDate, notifications, tickets, 48, purchaseId);
                    if (related.length > 0 && onNavigateTab) {
                      return (
                        <RelatedCommsTooltip items={related} onNavigate={(tab) => onNavigateTab(tab)}>
                          {dateStr}
                        </RelatedCommsTooltip>
                      );
                    }
                    return dateStr;
                  })()}</td>
                  <td>{formatAmount(p.amount)}</td>
                  <td>{formatAmount(p.interestAmount)}</td>
                  <td>{formatAmount(p.principalBalance)}</td>
                  <td>{p.typeName || p.type}</td>
                  <td>
                    {chargeSource ? (
                      <span className={`badge badge-source-${chargeSource.toLowerCase().replace(/[^a-z]/g, '-').replace(/-+/g, '-')}`}>
                        {chargeSource}
                      </span>
                    ) : '-'}
                  </td>
                  <td>{p.changeIndicatorName || p.changeIndicator}</td>
                  <td>
                    <span className={`badge ${getStatusClass(p.computedStatus)}`}>
                      {p.computedStatus || '-'}
                    </span>
                  </td>
                  <td>
                    {hasFinding ? (
                      <div
                        className="finding-inline-link"
                        onClick={(e) => { e.stopPropagation(); onNavigateTab && onNavigateTab('findings'); }}
                        title={paymentFindings.map(f => `[${f.severity}] ${f.ruleName}: ${f.description}`).join('\n\n')}
                      >
                        {paymentFindings.map((f, idx) => (
                          <div key={idx} style={{ marginBottom: idx < paymentFindings.length - 1 ? 2 : 0 }}>
                            <span style={{ fontSize: 11, color: f.severity === 'CRITICAL' ? '#c62828' : f.severity === 'HIGH' ? '#e65100' : f.severity === 'MEDIUM' ? '#f57f17' : '#757575', fontWeight: 600 }}>
                              {f.severity}
                            </span>
                            <div style={{ fontSize: 10, color: '#546e7a', lineHeight: 1.3, maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                              {f.ruleName}
                            </div>
                          </div>
                        ))}
                      </div>
                    ) : '-'}
                  </td>
                </tr>
                {isExpanded && (
                  <tr className="detail-row">
                    <td colSpan={11}>
                      <PaymentDetailPanel
                        payment={p}
                        allPayments={payments}
                        attempts={attemptsByPaymentId.get(p.id) || []}
                        transactions={txByPaymentId.get(p.id) || []}
                        checkoutActions={checkoutByPaymentId.get(p.id) || []}
                      />
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
          {sorted.length === 0 && (
            <tr>
              <td colSpan={11} className="empty-row">No payments to display</td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
};

export default PaymentsTable;
