import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../models/models.dart';
import '../theme/app_theme.dart';
import '../widgets/stat_card.dart';
import '../widgets/section_card.dart';
import '../widgets/badge_widget.dart';

class TransactionView extends StatelessWidget {
  final Transaction transaction;
  final EvaluationResult? evaluation;
  final void Function(String clientId) onClientTap;

  const TransactionView({
    super.key,
    required this.transaction,
    required this.evaluation,
    required this.onClientTap,
  });

  String _formatAmount(double n) {
    return NumberFormat.currency(locale: 'en_IN', symbol: '₹', decimalDigits: 2).format(n);
  }

  String _formatTime(int epoch) {
    if (epoch == 0) return '-';
    return DateFormat('dd MMM yyyy, hh:mm a').format(DateTime.fromMillisecondsSinceEpoch(epoch));
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildTxnDetail(),
        if (evaluation != null) _buildEvaluation(evaluation!),
        if (evaluation == null)
          SectionCard(
            title: 'Risk Evaluation',
            child: Center(
              child: Padding(
                padding: EdgeInsets.all(20),
                child: Text(
                  'No evaluation result found. Submit via POST /api/v1/transactions/evaluate to get a risk assessment.',
                  style: TextStyle(color: AppTheme.textSecondary),
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildTxnDetail() {
    return SectionCard(
      title: 'Transaction: ${transaction.txnId}',
      child: Wrap(
        spacing: 14,
        runSpacing: 14,
        children: [
          SizedBox(
            width: 200,
            child: GestureDetector(
              onTap: () => onClientTap(transaction.clientId),
              child: StatCard(label: 'Client ID', value: transaction.clientId, valueColor: AppTheme.accent),
            ),
          ),
          SizedBox(width: 180, child: StatCard(label: 'Type', value: transaction.txnType)),
          SizedBox(width: 200, child: StatCard(label: 'Amount', value: _formatAmount(transaction.amount))),
          SizedBox(width: 220, child: StatCard(label: 'Timestamp', value: _formatTime(transaction.timestamp))),
        ],
      ),
    );
  }

  Widget _buildEvaluation(EvaluationResult eval) {
    final scoreColor = AppTheme.riskColor(eval.riskLevel);

    return SectionCard(
      title: 'Risk Evaluation',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Score circle + action
          Row(
            children: [
              Container(
                width: 80,
                height: 80,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(color: scoreColor, width: 3),
                  gradient: RadialGradient(
                    colors: [scoreColor.withValues(alpha: 0.2), scoreColor.withValues(alpha: 0.05)],
                  ),
                ),
                alignment: Alignment.center,
                child: Text(
                  eval.compositeScore.toStringAsFixed(1),
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800, color: AppTheme.textPrimary),
                ),
              ),
              const SizedBox(width: 20),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ActionBadge(action: eval.action),
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      Text('Risk Level: ', style: TextStyle(fontSize: 13, color: AppTheme.textSecondary)),
                      RiskBadge(level: eval.riskLevel),
                    ],
                  ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 24),

          // Rule results table
          if (eval.ruleResults.isNotEmpty) ...[
            Text('Rule Evaluation Details', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppTheme.textSecondary)),
            const SizedBox(height: 12),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: DataTable(
                headingRowColor: WidgetStateProperty.all(Colors.transparent),
                dataRowColor: WidgetStateProperty.all(Colors.transparent),
                columns: [
                  DataColumn(label: Text('RULE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('TYPE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('TRIGGERED', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('DEVIATION', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('SCORE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('WEIGHT', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('REASON', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                ],
                rows: eval.ruleResults.map((r) {
                  return DataRow(cells: [
                    DataCell(Text(r.ruleName, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppTheme.textPrimary))),
                    DataCell(Text(r.ruleType, style: TextStyle(fontSize: 13, color: AppTheme.textPrimary))),
                    DataCell(Text(
                      r.triggered ? 'YES' : 'NO',
                      style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: r.triggered ? AppTheme.critical : AppTheme.low),
                    )),
                    DataCell(Text('${r.deviationPct.toStringAsFixed(1)}%', style: TextStyle(fontSize: 13, color: AppTheme.textPrimary))),
                    DataCell(Text(r.partialScore.toStringAsFixed(1), style: TextStyle(fontSize: 13, color: AppTheme.textPrimary))),
                    DataCell(Text(r.riskWeight.toString(), style: TextStyle(fontSize: 13, color: AppTheme.textPrimary))),
                    DataCell(SizedBox(
                      width: 300,
                      child: Text(r.reason, style: TextStyle(fontSize: 12, color: AppTheme.textSecondary)),
                    )),
                  ]);
                }).toList(),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
