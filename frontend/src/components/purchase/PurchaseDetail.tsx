import React, { useState, useMemo, useCallback, useEffect } from 'react';
import type { PurchaseSnapshot, AnalysisResult, SuggestedRepair, Finding, ReplicationRecord } from '../../types/domain';
import { rollbackReplication, getConfig, getReviewStatus, ReviewStatus } from '../../api/client';
import PaymentsTable from './PaymentsTable';
import PaymentsTab from './PaymentsTab';
import PurchaseTimeline from './PurchaseTimeline';
import FindingsPanel from './FindingsPanel';
import MoneySummary from './MoneySummary';
import NotificationsTab from './NotificationsTab';
import ActionsTimeline from './ActionsTimeline';
import TicketsTab from './TicketsTab';
import RepairDialog from '../repair/RepairDialog';
import UnifiedChargeEventsTab from './UnifiedChargeEventsTab';
import ReplicateInline from '../replicate/ReplicateInline';
import NotesAndLists from './NotesAndLists';
import DisbursalsTab from './DisbursalsTab';
import ManipulatorPanel from '../manipulator/ManipulatorPanel';
import IncidentDataPanel from './IncidentDataPanel';

type TabKey = 'payments' | 'findings' | 'money' | 'charges' | 'disbursals' | 'notifications' | 'actions' | 'tickets' | 'timeline' | 'manipulators';

interface PurchaseDetailProps {
  snapshot: PurchaseSnapshot;
  analysis: AnalysisResult;
  replications?: ReplicationRecord[];
  onRefresh: () => void;
  onListsChanged?: () => void;
}

// Loaded once, cached in module scope
let callCenterUrls: Record<string, string> | null = null;
function getCallCenterUrls(): Promise<Record<string, string>> {
  if (callCenterUrls) return Promise.resolve(callCenterUrls);
  return getConfig().then((cfg) => {
    callCenterUrls = cfg.callCenter;
    return callCenterUrls;
  });
}

function callCenterUrl(target: string, purchaseId: number, urls: Record<string, string>): string {
  const template = urls[target.toUpperCase()] || urls['PROD'] || '';
  return template.replace('{id}', String(purchaseId));
}

function formatAmount(value: number | null | undefined): string {
  if (value == null) return '-';
  return '$' + value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDate(value: string | null | undefined): string {
  if (!value) return '-';
  return value.substring(0, 10);
}

function timeAgo(iso: string | undefined): string {
  if (!iso) return '';
  try {
    const s = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 60) return `${s}s ago`;
    if (s < 3600) return `${Math.floor(s / 60)}m ago`;
    if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
    return `${Math.floor(s / 86400)}d ago`;
  } catch { return ''; }
}

