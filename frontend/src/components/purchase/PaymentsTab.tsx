import React, { useMemo } from 'react';
import type { PurchaseSnapshot, ChargeTransaction, Payment, PaymentAction, Finding } from '../../types/domain';
import PaymentsTable from './PaymentsTable';
import RelatedCommsTooltip, { findRelatedComms } from '../common/RelatedCommsTooltip';

interface PaymentsTabProps {
  snapshot: PurchaseSnapshot;
  highlightIds: number[];
  findings?: Finding[];
  checkoutActions?: Record<string, any>[];
  onNavigateTab?: (tab: string, itemId?: number | string) => void;
}

function formatAmount(value: number | null | undefined): string {
  if (value == null) return '-';
  return '$' + value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-';
  return value.substring(0, 19).replace('T', ' ');
}

// Charge transaction type codes
const CT_REFUND_TYPES = [5, 6, 7, 13]; // ADJUSTMENT_REFUND, CANCEL_REFUND, DP_ADJUSTMENT_REFUND, PAYMENT_REFUND
const CT_CHARGEBACK_TYPES = [3, 4];     // CHARGEBACK, REVERSE_CHARGEBACK
const CT_UNPAID_TYPE = 8;

// Payment action type codes
const PA_WORKOUT = 9;
const PA_SETTLEMENT = 12;
const PA_CANCEL = 3;
const PA_RESTORE = 8;
const PA_APR_CHANGE = 10;
const PA_AMOUNT_CHANGE = 2;
const PA_DATE_CHANGE = 1;
const PA_SPLIT = 6;
const PA_DELAYED = 7;

interface Section<T> {
  title: string;
  items: T[];
}

