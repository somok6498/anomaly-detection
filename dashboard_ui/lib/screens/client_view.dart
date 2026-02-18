import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../models/models.dart';
import '../theme/app_theme.dart';
import '../widgets/stat_card.dart';
import '../widgets/section_card.dart';
import '../widgets/badge_widget.dart';

class ClientView extends StatelessWidget {
  final ClientProfile profile;
  final List<Transaction> transactions;
  final List<EvaluationResult> evaluations;
  final void Function(String txnId) onTxnTap;

  const ClientView({
    super.key,
    required this.profile,
    required this.transactions,
    required this.evaluations,
    required this.onTxnTap,
  });

  String _formatAmount(double n) {
    final formatter = NumberFormat.currency(locale: 'en_IN', symbol: '₹', decimalDigits: 2);
    return formatter.format(n);
  }

  String _formatNum(int n) {
    return NumberFormat('#,##,###', 'en_IN').format(n);
  }

  String _formatTime(int epoch) {
    if (epoch == 0) return '-';
    return DateFormat('dd MMM yyyy, hh:mm a')
        .format(DateTime.fromMillisecondsSinceEpoch(epoch));
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildProfileCard(),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: _buildTypeDistribution()),
            const SizedBox(width: 20),
            Expanded(child: _buildAvgAmountByType()),
          ],
        ),
        _buildTransactionHistory(),
        _buildEvaluationHistory(),
      ],
    );
  }

  Widget _buildProfileCard() {
    return SectionCard(
      title: 'Client Profile: ${profile.clientId}',
      child: Wrap(
        spacing: 14,
        runSpacing: 14,
        children: [
          SizedBox(width: 180, child: StatCard(label: 'Total Transactions', value: _formatNum(profile.totalTxnCount))),
          SizedBox(width: 180, child: StatCard(label: 'EWMA Amount', value: _formatAmount(profile.ewmaAmount))),
          SizedBox(width: 180, child: StatCard(label: 'Amount Std Dev', value: _formatAmount(profile.amountStdDev))),
          SizedBox(width: 180, child: StatCard(label: 'EWMA Hourly TPS', value: profile.ewmaHourlyTps.toStringAsFixed(2))),
          SizedBox(width: 180, child: StatCard(label: 'TPS Std Dev', value: profile.tpsStdDev.toStringAsFixed(2))),
          SizedBox(width: 180, child: StatCard(label: 'Last Updated', value: _formatTime(profile.lastUpdated))),
        ],
      ),
    );
  }

  Widget _buildTypeDistribution() {
    final total = profile.txnTypeCounts.values.fold(0, (a, b) => a + b);
    final sorted = profile.txnTypeCounts.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));

    return SectionCard(
      title: 'Transaction Type Distribution',
      child: Column(
        children: sorted.asMap().entries.map((entry) {
          final i = entry.key;
          final type = entry.value.key;
          final count = entry.value.value;
          final pct = total > 0 ? (count / total * 100) : 0.0;
          final color = AppTheme.typeColors[i % AppTheme.typeColors.length];

          return Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Row(
              children: [
                SizedBox(
                  width: 50,
                  child: Text(type, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppTheme.textSecondary), textAlign: TextAlign.right),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Stack(
                    children: [
                      Container(height: 24, decoration: BoxDecoration(color: AppTheme.surface, borderRadius: BorderRadius.circular(4))),
                      FractionallySizedBox(
                        widthFactor: (pct / 100).clamp(0.02, 1.0),
                        child: Container(
                          height: 24,
                          decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(4)),
                          alignment: Alignment.centerLeft,
                          padding: const EdgeInsets.only(left: 8),
                          child: Text(_formatNum(count), style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: Colors.white)),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                SizedBox(width: 50, child: Text('${pct.toStringAsFixed(1)}%', style: const TextStyle(fontSize: 12, color: AppTheme.textSecondary))),
              ],
            ),
          );
        }).toList(),
      ),
    );
  }

  Widget _buildAvgAmountByType() {
    final sorted = profile.avgAmountByType.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));
    final maxVal = sorted.isNotEmpty ? sorted.first.value : 1.0;

    return SectionCard(
      title: 'Avg Amount by Type',
      child: Column(
        children: sorted.asMap().entries.map((entry) {
          final i = entry.key;
          final type = entry.value.key;
          final avg = entry.value.value;
          final pct = maxVal > 0 ? (avg / maxVal * 100) : 0.0;
          final color = AppTheme.typeColors[i % AppTheme.typeColors.length];

          return Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Row(
              children: [
                SizedBox(width: 50, child: Text(type, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppTheme.textSecondary), textAlign: TextAlign.right)),
                const SizedBox(width: 10),
                Expanded(
                  child: Stack(
                    children: [
                      Container(height: 24, decoration: BoxDecoration(color: AppTheme.surface, borderRadius: BorderRadius.circular(4))),
                      FractionallySizedBox(
                        widthFactor: (pct / 100).clamp(0.02, 1.0),
                        child: Container(
                          height: 24,
                          decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(4)),
                          alignment: Alignment.centerLeft,
                          padding: const EdgeInsets.only(left: 8),
                          child: Text(_formatAmount(avg), style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: Colors.white)),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 60),
              ],
            ),
          );
        }).toList(),
      ),
    );
  }

  Widget _buildTransactionHistory() {
    return SectionCard(
      title: 'Transaction History (Latest ${transactions.length})',
      child: transactions.isEmpty
          ? const Center(child: Text('No transactions found.', style: TextStyle(color: AppTheme.textSecondary)))
          : SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: DataTable(
                headingRowColor: WidgetStateProperty.all(Colors.transparent),
                dataRowColor: WidgetStateProperty.all(Colors.transparent),
                columns: const [
                  DataColumn(label: Text('TXN ID', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('TYPE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('AMOUNT', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('TIMESTAMP', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                ],
                rows: transactions.map((t) {
                  return DataRow(cells: [
                    DataCell(
                      GestureDetector(
                        onTap: () => onTxnTap(t.txnId),
                        child: Text(t.txnId, style: const TextStyle(color: AppTheme.accent, fontSize: 13)),
                      ),
                    ),
                    DataCell(Text(t.txnType, style: const TextStyle(fontSize: 13, color: AppTheme.textPrimary))),
                    DataCell(Text(_formatAmount(t.amount), style: const TextStyle(fontSize: 13, color: AppTheme.textPrimary))),
                    DataCell(Text(_formatTime(t.timestamp), style: const TextStyle(fontSize: 13, color: AppTheme.textPrimary))),
                  ]);
                }).toList(),
              ),
            ),
    );
  }

  Widget _buildEvaluationHistory() {
    return SectionCard(
      title: 'Evaluation History (Latest ${evaluations.length})',
      child: evaluations.isEmpty
          ? const Center(child: Text('No evaluations found. Submit transactions via /evaluate API first.', style: TextStyle(color: AppTheme.textSecondary)))
          : SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: DataTable(
                headingRowColor: WidgetStateProperty.all(Colors.transparent),
                dataRowColor: WidgetStateProperty.all(Colors.transparent),
                columns: const [
                  DataColumn(label: Text('TXN ID', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('SCORE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('RISK', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('ACTION', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('RULES', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                  DataColumn(label: Text('EVALUATED', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                ],
                rows: evaluations.map((e) {
                  final triggered = e.ruleResults.where((r) => r.triggered).length;
                  return DataRow(cells: [
                    DataCell(
                      GestureDetector(
                        onTap: () => onTxnTap(e.txnId),
                        child: Text(e.txnId, style: const TextStyle(color: AppTheme.accent, fontSize: 13)),
                      ),
                    ),
                    DataCell(Text(e.compositeScore.toStringAsFixed(1), style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: Colors.white))),
                    DataCell(RiskBadge(level: e.riskLevel)),
                    DataCell(ActionBadge(action: e.action)),
                    DataCell(Text('$triggered/${e.ruleResults.length}', style: const TextStyle(fontSize: 13, color: AppTheme.textPrimary))),
                    DataCell(Text(_formatTime(e.evaluatedAt), style: const TextStyle(fontSize: 13, color: AppTheme.textPrimary))),
                  ]);
                }).toList(),
              ),
            ),
    );
  }
}
