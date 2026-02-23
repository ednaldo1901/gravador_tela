import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:photo_manager/photo_manager.dart';
import 'package:share_plus/share_plus.dart';
import 'package:video_player/video_player.dart';

class PlayerPage extends StatefulWidget {
  final AssetEntity video;
  const PlayerPage({super.key, required this.video});

  @override
  State<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends State<PlayerPage> {
  VideoPlayerController? _c;
  bool _loading = true;

  bool _showControls = true;
  bool _dragging = false;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    setState(() => _loading = true);

    final file = await widget.video.file;
    if (file == null) {
      setState(() => _loading = false);
      return;
    }

    final c = VideoPlayerController.file(File(file.path));
    await c.initialize();
    await c.setLooping(false);

    // Rebuild quando o vídeo avança (pra atualizar scrubber)
    c.addListener(_onTick);

    setState(() {
      _c = c;
      _loading = false;
    });

    await c.play();
  }

  void _onTick() {
    if (!mounted) return;
    if (_dragging) return;
    setState(() {});
  }

  @override
  void dispose() {
    _c?.removeListener(_onTick);
    _c?.dispose();
    super.dispose();
  }

  String _fmt(Duration d) {
    final mm = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final ss = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    final hh = d.inHours;
    if (hh > 0) return '${hh.toString().padLeft(2, '0')}:$mm:$ss';
    return '$mm:$ss';
  }

  Future<void> _share() async {
    final f = await widget.video.file;
    if (f == null) return;
    await Share.shareXFiles([XFile(f.path)], text: 'Minha gravação de tela');
  }

  Future<void> _delete() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Apagar vídeo?'),
        content: const Text('Isso remove o vídeo da galeria.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Apagar'),
          ),
        ],
      ),
    );

    if (ok != true) return;

    await PhotoManager.editor.deleteWithIds([widget.video.id]);
    if (!mounted) return;
    Navigator.pop(context);
  }

  Future<void> _togglePlay() async {
    final c = _c;
    if (c == null) return;
    if (c.value.isPlaying) {
      await c.pause();
    } else {
      await c.play();
    }
    setState(() {});
  }

  Future<void> _seekBy(int seconds) async {
    final c = _c;
    if (c == null) return;
    final pos = c.value.position + Duration(seconds: seconds);
    final dur = c.value.duration;

    Duration clamped = pos;
    if (clamped < Duration.zero) clamped = Duration.zero;
    if (clamped > dur) clamped = dur;

    await c.seekTo(clamped);
    setState(() {});
  }

  Future<void> _openFullscreen() async {
    final c = _c;
    if (c == null) return;

    // pausa o rebuild “pesado” aqui; o controller é o mesmo
    await Navigator.of(
      context,
    ).push(MaterialPageRoute(builder: (_) => _FullscreenPlayer(controller: c)));

    // volta e atualiza
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final c = _c;

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black.withOpacity(0.6),
        foregroundColor: Colors.white,
        title: const Text('Player'),
        actions: [
          IconButton(onPressed: _share, icon: const Icon(Icons.share)),
          IconButton(
            onPressed: _delete,
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : (c == null
                  ? const Center(
                      child: Text(
                        'Não foi possível abrir o vídeo.',
                        style: TextStyle(color: Colors.white70),
                      ),
                    )
                  : GestureDetector(
                      onTap: () =>
                          setState(() => _showControls = !_showControls),
                      child: Stack(
                        children: [
                          // vídeo centralizado
                          Center(
                            child: AspectRatio(
                              aspectRatio: c.value.aspectRatio,
                              child: VideoPlayer(c),
                            ),
                          ),

                          // overlay play/pause grande (tipo streaming)
                          if (_showControls)
                            Positioned.fill(
                              child: Container(
                                color: Colors.black.withOpacity(0.25),
                                child: Center(
                                  child: IconButton(
                                    iconSize: 72,
                                    onPressed: _togglePlay,
                                    icon: Icon(
                                      c.value.isPlaying
                                          ? Icons.pause_circle_filled
                                          : Icons.play_circle_filled,
                                      color: Colors.white,
                                    ),
                                  ),
                                ),
                              ),
                            ),

                          // controles inferiores fixos (sem overflow)
                          if (_showControls)
                            Positioned(
                              left: 0,
                              right: 0,
                              bottom: 0,
                              child: _BottomControls(
                                position: c.value.position,
                                duration: c.value.duration,
                                playing: c.value.isPlaying,
                                fmt: _fmt,
                                onPlayPause: _togglePlay,
                                onBack10: () => _seekBy(-10),
                                onForward10: () => _seekBy(10),
                                onFullscreen: _openFullscreen,
                                onScrubStart: () =>
                                    setState(() => _dragging = true),
                                onScrubEnd: () =>
                                    setState(() => _dragging = false),
                                onSeekTo: (d) => c.seekTo(d),
                              ),
                            ),
                        ],
                      ),
                    )),
      ),
    );
  }
}

class _BottomControls extends StatelessWidget {
  final Duration position;
  final Duration duration;
  final bool playing;

  final String Function(Duration) fmt;

  final VoidCallback onPlayPause;
  final VoidCallback onBack10;
  final VoidCallback onForward10;
  final VoidCallback onFullscreen;

  final VoidCallback onScrubStart;
  final VoidCallback onScrubEnd;
  final Future<void> Function(Duration) onSeekTo;

  const _BottomControls({
    required this.position,
    required this.duration,
    required this.playing,
    required this.fmt,
    required this.onPlayPause,
    required this.onBack10,
    required this.onForward10,
    required this.onFullscreen,
    required this.onScrubStart,
    required this.onScrubEnd,
    required this.onSeekTo,
  });

