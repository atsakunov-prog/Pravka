package ru.zf.slushalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.data.Settings
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Объём книги под текстом читалки.
 *
 * Экран - не белый лист, а раскрытая книга: у корешка бумага уходит в сгиб и
 * темнеет, с внешнего края из-под страницы выглядывает срез стопки, на саму
 * страницу падает свет, а её край над стопкой отбрасывает тень. Рисуется не по
 * заранее заданным числам, а от размера экрана и от объёма самой книги.
 *
 * Две стопки, как в настоящей книге: слева - прочитанное, справа - то, что
 * осталось. В начале книги слева почти ничего, к концу - почти всё; сумма
 * постоянна и равна толщине книги, поэтому тонкий рассказ и толстый роман
 * выглядят по-разному. Снизу виден срез всего блока разом - он не меняется.
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

    /** Переплёт за книгой: у светлой бумаги - тёмный, у чёрной - чуть светлее страницы. */
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

/** Книга: сколько в ней бумаги, сколько её позади и как она раскрыта. */
data class BookShape(
    /** Толщина блока страниц целиком: от объёма книги, а не от места в ней. */
    val thickness: Dp,
    /** 0..1 - сколько прочитано: столько бумаги перешло на левую сторону. */
    val progress: Float,
    /** Разворот: две страницы и корешок посередине. */
    val spread: Boolean = false,
)

/**
 * Толщина книги по объёму текста.
 *
 * Корень, а не прямая: между рассказом и романом разница должна быть видна, а
 * между романом и эпопеей - уже нет, как и на полке. Сверху ограничено - книг
 * толще ладони не бывает, да и экран не резиновый.
 */
fun bookThickness(chars: Int): Dp {
    val pages = (chars / Settings.PAGE_CHARS).coerceAtLeast(1)
    return (3.5f + 0.5f * sqrt(pages.toFloat())).coerceIn(5f, 15f).dp
}

/**
 * Разворот: две страницы и корешок посередине.
 *
 * «По экрану» - от ширины окна, а не от того, что написано в паспорте
 * устройства: раскрытая книжка-телефон, планшет и телефон, положенный набок,
 * дают одно и то же - место на две полосы. Порог - 600 dp, обычная граница
 * «широкого» экрана; и только при листании страницами, в прокрутке разворота
 * быть не может.
 */
fun spreadOn(mode: String, paged: Boolean, width: Dp): Boolean = paged && when (mode) {
    Settings.SPREAD_ON -> true
    Settings.SPREAD_OFF -> false
    else -> width >= 600.dp
}

/** Какой стороной лист повёрнут к корешку. */
enum class PageSide { SINGLE, LEFT, RIGHT }

/**
 * Полосы, которые объём забирает у текста.
 *
 * От места в книге не зависят нарочно: разбивка на страницы меряется в этой
 * ширине, и перетекающая стопка гоняла бы пересчёт при каждом перелистывании.
 * Меняется только то, что нарисовано внутри отведённой полосы.
 */
data class PageInsets(
    /** Слева - стопка прочитанного. */
    val left: Dp,
    /** Справа - то, что осталось. */
    val right: Dp,
    /** Снизу - срез всей книги разом. */
    val bottom: Dp,
    /** Отступ текста от сгиба: строка не начинается в самой складке. */
    val fold: Dp,
) {
    /** Отступ листа от края экрана слева: у правой страницы разворота слева корешок. */
    fun sheetStart(side: PageSide): Dp = if (side == PageSide.RIGHT) 0.dp else left

    /** И справа: у левой страницы разворота справа корешок. */
    fun sheetEnd(side: PageSide): Dp = if (side == PageSide.LEFT) 0.dp else right

    fun textStart(side: PageSide): Dp = if (side == PageSide.LEFT) 0.dp else fold

    fun textEnd(side: PageSide): Dp = if (side == PageSide.LEFT) fold else 0.dp
}

fun pageInsets(style: String, shape: BookShape): PageInsets =
    if (style != Settings.PAGE_BOOK) PageInsets(0.dp, 0.dp, 0.dp, 0.dp)
    else PageInsets(
        // На одной странице прочитанное уходит за сгиб, и виден лишь его край;
        // в развороте это обрез левой страницы - он виден целиком.
        left = if (shape.spread) shape.thickness else shape.thickness * 0.6f,
        right = shape.thickness,
        bottom = (shape.thickness * 0.55f).coerceAtMost(9.dp),
        fold = 8.dp,
    )

