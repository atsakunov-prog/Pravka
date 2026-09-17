package ru.zf.pravka.trigger

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.BuildConfig
import ru.zf.pravka.R
import ru.zf.pravka.core.StackGeometry
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.Models
import ru.zf.pravka.data.Settings
import ru.zf.pravka.provider.MicRouting
import ru.zf.pravka.ui.Haptics

/**
 * Шестерёнка над «П» и её веер: быстрые настройки прямо на стекле.
 *
 * Владелец (18.09.2026): «надо добавить кнопку с настройками. Когда тапаю на
 * неё, открывается несколько таких же кругленьких штучек, которые
 * переключаются тапами. Первое — модель для Правки: включена, синенькая —
 * Опус, выключена — Сонет. Второе — слушать наушники или bt. Проверка
 * обновлений точно. И кнопка скрыть все кнопки — чтобы всё сжималось до
 * маленькой красной точки с П, ещё один тап — открывается».
 *
 * Она же убирает грязь со стекла: прежде над «П» висели три точки (убрать
 * всё), а между «П» и «З» — плашка микрофона. Оба переехали сюда: точки
 * стали кружком «спрятать» в веере, плашка — кружком микрофона.
 *
 * Окна: ГОЛОВА — шестерёнка (кнопки на экране) ИЛИ красная точка (всё
 * убрано), одно из двух; ВЕЕР — одно окно с рядом кружков, живёт секунды;
 * ЗАПИСКА — что сделал тап. Все три снимаются из WindowManager на
 * складывание Fold и при уборке: правило проекта, оплаченное четырьмя
 * чернотами (`docs/agreements.md`).
 *
 * Голову таскают, как кнопку, и за ней едет вся связка — иначе, когда всё
 * убрано, точка была бы единственным на экране и приросла бы к месту.
 *
 * Прозрачность головы и кружков — та же настройка, что у кнопок (владелец:
 * «прозрачность бабблов должна быть такая же, как и прозрачность кнопок»);
 * записка — текст, ей нужно быть читаемой, она непрозрачна, как бегущая
 * строка.
 */
