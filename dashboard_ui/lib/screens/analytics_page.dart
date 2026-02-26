import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';
import '../models/models.dart';
import '../services/api_service.dart';
import '../theme/app_theme.dart';
import '../widgets/section_card.dart';
import '../widgets/stat_card.dart';
import '../widgets/network_graph_widget.dart';

class AnalyticsPage extends StatefulWidget {
  final VoidCallback? onExportRulesCsv;
  final VoidCallback? onExportRulesPdf;

  const AnalyticsPage({super.key, this.onExportRulesCsv, this.onExportRulesPdf});

  @override
  State<AnalyticsPage> createState() => AnalyticsPageState();
}

class AnalyticsPageState extends State<AnalyticsPage> {
  final _api = ApiService();
  final _networkClientController = TextEditingController();

  bool _loadingRules = true;
  bool _loadingNetwork = false;
  String? _rulesError;
  String? _networkError;

  List<RulePerformance> _ruleStats = [];
  NetworkGraph? _networkGraph;
  GraphStatus? _graphStatus;
  String? _networkClientId;

  // Expose rule stats for export
  List<RulePerformance> get ruleStats => _ruleStats;

  @override
  void initState() {
    super.initState();
    _loadRulePerformance();
  }

  Future<void> _loadRulePerformance() async {
    setState(() {
      _loadingRules = true;
      _rulesError = null;
    });
    try {
      final results = await Future.wait([
        _api.getRulePerformance(),
        _api.getGraphStatus(),
      ]);
      if (mounted) {
        setState(() {
          _ruleStats = results[0] as List<RulePerformance>;
          _graphStatus = results[1] as GraphStatus;
          _loadingRules = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _rulesError = e.toString().replaceFirst('Exception: ', '');
          _loadingRules = false;
        });
      }
    }
  }

  Future<void> _loadNetwork(String clientId) async {
    if (clientId.isEmpty) return;
    setState(() {
      _loadingNetwork = true;
      _networkError = null;
      _networkClientId = clientId;
    });
    try {
      final graph = await _api.getClientNetwork(clientId);
      if (mounted) {
        setState(() {
          _networkGraph = graph;
          _loadingNetwork = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _networkError = e.toString().replaceFirst('Exception: ', '');
          _loadingNetwork = false;
        });
      }
    }
  }

