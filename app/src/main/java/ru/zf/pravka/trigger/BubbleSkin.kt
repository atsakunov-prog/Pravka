package ru.zf.pravka.trigger

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import ru.zf.pravka.core.DiskLook

/**
 * Шкурка плавающей кнопки: выпуклая клавиша вместо плоского кружка. Владелец
 * (19.09.2026, ночь): «может, кнопки на нём пореалистичнее?»
 *
 * Свет — сверху, как у тени под стеклом диска: блик у верхнего края, затенение
 * у нижнего, по кромке тонкая фаска — светлая сверху, тёмная снизу. Три слоя
 * поверх заливки, ни одного лишнего окна и ни одного нового поля состояния.
 *
 * Почему наследник `GradientDrawable`, а не свой `Drawable`: четыре
 * контроллера кнопок держат заливку полем `GradientDrawable?` и красят её
 * `setColor()` на каждое состояние (пишу · слушаю · напоминаю). Наследник
 * встаёт на то же поле и слышит те же вызовы — объём получают все состояния
 * разом, и ни одному контроллеру не пришлось переучиваться.
 *
 * Тень наружу кнопка отбросить не может: её окно ровно по кружку, и всё, что
 * вылезет за края, обрежется. Поэтому объём — целиком внутри круга; тень под
 * диском рисует стекло (`DiskController.PlateView`), у него окно шире.
 */
class BubbleSkin : GradientDrawable() {

    private companion object {
        /** Блик: ярче всего у верхнего края, к низу сходит в ноль. */
        private const val SHEEN = 0.26f
        /** Затенение у нижнего края — вторая половина объёма. */
        private const val FOOT = 0.20f
        /** Фаска по кромке: светлая дуга сверху, тёмная снизу. */
        private const val RIM_LIGHT = 0.34f
        private const val RIM_SHADE = 0.22f
        /** Толщина фаски — доля радиуса: на кнопке любого размера одна и та же. */
        private const val RIM_WIDTH = 0.055f
        /** Во сколько раз тусклее блик у нажатой кнопки. */
        private const val PRESSED_LIGHT = 0.3f
    }

    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Радиус, под который построены краски: меняется только с размером кнопки. */
    private var builtFor = 0f

    /**
     * Кнопка под пальцем: блик и фаска гаснут, затенение снизу остаётся —
     * клавиша ушла вниз, и свет с неё соскользнул. Ставит это `BubbleMotion`,
     * одно место на все кнопки, там же, где живёт сжатие: палец и так видит
     * ответ размером, а теперь ещё и светом.
     */
    var pressed = false
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    init {
        shape = GradientDrawable.OVAL
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        if (r <= 0f) return
        if (builtFor != r) {
            builtFor = r
            build(r)
        }
        // Прозрачность самого рисунка (не вида): краска с шейдером умножает
        // его цвета на свою альфу, так что слои гаснут вместе с заливкой.
        val a = alpha
        val lit = if (pressed) (a * PRESSED_LIGHT).toInt() else a
        sheen.alpha = lit
        foot.alpha = a
        rim.alpha = lit
        val save = canvas.save()
        // Рисуем от центра: слои строятся вокруг нуля и центрируются по
        // построению — тот же приём, что у глифов стопки.
        canvas.translate(b.exactCenterX(), b.exactCenterY())
        canvas.drawCircle(0f, 0f, r, foot)
        canvas.drawCircle(0f, 0f, r, sheen)
        canvas.drawCircle(0f, 0f, r - rim.strokeWidth / 2f, rim)
        canvas.restoreToCount(save)
    }

    /**
     * Центры бликовых градиентов — ЗА краем кружка: так внутри него ярче
     * всего оказывается сама кромка, а не пятно посередине. Пятно посередине
     * читалось бы как стеклянный шарик, кромка — как клавиша.
     */
    private fun build(r: Float) {
        sheen.shader = RadialGradient(
            0f, -r * 0.55f, r * 1.25f,
            intArrayOf(DiskLook.white(SHEEN), DiskLook.white(SHEEN * 0.35f), DiskLook.white(0f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        foot.shader = RadialGradient(
            0f, r * 0.9f, r * 1.15f,
            intArrayOf(DiskLook.black(FOOT), DiskLook.black(FOOT * 0.3f), DiskLook.black(0f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        rim.strokeWidth = (r * RIM_WIDTH).coerceAtLeast(1f)
        rim.shader = LinearGradient(
            0f, -r, 0f, r,
            intArrayOf(DiskLook.white(RIM_LIGHT), DiskLook.white(0f), DiskLook.black(RIM_SHADE)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
}
