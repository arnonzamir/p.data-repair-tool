import React, { useState } from 'react';
import type { SupportTicket } from '../../types/domain';

interface TicketsTabProps {
  tickets: SupportTicket[];
}

function formatDate(value: string | null | undefined): string {
  if (!value) return '-';
  return value.substring(0, 10);
}

function truncate(text: string | null | undefined, maxLen: number): string {
  if (!text) return '-';
  if (text.length <= maxLen) return text;
  return text.substring(0, maxLen) + '...';
}

const TicketsTab: React.FC<TicketsTabProps> = ({ tickets }) => {
  const [expandedId, setExpandedId] = useState<string | null>(null);

  if (tickets.length === 0) {
    return <div className="empty-message">No support tickets found.</div>;
  }

  const toggleExpand = (id: string) => {
    setExpandedId((prev) => (prev === id ? null : id));
  };

  return (
    <div className="tickets-tab">
      <table className="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>Subject</th>
            <th>Category</th>
            <th>Channel</th>
            <th>Assignee</th>
            <th>Created</th>
            <th>Description</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {tickets.map((t) => {
            const isExpanded = expandedId === t.id;
            return (
              <React.Fragment key={t.id}>
                <tr
                  className="ticket-row"
                  onClick={() => toggleExpand(t.id)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      toggleExpand(t.id);
                    }
                  }}
                >
                  <td>{t.id}</td>
                  <td>{t.subject ?? '-'}</td>
                  <td>{t.category ?? '-'}</td>
                  <td>{t.channel ?? '-'}</td>
                  <td>{t.assignee ?? '-'}</td>
                  <td>{formatDate(t.createdAt)}</td>
                  <td>{isExpanded ? '[collapse]' : truncate(t.description, 60)}</td>
                  <td>
                    <a
                      href={`https://call-center.prod.sunbit.com/?iss=https://sunbit.okta.com#/tickets/show/${t.id}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="btn btn-tiny"
                      onClick={(e) => e.stopPropagation()}
                    >
                      Open in CC
                    </a>
                  </td>
                </tr>
                {isExpanded && t.description && (
                  <tr className="ticket-expanded">
                    <td colSpan={8}>
                      <div className="ticket-description">{t.description}</div>
                    </td>
                    <td></td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

export default TicketsTab;
