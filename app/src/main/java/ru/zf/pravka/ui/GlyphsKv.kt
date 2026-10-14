package ru.zf.pravka.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Значки вида v2 — общий набор Ковчега (13.10.2026, план §2): одна линия 1,75,
// скруглённые концы, 24×24, цвет — от текста. Пути — слово в слово `ICONS` веба
// Дел (`server/pravka_dela/static/app.js`), а тот — копия общего слоя
// остальных сервисов: значок «Дела» в меню телефона и в шапке веба — один и
// тот же. Своего пути у значка нет — `Glyphs` рисует наш штрих той же толщины.

object KvGlyphs {

    /** Пути набора: имя — как в вебе. */
    private val PATHS: Map<String, String> = mapOf(
        "home" to "M3 10.5L12 3l9 7.5M5 9.5V20h5v-6h4v6h5V9.5",
        "dela" to "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM8.5 12.5l2.5 2.5 5-5.5",
        "mic" to "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3",
        "wallet" to "M19 7V5a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a2 2 0 0 1-2-2V6M16.5 14h.01",
        "kids" to "M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM4 10h16M8 3v4M16 3v4",
        "calendar" to "M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM4 10h16M8 3v4M16 3v4",
        "book" to "M12 6.5C10 5 7 4.5 3 5v13c4-.5 7 0 9 1.5 2-1.5 5-2 9-1.5V5c-4-.5-7 0-9 1.5zM12 6.5V19",
        "film" to "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM8 4v16M16 4v16M4 9h4M4 15h4M16 9h4M16 15h4",
        "candles" to "M6.5 11h3v10h-3zM14.5 11h3v10h-3zM8 8.5c-1.2-1-1.2-2.4 0-4 1.2 1.6 1.2 3 0 4zM16 8.5c-1.2-1-1.2-2.4 0-4 1.2 1.6 1.2 3 0 4zM4 21h16",
        "shield" to "M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z",
        "digest" to "M5 4h14a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9l-5 4V5a1 1 0 0 1 1-1zM8 8.5h8M8 12h5",
        "archive" to "M3 4h18v4H3zM5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8M10 12h4",
        "copies" to "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3",
        "cloud" to "M7 18h10a4 4 0 0 0 .6-7.95A6 6 0 0 0 6.2 9.1 4.5 4.5 0 0 0 7 18z",
        "globe" to "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM3 12h18M12 3c2.5 2.6 3.7 5.6 3.7 9s-1.2 6.4-3.7 9M12 3c-2.5 2.6-3.7 5.6-3.7 9s1.2 6.4 3.7 9",
        "monitor" to "M4 4h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM8 20h8M12 16v4",
        "sun" to "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
        "moon" to "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z",
        "logout" to "M14 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4M10 8l-4 4 4 4M6 12h10",
        "search" to "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4",
        "inbox" to "M4 13l2.5-7.5h11L20 13v6H4zM4 13h5l1.5 2.5h3L15 13h5",
        "list" to "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01",
        "target" to "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 11.5v1",
        "bookmark" to "M6 3h12v18l-6-4-6 4z",
        "funnel" to "M4 5h16l-6 7.5V19l-4 2v-8.5z",
        "briefcase" to "M4 8h16v11H4zM9 8V5h6v3M4 13h16",
        "users" to "M9 4a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7zM2.5 20a6.5 6.5 0 0 1 13 0M15.5 4.2a3.5 3.5 0 0 1 0 6.6M18 14.2a6.5 6.5 0 0 1 3.5 5.8",
        "link" to "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
        "user" to "M12 3.5a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM4.5 20.5a7.5 7.5 0 0 1 15 0",
        "reply" to "M9 14L4 9l5-5M4 9h10a6 6 0 0 1 6 6v5",
        "play" to "M7 4.5l12 7.5-12 7.5z",
        // Пауза плеера тейков — пара к «play» тем же штрихом (у веба своей нет: там плеера нет).
        "pause" to "M8 5h3v14H8zM13 5h3v14h-3z",
        "eye" to "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
        "plus" to "M12 5v14M5 12h14",
        "check" to "M5 12.5l4.5 4.5L19 7.5",
        "x" to "M6 6l12 12M18 6L6 18",
        "edit" to "M4 20h4L19 9l-4-4L4 16zM14 6l4 4",
        "trash" to "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3",
        "refresh" to "M20 11a8 8 0 0 0-14.6-4.5L4 8M4 4v4h4M4 13a8 8 0 0 0 14.6 4.5L20 16M20 20v-4h-4",
        "settings" to "M4 7h10M18 7h2M4 17h4M12 17h8M16 5v4M10 15v4",
        "clock" to "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2",
        "alert" to "M12 4l9 16H3zM12 10v4M12 17h.01",
        "info" to "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v6M12 7.5h.01",
        "chart" to "M4 20V4M4 20h16M8 16v-5M12 16V8M16 16v-3",
        "send" to "M4 12l16-8-6 16-3-7z",
        "folder" to "M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z",
        "arrowLeft" to "M19 12H5M11 6l-6 6 6 6",
        "chevron" to "M9 6l6 6-6 6",
        "coin" to "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM10 7.5h3.2a2.3 2.3 0 0 1 0 4.6H10M10 7.5v9M8.5 14.3h4.5",
        // Траты Claude в долларах (14.10.2026): «coin» — рубль, а Claude платится в $. Тот же путь — в ICONS веба Дел.
        "usd" to "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM14.6 9.2c-.4-1-1.4-1.7-2.6-1.7c-1.6 0-2.7.9-2.7 2.1c0 2.9 5.6 1.7 5.6 4.6c0 1.3-1.2 2.2-2.9 2.2c-1.3 0-2.4-.7-2.8-1.7M12 6v1.5M12 16.4V18",
        "car" to "M4 16v-4.5l2.2-5a1.5 1.5 0 0 1 1.4-1h8.8a1.5 1.5 0 0 1 1.4 1l2.2 5V16M3 16h18v2.5H3zM6.5 18.5V20M17.5 18.5V20M4 11.5h16M7 14h.01M17 14h.01",
        "chevronDown" to "M6 9l6 6 6-6",
        "copy" to "M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1zM5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1",
        "download" to "M12 4v11M7 10l5 5 5-5M5 20h14",
        "upload" to "M12 20V9M7 14l5-5 5 5M5 4h14",
        "flow" to "M4 8h15M15 4l4 4-4 4M20 16H5M9 12l-4 4 4 4",
        "headphones" to "M4 15v-3a8 8 0 0 1 16 0v3M4 15h3v5H5a1 1 0 0 1-1-1zM20 15h-3v5h2a1 1 0 0 0 1-1z",
        "magen" to "M12 3.5l7.4 12.75H4.6zM12 20.5L4.6 7.75h14.8z",
        "minus" to "M5 12h14",
        "more" to "M5 12h.01M12 12h.01M19 12h.01",
        "phone" to "M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2",
        "claude" to "M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6L5.6 18.4",
        "activity" to "M3 12h4l3-8 4 16 3-8h4",
        "food" to "M12 5a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM3 3v5a1.5 1.5 0 0 0 1.5 1.5h0A1.5 1.5 0 0 0 6 8V3M4.5 9.5V21M20.5 3c-1.4 1-2 2.6-2 4.5V12h2V21",
        "print" to "M7 9V4h10v5M7 17H5a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-2M7 14h10v6H7z",
        "server" to "M4 4h16v6H4zM4 14h16v6H4zM8 7h.01M8 17h.01",
        "smile" to "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM8.5 14.5a4.5 4.5 0 0 0 7 0M9 9.5h.01M15 9.5h.01",
        "up" to "M12 19V5.5M6 11l6-6 6 6",
        "stop" to "M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z",
        "down" to "M7 10l5 5 5-5",
        "menu" to "M4 7h16M4 12h16M4 17h16",
        "help" to "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17h.01",
        "chat" to "M5 5h14v10H10l-5 4z",
        "flame" to "M12 3c1 3 4 4.5 4 8.5a4 4 0 0 1-8 0c0-2 1-3 2-4 0 1.5 1 2.5 2 2.5 0-3-1-5 0-7z",
        "tree" to "M10 3h4v4h-4zM12 7v4M6 11h12M6 11v3M18 11v3M4 14h4v4H4zM16 14h4v4h-4z",
        // Холст «Ковчег на телефоне» (13.10.2026): молния «Сейчас», погода, снимок и галерея — тем же штрихом.
        "bolt" to "M13 3L4 14h7l-1 7 9-11h-7z",
        "rain" to "M7 15h10a4 4 0 0 0 .6-7.95A6 6 0 0 0 6.2 6.1 4.5 4.5 0 0 0 7 15zM9 18l-1 3M13 18l-1 3M17 18l-1 3",
        "image" to "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM8.5 10.5a1.5 1.5 0 1 0 0-3a1.5 1.5 0 0 0 0 3zM21 15l-5-5L5 19",
        "camera" to "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM12 10a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z",
        "gear" to gearPath(),
    )

