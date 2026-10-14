package ru.zf.pravka.core

import java.time.LocalDate

/**
 * Числа и слова экрана Засечки вида v2 (Правка 5.0, этап 3; план §4, холст
 * «Ковчег на телефоне», лист «Засечка»): итог дня по категориям, «что идёт» и
 * откуда оно записано, свёрнутая лента «идущее и шесть последних отрезков»,
 * подписи шапки и профиля. Счёта здесь нет — минуты и очки уже посчитаны
 * (`DayReport.byCategory` через `DayFacts`, `DayProfile`), лента собрана
 * (`Lenta.rows`); здесь только раскладка и слова, под тестом `ZasechkaDayTest`.
 */
object ZasechkaDay {

    /** Категория дня как её посчитал `DayReport.byCategory`: полное имя, минуты в сутках, очки. */
    data class CatIn(val name: String, val minutes: Int, val points: Int)

    /**
     * Строка «Итога дня» и легенды профиля: [label] — верхний уровень имени
     * («Работа» из «Работа: текущая» и «Работа: звонки»), [category] — полное
     * имя самой длинной из них (по нему берётся цвет «чей это»), [gap] — «не
     * размечено».
     */
    data class Total(val label: String, val category: String, val minutes: Int, val points: Int, val gap: Boolean)

    /**
     * Итог дня по верхнему уровню категории — как на холсте: подкатегории одного
     * цвета (`CategoryTone` красит по верхнему уровню) складываются в одну
     * строку, иначе рядом стояли бы три одинаковых квадратика. Длинное сверху,
     * пустое не показывается; сон и «не размечено» — тоже строки: это сутки, а
     * не только бодрствование.
     */
    fun totals(rows: List<CatIn>): List<Total> {
        val groups = LinkedHashMap<String, MutableList<CatIn>>()
        for (r in rows) {
            if (r.name.isBlank() || r.minutes <= 0) continue
            groups.getOrPut(CategoryTone.top(r.name)) { ArrayList() } += r
        }
        return groups.map { (_, list) ->
            val main = list.maxBy { it.minutes }
            val gap = CategoryTone.of(main.name).gap
            Total(
                label = if (gap) UNMARKED_WORD else main.name.substringBefore(':').trim(),
                category = main.name,
                minutes = list.sumOf { it.minutes },
                points = list.sumOf { it.points },
                gap = gap,
            )
        }.sortedWith(compareByDescending<Total> { it.minutes }.thenBy { it.label })
    }

    /** Как «не размечено» звучит в итогах — строчными, как в ленте: это дыра, а не категория. */
    const val UNMARKED_WORD = "не размечено"

    /** Сколько отрезков у свёрнутой ленты сложенного: идущее и шесть последних (холст). */
    const val RECENT_SEGS = 7

    /**
     * Свёрнутая лента: первые [segs] отрезков (свежие сверху) с тем, что в них
     * случилось. Ленту строками собрал `Lenta.rows` — здесь только срез.
     */
    fun recent(rows: List<Lenta.Row>, segs: Int = RECENT_SEGS): List<Lenta.Row> {
        var n = 0
        val out = ArrayList<Lenta.Row>()
        for (r in rows) {
            if (r is Lenta.Row.Seg && ++n > segs) break
            out += r
        }
        return out
    }

    /** Есть ли что раскрыть «Весь день»: отрезков больше, чем в свёрнутой. */
    fun hasMore(rows: List<Lenta.Row>, segs: Int = RECENT_SEGS): Boolean =
        rows.count { it is Lenta.Row.Seg } > segs

    /**
     * Откуда идущее записано — мелко у «Сейчас» («записано голосом»): то же
     * слово, что во второй строке отрезка ленты (`Lenta.sourceWord`), только
     * фразой. Заполнитель дыр — пусто.
     */
    fun said(source: String, category: String, raw: String = ""): String = when (Lenta.sourceWord(source, category, raw)) {
        "голос" -> "записано голосом"
        "текст" -> "записано текстом"
        "руками" -> "поправлено руками"
        "из дела" -> "начато из дела"
        "из Todoist" -> "начато из Todoist"
        "метка" -> "записано меткой"
        "автопилот" -> "записал автопилот"
        "Garmin" -> "с часов"
        "авто" -> "записано само"
        else -> ""
    }

    /** «0:08», «2:15» — сколько идёт дело: часы и минуты, как таймер на холсте. */
    fun timer(ms: Long): String {
        val m = (ms / 60_000L).coerceAtLeast(0L)
        return "${m / 60}:" + "%02d".format(m % 60)
    }

    /**
     * Подпись шапки: на сложенном «вт, 13 октября · очки +36» — день недели
     * коротко, как у Спорта и Еды (14.10.2026: «понедельник, 28 сентября · очки
     * +134» рядом с двумя значками на 411 dp уже не влезал); на разложенном —
     * полностью и «· не размечено 2 ч 2 м» (холст), если оно было.
     */
    fun subtitle(day: LocalDate, score: Int, unmarkedMin: Int, wide: Boolean): String =
        "${if (wide) Fmt.dayLong(day) else Fmt.dayList(day)} · очки ${Fmt.points(score)}" +
            if (wide && unmarkedMin > 0) " · не размечено ${Fmt.dur(unmarkedMin)}" else ""

    /** Подпись профиля: «очки +36 · пик +42 в 16:08» — пик, только если он выше «сейчас». */
    fun profileNote(p: DayProfile.Profile): String = with(DayProfile) {
        listOfNotNull("очки ${Fmt.points(p.value.round())}", peak(p)).joinToString(" · ")
    }
}
