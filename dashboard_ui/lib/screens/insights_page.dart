import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import '../theme/app_theme.dart';
import '../widgets/section_card.dart';
import '../widgets/stat_card.dart';

class InsightsPage extends StatefulWidget {
  const InsightsPage({super.key});

  @override
  State<InsightsPage> createState() => _InsightsPageState();
}

class _InsightsPageState extends State<InsightsPage> {
  static const _api = 'http://localhost:8080/api/v1/insights';

  bool _loading = true;
  String? _error;

  Map<String, dynamic> _segmentSummary = {};
  Map<String, dynamic> _railInsights = {};
  List<dynamic> _campaigns = [];
  Map<String, dynamic> _volume = {};
  List<dynamic> _migrations = [];

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  Future<void> _loadAll() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await Future.wait([
        http.get(Uri.parse('$_api/segments/summary')),
        http.get(Uri.parse('$_api/rails')),
        http.get(Uri.parse('$_api/campaigns')),
        http.get(Uri.parse('$_api/volume')),
        http.get(Uri.parse('$_api/rails/migration-opportunities')),
      ]);

      for (final r in results) {
        if (r.statusCode != 200) throw Exception('API error: ${r.statusCode}');
      }

      setState(() {
        _segmentSummary = jsonDecode(results[0].body);
        _railInsights = jsonDecode(results[1].body);
        _campaigns = jsonDecode(results[2].body);
        _volume = jsonDecode(results[3].body);
        _migrations = jsonDecode(results[4].body);
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = e.toString().replaceFirst('Exception: ', '');
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline, color: AppTheme.critical, size: 48),
            const SizedBox(height: 12),
            Text(_error!, style: TextStyle(color: AppTheme.critical)),
            const SizedBox(height: 12),
            ElevatedButton(onPressed: _loadAll, child: const Text('Retry')),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _loadAll,
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 1400),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildSegmentSection(),
                _buildRailSection(),
                _buildVolumeSection(),
                _buildCampaignSection(),
                if (_migrations.isNotEmpty) _buildMigrationSection(),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // ─── SEGMENTS ───────────────────────────────────────────────

