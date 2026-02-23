import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'recording_controller.dart';

class RecorderPage extends StatelessWidget {
  const RecorderPage({super.key});

  String _fmt(Duration d) {
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$m:$s';
  }

  @override
  Widget build(BuildContext context) {
    final rec = context.watch<RecordingController>();

    final isRecording = rec.isRecording;
    final isPaused = rec.isPaused;

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: const Text('Gravador de Tela'),
        backgroundColor: Colors.black.withValues(alpha: 0.5),
        foregroundColor: Colors.white,
        actions: [
          IconButton(
            onPressed: () {
              // abre aba Config depois (ou navegue se quiser)
            },
            icon: const Icon(Icons.settings),
          ),
        ],
      ),
      body: Stack(
        children: [
          // fundo
          Positioned.fill(
            child: Image.asset('assets/images/bg_2.jpg', fit: BoxFit.cover),
          ),

          // leve overlay para legibilidade
          Positioned.fill(
            child: Container(color: Colors.black.withValues(alpha: 0.35)),
          ),

          // conteúdo
          Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // Botão central redondo estilo imagem
                GestureDetector(
                  onTap: () async {
                    if (isRecording) {
                      await rec.stop();
                    } else if (isPaused) {
                      await rec.resume();
                    } else {
                      await rec.start(context);
                    }
                  },
                  child: Container(
                    width: 170,
                    height: 170,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: const RadialGradient(
                        colors: [Color(0xFFFF5A5A), Color(0xFFB10000)],
                      ),
                      boxShadow: [
                        BoxShadow(
                          color: Colors.black.withValues(alpha: 0.45),
                          blurRadius: 22,
                          offset: const Offset(0, 12),
                        ),
                      ],
                    ),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(
                          Icons.videocam,
                          color: Colors.white,
                          size: 52,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          isRecording
                              ? 'Parar'
                              : (isPaused ? 'Retomar' : 'Iniciar Gravação'),
                          style: const TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),

                const SizedBox(height: 16),

                // barra info (resolução/áudio) parecida com imagem
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 10,
                  ),
                  decoration: BoxDecoration(
                    color: Colors.black.withValues(alpha: 0.35),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Text(
                    isRecording
                        ? 'Gravando: ${_fmt(rec.elapsed)} | Áudio: Microfone'
                        : (isPaused
                              ? 'Pausado: ${_fmt(rec.elapsed)} | Áudio: Microfone'
                              : 'Resolução: Auto | Áudio: Microfone'),
                    style: const TextStyle(color: Colors.white),
                  ),
                ),

                const SizedBox(height: 18),

                // controles extras (pausar/retomar) como “poder escondido”
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    ElevatedButton.icon(
                      onPressed: isRecording ? rec.pause : null,
                      icon: const Icon(Icons.pause),
                      label: const Text('Pausar'),
                    ),
                    const SizedBox(width: 12),
                    ElevatedButton.icon(
                      onPressed: isPaused ? rec.resume : null,
                      icon: const Icon(Icons.play_arrow),
                      label: const Text('Retomar'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),

      // botão flutuante estilo “Gravar” (igual imagem)
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          if (isRecording) {
            await rec.stop();
          } else if (isPaused) {
            await rec.resume();
          } else {
            await rec.start(context);
          }
        },
        icon: Icon(isRecording ? Icons.stop : Icons.fiber_manual_record),
        label: Text(isRecording ? 'Parar' : 'Gravar'),
      ),
    );
  }
}
