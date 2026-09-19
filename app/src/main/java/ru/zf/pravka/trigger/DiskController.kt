package ru.zf.pravka.trigger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DiskGeometry
import ru.zf.pravka.core.DiskPhysics
import ru.zf.pravka.core.Spring
import ru.zf.pravka.core.StackGeometry
import ru.zf.pravka.data.Settings
import ru.zf.pravka.ui.Haptics

/**
 * Диск плавающих кнопок: шестерёнка в центре, кнопки по кольцу, под ними
 * стекло-тарелка. Владелец (19.09.2026): «крутящийся диск, который наполовину
 * виден из края экрана; четыре кнопки и посередине кнопка настроек; нажимаю
 * на неё долго — за неё двигаю диск; у края он виден наполовину, и я могу
 * его крутить».
 *
 * Диск НЕ переписывает четыре контроллера кнопок в одно окно. Каждая кнопка
 * остаётся своим окном со своими тикером, плашкой, «отменой» и значками —
 * всё это привязано к позиции окна кнопки и продолжает работать. Диск лишь
 * ставит окна на кольцо вместо столбика: геометрия — `core/DiskGeometry.kt`,
 * под тестами. Так же откатывается: тумблер «Диск вместо стопки» в Общих
 * возвращает стопку без пересборки.
 *
 * Жесты. Тап и долгое нажатие по кнопке — как были. Палец повёл кнопку —
 * крутится весь диск (`onRingDrag`), кнопка сама не двигается; на отпускании
 * диск щёлкает по ближайшей четверти, бросок доворачивает дальше. Шестерёнка:
 * тап — веер, долгое нажатие и потом тянуть — диск переезжает; отпустили у
 * края — прижался к нему так, что шестерёнка целиком на экране, а дальние
 * кнопки — за краем. Полминуты без касаний — диск возвращается домой
 * (`goHome`): «П» и «З» внутрь; это замена складыванию стопки.
 *
 * Окна. Тарелка — одно окно, FLAG_NOT_TOUCHABLE: касания идут сквозь неё в
 * приложение под диском, крутят диск только кнопки. Ей же и кнопкам —
 * FLAG_LAYOUT_NO_LIMITS, иначе WindowManager прижал бы уехавшее за край окно
 * обратно к краю. Кнопка, ушедшая за край ЦЕЛИКОМ, снимается из
 * WindowManager (`RingButton.setOffscreen`): докованный диск держит два окна
 * кнопок вместо четырёх — складывание Fold платит за каждое окно, и
 * невидимое тоже. Тарелка снимается на складывание, как всё остальное.
 *
 * Диск — одно тело, не бусы: одна пружина на поворот, две на центр
 * (`core/DiskPhysics.kt`), кадры — Choreographer с настоящим dt. Пружина
 * поворота стартует со скоростью броска (`Spring.reset(position, velocity)`).
 */
