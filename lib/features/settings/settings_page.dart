import 'package:flutter/material.dart';
import 'package:gravador_tela/core/platform/overlay_bubble_channel.dart';
import 'package:provider/provider.dart';

import '../recorder/recording_controller.dart';
import 'orientation_selector.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool bubbleEnabled = false;
  bool _initialized = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_initialized) {
      _initialized = true;
      _checkBubbleStatus();
    }
  }

  Future<void> _checkBubbleStatus() async {
    final hasPerm = await OverlayBubbleChannel.hasPermission();
    if (mounted) {
      setState(() => bubbleEnabled = hasPerm);
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = Provider.of<RecordingController>(context);
    
    return Scaffold(
      backgroundColor: const Color(0xFF0B0F16),
      appBar: AppBar(
        title: const Text(
          'Configurações',
          style: TextStyle(color: Colors.white),
        ),
        backgroundColor: Colors.transparent,
        elevation: 0,
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: ListView(
        children: [
          const SizedBox(height: 20),
          
          // SELETOR DE ORIENTAÇÃO
          OrientationSelector(
            selectedMode: controller.orientationMode,
            onModeSelected: (mode) => controller.setOrientationMode(mode),
          ),
          
          const SizedBox(height: 16),
          
          // OPÇÃO DA BOLHA FLUTUANTE
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            decoration: BoxDecoration(
              color: Colors.white.withOpacity(0.05),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.white.withOpacity(0.1)),
            ),
            child: SwitchListTile(
              title: const Text(
                'Bolha flutuante',
                style: TextStyle(color: Colors.white, fontSize: 16),
              ),
              subtitle: Text(
                'Controlar gravação fora do app',
                style: TextStyle(color: Colors.white.withOpacity(0.6), fontSize: 14),
              ),
              value: bubbleEnabled,
              activeColor: Colors.red,
              onChanged: (v) async {
                if (v) {
                  final ok = await OverlayBubbleChannel.hasPermission();
                  if (!ok) {
                    await OverlayBubbleChannel.openSettings();
                    final newOk = await OverlayBubbleChannel.hasPermission();
                    if (!newOk) return;
                  }
                  await OverlayBubbleChannel.show();
                } else {
                  await OverlayBubbleChannel.hide();
                }

                if (mounted) {
                  setState(() => bubbleEnabled = v);
                }
              },
            ),
          ),
          
          const SizedBox(height: 16),
          
          // INFORMAÇÕES ADICIONAIS
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 16),
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.white.withOpacity(0.05),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.white.withOpacity(0.1)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Informações',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 12),
                _buildInfoRow('Versão', '1.0.0'),
                _buildInfoRow('Modo atual', controller.orientationMode.name.toUpperCase()),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label,
            style: TextStyle(color: Colors.white.withOpacity(0.6)),
          ),
          Text(
            value,
            style: const TextStyle(color: Colors.white),
          ),
        ],
      ),
    );
  }
}