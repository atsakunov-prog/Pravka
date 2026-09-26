package ru.zf.pravka.trigger

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import ru.zf.pravka.core.AutoConfirm

/**
 * Часы плашки с «ОК»: молчание — это «да» ([AutoConfirm], владелец,
 * 26.09.2026: «не нажал ничего = подтвердил»). Одни на «Е» (еда по фото,
 * штрихкоду, переразбор), «Д» и «₽»: плашки у них разные, а правило одно,
 * и ошибиться в одной из копий значило бы снова терять наговоры.
 *
 * Ответ владельца — «ОК», «✕», «открыть во вкладке» — это [clear]: молчать
 * больше нечему. Правка «✎» — [suspend]: окно плашки снято на время ввода,
 * а ответ ещё за ним. Если ввод закроет не он (новая запись, складывание,
 * «спрятать всё»), «да» остаётся в силе, и его забирает [take] у того, кто
 * снимает плашку.
 *
 * Ответ получает `quiet`: true — плашку сняли снаружи, и кнопке не до
 * спиннера и итога в пилюле (она уже пишет новое или экран складывается);
 * тогда хватит тоста.
 */
internal class PlateClock(private val onExpire: () -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var answer: ((quiet: Boolean) -> Unit)? = null
    private var holdMs = 0L
    private var deadline = 0L
    private var label: TextView? = null

    /** Чья плашка ждёт: другая плашка на её месте — для этой молчание. */
    var key: String = ""
        private set

    /** «Запишу сам», «Отправлю сам», «Закрою» — меняется вместе с отметками. */
    var verb: String = ""
        set(value) {
            field = value
            paint()
        }

    /** Плашка снята на время «✎»: ответ ещё не дан. */
    var suspended = false
        private set

    val pending: Boolean get() = answer != null

    fun arm(key: String, holdMs: Long, verb: String, label: TextView, answer: (quiet: Boolean) -> Unit) {
        handler.removeCallbacks(tick)
        this.key = key
        this.holdMs = holdMs
        this.label = label
        this.answer = answer
        suspended = false
        deadline = SystemClock.uptimeMillis() + holdMs
        this.verb = verb
        handler.postDelayed(tick, TICK_MS)
    }

    /** Палец на плашке: он читает или снимает отметки — отсчёт с начала. */
    fun touch() {
        if (answer == null || suspended) return
        deadline = SystemClock.uptimeMillis() + holdMs
        paint()
    }

    fun suspend() {
        if (answer == null) return
        handler.removeCallbacks(tick)
        label = null
        suspended = true
    }

    fun clear() {
        handler.removeCallbacks(tick)
        answer = null
        label = null
        suspended = false
        key = ""
    }

    /** Забрать «да», не исполняя: когда его исполнить — решает тот, кто снимает плашку. */
    fun take(): ((quiet: Boolean) -> Unit)? {
        val a = answer
        clear()
        return a
    }

    private fun paint() {
        label?.text = AutoConfirm.line(verb, deadline - SystemClock.uptimeMillis())
    }

    private val tick = object : Runnable {
        override fun run() {
            if (answer == null || suspended) return
            if (SystemClock.uptimeMillis() >= deadline) {
                onExpire()
                return
            }
            paint()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
