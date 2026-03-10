import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:photo_manager/photo_manager.dart';

import '../../core/widgets/loading_overlay.dart';
import 'editor_controller.dart';
import 'video_trimmer.dart';
import 'editor_preview.dart';

class EditorPage extends StatefulWidget {
  final AssetEntity? initialVideo;
  
  const EditorPage({
    super.key,
    this.initialVideo,
  });

  @override
  State<EditorPage> createState() => _EditorPageState();
}

class _EditorPageState extends State<EditorPage> {
  late EditorController _controller;
  bool _isPlaying = false;

  @override
  void initState() {
    super.initState();
    _controller = EditorController();
    
    if (widget.initialVideo != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _controller.loadVideo(widget.initialVideo!);
      });
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _selectVideo() async {
    try {
      final PermissionState ps = await PhotoManager.requestPermissionExtend();
      if (!ps.hasAccess) {
        _showError('Permissão negada para acessar a galeria');
        return;
      }

      final List<AssetEntity>? result = await PhotoManager.getAssetPathList(
        type: RequestType.video,
        hasAll: true,
      ).then((albums) async {
        if (albums.isEmpty) return null;
        final recent = albums.first;
        return await recent.getAssetListPaged(page: 0, size: 100);
      });

      if (result != null && result.isNotEmpty) {
        await _controller.loadVideo(result.first);
      }
    } catch (e) {
      _showError('Erro ao selecionar vídeo: $e');
    }
  }

  Future<void> _exportVideo(BuildContext context) async {
    final file = await _controller.exportTrimmedVideo();
    
    if (file != null && mounted) {
      _showSuccess(context);
    }
  }

  void _togglePlayPause() {
    if (_isPlaying) {
      _controller.videoController?.pause();
    } else {
      _controller.videoController?.play();
    }
    setState(() => _isPlaying = !_isPlaying);
  }

