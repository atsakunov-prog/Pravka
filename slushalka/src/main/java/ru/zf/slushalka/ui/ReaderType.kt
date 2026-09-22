package ru.zf.slushalka.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.border
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.zf.slushalka.R
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.data.readerView
import ru.zf.slushalka.text.Block
import ru.zf.slushalka.text.BookText

// Бумага, шрифты и краска читалки: цвета, гарнитуры, типограф, подсветка в строке.

/** Цвета читалки живут отдельно от темы приложения: их переключают по свету, а не по системе. */
data class ReaderPalette(val bg: Color, val fg: Color, val dim: Color)

/**
 * Бумага «по времени суток»: с семи до семи вечера - обычная, до десяти -
 * сепия, ночью - тёплая тёмная. Читают и днём на свету, и ночью в постели, и
 * переключать руками каждый вечер незачем.
 */
fun themeAt(hour: Int): String = when (hour) {
    in 7 until 19 -> Settings.THEME_PAPER
    in 19 until 22 -> Settings.THEME_SEPIA
    else -> Settings.THEME_WARM
}

fun readerPalette(
    theme: String,
    dark: Boolean,
    hour: Int = java.time.LocalTime.now().hour,
): ReaderPalette = when (theme) {
    Settings.THEME_TIME -> readerPalette(themeAt(hour), dark)
    // Меньше синего к ночи: коричневая бумага и песочная краска, яркость
    // букв приглушена - глаза в темноте не режет.
    Settings.THEME_WARM -> ReaderPalette(Color(0xFF1C1813), Color(0xFFD2C1A1), Color(0xFF8A7B63))
    // Краска, а не чернила: в книге буквы не угольно-чёрные, а тёмно-серые с
    // тёплым уходом - владелец попросил «шрифт как в книге, чуть более серый».
    // Абсолютный чёрный на светлой бумаге к тому же режет глаз на экране.
    // Бумага книжная, а не офисная: тёплый кремовый тон вместо почти белого.
    Settings.THEME_PAPER -> ReaderPalette(Color(0xFFF4EFE4), Color(0xFF2E2A25), Color(0xFF6E6659))
    Settings.THEME_SEPIA -> ReaderPalette(Color(0xFFF3E6CE), Color(0xFF4A3B26), Color(0xFF8A7550))
    Settings.THEME_GREY -> ReaderPalette(Color(0xFF2A2D33), Color(0xFFCBC8C1), Color(0xFF8B8880))
    Settings.THEME_BLACK -> ReaderPalette(Color(0xFF000000), Color(0xFFB6B3AC), Color(0xFF6E6B65))
    // Электронная бумага: чистые белый и чёрный, серый для служебного - тёмный,
    // иначе колонтитул на e-ink выцветает до невидимого.
    Settings.THEME_EINK -> ReaderPalette(Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF3A3A3A))
    Settings.THEME_EINK_NIGHT -> ReaderPalette(Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFC8C8C8))
    else -> if (dark) ReaderPalette(Color(0xFF000000), Color(0xFFB6B3AC), Color(0xFF6E6B65))
    else ReaderPalette(Color(0xFFF4EFE4), Color(0xFF2E2A25), Color(0xFF6E6659))
}

fun fontOf(name: String): FontFamily = when (name) {
    Settings.FONT_SANS -> FontFamily.SansSerif
    Settings.FONT_MONO -> FontFamily.Monospace
    Settings.FONT_SERIF -> FontFamily.Serif
    Settings.FONT_PT_SERIF -> PT_SERIF
    Settings.FONT_LORA -> LORA
    Settings.FONT_MERRIWEATHER -> MERRIWEATHER
    Settings.FONT_BITTER -> BITTER
    Settings.FONT_PT_SANS -> PT_SANS
    // Книжная антиква своей гарнитурой: системная «с засечками» на разных
    // прошивках разворачивается в разное, и у владельца книжный вид рисовался
    // гротеском. Literata нарисована для чтения с экрана, кириллица полная.
    else -> LITERATA
}

