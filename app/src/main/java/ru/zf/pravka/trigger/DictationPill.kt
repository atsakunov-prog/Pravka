package ru.zf.pravka.trigger

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.DiskLook
import ru.zf.pravka.core.MicLevel
import ru.zf.pravka.core.PillGeometry
import ru.zf.pravka.core.PillLook
import ru.zf.pravka.ui.Haptics

/**
 * Пилюля диктовки — бегущая строка в одежде Gemini, одна на пять кнопок.
 *
 * Владелец (26.09.2026), со снимками Gemini: «мне очень нравится… как там
 * внизу вылезает такая маленькая плашка… Она будет вылезать не рядом с
 * кнопкой, а я вот нажимаю, допустим, на правку, и вот эта штука так
 * всплывает. Причём над клавиатурой, если клавиатура включена, или внизу
 * экрана… всплывать снизу… наверху немножко так вниз делает… — оп — назад.
 * И дальше точно так же всё поведение, как в плашке».
 *
 * Тем же днём, увидев, что клавиатуру приходится угадывать: «можно и без
 * этого… Или даже лучше: пускай сверху вылезает!» — и заводским стало место
 * СВЕРХУ: под строкой состояния посередине. Клавиатура всегда внизу, делить
 * ей с пилюлей нечего, и поле ввода мессенджера тоже остаётся открытым.
 *
 * Вечером того же дня пилюля стала пультом записи, как строка Gemini:
 *
 *  - **слева ✕ — отмена** («иногда хочется отменить»): та же отмена, что
 *    серая пилюля у кнопки, и пока пилюля на экране, серой нет — две отмены на
 *    одну запись были бы грязью. Нечего отменять (стрим чистки) — слева знак
 *    режима;
 *  - **кружок справа — «отправить»** («на неё ты нажимал, и это являлось
 *    Send'ом»): тот же тап, что по пишущей кнопке, — стоп и дальше как
 *    обычно. Работает, только пока идёт запись: иначе тап по кружку начал
 *    бы новую;
 *  - **потянул — переставил** («нажимаю на плашку и держу — её можно
 *    двигать… в заход она появится там же, в своём дефолтном месте»; и
 *    следом: «нельзя её двигать, если я беру её и начинаю тянуть»): пилюля
 *    едет за пальцем сразу, как он поехал, или после удержания, и остаётся
 *    там до конца показа; следующий показ — снова на своём месте. Запоминать
 *    не нужно: «там же» — это место, а не последнее касание;
 *  - **посередине — подсказка** «Саша, слушаю» (`core/PillHint.kt`), не
 *    бегущая, а стоящая по центру, как «Ask Gemini»; первое слово её гасит.
 *  - **набор текстом — в ней же** ([edit]). У «З», «Д», «₽», «Е» тап
 *    посередине глушит микрофон, и пилюля на своём месте становится полем:
 *    сказанное — уже в нём, клавиатура поднимается, кружок — стрелка
 *    «отправить», ✕ — отмена. Раньше набор был отдельным окошком у кнопки, и
 *    владелец (26.09.2026): «если я просто кликаю на плашку, она возвращается
 *    к предыдущему виду и притягивается к кнопке. А надо, чтобы прямо там, в
 *    этой прекрасной нашей плашке, можно было писать». Туда же уходит всякий
 *    набор кнопок: правка дела, траты, граммов — одно поле на всё.
 *
 * Место (`core/PillGeometry.kt`) — три на выбор в «Кнопках на экране»:
 * сверху (с завода), снизу над клавиатурой или над навигацией, у кнопки
 * (прежнее, откат). Кнопки на экране пилюля не накрывает. Вид — стекло цвета
 * режима, уведённого в чернила, с градиентом к кружку голоса и свечением от
 * него (`core/PillLook.kt`).
 *
 * **Где окно на самом деле — меряем, а не верим.** По AOSP окна доступности
 * лежат от верха экрана, и пилюля считала по нему. На телефоне владельца
 * снизу без клавиатуры она «появляется очень низко и выходит за границу
 * экрана вниз» — значит, система ставит окно не туда, куда просили. Поэтому
 * после первой компоновки пилюля сверяет, где её окно оказалось на экране
 * (`getLocationOnScreen`), с тем, куда его ставили; разница — сдвиг системы,
 * он запоминается на все пилюли и вычитается, одна строка в журнал. Окно
 * сверху и снизу — без пределов (`FLAG_LAYOUT_NO_LIMITS`), иначе
 * WindowManager подрезал бы его к краю, и замер мерил бы подрезку, а не
 * сдвиг. Снизу без клавиатуры пилюля к тому же не опускается ниже
 * [PillLook.MIN_FLOOR_DP]: у жестов Samsung полоса навигации бывает нулевой.
 *
 * Сверху окно не меняется вовсе: его верх стоит ровно по нижнему краю строки
 * состояния, пилюля выезжает из-за этого края (как уведомление), а под ней
 * запас на проскок ([PillLook.TOP_ROOM_DP]). Над строкой состояния окно не
 * заходит — там оно ловило бы касания и мешало стянуть шторку.
 *
 * Снизу нужна клавиатура, а служба её не подсматривает: правило Fold — «за
 * чужими окнами не подглядывать, на оконные события не подписываться».
 * Высоту клавиатуры отдаёт метрика СВОЕГО окна
 * (`WindowManager.currentWindowMetrics` — «сырые» отступы экрана, клавиатура
 * в них есть), одним вызовом в WindowManager, не на главном потоке службы: на
 * показ и раз в [POLL_MS], пока пилюля видна. Складывание идёт — не
 * спрашиваем вовсе. Окно снизу — пилюля и запас над ней ([PillLook.ROOM_DP])
 * на проскок; на время всплытия и ухода оно вытягивается вниз на глубину
 * всплытия и потом ужимается, не двигая верх: окно, которое и едет, и
 * растёт, система догоняет рывком.
 */
