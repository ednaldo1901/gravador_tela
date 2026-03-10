import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

class EditorPreview extends StatelessWidget {
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
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,  // ← Ocupa toda largura
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withOpacity(0.2)),
      ),
      clipBehavior: Clip.antiAlias,
      child: Stack(
        children: [
          // Vídeo
          AspectRatio(
            aspectRatio: controller.value.aspectRatio,
            child: VideoPlayer(controller),
          ),
          
          // Overlay escuro
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
          
          // Controles (agora com padding seguro)
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: Container(
              padding: const EdgeInsets.all(12),  // ← Reduzido para caber melhor
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,  // ← Importante!
                children: [
                  // Botão Play/Pause
                  IconButton(
                    onPressed: onPlayPause,
                    icon: Icon(
                      isPlaying
                          ? Icons.pause_circle_filled
                          : Icons.play_circle_filled,
                      color: Colors.white,
                      size: 40,  // ← Reduzido de 48 para 40
                    ),
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(),
                  ),
                  
                  const SizedBox(height: 4),
                  
                  // Barra de progresso e tempo
                  Row(
                    children: [
                      Text(
                        formatDuration(currentPosition),
                        style: const TextStyle(
                          color: Colors.white70,
                          fontSize: 11,  // ← Reduzido
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(2),
                          child: LinearProgressIndicator(
                            value: totalDuration.inMilliseconds > 0
                                ? currentPosition.inMilliseconds /
                                    totalDuration.inMilliseconds
                                : 0,
                            backgroundColor: Colors.white.withOpacity(0.2),
                            valueColor: const AlwaysStoppedAnimation<Color>(Colors.red),
                            minHeight: 3,  // ← Reduzido
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        formatDuration(totalDuration),
                        style: const TextStyle(
                          color: Colors.white70,
                          fontSize: 11,  // ← Reduzido
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