import { SectionCard, StatCard, ActionBadge, RiskBadge, ScoreCircle } from './SharedComponents';
import { formatAmount, formatDate, riskColor } from '../utils/helpers';

export default function TransactionView({ transaction, evaluation, onClientTap }) {
  return (
    <div>
      {/* Transaction Details */}
      <SectionCard title={`Transaction: ${transaction.txnId}`}>
        <div className="stat-row">
          <div onClick={() => onClientTap(transaction.clientId)} style={{ cursor: 'pointer' }}>
            <StatCard label="Client ID" value={transaction.clientId} color="var(--accent)" />
          </div>
          <StatCard label="Type" value={transaction.txnType} />
          <StatCard label="Amount" value={`₹${formatAmount(transaction.amount)}`} />
          <StatCard label="Timestamp" value={formatDate(transaction.timestamp)} />
        </div>
      </SectionCard>

      {/* Risk Evaluation */}
      {evaluation ? (
        <SectionCard title="Risk Evaluation">
          <div style={{ display: 'flex', alignItems: 'center', gap: 20, marginBottom: 20 }}>
            <ScoreCircle score={evaluation.compositeScore?.toFixed(1)} riskLevel={evaluation.riskLevel} />
            <div>
              <ActionBadge action={evaluation.action} />
              <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 6 }}>
                <span className="text-sm text-muted">Risk Level:</span>
                <RiskBadge level={evaluation.riskLevel} />
              </div>
              {evaluation.triggeredRuleCount > 0 && (
                <div className="text-xs text-muted" style={{ marginTop: 6 }}>
                  {evaluation.triggeredRuleCount} rule{evaluation.triggeredRuleCount === 1 ? '' : 's'} triggered
                  {evaluation.breadthBonus > 0 && ` · +${(evaluation.breadthBonus * 100).toFixed(1)}% breadth boost`}
                </div>
              )}
            </div>
          </div>

          {/* AI Explanation */}
          {evaluation.aiExplanation && (
            <div style={{
              padding: 16, marginBottom: 20, borderRadius: 10,
              background: 'rgba(74,158,255,0.08)', border: '1px solid rgba(74,158,255,0.3)',
            }}>
              <div style={{ display: 'flex', gap: 12 }}>
                <span style={{ fontSize: 20 }}>🤖</span>
                <div>
                  <div style={{ color: 'var(--accent)', fontSize: 12, fontWeight: 700, marginBottom: 6 }}>AI Analysis</div>
                  <div style={{ color: 'var(--text)', fontSize: 13, lineHeight: 1.5 }}>{evaluation.aiExplanation}</div>
                </div>
              </div>
            </div>
          )}

          {/* Rule Results Table */}
          {evaluation.ruleResults?.length > 0 && (
            <>
              <div className="text-sm text-muted" style={{ fontWeight: 600, marginBottom: 12 }}>Rule Evaluation Details</div>
              <div style={{ overflowX: 'auto' }}>
                <table className="data-table">
                  <thead>
                    <tr><th>Rule</th><th>Type</th><th>Triggered</th><th>Deviation</th><th>Score</th><th>Weight</th><th>Reason</th></tr>
                  </thead>
                  <tbody>
                    {evaluation.ruleResults.map((r, i) => (
                      <tr key={i}>
                        <td style={{ fontWeight: 600 }}>{r.ruleName}</td>
                        <td>{r.ruleType}</td>
                        <td style={{ fontWeight: 600, color: r.triggered ? 'var(--danger)' : 'var(--success)' }}>
                          {r.triggered ? 'YES' : 'NO'}
                        </td>
                        <td>{r.deviationPct?.toFixed(1)}%</td>
                        <td>{r.partialScore?.toFixed(1)}</td>
                        <td>{r.riskWeight}</td>
                        <td className="text-sm text-muted" style={{ maxWidth: 300 }}>{r.reason}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </SectionCard>
      ) : (
        <SectionCard title="Risk Evaluation">
          <div className="no-data" style={{ padding: 20 }}>
            No evaluation result found. Submit via POST /api/v1/transactions/evaluate to get a risk assessment.
          </div>
        </SectionCard>
      )}
    </div>
  );
}
