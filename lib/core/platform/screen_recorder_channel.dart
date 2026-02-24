import 'package:flutter/services.dart';

// Enum para modos de orientação
enum OrientationMode {
  auto,      // Automático - detecta orientação atual
  portrait,  // Força retrato
  landscape, // Força paisagem
  square,    // Quadrado (1080x1080)
}

class ScreenRecorderChannel {
  static const _channel = MethodChannel('screen_recorder');

  static Future<void> start({
    required int width,
    required int height,
    required int bitrate,
    required int fps,
    required bool recordMic,
    required OrientationMode orientationMode, // NOVO PARÂMETRO
  }) async {
    await _channel.invokeMethod('startRecording', {
      'width': width,
      'height': height,
      'bitrate': bitrate,
      'fps': fps,
      'recordMic': recordMic,
      'orientationMode': orientationMode.index, // 0,1,2,3
    });
  }

  static Future<void> stop() async {
    await _channel.invokeMethod('stopRecording');
  }

  static Future<void> pause() async {
    await _channel.invokeMethod('pauseRecording');
  }

  static Future<void> resume() async {
    await _channel.invokeMethod('resumeRecording');
  }

  static Future<Map<dynamic, dynamic>> getStatus() async {
    final res = await _channel.invokeMethod<Map<dynamic, dynamic>>('getStatus');
    return res ?? {'state': 'idle', 'lastUri': null};
  }
}