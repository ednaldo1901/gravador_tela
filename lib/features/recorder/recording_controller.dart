import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';
import 'package:gravador_tela/core/platform/overlay_bubble_channel.dart';
import 'package:gravador_tela/core/platform/screen_recorder_channel.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

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
      debugPrint('📐 Modo de orientação carregado: ${_orientationMode.name} (índice: $idx)');
    } catch (e) {
      debugPrint('⚠️ Erro ao carregar modo de orientação: $e');
    }
  }

  Future<void> setOrientationMode(OrientationMode mode) async {
    if (_orientationMode == mode) return;
    
    debugPrint('📐 Alterando modo de orientação: ${_orientationMode.name} -> ${mode.name}');
    _orientationMode = mode;

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('orientationMode', mode.index);
      debugPrint('💾 Modo de orientação salvo: ${mode.index}');
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

    debugPrint('📱 Dimensões reais da tela: ${realW}x${realH} (pixelRatio: $pixelRatio)');

    switch (_orientationMode) {
      case OrientationMode.portrait:
        final w = realW < realH ? realW : realH;
        final h = realW < realH ? realH : realW;
        debugPrint('📱 Modo RETRATO: ${w}x$h');
        return {'width': w, 'height': h};

      case OrientationMode.landscape:
        final w = realW > realH ? realW : realH;
        final h = realW > realH ? realH : realW;
        debugPrint('🌍 Modo PAISAGEM: ${w}x$h');
        return {'width': w, 'height': h};

      case OrientationMode.square:
        final s = (realW < realH ? realW : realH).clamp(720, 1080);
        debugPrint('⬛ Modo QUADRADO: ${s}x$s');
        return {'width': s, 'height': s};

      case OrientationMode.auto:
      default:
        debugPrint('🔄 Modo AUTO: ${realW}x$realH');
        return {'width': realW, 'height': realH};
    }
  }

  // ------------------ EventChannel ------------------

  Future<void> _onEvent(dynamic e) async {
    final map = Map<String, dynamic>.from(e as Map);

    final type = (map['type'] as String?) ?? '';
    final s = (map['state'] as String?) ?? 'idle';
    final ms = (map['elapsed'] as int?) ?? 0;
    final gameStatus = map['gameConfirmationStatus'] as String?;

    debugPrint('📡 EventChannel: type=$type, state=$s, gameStatus=$gameStatus');

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

    final maybeFinal = map['finalUri'] as String?;
    if (maybeFinal != null && maybeFinal.isNotEmpty) {
      finalUri = maybeFinal;
      debugPrint('💾 Vídeo final recebido: $finalUri');
    }

    notifyListeners();

    // no stop, se o Android não mandou finalUri (fallback), usa lastUri
    if (type == 'stop') {
      if ((finalUri == null || finalUri!.isEmpty) && (lastUri?.isNotEmpty ?? false)) {
        finalUri = lastUri;
        debugPrint('⚠️ Fallback: usando lastUri como finalUri');
        notifyListeners();
      }
    }
  }

  // ------------------ sync fallback ------------------

  Future<void> syncFromNative() async {
    debugPrint('🔄 Sincronizando com native...');
    final st = await ScreenRecorderChannel.getStatus();

    final s = (st['state'] as String?) ?? 'idle';
    lastUri = st['lastUri'] as String?;
    final ms = (st['elapsed'] is int) ? (st['elapsed'] as int) : 0;

    debugPrint('📊 Status native: state=$s, lastUri=$lastUri, elapsed=$ms');

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
    debugPrint('🔐 Verificando permissões...');
    final mic = await Permission.microphone.request();
    if (!mic.isGranted) throw Exception('Permissão do microfone negada.');
    await Permission.notification.request();
    debugPrint('✅ Permissões OK');
  }

  // ------------------ actions ------------------

  Future<void> start(BuildContext context) async {
    if (state == RecordingState.recording || state == RecordingState.paused) return;

    debugPrint('🎬 Iniciando gravação...');
    await _ensurePerms();

    // zera vídeo final anterior
    finalUri = null;

    final dims = _getDimensionsForMode(context);
    final w = dims['width']!;
    final h = dims['height']!;

    final bitrate = (w * h >= 1920 * 1080) ? 12 * 1000 * 1000 : 8 * 1000 * 1000;

    debugPrint('🎥 Start: mode=${_orientationMode.name} (index=${_orientationMode.index}) ${w}x$h bitrate=${bitrate ~/ 1000000}Mbps');

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
      if (ok) {
        await OverlayBubbleChannel.show();
        debugPrint('💬 Bolha flutuante ativada');
      }
    } catch (e) {
      debugPrint('⚠️ Falha ao mostrar bolha: $e');
    }

    state = RecordingState.recording;
    elapsed = Duration.zero;
    _startTimer();
    notifyListeners();
    debugPrint('✅ Gravação iniciada');
  }

  Future<void> pause() async {
    if (state != RecordingState.recording) return;
    debugPrint('⏸️ Pausando gravação...');
    await ScreenRecorderChannel.pause();

    state = RecordingState.paused;
    _stopTimer();
    notifyListeners();
    debugPrint('⏸️ Gravação pausada');
  }

  Future<void> resume() async {
    if (state != RecordingState.paused) return;
    debugPrint('▶️ Retomando gravação...');
    await ScreenRecorderChannel.resume();

    state = RecordingState.recording;
    _startTimer();
    notifyListeners();
    debugPrint('▶️ Gravação retomada');
  }

  Future<void> stop() async {
    if (state == RecordingState.idle) return;

    debugPrint('⏹️ Parando gravação...');
    state = RecordingState.stopping;
    notifyListeners();

    await ScreenRecorderChannel.stop();

    try {
      await OverlayBubbleChannel.hide();
      debugPrint('💬 Bolha flutuante desativada');
    } catch (e) {
      debugPrint('⚠️ Falha ao esconder bolha: $e');
    }

    // o evento "stop" vai chegar com finalUri (merge Android).
    // sync aqui é só fallback.
    await syncFromNative();
    debugPrint('⏹️ Gravação finalizada');
  }

  // ------------------ timer local (UI suave) ------------------

  void _startTimer() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      elapsed += const Duration(seconds: 1);
      notifyListeners();
    });
    debugPrint('⏱️ Timer iniciado');
  }

  void _startTimerFromNative(int ms) {
    elapsed = Duration(milliseconds: ms);
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      elapsed += const Duration(seconds: 1);
      notifyListeners();
    });
    debugPrint('⏱️ Timer sincronizado com native: ${elapsed.inSeconds}s');
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
    debugPrint('⏱️ Timer parado');
  }

  @override
  void dispose() {
    debugPrint('🗑️ Disposing RecordingController');
    _eventSub?.cancel();
    _stopTimer();
    super.dispose();
  }
}