import 'package:flutter/material.dart';
import 'package:gravador_tela/features/editor/editor_page.dart';
import 'package:photo_manager/photo_manager.dart';
import 'package:provider/provider.dart';
import 'package:photo_manager_image_provider/photo_manager_image_provider.dart';

import 'gallery_controller.dart';
import 'player_page.dart';

class GalleryPage extends StatefulWidget {
  const GalleryPage({super.key});

  @override
  State<GalleryPage> createState() => _GalleryPageState();
}

class _GalleryPageState extends State<GalleryPage>
    with AutomaticKeepAliveClientMixin, SingleTickerProviderStateMixin {
  @override
  bool get wantKeepAlive => true;

  late final TabController _tabController;
  bool _loadedOnce = false;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();

    if (!_loadedOnce) {
      _loadedOnce = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        context.read<GalleryController>().refresh();
      });
    }
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  String _fmtDuration(Duration d) {
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$m:$s';
  }

  String _fmtDate(DateTime dt) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final that = DateTime(dt.year, dt.month, dt.day);

    if (that == today) return 'Hoje';
    if (that == today.subtract(const Duration(days: 1))) return 'Ontem';

    final dd = dt.day.toString().padLeft(2, '0');
    final mm = dt.month.toString().padLeft(2, '0');
    final yy = (dt.year % 100).toString().padLeft(2, '0');
    return '$dd/$mm/$yy';
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);

    final gal = context.watch<GalleryController>();

    return Scaffold(
      backgroundColor: const Color(0xFF0B0F16),
      body: SafeArea(
        child: Column(
          children: [
            // TOP BAR (igual referência)
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 12, 14, 10),
              child: Row(
                children: [
                  const SizedBox(width: 4),
                  const Text(
                    'Seus Vídeos',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0.2,
                    ),
                  ),
                  const Spacer(),
                  IconButton(
                    onPressed: () async {
                      // simples: reloading por enquanto
                      await context.read<GalleryController>().refresh();
                    },
                    icon: const Icon(Icons.search, color: Colors.white),
                  ),
                ],
              ),
            ),

            // Tabs Galeria/Favoritos
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Container(
                decoration: BoxDecoration(
                  color: Colors.white.withOpacity(0.06),
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: Colors.white.withOpacity(0.08)),
                ),
                child: TabBar(
                  controller: _tabController,
                  labelColor: Colors.white,
                  unselectedLabelColor: Colors.white70,
                  indicator: BoxDecoration(
                    borderRadius: BorderRadius.circular(14),
                    color: Colors.white.withOpacity(0.10),
                  ),
                  dividerColor: Colors.transparent,
                  tabs: const [
                    Tab(text: 'Galeria'),
                    Tab(text: 'Favoritos'),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 12),

            Expanded(
              child: TabBarView(
                controller: _tabController,
                children: [
                  // GALERIA
                  _GalleryList(
                    loading: gal.loading,
                    hasAccess: gal.lastPermissionState?.hasAccess ?? true,
                    videos: gal.videos,
                    fmtDuration: _fmtDuration,
                    fmtDate: _fmtDate,
                    onRefresh: () => context.read<GalleryController>().refresh(),
                    onOpenSettings: () => context
                        .read<GalleryController>()
                        .openSystemGalleryPermission(),
                  ),

                  // FAVORITOS (placeholder)
                  Center(
                    child: Padding(
                      padding: const EdgeInsets.all(20),
                      child: Text(
                        'Favoritos (em breve)',
                        style: TextStyle(
                          color: Colors.white.withOpacity(0.75),
                          fontSize: 14,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _GalleryList extends StatelessWidget {
  final bool loading;
  final bool hasAccess;
  final List videos; // List<AssetEntity>
  final String Function(Duration) fmtDuration;
  final String Function(DateTime) fmtDate;
  final Future<void> Function() onRefresh;
  final Future<void> Function() onOpenSettings;

  const _GalleryList({
    required this.loading,
    required this.hasAccess,
    required this.videos,
    required this.fmtDuration,
    required this.fmtDate,
    required this.onRefresh,
    required this.onOpenSettings,
  });

  @override
  Widget build(BuildContext context) {
    if (loading) {
      return const Center(child: CircularProgressIndicator());
    }

    if (!hasAccess) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.lock_outline, size: 44, color: Colors.white70),
              const SizedBox(height: 10),
              const Text(
                'Permita acesso a Fotos e vídeos\npara ver suas gravações.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white),
              ),
              const SizedBox(height: 14),
              ElevatedButton.icon(
                onPressed: onRefresh,
                icon: const Icon(Icons.refresh),
                label: const Text('Tentar novamente'),
              ),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                onPressed: onOpenSettings,
                icon: const Icon(Icons.settings),
                label: const Text('Abrir permissões'),
              ),
            ],
          ),
        ),
      );
    }

    if (videos.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.video_library_outlined,
                  size: 44, color: Colors.white70),
              const SizedBox(height: 10),
              const Text(
                'Nenhuma gravação ainda.',
                style: TextStyle(color: Colors.white),
              ),
              const SizedBox(height: 14),
              ElevatedButton.icon(
                onPressed: onRefresh,
                icon: const Icon(Icons.refresh),
                label: const Text('Atualizar'),
              ),
            ],
          ),
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(14, 6, 14, 24),
        itemCount: videos.length,
        separatorBuilder: (_, __) => const SizedBox(height: 14),
        itemBuilder: (context, i) {
          final v = videos[i]; // AssetEntity

          return _VideoCard(
            asset: v,
            dateLabel: fmtDate(v.createDateTime),
            durationLabel: fmtDuration(v.videoDuration),
            onTap: () {
              Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => PlayerPage(video: v)),
              );
            },
          );
        },
      ),
    );
  }
}

