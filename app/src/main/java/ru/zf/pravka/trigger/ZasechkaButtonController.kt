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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.R
import ru.zf.pravka.data.Settings

// The Засечка (timesheet) button: Правка's little sibling, drawn from the
// same accessibility service as a second TYPE_ACCESSIBILITY_OVERLAY window.
// The crucial difference from the "П" button: this one is visible ALWAYS,
// not only in text fields - a timesheet must be reachable from anywhere,
// including the home screen. Gestures mirror the big button:
//   short tap  -> record an entry (speak, tap again to stop)
//   long press -> pomodoro menu + open the tab
//   drag       -> move; the "П" trails behind on a rubber band (owner's
//                 design: the two buttons travel as a linked pair)
// States: idle amber "З" / recording red stop / busy spinner / remind pulse
// (a time gap is waiting) / pomodoro countdown (the glyph becomes minutes).
class ZasechkaButtonController(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val onShortTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {

    companion object {
        private const val LONG_PRESS_MS = 450L
        private const val TICKER_ALPHA = 0.86f
        private const val TICKER_W_MULT = 6
        private const val TICKER_LINES = 3

        // Warm pair with the "П": red-orange pen there, a marker halfway
        // between orange and yellow here (owner tuned it twice - this is the
        // midpoint) - same paper-white glyph on both.
        private val AMBER = 0xFFF78810.toInt()
        private val AMBER_DEEP = 0xFFEA580C.toInt()   // remind pulse
        private val REC_RED = FloatingButtonController.REC_RED
        private val PAPER = 0xFFF7F3EA.toInt()
        // Записка «не смог»: тот же красный, что у записи, — цвет уже значит
        // «внимание сюда», второй заводить незачем.
        private val NOTE_BAD = REC_RED

        // Pomodoro faces: pine for focus, ink-soft for the break.
        val POMO_FOCUS = 0xFF2F6B5E.toInt()
        val POMO_BREAK = 0xFF6E6659.toInt()
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var buttonSize = dp(Settings.FAB_SIZE_DEFAULT)
    private var idleAlpha = Settings.FAB_ALPHA_DEFAULT

    private var button: FrameLayout? = null
    private var background: GradientDrawable? = null
    private var glyph: ImageView? = null
    private var counter: TextView? = null   // pomodoro minutes
    private var recDot: View? = null
    private var progress: ProgressBar? = null
    private var params: WindowManager.LayoutParams? = null
    private var busy = false
    private var recording = false
    private var reminding = false
    private var enabled = false
    private var pomodoroText: String? = null
    private var pomodoroColor: Int? = null
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
        progress?.visibility = if (value) View.VISIBLE else View.GONE
        applyFaceAndLook()
    }

    /** Recording: red stop glyph at full opacity, like the big button. */
    fun setRecording(value: Boolean) {
        recording = value
        recDot?.visibility = if (value) View.VISIBLE else View.GONE
        applyFaceAndLook()
    }

    /** A gap in the timesheet is waiting: amber pulse until an entry lands. */
    fun setRemind(value: Boolean) {
        if (reminding == value) return
        reminding = value
        applyFaceAndLook()
    }

    /**
     * The hourly wink (owner's request): three soft dips of alpha, then back
     * to the steady look - "я всё ещё считаю вот это". No-op while any louder
     * state (busy/recording/gap-remind) owns the button.
     */
    fun blinkOnce() {
        val b = button ?: return
        if (busy || recording || reminding) return
        pulse?.cancel()
        pulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
            duration = 450
            repeatCount = 5
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { b.alpha = it.animatedValue as Float }
            start()
        }
        b.postDelayed({ applyFaceAndLook() }, 3_000)
    }

    /** Перечитать глиф после переключения «иконки вместо букв». */
    fun refreshGlyph() {
        glyph?.setImageResource(ModeGlyphs.zasechka())
    }

    /** text = minutes left ("17"); null returns the "З" glyph. */
    fun setPomodoro(text: String?, color: Int?) {
        pomodoroText = text
        pomodoroColor = if (text != null) color else null
        counter?.text = text ?: ""
        applyFaceAndLook()
    }

    // One place decides face (glyph/counter/dot/spinner) and color/alpha from
    // the state set, so states can flip in any order without a stale look.
    private fun applyFaceAndLook() {
        val b = button ?: return
        glyph?.visibility =
            if (!busy && !recording && pomodoroText == null) View.VISIBLE else View.GONE
        counter?.visibility =
            if (!busy && !recording && pomodoroText != null) View.VISIBLE else View.GONE
        pulse?.cancel()
        pulse = null
        val pomo = pomodoroColor
        when {
            recording -> {
                background?.setColor(REC_RED)
                b.alpha = 1f
            }
            busy -> {
                background?.setColor(AMBER)
                b.alpha = 1f
            }
            reminding -> {
                background?.setColor(AMBER_DEEP)
                pulse = ValueAnimator.ofFloat(0.45f, 1f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener { b.alpha = it.animatedValue as Float }
                    start()
                }
            }
            pomo != null -> {
                background?.setColor(pomo)
                b.alpha = 0.92f
            }
            else -> {
                background?.setColor(AMBER)
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

    // ---- Elastic pair: this button can trail the other on a rubber band ----

    /** Fired while the owner drags THIS button (and once more on drop). */
    var onDragged: ((x: Int, y: Int, dropped: Boolean) -> Unit)? = null


    /**
     * Кнопка убрана в ручку — то есть спрятана по-настоящему, а не приглушена.
     * Схлопывается к точке, оттуда же и выезжает.
     */
    fun setStacked(value: Boolean) {
        val v = button ?: return
        if (value) {
            v.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150)
                .withEndAction { v.visibility = View.GONE }
                .start()
        } else {
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        }
    }

    // ---- Значок обучения: ⭐ над кнопкой, пока предложенные правила ждут
    // суда владельца. Тот же приём, что у «П» (FloatingButtonController):
    // приложение не дёргает уведомлением, а просто показывает, что ей есть
    // что сказать, — и тап ведёт туда, где судят. ----

    private var learnBadge: android.widget.TextView? = null
    private var learnBadgeParams: WindowManager.LayoutParams? = null

    fun showLearnBadge(emoji: String, onTap: () -> Unit) {
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
    }

    fun hideLearnBadge() {
        learnBadge?.let { runCatching { windowManager.removeView(it) } }
        learnBadge = null
        learnBadgeParams = null
    }

    /** Садится на правый верхний угол кнопки, не вылезая за экран. */
    private fun repositionLearnBadge() {
        val bp = params ?: return
        val p = learnBadgeParams ?: return
        val (w, _) = screenSize()
        p.x = (bp.x + buttonSize - dp(14)).coerceIn(0, (w - dp(30)).coerceAtLeast(0))
        p.y = (bp.y - dp(26)).coerceAtLeast(0)
        learnBadge?.let { runCatching { windowManager.updateViewLayout(it, p) } }
    }

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
                following = false
                if (followSettle) savePosition(view, p)
                return
            }
            p.x += followInc(dx)
            p.y += followInc(dy)
            runCatching { windowManager.updateViewLayout(view, p) }
            repositionTickerIfVisible()
            view.postDelayed(this, 16)
        }
    }

    // ~30% of the remaining distance per frame: fast at first, soft landing -
    // reads as a rubber band chasing the dragged button.
    private fun followInc(d: Int): Int {
        val step = (d * 0.30f).toInt()
        return if (step != 0) step else if (d > 0) 1 else -1
    }

    fun followTo(x: Int, y: Int, settle: Boolean) {
        if (!enabled) return
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
            // Same rule as the "П" plate: a hidden overlay window still
            // costs a relayout in every display transition, so it leaves
            // WindowManager and is rebuilt on the next show.
            if (!tickerVisible) {
                ticker?.let { runCatching { windowManager.removeView(it) } }
                ticker = null
                tickerText = null
                tickerParams = null
                lastTickerText = ""
                lastTickerAt = 0L
            }
        }.start()
    }

    // ---- Записка: что именно записалось, на две секунды ----
    //
    // Владелец: «засечка должна баблом на 2 секунды показывать, что за дело
    // записано. и что за дело исправлено и как». Раньше это был тост — а тост
    // на современном Android коротким не сделаешь, он душится системой при
    // частых показах и не даёт ни двух строк, ни цвета. Своя записка рядом с
    // кнопкой: две строки, свой цвет на удачу и на ошибку, свои две секунды.

    private var note: android.widget.TextView? = null
    private val noteDismiss = Runnable { hideNote() }

    fun hideNote() {
        val n = note ?: return
        n.removeCallbacks(noteDismiss)
        note = null
        n.animate().alpha(0f).setDuration(160).withEndAction {
            runCatching { windowManager.removeView(n) }
        }.start()
    }

    /** [ok] = false красит записку в красный: это не «записал», а «не смог». */
    fun showNote(text: String, ok: Boolean = true, holdMs: Long = 2_000) {
        hideNote()
        val view = TextView(service).apply {
            this.text = text
            setTextColor(PAPER)
            textSize = 14f
            maxLines = 3
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (ok) AMBER else NOTE_BAD)
            }
            alpha = 0f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { hideNote() }
        }
        val p = WindowManager.LayoutParams(
            tickerWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val bp = params
        val (w, h) = screenSize()
        if (bp != null) {
            val centerX = bp.x + buttonSize / 2
            p.x = if (centerX < w / 2) bp.x else (bp.x + buttonSize - tickerWidthPx()).coerceAtLeast(dp(8))
            // Над кнопкой, если она в нижней половине, иначе под ней: записка
            // не должна закрывать сам палец.
            p.y = if (bp.y > h / 2) (bp.y - dp(64)).coerceAtLeast(0) else bp.y + buttonSize + dp(8)
        }
        note = view
        runCatching { windowManager.addView(view, p) }
        view.animate().alpha(0.97f).setDuration(140).start()
        view.postDelayed(noteDismiss, holdMs)
    }

    // ---- Long-press menu: a single column of amber pills ----

    class MenuItem(val label: String, val onClick: () -> Unit)

    private var menu: android.widget.LinearLayout? = null
    private val menuDismiss = Runnable { hideMenu() }

    fun hideMenu() {
        val m = menu ?: return
        m.removeCallbacks(menuDismiss)
        runCatching { windowManager.removeView(m) }
        menu = null
    }

    fun showMenu(items: List<MenuItem>) {
        hideMenu()
        val column = android.widget.LinearLayout(service).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        for (item in items) {
            val pill = TextView(service).apply {
                text = item.label
                setTextColor(PAPER)
                textSize = 15f
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(AMBER)
                }
                alpha = 0.96f
                setPadding(dp(16), dp(9), dp(16), dp(9))
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
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val bp = params
        val (w, h) = screenSize()
        if (bp != null) {
            val buttonCenterX = bp.x + buttonSize / 2
            p.x = if (buttonCenterX < w / 2) bp.x + buttonSize + dp(8)
            else (bp.x - dp(170)).coerceAtLeast(0)
            p.y = bp.y.coerceIn(0, (h - dp(48) * items.size).coerceAtLeast(0))
        }
        menu = column
        runCatching { windowManager.addView(column, p) }
        // Not modal (the overlay can't see outside taps) - fades on its own.
        column.postDelayed(menuDismiss, 6000)
    }

    // ---- «Всё ещё …?»: the check-in bubble beside the button ----

    private var ask: android.widget.LinearLayout? = null
    private val askDismiss = Runnable { hideAsk() }

    fun hideAsk() {
        val a = ask ?: return
        a.removeCallbacks(askDismiss)
        runCatching { windowManager.removeView(a) }
        ask = null
    }

    /**
     * A dele has outlived its category's typical length: ask, in one line,
     * whether it is still going. «Да» just resets the timer, «Нет» hands the
     * owner straight to a new take. Fades on its own after half a minute -
     * an unanswered question must not sit on the screen forever.
     */
    fun showAsk(question: String, onYes: () -> Unit, onNo: () -> Unit) {
        hideAsk()
        hideMenu()
        val column = android.widget.LinearLayout(service).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(AMBER)
            }
            elevation = dp(4).toFloat()
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        column.addView(
            TextView(service).apply {
                text = question
                setTextColor(PAPER)
                textSize = 15f
                maxLines = 2
            }
        )
        val row = android.widget.LinearLayout(service).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        fun pill(label: String, action: () -> Unit) = TextView(service).apply {
            text = label
            setTextColor(PAPER)
            textSize = 15f
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x33000000)
            }
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setOnClickListener { hideAsk(); action() }
        }
        row.addView(pill("Да", onYes))
        row.addView(
            pill("Нет, другое", onNo),
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )
        column.addView(
            row,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        val p = WindowManager.LayoutParams(
            tickerWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val bp = params
        if (bp != null) {
            val (w, h) = screenSize()
            val plateW = tickerWidthPx()
            val gap = dp(8)
            val buttonCenterX = bp.x + buttonSize / 2
            p.x = (if (buttonCenterX < w / 2) bp.x + buttonSize + gap else bp.x - plateW - gap)
                .coerceIn(0, (w - plateW).coerceAtLeast(0))
            p.y = bp.y.coerceIn(0, (h - dp(96)).coerceAtLeast(0))
        }
        ask = column
        runCatching { windowManager.addView(column, p) }
        column.postDelayed(askDismiss, 30_000)
        blinkOnce()
    }

    /** How many overlay windows this controller currently holds. */
    fun windowCount(): Int =
        (if (button != null) 1 else 0) + (if (ticker != null) 1 else 0) +
            (if (input != null) 1 else 0) + (if (menu != null) 1 else 0)

    fun destroy() {
        hideAsk()
        pulse?.cancel()
        pulse = null
        hideMenu()
        hideInput()
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
            setColor(AMBER)
        }
        background = bg
        container.background = bg
        container.elevation = dp(4).toFloat()
        container.alpha = idleAlpha

        // The slab "З" - П's own geometry turned on its side (see the vector).
        glyph = ImageView(service).apply {
            setImageResource(ModeGlyphs.zasechka())
        }
        container.addView(
            glyph,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        counter = TextView(service).apply {
            visibility = View.GONE
            setTextColor(PAPER)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD,
            )
            textSize = 16f
            gravity = Gravity.CENTER
        }
        container.addView(
            counter,
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
                if (!busy && !recording && !reminding && pomodoroText == null) {
                    container.alpha = idleAlpha
                }
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
            setColor(AMBER)
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
        // Unlike the "П" plate this one is TOUCHABLE: a tap mid-dictation
        // kills the mic and swaps the plate for a type-in box (confidential
        // takes are typed, not said out loud).
        pill.setOnClickListener { onTickerTap?.invoke() }
        val p = WindowManager.LayoutParams(
            tickerWidthPx(),
            tickerHeightPx(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        tickerParams = p
        ticker = pill
        runCatching { windowManager.addView(pill, p) }
        pill.visibility = View.GONE
    }

    // Narrower than before (owner: on the cover screen the 6x plate ate the
    // whole width): 4.5 diameters, capped so the button stays visible beside.
    private fun tickerWidthPx(): Int {
        val (w, _) = screenSize()
        return minOf(buttonSize * 9 / 2, (w - buttonSize - dp(24)).coerceAtLeast(dp(120)))
    }

    private fun positionTicker() {
        val bp = params ?: return
        val tp = tickerParams ?: return
        val (w, h) = screenSize()
        val tickerW = tickerWidthPx()
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

    // ---- Type-in plate: the mic died, the keyboard talks instead ----

    /** Fired when the owner taps the live ticker plate mid-dictation. */
    var onTickerTap: (() -> Unit)? = null

    private var input: android.widget.LinearLayout? = null
    private var inputEdit: android.widget.EditText? = null

    fun showInput(prefill: String, onSubmit: (String) -> Unit) {
        hideInput()
        hideTicker()
        val row = android.widget.LinearLayout(service).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = buttonSize / 2f
                setColor(AMBER)
            }
            elevation = dp(4).toFloat()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(4), dp(2))
        }
        val edit = android.widget.EditText(service).apply {
            setText(prefill)
            setSelection(prefill.length)
            setTextColor(PAPER)
            setHintTextColor(0xB0F7F3EA.toInt())
            hint = "Чем занят?"
            textSize = 16f
            background = null
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    val t = text?.toString().orEmpty()
                    hideInput()
                    onSubmit(t)
                    true
                } else false
            }
        }
        inputEdit = edit
        row.addView(
            edit,
            android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ),
        )
        row.addView(
            TextView(service).apply {
                text = "➤"
                textSize = 18f
                setTextColor(PAPER)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val t = edit.text?.toString().orEmpty()
                    hideInput()
                    onSubmit(t)
                }
            }
        )
        row.addView(
            TextView(service).apply {
                text = "✕"
                textSize = 16f
                setTextColor(PAPER)
                alpha = 0.8f
                setPadding(dp(6), dp(6), dp(10), dp(6))
                setOnClickListener { hideInput() }
            }
        )
        val p = WindowManager.LayoutParams(
            tickerWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable (no NOT_FOCUSABLE): the IME must attach to the box.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }
        positionInput(p)
        input = row
        runCatching { windowManager.addView(row, p) }
        // Не один showSoftInput, а до победного: окно оверлея получает фокус
        // уже после addView, и первый вызов молча возвращает false.
        ImeKick.raise(service, edit)
    }

    fun hideInput() {
        input?.let { runCatching { windowManager.removeView(it) } }
        input = null
        inputEdit = null
    }

    private fun positionInput(p: WindowManager.LayoutParams) {
        val bp = params ?: return
        val (w, h) = screenSize()
        val plateW = tickerWidthPx()
        val plateH = dp(52)
        val gap = dp(8)
        p.y = (bp.y - (plateH - buttonSize) / 2).coerceIn(0, (h - plateH).coerceAtLeast(0))
        val buttonCenterX = bp.x + buttonSize / 2
        p.x = if (buttonCenterX < w / 2) bp.x + buttonSize + gap
        else bp.x - plateW - gap
        p.x = p.x.coerceIn(0, (w - plateW).coerceAtLeast(0))
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
            // Карманный страж: на локскрине в покое жест глотается целиком —
            // ни тапа, ни меню, ни перетаскивания (идущая запись кнопку не
            // глушит, чтобы стоп-тап доходил).
            //
            // ИСКЛЮЧЕНИЕ только у «З» и только для короткого тапа: владелец
            // хочет диктовать не разблокируя, ради этого Засечка и есть.
            // Одиночный тап по-прежнему ничего не делает — служба ждёт
            // второго за полторы секунды. В кармане это почти невозможно,
            // намеренно делается за полсекунды.
            if (service.isLockedIdle()) {
                if (event.actionMasked == MotionEvent.ACTION_UP && !busy && !recording) {
                    onShortTap()
                }
                return true
            }
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
                        onDragged?.invoke(p.x, p.y, false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    applyFaceAndLook()
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
