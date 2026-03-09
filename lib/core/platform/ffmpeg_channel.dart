import 'dart:io';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

class FFmpegChannel {
  static const MethodChannel _channel = MethodChannel('screen_recorder');

  /// Cortar vídeo usando FFmpeg (via nativo)
  static Future<File?> trimVideo({
    required String inputPath,
    required double startSeconds,
    required double durationSeconds,
    String? outputPath,
  }) async {
    try {
      // Se não especificar outputPath, criar um temporário
      final outPath = outputPath ?? await _getTempOutputPath();
      
      final result = await _channel.invokeMethod('trimVideo', {
        'inputPath': inputPath,
        'outputPath': outPath,
        'startSeconds': startSeconds,
        'durationSeconds': durationSeconds,
      });
      
      if (result != null && result['success'] == true) {
        return File(result['outputPath']);
      }
      
      return null;
    } catch (e) {
      print('❌ Erro ao cortar vídeo: $e');
      return null;
    }
  }

  /// Obter informações do vídeo
  static Future<Map<String, dynamic>?> getVideoInfo(String path) async {
    try {
      final result = await _channel.invokeMethod('getVideoInfo', {
        'path': path,
      });
      
      return result != null ? Map<String, dynamic>.from(result) : null;
    } catch (e) {
      print('❌ Erro ao obter info do vídeo: $e');
      return null;
    }
  }

  /// Criar caminho temporário para output
  static Future<String> _getTempOutputPath() async {
    final dir = await getApplicationDocumentsDirectory();
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    return '${dir.path}/trimmed_$timestamp.mp4';
  }

  /// Verificar se FFmpeg está disponível
  static Future<bool> isAvailable() async {
    try {
      final result = await _channel.invokeMethod('checkFFmpeg');
      return result == true;
    } catch (e) {
      return false;
    }
  }
}