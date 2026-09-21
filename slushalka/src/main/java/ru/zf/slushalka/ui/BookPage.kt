package ru.zf.slushalka.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.data.Settings
import java.util.Random
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
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

    /**
     * Обложка книжного вида: светлый крафт, как на присланной владельцем
     * фотографии, - не тёмный картон. На ночных темах - тёмная кожа, чтобы
     * книга не светилась ярче страницы.
     */
    val cover: Color =
        if (dark) lerp(paper, Color(0xFF3B2A1A), 0.55f) else lerp(paper, Color(0xFFA87C48), 0.75f)

    /**
     * Форзац - внутренняя сторона той же обложки. Тот же картон, чуть светлее
     * от света: владелец просил, чтобы край книги был под цвет обложки, а не
     * отдельной бежевой полосой.
     */
    val endpaper: Color = lerp(cover, paper, 0.06f)

    /** Бумага обреза - страницы блока с торца, чуть в тени. */
    val block: Color = lerp(paper, Color(0xFF3A3633), 0.06f)

    /** Каптал - плетёная тесьма в корешке, видна сверху и снизу между страницами. */
    val headband: Color = if (dark) Color(0xFF2F6B66) else Color(0xFF3F8F86)

    /**
     * Стол под книгой светлее, чем под колодой: на фотографии свёрстанной
     * книги она лежит на светло-сером, и тень от неё читается именно на нём.
     */
    val bookTable: Color =
        if (dark) backdrop else lerp(paper, Color(0xFF6E6B68), 0.06f + 0.30f * tableDark)

    /** Тень книги на стол: нейтрально-тёплая, с прозрачностью по месту. */
    fun cast(alpha: Float): Color =
        (if (dark) Color.Black else Color(0xFF3A3633)).copy(alpha = alpha)

    val sheen: Float = if (oled) 0f else if (dark) 0.06f else 0.05f
    val foot: Float = if (oled) 0f else if (dark) 0.10f else 0.05f
    val rimLight: Float = if (oled) 0.10f else if (dark) 0.14f else 0.55f
    val rimShade: Float = if (dark) 0.22f else 0.10f

    /**
     * Матовость - как иней на стекле диска Правки (`DiskLook.frostAlpha`, до
     * 0.14), владелец попросил «такую же матовость внутри книги и на столе».
     * Два слоя (см. [matte]): мелкое зерно и волокна. По бумаге слабее, чем по
     * столу и картону: под буквами шероховатость мешает читать. На настоящем
     * чёрном ничего: любой засвет зажигает OLED-страницу целиком.
     */
    val paperFine: Float = if (oled) 0f else if (dark) 0.14f else 0.16f
    val paperFibers: Float = if (oled) 0f else if (dark) 0.06f else 0.07f
    val tableFine: Float = if (dark) 0.16f else 0.20f
    val tableFibers: Float = if (dark) 0.08f else 0.11f
    val coverFine: Float = 0.18f
    val coverFibers: Float = 0.08f

    fun shadow(alpha: Float): Color =
        if (dark) Color.Black.copy(alpha = alpha * 0.65f) else Color(0xFF2E2418).copy(alpha = alpha)

    fun light(alpha: Float): Color = Color.White.copy(alpha = alpha)

    /**
     * Кромка страницы в стопке. [depth] - 0 у ближней к нам, 1 у самой
     * глубокой: чем глубже, тем ближе к столу, то есть дальше от света.
     */
    fun deck(depth: Float): Color = lerp(paper, backdrop, 0.10f + 0.55f * depth)

    /** Линии обреза: страницы блока, видные с торца, - тёмная нить между светлыми. */
    fun cut(depth: Float): Color = lerp(paper, cover, 0.08f + 0.42f * depth)
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
    /** Насколько страницы короче блока сверху и снизу: там виден каптал. */
    val reveal: Dp = 0.dp,
    /**
     * Полоса под обрез с внешней стороны страницы. Постоянная: сам обрез
     * внутри неё то шире, то уже (слева прочитанное, справа остаток), а
     * остаток полосы - форзац. Иначе ширина страницы плыла бы с каждым
     * перелистыванием.
     */
    val cut: Dp = 0.dp,
)

