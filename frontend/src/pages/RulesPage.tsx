import React, { useState, useEffect, useCallback } from 'react';
import type { RuleInfo } from '../types/domain';
import { listRules, enableRule, disableRule } from '../api/client';

export function RulesPage() {
  const [rules, setRules] = useState<RuleInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toggling, setToggling] = useState<string | null>(null);

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

  if (loading) return <div className="loading">Loading rules...</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <div className="card">
      <div className="card-header">Analysis Rules ({rules.length})</div>
      <table>
        <thead>
          <tr>
            <th>Rule ID</th>
            <th>Name</th>
            <th>Description</th>
            <th className="text-center">Enabled</th>
          </tr>
        </thead>
        <tbody>
          {rules.map((rule) => (
            <tr key={rule.ruleId}>
              <td className="mono">{rule.ruleId}</td>
              <td>{rule.ruleName}</td>
              <td className="text-muted">{rule.description}</td>
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
          ))}
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
