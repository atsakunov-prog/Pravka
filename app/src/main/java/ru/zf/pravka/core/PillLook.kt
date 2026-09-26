package ru.zf.pravka.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Вид и движение пилюли диктовки (`trigger/DictationPill.kt`) — числами, под
 * JVM-тестом: рисование никто не проверит, а коэффициенты проверить можно.
 *
 * Владелец (26.09.2026), со снимком Gemini: «они будут таких цветов же, как в
 * правке, деле и так далее, но прозрачнее во-первых, а во-вторых чуть
 * спокойнее цветов… и там, по-моему, какой-то даже градиент есть».
 *
 * «Спокойнее» — это не бледнее, а глубже. Бледный оранжевый под белым текстом
 * не читается на белом приложении; поэтому цвет режима уводится в тёплые
 * чернила ([INK]) — тон остаётся узнаваемым, а насыщенность уходит. Градиент
 * идёт слева направо: слева почти чернила, к кружку голоса цвет гуще, и от
 * самого кружка по пилюле растекается свечение — как синий свет вокруг
 * кнопки голоса у Gemini. Единственное чистое пятно цвета — сам кружок.
 *
 * «Прозрачнее» — плотность заливки ([DENSITY_DEFAULT]), ползунок в «Кнопках
 * на экране». Текст, знак режима и кружок плотностью не гаснут: прозрачным
 * становится стекло, а не то, что на нём написано.
 */
object PillLook {

    /** Тёплые чернила — та же ночь, что под плашками вкладок. */
    val INK = 0xFF1C1A17.toInt()

    /** Плотность стекла с завода: заметно прозрачнее прежней строки (0,82 чистого цвета). */
    const val DENSITY_DEFAULT = 0.7f
    const val DENSITY_MIN = 0.3f
    const val DENSITY_MAX = 1f

    /** Доля чернил в цвете: слева больше, у кружка меньше, в самом кружке — чуть-чуть. */
    const val INK_START = 0.72f
    const val INK_END = 0.50f
    const val INK_ORB = 0.18f

    /** Высота пилюли, dp: одна строка в 17sp и кружок голоса с полями. */
    const val HEIGHT_DP = 56

    /** С какой глубины всплывает, dp: пилюля выплывает снизу, а не проявляется на месте. */
    const val RISE_DP = 44

    /**
     * Запас окна над пилюлей, dp. Окно ровно с пилюлю срезало бы проскок —
     * верх стал бы плоским на те полтора кадра, когда «оп» и видно. Запас
     * обязан вмещать [overshoot] × [RISE_DP] — это проверяет тест.
     */
    const val ROOM_DP = 8

    /** Поля пилюли от краёв экрана и зазор до пола и до кнопок, dp. */
    const val SIDE_DP = 16
    const val GAP_DP = 10

    /** Уже этого пилюля не ужимается, а поднимается над кнопкой, dp. */
    const val MIN_WIDTH_DP = 200

    /**
     * Пружина всплытия. Владелец: «всплывать будет снизу… наверху немножко
     * так вниз делает, как-то так выплыло и чуть-чуть — оп — назад». Это
     * затухающая пружина с одним заметным проскоком: [DAMPING] 0,55 даёт
     * около восьмой части пути вверх сверх места, второй проскок уже не
     * виден. [OMEGA] — частота в долях длительности: к концу [ENTER_MS]
     * пружина успокаивается, и конец анимации не обрывает её на ходу.
     */
    const val DAMPING = 0.55f
    const val OMEGA = 11f
    const val ENTER_MS = 520L

    /** Уход вниз: быстрее появления — провожать глазами нечего. */
    const val EXIT_MS = 200L

    /**
     * Путь пружины 0..1 (и чуть больше на проскоке) за долю времени [t]
     * 0..1. В нуле — ноль, к единице приходит единицей.
     */
    fun spring(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val z = DAMPING
        val root = sqrt(1f - z * z)
        val wd = OMEGA * root
        val decay = exp(-z * OMEGA * x)
        return 1f - decay * (cos(wd * x) + (z / root) * sin(wd * x))
    }

    /** На какую долю пути пружина проскакивает место: e^(−ζπ/√(1−ζ²)). */
    fun overshoot(): Float {
        val z = DAMPING
        return exp(-z * PI.toFloat() / sqrt(1f - z * z))
    }

    /** Проявление на всплытии: за первую треть пути пилюля уже видна целиком. */
    fun enterAlpha(t: Float): Float = (t / 0.3f).coerceIn(0f, 1f)

    /** Заливка слева — почти чернила. */
    fun bodyStart(accent: Int): Int = mix(accent, INK, INK_START)

    /** Заливка у кружка — цвет режима гуще. */
    fun bodyEnd(accent: Int): Int = mix(accent, INK, INK_END)

    /** Кружок голоса — единственное почти чистое пятно цвета. */
    fun orb(accent: Int): Int = mix(accent, INK, INK_ORB)

    /**
     * Свечение от кружка по стеклу. Живёт от плотности, но медленнее её: на
     * очень прозрачном стекле оно — последнее, что держит цвет режима.
     */
    fun glowAlpha(density: Float): Float = 0.18f + 0.22f * density.coerceIn(0f, 1f)

    /**
     * Кромка стекла — светлая линия по краю. Без неё прозрачная пилюля на
     * тёмном приложении растворяется: край держит форму, когда заливки
     * почти нет.
     */
    fun rimAlpha(density: Float): Float = 0.16f + 0.10f * density.coerceIn(0f, 1f)

    /** Блик сверху — полоса света по верхней трети, как у плашек на стекле. */
    fun sheenAlpha(density: Float): Float = 0.06f + 0.10f * density.coerceIn(0f, 1f)

    /**
     * Три полоски волны в кружке: в тишине — значок (короткая, длинная,
     * короткая), от голоса все три растут. Доли от наибольшей высоты.
     */
    fun bars(level: Float): FloatArray {
        val l = level.coerceIn(0f, 1f)
        return FloatArray(3) { i ->
            val rest = BAR_REST[i]
            rest + (BAR_PEAK[i] - rest) * l
        }
    }

    private val BAR_REST = floatArrayOf(0.34f, 0.62f, 0.34f)
    private val BAR_PEAK = floatArrayOf(0.78f, 1.0f, 0.78f)

    /** Смесь двух цветов: [t] — доля второго. Альфа — первого. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * k).roundToInt().coerceIn(0, 255)
        }
        return (a.toLong() and 0xFF000000L).toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
