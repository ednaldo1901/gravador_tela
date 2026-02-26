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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    data class SegmentMetadata(
        val index: Int,
        val width: Int,
        val height: Int,
        val isGameMode: Boolean,
        val rotation: Int,
        val uri: String,
        val timestamp: Long = System.currentTimeMillis()
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
    @Volatile private var isGameMode = false
    @Volatile private var gamePackageName: String? = null
    @Volatile private var gameDetected = false
    
    // Resolução alvo para conversão retrato -> paisagem
    private var targetLandscapeWidth = 0
    private var targetLandscapeHeight = 0
    private var hasPortraitSegments = false
    private var portraitSegmentsCount = 0
    private var gameLandscapeResolution = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    
    // NOVO: Scheduler para detecção periódica
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var lastDetectedPackage: String? = null

    // Lista de pacotes de jogos conhecidos
    private val knownGamePackages = setOf(
        "com.tencent.ig", // PUBG Mobile
        "com.activision.callofduty.shooter", // COD Mobile
        "com.epicgames.fortnite", // Fortnite
        "com.mobile.legends", // Mobile Legends
        "com.dts.freefireth", // Free Fire
        "com.riotgames.league.wildrift", // Wild Rift
        "com.supercell.clashofclans", // Clash of Clans
        "com.supercell.royale", // Clash Royale
        "com.king.candycrushsaga", // Candy Crush
        "com.nianticlabs.pokemongo", // Pokemon GO
        "com.mojang.minecraftpe", // Minecraft
        "com.gameloft.android.ANMP.GloftA8HM", // Asphalt 8
        "com.gameloft.android.ANMP.GloftA9HM", // 🎮 Legacy of Discord
        "com.gameloft.android.ANMP.GloftA9HM.Global", // Legacy of Discord Global
        "com.ea.gp.needforspeed", // Need for Speed
        "com.kiloo.subwaysurf", // Subway Surfers
        "com.dts.freefiremax", // Free Fire Max
        "com.roblox.client", // Roblox
        "com.innersloth.spacemafia", // Among Us
        "com.playkids.game", // PlayKids
        "com.outfit7.mytalkingtomfree", // My Talking Tom
        "com.gtarcade.lod"
    )

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
            "segments" to segmentUris.toList(),
            "isGameMode" to isGameMode,
            "gameDetected" to gameDetected,
            "gamePackage" to gamePackageName
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
        resetGameDetection()

        state = RecState.RECORDING
        startForegroundServiceWithType()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, dataIntent)

        segmentIndex = 0
        segmentUris = mutableListOf()
        segmentMetadata.clear()
        lastOutputUriString = null
        finalOutputUriString = null
        hasPortraitSegments = false
        portraitSegmentsCount = 0

        pauseOffset = 0L
        startTime = SystemClock.elapsedRealtime()

        currentRotation = getDefaultDisplayRotation()
        
        // Detectar modo jogo
        detectGameMode()

        startNewSegmentForRotation(currentRotation, reason = "start")

        if (orientationMode == 0) {
            registerRotationListener()
            // NOVO: Iniciar detecção periódica
            startPeriodicGameDetection()
        } else {
            unregisterRotationListener()
        }

        sendEvent("start")
        notifyUpdateNotification()
    }

    private fun resetGameDetection() {
        isGameMode = false
        gameDetected = false
        gamePackageName = null
        targetLandscapeWidth = 0
        targetLandscapeHeight = 0
        gameLandscapeResolution = ""
        lastDetectedPackage = null
    }

    // NOVO: Detecção periódica a cada 2 segundos
    private fun startPeriodicGameDetection() {
        scheduler.scheduleAtFixedRate({
            if (state == RecState.RECORDING) {
                val currentPackage = getForegroundPackage()
                if (currentPackage != null && currentPackage != lastDetectedPackage) {
                    Log.d("REC", "🔄 Pacote em primeiro plano mudou: $lastDetectedPackage -> $currentPackage")
                    lastDetectedPackage = currentPackage
                    detectGameMode()
                }
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    // NOVO: Obter pacote em primeiro plano
    private fun getForegroundPackage(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val runningTasks = activityManager.getRunningTasks(1)
                if (runningTasks.isNotEmpty()) {
                    return runningTasks[0].topActivity?.packageName
                }
            } catch (e: Exception) {
                Log.d("REC", "Erro ao obter pacote em primeiro plano: ${e.message}")
            }
        }
        return null
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
                        Log.d("REC", "🔍 Verificando pacote em primeiro plano: $packageName")
                        
                        var detected = false
                        var detectionMethod = ""
                        
                        // Método 1: Categoria oficial do Android
                        try {
                            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
                            val isGameCategory = applicationInfo.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
                            Log.d("REC", "   Categoria oficial: $isGameCategory")
                            if (isGameCategory) {
                                detected = true
                                detectionMethod = "official_category"
                            }
                        } catch (e: Exception) {
                            Log.d("REC", "   Erro ao verificar categoria: ${e.message}")
                        }
                        
                        // Método 2: Lista de jogos conhecidos
                        if (!detected) {
                            val matchedPackage = knownGamePackages.find { packageName.contains(it) }
                            if (matchedPackage != null) {
                                Log.d("REC", "   Match na lista conhecida: $matchedPackage")
                                detected = true
                                detectionMethod = "known_packages_list"
                            } else {
                                Log.d("REC", "   Sem match na lista conhecida")
                            }
                        }
                        
                        // Se detectou um jogo e antes não estava em modo game
                        if (detected && !isGameMode) {
                            Log.d("REC", "🎮 MODO GAME DETECTADO! Pacote: $packageName (método: $detectionMethod)")
                            isGameMode = true
                            gameDetected = true
                            gamePackageName = packageName
                            
                            sendEvent("game_detected", mapOf(
                                "packageName" to packageName,
                                "detectionMethod" to detectionMethod
                            ))
                        } 
                        // Se não detectou jogo mas estava em modo game (saiu do jogo)
                        else if (!detected && isGameMode) {
                            Log.d("REC", "📱 Saindo do modo game (voltou para app normal: $packageName)")
                            isGameMode = false
                            gameDetected = false
                            // Não resetamos a resolução alvo porque já pode ter segmentos de jogo
                        }
                        // App normal (sem mudança)
                        else if (!detected && !isGameMode) {
                            Log.d("REC", "📱 App normal: $packageName")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("REC", "Erro ao detectar modo jogo: ${e.message}")
            }
        }
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
                
                if (rot == currentRotation) return
                
                val oldRotation = currentRotation
                currentRotation = rot
                
                Log.d("REC", "🔄 Rotação mudou: $oldRotation -> $rot")
                restartSegmentForRotation(rot)
            }
        }

        displayListener = listener
        displayManager?.registerDisplayListener(listener, mainHandler)
    }

    private fun unregisterRotationListener() {
        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        displayListener = null
    }

    private fun restartSegmentForRotation(rot: Int) {
        if (state != RecState.RECORDING) return
        stopCurrentRecorderOnly()
        startNewSegmentForRotation(rot, reason = "rotate")
    }

    private fun startNewSegmentForRotation(rot: Int, reason: String) {
        val (realW, realH, dpi) = getRealMetrics()
        val isLandscape = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270)

        val (w, h) = when {
            // MODO GAME DETECTADO
            isGameMode && isLandscape -> {
                val gameW = max(realW, realH)
                val gameH = min(realW, realH)
                
                Log.d("REC", "🎮 SEGMENTO DE JOGO EM PAISAGEM: ${gameW}x${gameH}")
                
                // Salvar resolução do jogo para conversão futura
                if (targetLandscapeWidth == 0) {
                    targetLandscapeWidth = gameW
                    targetLandscapeHeight = gameH
                    gameLandscapeResolution = "${gameW}x${gameH}"
                    Log.d("REC", "🎯 RESOLUÇÃO ALVO DEFINIDA PELO JOGO: ${gameW}x${gameH}")
                }
                
                Pair(gameW, gameH)
            }
            
            // Modo game mas ainda em retrato (início do jogo ou app em retrato)
            isGameMode && !isLandscape -> {
                Log.d("REC", "🎮 Segmento GAME RETRATO (aguardando paisagem): ${realW}x${realH}")
                hasPortraitSegments = true
                portraitSegmentsCount++
                Pair(realW, realH)
            }
            
            // Modos normais
            orientationMode == 1 -> // Portrait fixo
                Pair(min(realW, realH), max(realW, realH))
            orientationMode == 2 -> // Landscape fixo
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

        val metadata = SegmentMetadata(
            index = segmentIndex,
            width = w,
            height = h,
            isGameMode = isGameMode && isLandscape,
            rotation = rot,
            uri = uri.toString()
        )
        segmentMetadata.add(metadata)

        val gameInfo = if (isGameMode && isLandscape) " 🎮 JOGO" else if (isGameMode) " 🎮 (retrato)" else ""
        Log.d("REC", "🎞️ Segmento #$segmentIndex | ${w}x${h} | rot=$rot$gameInfo")

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
            "width" to w,
            "height" to h,
            "isGameMode" to (isGameMode && isLandscape)
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
        scheduler.shutdown() // Para a detecção periódica

        Log.d("REC", "🛑 Finalizando gravação...")
        Log.d("REC", "   Modo game: $isGameMode")
        Log.d("REC", "   Pacote: $gamePackageName")
        Log.d("REC", "   Tem segmentos retrato: $hasPortraitSegments")
        Log.d("REC", "   Resolução alvo: ${targetLandscapeWidth}x${targetLandscapeHeight}")

        state = RecState.STOPPING
        sendEvent("stopping", mapOf(
            "isGameMode" to isGameMode,
            "hasPortraitSegments" to hasPortraitSegments,
            "portraitSegmentsCount" to portraitSegmentsCount,
            "targetLandscape" to gameLandscapeResolution
        ))
        notifyUpdateNotification()

        stopCurrentRecorderOnly()

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        Thread {
            val finalUri = if (isGameMode && targetLandscapeWidth > 0 && hasPortraitSegments) {
                Log.d("REC", "🔄 Usando conversão retrato -> paisagem para modo game")
                convertPortraitToLandscapeAndMerge()
            } else {
                Log.d("REC", "📱 Usando merge normal")
                mergeSegmentsNormal()
            }
            
            finalOutputUriString = finalUri?.toString()
            lastOutputUriString = finalOutputUriString

            state = RecState.IDLE
            sendEvent("stop", mapOf(
                "finalUri" to finalOutputUriString,
                "conversionApplied" to (isGameMode && targetLandscapeWidth > 0 && hasPortraitSegments)
            ))
            stopForeground(true)
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        unregisterRotationListener()
        scheduler.shutdownNow()
        super.onDestroy()
    }

    private fun convertPortraitToLandscapeAndMerge(): Uri? {
        val seg = segmentUris.toList()
        if (seg.isEmpty()) return null
        if (seg.size == 1) return Uri.parse(seg.first())

        Log.d("REC", "🔄 INICIANDO CONVERSÃO RETRATO -> PAISAGEM PARA MODO GAME")
        Log.d("REC", "🎯 Resolução alvo paisagem: ${targetLandscapeWidth}x${targetLandscapeHeight}")
        Log.d("REC", "📊 Total segmentos: ${seg.size}, Segmentos retrato: $portraitSegmentsCount")

        val workDir = File(cacheDir, "ffmerge_game_convert")
        if (!workDir.exists()) workDir.mkdirs()

        val convertedFiles = mutableListOf<File>()
        
        try {
            seg.forEachIndexed { idx, uriStr ->
                val inputFile = File(workDir, "input_${idx + 1}.mp4")
                copyUriToFile(contentResolver, Uri.parse(uriStr), inputFile)
                
                val metadata = segmentMetadata.find { it.uri == uriStr }
                val isPortrait = metadata?.let { it.height > it.width } ?: false
                
                val outputFile = File(workDir, "converted_${idx + 1}.mp4")
                
                if (isPortrait && targetLandscapeWidth > 0) {
                    Log.d("REC", "  Convertendo segmento ${idx + 1}: ${metadata?.width}x${metadata?.height} (retrato) para ${targetLandscapeWidth}x${targetLandscapeHeight}")
                    
                    val convertCmd = "-y -i ${inputFile.absolutePath} " +
                            "-vf \"scale=$targetLandscapeWidth:$targetLandscapeHeight:force_original_aspect_ratio=decrease," +
                            "pad=$targetLandscapeWidth:$targetLandscapeHeight:(ow-iw)/2:(oh-ih)/2," +
                            "setsar=1\" " +
                            "-c:v libx264 -preset veryfast -crf 18 " +
                            "-c:a aac -b:a 192k " +
                            "${outputFile.absolutePath}"
                    
                    val result = FFmpegKit.execute(convertCmd)
                    
                    if (ReturnCode.isSuccess(result.returnCode)) {
                        Log.d("REC", "  ✅ Conversão bem-sucedida")
                        convertedFiles.add(outputFile)
                    } else {
                        Log.d("REC", "  ❌ Falha na conversão, usando original")
                        convertedFiles.add(inputFile)
                    }
                } else {
                    Log.d("REC", "  Mantendo segmento ${idx + 1} original (já em paisagem)")
                    convertedFiles.add(inputFile)
                }
            }
            
            val listFile = File(workDir, "list.txt")
            listFile.writeText(buildString {
                convertedFiles.forEach { f ->
                    append("file '")
                    append(f.absolutePath.replace("'", "'\\''"))
                    append("'\n")
                }
            })
            
            val mergedFile = File(workDir, "merged_${System.currentTimeMillis()}.mp4")
            
            val concatCmd = "-y -f concat -safe 0 -i ${listFile.absolutePath} " +
                    "-c:v libx264 -preset veryfast -crf 18 " +
                    "-c:a aac -b:a 192k " +
                    "${mergedFile.absolutePath}"
            
            Log.d("REC", "Concatenando ${convertedFiles.size} segmentos...")
            val concatResult = FFmpegKit.execute(concatCmd)
            
            if (!ReturnCode.isSuccess(concatResult.returnCode)) {
                Log.d("REC", "❌ Falha na concatenação")
                cleanup(workDir, convertedFiles, listFile, mergedFile)
                return mergeSegmentsNormal()
            }
            
            Log.d("REC", "✅ Conversão e merge concluídos!")
            
            val finalUri = createFinalMediaStoreOutput(gameConverted = true) ?: run {
                cleanup(workDir, convertedFiles, listFile, mergedFile)
                return Uri.parse(seg.last())
            }
            
            try {
                contentResolver.openOutputStream(finalUri, "w")?.use { os ->
                    mergedFile.inputStream().use { it.copyTo(os) }
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(finalUri, values, null, null)
                }
                
                seg.forEach { uriStr ->
                    try { contentResolver.delete(Uri.parse(uriStr), null, null) } catch (_: Exception) {}
                }
                
                Log.d("REC", "✅ Arquivo final salvo: $finalUri")
                cleanup(workDir, convertedFiles, listFile, mergedFile)
                return finalUri
                
            } catch (e: Exception) {
                Log.d("REC", "❌ Erro ao salvar: ${e.message}")
                cleanup(workDir, convertedFiles, listFile, mergedFile)
                return Uri.parse(seg.last())
            }
            
        } catch (e: Exception) {
            Log.d("REC", "❌ Erro no processo de conversão: ${e.message}")
            return mergeSegmentsNormal()
        }
    }

    private fun mergeSegmentsNormal(): Uri? {
        val seg = segmentUris.toList()
        if (seg.isEmpty()) return null
        if (seg.size == 1) return Uri.parse(seg.first())

        Log.d("REC", "📱 Merge normal (sem conversão)")

        val workDir = File(cacheDir, "ffmerge_normal")
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

        // Tentar copy primeiro
        val cmdCopy = "-y -f concat -safe 0 -i ${listFile.absolutePath} -c copy ${outTmp.absolutePath}"
        val s1 = FFmpegKit.execute(cmdCopy)

        var mergedOk = ReturnCode.isSuccess(s1.returnCode)
        if (!mergedOk) {
            Log.d("REC", "⚠️ Copy falhou, tentando re-encode...")
            val cmdReencode = "-y -f concat -safe 0 -i ${listFile.absolutePath} " +
                    "-c:v libx264 -preset veryfast -crf 18 " +
                    "-c:a aac -b:a 192k " +
                    "${outTmp.absolutePath}"

            val s2 = FFmpegKit.execute(cmdReencode)
            mergedOk = ReturnCode.isSuccess(s2.returnCode)
            if (!mergedOk) {
                Log.d("REC", "❌ Merge normal FALHOU")
                cleanup(workDir, segFiles, listFile, outTmp)
                return Uri.parse(seg.last())
            }
        }

        Log.d("REC", "✅ Merge normal concluído!")

        val finalUri = createFinalMediaStoreOutput(gameConverted = false) ?: run {
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

    private fun cleanup(dir: File, files: List<File>, listFile: File?, outFile: File?) {
        try { files.forEach { it.delete() } } catch (_: Exception) {}
        try { listFile?.delete() } catch (_: Exception) {}
        try { outFile?.delete() } catch (_: Exception) {}
    }

    private fun copyUriToFile(cr: ContentResolver, uri: Uri, outFile: File) {
        cr.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Falha ao abrir InputStream: $uri")
    }

    private fun createFinalMediaStoreOutput(gameConverted: Boolean): Uri? {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = if (gameConverted) "_game_converted_${targetLandscapeWidth}x${targetLandscapeHeight}" else ""
        val fileName = "record_${time}${suffix}_final.mp4"

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

        val gameInfo = if (isGameMode) " 🎮 ${gamePackageName?.substringAfterLast('.') ?: ""}" else ""
        val text = when (current) {
            RecState.RECORDING -> "Gravando... (Segmento $segmentIndex)$gameInfo"
            RecState.PAUSED -> "Pausado$gameInfo"
            RecState.STOPPING -> "Finalizando...$gameInfo"
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