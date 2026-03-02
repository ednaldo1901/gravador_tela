import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class UsageStatsChannel {
  static const _channel = MethodChannel('screen_recorder');

  /// Verifica se a permissão de acesso a dados de uso está concedida
  static Future<bool> hasPermission() async {
    try {
      return await _channel.invokeMethod<bool>('hasUsageStatsPermission') ?? false;
    } catch (e) {
      debugPrint('❌ Erro ao verificar permissão UsageStats: $e');
      return false;
    }
  }

  /// Abre as configurações de acesso a dados de uso
  static Future<void> openSettings() async {
    try {
      await _channel.invokeMethod('openUsageStatsSettings');
    } catch (e) {
      debugPrint('❌ Erro ao abrir configurações UsageStats: $e');
    }
  }
}