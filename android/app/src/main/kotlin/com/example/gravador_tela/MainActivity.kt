package com.example.gravador_tela

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        const val EXTRA_START_FROM_BUBBLE = "START_FROM_BUBBLE"
        const val ORIENTATION_MODE_AUTO = 0
        const val ORIENTATION_MODE_PORTRAIT = 1
        const val ORIENTATION_MODE_LANDSCAPE = 2
        const val ORIENTATION_MODE_SQUARE = 3
    }

    private val CHANNEL = "screen_recorder"
    private val EVENT_CHANNEL = "screen_recorder_events"
    private val REQ_MEDIA_PROJECTION = 1001

    private var pendingOptions: Map<String, Any>? = null
    private var pendingResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {

                    "startRecording" -> {
                        pendingOptions = call.arguments as Map<String, Any>
                        pendingResult = result

                        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val intent = mpm.createScreenCaptureIntent()
                        startActivityForResult(intent, REQ_MEDIA_PROJECTION)
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
                        val map = HashMap<String, Any?>()
                        map["state"] = ScreenRecordService.state.name.lowercase()
                        map["lastUri"] = ScreenRecordService.lastOutputUriString
                        map["elapsed"] = ScreenRecordService.getElapsedForFlutter()
                        result.success(map)
                    }

                    "hasOverlayPermission" -> result.success(Settings.canDrawOverlays(this))

                    "openOverlaySettings" -> {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                        result.success(null)
                    }

                    "showBubble" -> {
                        startService(Intent(this, OverlayBubbleService::class.java).apply {
                            action = OverlayBubbleService.ACTION_SHOW
                        })
                        result.success(null)
                    }

                    "hideBubble" -> {
                        startService(Intent(this, OverlayBubbleService::class.java).apply {
                            action = OverlayBubbleService.ACTION_HIDE
                        })
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    ScreenRecordService.eventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    ScreenRecordService.eventSink = null
                }
            })

        handleStartFromBubbleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStartFromBubbleIntent(intent)
    }

    private fun getRealScreenPx(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val p = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(p)
            Pair(p.x, p.y)
        }
    }

    private fun getScreenRotation(): Int {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return wm.defaultDisplay.rotation
    }

    private fun handleStartFromBubbleIntent(i: Intent?) {
        val startFromBubble = i?.getBooleanExtra(EXTRA_START_FROM_BUBBLE, false) == true
        if (!startFromBubble) return

        // Modo AUTO por padrão quando vem da bolha
        pendingOptions = mapOf(
            "orientationMode" to ORIENTATION_MODE_AUTO,
            "fps" to 30,
            "bitrate" to 8_000_000,
            "recordMic" to true
        )
        pendingResult = null

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpm.createScreenCaptureIntent()
        startActivityForResult(intent, REQ_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_MEDIA_PROJECTION) {
            val opts = pendingOptions
            val res = pendingResult

            pendingOptions = null
            pendingResult = null

            if (resultCode != Activity.RESULT_OK || data == null || opts == null) {
                res?.error("PERMISSION", "Negado", null)
                return
            }

            // Pega o modo de orientação escolhido pelo usuário
            val orientationMode = opts["orientationMode"] as Int? ?: ORIENTATION_MODE_AUTO
            
            // Calcula as dimensões baseado no modo escolhido
            val (width, height) = calculateDimensions(orientationMode)
            
            // Bitrate
            val bitrate = opts["bitrate"] as Int? ?: 8_000_000
            val fps = opts["fps"] as Int? ?: 30
            val recordMic = opts["recordMic"] as Boolean? ?: true

            android.util.Log.d("MAIN", "📱 Modo: $orientationMode | Dimensões: ${width}x${height}")

            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordService.EXTRA_DATA_INTENT, data)
                putExtra(ScreenRecordService.EXTRA_WIDTH, width)
                putExtra(ScreenRecordService.EXTRA_HEIGHT, height)
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
            
            res?.success(null)
        }
    }

    private fun calculateDimensions(mode: Int): Pair<Int, Int> {
        val (realW, realH) = getRealScreenPx()
        
        return when (mode) {
            ORIENTATION_MODE_PORTRAIT -> {
                // Força retrato: menor largura, maior altura
                Pair(minOf(realW, realH), maxOf(realW, realH))
            }
            ORIENTATION_MODE_LANDSCAPE -> {
                // Força paisagem: maior largura, menor altura
                Pair(maxOf(realW, realH), minOf(realW, realH))
            }
            ORIENTATION_MODE_SQUARE -> {
                // Modo quadrado: 1080x1080 (ou o máximo possível mantendo quadrado)
                val size = minOf(realW, realH, 1080)
                Pair(size, size)
            }
            else -> { // AUTO
                // Usa a orientação atual do dispositivo
                val rotation = getScreenRotation()
                val isLandscape = rotation == 1 || rotation == 3
                if (isLandscape) {
                    Pair(maxOf(realW, realH), minOf(realW, realH))
                } else {
                    Pair(minOf(realW, realH), maxOf(realW, realH))
                }
            }
        }
    }

    private fun minOf(a: Int, b: Int): Int = if (a < b) a else b
    private fun maxOf(a: Int, b: Int): Int = if (a > b) a else b
}