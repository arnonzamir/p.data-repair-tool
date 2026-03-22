import React, { useState, useEffect } from 'react';
import { replicatePurchase, generateSql, getReplicateDefaults, checkReplicationExists, rollbackReplication, getConfig } from '../../api/client';
import type { ReplicationResult } from '../../types/domain';

interface ReplicateInlineProps {
  purchaseId: number;
  onClose: () => void;
  onReplicated?: () => void;
}

interface TargetDefaults {
  customerRetailerId: number;
  paymentProfileId: number;
  idOffset: number;
}

interface ExistsCheck {
  targetPurchaseId: number;
  reachable: boolean;
  exists: boolean;
  rowCounts: Record<string, number>;
}

const ReplicateInline: React.FC<ReplicateInlineProps> = ({ purchaseId, onClose, onReplicated }) => {
  const [target, setTarget] = useState<'LOCAL' | 'STAGING'>('LOCAL');
  // execute is always true for "Replicate" button; "Preview SQL" uses execute=false
  const [idOffset, setIdOffset] = useState(100000000);
  // CR is now replicated from source with retailer ID overridden to local/staging
  const [namePrefix, setNamePrefix] = useState('Test');
  const [defaults, setDefaults] = useState<Record<string, TargetDefaults> | null>(null);
  const [ccUrls, setCcUrls] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ReplicationResult | null>(null);
  const [sqlPreview, setSqlPreview] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [existsCheck, setExistsCheck] = useState<ExistsCheck | null>(null);
  const [checking, setChecking] = useState(false);
  const [cleaning, setCleaning] = useState(false);

  useEffect(() => {
    getReplicateDefaults().then(setDefaults).catch(() => {});
    getConfig().then((cfg) => setCcUrls(cfg.callCenter)).catch(() => {});
  }, []);

  // When target changes, apply defaults and check existence
  useEffect(() => {
    if (defaults) {
      const d = defaults[target];
      if (d) {
        setIdOffset(d.idOffset);
      }
    }
  }, [target, defaults]);

  // Check existence whenever target or offset changes
  useEffect(() => {
    setExistsCheck(null);
    setResult(null);
    setError(null);
    setSqlPreview(null);

    setChecking(true);
    checkReplicationExists(purchaseId, target, idOffset)
      .then(setExistsCheck)
      .catch(() => setExistsCheck(null))
      .finally(() => setChecking(false));
  }, [purchaseId, target, idOffset]);

  const replicatedPurchaseId = purchaseId + idOffset;

  const handleCleanAndReimport = async () => {
    setCleaning(true);
    setError(null);
    try {
      // First rollback (uses stored rollback SQL if available, or we need to generate one)
      const rollbackResult = await rollbackReplication(purchaseId, target);
      if (!rollbackResult.success) {
        setError(`Cleanup failed: ${rollbackResult.error}. You may need to manually delete purchase ${replicatedPurchaseId} from ${target}.`);
        setCleaning(false);
        return;
      }

      // Now replicate fresh
      const res = await replicatePurchase({
        purchaseId,
        target,
        execute: true,
        idOffset,
        customerRetailerId: undefined,
        paymentProfileId: undefined,
        namePrefix: namePrefix || undefined,
      });
      setResult(res);
      if (res.success && res.executed && onReplicated) {
        onReplicated();
      }

      // Re-check existence
      checkReplicationExists(purchaseId, target, idOffset)
        .then(setExistsCheck)
        .catch(() => {});
    } catch (e: any) {
      setError(e.message);
    } finally {
      setCleaning(false);
    }
  };

  const handleGenerateSql = async () => {
    setLoading(true);
    setError(null);
    setSqlPreview(null);
    setResult(null);
    try {
      const res = await generateSql({
        purchaseId,
        target,
        execute: false,
        idOffset,
        customerRetailerId: undefined,
        paymentProfileId: undefined,
        namePrefix: namePrefix || undefined,
      });
      setSqlPreview(res.insertSql);
      // Trigger download
      const blob = new Blob([res.insertSql], { type: 'text/sql' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `replicate_${purchaseId}_${target.toLowerCase()}.sql`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const handleReplicate = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    setSqlPreview(null);
    try {
      const res = await replicatePurchase({
        purchaseId,
        target,
        execute: true,
        idOffset,
        customerRetailerId: undefined,
        paymentProfileId: undefined,
        namePrefix: namePrefix || undefined,
      });
      setResult(res);
      if (res.success && res.executed && onReplicated) {
        onReplicated();
      }
      // Re-check existence
      if (res.success && res.executed) {
        checkReplicationExists(purchaseId, target, idOffset)
          .then(setExistsCheck)
          .catch(() => {});
      }
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const callCenterUrl = (id: number) => {
    const template = ccUrls[target] || '';
    return template.replace('{id}', String(id));
  };

  const totalExistingRows = existsCheck
    ? Object.values(existsCheck.rowCounts).reduce((a, b) => a + b, 0)
    : 0;

  return (
    <div className="card replicate-inline">
      <div className="replicate-inline-header">
        <h4>Replicate Purchase {purchaseId}</h4>
        <button className="btn btn-small" onClick={onClose}>Close</button>
      </div>

      <div className="replicate-inline-form">
        <div className="form-row">
          <label>Target:</label>
          <label className="radio-label">
            <input
              type="radio"
              name="repl-target"
              checked={target === 'LOCAL'}
              onChange={() => setTarget('LOCAL')}
            />
            Local
          </label>
          <label className="radio-label">
            <input
              type="radio"
              name="repl-target"
              checked={target === 'STAGING'}
              onChange={() => setTarget('STAGING')}
            />
            Staging
          </label>
        </div>

        <div className="form-row">
          <label>ID Offset:</label>
          <input
            type="number"
            value={idOffset}
            onChange={(e) => setIdOffset(Number(e.target.value))}
            className="input-small"
          />
          <label>Name prefix:</label>
          <input
            type="text"
            value={namePrefix}
            onChange={(e) => setNamePrefix(e.target.value)}
            className="input-small"
            placeholder="e.g. Feb28"
          />
        </div>

        {defaults && defaults[target] && (
          <div className="defaults-note">
            <div>
              Full replication: purchase, customer + CR (retailer overridden to {target.toLowerCase()}),
              payment profiles + cards, charge-schema profiles + debit/ACH/RCC methods,
              payments, charge transactions, loan transactions, attempts, emails, tickets, properties.
            </div>
            <div style={{marginTop: 4}}>
              Shared entities (customers, profiles, CR) use INSERT IGNORE -- safe across multiple purchases.
            </div>
          </div>
        )}

        {/* Existence check banner */}
        {checking && (
          <div className="exists-banner exists-checking">
            Checking if purchase {replicatedPurchaseId} exists in {target}...
          </div>
        )}

        {existsCheck && !existsCheck.reachable && (
          <div className="exists-banner exists-unreachable">
            <div>Cannot reach {target} database.</div>
            {target === 'STAGING' && (
              <div style={{ marginTop: 8, fontSize: 12, background: '#f5f5f5', padding: '8px 12px', borderRadius: 4, borderLeft: '3px solid #90a4ae' }}>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>To set up staging MySQL credentials:</div>
                <div style={{ fontFamily: 'monospace', fontSize: 11, marginBottom: 2 }}>1. aws sso login --profile staging</div>
                <div style={{ fontFamily: 'monospace', fontSize: 11, marginBottom: 2 }}>2. sunbit-cli mysql file-creds -d staging -m rw</div>
                <div style={{ marginTop: 6, color: '#757575' }}>
                  This creates ~/.sunbit/mysql-staging.json with temporary credentials (valid for a few hours).
                  Re-run when they expire.
                </div>
              </div>
            )}
            {target === 'LOCAL' && (
              <div style={{ marginTop: 4, fontSize: 12, color: '#757575' }}>
                Make sure local MySQL is running at sunbit-mysql:30306.
              </div>
            )}
          </div>
        )}

        {existsCheck && existsCheck.reachable && existsCheck.exists && (
          <div className="exists-banner exists-found">
            <div>
              Purchase {existsCheck.targetPurchaseId} already exists in {target} ({totalExistingRows} rows across {Object.keys(existsCheck.rowCounts).length} tables:
              {' '}{Object.entries(existsCheck.rowCounts).map(([t, c]) => `${t}=${c}`).join(', ')})
            </div>
            <div className="exists-actions">
              <button
                className="btn btn-danger"
                onClick={handleCleanAndReimport}
                disabled={cleaning || loading}
              >
                {cleaning ? 'Cleaning and reimporting...' : 'Remove and reimport'}
              </button>
              <a
                href={callCenterUrl(existsCheck.targetPurchaseId)}
                target="_blank"
                rel="noopener noreferrer"
                className="btn btn-small"
              >
                Open existing in call center
              </a>
            </div>
          </div>
        )}

        {existsCheck && existsCheck.reachable && !existsCheck.exists && (
          <div className="exists-banner exists-clear">
            Purchase {replicatedPurchaseId} does not exist in {target}. Ready to replicate.
          </div>
        )}

        <div className="form-row">
          <button className="btn" onClick={handleGenerateSql} disabled={loading}>
            {loading ? 'Working...' : 'Generate and download SQL'}
          </button>
          <button className="btn btn-primary" onClick={handleReplicate} disabled={loading}>
            {loading ? 'Working...' : `Replicate into ${target === 'LOCAL' ? 'local' : 'staging'} database`}
          </button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {result && (
        <div className={`replicate-result ${result.success ? 'result-success' : 'result-failure'}`}>
          <p><strong>{result.success ? 'Replication successful' : 'Replication failed'}</strong></p>
          {result.executionError && <p className="text-fail">{result.executionError}</p>}
          <div className="replicate-tables-grid">
            <div>
              <h5>Purchase schema</h5>
              <table className="table table-compact">
                <thead><tr><th>Table</th><th>Rows</th></tr></thead>
                <tbody>
                  {Object.entries(result.tableRowCounts)
                    .filter(([t]) => !t.startsWith('charge_'))
                    .map(([table, count]) => (
                      <tr key={table}><td>{table}</td><td>{count}</td></tr>
                    ))}
                </tbody>
              </table>
            </div>
            <div>
              <h5>Charge schema</h5>
              <table className="table table-compact">
                <thead><tr><th>Table</th><th>Rows</th></tr></thead>
                <tbody>
                  {Object.entries(result.tableRowCounts)
                    .filter(([t]) => t.startsWith('charge_'))
                    .map(([table, count]) => (
                      <tr key={table}><td>{table.replace('charge_', '')}</td><td>{count}</td></tr>
                    ))}
                </tbody>
              </table>
            </div>
          </div>
          {(result as any).skippedTables?.length > 0 && (
            <p className="text-muted">Skipped: {(result as any).skippedTables.join(', ')}</p>
          )}
          <p className="text-muted">PII anonymized across {(result as any).piiLog?.length ?? result.piiRedactions ?? 0} fields. Shared entities use INSERT IGNORE.</p>
          {result.runDirectory && <p className="text-muted">Artifacts: {result.runDirectory}</p>}

          {result.success && result.executed && (
            <div className="call-center-links">
              <p>Replicated purchase ID: <strong>{replicatedPurchaseId}</strong></p>
              <a
                href={callCenterUrl(replicatedPurchaseId)}
                target="_blank"
                rel="noopener noreferrer"
                className="btn btn-small"
              >
                Open in {target === 'LOCAL' ? 'local' : 'staging'} call center
              </a>
            </div>
          )}
        </div>
      )}

      {sqlPreview && (
        <div className="sql-preview">
          <h5>Generated SQL ({sqlPreview.split('\n').length} lines)</h5>
          <pre className="code-block">{sqlPreview.substring(0, 5000)}{sqlPreview.length > 5000 ? '\n... (truncated)' : ''}</pre>
        </div>
      )}
    </div>
  );
};

export default ReplicateInline;
