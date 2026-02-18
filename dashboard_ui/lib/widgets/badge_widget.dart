import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

class ActionBadge extends StatelessWidget {
  final String action;

  const ActionBadge({super.key, required this.action});

  @override
  Widget build(BuildContext context) {
    final color = AppTheme.actionColor(action);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        action,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class RiskBadge extends StatelessWidget {
  final String level;

  const RiskBadge({super.key, required this.level});

  @override
  Widget build(BuildContext context) {
    final color = AppTheme.riskColor(level);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        level,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}
