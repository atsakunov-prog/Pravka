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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.R
import ru.zf.pravka.data.Settings

// Разноска: третья кнопка, «Р». Тот же TYPE_ACCESSIBILITY_OVERLAY, что у «П» и
// «З», та же плашка-тикер, те же жесты:
//   тап        -> наговор (говори, тап ещё раз — разбор)
//   долгое     -> меню: открыть «Дела», набрать текстом, разобрать заново
//   перетаскивание -> тройка ездит связкой (П сверху, З, Р снизу)
// Цвет — синие чернила редактора рядом с оранжевым и янтарным: три кнопки
// сразу различаются на ощупь глазом, а не только буквой.
//
// Собственное окно у кнопки только одно. Тикер, меню и плашка дел УХОДЯТ из
// WindowManager, когда не нужны: скрытое оверлейное окно всё равно стоит
// пересчёта при каждом складывании Fold (см. README, «больная тема»).
class RaznoskaButtonController(
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
        // Сколько дел показывать в плашке: остальное — в приложении.
        private const val PLATE_ROWS = 6

        // Синие чернила: третий цвет к оранжевому «П» и янтарному «З».
        val INK = 0xFF2A5D82.toInt()
        private val REC_RED = FloatingButtonController.REC_RED
        private val PAPER = 0xFFF7F3EA.toInt()
        // Бумага в полсилы - под названием дела; песочный - предупреждение о
        // дубле (красный на синем не читается).
        private val PAPER_DIM = 0xB8F7F3EA.toInt()
        private val SAND = 0xFFF6C177.toInt()
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var buttonSize = dp(Settings.FAB_SIZE_DEFAULT)
    private var idleAlpha = Settings.FAB_ALPHA_DEFAULT

    private var button: FrameLayout? = null
    private var background: GradientDrawable? = null
    private var glyph: ImageView? = null
    private var recDot: View? = null
    private var progress: ProgressBar? = null
    private var params: WindowManager.LayoutParams? = null
    private var busy = false
    private var recording = false
    private var enabled = false
    private var collectorsStarted = false

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

    /** Тумблер в настройках: включена — кнопка живёт на экране постоянно. */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) {
            if (button == null) create()
            button?.visibility = View.VISIBLE
        } else {
            hideTicker()
            hidePlate()
            hideMenu()
            hideInput()
            button?.let { runCatching { windowManager.removeView(it) } }
            button = null
            params = null
        }
    }

    fun setBusy(value: Boolean) {
        busy = value
        progress?.visibility = if (value) View.VISIBLE else View.GONE
        applyFace()
    }

    fun setRecording(value: Boolean) {
        recording = value
        recDot?.visibility = if (value) View.VISIBLE else View.GONE
        applyFace()
    }

    // Одно место решает лицо кнопки, чтобы состояния можно было переключать
    // в любом порядке и не поймать устаревший вид.
    private fun applyFace() {
        val b = button ?: return
        glyph?.visibility = if (!busy && !recording) View.VISIBLE else View.GONE
        when {
            recording -> {
                background?.setColor(REC_RED)
                b.alpha = 1f
            }
            busy -> {
                background?.setColor(INK)
                b.alpha = 1f
            }
            else -> {
                background?.setColor(INK)
                b.alpha = idleAlpha
            }
        }
    }

    fun onConfigurationChanged() {
        cachedScreen = null
        val p = params ?: return
        scope.launch {
            val (xFraction, yFraction) = settings.rFabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
            repositionTickerIfVisible()
        }
    }

    // ---- Связка трёх кнопок: эта может ехать за другой на резинке ----

    var onDragged: ((x: Int, y: Int, dropped: Boolean) -> Unit)? = null

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

    // ---- Тикер: живые слова, пока идёт наговор ----

    /** Тап по тикеру: микрофон замолчал, дальше набираем текстом. */
    var onTickerTap: (() -> Unit)? = null

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

    @SuppressLint("ClickableViewAccessibility")
    private fun createTicker() {
        val pill = FrameLayout(service)
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = buttonSize / 2f
            setColor(INK)
        }
        pill.elevation = dp(4).toFloat()
        pill.setOnClickListener { onTickerTap?.invoke() }
        val tv = TextView(service).apply {
            setTextColor(PAPER)
            textSize = 16f
            maxLines = TICKER_LINES
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(14), dp(8), dp(14), dp(8))
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

    private fun tickerWidthPx(): Int {
        val (w, _) = screenSize()
        return (buttonSize * TICKER_W_MULT).coerceAtMost(w - dp(24))
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
        tp.x = if (buttonCenterX < w / 2) bp.x + buttonSize + gap else bp.x - tickerW - gap
        tp.x = tp.x.coerceIn(0, (w - tickerW).coerceAtLeast(0))
    }

    // ---- Меню долгого нажатия: столбик синих пилюль ----

    class MenuItem(val label: String, val onClick: () -> Unit)

    private var menu: LinearLayout? = null
    private val menuDismiss = Runnable { hideMenu() }

    fun hideMenu() {
        val m = menu ?: return
        m.removeCallbacks(menuDismiss)
        runCatching { windowManager.removeView(m) }
        menu = null
    }

    fun showMenu(items: List<MenuItem>) {
        hideMenu()
        val column = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        for (item in items) {
            val pill = TextView(service).apply {
                text = item.label
                setTextColor(PAPER)
                textSize = 15f
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(INK)
                }
                alpha = 0.96f
                setPadding(dp(16), dp(9), dp(16), dp(9))
                setOnClickListener {
                    hideMenu()
                    item.onClick()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
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
            else (bp.x - dp(190)).coerceAtLeast(0)
            p.y = bp.y.coerceIn(0, (h - dp(48) * items.size).coerceAtLeast(0))
        }
        menu = column
        runCatching { windowManager.addView(column, p) }
        column.postDelayed(menuDismiss, 6000)
    }

    // ---- Плашка дел: та же пилюля, что тикер Правки и Засечки ----

    /** Одна строка плашки. [id] возвращается в колбэки: кого правим/одобряем. */
    class PlateRow(
        val id: Long,
        val title: String,
        val meta: String,
        val warn: String,
        val sent: Boolean = false,
    )

    private var plate: LinearLayout? = null
    private val plateDismiss = Runnable { hidePlate() }

    fun hidePlate() {
        val v = plate ?: return
        v.removeCallbacks(plateDismiss)
        runCatching { windowManager.removeView(v) }
        plate = null
    }

    fun plateVisible(): Boolean = plate != null

    /**
     * Разобранные дела рядом с кнопкой: синяя пилюля того же покроя, что
     * тикер «П» и плашка «З» - тот же радиус, та же бумажная белизна, та же
     * сторона экрана.
     *
     * Кружок слева - это ОТМЕТКА, а не отправка: все дела отмечены сразу,
     * тапом снимаешь лишние, и одно «ОК» в конце добавляет отмеченные. Так
     * решение принимается глазами по всему списку, а не по одному делу с
     * кручением после каждого. «✎» правит формулировку на месте, тап по
     * строке открывает дело целиком в «Делах».
     */
    fun showTasks(
        header: String,
        rows: List<PlateRow>,
        onEdit: (Long) -> Unit,
        onOpen: (Long) -> Unit,
        onSend: (List<Long>) -> Unit,
        holdMs: Long = 45_000,
    ) {
        hidePlate()
        val shown = rows.take(PLATE_ROWS)
        // По умолчанию отмечено всё, что ещё не уехало: обычно нужны все.
        val chosen = shown.filter { !it.sent }.map { it.id }.toMutableSet()
        val sheet = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = buttonSize / 2f
                setColor(INK)
            }
            alpha = 0.96f
            elevation = dp(4).toFloat()
            setPadding(dp(14), dp(10), dp(12), dp(8))
        }
        sheet.addView(
            TextView(service).apply {
                text = header
                setTextColor(PAPER_DIM)
                textSize = 12f
                letterSpacing = 0.08f
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    android.graphics.Typeface.BOLD,
                )
            }
        )
        // Кнопка «ОК» создаётся заранее: отметки меняют её счёт.
        val okButton = TextView(service).apply {
            setTextColor(INK)
            textSize = 15f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD,
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(PAPER)
            }
            setPadding(dp(22), dp(7), dp(22), dp(7))
        }
        fun refreshOk() {
            okButton.text = if (chosen.size > 1) "ОК · " + chosen.size else "ОК"
            okButton.alpha = if (chosen.isEmpty()) 0.45f else 1f
        }
        refreshOk()
        okButton.setOnClickListener {
            val ids = chosen.toList()
            hidePlate()
            if (ids.isNotEmpty()) onSend(ids)
        }

        for (row in shown) {
            val line = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            // Крупный кружок-отметка: по нему бьют пальцем на ходу.
            val mark = TextView(service).apply {
                text = "✓"
                textSize = 16f
                gravity = Gravity.CENTER
            }
            paintCheck(mark, row.sent || row.id in chosen)
            if (row.sent) {
                mark.alpha = 0.6f
            } else {
                mark.setOnClickListener {
                    if (row.id in chosen) chosen.remove(row.id) else chosen.add(row.id)
                    paintCheck(mark, row.id in chosen)
                    refreshOk()
                }
            }
            val markSize = dp(32)
            line.addView(
                mark,
                LinearLayout.LayoutParams(markSize, markSize).apply { marginEnd = dp(10) },
            )
            val texts = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                alpha = if (row.sent) 0.55f else 1f
                // Тап по строке - дело целиком в приложении.
                setOnClickListener {
                    hidePlate()
                    onOpen(row.id)
                }
            }
            texts.addView(
                TextView(service).apply {
                    text = row.title
                    setTextColor(PAPER)
                    textSize = 15f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
            if (row.meta.isNotBlank()) {
                texts.addView(
                    TextView(service).apply {
                        text = row.meta
                        setTextColor(PAPER_DIM)
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )
            }
            if (row.warn.isNotBlank()) {
                texts.addView(
                    TextView(service).apply {
                        text = row.warn
                        setTextColor(SAND)
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )
            }
            line.addView(
                texts,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (!row.sent) {
                line.addView(
                    TextView(service).apply {
                        text = "✎"
                        setTextColor(PAPER)
                        textSize = 17f
                        setPadding(dp(12), dp(6), dp(6), dp(6))
                        setOnClickListener { onEdit(row.id) }
                    }
                )
            }
            sheet.addView(
                line,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (rows.size > PLATE_ROWS) {
            sheet.addView(
                TextView(service).apply {
                    text = "…и ещё ${rows.size - PLATE_ROWS} — в «Делах»"
                    setTextColor(PAPER_DIM)
                    textSize = 12f
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }
        val buttons = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        buttons.addView(
            TextView(service).apply {
                text = "✕"
                setTextColor(PAPER)
                alpha = 0.75f
                textSize = 15f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener { hidePlate() }
            }
        )
        buttons.addView(okButton)
        sheet.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val p = WindowManager.LayoutParams(
            tickerWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        // Высоту WRAP_CONTENT заранее не знает никто: оцениваем по строкам,
        // чтобы плашка встала посередине кнопки и не свесилась за экран.
        val estimate = dp(34 + 44) + shown.sumOf { dp(if (it.warn.isBlank()) 46 else 64) }
        positionPlate(p, estimate)
        plate = sheet
        runCatching { windowManager.addView(sheet, p) }
        sheet.alpha = 0f
        sheet.animate().alpha(0.96f).setDuration(180).start()
        sheet.postDelayed(plateDismiss, holdMs)
    }

    /** Отмеченный кружок - залитый бумагой, снятый - только обводка. */
    private fun paintCheck(view: TextView, on: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (on) {
                setColor(PAPER)
            } else {
                setColor(0x00FFFFFF)
                setStroke(dp(2), PAPER_DIM)
            }
        }
        view.setTextColor(if (on) INK else 0x00FFFFFF)
    }

    // Рядом с кнопкой, на той стороне, где есть место - то же правило, что у
    // тикера; по вертикали прижимаем к экрану, список бывает высоким.
    private fun positionPlate(p: WindowManager.LayoutParams, estimatedHeight: Int) {
        val bp = params ?: return
        val (w, h) = screenSize()
        val plateW = tickerWidthPx()
        val buttonCenterX = bp.x + buttonSize / 2
        p.x = if (buttonCenterX < w / 2) bp.x + buttonSize + dp(8) else bp.x - plateW - dp(8)
        p.x = p.x.coerceIn(0, (w - plateW).coerceAtLeast(0))
        // По вертикали - серединой на кнопку, как тикер.
        p.y = (bp.y - (estimatedHeight - buttonSize) / 2)
            .coerceIn(0, (h - estimatedHeight).coerceAtLeast(0))
    }

    // ---- Записка: короткий итог с одной ручкой («↩︎ отменить») ----

    /**
     * Пилюля на пару строк рядом с кнопкой: что произошло и единственное
     * действие поверх. Живёт [holdMs] и уходит сама - это не диалог, а
     * реплика: «✓ 3 дела в Todoist · ↩︎».
     */
    fun showNote(text: String, actionLabel: String?, holdMs: Long = 12_000, onAction: (() -> Unit)?) {
        hidePlate()
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = buttonSize / 2f
                setColor(INK)
            }
            alpha = 0.96f
            elevation = dp(4).toFloat()
            setPadding(dp(16), dp(8), dp(10), dp(8))
        }
        row.addView(
            TextView(service).apply {
                this.text = text
                setTextColor(PAPER)
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (actionLabel != null && onAction != null) {
            row.addView(
                TextView(service).apply {
                    this.text = actionLabel
                    setTextColor(INK)
                    textSize = 14f
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF,
                        android.graphics.Typeface.BOLD,
                    )
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(PAPER)
                    }
                    setPadding(dp(14), dp(5), dp(14), dp(5))
                    setOnClickListener {
                        hidePlate()
                        onAction()
                    }
                }
            )
        }
        val p = WindowManager.LayoutParams(
            tickerWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionPlate(p, dp(64))
        // Записка живёт в том же окне, что плашка: их никогда не нужно два.
        plate = row
        runCatching { windowManager.addView(row, p) }
        row.alpha = 0f
        row.animate().alpha(0.96f).setDuration(160).start()
        row.postDelayed(plateDismiss, holdMs)
    }

    // ---- Набрать текстом: тот же ввод, что у Засечки ----

    private var input: LinearLayout? = null

    fun showInput(
        prefill: String,
        hint: String,
        onCancel: (() -> Unit)? = null,
        onSubmit: (String) -> Unit,
    ) {
        hideInput()
        hideTicker()
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = buttonSize / 2f
                setColor(INK)
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
            this.hint = hint
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
        row.addView(
            edit,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
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
                setOnClickListener {
                    hideInput()
                    onCancel?.invoke()
                }
            }
        )
        val p = WindowManager.LayoutParams(
            tickerWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Фокусируемое окно: без этого клавиатура не привяжется.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        positionInput(p)
        input = row
        runCatching { windowManager.addView(row, p) }
        row.post {
            edit.requestFocus()
            service.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideInput() {
        input?.let { runCatching { windowManager.removeView(it) } }
        input = null
    }

    private fun positionInput(p: WindowManager.LayoutParams) {
        val bp = params ?: return
        val (w, h) = screenSize()
        val plateW = tickerWidthPx()
        val plateH = dp(52)
        val gap = dp(8)
        p.y = (bp.y - (plateH - buttonSize) / 2).coerceIn(0, (h - plateH).coerceAtLeast(0))
        val buttonCenterX = bp.x + buttonSize / 2
        p.x = if (buttonCenterX < w / 2) bp.x + buttonSize + gap else bp.x - plateW - gap
        p.x = p.x.coerceIn(0, (w - plateW).coerceAtLeast(0))
    }

    /** Диагностика: сколько окон эта кнопка держит прямо сейчас. */
    fun windowCount(): Int =
        (if (button != null) 1 else 0) + (if (ticker != null) 1 else 0) +
            (if (menu != null) 1 else 0) + (if (plate != null) 1 else 0) +
            (if (input != null) 1 else 0)

    fun destroy() {
        hideMenu()
        hidePlate()
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
            setColor(INK)
        }
        background = bg
        container.background = bg
        container.elevation = dp(4).toFloat()
        container.alpha = idleAlpha

        glyph = ImageView(service).apply { setImageResource(R.drawable.ic_razn_glyph) }
        container.addView(
            glyph,
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
            y = (h * 0.75f).toInt()
        }
        params = p
        button = container
        windowManager.addView(container, p)
        container.visibility = if (enabled) View.VISIBLE else View.GONE

        scope.launch {
            val (xFraction, yFraction) = settings.rFabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            runCatching { windowManager.updateViewLayout(container, p) }
        }
        // Размер и прозрачность — общие ручки на все три кнопки. Подписка
        // ставится один раз за жизнь службы: тумблер «Р» убирает окно и может
        // создать его заново, а второй сборщик писал бы в мёртвое окно.
        if (!collectorsStarted) {
            collectorsStarted = true
            scope.launch {
                settings.fabSizeFlow.collect { sizeDp ->
                    buttonSize = dp(sizeDp)
                    val lp = params ?: return@collect
                    val view = button ?: return@collect
                    lp.width = buttonSize
                    lp.height = buttonSize
                    runCatching { windowManager.updateViewLayout(view, lp) }
                }
            }
            scope.launch {
                settings.fabAlphaFlow.collect { alpha ->
                    idleAlpha = alpha
                    if (!busy && !recording) {
                        button?.alpha = idleAlpha
                    }
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
            // Тот же карманный предохранитель, что у «З»: на локскрине вхолостую
            // жест проглатывается целиком.
            if (service.isLockedIdle()) return true
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
                        repositionTickerIfVisible()
                        onDragged?.invoke(p.x, p.y, false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    applyFace()
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
        scope.launch { settings.setRFabPosition(positionKey(), xFraction, yFraction) }
    }
}
