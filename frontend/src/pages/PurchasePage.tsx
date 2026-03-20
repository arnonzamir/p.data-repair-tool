import React, { useState, useEffect, useCallback } from 'react';
import type { AnalysisResult, PurchaseAnalysisResponse, Severity } from '../types/domain';
import { analyzePurchase, analyzeBatch, getPurchaseSummary } from '../api/client';
import PurchaseDetail from '../components/purchase/PurchaseDetail';
import ListBrowser from '../components/common/ListBrowser';

interface PurchasePageProps {
  purchaseId: number | null;
  onSelectPurchase: (id: number) => void;
  onClearPurchase: () => void;
}

function SeverityBadge({ severity }: { severity?: Severity | null }) {
  if (!severity) return <span className="text-muted">--</span>;
  const cls = `severity severity-${severity.toLowerCase()}`;
  return <span className={cls}>{severity}</span>;
}

function parsePurchaseIds(raw: string): number[] {
  return raw
    .split(/[\n,]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
    .map(Number)
    .filter((n) => !isNaN(n) && n > 0);
}

function formatCachedAt(cachedAt: string | null): string {
  if (!cachedAt) return '';
  try {
    const d = new Date(cachedAt);
    return d.toLocaleString();
  } catch {
    return cachedAt;
  }
}

function timeAgo(cachedAt: string | null): string {
  if (!cachedAt) return '';
  try {
    const d = new Date(cachedAt);
    const seconds = Math.floor((Date.now() - d.getTime()) / 1000);
    if (seconds < 60) return `${seconds}s ago`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
    return `${Math.floor(seconds / 86400)}d ago`;
  } catch {
    return '';
  }
}

// ---------------------------------------------------------------------------
// Recently viewed -- persisted in localStorage, capped at 50
// ---------------------------------------------------------------------------

const RECENT_KEY = 'purchase-repair-recent';
const RECENT_MAX = 50;

interface RecentEntry {
  id: number;
  viewedAt: string;
}

function loadRecent(): RecentEntry[] {
  try {
    return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
  } catch { return []; }
}

function addRecent(purchaseId: number): RecentEntry[] {
  const list = loadRecent().filter((e) => e.id !== purchaseId);
  list.unshift({ id: purchaseId, viewedAt: new Date().toISOString() });
  const trimmed = list.slice(0, RECENT_MAX);
  localStorage.setItem(RECENT_KEY, JSON.stringify(trimmed));
  return trimmed;
}

export function PurchasePage({ purchaseId, onSelectPurchase, onClearPurchase }: PurchasePageProps) {
  // Recently viewed
  const [recent, setRecent] = useState<RecentEntry[]>(loadRecent);
  const [listsVersion, setListsVersion] = useState(0);
  const [drawerOpen, setDrawerOpen] = useState(!purchaseId); // open by default in search mode

  // Search mode state
  const [singleId, setSingleId] = useState('');
  const [batchInput, setBatchInput] = useState('');
  const [batchResults, setBatchResults] = useState<AnalysisResult[] | null>(null);
  const [batchLoading, setBatchLoading] = useState(false);
  const [batchError, setBatchError] = useState<string | null>(null);

  // Detail mode state
  const [detailData, setDetailData] = useState<PurchaseAnalysisResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshingPurchaseId, setRefreshingPurchaseId] = useState<number | null>(null);
  const [loadProgress, setLoadProgress] = useState<{
    step: number; total: number; name: string; status: string; detail: string;
  } | null>(null);

  const loadDetail = useCallback((id: number, refresh = false) => {
    // Block parallel refreshes
    if (refresh && refreshingPurchaseId != null) {
      console.log(`[loadDetail] blocked -- already refreshing purchase ${refreshingPurchaseId}`);
      return;
    }

    if (refresh) {
      setRefreshing(true);
      setRefreshingPurchaseId(id);
    } else {
      setDetailLoading(true);
      setDetailData(null);
    }
    setDetailError(null);
    setLoadProgress(null);

    console.log(`[loadDetail] id=${id} refresh=${refresh}`);

    if (!refresh) {
      // Check if cached via lightweight summary endpoint first
      console.log(`[loadDetail] checking cache status`);
      getPurchaseSummary(id)
        .then((summary) => {
          console.log(`[loadDetail] summary response:`, JSON.stringify(summary));
          if (summary.cached) {
            // Cached -- use regular API (instant, no progress needed)
            console.log(`[loadDetail] cached, loading via API`);
            return analyzePurchase(id, false).then((result) => {
              console.log(`[loadDetail] cached load success`);
              setDetailData(result);
              setRecent(addRecent(id));
              setDetailLoading(false);
            });
          } else {
            // Not cached -- use SSE stream with progress, force refresh to skip cache check on server
            console.log(`[loadDetail] not cached, using SSE stream with refresh=true`);
            startStreamLoad(id, true);
          }
        })
        .catch(() => {
          // Summary check failed -- fall back to SSE stream
          console.log(`[loadDetail] summary check failed, using SSE stream`);
          startStreamLoad(id, false);
        });
    } else {
      // Refresh always uses SSE stream to show progress
      console.log(`[loadDetail] refresh mode, starting SSE stream`);
      startStreamLoad(id, true);
    }

    function startStreamLoad(purchaseId: number, forceRefresh: boolean) {
      const baseUrl = process.env.REACT_APP_API_URL || 'http://localhost:8090';
      const url = `${baseUrl}/api/v1/purchases/${purchaseId}/load-stream?refresh=${forceRefresh}`;
      console.log(`[SSE] connecting to ${url}`);
      const es = new EventSource(url);

      es.onopen = () => {
        console.log(`[SSE] connection opened, readyState=${es.readyState}`);
      };

      es.addEventListener('progress', (e: any) => {
        console.log(`[SSE] progress event:`, e.data);
        try {
          setLoadProgress(JSON.parse(e.data));
        } catch (err) {
          console.error(`[SSE] progress parse error:`, err);
        }
      });

      es.addEventListener('complete', (e: any) => {
        console.log(`[SSE] complete event received, data length=${e.data?.length}`);
        try {
          const result = JSON.parse(e.data);
          console.log(`[SSE] parsed complete, purchaseId=${result.snapshot?.purchaseId}, findings=${result.analysis?.findings?.length}`);
          setDetailData(result);
          setRecent(addRecent(purchaseId));
        } catch (err) {
          console.error(`[SSE] complete parse error:`, err);
          setDetailError('Failed to parse response');
        }
        setDetailLoading(false);
        setRefreshing(false);
        setRefreshingPurchaseId(null);
        setLoadProgress(null);
        es.close();
      });

      es.addEventListener('load-error', (e: any) => {
        console.log(`[SSE] load-error event:`, e.data);
        try {
          if (e.data) {
            const data = JSON.parse(e.data);
            setDetailError(data.message || 'Load failed');
          }
        } catch { /* ignore */ }
        setDetailLoading(false);
        setRefreshing(false);
        setRefreshingPurchaseId(null);
        es.close();
      });

      es.onmessage = (e) => {
        // Catch-all for unnamed events
        console.log(`[SSE] unnamed message event:`, e.data?.substring(0, 200));
      };

      es.onerror = (e) => {
        console.log(`[SSE] onerror fired, readyState=${es.readyState}`, e);
        if (es.readyState === EventSource.CLOSED) {
          console.log(`[SSE] connection closed (normal)`);
          return;
        }
        if (es.readyState === EventSource.CONNECTING) {
          console.log(`[SSE] reconnecting...`);
          return;
        }
        setDetailError('Connection to server lost');
        setDetailLoading(false);
        setRefreshing(false);
        setRefreshingPurchaseId(null);
        es.close();
      };
    }
  }, []);

  useEffect(() => {
    if (purchaseId != null) {
      loadDetail(purchaseId, false);
    }
  }, [purchaseId, loadDetail]);

  const handleSingleLoad = () => {
    const id = Number(singleId.trim());
    if (!isNaN(id) && id > 0) {
      onSelectPurchase(id);
    }
  };

  const handleBatchAnalyze = async () => {
    const ids = parsePurchaseIds(batchInput);
    if (ids.length === 0) return;
    setBatchLoading(true);
    setBatchError(null);
    setBatchResults(null);
    try {
      const resp = await analyzeBatch(ids);
      setBatchResults(resp.results);
    } catch (err: any) {
      setBatchError(err?.message || 'Batch analysis failed');
    } finally {
      setBatchLoading(false);
    }
  };

  const recentStrip = recent.length > 0 ? (
    <div className="recent-strip">
      <span className="recent-label">Recent:</span>
      <div className="recent-items">
        {recent.slice(0, 5).map((entry) => (
          <button
            key={entry.id}
            className={`recent-item ${entry.id === purchaseId ? 'active' : ''}`}
            onClick={() => onSelectPurchase(entry.id)}
            title={`Viewed ${timeAgo(entry.viewedAt)}`}
          >
            {entry.id}
          </button>
        ))}
        {recent.length > 5 && (
          <span className="recent-overflow">+{recent.length - 5} more</span>
        )}
      </div>
    </div>
  ) : null;

  // Drawer content (search, lists, batch)
  const drawerContent = (
    <div className="drawer-content">
      <div className="drawer-section">
        <div className="drawer-search-row">
          <input
            type="number"
            className="form-input"
            value={singleId}
            onChange={(e) => setSingleId(e.target.value)}
            placeholder="Purchase ID"
            onKeyDown={(e) => e.key === 'Enter' && handleSingleLoad()}
          />
          <button className="btn btn-primary btn-small" onClick={handleSingleLoad} disabled={!singleId.trim()}>
            Load
          </button>
        </div>
      </div>

      <div className="drawer-section">
        <ListBrowser activePurchaseId={purchaseId} onSelectPurchase={onSelectPurchase} recentPurchaseIds={recent.map(e => e.id)} refreshTrigger={listsVersion} />
      </div>

      <div className="drawer-section drawer-batch">
        <h4>Batch Analysis</h4>
        <textarea
          className="form-input"
          value={batchInput}
          onChange={(e) => setBatchInput(e.target.value)}
          placeholder={"IDs (one per line)"}
          rows={3}
        />
        <button
          className="btn btn-small"
          onClick={handleBatchAnalyze}
          disabled={batchLoading || parsePurchaseIds(batchInput).length === 0}
          style={{ marginTop: 4 }}
        >
          {batchLoading ? 'Analyzing...' : 'Batch Analyze'}
        </button>
        {batchError && <div className="error-banner mt-16">{batchError}</div>}
        {batchResults && (
          <div className="mt-16">
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Issues</th>
                  <th>Severity</th>
                </tr>
              </thead>
              <tbody>
                {batchResults.map((r) => (
                  <tr key={r.purchaseId} className="clickable-row" onClick={() => onSelectPurchase(r.purchaseId)}>
                    <td className="mono">{r.purchaseId}</td>
                    <td>{r.findings.length}</td>
                    <td><SeverityBadge severity={r.overallSeverity} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );

  // Detail mode
  if (purchaseId != null) {
    const cachedAt = detailData?.cachedAt ?? null;

    return (
      <div className="purchase-page-with-drawer">
        <div className={`purchase-drawer ${drawerOpen ? 'open' : 'closed'}`}>
          <button className="drawer-toggle" onClick={() => setDrawerOpen(!drawerOpen)}>
            <span className="drawer-toggle-label">Search & Lists</span>
          </button>
          {drawerOpen && drawerContent}
        </div>
        <div className="purchase-main">
          {recentStrip}

          {(detailLoading || refreshing) && (
            <div className="load-progress-panel">
              <div className="load-progress-header">
                {refreshing ? 'Refreshing' : 'Loading'} purchase {refreshingPurchaseId || purchaseId} from Snowflake...
              </div>
              {loadProgress && (
                <>
                  <div className="load-progress-bar-track">
                    <div
                      className="load-progress-bar-fill"
                      style={{ width: `${Math.round((loadProgress.step / loadProgress.total) * 100)}%` }}
                    />
                  </div>
                  <div className="load-progress-step">
                    <span className={`load-step-indicator ${loadProgress.status}`} />
                    <span className="load-step-name">{loadProgress.name}</span>
                    <span className="load-step-detail">{loadProgress.detail}</span>
                    <span className="load-step-counter">{loadProgress.step}/{loadProgress.total}</span>
                  </div>
                </>
              )}
              {!loadProgress && <div className="load-progress-connecting">Connecting...</div>}
            </div>
          )}
          {detailError && <div className="error-banner">{detailError}</div>}
          {detailData && (
            <>
              <div className="cache-bar">
                <span className="cache-timestamp">
                  Cached: {formatCachedAt(cachedAt)} ({timeAgo(cachedAt)})
                </span>
                <button
                  className="btn btn-rescan"
                  onClick={() => {
                    analyzePurchase(purchaseId, false).then((result) => {
                      setDetailData(result);
                    });
                  }}
                >
                  Rescan rules
                </button>
                <button
                  className="btn btn-refresh"
                  onClick={() => loadDetail(purchaseId, true)}
                  disabled={refreshing}
                >
                  {refreshing
                    ? (refreshingPurchaseId === purchaseId ? 'Refreshing...' : `Busy (refreshing ${refreshingPurchaseId})`)
                    : 'Refresh from Snowflake'}
                </button>
              </div>
              <PurchaseDetail
                snapshot={detailData.snapshot}
                analysis={detailData.analysis}
                replications={detailData.replications || []}
                onRefresh={() => loadDetail(purchaseId, false)}
                onListsChanged={() => setListsVersion((v) => v + 1)}
              />
            </>
          )}
        </div>
      </div>
    );
  }

  // Search mode (no purchase selected -- show drawer content as main)
  return (
    <div className="purchase-page-with-drawer">
      <div className="purchase-drawer open">
        {drawerContent}
      </div>
      <div className="purchase-main">
        {recentStrip}
        <div className="search-mode-welcome">
          <h2>Purchase Repair Tool</h2>
          <p>Enter a purchase ID in the search box or select one from a list.</p>
        </div>
      </div>
    </div>
  );
}
