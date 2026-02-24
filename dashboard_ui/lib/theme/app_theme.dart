import 'package:flutter/material.dart';

class AppTheme {
  static const Color bg = Color(0xFF0F1923);
  static const Color cardBg = Color(0xFF1A2736);
  static const Color cardBorder = Color(0xFF2A3A4A);
  static const Color surface = Color(0xFF253545);
  static const Color textPrimary = Color(0xFFE0E6ED);
  static const Color textSecondary = Color(0xFF7A8A9A);
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
      scaffoldBackgroundColor: bg,
      cardColor: cardBg,
      colorScheme: const ColorScheme.dark(
        primary: accent,
        surface: cardBg,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: cardBg,
        elevation: 0,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surface,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: cardBorder),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: cardBorder),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: accent),
        ),
        hintStyle: const TextStyle(color: textSecondary),
        labelStyle: const TextStyle(color: textSecondary),
      ),
    );
  }
}
