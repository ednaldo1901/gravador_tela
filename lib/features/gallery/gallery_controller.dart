import 'package:flutter/foundation.dart';
import 'package:photo_manager/photo_manager.dart';

class GalleryController extends ChangeNotifier {
  bool loading = false;
  List<AssetEntity> videos = [];

  PermissionState? lastPermissionState;

  Future<void> openSystemGalleryPermission() async {
    await PhotoManager.openSetting();
  }

  Future<bool> _ensurePmPermission() async {
    final ps = await PhotoManager.requestPermissionExtend();
    lastPermissionState = ps;

    debugPrint('🖼️ PhotoManager PermissionState=$ps hasAccess=${ps.hasAccess}');

    if (!ps.hasAccess) return false;
    return true;
  }

  Future<void> refresh() async {
    loading = true;
    notifyListeners();

    await PhotoManager.clearFileCache();

    final ok = await _ensurePmPermission();
    if (!ok) {
      videos = [];
      loading = false;
      notifyListeners();
      return;
    }

    // ✅ igual ao exemplo: pega somente o "Recent/All"
    final albums = await PhotoManager.getAssetPathList(
      type: RequestType.video,
      hasAll: true,
      onlyAll: true,
    );

    if (albums.isEmpty) {
      debugPrint('⚠️ Nenhum álbum de vídeo encontrado.');
      videos = [];
      loading = false;
      notifyListeners();
      return;
    }

    final all = albums.first;

    debugPrint('📁 Álbum(All/Recent): name=${all.name} isAll=${all.isAll}');

    final assets = await all.getAssetListPaged(page: 0, size: 500);
    assets.sort((a, b) => b.createDateTime.compareTo(a.createDateTime));

    debugPrint('🎞️ Total vídeos encontrados: ${assets.length}');
    if (assets.isNotEmpty) {
      debugPrint(
        '📌 Exemplo: id=${assets.first.id} relativePath=${assets.first.relativePath} date=${assets.first.createDateTime}',
      );
    }

    videos = assets;

    loading = false;
    notifyListeners();
  }
}