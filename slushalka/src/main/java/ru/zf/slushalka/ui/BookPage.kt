package ru.zf.slushalka.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.data.Settings
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Как выглядит страница читалки.
 *
 * Два мира, и оба - предметы, а не заливки экрана.
 *
 * **Книжный** - настоящий том: переплёт кантом по краям, плетёный корешок,
 * обрез блока, каптал, тень на столе. Собран по фотографии свёрстанной книги,
 * которую прислал владелец, и по его правилу: «не должно быть ни одного
 * элемента, который мы рисуем без света и тени».
 *
 * **Стопка** - колода карточек в языке Правки (`ui/CardLook.kt`,
 * `trigger/BubbleSkin.kt`): плотный тон, блик полосой по верхней трети,
 * затенение по нижней пятой, фаска по кромке.
 *
 * Общее правило обоих: всё, что не должно ехать при перелистывании - книга,
 * колода, - рисуется ПОД пейджером и стоит на месте ([pageUnder]). Едет
 * только сама страница ([pageSheet]).
 *
 * **Книга целиком в экране.** Узкий экран показывал половину разворота, и
 * половина уходила за край: «левый край книги вылезает, а правая страница
 * вылезает». Теперь на узком экране книга лежит в экране целиком - корешок
 * слева, обрез справа, - а страница не уезжает вбок, а **заворачивается**
 * ([pageFold]): линия сгиба идёт справа налево, за ней виден оборот листа, и
 * из-под него открывается следующая страница. Ничего за край не выходит.
 */

/**
 * Тона считаются от цвета бумаги, а не задаются числом: на светлой странице
 * объём держат тень на столе и тёмная кромка снизу, на тёмной - блик и фаска.
 *
 * [coverSeed] - цвет, снятый с обложки книги (`Covers.coverTone`). Владелец:
 * «цвет обложки книги изнутри должен совпадать с обложкой самой книги из
 * fb2». Прямо его брать нельзя - обложка бывает кислотной или чёрной, - он
 * приводится к картону в [bookCloth].
 */
class PaperTones(
    val paper: Color,
    val tableDark: Float = Settings.TABLE_MID,
    coverSeed: Color? = null,
) {

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
     * Переплёт: цвет книги, если он снят с обложки, иначе светлый крафт - как
     * на фотографии, которую прислал владелец.
     */
    val cover: Color = coverSeed?.let { bookCloth(it, dark) }
        ?: if (dark) lerp(paper, Color(0xFF3B2A1A), 0.55f) else lerp(paper, Color(0xFFA87C48), 0.75f)

    /**
     * Форзац - внутренняя сторона той же обложки: тот же картон, чуть светлее
     * от света. Край книги должен быть под цвет обложки, а не отдельной
     * бежевой полосой.
     */
    val endpaper: Color = lerp(cover, paper, 0.10f)

    /** Корешок глубже всего в тени: туда свет почти не доходит. */
    val spine: Color = lerp(cover, Color.Black, if (dark) 0.30f else 0.22f)

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
     * Матовость - как иней на стекле диска Правки: плитка шума по пикселям
     * экрана, но в тон поверхности. Зерно мелкое: владелец на живой сборке
     * попросил мельче, чем было, - крупные волокна читались не бумагой, а
     * рябью. По столу матовости нет вовсе, там только свет и тень.
     */
    val paperFine: Float = if (oled) 0f else if (dark) 0.10f else 0.11f
    val paperFibers: Float = if (oled) 0f else if (dark) 0.035f else 0.04f
    val coverFine: Float = 0.16f
    val coverFibers: Float = 0.06f

    fun shadow(alpha: Float): Color =
        if (dark) Color.Black.copy(alpha = alpha * 0.65f) else Color(0xFF2E2418).copy(alpha = alpha)

    fun light(alpha: Float): Color = Color.White.copy(alpha = alpha)

    /**
     * Кромка страницы в стопке. [depth] - 0 у ближней к нам, 1 у самой
     * глубокой: чем глубже, тем ближе к столу, то есть дальше от света.
     */
    fun deck(depth: Float): Color = lerp(paper, backdrop, 0.10f + 0.55f * depth)
}

/**
 * Цвет обложки книги, приведённый к переплёту.
 *
 * Переплёт не бывает ни кислотным, ни угольным: ткань или крашеный картон
 * держатся в узкой вилке по насыщенности и светлоте. Кислотный цвет с обложки
 * приглушается, чёрный поднимается, белый опускается - книга остаётся узнаваемо
 * «той самой», но выглядит переплётом, а не постером.
 */
fun bookCloth(seed: Color, dark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    hsv[1] = hsv[1].coerceIn(0.10f, 0.46f)
    hsv[2] = if (dark) hsv[2].coerceIn(0.16f, 0.34f) else hsv[2].coerceIn(0.34f, 0.66f)
    return Color(android.graphics.Color.HSVToColor(hsv))
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
    /**
     * Половина разворота: узкий экран показывает одну страницу настоящего
     * разворота, а не книгу из одной страницы. Владелец: «сделал бы так, что
     * это половина от вида двух страниц, но с корешком».
     */
    val half: Boolean = false,
) {
    /** Книга раскрыта: корешок посередине, страницы по обе стороны от него. */
    val opened: Boolean get() = spread || half
}

/**
 * Сколько соседней половины видно за корешком.
 *
 * Без этого «подглядывания» экран резал книгу ровно по сгибу, и она читалась
 * не раскрытой книгой, а обрубком: владелец на той сборке сказал «левый край
 * книги вылезает». Полоска чужой страницы за корешком объясняет глазу, что
 * книга шире экрана, и всё встаёт на место.
 */
