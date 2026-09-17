package ru.zf.pravka.trigger

import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.roundToInt
import ru.zf.pravka.core.ChainPhysics
import ru.zf.pravka.core.Spring

/**
 * Живость кнопок: как кнопка отвечает на палец и как едет за соседкой.
 * Владелец (18.09.2026): «давай добавим веселья: когда нажимаются, пускай
 * сжимаются или как-то увеличиваются; когда перетаскиваешь — всё ещё не ведут
 * себя как бусы, резко и ненатурально».
 *
 * Одно место на четыре кнопки, ручку и шестерёнку: прежний цикл догонялки был
 * скопирован в четыре контроллера, и поправить «резину» значило бы поправить
 * четыре раза.
 */
object BubbleMotion {

    /**
     * Кнопка под пальцем СЖИМАЕТСЯ, а не растёт, и это не вкус, а геометрия:
     * оверлейное окно ровно с кнопку, и всё, что вылезло за 1,0, окно
     * отрезает — круг стал бы квадратом по краям. Внутрь места сколько
     * угодно. Отпущенная возвращается с лёгким перелётом: OvershootInterpolator
     * с натяжением 1,2 даёт пик около 5 % от пройденного, то есть на
     * 0,12 хода — полпроцента за единицу; окно этого не отрезает заметно.
     */
    const val PRESSED = 0.88f
    /** Пока тащат — чуть поджата: «держу». */
    const val LIFTED = 0.94f

    fun press(v: View) {
        v.animate().cancel()
        v.animate().scaleX(PRESSED).scaleY(PRESSED)
            .setDuration(80).setInterpolator(DecelerateInterpolator()).start()
    }

    /** Палец поехал — из сжатой в чуть поджатую, без рывка. */
    fun lift(v: View) {
        v.animate().cancel()
        v.animate().scaleX(LIFTED).scaleY(LIFTED).setDuration(120).start()
    }

    fun release(v: View) {
        v.animate().cancel()
        v.animate().scaleX(1f).scaleY(1f)
            .setDuration(220).setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    /** Длинное нажатие сработало — кнопка коротко «кивает»: распрямляется под пальцем. */
    fun nod(v: View) {
        v.animate().cancel()
        v.animate().scaleX(1f).scaleY(1f).setDuration(160)
            .setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    /**
     * Кружок веера выскакивает: из точки — с перелётом — в свой размер, с
     * задержкой [delayMs] (кружки идут волной от шестерёнки). Начальное
     * состояние ставится сразу, чтобы первый кадр не мигнул готовым кружком.
     *
     * Страховка на случай, о котором в `docs/agreements.md` написано отдельно:
     * анимация на view, ещё не прошедшем компоновку, может не стартовать —
     * тогда через срок кружок просто ставится в конечное состояние руками.
     * Невидимый переключатель хуже непрыгнувшего.
     */
    fun pop(v: View, delayMs: Long, alpha: Float) {
        v.scaleX = 0.3f
        v.scaleY = 0.3f
        v.alpha = 0f
        v.post {
            v.animate().cancel()
            v.animate().scaleX(1f).scaleY(1f).alpha(alpha)
                .setStartDelay(delayMs).setDuration(260)
                .setInterpolator(OvershootInterpolator(1.8f)).start()
        }
        v.postDelayed({
            if (v.alpha < alpha - 0.05f || v.scaleX < 0.95f) {
                v.animate().cancel()
                v.scaleX = 1f
                v.scaleY = 1f
                v.alpha = alpha
            }
        }, delayMs + 400)
    }

}

/**
 * Догоняющая часть кнопки: две пружины ([Spring] по x и y) и кадровый цикл
 * на Choreographer. Контроллер говорит, откуда, куда и каким звеном цепочки
 * ехать; следопыт отдаёт ему координаты каждого кадра через [apply] и один
 * раз — [onSettled], когда приехал.
 *
 * Choreographer, а не `view.postDelayed(16)`: тот тикал не по кадрам, а
 * по таймеру, и часть шагов попадала между кадрами — отсюда часть «резкости».
 * Шаг физики берёт настоящий dt между кадрами, поэтому пропуск кадра не
 * ускоряет и не замедляет бусину.
 */
class ChainFollower(
    private val apply: (x: Int, y: Int) -> Unit,
    private val onSettled: (settle: Boolean) -> Unit,
) {
    private var sx: Spring? = null
    private var sy: Spring? = null
    private var link = 0
    private var targetX = 0f
    private var targetY = 0f
    private var settle = false
    private var running = false
    private var lastFrameNs = 0L

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val x = sx ?: return
            val y = sy ?: return
            val dt = if (lastFrameNs == 0L) 1f / 60f else (frameTimeNanos - lastFrameNs) / 1_000_000_000f
            lastFrameNs = frameTimeNanos
            val doneX = x.step(targetX, dt)
            val doneY = y.step(targetY, dt)
            apply(x.position.roundToInt(), y.position.roundToInt())
            if (doneX && doneY) {
                running = false
                onSettled(settle)
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    val active: Boolean get() = running

    /**
     * Ехать из ([fromX], [fromY]) — где кнопка сейчас — к цели звеном [link].
     * Пока цикл уже идёт, «откуда» не нужно: пружина помнит и позицию, и
     * скорость; поменялось звено — новая пружина наследует движение старой.
     */
    fun follow(fromX: Int, fromY: Int, toX: Int, toY: Int, link: Int, settle: Boolean) {
        targetX = toX.toFloat()
        targetY = toY.toFloat()
        this.settle = settle
        val cx = sx
        val cy = sy
        if (cx == null || cy == null || !running) {
            sx = ChainPhysics.spring(fromX.toFloat(), link)
            sy = ChainPhysics.spring(fromY.toFloat(), link)
            this.link = link
        } else if (link != this.link) {
            sx = ChainPhysics.spring(0f, link).also { it.inherit(cx) }
            sy = ChainPhysics.spring(0f, link).also { it.inherit(cy) }
            this.link = link
        }
        if (!running) {
            running = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    /** Остановить, где есть: следующий follow начнёт с места, которое назовёт контроллер. */
    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }
}
