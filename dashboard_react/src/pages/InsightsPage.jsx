import { useState, useEffect } from 'react';
import { getSegmentSummary, getRailInsights, getCampaigns, getVolumeInsights, getMigrationOpportunities } from '../api/apiService';
import { SectionCard, StatCard, SkeletonLoader } from '../components/SharedComponents';
import { formatAmount } from '../utils/helpers';
import { PieChart, Pie, Cell, BarChart, Bar, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';

const SEGMENT_COLORS = { HIGH_VALUE: '#C4243B', GROWING: '#10B981', STABLE: '#D49F38', DECLINING: '#F59E0B', DORMANT: '#9e9e9e', NEW: '#8B5CF6' };
const RAIL_COLORS = { NEFT: '#9d1d27', RTGS: '#C4243B', IMPS: '#10B981', UPI: '#D49F38', IFT: '#8B5CF6' };
const PRIORITY_COLORS = { HIGH: '#C4243B', MEDIUM: '#F59E0B', LOW: '#10B981' };

export default function InsightsPage() {
  const [segments, setSegments] = useState(null);
  const [rails, setRails] = useState(null);
  const [campaigns, setCampaigns] = useState(null);
  const [volume, setVolume] = useState(null);
  const [migrations, setMigrations] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      getSegmentSummary().catch(() => null),
      getRailInsights().catch(() => null),
      getCampaigns().catch(() => []),
      getVolumeInsights().catch(() => null),
      getMigrationOpportunities().catch(() => []),
    ]).then(([s, r, c, v, m]) => {
      setSegments(s); setRails(r); setCampaigns(c); setVolume(v); setMigrations(m);
      setLoading(false);
    });
  }, []);

  if (loading) return <SkeletonLoader type="card" count={5} />;

  const segMap = segments?.segments || segments;
  const segmentData = segMap && typeof segMap === 'object' && !Array.isArray(segMap)
    ? Object.entries(segMap).filter(([, v]) => v && typeof v === 'object').map(([name, info]) => ({
        name, count: info.count || 0, totalTxns: info.totalTransactions || 0, avgEwma: info.avgEwmaAmount || 0,
      }))
    : [];

  const railData = Array.isArray(rails) ? rails : (rails?.rails || []);

  const hourlyData = volume?.hourlyTpsDistribution ? Object.entries(volume.hourlyTpsDistribution).map(([h, v]) => ({
    hour: `${h.toString().padStart(2, '0')}:00`, tps: v,
  })).sort((a, b) => a.hour.localeCompare(b.hour)) : [];

  return (
    <div>
      <div className="grid-2">
        <SectionCard title="Client Segmentation">
          {segmentData.length > 0 ? (
            <>
              <div className="chart-wrap">
              <ResponsiveContainer width="100%" height={250}>
                <PieChart>
                  <Pie data={segmentData.filter(d => d.count > 0)} dataKey="count" nameKey="name" cx="50%" cy="50%" innerRadius={50} outerRadius={90}
                    label={({ name, count }) => `${name} (${count})`} labelLine={{ stroke: 'var(--chart-text)' }}>
                    {segmentData.filter(d => d.count > 0).map(d => <Cell key={d.name} fill={SEGMENT_COLORS[d.name] || '#999'} />)}
                  </Pie>
                  <Tooltip />
                </PieChart>
              </ResponsiveContainer>
              </div>
              <table className="data-table mt-8">
                <thead><tr><th>Segment</th><th>Count</th><th>Total Txns</th><th>Avg EWMA</th></tr></thead>
                <tbody>
                  {segmentData.map(d => (
                    <tr key={d.name}>
                      <td><span className="badge" style={{ background: SEGMENT_COLORS[d.name] || '#999', color: '#fff' }}>{d.name}</span></td>
                      <td>{d.count}</td><td>{d.totalTxns.toLocaleString()}</td><td>₹{formatAmount(d.avgEwma)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {segments?.medianEwmaAmount != null && (
                <div className="text-sm text-muted mt-8">Median EWMA Amount: ₹{formatAmount(segments.medianEwmaAmount)}</div>
              )}
            </>
          ) : <div className="no-data">No segment data</div>}
        </SectionCard>

        <SectionCard title="Rail Usage">
          {railData.length > 0 ? (
            <>
              <div className="chart-wrap">
              <ResponsiveContainer width="100%" height={250}>
                <BarChart data={railData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--chart-grid)" />
                  <XAxis dataKey="rail" stroke="var(--chart-text)" fontSize={12} />
                  <YAxis stroke="var(--chart-text)" fontSize={12} />
                  <Tooltip contentStyle={{ background: 'var(--card-bg)', border: '1px solid var(--border)', color: 'var(--text)' }} />
                  <Bar dataKey="transactionCount" name="Txn Count">
                    {railData.map(d => <Cell key={d.rail} fill={RAIL_COLORS[d.rail] || '#999'} />)}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
              </div>
              <table className="data-table mt-8">
                <thead><tr><th>Rail</th><th>Txn Count</th><th>Volume %</th><th>Avg Amount</th><th>Active Clients</th></tr></thead>
                <tbody>
                  {railData.map(d => (
                    <tr key={d.rail}>
                      <td><span className="badge" style={{ background: RAIL_COLORS[d.rail] || '#999', color: '#fff' }}>{d.rail}</span></td>
                      <td>{(d.transactionCount || 0).toLocaleString()}</td>
                      <td>{(d.volumeSharePct || 0).toFixed(1)}%</td>
                      <td>₹{formatAmount(d.avgAmount)}</td>
                      <td>{d.activeClients || 0}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          ) : <div className="no-data">No rail data</div>}
        </SectionCard>
      </div>

      <SectionCard title="Volume Analytics">
        {volume ? (
          <>
            <div className="stat-row">
              <StatCard label="Peak Hour" value={(volume.peakTpsHour ?? volume.peakHour) != null ? `${volume.peakTpsHour ?? volume.peakHour}:00` : '—'} />
              <StatCard label="Peak Day" value={volume.peakAmountDay || volume.peakDay || '—'} />
              <StatCard label="EWMA Daily Volume" value={(volume.systemEwmaDailyVolume || volume.ewmaDailyVolume) ? formatAmount(volume.systemEwmaDailyVolume || volume.ewmaDailyVolume) : '—'} />
            </div>
            {hourlyData.length > 0 && (
              <div className="chart-wrap">
              <ResponsiveContainer width="100%" height={200}>
                <LineChart data={hourlyData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--chart-grid)" />
                  <XAxis dataKey="hour" stroke="var(--chart-text)" fontSize={11} />
                  <YAxis stroke="var(--chart-text)" fontSize={11} />
                  <Tooltip contentStyle={{ background: 'var(--card-bg)', border: '1px solid var(--border)', color: 'var(--text)' }} />
                  <Line type="monotone" dataKey="tps" stroke="var(--accent)" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
              </div>
            )}
          </>
        ) : <div className="no-data">No volume data</div>}
      </SectionCard>

      <SectionCard title="Campaign Recommendations">
        {Array.isArray(campaigns) && campaigns.length > 0 ? (
          <table className="data-table">
            <thead><tr><th>Campaign</th><th>Priority</th><th>Target Segment</th><th>Clients</th><th>Description</th></tr></thead>
            <tbody>
              {campaigns.map((c, i) => (
                <tr key={i}>
                  <td style={{ fontWeight: 600 }}>{c.campaignName || c.name}</td>
                  <td><span className="badge" style={{ background: PRIORITY_COLORS[c.priority] || '#999', color: '#fff' }}>{c.priority}</span></td>
                  <td>{c.targetSegment || '—'}</td>
                  <td>{(c.targetClients || c.targetClientIds || []).length}</td>
                  <td className="text-sm">{c.description || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <div className="no-data">No campaigns</div>}
      </SectionCard>

      <SectionCard title="Rail Migration Opportunities">
        {Array.isArray(migrations) && migrations.length > 0 ? (
          <table className="data-table">
            <thead><tr><th>Client</th><th>From</th><th>To</th><th>Avg Amount</th><th>Impact</th><th>Reason</th></tr></thead>
            <tbody>
              {migrations.map((m, i) => (
                <tr key={i}>
                  <td>{m.clientId}</td>
                  <td><span className="badge" style={{ background: RAIL_COLORS[m.fromRail] || '#999', color: '#fff' }}>{m.fromRail}</span></td>
                  <td><span className="badge" style={{ background: RAIL_COLORS[m.toRail] || '#999', color: '#fff' }}>{m.toRail}</span></td>
                  <td>₹{formatAmount(m.avgAmount)}</td>
                  <td>{(m.impactScore || 0).toFixed(1)}</td>
                  <td className="text-sm">{m.reason || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <div className="no-data">No migration opportunities</div>}
      </SectionCard>
    </div>
  );
}
