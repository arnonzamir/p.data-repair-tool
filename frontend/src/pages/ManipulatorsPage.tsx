import React, { useState, useEffect, useCallback } from 'react';
import { listManipulators, enableManipulator, disableManipulator, ManipulatorInfo } from '../api/client';
import { renderRichText, renderMarkdownBlock } from '../components/common/RichText';

export function ManipulatorsPage() {
  const [manipulators, setManipulators] = useState<ManipulatorInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [toggling, setToggling] = useState<string | null>(null);

  const fetchManipulators = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listManipulators();
      setManipulators(data);
    } catch (err: any) {
      setError(err?.message || 'Failed to fetch manipulators');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchManipulators();
  }, [fetchManipulators]);

  const handleToggle = async (m: ManipulatorInfo) => {
    setToggling(m.id);
    try {
      if (m.enabled) {
        await disableManipulator(m.id);
      } else {
        await enableManipulator(m.id);
      }
      await fetchManipulators();
    } catch (err: any) {
      setError(err?.message || 'Failed to toggle manipulator');
    } finally {
      setToggling(null);
    }
  };

  const toggleExpand = (id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  if (loading) return <div className="loading">Loading manipulators...</div>;
  if (error) return <div className="error-banner">{error}</div>;

  // Group by category
  const byCategory: Record<string, ManipulatorInfo[]> = {};
  for (const m of manipulators) {
    const cat = m.category || 'OTHER';
    if (!byCategory[cat]) byCategory[cat] = [];
    byCategory[cat].push(m);
  }

  const categoryOrder = ['STRUCTURAL', 'FINANCIAL', 'REMEDIATION', 'CHARGEBACK', 'OTHER'];
  const categoryLabels: Record<string, string> = {
    STRUCTURAL: 'Structural Fixes',
    FINANCIAL: 'Financial Operations',
    REMEDIATION: 'Incident Remediation',
    CHARGEBACK: 'Chargeback Lifecycle',
    OTHER: 'Other',
  };

  return (
    <div>
      <div className="card" style={{ marginBottom: 16 }}>
        <div className="card-header">Loan Manipulators ({manipulators.length})</div>
        <p style={{ fontSize: 13, color: '#546e7a', margin: '8px 0 0 0' }}>
          Manipulators are composable fix operations that can be run against a purchase
          from the "Fix" tab. Each manipulator checks preconditions, previews its actions,
          executes against a target environment (LOCAL/STAGING/PROD), and verifies the result.
        </p>
      </div>

      {categoryOrder.map((cat) => {
        const items = byCategory[cat];
        if (!items || items.length === 0) return null;
        return (
          <div key={cat} style={{ marginBottom: 24 }}>
            <h3 style={{ margin: '0 0 8px 0' }}>
              <span className={`badge badge-category-${cat.toLowerCase()}`}>{cat}</span>
              {' '}{categoryLabels[cat] || cat} ({items.length})
            </h3>
            <table className="table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Name</th>
                  <th>Description</th>
                  <th>Parameters</th>
                  <th className="text-center">Status</th>
                </tr>
              </thead>
              <tbody>
                {items.map((m) => {
                  const isExpanded = expandedIds.has(m.id);
                  const hasDetail = m.detailedDescription && m.detailedDescription !== m.description;
                  return (
                    <React.Fragment key={m.id}>
                      <tr>
                        <td className="mono" style={{ fontSize: 12 }}>{m.id}</td>
                        <td><strong>{m.name}</strong></td>
                        <td>
                          <span style={{ fontSize: 12, color: '#546e7a' }}>{renderRichText(m.description)}</span>
                          {hasDetail && (
                            <div style={{ marginTop: 4 }}>
                              <button
                                className="rule-explanation-toggle"
                                onClick={() => toggleExpand(m.id)}
                              >
                                {isExpanded ? 'Hide details' : 'How does this work?'}
                              </button>
                            </div>
                          )}
                        </td>
                        <td style={{ fontSize: 11 }}>
                          {m.requiredParams.length > 0 ? (
                            m.requiredParams.map((p) => (
                              <div key={p.name} style={{ marginBottom: 2 }}>
                                <span className="mono">{p.name}</span>
                                <span style={{ color: '#9e9e9e' }}>
                                  {' '}({p.type.toLowerCase()}{p.required ? ', required' : ''})
                                </span>
                              </div>
                            ))
                          ) : (
                            <span style={{ color: '#9e9e9e' }}>none</span>
                          )}
                        </td>
                        <td className="text-center">
                          <button
                            className={`toggle-btn ${m.enabled ? 'toggle-enabled' : 'toggle-disabled'}`}
                            onClick={() => handleToggle(m)}
                            disabled={toggling === m.id}
                          >
                            {toggling === m.id ? '...' : m.enabled ? 'Enabled' : 'Disabled'}
                          </button>
                        </td>
                      </tr>
                      {isExpanded && hasDetail && (
                        <tr>
                          <td colSpan={5} style={{ padding: 0 }}>
                            <div className="rule-explanation-content" style={{ margin: '0 16px 12px 16px' }}>
                              {renderMarkdownBlock(m.detailedDescription!)}
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        );
      })}
    </div>
  );
}