/** Сколько кромок видно сейчас: колода тает по мере чтения. */
fun rimsNow(metrics: CardMetrics, shape: BookShape): Int {
    if (metrics.rims <= 0) return 0
    val left = (1f - shape.progress).coerceIn(0f, 1f)
    return ceil(metrics.rims * left).toInt().coerceIn(0, metrics.rims)
}

fun cardMetrics(look: PageLook, shape: BookShape): CardMetrics = when (look.style) {
    Settings.PAGE_FLAT -> CardMetrics(0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0, 0.dp, 0.dp, 0.dp, 0.dp)

    Settings.PAGE_VOLUME -> CardMetrics(
        side = look.margin,
        top = look.margin,
        bottom = look.margin,
        // У книги углы почти прямые: скруглять их как карточку значит потерять
        // переплёт, у него кант жёсткий.
        radius = 4.dp,
        deckStep = 0.dp,
        deckInset = 0.dp,
        rims = 0,
        cover = 10.dp,
        // Страницы смыкаются, между ними только щель сгиба: чёрная полоса в
        // палец шириной читалась дырой, а не корешком.
        spine = 2.dp,
        reveal = 3.dp,
        cut = 16.dp,
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
    // В книге у каждой страницы своя половина разворота: поле, кант и
    // половина сгиба. На одной странице то же самое - она и есть половина
    // разворота, по которому ездит камера.
    look.volume -> PageChrome(
        width = card.side + card.cover + card.cut + card.spine / 2,
        height = card.top + card.bottom + card.cover * 2 + card.reveal * 2,
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
    val outer = card.side + card.cover + card.cut
    return PaddingValues(
        start = when (side) {
            PageSide.RIGHT -> inner
            // Одна страница в прокрутке: корешок слева, обрез справа.
            PageSide.SINGLE -> card.side + card.cover + card.spine
            PageSide.LEFT -> outer
        },
        end = when (side) {
            PageSide.LEFT -> inner
            else -> outer
        },
        top = safeTop + card.top + card.cover + card.reveal,
        bottom = safeBottom + card.bottom + card.cover + card.reveal,
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
        .background(if (look.volume) tones.bookTable else tones.backdrop)
        .drawWithCache {
            // Свет сверху: стол к низу темнее. Ровная заливка читается фоном
            // экрана, затемнённая к низу и по углам - поверхностью, на которой
            // что-то лежит.
            val fall = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.07f),
                1f to tones.cast(0.10f),
            )
            val vignette = Brush.radialGradient(
                colors = listOf(Color.Transparent, tones.cast(if (look.volume) 0.20f else 0.26f)),
                center = Offset(size.width * 0.5f, size.height * 0.40f),
                radius = max(size.width, size.height) * 0.78f,
            )
            onDrawBehind {
                if (look.volume) drawRect(fall)
                drawRect(vignette)
            }
        }
        .then(
            if (!look.grain) Modifier
            else Modifier.matte(
                if (look.volume) tones.bookTable else tones.backdrop,
                tones.tableFine, tones.tableFibers, TABLE_FIBERS,
            )
        )

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
    // У книги тень своя, слоями (см. drawVolume): системная - ровный ореол
    // одной формы, а под томом на фотографии тень мягкая, гуще к нижнему
    // ребру и с плотной полосой там, где он касается стола.
    val depth = when (look.shadow) {
        Settings.SHADOW_NONE -> 0f
        Settings.SHADOW_DEEP -> 1.45f
        else -> 1f
    }
    return if (look.volume) this
        .drawWithCache {
            // Картон матовый, как стол и страницы: кисти зерна и волокон в тон
            // обложки. Считаются здесь, а не на каждый кадр, - плитка одна.
            val fine = if (look.grain) matteBrush(tones.cover, FINE_SEED, 1f) else null
            val fibers = if (look.grain) matteBrush(tones.cover, FIBERS_SEED, COVER_FIBERS.toPx()) else null
            onDrawBehind { drawVolume(tones, m, shape, depth, fine, fibers) }
        }
    else this
        .shadow(lift, corner, clip = false, ambientColor = tones.cast, spotColor = tones.cast)
        .drawBehind { drawDeck(tones, m, rimsNow(m, shape)) }
}

/**
 * Всё, что в книге не страница: тень на стол, картон обложки, форзац, обрез,
 * корешок и каптал.
 *
 * Собрано по фотографии свёрстанной книги, и правило одно: ни одного элемента
 * без света и тени. Свет сверху-спереди, поэтому верхние кромки светлее,
 * нижние темнее, тень под книгой гуще у нижнего ребра, обрез темнее у обложки
 * и светлее у верхней страницы, а сама страница отбрасывает тень на обрез.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVolume(
    tones: PaperTones,
    m: CardMetrics,
    shape: BookShape,
    /** Сила тени на стол: 0 - без тени, 1 - мягкая, больше - глубокая. */
    depth: Float,
    /** Матовость картона: зерно и волокна в тон обложки, null - выключена. */
    coverFine: Brush?,
    coverFibers: Brush?,
) {
    val w = size.width
    val h = size.height
    val cover = m.cover.toPx()
    val cut = m.cut.toPx()
    val reveal = m.reveal.toPx()
    val spine = m.spine.toPx()
    val hair = 1.dp.toPx().coerceAtLeast(1f)
    val dp = 1.dp.toPx()
    // Углы картона побиты: у каждого свой радиус, идеально ровных нет.
    val radii = floatArrayOf(4f, 6f, 3.5f, 5f).map { it * dp }.toFloatArray()

    // 1. Тень на стол: слоями с убывающей плотностью вместо размытия, каждый
    // следующий шире и ниже - выходит мягкий ореол по форме книги, гуще к
    // нижнему ребру. Плюс плотная контактная полоса там, где ребро касается
    // стола.
    if (depth > 0f) {
        val layers = 16
        for (i in layers downTo 1) {
            val k = i / layers.toFloat()
            val grow = 1.8f * dp * i * depth
            val drop = 1.3f * dp * i * depth
            drawPath(
                wornPath(-grow, -grow * 0.35f + drop, w + grow * 2f, h + grow * 1.35f, radii.map { it + grow * 0.4f }.toFloatArray()),
                tones.cast((0.04f * (1f - k) * (1f - k) + 0.004f) * depth.coerceAtMost(1.2f)),
            )
        }
        drawPath(wornPath(2f * dp, h - dp, w - 4f * dp, 4f * dp, floatArrayOf(2f * dp, 2f * dp, 3f * dp, 3f * dp)), tones.cast(0.22f))
        drawPath(wornPath(dp, h - dp, w - 2f * dp, 9f * dp, floatArrayOf(3f * dp, 3f * dp, 5f * dp, 5f * dp)), tones.cast(0.08f))
    }

    // 2. Обложка: картон, свет сверху, кромка завёрнута, углы потёрты.
    val outline = wornPath(0f, 0f, w, h, radii)
    drawPath(outline, tones.cover)
    if (coverFine != null || coverFibers != null) clipPath(outline) {
        // Шероховатость картона под светом и тенью, не поверх них.
        coverFine?.let { drawRect(it, alpha = tones.coverFine) }
        coverFibers?.let { drawRect(it, alpha = tones.coverFibers) }
    }
    drawPath(
        outline,
        Brush.verticalGradient(
            0f to tones.light(0.10f),
            0.5f to Color.Transparent,
            1f to tones.cast(0.14f),
        ),
    )
    clipPath(outline) {
        // Ребро картона: внутри контура темнее - край завёрнут; по самой
        // кромке светлая нить - ловит свет.
        drawPath(outline, tones.cast(0.14f), style = Stroke(width = 3f * dp))
        drawPath(wornPath(dp, dp, w - 2f * dp, h - 2f * dp, radii), tones.light(0.22f), style = Stroke(width = hair))
        // Потёртости: на углах картон стёрт до светлого, у каждого по-своему.
        listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h)).forEachIndexed { i, corner ->
            val rr = (9f + 4f * jitter(i, 1)) * dp
            drawRect(
                Brush.radialGradient(
                    colors = listOf(tones.light(0.34f + 0.14f * jitter(i, 2)), Color.Transparent),
                    center = corner,
                    radius = rr,
                ),
                topLeft = Offset(corner.x - rr, corner.y - rr),
                size = Size(rr * 2f, rr * 2f),
            )
        }
    }

    // 3. Блок страниц лежит на форзаце - той же обложке с изнанки. Форзац виден
    // там, где обрез ещё тонок.
    val bx = cover
    val by = cover
    val bw = w - cover * 2f
    val bh = h - cover * 2f
    if (bw <= 0f || bh <= 0f) return
    drawRect(tones.endpaper, topLeft = Offset(bx, by), size = Size(bw, bh))

    // Где страницы: внешние края с запасом под обрез. Слева обрез - прочитанное,
    // справа - остаток; сумма постоянна, как толщина книги.
    val p = shape.progress.coerceIn(0f, 1f)
    val lw = cut * p
    val rw = cut * (1f - p)
    val pxL = bx + cut
    val pxR = w - cover - cut

    // 4. Обрез: бумага с торца. У обложки в тени, к верхней странице светлее;
    // страницы - линии через полтора пункта, неровные по концам, как у
    // настоящего блока.
    fun cutBand(x0: Float, width: Float, towardsRight: Boolean) {
        if (width < dp) return
        drawRect(tones.block, topLeft = Offset(x0, by), size = Size(width, bh))
        drawRect(
            Brush.horizontalGradient(
                0f to tones.cast(0.34f),
                0.4f to tones.cast(0.14f),
                1f to tones.cast(0.03f),
                startX = if (towardsRight) x0 else x0 + width,
                endX = if (towardsRight) x0 + width else x0,
            ),
            topLeft = Offset(x0, by),
            size = Size(width, bh),
        )
        var x = x0 + 1.5f * dp
        var i = 0
        while (x < x0 + width) {
            val j1 = jitter(i, 3) * 1.8f * dp
            val j2 = jitter(i, 4) * 1.8f * dp
            drawRect(
                tones.cast(0.14f + 0.10f * jitter(i, 5)),
                topLeft = Offset(x, by + j1),
                size = Size(hair, (bh - j1 - j2).coerceAtLeast(0f)),
            )
            x += 1.5f * dp
            i++
        }
    }
    cutBand(pxL - lw, lw, towardsRight = true)
    cutBand(pxR, rw, towardsRight = false)
    // Блок возвышается над форзацем: тень наружу от его края.
    fun edgeShadow(x0: Float, dir: Int) {
        val band = 4f * dp
        drawRect(
            Brush.horizontalGradient(
                listOf(tones.cast(0.22f), Color.Transparent),
                startX = x0,
                endX = x0 + band * dir,
            ),
            topLeft = Offset(if (dir > 0) x0 else x0 - band, by),
            size = Size(band, bh),
        )
    }
    edgeShadow(pxL - lw, -1)
    edgeShadow(pxR + rw, +1)

    // 5. Верхний и нижний обрез: страницы стопкой под верхней, с её тенью.
    val spanL = pxL - lw
    val spanW = (pxR + rw) - spanL
    for ((yy, down) in listOf(by + bh - reveal to true, by to false)) {
        drawRect(tones.block, topLeft = Offset(spanL, yy), size = Size(spanW, reveal))
        drawRect(
            Brush.verticalGradient(
                listOf(tones.cast(0.26f), tones.cast(0.06f)),
                startY = if (down) yy else yy + reveal,
                endY = if (down) yy + reveal else yy,
            ),
            topLeft = Offset(spanL, yy),
            size = Size(spanW, reveal),
        )
        drawRect(
            tones.cast(0.12f),
            topLeft = Offset(spanL, if (down) yy + 1.5f * dp else yy + reveal - 2f * dp),
            size = Size(spanW, hair),
        )
    }

    // 6. Корешок: щель сгиба - посередине разворота или у корешка одной
    // страницы. Основную тень сгиба несут сами страницы (см. pageSheet).
    val spineAt = if (shape.spread) w / 2f - spine / 2f else bx
    drawRect(tones.cast(0.55f), topLeft = Offset(spineAt, by), size = Size(spine, bh))

    // 7. Каптал: плетёная тесьма в корешке, видна в зазоре над страницами и
    // под ними. Стежки косые, светлый через тёмный; с той стороны, что глубже
    // в сгибе, тень.
    if (shape.spread) {
        val hw = 22f * dp
        val hh = reveal + 4f * dp
        val hx = w / 2f - hw / 2f
        for ((yy, top) in listOf(by - dp to true, by + bh - hh + dp to false)) {
            clipRect(hx, yy, hx + hw, yy + hh) {
                drawRect(tones.headband, topLeft = Offset(hx, yy), size = Size(hw, hh))
                var x = hx - hh
                var i = 0
                while (x < hx + hw + hh) {
                    drawLine(
                        if (i % 2 == 0) tones.light(0.32f) else Color.Black.copy(alpha = 0.22f),
                        start = Offset(x, yy + hh),
                        end = Offset(x + hh * 0.7f, yy),
                        strokeWidth = 1.1f * dp,
                    )
                    x += 2.2f * dp
                    i++
                }
                drawRect(
                    Brush.verticalGradient(
                        listOf(tones.cast(0.30f), Color.Transparent),
                        startY = if (top) yy else yy + hh,
                        endY = if (top) yy + hh else yy,
                    ),
                    topLeft = Offset(hx, yy),
                    size = Size(hw, hh),
                )
            }
        }
    }
}