  void _pauseVideo() {
    if (_isPlaying) {
      _controller.videoController?.pause();
      setState(() => _isPlaying = false);
    }
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: Colors.red,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  void _showSuccess(BuildContext context) {
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Sucesso!'),
        content: const Text(
          'Vídeo exportado com sucesso!\n'
          'Ele foi salvo nos seus documentos.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('OK'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              Navigator.pop(context);
            },
            child: const Text('Voltar'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider.value(
      value: _controller,
      child: Consumer<EditorController>(
        builder: (context, controller, child) {
          return Scaffold(
            backgroundColor: Colors.black,
            appBar: AppBar(
              title: const Text(
                'Editor de Vídeo',
                style: TextStyle(color: Colors.white),
              ),
              backgroundColor: Colors.transparent,
              elevation: 0,
              leading: IconButton(
                icon: const Icon(Icons.close, color: Colors.white),
                onPressed: () => Navigator.pop(context),
              ),
              actions: [
                if (controller.originalVideo != null) ...[
                  IconButton(
                    icon: const Icon(Icons.video_library, color: Colors.white),
                    onPressed: _selectVideo,
                  ),
                  IconButton(
                    icon: const Icon(Icons.save, color: Colors.red),
                    onPressed: () => _exportVideo(context),
                  ),
                ],
              ],
            ),
            body: LoadingOverlay(
              isLoading: controller.state == EditorState.loading ||
                         controller.state == EditorState.exporting,
              message: controller.state == EditorState.loading
                  ? 'Carregando vídeo...'
                  : 'Exportando vídeo...',
              child: _buildBody(controller),
            ),
          );
        },
      ),
    );
  }

  Widget _buildBody(EditorController controller) {
    if (controller.originalVideo == null) {
      return _buildEmptyState();
    }

    if (controller.state == EditorState.error) {
      return _buildErrorState(controller);
    }

    return _buildEditor(controller);
  }

  Widget _buildEmptyState() {
    return Center(
      child: SingleChildScrollView(  // ← ADICIONADO para evitar overflow
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 120,
              height: 120,
              decoration: BoxDecoration(
                color: Colors.white.withOpacity(0.05),
                shape: BoxShape.circle,
                border: Border.all(color: Colors.white.withOpacity(0.1)),
              ),
              child: const Icon(
                Icons.video_library,
                size: 48,
                color: Colors.white54,
              ),
            ),
            const SizedBox(height: 24),
            const Text(
              'Nenhum vídeo selecionado',
              style: TextStyle(
                color: Colors.white,
                fontSize: 18,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Escolha um vídeo da galeria para começar',
              style: TextStyle(
                color: Colors.white.withOpacity(0.6),
                fontSize: 14,
              ),
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _selectVideo,
              icon: const Icon(Icons.video_library),
              label: const Text('Selecionar Vídeo'),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.red,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(
                  horizontal: 24,
                  vertical: 12,
                ),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(30),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildErrorState(EditorController controller) {
    return Center(
      child: SingleChildScrollView(  // ← ADICIONADO
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(
              Icons.error_outline,
              size: 64,
              color: Colors.red,
            ),
            const SizedBox(height: 16),
            Text(
              'Erro ao carregar vídeo',
              style: TextStyle(
                color: Colors.white.withOpacity(0.8),
                fontSize: 18,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              controller.errorMessage ?? 'Erro desconhecido',
              style: TextStyle(
                color: Colors.white.withOpacity(0.6),
                fontSize: 14,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            ElevatedButton(
              onPressed: () => controller.reset(),
              child: const Text('Tentar Novamente'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEditor(EditorController controller) {
    return SafeArea(
      child: LayoutBuilder(  // ← NOVO: adapta ao espaço disponível
        builder: (context, constraints) {
          return SingleChildScrollView(  // ← ADICIONADO para evitar overflow
            padding: const EdgeInsets.all(16),
            child: ConstrainedBox(
              constraints: BoxConstraints(
                minHeight: constraints.maxHeight,
              ),
              child: IntrinsicHeight(  // ← Garante altura correta
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    // Preview do vídeo (tamanho flexível)
                    if (controller.videoController != null)
                      AspectRatio(
                        aspectRatio: controller.videoController!.value.aspectRatio,
                        child: EditorPreview(
                          controller: controller.videoController!,
                          isPlaying: _isPlaying,
                          onPlayPause: _togglePlayPause,
                          currentPosition: controller.videoController!.value.position,
                          totalDuration: controller.videoDuration,
                          formatDuration: controller.formatDuration,
                        ),
                      ),
                    
                    const SizedBox(height: 16),
                    
                    // Trimmer (corte)
                    if (controller.videoDuration > Duration.zero)
                      VideoTrimmer(
                        minValue: 0,
                        maxValue: 1,
                        values: controller.trimRange,
                        onChanged: (values) {
                          controller.updateTrim(values);
                          _pauseVideo();
                        },
                        currentDuration: controller.trimEnd - controller.trimStart,
                        totalDuration: controller.videoDuration,
                        formatDuration: controller.formatDuration,
                      ),
                    
                    const SizedBox(height: 16),
                    
                    // Informações e botão de exportar
                    if (controller.exportedFile != null)
                      _buildExportedInfo(controller),
                    
                    // Botão de exportar
                    if (controller.exportedFile == null && 
                        controller.state != EditorState.exporting)
                      SizedBox(
                        width: double.infinity,
                        child: ElevatedButton(
                          onPressed: () => _exportVideo(context),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.red,
                            foregroundColor: Colors.white,
                            padding: const EdgeInsets.symmetric(vertical: 16),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(30),
                            ),
                          ),
                          child: const Text(
                            'Exportar Vídeo',
                            style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildExportedInfo(EditorController controller) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.green.withOpacity(0.1),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.green.withOpacity(0.3)),
      ),
      child: Row(
        children: [
          const Icon(
            Icons.check_circle,
            color: Colors.green,
            size: 24,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Vídeo exportado com sucesso!',
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Salvo em: ${controller.exportedPath?.split('/').last}',
                  style: TextStyle(
                    color: Colors.white.withOpacity(0.6),
                    fontSize: 12,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          IconButton(
            onPressed: () {
              // Compartilhar
            },
            icon: const Icon(Icons.share, color: Colors.white),
          ),
        ],
      ),
    );
  }
}