val BOOK_PEEK = 22.dp

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
 * ширине и высоте, и тающий обрез гонял бы пересчёт при каждом
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
    /** Корешок: плетёная полоса слева у одной страницы, щель сгиба в развороте. */
    val spine: Dp,
    /** Насколько страницы короче блока сверху и снизу: там виден каптал. */
    val reveal: Dp = 0.dp,
    /**
     * Полоса под обрез с внешней стороны страницы. Постоянная: сам обрез
     * внутри неё то шире, то уже, а остаток полосы - форзац. Иначе ширина
     * страницы плыла бы с каждым перелистыванием.
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
        // Кант, корешок и обрез отнимают ширину у текста, поэтому они ровно
        // такие, чтобы читаться книгой, и ни точкой больше.
        cover = 8.dp,
        // На одной странице корешок - настоящая полоса с плетением, в
        // развороте от него видна только щель сгиба. У толстой книги корешок
        // шире: он и есть толщина блока.
        // В раскрытой книге от корешка видна щель сгиба; на закрытой
        // половине - его полоса с плетением.
        spine = if (shape.opened) 3.dp else (7f + shape.thickness.value * 0.45f).coerceIn(9f, 16f).dp,
        reveal = 2.5.dp,
        // Обрез по толщине книги: у повести торец узкий, у тома широкий.
        // Мерка считается на книгу и при листании не меняется, поэтому
        // разбивку на страницы это не гоняет.
        cut = (5f + shape.thickness.value * 0.8f).coerceIn(9f, 20f).dp,
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

fun pageChrome(look: PageLook, card: CardMetrics, halves: Int, half: Boolean = false): PageChrome = when {
    look.flat -> PageChrome(0.dp, 0.dp)
    // Половина разворота: с одной стороны край книги, с другой - корешок и
    // полоска соседней страницы за ним.
    look.volume && half -> PageChrome(
        width = card.side + card.cover + card.cut + card.spine / 2 + BOOK_PEEK,
        height = card.top + card.bottom + card.cover * 2 + card.reveal * 2,
    )
    // В развороте у каждой страницы своя половина: поле, кант и половина щели.
    look.volume && halves > 1 -> PageChrome(
        width = card.side + card.cover + card.cut + card.spine / 2,
        height = card.top + card.bottom + card.cover * 2 + card.reveal * 2,
    )
    // Одна страница: слева корешок, справа обрез, кант с обеих сторон.
    look.volume -> PageChrome(
        width = card.side * 2 + card.cover * 2 + card.spine + card.cut,
        height = card.top + card.bottom + card.cover * 2 + card.reveal * 2,
    )
    else -> PageChrome(card.side * 2, card.top + card.bottom)
}

/**
 * Отступы страницы внутри её места.
 *
 * В книге поля экрана держит подложка с обложкой, странице остаются кант,
 * корешок и обрез; у карточки наоборот - она сама отходит от краёв экрана,
 * потому что её тень должна лечь на стол.
 */