/** Прямоугольник с разными радиусами углов: побитые уголки картона. */
private fun wornPath(x: Float, y: Float, w: Float, h: Float, r: FloatArray): Path = Path().apply {
    moveTo(x + r[0], y)
    lineTo(x + w - r[1], y)
    quadraticTo(x + w, y, x + w, y + r[1])
    lineTo(x + w, y + h - r[2])
    quadraticTo(x + w, y + h, x + w - r[2], y + h)
    lineTo(x + r[3], y + h)
    quadraticTo(x, y + h, x, y + h - r[3])
    lineTo(x, y + r[0])
    quadraticTo(x, y, x + r[0], y)
    close()
}

/**
 * Детерминированный «шум» для неровностей: одна и та же книга - одни и те же
 * зазубрины, ничего не кипит между кадрами.
 */
private fun jitter(i: Int, k: Int): Float {
    val v = sin(i * 12.9898 + k * 78.233) * 43758.5453
    return (v - floor(v)).toFloat()
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
        return this
            .background(tones.paper)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val dp = 1.dp.toPx()
                val gutterLeft = side != PageSide.LEFT     // корешок слева у правой и одиночной
                // Сгиб двумя ступенями: тёмная щель у самого корешка, крутой
                // склон, длинный мягкий хвост - и блик там, где бумага снова
                // выходит на свет. Владелец: «внутри тень, а потом сразу мягче и
                // градиентом».
                val band = (w * 0.22f).coerceIn(36f * dp, 80f * dp)
                val stops = arrayOf(
                    0f to tones.cast(0.36f),
                    0.05f to tones.cast(0.22f),
                    0.16f to tones.cast(0.10f),
                    0.55f to tones.cast(0.03f),
                    1f to Color.Transparent,
                )
                val fold = if (gutterLeft) Brush.horizontalGradient(*stops, startX = 0f, endX = band)
                else Brush.horizontalGradient(*stops, startX = w, endX = w - band)
                val foldAt = if (gutterLeft) Offset.Zero else Offset(w - band, 0f)
                val glowFrom = if (gutterLeft) band * 0.45f else w - band * 0.9f
                val glow = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to tones.light(0.07f),
                    1f to Color.Transparent,
                    startX = glowFrom,
                    endX = glowFrom + band * 0.45f,
                )
                // Свет сверху: страница чуть светлее у верха, чуть темнее у низа.
                val fall = Brush.verticalGradient(
                    0f to tones.light(0.10f),
                    0.4f to Color.Transparent,
                    1f to tones.cast(0.05f),
                )
                // Верхняя страница возвышается над обрезом: наружу от внешнего
                // края тень на обрез, а сама кромка листа ловит свет.
                val outerX = if (gutterLeft) w else 0f
                val dir = if (gutterLeft) 1f else -1f
                val castOut = Brush.horizontalGradient(
                    listOf(tones.cast(0.26f), Color.Transparent),
                    startX = outerX,
                    endX = outerX + 3f * dp * dir,
                )
                onDrawWithContent {
                    drawRect(castOut, topLeft = Offset(if (dir > 0) outerX else outerX - 3f * dp, 0f), size = Size(3f * dp, h))
                    drawRect(fall)
                    drawContent()
                    drawRect(fold, topLeft = foldAt, size = Size(band, h))
                    drawRect(glow, topLeft = Offset(glowFrom, 0f), size = Size(band * 0.45f, h))
                    drawRect(tones.light(0.55f), topLeft = Offset(if (gutterLeft) w - 1f else 0f, 0f), size = Size(1f, h))
                }
            }
            .then(if (look.grain) Modifier.matte(tones.paper, tones.paperFine, tones.paperFibers, PAPER_FIBERS) else Modifier)
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
        .then(if (look.grain) Modifier.matte(tones.paper, tones.paperFine, tones.paperFibers, PAPER_FIBERS) else Modifier)
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
 * Живая книга на одной странице: лист переворачивается вокруг корешка, а
 * камера едет за ним.
 *
 * Разворот шириной в два экрана лежит на месте, страницы - его половины.
 * С левой на правую камера просто едет вперёд, и пейджер делает это сам.
 * С правой на следующую левую лист переворачивается: правая страница
 * поднимается вокруг корешка (её левый край) и уходит за него, а из-за
 * корешка опускается её оборот - левая страница следующего разворота, у
 * которой корешок справа. Обе крепятся к корешку, а корешок вместе с камерой
 * едет от левого края экрана к правому: `translationX` у каждой удваивает
 * сдвиг пейджера в обратную сторону, чтобы двигаться с книгой, а не с
 * пальцем. Встречаются они на 90°, где лист виден ребром, - подмены не
 * заметно.
 */