  @override
  void dispose() {
    _networkClientController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1400),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildRulePerformanceSection(),
              const SizedBox(height: 8),
              _buildNetworkSection(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildRulePerformanceSection() {
    if (_loadingRules) {
      return const SectionCard(
        title: 'Rule Performance',
        child: Center(
          child: Padding(
            padding: EdgeInsets.all(40),
            child: CircularProgressIndicator(color: AppTheme.accent),
          ),
        ),
      );
    }

    if (_rulesError != null) {
      return SectionCard(
        title: 'Rule Performance',
        child: Text(_rulesError!, style: const TextStyle(color: AppTheme.critical)),
      );
    }

    final rulesWithTriggers = _ruleStats.where((r) => r.triggerCount > 0).toList();

    return SectionCard(
      title: 'Rule Performance Analytics',
      trailing: (widget.onExportRulesCsv != null || widget.onExportRulesPdf != null)
          ? PopupMenuButton<String>(
              icon: const Icon(Icons.download, color: AppTheme.textSecondary, size: 20),
              color: AppTheme.surface,
              onSelected: (v) {
                if (v == 'csv') widget.onExportRulesCsv?.call();
                if (v == 'pdf') widget.onExportRulesPdf?.call();
              },
              itemBuilder: (_) => [
                const PopupMenuItem(value: 'csv', child: Text('Export CSV', style: TextStyle(color: AppTheme.textPrimary, fontSize: 13))),
                const PopupMenuItem(value: 'pdf', child: Text('Export PDF', style: TextStyle(color: AppTheme.textPrimary, fontSize: 13))),
              ],
            )
          : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Precision bar chart
          if (rulesWithTriggers.isNotEmpty) ...[
            const Text('Precision by Rule (feedback-based)',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppTheme.textSecondary)),
            const SizedBox(height: 12),
            SizedBox(
              height: rulesWithTriggers.length * 36.0 + 40,
              child: BarChart(
                BarChartData(
                  alignment: BarChartAlignment.spaceAround,
                  maxY: 1.0,
                  barTouchData: BarTouchData(
                    touchTooltipData: BarTouchTooltipData(
                      getTooltipColor: (_) => AppTheme.surface,
                      getTooltipItem: (group, groupIndex, rod, rodIndex) {
                        final rule = rulesWithTriggers[group.x];
                        return BarTooltipItem(
                          '${rule.ruleName}\nPrecision: ${(rule.precision * 100).toStringAsFixed(1)}%\nTP: ${rule.tpCount} FP: ${rule.fpCount}',
                          const TextStyle(color: AppTheme.textPrimary, fontSize: 11),
                        );
                      },
                    ),
                  ),
                  titlesData: FlTitlesData(
                    leftTitles: AxisTitles(
                      sideTitles: SideTitles(
                        showTitles: true,
                        reservedSize: 36,
                        interval: 0.25,
                        getTitlesWidget: (value, meta) => Text(
                          '${(value * 100).toInt()}%',
                          style: const TextStyle(fontSize: 10, color: AppTheme.textSecondary),
                        ),
                      ),
                    ),
                    bottomTitles: AxisTitles(
                      sideTitles: SideTitles(
                        showTitles: true,
                        reservedSize: 60,
                        getTitlesWidget: (value, meta) {
                          final idx = value.toInt();
                          if (idx < 0 || idx >= rulesWithTriggers.length) return const SizedBox();
                          final name = rulesWithTriggers[idx].ruleName;
                          return Padding(
                            padding: const EdgeInsets.only(top: 8),
                            child: RotatedBox(
                              quarterTurns: -1,
                              child: Text(
                                name.length > 18 ? '${name.substring(0, 16)}...' : name,
                                style: const TextStyle(fontSize: 9, color: AppTheme.textSecondary),
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                    topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                    rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                  ),
                  borderData: FlBorderData(show: false),
                  gridData: FlGridData(
                    show: true,
                    drawVerticalLine: false,
                    horizontalInterval: 0.25,
                    getDrawingHorizontalLine: (value) => FlLine(
                      color: AppTheme.cardBorder.withValues(alpha: 0.5),
                      strokeWidth: 0.5,
                    ),
                  ),
                  barGroups: rulesWithTriggers.asMap().entries.map((entry) {
                    final precision = entry.value.precision;
                    Color barColor;
                    if (precision >= 0.7) {
                      barColor = AppTheme.pass;
                    } else if (precision >= 0.4) {
                      barColor = AppTheme.alert;
                    } else {
                      barColor = AppTheme.critical;
                    }
                    return BarChartGroupData(
                      x: entry.key,
                      barRods: [
                        BarChartRodData(
                          toY: precision,
                          color: barColor,
                          width: 20,
                          borderRadius: const BorderRadius.vertical(top: Radius.circular(3)),
                        ),
                      ],
                    );
                  }).toList(),
                ),
              ),
            ),
            const SizedBox(height: 24),
          ],
          // Full table
          const Text('All Rules',
              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppTheme.textSecondary)),
          const SizedBox(height: 8),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: DataTable(
              headingRowColor: WidgetStateProperty.all(Colors.transparent),
              dataRowColor: WidgetStateProperty.all(Colors.transparent),
              columns: const [
                DataColumn(label: Text('RULE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                DataColumn(label: Text('TYPE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                DataColumn(label: Text('WEIGHT', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                DataColumn(label: Text('TRIGGERS', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                DataColumn(label: Text('TP', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                DataColumn(label: Text('FP', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
                DataColumn(label: Text('PRECISION', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary))),
              ],
              rows: _ruleStats.map((r) {
                Color precColor = AppTheme.textSecondary;
                if (r.triggerCount > 0) {
                  if (r.precision >= 0.7) {
                    precColor = AppTheme.pass;
                  } else if (r.precision >= 0.4) {
                    precColor = AppTheme.alert;
                  } else {
                    precColor = AppTheme.critical;
                  }
                }
                return DataRow(cells: [
                  DataCell(Text(r.ruleName, style: const TextStyle(fontSize: 12, color: AppTheme.textPrimary))),
                  DataCell(Text(r.ruleType, style: const TextStyle(fontSize: 11, color: AppTheme.textSecondary))),
                  DataCell(Text(r.currentWeight.toStringAsFixed(1), style: const TextStyle(fontSize: 12, color: AppTheme.textPrimary))),
                  DataCell(Text(r.triggerCount.toString(), style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: Colors.white))),
                  DataCell(Text(r.tpCount.toString(), style: const TextStyle(fontSize: 12, color: AppTheme.pass))),
                  DataCell(Text(r.fpCount.toString(), style: const TextStyle(fontSize: 12, color: AppTheme.critical))),
                  DataCell(Text(
                    r.triggerCount > 0 ? '${(r.precision * 100).toStringAsFixed(1)}%' : '-',
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: precColor),
                  )),
                ]);
              }).toList(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNetworkSection() {
    return SectionCard(
      title: 'Beneficiary Network Visualization',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Graph status info
          if (_graphStatus != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: Wrap(
                spacing: 14,
                runSpacing: 8,
                children: [
                  SizedBox(width: 160, child: StatCard(
                    label: 'Graph Status',
                    value: _graphStatus!.isReady ? 'Ready' : 'Not Ready',
                  )),
                  SizedBox(width: 160, child: StatCard(
                    label: 'Beneficiaries',
                    value: _graphStatus!.totalBeneficiaries.toString(),
                  )),
                  SizedBox(width: 160, child: StatCard(
                    label: 'Clients',
                    value: _graphStatus!.totalClients.toString(),
                  )),
                ],
              ),
            ),
          // Search bar
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _networkClientController,
                  style: const TextStyle(color: AppTheme.textPrimary, fontSize: 14),
                  decoration: const InputDecoration(
                    hintText: 'Enter Client ID (e.g. CLIENT-007)...',
                    contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  ),
                  onSubmitted: (_) => _loadNetwork(_networkClientController.text.trim()),
                ),
              ),
              const SizedBox(width: 12),
              ElevatedButton(
                onPressed: _loadingNetwork ? null : () => _loadNetwork(_networkClientController.text.trim()),
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppTheme.accent,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                ),
                child: const Text('Load Network', style: TextStyle(fontWeight: FontWeight.w600)),
              ),
            ],
          ),
          const SizedBox(height: 16),
          // Network graph
          if (_loadingNetwork)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(40),
                child: CircularProgressIndicator(color: AppTheme.accent),
              ),
            )
          else if (_networkError != null)
            Text(_networkError!, style: const TextStyle(color: AppTheme.critical))
          else if (_networkGraph != null) ...[
            // Legend
            Row(
              children: [
                _legendDot(AppTheme.pass, 'Center Client'),
                const SizedBox(width: 16),
                _legendDot(AppTheme.accent, 'Other Client'),
                const SizedBox(width: 16),
                _legendDot(AppTheme.alert, 'Beneficiary'),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              '${_networkGraph!.nodes.length} nodes, ${_networkGraph!.edges.length} edges',
              style: const TextStyle(fontSize: 12, color: AppTheme.textSecondary),
            ),
            const SizedBox(height: 8),
            Container(
              decoration: BoxDecoration(
                color: AppTheme.surface,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: AppTheme.cardBorder),
              ),
              child: NetworkGraphWidget(graph: _networkGraph!),
            ),
          ] else
            const Center(
              child: Padding(
                padding: EdgeInsets.all(40),
                child: Text(
                  'Enter a client ID to visualize their beneficiary network.',
                  style: TextStyle(color: AppTheme.textSecondary),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _legendDot(Color color, String label) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 10, height: 10,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 6),
        Text(label, style: const TextStyle(fontSize: 11, color: AppTheme.textSecondary)),
      ],
    );
  }
}
