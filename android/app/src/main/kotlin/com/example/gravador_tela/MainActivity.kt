package com.example.gravador_tela

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit  // ← IMPORTANTE: Usar FFprobeKit para obter informações
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.StreamInformation

class MainActivity : FlutterActivity() {

    private val CHANNEL = "screen_recorder"
    private val EVENTS = "screen_recorder_events"
    private val REQ_MEDIA_PROJ = 7001
    private val REQ_OVERLAY_PERM = 7002

    private var pendingStartArgs: Map<String, Any>? = null

    companion object {
        const val EXTRA_START_FROM_BUBBLE = "EXTRA_START_FROM_BUBBLE"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENTS)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    ScreenRecordService.eventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    ScreenRecordService.eventSink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startRecording" -> {
                        @Suppress("UNCHECKED_CAST")
                        pendingStartArgs = call.arguments as Map<String, Any>
                        requestMediaProjection()
                        result.success(null)
                    }

                    "stopRecording" -> {
                        startService(Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_STOP
                        })
                        result.success(null)
                    }

                    "pauseRecording" -> {
                        startService(Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_PAUSE
                        })
                        result.success(null)
                    }

                    "resumeRecording" -> {
                        startService(Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_RESUME
                        })
                        result.success(null)
                    }

                    "getStatus" -> {
                        val map = hashMapOf<String, Any?>(
                            "state" to ScreenRecordService.state.name.lowercase(),
                            "lastUri" to ScreenRecordService.lastOutputUriString,
                            "finalUri" to ScreenRecordService.finalOutputUriString,
                            "elapsed" to ScreenRecordService.getElapsedForFlutter(),
                            "segments" to ScreenRecordService.segmentUris.toList(),
                            "gameConfirmationStatus" to ScreenRecordService.gameConfirmationStatus?.name
                        )
                        result.success(map)
                    }

                    "hasOverlayPermission" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            result.success(Settings.canDrawOverlays(this@MainActivity))
                        } else {
                            result.success(true)
                        }
                    }

                    "openOverlaySettings" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            intent.data = Uri.parse("package:$packageName")
                            startActivityForResult(intent, REQ_OVERLAY_PERM)
                        }
                        result.success(null)
                    }

                    "hasUsageStatsPermission" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                            val mode = appOps.checkOpNoThrow(
                                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                                android.os.Process.myUid(),
                                packageName
                            )
                            result.success(mode == android.app.AppOpsManager.MODE_ALLOWED)
                        } else {
                            result.success(false)
                        }
                    }

                    "openUsageStatsSettings" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            startActivity(intent)
                        }
                        result.success(null)
                    }

                    "showBubble" -> {
                        if (!isServiceRunning(OverlayBubbleService::class.java)) {
                            startService(Intent(this, OverlayBubbleService::class.java).apply {
                                action = OverlayBubbleService.ACTION_SHOW
                            })
                        }
                        result.success(null)
                    }

                    "hideBubble" -> {
                        startService(Intent(this, OverlayBubbleService::class.java).apply {
                            action = OverlayBubbleService.ACTION_HIDE
                        })
                        result.success(null)
                    }

                    // MÉTODOS DO EDITOR
                    "trimVideo" -> {
                        val inputPath = call.argument<String>("inputPath") ?: ""
                        val outputPath = call.argument<String>("outputPath") ?: ""
                        val startSeconds = call.argument<Double>("startSeconds") ?: 0.0
                        val durationSeconds = call.argument<Double>("durationSeconds") ?: 0.0
                        
                        val success = trimVideo(inputPath, outputPath, startSeconds, durationSeconds)
                        val map = hashMapOf<String, Any?>(
                            "success" to success,
                            "outputPath" to outputPath
                        )
                        result.success(map)
                    }

                    "getVideoInfo" -> {
                        val path = call.argument<String>("path") ?: ""
                        val info = getVideoInfo(path)
                        result.success(info)
                    }

                    "checkFFmpeg" -> {
                        result.success(true)
                    }

                    else -> result.notImplemented()
                }
            }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQ_MEDIA_PROJ -> handleMediaProjectionResult(resultCode, data)
            REQ_OVERLAY_PERM -> {
                // Nada a fazer
            }
        }
    }

    private fun handleMediaProjectionResult(resultCode: Int, data: Intent?) {
        val args = pendingStartArgs ?: return
        pendingStartArgs = null

        if (data == null || resultCode != Activity.RESULT_OK) return

        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START

            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA_INTENT, data)

            val bitrate = (args["bitrate"] as Number).toInt()
            val fps = (args["fps"] as Number).toInt()
            val recordMic = (args["recordMic"] as Boolean)
            val orientationMode = (args["orientationMode"] as Number).toInt()

            putExtra(ScreenRecordService.EXTRA_BITRATE, bitrate)
            putExtra(ScreenRecordService.EXTRA_FPS, fps)
            putExtra(ScreenRecordService.EXTRA_RECORD_MIC, recordMic)
            putExtra(ScreenRecordService.EXTRA_ORIENTATION_MODE, orientationMode)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // MÉTODO CORRIGIDO: trimVideo
    private fun trimVideo(inputPath: String, outputPath: String, startSeconds: Double, durationSeconds: Double): Boolean {
        return try {
            Log.d("FFmpeg", "Cortando vídeo: $inputPath -> $outputPath")
            Log.d("FFmpeg", "Início: ${startSeconds}s, Duração: ${durationSeconds}s")
            
            val cmd = "-y -i $inputPath -ss $startSeconds -t $durationSeconds -c copy $outputPath"
            val session = FFmpegKit.execute(cmd)
            val success = ReturnCode.isSuccess(session.returnCode)
            
            if (success) {
                Log.d("FFmpeg", "✅ Vídeo cortado com sucesso: $outputPath")
            } else {
                Log.e("FFmpeg", "❌ Falha ao cortar vídeo: ${session.output}")
            }
            
            success
        } catch (e: Exception) {
            Log.e("FFmpeg", "Erro ao cortar vídeo: ${e.message}")
            false
        }
    }

    // MÉTODO CORRIGIDO: getVideoInfo usando FFprobeKit
    private fun getVideoInfo(path: String): Map<String, Any>? {
        return try {
            Log.d("FFmpeg", "Obtendo informações do vídeo: $path")
            
            // CORREÇÃO: Usar FFprobeKit em vez de FFmpegKit.getMediaInformation
            val session = FFprobeKit.getMediaInformation(path)
            val mediaInfo = session.mediaInformation
            
            if (mediaInfo == null) {
                Log.e("FFmpeg", "Não foi possível obter informações do vídeo")
                return null
            }
            
            val streams = mediaInfo.streams
            val stream = streams?.firstOrNull()
            
            val info = mapOf(
                "duration" to (mediaInfo.duration?.toLong() ?: 0),
                "width" to (stream?.width ?: 0),
                "height" to (stream?.height ?: 0),
                "bitrate" to (mediaInfo.bitrate?.toLong() ?: 0),
                "codec" to (stream?.codec ?: "")
            )
            
            Log.d("FFmpeg", "Informações obtidas: $info")
            info
        } catch (e: Exception) {
            Log.e("FFmpeg", "Erro ao obter informações: ${e.message}")
            null
        }
    }
}