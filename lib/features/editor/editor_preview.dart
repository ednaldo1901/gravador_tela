import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

class EditorPreview extends StatefulWidget {
  final VideoPlayerController controller;
  final bool isPlaying;
  final VoidCallback onPlayPause;
  final Duration currentPosition;
  final Duration totalDuration;
  final String Function(Duration) formatDuration;

  const EditorPreview({
    Key? key,
    required this.controller,
    required this.isPlaying,
    required this.onPlayPause,
    required this.currentPosition,
    required this.totalDuration,
    required this.formatDuration,
  }) : super(key: key);

  @override
  State<EditorPreview> createState() => _EditorPreviewState();
}

class _EditorPreviewState extends State<EditorPreview> {
  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withOpacity(0.2)),
      ),
      clipBehavior: Clip.antiAlias,
      child: Stack(
        children: [
          // Vídeo
          AspectRatio(
            aspectRatio: widget.controller.value.aspectRatio,
            child: VideoPlayer(widget.controller),
          ),
          
          // Overlay escuro para melhor visibilidade dos controles
          Positioned.fill(
            child: Container(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.transparent,
                    Colors.black.withOpacity(0.7),
                  ],
                  stops: const [0.7, 1.0],
                ),
              ),
            ),
          ),
          
          // Controles
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: Container(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Botão Play/Pause
                  IconButton(
                    onPressed: widget.onPlayPause,
                    icon: Icon(
                      widget.isPlaying
                          ? Icons.pause_circle_filled
                          : Icons.play_circle_filled,
                      color: Colors.white,
                      size: 48,
                    ),
                  ),
                  
                  const SizedBox(height: 8),
                  
                  // Barra de progresso e tempo
                  Row(
                    children: [
                      Text(
                        widget.formatDuration(widget.currentPosition),
                        style: const TextStyle(
                          color: Colors.white70,
                          fontSize: 12,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(4),
                          child: LinearProgressIndicator(
                            value: widget.totalDuration.inMilliseconds > 0
                                ? widget.currentPosition.inMilliseconds /
                                    widget.totalDuration.inMilliseconds
                                : 0,
                            backgroundColor: Colors.white.withOpacity(0.2),
                            valueColor: const AlwaysStoppedAnimation<Color>(Colors.red),
                            minHeight: 4,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        widget.formatDuration(widget.totalDuration),
                        style: const TextStyle(
                          color: Colors.white70,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}