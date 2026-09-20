package ru.zf.slushalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.data.Settings
import kotlin.math.max

/**
 * Объём книги под текстом читалки.
 *
 * Экран - не белый лист, а верхняя страница книги, которая на чём-то лежит:
 * слева бумага уходит в корешок и темнеет, справа и снизу из-под неё выглядывает
 * стопка непрочитанных страниц, на саму страницу падает свет, а её край над
 * стопкой отбрасывает тень. Рисуется не по заранее заданным числам, а от
 * размера экрана: на планшете корешок шире, на телефоне уже.
 *
 * Толщина стопки - это место в книге: читаешь - стопка под страницей тает
 * (непрочитанного осталось меньше), а сгиб у корешка становится глубже -
 * прочитанное ушло на ту сторону разворота и лежит там пачкой. Само число
 * страниц в стопку не переводится: важно ощущение «сколько ещё», а не счёт.
 */

/**
 * Тени и блики считаются от бумаги, а не задаются числом.
 *
 * На белой странице объём держится на тенях; на чёрной (ночные темы) тени не
 * видно вовсе - там работают засветы, как на срезе книги в темноте, и стопка
 * рисуется светлее страницы, а не темнее.
 */
class PaperTones(val paper: Color) {

    val dark: Boolean = paper.luminance() < 0.18f

    /**
     * Настоящий чёрный - тема «белым по чёрному» и ночная автоматическая.
     * Её включают ради OLED: там чёрный пиксель просто не горит, и засвет
     * поперёк страницы, пусть и в пять процентов, отнимает у неё весь смысл.
     * Объём на такой бумаге держится только на краях.
     */
    val oled: Boolean = paper.luminance() < 0.02f

    /** Что видно за книгой: у светлой бумаги - тень стола, у чёрной - серость чуть светлее страницы. */
    val table: Color =
        if (dark) lerp(paper, Color.White, 0.09f) else lerp(paper, Color(0xFF3B2E1E), 0.34f)

    /** Тень тёплая: холодная серая на бумаге выглядит грязью, а не тенью. */
    fun shadow(alpha: Float): Color =
        if (dark) Color.Black.copy(alpha = alpha * 0.65f) else Color(0xFF2E2418).copy(alpha = alpha)

    fun light(alpha: Float): Color = Color.White.copy(alpha = alpha)

    /** Прожилка между листами стопки: на свету - тень, в темноте - засвет. */
    fun line(alpha: Float): Color = if (dark) light(alpha * 0.5f) else shadow(alpha)

    /**
     * Срез страницы в стопке. [depth] - 0 у ближней к нам, 1 у самой глубокой:
     * на свету глубина темнеет, в темноте светятся как раз верхние срезы.
     */
    fun leaf(depth: Float): Color =
        if (dark) lerp(paper, Color.White, 0.04f + 0.20f * (1f - depth))
        else lerp(paper, Color(0xFF6A5940), 0.05f + 0.30f * depth)
}

/**
 * Сколько объём забирает у текста.
 *
 * От места в книге не зависит нарочно: разбивка на страницы меряется в этой
 * ширине, и «тающая стопка» гоняла бы пересчёт на каждом перелистывании.
 */
data class PageInsets(val start: Dp, val end: Dp, val bottom: Dp)

fun pageInsets(style: String): PageInsets =
    if (style == Settings.PAGE_BOOK) PageInsets(start = 8.dp, end = 14.dp, bottom = 9.dp)
    else PageInsets(0.dp, 0.dp, 0.dp)

