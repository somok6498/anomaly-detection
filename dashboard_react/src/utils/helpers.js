export function formatAmount(val) {
  if (val == null) return '0';
  return Number(val).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

export function formatDate(epochMs) {
  if (!epochMs) return '—';
  return new Date(epochMs).toLocaleString();
}

export function formatShortDate(epochMs) {
  if (!epochMs) return '—';
  const d = new Date(epochMs);
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
}

export function riskColor(level) {
  const map = { LOW: '#10B981', MEDIUM: '#F59E0B', HIGH: '#C4243B', CRITICAL: '#9d1d27' };
  return map[level] || '#9e9e9e';
}

export function actionColor(action) {
  const map = { PASS: '#10B981', ALERT: '#F59E0B', BLOCK: '#C4243B' };
  return map[action] || '#9e9e9e';
}

export function feedbackColor(status) {
  const map = {
    PENDING: '#F59E0B', TRUE_POSITIVE: '#C4243B', FALSE_POSITIVE: '#10B981', AUTO_ACCEPTED: '#9e9e9e',
  };
  return map[status] || '#9e9e9e';
}

export function feedbackLabel(status) {
  const map = {
    PENDING: 'Pending', TRUE_POSITIVE: 'True +ve', FALSE_POSITIVE: 'False +ve', AUTO_ACCEPTED: 'Auto',
  };
  return map[status] || status;
}

export function urgencyColor(level) {
  const map = { CRITICAL: '#9d1d27', HIGH: '#C4243B', MEDIUM: '#F59E0B', LOW: '#10B981' };
  return map[level] || '#9e9e9e';
}

export function downloadBlob(content, filename, mime = 'text/csv') {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export function timestampedFilename(base, ext) {
  const d = new Date();
  const ts = d.toISOString().replace(/[-:T]/g, '').slice(0, 14);
  return `${base}_${ts}.${ext}`;
}

export function timePresetToMs(preset) {
  const map = {
    '1m': 60e3, '5m': 5 * 60e3, '15m': 15 * 60e3, '30m': 30 * 60e3,
    '1h': 3600e3, '6h': 6 * 3600e3, '12h': 12 * 3600e3, '24h': 86400e3, '7d': 7 * 86400e3,
  };
  return map[preset] || 15 * 60e3;
}