fun pagePadding(
    look: PageLook,
    card: CardMetrics,
    side: PageSide,
    safeTop: Dp,
    safeBottom: Dp,
    /** Половина разворота: за корешком видна полоска соседней страницы. */
    half: Boolean = false,
): PaddingValues {
    if (look.flat) return PaddingValues(0.dp)
    val under = underPadding(card, safeTop, safeBottom)
    if (!look.volume) return under
    // На половине разворота страница стоит там, где она окажется, когда
    // камера доедет до своей стороны: у корешка плюс полоска подглядывания.
    val inner = card.spine / 2 + if (half) BOOK_PEEK else 0.dp
    val outer = card.side + card.cover + card.cut
    return PaddingValues(
        start = when (side) {
            PageSide.RIGHT -> inner
            // Одна страница: корешок слева во всю ширину, обрез справа.
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

/**
 * Стол: плотный тон, падение света к низу и виньетка к краям.
 *
 * Без зерна нарочно. Оно тут было, и владелец на живой сборке сказал убрать:
 * «на столе зерно убираем, просто мягкую тень». Поверхность держат свет и
 * тень, а не шум.
 */
fun Modifier.readerBackdrop(tones: PaperTones, look: PageLook): Modifier =
    if (look.flat) this.background(tones.paper)
    else this
        .background(if (look.volume) tones.bookTable else tones.backdrop)
        .drawWithCache {
            // Свет сверху: стол к низу темнее. Ровная заливка читается фоном
            // экрана, затемнённая к низу и по углам - поверхностью.
            val fall = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.07f),
                1f to tones.cast(0.10f),
            )
            val vignette = Brush.radialGradient(
                colors = listOf(Color.Transparent, tones.cast(if (look.volume) 0.18f else 0.26f)),
                center = Offset(size.width * 0.5f, size.height * 0.40f),
                radius = max(size.width, size.height) * 0.82f,
            )
            onDrawBehind {
                if (look.volume) drawRect(fall)
                drawRect(vignette)
            }
        }

/**
 * Подложка под страницами: то, что при перелистывании стоит на месте.
 *
 * У книги это переплёт, форзац, обрез и корешок; у стопки - кромки следующих
 * карточек.
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
            // Кисти материала считаются на размер, а не на кадр.
            val fine = if (look.grain) matteBrush(tones.cover, FINE_SEED, 1f) else null
            val fibers = if (look.grain) matteBrush(tones.cover, FIBERS_SEED, COVER_FIBERS.toPx()) else null
            val cloth = clothBrush(tones.spine, CLOTH_THREAD.toPx())
            val band = headbandBrush(tones.headband, tones.paper, HEADBAND_THREAD.toPx())
            onDrawBehind { drawVolume(tones, m, shape, depth, fine, fibers, cloth, band) }
        }
    else this
        .shadow(lift, corner, clip = false, ambientColor = tones.cast, spotColor = tones.cast)
        .drawBehind { drawDeck(tones, m, rimsNow(m, shape)) }
}

/**
 * Всё, что в книге не страница: тень на стол, картон переплёта, форзац, обрез,
 * корешок и каптал.
 *
 * Собрано по фотографии свёрстанной книги, и правило одно: ни одного элемента
 * без света и тени. Свет сверху-спереди, поэтому верхние кромки светлее,
 * нижние темнее, тень под книгой гуще у нижнего ребра, обрез темнее у обложки
 * и светлее у верхней страницы, а сама страница отбрасывает тень на обрез.
 */
private fun DrawScope.drawVolume(
    tones: PaperTones,
    m: CardMetrics,
    shape: BookShape,
    /** Сила тени на стол: 0 - без тени, 1 - мягкая, больше - глубокая. */
    depth: Float,
    coverFine: Brush?,
    coverFibers: Brush?,
    cloth: Brush,
    headband: Brush,
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
    // нижнему ребру. Плюс плотная контактная полоса у самого ребра.
    if (depth > 0f) {
        val layers = 16
        for (i in layers downTo 1) {
            val k = i / layers.toFloat()
            val grow = 1.8f * dp * i * depth
            val drop = 1.3f * dp * i * depth
            // Свет под 145 градусами, значит тень уходит вправо и вниз.
            val side = drop * 0.7f
            drawPath(
                wornPath(-grow + side, -grow * 0.35f + drop, w + grow * 2f, h + grow * 1.35f, radii.map { it + grow * 0.4f }.toFloatArray()),
                tones.cast((0.04f * (1f - k) * (1f - k) + 0.004f) * depth.coerceAtMost(1.2f)),
            )
        }
        drawPath(wornPath(2f * dp, h - dp, w - 4f * dp, 4f * dp, floatArrayOf(2f * dp, 2f * dp, 3f * dp, 3f * dp)), tones.cast(0.22f))
        drawPath(wornPath(dp, h - dp, w - 2f * dp, 9f * dp, floatArrayOf(3f * dp, 3f * dp, 5f * dp, 5f * dp)), tones.cast(0.08f))
    }

    // 2. Переплёт: картон, свет сверху, кромка завёрнута, углы потёрты.
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

    // 3. Блок страниц лежит на форзаце - той же обложке с изнанки.
    val bx = cover
    val by = cover
    val bw = w - cover * 2f
    val bh = h - cover * 2f
    if (bw <= 0f || bh <= 0f) return
    drawRect(tones.endpaper, topLeft = Offset(bx, by), size = Size(bw, bh))

    val p = shape.progress.coerceIn(0f, 1f)

    if (shape.opened) {
        // Разворот: обрез с обеих сторон - слева прочитанное, справа остаток.
        val lw = cut * p
        val rw = cut * (1f - p)
        val pxL = bx + cut
        val pxR = w - cover - cut
        cutBand(tones, pxL - lw, by, lw, bh, towardsRight = true)
        cutBand(tones, pxR, by, rw, bh, towardsRight = false)
        edgeShadow(tones, pxL - lw, by, bh, -1)
        edgeShadow(tones, pxR + rw, by, bh, +1)
        blockEnds(tones, pxL - lw, (pxR + rw) - (pxL - lw), by, bh, reveal)
        // Сгиб: щель в волос и тень по обе стороны от неё. Полоса в цвет
        // переплёта читалась чертой, проведённой по белому листу, - владелец
        // это и увидел: «корешок красится в цвет обложки, а он просто тень».
        val at = w / 2f
        val throat = spine * 1.6f
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.35f to tones.cast(0.30f),
                0.5f to tones.cast(0.62f),
                0.65f to tones.cast(0.30f),
                1f to Color.Transparent,
                startX = at - throat,
                endX = at + throat,
            ),
            topLeft = Offset(at - throat, by),
            size = Size(throat * 2f, bh),
        )
        drawRect(tones.cast(0.72f), topLeft = Offset(at - hair / 2f, by), size = Size(hair, bh))
        headbands(tones, headband, w / 2f, by, bh, reveal, 22f * dp)
        return
    }

    // 4. Одна страница: корешок слева во всю высоту, обрез справа.
    val sx = bx
    val px = sx + spine
    // Обрез справа - это остаток книги: он тает по мере чтения, но торец
    // блока виден всегда, иначе к концу книга становится плоской.
    val rw = cut * (1f - p) + 2f * dp
    val cutX = w - cover - rw
    cutBand(tones, cutX, by, rw, bh, towardsRight = false)
    edgeShadow(tones, cutX + rw, by, bh, +1)
    blockEnds(tones, px, (cutX + rw) - px, by, bh, reveal)

    // Корешок: переплётная ткань. Не полоса одного цвета - плетение: владелец
    // о прежнем корешке сказал «очень компьютерно ровный, не бывает ровных, он
    // плетёный». Сверху цилиндрическая тень: корешок круглый и уходит в тень.
    clipRect(sx, by - cover, sx + spine, by + bh + cover) {
        drawRect(tones.spine, topLeft = Offset(sx, by - cover), size = Size(spine, bh + cover * 2f))
        drawRect(cloth, topLeft = Offset(sx, by - cover), size = Size(spine, bh + cover * 2f), alpha = 0.55f)
        // Круглая спинка: слева тень от сгиба, посередине свет, справа тень в
        // глубине, у самой страницы - снова светлая нить.
        drawRect(
            Brush.horizontalGradient(
                0f to tones.cast(0.52f),
                0.14f to tones.cast(0.24f),
                0.34f to tones.light(0.14f),
                0.66f to tones.cast(0.22f),
                1f to tones.cast(0.54f),
                startX = sx,
                endX = sx + spine,
            ),
            topLeft = Offset(sx, by - cover),
            size = Size(spine, bh + cover * 2f),
        )
        // Кромка страниц у корешка - светлая нить: бумага ловит свет на сгибе.
        drawRect(tones.light(0.18f), topLeft = Offset(sx + spine - hair, by), size = Size(hair, bh))
    }
    // Каптал у корешка: ровно в его ширину, иначе тесьма торчит из книги.
    headbands(tones, headband, sx + spine / 2f, by, bh, reveal, spine)
}

/**
 * Обрез: бумага с торца. У переплёта в тени, к верхней странице светлее;
 * страницы - линии через полтора пункта, неровные по концам, как у настоящего
 * блока.
 */
