package ru.zf.pravka.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ru.zf.pravka.core.CategoryRainbow

// Цвета Правки 4.0 (07.10.2026, DESIGN §4). Правило одно: цвет — это
// источник. Внутри режима — только цвет его кнопки на стекле и соседние
// оттенки; на «Сегодня» и в Общей статистике цвета режимов встречаются, но
// только как метка: монета, плашка режима, отметка в ленте. Состояние
// («ждём Claude», «план», «ошибка») меняет силу, а не оттенок — новых цветов
// для состояний нет. Вторичный текст тёплый, не серый: серое на тёплой ночи
// читалось грязью (владелец, 20.09: «как будто немного грязные»).
// Исключение одно — категории ленты: они снова в радуге (10.10.2026, ниже).

/** Основа — DESIGN §4.1. */
object Ink {
    val Bg = Color(0xFF100F0D)
    val Text = Color(0xFFF0EADF)
    val TextStrong = Color(0xFFFFF5E8)
    val TextSecondary = Color(0xFFC9B9A2)
    val TextMeta = Color(0xFFA98F72)
    val TextNote = Color(0xFF9C8670)
    val TextDisabled = Color(0xFF6E5844)
    val Cream = Color(0xFFF1E8D8)
    val PlanText = Color(0xFFE8DCCB)
    val Now = Color(0xFFFFF1DC)
    val NowInk = Color(0xFF2A1E12)
    val TimePast = Color(0xFFD8C6AE)
    val Caption = Color(0xFFA8998A)
    val FreeText = Color(0xFF9C8F80)
    val PlanEnd = Color(0xFF8F8476)
    val Warn = Color(0xFFF27A45)
    val LegendValue = Color(0xFFF9B566)
    /** Значок на клавише режима. */
    val KeyIcon = Color(0xFFFFF6EA)
    /** Буква на монете. */
    val CoinLetter = Color(0xFFEFE8DC)
    /** Погода и значки шапки «Сегодня». */
    val WeatherText = Color(0xFFEDE3D4)
    val WeatherIcon = Color(0xFFD9CBB6)
    /** Подсказка в строке «сказать» на «Сегодня». */
    val Placeholder = Color(0xFFBDAE98)
}

/**
 * Стекло плашки в режиме (DESIGN §8): основа — чернила [inkAlpha], поверх —
 * налёт цвета кнопки сверху [keyTop] вниз до [keyBottom], блик [sheen] у
 * верхней кромки, рамка [rim] и светлее сверху [rimTop].
 */
@Immutable
data class GlassSpec(
    val base: Color,
    /** Цвет налёта — сама кнопка режима. */
    val key: Color = Color.Transparent,
    val inkAlpha: Float,
    val keyTop: Float,
    val keyBottom: Float,
    val sheenColor: Color,
    val sheen: Float,
    val rimColor: Color,
    val rim: Float,
    val rimTopColor: Color,
    val rimTop: Float,
    val innerTop: Color,
)

/** Пятно света сверху экрана: центр в долях ширины и высоты слоя, радиусы — так же, цвет с силой. */
@Immutable
class LightBlob(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val color: Color)

/** Свет режима: высота слоя света в dp, общая сила и пятна (точно по макетам из `docs/design/redesign-4/mockups`). */
@Immutable
class LightSpec(val heightDp: Int, val opacity: Float, val blobs: List<LightBlob>)

/**
 * Цвета режима — DESIGN §4.2. [key] — ровно цвет плавающей кнопки; [ink] —
 * основа стекла; [tint] — линии, кольца, полосы; [label] — подписи и
 * заголовки разделов; [value] — цифры; [meta] — вторая строка; [eventText]
 * — текст отметки этого режима на «Сегодня»; [title] — название в шапке;
 * [ramp] — шкала графиков от светлого к тёмному, пять ступеней.
 */
@Immutable
class ModeColors(
    val key: Color,
    val ink: Color,
    val tint: Color,
    val label: Color,
    val value: Color,
    val meta: Color,
    val eventText: Color,
    val title: Color,
    val ramp: List<Color>,
    val glass: GlassSpec,
    val light: LightSpec,
    /** Подсказка в строке «сказать». */
    val placeholder: Color,
) {
    /** Плашка режима и чип: key @ α. */
    fun chip(alpha: Float = 0.38f) = key.copy(alpha = alpha)
}

private fun modeGlass(key: Color, ink: Color, tint: Color, inkA: Float, top: Float, bottom: Float, sheen: Float = 0.13f, rimTop: Float = 0.36f) =
    GlassSpec(
        base = ink, key = key, inkAlpha = inkA, keyTop = top, keyBottom = bottom,
        sheenColor = tint, sheen = sheen,
        rimColor = tint, rim = 0.20f, rimTopColor = tint, rimTop = rimTop,
        innerTop = Color.White.copy(alpha = 0.07f),
    )