/** Стопки под страницей: рисуются один раз на весь экран, под всем телом читалки. */
fun Modifier.bookStack(tones: PaperTones, style: String, shape: BookShape): Modifier =
    if (style != Settings.PAGE_BOOK) this.background(tones.paper)
    else this.drawWithCache {
        val insets = pageInsets(style, shape)
        val edgePx = insets.right.toPx()
        val leftPx = insets.left.toPx()
        val bottomPx = insets.bottom.toPx()
        val p = shape.progress.coerceIn(0f, 1f)
        // Прочитанное перешло налево, непрочитанное осталось справа. Снизу
        // виден срез всей книги разом, поэтому он не меняется.
        val read = leftPx * p
        val rest = edgePx * (1f - p)
        val radius = 3.dp.toPx()
        val hair = 1.dp.toPx().coerceAtLeast(1f)
        // Пять листов, а не двадцать: на срезе в сантиметр больше всё равно
        // не различить, а слипшиеся слои читаются грязной кромкой.
        val leaves = 5
        onDrawBehind {
            drawRect(tones.table)
            // Тень книги на переплёте: у среза густая, к краю сходит. Под
            // листами её не видно вовсе, а там, где стопка стаяла, она и
            // держит край - иначе осталась бы ровная серая полоса.
            drawRect(
                Brush.horizontalGradient(
                    listOf(tones.shadow(0.34f), Color.Transparent),
                    startX = size.width - edgePx,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - edgePx, 0f),
                size = Size(edgePx, size.height),
            )
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, tones.shadow(0.34f)),
                    startX = 0f,
                    endX = leftPx,
                ),
                size = Size(leftPx, size.height),
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
                val left = leftPx - read * f
                val right = size.width - edgePx + rest * f
                val bottom = size.height - bottomPx + bottomPx * f
                drawPath(leafPath(left, right, bottom, radius), tones.leaf(f))
                // Прожилка по краю листа: без неё слои сливаются в один тёмный
                // край, и стопка читается кромкой, а не стопкой.
                val tall = (bottom - radius * 2f).coerceAtLeast(0f)
                if (rest > 1f) drawRect(tones.line(0.35f), Offset(right - hair, radius), Size(hair, tall))
                if (read > 1f) drawRect(tones.line(0.35f), Offset(left, radius), Size(hair, tall))
                drawRect(
                    tones.line(0.25f),
                    topLeft = Offset(left + radius, bottom - hair),
                    size = Size((right - left - radius * 2f).coerceAtLeast(0f), hair),
                )
            }
            // Кромка переплёта по самому краю экрана: без неё полоса, с которой
            // стопка ещё не сошла (начало книги слева, конец справа), читается
            // не обложкой, а провалом.
            if (leftPx > 2f) drawRect(
                tones.light(0.10f),
                topLeft = Offset(0f, 0f),
                size = Size(hair, size.height),
            )
            if (edgePx > 2f) drawRect(
                tones.light(0.10f),
                topLeft = Offset(size.width - hair, 0f),
                size = Size(hair, size.height),
            )
        }
    }

/**
 * Сама страница: бумага, сгиб у корешка, засвет и тень края над стопкой.
 *
 * Рисуется под каждой страницей отдельно, а не один раз на экран: при
 * перелистывании лист уходит со своей бумагой и своим светом, иначе сквозь
 * него просвечивал бы текст следующего. [side] говорит, с какой стороны
 * корешок: у левой страницы разворота он справа, и вся картинка зеркалится.
 */