private fun DrawScope.cutBand(
    tones: PaperTones,
    x0: Float,
    y: Float,
    width: Float,
    height: Float,
    towardsRight: Boolean,
) {
    val dp = 1.dp.toPx()
    val hair = dp.coerceAtLeast(1f)
    if (width < dp) return
    drawRect(tones.block, topLeft = Offset(x0, y), size = Size(width, height))
    drawRect(
        Brush.horizontalGradient(
            0f to tones.cast(0.34f),
            0.4f to tones.cast(0.14f),
            1f to tones.cast(0.03f),
            startX = if (towardsRight) x0 else x0 + width,
            endX = if (towardsRight) x0 + width else x0,
        ),
        topLeft = Offset(x0, y),
        size = Size(width, height),
    )
    var x = x0 + 1.5f * dp
    var i = 0
    while (x < x0 + width) {
        val j1 = jitter(i, 3) * 1.8f * dp
        val j2 = jitter(i, 4) * 1.8f * dp
        // Веер: чем ближе к внешнему краю, тем сильнее лист отходит от
        // соседа - линии расходятся, а не стоят строем. Отсюда и наклон.
        val lean = (if (towardsRight) 1f else -1f) * (x - x0) / width.coerceAtLeast(1f) * 0.6f * dp
        drawLine(
            color = tones.cast(0.14f + 0.10f * jitter(i, 5)),
            start = Offset(x - lean, y + j1),
            end = Offset(x + lean, y + height - j2),
            strokeWidth = hair,
        )
        x += 1.5f * dp
        i++
    }
}

/** Блок возвышается над форзацем: тень наружу от его края. */
private fun DrawScope.edgeShadow(tones: PaperTones, x0: Float, y: Float, height: Float, dir: Int) {
    val band = 4f * 1.dp.toPx()
    drawRect(
        Brush.horizontalGradient(
            listOf(tones.cast(0.22f), Color.Transparent),
            startX = x0,
            endX = x0 + band * dir,
        ),
        topLeft = Offset(if (dir > 0) x0 else x0 - band, y),
        size = Size(band, height),
    )
}

/** Верхний и нижний обрез: страницы стопкой под верхней, с её тенью. */
private fun DrawScope.blockEnds(
    tones: PaperTones,
    x0: Float,
    width: Float,
    y: Float,
    height: Float,
    reveal: Float,
) {
    if (width <= 0f || reveal <= 0f) return
    val dp = 1.dp.toPx()
    val hair = dp.coerceAtLeast(1f)
    for ((yy, down) in listOf(y + height - reveal to true, y to false)) {
        drawRect(tones.block, topLeft = Offset(x0, yy), size = Size(width, reveal))
        drawRect(
            Brush.verticalGradient(
                listOf(tones.cast(0.26f), tones.cast(0.06f)),
                startY = if (down) yy else yy + reveal,
                endY = if (down) yy + reveal else yy,
            ),
            topLeft = Offset(x0, yy),
            size = Size(width, reveal),
        )
        drawRect(
            tones.cast(0.12f),
            topLeft = Offset(x0, if (down) yy + 1.5f * dp else yy + reveal - 2f * dp),
            size = Size(width, hair),
        )
    }
}

/**
 * Каптал: плетёная тесьма в корешке, видна в зазоре над страницами и под ними.
 * Плетение - плиткой (см. [clothBrush]), поверх - тень с той стороны, что
 * глубже в сгибе.
 */
