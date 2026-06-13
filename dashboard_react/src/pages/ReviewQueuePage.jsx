import { useState, useEffect, useCallback, useRef } from 'react';
import {
  getReviewQueue, getReviewDetail, getReviewStats,
  submitFeedback, submitBulkFeedback, getWeightHistory,
  getEvalResult, getAiFeedback, submitAiFeedback,
  getClientNarrative, getAlertTriage,
} from '../api/apiService';
import {
  SectionCard, StatCard, ActionBadge, FeedbackBadge, RiskBadge,
  TimeRangeSelector, ScoreCircle, SkeletonLoader,
} from '../components/SharedComponents';
import {
  formatAmount, formatDate, formatShortDate, riskColor, actionColor,
  feedbackColor, urgencyColor, downloadBlob, timestampedFilename, timePresetToMs,
} from '../utils/helpers';

function scoreToRiskLevel(score) {
  if (score == null) return 'LOW';
  if (score >= 70) return 'CRITICAL';
  if (score >= 50) return 'HIGH';
  if (score >= 30) return 'MEDIUM';
  return 'LOW';
}

function formatCountdown(deadline) {
  if (!deadline) return null;
  const remaining = new Date(deadline).getTime() - Date.now();
  if (remaining <= 0) return '0:00';
  const mins = Math.floor(remaining / 60000);
  const secs = Math.floor((remaining % 60000) / 1000);
  return `${mins}:${secs.toString().padStart(2, '0')}`;
}

function parseAttackPattern(raw) {
  if (!raw) return null;
  try { return typeof raw === 'string' ? JSON.parse(raw) : raw; } catch { return null; }
}

function toArray(data) {
  if (Array.isArray(data)) return data;
  if (data && Array.isArray(data.data)) return data.data;
  if (data && typeof data === 'object') return Object.values(data).find(Array.isArray) || [];
  return [];
}

