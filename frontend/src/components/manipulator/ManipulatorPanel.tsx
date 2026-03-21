import React, { useState, useEffect, useCallback } from 'react';
import { renderRichText, renderMarkdownBlock } from '../common/RichText';
import {
  getApplicableManipulators,
  previewManipulator,
  executeManipulator,
  ApplicableManipulator,
  ManipulatorPreview,
  ManipulatorRunResult,
} from '../../api/client';

interface ManipulatorPanelProps {
  purchaseId: number;
  onRefresh: () => void;
}

type Phase = 'list' | 'preview' | 'executing' | 'result';

const ManipulatorPanel: React.FC<ManipulatorPanelProps> = ({ purchaseId, onRefresh }) => {
  const [applicable, setApplicable] = useState<ApplicableManipulator[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Selected manipulator state
  const [selected, setSelected] = useState<ApplicableManipulator | null>(null);
  const [phase, setPhase] = useState<Phase>('list');
  const [preview, setPreview] = useState<ManipulatorPreview | null>(null);
  const [result, setResult] = useState<ManipulatorRunResult | null>(null);
  const [target, setTarget] = useState<string>('LOCAL');
  const [params, setParams] = useState<Record<string, any>>({});

  const loadApplicable = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getApplicableManipulators(purchaseId);
      setApplicable(data);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [purchaseId]);

  useEffect(() => {
    loadApplicable();
  }, [loadApplicable]);

  const handleSelect = async (m: ApplicableManipulator) => {
    setSelected(m);
    setParams(m.applicability.suggestedParams || {});
    setPhase('preview');
    setPreview(null);
    setResult(null);

    try {
      const p = await previewManipulator(purchaseId, m.manipulatorId, m.applicability.suggestedParams || {});
      setPreview(p);
    } catch (e: any) {
      setError(e.message);
    }
  };

  const handleExecute = async () => {
    if (!selected) return;
    setPhase('executing');
    setResult(null);

    try {
      const r = await executeManipulator(purchaseId, selected.manipulatorId, params, target);
      setResult(r);
      setPhase('result');
    } catch (e: any) {
      setError(e.message);
      setPhase('preview');
    }
  };

  const handleDone = () => {
    setSelected(null);
    setPhase('list');
    setPreview(null);
    setResult(null);
    setParams({});
    onRefresh();
    loadApplicable();
  };

  const handleBack = () => {
    setSelected(null);
    setPhase('list');
    setPreview(null);
    setResult(null);
  };

  // -- List phase --
  if (phase === 'list') {
    return (
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
          <h3 style={{ margin: 0 }}>Loan Manipulators</h3>
          <button className="btn btn-small" onClick={loadApplicable} disabled={loading}>
            {loading ? 'Checking...' : 'Refresh'}
          </button>
          <select value={target} onChange={e => setTarget(e.target.value)} style={{ fontSize: 12, padding: '2px 6px' }}>
            <option value="LOCAL">LOCAL</option>
            <option value="STAGING">STAGING</option>
            <option value="PROD">PROD</option>
          </select>
          <span style={{ fontSize: 11, color: '#757575' }}>Target: {target}</span>
        </div>

        {error && <div className="card" style={{ background: '#ffebee', color: '#c62828', marginBottom: 12 }}>{error}</div>}

        {applicable.length === 0 && !loading && (
          <div className="card" style={{ color: '#757575' }}>No applicable manipulators for this purchase.</div>
        )}

        {applicable.length > 0 && (
          <table className="table">
            <thead>
              <tr>
                <th>Manipulator</th>
                <th>Category</th>
                <th>Reason</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {applicable.map(m => (
                <tr key={m.manipulatorId}>
                  <td><strong>{m.name}</strong></td>
                  <td><span className={`badge badge-category-${m.category.toLowerCase()}`}>{m.category}</span></td>
                  <td style={{ fontSize: 12, color: '#546e7a' }}>{m.applicability.reason}</td>
                  <td>
                    <button className="btn btn-small" onClick={() => handleSelect(m)}>Select</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    );
  }

  // -- Preview phase --
  if (phase === 'preview' && selected) {
    return (
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
          <button className="btn btn-small" onClick={handleBack}>Back</button>
          <h3 style={{ margin: 0 }}>{selected.name}</h3>
          <span className={`badge badge-category-${selected.category.toLowerCase()}`}>{selected.category}</span>
          <span style={{ fontSize: 11, color: '#757575' }}>Target: {target}</span>
        </div>

        <div className="card" style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 13, marginBottom: 8 }}><strong>Reason:</strong> {selected.applicability.reason}</div>

          {preview ? (
            <>
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
                    <tr>
                      <th>#</th>
                      <th>Action</th>
                      <th>Description</th>
                      <th>Affected Payments</th>
                    </tr>
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
            </>
          ) : (
            <div style={{ color: '#757575' }}>Loading preview...</div>
          )}
        </div>

        {/* Parameters (if any suggested) */}
        {Object.keys(params).length > 0 && (
          <div className="card" style={{ marginBottom: 12 }}>
            <div style={{ fontSize: 13, marginBottom: 6 }}><strong>Parameters:</strong></div>
            {Object.entries(params).map(([key, value]) => (
              <div key={key} style={{ fontSize: 12, marginBottom: 4 }}>
                <span style={{ color: '#757575' }}>{key}: </span>
                <input
                  type="text"
                  value={String(value)}
                  onChange={e => setParams({ ...params, [key]: e.target.value })}
                  style={{ fontSize: 12, padding: '2px 6px', width: 200 }}
                />
              </div>
            ))}
          </div>
        )}

        <div style={{ display: 'flex', gap: 8 }}>
          <button
            className="btn"
            onClick={handleExecute}
            disabled={!preview}
            style={{ background: target === 'PROD' ? '#c62828' : '#2e7d32', color: '#fff' }}
          >
            Execute on {target}
          </button>
          {target === 'PROD' && (
            <span style={{ fontSize: 11, color: '#c62828', alignSelf: 'center' }}>
              WARNING: This will run against production
            </span>
          )}
        </div>
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
          <h3 style={{ margin: 0 }}>{selected?.name} -- Result</h3>
        </div>

        {/* Execution result */}
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

        {/* Verification result */}
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
