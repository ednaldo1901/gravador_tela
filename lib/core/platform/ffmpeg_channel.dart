import 'dart:io';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:photo_manager/photo_manager.dart';

class FFmpegChannel {
  static const MethodChannel _channel = MethodChannel('screen_recorder');

  /// Cortar vídeo usando FFmpeg e salvar na galeria
  static Future<File?> trimVideo({
    required String inputPath,
    required double startSeconds,
    required double durationSeconds,
    String? outputPath,
  }) async {
    try {
      // Se não especificar outputPath, criar na galeria
      final outPath = outputPath ?? await _getGalleryOutputPath();
      
      final result = await _channel.invokeMethod('trimVideo', {
        'inputPath': inputPath,
        'outputPath': outPath,
        'startSeconds': startSeconds,
        'durationSeconds': durationSeconds,
      });
      
      if (result != null && result['success'] == true) {
        final file = File(result['outputPath']);
        
        // NOVO: Adicionar à galeria usando PhotoManager
        if (await file.exists()) {
          final asset = await PhotoManager.editor.saveVideo(
            file,
            title: file.path.split('/').last,
          );
          if (asset != null) {
            print('✅ Vídeo adicionado à galeria: ${asset.title}');
          }
        }
        
        return file;
      }
      
      return null;
    } catch (e) {
      print('❌ Erro ao cortar vídeo: $e');
      return null;
    }
  }

  /// Criar caminho na pasta pública Movies/ScreenRecords
  static Future<String> _getGalleryOutputPath() async {
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final fileName = 'edited_video_$timestamp.mp4';
    
    // No Android, usar o diretório público de Movies
    if (Platform.isAndroid) {
      final directory = await getExternalStorageDirectory();
      // Ou usar um caminho na pasta Movies
      final moviesDir = Directory('/storage/emulated/0/Movies/ScreenRecords');
      
      if (!await moviesDir.exists()) {
        await moviesDir.create(recursive: true);
      }
      
      return '${moviesDir.path}/$fileName';
    }
    
    // Fallback para diretório de documentos
    final dir = await getApplicationDocumentsDirectory();
    return '${dir.path}/$fileName';
  }

  /// Verificar se FFmpeg está disponível
  static Future<bool> isAvailable() async {
    try {
      final result = await _channel.invokeMethod('checkFFmpeg');
      return result == true;
    } catch (e) {
      return false;
    }
  }
}