import React, { useState, useEffect } from 'react';
import { getLoanPerformance, getCheckoutActions } from '../../api/client';

interface IncidentDataPanelProps {
  purchaseId: number;
}

function formatAmount(v: any): string {
  if (v == null) return '-';
  return '$' + Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

const IncidentDataPanel: React.FC<IncidentDataPanelProps> = ({ purchaseId }) => {
  const [loanPerf, setLoanPerf] = useState<Record<string, any> | null>(null);
  const [checkoutActions, setCheckoutActions] = useState<Record<string, any>[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    setLoaded(false);
    Promise.all([
      getLoanPerformance(purchaseId).catch(() => null),
      getCheckoutActions(purchaseId).catch(() => []),
    ]).then(([lp, ca]) => {
      setLoanPerf(lp);
      setCheckoutActions(ca);
      setLoaded(true);
    });
  }, [purchaseId]);

  if (!loaded) return null;
  if (!loanPerf && checkoutActions.length === 0) return null;

  return (
    <div style={{ marginBottom: 12 }}>
      {/* Loan Performance */}
      {loanPerf && (
        <div className="card" style={{ marginBottom: 8 }}>
          <div style={{ fontSize: 12, marginBottom: 6 }}><strong>Loan Performance (Feb 27 Incident)</strong></div>
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 12 }}>
            <div>
              <span style={{ color: '#757575' }}>Incident Group: </span>
              <strong>{loanPerf.incident_group}</strong>
            </div>
            <div>
              <span style={{ color: '#757575' }}>Status: </span>
              <span>{loanPerf.feb27_loan_status} → {loanPerf.mar18_loan_status}</span>
            </div>
            <div>
              <span style={{ color: '#757575' }}>Delinquency: </span>
              <span>{loanPerf.feb27_delinquency_bucket} → {loanPerf.mar18_delinquency_bucket}</span>
            </div>
            <div>
              <span style={{ color: '#757575' }}>DPD: </span>
              <span>{loanPerf.feb27_days_past_due} → {loanPerf.mar18_days_past_due}</span>
            </div>
            <div>
              <span style={{ color: '#757575' }}>Paid: </span>
              <span>{formatAmount(loanPerf.feb27_paid_cum_amount)} → {formatAmount(loanPerf.mar18_paid_cum_amount)}</span>
            </div>
            <div>
              <span style={{ color: '#757575' }}>Outstanding: </span>
              <span>{formatAmount(loanPerf.feb27_outstanding_balance)} → {formatAmount(loanPerf.mar18_outstanding_balance)}</span>
            </div>
            <div>
              <span style={{ color: '#757575' }}>March Payments: </span>
              <strong>{formatAmount(loanPerf.march_payments)}</strong>
            </div>
            <div>
              <span style={{ color: '#757575' }}>Refund: </span>
              <span>{loanPerf.refund_status || '-'}</span>
            </div>
            <div>
              <span style={{ color: '#757575' }}>Conclusion: </span>
              <strong style={{ color: loanPerf.conclusion?.includes('Cleared') ? '#2e7d32' : '#e65100' }}>
                {loanPerf.conclusion || '-'}
              </strong>
            </div>
          </div>
        </div>
      )}

      {/* Checkout Actions */}
      {checkoutActions.length > 0 && (
        <div className="card" style={{ marginBottom: 8 }}>
          <div style={{ fontSize: 12, marginBottom: 6 }}>
            <strong>Checkout.com Actions</strong>
            <span style={{ color: '#757575' }}> ({checkoutActions.length})</span>
          </div>
          <table className="table table-compact">
            <thead>
              <tr>
                <th>Date</th>
                <th>Action</th>
                <th>Amount</th>
                <th>Payment ID</th>
                <th>Source</th>
              </tr>
            </thead>
            <tbody>
              {checkoutActions.map((a, i) => (
                <tr key={i} className={a.action_type === 'Refund' ? 'row-refund' : ''}>
                  <td className="mono" style={{ fontSize: 11 }}>{a.action_date || '-'}</td>
                  <td>
                    <span style={{
                      color: a.action_type === 'Refund' ? '#2e7d32' : a.action_type === 'Capture' ? '#1565c0' : '#546e7a',
                      fontWeight: 600, fontSize: 11,
                    }}>
                      {a.action_type}
                    </span>
                  </td>
                  <td style={{ fontSize: 11 }}>{formatAmount(a.amount)}</td>
                  <td className="mono" style={{ fontSize: 11 }}>{a.payment_id || '-'}</td>
                  <td style={{ fontSize: 11, color: '#757575' }}>{a.source || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default IncidentDataPanel;
