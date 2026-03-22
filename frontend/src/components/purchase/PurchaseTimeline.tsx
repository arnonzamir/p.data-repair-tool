import React, { useMemo } from 'react';
import type { PurchaseSnapshot } from '../../types/domain';

interface PurchaseTimelineProps {
  snapshot: PurchaseSnapshot;
  checkoutActions?: Record<string, any>[];
}

// ---------------------------------------------------------------------------
// Event types -- every source is normalized into a single timeline entry
// ---------------------------------------------------------------------------

type EventCategory =
  | 'origination'
  | 'charge'
  | 'payment'
  | 'refund'
  | 'chargeback'
  | 'dispute'
  | 'restructure'
  | 'notification'
  | 'ticket'
  | 'status';

interface TimelineEvent {
  timestamp: string;       // ISO string for sorting
  displayTime: string;     // formatted for display
  category: EventCategory;
  secondaryCategory?: EventCategory; // for merged charge+payment events
  title: string;
  detail: string;
  secondaryDetail?: string; // detail for the secondary category
  customerVisible: boolean; // whether the customer would know about this
  amount?: string;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatAmount(value: number | null | undefined): string {
  if (value == null) return '';
  return '$' + value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function fmt(value: string | null | undefined): string {
  if (!value) return '';
  return value.substring(0, 19).replace('T', ' ');
}

function ts(value: string | null | undefined): string {
  return value || '9999-99-99';
}

const CT_TYPE_NAMES: Record<number, string> = {
  0: 'Scheduled Payment',
  1: 'Unscheduled Payment',
  2: 'Down Payment',
  3: 'Chargeback',
  4: 'Reverse Chargeback',
  5: 'Adjustment Refund',
  6: 'Cancel Refund',
  7: 'DP Adjustment Refund',
  8: 'Unpaid (Reversal)',
  9: 'Retailer Adjustment',
  12: 'Interest Elimination',
  13: 'Payment Refund',
};

const PA_TYPE_NAMES: Record<number, string> = {
  0: 'Pay Now',
  1: 'Move Payments (date change)',
  2: 'Change Amount',
  3: 'Cancel Purchase',
  4: 'Unpaid',
  6: 'Split Payment',
  7: 'Delayed Charge Date',
  8: 'Restore Cancellation',
  9: 'Workout',
  10: 'APR Change',
  11: 'Reversal of Adjustments',
  12: 'Settlement',
  13: 'Unpaid with Refund',
};

const EMAIL_TYPE_NAMES: Record<number, string> = {
  1: 'Agreement',
  3: 'Agreement Draft',
  4: 'Retailer Charge Notification',
  5: 'Payment Received (On Schedule)',
  7: 'Paid Off',
  9: 'Payment Received (Late Purchase)',
  10: 'Pay Now Payment',
  12: 'Charge Failed',
};

const ATTEMPT_SOURCE: Record<number, string> = {
  0: 'automated job', 1: 'call center agent', 2: 'customer (web)',
  5: 'customer (IVR)', 6: 'customer (app)', 8: 'payment link', 9: 'chat',
};

const CATEGORY_COLORS: Record<EventCategory, string> = {
  origination: '#1565c0',
  charge: '#2e7d32',
  payment: '#2e7d32',
  refund: '#e65100',
  chargeback: '#b71c1c',
  dispute: '#6a1b9a',
  restructure: '#f57f17',
  notification: '#546e7a',
  ticket: '#00695c',
  status: '#37474f',
};

// ---------------------------------------------------------------------------
// Build unified timeline
// ---------------------------------------------------------------------------

function buildTimeline(snapshot: PurchaseSnapshot, checkoutActions: Record<string, any>[] = []): TimelineEvent[] {
  const events: TimelineEvent[] = [];

  // 1. Payment actions (loan management events)
  for (const action of snapshot.paymentActions) {
    const typeName = PA_TYPE_NAMES[action.type] || `Action type ${action.type}`;
    const isCustomerAction = [0].includes(action.type); // pay-now is customer-initiated
    const isRestructure = [1, 2, 6, 7, 9, 10, 11].includes(action.type);
    const isCancel = [3, 8].includes(action.type);

    let category: EventCategory = 'restructure';
    if (action.type === 0) category = 'payment';
    if (action.type === 12) category = 'restructure';
    if (isCancel) category = 'restructure';

    let customerNote = '';
    if (action.type === 0) customerNote = ' -- customer paid early or amount changed';
    if (action.type === 1) customerNote = ' -- payment dates shifted';
    if (action.type === 2) customerNote = ' -- loan amount changed, schedule recalculated';
    if (action.type === 3) customerNote = ' -- purchase cancelled, refunds may follow';
    if (action.type === 8) customerNote = ' -- cancellation reversed, payments restored';
    if (action.type === 9) customerNote = ' -- new payment plan created for hardship';
    if (action.type === 10) customerNote = ' -- interest rate changed';
    if (action.type === 12) customerNote = ' -- negotiated settlement';
    if (action.type === 13) customerNote = ' -- payment reversed with refund to card';

    events.push({
      timestamp: ts(action.timeOfAction),
      displayTime: fmt(action.timeOfAction),
      category,
      title: typeName,
      detail: `Action #${action.id}${customerNote}`,
      customerVisible: isCustomerAction || isCancel || action.type === 9 || action.type === 12,
    });
  }

  // 2. Unified charge events (merges charge_transaction + payment + processor attempt)
  const unifiedEvents = snapshot.unifiedChargeEvents || [];
  // Track which payment IDs are covered by unified events so we don't duplicate in section 6
  const paymentsCoveredByCharges = new Set<number>();

  for (const uce of unifiedEvents) {
    const ct = uce.chargeTransaction;
    const csa = uce.chargeServiceAttempt;
    const statuses = uce.chargeServiceStatuses || [];
    const latestStatus = statuses.length > 0 ? statuses[statuses.length - 1] : null;

    // Find the linked payment
    const linkedPayment = uce.paymentId
      ? snapshot.payments.find((p) => p.id === uce.paymentId)
      : null;

    if (ct) {
      const typeName = CT_TYPE_NAMES[ct.type] || `CT type ${ct.type}`;
      const isRefund = [5, 6, 7, 13].includes(ct.type);
      const isChargeback = [3, 4].includes(ct.type);

      let category: EventCategory = 'charge';
      if (isRefund) category = 'refund';
      if (isChargeback) category = 'chargeback';
      if (ct.type === 8) category = 'refund';

      // Build charge detail
      let title = typeName;
      let chargeDetail = '';
      let paymentDetail = '';
      let secondaryCategory: EventCategory | undefined = undefined;

      // Charge perspective
      if (ct.type === 0 || ct.type === 1) chargeDetail = 'Money charged from card';
      if (ct.type === 2) chargeDetail = 'Down payment charged';
      if (isRefund) chargeDetail = 'Money returned to card';
      if (ct.type === 3) chargeDetail = 'Bank reversed a charge (chargeback)';
      if (ct.type === 4) chargeDetail = 'Chargeback resolved, money re-collected';

      // Add processor status
      if (latestStatus?.chargeStatus) {
        chargeDetail += chargeDetail ? '. ' : '';
        chargeDetail += `Processor: ${latestStatus.chargeStatus}`;
        if (latestStatus.chargeStatusReason) chargeDetail += ` (${latestStatus.chargeStatusReason})`;
      }
      if (csa?.paymentProcessor) {
        chargeDetail += chargeDetail ? '. ' : '';
        chargeDetail += `Via ${csa.paymentProcessor}`;
      }

      // Merge linked payment info
      if (linkedPayment && linkedPayment.paidOffDate && [0, 1, 2].includes(ct.type)) {
        const payType = linkedPayment.type === 0 ? 'Scheduled installment' : linkedPayment.type === 10 ? 'Partial payment' : linkedPayment.type === 20 ? 'Payoff payment' : 'Payment';
        const ci = linkedPayment.changeIndicatorName || '';
        title = `${typeName} + ${payType} paid`;
        secondaryCategory = 'payment';
        paymentDetail = `Payment #${linkedPayment.id}, due ${linkedPayment.dueDate?.substring(0, 10)}`;
        if (ci && ci !== 'NONE') paymentDetail += `, CI=${ci}`;
        if (linkedPayment.interestAmount) paymentDetail += `, interest=${formatAmount(linkedPayment.interestAmount)}`;
        paymentsCoveredByCharges.add(linkedPayment.id);
      }

      events.push({
        timestamp: ts(uce.timestamp),
        displayTime: fmt(uce.timestamp),
        category,
        secondaryCategory,
        title,
        detail: chargeDetail,
        secondaryDetail: paymentDetail || undefined,
        customerVisible: [0, 1, 2, 3, 4, 5, 6, 7, 13].includes(ct.type),
        amount: formatAmount(uce.amount),
      });
    } else if (csa) {
      // Processor-only event (no purchase-service charge_transaction)
      const isFail = csa.status !== 0;
      const source = ATTEMPT_SOURCE[csa.type] || '';

      let detail = isFail ? 'Charge attempt FAILED' : 'Processor charge (no purchase record)';
      if (csa.failMessage) detail += `. ${csa.failMessage}`;
      if (latestStatus?.chargeStatus) {
        detail += `. ${latestStatus.chargeStatus}`;
        if (latestStatus.chargeStatusReason) detail += ` (${latestStatus.chargeStatusReason})`;
      }

      events.push({
        timestamp: ts(uce.timestamp),
        displayTime: fmt(uce.timestamp),
        category: 'charge',
        title: isFail ? 'Charge attempt FAILED' : 'Processor charge',
        detail,
        customerVisible: true,
        amount: formatAmount(uce.amount),
      });
    }
  }

  // 3. Failed payment attempts not in unified events (legacy purchase-schema only)
  if (unifiedEvents.length === 0) {
    for (const attempt of snapshot.paymentAttempts) {
      if (attempt.status === 0) continue;
      const source = attempt.source != null ? (ATTEMPT_SOURCE[attempt.source] || `source ${attempt.source}`) : 'unknown';
      events.push({
        timestamp: ts(attempt.dateTime),
        displayTime: fmt(attempt.dateTime),
        category: 'charge',
        title: 'Charge attempt FAILED',
        detail: `Via ${source}. ${attempt.failMessage || 'No failure message.'}`,
        customerVisible: true,
      });
    }
  }

  // 4. Emails / notifications
  for (const email of snapshot.notifications.sent) {
    const typeName = EMAIL_TYPE_NAMES[email.type] || `Email type ${email.type}`;

    let customerNote = '';
    if (email.type === 1) customerNote = 'Customer received the loan agreement';
    if (email.type === 5) customerNote = 'Customer notified that a payment was collected';
    if (email.type === 7) customerNote = 'Customer notified the loan is fully paid off';
    if (email.type === 10) customerNote = 'Customer notified of a manual/pay-now payment';
    if (email.type === 12) customerNote = 'Customer notified that a charge failed';

    events.push({
      timestamp: ts(email.changeStatusTime),
      displayTime: fmt(email.changeStatusTime),
      category: 'notification',
      title: `Email: ${typeName}`,
      detail: customerNote || `Notification sent to customer`,
      customerVisible: true,
    });
  }

  // 4b. Missing notifications
  for (const missing of snapshot.notifications.missing) {
    events.push({
      timestamp: ts(missing.paidOffDate),
      displayTime: fmt(missing.paidOffDate),
      category: 'notification',
      title: `MISSING email: ${missing.expectedEmailType}`,
      detail: missing.description,
      customerVisible: false,
    });
  }

  // 5. Support tickets
  for (const ticket of snapshot.supportTickets) {
    events.push({
      timestamp: ts(ticket.createdAt),
      displayTime: fmt(ticket.createdAt),
      category: 'ticket',
      title: `Ticket: ${ticket.subject || ticket.category || 'Support interaction'}`,
      detail: [
        ticket.channel ? `Channel: ${ticket.channel}` : null,
        ticket.assignee ? `Agent: ${ticket.assignee}` : null,
        ticket.description ? ticket.description.substring(0, 120) : null,
      ].filter(Boolean).join('. '),
      customerVisible: true,
    });
  }

  // 6. Key payment state changes (paid-off payments not already covered by unified events)
  const paidPayments = snapshot.payments.filter(
    (p) => p.isActive && p.paidOffDate && (p.type === 0 || p.type === 10 || p.type === 20)
      && !paymentsCoveredByCharges.has(p.id)
  );
  for (const p of paidPayments) {
    const typeLabel = p.type === 0 ? 'Scheduled installment' : 'Unscheduled payment';
    events.push({
      timestamp: ts(p.paidOffDate),
      displayTime: fmt(p.paidOffDate),
      category: 'payment',
      title: `${typeLabel} paid`,
      detail: `Payment #${p.id}, due ${p.dueDate?.substring(0, 10)}`,
      customerVisible: true,
      amount: formatAmount(p.amount),
    });
  }

  // 7. Disputed / chargebacked payments
  for (const p of snapshot.payments.filter((p) => p.dispute != null)) {
    events.push({
      timestamp: ts(p.creationDate),
      displayTime: fmt(p.creationDate),
      category: 'dispute',
      title: 'Dispute raised',
      detail: `Payment #${p.id}, dispute: ${p.dispute}`,
      customerVisible: true,
      amount: formatAmount(p.amount),
    });
  }

  // Checkout.com processor actions
  for (const ca of checkoutActions) {
    if (!ca.action_date) continue;
    const isRefund = ca.action_type === 'Refund';
    events.push({
      timestamp: ca.action_date,
      displayTime: ca.action_date?.substring(0, 19) || '',
      category: isRefund ? 'refund' as EventCategory : 'charge' as EventCategory,
      title: `Checkout.com ${ca.action_type}`,
      detail: `Payment #${ca.payment_id || '?'}, ${formatAmount(ca.amount)} (source: ${ca.source || '?'})`,
      customerVisible: true,
      amount: formatAmount(ca.amount),
    });
  }

  // Sort chronologically
  events.sort((a, b) => a.timestamp.localeCompare(b.timestamp));

  return events;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

const PurchaseTimeline: React.FC<PurchaseTimelineProps> = ({ snapshot, checkoutActions = [] }) => {
  const events = useMemo(() => buildTimeline(snapshot, checkoutActions), [snapshot, checkoutActions]);
  const [showInternal, setShowInternal] = React.useState(false);

  const displayed = showInternal ? events : events.filter((e) => e.customerVisible);

  return (
    <div className="purchase-timeline">
      <div className="timeline-controls">
        <label className="filter-toggle">
          <input
            type="checkbox"
            checked={showInternal}
            onChange={(e) => setShowInternal(e.target.checked)}
          />
          Show internal events
        </label>
        <span className="table-count">
          {displayed.length} events{!showInternal ? ` (${events.length - displayed.length} internal hidden)` : ''}
        </span>
      </div>

      <div className="timeline-list">
        {displayed.map((event, i) => (
          <div
            key={i}
            className={`timeline-event ${event.customerVisible ? '' : 'timeline-internal'}`}
            style={{ borderLeftColor: CATEGORY_COLORS[event.category] }}
          >
            <div className="timeline-event-header">
              <span className="timeline-time">{event.displayTime}</span>
              <span
                className="timeline-category"
                style={{ background: CATEGORY_COLORS[event.category] }}
              >
                {event.category}
              </span>
              {event.secondaryCategory && (
                <span
                  className="timeline-category"
                  style={{ background: CATEGORY_COLORS[event.secondaryCategory] }}
                >
                  {event.secondaryCategory}
                </span>
              )}
              {event.amount && <span className="timeline-amount">{event.amount}</span>}
            </div>
            <div className="timeline-event-title">{event.title}</div>
            <div className="timeline-event-detail">{event.detail}</div>
            {event.secondaryDetail && (
              <div className="timeline-event-detail">{event.secondaryDetail}</div>
            )}
          </div>
        ))}
        {displayed.length === 0 && (
          <p className="text-muted">No events to display.</p>
        )}
      </div>
    </div>
  );
};

export default PurchaseTimeline;
