package com.example.gravador_tela

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlin.math.max
import kotlin.math.min

class MainActivity : FlutterActivity() {

    companion object {
        const val EXTRA_START_FROM_BUBBLE = "START_FROM_BUBBLE"
    }

    private val CHANNEL = "screen_recorder"
    private val EVENT_CHANNEL = "screen_recorder_events"
    private val REQ_MEDIA_PROJECTION = 1001

    private var pendingOptions: Map<String, Any>? = null
    private var pendingResult: MethodChannel.Result? = null
    private var orientationListener: OrientationEventListener? = null

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
                        stopOrientationMonitoring()
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

                    "getCurrentOrientation" -> {
                        val orientation = resources.configuration.orientation
                        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
                        val (width, height) = getRealScreenPx()
                        
                        val map = HashMap<String, Any>()
                        map["isLandscape"] = isLandscape
                        map["width"] = width
                        map["height"] = height
                        map["rotation"] = getScreenRotation()
                        result.success(map)
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

    private fun getDimensionsForCurrentOrientation(): Triple<Int, Int, Boolean> {
        val (width, height) = getRealScreenPx()
        val rotation = getScreenRotation()
        val orientation = resources.configuration.orientation
        
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE ||
                rotation == 1 || rotation == 3 // 90° ou 270°
        
        return if (isLandscape) {
            // Em paisagem: largura deve ser maior que altura
            Triple(max(width, height), min(width, height), true)
        } else {
            // Em retrato: largura deve ser menor que altura
            Triple(min(width, height), max(width, height), false)
        }
    }

    private fun handleStartFromBubbleIntent(i: Intent?) {
        val startFromBubble = i?.getBooleanExtra(EXTRA_START_FROM_BUBBLE, false) == true
        if (!startFromBubble) return

        // usa a orientação ATUAL para definir as dimensões
        val (w, h, isLandscape) = getDimensionsForCurrentOrientation()

        // bitrate proporcional
        val bitrate = if (w * h >= 1920 * 1080) 12_000_000 else 8_000_000

        pendingOptions = mapOf(
            "width" to w,
            "height" to h,
            "bitrate" to bitrate,
            "fps" to 30,
            "recordMic" to true,
            "isLandscape" to isLandscape
        )
        pendingResult = null

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpm.createScreenCaptureIntent()
        startActivityForResult(intent, REQ_MEDIA_PROJECTION)
    }

    private fun startOrientationMonitoring() {
        if (orientationListener == null) {
            orientationListener = object : OrientationEventListener(this) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                    
                    // Detecta se é paisagem (entre 45-135 ou 225-315 graus)
                    val isLandscape = (orientation >= 45 && orientation < 135) || 
                                     (orientation >= 225 && orientation < 315)
                    
                    // Envia para o serviço quando a orientação muda
                    if (ScreenRecordService.state == ScreenRecordService.RecState.RECORDING) {
                        val intent = Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                            action = "ACTION_UPDATE_ORIENTATION"
                            putExtra("EXTRA_IS_LANDSCAPE", isLandscape)
                        }
                        startService(intent)
                    }
                }
            }
            orientationListener?.enable()
        }
    }

    private fun stopOrientationMonitoring() {
        orientationListener?.disable()
        orientationListener = null
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

            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordService.EXTRA_DATA_INTENT, data)
                putExtra(ScreenRecordService.EXTRA_WIDTH, opts["width"] as Int)
                putExtra(ScreenRecordService.EXTRA_HEIGHT, opts["height"] as Int)
                putExtra(ScreenRecordService.EXTRA_BITRATE, opts["bitrate"] as Int)
                putExtra(ScreenRecordService.EXTRA_FPS, opts["fps"] as Int)
                putExtra(ScreenRecordService.EXTRA_RECORD_MIC, opts["recordMic"] as Boolean)
                
                val isLandscape = opts["isLandscape"] as? Boolean ?: false
                putExtra(ScreenRecordService.EXTRA_IS_LANDSCAPE, isLandscape)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // Inicia o monitoramento de orientação
            startOrientationMonitoring()
            
            res?.success(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopOrientationMonitoring()
    }
}