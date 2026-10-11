package ru.zf.pravka.trigger

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import ru.zf.pravka.core.ZFace

// Значки идущего дела на кнопке «З» (`core/ZFace.kt`, 11.10.2026).
//
// Кнопки на стекле — всегда наш штрих (владелец, 26.09: «на кнопках мои
// значки мне нравились»): линия 2 из 24, круглые концы, без заливки, бумага
// по янтарю — как у пера, часов и вилки в `res/drawable/ic_mode_*_btn`.
// Пути — строкой SVG, как в `ui/Glyphs.kt`: где пара там уже есть (луна,
// гантеля, вилка, машина, книга, семья, сердце…), путь тот же, чтобы значок
// дела на кнопке и во вкладке был одним знаком. Двадцать XML-файлов ради
// двадцати значков не заводим — их нельзя сверить глазами с `Glyphs.kt`.
object ZFaceGlyphs {

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float): String =
        "M${x + rx},${y}h${w - 2 * rx}a$rx,$rx 0 0,1 $rx,$rx" +
            "v${h - 2 * rx}a$rx,$rx 0 0,1 ${-rx},$rx" +
            "h${-(w - 2 * rx)}a$rx,$rx 0 0,1 ${-rx},${-rx}" +
            "v${-(h - 2 * rx)}a$rx,$rx 0 0,1 $rx,${-rx}z"

    fun paths(icon: ZFace.Icon): List<String> = when (icon) {
        // Луна — `Glyphs.Moon`.
        ZFace.Icon.SLEEP -> listOf("M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
        // Гантеля — `Glyphs.Sport`, она же значок спорта во вкладке.
        ZFace.Icon.STRENGTH -> listOf("M6.5,7.5L6.5,16.5M3.5,9.5L3.5,14.5M17.5,7.5L17.5,16.5M20.5,9.5L20.5,14.5M6.5,12L17.5,12")
        // Бегущий: голова точкой, корпус вперёд, рука назад, нога в толчке.
        ZFace.Icon.RUN -> listOf(circle(13f, 4f, 1f), "M4 17l5 1 .75-1.5", "M15 21v-4l-4-3 1-6", "M7 12V9l5-1 3 3 3 1")
        // Велосипед: два колеса, рама, седок точкой.
        ZFace.Icon.BIKE -> listOf(circle(5f, 18f, 3f), circle(19f, 18f, 3f), "M12 19v-4l-3-3 5-4 2 3h3", circle(17f, 5f, 1f))
        // Прочий спорт — пульс (`Glyphs.Activity`).
        ZFace.Icon.SPORT -> listOf("M22 12h-4l-3 9L9 3l-3 12H2")
        // Пешком: тот же человечек, что у бега, но шагом.
        ZFace.Icon.WALK -> listOf(circle(13f, 4f, 1f), "M7 21l3-4", "M16 21l-2-4-3-3 1-6", "M6 12l2-3 4-1 3 3 3 1")
        // Транспорт — машина сбоку: кузов с кабиной и два колеса. `Glyphs.Car`
        // в кружке кнопки читался мыльницей — без кабины не видно, что это машина.
        ZFace.Icon.TRANSPORT -> listOf(
            "M4.5 17H2v-4a1.5 1.5 0 0 1 1.5-1.5H5L7.5 7h8l3.5 4.5h1.5A1.5 1.5 0 0 1 22 13v4h-2.5",
            "M8.5 17h7", "M5 11.5h14",
            circle(6.5f, 17f, 2f), circle(17.5f, 17f, 2f),
        )
        // Еда — вилка и нож `Glyphs.Food` (кнопка Еды в режиме значков).
        ZFace.Icon.FOOD -> listOf("M5,3L5,8M8,3L8,8M11,3L11,8M5,8a3,3 0 0,0 6,0M8,11L8,21M18,3C15.5,6 14.8,9.5 15.3,13H18ZM18,13L18,21")
        // Быт — дом.
        ZFace.Icon.HOME -> listOf("M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M9 22V12h6v10")
        // Систематизация — ползунки (`Glyphs.Tune`): настройка, сборка Правки.
        // Первым заходом были слои; владелец выбрал ползунки (11.10.2026).
        ZFace.Icon.ORDER -> listOf("M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6")
        // Семья — `Glyphs.Family`.
        ZFace.Icon.FAMILY -> listOf("M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", circle(9f, 7f, 4f), "M23 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75")
        // Социальное — облачко разговора: друзья, встречи, переписка. Людей
        // здесь не рисуем: рядом с «Семьёй» два человечка читались бы одним знаком.
        ZFace.Icon.SOCIAL -> listOf("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")
        // Привлечение — рупор: маркетинг, контент, продажи.
        ZFace.Icon.LEADS -> listOf("M3 11l18-5v12L3 14v-3z", "M11.6 16.8a3 3 0 1 1-5.8-1.6")
        // Текущая работа — портфель.
        ZFace.Icon.WORK -> listOf(rect(2f, 7f, 20f, 14f, 2f), "M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16")
        // Планирование — календарь (`Glyphs.Calendar`).
        ZFace.Icon.PLAN -> listOf(rect(3f, 4f, 18f, 18f, 2f), "M16 2v4M8 2v4M3 10h18")
        // Звонки — трубка.
        ZFace.Icon.CALL -> listOf(
            "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 " +
                "19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 " +
                "2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 " +
                "12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z",
        )
        // Чтение — `Glyphs.Book`.
        ZFace.Icon.READ -> listOf("M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5z", "M4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5")
        // Учёба — шапочка (`Glyphs.Learn`).
        ZFace.Icon.STUDY -> listOf("M22 10L12 5 2 10l10 5 10-5z", "M6 12v5c3 2 9 2 12 0v-5")
        // Близость — сердце (`Glyphs.Heart`): кнопка на виду, знак сдержанный.
        ZFace.Icon.LOVE -> listOf("M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21l7.8-7.5 1-1.1a5.5 5.5 0 0 0 0-7.8z")
        // Отдых — чашка кофе с паром (владелец, 11.10.2026; первым был телевизор).
        ZFace.Icon.REST -> listOf("M18 8h1a4 4 0 0 1 0 8h-1", "M2 8h16v9a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4V8z", "M6 1v3", "M10 1v3", "M14 1v3")
        // Потери — «Zzz»: залип, выпал (владелец, 11.10.2026; первыми были
        // песочные часы). Сон — луна, так что спутать их на кнопке нельзя.
        ZFace.Icon.LOSS -> listOf("M4 7h5l-5 6h5", "M13 11h4l-4 5h4", "M17 3h4l-4 4h4")
        // Не размечено (заполнитель дыры) — код «‹ ›»: время, которое ещё не
        // разобрано (владелец, 11.10.2026; до того у дыры была буква «З»).
        ZFace.Icon.UNMARKED -> listOf("M16 18l6-6-6-6", "M8 6l-6 6 6 6")
    }

    /**
     * Значок для `ImageView` кнопки. [scale] — доля кружка, как у глифов
     * режимов (`_btn` — 52%): во всю ширину кнопки пиктограмма смотрелась
     * ужасно (скрин владельца), отступ тот же, что у букв.
     */
    fun drawable(icon: ZFace.Icon, color: Int, scale: Float = 0.52f): Drawable =
        StrokeGlyph(paths(icon), color, scale)

    /**
     * Пути штрихом 2 из 24, вписанные в рамку по центру. Своего размера нет:
     * `ImageView` растягивает такой значок на всё поле, и место решает [scale],
     * как группа со scale 0.52 в векторе `_btn`.
     */
    private class StrokeGlyph(d: List<String>, color: Int, private val scale: Float) : Drawable() {
        private val parsed: List<Path> = d.map { PathParser.createPathFromPathData(it) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val k = minOf(b.width(), b.height()) * scale / 24f
            if (k <= 0f) return
            canvas.save()
            canvas.translate(b.exactCenterX() - 12f * k, b.exactCenterY() - 12f * k)
            canvas.scale(k, k)
            for (p in parsed) canvas.drawPath(p, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
