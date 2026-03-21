import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import type { AuditEntry } from '../types/domain';
import { getRecentAudit } from '../api/client';

type SortKey = 'timestamp' | 'operator' | 'purchaseId' | 'action' | 'durationMs';
type SortDir = 'asc' | 'desc';

const PAGE_SIZE = 50;

export function AuditPage() {
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // Filters
  const [filterOperator, setFilterOperator] = useState('');
  const [filterPurchaseId, setFilterPurchaseId] = useState('');
  const [filterAction, setFilterAction] = useState('');

  // Sorting
  const [sortKey, setSortKey] = useState<SortKey>('timestamp');
  const [sortDir, setSortDir] = useState<SortDir>('desc');

  const sentinelRef = useRef<HTMLDivElement>(null);

  const fetchPage = useCallback(async (offset: number, append: boolean) => {
    if (append) setLoadingMore(true); else setLoading(true);
    setError(null);
    try {
      const data = await getRecentAudit(PAGE_SIZE, offset);
      if (append) {
        setEntries(prev => [...prev, ...data]);
      } else {
        setEntries(data);
      }
      setHasMore(data.length === PAGE_SIZE);
    } catch (err: any) {
      setError(err?.message || 'Failed to fetch audit entries');
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  }, []);

  useEffect(() => {
    fetchPage(0, false);
  }, [fetchPage]);

  // Infinite scroll via IntersectionObserver
  useEffect(() => {
    if (!sentinelRef.current || !hasMore || loadingMore) return;
    const observer = new IntersectionObserver(
      (ents) => {
        if (ents[0].isIntersecting && hasMore && !loadingMore) {
          fetchPage(entries.length, true);
        }
      },
      { threshold: 0.1 },
    );
    observer.observe(sentinelRef.current);
    return () => observer.disconnect();
  }, [hasMore, loadingMore, entries.length, fetchPage]);

  // Unique values for action filter dropdown
  const actionValues = useMemo(() => {
    const actions = new Set(entries.map(e => e.action));
    return Array.from(actions).sort();
  }, [entries]);

  const operatorValues = useMemo(() => {
    const ops = new Set(entries.map(e => e.operator).filter(Boolean));
    return Array.from(ops).sort() as string[];
  }, [entries]);

  // Filter + sort
  const filtered = useMemo(() => {
    let result = entries;

    if (filterOperator) {
      result = result.filter(e => e.operator?.toLowerCase().includes(filterOperator.toLowerCase()));
    }
    if (filterPurchaseId) {
      result = result.filter(e => String(e.purchaseId).includes(filterPurchaseId));
    }
    if (filterAction) {
      result = result.filter(e => e.action === filterAction);
    }

    result = [...result].sort((a, b) => {
      let cmp = 0;
      switch (sortKey) {
        case 'timestamp':
          cmp = (a.timestamp || '').localeCompare(b.timestamp || '');
          break;
        case 'operator':
          cmp = (a.operator || '').localeCompare(b.operator || '');
          break;
        case 'purchaseId':
          cmp = a.purchaseId - b.purchaseId;
          break;
        case 'action':
          cmp = a.action.localeCompare(b.action);
          break;
        case 'durationMs':
          cmp = (a.durationMs || 0) - (b.durationMs || 0);
          break;
      }
      return sortDir === 'asc' ? cmp : -cmp;
    });

    return result;
  }, [entries, filterOperator, filterPurchaseId, filterAction, sortKey, sortDir]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir(prev => prev === 'asc' ? 'desc' : 'asc');
    } else {
      setSortKey(key);
      setSortDir(key === 'timestamp' ? 'desc' : 'asc');
    }
  };

  const sortIndicator = (key: SortKey) => {
    if (sortKey !== key) return '';
    return sortDir === 'asc' ? ' [asc]' : ' [desc]';
  };

  const toggleExpand = (id: string) => {
    setExpandedId((prev) => (prev === id ? null : id));
  };

  if (loading) return <div className="loading">Loading audit log...</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <div className="card">
      <div className="card-header">
        Audit Log ({filtered.length} of {entries.length} loaded)
      </div>

      {/* Filters */}
      <div style={{ display: 'flex', gap: 12, padding: '8px 12px', background: '#fafafa', borderBottom: '1px solid #e0e0e0', alignItems: 'center', flexWrap: 'wrap' }}>
        <div style={{ fontSize: 12, color: '#757575' }}>Filter:</div>
        <select
          value={filterOperator}
          onChange={e => setFilterOperator(e.target.value)}
          style={{ fontSize: 12, padding: '2px 6px' }}
        >
          <option value="">All operators</option>
          {operatorValues.map(op => <option key={op} value={op}>{op}</option>)}
        </select>
        <input
          type="text"
          value={filterPurchaseId}
          onChange={e => setFilterPurchaseId(e.target.value)}
          placeholder="Purchase ID"
          style={{ fontSize: 12, padding: '2px 6px', width: 120 }}
        />
        <select
          value={filterAction}
          onChange={e => setFilterAction(e.target.value)}
          style={{ fontSize: 12, padding: '2px 6px' }}
        >
          <option value="">All actions</option>
          {actionValues.map(a => <option key={a} value={a}>{a}</option>)}
        </select>
        {(filterOperator || filterPurchaseId || filterAction) && (
          <button
            className="btn btn-tiny"
            onClick={() => { setFilterOperator(''); setFilterPurchaseId(''); setFilterAction(''); }}
          >
            Clear filters
          </button>
        )}
      </div>

      <table className="table">
        <thead>
          <tr>
            <th className="sortable-header" onClick={() => toggleSort('timestamp')} style={{ cursor: 'pointer' }}>
              Timestamp{sortIndicator('timestamp')}
            </th>
            <th className="sortable-header" onClick={() => toggleSort('operator')} style={{ cursor: 'pointer' }}>
              Operator{sortIndicator('operator')}
            </th>
            <th className="sortable-header" onClick={() => toggleSort('purchaseId')} style={{ cursor: 'pointer' }}>
              Purchase ID{sortIndicator('purchaseId')}
            </th>
            <th className="sortable-header" onClick={() => toggleSort('action')} style={{ cursor: 'pointer' }}>
              Action{sortIndicator('action')}
            </th>
            <th className="sortable-header text-right" onClick={() => toggleSort('durationMs')} style={{ cursor: 'pointer' }}>
              Duration (ms){sortIndicator('durationMs')}
            </th>
          </tr>
        </thead>
        <tbody>
          {filtered.map((entry) => (
            <React.Fragment key={entry.id}>
              <tr
                className="clickable-row"
                onClick={() => toggleExpand(entry.id)}
              >
                <td className="mono">{formatTimestamp(entry.timestamp)}</td>
                <td>{entry.operator || '-'}</td>
                <td className="mono">{entry.purchaseId}</td>
                <td>{entry.action}</td>
                <td className="text-right mono">
                  {entry.durationMs != null ? entry.durationMs.toLocaleString() : '-'}
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
          {filtered.length === 0 && (
            <tr>
              <td colSpan={5} className="text-center text-muted">
                {entries.length === 0 ? 'No audit entries found.' : 'No entries match filters.'}
              </td>
            </tr>
          )}
        </tbody>
      </table>

      {/* Infinite scroll sentinel */}
      <div ref={sentinelRef} style={{ height: 1 }} />
      {loadingMore && (
        <div style={{ textAlign: 'center', padding: 12, fontSize: 13, color: '#757575' }}>
          Loading more entries...
        </div>
      )}
      {!hasMore && entries.length > 0 && (
        <div style={{ textAlign: 'center', padding: 12, fontSize: 12, color: '#9e9e9e' }}>
          All {entries.length} entries loaded.
        </div>
      )}
    </div>
  );
}

function formatTimestamp(val: string | null | undefined): string {
  if (!val) return '-';
  try {
    return new Date(val).toLocaleString();
  } catch {
    return val;
  }
}
