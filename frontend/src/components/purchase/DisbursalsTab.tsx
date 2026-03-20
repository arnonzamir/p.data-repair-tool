import React from 'react';
import type { Disbursal, DisbursalDiff, PlanInfo } from '../../types/domain';

interface DisbursalsTabProps {
  disbursals: Disbursal[];
  disbursalDiffs: DisbursalDiff[];
  plan: PlanInfo;
}

function formatAmount(v: number | null | undefined): string {
  if (v == null) return '-';
  return '$' + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDate(v: string | null | undefined): string {
  if (!v) return '-';
  return v.substring(0, 10);
}

const DisbursalsTab: React.FC<DisbursalsTabProps> = ({ disbursals, disbursalDiffs, plan }) => {
  const totalDisbursed = disbursals.reduce((sum, d) => sum + d.amount, 0);
  const isPast = (dateStr?: string) => {
    if (!dateStr) return false;
    return new Date(dateStr) < new Date();
  };

  // Group diffs by disbursal date (since the diff table links by date, not ID)
  const diffsByDate = new Map<string, DisbursalDiff[]>();
  for (const diff of disbursalDiffs) {
    const key = diff.disbursalDate?.substring(0, 10) || '';
    const existing = diffsByDate.get(key) || [];
    existing.push(diff);
    diffsByDate.set(key, existing);
  }

  return (
    <div>
      {/* Summary */}
      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', gap: 24, fontSize: 13 }}>
          <div>
            <span style={{ color: '#757575' }}>Amount financed: </span>
            <strong>{formatAmount(plan.amountFinanced)}</strong>
          </div>
          <div>
            <span style={{ color: '#757575' }}>Total disbursed: </span>
            <strong>{formatAmount(totalDisbursed)}</strong>
          </div>
          <div>
            <span style={{ color: '#757575' }}>Disbursals: </span>
            <strong>{disbursals.length}</strong>
          </div>
          <div>
            <span style={{ color: '#757575' }}>Released: </span>
            <strong>{disbursals.filter(d => isPast(d.disbursalDate)).length}</strong>
          </div>
          <div>
            <span style={{ color: '#757575' }}>Pending: </span>
            <strong>{disbursals.filter(d => !isPast(d.disbursalDate)).length}</strong>
          </div>
        </div>
      </div>

      {/* Disbursals table */}
      <table className="table">
        <thead>
          <tr>
            <th>#</th>
            <th>Release Date</th>
            <th>Amount</th>
            <th>Status</th>
            <th>Cumulative</th>
            <th>% of Total</th>
            <th>Adjustments</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          {disbursals.map((d, idx) => {
            const released = isPast(d.disbursalDate);
            const cumulative = disbursals.slice(0, idx + 1).reduce((sum, dd) => sum + dd.amount, 0);
            const pctOfTotal = plan.amountFinanced > 0 ? ((d.amount / plan.amountFinanced) * 100).toFixed(1) : '-';
            const diffs = diffsByDate.get(d.disbursalDate?.substring(0, 10) || '') || [];

            return (
              <tr key={d.id} className={released ? '' : 'row-pending-disbursal'}>
                <td>{idx + 1}</td>
                <td className="mono">{formatDate(d.disbursalDate)}</td>
                <td>{formatAmount(d.amount)}</td>
                <td>
                  <span className={`badge ${released ? 'badge-released' : 'badge-pending-disbursal'}`}>
                    {released ? 'Released' : 'Pending'}
                  </span>
                </td>
                <td>{formatAmount(cumulative)}</td>
                <td>{pctOfTotal}%</td>
                <td>
                  {diffs.length > 0 ? (
                    diffs.map((diff, i) => (
                      <span key={i} className="disbursal-diff">
                        {diff.amountDiff > 0 ? '+' : ''}{formatAmount(diff.amountDiff)}
                        {diff.paymentActionId ? ` (action #${diff.paymentActionId})` : ''}
                      </span>
                    ))
                  ) : '-'}
                </td>
                <td className="mono">{formatDate(d.creationTime)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

export default DisbursalsTab;
