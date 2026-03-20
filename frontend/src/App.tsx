import React, { useState, useEffect, useCallback } from 'react';
import './App.css';
import { PurchasePage } from './pages/PurchasePage';
import { RulesPage } from './pages/RulesPage';
// ReplicatePage removed -- replication is accessible from purchase detail view
import { AuditPage } from './pages/AuditPage';

type PageId = 'purchase' | 'rules' | 'audit';

const NAV_TABS: { id: PageId; label: string }[] = [
  { id: 'purchase', label: 'Purchase' },
  { id: 'rules', label: 'Rules' },
  { id: 'audit', label: 'Audit' },
];

// Parse hash: #/purchase/12345/payments or #/rules or #/audit
function parseHash(): { page: PageId; purchaseId: number | null; tab?: string } {
  const hash = window.location.hash.replace('#/', '');
  const parts = hash.split('/');
  if (parts[0] === 'purchase' && parts[1]) {
    const id = parseInt(parts[1], 10);
    return { page: 'purchase', purchaseId: isNaN(id) ? null : id, tab: parts[2] };
  }
  if (parts[0] === 'rules') return { page: 'rules', purchaseId: null };
  if (parts[0] === 'audit') return { page: 'audit', purchaseId: null };
  return { page: 'purchase', purchaseId: null };
}

function App() {
  const initial = parseHash();
  const [currentPage, setCurrentPage] = useState<PageId>(initial.page);
  const [purchaseId, setPurchaseId] = useState<number | null>(initial.purchaseId);
  const [initialTab] = useState<string | undefined>(initial.tab);
  const [operator, setOperator] = useState<string>(
    () => localStorage.getItem('operator') || ''
  );

  // Sync hash -> state on popstate (browser back/forward)
  useEffect(() => {
    const onHashChange = () => {
      const parsed = parseHash();
      setCurrentPage(parsed.page);
      setPurchaseId(parsed.purchaseId);
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  // Sync state -> hash
  useEffect(() => {
    if (currentPage === 'purchase' && purchaseId) {
      window.location.hash = `#/purchase/${purchaseId}`;
    } else if (currentPage === 'purchase') {
      window.location.hash = '#/purchase';
    } else {
      window.location.hash = `#/${currentPage}`;
    }
  }, [currentPage, purchaseId]);

  useEffect(() => {
    if (operator) {
      localStorage.setItem('operator', operator);
    } else {
      localStorage.removeItem('operator');
    }
  }, [operator]);

  const handleSelectPurchase = useCallback((id: number) => {
    setPurchaseId(id);
    setCurrentPage('purchase');
  }, []);

  const handleClearPurchase = useCallback(() => {
    setPurchaseId(null);
  }, []);

  const renderPage = () => {
    switch (currentPage) {
      case 'purchase':
        return (
          <PurchasePage
            purchaseId={purchaseId}
            onSelectPurchase={handleSelectPurchase}
            onClearPurchase={handleClearPurchase}
          />
        );
      case 'rules':
        return <RulesPage />;
      case 'audit':
        return <AuditPage />;
      default:
        return null;
    }
  };

  return (
    <div className="app-container">
      <nav className="nav-bar">
        <span className="nav-title">Purchase Repair Tool</span>
        <div className="nav-tabs">
          {NAV_TABS.map((tab) => (
            <button
              key={tab.id}
              className={`nav-tab${currentPage === tab.id ? ' active' : ''}`}
              onClick={() => setCurrentPage(tab.id)}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <div className="nav-spacer" />
        <div className="nav-operator">
          <label htmlFor="operator-input">Operator:</label>
          <input
            id="operator-input"
            type="text"
            value={operator}
            onChange={(e) => setOperator(e.target.value)}
            placeholder="your-name"
          />
        </div>
      </nav>
      <main className="app-main">
        {!operator.trim() ? (
          <div className="operator-gate">
            <div className="operator-gate-card">
              <h2>Set your operator name</h2>
              <p>All actions are logged with your name. Enter your name to continue.</p>
              <input
                type="text"
                className="form-input"
                placeholder="e.g. arnon.zamir"
                autoFocus
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    const val = (e.target as HTMLInputElement).value.trim();
                    if (val) setOperator(val);
                  }
                }}
              />
            </div>
          </div>
        ) : (
          renderPage()
        )}
      </main>
    </div>
  );
}

export default App;
