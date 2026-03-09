import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:photo_manager/photo_manager.dart';
import 'package:path_provider/path_provider.dart';
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
  
  // Exportar vídeo cortado
  Future<File?> exportTrimmedVideo() async {
    try {
      state = EditorState.exporting;
      errorMessage = null;
      notifyListeners();
      
      if (originalFile == null) throw Exception('Nenhum vídeo carregado');
      
      // Usar FFmpegChannel para cortar
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
  
  // Resetar editor
  void reset() {
    videoController?.dispose();
    videoController = null;
    originalVideo = null;
    originalFile = null;
    exportedFile = null;
    state = EditorState.idle;
    errorMessage = null;
    notifyListeners();
  }
}