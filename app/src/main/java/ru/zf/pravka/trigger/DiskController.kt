package ru.zf.pravka.trigger

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DiskGeometry
import ru.zf.pravka.core.DiskLook
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
        /**
         * Тень под стеклом: диск — предмет над приложением, а не пятно на нём.
         * Владелец (19.09, поздно) о шкале-метках: «засечки выглядят плохо,
         * неравномерные и залезают на сами круги… а может тень сделать
         * круга?» Метки сняты, тень мягкая: выходит за край тарелки на столько,
         * чуть сдвинута вниз, как от света сверху, и вдвое плотнее стекла (с
         * потолком) — на белом читается ореолом, на тёмном не мешает.
         * Плотности и цвета обеих шкурок стекла — `core/DiskLook.kt`.
         */
        private const val SHADOW_DP = 10
        private const val SHADOW_DROP_DP = 2
        /** Толщина фаски по кромке стекла — доля радиуса тарелки. */
        private const val RIM_WIDTH_FACTOR = 0.012f
        /**
         * Тень кнопки на стекле: насколько она шире самой кнопки. Полтора
         * радиуса — мягкое пятно, из-под кнопки видно только его край; меньше
         * читалось бы как обводка, больше — как грязь на стекле.
         */
        private const val SOCKET_SPREAD = 1.5f
        /** Угол кнопки сдвинулся меньше этого — стекло не перерисовываем. */
        private const val SOCKET_STEP_DEG = 0.25f
        /** Окно кнопки за краем с таким запасом — снимается. */
        private const val OFFSCREEN_MARGIN_DP = 2
        /** Кнопка не там, где ей быть, дальше этого — расставить заново. */
        private const val DRIFT_PX = 1
        /** Потолок начальной скорости пружины поворота, градусов в секунду. */
        private const val MAX_LAUNCH = 1500f
        /** Палец стоял дольше этого перед отпусканием — бросок не считается. */
        private const val FLICK_PAUSE_MS = 90L
        /** Второй тап по стеклу не позже этого — двойной: веер быстрых настроек. */
        private const val DOUBLE_TAP_MS = 320L
        /** Долгое нажатие на стекло — выдвинуть диск целиком (или убрать обратно). Как у кнопок. */
        private const val LONG_PRESS_MS = 450L
        /**
         * Палец, о котором столько не докладывали, — призрак: окно пропало
         * из-под него без отпускания (уехало за край, сложился экран), и
         * иначе следующее одиночное касание считалось бы вторым пальцем.
         */
        private const val FINGER_STALE_MS = 6_000L
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
    /** Какое стекло у тарелки — бумага или чернила; тумблер в Общих. */
    private var lightGlass = true
    // Ручки вида диска из настроек (владелец: «и нужно всё это в настройки»).
    // Размеры — числа, плотности — null, пока владелец не двинул ползунок:
    // до тех пор их считает `DiskLook`, следя за прозрачностью кнопок и стеклом.
    private var gapDp = Settings.DISK_GAP_DEFAULT
    private var gearPct = StackGeometry.GEAR_PCT_DEFAULT
    private var plateOverride: Float? = null
    private var socketOverride: Float? = null

    // Тарелка.
    private var plate: PlateView? = null
    private var plateParams: WindowManager.LayoutParams? = null
    /** Углы кнопок для теней на стекле — буфер, чтобы не сорить кадр за кадром. */
    private var socketAngles = FloatArray(0)

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

    // Палец везёт за шестерёнку или за стекло.
    private var sliding = false
    private var slideFacing = 0f

    // Два пальца — щипок: диск едет за их серединой, где бы ни взяли.
    private val fingers = LinkedHashMap<Any, FloatArray>()
    private var pinch = false
    private var pinchMidX = 0f
    private var pinchMidY = 0f
    private var pinchCx = 0f
    private var pinchCy = 0f

    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f

    init {
        scope.launch {
            settings.fabAlphaFlow.collect {
                idleAlpha = it
                repaintPlate()
            }
        }
        // Светлее или темнее — на живом стекле, без пересборки диска: тумблер
        // смотрят, переключая туда-сюда на том фоне, где диск терялся.
        scope.launch {
            settings.diskLightFlow.collect {
                lightGlass = it
                repaintPlate()
                // Шестерёнка на диске без подложки — её цвет тоже от стекла.
                head?.glassLight = it
            }
        }
        // Ползунки вида: размеры двигают геометрию (расставить заново),
        // плотности — только краски (перерисовать стекло).
        scope.launch {
            settings.diskGapFlow.collect {
                // Первое значение потока обычно равно заводскому — пересобирать
                // стекло на старте не за что, а пересборка снимает и вешает окна.
                if (gapDp == it) return@collect
                gapDp = it
                relayout()
            }
        }
        scope.launch {
            settings.diskGearFlow.collect {
                if (gearPct == it) return@collect
                gearPct = it
                relayout()
            }
        }
        scope.launch {
            settings.diskPlateAlphaFlow.collect {
                plateOverride = it
                repaintPlate()
            }
        }
        scope.launch {
            settings.diskSocketAlphaFlow.collect {
                socketOverride = it
                repaintPlate()
            }
        }
    }

    /** Краски стекла поменялись — перерисовать, не трогая геометрию. */
    private fun repaintPlate() {
        plate?.setLook(plateAlpha(), lightGlass, socketOverride)
    }

    /**
     * Размеры поменялись — расставить заново. Окно тарелки при этом меняет
     * размер, а его задают только при создании: проще снять и повесить, чем
     * плодить второй путь для редкого случая (ползунок двигают руками).
     */
    private fun relayout() {
        if (!shown || !placed || folded || allHidden) return
        hidePlate()
        showPlate()
        layout()
    }

    fun add(button: RingButton, enabled: () -> Boolean) {
        slots += Slot(button, enabled)
    }

    // ---- Размеры и рабочая область ----

    private class Dims(val button: Int, val gear: Int, val gap: Int, val shadow: Int) {
        val ring = DiskGeometry.ringRadius(button, gear, gap)
        val plate = DiskGeometry.plateRadius(button, gear, gap)
        val inset = DiskGeometry.DOCK_INSET
        /** Окно стекла — тарелка плюс тень по кругу. */
        val window = ((plate + shadow) * 2).roundToInt()
    }

    private fun dims(): Dims {
        val b = buttonSize().coerceAtLeast(1)
        return Dims(b, StackGeometry.gearSize(b, gearPct), dp(gapDp), dp(SHADOW_DP))
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
        load()
    }

    private fun load() {
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
        }
    }

    /** Диск выключен: стекло снимается, кнопки остаются — стопку расставит служба. */
    fun hide() {
        shown = false
        placed = false
        stopMotion()
        turning = null
        sliding = false
        pinch = false
        fingers.clear()
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
        }
    }

    /** Экран сложили или повернули: позиция для нового размера — с диска. */
    fun onConfigurationChanged() {
        if (!shown) return
        load()
    }

    // ---- Расстановка ----

    /** Расставить всё по текущим центру и повороту, без пружин. */
    fun layout() {
        if (!shown || !placed || folded) return
        val d = dims()
        val (w, h) = frame()
        // Пока диск везут, лицо не меняется: раскладки у левого и правого
        // краёв зеркальны («П» всегда сверху), и перескок посреди жеста
        // выглядел бы как рассыпавшиеся кнопки. Новое лицо — на броске (slide).
        val f = if (sliding) slideFacing else DiskGeometry.facing(cx, w)
        facing = f
        if (!allHidden) {
            val live = liveSlots()
            val margin = dp(OFFSCREEN_MARGIN_DP)
            if (socketAngles.size != live.size) socketAngles = FloatArray(live.size)
            live.forEachIndexed { i, slot ->
                val angle = DiskGeometry.slotAngle(i, live.size, f, rotation)
                socketAngles[i] = angle
                val (x, y) = DiskGeometry.slotOrigin(cx, cy, d.ring, angle, d.button)
                // Сначала место, потом окно: снятое окно ставится на место в
                // параметрах и вешается уже там, где надо.
                slot.button.followTo(x, y, settle = false, link = 1, snap = true)
                slot.button.setOffscreen(!DiskGeometry.onScreen(x, y, d.button, w, h, margin))
            }
            // Тени кнопок рисует стекло: своё окно кнопки у неё ровно по
            // кружку и наружу ничего не выпустит (см. `BubbleSkin`).
            plate?.setSockets(d.ring, d.button / 2f, socketAngles)
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
        val angle = DiskGeometry.slotAngle(index, live.size, facing ?: DiskGeometry.facing(cx, w), rotation)
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
        h.glassLight = lightGlass
        h.moveToCentre(cx.roundToInt(), cy.roundToInt(), d.button)
        // Шестерёнка крутится вместе с диском (владелец: «будет классный эффект»).
        h.setTurn(rotation)
        // Веер и записки шестерёнки — снаружи тарелки, не поверх кнопок.
        h.clearance = if (allHidden) 0 else (d.plate - h.headSizePx() / 2f).roundToInt().coerceAtLeast(0)
    }

    // ---- Палец крутит за кнопку ----

    fun onRingDrag(button: RingButton, rawX: Float, rawY: Float, localX: Float, localY: Float, action: Int) {
        if (!shown || !placed || folded || allHidden) return
        onTouched?.invoke()
        // Сначала — в общий счёт пальцев: второй палец на любом окне диска
        // делает щипок, и тогда это касание диск везёт, а не крутит.
        finger(button, rawX, rawY, action)
        if (pinch) return
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (sliding) return
                stopMotion()
                downRawX = rawX
                downRawY = rawY
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
                // Порог свой: кнопка докладывает каждый сдвиг, а поворот
                // начинается, когда палец действительно поехал.
                if (!turned && abs(rawX - downRawX) <= touchSlop && abs(rawY - downRawY) <= touchSlop) return
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
     * диска — её центр.
     */
    fun onHeadDragged(headX: Int, headY: Int, dropped: Boolean) {
        if (!shown || !placed || folded || pinch) return
        val size = head?.headSizePx() ?: 0
        slide(headX + size / 2f, headY + size / 2f, dropped)
    }

    /**
     * Диск везут — за шестерёнку или за стекло: центр под пальцем. Отпустили
     * — докование и, если диск переехал на другую половину экрана, доворот
     * домой: лицом внутрь.
     */
    private fun slide(nx: Float, ny: Float, dropped: Boolean) {
        if (!shown || !placed || folded) return
        onTouched?.invoke()
        val (w, _) = frame()
        if (!sliding) {
            sliding = true
            turning = null
            stopMotion()
            slideFacing = DiskGeometry.facing(cx, w)
        }
        cx = nx
        cy = ny
        if (allHidden) placeHead() else layout()
        if (!dropped) return
        sliding = false
        val (dx, dy) = docked()
        val newFacing = DiskGeometry.facing(dx, w)
        if (newFacing != slideFacing) {
            // Переехал на другую половину экрана: раскладка зеркалится
            // (`DiskGeometry.sense`), и диск встаёт домой — «П» сверху, «З»
            // снизу, лицом внутрь. Перескок кнопок здесь, на броске, а не
            // посреди жеста.
            rotation = 0f
            facing = newFacing
        }
        animateTo(DiskGeometry.snap(rotation, step()), dx, dy)
    }

    // ---- Два пальца: диск везут, где бы ни взяли ----

    /**
     * Пальцы на диске из всех его окон: от кнопки и от шестерёнки — по
     * первому указателю окна, от стекла — все. Два пальца разом — щипок:
     * диск едет за их серединой, где бы ни взяли, а жесты самих окон (тап,
     * меню, поворот, переезд за шестерёнку) этому касанию больше не
     * принадлежат. Владелец (19.09.2026): «если я беру двумя пальцами этот
     * диск, то могу его двигать, где бы я ни прикоснулся к нему».
     *
     * Почему через диск, а не в одном окне: система раздаёт указатели по
     * окнам под ними, и второй палец на другом окне приходит туда отдельным
     * ACTION_DOWN — щипок «через окна» видит только тот, кто слушает все.
     * [key] — кто докладывает: кнопка, «head» или указатель стекла.
     */
    fun finger(key: Any, rawX: Float, rawY: Float, action: Int) {
        if (!shown || !placed) return
        val now = SystemClock.uptimeMillis()
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                fingers.values.removeAll { now - it[2] > FINGER_STALE_MS }
                fingers[key] = floatArrayOf(rawX, rawY, now.toFloat())
                if (!pinch && fingers.size >= 2) beginPinch()
            }
            MotionEvent.ACTION_MOVE -> {
                fingers[key]?.let {
                    it[0] = rawX
                    it[1] = rawY
                    it[2] = now.toFloat()
                }
                if (pinch) movePinch()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                fingers.remove(key)
                if (pinch) {
                    // Ушёл один из двух — щипок кончился; ушёл третий или
                    // один из первых при живом третьем — середина считается
                    // от оставшейся пары заново, без скачка диска.
                    if (fingers.size < 2) endPinch() else rebasePinch()
                }
            }
        }
    }

    private fun pinchMid(): Pair<Float, Float> {
        val two = fingers.values.take(2)
        return (two[0][0] + two[1][0]) / 2f to (two[0][1] + two[1][1]) / 2f
    }

    private fun beginPinch() {
        pinch = true
        onTouched?.invoke()
        stopMotion()
        turning = null
        // Жесты окон гасятся: палец, который только что крутил или ждал
        // долгого нажатия, теперь везёт диск вместе со вторым.
        slots.forEach { it.button.cancelGesture() }
        head?.cancelGesture()
        rebasePinch()
        if (!sliding) {
            sliding = true
            slideFacing = DiskGeometry.facing(cx, frame().first)
        }
        Haptics.tick(service)
    }

    /** Опорная середина пальцев и центр диска — отсюда считается сдвиг. */
    private fun rebasePinch() {
        val (mx, my) = pinchMid()
        pinchMidX = mx
        pinchMidY = my
        pinchCx = cx
        pinchCy = cy
    }

    private fun movePinch() {
        val (mx, my) = pinchMid()
        slide(pinchCx + (mx - pinchMidX), pinchCy + (my - pinchMidY), dropped = false)
    }

    /** Один из двух пальцев поднялся — щипок кончился, диск докуется; оставшийся палец уже погашен. */
    private fun endPinch() {
        pinch = false
        slide(cx, cy, dropped = true)
    }

    /**
     * Палец на стекле — диск везут. За любое свободное место тарелки: север,
     * юг, запад, восток и промежутки между кнопками (кнопки — свои окна
     * поверх стекла, им касание достаётся первым). Владелец (19.09, вечер):
     * «перетаскивать неудобно, давай добавим перетаскивание за четыре края
     * круга плюс за свободные зоны между кружками». Углы квадрата окна вне
     * круга — не диск: касание там не принимается. Одиночный тап по стеклу —
     * ничего; двойной — веер быстрых настроек (владелец: «дабл тап в любом
     * месте, свободном от кнопки, должен вызывать дополнительные настройки»),
     * тот же, что по тапу на шестерёнку.
     */
    private inner class PlateTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startCx = 0f
        private var startCy = 0f
        private var dragging = false
        private var lastTapAt = 0L
        /** В этом касании был щипок — одиночная логика стекла молчит до следующего DOWN. */
        private var pinched = false
        /** Долгое нажатие сработало: диск выдвинулся или убрался, тап не считается. */
        private var held = false
        private val hold = Runnable {
            if (pinch || pinched || dragging) return@Runnable
            held = true
            Haptics.start(service)
            togglePullOut()
        }
        private val slop = ViewConfiguration.get(service).scaledTouchSlop

        private fun key(pointerId: Int): String = "plate:$pointerId"

        /** Внутри тарелки — без тени: тень не предмет, за неё не берут. */
        private fun inside(v: View, x: Float, y: Float): Boolean {
            val half = v.width / 2f
            val r = half - dp(SHADOW_DP)
            val dx = x - half
            val dy = y - half
            return dx * dx + dy * dy <= r * r
        }

        // Сырые координаты второго указателя: getRawX(index) есть только с
        // API 29, а сдвиг между указателями в окне тот же, что на экране.
        private fun rawX(event: MotionEvent, index: Int): Float = event.rawX + (event.getX(index) - event.getX(0))
        private fun rawY(event: MotionEvent, index: Int): Float = event.rawY + (event.getY(index) - event.getY(0))

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (service.isLockedIdle()) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!inside(v, event.x, event.y)) return false
                    if (allHidden) return false
                    onTouched?.invoke()
                    downX = event.rawX
                    downY = event.rawY
                    startCx = cx
                    startCy = cy
                    dragging = false
                    pinched = false
                    held = false
                    v.postDelayed(hold, LONG_PRESS_MS)
                    finger(key(event.getPointerId(0)), event.rawX, event.rawY, MotionEvent.ACTION_DOWN)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    v.removeCallbacks(hold)
                    val i = event.actionIndex
                    if (!inside(v, event.getX(i), event.getY(i))) return true
                    finger(key(event.getPointerId(i)), rawX(event, i), rawY(event, i), MotionEvent.ACTION_POINTER_DOWN)
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        finger(key(event.getPointerId(i)), rawX(event, i), rawY(event, i), MotionEvent.ACTION_MOVE)
                    }
                    if (pinch) {
                        v.removeCallbacks(hold)
                        pinched = true
                        dragging = false
                        return true
                    }
                    if (pinched || held) return true
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        v.removeCallbacks(hold)
                    }
                    if (dragging) slide(startCx + dx, startCy + dy, dropped = false)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    finger(key(event.getPointerId(i)), rawX(event, i), rawY(event, i), MotionEvent.ACTION_POINTER_UP)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(hold)
                    for (i in 0 until event.pointerCount) {
                        finger(key(event.getPointerId(i)), rawX(event, i), rawY(event, i), MotionEvent.ACTION_UP)
                    }
                    if (pinched || held) return true
                    if (dragging) {
                        slide(cx, cy, dropped = true)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapAt < DOUBLE_TAP_MS) {
                            lastTapAt = 0L
                            head?.toggleFan()
                        } else {
                            lastTapAt = now
                        }
                    }
                }
            }
            return true
        }
    }

    /**
     * Убраться: к ближайшему краю и домой — «П» и «З» внутрь, остальное за
     * край. Зовётся тиком службы по простою при тумблере «Автоматически
     * убирать диск к краю» (владелец: «через 30 секунд диск пришёл к
     * ближайшему краю и прилепился, так что остались только засечка и
     * правка»). Уже там или под пальцем — ничего.
     */
    fun tuck() {
        if (!shown || !placed || folded || allHidden) return
        if (animating || turning != null || sliding || pinch) return
        val d = dims()
        val (w, h) = frame()
        val f = facing ?: DiskGeometry.facing(cx, w)
        val edge = if (f == 0f) 0f else w.toFloat()
        val (dx, dy) = DiskGeometry.dock(edge, cy, w, h, d.plate, d.inset)
        val target = DiskGeometry.home(rotation)
        if (abs(target - rotation) < 0.5f && abs(dx - cx) < 0.5f && abs(dy - cy) < 0.5f) return
        animateTo(target, dx, dy)
    }

    /**
     * Выдвинуть диск целиком на экран — или, если он уже целиком виден,
     * убрать к краю (поворот не трогая). Долгое нажатие на стекло; владелец
     * согласился на «выдвижение по удержанию»: докованный диск показывает две
     * кнопки, а за третьей иначе надо крутить.
     */
    private fun togglePullOut() {
        if (!shown || !placed || folded || allHidden) return
        onTouched?.invoke()
        val d = dims()
        val (w, h) = frame()
        val f = facing ?: DiskGeometry.facing(cx, w)
        val whole = cx - d.plate >= 0f && cx + d.plate <= w
        val tx = when {
            whole -> if (f == 0f) 0f else w.toFloat()
            f == 0f -> d.plate + d.gap
            else -> w - d.plate - d.gap
        }
        val (dx, dy) = DiskGeometry.dock(tx, cy, w, h, d.plate, d.inset)
        stopMotion()
        turning = null
        animateTo(DiskGeometry.snap(rotation, step()), dx, dy)
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

    private fun plateAlpha(): Float = plateOverride ?: DiskLook.plateAlpha(idleAlpha, lightGlass)

    private fun showPlate() {
        if (plate != null || allHidden || folded) return
        val d = dims()
        val size = d.window
        val v = PlateView(service, d.shadow.toFloat(), dp(SHADOW_DROP_DP).toFloat()).apply {
            setLook(plateAlpha(), lightGlass, socketOverride)
            setOnTouchListener(PlateTouch())
        }
        // Стекло трогаемое: за него везут диск. Углы квадрата окна вне круга
        // касание не принимают (PlateTouch), но и в приложение под ними оно не
        // проходит — окно круглым не бывает; это цена, и она принята.
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (cx - d.plate - d.shadow).roundToInt()
            y = (cy - d.plate - d.shadow).roundToInt()
        }
        plate = v
        plateParams = p
        runCatching { windowManager.addView(v, p) }
        // Стекло добавилось последним — то есть ПОВЕРХ кнопок и шестерёнки, а
        // ему положено лежать под ними: касание сначала им. Порядок окон
        // одного типа — порядок добавления, так что всё, что сейчас висит,
        // перевешивается заново; снятого это не касается (reattach — no-op).
        slots.forEach { it.button.reattach() }
        head?.reattach()
    }

    private fun placePlate(d: Dims) {
        val v = plate ?: return
        val p = plateParams ?: return
        p.width = d.window
        p.height = d.window
        p.x = (cx - d.plate - d.shadow).roundToInt()
        p.y = (cy - d.plate - d.shadow).roundToInt()
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
     * Стекло диска: круг бумаги (или чернил — тумблер «Светлее · Темнее»), к
     * краю плотнее, по кромке фаска, под ним мягкая тень — чуть шире тарелки
     * и сдвинута вниз, как от света сверху. Вид больше тарелки на тень с
     * каждой стороны; рисуется от центра, как все глифы стопки —
     * центрируется по построению.
     *
     * Светлое стекло появилось 19.09 ночью: «давай его сделаем наоборот,
     * светлее, чем бэкграунд. А то теряется иногда». Тёмная тарелка пропадала
     * на тёмных экранах — а их у владельца большинство. Тень при этом
     * остаётся ЧЁРНОЙ и на светлом стекле: светлый предмет от светлого фона
     * отделяет именно она, а не он сам.
     *
     * Фаска — не обводка: тонкая дуга, светлая сверху и тёмная снизу, одним
     * штрихом с продольным градиентом. Ровная линия по кругу читалась бы как
     * рамка виджета (за это уже заплачено: край тарелки нарочно мягкий), а
     * фаска читается как толщина предмета — и работает только потому, что
     * сверху и снизу она разная.
     *
     * Прозрачность — в красках, а не на виде: тень, стекло и фаска — разные
     * слои с разной плотностью, а общая альфа вида утопила бы тень вместе со
     * стеклом. Тень аппаратному холсту рисуется кольцом-градиентом:
     * setShadowLayer у фигур работает только программно.
     */
    private class PlateView(context: Context, private val shadow: Float, private val drop: Float) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val socket = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var fillAlpha = 0.16f
        private var light = true
        /** Плотность тени кнопки: null — считать от плотности стекла. */
        private var socketAlpha: Float? = null
        private var shaderFor = 0f
        private var shaderAlpha = -1f
        private var shaderLight = true
        private var shaderSocket = -1f
        private var shaderSocketAlpha: Float? = Float.NaN

        // Где стоят кнопки: радиус кольца, радиус кнопки и углы. Диск
        // называет их на каждой расстановке; стекло рисует под ними тени.
        private var ring = 0f
        private var socketR = 0f
        private var angles = FloatArray(0)

        fun setLook(fillAlpha: Float, light: Boolean, socketAlpha: Float?) {
            this.fillAlpha = fillAlpha
            this.light = light
            this.socketAlpha = socketAlpha
            invalidate()
        }

        /**
         * Куда класть тени кнопок. Перерисовываемся не на каждый вызов, а
         * когда картинка правда поехала: диск зовёт это кадр за кадром, пока
         * крутится, и четверть градуса глазу не видна, а перерисовка стоит.
         */
        fun setSockets(ring: Float, radius: Float, src: FloatArray) {
            var moved = this.ring != ring || socketR != radius || angles.size != src.size
            if (!moved) {
                for (i in src.indices) {
                    if (abs(DiskGeometry.delta(angles[i], src[i])) > SOCKET_STEP_DEG) {
                        moved = true
                        break
                    }
                }
            }
            if (!moved) return
            this.ring = ring
            this.socketR = radius
            if (angles.size != src.size) angles = FloatArray(src.size)
            src.copyInto(angles)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) / 2f - shadow
            if (r <= 0f) return
            if (shaderFor != r || shaderAlpha != fillAlpha || shaderLight != light ||
                shaderSocket != socketR || shaderSocketAlpha != socketAlpha
            ) {
                shaderFor = r
                shaderAlpha = fillAlpha
                shaderLight = light
                shaderSocket = socketR
                shaderSocketAlpha = socketAlpha
                val glass = DiskLook.glass(light)
                val edge = DiskLook.withAlpha(glass, fillAlpha)
                val centre = DiskLook.withAlpha(glass, DiskLook.centreAlpha(fillAlpha))
                fill.shader = RadialGradient(
                    cx, cy, r,
                    intArrayOf(centre, centre, edge),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
                // Тень: плотная под тарелкой, к внешнему краю сходит в ноль.
                val dark = DiskLook.black(DiskLook.shadowAlpha(fillAlpha))
                val outer = r + shadow
                shade.shader = RadialGradient(
                    cx, cy + drop, outer,
                    intArrayOf(dark, dark, 0),
                    floatArrayOf(0f, (r - drop) / outer, 1f),
                    Shader.TileMode.CLAMP,
                )
                rim.strokeWidth = (r * RIM_WIDTH_FACTOR).coerceAtLeast(1f)
                rim.shader = LinearGradient(
                    cx, cy - r, cx, cy + r,
                    intArrayOf(
                        DiskLook.white(DiskLook.rimLightAlpha(fillAlpha, light)),
                        DiskLook.white(0f),
                        DiskLook.black(DiskLook.rimShadeAlpha(fillAlpha, light)),
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
                // Тень кнопки строится ВОКРУГ НУЛЯ и одна на все кнопки:
                // холст под каждую сдвигается сам, а шейдер едет с ним.
                val dense = socketAlpha ?: DiskLook.socketAlpha(fillAlpha, light)
                socket.shader = if (socketR <= 0f) null else RadialGradient(
                    0f, 0f, socketR * SOCKET_SPREAD,
                    intArrayOf(DiskLook.black(dense), DiskLook.black(dense), 0),
                    floatArrayOf(0f, 1f / SOCKET_SPREAD, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(cx, cy + drop, r + shadow, shade)
            canvas.drawCircle(cx, cy, r, fill)
            drawSockets(canvas, cx, cy)
            canvas.drawCircle(cx, cy, r - rim.strokeWidth / 2f, rim)
        }

        /** Тени под кнопками: свет сверху, поэтому пятно сдвинуто вниз, как у тарелки. */
        private fun drawSockets(canvas: Canvas, cx: Float, cy: Float) {
            if (socketR <= 0f || angles.isEmpty()) return
            for (a in angles) {
                val rad = Math.toRadians(a.toDouble())
                val save = canvas.save()
                canvas.translate(
                    cx + ring * cos(rad).toFloat(),
                    cy + ring * sin(rad).toFloat() + drop,
                )
                canvas.drawCircle(0f, 0f, socketR * SOCKET_SPREAD, socket)
                canvas.restoreToCount(save)
            }
        }
    }
}
