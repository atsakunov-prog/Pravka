package ru.zf.pravka.trigger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
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
 * диск щёлкает по ближайшей четверти, бросок доворачивает дальше. Убранный
 * диск за стекло везут только ПО КРАЮ, и он остаётся убранным. Шестерёнка:
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
        /** То же для выдавливания: полпикселя глазу не видно, а перерисовка стоит. */
        private const val SOCKET_STEP_PX = 0.5f
        /**
         * Стрелка на кромке: насколько она вдвинута внутрь и какого размера —
         * доли радиуса. Владелец (20.09.2026): «стрелку на краю диска
         * поменьше и побледнее» — она подсказка, а не кнопка режима, и
         * соперничать с «П» и «З» за взгляд ей незачем.
         */
        private const val ARROW_INSET = 0.09f
        private const val ARROW_SIZE = 0.065f
        /**
         * Палец попал в стрелку, если он ближе этого к её центру (доля
         * радиуса). Зона ОСТАЁТСЯ прежней, хотя рисунок уменьшился: целиться
         * приходится в стекло у края экрана, часто на ходу, и мелкий значок
         * не повод делать мелкой мишень.
         */
        private const val ARROW_TOUCH = 0.17f
        /** Плотность шеврона и его тени: подсказка, а не знак. */
        private const val ARROW_ALPHA = 0.5f
        private const val ARROW_SHADOW = 0.16f
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
        /**
         * Столько после тапа по стрелке следующий тап значит «ещё»: диск от
         * первого тапа уже ПОЕХАЛ, и под пальцем стоит не стрелка, а то, что
         * на её место приехало. Окно длиннее обычного двойного тапа: второй
         * тап делается не вслепую, а в ответ на увиденное движение.
         */
        private const val ARROW_AGAIN_MS = 500L
        /**
         * Во сколько порогов касания должен уйти палец по кнопке, чтобы ход
         * получил направление (`DiskGeometry.pull`). На самом пороге вертикаль
         * от диагонали отличает пара пикселей — решать там значило бы решать
         * наугад.
         */
        private const val PULL_DECIDE = 1.5f
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
    private var lightGlass = Settings.DISK_LIGHT_DEFAULT
    // Ручки вида диска из настроек (владелец: «и нужно всё это в настройки»).
    // Размеры — числа, плотности — null, пока владелец не двинул ползунок:
    // до тех пор их считает `DiskLook`, следя за прозрачностью кнопок и стеклом.
    private var gapDp = Settings.DISK_GAP_DEFAULT
    private var gearPct = StackGeometry.GEAR_PCT_DEFAULT
    private var plateOverride: Float? = null
    private var socketOverride: Float? = null
    private var frost = true
    private var rail = true
    private var inertia = true
    private var rollK = Settings.DISK_ROLL_DEFAULT
    /**
     * Диск убран к краю: стекло ушло за край, на экране остался краешек со
     * стрелкой, а «П» и «З» ВЫДАВЛЕНЫ из стекла и видны целиком (владелец,
     * 22.09.2026). Это состояние покоя — в него приходит автоуборка.
     */
    private var tucked = false
    /** На сколько кнопки сейчас выдавлены из стекла, вдоль лица; своя пружина. */
    private var extrude = 0f

    // Тарелка.
    private var plate: PlateView? = null
    private var plateParams: WindowManager.LayoutParams? = null
    /** Углы кнопок для теней на стекле — буфер, чтобы не сорить кадр за кадром. */
    private var socketAngles = FloatArray(0)

    // Движение: одно тело.
    private val turn = DiskPhysics.turn(0f)
    private val slideX = DiskPhysics.slide(0f)
    private val slideY = DiskPhysics.slide(0f)
    // Выдавливание кнопок — ОТДЕЛЬНАЯ пружина от тела диска: кнопки должны
    // доезжать и под пальцем, который в этот миг везёт диск за краешек.
    // Иначе, схватив убранный диск, владелец увидел бы, как кнопки прыгают
    // в стекло на первом же миллиметре.
    private val push = DiskPhysics.slide(0f)
    // Та же работа быстрее — на отрыве от края (`DiskPhysics.quick`); какая из
    // двух сейчас ведёт выдавливание — [pushSpring].
    private val pushQuick = DiskPhysics.quick(0f)
    private var pushSpring = push
    private var turnTarget = 0f
    private var slideTargetX = 0f
    private var slideTargetY = 0f
    private var pushTarget = 0f
    /** Тело диска едет (поворот и центр). */
    private var animating = false
    /** Кнопки выдавливаются или вдвигаются обратно. */
    private var pushing = false
    /** Кадр уже заказан — Choreographer не просят дважды. */
    private var framing = false
    private var lastFrameNs = 0L
    /** Когда последний раз тапнули стрелку: второй тап подряд выводит весь диск. */
    private var arrowTapAt = 0L

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

    // Палец повёл кнопку: крутит, везёт по краю или везёт целиком
    // (`DiskGeometry.pull`) — решается один раз на касание, null — ещё нет.
    private var pull: DiskGeometry.Pull? = null
    /** Кнопка от центра кольца по высоте в миг касания: верхняя она или нижняя. */
    private var pullButtonDy = 0f
    /** Центр диска в миг касания: по краю едет только высота. */
    private var pullStartCx = 0f
    private var pullStartCy = 0f
    /** Центр кольца от пальца: диск везут за кнопку — кнопка остаётся под пальцем. */
    private var ringOffX = 0f
    private var ringOffY = 0f
    /** Куда палец ведёт центр кольца, пока диск везут за кнопку целиком. */
    private var carryX = 0f
    private var carryY = 0f

    // Палец везёт за шестерёнку или за стекло (и за кнопку).
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
        scope.launch {
            settings.diskFrostFlow.collect {
                frost = it
                repaintPlate()
            }
        }
        scope.launch {
            settings.diskRailFlow.collect {
                rail = it
                repaintPlate()
            }
        }
        scope.launch { settings.diskInertiaFlow.collect { inertia = it } }
        scope.launch { settings.diskRollFlow.collect { rollK = it } }
    }

    /** Краски стекла поменялись — перерисовать, не трогая геометрию. */
    private fun repaintPlate() {
        plate?.setLook(plateAlpha(), lightGlass, socketOverride, frost, rail)
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
        // Тарелка и кольцо стали другого размера — выдавливание считалось от
        // прежних: у убранного диска довести его до нового.
        if (tucked) pushTo(extrusion(dims()))
        layout()
    }

    fun add(button: RingButton, enabled: () -> Boolean) {
        slots += Slot(button, enabled)
    }

    // ---- Размеры и рабочая область ----

    private class Dims(val button: Int, val gear: Int, val gap: Int, val shadow: Int) {
        val ring = DiskGeometry.ringRadius(button, gear, gap)
        val plate = DiskGeometry.plateRadius(button, gear, gap)
    }

    /**
     * Сторона окна стекла: тарелка и тень с обеих сторон. Чётная — центр окна
     * тогда на целом пикселе.
     *
     * Размер у окна ОДИН на всю жизнь, и стоит оно по центру КОЛЬЦА кнопок, а
     * не тарелки (см. [placePlate]). Раньше окно росло под растяжку прямо по
     * ходу уборки — ступеньками, потом разом перед выездом, — и оба раза
     * владелец увидел одно и то же: «сначала из диска вырастают уши и потом
     * дерганием он прячется» (22.09.2026). Окно, которое в одном кадре и
     * меняет размер, и едет, система ставит на место не сразу, а догоняет
     * рывком; кнопки же — свои окна, им ждать нечего. Окно, которое только
     * едет, так не делает: так ездит весь остальной диск, и там всё гладко.
     */
    private fun plateSide(d: Dims): Int = 2 * ceil(d.plate + d.shadow).toInt()

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
            val (dx, dy) = DiskGeometry.dock(fx * w, fy * h, w, h, d.plate, DiskGeometry.DOCK_INSET)
            cx = dx
            cy = dy
            rotation = DiskGeometry.norm(r)
            facing = null
            placed = true
            // Экран сменился (сложили, повернули) — диск приезжает выехавшим
            // на половину: уборка считается от свежего простоя, а не тащится
            // из прежней геометрии.
            tucked = false
            extrude = 0f
            stopAll()
            showPlate()
            layout()
        }
    }

    /** Диск выключен: стекло снимается, кнопки остаются — стопку расставит служба. */
    fun hide() {
        shown = false
        placed = false
        stopAll()
        tucked = false
        extrude = 0f
        turning = null
        pull = null
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
            stopAll()
            // Всё убрано в точку — уборка диска кончилась вместе с ним:
            // вернётся он выехавшим, иначе кнопки встали бы вокруг центра,
            // сдвинутого под давно уехавший край.
            tucked = false
            extrude = 0f
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
            val (bx, by) = ringCentre(f)
            if (socketAngles.size != live.size) socketAngles = FloatArray(live.size)
            live.forEachIndexed { i, slot ->
                val angle = DiskGeometry.slotAngle(i, live.size, f, rotation)
                socketAngles[i] = angle
                val (x, y) = DiskGeometry.slotOrigin(bx, by, d.ring, angle, d.button)
                // Сначала место, потом окно: снятое окно ставится на место в
                // параметрах и вешается уже там, где надо. Кнопка уже там —
                // WindowManager не зовём: пока кнопки выдавливаются, кольцо
                // почти стоит, и пустая перекладка окна только занимает кадр.
                if (slot.button.currentPosition() != (x to y)) {
                    slot.button.followTo(x, y, settle = false, link = 1, snap = true)
                }
                slot.button.setOffscreen(!DiskGeometry.onScreen(x, y, d.button, w, h, margin))
            }
            // Стекло знает, где тело диска: по этим числам оно и тени под
            // кнопками кладёт, и СВОЙ КОНТУР тянет за выдавленными кнопками
            // (владелец, 22.09.2026: «надо чтобы они выдавливались вместе с
            // краем»). Одни данные на оба — иначе карман и тень разъехались
            // бы на первом кадре поворота.
            plate?.setBody(
                d.plate,
                d.ring,
                d.button / 2f,
                DiskGeometry.podRadius(d.button, d.gap),
                socketAngles,
                bx - cx,
                by - cy,
            )
        }
        placeHead()
        placePlate(d)
        // Стрелке нужно знать, видна ли тарелка целиком: стрелка живёт только
        // у края.
        plate?.let {
            it.allHiddenView = allHidden
            it.setFacing(f, cx - d.plate < 0f || cx + d.plate > w, tucked)
        }
    }

    /** Где стоять кнопке [button] сейчас — для «П», которая появляется по show(). */
    fun slotOrigin(button: RingButton): Pair<Int, Int>? {
        if (!shown || !placed) return null
        val live = liveSlots()
        val index = live.indexOfFirst { it.button === button }
        if (index < 0) return null
        val d = dims()
        val (w, _) = frame()
        val f = facing ?: DiskGeometry.facing(cx, w)
        val angle = DiskGeometry.slotAngle(index, live.size, f, rotation)
        val (bx, by) = ringCentre(f)
        return DiskGeometry.slotOrigin(bx, by, d.ring, angle, d.button)
    }

    /**
     * Центр, ВОКРУГ КОТОРОГО стоят кнопки. Обычно это центр тарелки; у
     * убранного диска он сдвинут вдоль лица (внутрь экрана) на выдавливание:
     * стекло за краем, а «П» и «З» на экране целиком. Тарелка и шестерёнка
     * живут по-прежнему вокруг `cx, cy` — сдвинуто только кольцо.
     */
    private fun ringCentre(f: Float): Pair<Float, Float> {
        if (extrude <= 0f) return cx to cy
        val a = Math.toRadians(f.toDouble())
        return (cx + extrude * cos(a).toFloat()) to (cy + extrude * sin(a).toFloat())
    }

    /** Насколько кнопки выдавлены из стекла в убранном диске: считает геометрия. */
    private fun extrusion(d: Dims): Float =
        DiskGeometry.extrusion(d.plate, d.ring, d.button, liveSlots().size, d.gap)

    /**
     * Отступ докования для текущего состояния: обычный диск встаёт центром
     * ровно на край, убранный уходит за край на глубину уборки (на экране
     * остаётся краешек со стрелкой).
     */
    private fun dockInset(d: Dims): Int =
        if (tucked) -DiskGeometry.tuckDepth(d.plate) else DiskGeometry.DOCK_INSET

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
        val (bx, by) = ringCentre(f)
        val drifted = live.withIndex().any { (i, slot) ->
            val angle = DiskGeometry.slotAngle(i, live.size, f, rotation)
            val (x, y) = DiskGeometry.slotOrigin(bx, by, d.ring, angle, d.button)
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
                // Второй тап подряд после стрелки — «покажи диск целиком», даже
                // если под палец к этому мигу подъехала кнопка: он целился в
                // стрелку, а не в запись.
                if (arrowAgain()) {
                    slots.forEach { it.button.cancelGesture() }
                    head?.cancelGesture()
                    Haptics.tick(service)
                    arrowTap()
                    return
                }
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
                val (tx, ty) = turnCentre()
                pull = null
                pullButtonDy = by + button.buttonSizePx() / 2f - ty
                pullStartCx = cx
                pullStartCy = cy
                ringOffX = tx - (rawX + fingerOffX)
                ringOffY = ty - (rawY + fingerOffY)
                lastAngle = DiskGeometry.angleOf(tx, ty, rawX + fingerOffX, rawY + fingerOffY)
                lastAngleAt = SystemClock.uptimeMillis()
                lastDetent = detent(rotation)
            }
            MotionEvent.ACTION_MOVE -> {
                if (turning !== button) return
                // Что это за ход — решаем один раз, когда палец отошёл
                // достаточно, чтобы у хода было направление: на самом пороге
                // вертикаль от диагонали по двум-трём пикселям не отличить.
                if (pull == null) {
                    val mdx = rawX - downRawX
                    val mdy = rawY - downRawY
                    if (hypot(mdx, mdy) < touchSlop * PULL_DECIDE) return
                    var p = DiskGeometry.pull(mdx, mdy, pullButtonDy, dims().ring)
                    // «По краю» бывает только у диска, стоящего у края; посреди
                    // экрана такой ход — просто переезд.
                    if (p == DiskGeometry.Pull.EDGE && !atEdge()) p = DiskGeometry.Pull.CARRY
                    pull = p
                    if (p != DiskGeometry.Pull.TURN) beginPull(p)
                }
                when (pull) {
                    DiskGeometry.Pull.EDGE -> {
                        // Вдоль края: высота за пальцем, место у края прежнее.
                        slide(pullStartCx, pullStartCy + (rawY - downRawY), dropped = false, roll = false)
                        return
                    }
                    DiskGeometry.Pull.CARRY -> {
                        carryX = rawX + fingerOffX + ringOffX
                        carryY = rawY + fingerOffY + ringOffY
                        carryPlate()
                        slide(cx, cy, dropped = false, roll = false)
                        return
                    }
                    else -> {}
                }
                val (tx, ty) = turnCentre()
                val angle = DiskGeometry.angleOf(tx, ty, rawX + fingerOffX, rawY + fingerOffY)
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
                val was = pull
                pull = null
                if (was == DiskGeometry.Pull.EDGE || was == DiskGeometry.Pull.CARRY) {
                    // Отпустили — как любой переезд: у края докуется (убранный
                    // остаётся убранным), посреди экрана стоит, где оставили.
                    slide(cx, cy, dropped = true)
                    return
                }
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

    /**
     * Вокруг чего считать угол пальца: вокруг центра КОЛЬЦА, а не тарелки. У
     * убранного диска они разные (кнопки выдавлены), и считать угол вокруг
     * стекла значило бы, что кнопка под пальцем едет не за ним.
     */
    private fun turnCentre(): Pair<Float, Float> =
        ringCentre(facing ?: DiskGeometry.facing(cx, frame().first))

    /**
     * Куда докуется центр из текущего места: у края — к краю, посреди экрана
     * — где стоит. Отступ берётся по состоянию ([dockInset]): убранный диск
     * возвращается за край, а не выезжает на половину.
     */
    private fun docked(): Pair<Float, Float> {
        val d = dims()
        val (w, h) = frame()
        return DiskGeometry.dock(cx, cy, w, h, d.plate, dockInset(d))
    }

    // ---- Палец везёт за кнопку ----

    /**
     * Ход по кнопке решён, и это переезд, а не поворот. Лицо замирает на весь
     * переезд, как у переезда за стекло; [turning] остаётся — отпускание
     * придёт сюда же, через кнопку.
     *
     * Целиком ([DiskGeometry.Pull.CARRY]) — диск отцепляется от края:
     * убранный перестаёт быть убранным, и выдавленные кнопки садятся в
     * стекло быстрой пружиной, пока палец уводит диск (почему быстрой —
     * `DiskPhysics.quick`). Щелчок в руке — «отцепился».
     */
    private fun beginPull(p: DiskGeometry.Pull) {
        val docked = atEdge()
        sliding = true
        stopMotion()
        slideFacing = facing ?: DiskGeometry.facing(cx, frame().first)
        if (p != DiskGeometry.Pull.CARRY) return
        if (tucked) {
            tucked = false
            pushTo(0f, quick = true)
        }
        if (docked) Haptics.tick(service)
    }

    /**
     * Центр тарелки, пока диск везут за кнопку: кнопка под пальцем, значит
     * под пальцем кольцо, а тарелка отстаёт от него на то, что кнопкам ещё
     * вдвигаться. Зовётся и с касания, и с кадра — выдавливание гаснет между
     * касаниями, и без кадра кнопка уползала бы из-под пальца.
     */
    private fun carryPlate() {
        val a = Math.toRadians(slideFacing.toDouble())
        cx = carryX - extrude * cos(a).toFloat()
        cy = carryY - extrude * sin(a).toFloat()
    }

    /** Тарелка заходит за левый или правый край: диск стоит у края — половиной или убранным. */
    private fun atEdge(): Boolean {
        val d = dims()
        val (w, _) = frame()
        return cx - d.plate < 0f || cx + d.plate > w
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
    private fun slide(nx: Float, ny: Float, dropped: Boolean, roll: Boolean = true) {
        if (!shown || !placed || folded) return
        onTouched?.invoke()
        val (w, _) = frame()
        if (!sliding) {
            sliding = true
            turning = null
            stopMotion()
            slideFacing = DiskGeometry.facing(cx, w)
        }
        // Убранный диск везут ПО КРАЮ и оставляют убранным. Владелец
        // (22.09.2026): «когда они в состоянии ушек, мне обычно просто надо
        // потащить его по краю экрана и оставить в состоянии таких же ушек».
        // Поэтому палец двигает его только вдоль края: стекло остаётся за
        // краем, «П» и «З» — выдавленными, поворот не трогается. Вытащить диск
        // на экран — дело стрелки и долгого нажатия, а не переезда.
        if (tucked) {
            cy = ny
            if (allHidden) placeHead() else layout()
            if (!dropped) return
            sliding = false
            val (dx, dy) = docked()
            animateTo(rotation, dx, dy)
            return
        }
        // Инерция: диск катится по экрану, как колесо (`DiskGeometry.roll`).
        // Считаем от пройденного пути, а не от скорости: путь ровно тот, что
        // видит глаз, и на рывке пальца кольцо не дёргается лишнего.
        // За кнопку диск не катится: кнопка обязана остаться под пальцем.
        if (roll && inertia && !allHidden) rotation = DiskGeometry.norm(rotation + DiskGeometry.roll(nx - cx, dims().plate, rollK))
        cx = nx
        cy = ny
        if (allHidden) placeHead() else layout()
        if (!dropped) return
        sliding = false
        var (dx, dy) = docked()
        val newFacing = DiskGeometry.facing(dx, w)
        if (newFacing != slideFacing) {
            // Переехал на другую половину экрана: раскладка зеркалится
            // (`DiskGeometry.sense`), и диск встаёт домой — «П» сверху, «З»
            // снизу, лицом внутрь. Перескок кнопок здесь, на броске, а не
            // посреди жеста.
            rotation = 0f
            flip(newFacing)
            val again = docked()
            dx = again.first
            dy = again.second
        }
        animateTo(DiskGeometry.snap(rotation, step()), dx, dy)
    }

    /**
     * Лицо сменилось на броске — раскладка зеркалится. Кнопки, которые ещё
     * не вдвинулись после уборки, встают на кольцо сразу, а стекло подъезжает
     * под них: кольцо, сдвинутое вдоль СТАРОГО лица, иначе прыгнуло бы на
     * двойной сдвиг вдоль нового.
     */
    private fun flip(newFacing: Float) {
        if (extrude > 0f) {
            val a = Math.toRadians(slideFacing.toDouble())
            cx += extrude * cos(a).toFloat()
            cy += extrude * sin(a).toFloat()
            extrude = 0f
            pushing = false
            pushSpring = push
            push.reset(0f)
        }
        facing = newFacing
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
        // Кнопку вели одним пальцем — теперь диск везут двое.
        pull = null
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
        /** Касание началось на стрелке: на отпускании — её дело (убрать или достать). */
        private var onArrow = false
        /** В этом касании был щипок — одиночная логика стекла молчит до следующего DOWN. */
        private var pinched = false
        /** Долгое нажатие сработало: диск выдвинулся или убрался, тап не считается. */
        private var held = false
        private val hold = Runnable {
            if (pinch || pinched || dragging || onArrow) return@Runnable
            held = true
            Haptics.start(service)
            togglePullOut()
        }
        private val slop = ViewConfiguration.get(service).scaledTouchSlop

        private fun key(pointerId: Int): String = "plate:$pointerId"

        /** Палец на стрелке кромки: её зона шире рисунка — целиться некуда. */
        private fun onArrow(v: View, x: Float, y: Float): Boolean {
            val p = plate ?: return false
            if (!p.arrowShown()) return false
            val (ax, ay) = p.arrowAt()
            val reach = p.arrowReach()
            val dx = x - ax
            val dy = y - ay
            return dx * dx + dy * dy <= reach * reach
        }

        /**
         * На стекле — по живому контуру, с растяжкой и полосой тени. Считает
         * сам вид: контур, растянутый под кнопки, знает только он.
         */
        private fun inside(v: View, x: Float, y: Float): Boolean =
            (v as? PlateView)?.inBody(x, y) ?: false

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
                    // Стрелка на кромке — единственное место стекла со своим
                    // смыслом: убрать диск досрочно. Остальные жесты стекла на
                    // этом касании молчат, иначе долгое нажатие выдвинуло бы
                    // диск ровно тогда, когда его просили убрать.
                    onArrow = onArrow(v, event.x, event.y)
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
                    // Стрелка срабатывает на ОТПУСКАНИИ, а не на нажатии:
                    // палец, поехавший со стрелки, всё ещё везёт диск.
                    val arrow = onArrow
                    onArrow = false
                    if (dragging) {
                        slide(cx, cy, dropped = true)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // Сюда же — второй тап подряд после стрелки: он пришёл
                        // по стеклу, потому что стрелка от первого уже уехала.
                        if (arrow || arrowAgain()) {
                            Haptics.tick(service)
                            arrowTap()
                            return true
                        }
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
        retract()
    }

    /**
     * Сама уборка, без оглядки на автоматику: стекло за край до краешка со
     * стрелкой, кнопки выдавлены из него на экран целиком, поворот домой.
     * Владелец (22.09.2026): «когда они убираются, они должны выдавливаться
     * из диска наружу так, чтобы кнопки были целиком видны (правка и
     * засечка). И между ними был бы краешек диска со стрелкой».
     *
     * Почему выдавливание, а не просто «диск поглубже»: у докованного диска
     * кнопки сидят на кольце вокруг центра, и стоило центру уйти за край,
     * как край срезал бы и кнопки. Кольцо поэтому едет отдельно от стекла —
     * на столько, чтобы дальняя кромка «П» и «З» встала у края экрана
     * (`DiskGeometry.extrusion`). Ровно между ними остаётся тот краешек,
     * в котором живёт стрелка.
     */
    private fun retract() {
        if (!shown || !placed || folded || allHidden) return
        val d = dims()
        val (w, h) = frame()
        val f = facing ?: DiskGeometry.facing(cx, w)
        val edge = if (f == 0f) 0f else w.toFloat()
        val (dx, dy) = DiskGeometry.dock(edge, cy, w, h, d.plate, -DiskGeometry.tuckDepth(d.plate))
        val target = DiskGeometry.home(rotation)
        // Уже убран и стоит ровно там — не тревожим диск и не пишем место:
        // тик простоя приходит каждые полминуты.
        if (tucked && abs(target - rotation) < 0.5f && abs(dx - cx) < 0.5f && abs(dy - cy) < 0.5f) return
        tucked = true
        // Без stopMotion: диск, пойманный стрелкой на ходу, продолжает с той
        // скоростью, с какой ехал (см. [animateTo]), а не замирает на кадр.
        turning = null
        animateTo(target, dx, dy)
    }

    /**
     * Половина диска — как у докованного после переезда: центр ровно на краю,
     * шестерёнка видна до середины. Первый тап по стрелке убранного диска.
     */
    private fun halfOut() {
        if (!shown || !placed || folded || allHidden) return
        val d = dims()
        val (w, h) = frame()
        tucked = false
        turning = null
        val (dx, dy) = DiskGeometry.dock(cx, cy, w, h, d.plate, DiskGeometry.DOCK_INSET)
        animateTo(DiskGeometry.snap(rotation, step()), dx, dy)
    }

    /** Весь диск на экран, с просветом от края. Двойной тап по стрелке и долгое нажатие. */
    private fun wholeOut() {
        if (!shown || !placed || folded || allHidden) return
        val d = dims()
        val (w, h) = frame()
        val f = facing ?: DiskGeometry.facing(cx, w)
        tucked = false
        // Второй тап по стрелке приходит, пока диск ещё выезжает на половину:
        // новая цель подхватывает его ход, а не начинает с места.
        turning = null
        val tx = if (f == 0f) d.plate + d.gap else w - d.plate - d.gap
        val (dx, dy) = DiskGeometry.dock(tx, cy, w, h, d.plate, DiskGeometry.DOCK_INSET)
        animateTo(DiskGeometry.snap(rotation, step()), dx, dy)
    }

    /**
     * Тап по стрелке на краешке. Убранный диск ВЫЕЗЖАЕТ: первый тап —
     * половина, второй сразу за ним — весь диск (владелец, 22.09.2026: «один
     * тап по стрелке — вылезает половина диска, двойной тап — весь диск
     * вылезает»). Выехавший — убирается обратно.
     *
     * Первый тап срабатывает СРАЗУ, а не ждёт, не будет ли второго: половина
     * лежит по дороге к целому, и второй тап просто продолжает движение.
     * Ждать триста миллисекунд ради двойного значило бы платить задержкой на
     * каждом обычном тапе — а тапают тут одной рукой, на ходу.
     */
    private fun arrowTap() {
        val second = arrowAgain()
        arrowTapAt = SystemClock.uptimeMillis()
        onTouched?.invoke()
        when {
            tucked -> halfOut()
            second -> wholeOut()
            else -> retract()
        }
    }

    /**
     * Идёт ли окно «ещё» — второй тап подряд после стрелки. Считается от
     * ПЕРВОГО ТАПА, а не от места: к этому мигу стрелка уехала вместе с
     * диском, и второй тап честно попадает мимо неё — по стеклу или по
     * подъехавшей кнопке. Ждать окна перед первым тапом нельзя (задержка на
     * каждом обычном), поэтому ждём после него.
     */
    private fun arrowAgain(): Boolean =
        arrowTapAt > 0L && SystemClock.uptimeMillis() - arrowTapAt < ARROW_AGAIN_MS

    /**
     * Выдвинуть диск целиком на экран — или, если он уже целиком виден,
     * убрать к краю. Долгое нажатие на стекло; владелец согласился на
     * «выдвижение по удержанию»: убранный диск показывает две кнопки, а за
     * третьей иначе надо крутить.
     */
    private fun togglePullOut() {
        if (!shown || !placed || folded || allHidden) return
        onTouched?.invoke()
        val d = dims()
        val (w, _) = frame()
        val whole = !tucked && cx - d.plate >= 0f && cx + d.plate <= w
        if (whole) retract() else wholeOut()
    }

    // ---- Пружины ----

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating && !pushing) {
                framing = false
                return
            }
            val dt = if (lastFrameNs == 0L) 1f / 60f else (frameTimeNanos - lastFrameNs) / 1_000_000_000f
            lastFrameNs = frameTimeNanos
            if (animating) {
                val doneTurn = turn.step(turnTarget, dt)
                val doneX = slideX.step(slideTargetX, dt)
                val doneY = slideY.step(slideTargetY, dt)
                rotation = turn.position
                cx = slideX.position
                cy = slideY.position
                if (doneTurn && doneX && doneY) {
                    animating = false
                    rotation = DiskGeometry.norm(rotation)
                    persist()
                }
            }
            if (pushing) {
                if (pushSpring.step(pushTarget, dt)) pushing = false
                extrude = pushSpring.position
                // Диск везут за кнопку, а кнопки ещё вдвигаются: тарелка
                // подъезжает под них и между касаниями.
                if (pull == DiskGeometry.Pull.CARRY && turning != null) carryPlate()
            }
            layout()
            if (animating || pushing) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                framing = false
            }
        }
    }

    /** Кадры нужны — заказать, если ещё не заказаны. */
    private fun startFrames() {
        if (framing) return
        framing = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * Выдавить кнопки из стекла (или вдвинуть обратно) — своей пружиной.
     * Она живёт отдельно от тела: диск может в этот миг ехать под пальцем.
     *
     * Уже едет туда же — не трогаем: второй тап по стрелке зовёт сюда ту же
     * цель, и перезапуск с места растягивал бы вдвигание, пока тело диска
     * уезжает вперёд, — кнопки опережали бы стекло. Едет в другую сторону —
     * разворачивается со своей скоростью, а не замирает.
     */
    private fun pushTo(target: Float, quick: Boolean = false) {
        val spring = if (quick) pushQuick else push
        if (pushing && pushTarget == target && pushSpring === spring) return
        if (abs(target - extrude) < 0.5f && (!pushing || abs(pushSpring.velocity) < Spring.REST_SPEED)) {
            extrude = target
            pushing = false
            return
        }
        spring.reset(extrude, if (pushing) pushSpring.velocity else 0f)
        pushSpring = spring
        pushTarget = target
        pushing = true
        startFrames()
    }

    private fun animateTo(rotTarget: Float, toX: Float, toY: Float, launch: Float = 0f) {
        // Куда ехать выдавливанию, решает состояние: убранный диск выдавливает
        // кнопки на экран, любой другой держит их на кольце.
        val pushGoal = if (tucked) extrusion(dims()) else 0f
        val still = abs(rotTarget - rotation) < 0.5f && abs(toX - cx) < 0.5f &&
            abs(toY - cy) < 0.5f && abs(pushGoal - extrude) < 0.5f
        if (still && abs(launch) < 1f && !animating) {
            rotation = DiskGeometry.norm(rotation)
            persist()
            return
        }
        // Диск УЖЕ едет — новая цель подхватывает его скорость, а не гасит её:
        // второй тап по стрелке приходит посреди выезда на половину, и
        // перезапуск пружин с нуля останавливал бы диск на кадр, чтобы потом
        // поехать заново. Палец, взявший диск, скорость гасит сам ([stopMotion]).
        val carry = animating
        turnTarget = rotTarget
        slideTargetX = toX
        slideTargetY = toY
        turn.reset(rotation, if (launch != 0f) launch else if (carry) turn.velocity else 0f)
        slideX.reset(cx, if (carry) slideX.velocity else 0f)
        slideY.reset(cy, if (carry) slideY.velocity else 0f)
        animating = true
        pushTo(pushGoal)
        startFrames()
    }

    /**
     * Тело диска встало — его взял палец. Скорость пружин гасится вместе с
     * движением: следующая цель ([animateTo]) начнёт с места. Выдавливание
     * своей пружиной доезжает само.
     */
    private fun stopMotion() {
        if (!animating) return
        animating = false
        turn.reset(rotation)
        slideX.reset(cx)
        slideY.reset(cy)
        if (!pushing && framing) {
            framing = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    /** Всё разом: и тело, и выдавливание — на выключении диска. */
    private fun stopAll() {
        pushing = false
        animating = false
        if (framing) {
            framing = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
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
        val size = plateSide(d)
        val (bx, by) = ringCentre(facing ?: DiskGeometry.facing(cx, frame().first))
        val v = PlateView(service, d.shadow.toFloat(), dp(SHADOW_DROP_DP).toFloat()).apply {
            setLook(plateAlpha(), lightGlass, socketOverride, frost, rail)
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
            x = (bx - size / 2f).roundToInt()
            y = (by - size / 2f).roundToInt()
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

    /**
     * Окно стекла стоит по центру КОЛЬЦА кнопок, а тарелку вид рисует
     * сдвинутой на выдавливание (`PlateView.setBody`). Так растянутое стекло
     * под «П» и «З» всегда помещается в окно того же размера: карман кнопки
     * от центра кольца кончается ровно на радиусе тарелки. Обрезается при этом
     * только СПИНА тарелки — та, что у убранного диска за краем экрана (тест в
     * `DiskGeometryTest`). И пока кнопки выдавливаются, окно почти стоит, как и
     * они: едет рисунок внутри него, а не окна друг за другом.
     *
     * Размер не меняется никогда (почему — [plateSide]), а если и место то же,
     * WindowManager не зовём вовсе: поворот диска стекло не двигает.
     */
    private fun placePlate(d: Dims) {
        val v = plate ?: return
        val p = plateParams ?: return
        val size = plateSide(d)
        val (bx, by) = ringCentre(facing ?: DiskGeometry.facing(cx, frame().first))
        val x = (bx - size / 2f).roundToInt()
        val y = (by - size / 2f).roundToInt()
        if (p.x == x && p.y == y && p.width == size && p.height == size) return
        p.width = size
        p.height = size
        p.x = x
        p.y = y
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
        stopAll()
        hidePlate()
        shown = false
        placed = false
    }

    /**
     * Стекло диска: круг бумаги (или чернил — тумблер «Светлее · Темнее»), к
     * краю плотнее, по кромке фаска, под ним мягкая тень — чуть шире тарелки
     * и сдвинута вниз, как от света сверху. Вид больше тарелки на тень с
     * каждой стороны и стоит по центру КОЛЬЦА кнопок: у убранного диска
     * тарелка в нём сдвинута назад на выдавливание, и рисуется всё от её
     * центра (холст сдвигается туда, градиенты — вокруг нуля).
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
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val socket = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val topLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val frostPaint = Paint().apply { isFilterBitmap = false }
        private var fillAlpha = 0.16f
        private var light = Settings.DISK_LIGHT_DEFAULT
        /** Плотность тени кнопки: null — считать от плотности стекла. */
        private var socketAlpha: Float? = null
        private var frost = true
        private var rail = true
        private var shaderFor = 0f
        private var shaderCentre = Float.NaN
        private var shaderAlpha = -1f
        private var shaderLight = true
        private var shaderSocket = -1f
        private var shaderSocketAlpha: Float? = Float.NaN
        private var shaderRail: Boolean? = null
        private var shaderFrost: Boolean? = null
        private var grain: Bitmap? = null
        private val clip = Path()

        // Лицо диска и «виден ли он целиком» — от них зависит, куда смотрит
        // стрелка на кромке. (Дуга прогресса по кромке снята 26.09.2026 —
        // ожидание теперь отсчётом секунд на самой кнопке, `ButtonCountdown`.)
        private var facing = 180f
        private var atEdge = false
        /** Диск убран за край: стрелка смотрит внутрь — «тапни, и выеду». */
        private var retracted = false
        /** Всё убрано в точку — стекла нет, и стрелки тоже. */
        var allHiddenView = false
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val arrowPath = Path()

        // Где стоит тело диска: тарелка, кольцо кнопок с их углами, радиус
        // кнопки (под неё тень) и радиус кармана (под неё стекло). Диск
        // называет это на каждой расстановке. Сдвиг — выдавливание убранного
        // диска: кольцо уезжает из стекла на экран, и тени с карманами едут
        // за ним.
        private var plateR = 0f
        private var ring = 0f
        private var socketR = 0f
        private var podR = 0f
        private var angles = FloatArray(0)
        private var shiftX = 0f
        private var shiftY = 0f

        // Контур стекла: радиусы по лучам (`DiskGeometry.blob`) и путь по ним.
        // `blobbed` — стекло правда растянуто; ровный круг рисуется как
        // рисовался, без пути и без обрезки.
        private val blobR = FloatArray(DiskGeometry.BLOB_RAYS)
        private val blobTmp = FloatArray(DiskGeometry.BLOB_RAYS)
        private var pods = FloatArray(0)
        private var blobbed = false
        private val shape = Path()
        private var shapeAt = Float.NaN
        private val shadeFlat = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val fringe = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val podFringe = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun setLook(fillAlpha: Float, light: Boolean, socketAlpha: Float?, frost: Boolean, rail: Boolean) {
            this.fillAlpha = fillAlpha
            this.light = light
            this.socketAlpha = socketAlpha
            this.frost = frost
            this.rail = rail
            invalidate()
        }

        /**
         * Куда смотрит диск, торчит ли он за край и убран ли (тогда стрелка
         * зовёт достать). От этого зависит стрелка на кромке.
         */
        fun setFacing(facing: Float, atEdge: Boolean, retracted: Boolean) {
            if (this.facing == facing && this.atEdge == atEdge && this.retracted == retracted) return
            this.facing = facing
            this.atEdge = atEdge
            this.retracted = retracted
            invalidate()
        }

        /**
         * Центр тарелки в координатах вида. Окно стоит по центру КОЛЬЦА
         * (`DiskController.placePlate`), поэтому тарелка в нём сдвинута назад
         * на выдавливание; у невыдавленного диска это середина окна, как было.
         */
        private fun centreX(): Float = width / 2f - shiftX
        private fun centreY(): Float = height / 2f - shiftY

        /** Где стрелка от центра тарелки: у кромки со стороны лица диска. */
        private fun arrowOffset(): Pair<Float, Float> {
            val r = plateR - plateR * ARROW_INSET
            val a = Math.toRadians(facing.toDouble())
            return r * cos(a).toFloat() to r * sin(a).toFloat()
        }

        /** Где стрелка в координатах вида: у кромки ТАРЕЛКИ со стороны лица диска. */
        fun arrowAt(): Pair<Float, Float> {
            val (ox, oy) = arrowOffset()
            return centreX() + ox to centreY() + oy
        }

        /** Стрелка сейчас видна — значит по ней и жмут. */
        fun arrowShown(): Boolean = atEdge && !allHiddenView

        /**
         * Где тело диска: тарелка [plate], кольцо кнопок [ring] с углами
         * [src] и сдвигом выдавливания, радиус кнопки [radius] (под неё
         * тень) и радиус кармана [pod] (под неё стекло). Отсюда стекло берёт
         * И тени под кнопками, И свой контур — один источник на оба, иначе
         * карман и тень разъехались бы на первом же кадре поворота.
         *
         * Перерисовываемся не на каждый вызов, а когда картинка правда
         * поехала: диск зовёт это кадр за кадром, пока крутится, и четверть
         * градуса глазу не видна, а пересчёт контура и перерисовка стоят.
         */
        fun setBody(plate: Float, ring: Float, radius: Float, pod: Float, src: FloatArray, shiftX: Float, shiftY: Float) {
            var moved = plateR != plate || this.ring != ring || socketR != radius || podR != pod ||
                angles.size != src.size ||
                abs(this.shiftX - shiftX) > SOCKET_STEP_PX || abs(this.shiftY - shiftY) > SOCKET_STEP_PX
            if (!moved) {
                for (i in src.indices) {
                    if (abs(DiskGeometry.delta(angles[i], src[i])) > SOCKET_STEP_DEG) {
                        moved = true
                        break
                    }
                }
            }
            if (!moved) return
            plateR = plate
            this.ring = ring
            this.socketR = radius
            this.podR = pod
            this.shiftX = shiftX
            this.shiftY = shiftY
            if (angles.size != src.size) angles = FloatArray(src.size)
            src.copyInto(angles)
            reshape()
            invalidate()
        }

        /** Пересчитать карманы и контур: тело поехало. */
        private fun reshape() {
            if (pods.size != angles.size * 2) pods = FloatArray(angles.size * 2)
            for (i in angles.indices) {
                val a = Math.toRadians(angles[i].toDouble())
                pods[i * 2] = shiftX + ring * cos(a).toFloat()
                pods[i * 2 + 1] = shiftY + ring * sin(a).toFloat()
            }
            blobbed = DiskGeometry.blob(blobR, blobTmp, plateR, pods, podR)
            shapeAt = Float.NaN
        }

        /**
         * Путь по лучам контура. Рисуется от центра тарелки (холст сдвинут
         * туда), так что строится только на смену формы и живёт до следующей.
         */
        private fun shape(cx: Float, cy: Float): Path {
            if (shapeAt == cx) return shape
            shape.reset()
            val n = blobR.size
            for (i in 0 until n) {
                val a = i * 2.0 * Math.PI / n
                val x = cx + blobR[i] * cos(a).toFloat()
                val y = cy + blobR[i] * sin(a).toFloat()
                if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
            }
            shape.close()
            shapeAt = cx
            return shape
        }

        /**
         * Можно ли под этой точкой вида взять диск: всё стекло по его ЖИВОМУ
         * контуру, с растяжкой под кнопками, плюс полоса тени. Владелец
         * (22.09.2026): «для 100 % движения давай если я берусь между кнопками,
         * то могу двигать за него». У убранного диска «между кнопками» — это
         * краешек со стрелкой и плечи растяжки, и прежняя проверка (круг
         * тарелки плюс кружки карманов) плечи не узнавала: палец туда попадал,
         * а диск не ехал. Тень тоже засчитана: окно стекла всё равно не
         * пропускает её касания в приложение, так пусть они хотя бы везут диск.
         */
        fun inBody(x: Float, y: Float): Boolean {
            val dx = x - centreX()
            val dy = y - centreY()
            return hypot(dx, dy) <= contourAt(atan2(dy, dx)) + shadow
        }

        /** Радиус контура стекла по направлению [angle] (радианы) от центра тарелки. */
        private fun contourAt(angle: Float): Float {
            if (!blobbed) return plateR
            val n = blobR.size
            val step = 2.0 * Math.PI / n
            var at = angle / step
            if (at < 0) at += n
            val i = at.toInt() % n
            val t = (at - at.toInt()).toFloat()
            return blobR[i] + (blobR[(i + 1) % n] - blobR[i]) * t
        }

        /** Мишень стрелки: шире рисунка, и считается от ТАРЕЛКИ, а не от окна. */
        fun arrowReach(): Float = (plateR + shadow) * ARROW_TOUCH

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = plateR
            if (r <= 0f) return
            // Всё рисуется от центра тарелки: холст сдвигается туда, а
            // градиенты, контур и тени строятся вокруг нуля. Тарелка в окне
            // едет, пока кнопки выдавливаются, — пересобирать градиенты под
            // каждый её сдвиг значило бы делать это кадр за кадром.
            val moved = canvas.save()
            canvas.translate(centreX(), centreY())
            val cx = 0f
            val cy = 0f
            if (shaderFor != r || shaderCentre != cx || shaderAlpha != fillAlpha || shaderLight != light ||
                shaderSocket != socketR || shaderSocketAlpha != socketAlpha ||
                shaderRail != rail || shaderFrost != frost
            ) {
                shaderFor = r
                shaderCentre = cx
                shaderAlpha = fillAlpha
                shaderLight = light
                shaderSocket = socketR
                shaderSocketAlpha = socketAlpha
                shaderRail = rail
                shaderFrost = frost
                val glass = DiskLook.glass(light)
                val edge = DiskLook.withAlpha(glass, fillAlpha)
                val centre = DiskLook.withAlpha(glass, DiskLook.centreAlpha(fillAlpha))
                fill.shader = RadialGradient(
                    cx, cy, r,
                    intArrayOf(centre, centre, edge),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
                // Тень: под телом ровная, снаружи мягкая бахрома. Раньше это
                // был один радиальный градиент от центра тарелки; с
                // растянутым стеклом так нельзя — два градиента внахлёст дали
                // бы под перемычкой пятно вдвое плотнее. Тело поэтому
                // закрашивается РОВНО (один путь — нахлёста нет), а бахрома
                // рисуется только за его пределами.
                val dark = DiskLook.black(DiskLook.shadowAlpha(fillAlpha))
                shadeFlat.color = dark
                val outer = r + shadow
                fringe.shader = RadialGradient(
                    cx, cy, outer,
                    intArrayOf(dark, dark, 0),
                    floatArrayOf(0f, r / outer, 1f),
                    Shader.TileMode.CLAMP,
                )
                // Бахрома кармана строится ВОКРУГ НУЛЯ и одна на все карманы:
                // холст под каждый сдвигается сам, как у теней кнопок.
                val podOuter = podR + shadow
                podFringe.shader = if (podR <= 0f) null else RadialGradient(
                    0f, 0f, podOuter,
                    intArrayOf(dark, dark, 0),
                    floatArrayOf(0f, podR / podOuter, 1f),
                    Shader.TileMode.CLAMP,
                )
                // Свет сверху: верх тарелки ярче, низ уходит в ноль. Мягкая
                // заливка на полдиска, не кромка — за кромку отвечает фаска.
                topLight.shader = LinearGradient(
                    cx, cy - r, cx, cy + r * 0.3f,
                    intArrayOf(
                        DiskLook.white(DiskLook.topLightAlpha(fillAlpha, light)),
                        DiskLook.white(0f),
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
                // Рельс: канавка по кольцу кнопок. Один штрих шириной в
                // кнопку, а поперёк него — радиальный градиент от центра
                // тарелки: тёмная середина канавки и светлые края читаются
                // как вдавленность, а не как нарисованное кольцо.
                railPaint.strokeWidth = (socketR * 1.1f).coerceAtLeast(1f)
                val deep = DiskLook.railAlpha(fillAlpha, light)
                railPaint.shader = if (socketR <= 0f || ring <= 0f) null else RadialGradient(
                    cx, cy, ring + socketR,
                    intArrayOf(
                        DiskLook.white(0f),
                        DiskLook.white(deep * 0.8f),
                        DiskLook.black(deep),
                        DiskLook.white(deep * 0.5f),
                        DiskLook.white(0f),
                    ),
                    floatArrayOf(
                        0f,
                        ((ring - socketR * 0.55f) / (ring + socketR)).coerceIn(0f, 1f),
                        (ring / (ring + socketR)).coerceIn(0f, 1f),
                        ((ring + socketR * 0.55f) / (ring + socketR)).coerceIn(0f, 1f),
                        1f,
                    ),
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
            drawShade(canvas, cx, cy, r)
            // Тело — по контуру: растянутое стекло — ОДНОЙ обрезкой, под
            // которой заливка, иней и свет ложатся на всё полотно; ровный
            // круг остаётся кругом, как рисовался. Контур меняется кадр в кадр,
            // пока кнопки выдавливаются, и каждый путь, отданный на рисование,
            // обсчитывается заново — одна обрезка вместо четырёх путей.
            val body = canvas.save()
            if (blobbed) {
                canvas.clipPath(shape(cx, cy))
                canvas.drawPaint(fill)
            } else {
                canvas.drawCircle(cx, cy, r, fill)
            }
            if (frost) drawFrost(canvas, cx, cy, r)
            if (blobbed) canvas.drawPaint(topLight) else canvas.drawCircle(cx, cy, r, topLight)
            if (rail && railPaint.shader != null) canvas.drawCircle(cx, cy, ring, railPaint)
            drawSockets(canvas, cx, cy)
            canvas.restoreToCount(body)
            if (blobbed) canvas.drawPath(shape(cx, cy), rim)
            else canvas.drawCircle(cx, cy, r - rim.strokeWidth / 2f, rim)
            if (arrowShown()) drawArrow(canvas, cx, cy, r)
            canvas.restoreToCount(moved)
        }

        /**
         * Тень под стеклом: ровная плотность под всем телом и мягкая бахрома
         * снаружи. Тело закрашивается одним путём, поэтому нахлёста плотностей
         * внутри него нет; бахрома идёт за вычетом тела (`clipOutPath`), и
         * там, где карман кнопки подходит к тарелке, она лишь чуть густеет —
         * складка в месте, где стекло растянуто, глазу как раз понятна.
         */
        private fun drawShade(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val save = canvas.save()
            canvas.translate(0f, drop)
            val path = if (blobbed) shape(cx, cy) else clip.also {
                it.reset()
                it.addCircle(cx, cy, r, Path.Direction.CW)
            }
            canvas.drawPath(path, shadeFlat)
            canvas.clipOutPath(path)
            canvas.drawCircle(cx, cy, r + shadow, fringe)
            if (blobbed && podFringe.shader != null) {
                var p = 0
                while (p + 1 < pods.size) {
                    val px = pods[p]
                    val py = pods[p + 1]
                    p += 2
                    val at = canvas.save()
                    canvas.translate(cx + px, cy + py)
                    canvas.drawCircle(0f, 0f, podR + shadow, podFringe)
                    canvas.restoreToCount(at)
                }
            }
            canvas.restoreToCount(save)
        }

        /**
         * Стрелка на кромке (владелец, 20.09.2026): «на краю торчащего диска
         * должна быть маленькая стрелочка, которая бы убирала диск досрочно…
         * когда диск убран, то на краю есть такая же стрелка, но в другую
         * сторону, которая его открывает».
         *
         * Стоит она на кромке со стороны ЛИЦА — то есть в самой дальней от
         * края экрана точке тарелки. Это единственное место, которое видно и
         * у торчащего диска, и у убранного, и оно же всегда свободно: лицо
         * лежит ровно между «П» и «З», а кольцо кнопок проходит ближе к
         * центру. Смотрит стрелка туда, куда поедет диск: наружу — убрать,
         * внутрь — достать.
         */
        private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val (ox, oy) = arrowOffset()
            val ax = cx + ox
            val ay = cy + oy
            val size = (r * ARROW_SIZE).coerceAtLeast(4f)
            // Наружу — значит против лица; внутрь — по лицу.
            val dir = if (retracted) facing else facing + 180f
            arrow.strokeWidth = (size * 0.38f).coerceAtLeast(2f)
            arrowPath.reset()
            // Шеврон: два луча от кончика назад, под 40° к направлению.
            val tip = Math.toRadians(dir.toDouble())
            val tx = ax + cos(tip).toFloat() * size * 0.5f
            val ty = ay + sin(tip).toFloat() * size * 0.5f
            for (side in intArrayOf(-1, 1)) {
                val a = Math.toRadians((dir + side * 140f).toDouble())
                arrowPath.moveTo(tx, ty)
                arrowPath.lineTo(tx + cos(a).toFloat() * size, ty + sin(a).toFloat() * size)
            }
            // Тень под шевроном и сам шеврон: тот же свет сверху, что у всего
            // остального — тёмный штрих снизу, светлый поверх.
            arrow.color = DiskLook.black(ARROW_SHADOW)
            canvas.save()
            canvas.translate(0f, arrow.strokeWidth * 0.4f)
            canvas.drawPath(arrowPath, arrow)
            canvas.restore()
            arrow.color = DiskLook.withAlpha(DiskLook.gearInk(light), ARROW_ALPHA)
            canvas.drawPath(arrowPath, arrow)
        }

        /**
         * Иней: мелкое зерно по стеклу, плиткой 64×64 и с обрезкой по стеклу.
         * Зерно одно на все размеры и все шкурки — меняется только его
         * плотность: это шум, его незачем пересчитывать под каждую тарелку.
         */
        private fun drawFrost(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val grain = grain ?: makeGrain().also { grain = it }
            frostPaint.shader = frostPaint.shader ?: BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            frostPaint.alpha = (255 * DiskLook.frostAlpha(fillAlpha, light)).toInt().coerceIn(0, 255)
            // Иней красит ВСЁ полотно, а оно шире стекла на тень. Растянутое
            // тело уже обрезано по контуру — вторая обрезка тем же путём
            // ничего бы не дала; ровный круг обрезаем сами.
            if (blobbed) {
                canvas.drawPaint(frostPaint)
                return
            }
            val save = canvas.save()
            canvas.clipPath(clip.also {
                it.reset()
                it.addCircle(cx, cy, r, Path.Direction.CW)
            })
            canvas.drawPaint(frostPaint)
            canvas.restoreToCount(save)
        }

        /**
         * Плитка зерна: чёрно-белый шум без повторяющегося рисунка на глаз.
         * Seed постоянный — зерно не «кипит» при каждой перерисовке.
         */
        private fun makeGrain(): Bitmap {
            val size = 64
            val random = java.util.Random(20_260_920L)
            val pixels = IntArray(size * size)
            for (i in pixels.indices) {
                // Половина точек светлые, половина тёмные: средняя яркость
                // не сдвигается, значит зерно не красит стекло, а только
                // делает его шероховатым.
                val v = random.nextInt(256)
                pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }

        /** Тени под кнопками: свет сверху, поэтому пятно сдвинуто вниз, как у тарелки. */
        private fun drawSockets(canvas: Canvas, cx: Float, cy: Float) {
            if (socketR <= 0f || angles.isEmpty()) return
            for (a in angles) {
                val rad = Math.toRadians(a.toDouble())
                val save = canvas.save()
                canvas.translate(
                    cx + shiftX + ring * cos(rad).toFloat(),
                    cy + shiftY + ring * sin(rad).toFloat() + drop,
                )
                canvas.drawCircle(0f, 0f, socketR * SOCKET_SPREAD, socket)
                canvas.restoreToCount(save)
            }
        }
    }
}
