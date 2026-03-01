import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../theme/app_theme.dart';

enum TimeRangePreset {
  min1, min5, min15, min30, hour1, hour6, hour12, hour24, day7, custom
}

class TimeRange {
  final TimeRangePreset preset;
  final int fromEpochMs;
  final int toEpochMs;

  const TimeRange._({required this.preset, required this.fromEpochMs, required this.toEpochMs});

  factory TimeRange.fromPreset(TimeRangePreset preset) {
    final now = DateTime.now().millisecondsSinceEpoch;
    final duration = _presetDurations[preset];
    if (duration == null) {
      return TimeRange._(preset: preset, fromEpochMs: now - const Duration(minutes: 15).inMilliseconds, toEpochMs: now);
    }
    return TimeRange._(preset: preset, fromEpochMs: now - duration.inMilliseconds, toEpochMs: now);
  }

  factory TimeRange.custom(DateTime from, DateTime to) {
    return TimeRange._(
      preset: TimeRangePreset.custom,
      fromEpochMs: from.millisecondsSinceEpoch,
      toEpochMs: to.millisecondsSinceEpoch,
    );
  }

  String get label {
    if (preset != TimeRangePreset.custom) {
      return _presetLabels[preset] ?? '15m';
    }
    final from = DateTime.fromMillisecondsSinceEpoch(fromEpochMs);
    final to = DateTime.fromMillisecondsSinceEpoch(toEpochMs);
    final fmt = DateFormat('dd MMM HH:mm');
    return '${fmt.format(from)} — ${fmt.format(to)}';
  }

  static const _presetDurations = <TimeRangePreset, Duration>{
    TimeRangePreset.min1: Duration(minutes: 1),
    TimeRangePreset.min5: Duration(minutes: 5),
    TimeRangePreset.min15: Duration(minutes: 15),
    TimeRangePreset.min30: Duration(minutes: 30),
    TimeRangePreset.hour1: Duration(hours: 1),
    TimeRangePreset.hour6: Duration(hours: 6),
    TimeRangePreset.hour12: Duration(hours: 12),
    TimeRangePreset.hour24: Duration(hours: 24),
    TimeRangePreset.day7: Duration(days: 7),
  };

  static const _presetLabels = <TimeRangePreset, String>{
    TimeRangePreset.min1: '1m',
    TimeRangePreset.min5: '5m',
    TimeRangePreset.min15: '15m',
    TimeRangePreset.min30: '30m',
    TimeRangePreset.hour1: '1h',
    TimeRangePreset.hour6: '6h',
    TimeRangePreset.hour12: '12h',
    TimeRangePreset.hour24: '24h',
    TimeRangePreset.day7: '7d',
  };
}

class TimeRangeSelector extends StatefulWidget {
  final TimeRangePreset initialPreset;
  final ValueChanged<TimeRange> onChanged;

  const TimeRangeSelector({
    super.key,
    this.initialPreset = TimeRangePreset.min15,
    required this.onChanged,
  });

  @override
  State<TimeRangeSelector> createState() => _TimeRangeSelectorState();
}

class _TimeRangeSelectorState extends State<TimeRangeSelector> {
  late TimeRangePreset _selected;
  DateTime? _customFrom;
  DateTime? _customTo;

  @override
  void initState() {
    super.initState();
    _selected = widget.initialPreset;
  }

  void _selectPreset(TimeRangePreset preset) {
    setState(() => _selected = preset);
    widget.onChanged(TimeRange.fromPreset(preset));
  }

  Future<void> _showCustomPicker() async {
    final result = await showDialog<TimeRange>(
      context: context,
      builder: (ctx) => _CustomRangeDialog(
        initialFrom: _customFrom ?? DateTime.now().subtract(const Duration(hours: 1)),
        initialTo: _customTo ?? DateTime.now(),
      ),
    );
    if (result != null) {
      setState(() {
        _selected = TimeRangePreset.custom;
        _customFrom = DateTime.fromMillisecondsSinceEpoch(result.fromEpochMs);
        _customTo = DateTime.fromMillisecondsSinceEpoch(result.toEpochMs);
      });
      widget.onChanged(result);
    }
  }

