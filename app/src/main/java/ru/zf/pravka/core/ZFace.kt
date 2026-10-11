package ru.zf.pravka.core

/**
 * Лицо кнопки «З» — идущее дело (11.10.2026). Владелец подсмотрел у Серёжи:
 * «у него кнопка засечки меняется на значок с тем делом, которое есть…
 * и внизу под ним минуты, сколько уже это дело идёт».
 *
 * Вместо буквы «З» — значок категории открытой записи ленты, под ним — сколько
 * она идёт. Смотреть в ленту ради «что сейчас считается» больше не нужно:
 * промах разбора («я обедаю», а записалось «Работа») виден на краю экрана
 * сразу, пока его дёшево поправить, а не вечером в ленте.
 *
 * Категории владелец правит сам, поэтому значок узнаётся по словам названия,
 * а не по точному совпадению: «Работа: звонки», «Работа — звонки» и
 * «работа: созвоны» — одна трубка. Незнакомая категория значка не получает —
 * кнопка остаётся своей «З», но минуты под ней всё равно идут: сколько тянется
 * дело, полезно знать и без картинки. Заполнитель дыры («Не размечено») — не
 * дело, а время без названия: тоже «З» с минутами, и вместе с глубоким
 * янтарём напоминания это читается как «дыра идёт уже столько».
 *
 * Здесь только решения (под тестом `ZFaceTest`); пути значков и рисование —
 * `trigger/ZFaceGlyphs.kt`, кнопка — `trigger/ZasechkaButtonController.kt`.
 */
object ZFace {

    /** Значки категорий: пути штрихом — в `trigger/ZFaceGlyphs.kt`. */
    enum class Icon {
        SLEEP, STRENGTH, RUN, BIKE, SPORT, WALK, TRANSPORT, FOOD, HOME, ORDER,
        FAMILY, SOCIAL, LEADS, WORK, PLAN, CALL, READ, STUDY, LOVE, REST, LOSS,
    }

    /** Что показать: [icon] null — своя «З»; [start] — откуда считать минуты. */
    data class Face(val icon: Icon?, val start: Long)

    private const val MINUTE = 60_000L

    /**
     * Запас после смены минуты: тик, пришедший на миллисекунду раньше
     * границы, показал бы прежнее число и молча ждал бы ещё минуту.
     */
    const val TICK_SLACK_MS = 200L

    /** Лицо идущей записи ленты; заполнитель дыры ([source] «gap») — без значка. */
    fun of(category: String, source: String, start: Long): Face =
        Face(if (source == "gap") null else icon(category), start)

    /**
     * Значок по названию категории. Порядок важен: «Передвижение: вело» — это
     * дорога, а не спорт, поэтому сначала раздел, потом уточнение внутри него.
     */
    fun icon(category: String): Icon? {
        val c = category.trim().lowercase().replace('ё', 'е')
        if (c.isEmpty()) return null
        fun has(vararg words: String) = words.any { it in c }
        return when {
            c.startsWith("сон") -> Icon.SLEEP
            c.startsWith("спорт") -> when {
                has("сил", "желез", "зал") -> Icon.STRENGTH
                has("бег") -> Icon.RUN
                has("вело") -> Icon.BIKE
                else -> Icon.SPORT
            }
            c.startsWith("передвиж") || c.startsWith("дорога") -> when {
                has("пеш", "ходьб") -> Icon.WALK
                has("вело") -> Icon.BIKE
                else -> Icon.TRANSPORT
            }
            c.startsWith("еда") -> Icon.FOOD
            c.startsWith("быт") -> Icon.HOME
            c.startsWith("систематиз") -> Icon.ORDER
            c.startsWith("семья") -> Icon.FAMILY
            c.startsWith("социальн") -> Icon.SOCIAL
            c.startsWith("работа") -> when {
                has("привлеч", "продаж", "маркетинг") -> Icon.LEADS
                has("план") -> Icon.PLAN
                has("звон") -> Icon.CALL
                else -> Icon.WORK
            }
            // «Звонки» без раздела — звонок, про который непонятно, с кем и о чём.
            c.startsWith("звонк") -> Icon.CALL
            c.startsWith("чтение") -> Icon.READ
            c.startsWith("учеба") -> Icon.STUDY
            c.startsWith("секс") -> Icon.LOVE
            c.startsWith("отдых") -> Icon.REST
            c.startsWith("потери") -> Icon.LOSS
            else -> null
        }
    }

    /**
     * Подпись под значком. Владелец просил минуты — до часа это просто число
     * («25»); дальше часы с минутами («1:25»): «85» под значком считать в
     * уме, а «1:25» читается сразу. Запись, забытая открытой на сутки и
     * больше, — днями («2д»): шесть цифр на кнопку не помещаются. Счёт вниз:
     * минута, которая ещё не прошла, не показывается.
     */
    fun label(elapsedMs: Long): String {
        val min = elapsedMs.coerceAtLeast(0L) / MINUTE
        return when {
            min < 60 -> "$min"
            min < 24 * 60 -> "${min / 60}:${(min % 60).toString().padStart(2, '0')}"
            else -> "${min / (24 * 60)}д"
        }
    }

    /** Через сколько перерисовать подпись: к следующей смене минуты с запасом. */
    fun nextTickMs(elapsedMs: Long): Long =
        MINUTE - elapsedMs.coerceAtLeast(0L) % MINUTE + TICK_SLACK_MS
}
