package com.example.gravador_tela

import android.animation.ValueAnimator
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class OverlayBubbleService : Service() {

    companion object {
        const val ACTION_SHOW = "ACTION_SHOW"
        const val ACTION_HIDE = "ACTION_HIDE"
    }

    private enum class Side { LEFT, RIGHT }

    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    // tamanhos
    private var winSize = 0
    private var bubbleSize = 0
    private var radius = 0
    private var btnSize = 0
    private var padding = 0

    // views
    private var bubble: FrameLayout? = null
    private var timerText: TextView? = null
    private var iconView: ImageView? = null
    private var menuItems: List<View> = emptyList()

    // estado
    private var menuVisible = false
    private var docked = false
    private var dockedSide: Side = Side.LEFT

    // posição interna da bolha dentro do "canvas" (janela)
    private var bubbleLeftInWindow = 0
    private var bubbleTopInWindow = 0

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateUIFromService()
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // dimensões (dp)
        winSize = dp(260)
        bubbleSize = dp(66)
        radius = dp(96)
        btnSize = dp(48)
        padding = dp(12)

        // bolha centralizada verticalmente dentro da janela
        bubbleTopInWindow = (winSize - bubbleSize) / 2
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> show()
            ACTION_HIDE -> hide()
        }
        return START_NOT_STICKY
    }

    private fun show() {
        if (root != null) return

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            winSize,
            winSize,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(240)
        }

        root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        bubbleLeftInWindow = padding

        bubble = createBubble().also { root!!.addView(it) }

        menuItems = createRadialMenu().also { items ->
            items.forEach { root!!.addView(it) }
        }
        setMenuVisible(false, animate = false)

        wm?.addView(root, params)
        clampWindowToBounds()
        handler.post(tick)
    }

    private fun hide() {
        handler.removeCallbacksAndMessages(null)
        try {
            root?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        } finally {
            root = null
            bubble = null
            timerText = null
            iconView = null
            menuItems = emptyList()
        }
        stopSelf()
    }

    private fun createBubble(): FrameLayout {
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize).apply {
                leftMargin = bubbleLeftInWindow
                topMargin = bubbleTopInWindow
            }
            background = bubbleBg(isPaused = false)
            elevation = dp(10).toFloat()
        }

        val icon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = dp(12)
            }
            setImageResource(android.R.drawable.presence_video_online)
        }
        iconView = icon

        val t = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply { bottomMargin = dp(10) }
            text = "00:00"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        }
        timerText = t

        container.addView(icon)
        container.addView(t)

        container.setOnTouchListener(DragTouchListener())

        container.setOnClickListener {
            if (docked) {
                undock()
                return@setOnClickListener
            }
            setMenuVisible(!menuVisible, animate = true)
        }

        container.setOnLongClickListener {
            if (docked) {
                undock()
                return@setOnLongClickListener true
            }
            setMenuVisible(!menuVisible, animate = true)
            true
        }

        return container
    }

    private fun createRadialMenu(): List<View> {
        fun menuButton(iconRes: Int, contentDesc: String, onClick: () -> Unit): View {
            return FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(btnSize, btnSize)
                background = menuBtnBg()
                elevation = dp(10).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }

                addView(ImageView(this@OverlayBubbleService).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                    setImageResource(iconRes)
                    contentDescription = contentDesc
                })
            }
        }

        val btnStart = menuButton(android.R.drawable.ic_media_play, "Iniciar gravação") {
            startFromBubble()
            setMenuVisible(false, animate = true)
        }.also { it.tag = "btnStart" }

        val btnToggle = menuButton(android.R.drawable.ic_media_pause, "Pausar/Retomar") {
            togglePauseResume()
            setMenuVisible(false, animate = true)
        }.also { it.tag = "btnToggle" }

        val btnStopClose = menuButton(android.R.drawable.ic_menu_close_clear_cancel, "Parar/Fechar") {
            stopFromBubble()
            setMenuVisible(false, animate = true)
        }.also { it.tag = "btnStop" }

        val btnOpenApp = menuButton(android.R.drawable.ic_menu_manage, "Abrir app") {
            openApp()
            setMenuVisible(false, animate = true)
        }.also { it.tag = "btnOpen" }

        return listOf(btnStart, btnToggle, btnStopClose, btnOpenApp)
    }

    private fun positionRadialMenuItems() {
        val side = currentSideOnScreen()
        val openToRight = (side == Side.LEFT)

        val desiredBubbleLeft = if (openToRight) padding else (winSize - padding - bubbleSize)
        keepBubbleAbsoluteWhileChangingInternalLeft(desiredBubbleLeft)

        val angles = if (openToRight) listOf(-60.0, -20.0, 20.0, 60.0)
                     else listOf(240.0, 200.0, 160.0, 120.0)

        val centerX = bubbleLeftInWindow + bubbleSize / 2
        val centerY = bubbleTopInWindow + bubbleSize / 2

        menuItems.forEachIndexed { idx, v ->
            val rad = Math.toRadians(angles[idx])
            val dx = (cos(rad) * radius).roundToInt()
            val dy = (sin(rad) * radius).roundToInt()

            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = centerX + dx - btnSize / 2
            lp.topMargin = centerY + dy - btnSize / 2
            v.layoutParams = lp
        }
    }

    private fun setMenuVisible(visible: Boolean, animate: Boolean) {
        if (visible) positionRadialMenuItems()

        menuVisible = visible
        menuItems.forEach { item ->
            item.visibility = if (visible) View.VISIBLE else View.GONE
            if (animate) {
                item.alpha = if (visible) 0f else 1f
                item.scaleX = if (visible) 0.7f else 1f
                item.scaleY = if (visible) 0.7f else 1f
                item.animate()
                    .alpha(if (visible) 1f else 0f)
                    .scaleX(if (visible) 1f else 0.7f)
                    .scaleY(if (visible) 1f else 0.7f)
                    .setDuration(140)
                    .start()
            }
        }
    }

    private fun updateUIFromService() {
        val st = ScreenRecordService.state
        val elapsedMs = ScreenRecordService.getElapsedForFlutter()
        val sec = (elapsedMs / 1000).toInt()

        timerText?.text = String.format("%02d:%02d", sec / 60, sec % 60)

        val paused = (st == ScreenRecordService.RecState.PAUSED)
        bubble?.background = bubbleBg(isPaused = paused)

        iconView?.setImageResource(
            if (paused) android.R.drawable.ic_media_pause
            else android.R.drawable.presence_video_online
        )

        val toggle = menuItems.firstOrNull { it.tag == "btnToggle" } as? FrameLayout
        val toggleIcon = toggle?.getChildAt(0) as? ImageView
        toggleIcon?.setImageResource(
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        )

        val startBtn = menuItems.firstOrNull { it.tag == "btnStart" }
        val idle = (st == ScreenRecordService.RecState.IDLE)
        startBtn?.alpha = if (idle) 1f else 0.45f
        startBtn?.isEnabled = idle
    }

    private fun startFromBubble() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(i)
    }

    private fun togglePauseResume() {
        val st = ScreenRecordService.state
        if (st == ScreenRecordService.RecState.RECORDING) {
            startService(Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_PAUSE
            })
        } else if (st == ScreenRecordService.RecState.PAUSED) {
            startService(Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_RESUME
            })
        } else {
            startFromBubble()
        }
    }

    private fun stopFromBubble() {
        startService(Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        })
        hide()
    }

    private fun openApp() {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        openIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (openIntent != null) startActivity(openIntent)
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = params ?: return false

            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    dragging = false
                    if (docked) undockInstant()
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()

                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) {
                        dragging = true
                        setMenuVisible(false, animate = true)
                    }

                    lp.x = initialX + dx
                    lp.y = initialY + dy

                    clampWindowToBounds()
                    wm?.updateViewLayout(root, lp)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToEdgeSpring()
                        return true
                    }
                    return false
                }
            }
            return false
        }
    }

    private fun snapToEdgeSpring() {
        val lp = params ?: return
        val r = displayRect()

        val bubbleAbsX = bubbleAbsoluteX()
        val bubbleCenterX = bubbleAbsX + bubbleSize / 2

        val side = if (bubbleCenterX < (r.width() / 2)) Side.LEFT else Side.RIGHT
        val targetBubbleAbsX = if (side == Side.LEFT) r.left else (r.right - bubbleSize)

        val startWinX = lp.x
        val targetWinX = targetBubbleAbsX - bubbleLeftInWindow
        val dx = targetWinX - startWinX

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                lp.x = (startWinX + dx * t).toInt()
                clampWindowToBounds()
                wm?.updateViewLayout(root, lp)
            }
        }
        anim.start()

        handler.postDelayed({ dockToEdge(side) }, 360)
    }

    private fun dockToEdge(side: Side) {
        val lp = params ?: return
        val r = displayRect()

        docked = true
        dockedSide = side

        val hidden = dp(22)
        val targetBubbleAbsX = if (side == Side.LEFT) (r.left - hidden) else (r.right - bubbleSize + hidden)

        val startWinX = lp.x
        val targetWinX = targetBubbleAbsX - bubbleLeftInWindow
        val dx = targetWinX - startWinX

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = OvershootInterpolator(0.7f)
            addUpdateListener {
                val t = it.animatedValue as Float
                lp.x = (startWinX + dx * t).toInt()
                wm?.updateViewLayout(root, lp)
            }
        }
        anim.start()

        setMenuVisible(false, animate = true)
    }

    private fun undock() {
        val lp = params ?: return
        val r = displayRect()

        docked = false
        val targetBubbleAbsX = if (dockedSide == Side.LEFT) r.left else (r.right - bubbleSize)

        val startWinX = lp.x
        val targetWinX = targetBubbleAbsX - bubbleLeftInWindow
        val dx = targetWinX - startWinX

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = OvershootInterpolator(1.0f)
            addUpdateListener {
                val t = it.animatedValue as Float
                lp.x = (startWinX + dx * t).toInt()
                clampWindowToBounds()
                wm?.updateViewLayout(root, lp)
            }
        }
        anim.start()
    }

    private fun undockInstant() {
        val lp = params ?: return
        val r = displayRect()

        docked = false
        val targetBubbleAbsX = if (dockedSide == Side.LEFT) r.left else (r.right - bubbleSize)

        lp.x = targetBubbleAbsX - bubbleLeftInWindow
        clampWindowToBounds()
        wm?.updateViewLayout(root, lp)
    }

    private fun keepBubbleAbsoluteWhileChangingInternalLeft(newLeft: Int) {
        val lp = params ?: return
        val currentAbs = bubbleAbsoluteX()
        bubbleLeftInWindow = newLeft

        lp.x = currentAbs - bubbleLeftInWindow

        (bubble?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.leftMargin = bubbleLeftInWindow
            it.topMargin = bubbleTopInWindow
            bubble?.layoutParams = it
        }

        clampWindowToBounds()
        wm?.updateViewLayout(root, lp)
    }

    private fun bubbleAbsoluteX(): Int {
        val lp = params ?: return 0
        return lp.x + bubbleLeftInWindow
    }

    private fun currentSideOnScreen(): Side {
        val r = displayRect()
        val centerX = bubbleAbsoluteX() + bubbleSize / 2
        return if (centerX < (r.left + r.width() / 2)) Side.LEFT else Side.RIGHT
    }

    private fun clampWindowToBounds() {
        val lp = params ?: return
        val r = displayRect()

        val bubbleAbsX = lp.x + bubbleLeftInWindow
        val bubbleAbsY = lp.y + bubbleTopInWindow

        val clampedBubbleX = bubbleAbsX.coerceIn(r.left, r.right - bubbleSize)
        val clampedBubbleY = bubbleAbsY.coerceIn(r.top, r.bottom - bubbleSize)

        lp.x = clampedBubbleX - bubbleLeftInWindow
        lp.y = clampedBubbleY - bubbleTopInWindow
    }

    private fun displayRect(): Rect {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        val status = getStatusBarHeight()
        val bottomMargin = dp(16)

        return Rect(0, status, w, h - bottomMargin)
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun bubbleBg(isPaused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), 0x33FFFFFF)
            setColor(if (isPaused) 0xAA4A4A4A.toInt() else 0xAA000000.toInt())
        }
    }

    private fun menuBtnBg(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), 0x22FFFFFF)
            setColor(0xCC111111.toInt())
        }
    }

    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d).roundToInt()
    }
}