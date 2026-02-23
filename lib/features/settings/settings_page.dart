import 'package:flutter/material.dart';
import '../../core/platform/overlay_bubble_channel.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool bubbleEnabled = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Configurações')),
      body: ListView(
        children: [
          SwitchListTile(
            title: const Text('Bolha flutuante'),
            subtitle: const Text('Controlar gravação fora do app'),
            value: bubbleEnabled,
            onChanged: (v) async {
              if (v) {
                final ok = await OverlayBubbleChannel.hasPermission();
                if (!ok) {
                  await OverlayBubbleChannel.openSettings();
                  return;
                }
                await OverlayBubbleChannel.show();
              } else {
                await OverlayBubbleChannel.hide();
              }

              setState(() => bubbleEnabled = v);
            },
          ),
        ],
      ),
    );
  }
}