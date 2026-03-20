import React, { useState, useEffect, useCallback } from 'react';
import {
  getLists, createList, deleteList, addToList, removeFromList,
  getPurchaseSummary, getReviewStatuses, PurchaseListData, PurchaseSummaryData, ReviewStatus,
} from '../../api/client';

interface ListBrowserProps {
  /** Currently viewed purchase -- show "add to list" controls */
  activePurchaseId: number | null;
  /** Navigate to a purchase */
  onSelectPurchase: (id: number) => void;
  /** Recently viewed purchase IDs (shown as pinned top list) */
  recentPurchaseIds?: number[];
  /** Increment to trigger a refresh without losing selection */
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

const RECENT_LIST_ID = -1; // Virtual ID for the "Recently Viewed" list

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

  const refreshLists = useCallback(async () => {
    try {
      const data = await getLists();
      setLists(data);
    } catch { /* ignore */ }
  }, []);

  useEffect(() => { refreshLists(); }, [refreshLists]);

  // Refresh when trigger changes (status update, list membership change)
  useEffect(() => {
    if (refreshTrigger > 0) {
      refreshLists();
      // Also refresh statuses for currently selected list
      if (selectedList && selectedList.purchaseIds.length > 0) {
        getReviewStatuses(selectedList.purchaseIds).then(setStatuses).catch(() => {});
      }
    }
  }, [refreshTrigger]); // eslint-disable-line react-hooks/exhaustive-deps

  // Build the virtual "Recently Viewed" list
  const recentList: PurchaseListData | null = recentPurchaseIds.length > 0
    ? { id: RECENT_LIST_ID, name: 'Recently Viewed', createdAt: '', purchaseIds: recentPurchaseIds }
    : null;

  const selectedList = selectedListId === RECENT_LIST_ID
    ? recentList
    : lists.find((l) => l.id === selectedListId) || null;

  const isRecentSelected = selectedListId === RECENT_LIST_ID;

  // Load review statuses for the selected list's purchases
  useEffect(() => {
    if (selectedList && selectedList.purchaseIds.length > 0) {
      getReviewStatuses(selectedList.purchaseIds).then(setStatuses).catch(() => {});
    } else {
      setStatuses({});
    }
  }, [selectedList]);

  // Load summary when a purchase in a list is clicked
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
          {/* Recently Viewed -- always at top */}
          {recentList && (
            <div
              className={`list-name-item list-name-pinned ${isRecentSelected ? 'active' : ''}`}
              onClick={() => { setSelectedListId(RECENT_LIST_ID); setSelectedPurchaseId(null); }}
            >
              <div className="list-name-title">Recently Viewed</div>
              <div className="list-name-meta">{recentList.purchaseIds.length} purchases</div>
            </div>
          )}

          {/* User-created lists */}
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
          {!selectedList && <p className="text-muted">Select a list</p>}
          {selectedList && selectedList.purchaseIds.length === 0 && (
            <p className="text-muted">Empty list. Use "+ Add current" while viewing a purchase.</p>
          )}
          {selectedList && selectedList.purchaseIds.map((pid) => (
            <div
              key={pid}
              className={`list-purchase-item ${selectedPurchaseId === pid ? 'active' : ''} ${pid === activePurchaseId ? 'current-purchase' : ''}`}
              onClick={() => {
                // If cached, navigate directly. Otherwise show summary.
                getPurchaseSummary(pid).then((s) => {
                  if (s.cached) {
                    onSelectPurchase(pid);
                  } else {
                    setSelectedPurchaseId(pid);
                  }
                }).catch(() => setSelectedPurchaseId(pid));
              }}
            >
              {statuses[pid] && statuses[pid].status !== 'not-seen' && (
                <span className={`review-dot review-status-${statuses[pid].status}`} title={statuses[pid].status} />
              )}
              <span className="mono">{pid}</span>
              {!isRecentSelected && (
                <button
                  className="btn btn-tiny btn-danger"
                  onClick={(e) => { e.stopPropagation(); handleRemoveFromList(selectedList.id, pid); }}
                >
                  x
                </button>
              )}
            </div>
          ))}
        </div>

        {/* Right column: purchase summary */}
        <div className="list-summary-column">
          {selectedPurchaseId == null && <p className="text-muted">Select a purchase</p>}
          {selectedPurchaseId != null && summary == null && <p className="text-muted">Loading...</p>}
          {summary && (
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
                <p className="text-muted">Not cached -- load to see details</p>
              )}
              <button
                className="btn btn-primary btn-small"
                style={{ marginTop: 8 }}
                onClick={() => onSelectPurchase(summary.purchaseId)}
              >
                Open purchase
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default ListBrowser;