fun Modifier.bookSheet(
    tones: PaperTones,
    style: String,
    shape: BookShape,
    side: PageSide = PageSide.SINGLE,
): Modifier = this.drawWithCache {
    val w = size.width
    val h = size.height
    val p = shape.progress.coerceIn(0f, 1f)
    val book = style == Settings.PAGE_BOOK
    val flat = style == Settings.PAGE_FLAT
    val mirror = side == PageSide.LEFT
    // Сила всего объёма: «мягкий свет» - та же картинка вполсилы.
    val k = if (book) 1f else 0.45f
    // Корешок от ширины страницы, а не числом: чем больше прочитано, тем толще
    // пачка на той стороне и тем глубже уходит сгиб.
    val gutter = (w * 0.06f).coerceIn(18.dp.toPx(), 50.dp.toPx()) * (0.8f + 0.4f * p)

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
    val outerEdge = Brush.horizontalGradient(
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
    val corner = CornerRadius(if (book) 3.dp.toPx() else 0f)

    onDrawWithContent {
        // Всё, кроме текста, рисуется так, будто корешок слева; у левой
        // страницы разворота холст просто зеркалится.
        mirrored(mirror) {
            drawRoundRect(tones.paper, cornerRadius = corner)
            if (!flat) {
                drawRect(fold, size = Size(gutter, h))
                if (lit) {
                    drawRect(crest, topLeft = Offset(gutter * 0.55f, 0f), size = Size(gutter * 1.55f, h))
                    drawRect(glare)
                }
                if (book) {
                    drawRect(outerEdge, topLeft = Offset(w - edgeBand, 0f), size = Size(edgeBand, h))
                    drawRect(bottomEdge, topLeft = Offset(0f, h - edgeBand), size = Size(w, edgeBand))
                    // Кромка самого листа: тонкая светлая нить по краю - от неё
                    // страница и читается отдельным листом, а не заливкой.
                    drawRect(
                        tones.light(if (tones.dark) 0.16f else 0.34f),
                        topLeft = Offset(w - 1f, 3.dp.toPx()),
                        size = Size(1f, h - 6.dp.toPx()),
                    )
                }
            }
        }
        drawContent()
        if (!flat) mirrored(mirror) { drawRect(foldOverText, size = Size(gutter * 0.9f, h)) }
    }
}

/**
 * Насколько страница (или разворот) сдвинута от своего места: 0 - лежит на
 * месте, 1 - перевёрнута до конца, -1 - лежит нетронутой под следующей.
 */
fun PagerState.turnOffset(page: Int): Float = (currentPage - page) + currentPageOffsetFraction

/**
 * Перелистывание одной страницы.
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
                    cameraDistance = bookCamera(size.width, density)
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

/**
 * Разворот целиком, когда лист переворачивается сам: обе половины стоят
 * неподвижно, сдвиг пейджера отменяется, а под поднятым листом ложится тень.
 */
fun Modifier.spreadStill(tones: PaperTones, offset: () -> Float): Modifier = this
    .graphicsLayer {
        val off = offset()
        if (off > -1f && off < 1f) translationX = off * size.width
    }
    .drawWithContent {
        drawContent()
        val off = offset()
        if (off > -1f && off < 0f) {
            // Открывшаяся правая страница ещё в тени поднятого над ней листа.
            val t = -off
            drawRect(
                Brush.horizontalGradient(
                    listOf(tones.shadow(0.30f * t), Color.Transparent),
                    startX = size.width * 0.5f,
                    endX = size.width,
                ),
                topLeft = Offset(size.width * 0.5f, 0f),
                size = Size(size.width * 0.5f, size.height),
            )
        }
    }

/**
 * Правая половина разворота, пока с неё не подняли лист: как только он пошёл,
 * место занимает сам лист - две копии одной страницы разом дали бы двоение.
 */
fun Modifier.spreadRestingHalf(offset: () -> Float): Modifier = this.graphicsLayer {
    alpha = if (offset() > 0.001f) 0f else 1f
}

/**
 * Переворачиваемый лист разворота.
 *
 * Лист двусторонний, как в книге: [face] - его лицо, правая страница текущего
 * разворота, оно уходит от корешка влево (угол 0…-90). Оборот - левая
 * страница следующего разворота, она прилетает с той стороны (угол 90…0) и
 * ложится на левую половину. Обе половины поворота встречаются ровно на 90°,
 * где лист виден ребром, то есть не виден вовсе, - поэтому подмена незаметна.
 */
fun Modifier.spreadLeaf(face: Boolean, tones: PaperTones, offset: () -> Float): Modifier = this
    .graphicsLayer {
        val off = offset()
        val t = off.coerceIn(0f, 1f)
        cameraDistance = bookCamera(size.width, density)
        if (face) {
            alpha = if (off > 0.001f && t < 0.5f) 1f else 0f
            transformOrigin = TransformOrigin(0f, 0.5f)
            rotationY = -180f * t
        } else {
            alpha = if (t >= 0.5f && off < 1f) 1f else 0f
            transformOrigin = TransformOrigin(1f, 0.5f)
            rotationY = 180f * (1f - t)
        }
    }
    .drawWithContent {
        drawContent()
        val t = offset().coerceIn(0f, 1f)
        // Поднятый лист темнеет у корешка и светлеет свободным краем. У оборота
        // корешок справа, поэтому та же полоса зеркалится.
        val shade = Brush.horizontalGradient(
            if (face) listOf(tones.shadow(0.34f * t), Color.Transparent, tones.light(0.14f * t))
            else listOf(tones.light(0.14f * (1f - t)), Color.Transparent, tones.shadow(0.34f * (1f - t)))
        )
        drawRect(shade)
    }

/**
 * Камера для поворота листа - в двух с половиной его ширинах.
 *
 * Единица `cameraDistance` в Compose - не пиксель, а дюйм: RenderNode делит
 * пиксели на dpi (у View по умолчанию 1280*density px - те самые 8.0).
 * Поэтому не «26 * density»: на телефоне это сотни дюймов, и лист не
 * поворачивался бы, а просто сплющивался по ширине.
 */
private fun bookCamera(widthPx: Float, density: Float): Float =
    2.5f * widthPx / (160f * density)

/** Зеркальный холст - для левой страницы разворота, у которой корешок справа. */
private inline fun DrawScope.mirrored(on: Boolean, block: DrawScope.() -> Unit) {
    if (on) scale(scaleX = -1f, scaleY = 1f) { block() } else block()
}

/** Лист стопки: прямоугольник со скруглёнными углами. */
private fun leafPath(left: Float, right: Float, bottom: Float, radius: Float): Path = Path().apply {
    val r = radius.coerceAtMost(minOf(right - left, bottom) / 2f).coerceAtLeast(0f)
    moveTo(left + r, 0f)
    lineTo(right - r, 0f)
    quadraticTo(right, 0f, right, r)
    lineTo(right, bottom - r)
    quadraticTo(right, bottom, right - r, bottom)
    lineTo(left + r, bottom)
    quadraticTo(left, bottom, left, bottom - r)
    lineTo(left, r)
    quadraticTo(left, 0f, left + r, 0f)
    close()
}