class StackSettingsController(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val settings: Settings,
) {

    private companion object {
        /** Приглушённый «ink-soft», как у ручки: выключенный переключатель молчит. */
        private val GREY = 0xFF6E6659.toInt()
        /** «Синенькая»: включённый переключатель. Тот же синий, что был у гарнитуры на плашке. */
        private val ON = 0xFF2B7FA3.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
        private val NOTE_INK = 0xFF241F19.toInt()
        private val NOTE_BAD = 0xFFA8261B.toInt()
        private const val NOTE_ALPHA = 0.94f
        /** Веер не модальный (оверлей чужих тапов не видит) — уходит сам. */
        private const val FAN_HOLD_MS = 7_000L
        private const val NOTE_HOLD_MS = 2_200L
        /** Гарнитура выбрана, а её не видно среди входов — кружок бледнеет во столько. */
        private const val ABSENT_FACTOR = 0.45f
    }

    /** Кружки веера. Порядок в ряду — от шестерёнки: частое ближе, «спрятать» дальше всех. */
    enum class Knob { MODEL, MIC, UPDATE, HIDE }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val audioManager = service.getSystemService(AudioManager::class.java)
    private val density = service.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private val main = Handler(Looper.getMainLooper())

    private var buttonSize = dp(Settings.FAB_SIZE_DEFAULT)
    private var idleAlpha = Settings.FAB_ALPHA_DEFAULT

    // Голова: шестерёнка или точка. Позиция переживает пересборку головы.
    private var head: FrameLayout? = null
    private var headGlyph: View? = null
    private var headParams: WindowManager.LayoutParams? = null
    private var headIsDot = false
    private var headX = -1
    private var headY = -1

    private var fan: LinearLayout? = null
    private var fanParams: WindowManager.LayoutParams? = null
    private val knobs = java.util.EnumMap<Knob, FrameLayout>(Knob::class.java)
    private val fanDismiss = Runnable { hideFan() }

    private var note: TextView? = null
    private val noteDismiss = Runnable { hideNote() }

    // Состояние переключателей — из настроек, своего не хранит.
    private var strongModel = true
    private var modelLabel = ""
    private var headset = false
    private var headsetPresent = false
    private var deviceCallback: AudioDeviceCallback? = null
    private var checkingUpdates = false

    /** Любое касание — стопке отсчёт простоя заново. */
    var onTouched: (() -> Unit)? = null
    /** Голову тащат: координаты головы; связка едет за ней (см. [buttonOrigin]). */
    var onDragged: ((x: Int, y: Int, dropped: Boolean) -> Unit)? = null
    /** Кружок «спрятать»: убрать всё, оставить точку. */
    var onHideAll: (() -> Unit)? = null
    /** Тап по точке: вернуть всё. */
    var onShowAll: (() -> Unit)? = null

    init {
        scope.launch {
            settings.modelChoiceFlow(ModelRoute.PRAVKA).collect {
                strongModel = it.model != Settings.MODEL_SONNET
                modelLabel = Models.label(it.model)
                paintKnob(Knob.MODEL)
            }
        }
        scope.launch {
            settings.phoneMicOnlyFlow.collect {
                headset = !it
                paintKnob(Knob.MIC)
            }
        }
        scope.launch {
            settings.fabAlphaFlow.collect { alpha ->
                idleAlpha = alpha
                head?.alpha = alpha
                Knob.values().forEach { paintKnob(it) }
            }
        }
    }

    // ---- Голова ----

    /** Размер головы, какая она сейчас: шестерёнка или точка. */
    fun headSizePx(): Int =
        if (headIsDot) StackGeometry.dotSize(buttonSize) else StackGeometry.gearSize(buttonSize)

    fun currentPosition(): Pair<Int, Int>? = headParams?.let { it.x to it.y }

    /**
     * Показать голову нужного вида. Уже стоит такая — ничего не делает; стоит
     * другая — пересобирается на том же месте (окно одно в любой момент).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(dot: Boolean) {
        if (head != null && headIsDot == dot) return
        hideHead()
        headIsDot = dot
        val size = headSizePx()
        val frame = FrameLayout(service)
        val glyph: View = if (dot) {
            ImageView(service).apply {
                setImageResource(R.drawable.ic_fab_glyph)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(FloatingButtonController.ACCENT)
                }
            }
        } else {
            GearGlyph(service).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(GREY)
                }
            }
        }
        glyph.elevation = dp(3).toFloat()
        frame.addView(glyph, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        frame.alpha = idleAlpha
        frame.setOnTouchListener(HeadTouch())
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = headX.coerceAtLeast(0)
            y = headY.coerceAtLeast(0)
        }
        headParams = p
        head = frame
        headGlyph = glyph
        runCatching { windowManager.addView(frame, p) }
        // Точка ВЫСКАКИВАЕТ: всё сжалось в неё, и ей положено появиться с
        // перелётом. Шестерёнка встаёт тихо — она возвращается на своё место.
        if (dot) BubbleMotion.pop(glyph, 0, 1f)
    }

    /** Голова над «П» по центру; [buttonSize] — текущий размер кнопки. */
    fun moveTo(buttonX: Int, buttonY: Int, buttonSize: Int) {
        if (buttonSize > 0 && buttonSize != this.buttonSize) {
            // Размер кнопок поменяли в настройках — голова пересобирается под новый.
            this.buttonSize = buttonSize
            if (head != null) {
                val dot = headIsDot
                hideHead()
                show(dot)
            }
        }
        val p = headParams ?: return
        val v = head ?: return
        val size = headSizePx()
        val (w, h) = screen()
        p.x = (buttonX + (buttonSize - size) / 2).coerceIn(0, (w - size).coerceAtLeast(0))
        p.y = (buttonY - size - dp(5)).coerceIn(0, (h - size).coerceAtLeast(0))
        headX = p.x
        headY = p.y
        runCatching { windowManager.updateViewLayout(v, p) }
        if (fan != null) placeFan()
    }

    /**
     * Где стоит «П», если голова стоит в ([headX], [headY]): ровно обратное
     * [moveTo], чтобы после броска голова осталась там, где палец её
     * отпустил, без доводки и прыжка.
     */
    fun buttonOrigin(headX: Int, headY: Int, buttonSize: Int): Pair<Int, Int> {
        val size = headSizePx()
        return (headX - (buttonSize - size) / 2) to (headY + size + dp(5))
    }

    private fun hideHead() {
        head?.let { runCatching { windowManager.removeView(it) } }
        head = null
        headGlyph = null
        headParams = null
    }

    private inner class HeadTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (service.isLockedIdle()) return true
            val p = headParams ?: return false
            val glyph = headGlyph ?: v
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    BubbleMotion.press(glyph)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        hideFan()
                        BubbleMotion.lift(glyph)
                    }
                    if (dragging) {
                        val size = headSizePx()
                        val (w, h) = screen()
                        p.x = (startX + dx.toInt()).coerceIn(0, (w - size).coerceAtLeast(0))
                        p.y = (startY + dy.toInt()).coerceIn(0, (h - size).coerceAtLeast(0))
                        headX = p.x
                        headY = p.y
                        runCatching { windowManager.updateViewLayout(v, p) }
                        onDragged?.invoke(p.x, p.y, false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    BubbleMotion.release(glyph)
                    if (dragging) {
                        onDragged?.invoke(p.x, p.y, true)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        onTouched?.invoke()
                        if (headIsDot) onShowAll?.invoke() else toggleFan()
                    }
                }
            }
            return true
        }
    }

    // ---- Веер ----

    private fun toggleFan() {
        if (fan != null) hideFan() else showFan()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFan() {
        hideFan()
        val hp = headParams ?: return
        val size = StackGeometry.gearSize(buttonSize)
        val gap = dp(8)
        val (w, _) = screen()
        // Веер раскрывается в сторону, где есть место; ряд строится так,
        // чтобы частые кружки стояли ближе к шестерёнке, а «спрятать» —
        // дальше всех: случайный тап по нему стоит дороже.
        val toRight = hp.x + size / 2 < w / 2
        val order = if (toRight) Knob.values().toList() else Knob.values().reversed()
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        knobs.clear()
        order.forEachIndexed { i, knob ->
            val bubble = makeKnob(knob, size)
            val lp = LinearLayout.LayoutParams(size, size).apply {
                if (i > 0) marginStart = gap
            }
            row.addView(bubble, lp)
            knobs[knob] = bubble
        }
        val p = WindowManager.LayoutParams(
            Knob.values().size * size + (Knob.values().size - 1) * gap,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        fanParams = p
        fan = row
        placeFan()
        runCatching { windowManager.addView(row, p) }
        watchDevices()
        refreshPresence()
        Knob.values().forEach { paintKnob(it) }
        // Волна от шестерёнки: ближний кружок первым.
        val fromGear = if (toRight) order else order.reversed()
        fromGear.forEachIndexed { i, knob ->
            knobs[knob]?.let { BubbleMotion.pop(it, i * 40L, knobAlpha(knob)) }
        }
        (headGlyph as? GearGlyph)?.spin(open = true)
        row.postDelayed(fanDismiss, FAN_HOLD_MS)
    }

    private fun placeFan() {
        val hp = headParams ?: return
        val p = fanParams ?: return
        val f = fan ?: return
        val size = StackGeometry.gearSize(buttonSize)
        val gap = dp(8)
        val (w, h) = screen()
        p.x = StackGeometry.fanX(hp.x, headSizePx(), Knob.values().size, size, gap, w)
        p.y = (hp.y + (headSizePx() - size) / 2).coerceIn(0, (h - size).coerceAtLeast(0))
        if (f.parent != null) runCatching { windowManager.updateViewLayout(f, p) }
    }

    fun hideFan() {
        val f = fan ?: return
        f.removeCallbacks(fanDismiss)
        fan = null
        fanParams = null
        knobs.clear()
        (headGlyph as? GearGlyph)?.spin(open = false)
        unwatchDevices()
        runCatching { windowManager.removeView(f) }
    }

    /** Ещё один тап по кружку — веер живёт дальше. */
    private fun keepFan() {
        val f = fan ?: return
        f.removeCallbacks(fanDismiss)
        f.postDelayed(fanDismiss, FAN_HOLD_MS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeKnob(knob: Knob, size: Int): FrameLayout {
        val bubble = FrameLayout(service)
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(GREY)
        }
        bubble.elevation = dp(3).toFloat()
        val inset = size / 5
        val glyph: View = when (knob) {
            Knob.MIC -> ImageView(service).apply {
                imageTintList = ColorStateList.valueOf(PAPER)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(inset, inset, inset, inset)
            }
            Knob.HIDE -> ImageView(service).apply {
                setImageResource(R.drawable.ic_fab_glyph)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            Knob.MODEL -> KnobGlyph(service, KnobGlyph.Kind.SPARK)
            Knob.UPDATE -> KnobGlyph(service, KnobGlyph.Kind.DOWNLOAD)
        }
        glyph.tag = "glyph"
        bubble.addView(
            glyph,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        // Сжатие под пальцем — как у больших кнопок; сам тап — клик.
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> BubbleMotion.press(v)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> BubbleMotion.release(v)
            }
            false
        }
        bubble.setOnClickListener {
            onTouched?.invoke()
            keepFan()
            when (knob) {
                Knob.MODEL -> flipModel()
                Knob.MIC -> flipMic()
                Knob.UPDATE -> checkUpdates()
                Knob.HIDE -> {
                    hideNote()
                    hideFan()
                    onHideAll?.invoke()
                }
            }
        }
        return bubble
    }

    private fun knobAlpha(knob: Knob): Float =
        if (knob == Knob.MIC && headset && !headsetPresent) idleAlpha * ABSENT_FACTOR else idleAlpha

    /** Цвет и глиф кружка по состоянию; кружок не показан — ничего не делает. */
    private fun paintKnob(knob: Knob) {
        val bubble = knobs[knob] ?: return
        val on = when (knob) {
            Knob.MODEL -> strongModel
            Knob.MIC -> headset
            Knob.UPDATE -> checkingUpdates
            Knob.HIDE -> false
        }
        val color = when {
            knob == Knob.HIDE -> FloatingButtonController.ACCENT
            on -> ON
            else -> GREY
        }
        (bubble.background as? GradientDrawable)?.setColor(color)
        if (knob == Knob.MIC) {
            (bubble.findViewWithTag<View>("glyph") as? ImageView)
                ?.setImageResource(if (headset) R.drawable.ic_mic_headset else R.drawable.ic_mic_phone)
        }
        // Пока кружок выскакивает, его альфу ведёт анимация — не перебивать.
        if (bubble.scaleX >= 0.99f) bubble.alpha = knobAlpha(knob)
    }

    // ---- Переключатели ----

    /**
     * Модель чистки: «включено» — Опус (или что-то сильнее Сонета, если
     * владелец поставил Fable в настройках), «выключено» — Сонет. Тап меняет
     * на противоположное: с сильной — на Сонет, с Сонета — на Опус.
     */
    private fun flipModel() {
        val next = if (strongModel) Settings.MODEL_SONNET else Settings.MODEL_OPUS
        strongModel = next != Settings.MODEL_SONNET
        modelLabel = Models.label(next)
        paintKnob(Knob.MODEL)
        scope.launch { settings.setModel(ModelRoute.PRAVKA, next) }
        Haptics.start(service)
        service.app.eventLog.add("чистка: модель ${Models.label(next)} (кружок шестерёнки)")
        showNote("Чистка: ${Models.label(next)}")
    }

    private fun flipMic() {
        val next = !headset
        // Кружок отвечает сразу, не дожидаясь DataStore; коллектор подтвердит.
        headset = next
        refreshPresence()
        paintKnob(Knob.MIC)
        scope.launch { settings.setPhoneMicOnly(!next) }
        Haptics.start(service)
        val absent = next && !headsetPresent
        service.app.eventLog.add(
            if (next) "микрофон: Bluetooth-гарнитура" + (if (absent) " (сейчас не подключена)" else "")
            else "микрофон: телефон",
        )
        showNote(
            if (next) "Микрофон: гарнитура" + (if (absent) " — сейчас не подключена, слушает телефон" else "")
            else "Микрофон: телефон",
        )
    }

    /**
     * Проверка обновлений с кружка: та же дорога, что у кнопки в настройках
     * (`Updates.check(force)`), плюс сразу докачать и поставить, если есть
     * что. Причина неудачи доходит целой (правило: ошибку не затирать общей
     * фразой). Пока идёт — кружок синий и глиф крутится.
     */
    private fun checkUpdates() {
        if (checkingUpdates) {
            showNote("Уже проверяю…")
            return
        }
        val updates = service.app.updates
        checkingUpdates = true
        paintKnob(Knob.UPDATE)
        (knobs[Knob.UPDATE]?.findViewWithTag<View>("glyph") as? KnobGlyph)?.spin(true)
        showNote("Проверяю обновления…", holdMs = 20_000)
        scope.launch {
            try {
                val build = updates.check(force = true)
                val error = updates.state.value.error
                when {
                    build == null -> showNote("Не проверилось: ${error.ifBlank { "нет ответа" }}", bad = true)
                    !updates.isNewer(build) -> {
                        val foreign = build.versionCode > BuildConfig.VERSION_CODE
                        showNote(
                            if (foreign) "В ветке чужая сборка (${build.branch}) — у тебя ${BuildConfig.VERSION_NAME}, обновлений нет"
                            else "Обновлений нет — у тебя ${BuildConfig.VERSION_NAME}",
                        )
                    }
                    updates.readyNow() != null -> {
                        showNote("Есть ${build.versionName} — ставлю")
                        install()
                    }
                    else -> {
                        showNote("Есть ${build.versionName} — качаю…", holdMs = 60_000)
                        val file = updates.download(build)
                        if (file != null) {
                            showNote("Скачалась ${build.versionName} — ставлю")
                            install()
                        } else {
                            showNote(
                                "Не скачалось: ${updates.state.value.error.ifBlank { "файл не прошёл проверку" }}",
                                bad = true,
                            )
                        }
                    }
                }
            } finally {
                checkingUpdates = false
                (knobs[Knob.UPDATE]?.findViewWithTag<View>("glyph") as? KnobGlyph)?.spin(false)
                paintKnob(Knob.UPDATE)
            }
        }
    }

    /** Установщик — через тот же трамплин, что у уведомления: служба не Activity. */
    private fun install() {
        runCatching {
            service.startActivity(
                Intent(service, UpdateActivity::class.java)
                    .putExtra(UpdateActivity.EXTRA_WHAT, UpdateActivity.W_INSTALL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { showNote("Установщик не открылся: ${it.message}", bad = true) }
    }

    private fun refreshPresence() {
        headsetPresent = runCatching { MicRouting.headsetMic(audioManager) != null }.getOrDefault(false)
    }

    /** Гарнитура подключилась или отвалилась, пока веер открыт — кружок бледнеет или оживает. */
    private fun watchDevices() {
        if (deviceCallback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshPresence()
                paintKnob(Knob.MIC)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshPresence()
                paintKnob(Knob.MIC)
            }
        }
        deviceCallback = cb
        runCatching { audioManager.registerAudioDeviceCallback(cb, main) }
    }

    private fun unwatchDevices() {
        deviceCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
    }

    // ---- Записка: что сделал тап ----

    private fun showNote(text: String, bad: Boolean = false, holdMs: Long = NOTE_HOLD_MS) {
        hideNote()
        val hp = headParams ?: return
        val (w, h) = screen()
        val v = TextView(service).apply {
            this.text = text
            setTextColor(PAPER)
            textSize = 13f
            maxLines = 3
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (bad) NOTE_BAD else NOTE_INK)
            }
            alpha = NOTE_ALPHA
            setPadding(dp(14), dp(8), dp(14), dp(8))
            maxWidth = (w - dp(24)).coerceAtLeast(dp(120))
            setOnClickListener { hideNote() }
        }
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val noteW = v.measuredWidth
        val noteH = v.measuredHeight
        val size = headSizePx()
        // Под веером (или под головой, если веера нет), к той же стороне.
        val anchorX = fanParams?.x ?: hp.x
        val anchorW = fanParams?.width ?: size
        val centre = hp.x + size / 2
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (centre < w / 2) anchorX else anchorX + anchorW - noteW)
                .coerceIn(0, (w - noteW).coerceAtLeast(0))
            y = (hp.y + size + dp(8)).coerceIn(0, (h - noteH).coerceAtLeast(0))
        }
        note = v
        runCatching { windowManager.addView(v, p) }
        v.postDelayed(noteDismiss, holdMs)
    }

    private fun hideNote() {
        val n = note ?: return
        n.removeCallbacks(noteDismiss)
        note = null
        runCatching { windowManager.removeView(n) }
    }

    // ---- Служебное ----

    private fun screen(): Pair<Int, Int> {
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        return (bounds?.width() ?: 0) to (bounds?.height() ?: 0)
    }

    /** Перепись окон для журнала складывания: голова, веер, записка. */
    fun windowCount(): Int =
        (if (head != null) 1 else 0) + (if (fan != null) 1 else 0) + (if (note != null) 1 else 0)

    /**
     * Снять всё из WindowManager — на складывание Fold и в onDestroy. Позиция
     * головы остаётся в памяти, следующий show() поставит её туда же.
     */
    fun hideAll() {
        hideNote()
        hideFan()
        hideHead()
    }

    fun destroy() {
        hideAll()
        unwatchDevices()
    }

    /**
     * Шестерёнка: кольцо и восемь зубцов, нарисованные от центра. Не текст и
     * не готовая иконка: глиф, нарисованный от центра вида, центрируется по
     * построению — тот же урок, что у галочки ручки («какая-то галочка не
     * посередине»). Открытый веер поворачивает её на четверть: видно, что
     * она «взведена».
     */
    private class GearGlyph(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PAPER
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        fun spin(open: Boolean) {
            animate().cancel()
            animate().rotation(if (open) 90f else 0f).setDuration(260)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f
            paint.strokeWidth = w * 0.085f
            canvas.drawCircle(cx, cy, w * 0.19f, paint)
            val inner = w * 0.30f
            val outer = w * 0.40f
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val dx = Math.cos(a).toFloat()
                val dy = Math.sin(a).toFloat()
                canvas.drawLine(cx + dx * inner, cy + dy * inner, cx + dx * outer, cy + dy * outer, paint)
            }
        }
    }

    /** Глифы кружков веера, нарисованные от центра: искра (модель) и стрелка в лоток (обновление). */
    private class KnobGlyph(context: android.content.Context, private val kind: Kind) : View(context) {
        enum class Kind { SPARK, DOWNLOAD }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PAPER
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        private var spinner: android.animation.ObjectAnimator? = null

        /** Проверка идёт — стрелка крутится, пока не скажут стоп. */
        fun spin(on: Boolean) {
            spinner?.cancel()
            spinner = null
            if (on) {
                spinner = android.animation.ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
                    duration = 1100
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            } else {
                animate().rotation(0f).setDuration(200).start()
            }
        }

        override fun onDetachedFromWindow() {
            spinner?.cancel()
            spinner = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f
            when (kind) {
                Kind.SPARK -> {
                    // Четырёхлучевая искра: длинные лучи и короткие между ними.
                    paint.style = Paint.Style.FILL
                    val r = w * 0.30f
                    val r2 = r * 0.32f
                    path.reset()
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0 - 90.0)
                        val rr = if (i % 2 == 0) r else r2
                        val x = cx + Math.cos(a).toFloat() * rr
                        val y = cy + Math.sin(a).toFloat() * rr
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                Kind.DOWNLOAD -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = w * 0.085f
                    val top = cy - h * 0.26f
                    val tip = cy + h * 0.10f
                    canvas.drawLine(cx, top, cx, tip, paint)
                    val head = w * 0.14f
                    canvas.drawLine(cx - head, tip - head, cx, tip, paint)
                    canvas.drawLine(cx + head, tip - head, cx, tip, paint)
                    val tray = cy + h * 0.26f
                    val half = w * 0.26f
                    canvas.drawLine(cx - half, tray, cx + half, tray, paint)
                    canvas.drawLine(cx - half, tray - h * 0.10f, cx - half, tray, paint)
                    canvas.drawLine(cx + half, tray - h * 0.10f, cx + half, tray, paint)
                }
            }
        }
    }
}
