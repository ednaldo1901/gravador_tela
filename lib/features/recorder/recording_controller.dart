import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

import '../../core/platform/screen_recorder_channel.dart';
import '../../core/platform/overlay_bubble_channel.dart';

enum RecordingState { idle, recording, paused, stopping }

class RecordingController extends ChangeNotifier {
  RecordingController() {
    _eventSub = _eventChannel.receiveBroadcastStream().listen(
      _onEvent,
      onError: (e) => debugPrint('❌ EventChannel error: $e'),
    );
  }

  RecordingState state = RecordingState.idle;
  String? lastUri;

  Duration elapsed = Duration.zero;
  Timer? _timer;

  bool get isRecording => state == RecordingState.recording;
  bool get isPaused => state == RecordingState.paused;

  // ✅ EventChannel (Android -> Flutter)
  static const EventChannel _eventChannel =
      EventChannel('screen_recorder_events');

  StreamSubscription? _eventSub;

  void _onEvent(dynamic e) {
    final map = Map<String, dynamic>.from(e as Map);

    final s = (map['state'] as String?) ?? 'idle';
    final ms = (map['elapsed'] as int?) ?? 0;

    if (s == 'recording') {
      state = RecordingState.recording;
      _startTimerFromNative(ms);
    } else if (s == 'paused') {
      state = RecordingState.paused;
      _stopTimer();
      elapsed = Duration(milliseconds: ms);
    } else {
      state = RecordingState.idle;
      _stopTimer();
      elapsed = Duration.zero;
    }

    lastUri = map['lastUri'] as String?;
    notifyListeners();
  }

  Future<void> syncFromNative() async {
    final st = await ScreenRecorderChannel.getStatus();

    final s = (st['state'] as String?) ?? 'idle';
    lastUri = st['lastUri'] as String?;

    final ms = (st['elapsed'] is int) ? (st['elapsed'] as int) : 0;

    if (s == 'recording') {
      state = RecordingState.recording;
      _startTimerFromNative(ms);
    } else if (s == 'paused') {
      state = RecordingState.paused;
      _stopTimer();
      elapsed = Duration(milliseconds: ms);
    } else {
      state = RecordingState.idle;
      _stopTimer();
      elapsed = Duration.zero;
    }

    notifyListeners();
  }

  Future<void> _ensurePerms() async {
    final mic = await Permission.microphone.request();
    if (!mic.isGranted) throw Exception('Permissão do microfone negada.');
    await Permission.notification.request(); // Android 13+
  }

  Map<String, int> _screenPixels(BuildContext context) {
    final size = MediaQuery.of(context).size;
    final pixelRatio = MediaQuery.of(context).devicePixelRatio;
    return {
      'width': (size.width * pixelRatio).round(),
      'height': (size.height * pixelRatio).round(),
    };
  }

  Future<void> start(BuildContext context) async {
    if (state == RecordingState.recording || state == RecordingState.paused) {
      return;
    }

    await _ensurePerms();

    final px = _screenPixels(context);
    final w = px['width']!;
    final h = px['height']!;

    final bitrate =
        (w * h >= 1920 * 1080) ? 12 * 1000 * 1000 : 8 * 1000 * 1000;

    await ScreenRecorderChannel.start(
      width: w,
      height: h,
      bitrate: bitrate,
      fps: 30,
      recordMic: true,
    );

    // ✅ Mostra bolha (se já tiver permissão)
    try {
      final ok = await OverlayBubbleChannel.hasPermission();
      if (ok) await OverlayBubbleChannel.show();
    } catch (e) {
      debugPrint('⚠️ Falha ao mostrar bolha: $e');
    }

    state = RecordingState.recording;
    elapsed = Duration.zero;
    _startTimer();
    notifyListeners();
  }

  Future<void> pause() async {
    if (state != RecordingState.recording) return;
    await ScreenRecorderChannel.pause();

    // UI imediata (EventChannel também vai refletir)
    state = RecordingState.paused;
    _stopTimer();
    notifyListeners();
  }

  Future<void> resume() async {
    if (state != RecordingState.paused) return;
    await ScreenRecorderChannel.resume();

    state = RecordingState.recording;
    _startTimer();
    notifyListeners();
  }

  Future<void> stop() async {
    if (state == RecordingState.idle) return;

    state = RecordingState.stopping;
    notifyListeners();

    await ScreenRecorderChannel.stop();

    // ✅ Esconde bolha
    try {
      await OverlayBubbleChannel.hide();
    } catch (e) {
      debugPrint('⚠️ Falha ao esconder bolha: $e');
    }

    // Atualiza lastUri/state/elapsed
    await syncFromNative();
  }

  // Timer local (UI suave)
  void _startTimer() {
    _timer ??= Timer.periodic(const Duration(seconds: 1), (_) {
      elapsed += const Duration(seconds: 1);
      notifyListeners();
    });
  }

  void _startTimerFromNative(int ms) {
    elapsed = Duration(milliseconds: ms);
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      elapsed += const Duration(seconds: 1);
      notifyListeners();
    });
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    _stopTimer();
    super.dispose();
  }
}