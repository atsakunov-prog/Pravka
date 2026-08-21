package ru.zf.pravka.trigger

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.data.Settings

// The Засечка (timesheet) button: Правка's little sibling, drawn from the
// same accessibility service as a second TYPE_ACCESSIBILITY_OVERLAY window.
// The crucial difference from the "П" button: this one is visible ALWAYS,
// not only in text fields - a timesheet must be reachable from anywhere,
// including the home screen. Gestures mirror the big button:
//   short tap  -> record an entry (speak, tap again to stop)
//   long press -> open the Засечка tab
//   drag       -> move (own saved spot, независимо от «П»)
// States: idle ochre "З" / recording red stop / busy spinner / remind pulse
// (a time gap is waiting to be filled - the button itself is the reminder).
class ZasechkaButtonController(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val onShortTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {

    companion object {
        private const val LONG_PRESS_MS = 450L
        private const val TICKER_ALPHA = 0.82f
        private const val TICKER_W_MULT = 6
        private const val TICKER_LINES = 3

        // Editorial family, next shade over: ochre ink instead of red-pen
        // orange, so the two buttons never get confused.
        private val OCHRE = 0xFF8F6A1E.toInt()
        private val AMBER = 0xFFB45309.toInt()      // remind pulse
        private val REC_RED = FloatingButtonController.REC_RED
        private val PAPER = 0xFFF7F3EA.toInt()
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var buttonSize = dp(Settings.FAB_SIZE_DEFAULT)
    private var idleAlpha = Settings.FAB_ALPHA_DEFAULT

    private var button: FrameLayout? = null
    private var background: GradientDrawable? = null
    private var label: TextView? = null
    private var recDot: View? = null
    private var progress: ProgressBar? = null
    private var params: WindowManager.LayoutParams? = null
    private var busy = false
    private var recording = false
    private var reminding = false
    private var enabled = false
    private var pulse: ValueAnimator? = null

    private var ticker: FrameLayout? = null
    private var tickerText: TextView? = null
    private var tickerParams: WindowManager.LayoutParams? = null
    private var tickerVisible = false

    private fun dp(value: Int): Int = (value * density).toInt()

    private var cachedScreen: Pair<Int, Int>? = null

    private fun screenSize(): Pair<Int, Int> = cachedScreen ?: run {
        val bounds = windowManager.currentWindowMetrics.bounds
        (bounds.width() to bounds.height()).also { cachedScreen = it }
    }

    private fun positionKey(): String {
        val (w, h) = screenSize()
        return "${w}x$h"
    }

    /** The settings toggle: on = the button lives on screen permanently. */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) {
            if (button == null) create()
            button?.visibility = View.VISIBLE
        } else {
            button?.visibility = View.GONE
        }
    }

    fun setBusy(value: Boolean) {
        busy = value
        label?.visibility = if (value || recording) View.GONE else View.VISIBLE
        progress?.visibility = if (value) View.VISIBLE else View.GONE
        applyIdleLook()
    }

    /** Recording: red stop glyph at full opacity, like the big button. */
    fun setRecording(value: Boolean) {
        recording = value
        recDot?.visibility = if (value) View.VISIBLE else View.GONE
        label?.visibility = if (value || busy) View.GONE else View.VISIBLE
        applyIdleLook()
    }

    /** A gap in the timesheet is waiting: amber pulse until an entry lands. */
    fun setRemind(value: Boolean) {
        if (reminding == value) return
        reminding = value
        applyIdleLook()
    }

    // One place decides color/alpha/pulse from the state triple, so the
    // states can flip in any order without leaving a stale look behind.
    private fun applyIdleLook() {
        val b = button ?: return
        pulse?.cancel()
        pulse = null
        when {
            recording -> {
                background?.setColor(REC_RED)
                b.alpha = 1f
            }
            busy -> {
                background?.setColor(OCHRE)
                b.alpha = 1f
            }
            reminding -> {
                background?.setColor(AMBER)
                pulse = ValueAnimator.ofFloat(0.45f, 1f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener { b.alpha = it.animatedValue as Float }
                    start()
                }
            }
            else -> {
                background?.setColor(OCHRE)
                b.alpha = idleAlpha
            }
        }
    }

    fun onConfigurationChanged() {
        cachedScreen = null
        val p = params ?: return
        scope.launch {
            val (xFraction, yFraction) = settings.zFabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
            repositionTickerIfVisible()
        }
    }

    // ---- Mini-ticker: live words while an entry is being dictated ----

    fun showTicker() {
        if (ticker == null) createTicker()
        positionTicker()
        val t = ticker ?: return
        tickerText?.text = ""
        lastTickerText = ""
        lastTickerAt = 0L
        runCatching { windowManager.updateViewLayout(t, tickerParams) }
        if (!tickerVisible) {
            tickerVisible = true
            t.visibility = View.VISIBLE
            t.alpha = 0f
            t.animate().alpha(TICKER_ALPHA).setDuration(180).start()
        }
    }

    private fun tickerHeightPx(): Int = dp(TICKER_LINES * 24 + 16)

    private var lastTickerText = ""
    private var lastTickerAt = 0L

    fun updateTicker(text: String) {
        val tv = tickerText ?: return
        val tail = text.takeLast(300)
        if (tail == lastTickerText) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTickerAt < 120) return
        lastTickerAt = now
        lastTickerText = tail
        tv.text = tail
        // Same multi-line START-ellipsize trap as the big ticker: trim leading
        // lines after layout so the newest words stay visible.
        tv.post {
            val layout = tv.layout ?: return@post
            if (layout.lineCount > TICKER_LINES) {
                val cut = layout.getLineStart(layout.lineCount - TICKER_LINES)
                val current = tv.text?.toString() ?: return@post
                if (cut in 1 until current.length) tv.text = current.substring(cut)
            }
        }
    }

    fun repositionTickerIfVisible() {
        if (!tickerVisible) return
        positionTicker()
        ticker?.let { runCatching { windowManager.updateViewLayout(it, tickerParams) } }
    }

    fun hideTicker() {
        val t = ticker ?: return
        if (!tickerVisible) return
        tickerVisible = false
        t.animate().alpha(0f).setDuration(220).withEndAction {
            if (!tickerVisible) t.visibility = View.GONE
        }.start()
    }

    fun destroy() {
        pulse?.cancel()
        pulse = null
        button?.let { runCatching { windowManager.removeView(it) } }
        button = null
        ticker?.let { runCatching { windowManager.removeView(it) } }
        ticker = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create() {
        val container = FrameLayout(service)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(OCHRE)
        }
        background = bg
        container.background = bg
        container.elevation = dp(4).toFloat()
        container.alpha = idleAlpha

        // The "З" mark - same serif black weight as the "П" brand glyph.
        label = TextView(service).apply {
            text = "З"
            setTextColor(PAPER)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SERIF,
                android.graphics.Typeface.BOLD,
            )
            textSize = 20f
            gravity = Gravity.CENTER
        }
        container.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        recDot = View(service).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(PAPER)
                cornerRadius = dp(3).toFloat()
            }
        }
        val dotSize = dp(16)
        container.addView(recDot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER))

        progress = ProgressBar(service).apply {
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(PAPER)
        }
        val progressSize = dp(28)
        container.addView(
            progress,
            FrameLayout.LayoutParams(progressSize, progressSize, Gravity.CENTER),
        )

        val p = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (w, h) = screenSize()
            x = w - buttonSize
            y = (h * 0.62f).toInt()
        }
        params = p
        button = container
        windowManager.addView(container, p)
        container.visibility = if (enabled) View.VISIBLE else View.GONE

        scope.launch {
            val (xFraction, yFraction) = settings.zFabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            runCatching { windowManager.updateViewLayout(container, p) }
        }
        // Same size/alpha knobs as the big button - one look for the pair.
        scope.launch {
            settings.fabSizeFlow.collect { sizeDp ->
                buttonSize = dp(sizeDp)
                p.width = buttonSize
                p.height = buttonSize
                runCatching { windowManager.updateViewLayout(container, p) }
            }
        }
        scope.launch {
            settings.fabAlphaFlow.collect { alpha ->
                idleAlpha = alpha
                if (!busy && !recording && !reminding) container.alpha = idleAlpha
            }
        }

        container.setOnTouchListener(DragTouchListener())
    }

    private fun applyPosition(p: WindowManager.LayoutParams, xFraction: Float, yFraction: Float) {
        val (w, h) = screenSize()
        p.x = ((w - buttonSize) * xFraction.coerceIn(0f, 1f)).toInt()
        p.y = ((h - buttonSize) * yFraction.coerceIn(0f, 1f)).toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTicker() {
        val pill = FrameLayout(service)
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = buttonSize / 2f
            setColor(OCHRE)
        }
        pill.elevation = dp(4).toFloat()
        val tv = TextView(service).apply {
            setTextColor(PAPER)
            textSize = 17f
            maxLines = TICKER_LINES
            gravity = Gravity.BOTTOM or Gravity.START
            val padH = dp(16)
            val padV = dp(8)
            setPadding(padH, padV, padH, padV)
            setLineSpacing(0f, 1.05f)
        }
        tickerText = tv
        pill.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val p = WindowManager.LayoutParams(
            buttonSize * TICKER_W_MULT,
            tickerHeightPx(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        tickerParams = p
        ticker = pill
        runCatching { windowManager.addView(pill, p) }
        pill.visibility = View.GONE
    }

    private fun positionTicker() {
        val bp = params ?: return
        val tp = tickerParams ?: return
        val (w, h) = screenSize()
        val tickerW = buttonSize * TICKER_W_MULT
        val tickerH = tickerHeightPx()
        val gap = dp(8)
        tp.width = tickerW
        tp.height = tickerH
        tp.y = (bp.y - (tickerH - buttonSize) / 2).coerceIn(0, (h - tickerH).coerceAtLeast(0))
        val buttonCenterX = bp.x + buttonSize / 2
        tp.x = if (buttonCenterX < w / 2) bp.x + buttonSize + gap
        else bp.x - tickerW - gap
        tp.x = tp.x.coerceIn(0, (w - tickerW).coerceAtLeast(0))
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressFired = false
        private val longPressRunnable = Runnable {
            longPressFired = true
            if (!busy && !recording) onLongPress()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    longPressFired = false
                    pulse?.cancel()
                    view.alpha = 1f
                    view.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        view.removeCallbacks(longPressRunnable)
                    }
                    if (dragging && !longPressFired) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, p) }
                        repositionTickerIfVisible()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    applyIdleLook()
                    if (dragging) {
                        savePosition(view, p)
                    } else if (!longPressFired && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (!busy) onShortTap()
                    }
                }
            }
            return true
        }
    }

    private fun savePosition(view: View, p: WindowManager.LayoutParams) {
        val (w, h) = screenSize()
        p.x = p.x.coerceIn(0, w - buttonSize)
        p.y = p.y.coerceIn(0, h - buttonSize)
        runCatching { windowManager.updateViewLayout(view, p) }
        val xFraction = p.x.toFloat() / (w - buttonSize).coerceAtLeast(1)
        val yFraction = p.y.toFloat() / (h - buttonSize).coerceAtLeast(1)
        scope.launch { settings.setZFabPosition(positionKey(), xFraction, yFraction) }
    }
}
