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
import android.widget.TextView
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.R
import ru.zf.pravka.core.DiskLook
import ru.zf.pravka.core.PillGeometry
import ru.zf.pravka.data.Settings

// The Засечка (timesheet) button: Правка's little sibling, drawn from the
// same accessibility service as a second TYPE_ACCESSIBILITY_OVERLAY window.
// The crucial difference from the "П" button: this one is visible ALWAYS,
// not only in text fields - a timesheet must be reachable from anywhere,
// including the home screen. Gestures mirror the big button:
//   short tap  -> record an entry (speak, tap again to stop)
//   long press -> menu: «Записать мысль», what is running now, open the tab
//   drag       -> move; the "П" trails behind on a rubber band (owner's
//                 design: the two buttons travel as a linked pair)
// States: idle amber "З" / recording red stop / busy spinner / remind (steady deep amber)
// (a time gap is waiting).
class ZasechkaButtonController(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val onShortTap: () -> Unit,
    private val onLongPress: () -> Unit,
) : RingButton {

    companion object {
        private const val LONG_PRESS_MS = 450L
        // Тикер — та же пилюля диктовки, что у «П» (`DictationPill`); ширина —
        // настройка владельца, Settings.tickerWidthFlow.

        // Warm pair with the "П": red-orange pen there, a marker halfway
        // between orange and yellow here (owner tuned it twice - this is the
        // midpoint) - same paper-white glyph on both.
        /** Цвет кнопки «З» — им же идёт дуга прогресса по кромке стекла. */
        val AMBER = 0xFFF78810.toInt()
        private val AMBER_DEEP = 0xFFEA580C.toInt()   // remind: steady, no pulse
        private val REC_RED = FloatingButtonController.REC_RED
        private val PAPER = 0xFFF7F3EA.toInt()
        // Записка «не смог»: тот же красный, что у записи, — цвет уже значит
        // «внимание сюда», второй заводить незачем.
        private val NOTE_BAD = REC_RED

        // Тёмная таблетка меню — «Записать мысль»: главный пункт должен
        // читаться первым, не сливаясь с янтарными соседями.
        private val INK = 0xFF5A4A3A.toInt()
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
    private var glyph: ImageView? = null
    private var recDot: View? = null
    private var progress: ProgressBar? = null
    /** Секунды до ответа вместо колеса (`core/Countdown.kt`), пока кнопка занята. */
    // Секунды до ответа — и на кнопке, и в её пилюле: искры и число (версия 3).
    // Пилюлю лямбда берёт при вызове: отсчёт заговорит, когда кнопка уже собрана.
    private val replyClock = ButtonCountdown(service).also { c ->
        c.onLabel = { waiting, label -> pill.countdown(waiting, label) }
    }
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
        if (!busy && !recording && !reminding) button?.alpha = idleAlpha
    }

    override var onRingDrag: ((Float, Float, Float, Float, Int) -> Unit)? = null
    private var busy = false
    private var recording = false
    private var reminding = false
    private var enabled = false

    // Пилюля диктовки (`DictationPill`): живые слова, пока идёт наговор, —
    // снизу посередине, в одежде Gemini. Тап — микрофон молчит,
    // дальше набором (конфиденциальное не говорят вслух).
    private val pill = DictationPill(
        service, scope,
        accent = AMBER,
        glyph = R.drawable.ic_mode_zasechka,
        textSizeSp = 17f,
        screen = { screenSize() },
        owner = { params?.let { PillGeometry.Box(it.x, it.y, it.x + buttonSize, it.y + buttonSize) } },
    ).also {
        it.onTap = { onTickerTap?.invoke() }
        it.onSend = { if (!busy) onShortTap() }
    }

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
            applyStash()
        } else {
            applyStash()
        }
    }

    fun setBusy(value: Boolean) {
        busy = value
        replyClock.setBusy(value)
        applyFaceAndLook()
    }

    /** Recording: red stop glyph at full opacity, like the big button. */
    fun setRecording(value: Boolean) {
        recording = value
        pill.canSend = value
        if (!value) restPulse()
        recDot?.visibility = if (value) View.VISIBLE else View.GONE
        applyFaceAndLook()
    }

    /**
     * В ленте дыра — кнопка стоит глубоким янтарём, пока не ляжет запись.
     * Раньше она мерцала (0,45↔1 без конца), и раз в час ещё и «подмигивала»;
     * владелец (26.09.2026): «Засечка иногда пульсирует. Давай её тоже уберём,
     * эту пульсацию». Сигнал остался цветом, движения нет: мерцание на краю
     * экрана тянет глаз, даже когда смотреть незачем.
     */
    fun setRemind(value: Boolean) {
        if (reminding == value) return
        reminding = value
        applyFaceAndLook()
    }

    /** Перечитать глиф после переключения «иконки вместо букв». */
    fun refreshGlyph() {
        glyph?.setImageResource(ModeGlyphs.zasechka())
    }

    // One place decides face (glyph/dot/spinner) and color/alpha from the
    // state set, so states can flip in any order without a stale look.
    private fun applyFaceAndLook() {
        val b = button ?: return
        glyph?.visibility = if (!busy && !recording) View.VISIBLE else View.GONE
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
                b.alpha = 1f
            }
            else -> {
                background?.setColor(AMBER)
                b.alpha = idleAlpha
            }
        }
    }

    override fun onConfigurationChanged() {
        cachedScreen = null
        val p = params ?: return
        if (ringMode) {
            // На диске место кнопки — дело диска: он перечитает свою позицию
            // под новый экран и расставит всех. Своё сохранённое место — для стопки.
            repositionTickerIfVisible()
            repositionCancelBubble()
            return
        }
        scope.launch {
            val (xFraction, yFraction) = settings.zFabPosition(positionKey())
            applyPosition(p, xFraction, yFraction)
            button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
            repositionTickerIfVisible()
            repositionCancelBubble()
        }
    }

    // ---- Elastic pair: this button can trail the other on a rubber band ----

    /** Fired while the owner drags THIS button (and once more on drop). */
    override var onDragged: ((x: Int, y: Int, dropped: Boolean) -> Unit)? = null


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
        val want = !stashed && !folded && enabled && !offscreen
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
    // мягче (`core/ChainPhysics.kt`, под тестами). Цикл общий на четыре
    // кнопки — `ChainFollower` в `BubbleMotion.kt`.
    private val follower = ChainFollower(
        apply = frame@{ x, y ->
            val p = params ?: return@frame
            val view = button ?: return@frame
            p.x = x
            p.y = y
            runCatching { windowManager.updateViewLayout(view, p) }
            repositionTickerIfVisible()
            repositionCancelBubble()
            onFrame?.invoke()
        },
        onSettled = settled@{ settle ->
            val p = params ?: return@settled
            val view = button ?: return@settled
            if (settle) savePosition(view, p)
        },
    )

    /** Ехать к ([x], [y]); [link] — через сколько бусин от той, что тянут. */
    override fun followTo(x: Int, y: Int, settle: Boolean, link: Int, snap: Boolean) {
        if (!enabled) return
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
                repositionCancelBubble()
            }
            if (settle) savePosition(view, p)
            return
        }
        follower.follow(p.x, p.y, targetX, targetY, link, settle)
    }

    // ---- Пилюля диктовки: живые слова, пока надиктовывается запись ----

    fun showTicker() = pill.show()

    fun updateTicker(text: String, force: Boolean = false) = pill.update(text, force)

    /** Надпись посередине, пока слов нет: «Саша, секунду…», «Саша, слушаю» (`core/PillHint.kt`). */
    fun hintTicker(text: String) = pill.hint(text)

    /** Кнопку тащат, экран повернули, настройку крутят — пилюля следом. */
    fun repositionTickerIfVisible() = pill.reposition()

    fun hideTicker() = pill.hide()

    // ---- Записка: что именно записалось — итогом в пилюле ----
    //
    // Владелец: «засечка должна баблом на 2 секунды показывать, что за дело
    // записано. и что за дело исправлено и как». Своя записка появилась
    // вместо тоста (тот душится системой и цвета не даёт); 26.09.2026 она
    // переехала в пилюлю диктовки — «переделать под этот стиль Gemini… сама
    // пропадает через секунд 5». Красная — не «записал», а «не смог».

    /** Тап по итогу — в приложение (служба ставит: лента Засечки). */
    var onNoteOpen: (() -> Unit)? = null

    fun hideNote() = pill.dropResult()

    /** [ok] = false красит итог в красные чернила: это не «записал», а «не смог». */
    fun showNote(text: String, ok: Boolean = true, holdMs: Long = 2_000) =
        pill.result(text, ok, onOpen = onNoteOpen, holdMs = holdMs)

    /**
     * Итог разбора — что записано — в пилюле, на её месте (`DictationPill.result`):
     * сама уходит через пять секунд, тап раскрывает список с карандашами.
     */
    fun showResult(
        summary: String,
        rows: List<DictationPill.ResultRow> = emptyList(),
        footer: String = "",
        action: DictationPill.ResultAction? = null,
        onOpen: (() -> Unit)? = null,
        ok: Boolean = true,
        holdMs: Long = ru.zf.pravka.core.PillLook.RESULT_HOLD_MS,
    ) = pill.result(summary, ok, rows, footer, action, onOpen, holdMs)

    // ---- Long-press menu: a single column of amber pills ----

    /** [accent] — тёмная таблетка: главный пункт, читается первым. */
    class MenuItem(val label: String, val accent: Boolean = false, val onClick: () -> Unit)

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
                background = BubbleSkin().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (item.accent) INK else AMBER)
                }
                alpha = if (item.accent) 0.98f else 0.96f
                if (item.accent) {
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF,
                        android.graphics.Typeface.BOLD,
                    )
                    setPadding(dp(16), dp(11), dp(16), dp(11))
                } else {
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                }
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
            background = BubbleSkin().apply {
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
    }

    // ---- Серая «отмена» у идущей записи: как на «П» (владелец, 18.09.2026) ----
    // Общая на четыре кнопки (`CancelBubble.kt`): под ближним концом бегущей
    // строки, а не под кнопкой — под кнопкой стоит следующая кнопка стопки;
    // прозрачность — как у кнопок.

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
            (if (cancelBubble.shown) 1 else 0) +
            (if (menu != null) 1 else 0)

    override fun destroy() {
        hideAsk()
        hideMenu()
        hideInput()
        hideCancelBubble()
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
        val bg = BubbleSkin().apply { shape = GradientDrawable.OVAL; setColor(AMBER) }
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
            y = (h * 0.62f).toInt()
        }
        params = p
        button = container
        windowManager.addView(container, p)
        attached = true
        container.visibility = if (enabled) View.VISIBLE else View.GONE

        scope.launch {
            val (xFraction, yFraction) = settings.zFabPosition(positionKey())
            if (!ringMode) {
                applyPosition(p, xFraction, yFraction)
                runCatching { windowManager.updateViewLayout(container, p) }
            }
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

    // Ширина — настройка владельца (одна на все кнопки), кнопка рядом остаётся видна.
    private fun tickerWidthPx(): Int {
        val (w, _) = screenSize()
        return minOf(dp(service.cachedTickerWidthDp), (w - buttonSize - dp(24)).coerceAtLeast(dp(120)))
    }

    // ---- Type-in: the mic died, the keyboard talks instead — in the pill ----

    /** Fired when the owner taps the live ticker plate mid-dictation. */
    var onTickerTap: (() -> Unit)? = null

    /**
     * Набор вместо голоса — в пилюле, на её месте (`DictationPill.edit`):
     * сказанное уже в поле, кружок — «отправить», ✕ — отмена. Владелец
     * (26.09.2026): «прямо там, в этой прекрасной нашей плашке, можно было
     * писать» — прежнее окошко у кнопки снято.
     */
    fun showInput(prefill: String, onSubmit: (String) -> Unit) =
        pill.edit(prefill, "Чем занят?", onSubmit, onCancel = null)

    fun hideInput() = pill.dropEdit()

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
                        repositionTickerIfVisible()
                        repositionCancelBubble()
                        onDragged?.invoke(p.x, p.y, false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    pressed = null
                    BubbleMotion.release(view)
                    applyFaceAndLook()
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
