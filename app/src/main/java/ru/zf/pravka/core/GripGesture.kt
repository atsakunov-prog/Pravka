package ru.zf.pravka.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Палец на язычке колонки разворота (`ColumnGrip` в `DelaHomeUi.kt`): тап, удержание и протяжка — одним решением, без
 * Compose, под тестом `GripGestureTest`.
 *
 * Отладка 16.10.2026, запись 9: «нажимаю на вертикальные стрелки… колонки не двигаются, я имею в виду долгое нажатие».
 * На язычке висели `combinedClickable` и `draggable`: после долгого нажатия первый (foundation 1.9.4,
 * `detectTapGestures`) зовёт `onLongPress` и до отпускания съедает каждое движение пальца, а второй, увидев съеденное,
 * отменяет протяжку — вибрация «держу» есть, колонка стоит. Теперь одно решение:
 * - до долгого нажатия: сдвиг вбок больше [slop] (и не круче, чем вниз) — протяжка с накопленным сдвигом; вниз
 *   больше [slop] — палец не наш (ни тапа, ни протяжки);
 * - долгое нажатие — [Act.Held] (вибрация), после него любой сдвиг больше [heldSlop] — протяжка;
 * - отпустил: тянул — [Act.End]; держал, не двигая (и дольше долгого нажатия), — ничего; иначе — [Act.Toggle]
 *   (спрятать или показать колонку);
 * - жест оборвался посреди протяжки — [Act.End], иначе колонки застрянут «держат».
 *
 * Сдвиги — в dp, знак как у пальца по экрану (плюс — вправо); куда это ведёт колонку, решает язычок по своей стороне.
 */
class GripGesture(private val slop: Float = SLOP, private val heldSlop: Float = HELD_SLOP) {

    sealed interface Act {
        /** Ничего не делать. */
        data object None : Act
        /** Долгое нажатие — отклик пальцу «держу, можно тянуть». */
        data object Held : Act
        /** Протяжка началась; [dx] — сдвиг, накопленный до этого. */
        data class Start(val dx: Float) : Act
        /** Палец сдвинулся на [dx] посреди протяжки. */
        data class Drag(val dx: Float) : Act
        /** Протяжка кончилась (отпустил или жест оборвался). */
        data object End : Act
        /** Тап — спрятать или показать колонку. */
        data object Toggle : Act
    }

    private enum class Phase { DOWN, HELD, DRAG, GONE }

    private var phase = Phase.DOWN
    private var sx = 0f
    private var sy = 0f

    /** Идёт протяжка: движение пальца — наше, другим его не отдавать. */
    val dragging: Boolean get() = phase == Phase.DRAG

    /** Ждём ли ещё долгое нажатие (палец стоит, протяжки и удержания не было). */
    val waiting: Boolean get() = phase == Phase.DOWN

    /** Отказались от жеста (палец пошёл вниз): дальше — только дождаться, пока отпустят. */
    val gone: Boolean get() = phase == Phase.GONE

    fun move(dx: Float, dy: Float): Act = when (phase) {
        Phase.DOWN -> {
            sx += dx
            sy += dy
            when {
                abs(sx) > slop && abs(sx) >= abs(sy) -> { phase = Phase.DRAG; Act.Start(sx) }
                abs(sy) > slop -> { phase = Phase.GONE; Act.None }
                else -> Act.None
            }
        }
        Phase.HELD -> {
            sx += dx
            sy += dy
            if (hypot(sx, sy) > heldSlop) { phase = Phase.DRAG; Act.Start(sx) } else Act.None
        }
        Phase.DRAG -> Act.Drag(dx)
        Phase.GONE -> Act.None
    }

    /** Время долгого нажатия вышло, а палец стоит. */
    fun longPress(): Act = if (phase == Phase.DOWN) { phase = Phase.HELD; sx = 0f; sy = 0f; Act.Held } else Act.None

    /**
     * Отпустил. [longHeld] — палец пролежал дольше долгого нажатия: это удержание, а не тап, даже если таймер
     * «держу» не успел сработать (палец стоял неподвижно — событий движения не было, а время вышло).
     */
    fun up(longHeld: Boolean = false): Act = when (phase) {
        Phase.DRAG -> Act.End
        Phase.DOWN -> if (longHeld) Act.None else Act.Toggle
        Phase.HELD, Phase.GONE -> Act.None
    }.also { phase = Phase.GONE }

    fun cancel(): Act = (if (phase == Phase.DRAG) Act.End else Act.None).also { phase = Phase.GONE }

    companion object {
        /** Порог сдвига до протяжки, dp — как у системы (`touchSlop` ≈ 8 dp). */
        const val SLOP = 8f
        /** После удержания хватает малого сдвига: палец уже сказал «тяну». */
        const val HELD_SLOP = 2f
    }
}
