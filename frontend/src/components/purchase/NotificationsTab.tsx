import React, { useState } from 'react';
import type { NotificationSummary, NotificationRecord } from '../../types/domain';

interface NotificationsTabProps {
  notifications: NotificationSummary;
  purchaseId: number;
}

function formatDate(value: string | null | undefined): string {
  if (!value) return '-';
  return value.substring(0, 19).replace('T', ' ');
}

// Email type -> recipient mapping based on the knowledge base
const EMAIL_RECIPIENTS: Record<number, string> = {
  1: 'Customer',          // Agreement
  3: 'Customer',          // Agreement_Draft
  4: 'Retailer',          // Retailer_Charge_Notification
  5: 'Customer',          // OnSchedule (payment received)
  7: 'Customer',          // PaidOff
  9: 'Customer',          // PaidPaymentLatePurchase
  10: 'Customer',         // PayNowPayment
  12: 'Customer',         // ChargeFailed
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

function callCenterEmailUrl(purchaseId: number, notificationId?: string): string {
  if (notificationId) {
    return `https://call-center.prod.sunbit.com/?iss=https://sunbit.okta.com#/email/purchases/${purchaseId}/${notificationId}`;
  }
  return `https://call-center.prod.sunbit.com/?iss=https://sunbit.okta.com#/purchases/show/${purchaseId}/emails`;
}

function callCenterTicketUrl(ticketId: string | number): string {
  return `https://call-center.prod.sunbit.com/?iss=https://sunbit.okta.com#/tickets/show/${ticketId}`;
}

function EmailDetailPanel({ email, purchaseId }: { email: NotificationRecord; purchaseId: number }) {
  const recipient = EMAIL_RECIPIENTS[email.type] || 'Unknown';
  const typeName = EMAIL_TYPE_NAMES[email.type] || `Type ${email.type}`;

  return (
    <div className="email-detail-panel">
      <div className="email-detail-fields">
        <div className="email-detail-field">
          <span className="email-label">Type:</span>
          <span>{typeName}</span>
        </div>
        {email.subject && (
          <div className="email-detail-field">
            <span className="email-label">Subject:</span>
            <span>{email.subject}</span>
          </div>
        )}
        <div className="email-detail-field">
          <span className="email-label">Recipient:</span>
          <span className={`badge badge-recipient-${recipient.toLowerCase()}`}>{recipient}</span>
          {email.recipientAddress && (
            <span className="mono" style={{ marginLeft: 4, fontSize: 12 }}>{email.recipientAddress}</span>
          )}
        </div>
        {email.templateName && (
          <div className="email-detail-field">
            <span className="email-label">Template:</span>
            <span className="mono">{email.templateName}</span>
          </div>
        )}
        <div className="email-detail-field">
          <span className="email-label">Sent:</span>
          <span>{formatDate(email.changeStatusTime)}</span>
        </div>
        <div className="email-detail-field">
          <span className="email-label">Status:</span>
          <span>{email.status ?? '-'}</span>
        </div>
        {email.purchaseStatusOnSend != null && (
          <div className="email-detail-field">
            <span className="email-label">Purchase status at send:</span>
            <span>{email.purchaseStatusOnSend}</span>
          </div>
        )}
      </div>
      <div className="email-detail-actions">
        <a
          href={callCenterEmailUrl(purchaseId, email.notificationId ?? undefined)}
          target="_blank"
          rel="noopener noreferrer"
          className="btn btn-small"
        >
          View in Call Center
        </a>
      </div>
    </div>
  );
}

const NotificationsTab: React.FC<NotificationsTabProps> = ({ notifications, purchaseId }) => {
  const { sent, missing, erroneous } = notifications;
  const [expandedId, setExpandedId] = useState<number | null>(null);

  return (
    <div className="notifications-tab">
      {/* Sent */}
      <section className="notifications-section">
        <h3>Sent ({sent.length})</h3>
        {sent.length > 0 ? (
          <table className="table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Type</th>
                <th>Recipient</th>
                <th>Status</th>
                <th>Timestamp</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {sent.map((n) => (
                <React.Fragment key={n.id}>
                  <tr
                    className={`clickable-row ${expandedId === n.id ? 'row-expanded' : ''}`}
                    onClick={() => setExpandedId(expandedId === n.id ? null : n.id)}
                  >
                    <td className="mono">{n.id}</td>
                    <td>{n.typeName ?? EMAIL_TYPE_NAMES[n.type] ?? n.type}</td>
                    <td>
                      <span className={`badge badge-recipient-${(EMAIL_RECIPIENTS[n.type] || 'unknown').toLowerCase()}`}>
                        {EMAIL_RECIPIENTS[n.type] || '?'}
                      </span>
                    </td>
                    <td>{n.status ?? '-'}</td>
                    <td className="mono">{formatDate(n.changeStatusTime)}</td>
                    <td>
                      <a
                        href={callCenterEmailUrl(purchaseId, n.notificationId ?? undefined)}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="btn btn-tiny"
                        onClick={(e) => e.stopPropagation()}
                      >
                        {n.notificationId ? 'Open in CC' : 'Emails in CC'}
                      </a>
                    </td>
                  </tr>
                  {expandedId === n.id && (
                    <tr className="detail-row">
                      <td colSpan={6}>
                        <EmailDetailPanel email={n} purchaseId={purchaseId} />
                      </td>
                    </tr>
                  )}
                </React.Fragment>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="empty-message">No sent notifications.</p>
        )}
      </section>

      {/* Missing */}
      <section className="notifications-section">
        <h3>Missing ({missing.length})</h3>
        {missing.length > 0 ? (
          <table className="table table-missing">
            <thead>
              <tr>
                <th>Payment ID</th>
                <th>Expected Email</th>
                <th>Expected Recipient</th>
                <th>Description</th>
              </tr>
            </thead>
            <tbody>
              {missing.map((m, idx) => (
                <tr key={idx} className="row-missing">
                  <td className="mono">{m.paymentId}</td>
                  <td>{m.expectedEmailType}</td>
                  <td>Customer</td>
                  <td>{m.description}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="empty-message">No missing notifications.</p>
        )}
      </section>

      {/* Erroneous */}
      <section className="notifications-section">
        <h3>Erroneous ({erroneous.length})</h3>
        {erroneous.length > 0 ? (
          <table className="table table-erroneous">
            <thead>
              <tr>
                <th>Notification ID</th>
                <th>Type</th>
                <th>Recipient</th>
                <th>Reason</th>
              </tr>
            </thead>
            <tbody>
              {erroneous.map((e) => (
                <tr key={e.notificationId} className="row-erroneous">
                  <td className="mono">{e.notificationId}</td>
                  <td>{e.typeName ?? e.type}</td>
                  <td>{EMAIL_RECIPIENTS[e.type] || '?'}</td>
                  <td>{e.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="empty-message">No erroneous notifications.</p>
        )}
      </section>
    </div>
  );
};

export default NotificationsTab;
