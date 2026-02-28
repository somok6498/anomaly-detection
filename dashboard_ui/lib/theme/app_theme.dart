import 'package:flutter/material.dart';

class AppTheme {
  // Current mode — set by ThemeNotifier, read by widgets
  static bool isDark = true;

  // Dynamic colors that change with theme
  static Color get bg => isDark ? const Color(0xFF0F1923) : const Color(0xFFF5F7FA);
  static Color get cardBg => isDark ? const Color(0xFF1A2736) : Colors.white;
  static Color get cardBorder => isDark ? const Color(0xFF2A3A4A) : const Color(0xFFE2E8F0);
  static Color get surface => isDark ? const Color(0xFF253545) : const Color(0xFFF1F5F9);
  static Color get textPrimary => isDark ? const Color(0xFFE0E6ED) : const Color(0xFF1E293B);
  static Color get textSecondary => isDark ? const Color(0xFF7A8A9A) : const Color(0xFF64748B);
  static const Color accent = Color(0xFF4A9EFF);

  static const Color low = Color(0xFF36D399);
  static const Color medium = Color(0xFFF59E0B);
  static const Color high = Color(0xFFF97316);
  static const Color critical = Color(0xFFFF6B6B);

  static const Color pass = Color(0xFF36D399);
  static const Color alert = Color(0xFFF59E0B);
  static const Color block = Color(0xFFFF6B6B);

  static const List<Color> typeColors = [
    Color(0xFF4A9EFF),
    Color(0xFF36D399),
    Color(0xFFF59E0B),
    Color(0xFFF472B6),
    Color(0xFFA78BFA),
    Color(0xFFFB7185),
  ];

  static Color riskColor(String level) {
    switch (level.toUpperCase()) {
      case 'LOW':
        return low;
      case 'MEDIUM':
        return medium;
      case 'HIGH':
        return high;
      case 'CRITICAL':
        return critical;
      default:
        return textSecondary;
    }
  }

  static Color actionColor(String action) {
    switch (action.toUpperCase()) {
      case 'PASS':
        return pass;
      case 'ALERT':
        return alert;
      case 'BLOCK':
        return block;
      default:
        return textSecondary;
    }
  }

  static Color feedbackColor(String status) {
    switch (status.toUpperCase()) {
      case 'TRUE_POSITIVE':
        return critical;
      case 'FALSE_POSITIVE':
        return low;
      case 'AUTO_ACCEPTED':
        return medium;
      default:
        return textSecondary;
    }
  }

  static String feedbackLabel(String status) {
    switch (status.toUpperCase()) {
      case 'TRUE_POSITIVE':
        return 'True Positive';
      case 'FALSE_POSITIVE':
        return 'False Positive';
      case 'AUTO_ACCEPTED':
        return 'Auto-Accepted';
      default:
        return 'Pending';
    }
  }

  static ThemeData get darkTheme {
    return ThemeData.dark().copyWith(
      scaffoldBackgroundColor: const Color(0xFF0F1923),
      cardColor: const Color(0xFF1A2736),
      colorScheme: const ColorScheme.dark(
        primary: accent,
        surface: Color(0xFF1A2736),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Color(0xFF1A2736),
        elevation: 0,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xFF253545),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: Color(0xFF2A3A4A)),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: Color(0xFF2A3A4A)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: accent),
        ),
        hintStyle: const TextStyle(color: Color(0xFF7A8A9A)),
        labelStyle: const TextStyle(color: Color(0xFF7A8A9A)),
      ),
    );
  }

  static ThemeData get lightTheme {
    return ThemeData.light().copyWith(
      scaffoldBackgroundColor: const Color(0xFFF5F7FA),
      cardColor: Colors.white,
      colorScheme: const ColorScheme.light(
        primary: accent,
        surface: Colors.white,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.white,
        elevation: 0,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xFFF1F5F9),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: Color(0xFFE2E8F0)),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: Color(0xFFE2E8F0)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: accent),
        ),
        hintStyle: const TextStyle(color: Color(0xFF64748B)),
        labelStyle: const TextStyle(color: Color(0xFF64748B)),
      ),
    );
  }
}
