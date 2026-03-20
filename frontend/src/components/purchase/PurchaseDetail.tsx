import React, { useState, useMemo, useCallback, useEffect } from 'react';
import type { PurchaseSnapshot, AnalysisResult, SuggestedRepair, Finding, ReplicationRecord } from '../../types/domain';
import { rollbackReplication, getConfig } from '../../api/client';
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

type TabKey = 'payments' | 'findings' | 'money' | 'charges' | 'notifications' | 'actions' | 'tickets' | 'timeline';

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

const PurchaseDetail: React.FC<PurchaseDetailProps> = ({ snapshot, analysis, replications = [], onRefresh, onListsChanged }) => {
  const [activeTab, setActiveTab] = useState<TabKey>('payments');
  const [repairState, setRepairState] = useState<{ repair: SuggestedRepair; finding: Finding } | null>(null);
  const [showReplicate, setShowReplicate] = useState(false);
  const [rollingBack, setRollingBack] = useState<string | null>(null);
  const [ccUrls, setCcUrls] = useState<Record<string, string>>({});

  useEffect(() => { getCallCenterUrls().then(setCcUrls); }, []);

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
    { key: 'money', label: 'Loan Info' },
    { key: 'timeline', label: 'Timeline' },
    { key: 'actions', label: 'Purchase Actions' },
    { key: 'notifications', label: 'Notifications' },
    { key: 'tickets', label: `Tickets (${snapshot.supportTickets.length})` },
    { key: 'findings', label: `Issues (${analysis.findings.length})` },
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
  const autochargeStatus = isPaidOff ? 'N/A (paid off)'
    : isCancelled ? 'N/A (cancelled)'
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
            onNavigateTab={(tab) => setActiveTab(tab as TabKey)}
          />
        )}
        {activeTab === 'findings' && (
          <FindingsPanel findings={analysis.findings} onSelectRepair={handleSelectRepair} />
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
        {activeTab === 'notifications' && (
          <NotificationsTab notifications={snapshot.notifications} purchaseId={snapshot.purchaseId} />
        )}
        {activeTab === 'actions' && (
          <ActionsTimeline actions={snapshot.paymentActions} attempts={snapshot.paymentAttempts} />
        )}
        {activeTab === 'tickets' && (
          <TicketsTab tickets={snapshot.supportTickets} />
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