private fun modeLight(key: Color, tint: Color, a1: Float = 0.95f, a2: Float = 0.42f, a3: Float = 0.35f) =
    LightSpec(
        heightDp = 560, opacity = 0.85f,
        blobs = listOf(
            LightBlob(0.16f, 0f, 0.60f, 0.40f, key.copy(alpha = a1)),
            LightBlob(0.88f, 0f, 0.52f, 0.36f, tint.copy(alpha = a2)),
            LightBlob(0.50f, 0.26f, 0.80f, 0.40f, key.copy(alpha = a3)),
        ),
    )

object Modes {

    val Pravka: ModeColors = run {
        val key = Color(0xFFEA580C); val ink = Color(0xFF2E1608); val tint = Color(0xFFF5925A)
        ModeColors(
            key, ink, tint,
            label = Color(0xFFF7A878), value = Color(0xFFFFD2B5), meta = Color(0xFFB8907A),
            eventText = Color(0xFFFFD2B5), title = Color(0xFFFFF3E8),
            ramp = listOf(Color(0xFFFFD2B5), Color(0xFFF5925A), Color(0xFFEA580C), Color(0xFFA83E08), Color(0xFF6B2805)),
            glass = modeGlass(key, ink, tint, 0.80f, 0.16f, 0.06f),
            light = modeLight(key, tint, a1 = 0.90f, a2 = 0.50f, a3 = 0.30f),
            placeholder = Color(0xFFD9A888),
        )
    }

    val Zasechka: ModeColors = run {
        val key = Color(0xFFF78810); val ink = Color(0xFF2C190A); val tint = Color(0xFFF9A54C)
        ModeColors(
            key, ink, tint,
            label = Color(0xFFCDB394), value = Color(0xFFFFD9A8), meta = Color(0xFFA98F72),
            eventText = Color(0xFFFFD9A8), title = Color(0xFFFFF3E2),
            ramp = listOf(Color(0xFFFFD9A8), Color(0xFFFFC261), Color(0xFFF78810), Color(0xFFA9611F), Color(0xFF6B4426)),
            // В Засечке стекло — чернила 0.72 без налёта (`mockups/04`).
            glass = GlassSpec(
                base = ink, inkAlpha = 0.72f, keyTop = 0f, keyBottom = 0f,
                sheenColor = Color(0xFFFFC88C), sheen = 0.14f,
                rimColor = key, rim = 0.22f, rimTopColor = Color(0xFFFFCE96), rimTop = 0.38f,
                innerTop = Color(0xFFFFE4C3).copy(alpha = 0.10f),
            ),
            light = LightSpec(
                640, 0.85f,
                listOf(
                    LightBlob(0.18f, 0f, 0.60f, 0.34f, Color(0xFFFF9C22).copy(alpha = 0.80f)),
                    LightBlob(0.88f, 0f, 0.52f, 0.30f, Color(0xFFF77A10).copy(alpha = 0.62f)),
                    LightBlob(0.50f, 0.30f, 0.44f, 0.26f, key.copy(alpha = 0.22f)),
                ),
            ),
            placeholder = Color(0xFFCDAE88),
        )
    }

    val Dela: ModeColors = run {
        val key = Color(0xFF2A5D82); val ink = Color(0xFF0E1822); val tint = Color(0xFF6FA3CC)
        ModeColors(
            key, ink, tint,
            label = Color(0xFF9FC2DE), value = Color(0xFFD4E6F5), meta = Color(0xFF7F9AB2),
            eventText = Color(0xFFC3DBEE), title = Color(0xFFF2F6FA),
            ramp = listOf(Color(0xFFD4E6F5), Color(0xFF6FA3CC), Color(0xFF4A82AE), Color(0xFF2A5D82), Color(0xFF1A3C56)),
            glass = modeGlass(key, ink, tint, 0.84f, 0.22f, 0.10f),
            light = modeLight(key, tint, a2 = 0.45f),
            placeholder = Color(0xFF93AFC6),
        )
    }

    val Sport: ModeColors = run {
        val key = Color(0xFF2F6B4F); val ink = Color(0xFF0C1C14); val tint = Color(0xFF79C29C)
        ModeColors(
            key, ink, tint,
            label = Color(0xFF9DD3B5), value = Color(0xFFCDEBDB), meta = Color(0xFF7FA893),
            eventText = Color(0xFFBDE3CC), title = Color(0xFFF1F8F4),
            ramp = listOf(Color(0xFFCDEBDB), Color(0xFF79C29C), Color(0xFF4F9A75), Color(0xFF2F6B4F), Color(0xFF1D4532)),
            glass = modeGlass(key, ink, tint, 0.84f, 0.24f, 0.10f),
            light = modeLight(key, tint, a2 = 0.42f),
            placeholder = Color(0xFF94BBA6),
        )
    }

