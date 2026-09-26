package ru.zf.pravka.trigger

import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import ru.zf.pravka.core.Countdown

/**
 * Секунды до ответа на занятой кнопке — на месте колеса (`core/Countdown.kt`).
 *
 * Одна штука на кнопку, и видно её только пока кнопка ЗАНЯТА: запрос
 * запустила именно она, а число на свободной кнопке читалось бы как «что-то
 * идёт», хотя кнопка ничего не ждёт. Отсчёт приходит от транспорта всем
 * кнопкам сразу (`PravkaApp.workWatcher`), показывает та, что занята.
 *
 * Колесо и число делят одно место: пока число идёт — колеса нет; срок вышел
 * или отсчёта нет вовсе — колесо, как было. Решает это одно место —
 * [apply], — иначе два хозяина одной видимости спорили бы, и побеждал бы
 * последний.
 */
class ButtonCountdown(private val context: Context) {

    private companion object {
        /** Десятые меняются раз в сто миллисекунд — чаще перерисовывать незачем. */
        const val TICK_MS = 50L
        private val PAPER = 0xFFF7F3EA.toInt()
    }

    private var text: TextView? = null
    private var spinner: View? = null
    private var busy = false
    private var startedAt = 0L
    private var expectMs = 0L

    private val tick = Runnable { apply() }

    /** Встать в кнопку рядом с колесом: по центру, поверх него. */
    fun attach(container: FrameLayout, spinner: View) {
        text?.let { (it.parent as? FrameLayout)?.removeView(it) }
        val t = TextView(context).apply {
            setTextColor(PAPER)
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            // Цифры одной ширины: «9,4» → «9,3» не должно ёрзать по кнопке.
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            includeFontPadding = false
            visibility = View.GONE
        }
        container.addView(
            t,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        text = t
        this.spinner = spinner
        apply()
    }

    fun setBusy(value: Boolean) {
        busy = value
        apply()
    }

    /** Запрос ушёл: ждать [expectMs] по своей истории. */
    fun start(expectMs: Long) {
        startedAt = SystemClock.uptimeMillis()
        this.expectMs = expectMs
        apply()
    }

    /** Ответ пришёл или сорвался. */
    fun stop() {
        startedAt = 0L
        apply()
    }

    private fun apply() {
        val t = text ?: return
        t.removeCallbacks(tick)
        val label = if (busy && startedAt != 0L) {
            Countdown.label(expectMs - (SystemClock.uptimeMillis() - startedAt))
        } else {
            null
        }
        if (label != null) {
            t.text = label
            t.visibility = View.VISIBLE
            spinner?.visibility = View.GONE
            t.postDelayed(tick, TICK_MS)
        } else {
            t.visibility = View.GONE
            spinner?.visibility = if (busy) View.VISIBLE else View.GONE
        }
    }
}
