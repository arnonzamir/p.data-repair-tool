import React, { useState } from 'react';
import { remediationSnapshot, remediationSimulate, remediationExecute } from '../../api/client';

interface OperationComposerProps {
  purchaseId: number;
  onRefresh: () => void;
}

const OPERATION_TYPES = [
  { value: 'DEACTIVATE', label: 'Deactivate payment', fields: ['paymentId'] },
  { value: 'ACTIVATE', label: 'Activate payment', fields: ['paymentId'] },
  { value: 'CHANGE_AMOUNT', label: 'Change amount', fields: ['paymentId', 'amount'] },
  { value: 'CHANGE_DUE_DATE', label: 'Change due date', fields: ['paymentId', 'dueDate'] },
  { value: 'CHANGE_EFFECTIVE_DATE', label: 'Change effective date', fields: ['paymentId', 'effectiveDate'] },
  { value: 'SET_PAID_OFF', label: 'Mark as paid off', fields: ['paymentId', 'paidOffDate'] },
  { value: 'CLEAR_PAID_OFF', label: 'Clear paid off (mark unpaid)', fields: ['paymentId'] },
  { value: 'ATTACH_TO_PARENT', label: 'Attach to parent', fields: ['paymentId', 'parentId'] },
  { value: 'DETACH_FROM_PARENT', label: 'Detach from parent', fields: ['paymentId'] },
  { value: 'SET_CHANGE_INDICATOR', label: 'Set change indicator', fields: ['paymentId', 'changeIndicator'] },
  { value: 'SET_MANUAL_UNTIL', label: 'Set manualUntil', fields: ['paymentId', 'manualUntil'] },
  { value: 'CLEAR_MANUAL_UNTIL', label: 'Clear manualUntil', fields: ['paymentId'] },
  { value: 'CHANGE_INTEREST', label: 'Change interest fields', fields: ['paymentId', 'interestCharge', 'interestAmount', 'principalBalance'] },
  { value: 'CHANGE_TYPE', label: 'Change payment type', fields: ['paymentId', 'paymentType'] },
  { value: 'SET_ORIGINAL_PAYMENT', label: 'Set originalPaymentId', fields: ['paymentId', 'originalPaymentId'] },
  { value: 'SET_SPLIT_FROM', label: 'Set splitFrom', fields: ['paymentId', 'splitFromId'] },
  { value: 'CLEAR_SPLIT_FROM', label: 'Clear splitFrom', fields: ['paymentId'] },
  { value: 'CREATE_PAYMENT', label: 'Create new payment', fields: ['amount', 'dueDate', 'effectiveDate', 'paymentType'] },
  { value: 'RECALCULATE_SCHEDULE', label: 'Recalculate CPP status', fields: [] },
];

const CI_VALUES: Record<number, string> = {
  0: 'NONE', 4: 'DATE_CHANGE', 8: 'PAY_NOW', 32: 'MARKED_AS_UNPAID',
  512: 'CHANGE_AMOUNT', 514: 'CANCEL', 515: 'DELAYED_CHARGE', 517: 'WORKOUT',
  518: 'APR_CHANGE', 519: 'REVERSAL', 520: 'SETTLEMENT', 521: 'EARLY_CHARGE',
};

interface Operation {
  id: number;
  type: string;
  params: Record<string, string>;
}

let nextId = 1;

