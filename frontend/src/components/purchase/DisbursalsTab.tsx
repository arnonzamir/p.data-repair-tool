import React from 'react';
import type { Disbursal, DisbursalDiff, PlanInfo, OfferDisbursal, OfferDisbursalMapping } from '../../types/domain';

interface DisbursalsTabProps {
  disbursals: Disbursal[];
  disbursalDiffs: DisbursalDiff[];
  offerDisbursals: OfferDisbursal[];
  offerDisbursalMapping: OfferDisbursalMapping[];
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

function addMonths(dateStr: string, months: number): string {
  const d = new Date(dateStr + 'T00:00:00');
  d.setMonth(d.getMonth() + months);
  return d.toISOString().substring(0, 10);
}

interface MergedRow {
  offerDisbursal?: OfferDisbursal;
  actualDisbursal?: Disbursal;
  plannedDate?: string;
  isUnplanned: boolean;
}

function buildMergedRows(
  offerDisbursals: OfferDisbursal[],
  disbursals: Disbursal[],
  mapping: OfferDisbursalMapping[],
): MergedRow[] {
  const actualToOffer = new Map<number, number>();
  const offerToActual = new Map<number, number>();
  for (const m of mapping) {
    actualToOffer.set(m.actualDisbursalId, m.offerDisbursalId);
    offerToActual.set(m.offerDisbursalId, m.actualDisbursalId);
  }

  const actualById = new Map(disbursals.map(d => [d.id, d]));
  const usedActualIds = new Set<number>();

  // Find the base date: the actual date of the cycle-0 offer disbursal
  const cycle0Offer = offerDisbursals.find(od => od.cycle === 0);
  let baseDate: string | undefined;
  if (cycle0Offer) {
    const cycle0ActualId = offerToActual.get(cycle0Offer.id);
    const cycle0Actual = cycle0ActualId != null ? actualById.get(cycle0ActualId) : undefined;
    if (cycle0Actual?.disbursalDate) {
      baseDate = cycle0Actual.disbursalDate.substring(0, 10);
    }
  }
  // Fallback: use earliest actual disbursal date
  if (!baseDate && disbursals.length > 0) {
    const sorted = [...disbursals].sort((a, b) =>
      (a.disbursalDate || '').localeCompare(b.disbursalDate || '')
    );
    if (sorted[0]?.disbursalDate) {
      baseDate = sorted[0].disbursalDate.substring(0, 10);
    }
  }

  const rows: MergedRow[] = [];

  for (const od of offerDisbursals) {
    const actualId = offerToActual.get(od.id);
    const actual = actualId != null ? actualById.get(actualId) : undefined;
    if (actual) usedActualIds.add(actual.id);
    const plannedDate = baseDate ? addMonths(baseDate, od.cycle) : undefined;
    rows.push({ offerDisbursal: od, actualDisbursal: actual, plannedDate, isUnplanned: false });
  }

  for (const d of disbursals) {
    if (!usedActualIds.has(d.id)) {
      const offerId = actualToOffer.get(d.id);
      if (offerId != null) continue;
      rows.push({ offerDisbursal: undefined, actualDisbursal: d, isUnplanned: true });
    }
  }

  rows.sort((a, b) => {
    const dateA = a.actualDisbursal?.disbursalDate || a.plannedDate || '';
    const dateB = b.actualDisbursal?.disbursalDate || b.plannedDate || '';
    return dateA.localeCompare(dateB);
  });

  return rows;
}

const DisbursalsTab: React.FC<DisbursalsTabProps> = ({ disbursals, disbursalDiffs, offerDisbursals, offerDisbursalMapping, plan }) => {
  const totalDisbursed = disbursals.reduce((sum, d) => sum + d.amount, 0);
  const isPast = (dateStr?: string) => {
    if (!dateStr) return false;
    return new Date(dateStr) < new Date();
  };

  const diffsByDate = new Map<string, DisbursalDiff[]>();
  for (const diff of disbursalDiffs) {
    const key = diff.disbursalDate?.substring(0, 10) || '';
    const existing = diffsByDate.get(key) || [];
    existing.push(diff);
    diffsByDate.set(key, existing);
  }

  const hasOfferData = offerDisbursals.length > 0;
  const mergedRows = hasOfferData
    ? buildMergedRows(offerDisbursals, disbursals, offerDisbursalMapping)
    : disbursals.map(d => ({ actualDisbursal: d, isUnplanned: false } as MergedRow));

  const actualInOrder = mergedRows
    .filter(r => r.actualDisbursal)
    .map(r => r.actualDisbursal!);

  const cumulativeMap = new Map<number, number>();
  let cumulative = 0;
  for (const d of actualInOrder) {
    cumulative += d.amount;
    cumulativeMap.set(d.id, cumulative);
  }

  return (
    <div>
      {/* Summary */}
      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', gap: 24, fontSize: 13, flexWrap: 'wrap' }}>
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
          {hasOfferData && (
            <>
              <div>
                <span style={{ color: '#757575' }}>Planned: </span>
                <strong>{offerDisbursals.length}</strong>
              </div>
              <div>
                <span style={{ color: '#757575' }}>Unplanned: </span>
                <strong>{mergedRows.filter(r => r.isUnplanned).length}</strong>
              </div>
            </>
          )}
        </div>
      </div>

      {/* Table */}
      <table className="table">
        <thead>
          <tr>
            <th>#</th>
            {hasOfferData && (
              <>
                <th>Cycle</th>
                <th>Planned</th>
                <th>Actual</th>
              </>
            )}
            {!hasOfferData && <th>Date</th>}
            <th>Amount</th>
            <th>Status</th>
            <th>Cumulative</th>
            <th>Adjustments</th>
          </tr>
          {hasOfferData && (
            <tr style={{ fontSize: 11, color: '#757575' }}>
              <th></th>
              <th></th>
              <th>Date / % / Amount</th>
              <th>Date / % / Amount</th>
              <th></th>
              <th></th>
              <th></th>
              <th></th>
            </tr>
          )}
        </thead>
        <tbody>
          {mergedRows.map((row, idx) => {
            const actual = row.actualDisbursal;
            const offer = row.offerDisbursal;
            const released = actual ? isPast(actual.disbursalDate) : false;
            const cum = actual ? cumulativeMap.get(actual.id) : undefined;
            const pctOfTotal = actual && plan.amountFinanced > 0
              ? ((actual.amount / plan.amountFinanced) * 100).toFixed(2)
              : null;
            const diffs = actual
              ? (diffsByDate.get(actual.disbursalDate?.substring(0, 10) || '') || [])
              : [];
            const plannedAmount = offer && plan.amountFinanced > 0
              ? plan.amountFinanced * (offer.percent / 100)
              : null;

            const actualDateStr = actual?.disbursalDate?.substring(0, 10);
            const plannedDateStr = row.plannedDate;
            const dateMismatch = plannedDateStr && actualDateStr && plannedDateStr !== actualDateStr;

            return (
              <tr
                key={actual?.id ?? `offer-${offer?.id}`}
                className={`${row.isUnplanned ? 'row-unplanned-disbursal' : ''} ${actual && !released ? 'row-pending-disbursal' : ''}`}
              >
                <td>{idx + 1}</td>
                {hasOfferData && (
                  <>
                    <td>{offer ? offer.cycle : '-'}</td>
                    <td className="mono" style={{ fontSize: 12 }}>
                      {offer ? (
                        <>
                          <div>{plannedDateStr || '-'}</div>
                          <div style={{ color: '#757575' }}>
                            {Number(offer.percent).toFixed(2)}% = {plannedAmount != null ? formatAmount(plannedAmount) : '-'}
                          </div>
                        </>
                      ) : '-'}
                    </td>
                    <td className="mono" style={{ fontSize: 12 }}>
                      {actual ? (
                        <>
                          <div>
                            {actualDateStr}
                            {dateMismatch && (
                              <span style={{ color: '#e65100', fontSize: 10, marginLeft: 3 }} title={`Planned: ${plannedDateStr}`}>
                                (moved)
                              </span>
                            )}
                          </div>
                          <div style={{ color: '#757575' }}>
                            {pctOfTotal}% = {formatAmount(actual.amount)}
                            {plannedAmount != null && Math.abs(actual.amount - plannedAmount) > 0.01 && (
                              <span style={{ color: actual.amount > plannedAmount ? '#2e7d32' : '#c62828', marginLeft: 3 }}>
                                ({actual.amount > plannedAmount ? '+' : ''}{formatAmount(actual.amount - plannedAmount)})
                              </span>
                            )}
                          </div>
                        </>
                      ) : (
                        <span style={{ color: '#9e9e9e' }}>-</span>
                      )}
                    </td>
                  </>
                )}
                {!hasOfferData && (
                  <td className="mono">{actualDateStr || '-'}</td>
                )}
                <td>
                  {actual ? formatAmount(actual.amount) : '-'}
                </td>
                <td>
                  {actual ? (
                    <span className={`badge ${released ? 'badge-released' : 'badge-pending-disbursal'}`}>
                      {released ? 'Released' : 'Pending'}
                    </span>
                  ) : (
                    <span className="badge" style={{ background: '#e0e0e0', color: '#616161' }}>No actual</span>
                  )}
                  {row.isUnplanned && (
                    <div style={{ fontSize: 10, color: '#e65100', marginTop: 2 }}>extra</div>
                  )}
                </td>
                <td>{cum != null ? formatAmount(cum) : '-'}</td>
                <td>
                  {diffs.length > 0 ? (
                    diffs.map((diff, i) => (
                      <div key={i} className="disbursal-diff">
                        {diff.amountDiff > 0 ? '+' : ''}{formatAmount(diff.amountDiff)}
                        {diff.paymentActionId ? ` (action #${diff.paymentActionId})` : ''}
                      </div>
                    ))
                  ) : '-'}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

export default DisbursalsTab;