    /**
     * Значок приложения (`Glyphs.<Имя>`) → значок набора. Чего тут нет, у того
     * в наборе пары не нашлось — `Glyphs` рисует наш штрих той же толщиной 1,75.
     */
    private val FOR_GLYPH: Map<String, String> = mapOf(
        "Pravka" to "edit", "Zasechka" to "clock", "Delo" to "dela", "Sport" to "activity", "Food" to "food",
        "Money" to "wallet", "Stats" to "chart", "Export" to "upload", "Gear" to "gear", "More" to "more",
        "Forward" to "chevron", "ChevronDown" to "chevronDown", "Close" to "x", "Info" to "info", "Mic" to "mic",
        "Send" to "send", "Ask" to "help", "Timer" to "clock", "Bolt" to "bolt", "Play" to "play", "Pause" to "pause", "Book" to "book",
        "Camera" to "camera", "Image" to "image", "Edit" to "edit", "Delete" to "trash", "Copy" to "copy",
        "Refresh" to "refresh", "Note" to "chat", "Stop" to "stop", "Check" to "check", "Plus" to "plus",
        "Minus" to "minus", "Search" to "search", "Tune" to "settings", "Undo" to "reply", "Download" to "download",
        "Upload" to "upload", "Calendar" to "calendar", "Link" to "link", "Cloud" to "cloud", "Phone" to "phone",
        "Archive" to "archive", "Moon" to "moon", "ListLines" to "list", "Activity" to "activity", "Car" to "car",
        "ArrowUp" to "up", "Menu" to "menu", "Dollar" to "coin", "Error" to "alert", "Groups" to "users",
        "Sunny" to "sun", "Rainy" to "rain", "Bedtime" to "moon", "Family" to "users",
    )