private fun DrawScope.headbands(
    tones: PaperTones,
    cloth: Brush,
    centerX: Float,
    y: Float,
    height: Float,
    reveal: Float,
    width: Float,
) {
    val dp = 1.dp.toPx()
    val hw = width
    val hh = reveal + 4f * dp
    val hx = centerX - hw / 2f
    for ((yy, top) in listOf(y - dp to true, y + height - hh + dp to false)) {
        clipRect(hx, yy, hx + hw, yy + hh) {
            drawRect(tones.headband, topLeft = Offset(hx, yy), size = Size(hw, hh))
            drawRect(cloth, topLeft = Offset(hx, yy), size = Size(hw, hh))
            // Тесьма круглая: по краям уходит в тень, посередине ловит свет.
            drawRect(
                Brush.horizontalGradient(
                    0f to tones.cast(0.34f),
                    0.42f to tones.light(0.16f),
                    1f to tones.cast(0.30f),
                    startX = hx,
                    endX = hx + hw,
                ),
                topLeft = Offset(hx, yy),
                size = Size(hw, hh),
            )
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
private fun DrawScope.drawDeck(tones: PaperTones, m: CardMetrics, rims: Int) {
    if (rims <= 0) return
    val step = m.deckStep.toPx()
    val inset = m.deckInset.toPx()
    val radius = CornerRadius(m.radius.toPx())
    val hair = 1.dp.toPx().coerceAtLeast(1f)
    val dp = 1.dp.toPx()
    for (i in rims downTo 1) {
        val depth = i / rims.toFloat()
        // Колода лежит вразнобой: каждая карточка чуть сдвинута и повёрнута.
        // Идеально соосная стопка читается тенью под карточкой, а не стопкой.
        val skew = (jitter(i, 11) - 0.5f) * 2.4f * dp
        val dx = inset * i
        val at = Offset(dx + skew, 0f)
        val box = Size((size.width - dx * 2f).coerceAtLeast(0f), size.height + step * i)
        // Каждая кромка отбрасывает тень на ту, что под ней.
        drawRoundRect(
            color = tones.cast(0.10f),
            topLeft = Offset(at.x, at.y + 1.5f * dp),
            size = box,
            cornerRadius = radius,
        )
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
        // Внешние углы листа скруглены на точку с небольшим: бумага в книге
        // трётся о соседей и об обрез, идеально острых углов у неё не
        // бывает. У корешка угол острый - там лист не трётся ни обо что.
        val leaf = when (side) {
            PageSide.LEFT -> RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp)
            else -> RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)
        }
        return this
            .clip(leaf)
            .background(tones.paper)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val dp = 1.dp.toPx()
                val gutterLeft = side != PageSide.LEFT     // корешок слева у правой и одиночной
                // Сгиб двумя ступенями: тёмная щель у самого корешка, крутой
                // склон, длинный мягкий хвост - и блик там, где бумага снова
                // выходит на свет. Владелец: «внутри тень, а потом сразу мягче
                // и градиентом».
                val band = (w * 0.22f).coerceIn(36f * dp, 80f * dp)
                val stops = arrayOf(
                    0f to tones.cast(0.52f),
                    0.04f to tones.cast(0.34f),
                    0.14f to tones.cast(0.16f),
                    0.45f to tones.cast(0.05f),
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
                // Свет из верхнего левого угла, примерно под 145 градусами:
                // ровно вертикальный градиент читается заливкой, косой - светом
                // от окна. Амплитуда мала нарочно, на грани заметности.
                val fall = Brush.linearGradient(
                    0f to tones.light(0.09f),
                    0.45f to Color.Transparent,
                    1f to tones.cast(0.05f),
                    start = Offset.Zero,
                    end = Offset(w, h),
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
                val fine = if (look.grain) matteBrush(tones.paper, FINE_SEED, 1f) else null
                val fibers = if (look.grain) matteBrush(tones.paper, FIBERS_SEED, PAPER_FIBERS.toPx()) else null
                // Внешний край листа резан ножом и оттого не идеально прям:
                // зазубрины в полточки, детерминированные - иначе край кипел
                // бы между кадрами.
                val nickAt = if (gutterLeft) w - 0.5f * dp else 0f
                onDrawWithContent {
                    drawRect(castOut, topLeft = Offset(if (dir > 0) outerX else outerX - 3f * dp, 0f), size = Size(3f * dp, h))
                    fine?.let { drawRect(it, alpha = tones.paperFine) }
                    fibers?.let { drawRect(it, alpha = tones.paperFibers) }
                    drawRect(fall)
                    drawContent()
                    var y = 0f
                    var i = 0
                    while (y < h) {
                        val step = (6f + 10f * jitter(i, 6)) * dp
                        val deep = jitter(i, 7) * 0.6f * dp
                        drawRect(
                            tones.cast(0.10f + 0.10f * jitter(i, 8)),
                            topLeft = Offset(nickAt, y),
                            size = Size(0.5f * dp + deep, step.coerceAtMost(h - y)),
                        )
                        y += step
                        i++
                    }
                    drawRect(fold, topLeft = foldAt, size = Size(band, h))
                    drawRect(glow, topLeft = Offset(glowFrom, 0f), size = Size(band * 0.45f, h))
                    drawRect(tones.light(0.55f), topLeft = Offset(if (gutterLeft) w - 1f else 0f, 0f), size = Size(1f, h))
                }
            }
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
 * Перелистывание карточных видов.
 *
 * [offset] читается в фазе рисования, а не в перекомпоновке: пейджер двигает
 * страницу каждый кадр, и пересобирать на это дерево незачем.
 */
fun Modifier.pageTurn(style: String, gentle: Boolean, offset: () -> Float): Modifier = when (style) {
    Settings.TURN_SLIDE -> this

    Settings.TURN_FADE -> this.graphicsLayer {
        val off = offset()
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
                rotationZ = (if (gentle) -2f else -4f) * t
                val s = 1f - 0.03f * t
                scaleX = s
                scaleY = s
            }
            off > -1f -> {
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

/** Страница книги стоит на месте: пейджер возит слоты, мы сдвиг отменяем. */
fun Modifier.bookSlot(offset: () -> Float): Modifier = this.graphicsLayer {
    translationX = offset() * size.width
}

/**
 * Насколько книга отъехала вбок на половине разворота.
 *
 * Разворот шире экрана ровно на две полоски подглядывания, и камера ездит по
 * нему от левого края к правому: читаешь левую страницу - книга стоит слева,
 * смахнул - она доехала до правого края, и перед глазами правая страница.
 * Дальше лист переворачивается, и книга едет обратно. [phase] - 0 у левой
 * страницы, 1 у правой; на обратном пути она снова идёт к нулю.
 */
fun bookPan(phase: Float, widthPx: Float, peekPx: Float): Float =
    -(widthPx - 2f * peekPx) * phase.coerceIn(0f, 1f)

/** Фаза книги по месту в пейджере: 0 - левая страница, 1 - правая. */
fun bookPhase(position: Float): Float {
    val pair = ((position % 2f) + 2f) % 2f
    return min(pair, 2f - pair)
}

/**
 * Страница на половине разворота: едет вместе с книгой и переворачивается
 * вокруг корешка.
 *
 * Левая страница уходит из-под глаз, когда камера едет вправо, - никакого
 * переворота, книга просто сдвинулась. А вот с правой на следующую левую
 * лист переворачивается: он поднимается вокруг корешка, на его обороте и
 * оказывается следующая левая страница. Обе половины поворота стыкуются на
 * ребре (90 градусов), где листа всё равно не видно.
 */
fun Modifier.halfPan(
    side: PageSide,
    peek: Dp,
    offset: () -> Float,
    position: () -> Float,
): Modifier = this.graphicsLayer {
    val off = offset()
    // На место в книге ставятся только страницы этого разворота и соседнего.
    // Дальние должны уехать за экран, как их и увёз пейджер: иначе все
    // страницы книги складываются в одну стопку на двух местах, и поверх
    // читаемой ложится давно прочитанная - владелец на живой сборке увидел
    // ровно это, «левая страница просто как обложка».
    if (off < -1.05f || off > 2.05f) return@graphicsLayer
    val w = size.width
    val peekPx = peek.toPx()
    val pan = bookPan(bookPhase(position()), w, peekPx)
    // Своя фаза: левая страница живёт в начале хода камеры, правая - в конце.
    val home = if (side == PageSide.RIGHT) bookPan(1f, w, peekPx) else 0f
    translationX = off * w + pan - home
}

/**
 * Сам лист на половине разворота: поворот вокруг корешка.
 *
 * Вешается на страницу, а не на место пейджера: ось поворота - край листа у
 * корешка, а не край экрана. Левая страница уходит из-под глаз без поворота,
 * её увозит камера; переворачивается только лист с правой страницей, и на
 * его обороте оказывается следующая левая. Половины стыкуются на ребре, где
 * листа всё равно не видно.
 */
fun Modifier.halfLeaf(side: PageSide, offset: () -> Float): Modifier = this.graphicsLayer {
    val off = offset()
    cameraDistance = bookCamera(size.width, density)
    when {
        side == PageSide.RIGHT && off > 0f && off < 1f -> {
            val t = leafEase(off)
            transformOrigin = TransformOrigin(0f, 0.5f)
            rotationY = -180f * t
            // Подмена лица оборотом ровно на ребре: считаем по углу, а не по
            // пальцу. С разгоном они расходятся, и лист «щёлкал» на шестидесяти
            // градусах - владелец это и увидел.
            alpha = if (t < 0.5f) 1f else 0f
        }
        side == PageSide.LEFT && off < 0f && off > -1f -> {
            val t = leafEase(-off)
            transformOrigin = TransformOrigin(1f, 0.5f)
            rotationY = 180f * t
            alpha = if (t < 0.5f) 1f else 0f
        }
    }
}

/**
 * Разгон поворота: у бумаги есть вес.
 *
 * Лист сперва отрывается медленно, а к концу падает - равномерный поворот
 * читается картонкой на шарнире. Владелец: «они не должны перелистываться как
 * большие картонные листы, они же бумага».
 */
fun leafEase(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (1.62f - 0.62f * x)
}

/**
 * Заворот страницы - перелистывание одной страницы в книге.
 *
 * Владелец о прежнем: «перелистывание очень топорное, надо, чтобы страница
 * изгибалась и поворачивалась, а у тебя как будто страница из картона». Лист
 * не уезжает вбок (за краем экрана ему деться некуда - книга лежит в экране
 * целиком), а заворачивается: линия сгиба идёт справа налево, бумага
 * наматывается на сгиб круглым валиком, за ним видна изнанка листа с
 * просвечивающим текстом, а из-под неё - следующая страница.
 *
 * Свет и тень на каждом куске: валик сгиба круглый (тень - свет - тень),
 * изнанка темнеет к валику, на страницу под листом ложится тень, и тем гуще,
 * чем ближе к сгибу.
 */
@Composable
fun Modifier.pageFold(tones: PaperTones, offset: () -> Float): Modifier {
    val layer = rememberGraphicsLayer()
    return this.drawWithContent {
        val off = offset()
        if (off <= 0f) {
            drawContent()
            return@drawWithContent
        }
        if (off >= 1f) return@drawWithContent
        // Лист записывается один раз за кадр и потом рисуется трижды: лицо,
        // валик и изнанка. Иначе пришлось бы держать три копии страницы.
        layer.record { this@drawWithContent.drawContent() }
        val w = size.width
        val h = size.height
        val dp = 1.dp.toPx()
        // Сгиб идёт справа налево; у самого конца лист уже почти ушёл.
        val fold = w * (1f - off)
        val roll = (w * 0.05f).coerceIn(6f * dp, 16f * dp)   // радиус валика

        // 1. Тень от поднятого листа на страницу под ним: гуще у сгиба.
        val shade = (roll * 2.6f).coerceAtMost(w - fold)
        if (shade > 0f) drawRect(
            Brush.horizontalGradient(
                listOf(tones.cast(0.30f), Color.Transparent),
                startX = fold,
                endX = fold + shade,
            ),
            topLeft = Offset(fold, 0f),
            size = Size(shade, h),
        )

        // 2. Изнанка: бумага, на просвет чуть виден зеркальный текст, к валику
        // темнее - лист там уходит от света.
        val backFrom = (2f * fold - w).coerceAtLeast(0f)
        val backTo = (fold - roll).coerceAtLeast(backFrom)
        if (backTo > backFrom) clipRect(backFrom, 0f, backTo, h) {
            drawRect(tones.paper, topLeft = Offset(backFrom, 0f), size = Size(backTo - backFrom, h))
            // Зеркальная копия листа: бумага тонкая, текст с той стороны
            // просвечивает - без этого изнанка читается куском картона. Зеркало
            // ставится по самому сгибу, иначе бумага за валиком рвалась бы.
            // Сквозь лист видно немного: полный текст навыворот читался бы
            // ошибкой отрисовки, а не изнанкой.
            layer.alpha = 0.13f
            scale(-1f, 1f, pivot = Offset(fold, h / 2f)) { drawLayer(layer) }
            layer.alpha = 1f
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, tones.cast(0.05f), tones.cast(0.22f)),
                    startX = backFrom,
                    endX = backTo,
                ),
                topLeft = Offset(backFrom, 0f),
                size = Size(backTo - backFrom, h),
            )
        }

        // 3. Валик сгиба: круглая бумага - тень, свет, тень. Сюда же уходит
        // лицевая сторона, поэтому рисуем её сжатой на ширину валика.
        val rollFrom = (fold - roll).coerceAtLeast(0f)
        if (fold > rollFrom) clipRect(rollFrom, 0f, fold, h) {
            drawRect(tones.paper, topLeft = Offset(rollFrom, 0f), size = Size(fold - rollFrom, h))
            drawRect(
                Brush.horizontalGradient(
                    0f to tones.cast(0.26f),
                    0.38f to tones.light(0.18f),
                    0.72f to tones.cast(0.14f),
                    1f to tones.cast(0.38f),
                    startX = rollFrom,
                    endX = fold,
                ),
                topLeft = Offset(rollFrom, 0f),
                size = Size(fold - rollFrom, h),
            )
        }

        // 4. Лицо: плоская часть листа до валика, с тенью у самого сгиба -
        // бумага там уже начинает подниматься.
        if (rollFrom > 0f) clipRect(0f, 0f, rollFrom, h) {
            drawLayer(layer)
            val lift = (roll * 2f).coerceAtMost(rollFrom)
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, tones.cast(0.16f)),
                    startX = rollFrom - lift,
                    endX = rollFrom,
                ),
                topLeft = Offset(rollFrom - lift, 0f),
                size = Size(lift, h),
            )
        }
    }
}

