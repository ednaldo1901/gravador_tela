import 'package:flutter/material.dart';
import '../../core/platform/screen_recorder_channel.dart';

class OrientationSelector extends StatelessWidget {
  final OrientationMode selectedMode;
  final Function(OrientationMode) onModeSelected;

  const OrientationSelector({
    Key? key,
    required this.selectedMode,
    required this.onModeSelected,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.05),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.white.withOpacity(0.1)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Text(
              'Modo de Gravação',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: Colors.white,
              ),
            ),
          ),
          _buildOption(
            mode: OrientationMode.auto,
            icon: Icons.smartphone,
            title: 'Automático',
            description: 'Detecta automaticamente a orientação atual',
          ),
          _buildOption(
            mode: OrientationMode.portrait,
            icon: Icons.phone_iphone,
            title: 'Retrato (em pé)',
            description: '720x1280 - ideal para stories',
          ),
          _buildOption(
            mode: OrientationMode.landscape,
            icon: Icons.tablet_android,
            title: 'Paisagem (deitado)',
            description: '1280x720 - ideal para YouTube',
          ),
          _buildOption(
            mode: OrientationMode.square,
            icon: Icons.crop_square,
            title: 'Quadrado',
            description: '1080x1080 - ideal para Instagram',
          ),
        ],
      ),
    );
  }

  Widget _buildOption({
    required OrientationMode mode,
    required IconData icon,
    required String title,
    required String description,
  }) {
    final isSelected = selectedMode == mode;
    
    return InkWell(
      onTap: () => onModeSelected(mode),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: BoxDecoration(
          color: isSelected ? Colors.red.withOpacity(0.15) : Colors.transparent,
          border: Border(
            top: BorderSide(color: Colors.white.withOpacity(0.05)),
          ),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: isSelected ? Colors.red : Colors.white.withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(
                icon,
                color: isSelected ? Colors.white : Colors.white70,
                size: 24,
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w500,
                      color: isSelected ? Colors.red : Colors.white,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    description,
                    style: TextStyle(
                      fontSize: 12,
                      color: Colors.white.withOpacity(0.6),
                    ),
                  ),
                ],
              ),
            ),
            if (isSelected)
              const Icon(
                Icons.check_circle,
                color: Colors.red,
                size: 24,
              ),
          ],
        ),
      ),
    );
  }
}