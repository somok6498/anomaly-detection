import { useState, useCallback } from 'react';
import { riskColor, actionColor, feedbackColor, feedbackLabel } from '../utils/helpers';

/* ─────────────────────────────────────────────
   1. SectionCard
   ───────────────────────────────────────────── */
export function SectionCard({ title, trailing, children }) {
  return (
    <div className="section-card">
      {title && (
        <>
          <div className="section-card-header">
            <span className="section-card-title">{title}</span>
            {trailing && <div className="section-card-trailing">{trailing}</div>}
          </div>
          <div className="section-card-divider" />
        </>
      )}
      <div className="section-card-body">{children}</div>
    </div>
  );
}

/* ─────────────────────────────────────────────
   2. StatCard
   ───────────────────────────────────────────── */
export function StatCard({ label, value, color, onClick }) {
  const style = color ? { color } : undefined;
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      className={`stat-card${onClick ? ' stat-card--clickable' : ''}`}
      onClick={onClick || undefined}
      type={onClick ? 'button' : undefined}
    >
      <span className="stat-card-label">{label}</span>
      <span className="stat-card-value" style={style}>{value}</span>
    </Tag>
  );
}

/* ─────────────────────────────────────────────
   3. ActionBadge
   ───────────────────────────────────────────── */
export function ActionBadge({ action }) {
  const color = actionColor(action);
  const cls =
    action === 'PASS'
      ? 'badge-pass'
      : action === 'ALERT'
        ? 'badge-alert'
        : action === 'BLOCK'
          ? 'badge-block'
          : 'badge-default';
  return (
    <span
      className={`badge ${cls}`}
      style={{ color, backgroundColor: `${color}26` }}
    >
      {action}
    </span>
  );
}

/* ─────────────────────────────────────────────
   4. FeedbackBadge
   ───────────────────────────────────────────── */
export function FeedbackBadge({ status }) {
  const color = feedbackColor(status);
  const text = feedbackLabel(status);
  const cls =
    status === 'TRUE_POSITIVE'
      ? 'badge-tp'
      : status === 'FALSE_POSITIVE'
        ? 'badge-fp'
        : status === 'AUTO_ACCEPTED'
          ? 'badge-auto'
          : 'badge-pending';
  return (
    <span
      className={`badge ${cls}`}
      style={{ color, backgroundColor: `${color}26` }}
    >
      {text}
    </span>
  );
}

/* ─────────────────────────────────────────────
   5. RiskBadge
   ───────────────────────────────────────────── */
export function RiskBadge({ level }) {
  const color = riskColor(level);
  return (
    <span
      className="badge badge-risk"
      style={{ color, backgroundColor: `${color}26` }}
    >
      {level}
    </span>
  );
}

/* ─────────────────────────────────────────────
   6. TimeRangeSelector
   ───────────────────────────────────────────── */
const TIME_PRESETS = ['1m', '5m', '15m', '30m', '1h', '6h', '12h', '24h', '7d'];

export function TimeRangeSelector({ value, onChange, onCustomRange }) {
  const [showCustom, setShowCustom] = useState(false);
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');

  const handlePresetClick = useCallback(
    (preset) => {
      setShowCustom(false);
      onChange(preset);
    },
    [onChange],
  );

  const handleCustomToggle = useCallback(() => {
    setShowCustom((prev) => !prev);
  }, []);

  const handleApplyCustom = useCallback(() => {
    if (customFrom && customTo) {
      const fromDate = new Date(customFrom);
      const toDate = new Date(customTo);
      if (fromDate < toDate) {
        onCustomRange(fromDate, toDate);
        setShowCustom(false);
      }
    }
  }, [customFrom, customTo, onCustomRange]);

  const isValid = customFrom && customTo && new Date(customFrom) < new Date(customTo);

  return (
    <div className="time-range-selector">
      <div className="time-range-chips">
        <span className="time-range-icon">&#128339;</span>
        {TIME_PRESETS.map((preset) => (
          <button
            key={preset}
            type="button"
            className={`time-chip${value === preset ? ' time-chip--active' : ''}`}
            onClick={() => handlePresetClick(preset)}
          >
            {preset}
          </button>
        ))}
        <button
          type="button"
          className={`time-chip${value === 'custom' || showCustom ? ' time-chip--active' : ''}`}
          onClick={handleCustomToggle}
        >
          <span className="time-chip-cal-icon">&#128197;</span>
          {value === 'custom' ? 'Custom*' : 'Custom'}
        </button>
      </div>
      {showCustom && (
        <div className="time-range-custom">
          <div className="time-range-custom-row">
            <label className="time-range-custom-label">From</label>
            <input
              type="datetime-local"
              className="time-range-input"
              value={customFrom}
              onChange={(e) => setCustomFrom(e.target.value)}
            />
          </div>
          <div className="time-range-custom-row">
            <label className="time-range-custom-label">To</label>
            <input
              type="datetime-local"
              className="time-range-input"
              value={customTo}
              onChange={(e) => setCustomTo(e.target.value)}
            />
          </div>
          {customFrom && customTo && !isValid && (
            <span className="time-range-error">&quot;From&quot; must be before &quot;To&quot;</span>
          )}
          <button
            type="button"
            className="time-range-apply"
            disabled={!isValid}
            onClick={handleApplyCustom}
          >
            Apply
          </button>
        </div>
      )}
    </div>
  );
}

/* ─────────────────────────────────────────────
   7. SkeletonLoader
   ───────────────────────────────────────────── */
export function SkeletonLoader({ type = 'lines', count = 3 }) {
  if (type === 'card') {
    return (
      <div className="skeleton-wrapper">
        {Array.from({ length: count }, (_, i) => (
          <div key={i} className="skeleton-card">
            <div className="skeleton-line skeleton-line--title" />
            <div className="skeleton-card-stats">
              {Array.from({ length: 6 }, (_, j) => (
                <div key={j} className="skeleton-card-stat">
                  <div className="skeleton-line skeleton-line--sm" />
                  <div className="skeleton-line skeleton-line--md" />
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    );
  }

  if (type === 'table') {
    const cols = 4;
    const rows = count;
    return (
      <div className="skeleton-table">
        <div className="skeleton-line skeleton-line--title" />
        <div className="skeleton-table-header">
          {Array.from({ length: cols }, (_, i) => (
            <div key={i} className="skeleton-table-cell">
              <div className="skeleton-line skeleton-line--sm" />
            </div>
          ))}
        </div>
        <div className="skeleton-table-divider" />
        {Array.from({ length: rows }, (_, r) => (
          <div key={r} className="skeleton-table-row">
            {Array.from({ length: cols }, (_, c) => (
              <div key={c} className="skeleton-table-cell">
                <div className="skeleton-line" />
              </div>
            ))}
          </div>
        ))}
      </div>
    );
  }

  /* default: lines */
  return (
    <div className="skeleton-wrapper">
      {Array.from({ length: count }, (_, i) => (
        <div
          key={i}
          className="skeleton-line"
          style={{ width: `${60 + Math.random() * 40}%` }}
        />
      ))}
    </div>
  );
}

/* ─────────────────────────────────────────────
   8. ScoreCircle
   ───────────────────────────────────────────── */
export function ScoreCircle({ score, riskLevel }) {
  const color = riskColor(riskLevel);
  return (
    <div
      className="score-circle"
      style={{ borderColor: color, color }}
    >
      <span className="score-circle-value">{score}</span>
    </div>
  );
}
