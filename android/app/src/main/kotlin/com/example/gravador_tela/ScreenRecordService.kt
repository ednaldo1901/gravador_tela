package com.example.gravador_tela

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import io.flutter.plugin.common.EventChannel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class ScreenRecordService : Service() {

    enum class RecState { IDLE, RECORDING, PAUSED, STOPPING }
    
    enum class OrientationChangeType {
        NORMAL,      // App comum (não perguntar)
        GAME_LIKE,   // Possível game (perguntar)
        CONFIRMED_GAME // Já confirmado pelo usuário
    }

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
        
        private const val PREFS_NAME = "ScreenRecorderPrefs"
        private const val PREF_GAME_PACKAGE_PREFIX = "game_package_"

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
    
    @Volatile private var gameConfirmationStatus = OrientationChangeType.NORMAL
    @Volatile private var pendingGamePackage: String? = null
    @Volatile private var pendingRotation = Surface.ROTATION_0
    
    private lateinit var prefs: SharedPreferences
    
    private var targetLandscapeWidth = 0
    private var targetLandscapeHeight = 0
    private var hasPortraitSegments = false
    private var portraitSegmentsCount = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    
    private var confirmationOverlay: Dialog? = null
    private var windowManager: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
            "gameConfirmationStatus" to gameConfirmationStatus.name
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

        resetState()

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

        startNewSegmentForRotation(currentRotation, reason = "start")

        if (orientationMode == 0) {
            registerRotationListener()
        }

        sendEvent("start")
        notifyUpdateNotification()
    }

    private fun resetState() {
        gameConfirmationStatus = OrientationChangeType.NORMAL
        pendingGamePackage = null
        targetLandscapeWidth = 0
        targetLandscapeHeight = 0
        dismissConfirmationOverlay()
    }

    private fun isPackageConfirmedAsGame(packageName: String): Boolean {
        return prefs.getBoolean(PREF_GAME_PACKAGE_PREFIX + packageName, false)
    }

    private fun savePackageAsGame(packageName: String) {
        prefs.edit().putBoolean(PREF_GAME_PACKAGE_PREFIX + packageName, true).apply()
        Log.d("REC", "💾 Pacote salvo como game: $packageName")
    }

    private fun showGameConfirmationDialog(packageName: String, rotation: Int) {
        if (confirmationOverlay?.isShowing == true) return
        
        try {
            val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(0xDD111111.toInt())
                layoutParams = ViewGroup.LayoutParams(
                    dp(300),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            
            val title = TextView(this).apply {
                text = "🔄 Rotação Detectada"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            layout.addView(title)
            
            layout.addView(createSpacer(dp(16)))
            
            val message = TextView(this).apply {
                text = "O app '$packageName' mudou para orientação paisagem.\n\n" +
                       "Isso geralmente acontece em JOGOS.\n\n" +
                       "Deseja tratar como MODO GAME?\n" +
                       "(Isso irá converter todos os segmentos para paisagem)"
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
            }
            layout.addView(message)
            
            layout.addView(createSpacer(dp(24)))
            
            val buttonLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            
            val btnNo = Button(this).apply {
                text = "Não (App Normal)"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xAA333333.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    Log.d("REC", "❌ Usuário escolheu: App Normal para $packageName")
                    gameConfirmationStatus = OrientationChangeType.NORMAL
                    dismissConfirmationOverlay()
                    continueRecordingAfterConfirmation(false, rotation)
                }
            }
            buttonLayout.addView(btnNo)
            
            val btnYes = Button(this).apply {
                text = "Sim (Modo Game) 🎮"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFB71C1C.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
                setOnClickListener {
                    Log.d("REC", "✅ Usuário escolheu: Modo Game para $packageName")
                    savePackageAsGame(packageName)
                    gameConfirmationStatus = OrientationChangeType.CONFIRMED_GAME
                    dismissConfirmationOverlay()
                    continueRecordingAfterConfirmation(true, rotation)
                }
            }
            buttonLayout.addView(btnYes)
            
            layout.addView(buttonLayout)
            
            val checkBoxLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            
            val checkBox = CheckBox(this).apply {
                id = View.generateViewId()
                setTextColor(0xCCFFFFFF.toInt())
                text = "Lembrar minha escolha para este app"
            }
            checkBoxLayout.addView(checkBox)
            
            layout.addView(checkBoxLayout)
            
            dialog.setContentView(layout)
            
            val params = dialog.window?.attributes
            params?.width = WindowManager.LayoutParams.MATCH_PARENT
            params?.height = WindowManager.LayoutParams.WRAP_CONTENT
            params?.gravity = Gravity.CENTER
            params?.type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            params?.flags = params?.flags?.or(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) ?: 0
            params?.flags = params?.flags?.and(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()) ?: 0
            params?.flags = params?.flags?.or(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) ?: 0
            params?.flags = params?.flags?.or(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) ?: 0
            
            dialog.window?.attributes = params
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setDimAmount(0.6f)
            
            dialog.setOnDismissListener {
                confirmationOverlay = null
            }
            
            dialog.show()
            confirmationOverlay = dialog
            
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    Log.d("REC", "💾 Usuário marcou 'Lembrar' para $packageName")
                }
            }
            
        } catch (e: Exception) {
            Log.e("REC", "Erro ao mostrar diálogo: ${e.message}")
            continueRecordingAfterConfirmation(false, rotation)
        }
    }

    // CORREÇÃO: Método createSpacer SEM topMargin
    private fun createSpacer(height: Int): View {
        return Space(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
            )
        }
    }

    private fun dismissConfirmationOverlay() {
        try {
            confirmationOverlay?.dismiss()
        } catch (_: Exception) {}
        confirmationOverlay = null
    }

    private fun continueRecordingAfterConfirmation(isGame: Boolean, rotation: Int) {
        if (isGame) {
            targetLandscapeWidth = max(getRealMetrics().first, getRealMetrics().second)
            targetLandscapeHeight = min(getRealMetrics().first, getRealMetrics().second)
            hasPortraitSegments = true
            
            Log.d("REC", "🎮 Modo game confirmado pelo usuário")
            Log.d("REC", "🎯 Resolução alvo: ${targetLandscapeWidth}x${targetLandscapeHeight}")
            
            restartSegmentForRotation(rotation, isGameForced = true)
        } else {
            restartSegmentForRotation(rotation, isGameForced = false)
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
                
                val isLandscape = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270)
                
                if (isLandscape && gameConfirmationStatus == OrientationChangeType.NORMAL) {
                    val packageName = getForegroundPackage()
                    
                    if (packageName != null && packageName != "com.example.gravador_tela") {
                        if (isPackageConfirmedAsGame(packageName)) {
                            Log.d("REC", "🎮 Pacote já confirmado como game: $packageName")
                            gameConfirmationStatus = OrientationChangeType.CONFIRMED_GAME
                            restartSegmentForRotation(rot, isGameForced = true)
                        } else {
                            Log.d("REC", "❓ Primeira rotação para $packageName - perguntando usuário")
                            pendingGamePackage = packageName
                            pendingRotation = rot
                            
                            showGameConfirmationDialog(packageName, rot)
                        }
                    } else {
                        restartSegmentForRotation(rot, isGameForced = false)
                    }
                } else if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME) {
                    restartSegmentForRotation(rot, isGameForced = true)
                } else {
                    restartSegmentForRotation(rot, isGameForced = false)
                }
            }
        }

        displayListener = listener
        displayManager?.registerDisplayListener(listener, mainHandler)
    }

    private fun getForegroundPackage(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val runningTasks = activityManager.getRunningTasks(1)
                if (runningTasks.isNotEmpty()) {
                    return runningTasks[0].topActivity?.packageName
                }
            } catch (e: Exception) {
                Log.d("REC", "Erro ao obter pacote: ${e.message}")
            }
        }
        return null
    }

    private fun unregisterRotationListener() {
        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        displayListener = null
        dismissConfirmationOverlay()
    }

    private fun restartSegmentForRotation(rot: Int, isGameForced: Boolean) {
        if (state != RecState.RECORDING) return
        stopCurrentRecorderOnly()
        startNewSegmentForRotation(rot, if (isGameForced) "game_rotate" else "rotate")
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

    private fun startNewSegmentForRotation(rot: Int, reason: String) {
        val (realW, realH, dpi) = getRealMetrics()
        val isLandscape = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270)

        val (w, h) = when {
            gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && isLandscape -> {
                val gameW = max(realW, realH)
                val gameH = min(realW, realH)
                
                Log.d("REC", "🎮 SEGMENTO DE JOGO (confirmado): ${gameW}x${gameH}")
                
                if (targetLandscapeWidth == 0) {
                    targetLandscapeWidth = gameW
                    targetLandscapeHeight = gameH
                }
                
                Pair(gameW, gameH)
            }
            
            gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && !isLandscape -> {
                Log.d("REC", "🎮 Segmento GAME RETRATO: ${realW}x${realH}")
                hasPortraitSegments = true
                portraitSegmentsCount++
                Pair(realW, realH)
            }
            
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
            isGameMode = (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && isLandscape),
            rotation = rot,
            uri = uri.toString()
        )
        segmentMetadata.add(metadata)

        val gameInfo = when {
            gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && isLandscape -> " 🎮 JOGO"
            gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME -> " 🎮 (retrato)"
            else -> ""
        }
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
            "isGameMode" to (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && isLandscape)
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
        dismissConfirmationOverlay()

        Log.d("REC", "🛑 Finalizando gravação...")
        Log.d("REC", "   Game Confirmation: $gameConfirmationStatus")
        Log.d("REC", "   Tem segmentos retrato: $hasPortraitSegments")
        Log.d("REC", "   Resolução alvo: ${targetLandscapeWidth}x${targetLandscapeHeight}")

        state = RecState.STOPPING
        sendEvent("stopping", mapOf(
            "gameConfirmationStatus" to gameConfirmationStatus.name,
            "hasPortraitSegments" to hasPortraitSegments,
            "portraitSegmentsCount" to portraitSegmentsCount
        ))
        notifyUpdateNotification()

        stopCurrentRecorderOnly()

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        Thread {
            val finalUri = if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && 
                targetLandscapeWidth > 0 && hasPortraitSegments) {
                Log.d("REC", "🔄 Usando conversão retrato -> paisagem (modo game confirmado)")
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
                "conversionApplied" to (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && 
                    targetLandscapeWidth > 0 && hasPortraitSegments)
            ))
            stopForeground(true)
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        unregisterRotationListener()
        dismissConfirmationOverlay()
        super.onDestroy()
    }

    private fun convertPortraitToLandscapeAndMerge(): Uri? {
        val seg = segmentUris.toList()
        if (seg.isEmpty()) return null
        if (seg.size == 1) return Uri.parse(seg.first())

        Log.d("REC", "🔄 INICIANDO CONVERSÃO RETRATO -> PAISAGEM")
        Log.d("REC", "🎯 Resolução alvo: ${targetLandscapeWidth}x${targetLandscapeHeight}")
        Log.d("REC", "📊 Total segmentos: ${seg.size}, Retrato: $portraitSegmentsCount")

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
                    Log.d("REC", "  Convertendo segmento ${idx + 1}: ${metadata?.width}x${metadata?.height} -> ${targetLandscapeWidth}x${targetLandscapeHeight}")
                    
                    val convertCmd = "-y -i ${inputFile.absolutePath} " +
                            "-vf \"scale=$targetLandscapeWidth:$targetLandscapeHeight:force_original_aspect_ratio=decrease," +
                            "pad=$targetLandscapeWidth:$targetLandscapeHeight:(ow-iw)/2:(oh-ih)/2," +
                            "setsar=1\" " +
                            "-c:v libx264 -preset veryfast -crf 18 " +
                            "-c:a aac -b:a 192k " +
                            "${outputFile.absolutePath}"
                    
                    val result = FFmpegKit.execute(convertCmd)
                    
                    if (ReturnCode.isSuccess(result.returnCode)) {
                        Log.d("REC", "  ✅ Conversão OK")
                        convertedFiles.add(outputFile)
                    } else {
                        Log.d("REC", "  ❌ Falha, usando original")
                        convertedFiles.add(inputFile)
                    }
                } else {
                    Log.d("REC", "  Mantendo original")
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
            
            Log.d("REC", "Concatenando...")
            val concatResult = FFmpegKit.execute(concatCmd)
            
            if (!ReturnCode.isSuccess(concatResult.returnCode)) {
                cleanup(workDir, convertedFiles, listFile, mergedFile)
                return mergeSegmentsNormal()
            }
            
            Log.d("REC", "✅ Conversão concluída!")
            
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
                
                cleanup(workDir, convertedFiles, listFile, mergedFile)
                return finalUri
                
            } catch (e: Exception) {
                cleanup(workDir, convertedFiles, listFile, mergedFile)
                return Uri.parse(seg.last())
            }
            
        } catch (e: Exception) {
            return mergeSegmentsNormal()
        }
    }

    private fun mergeSegmentsNormal(): Uri? {
        val seg = segmentUris.toList()
        if (seg.isEmpty()) return null
        if (seg.size == 1) return Uri.parse(seg.first())

        Log.d("REC", "📱 Merge normal")

        val workDir = File(cacheDir, "ffmerge_normal")
        workDir.mkdirs()

        val segFiles = mutableListOf<File>()
        try {
            seg.forEachIndexed { idx, uriStr ->
                val f = File(workDir, "seg_${idx + 1}.mp4")
                copyUriToFile(contentResolver, Uri.parse(uriStr), f)
                segFiles.add(f)
            }
        } catch (e: Exception) {
            return Uri.parse(seg.last())
        }

        val listFile = File(workDir, "list.txt")
        listFile.writeText(segFiles.joinToString("\n") { "file '${it.absolutePath}'" })

        val outTmp = File(workDir, "merged_${System.currentTimeMillis()}.mp4")

        val cmdCopy = "-y -f concat -safe 0 -i ${listFile.absolutePath} -c copy ${outTmp.absolutePath}"
        val result = FFmpegKit.execute(cmdCopy)

        if (!ReturnCode.isSuccess(result.returnCode)) {
            return Uri.parse(seg.last())
        }

        val finalUri = createFinalMediaStoreOutput(gameConverted = false) ?: return Uri.parse(seg.last())

        try {
            contentResolver.openOutputStream(finalUri, "w")?.use { os ->
                outTmp.inputStream().use { it.copyTo(os) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(finalUri, values, null, null)
            }

            return finalUri
        } catch (_: Exception) {
            return Uri.parse(seg.last())
        }
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
        } ?: throw IllegalStateException("Falha ao copiar: $uri")
    }

    private fun createFinalMediaStoreOutput(gameConverted: Boolean): Uri? {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = if (gameConverted) "_game" else ""
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

        val gameInfo = if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME) " 🎮" else ""
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
        val fileName = "record_${time}_p${part}.mp4"

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
            ?: throw IllegalStateException("Falha ao abrir FileDescriptor")

        return Pair(uri, pfd)
    }

    private fun getDefaultDisplayRotation(): Int {
        return displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    private fun getRealMetrics(): Triple<Int, Int, Int> {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display?.getRealMetrics(metrics) ?: resources.displayMetrics.also { metrics.setTo(it) }
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}