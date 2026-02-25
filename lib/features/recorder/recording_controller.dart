import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/platform/screen_recorder_channel.dart';
import '../../core/platform/overlay_bubble_channel.dart';

enum RecordingState { idle, recording, paused, stopping }

class RecordingController extends ChangeNotifier {
  RecordingController() {
    _eventSub = _eventChannel.receiveBroadcastStream().listen(
      (e) {
        unawaited(_onEvent(e));
      },
      onError: (e) => debugPrint('❌ EventChannel error: $e'),
    );

    unawaited(_loadOrientationMode());
  }

  // ---------- estado ----------
  RecordingState state = RecordingState.idle;

  /// URI do último segmento em gravação (debug/fallback)
  String? lastUri;

  /// ✅ URI FINAL (merge no Android) — esse é o que a UI deve usar
  String? finalUri;

  Duration elapsed = Duration.zero;
  Timer? _timer;

  bool get isRecording => state == RecordingState.recording;
  bool get isPaused => state == RecordingState.paused;

  // ---------- orientação ----------
  OrientationMode _orientationMode = OrientationMode.auto;
  OrientationMode get orientationMode => _orientationMode;

  // ---------- canais ----------
  static const EventChannel _eventChannel = EventChannel('screen_recorder_events');
  StreamSubscription? _eventSub;

  // ------------------ prefs ------------------

  Future<void> _loadOrientationMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedIndex = prefs.getInt('orientationMode') ?? 0;
      final idx = savedIndex.clamp(0, OrientationMode.values.length - 1);
      _orientationMode = OrientationMode.values[idx];
      notifyListeners();
    } catch (e) {
      debugPrint('⚠️ Erro ao carregar modo de orientação: $e');
    }
  }

  Future<void> setOrientationMode(OrientationMode mode) async {
    if (_orientationMode == mode) return;
    _orientationMode = mode;

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('orientationMode', mode.index);
    } catch (e) {
      debugPrint('⚠️ Erro ao salvar modo de orientação: $e');
    }

    notifyListeners();
  }

  // ------------------ dimensões (para o START) ------------------

  Map<String, int> _getDimensionsForMode(BuildContext context) {
    final size = MediaQuery.of(context).size;
    final pixelRatio = MediaQuery.of(context).devicePixelRatio;
    final realW = (size.width * pixelRatio).round();
    final realH = (size.height * pixelRatio).round();

    switch (_orientationMode) {
      case OrientationMode.portrait:
        return {
          'width': realW < realH ? realW : realH,
          'height': realW < realH ? realH : realW,
        };

      case OrientationMode.landscape:
        return {
          'width': realW > realH ? realW : realH,
          'height': realW > realH ? realH : realW,
        };

      case OrientationMode.square:
        final s = (realW < realH ? realW : realH).clamp(720, 1080);
        return {'width': s, 'height': s};

      case OrientationMode.auto:
      default:
        return {'width': realW, 'height': realH};
    }
  }

  // ------------------ EventChannel ------------------

  Future<void> _onEvent(dynamic e) async {
    final map = Map<String, dynamic>.from(e as Map);

    final type = (map['type'] as String?) ?? '';
    final s = (map['state'] as String?) ?? 'idle';
    final ms = (map['elapsed'] as int?) ?? 0;

    // estado + timer
    if (s == 'recording') {
      state = RecordingState.recording;
      _startTimerFromNative(ms);
    } else if (s == 'paused') {
      state = RecordingState.paused;
      _stopTimer();
      elapsed = Duration(milliseconds: ms);
    } else if (s == 'stopping') {
      state = RecordingState.stopping;
      _stopTimer();
      elapsed = Duration(milliseconds: ms);
    } else {
      state = RecordingState.idle;
      _stopTimer();
      elapsed = Duration.zero;
    }

    // URIs
    lastUri = map['lastUri'] as String?;

    // ✅ finalUri vem no stop (merge Android)
    final maybeFinal = map['finalUri'] as String?;
    if (maybeFinal != null && maybeFinal.isNotEmpty) {
      finalUri = maybeFinal;
    }

    notifyListeners();

    // no stop, se o Android não mandou finalUri (fallback), usa lastUri
    if (type == 'stop') {
      if ((finalUri == null || finalUri!.isEmpty) && (lastUri?.isNotEmpty ?? false)) {
        finalUri = lastUri;
        notifyListeners();
      }
    }
  }

  // ------------------ sync fallback ------------------

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
    } else if (s == 'stopping') {
      state = RecordingState.stopping;
      _stopTimer();
      elapsed = Duration(milliseconds: ms);
    } else {
      state = RecordingState.idle;
      _stopTimer();
      elapsed = Duration.zero;
    }

    notifyListeners();
  }

  // ------------------ permissões ------------------

  Future<void> _ensurePerms() async {
    final mic = await Permission.microphone.request();
    if (!mic.isGranted) throw Exception('Permissão do microfone negada.');
    await Permission.notification.request();
  }

  // ------------------ actions ------------------

  Future<void> start(BuildContext context) async {
    if (state == RecordingState.recording || state == RecordingState.paused) return;

    await _ensurePerms();

    // zera vídeo final anterior
    finalUri = null;

    final dims = _getDimensionsForMode(context);
    final w = dims['width']!;
    final h = dims['height']!;

    final bitrate = (w * h >= 1920 * 1080) ? 12 * 1000 * 1000 : 8 * 1000 * 1000;

    debugPrint('🎥 Start: mode=${_orientationMode.name} ${w}x$h');

    await ScreenRecorderChannel.start(
      width: w,
      height: h,
      bitrate: bitrate,
      fps: 30,
      recordMic: true,
      orientationMode: _orientationMode,
    );

    // mostra bolha se permitido
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

    try {
      await OverlayBubbleChannel.hide();
    } catch (e) {
      debugPrint('⚠️ Falha ao esconder bolha: $e');
    }

    // o evento "stop" vai chegar com finalUri (merge Android).
    // sync aqui é só fallback.
    await syncFromNative();
  }

  // ------------------ timer local (UI suave) ------------------

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