fun Modifier.liveBookTurn(side: PageSide, offset: () -> Float): Modifier = this.graphicsLayer {
    val off = offset()
    val w = size.width
    when (side) {
        PageSide.RIGHT -> if (off > 0f && off < 1f) {
            cameraDistance = bookCamera(w, density)
            translationX = 2f * w * off
            transformOrigin = TransformOrigin(0f, 0.5f)
            rotationY = -180f * off
            alpha = if (off < 0.5f) 1f else 0f
        }
        PageSide.LEFT -> if (off > -1f && off < 0f) {
            val t = 1f + off
            cameraDistance = bookCamera(w, density)
            translationX = -2f * w * (1f - t)
            transformOrigin = TransformOrigin(1f, 0.5f)
            rotationY = 90f * (2f - 2f * t).coerceIn(0f, 1f)
            alpha = if (t > 0.5f) 1f else 0f
        }
        PageSide.SINGLE -> Unit
    }
}

/**
 * Камера для поворота листа - в двух с половиной его ширинах. Единица
 * `cameraDistance` в Compose - не пиксель, а дюйм: RenderNode делит пиксели
 * на dpi (у View по умолчанию 1280*density px - те самые 8.0).
 */
private fun bookCamera(widthPx: Float, density: Float): Float = 2.5f * widthPx / (160f * density)