class DictationPill(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val accent: Int,
    @param:DrawableRes private val glyph: Int,
    private val textSizeSp: Float,
    /** Размер экрана у кнопки-хозяйки: у неё он уже закэширован. */
    private val screen: () -> Pair<Int, Int>,
    /** Кнопка-хозяйка на экране — для места «У кнопки». */
    private val owner: () -> PillGeometry.Box?,
) {

    companion object {
        private val PAPER = 0xFFF7F3EA.toInt()
        /** Как часто спрашивать клавиатуру, пока пилюля видна. */
        private const val POLL_MS = 400L
        /** Меньше этого пилюля за клавиатурой не переезжает, dp. */
        private const val MOVE_DP = 3
        /** Долгое нажатие — как у кнопок на стекле. */
        private const val LONG_PRESS_MS = 450L
        /** Пилюля под пальцем чуть поджата — как кнопка, только мягче: она широкая. */
        private const val HELD_SCALE = 0.97f

        // Отступы экрана — общие на все пилюли: последнее, что узнали.
        // Новая пилюля встаёт по ним сразу, пока не пришёл свежий ответ.
        @Volatile private var lastIme = 0
        @Volatile private var lastNav = 0
        @Volatile private var lastStatus = 0
        @Volatile private var lastCutout = 0
        /** Сдвиг, который система добавляет к окну, — замер, общий на все пилюли. */
        private var frameDx = 0
        private var frameDy = 0
        /** Что последним записали в журнал: пишем только перемену, а не каждый тейк. */
        private var loggedIme = -1
    }

    /** Тап посередине. У «З», «Д», «₽», «Е» — набор вместо голоса; у «П» — ничего. */
    var onTap: (() -> Unit)? = null

    /** Тап по кружку — «отправить»: тот же тап, что по пишущей кнопке. */
    var onSend: (() -> Unit)? = null

    /** Идёт запись — кружку есть что отправлять. Иначе его тап начал бы новую. */
    var canSend: Boolean = false
        set(value) {
            field = value
            orb?.isClickable = value || editing
        }

    /** Отмена этой записи — ✕ слева. Нет её — слева знак режима. */
    var onCancel: (() -> Unit)? = null
        set(value) {
            field = value
            lead?.cancel = value != null || editing
        }

    /** Пилюля на экране или уже в пути туда — серой «отмене» у кнопки тогда не место. */
    val showing: Boolean get() = visible

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private fun dp(value: Int): Int = (value * density).toInt()

    private var root: FrameLayout? = null
    private var body: LinearLayout? = null
    private var lead: LeadMark? = null
    private var ticker: MarqueeTickerView? = null
    private var orb: VoiceOrb? = null
    private var skin: PillSkin? = null
    private var params: WindowManager.LayoutParams? = null

    /** Пилюля должна быть на экране (окна может ещё не быть — ждём отступы экрана). */
    private var visible = false
    /** Номер показа: ответ про отступы для старого показа — выбросить. */
    private var gen = 0
    private var animator: ValueAnimator? = null
    /** Идёт всплытие или уход: окну нужен запас снизу. */
    private var travelling = false
    private var pollJob: Job? = null
    /** Текст и подсказка, пришедшие раньше окна: окно ждёт ответа про отступы. */
    private var pendingText: String? = null
    private var pendingHint: String? = null
    private var lastText = ""
    private var lastAt = 0L
    private var level = 0f
    /** Где стоит этот показ — решено на показе, настройка действует со следующего. */
    private var place = PillGeometry.Place.TOP
    /** Экран, под который считаны отступы: сложили или повернули — перечитать. */
    private var shownScreen: Pair<Int, Int>? = null
    /** Владелец переставил пилюлю пальцем: до конца показа она стоит, где бросили. */
    private var manual = false

    /** Что набираем: исходный текст, подсказка, куда отдать и что делать на отмене. */
    private class EditRequest(
        val prefill: String,
        val hint: String,
        val onSubmit: (String) -> Unit,
        val onCancel: (() -> Unit)?,
    )

    /** Просьба набрать текстом — ждёт окна, если его ещё нет. */
    private var editReq: EditRequest? = null
    /** Пилюля сейчас поле ввода: окно фокусное, клавиатура поднята. */
    private var editing = false
    private var editor: EditText? = null

    /** Пилюля — поле ввода (а не бегущая строка). */
    val isEditing: Boolean get() = editing || editReq != null

    val windowCount: Int get() = if (root != null) 1 else 0

    // ---- Показ, текст, уход ----

    fun show() {
        // Новая запись поверх набора: поле закрывается, пилюля снова строка.
        if (editing) endEdit()
        editReq = null
        val already = visible
        visible = true
        lastText = ""
        lastAt = 0L
        pendingText = null
        pendingHint = null
        if (root != null) {
            // Каждый показ — с чистой строки, как было у прежней плашки.
            ticker?.reset()
            // Висит и так (идёт всплытие — пусть доигрывает) — всё.
            if (already) return
            // Окно ещё висит, но уходит после hide(). Конец тейка и начало
            // стрима чистки идут вплотную (hide → show): вернуть на место без
            // второго всплытия, иначе пилюля подпрыгивала бы между ними.
            // Переставленная пальцем остаётся, где была: это тот же показ.
            stopTravel()
            body?.translationY = 0f
            body?.alpha = 1f
            rest()
            relayout()
            startPolling()
            return
        }
        // Окна ещё нет, но оно уже в пути — ждёт ответа про отступы экрана.
        if (already) return
        level = 0f
        manual = false
        place = service.cachedTickerPlace
        if (place == PillGeometry.Place.BESIDE) {
            create()
            relayout()
            fadeIn()
            return
        }
        val g = ++gen
        scope.launch {
            val changed = refreshInsets()
            if (g != gen || !visible || root != null) return@launch
            if (changed && place == PillGeometry.Place.BOTTOM) logInsets()
            create()
            enter()
            startPolling()
        }
    }

    fun update(text: String, force: Boolean = false) {
        if (text == lastText) return
        val now = SystemClock.uptimeMillis()
        // [force] — для коротких надписей службы: их одна за тейк, и
        // проглотить её потолком частоты значит соврать о том, слышит движок
        // или ещё нет. Остальное — не чаще раза в 60 мс: строка всё равно
        // сглаживает движение сама.
        if (!force && now - lastAt < 60) return
        lastAt = now
        lastText = text
        val tv = ticker
        if (tv == null) {
            if (visible) pendingText = text
            return
        }
        tv.setTickerText(text)
    }

    /** Подсказка посередине, пока слов нет: «Саша, секунду…», «Саша, слушаю». */
    fun hint(text: String) {
        val tv = ticker
        if (tv == null) {
            if (visible) pendingHint = text
            return
        }
        tv.setHint(text)
    }

    /** Громкость 0..1 — волна в кружке. Сглаживание то же, что у пульса кнопки. */
    fun setLevel(value: Float) {
        level = MicLevel.smooth(level, value)
        orb?.setLevel(level)
    }

    /** Запись кончилась: волна — снова значок. */
    fun rest() {
        level = 0f
        orb?.setLevel(0f)
    }

    /** Кнопку тащат, экран повернули, ширину или плотность поменяли. */
    fun reposition() {
        if (!visible || root == null) return
        skin?.setDensity(service.cachedTickerDensity)
        val now = screen()
        if (now != shownScreen && place != PillGeometry.Place.BESIDE) {
            // Сложили или повернули посреди записи: строка состояния и вырез
            // другие — перечитать отступы и встать заново, и переставленной
            // пальцем тоже: прежнего места на новом экране нет.
            shownScreen = now
            manual = false
            scope.launch { if (refreshInsets() && visible) relayout() }
        }
        relayout()
    }

    fun hide() {
        if (!visible) return
        visible = false
        gen++
        stopPolling()
        pendingText = null
        pendingHint = null
        val b = body
        if (root == null || b == null) return
        stopTravel()
        val from = b.translationY
        // Переставленная пальцем гаснет на месте: её окно уже не вытянешь к краю.
        if (place == PillGeometry.Place.BESIDE || manual) {
            travel(PillLook.EXIT_MS, AccelerateInterpolator()) { f -> b.alpha = 1f - f }
            return
        }
        if (place == PillGeometry.Place.TOP) {
            // Обратно под строку состояния — тем же путём, каким выехала.
            val to = -dp(PillLook.topTravelDp()).toFloat()
            travel(PillLook.EXIT_MS, AccelerateInterpolator()) { f ->
                b.translationY = from + (to - from) * f
                b.alpha = 1f - 0.5f * f
            }
            return
        }
        // Уход вниз — по вытянутому окну: иначе низ пилюли срезался бы краем.
        travelling = true
        relayout()
        val to = dp(PillLook.RISE_DP) * 0.7f
        travel(PillLook.EXIT_MS, AccelerateInterpolator()) { f ->
            b.translationY = from + (to - from) * f
            b.alpha = 1f - f
        }
    }

    fun destroy() {
        visible = false
        gen++
        stopPolling()
        drop()
    }

    // ---- Окно ----

    private fun create() {
        val pillH = dp(PillLook.HEIGHT_DP)
        val orbSize = pillH - dp(14)
        val orbEnd = dp(7)
        val s = PillSkin(accent, orbCentreFromRight = orbEnd + orbSize / 2f).apply {
            setDensity(service.cachedTickerDensity)
        }
        val row = PillRow(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = s
            // Дети режутся по контуру пилюли: и текст, и свечение, и кружок.
            clipToOutline = true
            // Кликабельна всегда: иначе ряд не взял бы касание, и ни долгого
            // нажатия, ни переноса не было бы там, где нет кнопки.
            setOnClickListener { onTap?.invoke() }
        }
        // mutate: свой экземпляр знака — тинт и альфа не должны уйти в знак на кнопке.
        val l = LeadMark(service, service.getDrawable(glyph)?.mutate()).apply {
            // Слушатель — ДО флага: setOnClickListener сам делает вид
            // кликабельным, и знак режима глотал бы тап, положенный ряду.
            setOnClickListener { if (editing) cancelTyped() else onCancel?.invoke() }
            cancel = onCancel != null
        }
        // Мишень ✕ — во всю высоту и 48 dp в ширину: крестик маленький, палец нет.
        row.addView(l, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(6)
        })
        val tv = MarqueeTickerView(service, textColor = PAPER, textSizeSp = textSizeSp)
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        val o = VoiceOrb(service, PillLook.orb(accent)).apply {
            setLevel(level)
            contentDescription = "Отправить"
            setOnClickListener { if (editing) submitTyped() else if (canSend) onSend?.invoke() }
            isClickable = canSend
        }
        row.addView(o, LinearLayout.LayoutParams(orbSize, orbSize).apply {
            marginStart = dp(4)
            marginEnd = orbEnd
        })
        val frame = FrameLayout(service)
        frame.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, pillH).apply {
            gravity = Gravity.TOP
            topMargin = room()
        })
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // Сверху и снизу — без пределов: снизу окно на время всплытия вытянуто
        // за край, а замер сдвига (calibrate) должен мерить сдвиг, а не
        // подрезку к краю.
        if (place != PillGeometry.Place.BESIDE) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val p = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        root = frame
        body = row
        lead = l
        ticker = tv
        orb = o
        skin = s
        params = p
        pendingHint?.let { tv.setHint(it) }
        pendingText?.let { tv.setTickerText(it) }
        pendingHint = null
        pendingText = null
        shownScreen = screen()
        spotFor()?.let { layout(p, it) }
        frame.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int,
            ) {
                v.removeOnLayoutChangeListener(this)
                calibrate()
            }
        })
        runCatching { windowManager.addView(frame, p) }
        if (editReq != null) applyEdit()
    }

    // ---- Набор текстом в самой пилюле ----

    /**
     * Стать полем ввода на своём месте: [prefill] — уже сказанное (или то, что
     * правим), [hint] — подсказка пустого поля. Кружок — «отправить» ([onSubmit]
     * с набранным), ✕ — отмена ([onCancel]). Пилюли нет — всплывает, как на
     * запись; есть — превращается, не уезжая.
     */
    fun edit(prefill: String, hint: String, onSubmit: (String) -> Unit, onCancel: (() -> Unit)?) {
        show()
        editReq = EditRequest(prefill, hint, onSubmit, onCancel)
        if (root != null) applyEdit()
    }

    /** Закрыть поле без ответа никому: кнопку выключили, экран сложили, началась запись. */
    fun dropEdit() {
        if (!isEditing) return
        endEdit()
        hide()
    }

    private fun applyEdit() {
        val req = editReq ?: return
        val row = body ?: return
        val r = root ?: return
        val p = params ?: return
        val tv = ticker ?: return
        val e = editor ?: EditText(service).apply {
            setTextColor(PAPER)
            setHintTextColor(DiskLook.withAlpha(PAPER, 0.62f))
            textSize = textSizeSp
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(8), 0, dp(4), 0)
            gravity = Gravity.CENTER_VERTICAL
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { submitTyped(); true } else false
            }
        }.also { made ->
            // Поле встаёт на место строки: те же поля, та же доля ряда.
            row.addView(made, row.indexOfChild(tv), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            editor = made
        }
        editing = true
        tv.visibility = View.GONE
        e.visibility = View.VISIBLE
        e.setText(req.prefill)
        e.setSelection(req.prefill.length)
        e.hint = req.hint
        lead?.cancel = true
        orb?.arrow = true
        orb?.isClickable = true
        // Окно — фокусное: без этого клавиатура к полю не привяжется. Касания
        // мимо пилюли по-прежнему уходят приложению (NOT_TOUCH_MODAL).
        p.flags = (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        runCatching { windowManager.updateViewLayout(r, p) }
        // Не один showSoftInput, а до победного: фокус окно получает уже после
        // смены флагов, и первый вызов молча возвращает false.
        ImeKick.raise(service, e)
    }

    private fun submitTyped() {
        val req = editReq ?: return
        val typed = editor?.text?.toString().orEmpty()
        endEdit()
        hide()
        req.onSubmit(typed)
    }

    private fun cancelTyped() {
        val req = editReq
        endEdit()
        hide()
        req?.onCancel?.invoke()
    }

    /** Поле обратно в строку, окно — снова не фокусное, клавиатура — вниз. */
    private fun endEdit() {
        val e = editor
        if (e != null && editing) {
            runCatching {
                service.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(e.windowToken, 0)
            }
        }
        editing = false
        editReq = null
        e?.visibility = View.GONE
        ticker?.visibility = View.VISIBLE
        lead?.cancel = onCancel != null
        orb?.arrow = false
        orb?.isClickable = canSend
        val r = root ?: return
        val p = params ?: return
        p.flags = (p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        p.softInputMode = 0
        runCatching { windowManager.updateViewLayout(r, p) }
    }

    /**
     * Сверить, где окно оказалось на экране, с тем, куда его ставили. Разница
     * — сдвиг системы; запоминаем на все пилюли и встаём заново. Пилюля в эти
     * кадры ещё прозрачна и за краем своего окна — поправка не видна.
     */
    private fun calibrate() {
        if (place == PillGeometry.Place.BESIDE) return
        val r = root ?: return
        val p = params ?: return
        val loc = IntArray(2)
        r.getLocationOnScreen(loc)
        val dx = loc[0] - p.x
        val dy = loc[1] - p.y
        if (dx == frameDx && dy == frameDy) return
        frameDx = dx
        frameDy = dy
        service.logEvent("пилюля: система сдвигает окно на ${(dx / density).toInt()}×${(dy / density).toInt()} dp — ставлю с поправкой")
        relayout()
    }

    /**
     * Прозрачное над пилюлей внутри окна: снизу — запас на проскок вверх,
     * сверху — зазор до строки состояния (из-за его верхнего края пилюля и
     * выезжает).
     */
    private fun room(): Int = when (place) {
        PillGeometry.Place.BOTTOM -> dp(PillLook.ROOM_DP)
        PillGeometry.Place.TOP -> dp(PillLook.GAP_DP)
        PillGeometry.Place.BESIDE -> 0
    }

    /** Прозрачное под пилюлей: сверху — запас на проскок вниз. */
    private fun below(): Int = if (place == PillGeometry.Place.TOP) dp(PillLook.TOP_ROOM_DP) else 0

    /** Сдвиг системы по осям — у места «у кнопки» его нет: там всё в координатах кнопки. */
    private fun offX(): Int = if (place == PillGeometry.Place.BESIDE) 0 else frameDx
    private fun offY(): Int = if (place == PillGeometry.Place.BESIDE) 0 else frameDy

    /** Место на экране по нынешним кнопкам, клавиатуре и настройкам — в координатах экрана. */
    private fun spotFor(): PillGeometry.Spot? {
        val (w, h) = screen()
        val pillH = dp(PillLook.HEIGHT_DP)
        val want = dp(service.cachedTickerWidthDp)
        val side = dp(PillLook.SIDE_DP)
        val gap = dp(PillLook.GAP_DP)
        val minW = dp(PillLook.MIN_WIDTH_DP)
        if (place == PillGeometry.Place.BESIDE) {
            val b = owner() ?: return null
            return PillGeometry.beside(w, h, b, want, pillH, gap = dp(8), margin = dp(24))
        }
        // Кнопки стоят в координатах окон — на экран их переводит тот же сдвиг.
        val obstacles = service.pillObstacles().map {
            PillGeometry.Box(it.left + frameDx, it.top + frameDy, it.right + frameDx, it.bottom + frameDy)
        }
        return if (place == PillGeometry.Place.TOP) {
            PillGeometry.top(w, PillGeometry.ceiling(lastStatus, lastCutout, gap), want, pillH, side, gap, minW, obstacles)
        } else {
            val floor = PillGeometry.floor(h, lastIme, lastNav, gap, minBottom = dp(PillLook.MIN_FLOOR_DP))
            PillGeometry.bottom(w, floor, want, pillH, side, gap, minW, obstacles)
        }
    }

    /** Параметры окна под место: запас сверху, пилюля, и на ходу — глубина всплытия снизу. */
    private fun layout(p: WindowManager.LayoutParams, spot: PillGeometry.Spot): Boolean {
        val room = room()
        val rise = if (place == PillGeometry.Place.BOTTOM && travelling) dp(PillLook.RISE_DP) else 0
        val x = spot.x - offX()
        val y = spot.y - room - offY()
        val width = spot.width
        val height = room + dp(PillLook.HEIGHT_DP) + below() + rise
        if (p.x == x && p.y == y && p.width == width && p.height == height) return false
        p.x = x
        p.y = y
        p.width = width
        p.height = height
        return true
    }

    private fun relayout() {
        val r = root ?: return
        val p = params ?: return
        // Переставленная пальцем стоит, где бросили, до конца показа.
        if (manual) return
        val spot = spotFor() ?: return
        if (layout(p, spot)) runCatching { windowManager.updateViewLayout(r, p) }
    }

    // ---- Перенос пальцем ----

    /**
     * Ряд пилюли: палец, который ПОЕХАЛ, берёт пилюлю с собой — где угодно,
     * даже с ✕ или кружка; подержал на месте — тоже взял (с вибрацией: «можно
     * везти»). Первая версия брала только после удержания, а сдвиг раньше
     * срока считала сорвавшимся тапом, и владелец (26.09.2026): «почему-то
     * нельзя её двигать, если я беру её и начинаю тянуть». Тапам это не
     * мешает: тап — это палец, который не поехал. Смотрим на касание до детей
     * (dispatchTouchEvent): взяли — детям уходит отмена, чтобы ни ✕, ни
     * «отправить» не сработали от того же пальца, и дальше касание целиком
     * наше.
     */
    private inner class PillRow(context: Context) : LinearLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var armed = false
        private var dragging = false
        // В поле ввода долгое нажатие — выделение текста, а не «взять пилюлю»:
        // переносить её там можно только движением.
        private val grab = Runnable { if (armed && !editing) startDrag(buzz = true) }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    armed = true
                    dragging = false
                    postDelayed(grab, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        moveTo(ev.rawX - downX, ev.rawY - downY)
                        return true
                    }
                    if (armed && (abs(ev.rawX - downX) > touchSlop || abs(ev.rawY - downY) > touchSlop)) {
                        removeCallbacks(grab)
                        startDrag(buzz = false)
                        // От точки касания, а не от порога: пилюля остаётся
                        // под пальцем тем же местом, за которое взяли.
                        moveTo(ev.rawX - downX, ev.rawY - downY)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    armed = false
                    removeCallbacks(grab)
                    if (dragging) {
                        dragging = false
                        scaleX = 1f
                        scaleY = 1f
                        return true
                    }
                }
            }
            if (dragging) return true
            return super.dispatchTouchEvent(ev)
        }

        /** [buzz] — вибрация, когда взяли удержанием: палец стоит, ему надо сказать «поехали». */
        private fun startDrag(buzz: Boolean) {
            val p = params ?: return
            armed = false
            // Детям — отмена: палец больше не их, клик не сработает.
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            // Всплытие не доиграло — поставить на место сейчас, окно — в свой размер.
            stopTravel()
            translationY = 0f
            alpha = 1f
            relayout()
            manual = true
            dragging = true
            startX = p.x
            startY = p.y
            scaleX = HELD_SCALE
            scaleY = HELD_SCALE
            if (buzz) Haptics.start(service)
        }

        private fun moveTo(dx: Float, dy: Float) {
            val r = root ?: return
            val p = params ?: return
            val (w, h) = screen()
            // На экране целиком: в координатах окна экран начинается со сдвига системы.
            p.x = (startX + dx.toInt()).coerceIn(-offX(), (w - p.width - offX()).coerceAtLeast(-offX()))
            p.y = (startY + dy.toInt()).coerceIn(-offY(), (h - p.height - offY()).coerceAtLeast(-offY()))
            runCatching { windowManager.updateViewLayout(r, p) }
        }
    }

    // ---- Движение ----

    /**
     * Появление на пружине с одним проскоком («оп — назад»): сверху — из-под
     * строки состояния вниз, окно не меняется; снизу — из глубины вверх, по
     * вытянутому на время окну.
     */
    private fun enter() {
        val b = body ?: return
        val start = if (place == PillGeometry.Place.TOP) {
            -dp(PillLook.topTravelDp()).toFloat()
        } else {
            travelling = true
            dp(PillLook.RISE_DP).toFloat()
        }
        b.translationY = start
        b.alpha = 0f
        relayout()
        travel(PillLook.ENTER_MS, LinearInterpolator()) { t ->
            b.translationY = start * (1f - PillLook.spring(t))
            b.alpha = PillLook.enterAlpha(t)
        }
    }

    /** У кнопки — прежнее проявление на месте. */
    private fun fadeIn() {
        val b = body ?: return
        b.alpha = 0f
        travel(180L, LinearInterpolator()) { f -> b.alpha = f }
    }

    /**
     * Один аниматор на всё движение. ValueAnimator, а не `view.animate()`:
     * тот на только что прикреплённом окне не стартует, пока окно не прошло
     * компоновку (урок «кнопка правки пропала» в `FloatingButtonController`).
     */
    private fun travel(ms: Long, interp: android.animation.TimeInterpolator, step: (Float) -> Unit) {
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = interp
        }
        a.addUpdateListener { step(it.animatedFraction) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Отменённый — не наш: его место уже занял следующий.
                if (animator !== a) return
                animator = null
                step(1f)
                travelling = false
                if (visible) {
                    body?.translationY = 0f
                    body?.alpha = 1f
                    relayout()  // снизу — ужать окно обратно: только низ, верх на месте
                } else {
                    drop()
                }
            }
        })
        animator = a
        a.start()
    }

    private fun stopTravel() {
        val a = animator ?: return
        animator = null
        a.cancel()
        travelling = false
    }

    private fun drop() {
        stopTravel()
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        body = null
        lead = null
        editor = null
        editing = false
        ticker = null
        orb = null
        skin = null
        params = null
        lastText = ""
        lastAt = 0L
        manual = false
    }

    // ---- Отступы экрана: строка состояния сверху, клавиатура снизу ----

    /**
     * Спросить отступы экрана: строка состояния и вырез сверху, клавиатура и
     * навигация снизу. Не на главном потоке — это вызов в WindowManager, а
     * главный поток службы занят кнопками. true — что-то поменялось.
     */
    private suspend fun refreshInsets(): Boolean = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext false
        runCatching {
            val insets = windowManager.currentWindowMetrics.windowInsets
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val status = insets.getInsets(WindowInsets.Type.statusBars()).top
            val cutout = insets.getInsets(WindowInsets.Type.displayCutout()).top
            val changed = ime != lastIme || nav != lastNav || status != lastStatus || cutout != lastCutout
            lastIme = ime
            lastNav = nav
            lastStatus = status
            lastCutout = cutout
            changed
        }.getOrDefault(false)
    }

    private fun logInsets() {
        val ime = (lastIme / density).toInt()
        if (ime == loggedIme) return
        loggedIme = ime
        service.logEvent(
            if (ime > 0) "пилюля: над клавиатурой $ime dp"
            else "пилюля: клавиатуры нет, над навигацией ${(lastNav / density).toInt()} dp"
        )
    }

    private fun startPolling() {
        stopPolling()
        // Спрашивать на ходу есть смысл только снизу — там клавиатура. Сверху
        // строка состояния посреди записи не меняется, а поворот и
        // складывание ловит [reposition].
        if (place != PillGeometry.Place.BOTTOM) return
        pollJob = scope.launch {
            while (isActive && visible) {
                delay(POLL_MS)
                if (service.folding || !visible || manual) continue
                val before = params?.y
                if (!refreshInsets() || !visible || manual) continue
                logInsets()
                val spot = spotFor() ?: continue
                val now = before ?: continue
                // Клавиатура открылась или закрылась посреди тейка: пилюля
                // всплывает заново уже на новом полу — тем же движением.
                if (abs(spot.y - room() - offY() - now) >= dp(MOVE_DP) && animator == null) enter() else relayout()
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ---- Слева: ✕ отмены или знак режима ----

    /**
     * Левый край пилюли. Есть что отменять — ✕ на еле заметном кружке (кружок
     * говорит «это кнопка», у Gemini плюс так же стоит в своей мишени);
     * нечего — знак режима, как было.
     */
    private class LeadMark(context: Context, private val glyph: Drawable?) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PAPER
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density

        var cancel = false
            set(value) {
                field = value
                isClickable = value
                contentDescription = if (value) "Отмена" else null
                invalidate()
            }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            if (!cancel) {
                val g = glyph ?: return
                val half = (11 * density).toInt()
                g.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                g.setTint(PAPER)
                g.alpha = (255 * 0.82f).toInt()
                g.draw(canvas)
                return
            }
            disc.color = DiskLook.white(if (isPressed) 0.22f else 0.10f)
            canvas.drawCircle(cx, cy, 16 * density, disc)
            stroke.strokeWidth = 2 * density
            val arm = 6 * density
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, stroke)
            canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, stroke)
        }
    }

    // ---- Кружок голоса: круг цвета режима и волна из трёх полосок ----

    private class VoiceOrb(context: Context, color: Int) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = PAPER
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var level = 0f

        /** Поле ввода: вместо волны — стрелка «отправить», как у Gemini при наборе. */
        var arrow = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        fun setLevel(value: Float) {
            if (abs(value - level) < 0.01f) return
            level = value
            invalidate()
        }

        /** Нажали «отправить» — кружок темнеет под пальцем, как клавиша. */
        private val pressedShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = DiskLook.black(0.22f) }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val r = minOf(w, h) / 2f
            // Блик сверху, как у кнопок на стекле: кружок — клавиша, а не пятно.
            sheen.shader = RadialGradient(
                w / 2f, h / 2f - r * 0.6f, r * 1.2f,
                intArrayOf(DiskLook.white(0.22f), DiskLook.white(0.06f), DiskLook.white(0f)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            bar.strokeWidth = r * 0.17f
        }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, sheen)
            if (isPressed) canvas.drawCircle(cx, cy, r, pressedShade)
            if (arrow) {
                // Стрелка вверх: древко и два пера, тем же штрихом, что волна.
                val len = r * 0.42f
                val wing = r * 0.26f
                canvas.drawLine(cx, cy + len, cx, cy - len, bar)
                canvas.drawLine(cx, cy - len, cx - wing, cy - len + wing, bar)
                canvas.drawLine(cx, cy - len, cx + wing, cy - len + wing, bar)
                return
            }
            val bars = PillLook.bars(level)
            val maxH = r * 0.9f
            val step = r * 0.36f
            for (i in 0..2) {
                val x = cx + (i - 1) * step
                val half = maxH * bars[i] / 2f
                canvas.drawLine(x, cy - half, x, cy + half, bar)
            }
        }
    }

    // ---- Стекло пилюли ----

    /**
     * Заливка слева направо (почти чернила → цвет режима гуще), свечение от
     * кружка голоса, блик по верхней трети и светлая кромка. Плотность
     * гасит заливку сильно, свечение и кромку — слабо: на прозрачном стекле
     * форму держит край, а цвет режима — свечение.
     */
    private class PillSkin(private val accent: Int, private val orbCentreFromRight: Float) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val face = RectF()
        private var density = PillLook.DENSITY_DEFAULT

        fun setDensity(value: Float) {
            if (value == density && fill.shader != null) return
            density = value
            build(bounds)
            invalidateSelf()
        }

        override fun onBoundsChange(bounds: Rect) = build(bounds)

        private fun build(b: Rect) {
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val d = density.coerceIn(0f, 1f)
            fill.shader = LinearGradient(
                b.left.toFloat(), 0f, b.right.toFloat(), 0f,
                DiskLook.withAlpha(PillLook.bodyStart(accent), d),
                DiskLook.withAlpha(PillLook.bodyEnd(accent), d),
                Shader.TileMode.CLAMP,
            )
            val g = PillLook.glowAlpha(d)
            glow.shader = RadialGradient(
                b.right - orbCentreFromRight, b.exactCenterY(), h * 1.9f,
                intArrayOf(
                    DiskLook.withAlpha(accent, g),
                    DiskLook.withAlpha(accent, g * 0.35f),
                    DiskLook.withAlpha(accent, 0f),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            val top = b.top.toFloat()
            sheen.shader = LinearGradient(
                0f, top, 0f, top + h * 0.45f,
                DiskLook.white(PillLook.sheenAlpha(d)), DiskLook.white(0f),
                Shader.TileMode.CLAMP,
            )
            rim.strokeWidth = (h * 0.018f).coerceAtLeast(1f)
            val rimA = PillLook.rimAlpha(d)
            rim.shader = LinearGradient(
                0f, top, 0f, b.bottom.toFloat(),
                DiskLook.white(rimA), DiskLook.white(rimA * 0.35f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            face.set(b)
            val r = face.height() / 2f
            canvas.drawRoundRect(face, r, r, fill)
            canvas.drawRoundRect(face, r, r, glow)
            canvas.drawRoundRect(face, r, r, sheen)
            val inset = rim.strokeWidth / 2f
            face.inset(inset, inset)
            canvas.drawRoundRect(face, r - inset, r - inset, rim)
        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, bounds.height() / 2f)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
