package ru.zf.pravka.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Значки приложения — один штрих на всё (24.09.2026, «второе издание»).
//
// Почему свои, а не Material. Пять значков режимов (`res/drawable/ic_mode_*`)
// нарисованы линией 2dp с круглыми концами — перо, часы, галочка, гантеля,
// вилка. Контуры Material рядом с ними читались бы чужим почерком: у них
// заливки, прямые концы и другая толщина. Поэтому весь набор продолжает штрих
// режимов, а пути пишутся строкой SVG — так их можно сверить глазами с
// макетом и дорисовать новый, не заводя XML на каждый.
//
// Почему не material-icons-extended: APK собирается без R8, и десятки
// мегабайт ради трёх десятков значков не окупаются (тот же довод, что в
// Слушалке).
//
// Правило: одно действие — один значок везде. «Голосом» — всегда микрофон,
// «Спросить Claude» — всегда искры, «Поправить» — всегда карандаш. Новое
// действие сначала ищет себе значок здесь и только потом рисует новый.

/** Окружность строкой пути: у `addPathNodes` нет `<circle>`. */
private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

/** Прямоугольник со скруглением строкой пути. */
private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float): String =
    "M${x + rx},${y}h${w - 2 * rx}a$rx,$rx 0 0,1 $rx,$rx" +
        "v${h - 2 * rx}a$rx,$rx 0 0,1 ${-rx},$rx" +
        "h${-(w - 2 * rx)}a$rx,$rx 0 0,1 ${-rx},${-rx}" +
        "v${-(h - 2 * rx)}a$rx,$rx 0 0,1 $rx,${-rx}z"

/**
 * Значок из путей: штрих 2 из 24, круглые концы и стыки, без заливки. Цвет
 * здесь чёрный, настоящий даёт `Icon(tint = …)` — он красит весь вектор.
 */