class DiskController(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val settings: Settings,
) {

    private companion object {
        /** Тарелка — тёмное стекло: чернила, не цвет режима; диск не кнопка и не спорит с ними. */
        private val GLASS = 0xFF3A342B.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
        /** Доля прозрачности кнопок, с которой стоит тарелка: заметно, но под кнопками. */
        private const val PLATE_ALPHA_FACTOR = 0.45f
        /** Ободок тарелки — бумага, в эту доли от её же прозрачности. */
        private const val RIM_ALPHA = 0.55f
        /** Окно кнопки за краем с таким запасом — снимается. */
        private const val OFFSCREEN_MARGIN_DP = 2
        /** Кнопка не там, где ей быть, дальше этого — расставить заново. */
        private const val DRIFT_PX = 1
        /** Потолок начальной скорости пружины поворота, градусов в секунду. */
        private const val MAX_LAUNCH = 1500f
        /** Палец стоял дольше этого перед отпусканием — бросок не считается. */
        private const val FLICK_PAUSE_MS = 90L
    }

    private class Slot(val button: RingButton, val enabled: () -> Boolean)

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private val slots = mutableListOf<Slot>()

    /** Шестерёнка (или точка) в центре диска — её ставит диск. */
    var head: StackSettingsController? = null
    /** Любое касание диска — стопке отсчёт простоя заново. */
    var onTouched: (() -> Unit)? = null
    /** Текущий размер кнопки — настройка владельца, читается на каждой расстановке. */
    var buttonSize: () -> Int = { dp(Settings.FAB_SIZE_DEFAULT) }

    // Состояние: центр в координатах окон и поворот в градусах.
    private var cx = 0f
    private var cy = 0f
    private var rotation = 0f
    private var facing: Float? = null

    private var shown = false
    /** Позиция прочитана с диска — до этого расставлять нечего. */
    private var placed = false
    private var folded = false
    private var allHidden = false
    private var idleAlpha = Settings.FAB_ALPHA_DEFAULT

    // Тарелка.
    private var plate: PlateView? = null
    private var plateParams: WindowManager.LayoutParams? = null

    // Движение: одно тело.
    private val turn = DiskPhysics.turn(0f)
    private val slideX = DiskPhysics.slide(0f)
    private val slideY = DiskPhysics.slide(0f)
    private var turnTarget = 0f
    private var slideTargetX = 0f
    private var slideTargetY = 0f
    private var animating = false
    private var lastFrameNs = 0L

    // Палец крутит за кнопку.
    private var turning: RingButton? = null
    private var fingerOffX = 0f
    private var fingerOffY = 0f
    private var lastAngle = 0f
    private var lastAngleAt = 0L
    private var turnDelta = 0f
    private var turnStart = 0f
    private var turnVelocity = 0f
    private var turned = false
    private var lastDetent = 0

    // Палец везёт за шестерёнку.
    private var sliding = false
    private var slideFacing = 0f

    init {
        scope.launch {
            settings.fabAlphaFlow.collect {
                idleAlpha = it
                plate?.alpha = plateAlpha()
            }
        }
    }

    fun add(button: RingButton, enabled: () -> Boolean) {
        slots += Slot(button, enabled)
    }

    // ---- Размеры и рабочая область ----

    private class Dims(val button: Int, val gear: Int, val gap: Int) {
        val ring = DiskGeometry.ringRadius(button, gear, gap)
        val plate = DiskGeometry.plateRadius(button, gear, gap)
        val inset = DiskGeometry.dockInset(gear, gap)
    }

    private fun dims(): Dims {
        val b = buttonSize().coerceAtLeast(1)
        return Dims(b, StackGeometry.gearSize(b), dp(8))
    }

    /**
     * Рабочая область: экран без системных полос. Окна оверлеев считают x/y
     * от неё (они вписываются в системные полосы по умолчанию), поэтому и
     * докование, и «за краем» считаются в ней же.
     */
    private fun frame(): Pair<Int, Int> = cachedFrame ?: measureFrame().also { cachedFrame = it }

    // currentWindowMetrics — binder-вызов в WindowManagerService, а layout()
    // идёт кадр в кадр под пальцем. Размер меняется только со сменой
    // конфигурации — там кэш и сбрасывается (load()).
    private var cachedFrame: Pair<Int, Int>? = null

    private fun measureFrame(): Pair<Int, Int> {
        val metrics = runCatching { windowManager.currentWindowMetrics }.getOrNull() ?: return 0 to 0
        val bounds = metrics.bounds
        var w = bounds.width()
        var h = bounds.height()
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val ins = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                w -= ins.left + ins.right
                h -= ins.top + ins.bottom
            }
        }
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    /** Ключ экрана — тот же, что у позиций кнопок стопки: полные размеры дисплея. */
    private fun frameKey(): String {
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull() ?: return "0x0"
        return "${bounds.width()}x${bounds.height()}"
    }

    private fun liveSlots(): List<Slot> = slots.filter { it.enabled() }

    private fun step(): Float = DiskGeometry.spread(liveSlots().size)

    // ---- Показать и убрать ----

    /** Диск включён: прочитать позицию и расставить. Кнопки — под тарелку, тарелка — вниз. */
    fun show() {
        if (shown) return
        shown = true
        load(reattach = true)
    }

    private fun load(reattach: Boolean) {
        cachedFrame = null
        val key = frameKey()
        scope.launch {
            val (fx, fy, r) = settings.diskPlace(key)
            if (!shown) return@launch
            val d = dims()
            val (w, h) = frame()
            val (dx, dy) = DiskGeometry.dock(fx * w, fy * h, w, h, d.plate, d.inset)
            cx = dx
            cy = dy
            rotation = DiskGeometry.norm(r)
            facing = null
            placed = true
            stopMotion()
            showPlate()
            layout()
            // Тарелка добавилась последней — то есть поверх кнопок и
            // шестерёнки. Порядок окон одного типа — порядок добавления, так
            // что они перевешиваются заново и оказываются над стеклом.
            if (reattach) {
                slots.forEach { it.button.reattach() }
                head?.reattach()
            }
        }
    }

    /** Диск выключен: стекло снимается, кнопки остаются — стопку расставит служба. */
    fun hide() {
        shown = false
        placed = false
        stopMotion()
        turning = null
        sliding = false
        hidePlate()
        slots.forEach { it.button.setOffscreen(false) }
    }

    /** Складывание Fold: тарелка снимается на переход и возвращается после. */
    fun setFolded(value: Boolean) {
        folded = value
        if (value) {
            stopMotion()
            hidePlate()
        } else if (shown && placed) {
            showPlate()
            layout()
        }
    }

    /** Всё убрано в точку: тарелки нет, точка стоит в центре диска. */
    fun setAllHidden(value: Boolean) {
        allHidden = value
        if (value) {
            stopMotion()
            hidePlate()
            placeHead()
        } else if (shown && placed && !folded) {
            showPlate()
            layout()
            slots.forEach { it.button.reattach() }
        }
    }

    /** Экран сложили или повернули: позиция для нового размера — с диска. */
    fun onConfigurationChanged() {
        if (!shown) return
        load(reattach = false)
    }

    // ---- Расстановка ----

    /** Расставить всё по текущим центру и повороту, без пружин. */
    fun layout() {
        if (!shown || !placed || folded) return
        val d = dims()
        val (w, h) = frame()
        val f = DiskGeometry.facing(cx, w)
        val previous = facing
        if (previous != null && previous != f && sliding) {
            // Диск переехал через середину экрана и смотрит теперь в другую
            // сторону. Абсолютные углы кнопок не меняются — иначе они
            // перескочили бы на другой бок посреди жеста; домой диск
            // довернётся после броска (см. onHeadDragged).
            rotation += previous - f
        }
        facing = f
        if (!allHidden) {
            val live = liveSlots()
            val margin = dp(OFFSCREEN_MARGIN_DP)
            live.forEachIndexed { i, slot ->
                val angle = DiskGeometry.slotAngle(i, live.size, f, rotation)
                val (x, y) = DiskGeometry.slotOrigin(cx, cy, d.ring, angle, d.button)
                // Сначала место, потом окно: снятое окно ставится на место в
                // параметрах и вешается уже там, где надо.
                slot.button.followTo(x, y, settle = false, link = 1, snap = true)
                slot.button.setOffscreen(!DiskGeometry.onScreen(x, y, d.button, w, h, margin))
            }
        }
        placeHead()
        placePlate(d)
    }

    /** Где стоять кнопке [button] сейчас — для «П», которая появляется по show(). */
    fun slotOrigin(button: RingButton): Pair<Int, Int>? {
        if (!shown || !placed) return null
        val live = liveSlots()
        val index = live.indexOfFirst { it.button === button }
        if (index < 0) return null
        val d = dims()
        val (w, _) = frame()
        val angle = DiskGeometry.slotAngle(index, live.size, DiskGeometry.facing(cx, w), rotation)
        return DiskGeometry.slotOrigin(cx, cy, d.ring, angle, d.button)
    }

    /**
     * Самолечение по тику службы: кнопка не там, где ей быть (включили
     * тумблером, поменяли размер, проиграла гонку с восстановлением своей
     * старой позиции) — расставить заново. Пока палец на диске или идёт
     * пружина, не лезть: они расставляют сами, кадр в кадр.
     */
    fun refresh() {
        if (!shown || !placed || folded) return
        if (animating || turning != null || sliding) return
        if (allHidden) {
            placeHead()
            return
        }
        val d = dims()
        val (w, _) = frame()
        val f = DiskGeometry.facing(cx, w)
        val live = liveSlots()
        val drifted = live.withIndex().any { (i, slot) ->
            val angle = DiskGeometry.slotAngle(i, live.size, f, rotation)
            val (x, y) = DiskGeometry.slotOrigin(cx, cy, d.ring, angle, d.button)
            val (px, py) = slot.button.currentPosition() ?: return@any true
            abs(px - x) > DRIFT_PX || abs(py - y) > DRIFT_PX
        }
        if (drifted || f != facing) layout() else {
            placeHead()
            placePlate(d)
        }
    }

    private fun placeHead() {
        val h = head ?: return
        val d = dims()
        h.moveToCentre(cx.roundToInt(), cy.roundToInt(), d.button)
        // Веер и записки шестерёнки — снаружи тарелки, не поверх кнопок.
        h.clearance = if (allHidden) 0 else (d.plate - h.headSizePx() / 2f).roundToInt().coerceAtLeast(0)
    }

    // ---- Палец крутит за кнопку ----

    fun onRingDrag(button: RingButton, rawX: Float, rawY: Float, localX: Float, localY: Float, action: Int) {
        if (!shown || !placed || folded || allHidden) return
        onTouched?.invoke()
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (sliding) return
                stopMotion()
                val (bx, by) = button.currentPosition() ?: return
                // Сырые координаты — дисплея, позиции окон — рабочей области.
                // Разница постоянна и известна в момент касания: палец в окне
                // кнопки, а кнопка — там, где мы её поставили.
                fingerOffX = bx + localX - rawX
                fingerOffY = by + localY - rawY
                turning = button
                turnStart = rotation
                turnDelta = 0f
                turnVelocity = 0f
                turned = false
                lastAngle = DiskGeometry.angleOf(cx, cy, rawX + fingerOffX, rawY + fingerOffY)
                lastAngleAt = SystemClock.uptimeMillis()
                lastDetent = detent(rotation)
            }
            MotionEvent.ACTION_MOVE -> {
                if (turning !== button) return
                val angle = DiskGeometry.angleOf(cx, cy, rawX + fingerOffX, rawY + fingerOffY)
                val delta = DiskGeometry.delta(lastAngle, angle)
                val now = SystemClock.uptimeMillis()
                val dt = (now - lastAngleAt) / 1000f
                if (dt > 0f) {
                    // Скорость — сглаженная: события приходят неровно, а
                    // бросок должен считаться по последним миллиметрам, не по
                    // одному дёрганому событию.
                    turnVelocity = turnVelocity * 0.6f + (delta / dt) * 0.4f
                }
                lastAngle = angle
                lastAngleAt = now
                turnDelta += delta
                turned = true
                rotation = turnStart + turnDelta
                layout()
                val det = detent(rotation)
                if (det != lastDetent) {
                    lastDetent = det
                    Haptics.tick(service)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (turning !== button) return
                turning = null
                // Палец замер и только потом отпустил — это не бросок:
                // скорость последних миллиметров уже ничего не значит.
                val paused = SystemClock.uptimeMillis() - lastAngleAt > FLICK_PAUSE_MS
                val velocity = if (turned && !paused) turnVelocity else 0f
                val target = DiskGeometry.snap(rotation, step(), velocity)
                // Центр — заодно к краю: палец мог схватить кнопку посреди
                // докования, и диск остался бы висеть на полпути.
                val (dx, dy) = docked()
                animateTo(target, dx, dy, launch = velocity.coerceIn(-MAX_LAUNCH, MAX_LAUNCH))
            }
        }
    }

    private fun detent(rotation: Float): Int = (rotation / step()).roundToInt()

    /** Куда докуется центр из текущего места: у края — к краю, посреди экрана — где стоит. */
    private fun docked(): Pair<Float, Float> {
        val d = dims()
        val (w, h) = frame()
        return DiskGeometry.dock(cx, cy, w, h, d.plate, d.inset)
    }

    // ---- Палец везёт за шестерёнку ----

    /**
     * Голова едет: ([headX], [headY]) — левый верхний угол её окна, центр
     * диска — её центр. Отпустили — докование и, если диск переехал на другую
     * половину экрана, доворот домой: лицом внутрь.
     */
    fun onHeadDragged(headX: Int, headY: Int, dropped: Boolean) {
        if (!shown || !placed || folded) return
        onTouched?.invoke()
        val size = head?.headSizePx() ?: 0
        val (w, h) = frame()
        if (!sliding) {
            sliding = true
            turning = null
            stopMotion()
            slideFacing = DiskGeometry.facing(cx, w)
        }
        cx = headX + size / 2f
        cy = headY + size / 2f
        if (allHidden) placeHead() else layout()
        if (!dropped) return
        sliding = false
        val (dx, dy) = docked()
        val turnedAround = DiskGeometry.facing(dx, w) != slideFacing
        val target = if (turnedAround) DiskGeometry.home(rotation) else DiskGeometry.snap(rotation, step())
        animateTo(target, dx, dy)
    }

    /**
     * Домой: «П» и «З» внутрь, коротким путём. Зовётся тиком службы по
     * простою (замена складыванию стопки); уже дома или под пальцем — ничего.
     */
    fun goHome() {
        if (!shown || !placed || folded || allHidden) return
        if (animating || turning != null || sliding) return
        val target = DiskGeometry.home(rotation)
        val (dx, dy) = docked()
        if (abs(target - rotation) < 0.5f && abs(dx - cx) < 0.5f && abs(dy - cy) < 0.5f) return
        animateTo(target, dx, dy)
    }

    // ---- Пружины ----

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            val dt = if (lastFrameNs == 0L) 1f / 60f else (frameTimeNanos - lastFrameNs) / 1_000_000_000f
            lastFrameNs = frameTimeNanos
            val doneTurn = turn.step(turnTarget, dt)
            val doneX = slideX.step(slideTargetX, dt)
            val doneY = slideY.step(slideTargetY, dt)
            rotation = turn.position
            cx = slideX.position
            cy = slideY.position
            layout()
            if (doneTurn && doneX && doneY) {
                animating = false
                rotation = DiskGeometry.norm(rotation)
                persist()
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun animateTo(rotTarget: Float, toX: Float, toY: Float, launch: Float = 0f) {
        val still = abs(rotTarget - rotation) < 0.5f && abs(toX - cx) < 0.5f && abs(toY - cy) < 0.5f
        if (still && abs(launch) < 1f) {
            rotation = DiskGeometry.norm(rotation)
            persist()
            return
        }
        turnTarget = rotTarget
        slideTargetX = toX
        slideTargetY = toY
        turn.reset(rotation, launch)
        slideX.reset(cx)
        slideY.reset(cy)
        if (!animating) {
            animating = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun stopMotion() {
        if (!animating) return
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun persist() {
        val (w, h) = frame()
        if (w <= 1 || h <= 1) return
        val key = frameKey()
        val fx = cx / w
        val fy = cy / h
        val r = rotation
        scope.launch { settings.setDiskPlace(key, fx, fy, r) }
    }

    // ---- Тарелка ----

    private fun plateAlpha(): Float = idleAlpha * PLATE_ALPHA_FACTOR

    private fun showPlate() {
        if (plate != null || allHidden || folded) return
        val d = dims()
        val size = (d.plate * 2).roundToInt()
        val v = PlateView(service, dp(1) + dp(1) / 2f).apply { alpha = plateAlpha() }
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (cx - d.plate).roundToInt()
            y = (cy - d.plate).roundToInt()
        }
        plate = v
        plateParams = p
        runCatching { windowManager.addView(v, p) }
    }

    private fun placePlate(d: Dims) {
        val v = plate ?: return
        val p = plateParams ?: return
        val size = (d.plate * 2).roundToInt()
        p.width = size
        p.height = size
        p.x = (cx - d.plate).roundToInt()
        p.y = (cy - d.plate).roundToInt()
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    /** Именно removeView: скрытое окно стоит столько же, сколько видимое. */
    private fun hidePlate() {
        plate?.let { runCatching { windowManager.removeView(it) } }
        plate = null
        plateParams = null
    }

    /** Перепись окон для журнала складывания. */
    fun windowCount(): Int = if (plate != null) 1 else 0

    fun destroy() {
        stopMotion()
        hidePlate()
        shown = false
        placed = false
    }

    /**
     * Стекло диска: круг чернил с тонким бумажным ободком. Рисуется от
     * центра вида, как все глифы стопки — центрируется по построению.
     */
    private class PlateView(context: Context, private val rim: Float) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GLASS
            style = Paint.Style.FILL
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PAPER
            style = Paint.Style.STROKE
            strokeWidth = rim
            alpha = (255 * RIM_ALPHA).toInt()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = minOf(w, h) / 2f
            canvas.drawCircle(w / 2f, h / 2f, r - rim, fill)
            canvas.drawCircle(w / 2f, h / 2f, r - rim, edge)
        }
    }
}
