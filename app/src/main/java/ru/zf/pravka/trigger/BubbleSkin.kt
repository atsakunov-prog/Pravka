package ru.zf.pravka.trigger

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import ru.zf.pravka.core.DiskLook

/**
 * Шкурка стекла: выпуклая клавиша вместо плоского кружка и такая же плашка
 * вместо плоского прямоугольника. Владелец (19.09.2026, ночь): «может, кнопки
 * на нём пореалистичнее?» — и следом: «кнопки очень красивые. Сделаешь тогда
 * их характеристики и у плашек».
 *
 * Свет — сверху, как у тени под стеклом диска: блик у верхнего края, затенение
 * у нижнего, по кромке тонкая фаска — светлая сверху, тёмная снизу. Три слоя
 * поверх заливки, ни одного лишнего окна и ни одного нового поля состояния.
 *
 * Круг и плашка светятся по-разному, и это не лень, а форма. У кружка блик
 * радиальный: центр градиента вынесен ЗА край, поэтому ярче всего оказывается
 * сама кромка, а не пятно посередине — пятно читалось бы как стеклянный шарик,
 * кромка как клавиша. У плашки блик продольный: она широкая, и радиальное
 * пятно на ней выглядело бы фонарём, а ровная полоса сверху — стеклом.
 *
 * Почему наследник `GradientDrawable`, а не свой `Drawable`: четыре
 * контроллера кнопок держат заливку полем `GradientDrawable?` и красят её
 * `setColor()` на каждое состояние (пишу · слушаю · напоминаю), а плашки
 * собираются тем же `.apply { cornerRadius = …; setColor(…) }`. Наследник
 * встаёт на то же место и слышит те же вызовы — объём получают все состояния
 * разом, и ни одному контроллеру не пришлось переучиваться. Форму он тоже
 * берёт у себя же (`shape`, `cornerRadius`), поэтому заводское — прямоугольник,
 * как у родителя, а кружки просят `shape = OVAL` явно.
 *
 * Тень наружу ни кнопка, ни плашка отбросить не могут: окно ровно по ним, и
 * всё, что вылезет за края, обрежется. Поэтому объём — целиком внутри фигуры;
 * тень под диском рисует стекло (`DiskController.PlateView`), у него окно шире.
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
        /** Толщина фаски — доля половины меньшей стороны: на любом размере одна. */
        private const val RIM_WIDTH = 0.055f
        /** Во сколько раз тусклее блик у нажатой кнопки. */
        private const val PRESSED_LIGHT = 0.3f
        /**
         * У плашки блик занимает верхнюю треть, а затенение — нижнюю пятую:
         * она низкая и широкая, и растяни их на всю высоту — получится не
         * стекло, а градиентная заливка.
         */
        private const val PILL_SHEEN_SPAN = 0.34f
        private const val PILL_FOOT_SPAN = 0.2f
        /**
         * Низ плашки СВЕТЛЕЕ, чем низ кружка, и это правило, а не вкус.
         * Владелец (20.09.2026): «плашки с текстом классно выглядят
         * объёмными. Только вот нижняя часть всё равно чуть выглядит
         * грязноватой». У кружка затенение снизу — это уход поверхности от
         * света, оно короткое и скруглённое. У плашки та же плотность ложится
         * ровной полосой во всю ширину под текстом — и полоса читается не
         * тенью, а налётом. Поэтому у неё своя, вдвое меньшая, и фаска снизу
         * тоже мягче.
         */
        private const val PILL_FOOT = 0.1f
        private const val PILL_RIM_SHADE = 0.12f
    }

    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val face = RectF()

    // Под что построены краски: размер и форма. Меняется редко — на смену
    // размера кнопки или на первую компоновку плашки.
    private var builtW = 0f
    private var builtH = 0f
    private var builtOval = false

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

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val oval = shape == GradientDrawable.OVAL
        if (builtW != w || builtH != h || builtOval != oval) {
            builtW = w
            builtH = h
            builtOval = oval
            build(w, h, oval)
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
        drawFace(canvas, w, h, oval, 0f, foot)
        drawFace(canvas, w, h, oval, 0f, sheen)
        drawFace(canvas, w, h, oval, rim.strokeWidth / 2f, rim)
        canvas.restoreToCount(save)
    }

    /** Фигура вокруг нуля, поджатая на [inset] со всех сторон: кружок или плашка. */
    private fun drawFace(canvas: Canvas, w: Float, h: Float, oval: Boolean, inset: Float, paint: Paint) {
        face.set(-w / 2f + inset, -h / 2f + inset, w / 2f - inset, h / 2f - inset)
        if (face.isEmpty) return
        if (oval) {
            canvas.drawOval(face, paint)
        } else {
            // Радиус скругления — свой же, поджатый вместе с фигурой: иначе
            // фаска срезала бы углы плашки не там, где её край.
            val r = (cornerRadius - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(face, r, r, paint)
        }
    }

    private fun build(w: Float, h: Float, oval: Boolean) {
        val half = minOf(w, h) / 2f
        val top = -h / 2f
        val bottom = h / 2f
        if (oval) {
            sheen.shader = RadialGradient(
                0f, -half * 0.55f, half * 1.25f,
                intArrayOf(DiskLook.white(SHEEN), DiskLook.white(SHEEN * 0.35f), DiskLook.white(0f)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            foot.shader = RadialGradient(
                0f, half * 0.9f, half * 1.15f,
                intArrayOf(DiskLook.black(FOOT), DiskLook.black(FOOT * 0.3f), DiskLook.black(0f)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else {
            sheen.shader = LinearGradient(
                0f, top, 0f, top + h * PILL_SHEEN_SPAN,
                intArrayOf(DiskLook.white(SHEEN), DiskLook.white(SHEEN * 0.3f), DiskLook.white(0f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            foot.shader = LinearGradient(
                0f, bottom, 0f, bottom - h * PILL_FOOT_SPAN,
                intArrayOf(DiskLook.black(PILL_FOOT), DiskLook.black(0f)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        // Фаска у обеих форм одна: тонкий штрих с продольным градиентом —
        // светлый сверху, тёмный снизу, посередине его нет. Темнота низа у
        // плашки своя: см. PILL_RIM_SHADE.
        rim.strokeWidth = (half * RIM_WIDTH).coerceAtLeast(1f)
        rim.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                DiskLook.white(RIM_LIGHT),
                DiskLook.white(0f),
                DiskLook.black(if (oval) RIM_SHADE else PILL_RIM_SHADE),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
}