private fun glyph(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = "Glyphs.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

object Glyphs {
    // ---- Режимы: те же пути, что в res/drawable/ic_mode_*.xml ----
    val Pravka by lazy { glyph("Pravka", "M20.24,12.24a6,6 0 0,0 -8.49,-8.49L5,10.5V19h8.5z", "M16,8L2,22", "M17.5,15H9") }
    val Zasechka by lazy {
        glyph(
            "Zasechka",
            circle(12f, 12f, 7f),
            "M12,9L12,12L13.5,13.5",
            "M16.51,17.35l-0.35,3.83a2,2 0 0,1 -2,1.82H9.83a2,2 0 0,1 -2,-1.82l-0.35,-3.83m0.01,-10.7l0.35,-3.83A2,2 0 0,1 9.83,1h4.35a2,2 0 0,1 2,1.82l0.35,3.83",
        )
    }
    val Delo by lazy { glyph("Delo", "M20,6L9,17L4,12") }
    val Sport by lazy { glyph("Sport", "M6.5,7.5L6.5,16.5M3.5,9.5L3.5,14.5M17.5,7.5L17.5,16.5M20.5,9.5L20.5,14.5M6.5,12L17.5,12") }
    val Food by lazy { glyph("Food", "M5,3L5,8M8,3L8,8M11,3L11,8M5,8a3,3 0 0,0 6,0M8,11L8,21M18,3C15.5,6 14.8,9.5 15.3,13H18ZM18,13L18,21") }
    val Money by lazy { glyph("Money", "M9,20V4h5a4.5,4.5 0,0 1,0 9H6.5M6.5,16.5H14") }

    // ---- Шапка и служебное ----
    /**
     * Статистика — круговая диаграмма с вынутым сектором (24.09.2026).
     * Прежние три палочки на черте владелец назвал ужасными: тонкие линии
     * разной высоты читались гребёнкой, а не графиком.
     */
    val Stats by lazy { glyph("Stats", "M21.21 15.89A10 10 0 1 1 8 2.83", "M22 12A10 10 0 0 0 12 2v10z") }
    val Export by lazy { glyph("Export", "M12,15L12,4M7.5,8.5L12,4L16.5,8.5M4,15v4a2,2 0 0,0 2,2h12a2,2 0 0,0 2,-2v-4") }
    val Gear by lazy {
        glyph(
            "Gear",
            circle(12f, 12f, 3f),
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        )
    }
    val More by lazy { glyph("More", circle(5f, 12f, 1f), circle(12f, 12f, 1f), circle(19f, 12f, 1f)) }
    val Back by lazy { glyph("Back", "M15 18l-6-6 6-6") }
    val Forward by lazy { glyph("Forward", "M9 18l6-6-6-6") }
    val ChevronDown by lazy { glyph("ChevronDown", "M6 9l6 6 6-6") }
    val ChevronUp by lazy { glyph("ChevronUp", "M6 15l6-6 6 6") }
    val Close by lazy { glyph("Close", "M18 6L6 18M6 6l12 12") }
    val Info by lazy { glyph("Info", circle(12f, 12f, 10f), "M12 16v-4M12 8h.01") }

    // ---- Действия ----
    val Mic by lazy { glyph("Mic", "M12,15a3.5,3.5 0 0,0 3.5,-3.5V6.5a3.5,3.5 0 0,0 -7,0v5A3.5,3.5 0 0,0 12,15z", "M5.5,11.5a6.5,6.5 0 0,0 13,0M12,18L12,21M8.5,21L15.5,21") }
    val Send by lazy { glyph("Send", "M22 2L11 13M22 2l-7 20-4-9-9-4z") }
    /** Claude: искры. Всё, что делает модель, — этим значком. */
    val Spark by lazy { glyph("Spark", "M11 3l1.9 5.1L18 10l-5.1 1.9L11 17l-1.9-5.1L4 10l5.1-1.9z", "M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z") }
    /** Спросить: пузырь с вопросом. */
    val Ask by lazy {
        glyph(
            "Ask",
            "M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5z",
            "M9.8 9.5a2.3 2.3 0 0 1 4.4.8c0 1.5-2.2 2-2.2 2M12 15h.01",
        )
    }
    val Timer by lazy { glyph("Timer", circle(12f, 13f, 8f), "M12 9v4l2.5 2.5M9 2h6") }
    val Play by lazy { glyph("Play", circle(12f, 12f, 9f), "M10 8.5l5 3.5-5 3.5z") }
    /** Как делать: раскрытая книжка. */
    val Book by lazy { glyph("Book", "M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5z", "M4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5") }
    val Camera by lazy { glyph("Camera", "M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z", circle(12f, 13f, 4f)) }
    val Image by lazy { glyph("Image", rect(3f, 3f, 18f, 18f, 2f), circle(8.5f, 8.5f, 1.5f), "M21 15l-5-5L5 21") }
    val Barcode by lazy { glyph("Barcode", "M3 5v14M6.5 5v14M10 5v14M13 5v14M17 5v14M21 5v14") }
    val Edit by lazy { glyph("Edit", "M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z") }
    val Delete by lazy { glyph("Delete", "M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2") }
    val Copy by lazy { glyph("Copy", rect(9f, 9f, 13f, 13f, 2f), "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1") }
    val Paste by lazy { glyph("Paste", "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", rect(8f, 2f, 8f, 4f, 1f)) }
    val Share by lazy { glyph("Share", circle(18f, 5f, 3f), circle(6f, 12f, 3f), circle(18f, 19f, 3f), "M8.6 13.5l6.8 4M15.4 6.5l-6.8 4") }
    val Refresh by lazy { glyph("Refresh", "M23 4v6h-6M1 20v-6h6", "M3.5 9a9 9 0 0 1 14.9-3.4L23 10M1 14l4.6 4.4A9 9 0 0 0 20.5 15") }
    /** Заметка, комментарий: пузырь-прямоугольник. */
    val Note by lazy { glyph("Note", "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z") }
    val Stop by lazy { glyph("Stop", rect(6f, 6f, 12f, 12f, 2f)) }
    val Check by lazy { glyph("Check", "M20 6L9 17l-5-5") }
    val Plus by lazy { glyph("Plus", "M12 5v14M5 12h14") }
    val Minus by lazy { glyph("Minus", "M5 12h14") }
    val Heart by lazy { glyph("Heart", "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21l7.8-7.5 1-1.1a5.5 5.5 0 0 0 0-7.8z") }
    val Bell by lazy { glyph("Bell", "M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9", "M13.7 21a2 2 0 0 1-3.4 0") }
    val Place by lazy { glyph("Place", "M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z", circle(12f, 10f, 3f)) }
    val Search by lazy { glyph("Search", circle(11f, 11f, 7.5f), "M21 21l-4.6-4.6") }
    val Tune by lazy { glyph("Tune", "M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6") }
    val Undo by lazy { glyph("Undo", "M9 14L4 9l5-5", "M4 9h11a5 5 0 0 1 0 10h-3") }
    val Download by lazy { glyph("Download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5M12 15V3") }
    val Upload by lazy { glyph("Upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M17 8l-5-5-5 5M12 3v12") }
    val Tag by lazy { glyph("Tag", "M20.6 13.4l-7.2 7.2a2 2 0 0 1-2.8 0L2 12V2h10l8.6 8.6a2 2 0 0 1 0 2.8z", "M7 7h.01") }
    val Calendar by lazy { glyph("Calendar", rect(3f, 4f, 18f, 18f, 2f), "M16 2v4M8 2v4M3 10h18") }

    // ---- Настройки и «Ещё» ----
    val Key by lazy { glyph("Key", circle(7.5f, 15.5f, 4.5f), "M10.7 12.3L21 2M17 6l3 3M14.5 8.5l2.5 2.5") }
    val Link by lazy { glyph("Link", "M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7", "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7") }
    /** Диск кнопок: стекло и четыре кнопки вокруг шестерёнки. */
    val Disk by lazy {
        glyph(
            "Disk",
            circle(12f, 12f, 9.5f), circle(12f, 12f, 1.6f),
            circle(12f, 6.2f, 1.6f), circle(17.8f, 12f, 1.6f), circle(12f, 17.8f, 1.6f), circle(6.2f, 12f, 1.6f),
        )
    }
    val Phone by lazy { glyph("Phone", rect(5f, 2f, 14f, 20f, 2f), "M12 18h.01") }
    val Layers by lazy { glyph("Layers", "M12 2L2 7l10 5 10-5z", "M2 17l10 5 10-5M2 12l10 5 10-5") }
    val Archive by lazy { glyph("Archive", "M21 8v13H3V8", rect(1f, 3f, 22f, 5f, 1f), "M10 12h4") }
    val Palette by lazy { glyph("Palette", rect(3f, 3f, 18f, 18f, 4f), "M3 9h18M9 21V9") }
    /** Словарь: буква «Т» наборщика. */
    val Dictionary by lazy { glyph("Dictionary", "M4 7V4h16v3M9 20h6M12 4v16") }
    /** Промпты: лист с текстом. */
    val Scroll by lazy { glyph("Scroll", "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z", "M14 2v6h6M8 13h8M8 17h5") }
    /** Разборы: полумесяц — они ночные. */
    val Moon by lazy { glyph("Moon", "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z") }
    /** Обучение: шапочка выпускника. */
    val Learn by lazy { glyph("Learn", "M22 10L12 5 2 10l10 5 10-5z", "M6 12v5c3 2 9 2 12 0v-5") }
    val ListLines by lazy { glyph("ListLines", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01") }
    val Activity by lazy { glyph("Activity", "M22 12h-4l-3 9L9 3l-3 12H2") }
    val Nfc by lazy { glyph("Nfc", "M6 8.5a5 5 0 0 1 0 7M9.5 5.5a9.5 9.5 0 0 1 0 13M13 3a13 13 0 0 1 0 18") }
    val Car by lazy { glyph("Car", "M5 17h14M5 17a2 2 0 1 1-4 0v-5l2.5-6h17l2.5 6v5a2 2 0 1 1-4 0", circle(7f, 17f, 2f), circle(17f, 17f, 2f)) }
    val Wifi by lazy { glyph("Wifi", "M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M2 9a15 15 0 0 1 20 0M12 20h.01") }
}
