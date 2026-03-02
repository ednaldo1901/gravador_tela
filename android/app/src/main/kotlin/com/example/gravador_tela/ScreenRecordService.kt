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

        @Volatile var gameConfirmationStatus: OrientationChangeType? = null

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

    // Lista de pacotes do sistema para ignorar
    private val systemPackages = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.phone",
        "com.android.dialer",
        "com.android.mms",
        "com.android.contacts",
        "android",
        "system"
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

    // Lista de TODOS os segmentos temporários (serão deletados)
    private val tempSegmentUris = mutableListOf<String>()
    
    // Lista apenas de segmentos de game (paisagem) que vão para o merge
    private val gameSegmentUris = mutableListOf<String>()
    private val gameSegmentMetadata = mutableListOf<SegmentMetadata>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private var confirmationOverlay: Dialog? = null
    private var windowManager: WindowManager? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var lastDetectedPackage: String? = null
    private var lastAppBeforeRecorder: String? = null

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
            "gameSegments" to gameSegmentUris.size,
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
        storeLastAppBeforeRecording()

        state = RecState.RECORDING
        startForegroundServiceWithType()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, dataIntent)

        segmentIndex = 0
        segmentUris = mutableListOf()
        segmentMetadata.clear()
        tempSegmentUris.clear()
        gameSegmentUris.clear()
        gameSegmentMetadata.clear()
        lastOutputUriString = null
        finalOutputUriString = null

        pauseOffset = 0L
        startTime = SystemClock.elapsedRealtime()

        currentRotation = getDefaultDisplayRotation()
        startNewSegmentForRotation(currentRotation, reason = "start")

        if (orientationMode == 0) {
            registerRotationListener()
            startPeriodicGameDetection()
        }

        sendEvent("start")
        notifyUpdateNotification()
    }

    private fun isSystemPackage(packageName: String): Boolean {
        return systemPackages.any { packageName.contains(it) } ||
               packageName.startsWith("com.android.") ||
               packageName.startsWith("com.google.android.") ||
               packageName.startsWith("android")
    }

    private fun hasUsageStatsPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                )
                return mode == android.app.AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    private fun getLastAppFromUsageStats(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 2 * 60 * 1000
            
            val stats = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )
            
            if (!stats.isNullOrEmpty()) {
                stats
                    .filter { 
                        it.packageName != packageName && 
                        !isSystemPackage(it.packageName)
                    }
                    .sortedByDescending { it.lastTimeUsed }
                    .firstOrNull()
                    ?.packageName
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("REC", "Erro UsageStats: ${e.message}")
            null
        }
    }

    private fun getLastAppFromRunningTasks(): String? {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                @Suppress("DEPRECATION")
                val runningTasks = activityManager.getRunningTasks(5)
                for (task in runningTasks) {
                    val pkg = task.baseActivity?.packageName
                    if (pkg != null && 
                        pkg != packageName && 
                        !isSystemPackage(pkg)) {
                        return pkg
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val recentTasks = activityManager.getRecentTasks(5, ActivityManager.RECENT_WITH_EXCLUDED)
                for (task in recentTasks) {
                    val pkg = task.baseActivity?.packageName
                    if (pkg != null && 
                        pkg != packageName && 
                        !isSystemPackage(pkg)) {
                        return pkg
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun storeLastAppBeforeRecording() {
        Log.d("REC", "📱 Tentando obter último app antes do gravador...")
        
        if (hasUsageStatsPermission()) {
            Log.d("REC", "📱 Permissão UsageStats concedida, consultando...")
            val appFromUsage = getLastAppFromUsageStats()
            if (appFromUsage != null) {
                lastAppBeforeRecorder = appFromUsage
                Log.d("REC", "📱 Último app via UsageStats: $lastAppBeforeRecorder")
                return
            } else {
                Log.d("REC", "📱 UsageStats retornou null ou só apps do sistema")
            }
        } else {
            Log.d("REC", "⚠️ Permissão UsageStats NÃO concedida")
        }
        
        Log.d("REC", "📱 Tentando runningTasks como fallback...")
        val appFromTasks = getLastAppFromRunningTasks()
        if (appFromTasks != null) {
            lastAppBeforeRecorder = appFromTasks
            Log.d("REC", "📱 Último app via runningTasks: $lastAppBeforeRecorder")
            return
        }
        
        Log.d("REC", "📱 Não foi possível determinar o último app")
    }

    private fun resetState() {
        gameConfirmationStatus = OrientationChangeType.NORMAL
        pendingGamePackage = null
        dismissConfirmationOverlay()
        lastDetectedPackage = null
        lastAppBeforeRecorder = null
        tempSegmentUris.clear()
        gameSegmentUris.clear()
        gameSegmentMetadata.clear()
    }

    private fun getMostLikelyGameApp(): String? {
        Log.d("REC", "🔍 Buscando app mais provável para modo game...")
        
        if (lastAppBeforeRecorder != null) {
            if (isSystemPackage(lastAppBeforeRecorder!!)) {
                Log.d("REC", "   ⚠️ Último app é do sistema, ignorando: $lastAppBeforeRecorder")
            } else {
                Log.d("REC", "   ✅ Último app antes do gravador: $lastAppBeforeRecorder")
                return lastAppBeforeRecorder
            }
        }
        
        if (hasUsageStatsPermission()) {
            val appFromUsage = getLastAppFromUsageStats()
            if (appFromUsage != null) {
                Log.d("REC", "   ✅ App via UsageStats (agora): $appFromUsage")
                return appFromUsage
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningAppProcesses = activityManager.runningAppProcesses
                
                if (runningAppProcesses != null) {
                    for (process in runningAppProcesses) {
                        if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            if (process.processName != packageName && !isSystemPackage(process.processName)) {
                                Log.d("REC", "   ✅ App em foreground via processes: ${process.processName}")
                                return process.processName
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("REC", "   Erro runningAppProcesses: ${e.message}")
            }
        }
        
        Log.d("REC", "   ❌ Nenhum app candidato encontrado")
        return null
    }

    private fun startPeriodicGameDetection() {
        scheduler.scheduleAtFixedRate({
            if (state == RecState.RECORDING) {
                val currentPackage = getForegroundPackage()
                if (currentPackage != null && currentPackage != lastDetectedPackage) {
                    Log.d("REC", "🔄 Pacote mudou: $lastDetectedPackage -> $currentPackage")
                    lastDetectedPackage = currentPackage

                    if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME &&
                        currentPackage == packageName
                    ) {
                        Log.d("REC", "📱 Voltou para o app, mantendo modo game")
                    }
                }
            }
        }, 2, 2, TimeUnit.SECONDS)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.e("REC", "Sem permissão de overlay para diálogo")
                continueRecordingAfterConfirmation(false, rotation)
                return
            }
        }

        try {
            val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(0xDD111111.toInt())
                layoutParams = ViewGroup.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val title = TextView(this).apply {
                text = "🎮 Modo Game Detectado"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            layout.addView(title)

            layout.addView(createSpacer(dp(16)))

            val appName = packageName.substringAfterLast('.').take(20)
            val message = TextView(this).apply {
                text = "O app '$appName' mudou para orientação paisagem.\n\n" +
                       "Isso geralmente acontece em JOGOS.\n\n" +
                       "Deseja ativar o MODO GAME?\n" +
                       "(Apenas o vídeo do jogo será salvo)"
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
            }
            layout.addView(message)

            layout.addView(createSpacer(dp(24)))

            val buttonLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                    
                    // ANTES de ativar modo game, DELETAR todos os segmentos anteriores
                    Log.d("REC", "🗑️ Removendo ${tempSegmentUris.size} segmentos anteriores...")
                    tempSegmentUris.forEach { uriStr ->
                        try { contentResolver.delete(Uri.parse(uriStr), null, null) } 
                        catch (e: Exception) { Log.e("REC", "Erro ao deletar $uriStr: ${e.message}") }
                    }
                    tempSegmentUris.clear()
                    
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
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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

            dialog.setOnDismissListener { confirmationOverlay = null }

            dialog.show()
            confirmationOverlay = dialog

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) Log.d("REC", "💾 Usuário marcou 'Lembrar' para $packageName")
            }

        } catch (e: Exception) {
            Log.e("REC", "Erro ao mostrar diálogo: ${e.message}")
            continueRecordingAfterConfirmation(false, rotation)
        }
    }

    private fun showManualAppInputDialog(rotation: Int) {
        if (confirmationOverlay?.isShowing == true) return
        
        try {
            val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(0xDD111111.toInt())
                layoutParams = ViewGroup.LayoutParams(dp(350), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            
            val title = TextView(this).apply {
                text = "🎮 Detecção Manual"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            layout.addView(title)
            
            layout.addView(createSpacer(dp(16)))
            
            val message = TextView(this).apply {
                text = "Não foi possível detectar automaticamente.\n\n" +
                       "Digite o nome do pacote do jogo\n" +
                       "(ex: com.gtarcade.lod)"
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
            }
            layout.addView(message)
            
            layout.addView(createSpacer(dp(16)))
            
            val input = EditText(this).apply {
                hint = "com.gtarcade.lod"
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0x88FFFFFF.toInt())
                background.setTint(0x33FFFFFF.toInt())
            }
            layout.addView(input)
            
            layout.addView(createSpacer(dp(16)))
            
            val buttonLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            
            val btnCancel = Button(this).apply {
                text = "Cancelar"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xAA333333.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener { 
                    dialog.dismiss()
                    continueRecordingAfterConfirmation(false, rotation)
                }
            }
            buttonLayout.addView(btnCancel)
            
            val btnOk = Button(this).apply {
                text = "Confirmar"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFB71C1C.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
                setOnClickListener {
                    val packageName = input.text.toString()
                    if (packageName.isNotBlank() && !isSystemPackage(packageName)) {
                        // DELETAR segmentos anteriores
                        Log.d("REC", "🗑️ Removendo ${tempSegmentUris.size} segmentos anteriores...")
                        tempSegmentUris.forEach { uriStr ->
                            try { contentResolver.delete(Uri.parse(uriStr), null, null) } 
                            catch (e: Exception) { Log.e("REC", "Erro ao deletar $uriStr: ${e.message}") }
                        }
                        tempSegmentUris.clear()
                        
                        savePackageAsGame(packageName)
                        gameConfirmationStatus = OrientationChangeType.CONFIRMED_GAME
                        dialog.dismiss()
                        continueRecordingAfterConfirmation(true, rotation)
                    } else {
                        Toast.makeText(this@ScreenRecordService, 
                            if (packageName.isBlank()) "Digite um nome de pacote" else "Pacote do sistema inválido", 
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            buttonLayout.addView(btnOk)
            
            layout.addView(buttonLayout)
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
            dialog.window?.attributes = params
            
            dialog.show()
            
        } catch (e: Exception) {
            Log.e("REC", "Erro no diálogo manual: ${e.message}")
            continueRecordingAfterConfirmation(false, rotation)
        }
    }

    private fun createSpacer(height: Int): View {
        return Space(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        }
    }

    private fun dismissConfirmationOverlay() {
        try { confirmationOverlay?.dismiss() } catch (_: Exception) {}
        confirmationOverlay = null
    }

    private fun continueRecordingAfterConfirmation(isGame: Boolean, rotation: Int) {
        if (isGame) {
            Log.d("REC", "🎮 MODO GAME ATIVADO! Apenas segmentos PAISAGEM serão mantidos")
        } else {
            Log.d("REC", "📱 Modo normal (sem game)")
        }
        restartSegmentForRotation(rotation, isGameForced = isGame)
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

                // Se ainda não confirmamos modo game, podemos perguntar
                if (isLandscape && gameConfirmationStatus == OrientationChangeType.NORMAL) {
                    val candidatePackage = getMostLikelyGameApp()

                    if (candidatePackage != null) {
                        if (isPackageConfirmedAsGame(candidatePackage)) {
                            Log.d("REC", "🎮 Pacote já confirmado como game: $candidatePackage")
                            
                            // DELETAR segmentos anteriores
                            Log.d("REC", "🗑️ Removendo ${tempSegmentUris.size} segmentos anteriores...")
                            tempSegmentUris.forEach { uriStr ->
                                try { contentResolver.delete(Uri.parse(uriStr), null, null) } 
                                catch (e: Exception) { Log.e("REC", "Erro ao deletar $uriStr: ${e.message}") }
                            }
                            tempSegmentUris.clear()
                            
                            gameConfirmationStatus = OrientationChangeType.CONFIRMED_GAME
                            restartSegmentForRotation(rot, isGameForced = true)
                        } else {
                            Log.d("REC", "❓ Primeira rotação - perguntando sobre $candidatePackage")
                            pendingGamePackage = candidatePackage
                            pendingRotation = rot
                            showGameConfirmationDialog(candidatePackage, rot)
                        }
                    } else {
                        Log.d("REC", "   Nenhum app candidato encontrado")
                        showManualAppInputDialog(rot)
                    }
                } 
                else if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME) {
                    restartSegmentForRotation(rot, isGameForced = true)
                }
                else {
                    restartSegmentForRotation(rot, isGameForced = false)
                }
            }
        }

        displayListener = listener
        displayManager?.registerDisplayListener(listener, mainHandler)
    }

    private fun getForegroundPackage(): String? {
        Log.d("REC", "🔍 Tentando obter pacote em primeiro plano...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                @Suppress("DEPRECATION")
                val runningTasks = activityManager.getRunningTasks(1)
                Log.d("REC", "   runningTasks size: ${runningTasks.size}")

                if (runningTasks.isNotEmpty()) {
                    val topActivity = runningTasks[0].topActivity
                    Log.d("REC", "   topActivity: $topActivity")

                    val packageName = topActivity?.packageName
                    Log.d("REC", "   packageName: $packageName")

                    return packageName
                } else {
                    Log.d("REC", "   runningTasks está vazio!")
                }
            } catch (e: Exception) {
                Log.e("REC", "❌ Erro ao obter pacote: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Log.d("REC", "   Android version < Q (API 29), não suportado")

            try {
                @Suppress("DEPRECATION")
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                @Suppress("DEPRECATION")
                val runningTasks = activityManager.getRunningTasks(1)
                if (runningTasks.isNotEmpty()) {
                    val packageName = runningTasks[0].topActivity?.packageName
                    Log.d("REC", "   (fallback) packageName: $packageName")
                    return packageName
                }
            } catch (e: Exception) {
                Log.e("REC", "❌ Erro no fallback: ${e.message}")
            }
        }

        Log.d("REC", "   Não foi possível obter pacote")
        return null
    }

    private fun unregisterRotationListener() {
        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        displayListener = null
        dismissConfirmationOverlay()
        try { scheduler.shutdown() } catch (_: Exception) {}
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
            orientationMode == 1 -> Pair(min(realW, realH), max(realW, realH))
            orientationMode == 2 -> Pair(max(realW, realH), min(realW, realH))
            orientationMode == 3 -> {
                val s = min(realW, realH)
                Pair(s, s)
            }
            else -> {
                if (isLandscape) Pair(max(realW, realH), min(realW, realH))
                else Pair(min(realW, realH), max(realW, realH))
            }
        }

        segmentIndex += 1
        val (uri, pfd) = createMediaStoreOutput(segmentIndex)
        outputUri = uri
        outputPfd = pfd
        lastOutputUriString = uri.toString()

        // Guarda TODOS os segmentos temporariamente (serão deletados depois)
        tempSegmentUris.add(uri.toString())

        val metadata = SegmentMetadata(
            index = segmentIndex,
            width = w,
            height = h,
            isGameMode = (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && isLandscape),
            rotation = rot,
            uri = uri.toString()
        )
        segmentMetadata.add(metadata)
        
        // Em modo game, só adiciona à lista de merge se for PAISAGEM
        if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME) {
            if (isLandscape) {
                gameSegmentUris.add(uri.toString())
                gameSegmentMetadata.add(metadata)
                Log.d("REC", "🎮 Segmento PAISAGEM #${gameSegmentUris.size} salvo para merge (modo game)")
            } else {
                Log.d("REC", "🗑️ Segmento RETRATO IGNORADO (modo game ativo)")
            }
        }

        val gameInfo = if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && isLandscape) " 🎮" 
                      else if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME) " 🗑️" 
                      else ""
        Log.d("REC", "🎞️ Segmento #$segmentIndex | ${w}x${h} | rot=$rot$gameInfo")

        // Só inicia o recorder se não for modo game com retrato
        if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && !isLandscape) {
            Log.d("REC", "⏭️ Pulando gravação - modo game com retrato")
            return
        }

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

        // NÃO finaliza na galeria ainda - mantém como temporário
        // finalizeMediaStoreUri(finishedUri)
        
        try { outputPfd?.close() } catch (_: Exception) {}
        outputPfd = null
        outputUri = null
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
        Log.d("REC", "   Segmentos temporários: ${tempSegmentUris.size}")
        Log.d("REC", "   Segmentos game (paisagem) para merge: ${gameSegmentUris.size}")

        state = RecState.STOPPING
        sendEvent(
            "stopping",
            mapOf(
                "gameConfirmationStatus" to gameConfirmationStatus.name,
                "gameSegmentsCount" to gameSegmentUris.size
            )
        )
        notifyUpdateNotification()

        stopCurrentRecorderOnly()

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        Thread {
            val finalUri = if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME && 
                              gameSegmentUris.isNotEmpty()) {
                Log.d("REC", "🎮 Processando ${gameSegmentUris.size} segmentos de game...")
                mergeGameSegments()
            } else {
                // Se não for modo game, não salva nada
                Log.d("REC", "📱 Modo normal - deletando todos os segmentos")
                tempSegmentUris.forEach { uriStr ->
                    try { contentResolver.delete(Uri.parse(uriStr), null, null) } 
                    catch (e: Exception) { Log.e("REC", "Erro ao deletar $uriStr: ${e.message}") }
                }
                null
            }

            finalOutputUriString = finalUri?.toString()
            lastOutputUriString = finalOutputUriString

            state = RecState.IDLE
            sendEvent(
                "stop",
                mapOf(
                    "finalUri" to finalOutputUriString,
                    "gameSegmentsUsed" to gameSegmentUris.size
                )
            )
            stopForeground(true)
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        unregisterRotationListener()
        dismissConfirmationOverlay()
        try { scheduler.shutdownNow() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun mergeGameSegments(): Uri? {
        if (gameSegmentUris.isEmpty()) return null
        if (gameSegmentUris.size == 1) {
            // Se só tem um segmento, ele é o vídeo final
            val uri = Uri.parse(gameSegmentUris.first())
            
            // Finalizar o único segmento na galeria
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, values, null, null)
                    Log.d("REC", "✅ Vídeo único finalizado na galeria: $uri")
                } catch (e: Exception) {
                    Log.e("REC", "Erro ao finalizar vídeo: ${e.message}")
                }
            }
            
            // Deletar todos os outros segmentos temporários (se houver)
            tempSegmentUris.filter { it != gameSegmentUris.first() }.forEach { uriStr ->
                try { contentResolver.delete(Uri.parse(uriStr), null, null) } 
                catch (e: Exception) { Log.e("REC", "Erro ao deletar $uriStr: ${e.message}") }
            }
            
            return uri
        }

        Log.d("REC", "🔄 Mesclando ${gameSegmentUris.size} segmentos de game...")

        val workDir = File(cacheDir, "ffmerge_game")
        workDir.mkdirs()

        val segFiles = mutableListOf<File>()
        try {
            gameSegmentUris.forEachIndexed { idx, uriStr ->
                val f = File(workDir, "game_seg_${idx + 1}.mp4")
                copyUriToFile(contentResolver, Uri.parse(uriStr), f)
                segFiles.add(f)
                Log.d("REC", "   Segmento game ${idx + 1} copiado")
            }
        } catch (e: Exception) {
            Log.e("REC", "Erro ao copiar segmentos: ${e.message}")
            return null
        }

        val listFile = File(workDir, "list.txt")
        listFile.writeText(segFiles.joinToString("\n") { "file '${it.absolutePath}'" })

        val mergedFile = File(workDir, "game_merged_${System.currentTimeMillis()}.mp4")

        val cmdCopy = "-y -f concat -safe 0 -i ${listFile.absolutePath} -c copy ${mergedFile.absolutePath}"
        val result = FFmpegKit.execute(cmdCopy)

        if (!ReturnCode.isSuccess(result.returnCode)) {
            Log.e("REC", "Falha no merge dos segmentos game")
            return null
        }

        Log.d("REC", "✅ Merge dos segmentos game concluído!")

        val finalUri = createFinalMediaStoreOutput(gameConverted = true)
        if (finalUri == null) {
            Log.e("REC", "Falha ao criar arquivo final na galeria")
            return null
        }

        try {
            contentResolver.openOutputStream(finalUri, "w")?.use { os ->
                mergedFile.inputStream().use { it.copyTo(os) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(finalUri, values, null, null)
            }

            // Deletar TODOS os segmentos temporários (incluindo os de game)
            Log.d("REC", "🗑️ Removendo ${tempSegmentUris.size} segmentos temporários da galeria...")
            tempSegmentUris.forEach { uriStr ->
                try {
                    val deleted = contentResolver.delete(Uri.parse(uriStr), null, null)
                    Log.d("REC", "   → $uriStr removido (deleted=$deleted)")
                } catch (e: Exception) {
                    Log.e("REC", "   Erro ao deletar $uriStr: ${e.message}")
                }
            }

            Log.d("REC", "✅ Vídeo final salvo na galeria: $finalUri")
            return finalUri

        } catch (e: Exception) {
            Log.e("REC", "Erro ao salvar vídeo final: ${e.message}")
            return null
        } finally {
            cleanup(workDir, segFiles, listFile, mergedFile)
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

        val gameInfo = if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME) 
            " 🎮 (${gameSegmentUris.size} seg)" else ""
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
        val gameTag = if (gameConfirmationStatus == OrientationChangeType.CONFIRMED_GAME) "_game" else ""
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