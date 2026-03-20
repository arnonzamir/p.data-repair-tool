import React, { useMemo } from 'react';
import type { PaymentAction, PaymentAttempt } from '../../types/domain';

interface ActionsTimelineProps {
  actions: PaymentAction[];
  attempts: PaymentAttempt[];
}

interface TimelineEntry {
  timestamp: string;
  kind: 'action' | 'attempt';
  action?: PaymentAction;
  attempt?: PaymentAttempt;
}

function formatTimestamp(value: string | null | undefined): string {
  if (!value) return '-';
  return value.substring(0, 19).replace('T', ' ');
}

const ActionsTimeline: React.FC<ActionsTimelineProps> = ({ actions, attempts }) => {
  const timeline = useMemo(() => {
    const entries: TimelineEntry[] = [];

    for (const a of actions) {
      entries.push({
        timestamp: a.timeOfAction || '',
        kind: 'action',
        action: a,
      });
    }

    for (const att of attempts) {
      entries.push({
        timestamp: att.dateTime || '',
        kind: 'attempt',
        attempt: att,
      });
    }

    entries.sort((a, b) => {
      if (!a.timestamp && !b.timestamp) return 0;
      if (!a.timestamp) return 1;
      if (!b.timestamp) return -1;
      return a.timestamp.localeCompare(b.timestamp);
    });

    return entries;
  }, [actions, attempts]);

  if (timeline.length === 0) {
    return <div className="empty-message">No actions or attempts recorded.</div>;
  }

  return (
    <div className="actions-timeline">
      <table className="table">
        <thead>
          <tr>
            <th>Timestamp</th>
            <th>Kind</th>
            <th>Type / Source</th>
            <th>Details</th>
          </tr>
        </thead>
        <tbody>
          {timeline.map((entry, idx) => {
            if (entry.kind === 'action' && entry.action) {
              const a = entry.action;
              return (
                <tr key={`action-${a.id}-${idx}`} className="timeline-action">
                  <td>{formatTimestamp(a.timeOfAction)}</td>
                  <td><span className="badge badge-action">Action</span></td>
                  <td>{a.typeName ?? `Type ${a.type}`}</td>
                  <td>Action ID: {a.id}</td>
                </tr>
              );
            }

            if (entry.kind === 'attempt' && entry.attempt) {
              const att = entry.attempt;
              return (
                <tr key={`attempt-${att.id}-${idx}`} className="timeline-attempt">
                  <td>{formatTimestamp(att.dateTime)}</td>
                  <td><span className="badge badge-attempt">Attempt</span></td>
                  <td>{att.sourceName ?? (att.source != null ? `Source ${att.source}` : '-')}</td>
                  <td>
                    <span>Payment: {att.paymentId}</span>
                    {att.status != null && <span> | Status: {att.status}</span>}
                    {att.failMessage && <span className="text-fail"> | Fail: {att.failMessage}</span>}
                  </td>
                </tr>
              );
            }

            return null;
          })}
        </tbody>
      </table>
    </div>
  );
};

export default ActionsTimeline;
