import React, { useState, useEffect, useCallback } from 'react';
import {
  getLists, createList, deleteList, addToList, removeFromList,
  getPurchaseSummary, getReviewStatuses, setReviewStatus,
  PurchaseListData, PurchaseSummaryData, ReviewStatus,
} from '../../api/client';

interface ListBrowserProps {
  activePurchaseId: number | null;
  onSelectPurchase: (id: number) => void;
  recentPurchaseIds?: number[];
  refreshTrigger?: number;
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

const RECENT_LIST_ID = -1;

const ListBrowser: React.FC<ListBrowserProps> = ({ activePurchaseId, onSelectPurchase, recentPurchaseIds = [], refreshTrigger = 0 }) => {
  const [lists, setLists] = useState<PurchaseListData[]>([]);
  const [selectedListId, setSelectedListIdState] = useState<number | null>(() => {
    const saved = localStorage.getItem('selectedListId');
    if (saved) return parseInt(saved, 10);
    return recentPurchaseIds.length > 0 ? RECENT_LIST_ID : null;
  });
  const setSelectedListId = (id: number | null) => {
    setSelectedListIdState(id);
    if (id != null) localStorage.setItem('selectedListId', String(id));
    else localStorage.removeItem('selectedListId');
  };
  const [selectedPurchaseId, setSelectedPurchaseId] = useState<number | null>(null);
  const [summary, setSummary] = useState<PurchaseSummaryData | null>(null);
  const [statuses, setStatuses] = useState<Record<number, ReviewStatus>>({});
  const [newListName, setNewListName] = useState('');
  const [loading, setLoading] = useState(false);
  const [updatingStatus, setUpdatingStatus] = useState<number | null>(null);

  const refreshLists = useCallback(async () => {
    try {
      const data = await getLists();
      setLists(data);
    } catch { /* ignore */ }
  }, []);

  useEffect(() => { refreshLists(); }, [refreshLists]);

  useEffect(() => {
    if (refreshTrigger > 0) {
      refreshLists();
      if (selectedList && selectedList.purchaseIds.length > 0) {
        getReviewStatuses(selectedList.purchaseIds).then(setStatuses).catch(() => {});
      }
    }
  }, [refreshTrigger]); // eslint-disable-line react-hooks/exhaustive-deps

  const recentList: PurchaseListData | null = recentPurchaseIds.length > 0
    ? { id: RECENT_LIST_ID, name: 'Recently Viewed', createdAt: '', purchaseIds: recentPurchaseIds }
    : null;

  const selectedList = selectedListId === RECENT_LIST_ID
    ? recentList
    : lists.find((l) => l.id === selectedListId) || null;

  const isRecentSelected = selectedListId === RECENT_LIST_ID;

  // Load review statuses for selected list
  useEffect(() => {
    if (selectedList && selectedList.purchaseIds.length > 0) {
      getReviewStatuses(selectedList.purchaseIds).then(setStatuses).catch(() => {});
    } else {
      setStatuses({});
    }
  }, [selectedList]); // eslint-disable-line react-hooks/exhaustive-deps

  // Poll statuses every 30s
  useEffect(() => {
    if (!selectedList || selectedList.purchaseIds.length === 0) return;
    const interval = setInterval(() => {
      refreshLists();
      getReviewStatuses(selectedList.purchaseIds).then(setStatuses).catch(() => {});
    }, 30000);
    return () => clearInterval(interval);
  }, [selectedList, refreshLists]);

  useEffect(() => {
    if (selectedPurchaseId == null) { setSummary(null); return; }
    setSummary(null);
    getPurchaseSummary(selectedPurchaseId).then(setSummary).catch(() => {});
  }, [selectedPurchaseId]);

  const handleCreateList = async () => {
    if (!newListName.trim()) return;
    setLoading(true);
    try {
      await createList(newListName.trim());
      setNewListName('');
      await refreshLists();
    } catch { /* ignore */ }
    setLoading(false);
  };

  const handleDeleteList = async (listId: number) => {
    if (!window.confirm('Delete this list?')) return;
    await deleteList(listId);
    if (selectedListId === listId) { setSelectedListId(null); setSelectedPurchaseId(null); }
    await refreshLists();
  };

  const handleAddActivePurchase = async (listId: number) => {
    if (activePurchaseId == null) return;
    await addToList(listId, activePurchaseId);
    await refreshLists();
  };

  const handleRemoveFromList = async (listId: number, purchaseId: number) => {
    await removeFromList(listId, purchaseId);
    if (selectedPurchaseId === purchaseId) setSelectedPurchaseId(null);
    await refreshLists();
  };

  const handleSetStatus = async (pid: number, status: string) => {
    setUpdatingStatus(pid);
    try {
      await setReviewStatus(pid, status);
      if (selectedList && selectedList.purchaseIds.length > 0) {
        const updated = await getReviewStatuses(selectedList.purchaseIds);
        setStatuses(updated);
      }
    } catch { /* ignore */ }
    setUpdatingStatus(null);
  };

  const operator = localStorage.getItem('operator') || 'anonymous';

  return (
    <div className="list-browser">
      <div className="list-browser-header">
        <h3>Purchase Lists</h3>
        <div className="list-create-form">
          <input
            type="text"
            value={newListName}
            onChange={(e) => setNewListName(e.target.value)}
            placeholder="New list name (e.g. Feb28-incident)"
            className="input-small"
            onKeyDown={(e) => e.key === 'Enter' && handleCreateList()}
          />
          <button className="btn btn-small" onClick={handleCreateList} disabled={loading || !newListName.trim()}>
            Create
          </button>
        </div>
      </div>

      <div className="list-browser-body">
        {/* Left column: list names */}
        <div className="list-names-column">
          {recentList && (
            <div
              className={`list-name-item list-name-pinned ${isRecentSelected ? 'active' : ''}`}
              onClick={() => { setSelectedListId(RECENT_LIST_ID); setSelectedPurchaseId(null); }}
            >
              <div className="list-name-title">Recently Viewed</div>
              <div className="list-name-meta">{recentList.purchaseIds.length} purchases</div>
            </div>
          )}

          {lists.length === 0 && !recentList && <p className="text-muted">No lists yet</p>}
          {lists.map((list) => (
            <div
              key={list.id}
              className={`list-name-item ${selectedListId === list.id ? 'active' : ''}`}
              onClick={() => { setSelectedListId(list.id); setSelectedPurchaseId(null); }}
            >
              <div className="list-name-title">{list.name}</div>
              <div className="list-name-meta">
                {list.purchaseIds.length} purchases
              </div>
              <div className="list-name-actions">
                {activePurchaseId != null && (
                  <button
                    className="btn btn-tiny"
                    onClick={(e) => { e.stopPropagation(); handleAddActivePurchase(list.id); }}
                    title={`Add purchase ${activePurchaseId} to this list`}
                  >
                    + Add current
                  </button>
                )}
                <button
                  className="btn btn-tiny btn-danger"
                  onClick={(e) => { e.stopPropagation(); handleDeleteList(list.id); }}
                >
                  Delete
                </button>
              </div>
            </div>
          ))}
        </div>

        {/* Middle column: purchases in selected list */}
        <div className="list-purchases-column">
          {selectedList && (
            <div className="list-purchases-title">{selectedList.name}</div>
          )}
          {!selectedList && <p className="text-muted">Select a list</p>}
          {selectedList && selectedList.purchaseIds.length === 0 && (
            <p className="text-muted">Empty list. Use "+ Add current" while viewing a purchase.</p>
          )}
          {selectedList && selectedList.purchaseIds.map((pid) => {
            const rs = statuses[pid];
            const statusStr = rs?.status || 'not-seen';
            const isWorkedByOther = statusStr === 'at-work' && rs?.updatedBy && rs.updatedBy !== operator;

            return (
              <div
                key={pid}
                className={`list-purchase-item ${selectedPurchaseId === pid ? 'active' : ''} ${pid === activePurchaseId ? 'current-purchase' : ''} ${isWorkedByOther ? 'claimed-by-other' : ''}`}
                onClick={() => {
                  getPurchaseSummary(pid).then((s) => {
                    if (s.cached) {
                      onSelectPurchase(pid);
                    } else {
                      setSelectedPurchaseId(pid);
                    }
                  }).catch(() => setSelectedPurchaseId(pid));
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 4, flex: 1 }}>
                  {statusStr !== 'not-seen' && (
                    <span className={`review-dot review-status-${statusStr}`} title={statusStr} />
                  )}
                  <span className="mono">{pid}</span>
                  {statusStr !== 'not-seen' && rs?.updatedBy && (
                    <span className={`sync-claim-badge ${rs.updatedBy === operator ? 'mine' : 'other'}`}>
                      {rs.updatedBy}
                    </span>
                  )}
                </div>
                <div style={{ display: 'flex', gap: 2 }} onClick={(e) => e.stopPropagation()}>
                  {updatingStatus === pid && (
                    <span style={{ fontSize: 10, color: '#757575' }}>...</span>
                  )}
                  {updatingStatus !== pid && (
                    <button
                      className="btn btn-tiny btn-danger"
                      onClick={() => handleRemoveFromList(selectedList.id, pid)}
                    >
                      x
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        {/* Right column: purchase summary */}
        <div className="list-summary-column">
          {selectedPurchaseId == null && <p className="text-muted">Select a purchase</p>}
          {selectedPurchaseId != null && summary == null && <p className="text-muted">Loading...</p>}
          {summary && (() => {
            const rs = statuses[summary.purchaseId];
            return (
              <div className="purchase-summary-card">
                <h4>Purchase {summary.purchaseId}</h4>
                {summary.cached ? (
                  <>
                    <div className="summary-field">
                      <span className="summary-label">Cached</span>
                      <span className="summary-value">{timeAgo(summary.cachedAt)}</span>
                    </div>
                    {summary.replications.length > 0 ? (
                      <div className="summary-field">
                        <span className="summary-label">Replicated to</span>
                        <span className="summary-value">
                          {summary.replications.map((r) => `${r.target} (${r.replicatedPurchaseId})`).join(', ')}
                        </span>
                      </div>
                    ) : (
                      <div className="summary-field">
                        <span className="summary-label">Replicated</span>
                        <span className="summary-value text-muted">No</span>
                      </div>
                    )}
                  </>
                ) : (
                  <p className="text-muted">Not cached. Load to see details.</p>
                )}

                {/* Status info */}
                {rs && rs.status !== 'not-seen' && (
                  <div className="summary-field">
                    <span className="summary-label">Status</span>
                    <span className="summary-value">{rs.status} by {rs.updatedBy} ({timeAgo(rs.updatedAt)})</span>
                  </div>
                )}

                {/* Status buttons */}
                <div style={{ marginTop: 8, display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                  {['at-work', 'done', 'need-fixing', 'not-seen'].map((s) => (
                    <button
                      key={s}
                      className={`btn btn-tiny ${rs?.status === s ? 'btn-active' : ''}`}
                      onClick={() => handleSetStatus(summary.purchaseId, s)}
                      disabled={updatingStatus === summary.purchaseId}
                    >
                      {s}
                    </button>
                  ))}
                </div>

                <button
                  className="btn btn-primary btn-small"
                  style={{ marginTop: 8 }}
                  onClick={() => onSelectPurchase(summary.purchaseId)}
                >
                  Open purchase
                </button>
              </div>
            );
          })()}
        </div>
      </div>
    </div>
  );
};

export default ListBrowser;