/** Стопка под страницей: рисуется один раз на весь экран, под всем телом читалки. */
fun Modifier.bookStack(tones: PaperTones, style: String, progress: Float): Modifier =
    if (style != Settings.PAGE_BOOK) this.background(tones.paper)
    else this.drawWithCache {
        val insets = pageInsets(style)
        val endPx = insets.end.toPx()
        val bottomPx = insets.bottom.toPx()
        // К концу книги стопка под страницей тает, и у края остаётся переплёт
        // с тенью книги на нём. Ноль тридцать - чтобы на последней странице
        // книга не выродилась в лист на ровной серой полосе.
        val thick = 0.30f + 0.70f * (1f - progress.coerceIn(0f, 1f))
        val radius = 3.dp.toPx()
        val hair = 1.dp.toPx().coerceAtLeast(1f)
        // Пять листов, а не двадцать: на срезе в сантиметр больше всё равно
        // не различить, а слипшиеся слои читаются грязной кромкой.
        val leaves = 5
        onDrawBehind {
            drawRect(tones.table)
            // Тень от книги на переплёт: у края стопки она густая, к самому
            // краю экрана сходит. Рисуется под листами, поэтому на толстой
            // стопке её не видно вовсе, а на дочитанной книге она и держит
            // край - иначе там оставалась бы ровная серая полоса.
            drawRect(
                Brush.horizontalGradient(
                    listOf(tones.shadow(0.34f), Color.Transparent),
                    startX = size.width - endPx,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - endPx, 0f),
                size = Size(endPx, size.height),
            )
            drawRect(
                Brush.verticalGradient(
                    listOf(tones.shadow(0.30f), Color.Transparent),
                    startY = size.height - bottomPx,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - bottomPx),
                size = Size(size.width, bottomPx),
            )
            for (j in leaves downTo 1) {
                val f = j / leaves.toFloat()
                val right = size.width - endPx + endPx * thick * f
                val bottom = size.height - bottomPx + bottomPx * thick * f
                drawPath(leafPath(right, bottom, radius), tones.leaf(f))
                // Прожилка по краю листа: без неё слои сливаются в один тёмный
                // край, и стопка читается кромкой, а не стопкой.
                drawRect(
                    tones.line(0.35f),
                    topLeft = Offset(right - hair, radius),
                    size = Size(hair, (bottom - radius * 2f).coerceAtLeast(0f)),
                )
                drawRect(
                    tones.line(0.25f),
                    topLeft = Offset(radius, bottom - hair),
                    size = Size((right - radius * 2f).coerceAtLeast(0f), hair),
                )
            }
        }
    }

/**
 * Сама страница: бумага, сгиб у корешка, засвет и тень края над стопкой.
 *
 * Рисуется под каждой страницей отдельно, а не один раз на экран: при
 * перелистывании лист уходит со своей бумагой и своим светом, иначе сквозь
 * него просвечивал бы текст следующего.
 */
fun Modifier.bookSheet(tones: PaperTones, style: String, progress: Float): Modifier =
    this.drawWithCache {
        val w = size.width
        val h = size.height
        val p = progress.coerceIn(0f, 1f)
        val book = style == Settings.PAGE_BOOK
        val flat = style == Settings.PAGE_FLAT
        // Сила всего объёма: «мягкий свет» - та же картинка вполсилы.
        val k = if (book) 1f else 0.45f
        // Корешок от ширины экрана, а не числом: чем больше прочитано, тем
        // толще пачка на той стороне и тем глубже уходит сгиб.
        val gutter = (w * 0.06f).coerceIn(18.dp.toPx(), 50.dp.toPx()) * (0.7f + 0.6f * p)

        // Шов у самого края тугой, дальше бумага быстро выходит на свет: так
        // читается сгиб, а не просто затемнение полосой.
        val fold = Brush.horizontalGradient(
            0f to tones.shadow(0.46f * k),
            0.06f to tones.shadow(0.30f * k),
            0.42f to tones.shadow(0.10f * k),
            1f to Color.Transparent,
            startX = 0f,
            endX = gutter,
        )
        // Гребень: бумага выходит из сгиба и первым делом ловит свет.
        val crest = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.45f to tones.light(0.10f * k),
            1f to Color.Transparent,
            startX = gutter * 0.55f,
            endX = gutter * 2.1f,
        )
        val glare = Brush.radialGradient(
            colors = listOf(tones.light((if (tones.dark) 0.05f else 0.11f) * k), Color.Transparent),
            center = Offset(w * 0.34f, h * 0.06f),
            radius = max(w, h) * 0.95f,
        )
        // На настоящем чёрном свет по бумаге не рисуем совсем: см. [PaperTones.oled].
        val lit = !flat && !tones.oled
        val edgeBand = 16.dp.toPx()
        val rightEdge = Brush.horizontalGradient(
            listOf(Color.Transparent, tones.shadow(0.10f)),
            startX = w - edgeBand,
            endX = w,
        )
        val bottomEdge = Brush.verticalGradient(
            listOf(Color.Transparent, tones.shadow(0.08f)),
            startY = h - edgeBand,
            endY = h,
        )
        // Сгиб ложится и на буквы: у настоящей книги строка у корешка тоже в тени.
        val foldOverText = Brush.horizontalGradient(
            0f to tones.shadow(0.13f * k),
            1f to Color.Transparent,
            startX = 0f,
            endX = gutter * 0.9f,
        )

        onDrawWithContent {
            drawRect(tones.paper)
            if (!flat) {
                drawRect(fold, size = Size(gutter, h))
                if (lit) {
                    drawRect(
                        crest,
                        topLeft = Offset(gutter * 0.55f, 0f),
                        size = Size(gutter * 1.55f, h),
                    )
                    drawRect(glare)
                }
                if (book) {
                    drawRect(rightEdge, topLeft = Offset(w - edgeBand, 0f), size = Size(edgeBand, h))
                    drawRect(bottomEdge, topLeft = Offset(0f, h - edgeBand), size = Size(w, edgeBand))
                    // Кромка самого листа: тонкая светлая нить по краю - от неё
                    // страница и читается как отдельный лист, а не как заливка.
                    drawRect(
                        tones.light(if (tones.dark) 0.16f else 0.34f),
                        topLeft = Offset(w - 1f, 2.dp.toPx()),
                        size = Size(1f, h - 4.dp.toPx()),
                    )
                }
            }
            drawContent()
            if (!flat) drawRect(foldOverText, size = Size(gutter * 0.9f, h))
        }
    }