const PurchaseDetail: React.FC<PurchaseDetailProps> = ({ snapshot, analysis, replications = [], onRefresh, onListsChanged }) => {
  const [activeTab, setActiveTab] = useState<TabKey>('payments');
  const [repairState, setRepairState] = useState<{ repair: SuggestedRepair; finding: Finding } | null>(null);
  const [showReplicate, setShowReplicate] = useState(false);
  const [rollingBack, setRollingBack] = useState<string | null>(null);
  const [ccUrls, setCcUrls] = useState<Record<string, string>>({});
  const [reviewState, setReviewState] = useState<ReviewStatus | null>(null);

  useEffect(() => { getCallCenterUrls().then(setCcUrls); }, []);

  useEffect(() => {
    getReviewStatus(snapshot.purchaseId)
      .then(setReviewState)
      .catch(() => setReviewState(null));
  }, [snapshot.purchaseId]);

  const handleRollback = useCallback(async (r: ReplicationRecord) => {
    if (!window.confirm(`Rollback replication of purchase ${snapshot.purchaseId} from ${r.target}? This will DELETE the replicated data (purchase ${r.replicatedPurchaseId}).`)) {
      return;
    }
    setRollingBack(r.target);
    try {
      const result = await rollbackReplication(snapshot.purchaseId, r.target);
      if (result.success) {
        onRefresh();
      } else {
        alert(`Rollback failed: ${result.error}`);
      }
    } catch (e: any) {
      alert(`Rollback failed: ${e.message}`);
    } finally {
      setRollingBack(null);
    }
  }, [snapshot.purchaseId, onRefresh]);

  const highlightIds = useMemo(() => {
    const ids = new Set<number>();
    for (const f of analysis.findings) {
      for (const pid of f.affectedPaymentIds) {
        ids.add(pid);
      }
    }
    return Array.from(ids);
  }, [analysis.findings]);

  const tabs: { key: TabKey; label: string }[] = [
    { key: 'payments', label: 'Payments' },
    { key: 'charges', label: `Charges (${(snapshot.unifiedChargeEvents || []).length})` },
    ...(snapshot.isMultiDisbursal ? [{ key: 'disbursals' as TabKey, label: `Disbursals (${(snapshot.disbursals || []).length})` }] : []),
    { key: 'timeline', label: 'Timeline' },
    { key: 'actions', label: 'Purchase Actions' },
    { key: 'notifications', label: 'Notifications' },
    { key: 'tickets', label: `Tickets (${snapshot.supportTickets.length})` },
    { key: 'findings', label: `Issues (${analysis.findings.length})` },
    { key: 'manipulators', label: 'Fix' },
  ];

  const scheduleGap = snapshot.balanceCheck.scheduleGap;
  const moneyGap = snapshot.balanceCheck.moneyGap;

  // Determine autocharge status:
  // OFF if any active unpaid payment has manual_until in the future
  // Also OFF if CPP status is PAID_OFF or purchase status indicates cancelled
  const now = new Date().toISOString();
  const activeUnpaid = snapshot.payments.filter(
    (p) => p.isActive && !p.paidOffDate && p.type !== 30
  );
  const hasManualUntil = activeUnpaid.some(
    (p) => p.manualUntil && p.manualUntil > now
  );
  const isPaidOff = snapshot.cppStatus === 'PAID_OFF';
  const isCancelled = snapshot.purchaseStatus === 60 || snapshot.purchaseStatus === 70;
  const isAutopayPaused = snapshot.isAutopayPaused;
  const autochargeStatus = isPaidOff ? 'N/A (paid off)'
    : isCancelled ? 'N/A (cancelled)'
    : isAutopayPaused ? 'OFF (autopay paused)'
    : activeUnpaid.length === 0 ? 'N/A (no unpaid)'
    : hasManualUntil ? 'OFF (manual_until set)'
    : 'ON';

  const handleSelectRepair = (repair: SuggestedRepair, finding: Finding) => {
    setRepairState({ repair, finding });
  };

  const handleRepairComplete = () => {
    setRepairState(null);
    onRefresh();
  };

  return (
    <div className="purchase-detail">
      {/* Header */}
      <div className="purchase-header">
        <div className="purchase-header-left">
          <h2>Purchase {snapshot.purchaseId}</h2>
          <span className={`badge badge-cpp-${snapshot.cppStatus.toLowerCase()}`}>
            {snapshot.cppStatus}
          </span>
          {snapshot.isAutopayPaused && (
            <span className="badge badge-paused">AUTOPAY PAUSED</span>
          )}
          {snapshot.isMultiDisbursal && (
            <span className="badge badge-multi-disbursal">MULTI-DISBURSAL ({(snapshot.disbursals || []).length})</span>
          )}
          {autochargeStatus === 'OFF (manual_until set)' && (
            <span className="badge badge-autocharge-off">AUTOCHARGE OFF</span>
          )}
          {autochargeStatus === 'ON' && (
            <span className="badge badge-autocharge-on">AUTOCHARGE ON</span>
          )}
          {snapshot.specialStatus && (
            <span className="badge badge-special">{snapshot.specialStatus}</span>
          )}
        </div>
        <div className="purchase-header-right">
          <span className="load-timestamp">Loaded: {formatDate(snapshot.loadedAt)}</span>
          <a
            href={callCenterUrl('PROD', snapshot.purchaseId, ccUrls)}
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-small"
          >
            Call Center (prod)
          </a>
          {replications.map((r) => (
            <span key={r.target} className="replication-link-group">
              <a
                href={callCenterUrl(r.target, r.replicatedPurchaseId, ccUrls)}
                target="_blank"
                rel="noopener noreferrer"
                className="btn btn-small"
              >
                Call Center ({r.target.toLowerCase()})
              </a>
              {r.hasRollback && (
                <button
                  className="btn btn-small btn-danger"
                  onClick={() => handleRollback(r)}
                  disabled={rollingBack === r.target}
                >
                  {rollingBack === r.target ? 'Rolling back...' : 'Rollback'}
                </button>
              )}
            </span>
          ))}
          <button className="btn" onClick={() => setShowReplicate(!showReplicate)}>
            {showReplicate ? 'Hide Replicate' : 'Replicate'}
          </button>
          <button className="btn" onClick={onRefresh}>Refresh</button>
        </div>
      </div>

      {/* Work-in-progress warning */}
      {reviewState?.status === 'at-work' && reviewState.updatedBy && reviewState.updatedBy !== (localStorage.getItem('operator') || '') && (
        <div className="claim-warning">
          This purchase is being worked on by <strong>{reviewState.updatedBy}</strong>
          {reviewState.updatedAt ? ` (${timeAgo(reviewState.updatedAt)})` : ''}.
          Coordinate before making changes.
        </div>
      )}

      {/* Replicate inline panel */}
      {showReplicate && (
        <ReplicateInline
          purchaseId={snapshot.purchaseId}
          onClose={() => setShowReplicate(false)}
          onReplicated={onRefresh}
        />
      )}

      {/* Notes and Lists */}
      <NotesAndLists purchaseId={snapshot.purchaseId} onListsChanged={onListsChanged} />

      {/* Summary Bar */}
      <div className="summary-bar">
        <div className="card summary-card">
          <h4>Plan</h4>
          <div className="summary-rows">
            <div className="summary-row">
              <span className="summary-label">Total</span>
              <span className="summary-value">{formatAmount(snapshot.plan.totalAmount)}</span>
            </div>
            <div className="summary-row">
              <span className="summary-label">Financed</span>
              <span className="summary-value">{formatAmount(snapshot.plan.amountFinanced)}</span>
            </div>
            <div className="summary-row">
              <span className="summary-label">APR</span>
              <span className="summary-value">
                {snapshot.plan.nominalApr != null ? `${snapshot.plan.nominalApr}%` : '-'}
              </span>
            </div>
            <div className="summary-row">
              <span className="summary-label">Term</span>
              <span className="summary-value">
                {snapshot.plan.numInstallments != null ? `${snapshot.plan.numInstallments} mo` : '-'}
              </span>
            </div>
          </div>
        </div>

        <div className="card summary-card">
          <h4>Balance</h4>
          <div className="summary-rows">
            <div className="summary-row">
              <span className="summary-label">Collected</span>
              <span className="summary-value">{formatAmount(snapshot.balanceCheck.moneyCollected)}</span>
            </div>
            <div className="summary-row">
              <span className="summary-label">Refunded</span>
              <span className="summary-value">{formatAmount(snapshot.balanceCheck.moneyReturned)}</span>
            </div>
            <div className="summary-row">
              <span className="summary-label">Recorded collected</span>
              <span className="summary-value">{formatAmount(snapshot.balanceCheck.paidInstallments + snapshot.balanceCheck.downPayment)}</span>
            </div>
            <div className="summary-row">
              <span className="summary-label">Schedule Gap</span>
              <span className={`summary-value ${scheduleGap === 0 ? 'text-pass' : 'text-fail'}`}>
                {formatAmount(scheduleGap)}
              </span>
            </div>
            <div className="summary-row">
              <span className="summary-label">Money Gap</span>
              <span className={`summary-value ${moneyGap === 0 ? 'text-pass' : 'text-fail'}`}>
                {formatAmount(moneyGap)}
              </span>
            </div>
          </div>
        </div>

        <div className="card summary-card">
          <h4>Findings</h4>
          <div className="summary-rows findings-counts">
            {analysis.criticalCount > 0 && (
              <span className="badge severity-critical">{analysis.criticalCount} CRITICAL</span>
            )}
            {analysis.highCount > 0 && (
              <span className="badge severity-high">{analysis.highCount} HIGH</span>
            )}
            {analysis.mediumCount > 0 && (
              <span className="badge severity-medium">{analysis.mediumCount} MEDIUM</span>
            )}
            {analysis.lowCount > 0 && (
              <span className="badge severity-low">{analysis.lowCount} LOW</span>
            )}
            {analysis.findings.length === 0 && (
              <span className="text-pass">No findings</span>
            )}
          </div>
        </div>

        <div className="card summary-card">
          <h4>Worst Finding</h4>
          <div className="summary-rows">
            {analysis.findings.length > 0 ? (() => {
              const order = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
              const worst = analysis.findings.reduce((a, b) =>
                order.indexOf(a.severity) <= order.indexOf(b.severity) ? a : b
              );
              return (
                <div>
                  <span className={`badge severity-${worst.severity.toLowerCase()}`}>{worst.severity}</span>
                  <div style={{ fontSize: 12, marginTop: 4, color: '#546e7a' }}>
                    {worst.ruleName}: {worst.description.substring(0, 80)}{worst.description.length > 80 ? '...' : ''}
                  </div>
                </div>
              );
            })() : (
              <span className="text-pass">No findings</span>
            )}
          </div>
        </div>
      </div>

      {/* Incident data (shows only if data exists for this purchase) */}
      <IncidentDataPanel purchaseId={snapshot.purchaseId} />

      {/* Tabs */}
      <div className="tab-bar">
        {tabs.map((t) => (
          <button
            key={t.key}
            className={`tab ${activeTab === t.key ? 'active' : ''}`}
            onClick={() => setActiveTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      <div className="tab-content">
        {activeTab === 'timeline' && (
          <PurchaseTimeline snapshot={snapshot} />
        )}
        {activeTab === 'payments' && (
          <PaymentsTab
            snapshot={snapshot}
            highlightIds={highlightIds}
            findings={analysis.findings}
            onNavigateTab={(tab) => setActiveTab(tab as TabKey)}
          />
        )}
        {activeTab === 'findings' && (
          <FindingsPanel findings={analysis.findings} ruleResults={analysis.ruleResults} onSelectRepair={handleSelectRepair} onRescan={onRefresh} />
        )}
        {activeTab === 'charges' && (
          <UnifiedChargeEventsTab
            events={snapshot.unifiedChargeEvents || []}
            notifications={snapshot.notifications}
            tickets={snapshot.supportTickets}
            onNavigateTab={(tab) => setActiveTab(tab as TabKey)}
            purchaseId={snapshot.purchaseId}
          />
        )}
        {activeTab === 'money' && (
          <MoneySummary
            balanceCheck={snapshot.balanceCheck}
            moneyMovement={snapshot.moneyMovement}
            crossSchema={snapshot.crossSchemaReconciliation}
            unifiedChargeEvents={snapshot.unifiedChargeEvents}
          />
        )}
        {activeTab === 'disbursals' && snapshot.isMultiDisbursal && (
          <DisbursalsTab
            disbursals={snapshot.disbursals || []}
            disbursalDiffs={snapshot.disbursalDiffs || []}
            offerDisbursals={snapshot.offerDisbursals || []}
            offerDisbursalMapping={snapshot.offerDisbursalMapping || []}
            plan={snapshot.plan}
          />
        )}
        {activeTab === 'notifications' && (
          <NotificationsTab notifications={snapshot.notifications} purchaseId={snapshot.purchaseId} />
        )}
        {activeTab === 'actions' && (
          <ActionsTimeline actions={snapshot.paymentActions} attempts={snapshot.paymentAttempts} />
        )}
        {activeTab === 'tickets' && (
          <TicketsTab tickets={snapshot.supportTickets} />
        )}
        {activeTab === 'manipulators' && (
          <ManipulatorPanel purchaseId={snapshot.purchaseId} onRefresh={onRefresh} />
        )}
      </div>

      {/* Repair Dialog */}
      {repairState && (
        <RepairDialog
          repair={repairState.repair}
          finding={repairState.finding}
          purchaseId={snapshot.purchaseId}
          onClose={() => setRepairState(null)}
          onComplete={handleRepairComplete}
        />
      )}
    </div>
  );
};

export default PurchaseDetail;
