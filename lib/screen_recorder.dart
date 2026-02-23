import 'package:flutter/services.dart';

class ScreenRecorder {
  static const _channel = MethodChannel('screen_recorder');


  static Future<void> start({
    int width = 1280,
    int height = 720,
    int bitrate = 6 * 1000 * 1000,
    int fps = 30,
    bool recordMic = true,
  }) async {
    await _channel.invokeMethod('startRecording', {
      'width': width,
      'height': height,
      'bitrate': bitrate,
      'fps': fps,
      'recordMic': recordMic,
    });
  }

  static Future<String?> stop() async {
    final path = await _channel.invokeMethod<String>('stopRecording');
    return path;
  }
}
