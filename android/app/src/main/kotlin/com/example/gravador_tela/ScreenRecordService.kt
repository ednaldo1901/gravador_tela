package com.example.gravador_tela

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import io.flutter.plugin.common.EventChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        const val EXTRA_ORIENTATION_MODE = "EXTRA_ORIENTATION_MODE"

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
    private var orientationMode = 0 // AUTO = 0, PORTRAIT = 1, LANDSCAPE = 2, SQUARE = 3
    private var isRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // NÃO configuramos mais o listener de orientação
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

        // Pega as dimensões e modo escolhido pelo usuário
        currentWidth = intent.getIntExtra(EXTRA_WIDTH, 720)
        currentHeight = intent.getIntExtra(EXTRA_HEIGHT, 1280)
        orientationMode = intent.getIntExtra(EXTRA_ORIENTATION_MODE, 0) // 0 = AUTO
        
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        val recordMic = intent.getBooleanExtra(EXTRA_RECORD_MIC, true)

        val modeName = when (orientationMode) {
            1 -> "RETRATO"
            2 -> "PAISAGEM"
            3 -> "QUADRADO"
            else -> "AUTO"
        }
        
        Log.d("REC", "📱 Iniciando gravação: ${currentWidth}x${currentHeight} | Modo: $modeName")

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

        try {
            recorder = MediaRecorder().apply {
                if (recordMic) setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)

                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputPfd!!.fileDescriptor)

                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(bitrate)
                setVideoFrameRate(fps)
                setVideoSize(currentWidth, currentHeight)

                if (recordMic) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(192_000)
                    setAudioSamplingRate(48_000)
                    setAudioChannels(1)
                }
                prepare()
                Log.d("REC", "✅ MediaRecorder preparado")
            }
        } catch (e: Exception) {
            Log.e("REC", "❌ Erro ao configurar MediaRecorder: ${e.message}")
            stopSelf()
            return
        }

        try {
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
            Log.d("REC", "✅ VirtualDisplay criado")
        } catch (e: Exception) {
            Log.e("REC", "❌ Erro ao criar VirtualDisplay: ${e.message}")
            stopSelf()
            return
        }

        try {
            recorder!!.start()
            Log.d("REC", "✅ Gravação iniciada")
            sendEvent("start")
            notifyUpdateNotification()
        } catch (e: Exception) {
            Log.e("REC", "❌ Erro ao iniciar gravação: ${e.message}")
            stopSelf()
        }
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
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null

            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null

            try { mediaProjection?.stop() } catch (_: Exception) {}
            mediaProjection = null
        }

        try { outputPfd?.close() } catch (_: Exception) {}
        outputPfd = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputUri?.let { uri ->
                try {
                    val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, values, null, null)
                } catch (_: Exception) {}
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
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(state))
        } catch (_: Exception) {}
    }

    private fun buildNotification(current: RecState): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 2001, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, ScreenRecordService::class.java).apply { action = ACTION_PAUSE }
        val pausePending = PendingIntent.getService(
            this, 2002, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(this, ScreenRecordService::class.java).apply { action = ACTION_RESUME }
        val resumePending = PendingIntent.getService(
            this, 2003, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openAppPending = PendingIntent.getActivity(
            this, 2004, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, NOTIF_CHANNEL_ID)
            else
                Notification.Builder(this)

        val modoText = when (orientationMode) {
            1 -> "📱 Retrato"
            2 -> "🌍 Paisagem"
            3 -> "⬛ Quadrado"
            else -> "🔄 Auto"
        }

        val text = when (current) {
            RecState.RECORDING -> "Gravando $modoText ${currentWidth}x${currentHeight}"
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
            builder.addAction(0, "Pausar", pausePending)
        } else if (current == RecState.PAUSED) {
            builder.addAction(0, "Retomar", resumePending)
        }

        if (current == RecState.RECORDING || current == RecState.PAUSED || current == RecState.STOPPING) {
            builder.addAction(0, "Parar", stopPending)
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