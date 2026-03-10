import 'package:flutter/material.dart';

class VideoTrimmer extends StatelessWidget {
  final double minValue;
  final double maxValue;
  final RangeValues values;
  final Function(RangeValues) onChanged;
  final Duration currentDuration;
  final Duration totalDuration;
  final String Function(Duration) formatDuration;

  const VideoTrimmer({
    Key? key,
    required this.minValue,
    required this.maxValue,
    required this.values,
    required this.onChanged,
    required this.currentDuration,
    required this.totalDuration,
    required this.formatDuration,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12), // ← Reduzido padding
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.05),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.white.withOpacity(0.1)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min, // ← Importante!
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Título
          const Text(
            'Cortar Vídeo',
            style: TextStyle(
              color: Colors.white,
              fontSize: 14, // ← Reduzido
              fontWeight: FontWeight.w600,
            ),
          ),

          const SizedBox(height: 12), // ← Reduzido
          // Range Slider
          RangeSlider(
            values: values,
            min: minValue,
            max: maxValue,
            divisions: 100,
            activeColor: Colors.red,
            inactiveColor: Colors.white.withOpacity(0.2),
            labels: RangeLabels(
              'Início: ${formatDuration(currentDuration)}',
              'Fim: ${formatDuration(totalDuration - currentDuration)}',
            ),
            onChanged: onChanged,
          ),

          const SizedBox(height: 8),

          // Informações de tempo em linha (não coluna)
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              _buildTimeInfo(
                'Início',
                formatDuration(currentDuration),
                Icons.play_arrow,
              ),
              _buildTimeInfo(
                'Duração',
                formatDuration(totalDuration - currentDuration),
                Icons.timer,
              ),
              _buildTimeInfo(
                'Selecionado',
                formatDuration(totalDuration - currentDuration),
                Icons.content_cut,
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildTimeInfo(String label, String time, IconData icon) {
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: 8,
        vertical: 4,
      ), // ← Reduzido
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 12, color: Colors.white70),
              const SizedBox(width: 2),
              Text(
                label,
                style: TextStyle(
                  color: Colors.white.withOpacity(0.7),
                  fontSize: 10, // ← Reduzido
                ),
              ),
            ],
          ),
          const SizedBox(height: 2),
          Text(
            time,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 11, // ← Reduzido
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}