/**
 * Лист разворота, поворачивающийся вокруг корешка.
 *
 * На развороте лист есть куда переворачивать - на соседнюю половину, - и он
 * там и переворачивается: правая страница поднимается вокруг корешка, на её
 * обороте оказывается левая страница следующего разворота. [back] - эта самая
 * изнанка: её видно со второй половины поворота.
 *
 * Прежде пара страниц уезжала вбок колодой, и владелец это заметил сразу:
 * «если страницы двойные, то они съезжают вместе как со стопки, странно очень».
 */
fun Modifier.leafTurn(back: Boolean, offset: () -> Float): Modifier = this.graphicsLayer {
    val t = leafEase(offset().coerceIn(0f, 1f))
    // Ось - левый край правой половины, то есть корешок.
    transformOrigin = TransformOrigin(0f, 0.5f)
    cameraDistance = bookCamera(size.width, density)
    rotationY = -180f * t
    // Лицо видно до ребра, изнанка - после: на 90° лист виден ребром, и
    // подмены не заметно. Считаем по углу, а не по пальцу: с разгоном это
    // разные вещи.
    alpha = if (back == (t >= 0.5f)) 1f else 0f
}

/**
 * Тень, которую поднятый лист бросает на страницу под ним.
 *
 * Без неё поворот читается наложением двух картинок: лист висит в воздухе и
 * ничего вокруг себя не меняет. Тень живёт по законам света - сверху и чуть
 * спереди: она ложится от корешка в ту сторону, куда наклонён лист, сжимается
 * вместе с его проекцией (ширина по косинусу угла), гуще всего у самого
 * корешка и в середине поворота, когда лист стоит торчком.
 *
 * Рисуется на неповёрнутом слое той же геометрии, что и лист: повёрнутый слой
 * унёс бы тень вместе с собой.
 */