    private val cache = HashMap<String, ImageVector>()

    /** Значок набора по имени веба («home», «shield»…); нет такого — null. */
    fun byKey(key: String): ImageVector? {
        val d = PATHS[key] ?: return null
        return synchronized(cache) { cache.getOrPut(key) { build("Kv.$key", d) } }
    }

    /** Пара для значка приложения [glyph] (имя геттера `Glyphs`), если она есть в наборе. */
    fun forGlyph(glyph: String): ImageVector? = FOR_GLYPH[glyph]?.let(::byKey)

    /** Знает ли набор такой значок — для меню: значок из настроек сервера без пары рисуется списком. */
    fun has(key: String): Boolean = key in PATHS

    private fun build(name: String, d: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    /** Толщина линии набора. */
    const val STROKE = 1.75f
}

/** Шестерёнка — восемь зубцов, считается один раз, как в вебе: «солнце» рядом слишком на неё похоже. */
private fun gearPath(): String {
    val sb = StringBuilder()
    val rad = Math.PI / 180
    for (i in 0 until 8) {
        val a = i * 45
        listOf((a - 15) to 7.0, (a - 8) to 9.6, (a + 8) to 9.6, (a + 15) to 7.0).forEachIndexed { j, (deg, r) ->
            sb.append(if (i == 0 && j == 0) "M" else "L")
            sb.append("%.2f %.2f".format(java.util.Locale.ROOT, 12 + r * cos(deg * rad), 12 + r * sin(deg * rad)))
        }
    }
    return sb.append("zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z").toString()
}