/**
 * Матовая поверхность: бумага, стол, картон.
 *
 * Взято у диска Правки - иней на стекле (`DiskController.drawFrost`,
 * `ui/CardLook.kt`): плитка шума 64x64 по пикселям экрана, повтором. Два
 * отличия. Плитка не серая, а в тон поверхности - точки светлее и темнее её
 * цвета, - иначе на светлой бумаге зерно сереет страницу, а на стекле диска
 * этого не видно, оно на просвет. И слоёв два: мелкое зерно ([fine], иней) и
 * оно же крупнее и размытое ([fibers], волокна) - без второго материал
 * читается телевизионным шумом, а не бумагой или картоном.
 *
 * Рисуется ПОД содержимым (`drawBehind`): поверх текста шероховатость
 * читалась бы грязным стеклом, а не материалом.
 */
fun Modifier.matte(base: Color, fine: Float, fibers: Float, fiberScale: Dp): Modifier =
    if (fine <= 0f && fibers <= 0f) this else drawWithCache {
        val fineBrush = matteBrush(base, FINE_SEED, 1f)
        val fiberBrush = matteBrush(base, FIBERS_SEED, fiberScale.toPx())
        onDrawBehind {
            if (fine > 0f) drawRect(fineBrush, alpha = fine)
            if (fibers > 0f) drawRect(fiberBrush, alpha = fibers)
        }
    }

