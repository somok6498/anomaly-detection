import { useState, useEffect, useCallback } from 'react';
import { useTheme } from './context/ThemeContext';
import { getReviewStats, getProfile, getTransactionsByClient, getEvalsByClient, getTransaction, getEvalResult } from './api/apiService';
import ReviewQueuePage from './pages/ReviewQueuePage';
import AnalyticsPage from './pages/AnalyticsPage';
import InsightsPage from './pages/InsightsPage';
import SettingsPage from './pages/SettingsPage';
import ChatPage from './pages/ChatPage';
import ClientView from './components/ClientView';
import TransactionView from './components/TransactionView';

const TABS = ['Investigation', 'Review Queue', 'Analytics', 'Insights', 'Settings'];

export default function App() {
  const { isDark, toggle } = useTheme();
  const [activeTab, setActiveTab] = useState(0);
  const [searchType, setSearchType] = useState('client');
  const [searchQuery, setSearchQuery] = useState('');
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState('');
  const [pendingCount, setPendingCount] = useState(0);

  const [profile, setProfile] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [evaluations, setEvaluations] = useState([]);
  const [hasMoreTxns, setHasMoreTxns] = useState(false);
  const [txnCursor, setTxnCursor] = useState(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const [txnDetail, setTxnDetail] = useState(null);
  const [evalDetail, setEvalDetail] = useState(null);

  const [chatOpen, setChatOpen] = useState(false);
  const [chatFullscreen, setChatFullscreen] = useState(false);

  useEffect(() => {
    const load = () => getReviewStats().then(s => setPendingCount(s.pending || 0)).catch(() => {});
    load();
    const id = setInterval(load, 30000);
    return () => clearInterval(id);
  }, []);

  const handleSearch = useCallback(async () => {
    const q = searchQuery.trim().toUpperCase();
    if (!q) return;
    setSearching(true);
    setError('');
    setProfile(null); setTransactions([]); setEvaluations([]);
    setTxnDetail(null); setEvalDetail(null);
    try {
      if (searchType === 'client') {
        const [p, t, e] = await Promise.all([
          getProfile(q),
          getTransactionsByClient(q, { limit: 50 }),
          getEvalsByClient(q, { limit: 200 }),
        ]);
        setProfile(p);
        setTransactions(t.data || []);
        setHasMoreTxns(t.hasMore || false);
        setTxnCursor(t.nextCursor || null);
        setEvaluations(e.data || []);
      } else {
        const t = await getTransaction(q);
        setTxnDetail(t);
        const ev = await getEvalResult(q);
        setEvalDetail(ev);
      }
    } catch (e) {
      setError(e.response?.data?.error || e.message || 'Not found');
    }
    setSearching(false);
  }, [searchQuery, searchType]);

  const handleLoadMore = useCallback(async () => {
    if (!profile || !txnCursor) return;
    setLoadingMore(true);
    try {
      const t = await getTransactionsByClient(profile.clientId, { limit: 50, before: txnCursor });
      setTransactions(prev => [...prev, ...(t.data || [])]);
      setHasMoreTxns(t.hasMore || false);
      setTxnCursor(t.nextCursor || null);
    } catch (_) {}
    setLoadingMore(false);
  }, [profile, txnCursor]);

  const handleTxnTap = useCallback(async (txnId) => {
    setSearchType('txn');
    setSearchQuery(txnId);
    setProfile(null); setTransactions([]); setEvaluations([]);
    try {
      const t = await getTransaction(txnId);
      setTxnDetail(t);
      const ev = await getEvalResult(txnId);
      setEvalDetail(ev);
    } catch (_) {}
  }, []);

  const handleClientTap = useCallback(async (clientId) => {
    setSearchType('client');
    setSearchQuery(clientId);
    setSearching(true);
    setTxnDetail(null); setEvalDetail(null);
    try {
      const [p, t, e] = await Promise.all([
        getProfile(clientId),
        getTransactionsByClient(clientId, { limit: 50 }),
        getEvalsByClient(clientId, { limit: 200 }),
      ]);
      setProfile(p);
      setTransactions(t.data || []);
      setHasMoreTxns(t.hasMore || false);
      setTxnCursor(t.nextCursor || null);
      setEvaluations(e.data || []);
    } catch (_) {}
    setSearching(false);
  }, []);

  const renderContent = () => {
    switch (activeTab) {
      case 0:
        if (searching) return <div className="loading">Searching...</div>;
        if (error) return <div className="no-data">{error}</div>;
        if (profile) return (
          <ClientView profile={profile} transactions={transactions} evaluations={evaluations}
            onTxnTap={handleTxnTap} onLoadMore={handleLoadMore} hasMore={hasMoreTxns} loadingMore={loadingMore} />
        );
        if (txnDetail) return (
          <TransactionView transaction={txnDetail} evaluation={evalDetail} onClientTap={handleClientTap} />
        );
        return <div className="no-data">Search for a client or transaction to begin investigation</div>;
      case 1: return <ReviewQueuePage />;
      case 2: return <AnalyticsPage />;
      case 3: return <InsightsPage />;
      case 4: return <SettingsPage />;
      default: return null;
    }
  };

  return (
    <div className="app-container">
      <header className="dashboard-header">
        <div className="header-left">
          <span className="logo">
            <span className="logo-mark">AD</span>
            Anomaly Detection
          </span>
          <nav className="tab-bar">
            {TABS.map((tab, i) => (
              <button key={tab} className={`tab-btn ${activeTab === i ? 'active' : ''}`} onClick={() => setActiveTab(i)}>
                {tab}
                {i === 1 && pendingCount > 0 && <span className="tab-badge">{pendingCount}</span>}
              </button>
            ))}
          </nav>
        </div>
        <div className="header-right">
          {activeTab === 0 && (
            <div className="search-bar">
              <select className="search-select" value={searchType} onChange={e => setSearchType(e.target.value)}>
                <option value="client">Client ID</option>
                <option value="txn">Transaction ID</option>
              </select>
              <input className="search-input" placeholder={searchType === 'client' ? 'CLIENT-001' : 'TXN-001'}
                value={searchQuery} onChange={e => setSearchQuery(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleSearch()} />
              <button className="btn btn-primary btn-sm" onClick={handleSearch} disabled={searching} style={{ borderRadius: 6, margin: 2 }}>Search</button>
            </div>
          )}
          <button className="btn-icon" onClick={toggle} title="Toggle theme"
            style={{ width: 36, height: 36, borderRadius: 10, border: '1px solid var(--border)' }}>
            {isDark ? '☀' : '☾'}
          </button>
        </div>
      </header>

      <main className="content-area">{renderContent()}</main>

      <button className="chat-fab" onClick={() => setChatOpen(o => !o)} title="AI Assistant">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
        </svg>
      </button>
      <div className={`chat-panel ${chatOpen ? (chatFullscreen ? 'fullscreen' : 'open') : 'closed'}`}>
        {chatOpen && (
          <ChatPage isFullscreen={chatFullscreen}
            onClose={() => { setChatOpen(false); setChatFullscreen(false); }}
            onToggleFullscreen={() => setChatFullscreen(f => !f)} />
        )}
      </div>
    </div>
  );
}
