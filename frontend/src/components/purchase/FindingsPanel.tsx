import React, { useState, useMemo } from 'react';
import type { Finding, SuggestedRepair, Severity } from '../../types/domain';

interface FindingsPanelProps {
  findings: Finding[];
  onSelectRepair: (repair: SuggestedRepair, finding: Finding) => void;
}

const SEVERITY_ORDER: Record<Severity, number> = {
  CRITICAL: 0,
  HIGH: 1,
  MEDIUM: 2,
  LOW: 3,
};

function severityClass(severity: Severity): string {
  return `severity-${severity.toLowerCase()}`;
}

const FindingsPanel: React.FC<FindingsPanelProps> = ({ findings, onSelectRepair }) => {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  const grouped = useMemo(() => {
    const sorted = [...findings].sort(
      (a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity]
    );
    const groups: Record<Severity, Finding[]> = {
      CRITICAL: [],
      HIGH: [],
      MEDIUM: [],
      LOW: [],
    };
    for (const f of sorted) {
      groups[f.severity].push(f);
    }
    return groups;
  }, [findings]);

  const toggleExpand = (ruleId: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(ruleId)) {
        next.delete(ruleId);
      } else {
        next.add(ruleId);
      }
      return next;
    });
  };

  if (findings.length === 0) {
    return <div className="findings-empty">No findings detected.</div>;
  }

  const severities: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

  return (
    <div className="findings-panel">
      {severities.map((sev) => {
        const items = grouped[sev];
        if (items.length === 0) return null;
        return (
          <div key={sev} className="findings-group">
            <h4 className="findings-group-header">
              <span className={`badge ${severityClass(sev)}`}>{sev}</span>
              <span className="findings-group-count">{items.length} finding{items.length !== 1 ? 's' : ''}</span>
            </h4>
            {items.map((f) => {
              const key = `${f.ruleId}-${f.affectedPaymentIds.join(',')}`;
              const isExpanded = expandedIds.has(key);
              return (
                <div key={key} className="finding-card card">
                  <div
                    className="finding-header"
                    onClick={() => toggleExpand(key)}
                    role="button"
                    tabIndex={0}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        toggleExpand(key);
                      }
                    }}
                    aria-expanded={isExpanded}
                  >
                    <span className={`badge ${severityClass(f.severity)}`}>{f.severity}</span>
                    <span className="finding-rule">{f.ruleName}</span>
                    <span className="finding-expand-indicator">{isExpanded ? '[-]' : '[+]'}</span>
                  </div>
                  <p className="finding-description">{f.description}</p>

                  {isExpanded && (
                    <div className="finding-details">
                      {/* Evidence */}
                      {f.evidence && Object.keys(f.evidence).length > 0 && (
                        <div className="finding-evidence">
                          <h5>Evidence</h5>
                          <table className="table table-compact">
                            <thead>
                              <tr>
                                <th>Key</th>
                                <th>Value</th>
                              </tr>
                            </thead>
                            <tbody>
                              {Object.entries(f.evidence).map(([k, v]) => (
                                <tr key={k}>
                                  <td className="evidence-key">{k}</td>
                                  <td className="evidence-value">
                                    {typeof v === 'object' ? JSON.stringify(v) : String(v ?? '-')}
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}

                      {/* Affected Payment IDs */}
                      {f.affectedPaymentIds.length > 0 && (
                        <div className="finding-affected">
                          <h5>Affected Payment IDs</h5>
                          <div className="affected-ids">
                            {f.affectedPaymentIds.map((pid) => (
                              <span key={pid} className="badge badge-id">{pid}</span>
                            ))}
                          </div>
                        </div>
                      )}

                      {/* Suggested Repairs */}
                      {f.suggestedRepairs && f.suggestedRepairs.length > 0 && (
                        <div className="finding-repairs">
                          <h5>Suggested Repairs</h5>
                          <div className="repair-buttons">
                            {f.suggestedRepairs.map((r, idx) => (
                              <button
                                key={idx}
                                className="btn btn-primary"
                                onClick={() => onSelectRepair(r, f)}
                              >
                                {r.action} - {r.description}
                              </button>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        );
      })}
    </div>
  );
};

export default FindingsPanel;
