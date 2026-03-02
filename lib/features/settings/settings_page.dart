import 'package:flutter/material.dart';
import 'package:gravador_tela/core/platform/overlay_bubble_channel.dart';
import 'package:gravador_tela/core/platform/usage_stats_channel.dart';
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
  bool _hasUsageStatsPermission = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_initialized) {
      _initialized = true;
      _checkBubbleStatus();
      _checkUsageStatsPermission();
    }
  }

  Future<void> _checkBubbleStatus() async {
    final hasPerm = await OverlayBubbleChannel.hasPermission();
    if (mounted) {
      setState(() => bubbleEnabled = hasPerm);
    }
  }

  Future<void> _checkUsageStatsPermission() async {
    final hasPerm = await UsageStatsChannel.hasPermission();
    if (mounted) {
      setState(() => _hasUsageStatsPermission = hasPerm);
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

          // NOVO: AVISO DE PERMISSÃO USAGE STATS
          if (!_hasUsageStatsPermission) ...[
            const SizedBox(height: 8),
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.amber.withOpacity(0.1),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: Colors.amber.withOpacity(0.3)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.warning_amber, color: Colors.amber),
                      const SizedBox(width: 8),
                      const Expanded(
                        child: Text(
                          'Permissão de Uso Necessária',
                          style: TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.bold,
                            fontSize: 16,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Para detectar jogos automaticamente, ative o "Acesso a dados de uso" nas configurações.',
                    style: TextStyle(
                      color: Colors.white70,
                      fontSize: 14,
                    ),
                  ),
                  const SizedBox(height: 12),
                  ElevatedButton.icon(
                    onPressed: () async {
                      await UsageStatsChannel.openSettings();
                      // Verificar novamente após voltar
                      await Future.delayed(const Duration(seconds: 2));
                      await _checkUsageStatsPermission();
                    },
                    icon: const Icon(Icons.settings),
                    label: const Text('Abrir Configurações'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.amber,
                      foregroundColor: Colors.black,
                    ),
                  ),
                ],
              ),
            ),
          ],
          
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
                _buildInfoRow('Detecção automática', _hasUsageStatsPermission ? '✅ Ativa' : '⚠️ Inativa'),
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