import 'package:flutter/material.dart';

class AppColors {
  // Cores principais
  static const Color primary = Color(0xFFE53935);
  static const Color primaryDark = Color(0xFFB71C1C);
  static const Color primaryLight = Color(0xFFFFCDD2);
  
  // Background
  static const Color background = Color(0xFF000000);
  static const Color surface = Color(0xFF121212);
  static const Color surfaceLight = Color(0xFF1E1E1E);
  
  // Texto
  static const Color textPrimary = Color(0xFFFFFFFF);
  static const Color textSecondary = Color(0xFFB0B0B0);
  static const Color textHint = Color(0xFF666666);
  
  // Estados
  static const Color success = Color(0xFF4CAF50);
  static const Color warning = Color(0xFFFFC107);
  static const Color error = Color(0xFFF44336);
  static const Color info = Color(0xFF2196F3);
  
  // Overlays
  static const Color overlayLight = Color(0x1AFFFFFF);
  static const Color overlayMedium = Color(0x33FFFFFF);
  static const Color overlayDark = Color(0x66FFFFFF);
  
  // Bordas
  static const Color border = Color(0x33FFFFFF);
  static const Color borderLight = Color(0x1AFFFFFF);
  
  // Gradientes
  static const LinearGradient primaryGradient = LinearGradient(
    colors: [Color(0xFFE53935), Color(0xFFB71C1C)],
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
  );
  
  static const RadialGradient radialGradient = RadialGradient(
    colors: [Color(0xFFFF5A5A), Color(0xFFB10000)],
  );
}