class _VideoCard extends StatelessWidget {
  final dynamic asset; // AssetEntity
  final String dateLabel;
  final String durationLabel;
  final VoidCallback onTap;

  const _VideoCard({
    required this.asset,
    required this.dateLabel,
    required this.durationLabel,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final title = 'Gravação';

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        height: 118,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          color: Colors.white.withOpacity(0.06),
          border: Border.all(color: Colors.white.withOpacity(0.08)),
          boxShadow: const [
            BoxShadow(
              blurRadius: 18,
              offset: Offset(0, 10),
              color: Color(0x32000000),
            ),
          ],
        ),
        clipBehavior: Clip.antiAlias,
        child: Stack(
          children: [
            // Thumbnail full-card
            Positioned.fill(
              child: AssetEntityImage(
                asset,
                isOriginal: false,
                fit: BoxFit.cover,
                thumbnailSize: const ThumbnailSize(960, 540),
              ),
            ),

            // Gradient overlay (para legibilidade)
            Positioned.fill(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.bottomCenter,
                    end: Alignment.center,
                    colors: [
                      Colors.black.withOpacity(0.75),
                      Colors.black.withOpacity(0.15),
                    ],
                  ),
                ),
              ),
            ),

            // Title + date
            Positioned(
              left: 14,
              bottom: 12,
              right: 84,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 16,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    dateLabel,
                    style: TextStyle(
                      color: Colors.white.withOpacity(0.85),
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ],
              ),
            ),
            // No _VideoCard, adicionar um botão de editar
// Adicione no Stack, próximo ao duration badge:

Positioned(
  top: 12,
  right: 12,
  child: Container(
    decoration: BoxDecoration(
      color: Colors.black.withOpacity(0.55),
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: Colors.white.withOpacity(0.10)),
    ),
    child: IconButton(
      icon: const Icon(Icons.edit, color: Colors.white, size: 18),
      onPressed: () {
        Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => EditorPage(initialVideo: asset),
          ),
        );
      },
    ),
  ),
),

            // Duration badge
            Positioned(
              right: 12,
              bottom: 12,
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                decoration: BoxDecoration(
                  color: Colors.black.withOpacity(0.55),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.white.withOpacity(0.10)),
                ),
                child: Text(
                  durationLabel,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ),

          ],
        ),
      ),
    );
  }
}