export default function ReviewQueuePage() {
  const [actionFilter, setActionFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [clientFilter, setClientFilter] = useState('');
  const [timePreset, setTimePreset] = useState('24h');
  const [fromDate, setFromDate] = useState(null);
  const [toDate, setToDate] = useState(null);
  const [scoreOp, setScoreOp] = useState('any');
  const [scoreVal, setScoreVal] = useState('');

  const [queue, setQueue] = useState([]);
  const [stats, setStats] = useState({ pending: 0, truePositive: 0, falsePositive: 0, autoAccepted: 0 });
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [selectedId, setSelectedId] = useState(null);
  const [selectedIds, setSelectedIds] = useState(new Set());
  const [weightHistory, setWeightHistory] = useState([]);
  const [evalResult, setEvalResult] = useState(null);
  const [aiFeedback, setAiFeedback] = useState(null);
  const [aiNarrative, setAiNarrative] = useState(null);
  const [narrativeLoading, setNarrativeLoading] = useState(false);
  const [triageResults, setTriageResults] = useState(null);
  const [triageLoading, setTriageLoading] = useState(false);
  const [showTriage, setShowTriage] = useState(false);
  const [sortCol, setSortCol] = useState('score');
  const [sortAsc, setSortAsc] = useState(false);
  const [, setTick] = useState(0);
  const [exportOpen, setExportOpen] = useState(false);
  const exportRef = useRef(null);
  const [submitting, setSubmitting] = useState(false);
  const [bulkSubmitting, setBulkSubmitting] = useState(false);

  const getTimeRange = useCallback(() => {
    if (fromDate && toDate) return { fromDate: fromDate.getTime(), toDate: toDate.getTime() };
    const now = Date.now();
    return { fromDate: now - timePresetToMs(timePreset), toDate: now };
  }, [timePreset, fromDate, toDate]);

  const fetchQueue = useCallback(async () => {
    setLoading(true);
    try {
      const { fromDate: f, toDate: t } = getTimeRange();
      const params = { fromDate: f, toDate: t };
      if (actionFilter !== 'ALL') params.action = actionFilter;
      if (statusFilter !== 'ALL') params.feedbackStatus = statusFilter;
      if (clientFilter.trim()) params.clientId = clientFilter.trim().toUpperCase();

      const [queueData, statsData] = await Promise.all([
        getReviewQueue(params),
        getReviewStats({ fromDate: f, toDate: t }),
      ]);

      let items = toArray(queueData);
      if (scoreOp !== 'any' && scoreVal !== '') {
        const sv = parseFloat(scoreVal);
        if (!isNaN(sv)) {
          items = items.filter(i => {
            const s = i.compositeScore ?? i.score ?? 0;
            return scoreOp === 'gt' ? s > sv : s < sv;
          });
        }
      }
      setQueue(items);
      setStats(statsData || { pending: 0, truePositive: 0, falsePositive: 0, autoAccepted: 0 });
    } catch (err) {
      console.error('Failed to fetch review queue:', err);
      setQueue([]);
    }
    setLoading(false);
  }, [actionFilter, statusFilter, clientFilter, timePreset, fromDate, toDate, scoreOp, scoreVal, getTimeRange]);

  useEffect(() => { fetchQueue(); }, [fetchQueue]);
  useEffect(() => {
    const id = setInterval(() => setTick(t => t + 1), 1000);
    return () => clearInterval(id);
  }, []);
  useEffect(() => {
    const handler = (e) => { if (exportRef.current && !exportRef.current.contains(e.target)) setExportOpen(false); };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const getScore = (item) => item.compositeScore ?? item.score ?? 0;
  const getStatus = (item) => item.feedbackStatus ?? item.status ?? 'PENDING';
  const getTxnType = (item) => item.txnType ?? item.type ?? '—';

  const loadDetail = useCallback(async (id) => {
    setSelectedId(id);
    setDetailLoading(true);
    setAiFeedback(null); setAiNarrative(null); setWeightHistory([]); setEvalResult(null);
    try {
      const [raw, wh, fb] = await Promise.all([
        getReviewDetail(id),
        getWeightHistory({ limit: 20 }).catch(() => []),
        getAiFeedback(id).catch(() => null),
      ]);
      const q = raw.queueItem || {};
      const ev = raw.evaluation || {};
      const txn = raw.transaction || {};
      const cp = raw.clientProfile || null;
      const flat = {
        ...q,
        ...txn,
        compositeScore: q.compositeScore ?? ev.compositeScore,
        score: q.compositeScore ?? ev.compositeScore,
        riskLevel: q.riskLevel ?? ev.riskLevel,
        action: q.action ?? ev.action,
        feedbackStatus: q.feedbackStatus,
        feedbackBy: q.feedbackBy,
        reviewedAt: q.feedbackAt,
        aiExplanation: ev.aiExplanation,
        attackPattern: ev.attackPattern,
        clientProfile: cp,
      };
      setDetail(flat);
      setEvalResult(ev);
      setWeightHistory(toArray(wh));
      setAiFeedback(fb);
    } catch (err) {
      console.error('Failed to load detail:', err);
    }
    setDetailLoading(false);
  }, []);

  const handleSort = useCallback((col) => {
    if (sortCol === col) setSortAsc(p => !p);
    else { setSortCol(col); setSortAsc(true); }
  }, [sortCol]);

  const sortedQueue = [...queue].sort((a, b) => {
    let va, vb;
    if (sortCol === 'score') { va = getScore(a); vb = getScore(b); }
    else if (sortCol === 'status') { va = getStatus(a); vb = getStatus(b); }
    else { va = a[sortCol]; vb = b[sortCol]; }
    if (typeof va === 'string') va = va.toLowerCase();
    if (typeof vb === 'string') vb = vb.toLowerCase();
    if (va < vb) return sortAsc ? -1 : 1;
    if (va > vb) return sortAsc ? 1 : -1;
    return 0;
  });

  const sortIcon = (col) => sortCol !== col ? ' ↕' : sortAsc ? ' ↑' : ' ↓';

  const toggleSelect = useCallback((id, e) => {
    e.stopPropagation();
    setSelectedIds(prev => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n; });
  }, []);

  const toggleSelectAll = useCallback(() => {
    setSelectedIds(prev => prev.size === sortedQueue.length ? new Set() : new Set(sortedQueue.map(i => i.txnId)));
  }, [sortedQueue]);

  const handleFeedback = useCallback(async (feedback) => {
    if (!selectedId || submitting) return;
    setSubmitting(true);
    try {
      await submitFeedback(selectedId, feedback);
      await Promise.all([fetchQueue(), loadDetail(selectedId)]);
    } catch (err) { console.error('Feedback failed:', err); }
    setSubmitting(false);
  }, [selectedId, submitting, fetchQueue, loadDetail]);

  const handleBulkFeedback = useCallback(async (feedback) => {
    if (selectedIds.size === 0 || bulkSubmitting) return;
    setBulkSubmitting(true);
    try {
      await submitBulkFeedback(Array.from(selectedIds), feedback);
      setSelectedIds(new Set());
      await fetchQueue();
    } catch (err) { console.error('Bulk feedback failed:', err); }
    setBulkSubmitting(false);
  }, [selectedIds, bulkSubmitting, fetchQueue]);

  const handleAiThumb = useCallback(async (helpful) => {
    if (!selectedId) return;
    try {
      await submitAiFeedback(selectedId, helpful, 'dashboard');
      setAiFeedback(await getAiFeedback(selectedId));
    } catch (_) {}
  }, [selectedId]);

  const handleNarrative = useCallback(async () => {
    if (!detail?.clientId) return;
    setNarrativeLoading(true);
    try { setAiNarrative(await getClientNarrative(detail.clientId)); } catch (_) {}
    setNarrativeLoading(false);
  }, [detail]);

  const handleTriage = useCallback(async () => {
    if (showTriage) { setShowTriage(false); return; }
    setTriageLoading(true);
    try { setTriageResults(toArray(await getAlertTriage())); setShowTriage(true); } catch (_) {}
    setTriageLoading(false);
  }, [showTriage]);

  const handleExport = useCallback(async (format) => {
    setExportOpen(false);
    const items = sortedQueue;
    if (format === 'csv') {
      const header = 'TXN_ID,CLIENT_ID,ACTION,SCORE,STATUS,AMOUNT,TIMESTAMP';
      const rows = items.map(i => `${i.txnId},${i.clientId},${i.action},${getScore(i)},${getStatus(i)},${i.amount || ''},${i.timestamp || ''}`);
      downloadBlob(`${header}\n${rows.join('\n')}`, timestampedFilename('review_queue', 'csv'));
    } else if (format === 'pdf') {
      const { default: jsPDF } = await import('jspdf');
      await import('jspdf-autotable');
      const doc = new jsPDF();
      doc.setFontSize(14);
      doc.text('Review Queue Export', 14, 18);
      doc.setFontSize(9);
      doc.text(`Generated: ${new Date().toLocaleString()}`, 14, 25);
      doc.autoTable({
        startY: 30,
        head: [['TXN ID', 'CLIENT', 'ACTION', 'SCORE', 'STATUS', 'AMOUNT']],
        body: items.map(i => [i.txnId, i.clientId, i.action, getScore(i), getStatus(i), formatAmount(i.amount)]),
        styles: { fontSize: 8 },
        headStyles: { fillColor: [157, 29, 39] },
      });
      const blob = doc.output('blob');
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = timestampedFilename('review_queue', 'pdf'); a.click();
      URL.revokeObjectURL(url);
    }
  }, [sortedQueue]);

  const handleStatClick = useCallback((s) => setStatusFilter(s), []);

  const attackPattern = detail ? parseAttackPattern(detail.attackPattern) : null;

  return (
    <div className="split-panel">
      {/* ====== LEFT: Queue ====== */}
      <div className="split-left" style={{ padding: 16 }}>
        {/* Filters */}
        <div className="filter-bar">
          <select className="select" style={{ width: 130 }} value={actionFilter} onChange={e => setActionFilter(e.target.value)}>
            <option value="ALL">All Actions</option>
            <option value="ALERT">ALERT</option>
            <option value="BLOCK">BLOCK</option>
          </select>
          <select className="select" style={{ width: 150 }} value={statusFilter} onChange={e => setStatusFilter(e.target.value)}>
            <option value="ALL">All Status</option>
            <option value="PENDING">PENDING</option>
            <option value="TRUE_POSITIVE">TRUE POSITIVE</option>
            <option value="FALSE_POSITIVE">FALSE POSITIVE</option>
            <option value="AUTO_ACCEPTED">AUTO ACCEPTED</option>
          </select>
          <input className="input" style={{ width: 110 }} placeholder="Client ID" value={clientFilter}
            onChange={e => setClientFilter(e.target.value)} onKeyDown={e => e.key === 'Enter' && fetchQueue()} />
          <button className="btn btn-primary btn-sm" onClick={fetchQueue}>Apply</button>
          <div ref={exportRef} style={{ position: 'relative' }}>
            <button className="btn btn-outline btn-sm" onClick={() => setExportOpen(o => !o)}>Export ▾</button>
            {exportOpen && (
              <div style={{ position: 'absolute', top: '100%', right: 0, background: 'var(--card-bg)', border: '1px solid var(--border)', borderRadius: 8, zIndex: 10, minWidth: 100, boxShadow: 'var(--shadow-md)', overflow: 'hidden' }}>
                <button className="btn btn-sm" style={{ display: 'block', width: '100%', textAlign: 'left', borderRadius: 0 }} onClick={() => handleExport('csv')}>CSV</button>
                <button className="btn btn-sm" style={{ display: 'block', width: '100%', textAlign: 'left', borderRadius: 0 }} onClick={() => handleExport('pdf')}>PDF</button>
              </div>
            )}
          </div>
        </div>

        <TimeRangeSelector value={timePreset} onChange={p => { setTimePreset(p); setFromDate(null); setToDate(null); }}
          onCustomRange={(f, t) => { setFromDate(f); setToDate(t); setTimePreset('custom'); }} />

        {/* Score filter */}
        <div className="flex-row" style={{ marginTop: 8, marginBottom: 12 }}>
          <span className="text-sm text-muted" style={{ fontWeight: 500 }}>Score:</span>
          <select className="select" style={{ width: 70 }} value={scoreOp} onChange={e => setScoreOp(e.target.value)}>
            <option value="any">Any</option>
            <option value="gt">&gt;</option>
            <option value="lt">&lt;</option>
          </select>
          {scoreOp !== 'any' && (
            <input className="input" style={{ width: 70 }} type="number" min={0} max={100} value={scoreVal}
              onChange={e => setScoreVal(e.target.value)} placeholder="0-100" />
          )}
        </div>

        {/* Stats */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8, marginBottom: 12 }}>
          <StatCard label="Pending" value={stats.pending ?? 0} color={statusFilter === 'PENDING' ? 'var(--accent)' : undefined}
            onClick={() => handleStatClick(statusFilter === 'PENDING' ? 'ALL' : 'PENDING')} />
          <StatCard label="True +ve" value={stats.truePositive ?? 0} color={statusFilter === 'TRUE_POSITIVE' ? 'var(--danger)' : undefined}
            onClick={() => handleStatClick(statusFilter === 'TRUE_POSITIVE' ? 'ALL' : 'TRUE_POSITIVE')} />
          <StatCard label="False +ve" value={stats.falsePositive ?? 0} color={statusFilter === 'FALSE_POSITIVE' ? 'var(--success)' : undefined}
            onClick={() => handleStatClick(statusFilter === 'FALSE_POSITIVE' ? 'ALL' : 'FALSE_POSITIVE')} />
          <StatCard label="Auto" value={stats.autoAccepted ?? 0} color={statusFilter === 'AUTO_ACCEPTED' ? 'var(--text-secondary)' : undefined}
            onClick={() => handleStatClick(statusFilter === 'AUTO_ACCEPTED' ? 'ALL' : 'AUTO_ACCEPTED')} />
        </div>

        {/* Smart Triage */}
        <div style={{ marginBottom: 12 }}>
          <button className="btn btn-outline btn-sm" style={{ width: '100%' }} onClick={handleTriage} disabled={triageLoading}>
            {triageLoading ? 'Analyzing...' : showTriage ? 'Hide Smart Triage' : 'Smart Triage'}
          </button>
          {showTriage && triageResults?.length > 0 && (
            <div style={{ marginTop: 8, border: '1px solid var(--border)', borderRadius: 8, maxHeight: 200, overflowY: 'auto' }}>
              {triageResults.map((item, i) => (
                <div key={item.txnId || i} style={{ padding: '8px 12px', borderBottom: i < triageResults.length - 1 ? '1px solid var(--border)' : 'none', cursor: 'pointer' }}
                  onClick={() => loadDetail(item.txnId)}>
                  <div className="flex-between">
                    <span style={{ fontWeight: 600, fontSize: 13 }}>{item.txnId}</span>
                    <span className="badge" style={{ background: urgencyColor(item.urgency), color: '#fff' }}>{item.urgency}</span>
                  </div>
                  {item.reasoning && <div className="text-xs text-muted" style={{ marginTop: 4 }}>{item.reasoning}</div>}
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Bulk Actions */}
        {selectedIds.size > 0 && (
          <div className="flex-row" style={{ marginBottom: 12 }}>
            <span className="text-sm">{selectedIds.size} selected</span>
            <button className="btn btn-danger btn-sm" disabled={bulkSubmitting} onClick={() => handleBulkFeedback('TRUE_POSITIVE')}>Mark True +ve</button>
            <button className="btn btn-success btn-sm" disabled={bulkSubmitting} onClick={() => handleBulkFeedback('FALSE_POSITIVE')}>Mark False +ve</button>
          </div>
        )}

        {/* Queue Table */}
        {loading ? <SkeletonLoader type="table" count={8} /> : sortedQueue.length === 0 ? (
          <div className="no-data">No items match the current filters.</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th style={{ width: 30 }}><input type="checkbox" checked={selectedIds.size === sortedQueue.length && sortedQueue.length > 0} onChange={toggleSelectAll} /></th>
                  <th className="sortable" onClick={() => handleSort('txnId')}>TXN ID{sortIcon('txnId')}</th>
                  <th className="sortable" onClick={() => handleSort('action')}>ACTION{sortIcon('action')}</th>
                  <th className="sortable" onClick={() => handleSort('score')}>SCORE{sortIcon('score')}</th>
                  <th className="sortable" onClick={() => handleSort('status')}>STATUS{sortIcon('status')}</th>
                </tr>
              </thead>
              <tbody>
                {sortedQueue.map(item => {
                  const score = getScore(item);
                  const status = getStatus(item);
                  return (
                    <tr key={item.txnId} onClick={() => loadDetail(item.txnId)}
                      className={selectedId === item.txnId ? 'selected' : ''} style={{ cursor: 'pointer' }}>
                      <td onClick={e => e.stopPropagation()}>
                        <input type="checkbox" checked={selectedIds.has(item.txnId)} onChange={e => toggleSelect(item.txnId, e)} />
                      </td>
                      <td className="font-mono text-sm">{item.txnId}</td>
                      <td><ActionBadge action={item.action} /></td>
                      <td><span style={{ color: riskColor(scoreToRiskLevel(score)), fontWeight: 700 }}>{typeof score === 'number' ? score.toFixed(1) : score}</span></td>
                      <td>
                        <FeedbackBadge status={status} />
                        {status === 'PENDING' && item.autoAcceptDeadline && (
                          <span className="font-mono text-xs text-muted" style={{ marginLeft: 6 }}>{formatCountdown(item.autoAcceptDeadline)}</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* ====== RIGHT: Detail ====== */}
      <div className="split-right" style={{ padding: 16 }}>
        {!selectedId ? (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
            <span className="text-muted">Select a queue item to view details</span>
          </div>
        ) : detailLoading ? (
          <SkeletonLoader type="card" count={4} />
        ) : !detail ? (
          <div className="no-data">Failed to load detail.</div>
        ) : (
          <>
            {/* Review Decision */}
            <SectionCard title="Review Decision">
              {(getStatus(detail) === 'PENDING') ? (
                <div className="flex-row" style={{ gap: 12 }}>
                  <button className="btn btn-danger" disabled={submitting} onClick={() => handleFeedback('TRUE_POSITIVE')}>True Positive</button>
                  <button className="btn btn-success" disabled={submitting} onClick={() => handleFeedback('FALSE_POSITIVE')}>False Positive</button>
                </div>
              ) : (
                <div className="flex-row" style={{ gap: 12 }}>
                  <FeedbackBadge status={getStatus(detail)} />
                  {detail.reviewedBy && <span className="text-sm text-muted">by {detail.reviewedBy}</span>}
                  {detail.reviewedAt && <span className="text-sm text-muted">at {formatDate(detail.reviewedAt)}</span>}
                </div>
              )}
            </SectionCard>

            {/* AI Analysis */}
            <SectionCard title="AI Analysis">
              {attackPattern && (
                <div style={{ marginBottom: 10 }}>
                  <div className="flex-row" style={{ marginBottom: 6 }}>
                    <span className="badge badge-escalated">{attackPattern.pattern}</span>
                    <span className="text-xs text-muted">Confidence: {Math.round((attackPattern.confidence || 0) * 100)}%</span>
                  </div>
                  {attackPattern.summary && <p style={{ margin: '0 0 8px', fontSize: 13, lineHeight: 1.5 }}>{attackPattern.summary}</p>}
                </div>
              )}
              {detail.aiExplanation && (
                <p style={{ margin: '0 0 10px', fontSize: 13, lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>{detail.aiExplanation}</p>
              )}
              {!attackPattern && !detail.aiExplanation && <span className="text-muted text-sm">No AI analysis available.</span>}
              <div className="flex-row" style={{ marginTop: 8 }}>
                <span className="text-xs text-muted">Helpful?</span>
                <button className="btn-icon" onClick={() => handleAiThumb(true)}
                  style={{ background: aiFeedback?.helpful === true ? 'var(--success-soft)' : undefined, borderRadius: 6 }}>👍</button>
                <button className="btn-icon" onClick={() => handleAiThumb(false)}
                  style={{ background: aiFeedback?.helpful === false ? 'var(--danger-soft)' : undefined, borderRadius: 6 }}>👎</button>
              </div>
            </SectionCard>

            {/* Transaction Details */}
            <SectionCard title="Transaction Details">
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8 }}>
                <StatCard label="Client ID" value={detail.clientId} />
                <StatCard label="Type" value={getTxnType(detail)} />
                <StatCard label="Amount" value={`₹${formatAmount(detail.amount)}`} />
                <StatCard label="Timestamp" value={formatShortDate(detail.timestamp)} />
              </div>
            </SectionCard>

            {/* Risk Evaluation */}
            <SectionCard title="Risk Evaluation">
              <div className="flex-row" style={{ gap: 16, marginBottom: 16 }}>
                <ScoreCircle score={typeof getScore(detail) === 'number' ? getScore(detail).toFixed(1) : getScore(detail)} riskLevel={scoreToRiskLevel(getScore(detail))} />
                <div>
                  <ActionBadge action={detail.action} />
                  <div style={{ marginTop: 6 }}>
                    <span className="text-sm text-muted">Risk: </span>
                    <RiskBadge level={scoreToRiskLevel(getScore(detail))} />
                  </div>
                </div>
              </div>
              {evalResult?.ruleResults?.length > 0 && (
                <div style={{ overflowX: 'auto' }}>
                  <table className="data-table">
                    <thead><tr><th>Rule</th><th>Triggered</th><th>Deviation</th><th>Score</th><th>Weight</th></tr></thead>
                    <tbody>
                      {evalResult.ruleResults.map((r, i) => (
                        <tr key={i}>
                          <td className="font-mono text-sm">{r.ruleName}</td>
                          <td><span className="badge" style={{ background: r.triggered ? 'var(--danger-soft)' : 'var(--success-soft)', color: r.triggered ? 'var(--danger)' : 'var(--success)' }}>{r.triggered ? 'YES' : 'NO'}</span></td>
                          <td>{r.deviationPct != null ? `${r.deviationPct.toFixed(1)}%` : '—'}</td>
                          <td style={{ fontWeight: 600 }}>{r.partialScore != null ? r.partialScore.toFixed(1) : '—'}</td>
                          <td>{r.riskWeight ?? '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </SectionCard>

            {/* Client Profile */}
            <SectionCard title="Client Profile">
              {detail.clientProfile ? (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, marginBottom: 10 }}>
                  <StatCard label="Total Txns" value={detail.clientProfile.totalTransactions ?? detail.clientProfile.totalTxnCount ?? '—'} />
                  <StatCard label="Avg Amount" value={`₹${formatAmount(detail.clientProfile.avgAmount ?? detail.clientProfile.ewmaAmount)}`} />
                  <StatCard label="Risk Level" value={detail.clientProfile.riskLevel ?? '—'} />
                </div>
              ) : <span className="text-sm text-muted">No client profile data available.</span>}
              <button className="btn btn-outline btn-sm" onClick={handleNarrative} disabled={narrativeLoading} style={{ marginTop: 8 }}>
                {narrativeLoading ? 'Loading...' : 'AI Narrative'}
              </button>
              {aiNarrative && (
                <div style={{ marginTop: 10, padding: '10px 14px', background: 'var(--bg-subtle)', borderRadius: 8, fontSize: 13, lineHeight: 1.6, whiteSpace: 'pre-wrap', color: 'var(--text)' }}>
                  {typeof aiNarrative === 'string' ? aiNarrative : aiNarrative.narrative || JSON.stringify(aiNarrative, null, 2)}
                </div>
              )}
            </SectionCard>

            {/* Weight History */}
            {weightHistory.length > 0 && (
              <SectionCard title="Weight History">
                <div style={{ overflowX: 'auto' }}>
                  <table className="data-table">
                    <thead><tr><th>Rule</th><th>Old</th><th>New</th><th>TP</th><th>FP</th><th>Ratio</th><th>Date</th></tr></thead>
                    <tbody>
                      {weightHistory.map((e, i) => (
                        <tr key={i}>
                          <td className="font-mono text-sm">{e.ruleName ?? e.ruleId}</td>
                          <td>{e.oldWeight != null ? e.oldWeight.toFixed(2) : '—'}</td>
                          <td style={{ fontWeight: 600 }}>{e.newWeight != null ? e.newWeight.toFixed(2) : '—'}</td>
                          <td>{e.truePositives ?? e.tp ?? '—'}</td>
                          <td>{e.falsePositives ?? e.fp ?? '—'}</td>
                          <td>{e.truePositives != null && e.falsePositives != null
                            ? `${((e.truePositives / Math.max(e.truePositives + e.falsePositives, 1)) * 100).toFixed(1)}%` : '—'}</td>
                          <td className="text-sm">{formatShortDate(e.date ?? e.timestamp ?? e.tunedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </SectionCard>
            )}
          </>
        )}
      </div>
    </div>
  );
}