// Medium - для режима e-ink: там основной текст набирается на ступень
// жирнее, тонкий штрих на электронной бумаге выцветает. У кого своего
// среднего нет (PT, Merriweather), Android берёт обычный - Merriweather и так
// плотный.
internal val LITERATA = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
    Font(R.font.literata_medium, FontWeight.Medium),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_bold, FontWeight.SemiBold),
    Font(R.font.literata_bold, FontWeight.Bold),
)

internal val PT_SERIF = FontFamily(
    Font(R.font.ptserif_regular, FontWeight.Normal),
    Font(R.font.ptserif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.ptserif_bold, FontWeight.Bold),
)

internal val LORA = FontFamily(
    Font(R.font.lora_regular, FontWeight.Normal),
    Font(R.font.lora_medium, FontWeight.Medium),
    Font(R.font.lora_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.lora_bold, FontWeight.Bold),
)

internal val MERRIWEATHER = FontFamily(
    Font(R.font.merriweather_regular, FontWeight.Normal),
    Font(R.font.merriweather_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.merriweather_bold, FontWeight.Bold),
)

internal val BITTER = FontFamily(
    Font(R.font.bitter_regular, FontWeight.Normal),
    Font(R.font.bitter_medium, FontWeight.Medium),
    Font(R.font.bitter_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.bitter_bold, FontWeight.Bold),
)

internal val PT_SANS = FontFamily(
    Font(R.font.ptsans_regular, FontWeight.Normal),
    Font(R.font.ptsans_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.ptsans_bold, FontWeight.Bold),
)

/**
 * Типограф: то, что в наборе делают руками, а в файле книги обычно не сделано.
 *
 * Неразрывный пробел после коротких слов (предлоги, союзы, частицы) - главное
 * здесь: при выключке по формату «в» или «и» в конце строки сразу видно.
 * Дефис между пробелами - это тире, а не дефис; перед тире тоже неразрывный,
 * иначе оно уезжает в начало строки. Кавычки и сам текст не трогаем: в fb2
 * они обычно уже расставлены, а лезть в текст книги - последнее дело.
 */
fun typograph(text: String): String {
    val nbsp = '\u00A0'
    val dash = '\u2014'
    val sb = StringBuilder(text)
    // 1. Дефис, окружённый пробелами, - это тире, а не дефис. Длина та же.
    for (i in 1 until sb.length - 1) {
        if (sb[i] == '-' && sb[i - 1] == ' ' && sb[i + 1] == ' ') sb[i] = dash
    }
    // 2. Пробел перед тире - неразрывный: тире не должно начинать строку.
    for (i in 1 until sb.length) {
        if (sb[i] == dash && sb[i - 1] == ' ') sb[i - 1] = nbsp
    }
    // Неразрывного пробела после коротких слов здесь нет нарочно, хотя в
    // наборе он положен. Android не растягивает неразрывный пробел при
    // выключке по формату: предлог слипается со своим словом, а остальные
    // пробелы в строке растягиваются вдвое - получается рваный ритм, куда
    // заметнее висячего предлога. Поставить «клей» без растяжения (word
    // joiner) нельзя: он добавил бы знаки, а длина текста здесь
    // неприкосновенна - на ней держатся позиции чтения и разметка.
    return sb.toString()
}

/**
 * Капитель для первых слов главы: Literata малых прописных не содержит, и
 * подмена шрифта тут была бы хуже подделки - берём прописные пониженного
 * кегля с разрядкой, как делают в наборе, когда капители нет.
 */
fun smallCapsHead(text: String, words: Int = 3): Pair<String, Int> {
    var seen = 0
    var i = 0
    while (i < text.length && seen < words) {
        val space = text.indexOf(' ', i)
        if (space < 0) { i = text.length; break }
        i = space + 1
        seen++
    }
    val end = i.coerceAtMost(text.length)
    return text.uppercase() to end
}