  @override
  Widget build(BuildContext context) {
    final posMs = position.inMilliseconds.clamp(0, duration.inMilliseconds);
    final maxMs = duration.inMilliseconds <= 0 ? 1 : duration.inMilliseconds;

    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.fromLTRB(14, 10, 14, 14),
        decoration: BoxDecoration(
          color: Colors.black.withOpacity(0.65),
          border: Border(
            top: BorderSide(color: Colors.white.withOpacity(0.10)),
          ),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // tempo + slider + fullscreen
            Row(
              children: [
                Text(
                  fmt(position),
                  style: const TextStyle(color: Colors.white70),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: SliderTheme(
                    data: SliderTheme.of(context).copyWith(
                      trackHeight: 3,
                      thumbShape: const RoundSliderThumbShape(
                        enabledThumbRadius: 7,
                      ),
                      overlayShape: const RoundSliderOverlayShape(
                        overlayRadius: 14,
                      ),
                    ),
                    child: Slider(
                      value: posMs.toDouble(),
                      max: maxMs.toDouble(),
                      onChangeStart: (_) => onScrubStart(),
                      onChangeEnd: (_) => onScrubEnd(),
                      onChanged: (v) =>
                          onSeekTo(Duration(milliseconds: v.round())),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Text(
                  fmt(duration),
                  style: const TextStyle(color: Colors.white70),
                ),
                IconButton(
                  onPressed: onFullscreen,
                  icon: const Icon(Icons.fullscreen, color: Colors.white),
                ),
              ],
            ),

            const SizedBox(height: 6),

            // botões (estilo Play Store)
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                  onPressed: onBack10,
                  icon: const Icon(Icons.replay_10, color: Colors.white),
                  iconSize: 30,
                ),
                const SizedBox(width: 10),
                IconButton(
                  onPressed: onPlayPause,
                  icon: Icon(
                    playing
                        ? Icons.pause_circle_filled
                        : Icons.play_circle_filled,
                    color: Colors.white,
                  ),
                  iconSize: 56,
                ),
                const SizedBox(width: 10),
                IconButton(
                  onPressed: onForward10,
                  icon: const Icon(Icons.forward_10, color: Colors.white),
                  iconSize: 30,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _FullscreenPlayer extends StatefulWidget {
  final VideoPlayerController controller;
  const _FullscreenPlayer({required this.controller});

  @override
  State<_FullscreenPlayer> createState() => _FullscreenPlayerState();
}

class _FullscreenPlayerState extends State<_FullscreenPlayer> {
  bool _show = true;

  @override
  void initState() {
    super.initState();
    _enter();
    widget.controller.addListener(_tick);
  }

  void _tick() {
    if (!mounted) return;
    setState(() {});
  }

  Future<void> _enter() async {
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    await SystemChrome.setPreferredOrientations([
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
  }

  Future<void> _exit() async {
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    await SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
    ]);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_tick);
    _exit();
    super.dispose();
  }

  String _fmt(Duration d) {
    final mm = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final ss = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    final hh = d.inHours;
    if (hh > 0) return '${hh.toString().padLeft(2, '0')}:$mm:$ss';
    return '$mm:$ss';
  }

  Future<void> _togglePlay() async {
    if (widget.controller.value.isPlaying) {
      await widget.controller.pause();
    } else {
      await widget.controller.play();
    }
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;

    return Scaffold(
      backgroundColor: Colors.black,
      body: GestureDetector(
        onTap: () => setState(() => _show = !_show),
        child: Stack(
          children: [
            Center(
              child: AspectRatio(
                aspectRatio: c.value.aspectRatio,
                child: VideoPlayer(c),
              ),
            ),
            if (_show)
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: SafeArea(
                  top: false,
                  child: Container(
                    padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
                    color: Colors.black.withOpacity(0.55),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Row(
                          children: [
                            Text(
                              _fmt(c.value.position),
                              style: const TextStyle(color: Colors.white70),
                            ),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Slider(
                                value: c.value.position.inMilliseconds
                                    .clamp(0, c.value.duration.inMilliseconds)
                                    .toDouble(),
                                max:
                                    (c.value.duration.inMilliseconds <= 0
                                            ? 1
                                            : c.value.duration.inMilliseconds)
                                        .toDouble(),
                                onChanged: (v) =>
                                    c.seekTo(Duration(milliseconds: v.round())),
                              ),
                            ),
                            const SizedBox(width: 10),
                            Text(
                              _fmt(c.value.duration),
                              style: const TextStyle(color: Colors.white70),
                            ),
                            IconButton(
                              onPressed: () => Navigator.pop(context),
                              icon: const Icon(
                                Icons.fullscreen_exit,
                                color: Colors.white,
                              ),
                            ),
                          ],
                        ),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            IconButton(
                              onPressed: () async {
                                final pos =
                                    c.value.position -
                                    const Duration(seconds: 10);
                                await c.seekTo(
                                  pos < Duration.zero ? Duration.zero : pos,
                                );
                              },
                              icon: const Icon(
                                Icons.replay_10,
                                color: Colors.white,
                              ),
                              iconSize: 30,
                            ),
                            const SizedBox(width: 10),
                            IconButton(
                              onPressed: _togglePlay,
                              icon: Icon(
                                c.value.isPlaying
                                    ? Icons.pause_circle_filled
                                    : Icons.play_circle_filled,
                                color: Colors.white,
                              ),
                              iconSize: 56,
                            ),
                            const SizedBox(width: 10),
                            IconButton(
                              onPressed: () async {
                                final pos =
                                    c.value.position +
                                    const Duration(seconds: 10);
                                final dur = c.value.duration;
                                await c.seekTo(pos > dur ? dur : pos);
                              },
                              icon: const Icon(
                                Icons.forward_10,
                                color: Colors.white,
                              ),
                              iconSize: 30,
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