/**
 * Кисть зерна в тон [base], растянутая в [scale] раз: растянутую плитку
 * шейдер сглаживает билинейно, и точки расплываются в мягкие пятна - волокна.
 */
private fun matteBrush(base: Color, seed: Long, scale: Float): ShaderBrush {
    val shader = ImageShader(matteTile(base, seed), TileMode.Repeated, TileMode.Repeated)
    if (scale != 1f) shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale) })
    return ShaderBrush(shader)
}

/**
 * Плитки по цвету и семени, чтобы не считать шум заново на каждую страницу:
 * цветов у бумаги, стола и картона считанные единицы.
 */
private val matteTiles = HashMap<Pair<Int, Long>, ImageBitmap>()

/**
 * Плитка шума в тон поверхности. Половина точек светлее базового цвета (к
 * белому, но не до конца), половина темнее (к тёплому тёмному): средний тон
 * почти не сдвигается, значит зерно не красит поверхность, а только делает её
 * шероховатой. Seed постоянный: зерно не должно кипеть между перерисовками.
 */
private fun matteTile(base: Color, seed: Long): ImageBitmap = matteTiles.getOrPut(base.toArgb() to seed) {
    val size = 64
    val random = Random(seed)
    val dark = lerp(base, Color(0xFF2A211A), 0.55f)
    val pixels = IntArray(size * size)
    for (i in pixels.indices) {
        val u = random.nextFloat() * 2f - 1f
        val c = if (u < 0f) lerp(base, Color.White, -u * 0.7f) else lerp(base, dark, u)
        pixels[i] = c.toArgb()
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private const val FINE_SEED = 20_260_920L
private const val FIBERS_SEED = 7L

/** Размер волокна: у бумаги мельче, у картона и стола крупнее. */
private val PAPER_FIBERS = 3.dp
private val COVER_FIBERS = 5.dp
private val TABLE_FIBERS = 6.dp

/** Блик занимает верхнюю треть, затенение - нижнюю пятую: как у плашек Правки. */
private const val SHEEN_SPAN = 0.34f
private const val FOOT_SPAN = 0.2f

/**
 * Скругление карточки. Крупное нарочно: у Правки кнопки и плашки круглые, и
 * страница с робким радиусом рядом с ними выглядела бы диалогом системы.
 */
private val CARD_RADIUS = 24.dp
