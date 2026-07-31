package ru.zf.pravka.trigger

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.R
import ru.zf.pravka.data.Settings

// Floating button drawn from the accessibility service as a
// TYPE_ACCESSIBILITY_OVERLAY window (spec 5.3): no SYSTEM_ALERT_WINDOW
// permission. Gestures (owner's decision):
//   short tap  -> dictate (record, transcribe, fix)
//   long press -> fix the text already in the field (CLEAN)
//   drag       -> move (free positioning, saved per screen size)
// While recording it turns into a red stop button and stays visible in
// EVERY app, not only when a field is focused (Wispr-style).
class FloatingButtonController(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val onShortTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {

    companion object {
        private const val LONG_PRESS_MS = 450L
        private const val TICKER_ALPHA = 0.6f   // a touch see-through, still readable
        private const val TICKER_W_MULT = 6     // width in button-diameters
        private const val TICKER_LINES = 4      // teleprompter: up to four lines tall

        // Editorial palette shared with ui/Theme.kt and the launcher icon:
        // vermilion circle, paper-white geometric "П"; deep red while recording.
        private val VERMILION = 0xFFC13B2A.toInt()
        private val REC_RED = 0xFFD8342A.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var buttonSize = dp(Settings.FAB_SIZE_DEFAULT)
    private var idleAlpha = Settings.FAB_ALPHA_DEFAULT

    private var button: FrameLayout? = null
    private var background: GradientDrawable? = null
    private var label: ImageView? = null
    private var recDot: View? = null
    private var progress: ProgressBar? = null
    private var params: WindowManager.LayoutParams? = null
    private var busy = false
    private var recording = false
    private var visible = false

    // Live-dictation "telegraph": a translucent pill next to the button where
    // recognized words crawl by (marquee) while the Google engine listens.
    private var ticker: FrameLayout? = null
    private var tickerText: android.widget.TextView? = null
    private var tickerParams: WindowManager.LayoutParams? = null
    private var tickerVisible = false

    private fun dp(value: Int): Int = (value * density).toInt()

    // currentWindowMetrics is a binder call to WindowManagerService, and this is
    // read on every drag frame and every reposition. The bounds only change on a
    // configuration change (fold/rotate), so cache and invalidate there.
    private var cachedScreen: Pair<Int, Int>? = null

    private fun screenSize(): Pair<Int, Int> = cachedScreen ?: run {
        val bounds = windowManager.currentWindowMetrics.bounds
        (bounds.width() to bounds.height()).also { cachedScreen = it }
    }

    private fun positionKey(): String {
        val (w, h) = screenSize()
        return "${w}x$h"
    }

    fun show() {
        if (button == null) create()
        if (!visible) {
            button?.visibility = View.VISIBLE
            visible = true
        }
    }

    fun hide() {
        // While recording the stop button must stay up in every app.
        if (recording) return
        if (visible && !busy) {
            button?.visibility = View.GONE
            visible = false
        }
    }

    fun setBusy(value: Boolean) {
        busy = value
        label?.visibility = if (value || recording) View.GONE else View.VISIBLE
        progress?.visibility = if (value) View.VISIBLE else View.GONE
        button?.alpha = if (value) 1f else idleAlpha
    }

    /** Recording on: red stop dot, full opacity, pinned visible everywhere. */
    fun setRecording(value: Boolean) {
        recording = value
        background?.setColor(if (value) REC_RED else VERMILION)
        recDot?.visibility = if (value) View.VISIBLE else View.GONE
        label?.visibility = if (value || busy) View.GONE else View.VISIBLE
        button?.alpha = if (value) 1f else idleAlpha
        if (value) show()
    }

    fun onConfigurationChanged() {
        cachedScreen = null  // fold/rotate: re-measure once
        val p = params ?: return
        scope.launch {
            val (xFraction, yFraction) = settings.fabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
            repositionTickerIfVisible()
        }
    }

    // ---- Live-dictation ticker (telegraph) ----

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

    // Height that fits TICKER_LINES lines of the ticker text plus padding.
    private fun tickerHeightPx(): Int = dp(TICKER_LINES * 24 + 16)

    private var lastTickerText = ""
    private var lastTickerAt = 0L

    fun updateTicker(text: String) {
        val tv = tickerText ?: return
        // Partials arrive several times a second, and each assignment forces a
        // full measure/layout/draw of a 4-line START-ellipsized TextView. Cap the
        // refresh rate and skip identical text (the recognizer re-emits the same
        // partial often), so the overlay stops competing with recognition for the
        // main thread.
        val tail = text.takeLast(400)
        if (tail == lastTickerText) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTickerAt < 120) return
        lastTickerAt = now
        lastTickerText = tail
        // Steady, bottom-anchored tail (teleprompter): newest words sit on the
        // bottom line, older lines ride up and off the top. No per-update
        // animation (the earlier "settle" nudge read as a jump-down).
        tv.text = tail
    }

    /** Keep the pill glued to the button while it's dragged / on rotate. */
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
            t.visibility = View.GONE
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTicker() {
        val pill = FrameLayout(service)
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = buttonSize / 2f
            setColor(VERMILION)
        }
        pill.elevation = dp(4).toFloat()
        val tv = android.widget.TextView(service).apply {
            setTextColor(PAPER)
            textSize = 17f
            maxLines = TICKER_LINES
            // Keep the newest words visible and truncate the OLD start with a
            // leading ellipsis - no jump-back-to-start that marquee had.
            ellipsize = android.text.TextUtils.TruncateAt.START
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

    // Sit the pill beside the button, on the side that has room: button near
    // the left edge -> ticker to its right, and vice versa.
    private fun positionTicker() {
        val bp = params ?: return
        val tp = tickerParams ?: return
        val (w, h) = screenSize()
        val tickerW = buttonSize * TICKER_W_MULT
        val tickerH = tickerHeightPx()
        val gap = dp(8)
        tp.width = tickerW
        tp.height = tickerH
        // Vertically centre the (taller) pill on the button.
        tp.y = (bp.y - (tickerH - buttonSize) / 2).coerceIn(0, (h - tickerH).coerceAtLeast(0))
        val buttonCenterX = bp.x + buttonSize / 2
        tp.x = if (buttonCenterX < w / 2) bp.x + buttonSize + gap
        else bp.x - tickerW - gap
        tp.x = tp.x.coerceIn(0, (w - tickerW).coerceAtLeast(0))
    }

    fun destroy() {
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
            setColor(VERMILION)
        }
        background = bg
        container.background = bg
        container.elevation = dp(4).toFloat()
        container.alpha = idleAlpha

        label = ImageView(service).apply {
            setImageResource(R.drawable.ic_fab_glyph)
        }
        container.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // White square "stop" glyph, shown only while recording.
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
            y = h / 2
        }
        params = p
        button = container
        windowManager.addView(container, p)
        container.visibility = View.GONE

        scope.launch {
            val (xFraction, yFraction) = settings.fabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            runCatching { windowManager.updateViewLayout(container, p) }
        }
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
                if (!busy && !recording) container.alpha = idleAlpha
            }
        }

        container.setOnTouchListener(DragTouchListener())
    }

    private fun applyPosition(p: WindowManager.LayoutParams, xFraction: Float, yFraction: Float) {
        val (w, h) = screenSize()
        p.x = ((w - buttonSize) * xFraction.coerceIn(0f, 1f)).toInt()
        p.y = ((h - buttonSize) * yFraction.coerceIn(0f, 1f)).toInt()
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
            // Long press = fix the field; not available while recording.
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
                        repositionTickerIfVisible()  // the pill rides along
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    if (!busy && !recording) view.alpha = idleAlpha
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

    // Free positioning - the button stays exactly where the owner drops it.
    private fun savePosition(view: View, p: WindowManager.LayoutParams) {
        val (w, h) = screenSize()
        p.x = p.x.coerceIn(0, w - buttonSize)
        p.y = p.y.coerceIn(0, h - buttonSize)
        runCatching { windowManager.updateViewLayout(view, p) }
        val xFraction = p.x.toFloat() / (w - buttonSize).coerceAtLeast(1)
        val yFraction = p.y.toFloat() / (h - buttonSize).coerceAtLeast(1)
        scope.launch { settings.setFabPosition(positionKey(), xFraction, yFraction) }
    }
}