    val Food: ModeColors = run {
        val key = Color(0xFF5E7A1F); val ink = Color(0xFF161C08); val tint = Color(0xFFAFCB62)
        ModeColors(
            key, ink, tint,
            label = Color(0xFFC8DC8E), value = Color(0xFFE4EFC2), meta = Color(0xFF9AAA74),
            eventText = Color(0xFFD6E6A6), title = Color(0xFFF6F9EC),
            ramp = listOf(Color(0xFFE4EFC2), Color(0xFFAFCB62), Color(0xFF8BA83C), Color(0xFF5E7A1F), Color(0xFF3D5212)),
            glass = modeGlass(key, ink, tint, 0.86f, 0.24f, 0.10f, sheen = 0.12f, rimTop = 0.34f),
            light = modeLight(key, tint, a2 = 0.40f),
            placeholder = Color(0xFFADBE86),
        )
    }

    val Money: ModeColors = run {
        val key = Color(0xFF5B4A8C); val ink = Color(0xFF161124); val tint = Color(0xFFAE9DE0)
        ModeColors(
            key, ink, tint,
            label = Color(0xFFC6BAEA), value = Color(0xFFE2DBF6), meta = Color(0xFF9A90B8),
            eventText = Color(0xFFD6CCF2), title = Color(0xFFF5F2FC),
            ramp = listOf(Color(0xFFE2DBF6), Color(0xFFAE9DE0), Color(0xFF8C78C8), Color(0xFF5B4A8C), Color(0xFF3B3060)),
            glass = modeGlass(key, ink, tint, 0.86f, 0.26f, 0.12f),
            light = modeLight(key, tint, a2 = 0.40f),
            placeholder = Color(0xFFADA3C8),
        )
    }

    /**
     * «Сегодня» и Общая статистика — нейтральные кремовые: свет — крем слева и
     * тёплый янтарь справа, «цвет дня», а не метка Засечки (DESIGN §3.2).
     */
    val Today: ModeColors = run {
        val cream = Ink.Cream
        ModeColors(
            key = cream, ink = Color(0xFF221C16), tint = cream,
            label = Ink.TextSecondary, value = Ink.TextStrong, meta = Ink.TextMeta,
            eventText = Ink.Text, title = Ink.TextStrong,
            ramp = Zasechka.ramp,
            glass = GlassSpec(
                base = Color(0xFF221C16), inkAlpha = 0.84f, keyTop = 0f, keyBottom = 0f,
                sheenColor = Color(0xFFFFE6C8), sheen = 0.12f,
                rimColor = Color(0xFFFFDCB4), rim = 0.16f, rimTopColor = Color(0xFFFFE6C8), rimTop = 0.30f,
                innerTop = Color(0xFFFFF0DC).copy(alpha = 0.08f),
            ),
            light = LightSpec(
                420, 0.9f,
                listOf(
                    LightBlob(0.20f, 0f, 0.64f, 0.50f, Color(0xFFFFD6A0).copy(alpha = 0.40f)),
                    LightBlob(0.90f, 0f, 0.50f, 0.44f, Color(0xFFF78810).copy(alpha = 0.30f)),
                ),
            ),
            placeholder = Ink.Placeholder,
        )
    }

    /** Ещё, настройки и служебные экраны — в цвете Правки (DESIGN §12.10), свет вдвое тише. */
    val Service: ModeColors = run {
        val p = Pravka
        ModeColors(
            p.key, p.ink, p.tint, p.label, p.value, p.meta, p.eventText, p.title, p.ramp, p.glass,
            LightSpec(
                p.light.heightDp, 0.5f,
                p.light.blobs,
            ),
            p.placeholder,
        )
    }

    fun of(decor: ModeDecor): ModeColors = when (decor) {
        ModeDecor.PRAVKA -> Pravka
        ModeDecor.ZASECHKA -> Zasechka
        ModeDecor.DELA -> Dela
        ModeDecor.SPORT -> Sport
        ModeDecor.FOOD -> Food
        ModeDecor.MONEY -> Money
        ModeDecor.TODAY -> Today
        ModeDecor.SERVICE -> Service
    }
}

/** Цвета текущего режима — детали набора берут их отсюда, а не из констант (DESIGN §14). */
val LocalMode = staticCompositionLocalOf { Modes.Today }

