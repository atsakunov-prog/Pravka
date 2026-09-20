package ru.zf.slushalka.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.data.Settings
import java.util.Random
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Как выглядит страница читалки.
 *
 * Не бумажный скевоморфизм с корешком, сгибом и обрезом: владелец
 * (20.09.2026) - «ужасно всё это выглядит, как будто из компьютеров 90-х.
 * Давай сделаем так, как будто это набор страниц. Страницы давай сделаем
 * объёмными... стопка страниц, и она смахивается».
 *
 * Язык взят у Правки - у диска и плашек (`ui/CardLook.kt`,
 * `trigger/BubbleSkin.kt`), чтобы два приложения одного хозяина не выглядели
 * как два дизайна: плотный тон, блик полосой по верхней трети, затенение по
 * нижней пятой, тонкая фаска по кромке (светлая сверху, тёмная снизу) и зерно
 * поверх заливки. Числа здесь свои: страница большая и читается вблизи, а
 * буквам нужен спокойный фон - где у плашки объём, там у страницы намёк.
 *
 * Страница - верхняя карточка колоды: под ней видны кромки следующих, она
 * лежит на столе с мягкой тенью и смахивается вбок, а из колоды поднимается
 * следующая.
 */

/**
 * Тона считаются от цвета бумаги, а не задаются числом: на светлой странице
 * объём держат тень на столе и тёмная кромка снизу, на тёмной - блик и фаска.
 */
class PaperTones(val paper: Color) {

    val dark: Boolean = paper.luminance() < 0.18f

    /**
     * Настоящий чёрный - «белым по чёрному» и ночная автоматическая. Её
     * включают ради OLED, где чёрный пиксель просто не горит: ни блика, ни
     * зерна по такой странице, иначе они зажигают её целиком. Объём там
     * держится на одной кромке.
     */
    val oled: Boolean = paper.luminance() < 0.02f

    /** Стол, на котором лежит колода. */
    val backdrop: Color =
        if (dark) lerp(paper, Color.White, 0.10f) else lerp(paper, Color(0xFF2A211A), 0.62f)

    /** Цвет отброшенной тени: тёплый, холодная серая на бумаге читается грязью. */
    val cast: Color = if (dark) Color.Black else Color(0xFF2A211A)

    // Слои объёма. У Правки на плашках это 0,07 и 0,03 - там мелкий текст на
    // тёмном; здесь страница во весь экран, и те же числа полосили бы.
    val sheen: Float = if (oled) 0f else if (dark) 0.06f else 0.05f
    val foot: Float = if (oled) 0f else if (dark) 0.10f else 0.05f
    val rimLight: Float = if (oled) 0.10f else if (dark) 0.14f else 0.55f
    val rimShade: Float = if (dark) 0.22f else 0.10f
    fun light(alpha: Float): Color = Color.White.copy(alpha = alpha)

    /** Зерно по странице - вдвое слабее, чем по столу: под буквами оно мешает. */
    val grain: Float = if (oled) 0f else if (dark) 0.025f else 0.018f
    val tableGrain: Float = if (dark) 0.05f else 0.035f

    /**
     * Кромка страницы в колоде. [depth] - 0 у ближней, 1 у самой глубокой: чем
     * глубже, тем ближе к столу, то есть дальше от света.
     */
    fun deck(depth: Float): Color = lerp(paper, backdrop, 0.10f + 0.55f * depth)
}

/** Книга: сколько в ней бумаги, сколько её позади и как она разложена. */
data class BookShape(
    /** Толщина книги: от объёма текста, а не от места в нём. */
    val thickness: Dp,
    /** 0..1 - сколько прочитано: столько карточек из колоды уже смахнули. */
    val progress: Float,
    /** Разворот: две страницы рядом. */
    val spread: Boolean = false,
)

/**
 * Толщина книги по объёму текста.
 *
 * Корень, а не прямая: между рассказом и романом разница должна быть видна, а
 * между романом и эпопеей - уже нет, как и на полке. Сверху ограничено: книг
 * толще ладони не бывает, да и экран не резиновый.
 */
fun bookThickness(chars: Int): Dp {
    val pages = (chars / Settings.PAGE_CHARS).coerceAtLeast(1)
    return (3.5f + 0.5f * sqrt(pages.toFloat())).coerceIn(5f, 15f).dp
}

/**
 * Разворот: две страницы, когда экран это позволяет.
 *
 * «По экрану» - от ширины окна, а не от того, что написано в паспорте
 * устройства: раскрытая книжка-телефон, планшет и телефон, положенный набок,
 * дают одно и то же - место на две полосы. Порог 600 dp, обычная граница
 * широкого экрана; и только при листании страницами - в ленте разворота быть
 * не может.
 */