/**
 * Тот же текст, но с подсвеченной фразой. Подсветка живёт внутри строки, а не
 * заливает абзац целиком: найденное предложение видно, соседние - нет.
 */
/**
 * Первый абзац главы: первые слова капителью.
 *
 * Literata малых прописных не содержит, поэтому капитель - прописные
 * пониженного кегля с разрядкой: так её и подделывают в наборе, когда своей
 * нет. Длина строки при этом не меняется ни на знак - меняются только стили
 * поверх тех же букв, - значит разбивка на страницы остаётся верной.
 */
internal fun openingText(
    text: String,
    start: Int,
    ink: TextInk,
    palette: ReaderPalette,
): androidx.compose.ui.text.AnnotatedString {
    val base = litText(text, start, ink, palette)
    // Три слова или первое предложение - что короче: длинную капитель читать
    // тяжело, она сбивает с ритма.
    var end = 0
    var words = 0
    while (end < text.length && words < 3) {
        val space = text.indexOf(' ', end)
        if (space < 0) { end = text.length; break }
        end = space + 1
        words++
    }
    end = end.coerceAtMost(text.length).coerceAtMost(28)
    if (end <= 0) return base
    return androidx.compose.ui.text.buildAnnotatedString {
        append(base)
        addStyle(
            androidx.compose.ui.text.SpanStyle(
                fontSize = 0.86.em,
                letterSpacing = 0.06.em,
                fontFeatureSettings = "smcp",
            ),
            0, end,
        )
        // Прописными - тем же текстом, но в верхнем регистре: SpanStyle
        // регистра не меняет, поэтому подменяем сам кусок.
    }.let { styled ->
        androidx.compose.ui.text.buildAnnotatedString {
            append(text.substring(0, end).uppercase())
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    fontSize = 0.86.em,
                    letterSpacing = 0.06.em,
                ),
                0, end,
            )
            append(styled.subSequence(end, styled.length))
        }
    }
}

/**
 * Детерминированный «шум» по месту в книге: одна и та же страница всегда
 * перекошена одинаково. Без этого неровности печати дрожали бы на каждом
 * кадре, и вместо живой бумаги вышла бы рябь.
 */
internal fun pageNoise(key: Int, salt: Int): Float {
    val v = kotlin.math.sin(key * 0.0173 + salt * 12.9898) * 43758.5453
    return (v - kotlin.math.floor(v)).toFloat()
}

internal fun litText(
    text: String,
    start: Int,
    ink: TextInk,
    palette: ReaderPalette,
): androidx.compose.ui.text.AnnotatedString {
    val end = start + text.length
    // Диапазон книги - в пределы этого куска; пустой пересечение не рисуем.
    fun span(r: IntRange): Pair<Int, Int>? {
        if (r.last <= start || r.first >= end) return null
        val from = (r.first - start).coerceIn(0, text.length)
        val to = (r.last - start).coerceIn(from, text.length)
        return if (to > from) from to to else null
    }
    val lit = ink.highlight?.takeIf { ink.highlightAlpha > 0.01f }?.let(::span)
    val sel = ink.selection?.let(::span)
    val marks = ink.notes.mapNotNull(::span)
    if (lit == null && sel == null && marks.isEmpty()) return androidx.compose.ui.text.AnnotatedString(text)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        // Порядок - снизу вверх: маркер пометки, поверх найденная фраза, поверх
        // всего выделение - оно то, что сейчас в руках.
        marks.forEach { (a, b) ->
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = ink.noteColor,
                    textDecoration = if (ink.underlineNotes) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                ),
                a, b,
            )
        }
        lit?.let { (a, b) ->
            addStyle(
                androidx.compose.ui.text.SpanStyle(background = palette.fg.copy(alpha = 0.22f * ink.highlightAlpha)),
                a, b,
            )
        }
        sel?.let { (a, b) ->
            addStyle(androidx.compose.ui.text.SpanStyle(background = ink.selectionColor), a, b)
        }
    }
}
