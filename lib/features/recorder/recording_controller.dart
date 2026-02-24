import 'dart:async';
import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart'; // ADICIONAR no pubspec.yaml

import '../../core/platform/screen_recorder_channel.dart';
import '../../core/platform/overlay_bubble_channel.dart';

enum RecordingState { idle, recording, paused, stopping }

class RecordingController extends ChangeNotifier {
  RecordingController() {
    _eventSub = _eventChannel.receiveBroadcastStream().listen(
      _onEvent,
      onError: (e) => debugPrint('❌ EventChannel error: $e'),
    );
    _loadOrientationMode(); // CARREGA O MODO SALVO
  }

  RecordingState state = RecordingState.idle;
  String? lastUri;
  Duration elapsed = Duration.zero;
  Timer? _timer;

  // NOVO: modo de orientação selecionado
  OrientationMode _orientationMode = OrientationMode.auto;
  OrientationMode get orientationMode => _orientationMode;

  bool get isRecording => state == RecordingState.recording;
  bool get isPaused => state == RecordingState.paused;

  static const EventChannel _eventChannel = EventChannel(
    'screen_recorder_events',
  );

  StreamSubscription? _eventSub;

  // NOVO: carregar modo salvo
  Future<void> _loadOrientationMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedIndex = prefs.getInt('orientationMode') ?? 0;
      _orientationMode = OrientationMode.values[savedIndex];
      notifyListeners();
    } catch (e) {
      debugPrint('Erro ao carregar modo de orientação: $e');
    }
  }

  // NOVO: salvar e atualizar modo
  Future<void> setOrientationMode(OrientationMode mode) async {
    if (_orientationMode == mode) return;

    _orientationMode = mode;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('orientationMode', mode.index);
    } catch (e) {
      debugPrint('Erro ao salvar modo de orientação: $e');
    }
    notifyListeners();
  }

  // NOVO: calcular dimensões baseado no modo selecionado
  Map<String, int> _getDimensionsForMode(BuildContext context) {
    final size = MediaQuery.of(context).size;
    final pixelRatio = MediaQuery.of(context).devicePixelRatio;
    final realW = (size.width * pixelRatio).round();
    final realH = (size.height * pixelRatio).round();

    switch (_orientationMode) {
      case OrientationMode.portrait:
        // Força retrato: menor largura, maior altura
        return {
          'width': realW < realH ? realW : realH,
          'height': realW < realH ? realH : realW,
        };

      case OrientationMode.landscape:
        // Força paisagem: maior largura, menor altura
        return {
          'width': realW > realH ? realW : realH,
          'height': realW > realH ? realH : realW,
        };

      case OrientationMode.square:
        // Modo quadrado: 1080x1080 (ou o máximo possível)
        final size = (realW < realH ? realW : realH).clamp(720, 1080);
        return {'width': size, 'height': size};

      case OrientationMode.auto:
      default:
        // Automático: usa orientação atual
        return {'width': realW, 'height': realH};
    }
  }

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
    await Permission.notification.request();
  }

  Future<void> start(BuildContext context) async {
    if (state == RecordingState.recording || state == RecordingState.paused) {
      return;
    }

    await _ensurePerms();

    // USA AS DIMENSÕES BASEADAS NO MODO SELECIONADO
    final dims = _getDimensionsForMode(context);
    final w = dims['width']!;
    final h = dims['height']!;

    final bitrate = (w * h >= 1920 * 1080) ? 12 * 1000 * 1000 : 8 * 1000 * 1000;

    debugPrint('🎥 Iniciando gravação: Modo=${_orientationMode.name} ${w}x$h');

    await ScreenRecorderChannel.start(
      width: w,
      height: h,
      bitrate: bitrate,
      fps: 30,
      recordMic: true,
      orientationMode: _orientationMode, // PASSA O MODO SELECIONADO
    );

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

    await syncFromNative();
  }

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
