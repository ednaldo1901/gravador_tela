package com.example.gravador_tela

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.WindowManager
import io.flutter.plugin.common.EventChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ScreenRecordService : Service() {

    enum class RecState { IDLE, RECORDING, PAUSED, STOPPING }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA_INTENT = "EXTRA_DATA_INTENT"
        const val EXTRA_WIDTH = "EXTRA_WIDTH"
        const val EXTRA_HEIGHT = "EXTRA_HEIGHT"
        const val EXTRA_BITRATE = "EXTRA_BITRATE"
        const val EXTRA_FPS = "EXTRA_FPS"
        const val EXTRA_RECORD_MIC = "EXTRA_RECORD_MIC"

        private const val NOTIF_CHANNEL_ID = "screen_record_channel"
        private const val NOTIF_ID = 101

        @Volatile var lastOutputUriString: String? = null
        @Volatile var state: RecState = RecState.IDLE

        // EventChannel sink
        @Volatile var eventSink: EventChannel.EventSink? = null

        // Timer
        @Volatile var startTime = 0L
        @Volatile var pauseOffset = 0L

        fun getElapsedForFlutter(): Long {
            return when (state) {
                RecState.RECORDING -> SystemClock.elapsedRealtime() - startTime + pauseOffset
                RecState.PAUSED -> pauseOffset
                else -> 0L
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null

    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null

    private var currentWidth = 0
    private var currentHeight = 0
    private var isLandscape = false
    private var isRecording = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupOrientationListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun sendEvent(type: String) {
        val map = HashMap<String, Any?>()
        map["type"] = type
        map["state"] = state.name.lowercase()
        map["lastUri"] = lastOutputUriString
        map["elapsed"] = getElapsedForFlutter()
        eventSink?.success(map)
    }

    private fun setupOrientationListener() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY && isRecording) {
                    checkOrientationAndRestartIfNeeded()
                }
            }
        }, handler)
    }

    // ✅ Verifica se a orientação mudou e reinicia a gravação se necessário
    private fun checkOrientationAndRestartIfNeeded() {
        val newOrientation = resources.configuration.orientation
        val newIsLandscape = newOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        if (newIsLandscape != isLandscape && state == RecState.RECORDING) {
            Log.d("REC", "🔄 Orientação mudou! Reiniciando gravação...")
            
            // Pausa temporariamente
            val wasRecording = true
            pauseRecording()
            
            // Pega novas dimensões
            val (newWidth, newHeight) = getCurrentScreenDimensions()
            
            // Reinicia com as novas dimensões
            handler.postDelayed({
                if (wasRecording) {
                    restartRecordingWithNewDimensions(newWidth, newHeight, newIsLandscape)
                }
            }, 500)
        }
    }

    private fun getCurrentScreenDimensions(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val point = Point()
        display.getRealSize(point)
        
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Pair(max(point.x, point.y), min(point.x, point.y))
        } else {
            Pair(min(point.x, point.y), max(point.x, point.y))
        }
    }

    // ✅ Reinicia a gravação com novas dimensões
    private fun restartRecordingWithNewDimensions(newWidth: Int, newHeight: Int, newIsLandscape: Boolean) {
        try {
            // Para a gravação atual
            stopRecording()
            
            // Aguarda um pouco
            Thread.sleep(300)
            
            // Reinicia com as novas dimensões
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, lastResultCode)
                putExtra(EXTRA_DATA_INTENT, lastDataIntent)
                putExtra(EXTRA_WIDTH, newWidth)
                putExtra(EXTRA_HEIGHT, newHeight)
                putExtra(EXTRA_BITRATE, lastBitrate)
                putExtra(EXTRA_FPS, lastFps)
                putExtra(EXTRA_RECORD_MIC, lastRecordMic)
                putExtra("EXTRA_IS_LANDSCAPE", newIsLandscape)
            }
            
            startService(intent)
            Log.d("REC", "✅ Gravação reiniciada com dimensões: ${newWidth}x${newHeight}")
        } catch (e: Exception) {
            Log.e("REC", "❌ Erro ao reiniciar gravação: ${e.message}")
        }
    }

    private var lastResultCode = 0
    private var lastDataIntent: Intent? = null
    private var lastBitrate = 8_000_000
    private var lastFps = 30
    private var lastRecordMic = true

    private fun startRecording(intent: Intent) {
        if (state == RecState.RECORDING || state == RecState.PAUSED) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val dataIntent: Intent? =
            if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA_INTENT)

        if (dataIntent == null || resultCode != Activity.RESULT_OK) {
            stopSelf()
            return
        }

        // Salva para possível reinicialização
        lastResultCode = resultCode
        lastDataIntent = dataIntent
        lastBitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)
        lastFps = intent.getIntExtra(EXTRA_FPS, 30)
        lastRecordMic = intent.getBooleanExtra(EXTRA_RECORD_MIC, true)

        currentWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
        currentHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
        isLandscape = intent.getBooleanExtra("EXTRA_IS_LANDSCAPE", false)
        
        Log.d("REC", "📱 Iniciando gravação: ${currentWidth}x${currentHeight} | Landscape: $isLandscape")

        val (uri, pfd) = createMediaStoreOutput()
        outputUri = uri
        outputPfd = pfd
        lastOutputUriString = uri.toString()

        state = RecState.RECORDING
        isRecording = true
        startTime = SystemClock.elapsedRealtime()
        pauseOffset = 0L

        startForeground(NOTIF_ID, buildNotification(state))

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, dataIntent)

        recorder = MediaRecorder().apply {
            if (lastRecordMic) setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)

            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputPfd!!.fileDescriptor)

            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(lastBitrate)
            setVideoFrameRate(lastFps)
            setVideoSize(currentWidth, currentHeight)

            if (lastRecordMic) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(192_000)
                setAudioSamplingRate(48_000)
                setAudioChannels(1)
            }
            prepare()
        }

        val surface = recorder!!.surface
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenRecorderDisplay",
            currentWidth,
            currentHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        recorder!!.start()
        Log.d("REC", "✅ Gravando... Dimensões: ${currentWidth}x${currentHeight}")
        sendEvent("start")
        notifyUpdateNotification()
    }

    private fun pauseRecording() {
        if (state != RecState.RECORDING) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            recorder?.pause()
            pauseOffset += SystemClock.elapsedRealtime() - startTime
            state = RecState.PAUSED
            Log.d("REC", "⏸ Pausado.")
            sendEvent("pause")
            notifyUpdateNotification()
        } catch (e: Exception) {
            Log.d("REC", "❌ Erro pause: ${e.message}")
        }
    }

    private fun resumeRecording() {
        if (state != RecState.PAUSED) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            recorder?.resume()
            startTime = SystemClock.elapsedRealtime()
            state = RecState.RECORDING
            Log.d("REC", "▶️ Retomado.")
            sendEvent("resume")
            notifyUpdateNotification()
        } catch (e: Exception) {
            Log.d("REC", "❌ Erro resume: ${e.message}")
        }
    }

    private fun stopRecording() {
        if (state == RecState.IDLE) return

        Log.d("REC", "⏹ Parando gravação...")
        state = RecState.STOPPING
        isRecording = false
        notifyUpdateNotification()

        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.d("REC", "⚠️ recorder.stop falhou: ${e.message}")
        } finally {
            recorder?.release()
            recorder = null

            virtualDisplay?.release()
            virtualDisplay = null

            mediaProjection?.stop()
            mediaProjection = null
        }

        try { outputPfd?.close() } catch (_: Exception) {}
        outputPfd = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputUri?.let { uri ->
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, values, null, null)
            }
        }

        Log.d("REC", "📁 Finalizado URI=$outputUri")

        outputUri = null
        state = RecState.IDLE

        sendEvent("stop")
        stopForeground(true)
        stopSelf()
    }

    private fun notifyUpdateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(state))
    }

    private fun buildNotification(current: RecState): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 2001, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val pauseIntent = Intent(this, ScreenRecordService::class.java).apply { action = ACTION_PAUSE }
        val pausePending = PendingIntent.getService(
            this, 2002, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val resumeIntent = Intent(this, ScreenRecordService::class.java).apply { action = ACTION_RESUME }
        val resumePending = PendingIntent.getService(
            this, 2003, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openAppPending = PendingIntent.getActivity(
            this, 2004, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, NOTIF_CHANNEL_ID)
            else
                Notification.Builder(this)

        val text = when (current) {
            RecState.RECORDING -> "Gravando... ${currentWidth}x${currentHeight}"
            RecState.PAUSED -> "Pausado"
            RecState.STOPPING -> "Finalizando..."
            RecState.IDLE -> "Pronto"
        }

        builder
            .setContentTitle("Gravador de Tela")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(current == RecState.RECORDING || current == RecState.PAUSED || current == RecState.STOPPING)
            .setContentIntent(openAppPending)

        if (current == RecState.RECORDING) {
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Pausar",
                    pausePending
                ).build()
            )
        } else if (current == RecState.PAUSED) {
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_play,
                    "Retomar",
                    resumePending
                ).build()
            )
        }

        if (current == RecState.RECORDING || current == RecState.PAUSED || current == RecState.STOPPING) {
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Parar",
                    stopPending
                ).build()
            )
        }

        return builder.build()
    }

    private fun createMediaStoreOutput(): Pair<Uri, ParcelFileDescriptor> {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "record_$time.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecords")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Falha ao criar no MediaStore")

        val pfd = contentResolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("Falha ao abrir FileDescriptor do MediaStore")

        return Pair(uri, pfd)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Gravação de tela",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}