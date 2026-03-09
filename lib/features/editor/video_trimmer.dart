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
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.05),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.white.withOpacity(0.1)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Título
          const Text(
            'Cortar Vídeo',
            style: TextStyle(
              color: Colors.white,
              fontSize: 16,
              fontWeight: FontWeight.w600,
            ),
          ),
          
          const SizedBox(height: 20),
          
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
          
          const SizedBox(height: 16),
          
          // Informações de tempo
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
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
          
          const SizedBox(height: 8),
          
          // Progresso visual
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: (totalDuration.inMilliseconds - currentDuration.inMilliseconds) / 
                     totalDuration.inMilliseconds,
              backgroundColor: Colors.white.withOpacity(0.1),
              valueColor: const AlwaysStoppedAnimation<Color>(Colors.red),
              minHeight: 6,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTimeInfo(String label, String time, IconData icon) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.03),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        children: [
          Row(
            children: [
              Icon(icon, size: 14, color: Colors.white70),
              const SizedBox(width: 4),
              Text(
                label,
                style: TextStyle(
                  color: Colors.white.withOpacity(0.7),
                  fontSize: 12,
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            time,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}