  Widget _buildSegmentSection() {
    final segments = ['HIGH_VALUE', 'GROWING', 'STABLE', 'DECLINING', 'DORMANT', 'NEW'];
    final labels = ['High Value', 'Growing', 'Stable', 'Declining', 'Dormant', 'New'];
    final colors = [
      const Color(0xFFA78BFA), AppTheme.pass, AppTheme.accent,
      AppTheme.alert, AppTheme.critical, const Color(0xFF7A8A9A),
    ];
    final totalClients = (_segmentSummary['totalClients'] ?? 0) as num;

    return SectionCard(
      title: 'Client Segmentation',
      trailing: Text(
        '$totalClients clients',
        style: TextStyle(color: AppTheme.textSecondary, fontSize: 13),
      ),
      child: Column(
        children: [
          // Segment stat cards
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: List.generate(segments.length, (i) {
              final seg = _segmentSummary[segments[i]] as Map<String, dynamic>? ?? {};
              final count = (seg['count'] ?? 0) as num;
              return SizedBox(
                width: 200,
                child: StatCard(
                  label: labels[i],
                  value: count.toString(),
                  valueColor: colors[i],
                ),
              );
            }),
          ),
          const SizedBox(height: 20),
          // Segment bar visualization
          ClipRRect(
            borderRadius: BorderRadius.circular(6),
            child: SizedBox(
              height: 32,
              child: Row(
                children: List.generate(segments.length, (i) {
                  final seg = _segmentSummary[segments[i]] as Map<String, dynamic>? ?? {};
                  final count = (seg['count'] ?? 0) as num;
                  if (count == 0 || totalClients == 0) return const SizedBox();
                  final pct = count / totalClients;
                  return Expanded(
                    flex: (pct * 1000).round().clamp(1, 1000),
                    child: Tooltip(
                      message: '${labels[i]}: $count (${(pct * 100).toStringAsFixed(0)}%)',
                      child: Container(
                        color: colors[i],
                        alignment: Alignment.center,
                        child: pct > 0.08
                            ? Text(
                                labels[i],
                                style: const TextStyle(
                                  color: Colors.white, fontSize: 11, fontWeight: FontWeight.w600,
                                ),
                                overflow: TextOverflow.ellipsis,
                              )
                            : null,
                      ),
                    ),
                  );
                }),
              ),
            ),
          ),
          const SizedBox(height: 16),
          // Client list per segment
          ...segments.asMap().entries.where((e) {
            final seg = _segmentSummary[e.value] as Map<String, dynamic>? ?? {};
            return ((seg['count'] ?? 0) as num) > 0;
          }).map((e) {
            final seg = _segmentSummary[e.value] as Map<String, dynamic>? ?? {};
            final clients = (seg['clients'] as List<dynamic>?)?.cast<String>() ?? [];
            final avgAmt = (seg['avgEwmaAmount'] ?? 0) as num;
            return Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                children: [
                  Container(
                    width: 10, height: 10,
                    decoration: BoxDecoration(color: colors[e.key], shape: BoxShape.circle),
                  ),
                  const SizedBox(width: 8),
                  SizedBox(
                    width: 90,
                    child: Text(labels[e.key],
                        style: TextStyle(color: AppTheme.textPrimary, fontSize: 13, fontWeight: FontWeight.w600)),
                  ),
                  Expanded(
                    child: Text(
                      clients.join(', '),
                      style: TextStyle(color: AppTheme.textSecondary, fontSize: 12),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  Text(
                    'Avg: ${_formatCurrency(avgAmt.toDouble())}',
                    style: TextStyle(color: AppTheme.textSecondary, fontSize: 12),
                  ),
                ],
              ),
            );
          }),
        ],
      ),
    );
  }

  // ─── RAILS ──────────────────────────────────────────────────

  Widget _buildRailSection() {
    final rails = (_railInsights['rails'] as List<dynamic>?) ?? [];
    final totalTxns = (_railInsights['totalTransactions'] ?? 1) as num;

    return SectionCard(
      title: 'Payment Rail Distribution',
      trailing: Text(
        '${_formatNumber(totalTxns.toInt())} total transactions',
        style: TextStyle(color: AppTheme.textSecondary, fontSize: 13),
      ),
      child: Column(
        children: [
          ...rails.asMap().entries.map((entry) {
            final r = entry.value as Map<String, dynamic>;
            final rail = r['rail'] as String;
            final count = (r['transactionCount'] ?? 0) as num;
            final sharePct = (r['volumeSharePct'] ?? 0) as num;
            final avgAmt = (r['avgAmountPerTxn'] ?? 0) as num;
            final clients = (r['activeClients'] ?? 0) as num;
            final realTime = r['realTime'] == true;
            final color = AppTheme.typeColors[entry.key % AppTheme.typeColors.length];

            return Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      SizedBox(
                        width: 50,
                        child: Text(rail,
                            style: TextStyle(color: AppTheme.textPrimary, fontSize: 14, fontWeight: FontWeight.w700)),
                      ),
                      if (realTime) ...[
                        const SizedBox(width: 6),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                          decoration: BoxDecoration(
                            color: AppTheme.pass.withValues(alpha: 0.15),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text('REAL-TIME',
                              style: TextStyle(color: AppTheme.pass, fontSize: 9, fontWeight: FontWeight.w700)),
                        ),
                      ],
                      const Spacer(),
                      Text('${sharePct.toStringAsFixed(1)}%',
                          style: TextStyle(color: color, fontSize: 14, fontWeight: FontWeight.w700)),
                      const SizedBox(width: 16),
                      Text('${_formatNumber(count.toInt())} txns',
                          style: TextStyle(color: AppTheme.textSecondary, fontSize: 12)),
                      const SizedBox(width: 16),
                      Text('Avg ${_formatCurrency(avgAmt.toDouble())}',
                          style: TextStyle(color: AppTheme.textSecondary, fontSize: 12)),
                      const SizedBox(width: 16),
                      Text('$clients clients',
                          style: TextStyle(color: AppTheme.textSecondary, fontSize: 12)),
                    ],
                  ),
                  const SizedBox(height: 6),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      value: (sharePct / 100).clamp(0, 1).toDouble(),
                      backgroundColor: AppTheme.surface,
                      valueColor: AlwaysStoppedAnimation(color),
                      minHeight: 8,
                    ),
                  ),
                ],
              ),
            );
          }),
        ],
      ),
    );
  }

  // ─── VOLUME ─────────────────────────────────────────────────

  Widget _buildVolumeSection() {
    final hourlyTps = (_volume['hourlyTpsDistribution'] as Map<String, dynamic>?) ?? {};
    final dailyAmt = (_volume['dailyAmountDistribution'] as Map<String, dynamic>?) ?? {};
    final peakHour = _volume['peakTpsHour'] ?? '--';
    final peakDay = _volume['peakAmountDay'] ?? '--';
    final dailyVol = (_volume['systemEwmaDailyVolume'] ?? 0) as num;

    final maxTps = hourlyTps.values.fold<double>(0, (a, b) => (b as num).toDouble() > a ? (b as num).toDouble() : a);

    return SectionCard(
      title: 'Volume & Timing Intelligence',
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _chip('Peak Hour', '$peakHour:00', AppTheme.accent),
          const SizedBox(width: 8),
          _chip('Peak Day', peakDay.toString(), AppTheme.pass),
          const SizedBox(width: 8),
          _chip('Daily Volume', _formatCurrency(dailyVol.toDouble()), const Color(0xFFA78BFA)),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Hourly TPS Distribution (24h)',
              style: TextStyle(color: AppTheme.textSecondary, fontSize: 12, fontWeight: FontWeight.w600)),
          const SizedBox(height: 12),
          SizedBox(
            height: 120,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: List.generate(24, (h) {
                final slot = h.toString().padLeft(2, '0');
                final val = (hourlyTps[slot] ?? 0) as num;
                final pct = maxTps > 0 ? val / maxTps : 0.0;
                final isPeak = slot == peakHour;

                return Expanded(
                  child: Tooltip(
                    message: '$slot:00 — ${val.toStringAsFixed(1)} TPS',
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 1),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          Expanded(
                            child: Align(
                              alignment: Alignment.bottomCenter,
                              child: FractionallySizedBox(
                                heightFactor: pct.clamp(0.02, 1.0).toDouble(),
                                child: Container(
                                  decoration: BoxDecoration(
                                    color: isPeak ? AppTheme.accent : AppTheme.accent.withValues(alpha: 0.5),
                                    borderRadius: const BorderRadius.vertical(top: Radius.circular(2)),
                                  ),
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            h % 3 == 0 ? '$h' : '',
                            style: TextStyle(color: AppTheme.textSecondary, fontSize: 9),
                          ),
                        ],
                      ),
                    ),
                  ),
                );
              }),
            ),
          ),
          const SizedBox(height: 24),
          Text('Day-of-Week Amount Distribution',
              style: TextStyle(color: AppTheme.textSecondary, fontSize: 12, fontWeight: FontWeight.w600)),
          const SizedBox(height: 12),
          ...dailyAmt.entries.map((e) {
            final maxAmt = dailyAmt.values.fold<double>(
                0, (a, b) => (b as num).toDouble() > a ? (b as num).toDouble() : a);
            final val = (e.value as num).toDouble();
            final pct = maxAmt > 0 ? val / maxAmt : 0.0;
            final isWeekend = e.key == 'Sat' || e.key == 'Sun';

            return Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                children: [
                  SizedBox(
                    width: 36,
                    child: Text(e.key,
                        style: TextStyle(
                          color: isWeekend ? AppTheme.alert : AppTheme.textPrimary,
                          fontSize: 13, fontWeight: FontWeight.w600,
                        )),
                  ),
                  Expanded(
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: LinearProgressIndicator(
                        value: pct.clamp(0, 1),
                        backgroundColor: AppTheme.surface,
                        valueColor: AlwaysStoppedAnimation(
                            isWeekend ? AppTheme.alert.withValues(alpha: 0.7) : AppTheme.pass),
                        minHeight: 18,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  SizedBox(
                    width: 90,
                    child: Text(
                      _formatCurrency(val),
                      textAlign: TextAlign.right,
                      style: TextStyle(color: AppTheme.textSecondary, fontSize: 12),
                    ),
                  ),
                ],
              ),
            );
          }),
        ],
      ),
    );
  }

  // ─── CAMPAIGNS ──────────────────────────────────────────────

  Widget _buildCampaignSection() {
    final priorityColors = {
      'HIGH': AppTheme.critical,
      'MEDIUM': AppTheme.alert,
      'LOW': AppTheme.pass,
    };

    return SectionCard(
      title: 'Campaign Recommendations',
      trailing: Text('${_campaigns.length} campaigns',
          style: TextStyle(color: AppTheme.textSecondary, fontSize: 13)),
      child: Wrap(
        spacing: 16,
        runSpacing: 16,
        children: _campaigns.map((c) {
          final cam = c as Map<String, dynamic>;
          final priority = cam['priority'] ?? 'LOW';
          final color = priorityColors[priority] ?? AppTheme.textSecondary;
          final targets = (cam['targetClients'] as List<dynamic>?)?.cast<String>() ?? [];

          return Container(
            width: 420,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppTheme.surface,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: color.withValues(alpha: 0.3)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(cam['name'] ?? '',
                          style: TextStyle(
                              color: AppTheme.textPrimary, fontSize: 14, fontWeight: FontWeight.w600)),
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      decoration: BoxDecoration(
                        color: color.withValues(alpha: 0.15),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(priority,
                          style: TextStyle(color: color, fontSize: 10, fontWeight: FontWeight.w700)),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(cam['description'] ?? '',
                    style: TextStyle(color: AppTheme.textSecondary, fontSize: 12, height: 1.4)),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Icon(Icons.people_outline, size: 14, color: AppTheme.textSecondary),
                    const SizedBox(width: 4),
                    Text('${targets.length} target clients',
                        style: TextStyle(color: AppTheme.textSecondary, fontSize: 11)),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(targets.join(', '),
                          style: TextStyle(color: AppTheme.accent, fontSize: 11),
                          overflow: TextOverflow.ellipsis),
                    ),
                  ],
                ),
              ],
            ),
          );
        }).toList(),
      ),
    );
  }

  // ─── MIGRATION OPPORTUNITIES ────────────────────────────────

  Widget _buildMigrationSection() {
    return SectionCard(
      title: 'Rail Migration Opportunities',
      trailing: Text('${_migrations.length} opportunities',
          style: TextStyle(color: AppTheme.textSecondary, fontSize: 13)),
      child: Column(
        children: [
          // Header row
          Container(
            padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 12),
            decoration: BoxDecoration(
              color: AppTheme.surface,
              borderRadius: BorderRadius.circular(6),
            ),
            child: Row(
              children: [
                _tableHeader('Client', 100),
                _tableHeader('From', 60),
                _tableHeader('To', 60),
                Expanded(child: _tableHeader('Reason', 0)),
                _tableHeader('Txns', 60),
                _tableHeader('Avg Amt', 80),
                _tableHeader('Impact', 70),
              ],
            ),
          ),
          const SizedBox(height: 4),
          ..._migrations.take(15).map((m) {
            final mig = m as Map<String, dynamic>;
            return Container(
              padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 12),
              decoration: BoxDecoration(
                border: Border(bottom: BorderSide(color: AppTheme.cardBorder, width: 0.5)),
              ),
              child: Row(
                children: [
                  SizedBox(
                    width: 100,
                    child: Text(mig['clientId'] ?? '',
                        style: TextStyle(color: AppTheme.accent, fontSize: 12, fontWeight: FontWeight.w600)),
                  ),
                  SizedBox(
                    width: 60,
                    child: Text(mig['fromRail'] ?? '',
                        style: TextStyle(color: AppTheme.critical, fontSize: 12)),
                  ),
                  SizedBox(
                    width: 60,
                    child: Text(mig['toRail'] ?? '',
                        style: TextStyle(color: AppTheme.pass, fontSize: 12)),
                  ),
                  Expanded(
                    child: Text(mig['reason'] ?? '',
                        style: TextStyle(color: AppTheme.textSecondary, fontSize: 11),
                        overflow: TextOverflow.ellipsis, maxLines: 2),
                  ),
                  SizedBox(
                    width: 60,
                    child: Text(_formatNumber((mig['affectedTransactions'] ?? 0) as int),
                        textAlign: TextAlign.right,
                        style: TextStyle(color: AppTheme.textPrimary, fontSize: 12)),
                  ),
                  SizedBox(
                    width: 80,
                    child: Text(_formatCurrency((mig['avgAmount'] ?? 0).toDouble()),
                        textAlign: TextAlign.right,
                        style: TextStyle(color: AppTheme.textPrimary, fontSize: 12)),
                  ),
                  SizedBox(
                    width: 70,
                    child: Text(((mig['impactScore'] ?? 0) as num).toStringAsFixed(0),
                        textAlign: TextAlign.right,
                        style: TextStyle(color: AppTheme.alert, fontSize: 12, fontWeight: FontWeight.w700)),
                  ),
                ],
              ),
            );
          }),
        ],
      ),
    );
  }

  // ─── HELPERS ────────────────────────────────────────────────

  Widget _tableHeader(String text, double width) {
    final child = Text(text.toUpperCase(),
        style: TextStyle(color: AppTheme.textSecondary, fontSize: 10, fontWeight: FontWeight.w700, letterSpacing: 0.5));
    return width > 0 ? SizedBox(width: width, child: child) : child;
  }

  Widget _chip(String label, String value, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text('$label: ', style: TextStyle(color: AppTheme.textSecondary, fontSize: 11)),
          Text(value, style: TextStyle(color: color, fontSize: 11, fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }

  String _formatCurrency(double amount) {
    if (amount >= 10000000) return '${(amount / 10000000).toStringAsFixed(1)}Cr';
    if (amount >= 100000) return '${(amount / 100000).toStringAsFixed(1)}L';
    if (amount >= 1000) return '${(amount / 1000).toStringAsFixed(1)}K';
    return amount.toStringAsFixed(0);
  }

  String _formatNumber(int n) {
    if (n >= 1000000) return '${(n / 1000000).toStringAsFixed(1)}M';
    if (n >= 1000) return '${(n / 1000).toStringAsFixed(1)}K';
    return n.toString();
  }
}