fun Modifier.leafCast(tones: PaperTones, gutterLeft: Boolean, offset: () -> Float): Modifier =
    drawBehind {
        val off = offset()
        // Лицо листа поворачивается на положительном ходе, оборот - на
        // отрицательном; тень нужна и там, и там.
        val t = leafEase(if (off > 0f) off else -off)
        if (t <= 0.01f || t >= 0.99f) return@drawBehind
        val angle = (Math.PI * t).toFloat()
        val w = size.width
        val h = size.height
        val dp = 1.dp.toPx()
        // Проекция листа на страницу: во сколько раз он «укоротился».
        val span = (w * abs(cos(angle))).coerceAtLeast(2f * dp)
        // Наклон: до ребра лист висит над своей половиной, после - над соседней.
        val toRight = gutterLeft == (t < 0.5f)
        val axis = if (gutterLeft) 0f else w
        val from = if (toRight) axis else axis - span
        val dense = sin(angle)                       // гуще всего на ребре
        drawRect(
            Brush.horizontalGradient(
                0f to tones.cast(0.34f * dense),
                0.35f to tones.cast(0.16f * dense),
                1f to Color.Transparent,
                startX = axis,
                endX = if (toRight) axis + span else axis - span,
            ),
            topLeft = Offset(from, 0f),
            size = Size(span, h),
        )
        // Контактная полоса у самого корешка: там лист ещё касается страницы.
        val touch = 5f * dp
        drawRect(
            Brush.horizontalGradient(
                listOf(tones.cast(0.30f * dense), Color.Transparent),
                startX = axis,
                endX = if (toRight) axis + touch else axis - touch,
            ),
            topLeft = Offset(if (toRight) axis else axis - touch, 0f),
            size = Size(touch, h),
        )
    }

/**
 * Бумага гнётся.
 *
 * Поворот плоского прямоугольника читается картонкой, поэтому лист при
 * повороте выгибается парусом и по-разному ловит свет: у корешка тень, к
 * свободному краю светлее, по дуге - блик. Считает это шейдер (AGSL), он есть
 * с Android 13; на старых - хотя бы свет и тень градиентом.
 *
 * [amount] - насколько лист поднят: 0 у лежащего, 1 в середине поворота.
 */
fun Modifier.paperBend(tones: PaperTones, amount: () -> Float): Modifier = composed {
    // Шейдер собирается один раз и только там, где он есть (Android 13+).
    // Если драйвер его не примет, остаётся свет и тень градиентом: перелистывание
    // не должно ронять читалку из-за украшения.
    val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { runCatching { android.graphics.RuntimeShader(BEND_SHADER) }.getOrNull() }
    } else null
    if (shader != null) graphicsLayer {
        val a = amount().coerceIn(0f, 1f)
        if (a <= 0.001f) {
            renderEffect = null
            return@graphicsLayer
        }
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("amount", a)
        renderEffect = android.graphics.RenderEffect
            .createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    } else drawWithCache {
        val light = Brush.horizontalGradient(
            0f to tones.cast(0.22f),
            0.45f to tones.light(0.10f),
            1f to tones.cast(0.06f),
        )
        onDrawWithContent {
            drawContent()
            val a = amount().coerceIn(0f, 1f)
            if (a > 0.001f) drawRect(light, alpha = a)
        }
    }
}

/**
 * Изгиб и свет одним проходом: бумага выгибается парусом (строки в середине
 * листа сходятся тем сильнее, чем выше он поднят), у корешка тень, по дуге -
 * блик.
 *
 * Знак сдвига важен: изгиб **втягивает** содержимое, а не растягивает. Со
 * вторым по краям листа появлялись бы прозрачные полосы - шейдер брал бы
 * точки за границей слоя.
 */
