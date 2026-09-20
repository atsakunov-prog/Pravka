package ru.zf.slushalka.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.data.Settings
import java.util.Random
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Как выглядит страница читалки.
 *
 * Два мира, и оба - предметы, а не заливки экрана.
 *
 * **Книжный** - настоящий том: обложка кантом по краям, корешок, срез блока
 * сбоку, тень на столе. Владелец прислал фотографию раскрытой книги: «взять
 * обложку книги и запулить её по края, сделать корешок как здесь, сделать
 * красивый стол с тенью».
 *
 * **Стопка** - колода карточек в языке Правки (`ui/CardLook.kt`,
 * `trigger/BubbleSkin.kt`): плотный тон, блик полосой по верхней трети,
 * затенение по нижней пятой, фаска по кромке, зерно поверх заливки.
 *
 * Общее правило обоих: всё, что не должно ехать при перелистывании - колода,
 * обложка, корешок, срез, - рисуется ПОД пейджером и стоит на месте
 * ([pageUnder]). Едет только сама страница ([pageSheet]): владелец на живой
 * сборке - «страница съезжает как будто вместе со стопкой снизу».
 */

/**
 * Тона считаются от цвета бумаги, а не задаются числом: на светлой странице
 * объём держат тень на столе и тёмная кромка снизу, на тёмной - блик и фаска.
 */
class PaperTones(val paper: Color, val tableDark: Float = Settings.TABLE_MID) {

    val dark: Boolean = paper.luminance() < 0.18f

    /**
     * Настоящий чёрный - «белым по чёрному» и ночная автоматическая. Её
     * включают ради OLED, где чёрный пиксель просто не горит: ни блика, ни
     * зерна по такой странице, иначе они зажигают её целиком.
     */
    val oled: Boolean = paper.luminance() < 0.02f

    /** Стол, на котором лежит книга. */
    val backdrop: Color =
        if (dark) lerp(paper, Color.White, 0.06f + 0.12f * tableDark)
        else lerp(paper, Color(0xFF2A211A), tableDark)

    /** Цвет отброшенной тени: тёплый, холодная серая на бумаге читается грязью. */
    val cast: Color = if (dark) Color.Black else Color(0xFF2A211A)

    /** Обложка книжного вида: тёплый картон, заметно темнее бумаги. */
    val cover: Color =
        if (dark) lerp(paper, Color(0xFF6B4A2A), 0.38f) else lerp(paper, Color(0xFF6B4A2A), 0.62f)

    /** Срез блока страниц под верхней: та же бумага, но в тени переплёта. */
    val block: Color = lerp(paper, cover, 0.16f)

    val sheen: Float = if (oled) 0f else if (dark) 0.06f else 0.05f
    val foot: Float = if (oled) 0f else if (dark) 0.10f else 0.05f
    val rimLight: Float = if (oled) 0.10f else if (dark) 0.14f else 0.55f
    val rimShade: Float = if (dark) 0.22f else 0.10f
    /** Зерно по странице - вдвое слабее, чем по столу: под буквами оно мешает. */
    val grain: Float = if (oled) 0f else if (dark) 0.025f else 0.018f
    val tableGrain: Float = if (dark) 0.05f else 0.035f

    fun shadow(alpha: Float): Color =
        if (dark) Color.Black.copy(alpha = alpha * 0.65f) else Color(0xFF2E2418).copy(alpha = alpha)

    fun light(alpha: Float): Color = Color.White.copy(alpha = alpha)

    /**
     * Кромка страницы в стопке. [depth] - 0 у ближней к нам, 1 у самой
     * глубокой: чем глубже, тем ближе к столу, то есть дальше от света.
     */
    fun deck(depth: Float): Color = lerp(paper, backdrop, 0.10f + 0.55f * depth)

    /** То же для среза блока в книге: он уходит не в стол, а под обложку. */
    fun cut(depth: Float): Color = lerp(paper, cover, 0.10f + 0.5f * depth)
}

/**
 * Что включено в виде страницы.
 *
 * Каждый слой - свой тумблер, как у плашек Правки: владелец хочет крутить
 * объём, а не получать его готовым.
 */
data class PageLook(
    val style: String,
    /** Поле от края экрана: сколько стола видно вокруг книги. */
    val margin: Dp,
    val shadow: String,
    val bevel: Boolean,
    val sheen: Boolean,
    val grain: Boolean,
) {
    val volume: Boolean get() = style == Settings.PAGE_VOLUME
    val flat: Boolean get() = style == Settings.PAGE_FLAT
}

