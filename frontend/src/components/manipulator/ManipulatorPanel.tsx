import React, { useState, useEffect, useCallback } from 'react';
import { renderRichText, renderMarkdownBlock } from '../common/RichText';
import {
  listManipulators,
  getApplicableManipulators,
  previewManipulator,
  executeManipulator,
  ManipulatorInfo,
  ApplicableManipulator,
  ManipulatorPreview,
  ManipulatorRunResult,
} from '../../api/client';

interface ManipulatorPanelProps {
  purchaseId: number;
  onRefresh: () => void;
}

type Phase = 'list' | 'configure' | 'executing' | 'result';

const ManipulatorPanel: React.FC<ManipulatorPanelProps> = ({ purchaseId, onRefresh }) => {
  const [allManipulators, setAllManipulators] = useState<ManipulatorInfo[]>([]);
  const [applicability, setApplicability] = useState<Record<string, ApplicableManipulator>>({});
  const [checkingApplicability, setCheckingApplicability] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Selected manipulator state
  const [selected, setSelected] = useState<ManipulatorInfo | null>(null);
  const [phase, setPhase] = useState<Phase>('list');
  const [preview, setPreview] = useState<ManipulatorPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [result, setResult] = useState<ManipulatorRunResult | null>(null);
  const [target, setTarget] = useState<string>('LOCAL');
  const [params, setParams] = useState<Record<string, any>>({});
  const [showCurl, setShowCurl] = useState(false);
  const [curlText, setCurlText] = useState('');
  const [curlCopied, setCurlCopied] = useState(false);

  // Load all manipulators instantly (from registry, no Snowflake)
  useEffect(() => {
    listManipulators().then(setAllManipulators).catch(e => setError(e.message));
  }, []);

  // Check applicability in background (non-blocking)
  const checkApplicability = useCallback(async () => {
    setCheckingApplicability(true);
    try {
      const data = await getApplicableManipulators(purchaseId);
      const map: Record<string, ApplicableManipulator> = {};
      for (const m of data) map[m.manipulatorId] = m;
      setApplicability(map);
    } catch (_) { /* non-blocking */ }
    setCheckingApplicability(false);
  }, [purchaseId]);

  useEffect(() => {
    checkApplicability();
  }, [checkApplicability]);

  const handleSelect = async (m: ManipulatorInfo) => {
    setSelected(m);
    setPhase('configure');
    setPreview(null);
    setResult(null);

    // Pre-fill from existing applicability check
    const app = applicability[m.id];
    const suggested = app?.applicability?.suggestedParams || {};
    const initial: Record<string, any> = {};
    for (const p of m.requiredParams) {
      initial[p.name] = suggested[p.name] ?? p.defaultValue ?? '';
    }
    setParams(initial);

    // If no suggested params yet, trigger a fresh applicability check for this purchase
    if (Object.keys(suggested).length === 0 && m.requiredParams.length > 0) {
      try {
        const fresh = await getApplicableManipulators(purchaseId);
        const freshApp = fresh.find(a => a.manipulatorId === m.id);
        if (freshApp?.applicability?.suggestedParams) {
          const freshSuggested = freshApp.applicability.suggestedParams;
          const updated: Record<string, any> = {};
          for (const p of m.requiredParams) {
            updated[p.name] = freshSuggested[p.name] ?? initial[p.name] ?? '';
          }
          setParams(updated);
          setApplicability(prev => ({ ...prev, [m.id]: freshApp }));
        }
      } catch (_) { /* non-blocking */ }
    }
  };

  const handlePreview = async () => {
    if (!selected) return;
    setPreviewLoading(true);
    try {
      const p = await previewManipulator(purchaseId, selected.id, params);
      setPreview(p);
    } catch (e: any) {
      setError(e.message);
    }
    setPreviewLoading(false);
  };

  const handleExecute = async () => {
    if (!selected) return;
    setPhase('executing');
    setResult(null);
    try {
      const r = await executeManipulator(purchaseId, selected.id, params, target);
      setResult(r);
      setPhase('result');
    } catch (e: any) {
      setError(e.message);
      setPhase('configure');
    }
  };

  const handleDone = () => {
    setSelected(null);
    setPhase('list');
    setPreview(null);
    setResult(null);
    setParams({});
    onRefresh();
    checkApplicability();
  };

  const generateCurl = () => {
    if (!selected) return;
    const baseUrl = window.location.origin;
    const operator = localStorage.getItem('operator') || 'anonymous';
    const body = JSON.stringify({ manipulatorId: selected.id, params }, null, 2);
    const curl = [
      `curl -X POST '${baseUrl}/api/v1/manipulators/${purchaseId}/execute'`,
      `  -H 'Content-Type: application/json'`,
      `  -H 'X-Operator: ${operator}'`,
      `  -H 'X-Target: ${target}'`,
      `  -d '${body}'`,
    ].join(' \\\n');
    setCurlText(curl);
    setShowCurl(true);
    setCurlCopied(false);
  };

  const copyCurl = () => {
    navigator.clipboard.writeText(curlText).then(() => {
      setCurlCopied(true);
      setTimeout(() => setCurlCopied(false), 2000);
    });
  };

  const handleBack = () => {
    setSelected(null);
    setPhase('list');
    setPreview(null);
    setResult(null);
  };

  // Group manipulators by category
  const byCategory: Record<string, ManipulatorInfo[]> = {};
  for (const m of allManipulators) {
    const cat = m.category || 'OTHER';
    if (!byCategory[cat]) byCategory[cat] = [];
    byCategory[cat].push(m);
  }
  const categoryOrder = ['REMEDIATION', 'STRUCTURAL', 'FINANCIAL', 'CHARGEBACK'];

  // -- List phase --
  if (phase === 'list') {
    return (
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
          <h3 style={{ margin: 0 }}>Loan Manipulators</h3>
          <select value={target} onChange={e => setTarget(e.target.value)} style={{ fontSize: 12, padding: '2px 6px' }}>
            <option value="LOCAL">LOCAL</option>
            <option value="STAGING">STAGING</option>
            <option value="PROD">PROD</option>
          </select>
          <span style={{ fontSize: 11, color: '#757575' }}>Target: {target}</span>
          {checkingApplicability && <span style={{ fontSize: 11, color: '#757575' }}>Checking applicability...</span>}
        </div>

        {error && <div className="card" style={{ background: '#ffebee', color: '#c62828', marginBottom: 12 }}>{error}</div>}

        {categoryOrder.map(cat => {
          const items = byCategory[cat];
          if (!items || items.length === 0) return null;
          return (
            <div key={cat} style={{ marginBottom: 16 }}>
              <div style={{ marginBottom: 6 }}>
                <span className={`badge badge-category-${cat.toLowerCase()}`}>{cat}</span>
              </div>
              <table className="table">
                <thead>
                  <tr>
                    <th>Manipulator</th>
                    <th>Status</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {items.map(m => {
                    const app = applicability[m.id];
                    const isApplicable = app?.applicability?.canApply;
                    return (
                      <tr key={m.id}>
                        <td>
                          <strong>{m.name}</strong>
                          <div style={{ fontSize: 11, color: '#546e7a' }}>{m.description}</div>
                        </td>
                        <td style={{ fontSize: 11, width: 200 }}>
                          {app ? (
                            <span style={{ color: isApplicable ? '#2e7d32' : '#757575' }}>
                              {isApplicable ? 'Applicable' : 'Not applicable'}
                              {app.applicability?.reason && (
                                <div style={{ fontSize: 10, color: '#9e9e9e', marginTop: 2 }}>
                                  {app.applicability.reason.substring(0, 80)}
                                </div>
                              )}
                            </span>
                          ) : (
                            checkingApplicability ? <span style={{ color: '#9e9e9e' }}>...</span> : null
                          )}
                        </td>
                        <td>
                          <button className="btn btn-small" onClick={() => handleSelect(m)}>
                            Configure
                          </button>
                        </td>
                      </tr>
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

  // -- Configure phase --
  if (phase === 'configure' && selected) {
    const app = applicability[selected.id];
    return (
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
          <button className="btn btn-small" onClick={handleBack}>Back</button>
          <h3 style={{ margin: 0 }}>{selected.name}</h3>
          <span className={`badge badge-category-${selected.category.toLowerCase()}`}>{selected.category}</span>
          <span style={{ fontSize: 11, color: '#757575' }}>Target: {target}</span>
        </div>

        <div className="card" style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 13, marginBottom: 8 }}><strong>{selected.description}</strong></div>

          {selected.detailedDescription && selected.detailedDescription !== selected.description && (
            <div className="rule-explanation-content" style={{ marginBottom: 8 }}>
              {renderMarkdownBlock(selected.detailedDescription)}
            </div>
          )}

          {app && (
            <div style={{ fontSize: 12, padding: '6px 10px', borderRadius: 4, marginBottom: 4,
              background: app.applicability?.canApply ? '#e8f5e9' : '#fff3e0',
              color: app.applicability?.canApply ? '#2e7d32' : '#e65100' }}>
              {app.applicability?.canApply ? 'Applicable: ' : 'Not applicable: '}
              {app.applicability?.reason}
            </div>
          )}
        </div>

        {/* Parameters */}
        <div className="card" style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 13, marginBottom: 8 }}><strong>Parameters</strong></div>
          {selected.requiredParams.length === 0 && (
            <div style={{ fontSize: 12, color: '#757575' }}>No parameters required.</div>
          )}
          {selected.requiredParams.map(p => {
            const suggested = app?.applicability?.suggestedParams?.[p.name];
            return (
              <div key={p.name} style={{ marginBottom: 10 }}>
                <label style={{ fontSize: 12, display: 'block', marginBottom: 2 }}>
                  <span className="mono" style={{ fontWeight: 600 }}>{p.name}</span>
                  <span style={{ color: '#9e9e9e' }}> ({p.type.toLowerCase()}{p.required ? ', required' : ''})</span>
                </label>
                <div style={{ fontSize: 11, color: '#546e7a', marginBottom: 3 }}>{p.description}</div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <input
                    type="text"
                    value={String(params[p.name] ?? '')}
                    onChange={e => setParams({ ...params, [p.name]: e.target.value })}
                    style={{ fontSize: 12, padding: '4px 8px', width: 300, border: '1px solid #ccc', borderRadius: 3 }}
                    placeholder={p.defaultValue != null ? `Default: ${p.defaultValue}` : ''}
                  />
                  {suggested != null && String(suggested) !== String(params[p.name]) && (
                    <button
                      className="btn btn-tiny"
                      onClick={() => setParams({ ...params, [p.name]: suggested })}
                      title={`Use suggested value: ${suggested}`}
                    >
                      Use suggested: {String(suggested).substring(0, 20)}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        {/* Actions */}
        <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap', alignItems: 'center' }}>
          <button className="btn" onClick={handlePreview} disabled={previewLoading}>
            {previewLoading ? 'Loading preview...' : 'Preview'}
          </button>
          <button
            className="btn"
            onClick={handleExecute}
            style={{ background: target === 'PROD' ? '#c62828' : '#2e7d32', color: '#fff' }}
          >
            Execute on {target}
          </button>
          <button className="btn btn-small" onClick={generateCurl} style={{ background: '#37474f', color: '#fff' }}>
            Copy as cURL
          </button>
          {target === 'PROD' && (
            <span style={{ fontSize: 11, color: '#c62828', alignSelf: 'center' }}>
              This will run against production
            </span>
          )}
        </div>

        {/* cURL modal */}
        {showCurl && (
          <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
            onClick={() => setShowCurl(false)}>
            <div style={{ background: '#fff', borderRadius: 8, padding: 20, maxWidth: 700, width: '90%', maxHeight: '80vh', overflow: 'auto' }}
              onClick={e => e.stopPropagation()}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                <h3 style={{ margin: 0 }}>cURL Command</h3>
                <button className="btn btn-small" onClick={() => setShowCurl(false)}>Close</button>
              </div>
              <textarea
                value={curlText}
                onChange={e => setCurlText(e.target.value)}
                style={{ width: '100%', height: 200, fontFamily: 'monospace', fontSize: 12, padding: 10, border: '1px solid #e0e0e0', borderRadius: 4, resize: 'vertical' }}
              />
              <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                <button className="btn" onClick={copyCurl} style={{ background: '#1565c0', color: '#fff' }}>
                  {curlCopied ? 'Copied' : 'Copy to clipboard'}
                </button>
              </div>
            </div>
          </div>
        )}

        {preview && (
          <div className="card" style={{ marginBottom: 12 }}>
            <div style={{ fontSize: 13, marginBottom: 8 }}><strong>Preview:</strong> {renderRichText(preview.description)}</div>

            {preview.warnings.length > 0 && (
              <div style={{ background: '#fff3e0', padding: '6px 10px', borderRadius: 4, marginBottom: 8 }}>
                {preview.warnings.map((w, i) => (
                  <div key={i} style={{ fontSize: 12, color: '#e65100' }}>{renderRichText(w)}</div>
                ))}
              </div>
            )}

            {preview.steps.length > 0 && (
              <table className="table" style={{ marginTop: 8 }}>
                <thead>
                  <tr><th>#</th><th>Action</th><th>Description</th><th>Affected</th></tr>
                </thead>
                <tbody>
                  {preview.steps.map(s => (
                    <tr key={s.order}>
                      <td>{s.order}</td>
                      <td><span className="badge" style={{ background: '#e3f2fd', color: '#1565c0' }}>{s.action}</span></td>
                      <td style={{ fontSize: 12 }}>{s.description}</td>
                      <td className="mono" style={{ fontSize: 11 }}>{s.affectedPaymentIds.join(', ') || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}
      </div>
    );
  }

  // -- Executing phase --
  if (phase === 'executing') {
    return (
      <div className="card">
        <div style={{ fontSize: 14 }}>Executing {selected?.name} on {target}...</div>
      </div>
    );
  }

  // -- Result phase --
  if (phase === 'result' && result) {
    return (
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
          <h3 style={{ margin: 0 }}>{selected?.name} Result</h3>
        </div>

        <div className="card" style={{ marginBottom: 12, background: result.success ? '#e8f5e9' : '#ffebee' }}>
          <div style={{ fontSize: 14, fontWeight: 'bold', marginBottom: 6 }}>
            {result.success ? 'Execution succeeded' : 'Execution failed'}
          </div>

          {result.execution && (
            <>
              {result.execution.error && (
                <div style={{ color: '#c62828', fontSize: 12, marginBottom: 8 }}>{result.execution.error}</div>
              )}
              <table className="table" style={{ marginTop: 8 }}>
                <thead>
                  <tr><th>#</th><th>Action</th><th>Status</th><th>Error</th></tr>
                </thead>
                <tbody>
                  {result.execution.stepsExecuted.map(s => (
                    <tr key={s.order}>
                      <td>{s.order}</td>
                      <td>{s.action}</td>
                      <td>
                        <span className={`badge ${s.success ? 'badge-released' : 'severity-critical'}`}>
                          {s.success ? 'OK' : 'FAILED'}
                        </span>
                      </td>
                      <td style={{ fontSize: 11, color: '#c62828' }}>{s.error || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </div>

        {result.verification && (
          <div className="card" style={{ marginBottom: 12, background: result.verification.passed ? '#e8f5e9' : '#fff3e0' }}>
            <div style={{ fontSize: 14, fontWeight: 'bold', marginBottom: 6 }}>
              Verification: {result.verification.passed ? 'PASSED' : 'NEEDS REVIEW'}
            </div>
            <div style={{ fontSize: 12 }}>{result.verification.reason}</div>
            {result.verification.resolvedFindings.length > 0 && (
              <div style={{ marginTop: 8 }}>
                <div style={{ fontSize: 12, fontWeight: 'bold' }}>Resolved:</div>
                {result.verification.resolvedFindings.map((f, i) => (
                  <div key={i} style={{ fontSize: 11, color: '#2e7d32' }}>{f}</div>
                ))}
              </div>
            )}
          </div>
        )}

        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={handleDone}>Done (Refresh Purchase)</button>
          <button className="btn btn-small" onClick={handleBack}>Back to List</button>
        </div>
      </div>
    );
  }

  return null;
};

export default ManipulatorPanel;
