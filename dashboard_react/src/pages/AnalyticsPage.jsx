import { useState, useEffect, useCallback } from 'react';
import { getRulePerformance, getClientNetwork, getGraphStatus, getAiFeedbackStats, getSilenceStatus, triggerSilenceCheck } from '../api/apiService';
import { SectionCard, StatCard, TimeRangeSelector, SkeletonLoader } from '../components/SharedComponents';
import { formatAmount, formatDate, timePresetToMs, downloadBlob, timestampedFilename } from '../utils/helpers';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell } from 'recharts';

const NODE_COLORS = { client: '#C4243B', beneficiary: '#10B981', center: '#D49F38' };

export default function AnalyticsPage() {
  const [timePreset, setTimePreset] = useState('7d');
  const [fromDate, setFromDate] = useState(null);
  const [toDate, setToDate] = useState(null);
  const [ruleStats, setRuleStats] = useState([]);
  const [loadingRules, setLoadingRules] = useState(true);

  const [networkClientId, setNetworkClientId] = useState('');
  const [networkGraph, setNetworkGraph] = useState(null);
  const [graphStatus, setGraphStatus] = useState(null);
  const [loadingGraph, setLoadingGraph] = useState(false);

  const [selectedNode, setSelectedNode] = useState(null);

  const [aiFeedbackStats, setAiFeedbackStats] = useState(null);
  const [silenceStatus, setSilenceStatus] = useState(null);
  const [loadingSilence, setLoadingSilence] = useState(true);

  const getTimeRange = useCallback(() => {
    if (fromDate && toDate) return { fromDate: fromDate.getTime(), toDate: toDate.getTime() };
    const now = Date.now();
    return { fromDate: now - timePresetToMs(timePreset), toDate: now };
  }, [timePreset, fromDate, toDate]);

  const loadRules = useCallback(async () => {
    setLoadingRules(true);
    try {
      const { fromDate: f, toDate: t } = getTimeRange();
      const data = await getRulePerformance({ fromDate: f, toDate: t });
      setRuleStats(data || []);
    } catch (_) {}
    setLoadingRules(false);
  }, [getTimeRange]);

  useEffect(() => { loadRules(); }, [loadRules]);

  useEffect(() => {
    getGraphStatus().then(setGraphStatus).catch(() => {});
    getAiFeedbackStats().then(setAiFeedbackStats).catch(() => {});
    getSilenceStatus().then(s => { setSilenceStatus(s); setLoadingSilence(false); }).catch(() => setLoadingSilence(false));
  }, []);

  const loadNetwork = async () => {
    const id = networkClientId.trim().toUpperCase();
    if (!id) return;
    setLoadingGraph(true);
    setSelectedNode(null);
    try {
      setNetworkGraph(await getClientNetwork(id));
    } catch (e) {
      setNetworkGraph(null);
      alert(e.message);
    }
    setLoadingGraph(false);
  };

  const handleSilenceCheck = async () => {
    try {
      await triggerSilenceCheck();
      const s = await getSilenceStatus();
      setSilenceStatus(s);
    } catch (_) {}
  };

  const exportRulesCsv = () => {
    const header = 'Rule,Type,Weight,Triggers,TP,FP,Precision';
    const rows = ruleStats.map(r => `${r.ruleName},${r.ruleType},${r.currentWeight},${r.triggerCount},${r.tpCount},${r.fpCount},${(r.precision * 100).toFixed(1)}%`);
    downloadBlob(`${header}\n${rows.join('\n')}`, timestampedFilename('rule_performance', 'csv'));
  };

  const precisionColor = (p) => p >= 0.7 ? 'var(--success)' : p >= 0.4 ? 'var(--warning)' : 'var(--danger)';

  const chartData = ruleStats.filter(r => r.triggerCount > 0).map(r => ({
    name: r.ruleName.length > 20 ? r.ruleName.slice(0, 18) + '…' : r.ruleName,
    triggers: r.triggerCount, precision: r.precision,
  }));

  const renderGraph = () => {
    if (!networkGraph?.nodes?.length) return <div className="no-data">Load a client network to visualize</div>;
    const { nodes, edges } = networkGraph;
    const center = nodes.find(n => n.center || n.isCenter) || nodes[0];
    const allClients = nodes.filter(n => (n.type === 'client' || n.type === 'CLIENT'));
    const otherClients = allClients.filter(n => !n.center && !n.isCenter);
    const benes = nodes.filter(n => n.type === 'beneficiary' || n.type === 'BENEFICIARY');
    const shared = benes.filter(n => n.fanIn > 1);
    const unshared = benes.filter(n => n.fanIn <= 1);

    const W = 600, H = 600, cx = W / 2, cy = H / 2;
    const positions = {};

    if (center) positions[center.id] = { x: cx, y: cy };

    if (shared.length > 0) {
      const r1 = 80;
      otherClients.forEach((n, i) => {
        const a = (2 * Math.PI * i) / Math.max(otherClients.length, 1) - Math.PI / 2;
        positions[n.id] = { x: cx + r1 * Math.cos(a), y: cy + r1 * Math.sin(a) };
      });
      const r2 = 160;
      shared.forEach((n, i) => {
        const a = (2 * Math.PI * i) / Math.max(shared.length, 1) - Math.PI / 2;
        positions[n.id] = { x: cx + r2 * Math.cos(a), y: cy + r2 * Math.sin(a) };
      });
      const r3 = 250;
      unshared.forEach((n, i) => {
        const a = (2 * Math.PI * i) / Math.max(unshared.length, 1);
        positions[n.id] = { x: cx + r3 * Math.cos(a), y: cy + r3 * Math.sin(a) };
      });
    } else {
      const r1 = 90;
      otherClients.forEach((n, i) => {
        const a = (2 * Math.PI * i) / Math.max(otherClients.length, 1) - Math.PI / 2;
        positions[n.id] = { x: cx + r1 * Math.cos(a), y: cy + r1 * Math.sin(a) };
      });
      const r2 = 200;
      benes.forEach((n, i) => {
        const a = (2 * Math.PI * i) / Math.max(benes.length, 1);
        positions[n.id] = { x: cx + r2 * Math.cos(a), y: cy + r2 * Math.sin(a) };
      });
    }

    const connectedBenes = new Set();
    const connectedEdges = new Set();
    if (selectedNode) {
      edges.forEach((e, i) => {
        if (e.from === selectedNode) { connectedBenes.add(e.to); connectedEdges.add(i); }
        if (e.to === selectedNode) { connectedBenes.add(e.from); connectedEdges.add(i); }
      });
    }

    const hasShared = shared.length > 0;
    const isClientNode = (n) => {
      const t = n.type?.toLowerCase();
      return t === 'client' || n.center || n.isCenter;
    };

    const handleNodeClick = (n) => {
      if (!isClientNode(n)) return;
      setSelectedNode(prev => prev === n.id ? null : n.id);
    };

    return (
      <div>
        <div style={{ display: 'flex', gap: 16, marginBottom: 8, flexWrap: 'wrap', alignItems: 'center', color: 'var(--text)' }}>
          <span className="text-xs"><span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: '50%', background: NODE_COLORS.center, marginRight: 4 }} />Center Client</span>
          {otherClients.length > 0 && <span className="text-xs"><span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: '50%', background: NODE_COLORS.client, marginRight: 4 }} />Other Clients</span>}
          {hasShared && <span className="text-xs"><span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: '50%', background: '#FF6B6B', marginRight: 4 }} />Shared Beneficiaries ({shared.length})</span>}
          <span className="text-xs"><span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: '50%', background: NODE_COLORS.beneficiary, marginRight: 4 }} />Unique Beneficiaries</span>
          {selectedNode && <button className="btn btn-outline btn-sm" style={{ marginLeft: 'auto', fontSize: 11, padding: '2px 8px' }} onClick={() => setSelectedNode(null)}>Clear selection</button>}
        </div>
        {selectedNode && <div className="text-xs" style={{ marginBottom: 8, color: 'var(--accent)' }}>Showing connections for: <strong>{selectedNode}</strong> ({connectedBenes.size} beneficiaries)</div>}
        <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', maxHeight: 600 }}>
          {edges.map((e, i) => {
            const from = positions[e.from], to = positions[e.to];
            if (!from || !to) return null;
            const isHighlighted = selectedNode && connectedEdges.has(i);
            const isDimmed = selectedNode && !connectedEdges.has(i);
            const isSharedEdge = (e.from !== center?.id);
            return <line key={i} x1={from.x} y1={from.y} x2={to.x} y2={to.y}
              stroke={isHighlighted ? '#FFD700' : isSharedEdge ? '#FF6B6B' : 'var(--border-strong)'}
              strokeWidth={isHighlighted ? 3 : isSharedEdge ? 2 : 1}
              opacity={isDimmed ? 0.08 : isHighlighted ? 1 : isSharedEdge ? 0.7 : 0.3} />;
          })}
          {nodes.map(n => {
            const p = positions[n.id];
            if (!p) return null;
            const isCenter = n.center || n.isCenter;
            const ntype = n.type?.toLowerCase();
            const isSharedBene = ntype === 'beneficiary' && n.fanIn > 1;
            const isClient = isClientNode(n);
            const isSelected = selectedNode === n.id;
            const isConnected = selectedNode && (connectedBenes.has(n.id) || n.id === selectedNode);
            const isDimmed = selectedNode && !isConnected;

            const baseColor = isCenter ? NODE_COLORS.center : isSharedBene ? '#FF6B6B' : NODE_COLORS[ntype] || '#999';
            const color = isSelected ? '#FFD700' : baseColor;
            const size = isCenter ? 16 : isSharedBene ? 10 + Math.min(n.fanIn, 5) : isClient ? 12 : 6;
            const labelText = n.label?.length > 20 ? n.label.slice(0, 18) + '…' : (n.label || n.id);

            return (
              <g key={n.id} onClick={() => handleNodeClick(n)} style={{ cursor: isClient ? 'pointer' : 'default' }}
                opacity={isDimmed ? 0.15 : 1}>
                {isSelected && <circle cx={p.x} cy={p.y} r={size + 8} fill="none" stroke="#FFD700" strokeWidth={2} opacity={0.6}>
                  <animate attributeName="r" values={`${size + 6};${size + 10};${size + 6}`} dur="2s" repeatCount="indefinite" />
                </circle>}
                {isSharedBene && <circle cx={p.x} cy={p.y} r={size + 4} fill="none" stroke="#FF6B6B" strokeWidth={1} opacity={0.4} />}
                <circle cx={p.x} cy={p.y} r={size} fill={color}
                  stroke={isSelected ? '#FFD700' : isConnected ? '#FFD700' : 'var(--card-bg)'}
                  strokeWidth={isSelected ? 3 : isConnected ? 2 : 2} />
                {isClient && <title>Click to highlight connections for {n.label || n.id}</title>}
                <text x={p.x} y={p.y + size + 13} textAnchor="middle"
                  fill={isSelected ? '#FFD700' : isConnected ? '#FFD700' : isSharedBene ? '#FF6B6B' : 'var(--text-secondary)'}
                  fontSize={isCenter || isSelected ? 11 : isSharedBene ? 10 : 9}
                  fontWeight={isCenter || isSharedBene || isSelected || isConnected ? 700 : 400}>
                  {labelText}
                </text>
                {n.fanIn > 1 && <text x={p.x} y={p.y + 4} textAnchor="middle" fill="#fff" fontSize={9} fontWeight={700}>{n.fanIn}</text>}
              </g>
            );
          })}
        </svg>
      </div>
    );
  };

  return (
    <div>
      <SectionCard title="Rule Performance" trailing={
        <div className="flex-row">
          <button className="btn btn-outline btn-sm" onClick={exportRulesCsv}>Export CSV</button>
          <TimeRangeSelector value={timePreset} onChange={p => { setTimePreset(p); setFromDate(null); setToDate(null); }}
            onCustomRange={(f, t) => { setFromDate(f); setToDate(t); setTimePreset('custom'); }} />
        </div>
      }>
        {loadingRules ? <SkeletonLoader type="card" count={2} /> : (
          <>
            {chartData.length > 0 && (
              <div className="chart-wrap">
              <ResponsiveContainer width="100%" height={220}>
                <BarChart data={chartData} layout="vertical" margin={{ left: 100 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--chart-grid)" />
                  <XAxis type="number" stroke="var(--chart-text)" fontSize={11} />
                  <YAxis type="category" dataKey="name" stroke="var(--chart-text)" fontSize={11} width={100} />
                  <Tooltip contentStyle={{ background: 'var(--card-bg)', border: '1px solid var(--border)', color: 'var(--text)' }} />
                  <Bar dataKey="triggers" fill="var(--accent)" radius={[0, 4, 4, 0]} />
                </BarChart>
              </ResponsiveContainer>
              </div>
            )}
            <table className="data-table mt-8">
              <thead><tr><th>Rule</th><th>Type</th><th>Weight</th><th>Triggers</th><th>TP</th><th>FP</th><th>Precision</th></tr></thead>
              <tbody>
                {ruleStats.map(r => (
                  <tr key={r.ruleId}>
                    <td title={r.description}>{r.ruleName} <span className="text-muted" style={{ cursor: 'help' }}>ⓘ</span></td>
                    <td className="text-sm">{r.ruleType}</td>
                    <td>{r.currentWeight.toFixed(1)}</td>
                    <td>{r.triggerCount}</td><td>{r.tpCount}</td><td>{r.fpCount}</td>
                    <td style={{ color: r.triggerCount > 0 ? precisionColor(r.precision) : 'var(--text-secondary)' }}>
                      {r.triggerCount > 0 ? `${(r.precision * 100).toFixed(1)}%` : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </SectionCard>

      <div className="grid-2">
        <SectionCard title="Beneficiary Network">
          <div className="flex-row mb-8">
            <input className="input" placeholder="CLIENT-007 (mule pattern)" value={networkClientId}
              onChange={e => setNetworkClientId(e.target.value)} onKeyDown={e => e.key === 'Enter' && loadNetwork()} style={{ maxWidth: 200 }} />
            <button className="btn btn-primary btn-sm" onClick={loadNetwork} disabled={loadingGraph}>Load</button>
          </div>
          {graphStatus && (
            <div className="text-xs text-muted mb-8" style={{ color: 'var(--text-secondary)' }}>
              {graphStatus.totalClients} clients · {graphStatus.totalBeneficiaries} beneficiaries · {graphStatus.isReady ? '✓ Ready' : '⏳ Building'}
            </div>
          )}
          {loadingGraph ? <SkeletonLoader type="card" count={1} /> : renderGraph()}
        </SectionCard>

        <div>
          <SectionCard title="AI Feedback Stats">
            {aiFeedbackStats ? (
              <>
                <div className="stat-row">
                  <StatCard label="Total Ratings" value={aiFeedbackStats.total || 0} />
                  <StatCard label="Helpful" value={aiFeedbackStats.helpful || 0} color="var(--success)" />
                  <StatCard label="Not Helpful" value={aiFeedbackStats.notHelpful || 0} color="var(--danger)" />
                  <StatCard label="Helpful Rate" value={aiFeedbackStats.helpfulPct != null ? `${aiFeedbackStats.helpfulPct.toFixed(0)}%` : '—'} />
                </div>
                {(aiFeedbackStats.total || 0) > 0 && (
                  <div style={{ display: 'flex', height: 8, borderRadius: 4, overflow: 'hidden', marginTop: 8 }}>
                    <div style={{ width: `${aiFeedbackStats.helpfulPct || 0}%`, background: 'var(--success)' }} />
                    <div style={{ flex: 1, background: 'var(--danger)' }} />
                  </div>
                )}
              </>
            ) : <SkeletonLoader type="lines" count={2} />}
          </SectionCard>

          <SectionCard title="Silence Detection" trailing={
            <button className="btn btn-outline btn-sm" onClick={handleSilenceCheck}>Trigger Check</button>
          }>
            {loadingSilence ? <SkeletonLoader type="lines" count={3} /> : silenceStatus ? (
              <>
                <StatCard label="Silent Clients" value={silenceStatus.silentClientCount || 0}
                  color={(silenceStatus.silentClientCount || 0) > 0 ? 'var(--warning)' : 'var(--success)'} />
                {(silenceStatus.clients || []).length > 0 && (
                  <table className="data-table mt-8">
                    <thead><tr><th>Client</th><th>EWMA TPS</th><th>Expected Gap</th><th>Silent For</th><th>Last Txn</th></tr></thead>
                    <tbody>
                      {silenceStatus.clients.map(c => (
                        <tr key={c.clientId}>
                          <td>{c.clientId}</td>
                          <td>{(c.ewmaHourlyTps || 0).toFixed(2)}</td>
                          <td>{(c.expectedGapMinutes || 0).toFixed(0)}m</td>
                          <td style={{ color: 'var(--warning)' }}>{(c.silentForMinutes || 0) > 60 ? `${Math.floor(c.silentForMinutes / 60)}h ${c.silentForMinutes % 60}m` : `${c.silentForMinutes}m`}</td>
                          <td className="text-sm">{formatDate(c.lastTransactionAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </>
            ) : <div className="no-data">Unable to load silence data</div>}
          </SectionCard>
        </div>
      </div>
    </div>
  );
}