/** Книга: сколько в ней бумаги, сколько её позади и как она разложена. */
data class BookShape(
    /** Толщина книги: от объёма текста, а не от места в нём. */
    val thickness: Dp,
    /** 0..1 - сколько прочитано. */
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
 * дают одно и то же - место на две полосы. Порог 600 dp; и только при листании
 * страницами - в ленте разворота быть не может.
 */
fun spreadOn(mode: String, paged: Boolean, width: Dp): Boolean = paged && when (mode) {
    Settings.SPREAD_ON -> true
    Settings.SPREAD_OFF -> false
    else -> width >= 600.dp
}

/** Какой стороной страница повёрнута к корешку. */
enum class PageSide { SINGLE, LEFT, RIGHT }

/**
 * Мерки: поля от края экрана, скругление, кант обложки, корешок и колода.
 *
 * От места в книге не зависят нарочно: разбивка на страницы меряется в этой
 * ширине и высоте, и тающая колода гоняла бы пересчёт при каждом
 * перелистывании. Меняется только то, что нарисовано внутри.
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
    /** Кант обложки: от её края до блока страниц. */
    val cover: Dp,
    /** Корешок: щель между страницами разворота (или полоса слева у одной). */
    val spine: Dp,
)

/** Сколько кромок видно сейчас: колода тает по мере чтения. */
fun rimsNow(metrics: CardMetrics, shape: BookShape): Int {
    if (metrics.rims <= 0) return 0
    val left = (1f - shape.progress).coerceIn(0f, 1f)
    return ceil(metrics.rims * left).toInt().coerceIn(0, metrics.rims)
}

fun cardMetrics(look: PageLook, shape: BookShape): CardMetrics = when (look.style) {
    Settings.PAGE_FLAT -> CardMetrics(0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0, 0.dp, 0.dp)

    Settings.PAGE_VOLUME -> CardMetrics(
        side = look.margin,
        top = look.margin,
        bottom = look.margin,
        // У книги углы почти прямые: скруглять их как карточку значит потерять
        // переплёт, у него кант жёсткий.
        radius = 6.dp,
        deckStep = 0.dp,
        deckInset = 0.dp,
        rims = 3,
        cover = 7.dp,
        spine = 16.dp,
    )

    Settings.PAGE_SOFT -> CardMetrics(
        side = look.margin,
        top = look.margin,
        bottom = look.margin,
        radius = CARD_RADIUS,
        deckStep = 0.dp,
        deckInset = 0.dp,
        rims = 0,
        cover = 0.dp,
        spine = 0.dp,
    )

    else -> {
        // Толстая книга - колода в четыре кромки, тонкая - в одну: объём книги
        // виден с первой страницы, а не только в счётчике внизу.
        val rims = (shape.thickness.value / 4f).toInt().coerceIn(1, 4)
        val step = 5.dp
        CardMetrics(
            side = look.margin,
            top = look.margin,
            bottom = look.margin + step * rims,
            radius = CARD_RADIUS,
            deckStep = step,
            deckInset = 5.dp,
            rims = rims,
            cover = 0.dp,
            spine = 0.dp,
        )
    }
}

/** Сколько ширины и высоты у одной страницы отнимает всё, что не текст. */
data class PageChrome(val width: Dp, val height: Dp)

fun pageChrome(look: PageLook, card: CardMetrics, halves: Int): PageChrome = when {
    look.flat -> PageChrome(0.dp, 0.dp)
    // В книге поля, кант и корешок делятся на обе страницы разворота.
    look.volume -> PageChrome(
        width = (card.side * 2 + card.cover * 2 + card.spine) / halves,
        height = card.top + card.bottom + card.cover * 2,
    )
    else -> PageChrome(card.side * 2, card.top + card.bottom)
}

/**
 * Отступы страницы внутри её места.
 *
 * В книге поля экрана держит подложка с обложкой, странице остаются кант и
 * корешок; у карточки наоборот - она сама отходит от краёв экрана, потому что
 * её тень должна лечь на стол.
 */
fun pagePadding(
    look: PageLook,
    card: CardMetrics,
    side: PageSide,
    safeTop: Dp,
    safeBottom: Dp,
): PaddingValues {
    if (look.flat) return PaddingValues(0.dp)
    val under = underPadding(card, safeTop, safeBottom)
    if (!look.volume) return under
    // В книге к полям добавляются кант обложки и корешок - с той стороны,
    // которой страница к нему повёрнута. С внутренней стороны разворота поля
    // экрана не нужны: там соседняя страница.
    val inner = card.spine / 2
    return PaddingValues(
        start = when (side) {
            PageSide.RIGHT -> inner
            PageSide.SINGLE -> card.side + card.cover + card.spine
            PageSide.LEFT -> card.side + card.cover
        },
        end = when (side) {
            PageSide.LEFT -> inner
            else -> card.side + card.cover
        },
        top = safeTop + card.top + card.cover,
        bottom = safeBottom + card.bottom + card.cover,
    )
}