function ChargeTransactionTable({ transactions, title, notifications, tickets, onCommsNavigate, purchaseId }: {
  transactions: ChargeTransaction[];
  title: string;
  notifications?: import('../../types/domain').NotificationSummary;
  tickets?: import('../../types/domain').SupportTicket[];
  onCommsNavigate?: (tab: 'notifications' | 'tickets', itemId: number | string) => void;
  purchaseId?: number;
}) {
  if (transactions.length === 0) {
    return (
      <div className="activity-section">
        <h4>{title}</h4>
        <p className="text-muted">None</p>
      </div>
    );
  }
  return (
    <div className="activity-section">
      <h4>{title} ({transactions.length})</h4>
      <table className="table table-compact">
        <thead>
          <tr>
            <th>ID</th>
            <th>Type</th>
            <th>Amount</th>
            <th>Time</th>
            <th>Chargeback</th>
            <th>Manual Adj</th>
            <th>Parent TX</th>
          </tr>
        </thead>
        <tbody>
          {transactions.map((ct) => {
            const related = notifications && tickets
              ? findRelatedComms(ct.chargeTime, notifications, tickets, 48, purchaseId)
              : [];
            return (
              <tr key={ct.id}>
                <td className="mono">{ct.id}</td>
                <td>
                  {related.length > 0 && onCommsNavigate ? (
                    <RelatedCommsTooltip items={related} onNavigate={onCommsNavigate}>
                      {ct.typeName || ct.type}
                    </RelatedCommsTooltip>
                  ) : (
                    <>{ct.typeName || ct.type}</>
                  )}
                </td>
                <td>{formatAmount(ct.amount)}</td>
                <td className="mono">{formatDateTime(ct.chargeTime)}</td>
                <td>{ct.chargeback ? 'Yes' : '-'}</td>
                <td>{ct.manualAdjustment ? 'Yes' : '-'}</td>
                <td className="mono">{ct.parentId ?? '-'}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function PaymentActionTable({ actions, title }: { actions: PaymentAction[]; title: string }) {
  if (actions.length === 0) {
    return (
      <div className="activity-section">
        <h4>{title}</h4>
        <p className="text-muted">None</p>
      </div>
    );
  }
  return (
    <div className="activity-section">
      <h4>{title} ({actions.length})</h4>
      <table className="table table-compact">
        <thead>
          <tr>
            <th>ID</th>
            <th>Type</th>
            <th>Time</th>
          </tr>
        </thead>
        <tbody>
          {actions.map((a) => (
            <tr key={a.id}>
              <td className="mono">{a.id}</td>
              <td>{a.typeName || a.type}</td>
              <td className="mono">{formatDateTime(a.timeOfAction)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DisputedPaymentsTable({ payments }: { payments: Payment[] }) {
  if (payments.length === 0) {
    return (
      <div className="activity-section">
        <h4>Disputes</h4>
        <p className="text-muted">None</p>
      </div>
    );
  }
  return (
    <div className="activity-section">
      <h4>Disputes ({payments.length})</h4>
      <table className="table table-compact">
        <thead>
          <tr>
            <th>Payment ID</th>
            <th>Amount</th>
            <th>Due Date</th>
            <th>Dispute</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {payments.map((p) => (
            <tr key={p.id}>
              <td className="mono">{p.id}</td>
              <td>{formatAmount(p.amount)}</td>
              <td>{p.dueDate?.substring(0, 10) ?? '-'}</td>
              <td>{p.dispute}</td>
              <td>{p.computedStatus}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const PaymentsTab: React.FC<PaymentsTabProps> = ({ snapshot, highlightIds, findings, checkoutActions = [], onNavigateTab }) => {
  const handleCommsNavigate = (tab: 'notifications' | 'tickets', itemId: number | string) => {
    if (onNavigateTab) onNavigateTab(tab, itemId);
  };
  const refunds = useMemo(
    () => snapshot.chargeTransactions.filter((ct) => CT_REFUND_TYPES.includes(ct.type)),
    [snapshot.chargeTransactions]
  );

  const chargebacks = useMemo(
    () => snapshot.chargeTransactions.filter((ct) => CT_CHARGEBACK_TYPES.includes(ct.type)),
    [snapshot.chargeTransactions]
  );

  const unpaids = useMemo(
    () => snapshot.chargeTransactions.filter((ct) => ct.type === CT_UNPAID_TYPE),
    [snapshot.chargeTransactions]
  );

  const disputedPayments = useMemo(
    () => snapshot.payments.filter((p) => p.dispute != null),
    [snapshot.payments]
  );

  const chargebackPayments = useMemo(
    () => snapshot.payments.filter((p) => p.chargeBack != null || p.chargeBackEnhancement != null),
    [snapshot.payments]
  );

  const workouts = useMemo(
    () => snapshot.paymentActions.filter((a) => a.type === PA_WORKOUT),
    [snapshot.paymentActions]
  );

  const settlements = useMemo(
    () => snapshot.paymentActions.filter((a) => a.type === PA_SETTLEMENT),
    [snapshot.paymentActions]
  );

  const cancellations = useMemo(
    () => snapshot.paymentActions.filter((a) => a.type === PA_CANCEL),
    [snapshot.paymentActions]
  );

  const restorations = useMemo(
    () => snapshot.paymentActions.filter((a) => a.type === PA_RESTORE),
    [snapshot.paymentActions]
  );

  const aprChanges = useMemo(
    () => snapshot.paymentActions.filter((a) => a.type === PA_APR_CHANGE),
    [snapshot.paymentActions]
  );

  const amountChanges = useMemo(
    () => snapshot.paymentActions.filter((a) => a.type === PA_AMOUNT_CHANGE),
    [snapshot.paymentActions]
  );

  const dateChanges = useMemo(
    () => snapshot.paymentActions.filter((a) => a.type === PA_DATE_CHANGE),
    [snapshot.paymentActions]
  );

  const splits = useMemo(
    () => snapshot.paymentActions.filter((a) => a.type === PA_SPLIT),
    [snapshot.paymentActions]
  );

  const delays = useMemo(
    () => snapshot.paymentActions.filter((a) => a.type === PA_DELAYED),
    [snapshot.paymentActions]
  );

  return (
    <div>
      {/* Main payments table */}
      <PaymentsTable
        payments={snapshot.payments}
        paymentAttempts={snapshot.paymentAttempts}
        chargeTransactions={snapshot.chargeTransactions}
        highlightIds={highlightIds}
        findings={findings}
        checkoutActions={checkoutActions}
        auditTrail={snapshot.auditTrail}
        chargeServiceAttempts={snapshot.chargeServiceAttempts}
        notifications={snapshot.notifications}
        tickets={snapshot.supportTickets}
        onNavigateTab={onNavigateTab}
        purchaseId={snapshot.purchaseId}
      />

      {/* Activity sections */}
      <div className="activity-sections">
        <ChargeTransactionTable transactions={refunds} title="Refunds"
          notifications={snapshot.notifications} tickets={snapshot.supportTickets} onCommsNavigate={handleCommsNavigate} purchaseId={snapshot.purchaseId} />
        <ChargeTransactionTable transactions={chargebacks} title="Chargebacks"
          notifications={snapshot.notifications} tickets={snapshot.supportTickets} onCommsNavigate={handleCommsNavigate} purchaseId={snapshot.purchaseId} />
        <ChargeTransactionTable transactions={unpaids} title="Unpaids (Reversals)"
          notifications={snapshot.notifications} tickets={snapshot.supportTickets} onCommsNavigate={handleCommsNavigate} purchaseId={snapshot.purchaseId} />
        <DisputedPaymentsTable payments={disputedPayments} />

        {/* Chargeback-flagged payments (distinct from chargeback transactions) */}
        {chargebackPayments.length > 0 && (
          <div className="activity-section">
            <h4>Payments with Chargeback Flag ({chargebackPayments.length})</h4>
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>Payment ID</th>
                  <th>Amount</th>
                  <th>Due Date</th>
                  <th>chargeBack</th>
                  <th>chargeBackEnhancement</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {chargebackPayments.map((p) => (
                  <tr key={p.id}>
                    <td className="mono">{p.id}</td>
                    <td>{formatAmount(p.amount)}</td>
                    <td>{p.dueDate?.substring(0, 10) ?? '-'}</td>
                    <td>{p.chargeBack ?? '-'}</td>
                    <td>{p.chargeBackEnhancement ?? '-'}</td>
                    <td>{p.computedStatus}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {chargebackPayments.length === 0 && (
          <div className="activity-section">
            <h4>Payments with Chargeback Flag</h4>
            <p className="text-muted">None</p>
          </div>
        )}

        <PaymentActionTable actions={workouts} title="Workouts" />
        <PaymentActionTable actions={settlements} title="Settlements" />
        <PaymentActionTable actions={cancellations} title="Cancellations" />
        <PaymentActionTable actions={restorations} title="Restorations" />
        <PaymentActionTable actions={amountChanges} title="Amount Changes" />
        <PaymentActionTable actions={aprChanges} title="APR Changes" />
        <PaymentActionTable actions={dateChanges} title="Date Changes" />
        <PaymentActionTable actions={splits} title="Split Payments" />
        <PaymentActionTable actions={delays} title="Delayed Charges" />
      </div>
    </div>
  );
};

export default PaymentsTab;