/**
 * Насколько страница сдвинута от своего места: 0 - лежит на месте, 1 -
 * перевёрнута до конца, -1 - лежит нетронутой под следующей.
 */
fun PagerState.turnOffset(page: Int): Float = (currentPage - page) + currentPageOffsetFraction

/**
 * Перелистывание.
 *
 * [offset] читается в фазе рисования, а не в перекомпоновке: пейджер двигает
 * страницу каждый кадр, и пересобирать на это дерево незачем.
 *
 * Пейджер по-своему возит обе страницы - и уходящую, и приходящую. Книга так
 * не устроена: нижний лист лежит неподвижно, двигается только верхний.
 * Поэтому нижнему сдвиг пейджера отменяется (`translationX = off * width`), а
 * верхний либо уезжает сам, либо поворачивается вокруг корешка. Порядок
 * рисования задаёт `zIndex` в самой читалке: ранняя страница лежит поверх
 * поздней, как в книге.
 */
fun Modifier.pageTurn(style: String, tones: PaperTones, offset: () -> Float): Modifier =
    when (style) {
        Settings.TURN_SLIDE -> this

        Settings.TURN_FADE -> this.graphicsLayer {
            val off = offset()
            // Дальние страницы оставляем пейджеру: подтянутые к середине, они
            // закрыли бы собой читаемую, если бы порядок рисования сбился.
            if (off > -1f && off < 1f) translationX = off * size.width
            alpha = 1f - off.coerceIn(0f, 1f)
        }

        Settings.TURN_OVER -> this
            .graphicsLayer {
                val off = offset()
                if (off > -1f && off <= 0f) translationX = off * size.width
            }
            .drawWithContent {
                drawContent()
                val off = offset()
                if (off > -1f && off < 0f) {
                    // Тень от уходящего листа: полоса идёт ровно по его краю.
                    val x = -off * size.width
                    val band = 20.dp.toPx().coerceAtMost(size.width - x)
                    if (band > 0f) drawRect(
                        Brush.horizontalGradient(
                            listOf(tones.shadow(0.30f), Color.Transparent),
                            startX = x,
                            endX = x + band,
                        ),
                        topLeft = Offset(x, 0f),
                        size = Size(band, size.height),
                    )
                }
            }

        // TURN_BOOK: лист поворачивается вокруг корешка, открывая следующий.
        else -> this
            .graphicsLayer {
                val off = offset()
                if (off > -1f && off < 1f) translationX = off * size.width
                if (off > 0f) {
                    // Камера в двух с половиной ширинах страницы. Единица
                    // cameraDistance - не пиксель, а дюйм: RenderNode делит
                    // пиксели на dpi (у View по умолчанию 1280*density px =
                    // те самые 8.0). Поэтому не «26 * density» - на телефоне
                    // это сотни дюймов, и лист не поворачивался бы, а просто
                    // сплющивался по ширине.
                    cameraDistance = 2.5f * size.width / (160f * density)
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = -90f * off.coerceAtMost(1f)
                }
            }
            .drawWithContent {
                drawContent()
                val off = offset()
                when {
                    // Поднятый лист: у корешка он в тени, свободным краем ловит свет.
                    off > 0f -> {
                        val t = off.coerceAtMost(1f)
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(
                                    tones.shadow(0.34f * t),
                                    Color.Transparent,
                                    tones.light(0.14f * t),
                                )
                            )
                        )
                    }
                    // Под ним - тень от него же, гаснет, когда лист ушёл.
                    off > -1f -> {
                        val t = -off
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(tones.shadow(0.26f * t), Color.Transparent),
                                startX = 0f,
                                endX = size.width * 0.55f,
                            )
                        )
                    }
                }
            }
    }

/** Лист стопки: прямоугольник от левого края экрана со скруглёнными правыми углами. */
private fun leafPath(right: Float, bottom: Float, radius: Float): Path = Path().apply {
    val r = radius.coerceAtMost(minOf(right, bottom) / 2f).coerceAtLeast(0f)
    moveTo(0f, 0f)
    lineTo(right - r, 0f)
    quadraticTo(right, 0f, right, r)
    lineTo(right, bottom - r)
    quadraticTo(right, bottom, right - r, bottom)
    lineTo(0f, bottom)
    close()
}
