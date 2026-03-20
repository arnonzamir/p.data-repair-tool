import React, { useState } from 'react';
import type { ReplicateRequest, ReplicationResult } from '../types/domain';
import { replicatePurchase, generateSql } from '../api/client';

export function ReplicatePage() {
  const [purchaseId, setPurchaseId] = useState('');
  const [target, setTarget] = useState<'LOCAL' | 'STAGING'>('LOCAL');
  const [execute, setExecute] = useState(false);
  const [idOffset, setIdOffset] = useState('');
  const [customerRetailerId, setCustomerRetailerId] = useState('');
  const [paymentProfileId, setPaymentProfileId] = useState('');

  const [sqlPreview, setSqlPreview] = useState<{
    insertSql: string;
    rollbackSql: string;
    tableRowCounts: Record<string, number>;
  } | null>(null);
  const [result, setResult] = useState<ReplicationResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const buildRequest = (): ReplicateRequest | null => {
    const id = Number(purchaseId.trim());
    if (isNaN(id) || id <= 0) return null;
    const req: ReplicateRequest = { purchaseId: id, target, execute };
    if (idOffset.trim()) req.idOffset = Number(idOffset.trim());
    if (customerRetailerId.trim()) req.customerRetailerId = Number(customerRetailerId.trim());
    if (paymentProfileId.trim()) req.paymentProfileId = Number(paymentProfileId.trim());
    return req;
  };

  const handleGenerateSql = async () => {
    const req = buildRequest();
    if (!req) return;
    setLoading(true);
    setError(null);
    setSqlPreview(null);
    setResult(null);
    try {
      const data = await generateSql({ ...req, execute: false });
      setSqlPreview(data);
    } catch (err: any) {
      setError(err?.message || 'Failed to generate SQL');
    } finally {
      setLoading(false);
    }
  };

  const handleReplicate = async () => {
    const req = buildRequest();
    if (!req) return;
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const data = await replicatePurchase(req);
      setResult(data);
    } catch (err: any) {
      setError(err?.message || 'Replication failed');
    } finally {
      setLoading(false);
    }
  };

  const isValid = (() => {
    const id = Number(purchaseId.trim());
    return !isNaN(id) && id > 0;
  })();

  return (
    <div>
      <div className="card">
        <div className="card-header">Replicate Purchase</div>

        <div className="form-row mb-16">
          <div className="form-group" style={{ flex: 1, maxWidth: 250 }}>
            <label htmlFor="rep-purchase-id">Purchase ID</label>
            <input
              id="rep-purchase-id"
              type="number"
              className="form-input"
              value={purchaseId}
              onChange={(e) => setPurchaseId(e.target.value)}
              placeholder="e.g. 20685669"
            />
          </div>

          <div className="form-group">
            <label>Target</label>
            <div className="radio-group">
              <label>
                <input
                  type="radio"
                  name="target"
                  checked={target === 'LOCAL'}
                  onChange={() => setTarget('LOCAL')}
                />
                LOCAL
              </label>
              <label>
                <input
                  type="radio"
                  name="target"
                  checked={target === 'STAGING'}
                  onChange={() => setTarget('STAGING')}
                />
                STAGING
              </label>
            </div>
          </div>

          <div className="form-group">
            <label>&nbsp;</label>
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={execute}
                onChange={(e) => setExecute(e.target.checked)}
              />
              Execute SQL
            </label>
          </div>
        </div>

        <div className="form-row mb-16">
          <div className="form-group" style={{ flex: 1, maxWidth: 200 }}>
            <label htmlFor="rep-id-offset">ID Offset (optional)</label>
            <input
              id="rep-id-offset"
              type="number"
              className="form-input"
              value={idOffset}
              onChange={(e) => setIdOffset(e.target.value)}
              placeholder="e.g. 900000000"
            />
          </div>
          <div className="form-group" style={{ flex: 1, maxWidth: 200 }}>
            <label htmlFor="rep-customer-retailer">Customer Retailer ID (optional)</label>
            <input
              id="rep-customer-retailer"
              type="number"
              className="form-input"
              value={customerRetailerId}
              onChange={(e) => setCustomerRetailerId(e.target.value)}
            />
          </div>
          <div className="form-group" style={{ flex: 1, maxWidth: 200 }}>
            <label htmlFor="rep-payment-profile">Payment Profile ID (optional)</label>
            <input
              id="rep-payment-profile"
              type="number"
              className="form-input"
              value={paymentProfileId}
              onChange={(e) => setPaymentProfileId(e.target.value)}
            />
          </div>
        </div>

        <div className="flex gap-8">
          <button
            className="btn"
            onClick={handleGenerateSql}
            disabled={!isValid || loading}
          >
            {loading ? 'Working...' : 'Generate SQL'}
          </button>
          <button
            className="btn btn-primary"
            onClick={handleReplicate}
            disabled={!isValid || loading}
          >
            {loading ? 'Working...' : 'Replicate'}
          </button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {sqlPreview && (
        <div className="card">
          <div className="card-header">Generated SQL</div>
          <div className="mb-16">
            <strong className="text-small">Table Row Counts:</strong>
            <table className="mt-8" style={{ maxWidth: 400 }}>
              <thead>
                <tr>
                  <th>Table</th>
                  <th className="text-right">Rows</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(sqlPreview.tableRowCounts).map(([table, count]) => (
                  <tr key={table}>
                    <td className="mono">{table}</td>
                    <td className="text-right">{count}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mb-16">
            <strong className="text-small">Insert SQL:</strong>
            <pre className="code-block mt-8">{sqlPreview.insertSql}</pre>
          </div>
          <div>
            <strong className="text-small">Rollback SQL:</strong>
            <pre className="code-block mt-8">{sqlPreview.rollbackSql}</pre>
          </div>
        </div>
      )}

      {result && (
        <div className="card">
          <div className="card-header">Replication Result</div>
          {result.success ? (
            <div className="success-banner mb-16">
              Replication successful. Executed: {result.executed ? 'Yes' : 'No (dry run)'}
            </div>
          ) : (
            <div className="error-banner mb-16">
              Replication failed.{result.executionError ? ` Error: ${result.executionError}` : ''}
            </div>
          )}
          <table style={{ maxWidth: 500 }}>
            <tbody>
              <tr><td className="text-muted">Purchase ID</td><td className="mono">{result.purchaseId}</td></tr>
              <tr><td className="text-muted">Target</td><td>{result.target}</td></tr>
              <tr><td className="text-muted">PII Redactions</td><td>{result.piiRedactions}</td></tr>
              {result.runDirectory && (
                <tr><td className="text-muted">Run Directory</td><td className="mono">{result.runDirectory}</td></tr>
              )}
            </tbody>
          </table>
          {result.tableRowCounts && Object.keys(result.tableRowCounts).length > 0 && (
            <div className="mt-16">
              <strong className="text-small">Table Row Counts:</strong>
              <table className="mt-8" style={{ maxWidth: 400 }}>
                <thead>
                  <tr>
                    <th>Table</th>
                    <th className="text-right">Rows</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(result.tableRowCounts).map(([table, count]) => (
                    <tr key={table}>
                      <td className="mono">{table}</td>
                      <td className="text-right">{count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