/**
 * Отступ подложки - обложки или колоды - от краёв экрана. Она стоит на месте,
 * страницы ездят поверх, поэтому мерка у них общая, а padding разный.
 */
fun underPadding(card: CardMetrics, safeTop: Dp, safeBottom: Dp): PaddingValues = PaddingValues(
    start = card.side,
    end = card.side,
    top = safeTop + card.top,
    bottom = safeBottom + card.bottom,
)

/** Стол: плотный тон, виньетка к краям и зерно - как стекло диска у Правки. */
fun Modifier.readerBackdrop(tones: PaperTones, look: PageLook): Modifier =
    if (look.flat) this.background(tones.paper)
    else this
        .background(tones.backdrop)
        .drawWithCache {
            // Ровная заливка читается фоном экрана, затемнённая по углам -
            // поверхностью, на которой что-то лежит.
            val vignette = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.26f)),
                center = Offset(size.width * 0.5f, size.height * 0.42f),
                radius = max(size.width, size.height) * 0.74f,
            )
            onDrawBehind { drawRect(vignette) }
        }
        .then(if (look.grain) Modifier.grain(tones.tableGrain) else Modifier)

/**
 * Подложка под страницами: то, что при перелистывании стоит на месте.
 *
 * У книги это обложка, срез блока и корешок; у стопки - кромки следующих
 * карточек. Рисуется на своём Box под пейджером, поэтому страница уезжает
 * одна, а книга остаётся лежать.
 */
fun Modifier.pageUnder(tones: PaperTones, look: PageLook, shape: BookShape): Modifier {
    if (look.flat || look.style == Settings.PAGE_SOFT) return this
    val m = cardMetrics(look, shape)
    val lift = when (look.shadow) {
        Settings.SHADOW_NONE -> 0.dp
        Settings.SHADOW_DEEP -> 20.dp
        else -> 10.dp
    }
    val corner = RoundedCornerShape(m.radius)
    return if (look.volume) this
        .shadow(lift, corner, clip = false, ambientColor = tones.cast, spotColor = tones.cast)
        .drawBehind { drawVolume(tones, m, shape) }
    else this
        .shadow(lift, RoundedCornerShape(m.radius), clip = false, ambientColor = tones.cast, spotColor = tones.cast)
        .drawBehind { drawDeck(tones, m, rimsNow(m, shape)) }
}

/** Обложка, срез блока и корешок - всё, что в книге не страница. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVolume(
    tones: PaperTones,
    m: CardMetrics,
    shape: BookShape,
) {
    val radius = CornerRadius(m.radius.toPx())
    val coverPx = m.cover.toPx()
    val hair = 1.dp.toPx().coerceAtLeast(1f)
    // Переплёт во всю область.
    drawRoundRect(tones.cover, cornerRadius = radius)
    // Кант ловит свет по верхней кромке - без него обложка плоская, как фон.
    drawRoundRect(
        Brush.verticalGradient(
            0f to tones.light(0.18f),
            0.5f to Color.Transparent,
            1f to tones.shadow(0.18f),
        ),
        cornerRadius = radius,
        style = Stroke(width = hair),
    )
    // Блок страниц внутри переплёта.
    val block = Size(size.width - coverPx * 2f, size.height - coverPx * 2f)
    if (block.width <= 0f || block.height <= 0f) return
    val at = Offset(coverPx, coverPx)
    drawRect(tones.block, topLeft = at, size = block)
    // Срез блока по бокам: слева прочитанное, справа остаток. Вся толщина
    // книги постоянна, меняется только, с какой стороны её больше.
    val cut = (m.spine * 0.45f).toPx()
    val p = shape.progress.coerceIn(0f, 1f)
    val layers = m.rims.coerceAtLeast(1)
    for (i in layers downTo 1) {
        val depth = i / layers.toFloat()
        val leftW = cut * p * depth
        val rightW = cut * (1f - p) * depth
        if (leftW > 0.5f) drawRect(
            tones.cut(depth),
            topLeft = Offset(at.x, at.y),
            size = Size(leftW, block.height),
        )
        if (rightW > 0.5f) drawRect(
            tones.cut(depth),
            topLeft = Offset(at.x + block.width - rightW, at.y),
            size = Size(rightW, block.height),
        )
    }
    // Корешок: щель между страницами (или полоса слева у одной страницы).
    val spinePx = m.spine.toPx()
    val spineAt = if (shape.spread) size.width / 2f - spinePx / 2f else at.x
    drawRect(
        Brush.horizontalGradient(
            0f to tones.shadow(0.20f),
            0.5f to tones.shadow(0.55f),
            1f to tones.shadow(0.20f),
            startX = spineAt,
            endX = spineAt + spinePx,
        ),
        topLeft = Offset(spineAt, at.y),
        size = Size(spinePx, block.height),
    )
}

/** Кромки следующих карточек под верхней. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDeck(
    tones: PaperTones,
    m: CardMetrics,
    rims: Int,
) {
    if (rims <= 0) return
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
        // Светлая нить по кромке каждой: без неё соседние сливаются в одну
        // серую полосу, и колода читается тенью, а не стопкой.
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
 * Сама страница. В книге - лист бумаги с тенью от сгиба у корешка; в стопке -
 * карточка со всеми слоями объёма.
 */