fun spreadOn(mode: String, paged: Boolean, width: Dp): Boolean = paged && when (mode) {
    Settings.SPREAD_ON -> true
    Settings.SPREAD_OFF -> false
    else -> width >= 600.dp
}

/**
 * Мерки карточки: поля от края экрана, скругление и место под колоду.
 *
 * Место под колоду отведено по самой толстой её мерке и от места в книге не
 * зависит: разбивка на страницы меряется в этой ширине и высоте, и тающая
 * колода гоняла бы пересчёт при каждом перелистывании. Меняется только число
 * нарисованных кромок.
 */
data class CardMetrics(
    val side: Dp,
    val top: Dp,
    val bottom: Dp,
    val radius: Dp,
    val deckStep: Dp,
    val deckInset: Dp,
    /** Сколько кромок под карточкой у полной книги. */
    val rims: Int,
)

/** Сколько кромок видно сейчас: колода тает по мере чтения. */
fun rimsNow(metrics: CardMetrics, shape: BookShape): Int {
    if (metrics.rims <= 0) return 0
    val left = (1f - shape.progress).coerceIn(0f, 1f)
    return ceil(metrics.rims * left).toInt().coerceIn(0, metrics.rims)
}

fun cardMetrics(style: String, shape: BookShape): CardMetrics = when (style) {
    Settings.PAGE_FLAT -> CardMetrics(0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0)
    Settings.PAGE_SOFT -> CardMetrics(8.dp, 7.dp, 9.dp, 20.dp, 0.dp, 0.dp, 0)
    else -> {
        // Толстая книга - колода в четыре кромки, тонкая - в одну: объём книги
        // виден с первой страницы, а не только в счётчике внизу.
        val rims = (shape.thickness.value / 4f).toInt().coerceIn(1, 4)
        val step = 5.dp
        CardMetrics(
            side = 8.dp,
            top = 7.dp,
            bottom = 9.dp + step * rims,
            radius = 20.dp,
            deckStep = step,
            deckInset = 5.dp,
            rims = rims,
        )
    }
}

/** Стол под колодой: плотный тон и зерно, как на стекле диска у Правки. */
fun Modifier.readerBackdrop(tones: PaperTones, style: String): Modifier =
    if (style == Settings.PAGE_FLAT) this.background(tones.paper)
    else this.background(tones.backdrop).grain(tones.tableGrain)

/**
 * Карточка страницы: колода под ней, тень на стол, заливка, блик, затенение,
 * фаска и зерно. Порядок слоёв тот же, что у плашек Правки, - свет в двух
 * приложениях одного хозяина должен падать с одной стороны.
 */
fun Modifier.pageCard(tones: PaperTones, style: String, shape: BookShape): Modifier {
    if (style == Settings.PAGE_FLAT) return this.background(tones.paper)
    val m = cardMetrics(style, shape)
    val corner = RoundedCornerShape(m.radius)
    val rims = rimsNow(m, shape)
    return this
        // Тень рисуется по фигуре карточки и НЕ обрезает содержимое: кромки
        // колоды лежат ниже её края и должны остаться видимыми. Она идёт
        // ПЕРЕД колодой, то есть под ней: тень, размазанная поверх кромок,
        // съедала их - стопка превращалась в серое пятно.
        .shadow(
            elevation = if (m.rims > 0) 10.dp else 7.dp,
            shape = corner,
            clip = false,
            ambientColor = tones.cast,
            spotColor = tones.cast,
        )
        .then(if (rims > 0) Modifier.deck(tones, m, rims) else Modifier)
        .clip(corner)
        .background(tones.paper)
        // Блик полосой по верхней трети, к концу полосы в ноль: градиент во всю
        // высоту читался бы не объёмом, а заливкой (у Правки та же история).
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = tones.sheen),
                SHEEN_SPAN * 0.55f to Color.White.copy(alpha = tones.sheen * 0.3f),
                SHEEN_SPAN to Color.Transparent,
            )
        )
        .background(
            Brush.verticalGradient(
                1f - FOOT_SPAN to Color.Transparent,
                1f to Color.Black.copy(alpha = tones.foot),
            )
        )
        // Фаска: светлая линия сверху, тёмная снизу, посередине её нет. Ровный
        // кант по периметру читался бы рамкой виджета, разный - толщиной.
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = tones.rimLight),
                0.5f to Color.Transparent,
                1f to Color.Black.copy(alpha = tones.rimShade),
            ),
            shape = corner,
        )
        .grain(tones.grain)
}