private const val BEND_SHADER = """
uniform shader content;
uniform float2 size;
uniform float amount;
half4 main(float2 p) {
    float u = clamp(p.x / size.x, 0.0, 1.0);
    float v = p.y / size.y;
    float bow = sin(u * 3.14159265);
    float2 q = float2(p.x, p.y - (v - 0.5) * bow * amount * size.y * 0.11);
    half4 c = content.eval(q);
    // Свет: у корешка тень (там бумага уходит в сгиб), по дуге блик, и весь
    // лист темнеет к середине поворота - он встаёт ребром к свету.
    float light = 1.0 - 0.30 * amount * (1.0 - u) + 0.16 * amount * bow - 0.14 * amount;
    half k = half(clamp(light, 0.0, 1.5));
    return half4(c.r * k, c.g * k, c.b * k, c.a);
}
"""

/**
 * Камера для поворота листа - в двух с половиной его ширинах. Единица
 * `cameraDistance` в Compose - не пиксель, а дюйм: RenderNode делит пиксели
 * на dpi (у View по умолчанию 1280*density px - те самые 8.0).
 */
private fun bookCamera(widthPx: Float, density: Float): Float = 2.5f * widthPx / (160f * density)

/**
 * Матовая поверхность: бумага и картон переплёта.
 *
 * Взято у диска Правки - иней на стекле (`DiskController.drawFrost`): плитка
 * шума по пикселям экрана, повтором. Два отличия. Плитка не серая, а в тон
 * поверхности - точки светлее и темнее её цвета, - иначе на светлой бумаге
 * зерно сереет страницу. И слоёв два: мелкое зерно и оно же крупнее и
 * размытое (волокна) - без второго материал читается телевизионным шумом.
 *
 * Стола тут нет нарочно: владелец попросил оставить на нём только свет и тень.
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

/** Плитки по цвету и семени: цветов у бумаги и картона считанные единицы. */
private val matteTiles = HashMap<Pair<Int, Long>, ImageBitmap>()

/**
 * Плитка шума в тон поверхности. Половина точек светлее базового цвета,
 * половина темнее: средний тон не сдвигается, значит зерно не красит
 * поверхность, а только делает её шероховатой.
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

/** Плитки плетения: одна на цвет и толщину нити. */
private val clothTiles = HashMap<Pair<Int, Int>, ImageBitmap>()

/**
 * Переплётная ткань: нити основы и утка через одну, как в настоящем коленкоре.
 * Нить, лежащая сверху, ловит свет, нижняя уходит в тень - отсюда шахматка,
 * которая и читается плетением, а не полосами.
 */
private fun clothBrush(base: Color, thread: Float): ShaderBrush {
    val px = thread.coerceAtLeast(2f).toInt()
    val tile = clothTiles.getOrPut(base.toArgb() to px) {
        val size = (px * 2).coerceIn(4, 64)
        val half = size / 2
        val pixels = IntArray(size * size)
        val random = Random(1_959L + px)
        for (y in 0 until size) for (x in 0 until size) {
            // Шахматка: в одной клетке сверху нить основы, в соседней - утка.
            val warp = (x / half + y / half) % 2 == 0
            // Круглая нить: к середине светлее, по краям в тень.
            val across = if (warp) (x % half) else (y % half)
            val k = 1f - abs(across - (half - 1) / 2f) / (half / 2f + 0.5f)
            val shade = if (warp) 0.14f * k else -0.10f * (0.4f + k)
            val noise = (random.nextFloat() - 0.5f) * 0.06f
            val v = (shade + noise).coerceIn(-0.35f, 0.35f)
            val c = if (v >= 0f) lerp(base, Color.White, v) else lerp(base, Color.Black, -v)
            pixels[y * size + x] = c.toArgb()
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
}

/**
 * Каптал: цветная нить через светлую поперёк корешка - так тесьму и видно в
 * торце книги. Шахматка переплётной ткани тут не годится: на живой сборке
 * каптал читался наклейкой, а не плетением.
 */
private fun headbandBrush(base: Color, paper: Color, thread: Float): ShaderBrush {
    val px = thread.coerceAtLeast(2f).toInt()
    val tile = headbandTiles.getOrPut(base.toArgb() to px) {
        val size = (px * 2).coerceIn(4, 32)
        val half = size / 2
        val pale = lerp(paper, Color.White, 0.35f)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val colour = if (y < half) base else pale
            // Нить круглая: к середине стежка светлее, по краям в тень.
            val across = if (y < half) y else y - half
            val k = 1f - abs(across - (half - 1) / 2f) / (half / 2f + 0.5f)
            val c = lerp(lerp(colour, Color.Black, 0.22f), lerp(colour, Color.White, 0.16f), k)
            pixels[y * size + x] = c.toArgb()
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
}

private val headbandTiles = HashMap<Pair<Int, Int>, ImageBitmap>()

private const val FINE_SEED = 20_260_920L
private const val FIBERS_SEED = 7L

/** Волокно бумаги: мелкое, иначе на экране читается рябью, а не материалом. */
private val PAPER_FIBERS = 1.6.dp
private val COVER_FIBERS = 3.dp
/** Нить переплёта и каптала: у ткани корешка крупнее, у тесьмы мельче. */
private val CLOTH_THREAD = 2.dp
private val HEADBAND_THREAD = 1.6.dp

/** Блик занимает верхнюю треть, затенение - нижнюю пятую: как у плашек Правки. */
private const val SHEEN_SPAN = 0.34f
private const val FOOT_SPAN = 0.2f

/**
 * Скругление карточки. Крупное нарочно: у Правки кнопки и плашки круглые, и
 * страница с робким радиусом рядом с ними выглядела бы диалогом системы.
 */
private val CARD_RADIUS = 24.dp
