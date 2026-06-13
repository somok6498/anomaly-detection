import { SectionCard, StatCard, ActionBadge, RiskBadge, ScoreCircle } from './SharedComponents';
import { formatAmount, formatDate, riskColor, actionColor } from '../utils/helpers';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine, ReferenceArea } from 'recharts';

const TYPE_COLORS = ['#9d1d27', '#D49F38', '#10B981', '#C4243B', '#8B5CF6', '#F59E0B'];

export default function ClientView({ profile, transactions, evaluations, onTxnTap, onLoadMore, hasMore, loadingMore }) {
  const sorted = [...evaluations].sort((a, b) => a.evaluatedAt - b.evaluatedAt);
  const chartData = sorted.map((e, i) => ({
    idx: i, score: e.compositeScore, action: e.action, txnId: e.txnId,
  }));

  const typeCounts = profile.txnTypeCounts || {};
  const totalTxnsByType = Object.values(typeCounts).reduce((a, b) => a + b, 0);
  const typeEntries = Object.entries(typeCounts).sort((a, b) => b[1] - a[1]);

  const avgAmounts = profile.avgAmountByType || {};
  const amountEntries = Object.entries(avgAmounts).sort((a, b) => b[1] - a[1]);
  const maxAvg = amountEntries.length > 0 ? amountEntries[0][1] : 1;

  const CustomDot = (props) => {
    const { cx, cy, payload } = props;
    const color = actionColor(payload.action);
    return <circle cx={cx} cy={cy} r={3} fill={color} stroke="var(--card-bg)" strokeWidth={1} />;
  };

  return (
    <div>
      {/* Profile Stats */}
      <SectionCard title={`Client Profile: ${profile.clientId}`}>
        <div className="stat-row">
          <StatCard label="Total Transactions" value={(profile.totalTxnCount || 0).toLocaleString('en-IN')} />
          <StatCard label="EWMA Amount" value={`₹${formatAmount(profile.ewmaAmount)}`} />
          <StatCard label="Amount Std Dev" value={`₹${formatAmount(profile.amountStdDev)}`} />
          <StatCard label="EWMA Hourly TPS" value={(profile.ewmaHourlyTps || 0).toFixed(2)} />
          <StatCard label="TPS Std Dev" value={(profile.tpsStdDev || 0).toFixed(2)} />
          <StatCard label="Last Updated" value={formatDate(profile.lastUpdated)} />
        </div>
      </SectionCard>

      {/* Risk Score Trend */}
      {chartData.length > 0 && (
        <SectionCard title={`Risk Score Trend (${sorted.length} evaluations)`}>
          <div className="chart-wrap">
          <ResponsiveContainer width="100%" height={220}>
            <LineChart data={chartData} margin={{ top: 10, right: 10, bottom: 0, left: 0 }}>
              <ReferenceArea y1={0} y2={30} fill="#4caf50" fillOpacity={0.12} />
              <ReferenceArea y1={30} y2={70} fill="#ff9800" fillOpacity={0.12} />
              <ReferenceArea y1={70} y2={100} fill="#f44336" fillOpacity={0.12} />
              <CartesianGrid strokeDasharray="3 3" stroke="var(--chart-grid)" vertical={false} />
              <XAxis dataKey="idx" hide />
              <YAxis domain={[0, 100]} stroke="var(--chart-text)" fontSize={10} interval={0} ticks={[0, 20, 40, 60, 80, 100]} />
              <ReferenceLine y={30} stroke="#ff9800" strokeDasharray="5 5" strokeOpacity={0.7} label={{ value: 'ALERT 30', position: 'right', style: { fontSize: 9, fill: '#ff9800' } }} />
              <ReferenceLine y={70} stroke="#f44336" strokeDasharray="5 5" strokeOpacity={0.7} label={{ value: 'BLOCK 70', position: 'right', style: { fontSize: 9, fill: '#f44336' } }} />
              <Tooltip contentStyle={{ background: 'var(--card-bg)', border: '1px solid var(--border)', color: 'var(--text)' }}
                formatter={(val, name, props) => [`${val.toFixed(1)} (${props.payload.action})`, 'Score']}
                labelFormatter={(i) => chartData[i]?.txnId || ''} />
              <Line type="monotone" dataKey="score" stroke="var(--accent)" strokeWidth={2} dot={<CustomDot />}
                activeDot={{ r: 5, stroke: 'var(--accent)' }} />
            </LineChart>
          </ResponsiveContainer>
          </div>
        </SectionCard>
      )}

      {/* Type Distribution + Avg Amount */}
      <div className="grid-2">
        <SectionCard title="Transaction Type Distribution">
          {typeEntries.length > 0 ? typeEntries.map(([type, count], i) => {
            const pct = totalTxnsByType > 0 ? (count / totalTxnsByType * 100) : 0;
            const color = TYPE_COLORS[i % TYPE_COLORS.length];
            return (
              <div key={type} style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
                <span style={{ width: 50, textAlign: 'right', fontSize: 12, fontWeight: 600, color: 'var(--text-secondary)' }}>{type}</span>
                <div style={{ flex: 1, position: 'relative', height: 24, background: 'var(--surface)', borderRadius: 4 }}>
                  <div style={{ width: `${Math.max(pct, 2)}%`, height: '100%', background: color, borderRadius: 4, display: 'flex', alignItems: 'center', paddingLeft: 8 }}>
                    <span style={{ fontSize: 11, fontWeight: 600, color: '#fff' }}>{count.toLocaleString('en-IN')}</span>
                  </div>
                </div>
                <span style={{ width: 50, fontSize: 12, color: 'var(--text-secondary)' }}>{pct.toFixed(1)}%</span>
              </div>
            );
          }) : <div className="no-data">No type data</div>}
        </SectionCard>

        <SectionCard title="Avg Amount by Type">
          {amountEntries.length > 0 ? amountEntries.map(([type, avg], i) => {
            const pct = maxAvg > 0 ? (avg / maxAvg * 100) : 0;
            const color = TYPE_COLORS[i % TYPE_COLORS.length];
            return (
              <div key={type} style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
                <span style={{ width: 50, textAlign: 'right', fontSize: 12, fontWeight: 600, color: 'var(--text-secondary)' }}>{type}</span>
                <div style={{ flex: 1, position: 'relative', height: 24, background: 'var(--surface)', borderRadius: 4 }}>
                  <div style={{ width: `${Math.max(pct, 2)}%`, height: '100%', background: color, borderRadius: 4, display: 'flex', alignItems: 'center', paddingLeft: 8 }}>
                    <span style={{ fontSize: 11, fontWeight: 600, color: '#fff' }}>₹{formatAmount(avg)}</span>
                  </div>
                </div>
              </div>
            );
          }) : <div className="no-data">No amount data</div>}
        </SectionCard>
      </div>

      {/* Transaction History */}
      <SectionCard title={`Transaction History (${transactions.length}${hasMore ? '+' : ''})`}>
        {transactions.length === 0 ? <div className="no-data">No transactions found.</div> : (
          <>
            <div style={{ overflowX: 'auto' }}>
              <table className="data-table">
                <thead><tr><th>TXN ID</th><th>Type</th><th>Amount</th><th>Timestamp</th></tr></thead>
                <tbody>
                  {transactions.map(t => (
                    <tr key={t.txnId}>
                      <td><a href="#" onClick={e => { e.preventDefault(); onTxnTap(t.txnId); }} style={{ color: 'var(--link)' }}>{t.txnId}</a></td>
                      <td>{t.txnType}</td>
                      <td>₹{formatAmount(t.amount)}</td>
                      <td>{formatDate(t.timestamp)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {hasMore && (
              <div style={{ textAlign: 'center', marginTop: 12 }}>
                {loadingMore ? <span className="text-muted">Loading...</span> : (
                  <button className="btn btn-outline btn-sm" onClick={onLoadMore}>Load More</button>
                )}
              </div>
            )}
          </>
        )}
      </SectionCard>

      {/* Evaluation History */}
      <SectionCard title={`Evaluation History (Latest ${evaluations.length})`}>
        {evaluations.length === 0 ? <div className="no-data">No evaluations found. Submit transactions via /evaluate API first.</div> : (
          <div style={{ overflowX: 'auto' }}>
            <table className="data-table">
              <thead><tr><th>TXN ID</th><th>Score</th><th>Risk</th><th>Action</th><th>Rules</th><th>Evaluated</th></tr></thead>
              <tbody>
                {evaluations.map(e => {
                  const triggered = (e.ruleResults || []).filter(r => r.triggered).length;
                  return (
                    <tr key={e.txnId}>
                      <td><a href="#" onClick={ev => { ev.preventDefault(); onTxnTap(e.txnId); }} style={{ color: 'var(--link)' }}>{e.txnId}</a></td>
                      <td style={{ fontWeight: 700 }}>{e.compositeScore?.toFixed(1)}</td>
                      <td><RiskBadge level={e.riskLevel} /></td>
                      <td><ActionBadge action={e.action} /></td>
                      <td>{triggered}/{(e.ruleResults || []).length}</td>
                      <td>{formatDate(e.evaluatedAt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>
    </div>
  );
}
