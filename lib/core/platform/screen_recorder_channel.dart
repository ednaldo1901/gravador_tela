import 'package:flutter/services.dart';

enum OrientationMode { auto, portrait, landscape, square }

class ScreenRecorderChannel {
  static const _channel = MethodChannel('screen_recorder');

  static Future<void> start({
    required int width,
    required int height,
    required int bitrate,
    required int fps,
    required bool recordMic,
    required OrientationMode orientationMode,
  }) async {
    await _channel.invokeMethod('startRecording', {
      'width': width,
      'height': height,
      'bitrate': bitrate,
      'fps': fps,
      'recordMic': recordMic,
      'orientationMode': orientationMode.index,
    });
  }

  static Future<void> stop() => _channel.invokeMethod('stopRecording');
  static Future<void> pause() => _channel.invokeMethod('pauseRecording');
  static Future<void> resume() => _channel.invokeMethod('resumeRecording');

  static Future<Map<dynamic, dynamic>> getStatus() async {
    final res = await _channel.invokeMethod<Map<dynamic, dynamic>>('getStatus');
    return res ??
        {
          'state': 'idle',
          'lastUri': null,
          'finalUri': null,
          'elapsed': 0,
          'segments': <String>[],
        };
  }
}