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
        private const val TICKER_ALPHA = 0.82f  // near-opaque, owner found 0.6 too see-through
        private const val TICKER_W_MULT = 6     // width in button-diameters
        private const val TICKER_LINES = 4      // teleprompter: up to four lines tall

        // Editorial palette shared with ui/Theme.kt and the launcher icon:
        // orange circle, paper-white geometric "П"; deep red while recording.
        val ACCENT = 0xFFEA580C.toInt()
        val REC_RED = 0xFFD8342A.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
        private val GRAY = 0xFF6E6659.toInt()  // ink-soft: the cancel bubble
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
    // Long-press menu: a vertical stack of pills beside the button.
    private var menu: android.widget.LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var menuVisible = false
    private val menuDismiss = Runnable { hideMenu() }

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
            // Pair placement (owner's design): the "П" appears docked right
            // above the "З", so the two read as one linked pair of bubbles.
            pairAnchor?.invoke()?.let { (x, y) ->
                params?.let { p ->
                    val (w, h) = screenSize()
                    p.x = x.coerceIn(0, (w - buttonSize).coerceAtLeast(0))
                    p.y = y.coerceIn(0, (h - buttonSize).coerceAtLeast(0))
                    button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
                }
            }
            button?.visibility = View.VISIBLE
            visible = true
        }
        if (badgeWanted) {
            repositionLearnBadge()
            learnBadge?.visibility = View.VISIBLE
        }
    }

    // ---- Elastic pair: trail the "З" button on a rubber band ----

    /** Fired while the owner drags THIS button (and once more on drop). */
    var onDragged: ((x: Int, y: Int, dropped: Boolean) -> Unit)? = null

    /** Where this button should appear when it shows up (docked over "З"). */
    var pairAnchor: (() -> Pair<Int, Int>?)? = null

    fun currentPosition(): Pair<Int, Int>? = params?.let { it.x to it.y }

    fun buttonSizePx(): Int = buttonSize

    private var followTargetX = 0
    private var followTargetY = 0
    private var followSettle = false
    private var following = false
    private val followStep = object : Runnable {
        override fun run() {
            val p = params ?: return
            val view = button ?: return
            val dx = followTargetX - p.x
            val dy = followTargetY - p.y
            if (abs(dx) <= 2 && abs(dy) <= 2) {
                p.x = followTargetX
                p.y = followTargetY
                runCatching { windowManager.updateViewLayout(view, p) }
                repositionTickerIfVisible()
                repositionLearnBadge()
                following = false
                if (followSettle) savePosition(view, p)
                return
            }
            p.x += followInc(dx)
            p.y += followInc(dy)
            runCatching { windowManager.updateViewLayout(view, p) }
            repositionTickerIfVisible()
            repositionLearnBadge()
            view.postDelayed(this, 16)
        }
    }

    // ~30% of the remaining distance per frame - the rubber-band feel.
    private fun followInc(d: Int): Int {
        val step = (d * 0.30f).toInt()
        return if (step != 0) step else if (d > 0) 1 else -1
    }

    /** No-op while hidden: an off-screen "П" must not chase the "З". */
    fun followTo(x: Int, y: Int, settle: Boolean) {
        if (!visible) return
        val view = button ?: return
        val (w, h) = screenSize()
        followTargetX = x.coerceIn(0, (w - buttonSize).coerceAtLeast(0))
        followTargetY = y.coerceIn(0, (h - buttonSize).coerceAtLeast(0))
        followSettle = settle
        if (!following) {
            following = true
            view.removeCallbacks(followStep)
            view.post(followStep)
        }
    }

    fun hide() {
        // While recording the stop button must stay up in every app.
        if (recording) return
        if (visible && !busy) {
            button?.visibility = View.GONE
            visible = false
            learnBadge?.visibility = View.GONE
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
        background?.setColor(if (value) REC_RED else ACCENT)
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
            repositionLearnBadge()
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
        // ellipsize=START is silently ignored on a multi-line TextView, so once
        // the text exceeded 4 lines the view showed the FIRST 4 lines forever -
        // the newest words never appeared (read as huge recognition lag). Trim
        // leading lines after layout so the tail is what stays visible.
        tv.post {
            val layout = tv.layout ?: return@post
            if (layout.lineCount > TICKER_LINES) {
                val cut = layout.getLineStart(layout.lineCount - TICKER_LINES)
                val current = tv.text?.toString() ?: return@post
                if (cut in 1 until current.length) tv.text = current.substring(cut)
            }
        }
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
            // hide -> immediate re-show (dictation ends, CLEAN streaming starts)
            // cancels this fade; the end action can still run and must not hide
            // the ticker that showTicker() just brought back.
            if (!tickerVisible) t.visibility = View.GONE
        }.start()
    }

    // ---- Long-press menu: colored columns side by side ----

    class MenuItem(val label: String, val color: Int, val onClick: () -> Unit)

    fun toggleMenu(groups: List<List<MenuItem>>) {
        if (menuVisible) hideMenu() else showMenu(groups)
    }

    fun hideMenu() {
        val m = menu ?: return
        menuVisible = false
        m.removeCallbacks(menuDismiss)
        runCatching { windowManager.removeView(m) }
        menu = null
    }

    private fun showMenu(groups: List<List<MenuItem>>) {
        hideMenu()
        // Editing actions in red, AI actions in orange - two columns side by
        // side (owner's design).
        val row = android.widget.LinearLayout(service).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        for (group in groups) {
            val column = android.widget.LinearLayout(service).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            for (item in group) {
                val pill = android.widget.TextView(service).apply {
                    text = item.label
                    setTextColor(PAPER)
                    textSize = 15f
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(item.color)
                    }
                    alpha = 0.92f
                    val padH = dp(16)
                    val padV = dp(9)
                    setPadding(padH, padV, padH, padV)
                    setOnClickListener {
                        hideMenu()
                        item.onClick()
                    }
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
                column.addView(pill, lp)
            }
            val clp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            row.addView(column, clp)
        }
        val column = row
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        // Beside the button, on the side with room (same rule as the ticker),
        // vertically clamped on screen.
        val bp = params
        val (w, h) = screenSize()
        if (bp != null) {
            val buttonCenterX = bp.x + buttonSize / 2
            p.x = if (buttonCenterX < w / 2) bp.x + buttonSize + dp(8)
            else (bp.x - dp(180)).coerceAtLeast(0)
            p.y = bp.y.coerceIn(0, (h - dp(48) * (groups.maxOfOrNull { it.size } ?: 1)).coerceAtLeast(0))
        }
        menuParams = p
        menu = column
        menuVisible = true
        runCatching { windowManager.addView(column, p) }
        // Not modal (the overlay can't see outside taps) - fade away on its own.
        column.postDelayed(menuDismiss, 6000)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTicker() {
        val pill = FrameLayout(service)
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = buttonSize / 2f
            setColor(ACCENT)
        }
        pill.elevation = dp(4).toFloat()
        val tv = android.widget.TextView(service).apply {
            setTextColor(PAPER)
            textSize = 17f
            maxLines = TICKER_LINES
            // NOTE: TruncateAt.START is ignored on multi-line TextViews - the
            // tail-trimming in updateTicker() is what keeps new words visible.
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

    // ---- Learn badge: 💡 (есть предложения) / ⭐ (новое правило) above the
    // button - "у неё идея возникла". Tap opens the learning section. ----

    private var learnBadge: android.widget.TextView? = null
    private var learnBadgeParams: WindowManager.LayoutParams? = null
    private var badgeWanted = false

    fun showLearnBadge(emoji: String, onTap: () -> Unit) {
        badgeWanted = true
        if (learnBadge == null) {
            val pill = android.widget.TextView(service).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            learnBadgeParams = p
            learnBadge = pill
            runCatching { windowManager.addView(pill, p) }
        }
        learnBadge?.text = emoji
        learnBadge?.setOnClickListener { onTap() }
        repositionLearnBadge()
        learnBadge?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun hideLearnBadge() {
        badgeWanted = false
        learnBadge?.visibility = View.GONE
    }

    /** Perches on the button's top-right corner, clamped on screen. */
    private fun repositionLearnBadge() {
        val bp = params ?: return
        val p = learnBadgeParams ?: return
        val (w, _) = screenSize()
        p.x = (bp.x + buttonSize - dp(14)).coerceIn(0, (w - dp(30)).coerceAtLeast(0))
        p.y = (bp.y - dp(26)).coerceAtLeast(0)
        learnBadge?.let { runCatching { windowManager.updateViewLayout(it, p) } }
    }

    // ---- The gray "отмена" bubble, shown only while recording ----

    private var cancelBubble: android.widget.TextView? = null

    fun showCancelBubble(onCancel: () -> Unit) {
        hideCancelBubble()
        val bp = params ?: return
        val pill = android.widget.TextView(service).apply {
            text = "отмена"
            setTextColor(PAPER)
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(GRAY)
            }
            alpha = 0.9f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { onCancel() }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (w, h) = screenSize()
            // Right under the button, clamped on screen.
            x = bp.x.coerceIn(0, (w - dp(90)).coerceAtLeast(0))
            y = (bp.y + buttonSize + dp(8)).coerceAtMost(h - dp(44))
        }
        cancelBubble = pill
        runCatching { windowManager.addView(pill, p) }
    }

    fun hideCancelBubble() {
        cancelBubble?.let { runCatching { windowManager.removeView(it) } }
        cancelBubble = null
    }

    fun destroy() {
        learnBadge?.let { runCatching { windowManager.removeView(it) } }
        learnBadge = null
        hideCancelBubble()
        hideMenu()
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
            setColor(ACCENT)
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
                        repositionLearnBadge()
                        onDragged?.invoke(p.x, p.y, false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    if (!busy && !recording) view.alpha = idleAlpha
                    if (dragging) {
                        savePosition(view, p)
                        onDragged?.invoke(p.x, p.y, true)
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