// ---------------------------------------------------------------------------
// Засечка: категории — радуга владельца
// ---------------------------------------------------------------------------

// Правка 4.0 красила категории только оттенками оранжевого по цене часа
// (DESIGN §4.3). Владелец, 10.10.2026: «у меня полностью пропало понимание,
// чем я занимаюсь. Вот раньше вот этот вот радужный был намного лучше» —
// пять соседних янтарей на ленте, круге и полосе не различались. Вернули
// радугу до 4.0: место категории на спектре — `core/CategoryRainbow.kt`
// (одна на всё приложение: порядок итогов, донат Отчёта и краска), мягкость
// та же, что была («чуть-чуть помягче»): насыщенность 0.55, яркость 0.94.

/** Заливка категории по месту на радуге: [hue] 0 (красный) … 292 (фиолетовый). */
fun rainbowFill(hue: Float): Color = Color.hsv(hue, 0.55f, 0.94f)

/** Подпись категории тем же оттенком, на тон светлее заливки — мелкий текст на тёмном. */
fun rainbowText(hue: Float): Color = Color.hsv(hue, 0.42f, 0.98f)

/** Цвет категории ленты — её место на радуге; без категории — тёплый вторичный. */
fun categoryFill(category: String): Color =
    if (category.isBlank()) Ink.TextMeta else rainbowFill(CategoryRainbow.hue(category))

/** Подпись категории (тег, вторая строка записи) в её радужном оттенке. */
fun categoryText(category: String): Color =
    if (category.isBlank()) Ink.TextMeta else rainbowText(CategoryRainbow.hue(category))

/**
 * Группа категории ленты — для полосы и легенды плашки времени. Цвет группы —
 * её область радуги, ровно оттенок главной категории группы: работа красная
 * («Работа: текущая»), спорт жёлтый («Спорт: бег»), семья салатовая,
 * быт синий, сон мятный, потери фиолетовые и со штриховкой. «Впереди» —
 * не категория, а остаток дня: места на радуге у него нет, он кремовой
 * штриховкой.
 */
enum class ZGroup(val fill: Color, val text: Color, val label: String) {
    WORK(rainbowFill(8f), rainbowText(8f), "работа"),
    SPORT(rainbowFill(56f), rainbowText(56f), "спорт"),
    FAMILY(rainbowFill(88f), rainbowText(88f), "семья"),
    LIFE(rainbowFill(235f), rainbowText(235f), "быт"),
    SLEEP(rainbowFill(155f), rainbowText(155f), "сон"),
    LOSS(rainbowFill(292f), rainbowText(292f), "потери"),
    AHEAD(Ink.Cream, Color(0xFFE8D3B8), "впереди"),
    ;

    /** Штриховка — у потерь и у «впереди» (DESIGN §4.3). */
    val hatched: Boolean get() = this == LOSS || this == AHEAD

    companion object {
        /**
         * Группа по названию категории ленты. Имена категорий — свои у
         * владельца (`ZasechkaStore.DEFAULT_CATEGORIES`) и нейтральные у
         * остальных; группа узнаётся по префиксу и слову, как в `DayReport`.
         * [value] — очки за час категории: отрицательная цена без явного
         * слова — тоже потери.
         */
        fun of(category: String, value: Int = 0): ZGroup {
            val c = category.trim().lowercase()
            return when {
                c.isEmpty() -> LIFE
                // Дыра без записи — та же потеря для балла (`DayReport.isHole`).
                c == "не размечено" -> LOSS
                c == "сон" || c.startsWith("сон") -> SLEEP
                c.startsWith("потер") || c.contains("отвлеч") || c.contains("залип") -> LOSS
                c.startsWith("работа") || c.startsWith("зф") -> WORK
                c.startsWith("спорт") -> SPORT
                c.startsWith("семья") || c.startsWith("социал") || c.startsWith("дети") ||
                    c == "секс: с марианной" -> FAMILY
                c.startsWith("еда") || c.startsWith("передвиж") || c.startsWith("систематиз") ||
                    c.startsWith("быт") || c.startsWith("отдых") -> LIFE
                // Остальные (свои категории, «Чтение», «Звонки», «Учёба») — по цене
                // часа, как и вся шкала.
                value < 0 -> LOSS
                value >= 7 -> WORK
                value >= 5 -> FAMILY
                else -> LIFE
            }
        }
    }
}

/** Очки: плюс — #FFC261, минус — #F27A45 (DESIGN §4.3). */
val PointsPlus = Color(0xFFFFC261)
val PointsMinus = Color(0xFFF27A45)
fun pointsColor(p: Int): Color = if (p < 0) PointsMinus else PointsPlus