/**
 * Колода под карточкой: кромки следующих страниц. Рисуются ниже её края -
 * `drawBehind` рисует в своих координатах, но не обрезает по ним, а место под
 * них отведено в [CardMetrics.bottom].
 */
private fun Modifier.deck(tones: PaperTones, m: CardMetrics, rims: Int): Modifier = drawBehind {
    val step = m.deckStep.toPx()
    val inset = m.deckInset.toPx()
    val radius = CornerRadius(m.radius.toPx())
    val hair = 1.dp.toPx().coerceAtLeast(1f)
    for (i in rims downTo 1) {
        val depth = i / rims.toFloat()
        val dx = inset * i
        val at = Offset(dx, 0f)
        val box = Size((size.width - dx * 2f).coerceAtLeast(0f), size.height + step * i)
        drawRoundRect(color = tones.deck(depth), topLeft = at, size = box, cornerRadius = radius)
        // Светлая нить по кромке каждой: без неё соседние кромки сливаются в
        // одну серую полосу, и колода читается тенью, а не стопкой.
        drawRoundRect(
            color = tones.light(0.22f),
            topLeft = at,
            size = box,
            cornerRadius = radius,
            style = Stroke(width = hair),
        )
    }
}

/**
 * Насколько страница сдвинута от своего места: 0 - лежит сверху, 1 - смахнута,
 * -1 - ещё в колоде.
 */
fun PagerState.turnOffset(page: Int): Float = (currentPage - page) + currentPageOffsetFraction

/**
 * Перелистывание.
 *
 * [offset] читается в фазе рисования, а не в перекомпоновке: пейджер двигает
 * страницу каждый кадр, и пересобирать на это дерево незачем. Порядок
 * рисования задаёт `zIndex` в самой читалке - ранняя карточка лежит поверх
 * поздней, как в колоде.
 */
fun Modifier.pageTurn(style: String, gentle: Boolean, offset: () -> Float): Modifier = when (style) {
    Settings.TURN_SLIDE -> this

    Settings.TURN_FADE -> this.graphicsLayer {
        val off = offset()
        // Дальние страницы оставляем пейджеру: подтянутые к середине, они
        // закрыли бы читаемую, если бы порядок рисования сбился.
        if (off > -1f && off < 1f) translationX = off * size.width
        alpha = 1f - off.coerceIn(0f, 1f)
    }

    // TURN_DECK: верхнюю смахивают, следующая поднимается из колоды.
    else -> this.graphicsLayer {
        val off = offset()
        when {
            off > 0f -> {
                // Уходит сама - пейджер её и так везёт; от нас наклон и лёгкое
                // уменьшение, будто карточку отбросили от себя.
                val t = off.coerceAtMost(1f)
                transformOrigin = TransformOrigin(0.5f, 0.9f)
                // Разворот отклоняется вдвое меньше: пара карточек, повёрнутая
                // как одна, читается перекосом экрана, а не броском.
                rotationZ = (if (gentle) -2f else -4f) * t
                val s = 1f - 0.03f * t
                scaleX = s
                scaleY = s
            }
            off > -1f -> {
                // Нижняя лежит на месте, а не едет за пейджером, и всплывает:
                // ниже, мельче и прижата к колоде, пока верхняя её не открыла.
                translationX = off * size.width
                val t = -off
                translationY = 10.dp.toPx() * t
                val s = 1f - 0.035f * t
                scaleX = s
                scaleY = s
            }
        }
    }
}

/**
 * Зерно: плитка шума 64x64 повтором. Взято у Правки (`ui/CardLook.kt`) вместе
 * с причиной рисовать его ПОД содержимым - поверх текста оно читается грязным
 * стеклом, а не материалом.
 */
fun Modifier.grain(alpha: Float): Modifier = if (alpha <= 0f) this else composed {
    val brush = remember {
        ShaderBrush(ImageShader(grainBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    drawBehind { drawRect(brush = brush, alpha = alpha) }
}

/** Seed постоянный: зерно не должно кипеть при каждой перерисовке. */
private fun grainBitmap(): ImageBitmap {
    val size = 64
    val random = Random(20_260_920L)
    val pixels = IntArray(size * size)
    for (i in pixels.indices) {
        val v = random.nextInt(256)
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Блик занимает верхнюю треть, затенение - нижнюю пятую: как у плашек Правки. */
private const val SHEEN_SPAN = 0.34f
private const val FOOT_SPAN = 0.2f
