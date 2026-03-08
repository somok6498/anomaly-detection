import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';
import '../models/models.dart';
import '../services/api_service.dart';
import '../theme/app_theme.dart';
import '../widgets/section_card.dart';
import '../widgets/stat_card.dart';
import '../widgets/network_graph_widget.dart';
import '../utils/toast_helper.dart';
import '../widgets/time_range_selector.dart';

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
  bool _loadingSilence = true;
  bool _checkingSilence = false;
  bool _loadingAiFeedback = true;
  String? _rulesError;
  String? _networkError;
  String? _silenceError;
  String? _aiFeedbackError;

  List<RulePerformance> _ruleStats = [];
  NetworkGraph? _networkGraph;
  GraphStatus? _graphStatus;
  String? _networkClientId;
  SilenceStatus? _silenceStatus;
  Map<String, dynamic>? _aiFeedbackStats;

  // Time range filter
  TimeRangePreset _timePreset = TimeRangePreset.min15;
  int? _fromDate;
  int? _toDate;

  // Expose rule stats for export
  List<RulePerformance> get ruleStats => _ruleStats;

  @override
  void initState() {
    super.initState();
    final defaultRange = TimeRange.fromPreset(TimeRangePreset.min15);
    _fromDate = defaultRange.fromEpochMs;
    _toDate = defaultRange.toEpochMs;
    _loadRulePerformance();
    _loadSilenceStatus();
    _loadAiFeedbackStats();
  }

  Future<void> _loadRulePerformance() async {
    // Recompute relative time range for non-custom presets
    if (_timePreset != TimeRangePreset.custom) {
      final range = TimeRange.fromPreset(_timePreset);
      _fromDate = range.fromEpochMs;
      _toDate = range.toEpochMs;
    }

    setState(() {
      _loadingRules = true;
      _rulesError = null;
    });
    try {
      final results = await Future.wait([
        _api.getRulePerformance(fromDate: _fromDate, toDate: _toDate),
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
        final msg = e.toString().replaceFirst('Exception: ', '');
        setState(() {
          _rulesError = msg;
          _loadingRules = false;
        });
        ToastHelper.showError(context, 'Failed to load analytics: $msg');
      }
    }
  }

  Future<void> _loadNetwork(String rawClientId) async {
    final clientId = rawClientId.toUpperCase();
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
        final msg = e.toString().replaceFirst('Exception: ', '');
        setState(() {
          _networkError = msg;
          _loadingNetwork = false;
        });
        ToastHelper.showError(context, 'Failed to load network: $msg');
      }
    }
  }

  Future<void> _loadSilenceStatus() async {
    setState(() {
      _loadingSilence = true;
      _silenceError = null;
    });
    try {
      final status = await _api.getSilenceStatus();
      if (mounted) {
        setState(() {
          _silenceStatus = status;
          _loadingSilence = false;
        });
      }
    } catch (e) {
      if (mounted) {
        final msg = e.toString().replaceFirst('Exception: ', '');
        setState(() {
          _silenceError = msg;
          _loadingSilence = false;
        });
      }
    }
  }

  Future<void> _loadAiFeedbackStats() async {
    setState(() {
      _loadingAiFeedback = true;
      _aiFeedbackError = null;
    });
    try {
      final stats = await _api.getAiFeedbackStats();
      if (mounted) {
        setState(() {
          _aiFeedbackStats = stats;
          _loadingAiFeedback = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _aiFeedbackError = e.toString().replaceFirst('Exception: ', '');
          _loadingAiFeedback = false;
        });
      }
    }
  }

  Future<void> _triggerSilenceCheck() async {
    setState(() => _checkingSilence = true);
    try {
      await _api.triggerSilenceCheck();
      await _loadSilenceStatus();
    } catch (e) {
      if (mounted) {
        ToastHelper.showError(context, 'Silence check failed: ${e.toString().replaceFirst('Exception: ', '')}');
      }
    } finally {
      if (mounted) {
        setState(() => _checkingSilence = false);
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
              Container(
                margin: const EdgeInsets.only(bottom: 16),
                child: TimeRangeSelector(
                  initialPreset: _timePreset,
                  onChanged: (range) {
                    setState(() {
                      _timePreset = range.preset;
                      _fromDate = range.fromEpochMs;
                      _toDate = range.toEpochMs;
                    });
                    _loadRulePerformance();
                  },
                ),
              ),
              _buildRulePerformanceSection(),
              const SizedBox(height: 8),
              _buildAiFeedbackSection(),
              const SizedBox(height: 8),
              _buildNetworkSection(),
              const SizedBox(height: 8),
              _buildSilenceSection(),
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
              icon: Icon(Icons.download, color: AppTheme.textSecondary, size: 20),
              color: AppTheme.surface,
              onSelected: (v) {
                if (v == 'csv') widget.onExportRulesCsv?.call();
                if (v == 'pdf') widget.onExportRulesPdf?.call();
              },
              itemBuilder: (_) => [
                PopupMenuItem(value: 'csv', child: Text('Export CSV', style: TextStyle(color: AppTheme.textPrimary, fontSize: 13))),
                PopupMenuItem(value: 'pdf', child: Text('Export PDF', style: TextStyle(color: AppTheme.textPrimary, fontSize: 13))),
              ],
            )
          : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Precision bar chart
          if (rulesWithTriggers.isNotEmpty) ...[
            Text('Precision by Rule (feedback-based)',
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
                        final desc = rule.description.isNotEmpty ? '\n${rule.description}' : '';
                        return BarTooltipItem(
                          '${rule.ruleName}$desc\nPrecision: ${(rule.precision * 100).toStringAsFixed(1)}%\nTP: ${rule.tpCount} FP: ${rule.fpCount}',
                          TextStyle(color: AppTheme.textPrimary, fontSize: 11),
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
                          style: TextStyle(fontSize: 10, color: AppTheme.textSecondary),
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
                                style: TextStyle(fontSize: 9, color: AppTheme.textSecondary),
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
          Text('All Rules',
              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppTheme.textSecondary)),
          const SizedBox(height: 8),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: DataTable(
              headingRowColor: WidgetStateProperty.all(Colors.transparent),
              dataRowColor: WidgetStateProperty.all(Colors.transparent),
              columns: [
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
                  DataCell(Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(r.ruleName, style: TextStyle(fontSize: 12, color: AppTheme.textPrimary)),
                      if (r.description.isNotEmpty) ...[
                        const SizedBox(width: 4),
                        Tooltip(
                          message: r.description,
                          preferBelow: false,
                          child: Icon(Icons.info_outline, size: 14, color: AppTheme.textSecondary),
                        ),
                      ],
                    ],
                  )),
                  DataCell(Text(r.ruleType, style: TextStyle(fontSize: 11, color: AppTheme.textSecondary))),
                  DataCell(Text(r.currentWeight.toStringAsFixed(1), style: TextStyle(fontSize: 12, color: AppTheme.textPrimary))),
                  DataCell(Text(r.triggerCount.toString(), style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppTheme.textPrimary))),
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

  Widget _buildAiFeedbackSection() {
    if (_loadingAiFeedback) {
      return const SectionCard(
        title: 'AI Explanation Feedback',
        child: Center(
          child: Padding(
            padding: EdgeInsets.all(40),
            child: CircularProgressIndicator(color: AppTheme.accent),
          ),
        ),
      );
    }

    if (_aiFeedbackError != null) {
      return SectionCard(
        title: 'AI Explanation Feedback',
        child: Text(_aiFeedbackError!, style: const TextStyle(color: AppTheme.critical)),
      );
    }

    final stats = _aiFeedbackStats;
    final helpful = (stats?['helpful'] ?? 0) as int;
    final notHelpful = (stats?['notHelpful'] ?? 0) as int;
    final total = (stats?['total'] ?? 0) as int;
    final helpfulPct = (stats?['helpfulPct'] ?? 0.0) as double;

    if (total == 0) {
      return SectionCard(
        title: 'AI Explanation Feedback',
        trailing: IconButton(
          onPressed: _loadAiFeedbackStats,
          icon: Icon(Icons.refresh, color: AppTheme.textSecondary, size: 20),
          tooltip: 'Refresh',
        ),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 24),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.info_outline, color: AppTheme.textSecondary, size: 20),
              const SizedBox(width: 8),
              Text('No AI feedback submitted yet. Rate explanations in the review queue.',
                  style: TextStyle(fontSize: 14, color: AppTheme.textSecondary)),
            ],
          ),
        ),
      );
    }

    final barWidth = 200.0;

    return SectionCard(
      title: 'AI Explanation Feedback',
      trailing: IconButton(
        onPressed: _loadAiFeedbackStats,
        icon: Icon(Icons.refresh, color: AppTheme.textSecondary, size: 20),
        tooltip: 'Refresh',
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Stat cards row
          Wrap(
            spacing: 14,
            runSpacing: 8,
            children: [
              SizedBox(width: 140, child: StatCard(label: 'Total Ratings', value: total.toString())),
              SizedBox(width: 140, child: StatCard(label: 'Helpful', value: helpful.toString(), valueColor: AppTheme.pass)),
              SizedBox(width: 140, child: StatCard(label: 'Not Helpful', value: notHelpful.toString(), valueColor: AppTheme.critical)),
              SizedBox(width: 140, child: StatCard(
                label: 'Helpful Rate',
                value: '${helpfulPct.toStringAsFixed(1)}%',
                valueColor: helpfulPct >= 70 ? AppTheme.pass : helpfulPct >= 40 ? AppTheme.alert : AppTheme.critical,
              )),
            ],
          ),
          const SizedBox(height: 20),
          // Visual bar
          Text('Feedback Distribution', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppTheme.textSecondary)),
          const SizedBox(height: 8),
          Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: SizedBox(
                  width: barWidth,
                  height: 24,
                  child: Row(
                    children: [
                      if (helpful > 0)
                        Container(
                          width: barWidth * (helpful / total),
                          color: AppTheme.pass,
                          alignment: Alignment.center,
                          child: helpful / total > 0.15
                              ? Text('$helpful', style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: Colors.white))
                              : null,
                        ),
                      if (notHelpful > 0)
                        Container(
                          width: barWidth * (notHelpful / total),
                          color: AppTheme.critical,
                          alignment: Alignment.center,
                          child: notHelpful / total > 0.15
                              ? Text('$notHelpful', style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: Colors.white))
                              : null,
                        ),
                    ],
                  ),
                ),
              ),
              const SizedBox(width: 12),
              _legendDot(AppTheme.pass, 'Helpful'),
              const SizedBox(width: 12),
              _legendDot(AppTheme.critical, 'Not Helpful'),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            'Feedback is used to improve future AI explanations. Not-helpful ratings train the model to avoid similar patterns.',
            style: TextStyle(fontSize: 11, color: AppTheme.textSecondary, fontStyle: FontStyle.italic),
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
                  style: TextStyle(color: AppTheme.textPrimary, fontSize: 14),
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
            // Legend + stats row
            Row(
              children: [
                _legendDot(AppTheme.pass, 'Center Client'),
                const SizedBox(width: 16),
                _legendDot(AppTheme.accent, 'Other Client'),
                const SizedBox(width: 16),
                _legendDot(AppTheme.alert, 'Beneficiary'),
                const SizedBox(width: 16),
                _legendDot(AppTheme.critical, 'High Fan-in (badge)'),
                const Spacer(),
                Text(
                  '${_networkGraph!.nodes.length} nodes, ${_networkGraph!.edges.length} edges',
                  style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Click a node to highlight its connections. Click again to deselect.',
              style: TextStyle(fontSize: 11, color: AppTheme.textSecondary),
            ),
            const SizedBox(height: 12),
            Container(
              decoration: BoxDecoration(
                color: AppTheme.cardBg,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: AppTheme.cardBorder),
              ),
              clipBehavior: Clip.antiAlias,
              child: NetworkGraphWidget(graph: _networkGraph!),
            ),
          ] else
            Center(
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

  Widget _buildSilenceSection() {
    return SectionCard(
      title: 'Silence Detection Monitor',
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_checkingSilence)
            SizedBox(width: 16, height: 16, child: CircularProgressIndicator(color: AppTheme.accent, strokeWidth: 2))
          else
            IconButton(
              onPressed: _triggerSilenceCheck,
              icon: Icon(Icons.refresh, color: AppTheme.textSecondary, size: 20),
              tooltip: 'Run silence check now',
            ),
        ],
      ),
      child: _loadingSilence
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(40),
                child: CircularProgressIndicator(color: AppTheme.accent),
              ),
            )
          : _silenceError != null
              ? Text(_silenceError!, style: const TextStyle(color: AppTheme.critical))
              : _buildSilenceContent(),
    );
  }

  Widget _buildSilenceContent() {
    final status = _silenceStatus;
    if (status == null || status.silentClientCount == 0) {
      return Container(
        padding: const EdgeInsets.symmetric(vertical: 24),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.check_circle, color: AppTheme.pass, size: 20),
            const SizedBox(width: 8),
            Text('All clients active — no silence alerts',
                style: TextStyle(fontSize: 14, color: AppTheme.pass, fontWeight: FontWeight.w500)),
          ],
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Summary stat cards
        Padding(
          padding: const EdgeInsets.only(bottom: 16),
          child: Wrap(
            spacing: 14,
            runSpacing: 8,
            children: [
              SizedBox(width: 160, child: StatCard(
                label: 'Silent Clients',
                value: status.silentClientCount.toString(),
                valueColor: status.silentClientCount > 0 ? AppTheme.critical : AppTheme.pass,
              )),
            ],
          ),
        ),
        // Client table
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: DataTable(
            headingRowColor: WidgetStateProperty.all(Colors.transparent),
            dataRowColor: WidgetStateProperty.all(Colors.transparent),
            columns: [
              DataColumn(label: Text('CLIENT', style: _colStyle)),
              DataColumn(label: Text('SILENT FOR', style: _colStyle)),
              DataColumn(label: Text('LAST TXN', style: _colStyle)),
              DataColumn(label: Text('EXPECTED GAP', style: _colStyle)),
              DataColumn(label: Text('RATIO', style: _colStyle)),
              DataColumn(label: Text('EWMA TPS', style: _colStyle)),
              DataColumn(label: Text('AVG AMOUNT', style: _colStyle)),
              DataColumn(label: Text('TOTAL TXNS', style: _colStyle)),
            ],
            rows: status.clients.map((c) {
              Color ratioColor = AppTheme.textPrimary;
              if (c.silenceRatio >= 5) {
                ratioColor = AppTheme.critical;
              } else if (c.silenceRatio >= 3) {
                ratioColor = AppTheme.alert;
              }

              return DataRow(cells: [
                DataCell(Text(c.clientId, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppTheme.accent))),
                DataCell(Text(_formatDuration(c.silentForMinutes), style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppTheme.critical))),
                DataCell(Text(
                  c.lastTransactionAt > 0 ? _formatTimeAgo(c.lastTransactionAt) : '-',
                  style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
                )),
                DataCell(Text(
                  c.expectedGapMinutes > 0 ? '${c.expectedGapMinutes.toStringAsFixed(1)} min' : '-',
                  style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
                )),
                DataCell(Text(
                  c.silenceRatio > 0 ? '${c.silenceRatio.toStringAsFixed(1)}x' : '-',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: ratioColor),
                )),
                DataCell(Text(
                  c.ewmaHourlyTps > 0 ? '${c.ewmaHourlyTps.toStringAsFixed(1)}/hr' : '-',
                  style: TextStyle(fontSize: 12, color: AppTheme.textPrimary),
                )),
                DataCell(Text(
                  c.ewmaAmount > 0 ? '₹${c.ewmaAmount.toStringAsFixed(0)}' : '-',
                  style: TextStyle(fontSize: 12, color: AppTheme.textPrimary),
                )),
                DataCell(Text(
                  c.totalTxnCount > 0 ? c.totalTxnCount.toString() : '-',
                  style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
                )),
              ]);
            }).toList(),
          ),
        ),
      ],
    );
  }

  TextStyle get _colStyle => TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppTheme.textSecondary);

  String _formatDuration(int minutes) {
    if (minutes < 60) return '${minutes}m';
    final hours = minutes ~/ 60;
    final mins = minutes % 60;
    if (hours < 24) return '${hours}h ${mins}m';
    final days = hours ~/ 24;
    final remHours = hours % 24;
    return '${days}d ${remHours}h';
  }

  String _formatTimeAgo(int epochMs) {
    final diff = DateTime.now().millisecondsSinceEpoch - epochMs;
    final minutes = diff ~/ 60000;
    if (minutes < 60) return '${minutes}m ago';
    final hours = minutes ~/ 60;
    if (hours < 24) return '${hours}h ${minutes % 60}m ago';
    final days = hours ~/ 24;
    return '${days}d ${hours % 24}h ago';
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
        Text(label, style: TextStyle(fontSize: 11, color: AppTheme.textSecondary)),
      ],
    );
  }
}
