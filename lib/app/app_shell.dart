import 'package:flutter/material.dart';
import 'package:google_nav_bar/google_nav_bar.dart';
import 'package:provider/provider.dart';

import '../features/recorder/recording_controller.dart';
import '../features/recorder/recorder_page.dart';
import '../features/gallery/gallery_controller.dart';
import '../features/gallery/gallery_page.dart';
import '../features/settings/settings_page.dart';
import '../features/editor/editor_page.dart';

class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  int index = 0;
  String? _lastHandledUri;

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final rec = context.read<RecordingController>();
      rec.addListener(_onRecordingChanged);

      await rec.syncFromNative();
      _onRecordingChanged();
    });
  }

  void _onRecordingChanged() {
    if (!mounted) return;

    final rec = context.read<RecordingController>();
    final finished = rec.state == RecordingState.idle && rec.lastUri != null;
    if (!finished) return;

    if (_lastHandledUri == rec.lastUri) return;
    _lastHandledUri = rec.lastUri;

    context.read<GalleryController>().refresh();
  }

  @override
  void dispose() {
    try {
      context.read<RecordingController>().removeListener(_onRecordingChanged);
    } catch (_) {}
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final pages = const [
      RecorderPage(),
      GalleryPage(),
      SettingsPage(),
      EditorPage(),
    ];

    return Scaffold(
      body: pages[index],
      bottomNavigationBar: Container(
        padding: const EdgeInsets.fromLTRB(14, 10, 14, 14),
        decoration: BoxDecoration(color: Colors.black.withValues(alpha: 0.9)),
        child: GNav(
          gap: 8,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          tabBorderRadius: 16,
          tabBackgroundColor: Colors.red.withValues(alpha: 0.15),
          color: Colors.white70,
          activeColor: Colors.red,
          tabs: const [
            GButton(icon: Icons.fiber_manual_record, text: 'Gravar'),
            GButton(icon: Icons.video_library_outlined, text: 'Galeria'),
            GButton(icon: Icons.settings_outlined, text: 'Config'),
            GButton(icon: Icons.edit_outlined, text: 'Criar'),
          ],
          selectedIndex: index,
          onTabChange: (i) async {
            setState(() => index = i);

            // ✅ FORÇA refresh quando entrar na Galeria
            if (i == 1) {
              await context.read<GalleryController>().refresh();
            }
          },
        ),
      ),
    );
  }
}
