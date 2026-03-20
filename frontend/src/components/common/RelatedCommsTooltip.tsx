import React from 'react';
import type { NotificationSummary, NotificationRecord, SupportTicket } from '../../types/domain';

/**
 * Finds emails and tickets related to a given event timestamp (within a time window).
 * Used to show "related communications" on hover for chargebacks, refunds, disputes, etc.
 */

const EMAIL_TYPE_NAMES: Record<number, string> = {
  1: 'Agreement', 3: 'Agreement Draft', 4: 'Retailer Charge Notification',
  5: 'Payment Received', 7: 'Paid Off', 9: 'Payment Received (Late)',
  10: 'Pay Now Payment', 12: 'Charge Failed',
};

interface RelatedItem {
  kind: 'email' | 'ticket';
  id: number | string;
  label: string;
  time: string;
  callCenterUrl?: string;
}

const CC_BASE = 'https://call-center.prod.sunbit.com/?iss=https://sunbit.okta.com#';

/** Find emails and tickets within windowHours of the given ISO timestamp. */
export function findRelatedComms(
  timestamp: string | null | undefined,
  notifications: NotificationSummary,
  tickets: SupportTicket[],
  windowHours: number = 48,
  purchaseId?: number,
): RelatedItem[] {
  if (!timestamp) return [];
  const eventTime = new Date(timestamp).getTime();
  if (isNaN(eventTime)) return [];
  const windowMs = windowHours * 3600 * 1000;
  const items: RelatedItem[] = [];

  for (const email of notifications.sent) {
    if (!email.changeStatusTime) continue;
    const emailTime = new Date(email.changeStatusTime).getTime();
    if (isNaN(emailTime)) continue;
    if (Math.abs(emailTime - eventTime) <= windowMs) {
      const notifId = (email as any).notificationId;
      items.push({
        kind: 'email',
        id: email.id,
        label: (email as any).subject || EMAIL_TYPE_NAMES[email.type] || `Email type ${email.type}`,
        time: email.changeStatusTime.substring(0, 19).replace('T', ' '),
        callCenterUrl: purchaseId
          ? (notifId ? `${CC_BASE}/email/purchases/${purchaseId}/${notifId}` : `${CC_BASE}/purchases/show/${purchaseId}/emails`)
          : undefined,
      });
    }
  }

  for (const ticket of tickets) {
    if (!ticket.createdAt) continue;
    const ticketTime = new Date(ticket.createdAt).getTime();
    if (isNaN(ticketTime)) continue;
    if (Math.abs(ticketTime - eventTime) <= windowMs) {
      items.push({
        kind: 'ticket',
        id: ticket.id,
        label: ticket.subject || ticket.category || 'Support ticket',
        time: ticket.createdAt.substring(0, 19).replace('T', ' '),
        callCenterUrl: `${CC_BASE}/tickets/show/${ticket.id}`,
      });
    }
  }

  items.sort((a, b) => a.time.localeCompare(b.time));
  return items;
}

interface RelatedCommsTooltipProps {
  items: RelatedItem[];
  onNavigate: (tab: 'notifications' | 'tickets', itemId: number | string) => void;
  children: React.ReactNode;
}

/**
 * Wraps children in a hover tooltip that shows related emails and tickets.
 * If no related items, renders children as-is (no tooltip).
 */
const RelatedCommsTooltip: React.FC<RelatedCommsTooltipProps> = ({ items, onNavigate, children }) => {
  const [show, setShow] = React.useState(false);
  const hideTimeout = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  if (items.length === 0) return <>{children}</>;

  const handleEnter = () => {
    if (hideTimeout.current) { clearTimeout(hideTimeout.current); hideTimeout.current = null; }
    setShow(true);
  };
  const handleLeave = () => {
    hideTimeout.current = setTimeout(() => setShow(false), 200);
  };

  return (
    <span
      className="related-comms-wrapper"
      onMouseEnter={handleEnter}
      onMouseLeave={handleLeave}
    >
      <span className="related-comms-indicator">{children}</span>
      {show && (
        <div className="related-comms-tooltip">
          <div className="related-comms-header">Related Communications ({items.length})</div>
          {items.map((item, i) => (
            <div
              key={`${item.kind}-${item.id}-${i}`}
              className="related-comms-item"
              onClick={(e) => {
                e.stopPropagation();
                onNavigate(item.kind === 'email' ? 'notifications' : 'tickets', item.id);
              }}
            >
              <span className={`related-comms-kind related-comms-${item.kind}`}>
                {item.kind === 'email' ? 'Email' : 'Ticket'}
              </span>
              <span className="related-comms-label">{item.label}</span>
              <span className="related-comms-time">{item.time}</span>
              {item.callCenterUrl && (
                <a
                  href={item.callCenterUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="related-comms-cc-link"
                  onClick={(e) => e.stopPropagation()}
                >
                  CC
                </a>
              )}
            </div>
          ))}
        </div>
      )}
    </span>
  );
};

export default RelatedCommsTooltip;
