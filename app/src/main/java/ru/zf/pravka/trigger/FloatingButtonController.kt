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
import ru.zf.pravka.core.DiskLook
import ru.zf.pravka.core.PillGeometry
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
) : RingButton {

    companion object {
        private const val LONG_PRESS_MS = 450L
        // Тикер — бегущая строка в одну линию (владелец, 15.09.2026) в пилюле
        // диктовки (`DictationPill`, 26.09.2026); ширина — Settings.tickerWidthFlow.

        // Editorial palette shared with ui/Theme.kt and the launcher icon:
        // orange circle, paper-white geometric "П"; deep red while recording.
        val ACCENT = 0xFFEA580C.toInt()
        val REC_RED = 0xFFD8342A.toInt()
        /** Невыбранная кнопка переключателя в меню: серая, чтобы выбранная читалась сразу. */
        val MUTED = 0xFF6B6660.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var buttonSize = dp(Settings.FAB_SIZE_DEFAULT)
    /** Прозрачность кнопок — настройка владельца (слайдер в Общих). */
    private var fabAlpha = Settings.FAB_ALPHA_DEFAULT

    /**
     * Лицо кнопки: на диске плотнее настройки. Светлое стекло просвечивало
     * сквозь полупрозрачную кнопку, и владелец читал это как «диск над
     * кнопками, а не наоборот» (19.09.2026). Порядок окон тут ни при чём —
     * дело в плотности; «не мешать приложению» на диске держит тарелка.
     */
    private val idleAlpha: Float get() = DiskLook.faceAlpha(fabAlpha, ringMode, faceOverride)

    /** Ползунок «плотность кнопок» из настроек; null — считать по формуле. */
    private var faceOverride: Float? = null

    private var button: FrameLayout? = null
    private var background: GradientDrawable? = null
    private var label: ImageView? = null
    private var recDot: View? = null
    private var progress: ProgressBar? = null
    /** Секунды до ответа вместо колеса (`core/Countdown.kt`), пока кнопка занята. */
    private val replyClock = ButtonCountdown(service)
    private var params: WindowManager.LayoutParams? = null
    /** Убрана в ручку: сильнее любых других причин показать кнопку. */
    private var stashed = false
    /** Окно кнопки реально висит в WindowManager. */
    private var attached = false
    /** Идёт складывание: окно снято на время перехода. */
    private var folded = false
    /** Диск: окно кнопки целиком за краем экрана — снято из WindowManager (см. `DiskController`). */
    private var offscreen = false

    /**
     * Кнопка на диске (`DiskController`): сама себя не ставит и не тащит —
     * палец крутит диск ([onRingDrag]); цели [followTo] не режутся краем
     * экрана, а окну разрешено выезжать за край (FLAG_LAYOUT_NO_LIMITS). В
     * стопке флага нет: там за край кнопка не заходит, и прижимать её к
     * экрану должен WindowManager, как и раньше.
     */
    override var ringMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Режим сменился — сменилась и плотность лица (DiskLook.faceAlpha);
            // ставим её ДО ранних возвратов ниже: те про окно, а не про цвет.
            applyFaceAlpha()
            val p = params ?: return
            val noLimits = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            p.flags = if (value) p.flags or noLimits else p.flags and noLimits.inv()
            if (attached) button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }

    /**
     * Громкость голоса — волна в кружке пилюли. Сама кнопка от голоса больше
     * не пульсирует: владелец (26.09.2026): «уберём дрожание кнопки на диске,
     * оно немножко отвлекает». Голос виден в одном месте, а не в двух, и
     * глаз не дёргается к краю экрана на каждом слове.
     */
    override fun setLevel(level: Float) {
        if (recording) pill.setLevel(level)
    }

    /** Запись кончилась — волна в пилюле снова значок, кнопка в своём размере. */
    private fun restPulse() {
        pill.rest()
        button?.let { v ->
            v.animate().cancel()
            v.scaleX = 1f
            v.scaleY = 1f
        }
    }

    /** Поставить лицу текущую плотность — пока кнопка не занята и не пишет. */
    private fun applyFaceAlpha() {
        cancelBubble.setAlpha(idleAlpha)
        if (!busy && !recording) button?.alpha = idleAlpha
    }

    override var onRingDrag: ((Float, Float, Float, Float, Int) -> Unit)? = null
    private var busy = false
    private var recording = false
    private var visible = false

    // Long-press menu: a vertical stack of pills beside the button.
    private var menu: android.widget.LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var menuVisible = false
    private val menuDismiss = Runnable { hideMenu() }

    // Пилюля диктовки (`DictationPill`): живые слова, пока слушает движок, в
    // одежде Gemini. Слева ✕ — отмена, кружок справа — «отправить» (тот же
    // тап, что по кнопке), долгое нажатие — переставить.
    private val pill = DictationPill(
        service, scope,
        accent = ACCENT,
        glyph = R.drawable.ic_mode_pravka,
        textSizeSp = 17f,
        screen = { screenSize() },
        owner = { params?.let { PillGeometry.Box(it.x, it.y, it.x + buttonSize, it.y + buttonSize) } },
    ).also { it.onSend = { if (!busy) onShortTap() } }

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
            // Флаг ПЕРЕД applyStash: она решает по нему, вешать окно или нет.
            visible = true
            applyStash()
        }
        if (badgeWanted) {
            repositionLearnBadge()
            learnBadge?.visibility = View.VISIBLE
        }
    }

    /** Перечитать глиф после переключения «иконки вместо букв». */
    fun refreshGlyph() {
        label?.setImageResource(ModeGlyphs.pravka())
    }

    // ---- Elastic pair: trail the "З" button on a rubber band ----

    /** Fired while the owner drags THIS button (and once more on drop). */
    override var onDragged: ((x: Int, y: Int, dropped: Boolean) -> Unit)? = null

    /** Where this button should appear when it shows up (docked over "З"). */
    var pairAnchor: (() -> Pair<Int, Int>?)? = null


    /**
     * Кнопка убрана в ручку. Прятать надо НАДЁЖНО, и две прежние попытки
     * этого не сделали: первая только приглушала (кнопка оставалась на
     * экране), вторая гасила из withEndAction — а он не выполняется, если
     * анимацию кто-то перебил, и applyFace, который дёргается на каждом
     * изменении состояния, тут же ставит альфу обратно своим числом.
     *
     * Поэтому теперь у скрытия свой флаг, гашение по таймеру (а не по концу
     * анимации), и ЕДИНСТВЕННОЕ место, которое пишет видимость кнопки, —
     * [applyStash]. Пока несколько мест пишут одно поле, побеждает
     * последнее, и это всегда не то, которого ждёшь.
     */
    override fun setStacked(value: Boolean) {
        stashed = value
        val v = button ?: return
        if (value) {
            v.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(140).start()
            v.postDelayed({ if (stashed) applyStash() }, 150)
        } else {
            // НИКАКОЙ анимации проявления. Только что прикреплённое окно ещё
            // не прошло ни одного прохода компоновки, и ViewPropertyAnimator
            // на нём попросту не стартует — альфа так и остаётся нулевой.
            // Владелец увидел это буквально: «кнопка правки пропала,
            // остальные есть». Остальные-то сразу двигает followTo, а «П» —
            // якорь цепочки, её не двигает никто, и проход компоновки ей
            // взяться неоткуда. Поэтому конечные значения ставятся напрямую.
            // Вид возвращает applyStash — там же, где возвращается окно.
            v.animate().cancel()
            applyStash()
        }
    }

    /**
     * Складывание идёт — окно кнопки снимается на время перехода.
     *
     * Владелец сам показал, где ответ: «если все кнопки сложить в три точки,
     * то никаких проблем нет, складывается всё отлично». В журнале при этом
     * «наших окон 0», а с кнопками на экране — четыре, и чернота на пять
     * секунд. Складывание Fold пересчитывает и ЖДЁТ каждое наше оверлейное
     * окно, и четыре — это уже дорого, независимо от того, чьи они.
     *
     * Поэтому на время перехода их нет вовсе, а через полсекунды они
     * возвращаются на свои места. Пользоваться ими всё равно нельзя: экран в
     * этот момент чёрный.
     *
     * Исключение — идущая запись: кнопку «стоп» отнимать нельзя даже на
     * полсекунды, иначе останавливать наговор будет нечем.
     */
    override fun setFolded(value: Boolean) {
        folded = value && !recording
        applyStash()
    }

    /**
     * Спрятанная кнопка УХОДИТ ИЗ WindowManager, а не остаётся невидимым
     * окном, и это не оптимизация, а правило проекта, оплаченное трижды:
     * складывание Fold пересчитывает и ЖДЁТ каждое наше оверлейное окно, и
     * чернота на пять секунд приходила ровно отсюда. Первая версия скрытия
     * ставила View.GONE — окно при этом остаётся, а с ним и цена.
     *
     * Позиция живёт в params и переживает открепление, поэтому кнопка
     * возвращается ровно туда, где была.
     */
    private fun applyStash() {
        val v = button ?: return
        val p = params ?: return
        val want = !stashed && !folded && visible && !offscreen
        // Кнопка должна быть видна, а её не видно — чиним вид целиком:
        // видимость, масштаб, альфу. Проверка идёт ДО выхода «нечего
        // менять», и это не перестраховка, а разбор двух подряд промахов.
        //
        // «П» — единственная кнопка, чей create() ставит контейнеру GONE
        // (её задумывали появляющейся по show()), и раскрывала её ровно та
        // строка show(), которую я заменил на applyStash. Окно при этом уже
        // прикреплено самим create(), так что выход «нечего менять»
        // срабатывал раньше любого шанса показать кнопку: перепись честно
        // считала окно четвёртым, а кнопки не было.
        //
        // Здоровую кнопку не трогаем: у «З» альфу анимирует пульс
        // напоминания, и переписывать её на каждом вызове значило бы гасить
        // его. Поэтому условие про заведомо невидимое, а не «всегда».
        if (want && (!attached || v.visibility != View.VISIBLE || v.alpha <= 0.02f)) {
            v.visibility = View.VISIBLE
            v.scaleX = 1f
            v.scaleY = 1f
            v.alpha = if (busy || recording) 1f else idleAlpha
        }
        if (want == attached) return
        attached = want
        if (want) {
            runCatching { windowManager.addView(v, p) }
        } else {
            runCatching { windowManager.removeView(v) }
        }
    }

    override fun currentPosition(): Pair<Int, Int>? = params?.let { it.x to it.y }

    override fun onScreen(): Boolean = attached

    override fun startCountdown(expectMs: Long) = replyClock.start(expectMs)

    override fun stopCountdown() = replyClock.stop()

    /** Диск: окно целиком за краем — снять; показался край — вернуть. Своё поле, не `stashed`. */
    override fun setOffscreen(value: Boolean) {
        if (offscreen == value) return
        offscreen = value
        applyStash()
    }

    /** Снять и повесить заново — поверх окон, добавленных позже (тарелка диска). */
    override fun reattach() {
        val v = button ?: return
        val p = params ?: return
        if (!attached) return
        runCatching { windowManager.removeView(v) }
        runCatching { windowManager.addView(v, p) }
    }

    override fun buttonSizePx(): Int = buttonSize

    /** Каждый кадр догонялки: ручка и шестерёнка едут за бусами (служба ставит refreshHandles). */
    override var onFrame: (() -> Unit)? = null

    // Пружина вместо «30 % пути за кадр»: у бусины есть скорость, она
    // догоняет, чуть проскакивает и успокаивается; звено дальше от пальца —
    // мягче (`core/ChainPhysics.kt`, под тестами).
    private val follower = ChainFollower(
        apply = frame@{ x, y ->
            val p = params ?: return@frame
            val view = button ?: return@frame
            p.x = x
            p.y = y
            runCatching { windowManager.updateViewLayout(view, p) }
            repositionTickerIfVisible()
            repositionLearnBadge()
            repositionCancelBubble()
            onFrame?.invoke()
        },
        onSettled = settled@{ settle ->
            val p = params ?: return@settled
            val view = button ?: return@settled
            if (settle) savePosition(view, p)
        },
    )

    /**
     * Hidden "П" (no text field focused - most of the time) still keeps
     * formation: it snaps to the target silently, so the next time it
     * appears it is already docked where the "З" dropped it. Visible "П"
     * chases on the spring; [link] — how many beads away the dragged one is.
     */
    override fun followTo(x: Int, y: Int, settle: Boolean, link: Int, snap: Boolean) {
        val view = button ?: return
        val p = params ?: return
        val (w, h) = screenSize()
        // На диске цель не режется краем: кнопке положено выезжать за него.
        val targetX = if (ringMode) x else x.coerceIn(0, (w - buttonSize).coerceAtLeast(0))
        val targetY = if (ringMode) y else y.coerceIn(0, (h - buttonSize).coerceAtLeast(0))
        // ОТКРЕПЛЁННОЕ окно догонять нечем: view.post у view без окна не
        // выполняется вовсе — он ждёт следующего прикрепления. Владелец
        // увидел это так: убрал всё в три точки, оттащил их, и через пару
        // секунд точки вернулись к правому краю. Точки-то стоят НАД «П», а
        // «П» с откреплённым окном никуда не уехала — её параметры остались
        // старыми, и первый же пересчёт вернул ручку к ним.
        //
        // Поэтому спрятанная кнопка встаёт на место сразу, без резинки:
        // догонять всё равно некому, а координаты обязаны быть настоящими.
        //
        // С [snap] — то же самое для видимой кнопки: диск ведёт свою анимацию
        // сам и ставит кнопки кадр в кадр, пружина здесь только мешала бы.
        if (!attached || snap) {
            follower.stop()
            p.x = targetX
            p.y = targetY
            runCatching { windowManager.updateViewLayout(view, p) }
            if (attached) {
                repositionTickerIfVisible()
                repositionLearnBadge()
                repositionCancelBubble()
            }
            if (settle) savePosition(view, p)
            return
        }
        follower.follow(p.x, p.y, targetX, targetY, link, settle)
    }

    fun hide() {
        // While recording the stop button must stay up in every app.
        if (recording) return
        if (visible && !busy) {
            visible = false
            applyStash()
            hideLearnBadge()
        }
    }

    fun setBusy(value: Boolean) {
        busy = value
        label?.visibility = if (value || recording) View.GONE else View.VISIBLE
        replyClock.setBusy(value)
        button?.alpha = if (value) 1f else idleAlpha
    }

    /** Recording on: red stop dot, full opacity, pinned visible everywhere. */
    fun setRecording(value: Boolean) {
        recording = value
        pill.canSend = value
        if (!value) restPulse()
        background?.setColor(if (value) REC_RED else ACCENT)
        recDot?.visibility = if (value) View.VISIBLE else View.GONE
        label?.visibility = if (value || busy) View.GONE else View.VISIBLE
        button?.alpha = if (value) 1f else idleAlpha
        if (value) show()
    }

    override fun onConfigurationChanged() {
        cachedScreen = null  // fold/rotate: re-measure once
        val p = params ?: return
        if (ringMode) {
            // На диске место кнопки — дело диска: он перечитает свою позицию
            // под новый экран и расставит всех. Своё сохранённое место — для стопки.
            repositionTickerIfVisible()
            repositionCancelBubble()
            return
        }
        scope.launch {
            val (xFraction, yFraction) = settings.fabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
            repositionTickerIfVisible()
            repositionLearnBadge()
            repositionCancelBubble()
        }
    }

    // ---- Пилюля диктовки: живые слова, пока слушает движок ----

    fun showTicker() = pill.show()

    fun updateTicker(text: String, force: Boolean = false) = pill.update(text, force)

    /** Надпись посередине, пока слов нет: «Саша, секунду…», «Саша, слушаю» (`core/PillHint.kt`). */
    fun hintTicker(text: String) = pill.hint(text)

    /** Кнопку тащат, экран повернули, настройку крутят — пилюля следом. */
    fun repositionTickerIfVisible() = pill.reposition()

    fun hideTicker() = pill.hide()

    // ---- Long-press menu: colored columns side by side ----

    class MenuItem(val label: String, val color: Int, val onClick: () -> Unit)

    /**
     * [topRow] — ряд кнопок поперёк над колонками (усилие чистки у «П»,
     * 22.09.2026): три пилюли в строку читаются как один переключатель, а
     * не как ещё три пункта списка.
     */
    fun toggleMenu(groups: List<List<MenuItem>>, topRow: List<MenuItem> = emptyList()) {
        if (menuVisible) hideMenu() else showMenu(groups, topRow)
    }

    fun hideMenu() {
        val m = menu ?: return
        menuVisible = false
        m.removeCallbacks(menuDismiss)
        runCatching { windowManager.removeView(m) }
        menu = null
    }

    private fun showMenu(groups: List<List<MenuItem>>, topRow: List<MenuItem> = emptyList()) {
        hideMenu()
        // Editing actions in red, AI actions in orange - two columns side by
        // side (owner's design).
        val row = android.widget.LinearLayout(service).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        fun pill(item: MenuItem, padH: Int = dp(16)) = android.widget.TextView(service).apply {
            text = item.label
            setTextColor(PAPER)
            textSize = 15f
            background = BubbleSkin().apply {
                cornerRadius = dp(18).toFloat()
                setColor(item.color)
            }
            alpha = 0.92f
            setPadding(padH, dp(9), padH, dp(9))
            setOnClickListener {
                hideMenu()
                item.onClick()
            }
        }
        for (group in groups) {
            val column = android.widget.LinearLayout(service).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            for (item in group) {
                val pill = pill(item)
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
        val column: android.widget.LinearLayout = if (topRow.isEmpty()) row else {
            android.widget.LinearLayout(service).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val strip = android.widget.LinearLayout(service).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                for ((i, item) in topRow.withIndex()) {
                    strip.addView(pill(item, padH = dp(12)), android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(6); if (i > 0) marginStart = dp(6) })
                }
                addView(strip)
                addView(row)
            }
        }
        // Ширина по факту, а не прикидкой: ряд усилия шире колонки, и меню
        // слева от кнопки легло бы на неё саму.
        column.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
        val menuW = column.measuredWidth.coerceAtLeast(dp(180))
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
            else (bp.x - menuW - dp(8)).coerceAtLeast(0)
            p.y = bp.y.coerceIn(0, (h - column.measuredHeight).coerceAtLeast(0))
        }
        menuParams = p
        menu = column
        menuVisible = true
        runCatching { windowManager.addView(column, p) }
        // Not modal (the overlay can't see outside taps) - fade away on its own.
        column.postDelayed(menuDismiss, 6000)
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
        learnBadge?.let { runCatching { windowManager.removeView(it) } }
        learnBadge = null
        learnBadgeParams = null
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
    // Общая на четыре кнопки (`CancelBubble.kt`): под ближним концом бегущей
    // строки, а не под кнопкой — под кнопкой стоит «З»; прозрачность — как у
    // кнопок.

    private val cancelBubble = CancelBubble(service, windowManager)

    fun showCancelBubble(onCancel: () -> Unit) {
        pill.onCancel = onCancel
        // В пилюле свой ✕ — серая «отмена» рядом была бы второй на ту же
        // запись. Она остаётся там, где пилюли нет (запись Whisper у «П»).
        if (pill.showing) return
        cancelBubble.show(idleAlpha, onCancel)
        repositionCancelBubble()
    }

    /** Пилюля едет за кнопкой: тащат, догоняет, повернули экран. */
    fun repositionCancelBubble() {
        if (!cancelBubble.shown) return
        val bp = params ?: return
        val (w, h) = screenSize()
        cancelBubble.place(bp.x, bp.y, buttonSize, w, h)
    }

    fun hideCancelBubble() {
        pill.onCancel = null
        cancelBubble.hide()
    }

    /** How many overlay windows this controller currently holds. */
    override fun windowCount(): Int =
        // Именно attached, а не «button != null»: спрятанная кнопка держит
        // свой View, но окна в WindowManager у неё нет — и в перепись,
        // которой меряют цену складывания, она входить не должна.
        (if (attached) 1 else 0) + pill.windowCount +
            (if (learnBadge != null) 1 else 0) + (if (cancelBubble.shown) 1 else 0) +
            (if (menu != null) 1 else 0)

    override fun destroy() {
        learnBadge?.let { runCatching { windowManager.removeView(it) } }
        learnBadge = null
        hideCancelBubble()
        hideMenu()
        follower.stop()
        button?.let { runCatching { windowManager.removeView(it) } }
        attached = false
        button = null
        pill.destroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create() {
        val container = FrameLayout(service)
        // Не плоский кружок: выпуклая клавиша со светом сверху (`BubbleSkin`).
        // Наследник GradientDrawable — поле и все setColor по состояниям те же.
        val bg = BubbleSkin().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
        background = bg
        container.background = bg
        container.elevation = dp(4).toFloat()
        container.alpha = idleAlpha

        label = ImageView(service).apply {
            setImageResource(ModeGlyphs.pravka())
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
        // Секунды до ответа на месте колеса (`ButtonCountdown`), пока кнопка занята.
        progress?.let { replyClock.attach(container, it) }

        val p = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (ringMode) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val (w, h) = screenSize()
            x = w - buttonSize
            y = h / 2
        }
        params = p
        button = container
        windowManager.addView(container, p)
        attached = true
        container.visibility = View.GONE

        scope.launch {
            val (xFraction, yFraction) = settings.fabPosition(positionKey())
            if (!ringMode) {
                applyPosition(p, xFraction, yFraction)
                runCatching { windowManager.updateViewLayout(container, p) }
            }
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
                fabAlpha = alpha
                applyFaceAlpha()
            }
        }
        scope.launch {
            settings.diskFaceAlphaFlow.collect { value ->
                faceOverride = value
                applyFaceAlpha()
            }
        }

        container.setOnTouchListener(DragTouchListener().also { touch = it })
    }

    private fun applyPosition(p: WindowManager.LayoutParams, xFraction: Float, yFraction: Float) {
        val (w, h) = screenSize()
        p.x = ((w - buttonSize) * xFraction.coerceIn(0f, 1f)).toInt()
        p.y = ((h - buttonSize) * yFraction.coerceIn(0f, 1f)).toInt()
    }

    private var touch: DragTouchListener? = null

    override fun cancelGesture() {
        touch?.swallow()
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressFired = false
        private var pressed: View? = null
        /** Диск взяли двумя пальцами — этот жест кнопке больше не принадлежит. */
        private var swallowed = false

        fun swallow() {
            if (swallowed) return
            swallowed = true
            pressed?.let { v ->
                v.removeCallbacks(longPressRunnable)
                BubbleMotion.release(v)
            }
        }
        private val longPressRunnable = Runnable {
            longPressFired = true
            pressed?.let { BubbleMotion.nod(it) }
            // Long press = fix the field; not available while recording.
            if (!busy && !recording) onLongPress()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            // Lockscreen pocket guard: while idle, the whole gesture is
            // swallowed - no tap, no menu, no drag (a running take keeps
            // the button alive so its stop-tap still works).
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
                    swallowed = false
                    pressed = view
                    view.alpha = 1f
                    // Сжалась под пальцем (`BubbleMotion`): кнопка отвечает на касание телом.
                    BubbleMotion.press(view)
                    // Палец лёг — будим движок распознавания, не дожидаясь, чем
                    // кончится касание: между тапом и «слышу» движок глух, и
                    // самое дорогое в этом окне можно оплатить прямо сейчас.
                    service.warmSpeech()
                    view.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    if (ringMode) onRingDrag?.invoke(event.rawX, event.rawY, event.x, event.y, MotionEvent.ACTION_DOWN)
                }
                MotionEvent.ACTION_MOVE -> {
                    // Диск слышит каждый сдвиг, и до порога тоже: второй палец
                    // на стекле делает из касания щипок, и этот палец везёт
                    // диск. Порог для поворота диск держит свой. Кнопка сама
                    // на диске не едет — палец крутит диск, диск ставит кнопку.
                    if (ringMode && !longPressFired) {
                        onRingDrag?.invoke(event.rawX, event.rawY, event.x, event.y, MotionEvent.ACTION_MOVE)
                    }
                    if (swallowed) return true
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        view.removeCallbacks(longPressRunnable)
                        BubbleMotion.lift(view)
                    }
                    if (dragging && !longPressFired && !ringMode) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, p) }
                        repositionTickerIfVisible()  // the pill rides along
                        repositionLearnBadge()
                        repositionCancelBubble()
                        onDragged?.invoke(p.x, p.y, false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    pressed = null
                    BubbleMotion.release(view)
                    if (!busy && !recording) view.alpha = idleAlpha
                    if (ringMode) {
                        // Диск: отпустили — щёлкнуть по ближайшей четверти; тап остаётся тапом.
                        onRingDrag?.invoke(event.rawX, event.rawY, event.x, event.y, MotionEvent.ACTION_UP)
                        if (!swallowed && !dragging && !longPressFired && event.actionMasked == MotionEvent.ACTION_UP) {
                            if (!busy) onShortTap()
                        }
                    } else if (dragging) {
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
