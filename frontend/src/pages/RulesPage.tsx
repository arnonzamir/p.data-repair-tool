import React, { useState, useEffect, useCallback } from 'react';
import type { RuleInfo } from '../types/domain';
import { listRules, enableRule, disableRule } from '../api/client';
import { renderMarkdownBlock } from '../components/common/RichText';

export function RulesPage() {
  const [rules, setRules] = useState<RuleInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toggling, setToggling] = useState<string | null>(null);
  const [expandedRules, setExpandedRules] = useState<Set<string>>(new Set());

  const fetchRules = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listRules();
      setRules(data);
    } catch (err: any) {
      setError(err?.message || 'Failed to fetch rules');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchRules();
  }, [fetchRules]);

  const handleToggle = async (rule: RuleInfo) => {
    setToggling(rule.ruleId);
    try {
      if (rule.enabled) {
        await disableRule(rule.ruleId);
      } else {
        await enableRule(rule.ruleId);
      }
      await fetchRules();
    } catch (err: any) {
      setError(err?.message || 'Failed to toggle rule');
    } finally {
      setToggling(null);
    }
  };

  const toggleExpand = (ruleId: string) => {
    setExpandedRules((prev) => {
      const next = new Set(prev);
      if (next.has(ruleId)) next.delete(ruleId); else next.add(ruleId);
      return next;
    });
  };

  if (loading) return <div className="loading">Loading rules...</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <div className="card">
      <div className="card-header">Analysis Rules ({rules.length})</div>
      <table className="table">
        <thead>
          <tr>
            <th>Rule ID</th>
            <th>Name</th>
            <th>Description</th>
            <th className="text-center">Enabled</th>
          </tr>
        </thead>
        <tbody>
          {rules.map((rule) => {
            const isExpanded = expandedRules.has(rule.ruleId);
            const hasDetail = rule.detailedDescription && rule.detailedDescription !== rule.description;
            return (
              <React.Fragment key={rule.ruleId}>
                <tr>
                  <td className="mono">{rule.ruleId}</td>
                  <td>{rule.ruleName}</td>
                  <td>
                    <span className="text-muted">{rule.description}</span>
                    {hasDetail && (
                      <div style={{ marginTop: 4 }}>
                        <button
                          className="rule-explanation-toggle"
                          onClick={() => toggleExpand(rule.ruleId)}
                        >
                          {isExpanded ? 'Hide details' : 'How does this rule work?'}
                        </button>
                      </div>
                    )}
                  </td>
                  <td className="text-center">
                    <button
                      className={`toggle-btn ${rule.enabled ? 'toggle-enabled' : 'toggle-disabled'}`}
                      onClick={() => handleToggle(rule)}
                      disabled={toggling === rule.ruleId}
                    >
                      {toggling === rule.ruleId
                        ? '...'
                        : rule.enabled
                        ? 'Enabled'
                        : 'Disabled'}
                    </button>
                  </td>
                </tr>
                {isExpanded && hasDetail && (
                  <tr>
                    <td colSpan={4} style={{ padding: 0 }}>
                      <div className="rule-explanation-content" style={{ margin: '0 16px 12px 16px' }}>
                        {renderMarkdownBlock(rule.detailedDescription!)}
                      </div>
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
          {rules.length === 0 && (
            <tr>
              <td colSpan={4} className="text-center text-muted">
                No rules configured.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
