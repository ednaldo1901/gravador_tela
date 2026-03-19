import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:photo_manager/photo_manager.dart';
import 'package:video_player/video_player.dart';
import '../../core/platform/ffmpeg_channel.dart';

enum EditorState { idle, loading, trimming, exporting, done, error }

class EditorController extends ChangeNotifier {
  EditorController();
  
  // Estado
  EditorState state = EditorState.idle;
  String? errorMessage;
  
  // Vídeo original
  AssetEntity? originalVideo;
  File? originalFile;
  VideoPlayerController? videoController;
  
  // Metadados
  Duration videoDuration = Duration.zero;
  double videoAspectRatio = 16/9;
  
  // Corte
  Duration trimStart = Duration.zero;
  Duration trimEnd = Duration.zero;
  RangeValues trimRange = const RangeValues(0, 1);
  
  // Vídeo exportado
  File? exportedFile;
  String? exportedPath;
  AssetEntity? exportedAsset;  // NOVO: Asset da galeria
  
  @override
  void dispose() {
    videoController?.dispose();
    super.dispose();
  }
  
  // Carregar vídeo para edição
  Future<bool> loadVideo(AssetEntity video) async {
    try {
      state = EditorState.loading;
      errorMessage = null;
      notifyListeners();
      
      originalVideo = video;
      originalFile = await video.file;
      
      if (originalFile == null) {
        throw Exception('Não foi possível carregar o arquivo');
      }
      
      // Inicializar player
      videoController = VideoPlayerController.file(originalFile!);
      await videoController!.initialize();
      
      videoDuration = videoController!.value.duration;
      videoAspectRatio = videoController!.value.aspectRatio;
      
      // Resetar cortes
      trimStart = Duration.zero;
      trimEnd = videoDuration;
      trimRange = RangeValues(0, 1);
      
      state = EditorState.idle;
      notifyListeners();
      return true;
      
    } catch (e) {
      state = EditorState.error;
      errorMessage = e.toString();
      notifyListeners();
      return false;
    }
  }
  
  // Atualizar corte
  void updateTrim(RangeValues values) {
    trimRange = values;
    trimStart = Duration(
      milliseconds: (videoDuration.inMilliseconds * values.start).round()
    );
    trimEnd = Duration(
      milliseconds: (videoDuration.inMilliseconds * values.end).round()
    );
    notifyListeners();
  }
  
  // Formatar duração
  String formatDuration(Duration d) {
    String twoDigits(int n) => n.toString().padLeft(2, '0');
    String twoDigitMinutes = twoDigits(d.inMinutes.remainder(60));
    String twoDigitSeconds = twoDigits(d.inSeconds.remainder(60));
    return '${twoDigits(d.inHours)}:$twoDigitMinutes:$twoDigitSeconds';
  }
  
  // Exportar vídeo cortado para a galeria
  Future<File?> exportTrimmedVideo() async {
    try {
      state = EditorState.exporting;
      errorMessage = null;
      notifyListeners();
      
      if (originalFile == null) throw Exception('Nenhum vídeo carregado');
      
      // Usar FFmpegChannel para cortar (já salva na galeria)
      final trimmedFile = await FFmpegChannel.trimVideo(
        inputPath: originalFile!.path,
        startSeconds: trimStart.inMilliseconds / 1000,
        durationSeconds: (trimEnd.inMilliseconds - trimStart.inMilliseconds) / 1000,
      );
      
      if (trimmedFile == null) {
        throw Exception('Erro ao processar vídeo');
      }
      
      exportedFile = trimmedFile;
      exportedPath = trimmedFile.path;
      
      // Buscar o asset recém-criado na galeria
      await _refreshExportedAsset();
      
      state = EditorState.done;
      notifyListeners();
      
      return trimmedFile;
      
    } catch (e) {
      state = EditorState.error;
      errorMessage = e.toString();
      notifyListeners();
      return null;
    }
  }
  
  // NOVO: Buscar o asset na galeria pelo caminho
  Future<void> _refreshExportedAsset() async {
    if (exportedPath == null) return;
    
    try {
      final PermissionState ps = await PhotoManager.requestPermissionExtend();
      if (!ps.hasAccess) return;
      
      final albums = await PhotoManager.getAssetPathList(
        type: RequestType.video,
        hasAll: true,
        onlyAll: true,
      );
      
      if (albums.isEmpty) return;
      
      final all = albums.first;
      final assets = await all.getAssetListPaged(page: 0, size: 100);
      
      // Encontrar o vídeo mais recente que corresponde ao nosso arquivo
      final now = DateTime.now().millisecondsSinceEpoch;
      final recent = assets.firstWhere(
        (asset) => (now - asset.createDateTime.millisecondsSinceEpoch) < 5000,
        orElse: () => assets.first,
      );
      
      exportedAsset = recent;
      
    } catch (e) {
      debugPrint('Erro ao buscar asset: $e');
    }
  }
  
  // Resetar editor
  void reset() {
    videoController?.dispose();
    videoController = null;
    originalVideo = null;
    originalFile = null;
    exportedFile = null;
    exportedAsset = null;
    state = EditorState.idle;
    errorMessage = null;
    notifyListeners();
  }
}