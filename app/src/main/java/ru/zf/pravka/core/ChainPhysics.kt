package ru.zf.pravka.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Пружина с затуханием для одной координаты — то, на чём кнопки стопки едут
 * за пальцем и друг за другом.
 *
 * Прежняя «резинка» брала 30 % оставшегося пути за кадр: это экспонента без
 * скорости и без памяти. Кнопки трогались все одновременно, тормозили все
 * одинаково и никогда не проскакивали цель — глаз читает это как рывок, а не
 * как движение. Владелец (18.09.2026): «когда перетаскиваешь, они всё ещё не
 * ведут себя как бусы, как-то резко и ненатурально».
 *
 * У пружины есть скорость, поэтому бусина разгоняется, догоняет и чуть
 * проскакивает, а затухание ниже единицы (см. [ChainPhysics]) даёт этот
 * небольшой перелёт. Шаг — полуявный Эйлер с подшагами: при больших dt
 * (пропущенный кадр) явная схема раскачивается, а не гасится.
 *
 * Единицы — пиксели и секунды. Без Android-зависимостей: JVM-тесты гоняют ту
 * же арифметику, что и служба.
 */
class Spring(position: Float, val stiffness: Float, val dampingRatio: Float) {

    var position: Float = position
        private set
    var velocity: Float = 0f
        private set

    /**
     * Поставить на место; [velocity] — с какой скоростью продолжать. Ноль
     * (умолчание) — покой. Диск подхватывает здесь скорость броска: палец
     * отпустил его на ходу, и пружина к щелчку стартует с этой скоростью, а
     * не с нуля — иначе диск замирал бы на миг и лишь потом ехал.
     */
    fun reset(position: Float, velocity: Float = 0f) {
        this.position = position
        this.velocity = velocity
    }

    /**
     * Продолжить движение [other] с той же позиции и скорости: жёсткость
     * поменялась (кнопку стали тянуть с другого конца цепочки), а бусина не
     * должна дёрнуться на месте.
     */
    fun inherit(other: Spring) {
        position = other.position
        velocity = other.velocity
    }

    /**
     * Шаг к [target] за [dtSec]. Возвращает true, когда пружина успокоилась
     * у цели — тогда позиция ставится точно на цель, чтобы не дрожать на
     * долях пикселя.
     */
    fun step(target: Float, dtSec: Float): Boolean {
        var remaining = dtSec.coerceIn(0f, MAX_DT)
        val damping = 2f * dampingRatio * sqrt(stiffness)
        while (remaining > 0f) {
            val dt = min(remaining, SUBSTEP)
            val accel = stiffness * (target - position) - damping * velocity
            velocity += accel * dt
            position += velocity * dt
            remaining -= dt
        }
        val settled = abs(target - position) < REST_DISTANCE && abs(velocity) < REST_SPEED
        if (settled) {
            position = target
            velocity = 0f
        }
        return settled
    }

    companion object {
        /** Кадр длиннее — считаем как 50 мс: догонять пропущенное время незачем. */
        const val MAX_DT = 1f / 20f
        /** Подшаг интегрирования: на 120 Гц полуявный Эйлер устойчив при любой нашей жёсткости. */
        const val SUBSTEP = 1f / 120f
        /** Покой: ближе половины пикселя к цели… */
        const val REST_DISTANCE = 0.5f
        /** …и медленнее пяти пикселей в секунду. */
        const val REST_SPEED = 5f
    }
}

/**
 * Параметры пружин по месту в цепочке. Бусы на нитке: та, что рядом с
 * пальцем, идёт почти вплотную, следующая мягче, хвост — мягче всех и
 * приезжает последним. Так и получается «волна» вместо строя.
 *
 * [link] — сколько звеньев между тянутой кнопкой и этой: 1 — соседняя.
 */
object ChainPhysics {

    /** Жёсткость и относительное затухание для звена [link]. */
    fun forLink(link: Int): Pair<Float, Float> = when {
        link <= 1 -> 380f to 0.74f
        link == 2 -> 240f to 0.70f
        link == 3 -> 160f to 0.66f
        else -> 120f to 0.64f
    }

    fun spring(position: Float, link: Int): Spring {
        val (k, zeta) = forLink(link)
        return Spring(position, k, zeta)
    }
}

/**
 * Пружины диска (`trigger/DiskController.kt`). Диск — одно тело, а не бусы:
 * кнопки на нём едут вместе, и пружина одна на поворот и две на центр.
 * Поворот мягче связки и с чуть большим перелётом — щелчок, а не удар о
 * стенку; переезд к краю — плотнее, докование должно читаться как «встал».
 */
object DiskPhysics {
    const val TURN_STIFFNESS = 220f
    const val TURN_DAMPING = 0.70f
    const val SLIDE_STIFFNESS = 300f
    const val SLIDE_DAMPING = 0.82f

    /**
     * Отрыв от края: кнопку повели вбок, и убранный диск становится диском —
     * выдавленные кнопки садятся в стекло за десятую долю секунды и без
     * перелёта. Обычная пружина вдвигала бы их треть секунды, а палец за это
     * время уводит диск от края, и спина тарелки, ещё не подъехавшей под
     * кнопки, успела бы показаться из-за обреза окна стекла.
     */
    const val QUICK_STIFFNESS = 1600f
    const val QUICK_DAMPING = 1f

    fun turn(position: Float): Spring = Spring(position, TURN_STIFFNESS, TURN_DAMPING)
    fun slide(position: Float): Spring = Spring(position, SLIDE_STIFFNESS, SLIDE_DAMPING)
    fun quick(position: Float): Spring = Spring(position, QUICK_STIFFNESS, QUICK_DAMPING)
}
