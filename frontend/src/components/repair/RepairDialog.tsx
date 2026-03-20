import React, { useState } from 'react';
import type { SuggestedRepair, Finding, DryRunResult, RepairResult } from '../../types/domain';
import { dryRun, executeRepair } from '../../api/client';

interface RepairDialogProps {
  repair: SuggestedRepair;
  finding: Finding;
  purchaseId: number;
  onClose: () => void;
  onComplete: () => void;
}

const RepairDialog: React.FC<RepairDialogProps> = ({
  repair,
  finding,
  purchaseId,
  onClose,
  onComplete,
}) => {
  const [reason, setReason] = useState('');
  const [dryRunResult, setDryRunResult] = useState<DryRunResult | null>(null);
  const [repairResult, setRepairResult] = useState<RepairResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const buildRequest = () => ({
    purchaseId,
    actionType: repair.action,
    reason,
    ...repair.parameters,
  });

  const handleDryRun = async () => {
    if (!reason.trim()) {
      setError('Reason is required.');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const result = await dryRun(buildRequest());
      setDryRunResult(result);
    } catch (e: any) {
      setError(e.message || 'Dry run failed.');
    } finally {
      setLoading(false);
    }
  };

  const handleExecute = async () => {
    if (!reason.trim()) {
      setError('Reason is required.');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const result = await executeRepair(buildRequest());
      setRepairResult(result);
      if (result.success) {
        // Allow user to see the result before closing
      }
    } catch (e: any) {
      setError(e.message || 'Execution failed.');
    } finally {
      setLoading(false);
    }
  };

  const handleDone = () => {
    if (repairResult?.success) {
      onComplete();
    } else {
      onClose();
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-header">
          <h3>Execute Repair</h3>
          <button className="btn modal-close-btn" onClick={onClose}>X</button>
        </div>

        <div className="modal-body">
          {/* Finding Info */}
          <section className="modal-section">
            <h4>Finding</h4>
            <p className="finding-description">{finding.description}</p>
            <span className={`badge severity-${finding.severity.toLowerCase()}`}>
              {finding.severity}
            </span>
          </section>

          {/* Repair Info */}
          <section className="modal-section">
            <h4>Repair Action</h4>
            <p><strong>Action:</strong> {repair.action}</p>
            <p><strong>Description:</strong> {repair.description}</p>
            {Object.keys(repair.parameters).length > 0 && (
              <div>
                <strong>Parameters:</strong>
                <table className="table table-compact">
                  <tbody>
                    {Object.entries(repair.parameters).map(([k, v]) => (
                      <tr key={k}>
                        <td className="evidence-key">{k}</td>
                        <td>{typeof v === 'object' ? JSON.stringify(v) : String(v ?? '-')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {/* Reason Input */}
          <section className="modal-section">
            <label htmlFor="repair-reason"><strong>Reason (required):</strong></label>
            <textarea
              id="repair-reason"
              className="reason-input"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Describe why this repair is being executed..."
              rows={3}
            />
          </section>

          {/* Error */}
          {error && <div className="modal-error">{error}</div>}

          {/* Dry Run Result */}
          {dryRunResult && (
            <section className="modal-section">
              <h4>Dry Run Result</h4>
              <p>{dryRunResult.description}</p>
              {dryRunResult.preview && Object.keys(dryRunResult.preview).length > 0 && (
                <table className="table table-compact">
                  <tbody>
                    {Object.entries(dryRunResult.preview).map(([k, v]) => (
                      <tr key={k}>
                        <td className="evidence-key">{k}</td>
                        <td>{typeof v === 'object' ? JSON.stringify(v) : String(v ?? '-')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
          )}

          {/* Repair Result */}
          {repairResult && (
            <section className="modal-section">
              <h4>Execution Result</h4>
              <p className={repairResult.success ? 'text-pass' : 'text-fail'}>
                {repairResult.success ? 'Repair executed successfully.' : 'Repair failed.'}
              </p>
              {repairResult.error && <p className="text-fail">{repairResult.error}</p>}
              {repairResult.verificationResult && (
                <div>
                  <h5>Verification Findings: {repairResult.verificationResult.findings.length}</h5>
                  {repairResult.verificationResult.findings.map((f, idx) => (
                    <div key={idx} className="verification-finding">
                      <span className={`badge severity-${f.severity.toLowerCase()}`}>{f.severity}</span>
                      <span> {f.ruleName}: {f.description}</span>
                    </div>
                  ))}
                </div>
              )}
            </section>
          )}
        </div>

        <div className="modal-footer">
          {!repairResult && (
            <>
              {repair.supportsDryRun && (
                <button
                  className="btn"
                  onClick={handleDryRun}
                  disabled={loading}
                >
                  {loading ? 'Running...' : 'Dry Run'}
                </button>
              )}
              <button
                className="btn btn-danger"
                onClick={handleExecute}
                disabled={loading}
              >
                {loading ? 'Executing...' : 'Execute'}
              </button>
            </>
          )}
          {repairResult && (
            <button className="btn btn-primary" onClick={handleDone}>
              {repairResult.success ? 'Done (Refresh)' : 'Close'}
            </button>
          )}
          {!repairResult && (
            <button className="btn" onClick={onClose} disabled={loading}>
              Cancel
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export default RepairDialog;