const OperationComposer: React.FC<OperationComposerProps> = ({ purchaseId, onRefresh }) => {
  const [operations, setOperations] = useState<Operation[]>([]);
  const [target, setTarget] = useState('LOCAL');
  const [reason, setReason] = useState('');
  const [result, setResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showCurl, setShowCurl] = useState(false);
  const [curlText, setCurlText] = useState('');

  const addOperation = (type: string) => {
    setOperations([...operations, { id: nextId++, type, params: {} }]);
  };

  const removeOperation = (id: number) => {
    setOperations(operations.filter(op => op.id !== id));
  };

  const updateParam = (id: number, field: string, value: string) => {
    setOperations(operations.map(op =>
      op.id === id ? { ...op, params: { ...op.params, [field]: value } } : op
    ));
  };

  const moveUp = (index: number) => {
    if (index === 0) return;
    const newOps = [...operations];
    [newOps[index - 1], newOps[index]] = [newOps[index], newOps[index - 1]];
    setOperations(newOps);
  };

  const moveDown = (index: number) => {
    if (index === operations.length - 1) return;
    const newOps = [...operations];
    [newOps[index], newOps[index + 1]] = [newOps[index + 1], newOps[index]];
    setOperations(newOps);
  };

  const buildOpsPayload = () => {
    return operations.map(op => {
      const payload: Record<string, any> = { type: op.type };
      const typeDef = OPERATION_TYPES.find(t => t.value === op.type);
      if (typeDef) {
        for (const field of typeDef.fields) {
          const val = op.params[field];
          if (val !== undefined && val !== '') {
            if (['paymentId', 'parentId', 'splitFromId', 'originalPaymentId', 'changeIndicator', 'paymentType'].includes(field)) {
              payload[field] = parseInt(val, 10);
            } else if (['amount', 'interestCharge', 'interestAmount', 'principalBalance'].includes(field)) {
              payload[field] = parseFloat(val);
            } else {
              payload[field] = val;
            }
          }
        }
      }
      return payload;
    });
  };

  const handleSimulate = async () => {
    setLoading(true); setError(null); setResult(null);
    try {
      const r = await remediationSimulate(purchaseId, buildOpsPayload(), target);
      setResult({ mode: 'simulate', data: r });
    } catch (e: any) { setError(e.message); }
    setLoading(false);
  };

  const handleDryRun = async () => {
    if (!reason.trim()) { setError('Reason is required'); return; }
    setLoading(true); setError(null); setResult(null);
    try {
      const r = await remediationExecute(purchaseId, buildOpsPayload(), reason, true, false, target);
      setResult({ mode: 'dryRun', data: r });
    } catch (e: any) { setError(e.message); }
    setLoading(false);
  };

  const handleExecute = async () => {
    if (!reason.trim()) { setError('Reason is required'); return; }
    if (!window.confirm(`Execute ${operations.length} operation(s) on purchase ${purchaseId} in ${target}? This WILL modify the database.`)) return;
    setLoading(true); setError(null); setResult(null);
    try {
      const r = await remediationExecute(purchaseId, buildOpsPayload(), reason, false, false, target);
      setResult({ mode: 'execute', data: r });
      onRefresh();
    } catch (e: any) { setError(e.message); }
    setLoading(false);
  };

  const generateCurl = () => {
    const baseUrl = window.location.origin;
    const operator = localStorage.getItem('operator') || 'anonymous';
    const body = JSON.stringify({
      purchaseId,
      operations: buildOpsPayload(),
      reason: reason || 'remediation',
      dryRun: true,
      skipTicket: false,
    }, null, 2);
    const curl = [
      `curl -X POST '${baseUrl}/api/v1/remediation/execute'`,
      `  -H 'Content-Type: application/json'`,
      `  -H 'X-Operator: ${operator}'`,
      `  -H 'X-Target: ${target}'`,
      `  -d '${body}'`,
    ].join(' \\\n');
    setCurlText(curl);
    setShowCurl(true);
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
        <h3 style={{ margin: 0 }}>Payment Operations</h3>
        <select value={target} onChange={e => setTarget(e.target.value)} style={{ fontSize: 12, padding: '2px 6px' }}>
          <option value="LOCAL">LOCAL</option>
          <option value="STAGING">STAGING</option>
          <option value="PROD">PROD</option>
        </select>
      </div>

      {/* Add operation */}
      <div className="card" style={{ marginBottom: 12 }}>
        <div style={{ fontSize: 12, marginBottom: 6 }}><strong>Add operation:</strong></div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
          {OPERATION_TYPES.map(t => (
            <button key={t.value} className="btn btn-tiny" onClick={() => addOperation(t.value)} style={{ fontSize: 10 }}>
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {/* Operations list */}
      {operations.length > 0 && (
        <div className="card" style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 12, marginBottom: 8 }}><strong>Operations ({operations.length}):</strong></div>
          {operations.map((op, idx) => {
            const typeDef = OPERATION_TYPES.find(t => t.value === op.type);
            return (
              <div key={op.id} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, marginBottom: 8, padding: '6px 8px', background: '#fafafa', borderRadius: 4, border: '1px solid #e0e0e0' }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <button className="list-line-btn" onClick={() => moveUp(idx)} title="Move up" disabled={idx === 0}>^</button>
                  <button className="list-line-btn" onClick={() => moveDown(idx)} title="Move down" disabled={idx === operations.length - 1}>v</button>
                </div>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 4 }}>
                    <span style={{ color: '#757575', marginRight: 6 }}>#{idx + 1}</span>
                    {typeDef?.label || op.type}
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {typeDef?.fields.map(field => (
                      <div key={field} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        <label style={{ fontSize: 10, color: '#757575' }}>{field}:</label>
                        {field === 'changeIndicator' ? (
                          <select
                            value={op.params[field] || ''}
                            onChange={e => updateParam(op.id, field, e.target.value)}
                            style={{ fontSize: 11, padding: '1px 3px' }}
                          >
                            <option value="">select</option>
                            {Object.entries(CI_VALUES).map(([code, name]) => (
                              <option key={code} value={code}>{code} ({name})</option>
                            ))}
                          </select>
                        ) : field === 'paymentType' ? (
                          <select
                            value={op.params[field] || ''}
                            onChange={e => updateParam(op.id, field, e.target.value)}
                            style={{ fontSize: 11, padding: '1px 3px' }}
                          >
                            <option value="">select</option>
                            <option value="0">0 (scheduled)</option>
                            <option value="10">10 (unscheduled partial)</option>
                            <option value="20">20 (unscheduled payoff)</option>
                            <option value="30">30 (down payment)</option>
                          </select>
                        ) : (
                          <input
                            type="text"
                            value={op.params[field] || ''}
                            onChange={e => updateParam(op.id, field, e.target.value)}
                            placeholder={field}
                            style={{ fontSize: 11, padding: '2px 4px', width: field.includes('Date') || field.includes('Until') ? 140 : 80, border: '1px solid #ccc', borderRadius: 2 }}
                          />
                        )}
                      </div>
                    ))}
                  </div>
                </div>
                <button className="list-line-btn list-line-btn-danger" onClick={() => removeOperation(op.id)} title="Remove">x</button>
              </div>
            );
          })}
        </div>
      )}

      {/* Reason + actions */}
      {operations.length > 0 && (
        <div className="card" style={{ marginBottom: 12 }}>
          <div style={{ marginBottom: 8 }}>
            <label style={{ fontSize: 12, display: 'block', marginBottom: 2 }}><strong>Reason</strong> (required for execute):</label>
            <input
              type="text"
              value={reason}
              onChange={e => setReason(e.target.value)}
              placeholder="e.g. Feb 27 incident correction"
              style={{ fontSize: 12, padding: '4px 8px', width: '100%', border: '1px solid #ccc', borderRadius: 3 }}
            />
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <button className="btn" onClick={handleSimulate} disabled={loading}>
              {loading ? '...' : 'Simulate (in-memory)'}
            </button>
            <button className="btn" onClick={handleDryRun} disabled={loading} style={{ background: '#1565c0', color: '#fff' }}>
              {loading ? '...' : 'Dry Run (DB + rollback)'}
            </button>
            <button
              className="btn"
              onClick={handleExecute}
              disabled={loading}
              style={{ background: target === 'PROD' ? '#c62828' : '#2e7d32', color: '#fff' }}
            >
              {loading ? '...' : `Execute on ${target}`}
            </button>
            <button className="btn btn-small" onClick={generateCurl} style={{ background: '#37474f', color: '#fff' }}>
              cURL
            </button>
            {target === 'PROD' && (
              <span style={{ fontSize: 11, color: '#c62828' }}>Production. Changes are permanent.</span>
            )}
          </div>
        </div>
      )}

      {error && <div className="card" style={{ background: '#ffebee', color: '#c62828', marginBottom: 12 }}>{error}</div>}

      {/* Results */}
      {result && (
        <div className="card" style={{ marginBottom: 12, background: result.data.failedCount > 0 ? '#ffebee' : '#e8f5e9' }}>
          <div style={{ fontSize: 14, fontWeight: 'bold', marginBottom: 8 }}>
            {result.mode === 'simulate' ? 'Simulation Result' :
             result.mode === 'dryRun' ? 'Dry Run Result (rolled back)' :
             'Execution Result'}
            {result.data.dryRun && ' (no changes applied)'}
          </div>

          {/* Operation results */}
          {(result.data.operations || result.data.steps || []).map((step: any, i: number) => (
            <div key={i} style={{ padding: '4px 8px', marginBottom: 4, borderRadius: 3, fontSize: 12,
              background: step.status === 'SUCCESS' ? '#f1f8e9' : '#fce4ec' }}>
              <div style={{ display: 'flex', gap: 8 }}>
                <span style={{ fontWeight: 600 }}>#{step.index + 1}</span>
                <span>{step.operation?.type}</span>
                {step.paymentId && <span className="mono">Payment #{step.paymentId}</span>}
                <span style={{ color: step.status === 'SUCCESS' ? '#2e7d32' : '#c62828', fontWeight: 600 }}>
                  {step.status}
                </span>
              </div>
              {step.changes && step.changes.length > 0 && (
                <div style={{ fontSize: 11, color: '#546e7a', marginTop: 2 }}>
                  {step.changes.map((c: any, j: number) => (
                    <span key={j} style={{ marginRight: 12 }}>
                      {c.field}: {JSON.stringify(c.before)} {'->'} {JSON.stringify(c.after)}
                    </span>
                  ))}
                </div>
              )}
              {step.error && <div style={{ color: '#c62828', fontSize: 11 }}>{step.error}</div>}
            </div>
          ))}

          {result.data.successCount != null && (
            <div style={{ fontSize: 12, marginTop: 8, color: '#546e7a' }}>
              {result.data.successCount} succeeded, {result.data.failedCount} failed
            </div>
          )}
        </div>
      )}

      {/* cURL modal */}
      {showCurl && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
          onClick={() => setShowCurl(false)}>
          <div style={{ background: '#fff', borderRadius: 8, padding: 20, maxWidth: 700, width: '90%', maxHeight: '80vh', overflow: 'auto' }}
            onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
              <h3 style={{ margin: 0 }}>cURL Command</h3>
              <button className="btn btn-small" onClick={() => setShowCurl(false)}>Close</button>
            </div>
            <textarea
              value={curlText}
              onChange={e => setCurlText(e.target.value)}
              style={{ width: '100%', height: 200, fontFamily: 'monospace', fontSize: 12, padding: 10, border: '1px solid #e0e0e0', borderRadius: 4, resize: 'vertical' }}
            />
            <button className="btn" onClick={() => { navigator.clipboard.writeText(curlText); }} style={{ marginTop: 8, background: '#1565c0', color: '#fff' }}>
              Copy
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default OperationComposer;
