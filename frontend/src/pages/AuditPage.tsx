import React, { useState, useEffect, useCallback } from 'react';
import type { AuditEntry } from '../types/domain';
import { getRecentAudit } from '../api/client';

export function AuditPage() {
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const fetchAudit = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getRecentAudit(50);
      setEntries(data);
    } catch (err: any) {
      setError(err?.message || 'Failed to fetch audit entries');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAudit();
  }, [fetchAudit]);

  const toggleExpand = (id: string) => {
    setExpandedId((prev) => (prev === id ? null : id));
  };

  if (loading) return <div className="loading">Loading audit log...</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <div className="card">
      <div className="card-header">Recent Audit Entries ({entries.length})</div>
      <table>
        <thead>
          <tr>
            <th>Timestamp</th>
            <th>Operator</th>
            <th>Purchase ID</th>
            <th>Action</th>
            <th className="text-right">Duration (ms)</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <React.Fragment key={entry.id}>
              <tr
                className="clickable-row"
                onClick={() => toggleExpand(entry.id)}
              >
                <td className="mono">{formatTimestamp(entry.timestamp)}</td>
                <td>{entry.operator || '--'}</td>
                <td className="mono">{entry.purchaseId}</td>
                <td>{entry.action}</td>
                <td className="text-right mono">
                  {entry.durationMs != null ? entry.durationMs.toLocaleString() : '--'}
                </td>
              </tr>
              {expandedId === entry.id && (
                <tr className="expandable-row">
                  <td colSpan={5}>
                    <div className="expand-content">
                      {entry.input != null && (
                        <div className="mb-16">
                          <strong className="text-small">Input:</strong>
                          <pre className="code-block mt-8">
                            {JSON.stringify(entry.input, null, 2)}
                          </pre>
                        </div>
                      )}
                      {entry.output != null && (
                        <div>
                          <strong className="text-small">Output:</strong>
                          <pre className="code-block mt-8">
                            {JSON.stringify(entry.output, null, 2)}
                          </pre>
                        </div>
                      )}
                      {entry.input == null && entry.output == null && (
                        <span className="text-muted">No input/output data recorded.</span>
                      )}
                    </div>
                  </td>
                </tr>
              )}
            </React.Fragment>
          ))}
          {entries.length === 0 && (
            <tr>
              <td colSpan={5} className="text-center text-muted">
                No audit entries found.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function formatTimestamp(val: string | null | undefined): string {
  if (!val) return '--';
  try {
    return new Date(val).toLocaleString();
  } catch {
    return val;
  }
}