  @override
  Widget build(BuildContext context) {
    final presets = TimeRangePreset.values.where((p) => p != TimeRangePreset.custom).toList();

    return SizedBox(
      height: 36,
      child: Row(
        children: [
          Icon(Icons.schedule, size: 14, color: AppTheme.textSecondary),
          const SizedBox(width: 6),
          Expanded(
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  ...presets.map((p) => _buildChip(
                    label: TimeRange._presetLabels[p]!,
                    isActive: _selected == p,
                    onTap: () => _selectPreset(p),
                  )),
                  _buildChip(
                    label: _selected == TimeRangePreset.custom ? _customLabel() : 'Custom',
                    isActive: _selected == TimeRangePreset.custom,
                    onTap: _showCustomPicker,
                    icon: Icons.calendar_today,
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _customLabel() {
    if (_customFrom == null || _customTo == null) return 'Custom';
    final fmt = DateFormat('dd MMM HH:mm');
    return '${fmt.format(_customFrom!)} — ${fmt.format(_customTo!)}';
  }

  Widget _buildChip({
    required String label,
    required bool isActive,
    required VoidCallback onTap,
    IconData? icon,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 2),
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
          decoration: BoxDecoration(
            color: isActive ? AppTheme.accent.withValues(alpha: 0.15) : AppTheme.surface,
            borderRadius: BorderRadius.circular(6),
            border: Border.all(
              color: isActive ? AppTheme.accent : AppTheme.cardBorder,
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (icon != null) ...[
                Icon(icon, size: 11, color: isActive ? AppTheme.accent : AppTheme.textSecondary),
                const SizedBox(width: 4),
              ],
              Text(
                label,
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: isActive ? FontWeight.w700 : FontWeight.w500,
                  color: isActive ? AppTheme.accent : AppTheme.textSecondary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Custom Range Dialog ──

class _CustomRangeDialog extends StatefulWidget {
  final DateTime initialFrom;
  final DateTime initialTo;

  const _CustomRangeDialog({required this.initialFrom, required this.initialTo});

  @override
  State<_CustomRangeDialog> createState() => _CustomRangeDialogState();
}

class _CustomRangeDialogState extends State<_CustomRangeDialog> {
  late DateTime _from;
  late DateTime _to;

  @override
  void initState() {
    super.initState();
    _from = widget.initialFrom;
    _to = widget.initialTo;
  }

  Future<void> _pickDate(bool isFrom) async {
    final current = isFrom ? _from : _to;
    final picked = await showDatePicker(
      context: context,
      initialDate: current,
      firstDate: DateTime.now().subtract(const Duration(days: 365)),
      lastDate: DateTime.now(),
      builder: (ctx, child) => Theme(
        data: ThemeData.dark().copyWith(
          colorScheme: ColorScheme.dark(
            primary: AppTheme.accent,
            surface: AppTheme.cardBg,
          ),
        ),
        child: child!,
      ),
    );
    if (picked != null) {
      setState(() {
        if (isFrom) {
          _from = DateTime(picked.year, picked.month, picked.day, _from.hour, _from.minute);
        } else {
          _to = DateTime(picked.year, picked.month, picked.day, _to.hour, _to.minute);
        }
      });
    }
  }

  Future<void> _pickTime(bool isFrom) async {
    final current = isFrom ? _from : _to;
    final picked = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(current),
      builder: (ctx, child) => Theme(
        data: ThemeData.dark().copyWith(
          colorScheme: ColorScheme.dark(
            primary: AppTheme.accent,
            surface: AppTheme.cardBg,
          ),
        ),
        child: child!,
      ),
    );
    if (picked != null) {
      setState(() {
        if (isFrom) {
          _from = DateTime(_from.year, _from.month, _from.day, picked.hour, picked.minute);
        } else {
          _to = DateTime(_to.year, _to.month, _to.day, picked.hour, picked.minute);
        }
      });
    }
  }

  bool get _isValid => _from.isBefore(_to);

  @override
  Widget build(BuildContext context) {
    final dateFmt = DateFormat('dd MMM yyyy');
    final timeFmt = DateFormat('HH:mm');

    return AlertDialog(
      backgroundColor: AppTheme.cardBg,
      title: Text('Custom Time Range',
          style: TextStyle(color: AppTheme.textPrimary, fontSize: 16)),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _buildRow('From', _from, dateFmt, timeFmt, true),
          const SizedBox(height: 16),
          _buildRow('To', _to, dateFmt, timeFmt, false),
          if (!_isValid) ...[
            const SizedBox(height: 12),
            Text('"From" must be before "To"',
                style: TextStyle(color: AppTheme.critical, fontSize: 12)),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text('Cancel', style: TextStyle(color: AppTheme.textSecondary)),
        ),
        ElevatedButton(
          onPressed: _isValid
              ? () => Navigator.pop(context, TimeRange.custom(_from, _to))
              : null,
          style: ElevatedButton.styleFrom(
            backgroundColor: AppTheme.accent,
            foregroundColor: Colors.white,
            disabledBackgroundColor: AppTheme.surface,
          ),
          child: const Text('Apply'),
        ),
      ],
    );
  }

  Widget _buildRow(String label, DateTime dt, DateFormat dateFmt, DateFormat timeFmt, bool isFrom) {
    return Row(
      children: [
        SizedBox(
          width: 40,
          child: Text(label, style: TextStyle(
            color: AppTheme.textSecondary, fontSize: 12, fontWeight: FontWeight.w600,
          )),
        ),
        const SizedBox(width: 8),
        OutlinedButton(
          onPressed: () => _pickDate(isFrom),
          style: OutlinedButton.styleFrom(
            foregroundColor: AppTheme.textPrimary,
            side: BorderSide(color: AppTheme.cardBorder),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          ),
          child: Text(dateFmt.format(dt), style: const TextStyle(fontSize: 12)),
        ),
        const SizedBox(width: 8),
        OutlinedButton(
          onPressed: () => _pickTime(isFrom),
          style: OutlinedButton.styleFrom(
            foregroundColor: AppTheme.textPrimary,
            side: BorderSide(color: AppTheme.cardBorder),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          ),
          child: Text(timeFmt.format(dt), style: const TextStyle(fontSize: 12)),
        ),
      ],
    );
  }
}