fun Modifier.pageSheet(
    tones: PaperTones,
    look: PageLook,
    shape: BookShape,
    side: PageSide,
): Modifier {
    if (look.flat) return this.background(tones.paper)
    val m = cardMetrics(look, shape)
    if (look.volume) {
        val gutter = side != PageSide.SINGLE
        return this
            .background(tones.paper)
            .drawWithCache {
                // Тень от сгиба ложится на строку у корешка - как в книге.
                val w = size.width
                val band = (w * 0.10f).coerceAtMost(46.dp.toPx())
                val fold = if (side == PageSide.LEFT) Brush.horizontalGradient(
                    0f to Color.Transparent,
                    1f to tones.shadow(0.20f),
                    startX = w - band,
                    endX = w,
                ) else Brush.horizontalGradient(
                    0f to tones.shadow(0.20f),
                    1f to Color.Transparent,
                    startX = 0f,
                    endX = band,
                )
                val at = if (side == PageSide.LEFT) Offset(w - band, 0f) else Offset.Zero
                onDrawWithContent {
                    drawContent()
                    if (gutter || side == PageSide.SINGLE) {
                        drawRect(fold, topLeft = at, size = Size(band, size.height))
                    }
                }
            }
            .then(if (look.grain) Modifier.grain(tones.grain) else Modifier)
    }
    val corner = RoundedCornerShape(m.radius)
    val lift = when (look.shadow) {
        Settings.SHADOW_NONE -> 0.dp
        Settings.SHADOW_DEEP -> 20.dp
        else -> 10.dp
    }
    return this
        .shadow(lift, corner, clip = false, ambientColor = tones.cast, spotColor = tones.cast)
        .clip(corner)
        .background(tones.paper)
        // Блик полосой по верхней трети, к концу полосы в ноль: градиент во всю
        // высоту читался бы не объёмом, а заливкой (у Правки та же история).
        .then(
            if (!look.sheen) Modifier else Modifier
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
        )
        // Фаска: светлая линия сверху, тёмная снизу, посередине её нет. Ровный
        // кант по периметру читался бы рамкой виджета, разный - толщиной.
        .then(
            if (!look.bevel) Modifier else Modifier.border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = tones.rimLight),
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = tones.rimShade),
                ),
                shape = corner,
            )
        )
        .then(if (look.grain) Modifier.grain(tones.grain) else Modifier)
}

/**
 * Насколько страница сдвинута от своего места: 0 - лежит сверху, 1 - смахнута,
 * -1 - ещё под верхней.
 */
fun PagerState.turnOffset(page: Int): Float = (currentPage - page) + currentPageOffsetFraction

/**
 * Перелистывание.
 *
 * [offset] читается в фазе рисования, а не в перекомпоновке: пейджер двигает
 * страницу каждый кадр, и пересобирать на это дерево незачем. Порядок
 * рисования задаёт `zIndex` в самой читалке - ранняя страница лежит поверх
 * поздней.
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

    // TURN_DECK: верхнюю смахивают, следующая поднимается снизу.
    else -> this.graphicsLayer {
        val off = offset()
        when {
            off > 0f -> {
                val t = off.coerceAtMost(1f)
                transformOrigin = TransformOrigin(0.5f, 0.9f)
                // Разворот отклоняется вдвое меньше: пара страниц, повёрнутая
                // как одна, читается перекосом экрана, а не броском.
                rotationZ = (if (gentle) -2f else -4f) * t
                val s = 1f - 0.03f * t
                scaleX = s
                scaleY = s
            }
            off > -1f -> {
                // Нижняя лежит на месте, а не едет за пейджером, и всплывает.
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

/**
 * Скругление карточки. Крупное нарочно: у Правки кнопки и плашки круглые, и
 * страница с робким радиусом рядом с ними выглядела бы диалогом системы.
 */
private val CARD_RADIUS = 24.dp
