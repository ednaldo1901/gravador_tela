package com.example.gravador_tela

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "screen_recorder"
    private val EVENTS = "screen_recorder_events"
    private val REQ_MEDIA_PROJ = 7001

    private var pendingStartArgs: Map<String, Any>? = null

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
                            "segments" to ScreenRecordService.segmentUris.toList()
                        )
                        result.success(map)
                    }

                    else -> result.notImplemented()
                }
            }
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQ_MEDIA_PROJ) return

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
}