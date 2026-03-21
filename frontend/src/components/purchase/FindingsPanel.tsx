import React, { useState, useMemo } from 'react';
import type { Finding, SuggestedRepair, Severity, RuleExecutionResult } from '../../types/domain';
import { renderRichText, renderMarkdownBlock } from '../common/RichText';

interface FindingsPanelProps {
  findings: Finding[];
  ruleResults?: RuleExecutionResult[];
  onSelectRepair: (repair: SuggestedRepair, finding: Finding) => void;
  onRescan?: () => void;
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

const FindingsPanel: React.FC<FindingsPanelProps> = ({ findings, ruleResults = [], onSelectRepair, onRescan }) => {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [expandedRules, setExpandedRules] = useState<Set<string>>(new Set());

  const ruleDescriptions = useMemo(() => {
    const map: Record<string, string> = {};
    for (const r of ruleResults) {
      if (r.detailedDescription) map[r.ruleId] = r.detailedDescription;
    }
    return map;
  }, [ruleResults]);

  const toggleRuleExpand = (ruleId: string) => {
    setExpandedRules((prev) => {
      const next = new Set(prev);
      if (next.has(ruleId)) next.delete(ruleId); else next.add(ruleId);
      return next;
    });
  };

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
    return (
      <div className="findings-empty">
        No findings detected.
        {onRescan && (
          <button className="btn btn-rescan" style={{ marginLeft: 12 }} onClick={onRescan}>
            Rescan for issues
          </button>
        )}
      </div>
    );
  }

  const severities: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

  return (
    <div className="findings-panel">
      {onRescan && (
        <div style={{ marginBottom: 12 }}>
          <button className="btn btn-rescan" onClick={onRescan}>
            Rescan for issues
          </button>
        </div>
      )}
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
                  <div className="finding-description">{renderMarkdownBlock(f.description)}</div>

                  {ruleDescriptions[f.ruleId] && (
                    <div className="rule-explanation">
                      <button
                        className="rule-explanation-toggle"
                        onClick={(e) => { e.stopPropagation(); toggleRuleExpand(f.ruleId); }}
                      >
                        {expandedRules.has(f.ruleId) ? 'Hide rule logic' : 'How does this rule work?'}
                      </button>
                      {expandedRules.has(f.ruleId) && (
                        <div className="rule-explanation-content">
                          {renderMarkdownBlock(ruleDescriptions[f.ruleId])}
                        </div>
                      )}
                    </div>
                  )}

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
