package com.example.gravador_tela

import android.app.*
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import io.flutter.plugin.common.EventChannel
import java.io.File
import java.io.FileOutputStream
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
        const val EXTRA_BITRATE = "EXTRA_BITRATE"
        const val EXTRA_FPS = "EXTRA_FPS"
        const val EXTRA_RECORD_MIC = "EXTRA_RECORD_MIC"
        const val EXTRA_ORIENTATION_MODE = "EXTRA_ORIENTATION_MODE"

        private const val NOTIF_CHANNEL_ID = "screen_record_channel"
        private const val NOTIF_ID = 101

        @Volatile var state: RecState = RecState.IDLE
        @Volatile var lastOutputUriString: String? = null
        @Volatile var finalOutputUriString: String? = null
        @Volatile var segmentUris: MutableList<String> = mutableListOf()
        
        // NOVO: Armazenar metadados dos segmentos
        @Volatile var segmentMetadata: MutableList<SegmentMetadata> = mutableListOf()
        @Volatile var eventSink: EventChannel.EventSink? = null

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

    // NOVO: Classe para metadados dos segmentos
    data class SegmentMetadata(
        val index: Int,
        val width: Int,
        val height: Int,
        val isGameMode: Boolean,
        val rotation: Int,
        val uri: String
    )

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null

    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null

    private var bitrate = 8_000_000
    private var fps = 30
    private var recordMic = true
    private var orientationMode = 0

    private var segmentIndex = 0
    private var currentRotation = Surface.ROTATION_0
    private var lastRotationChangeAt = 0L
    
    // Detecção de modo de jogo
    private var isGameMode = false
    private var forcedLandscapeForGame = false
    
    // NOVO: Resolução alvo para merge (prioriza jogo)
    private var targetWidth = 0
    private var targetHeight = 0
    private var hasGameSegment = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
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

    private fun sendEvent(type: String, extra: Map<String, Any?> = emptyMap()) {
        val map = hashMapOf<String, Any?>(
            "type" to type,
            "state" to state.name.lowercase(),
            "lastUri" to lastOutputUriString,
            "finalUri" to finalOutputUriString,
            "elapsed" to getElapsedForFlutter(),
            "segments" to segmentUris.toList()
        )
        map.putAll(extra)
        try { eventSink?.success(map) } catch (_: Exception) {}
    }

    private fun startRecording(intent: Intent) {
        if (state == RecState.RECORDING || state == RecState.PAUSED) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (dataIntent == null || resultCode != Activity.RESULT_OK) {
            Log.d("REC", "❌ Sem permissão MediaProjection.")
            stopSelf()
            return
        }

        bitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)
        fps = intent.getIntExtra(EXTRA_FPS, 30)
        recordMic = intent.getBooleanExtra(EXTRA_RECORD_MIC, true)
        orientationMode = intent.getIntExtra(EXTRA_ORIENTATION_MODE, 0)

        // Reset
        isGameMode = false
        forcedLandscapeForGame = false
        hasGameSegment = false
        targetWidth = 0
        targetHeight = 0
        segmentMetadata.clear()

        state = RecState.RECORDING
        startForegroundServiceWithType()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, dataIntent)

        segmentIndex = 0
        segmentUris = mutableListOf()
        lastOutputUriString = null
        finalOutputUriString = null

        pauseOffset = 0L
        startTime = SystemClock.elapsedRealtime()

        currentRotation = getDefaultDisplayRotation()
        
        detectGameMode()

        startNewSegmentForRotation(currentRotation, reason = "start", isInitialSegment = true)

        if (orientationMode == 0) registerRotationListener() else unregisterRotationListener()

        sendEvent("start")
        notifyUpdateNotification()
    }

    private fun detectGameMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val runningTasks = activityManager.getRunningTasks(1)
                if (runningTasks.isNotEmpty()) {
                    val topActivity = runningTasks[0].topActivity
                    val packageName = topActivity?.packageName
                    
                    if (packageName != null) {
                        val packageManager = packageManager
                        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
                        
                        val isGame = applicationInfo.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
                        
                        if (isGame) {
                            Log.d("REC", "🎮 Modo jogo detectado: $packageName")
                            isGameMode = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("REC", "Erro ao detectar modo jogo: ${e.message}")
            }
        }
    }

    private fun checkForGameMode() {
        if (isGameMode) return
        
        mainHandler.postDelayed({
            if (state == RecState.RECORDING && !isGameMode) {
                detectGameMode()
                checkForGameMode()
            }
        }, 2000)
    }

    private fun startForegroundServiceWithType() {
        val notification = buildNotification(state)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundServiceType = if (recordMic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            
            startForeground(NOTIF_ID, notification, foregroundServiceType)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun registerRotationListener() {
        if (displayListener != null) return

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (state != RecState.RECORDING) return
                
                val now = SystemClock.elapsedRealtime()
                if (now - lastRotationChangeAt < 800) return
                lastRotationChangeAt = now

                val rot = getDefaultDisplayRotation()
                
                if (isGameMode) {
                    val isLandscape = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270)
                    
                    if (isLandscape && !forcedLandscapeForGame) {
                        forcedLandscapeForGame = true
                        currentRotation = rot
                        Log.d("REC", "🎮 Jogo em paisagem - criando segmento especial")
                        restartSegmentForRotation(rot, isGameForced = true)
                    } else if (!isLandscape && forcedLandscapeForGame) {
                        forcedLandscapeForGame = false
                        currentRotation = rot
                        restartSegmentForRotation(rot, isGameForced = true)
                    }
                    return
                }
                
                if (rot == currentRotation) return
                currentRotation = rot
                restartSegmentForRotation(rot, isGameForced = false)
            }
        }

        displayListener = listener
        displayManager?.registerDisplayListener(listener, mainHandler)
        
        checkForGameMode()
    }

    private fun unregisterRotationListener() {
        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        displayListener = null
    }

    private fun restartSegmentForRotation(rot: Int, isGameForced: Boolean) {
        if (state != RecState.RECORDING) return
        stopCurrentRecorderOnly()
        startNewSegmentForRotation(rot, if (isGameForced) "game_rotate" else "rotate", isInitialSegment = false)
    }

    private fun startNewSegmentForRotation(rot: Int, reason: String, isInitialSegment: Boolean) {
        val (realW, realH, dpi) = getRealMetrics()
        val isLandscape = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270)

        val (w, h) = when {
            isGameMode && isLandscape -> {
                val gameW = max(realW, realH)
                val gameH = min(realW, realH)
                Log.d("REC", "🎮 Dimensões otimizadas para jogo: ${gameW}x${gameH}")
                
                // NOVO: Define resolução alvo baseada no jogo
                if (!hasGameSegment) {
                    targetWidth = gameW
                    targetHeight = gameH
                    hasGameSegment = true
                    Log.d("REC", "🎯 Resolução alvo definida pelo jogo: ${targetWidth}x${targetHeight}")
                }
                
                Pair(gameW, gameH)
            }
            
            orientationMode == 1 -> // Portrait
                Pair(min(realW, realH), max(realW, realH))
            orientationMode == 2 -> // Landscape
                Pair(max(realW, realH), min(realW, realH))
            orientationMode == 3 -> { // Square
                val s = min(realW, realH)
                Pair(s, s)
            }
            else -> { // Auto
                if (isLandscape) Pair(max(realW, realH), min(realW, realH))
                else Pair(min(realW, realH), max(realW, realH))
            }
        }

        segmentIndex += 1
        val (uri, pfd) = createMediaStoreOutput(segmentIndex)
        outputUri = uri
        outputPfd = pfd
        lastOutputUriString = uri.toString()

        // NOVO: Salvar metadados do segmento
        val metadata = SegmentMetadata(
            index = segmentIndex,
            width = w,
            height = h,
            isGameMode = isGameMode,
            rotation = rot,
            uri = uri.toString()
        )
        segmentMetadata.add(metadata)

        Log.d("REC", "🎞️ Segment#$segmentIndex reason=$reason -> ${w}x${h} uri=$uri gameMode=$isGameMode")

        recorder = MediaRecorder().apply {
            if (recordMic) setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputPfd!!.fileDescriptor)

            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(fps)
            setVideoSize(w, h)

            if (recordMic) {
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
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        recorder!!.start()
        sendEvent("segment", mapOf(
            "segmentIndex" to segmentIndex,
            "isGameMode" to isGameMode,
            "width" to w,
            "height" to h
        ))
        notifyUpdateNotification()
    }

    private fun stopCurrentRecorderOnly() {
        val finishedUri = outputUri

        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.d("REC", "⚠️ stop segment falhou: ${e.message}")
        } finally {
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
        }

        finalizeMediaStoreUri(finishedUri)
        try { outputPfd?.close() } catch (_: Exception) {}
        outputPfd = null
        outputUri = null

        finishedUri?.let { segmentUris.add(it.toString()) }
    }

    private fun finalizeMediaStoreUri(uri: Uri?) {
        if (uri == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, values, null, null)
            } catch (_: Exception) {}
        }
    }

    private fun pauseRecording() {
        if (state != RecState.RECORDING) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            recorder?.pause()
            pauseOffset += SystemClock.elapsedRealtime() - startTime
            state = RecState.PAUSED
            sendEvent("pause")
            notifyUpdateNotification()
        } catch (e: Exception) {
            Log.d("REC", "❌ pause: ${e.message}")
        }
    }

    private fun resumeRecording() {
        if (state != RecState.PAUSED) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            recorder?.resume()
            startTime = SystemClock.elapsedRealtime()
            state = RecState.RECORDING
            sendEvent("resume")
            notifyUpdateNotification()
        } catch (e: Exception) {
            Log.d("REC", "❌ resume: ${e.message}")
        }
    }

    private fun stopRecording() {
        if (state == RecState.IDLE) return

        unregisterRotationListener()

        state = RecState.STOPPING
        sendEvent("stopping")
        notifyUpdateNotification()

        stopCurrentRecorderOnly()

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        Thread {
            val finalUri = mergeSegmentsWithGamePriority()
            finalOutputUriString = finalUri?.toString()
            lastOutputUriString = finalOutputUriString

            state = RecState.IDLE
            sendEvent("stop")
            stopForeground(true)
            stopSelf()
        }.start()
    }

    // NOVO: Merge priorizando resolução do jogo (CORRIGIDO)
    private fun mergeSegmentsWithGamePriority(): Uri? {
        val seg = segmentUris.toList()
        if (seg.isEmpty()) return null
        if (seg.size == 1) return Uri.parse(seg.first())

        // Se não tem segmento de jogo, usa merge normal
        if (!hasGameSegment || targetWidth == 0 || targetHeight == 0) {
            return mergeSegmentsCopyFirstFallback()
        }

        Log.d("REC", "🎯 Merge priorizando jogo - resolução alvo: ${targetWidth}x${targetHeight}")

        val workDir = File(cacheDir, "ffmerge_game")
        if (!workDir.exists()) workDir.mkdirs()

        val segFiles = mutableListOf<File>()
        try {
            seg.forEachIndexed { idx, uriStr ->
                val u = Uri.parse(uriStr)
                val f = File(workDir, "seg_${idx + 1}.mp4")
                copyUriToFile(contentResolver, u, f)
                segFiles.add(f)
            }
        } catch (e: Exception) {
            cleanup(workDir, segFiles, null, null)
            return Uri.parse(seg.last())
        }

        val listFile = File(workDir, "list.txt")
        listFile.writeText(buildString {
            segFiles.forEach { f ->
                append("file '")
                append(f.absolutePath.replace("'", "'\\''"))
                append("'\n")
            }
        })

        val outTmp = File(workDir, "merged_${System.currentTimeMillis()}.mp4")

        // CORREÇÃO: Construir o filtro complexo corretamente sem erro de escopo
        val complexFilter = StringBuilder()
        
        // Adiciona scale+pad para cada segmento
        for (i in 0 until segFiles.size) {
            complexFilter.append("[$i:v]scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2,setsar=1[v$i];")
        }
        
        // Concatena todos os segmentos processados
        val videoInputs = (0 until segFiles.size).joinToString("") { "[v$it]" }
        complexFilter.append("${videoInputs}concat=n=${segFiles.size}:v=1:a=1[v][a]")

        val cmdReencode = "-y -f concat -safe 0 -i ${listFile.absolutePath} " +
                "-filter_complex \"${complexFilter.toString()}\" " +
                "-map \"[v]\" -map \"[a]\" " +
                "-c:v libx264 -preset veryfast -crf 18 " +
                "-c:a aac -b:a 192k " +
                "${outTmp.absolutePath}"

        Log.d("REC", "Executando FFmpeg com prioridade para jogo")
        val result = FFmpegKit.execute(cmdReencode)

        if (!ReturnCode.isSuccess(result.returnCode)) {
            Log.d("REC", "⚠️ Merge com prioridade falhou, usando fallback")
            cleanup(workDir, segFiles, listFile, outTmp)
            return mergeSegmentsCopyFirstFallback()
        }

        val finalUri = createFinalMediaStoreOutput() ?: run {
            cleanup(workDir, segFiles, listFile, outTmp)
            return Uri.parse(seg.last())
        }

        try {
            contentResolver.openOutputStream(finalUri, "w")?.use { os ->
                outTmp.inputStream().use { it.copyTo(os) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(finalUri, values, null, null)
            }

            seg.forEach { uriStr ->
                try { contentResolver.delete(Uri.parse(uriStr), null, null) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            cleanup(workDir, segFiles, listFile, outTmp)
            return Uri.parse(seg.last())
        }

        cleanup(workDir, segFiles, listFile, outTmp)
        return finalUri
    }

    // Mantido como fallback
    private fun mergeSegmentsCopyFirstFallback(): Uri? {
        val seg = segmentUris.toList()
        if (seg.isEmpty()) return null
        if (seg.size == 1) return Uri.parse(seg.first())

        val workDir = File(cacheDir, "ffmerge_fallback")
        if (!workDir.exists()) workDir.mkdirs()

        val segFiles = mutableListOf<File>()
        try {
            seg.forEachIndexed { idx, uriStr ->
                val u = Uri.parse(uriStr)
                val f = File(workDir, "seg_${idx + 1}.mp4")
                copyUriToFile(contentResolver, u, f)
                segFiles.add(f)
            }
        } catch (e: Exception) {
            cleanup(workDir, segFiles, null, null)
            return Uri.parse(seg.last())
        }

        val listFile = File(workDir, "list.txt")
        listFile.writeText(buildString {
            segFiles.forEach { f ->
                append("file '")
                append(f.absolutePath.replace("'", "'\\''"))
                append("'\n")
            }
        })

        val outTmp = File(workDir, "merged_${System.currentTimeMillis()}.mp4")

        val cmdCopy = "-y -f concat -safe 0 -i ${listFile.absolutePath} -c copy ${outTmp.absolutePath}"
        val s1 = FFmpegKit.execute(cmdCopy)

        var mergedOk = ReturnCode.isSuccess(s1.returnCode)
        if (!mergedOk) {
            val cmdReencode = "-y -f concat -safe 0 -i ${listFile.absolutePath} " +
                    "-c:v libx264 -preset veryfast -crf 18 " +
                    "-c:a aac -b:a 192k " +
                    "${outTmp.absolutePath}"

            val s2 = FFmpegKit.execute(cmdReencode)
            mergedOk = ReturnCode.isSuccess(s2.returnCode)
            if (!mergedOk) {
                cleanup(workDir, segFiles, listFile, outTmp)
                return Uri.parse(seg.last())
            }
        }

        val finalUri = createFinalMediaStoreOutput() ?: run {
            cleanup(workDir, segFiles, listFile, outTmp)
            return Uri.parse(seg.last())
        }

        try {
            contentResolver.openOutputStream(finalUri, "w")?.use { os ->
                outTmp.inputStream().use { it.copyTo(os) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(finalUri, values, null, null)
            }

            seg.forEach { uriStr ->
                try { contentResolver.delete(Uri.parse(uriStr), null, null) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            cleanup(workDir, segFiles, listFile, outTmp)
            return Uri.parse(seg.last())
        }

        cleanup(workDir, segFiles, listFile, outTmp)
        return finalUri
    }

    private fun cleanup(dir: File, segFiles: List<File>, listFile: File?, outTmp: File?) {
        try { segFiles.forEach { it.delete() } } catch (_: Exception) {}
        try { listFile?.delete() } catch (_: Exception) {}
        try { outTmp?.delete() } catch (_: Exception) {}
    }

    private fun copyUriToFile(cr: ContentResolver, uri: Uri, outFile: File) {
        cr.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Falha ao abrir InputStream: $uri")
    }

    private fun createFinalMediaStoreOutput(): Uri? {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val gameTag = if (hasGameSegment) "_with_game" else ""
        val fileName = "record_${time}${gameTag}_final.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecords")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        return try {
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) {
            null
        }
    }

    private fun notifyUpdateNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(state))
        } catch (_: Exception) {}
    }

    private fun buildNotification(current: RecState): Notification {
        val stopPending = PendingIntent.getService(
            this, 2001,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val pausePending = PendingIntent.getService(
            this, 2002,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val resumePending = PendingIntent.getService(
            this, 2003,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openAppPending = PendingIntent.getActivity(
            this, 2004, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val gameSuffix = if (isGameMode) " 🎮" else if (hasGameSegment) " (jogo detectado)" else ""
        val text = when (current) {
            RecState.RECORDING -> "Gravando... (Segmento $segmentIndex)$gameSuffix"
            RecState.PAUSED -> "Pausado$gameSuffix"
            RecState.STOPPING -> "Finalizando...$gameSuffix"
            RecState.IDLE -> "Pronto"
        }

        builder
            .setContentTitle("Gravador de Tela")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(current == RecState.RECORDING || current == RecState.PAUSED || current == RecState.STOPPING)
            .setContentIntent(openAppPending)

        if (current == RecState.RECORDING) {
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "Pausar", pausePending
            ).build())
        } else if (current == RecState.PAUSED) {
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_play, "Retomar", resumePending
            ).build())
        }

        if (current == RecState.RECORDING || current == RecState.PAUSED || current == RecState.STOPPING) {
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPending
            ).build())
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Gravação de tela",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createMediaStoreOutput(part: Int): Pair<Uri, ParcelFileDescriptor> {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val gameTag = if (isGameMode) "_game" else ""
        val fileName = "record_${time}${gameTag}_p${part}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecords")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Falha ao criar no MediaStore")

        val pfd = contentResolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("Falha ao abrir FileDescriptor do MediaStore")

        return Pair(uri, pfd)
    }

    private fun getDefaultDisplayRotation(): Int {
        val dm = displayManager ?: return Surface.ROTATION_0
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return Surface.ROTATION_0
        return d.rotation
    }

    private fun getRealMetrics(): Triple<Int, Int, Int> {
        val dm = displayManager
        val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()

        if (d != null) d.getRealMetrics(m) else resources.displayMetrics.also { m.setTo(it) }
        return Triple(m.widthPixels, m.heightPixels, m.densityDpi)
    }
}