import { useState, useEffect, useCallback } from 'react';
import {
  getRules, updateRule, getThresholds, updateThresholds,
  getFeedbackConfig, updateFeedbackConfig, getTransactionTypes, updateTransactionTypes,
  getAerospikeInfo, getSilenceConfig, updateSilenceConfig,
  getTwilioConfig, updateTwilioConfig, getOllamaConfig, updateOllamaConfig,
} from '../api/apiService';
import { SectionCard, StatCard, SkeletonLoader } from '../components/SharedComponents';

export default function SettingsPage() {
  const [loading, setLoading] = useState(true);
  const [rules, setRules] = useState([]);
  const [dirtyRules, setDirtyRules] = useState(new Set());
  const [thresholds, setThresholds] = useState(null);
  const [feedback, setFeedback] = useState(null);
  const [txnTypes, setTxnTypes] = useState([]);
  const [newType, setNewType] = useState('');
  const [aerospike, setAerospike] = useState(null);
  const [silence, setSilence] = useState(null);
  const [twilio, setTwilio] = useState(null);
  const [ollama, setOllama] = useState(null);
  const [toast, setToast] = useState(null);

  const showToast = (msg, ok) => { setToast({ msg, ok }); setTimeout(() => setToast(null), 3000); };

  useEffect(() => {
    Promise.all([
      getRules().catch(() => []),
      getThresholds().catch(() => ({})),
      getFeedbackConfig().catch(() => ({})),
      getTransactionTypes().catch(() => []),
      getAerospikeInfo().catch(() => ({})),
      getSilenceConfig().catch(() => ({})),
      getTwilioConfig().catch(() => ({})),
      getOllamaConfig().catch(() => ({})),
    ]).then(([r, th, fb, tt, ae, si, tw, ol]) => {
      setRules(r || []);
      setThresholds(th || {});
      setFeedback(fb || {});
      setTxnTypes(tt || []);
      setAerospike(ae || {});
      setSilence(si || {});
      setTwilio(tw || {});
      setOllama(ol || {});
      setLoading(false);
    });
  }, []);

  const markDirty = (ruleId) => setDirtyRules(prev => new Set(prev).add(ruleId));

  const updateRuleField = (ruleId, field, value) => {
    setRules(prev => prev.map(r => r.ruleId === ruleId ? { ...r, [field]: value } : r));
    markDirty(ruleId);
  };

  const saveRules = async () => {
    let saved = 0;
    for (const id of dirtyRules) {
      const rule = rules.find(r => r.ruleId === id);
      if (!rule) continue;
      try { await updateRule(id, rule); saved++; } catch (e) { showToast(`Failed: ${id}`, false); return; }
    }
    setDirtyRules(new Set());
    showToast(`${saved} rule(s) saved`, true);
  };

  const saveThresholds = async () => {
    try {
      const updated = await updateThresholds(thresholds);
      setThresholds(updated);
      showToast('Thresholds saved', true);
    } catch (e) { showToast('Failed to save thresholds', false); }
  };

  const saveFeedback = async () => {
    try {
      const updated = await updateFeedbackConfig(feedback);
      setFeedback(updated);
      showToast('Feedback config saved', true);
    } catch (e) { showToast('Failed to save feedback config', false); }
  };

  const saveTxnTypes = async () => {
    try {
      const updated = await updateTransactionTypes(txnTypes);
      setTxnTypes(updated);
      showToast('Transaction types saved', true);
    } catch (e) { showToast('Failed to save transaction types', false); }
  };

  const addType = () => {
    const t = newType.trim().toUpperCase();
    if (!t || txnTypes.includes(t)) return;
    setTxnTypes(prev => [...prev, t]);
    setNewType('');
  };

  const saveSilence = async () => {
    try {
      const updated = await updateSilenceConfig(silence);
      setSilence(updated);
      showToast('Silence config saved', true);
    } catch (e) { showToast('Failed to save silence config', false); }
  };

  const saveTwilio = async () => {
    try {
      const updated = await updateTwilioConfig(twilio);
      setTwilio(updated);
      showToast('Twilio config saved', true);
    } catch (e) { showToast('Failed to save Twilio config', false); }
  };

  const saveOllama = async () => {
    try {
      const updated = await updateOllamaConfig(ollama);
      setOllama(updated);
      showToast('Ollama config saved', true);
    } catch (e) { showToast('Failed to save Ollama config', false); }
  };

  const SaveBtn = ({ onClick }) => (
    <button className="btn btn-primary btn-sm" onClick={onClick}>Save</button>
  );

  const Field = ({ label, value, onChange, width = 140 }) => (
    <div style={{ display: 'inline-flex', flexDirection: 'column', marginRight: 24, marginBottom: 12 }}>
      <label className="text-xs text-muted" style={{ fontWeight: 600, marginBottom: 4 }}>{label}</label>
      <input className="input" style={{ width }} value={value ?? ''} onChange={e => onChange(e.target.value)} />
    </div>
  );

  if (loading) return <SkeletonLoader type="card" count={4} />;

  return (
    <div>
      {toast && (
        <div style={{
          position: 'fixed', top: 16, right: 16, zIndex: 999, padding: '10px 20px', borderRadius: 8,
          background: toast.ok ? 'var(--success)' : 'var(--danger)', color: '#fff', fontSize: 13, fontWeight: 600,
        }}>{toast.msg}</div>
      )}

      <div className="section-card" style={{ borderColor: 'var(--warning)', marginBottom: 20 }}>
        <div style={{ padding: 12, display: 'flex', alignItems: 'center', gap: 12, color: 'var(--warning)', fontSize: 13 }}>
          <span>ⓘ</span>
          <span>Threshold, feedback, silence detection, Twilio, Ollama, and transaction type changes apply immediately but reset to application.yml defaults on restart. Rule changes are persisted to Aerospike.</span>
        </div>
      </div>

      {/* Rules */}
      <SectionCard title="Anomaly Rules" trailing={
        dirtyRules.size > 0 ? <button className="btn btn-primary btn-sm" onClick={saveRules}>Save {dirtyRules.size} Modified</button> : null
      }>
        <div style={{ overflowX: 'auto' }}>
          <table className="data-table">
            <thead>
              <tr><th>Enabled</th><th>Rule Name</th><th>Type</th><th>Variance %</th><th>Weight</th></tr>
            </thead>
            <tbody>
              {rules.map(r => (
                <tr key={r.ruleId} style={dirtyRules.has(r.ruleId) ? { background: 'rgba(74,158,255,0.06)' } : undefined}>
                  <td>
                    <input type="checkbox" checked={r.enabled} onChange={e => updateRuleField(r.ruleId, 'enabled', e.target.checked)} />
                  </td>
                  <td title={r.description}>{r.name || r.ruleName} <span className="text-muted" style={{ cursor: 'help' }}>ⓘ</span></td>
                  <td className="text-sm">{r.ruleType}</td>
                  <td>
                    <input className="input" style={{ width: 80 }} type="number" value={r.variancePct ?? ''}
                      onChange={e => updateRuleField(r.ruleId, 'variancePct', parseFloat(e.target.value) || 0)} />
                  </td>
                  <td>
                    <input className="input" style={{ width: 60 }} type="number" step="0.1" value={r.riskWeight ?? ''}
                      onChange={e => updateRuleField(r.ruleId, 'riskWeight', parseFloat(e.target.value) || 0)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </SectionCard>

      {/* Thresholds */}
      {thresholds && (
        <SectionCard title="Global Thresholds" trailing={<SaveBtn onClick={saveThresholds} />}>
          <div style={{ display: 'flex', flexWrap: 'wrap' }}>
            <Field label="Alert Threshold" value={thresholds.alertThreshold} onChange={v => setThresholds(p => ({ ...p, alertThreshold: parseFloat(v) || 0 }))} />
            <Field label="Block Threshold" value={thresholds.blockThreshold} onChange={v => setThresholds(p => ({ ...p, blockThreshold: parseFloat(v) || 0 }))} />
            <Field label="EWMA Alpha" value={thresholds.ewmaAlpha} width={100} onChange={v => setThresholds(p => ({ ...p, ewmaAlpha: parseFloat(v) || 0 }))} />
            <Field label="Min Profile Txns" value={thresholds.minProfileTxns} width={120} onChange={v => setThresholds(p => ({ ...p, minProfileTxns: parseInt(v) || 0 }))} />
            <Field label="Breadth Multiplier" value={thresholds.breadthMultiplierPct} onChange={v => setThresholds(p => ({ ...p, breadthMultiplierPct: parseFloat(v) || 0 }))} />
          </div>
        </SectionCard>
      )}

      {/* Feedback */}
      {feedback && (
        <SectionCard title="Feedback & Auto-Tuning" trailing={<SaveBtn onClick={saveFeedback} />}>
          <div style={{ display: 'flex', flexWrap: 'wrap' }}>
            <Field label="Auto-Accept Timeout (ms)" value={feedback.autoAcceptTimeoutMs} width={160} onChange={v => setFeedback(p => ({ ...p, autoAcceptTimeoutMs: parseInt(v) || 0 }))} />
            <Field label="Tuning Interval (hrs)" value={feedback.tuningIntervalHours} onChange={v => setFeedback(p => ({ ...p, tuningIntervalHours: parseInt(v) || 0 }))} />
            <Field label="Min Samples" value={feedback.minSamplesForTuning} width={100} onChange={v => setFeedback(p => ({ ...p, minSamplesForTuning: parseInt(v) || 0 }))} />
            <Field label="Weight Floor" value={feedback.weightFloor} width={100} onChange={v => setFeedback(p => ({ ...p, weightFloor: parseFloat(v) || 0 }))} />
            <Field label="Weight Ceiling" value={feedback.weightCeiling} width={100} onChange={v => setFeedback(p => ({ ...p, weightCeiling: parseFloat(v) || 0 }))} />
            <Field label="Max Adjustment %" value={feedback.maxAdjustmentPct} width={120} onChange={v => setFeedback(p => ({ ...p, maxAdjustmentPct: parseFloat(v) || 0 }))} />
          </div>
        </SectionCard>
      )}

      {/* Transaction Types */}
      <SectionCard title="Transaction Types" trailing={<SaveBtn onClick={saveTxnTypes} />}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
          {txnTypes.map(t => (
            <span key={t} className="badge" style={{ background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text)', padding: '4px 10px', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              {t}
              <button style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', fontSize: 14, padding: 0, lineHeight: 1 }}
                onClick={() => setTxnTypes(prev => prev.filter(x => x !== t))}>×</button>
            </span>
          ))}
        </div>
        <div className="flex-row">
          <input className="input" style={{ maxWidth: 160 }} placeholder="e.g. CBDC" value={newType}
            onChange={e => setNewType(e.target.value)} onKeyDown={e => e.key === 'Enter' && addType()} />
          <button className="btn btn-outline btn-sm" onClick={addType}>Add</button>
        </div>
      </SectionCard>

      {/* Silence Detection */}
      {silence && (
        <SectionCard title="Silence Detection" trailing={<SaveBtn onClick={saveSilence} />}>
          <div className="flex-row mb-8">
            <label className="text-xs text-muted" style={{ fontWeight: 600 }}>Enabled</label>
            <input type="checkbox" checked={silence.enabled || false} onChange={e => setSilence(p => ({ ...p, enabled: e.target.checked }))} />
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap' }}>
            <Field label="Check Interval (min)" value={silence.checkIntervalMinutes} onChange={v => setSilence(p => ({ ...p, checkIntervalMinutes: parseInt(v) || 0 }))} />
            <Field label="Silence Multiplier" value={silence.silenceMultiplier} width={130} onChange={v => setSilence(p => ({ ...p, silenceMultiplier: parseFloat(v) || 0 }))} />
            <Field label="Min Expected TPS" value={silence.minExpectedTps} width={130} onChange={v => setSilence(p => ({ ...p, minExpectedTps: parseFloat(v) || 0 }))} />
            <Field label="Min Completed Hours" value={silence.minCompletedHours} width={150} onChange={v => setSilence(p => ({ ...p, minCompletedHours: parseInt(v) || 0 }))} />
          </div>
        </SectionCard>
      )}

      {/* Twilio */}
      {twilio && (
        <SectionCard title="Twilio Notifications" trailing={<SaveBtn onClick={saveTwilio} />}>
          <div className="flex-row mb-8" style={{ gap: 24 }}>
            <div className="flex-row">
              <label className="text-xs text-muted" style={{ fontWeight: 600 }}>Enabled</label>
              <input type="checkbox" checked={twilio.enabled || false} onChange={e => setTwilio(p => ({ ...p, enabled: e.target.checked }))} />
            </div>
            <div className="flex-row">
              <label className="text-xs text-muted" style={{ fontWeight: 600 }}>Channel</label>
              <select className="select" value={twilio.channel || 'sms'} onChange={e => setTwilio(p => ({ ...p, channel: e.target.value }))}>
                <option value="sms">SMS</option>
                <option value="whatsapp">WhatsApp</option>
              </select>
            </div>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap' }}>
            <Field label="Account SID" value={twilio.accountSid} width={220} onChange={v => setTwilio(p => ({ ...p, accountSid: v }))} />
            <Field label="From Number" value={twilio.fromNumber} width={160} onChange={v => setTwilio(p => ({ ...p, fromNumber: v }))} />
            <Field label="To Number" value={twilio.toNumber} width={160} onChange={v => setTwilio(p => ({ ...p, toNumber: v }))} />
          </div>
          <div className="text-xs text-muted" style={{ marginTop: 4 }}>Auth Token: {twilio.authToken || '(not set)'}</div>
        </SectionCard>
      )}

      {/* Ollama */}
      {ollama && (
        <SectionCard title="Ollama / LLM" trailing={<SaveBtn onClick={saveOllama} />}>
          <div style={{ display: 'flex', flexWrap: 'wrap' }}>
            <Field label="Host URL" value={ollama.host} width={260} onChange={v => setOllama(p => ({ ...p, host: v }))} />
            <Field label="Model" value={ollama.model} width={160} onChange={v => setOllama(p => ({ ...p, model: v }))} />
            <Field label="Timeout (seconds)" value={ollama.timeoutSeconds} width={130} onChange={v => setOllama(p => ({ ...p, timeoutSeconds: parseInt(v) || 0 }))} />
          </div>
        </SectionCard>
      )}

      {/* Aerospike */}
      {aerospike && (
        <SectionCard title="Aerospike Connection (Read-Only)">
          <div className="stat-row">
            <StatCard label="Host" value={aerospike.host || '—'} />
            <StatCard label="Port" value={aerospike.port || '—'} />
            <StatCard label="Namespace" value={aerospike.namespace || '—'} />
          </div>
        </SectionCard>
      )}
    </div>
  );
}
