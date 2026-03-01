import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../services/api_service.dart';
import '../models/models.dart';
import '../widgets/section_card.dart';
import '../widgets/stat_card.dart';
import '../utils/toast_helper.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final _api = ApiService();
  bool _loading = true;

  // Data
  List<AnomalyRuleModel> _rules = [];
  ThresholdConfig? _thresholds;
  FeedbackConfigModel? _feedback;
  List<String> _txnTypes = [];
  AerospikeInfo? _aerospike;

  // Dirty tracking for rules
  final Set<String> _dirtyRuleIds = {};

  // Threshold controllers
  final _alertCtrl = TextEditingController();
  final _blockCtrl = TextEditingController();
  final _ewmaCtrl = TextEditingController();
  final _minTxnsCtrl = TextEditingController();

  // Feedback controllers
  final _timeoutCtrl = TextEditingController();
  final _tuningHrsCtrl = TextEditingController();
  final _minSamplesCtrl = TextEditingController();
  final _floorCtrl = TextEditingController();
  final _ceilingCtrl = TextEditingController();
  final _maxAdjCtrl = TextEditingController();

  // Txn type add
  final _newTypeCtrl = TextEditingController();

  // Rule text controllers keyed by ruleId
  final Map<String, TextEditingController> _varianceControllers = {};
  final Map<String, TextEditingController> _weightControllers = {};

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  @override
  void dispose() {
    _alertCtrl.dispose();
    _blockCtrl.dispose();
    _ewmaCtrl.dispose();
    _minTxnsCtrl.dispose();
    _timeoutCtrl.dispose();
    _tuningHrsCtrl.dispose();
    _minSamplesCtrl.dispose();
    _floorCtrl.dispose();
    _ceilingCtrl.dispose();
    _maxAdjCtrl.dispose();
    _newTypeCtrl.dispose();
    for (final c in _varianceControllers.values) { c.dispose(); }
    for (final c in _weightControllers.values) { c.dispose(); }
    super.dispose();
  }

  Future<void> _loadAll() async {
    setState(() => _loading = true);
    try {
      final results = await Future.wait([
        _api.getRules(),
        _api.getThresholds(),
        _api.getFeedbackConfig(),
        _api.getTransactionTypes(),
        _api.getAerospikeInfo(),
      ]);
      _rules = results[0] as List<AnomalyRuleModel>;
      _thresholds = results[1] as ThresholdConfig;
      _feedback = results[2] as FeedbackConfigModel;
      _txnTypes = results[3] as List<String>;
      _aerospike = results[4] as AerospikeInfo;

      // Init threshold controllers
      _alertCtrl.text = _thresholds!.alertThreshold.toString();
      _blockCtrl.text = _thresholds!.blockThreshold.toString();
      _ewmaCtrl.text = _thresholds!.ewmaAlpha.toString();
      _minTxnsCtrl.text = _thresholds!.minProfileTxns.toString();

      // Init feedback controllers
      _timeoutCtrl.text = _feedback!.autoAcceptTimeoutMs.toString();
      _tuningHrsCtrl.text = _feedback!.tuningIntervalHours.toString();
      _minSamplesCtrl.text = _feedback!.minSamplesForTuning.toString();
      _floorCtrl.text = _feedback!.weightFloor.toString();
      _ceilingCtrl.text = _feedback!.weightCeiling.toString();
      _maxAdjCtrl.text = _feedback!.maxAdjustmentPct.toString();

      // Init rule controllers
      _varianceControllers.clear();
      _weightControllers.clear();
      for (final r in _rules) {
        _varianceControllers[r.ruleId] = TextEditingController(text: r.variancePct.toString());
        _weightControllers[r.ruleId] = TextEditingController(text: r.riskWeight.toString());
      }
      _dirtyRuleIds.clear();
    } catch (e) {
      if (mounted) ToastHelper.showError(context, 'Failed to load config: $e');
    }
    if (mounted) setState(() => _loading = false);
  }

  // ── Save handlers ──

  Future<void> _saveRules() async {
    int saved = 0;
    for (final ruleId in _dirtyRuleIds.toList()) {
      final rule = _rules.firstWhere((r) => r.ruleId == ruleId);
      try {
        await _api.updateRule(ruleId, rule);
        saved++;
      } catch (e) {
        if (mounted) ToastHelper.showError(context, 'Failed to save $ruleId: $e');
        return;
      }
    }
    _dirtyRuleIds.clear();
    if (mounted) {
      setState(() {});
      ToastHelper.showSuccess(context, '$saved rule(s) saved');
    }
  }

  Future<void> _saveThresholds() async {
    final alert = double.tryParse(_alertCtrl.text);
    final block = double.tryParse(_blockCtrl.text);
    final ewma = double.tryParse(_ewmaCtrl.text);
    final minTxns = int.tryParse(_minTxnsCtrl.text);
    if (alert == null || block == null || ewma == null || minTxns == null) {
      ToastHelper.showError(context, 'Invalid number format');
      return;
    }
    try {
      final updated = await _api.updateThresholds(ThresholdConfig(
        alertThreshold: alert, blockThreshold: block,
        ewmaAlpha: ewma, minProfileTxns: minTxns,
      ));
      _thresholds = updated;
      if (mounted) ToastHelper.showSuccess(context, 'Thresholds saved');
    } catch (e) {
      if (mounted) ToastHelper.showError(context, '$e');
    }
  }

  Future<void> _saveFeedback() async {
    final timeout = int.tryParse(_timeoutCtrl.text);
    final hrs = int.tryParse(_tuningHrsCtrl.text);
    final samples = int.tryParse(_minSamplesCtrl.text);
    final floor = double.tryParse(_floorCtrl.text);
    final ceiling = double.tryParse(_ceilingCtrl.text);
    final adj = double.tryParse(_maxAdjCtrl.text);
    if (timeout == null || hrs == null || samples == null ||
        floor == null || ceiling == null || adj == null) {
      ToastHelper.showError(context, 'Invalid number format');
      return;
    }
    try {
      final updated = await _api.updateFeedbackConfig(FeedbackConfigModel(
        autoAcceptTimeoutMs: timeout, tuningIntervalHours: hrs,
        minSamplesForTuning: samples, weightFloor: floor,
        weightCeiling: ceiling, maxAdjustmentPct: adj,
      ));
      _feedback = updated;
      if (mounted) ToastHelper.showSuccess(context, 'Feedback config saved');
    } catch (e) {
      if (mounted) ToastHelper.showError(context, '$e');
    }
  }

  Future<void> _saveTxnTypes() async {
    try {
      final updated = await _api.updateTransactionTypes(_txnTypes);
      setState(() => _txnTypes = updated);
      if (mounted) ToastHelper.showSuccess(context, 'Transaction types saved');
    } catch (e) {
      if (mounted) ToastHelper.showError(context, '$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1400),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildWarningBanner(),
              _buildRulesSection(),
              _buildThresholdsSection(),
              _buildFeedbackSection(),
              _buildTxnTypesSection(),
              _buildAerospikeSection(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildWarningBanner() {
    return Container(
      padding: const EdgeInsets.all(12),
      margin: const EdgeInsets.only(bottom: 20),
      decoration: BoxDecoration(
        color: AppTheme.alert.withValues(alpha: 0.1),
        border: Border.all(color: AppTheme.alert),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          const Icon(Icons.info_outline, color: AppTheme.alert),
          const SizedBox(width: 12),
          Expanded(child: Text(
            'Threshold, feedback, and transaction type changes apply immediately but reset '
            'to application.yml defaults on restart. Rule changes are persisted to Aerospike.',
            style: TextStyle(color: AppTheme.alert, fontSize: 13),
          )),
        ],
      ),
    );
  }

  // ── Rules Section ──

  Widget _buildRulesSection() {
    return SectionCard(
      title: 'Anomaly Rules',
      trailing: _dirtyRuleIds.isNotEmpty
          ? ElevatedButton.icon(
              onPressed: _saveRules,
              icon: const Icon(Icons.save, size: 16),
              label: Text('Save ${_dirtyRuleIds.length} Modified'),
              style: ElevatedButton.styleFrom(
                backgroundColor: AppTheme.accent,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
              ),
            )
          : null,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: DataTable(
          headingRowColor: WidgetStateProperty.all(Colors.transparent),
          dataRowColor: WidgetStateProperty.all(Colors.transparent),
          columnSpacing: 20,
          columns: [
            DataColumn(label: Text('ENABLED', style: _headerStyle())),
            DataColumn(label: Text('RULE NAME', style: _headerStyle())),
            DataColumn(label: Text('TYPE', style: _headerStyle())),
            DataColumn(label: Text('VARIANCE %', style: _headerStyle())),
            DataColumn(label: Text('WEIGHT', style: _headerStyle())),
            DataColumn(label: Text('PARAMS', style: _headerStyle())),
          ],
          rows: _rules.map((rule) {
            final isDirty = _dirtyRuleIds.contains(rule.ruleId);
            return DataRow(
              color: isDirty ? WidgetStateProperty.all(AppTheme.accent.withValues(alpha: 0.06)) : null,
              cells: [
                DataCell(Switch(
                  value: rule.enabled,
                  activeTrackColor: AppTheme.accent,
                  onChanged: (v) {
                    setState(() {
                      rule.enabled = v;
                      _dirtyRuleIds.add(rule.ruleId);
                    });
                  },
                )),
                DataCell(Text(rule.name, style: TextStyle(color: AppTheme.textPrimary, fontSize: 12, fontWeight: FontWeight.w500))),
                DataCell(Text(rule.ruleType, style: TextStyle(color: AppTheme.textSecondary, fontSize: 11))),
                DataCell(SizedBox(
                  width: 80,
                  child: TextField(
                    controller: _varianceControllers[rule.ruleId],
                    style: TextStyle(color: AppTheme.textPrimary, fontSize: 12),
                    decoration: _compactInputDecor(),
                    onChanged: (v) {
                      final val = double.tryParse(v);
                      if (val != null) {
                        rule.variancePct = val;
                        setState(() => _dirtyRuleIds.add(rule.ruleId));
                      }
                    },
                  ),
                )),
                DataCell(SizedBox(
                  width: 60,
                  child: TextField(
                    controller: _weightControllers[rule.ruleId],
                    style: TextStyle(color: AppTheme.textPrimary, fontSize: 12),
                    decoration: _compactInputDecor(),
                    onChanged: (v) {
                      final val = double.tryParse(v);
                      if (val != null) {
                        rule.riskWeight = val;
                        setState(() => _dirtyRuleIds.add(rule.ruleId));
                      }
                    },
                  ),
                )),
                DataCell(IconButton(
                  icon: Icon(Icons.tune, size: 18, color: rule.params.isNotEmpty ? AppTheme.accent : AppTheme.textSecondary),
                  tooltip: '${rule.params.length} parameter(s)',
                  onPressed: () => _showParamsDialog(rule),
                )),
              ],
            );
          }).toList(),
        ),
      ),
    );
  }

  void _showParamsDialog(AnomalyRuleModel rule) {
    final entries = Map<String, String>.from(rule.params);
    final keyCtrl = TextEditingController();
    final valCtrl = TextEditingController();

    showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => AlertDialog(
          backgroundColor: AppTheme.cardBg,
          title: Text('Parameters — ${rule.name}',
              style: TextStyle(color: AppTheme.textPrimary, fontSize: 16)),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                ...entries.entries.map((e) => Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Row(
                    children: [
                      SizedBox(width: 140, child: Text(e.key,
                          style: TextStyle(color: AppTheme.textSecondary, fontSize: 12, fontWeight: FontWeight.w600))),
                      const SizedBox(width: 8),
                      Expanded(child: TextField(
                        controller: TextEditingController(text: e.value),
                        style: TextStyle(color: AppTheme.textPrimary, fontSize: 12),
                        decoration: _compactInputDecor(),
                        onChanged: (v) => entries[e.key] = v,
                      )),
                      IconButton(
                        icon: Icon(Icons.close, size: 16, color: AppTheme.critical),
                        onPressed: () => setDialogState(() => entries.remove(e.key)),
                      ),
                    ],
                  ),
                )),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(child: TextField(
                      controller: keyCtrl,
                      style: TextStyle(color: AppTheme.textPrimary, fontSize: 12),
                      decoration: _compactInputDecor().copyWith(hintText: 'Key'),
                    )),
                    const SizedBox(width: 8),
                    Expanded(child: TextField(
                      controller: valCtrl,
                      style: TextStyle(color: AppTheme.textPrimary, fontSize: 12),
                      decoration: _compactInputDecor().copyWith(hintText: 'Value'),
                    )),
                    IconButton(
                      icon: const Icon(Icons.add, size: 18, color: AppTheme.accent),
                      onPressed: () {
                        if (keyCtrl.text.isNotEmpty) {
                          setDialogState(() => entries[keyCtrl.text] = valCtrl.text);
                          keyCtrl.clear();
                          valCtrl.clear();
                        }
                      },
                    ),
                  ],
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: Text('Cancel', style: TextStyle(color: AppTheme.textSecondary)),
            ),
            ElevatedButton(
              onPressed: () {
                setState(() {
                  rule.params = Map<String, String>.from(entries);
                  _dirtyRuleIds.add(rule.ruleId);
                });
                Navigator.pop(ctx);
              },
              style: ElevatedButton.styleFrom(
                backgroundColor: AppTheme.accent,
                foregroundColor: Colors.white,
              ),
              child: const Text('Apply'),
            ),
          ],
        ),
      ),
    );
  }

  // ── Thresholds Section ──

  Widget _buildThresholdsSection() {
    return SectionCard(
      title: 'Global Thresholds',
      trailing: _buildSaveBtn(_saveThresholds),
      child: Wrap(
        spacing: 24,
        runSpacing: 16,
        children: [
          _buildField('Alert Threshold', _alertCtrl, 120),
          _buildField('Block Threshold', _blockCtrl, 120),
          _buildField('EWMA Alpha', _ewmaCtrl, 100),
          _buildField('Min Profile Txns', _minTxnsCtrl, 120),
        ],
      ),
    );
  }

  // ── Feedback Section ──

  Widget _buildFeedbackSection() {
    return SectionCard(
      title: 'Feedback & Auto-Tuning',
      trailing: _buildSaveBtn(_saveFeedback),
      child: Wrap(
        spacing: 24,
        runSpacing: 16,
        children: [
          _buildField('Auto-Accept Timeout (ms)', _timeoutCtrl, 160),
          _buildField('Tuning Interval (hrs)', _tuningHrsCtrl, 140),
          _buildField('Min Samples', _minSamplesCtrl, 100),
          _buildField('Weight Floor', _floorCtrl, 100),
          _buildField('Weight Ceiling', _ceilingCtrl, 100),
          _buildField('Max Adjustment %', _maxAdjCtrl, 120),
        ],
      ),
    );
  }

  // ── Transaction Types ──

  Widget _buildTxnTypesSection() {
    return SectionCard(
      title: 'Transaction Types',
      trailing: _buildSaveBtn(_saveTxnTypes),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _txnTypes.map((type) => Chip(
              label: Text(type, style: TextStyle(color: AppTheme.textPrimary, fontSize: 12)),
              backgroundColor: AppTheme.surface,
              side: BorderSide(color: AppTheme.cardBorder),
              deleteIcon: Icon(Icons.close, size: 16, color: AppTheme.textSecondary),
              onDeleted: () => setState(() => _txnTypes.remove(type)),
            )).toList(),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              SizedBox(
                width: 160,
                child: TextField(
                  controller: _newTypeCtrl,
                  style: TextStyle(color: AppTheme.textPrimary, fontSize: 13),
                  decoration: InputDecoration(
                    hintText: 'e.g. CBDC',
                    contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                  ),
                  onSubmitted: (_) => _addType(),
                ),
              ),
              const SizedBox(width: 8),
              ElevatedButton(
                onPressed: _addType,
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppTheme.surface,
                  foregroundColor: AppTheme.textPrimary,
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
                ),
                child: const Text('Add', style: TextStyle(fontSize: 12)),
              ),
            ],
          ),
        ],
      ),
    );
  }

  void _addType() {
    final type = _newTypeCtrl.text.trim().toUpperCase();
    if (type.isEmpty) return;
    if (_txnTypes.contains(type)) {
      ToastHelper.showError(context, '$type already exists');
      return;
    }
    setState(() => _txnTypes.add(type));
    _newTypeCtrl.clear();
  }

  // ── Aerospike ──

  Widget _buildAerospikeSection() {
    if (_aerospike == null) return const SizedBox();
    return SectionCard(
      title: 'Aerospike Connection (Read-Only)',
      child: Row(
        children: [
          Expanded(child: StatCard(label: 'Host', value: _aerospike!.host)),
          const SizedBox(width: 12),
          Expanded(child: StatCard(label: 'Port', value: _aerospike!.port.toString())),
          const SizedBox(width: 12),
          Expanded(child: StatCard(label: 'Namespace', value: _aerospike!.namespace)),
        ],
      ),
    );
  }

  // ── Helpers ──

  TextStyle _headerStyle() => TextStyle(
    fontSize: 10, fontWeight: FontWeight.w700, color: AppTheme.textSecondary,
  );

  InputDecoration _compactInputDecor() => InputDecoration(
    isDense: true,
    contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
    border: OutlineInputBorder(borderRadius: BorderRadius.circular(4)),
  );

  Widget _buildField(String label, TextEditingController ctrl, double width) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: TextStyle(color: AppTheme.textSecondary, fontSize: 11, fontWeight: FontWeight.w600)),
        const SizedBox(height: 4),
        SizedBox(
          width: width,
          child: TextField(
            controller: ctrl,
            style: TextStyle(color: AppTheme.textPrimary, fontSize: 13),
            decoration: InputDecoration(
              isDense: true,
              contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSaveBtn(VoidCallback onPressed) {
    return ElevatedButton.icon(
      onPressed: onPressed,
      icon: const Icon(Icons.save, size: 14),
      label: const Text('Save', style: TextStyle(fontSize: 12)),
      style: ElevatedButton.styleFrom(
        backgroundColor: AppTheme.accent,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
      ),
    );
  }
}
