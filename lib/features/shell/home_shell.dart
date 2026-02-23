import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../gallery/gallery_controller.dart';
import '../gallery/gallery_page.dart';
import '../recorder/recorder_page.dart';
import '../recorder/recording_controller.dart';

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int index = 0;

  // pra não dar refresh múltiplas vezes pro mesmo uri
  String? _lastHandledUri;

  @override
  void initState() {
    super.initState();

    // Espera providers montarem e então registra listener
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final rec = context.read<RecordingController>();
      rec.addListener(_onRecordingChanged);

      // opcional: sincroniza status ao abrir o app
      rec.syncFromNative();
    });
  }

  void _onRecordingChanged() {
    final rec = context.read<RecordingController>();

    final finished = rec.state == RecordingState.idle && rec.lastUri != null;

    if (!finished) return;

    // evita duplicar refresh pro mesmo vídeo
    if (_lastHandledUri == rec.lastUri) return;
    _lastHandledUri = rec.lastUri;

    // ✅ atualiza galeria
    context.read<GalleryController>().refresh();
  }

  @override
  void dispose() {
    // remove listener com segurança
    try {
      context.read<RecordingController>().removeListener(_onRecordingChanged);
    } catch (_) {}
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      const RecorderPage(),
      const GalleryPage(),
      const _StubPage(title: 'Configurações (em breve)'),
      const _StubPage(title: 'Criar/Editar (em breve)'),
    ];

    return Scaffold(
      body: pages[index],
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (i) => setState(() => index = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.fiber_manual_record),
            label: 'Gravar',
          ),
          NavigationDestination(
            icon: Icon(Icons.video_library_outlined),
            label: 'Galeria',
          ),
          NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            label: 'Config',
          ),
          NavigationDestination(
            icon: Icon(Icons.auto_fix_high_outlined),
            label: 'Editar',
          ),
        ],
      ),
    );
  }
}

class _StubPage extends StatelessWidget {
  final String title;
  const _StubPage({required this.title});

  @override
  Widget build(BuildContext context) {
    return Center(child: Text(title));
  }
}
