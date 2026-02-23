import 'package:flutter/services.dart';

class ScreenRecorderChannel {
  static const _channel = MethodChannel('screen_recorder');

  static Future<void> start({
    required int width,
    required int height,
    required int bitrate,
    required int fps,
    required bool recordMic,
  }) async {
    await _channel.invokeMethod('startRecording', {
      'width': width,
      'height': height,
      'bitrate': bitrate,
      'fps': fps,
      'recordMic': recordMic,
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