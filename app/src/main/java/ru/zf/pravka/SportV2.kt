package ru.zf.pravka

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.SportCoach
import ru.zf.pravka.core.TrafficLight
import ru.zf.pravka.data.PlanStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvBarRow
import ru.zf.pravka.ui.KvCallout
import ru.zf.pravka.ui.KvColumn
import ru.zf.pravka.ui.KvColumns
import ru.zf.pravka.ui.KvGap
import ru.zf.pravka.ui.KvKpi
import ru.zf.pravka.ui.KvKpiRow
import ru.zf.pravka.ui.KvLink
import ru.zf.pravka.ui.KvListRow
import ru.zf.pravka.ui.KvNote
import ru.zf.pravka.ui.KvPill
import ru.zf.pravka.ui.KvSmallButton
import ru.zf.pravka.ui.KvTabs
import ru.zf.pravka.ui.KvTone
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard

// «Тело» и «Спорт» в виде v2 (Правка 5.0, этап 6, холст `Sport.dc.html`):
// плашки, которых у вкладки 4.0 не было, — готовность с крупными числами,
// «Сегодня» строками, план недели, нагрузка столбиками, последние тренировки.
// Раскладка экрана — в `SportTab.kt` (ветка v2), прежние плашки (зарядка,
// упражнения, силовая, графики «Пути», журнал) — там же. Логика не здесь:
// всё — из тех же сторов, что у 4.0, и считается тем же светофором.

/** Разделы «Тела» на сложенном: Спорт (сегодня), Путь, Журнал и Еда — вкладками, как на холсте. */
internal const val BODY_SPORT = 0
internal const val BODY_PATH = 1
internal const val BODY_JOURNAL = 2
internal const val BODY_FOOD = 3

/**
 * Вкладки «Тела» на сложенном (холст: «Спорт · Еда» под шапкой «Тело»).
 * Между ними — «Путь» и «Журнал»: части вкладки, которые владелец разделил
 * 24.09.2026, чтобы утром не листать мимо нужного, — им нужен вход, а у холста
 * его не было. Еды нет в профиле — нет и вкладки.
 */
@Composable
internal fun BodyTabs(selected: Int, onSelect: (Int) -> Unit, food: Boolean, modifier: Modifier = Modifier) {
    val options = listOf("Спорт", "Путь", "Журнал") + if (food) listOf("Еда") else emptyList()
    KvTabs(options, selected, onSelect, modifier)
}

/** Разделы «Спорта» на развороте — как в вебе сервиса: сегодня, путь, журнал. */
internal val SPORT_SECTIONS = listOf("Сегодня", "Путь", "Журнал")

/**
 * Период нагрузки и журнала: сегмент в шапке на развороте и над журналом на
 * сложенном. Холст просил «Год», но телефон держит тренировки за 120 дней
 * (`Settings.sportDays`), и «год» показал бы те же четыре месяца — поэтому «3 месяца».
 */
internal val SPORT_PERIODS = listOf(7 to "Неделя", 14 to "2 недели", 30 to "Месяц", 90 to "3 месяца")

private val RU = Locale.forLanguageTag("ru")

/** «7,6» — с запятой, как в вебе: числа v2 по-русски. */
internal fun ru1(v: Double): String = String.format(RU, "%.1f", v)

/** «+2,5», «−4,0»: форма со знаком. */
internal fun ruSigned1(v: Double): String = (if (v > 0.05) "+" else "") + Fmt.minus(ru1(v))

/** «07:30 · пульс 130 · нагрузка 40» — подпись строки тренировки. */
internal fun workoutSub(w: SportStore.Workout, withTime: Boolean = true): String = buildList {
    if (withTime) add(Fmt.hm(w.start))
    if (w.km >= 0.1) add(ru1(w.km) + " км")
    if (w.avgHr > 0) add("пульс ${w.avgHr}")
    if (w.load > 0) add("нагрузка ${w.load}")
}.joinToString(" · ")

/** Название тренировки: своё имя часов или вид («Бег»). */
internal fun workoutTitle(w: SportStore.Workout): String =
    w.name.ifBlank { SportCoach.sportName(w.type) }

/** «d.MM» — дата строки тренировки, как на холсте («08.10»). */
internal fun workoutDay(w: SportStore.Workout): String {
    val d = java.time.Instant.ofEpochMilli(w.start).atZone(ZoneId.systemDefault()).toLocalDate()
    return String.format(Locale.US, "%02d.%02d", d.dayOfMonth, d.monthValue)
}

/**
 * Готовность (холст: `.sec` и статус словом справа, три крупных числа, под
 * ними — вес и шаги мелко). Статус — вердикт светофора словом: «на заметку»
 * цветом `--warn`, «стоп и к врачу» — `--bad`, хорошее не красится. Почему —
 * строкой под числами, нарушения его правил — тёплым. На развороте ([wide]) —
 * ещё полосы тренированности, усталости и пульса покоя.
 */
@Composable
internal fun ReadinessCardV2(
    verdict: TrafficLight.Verdict,
    health: List<SportStore.Health>,
    error: String,
    wide: Boolean,
) {
    val today = health.firstOrNull()
    PaperCard(
        label = "Готовность",
        info = "Светофор считается на телефоне: сон, HRV и форма из intervals и сохранённые " +
            "правила блока. Пульс покоя выше своей базы на пять — сразу «отдых».",
        trailing = {
            KvPill(
                verdict.headline,
                tone = when {
                    verdict.headline.startsWith("Стоп") -> KvTone.BAD
                    verdict.tone < 0 -> KvTone.WARN
                    else -> KvTone.PLAIN
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        },
    ) {
        if (today != null && (today.ctl > 0 || today.sleepHours > 0 || today.hrv > 0)) {
            KvKpiRow {
                if (today.ctl > 0 || today.atl > 0) {
                    KvKpi(ruSigned1(today.tsb), Modifier.weight(1f), label = if (wide) "форма (TSB)" else "форма · CTL ${ru1(today.ctl)}")
                }
                if (today.sleepHours > 0) {
                    KvKpi(
                        ru1(today.sleepHours), Modifier.weight(1f), unit = "ч",
                        label = "сон" + if (today.sleepScore > 0) (if (wide) " · " else " · оценка ") + today.sleepScore else "",
                    )
                }
                if (today.hrv > 0) {
                    KvKpi("${today.hrv}", Modifier.weight(1f), label = "HRV" + if (!wide && today.restingHr > 0) " · покой ${today.restingHr}" else "")
                }
            }
        }
        if (wide && today != null) {
            // Шкала — своё же лучшее за выгрузку: полоса показывает, далеко ли до пика, а не «сколько из ста».
            val top = remember(health) { health.take(120).maxOfOrNull { maxOf(it.ctl, it.atl) }?.coerceAtLeast(1.0) ?: 1.0 }
            KvGap(10.dp)
            if (today.ctl > 0) KvBarRow("Фитнес CTL", (today.ctl / top).toFloat(), ru1(today.ctl), labelWidth = 116.dp, valueWidth = 54.dp)
            if (today.atl > 0) KvBarRow("Усталость ATL", (today.atl / top).toFloat(), ru1(today.atl), labelWidth = 116.dp, valueWidth = 54.dp)
            if (today.restingHr > 0) KvBarRow("Пульс покоя", today.restingHr / 100f, "${today.restingHr}", labelWidth = 116.dp, valueWidth = 54.dp)
        }
        if (verdict.because.isNotBlank()) {
            KvGap()
            Text(verdict.because, style = LocalPravkaType.current.body.copy(fontSize = 13.5.sp, lineHeight = 19.sp), color = Kv.muted)
        }
        for (w in verdict.warnings) {
            KvGap(6.dp)
            Text(w, style = LocalPravkaType.current.meta.copy(fontWeight = FontWeight.SemiBold), color = Kv.warn)
        }
        if (error.isNotBlank()) {
            KvGap(6.dp)
            Text(error, style = LocalPravkaType.current.meta, color = Kv.bad)
        }
        // Вес — последний замер с датой (весы бывают не каждый день), шаги — только сегодняшние.
        val weight = health.firstOrNull { it.weightKg > 0 }
        val todayKey = dayKey(System.currentTimeMillis())
        val tail = listOfNotNull(
            weight?.let { "вес ${ru1(it.weightKg)} кг (" + it.date.takeLast(5).split('-').reversed().joinToString(".") + ")" },
            today?.takeIf { it.date == todayKey && it.steps > 0 }?.let { "шагов сегодня ${Fmt.num(it.steps)}" },
        )
        if (tail.isNotEmpty()) {
            KvGap()
            KvNote(tail.joinToString(" · "))
        }
    }
}

/**
 * «Последние» (холст: дата мелко, название, подробности, минуты справа).
 * На сложенном — три до сегодняшнего дня и «Все» — в журнал; на развороте —
 * пять вместе с сегодняшними.
 */
@Composable
internal fun LastWorkoutsCardV2(
    workouts: List<SportStore.Workout>,
    title: String,
    count: Int,
    skipToday: Boolean,
    onAll: (() -> Unit)?,
) {
    val todayKey = dayKey(System.currentTimeMillis())
    val list = remember(workouts, skipToday) {
        workouts.sortedByDescending { it.start }
            .filter { !skipToday || dayKey(it.start) != todayKey }
            .take(count)
    }
    if (list.isEmpty()) return
    PaperCard(label = title, trailing = onAll?.let { { KvLink("Все", it) } }) {
        list.forEachIndexed { i, w ->
            KvListRow(
                title = workoutTitle(w),
                sub = workoutSub(w, withTime = false),
                lead = workoutDay(w),
                value = Fmt.dur(w.minutes.toInt()).replace(" м", " мин"),
                valueSmall = true,
                divider = i > 0,
            )
        }
    }
}

/**
 * «Нагрузка» (холст: столбики по дням за две недели, сегодня — акцентом,
 * итог строкой). Месяц — тоже по дням; три месяца — по неделям, иначе
 * девяносто столбиков в ширину плашки сливаются в штриховку.
 */
@Composable
internal fun LoadCardV2(workouts: List<SportStore.Workout>, days: Int) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val first = today.minusDays((days - 1).toLong())
    val inRange = remember(workouts, days) {
        workouts.filter { !java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().isBefore(first) }
    }
    val byDay = remember(inRange) {
        inRange.groupBy { java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
            .mapValues { (_, l) -> l.sumOf { it.load } }
    }
    val columns = remember(byDay, days) {
        if (days <= 31) {
            (0 until days).map { k ->
                val d = first.plusDays(k.toLong())
                KvColumn((byDay[d] ?: 0).toFloat(), "${d.dayOfMonth}", accent = d == today)
            }
        } else {
            // Недели с понедельника; подпись — число понедельника.
            val monday = first.minusDays((first.dayOfWeek.value - 1).toLong())
            generateSequence(monday) { it.plusWeeks(1) }.takeWhile { !it.isAfter(today) }.map { wk ->
                val sum = (0 until 7).sumOf { byDay[wk.plusDays(it.toLong())] ?: 0 }
                KvColumn(sum.toFloat(), "${wk.dayOfMonth}", accent = !today.isBefore(wk) && today.isBefore(wk.plusWeeks(1)))
            }.toList()
        }
    }
    PaperCard(label = "Нагрузка", note = Fmt.dayMonth(first) + " — " + Fmt.dayMonth(today)) {
        KvColumns(columns)
        KvGap()
        val minutes = inRange.sumOf { it.minutes }.toInt()
        KvNote(
            if (inRange.isEmpty()) "тренировок не было"
            else plural(inRange.size, "тренировка", "тренировки", "тренировок") + " · " +
                Fmt.dur(minutes).replace(" м", " мин") + " · нагрузка ${inRange.sumOf { it.load }}"
        )
    }
}

/**
 * План недели (холст: `.sec` с датами, дни строками). Плана в календаре нет —
 * подсказка словами и «Обновить»: вдруг его только что запушили из чата.
 * Тап по дню раскрывает его комментарии из описаний событий.
 */
@Composable
internal fun WeekPlanCardV2(app: PravkaApp, planDays: List<PlanStore.PlanDay>, onRefresh: () -> Unit, syncing: Boolean) {
    val todayKey = remember { dayKey(System.currentTimeMillis()) }
    val upcoming = remember(planDays, todayKey) {
        app.planStore.upcoming(6).groupBy { it.date }.toList().sortedBy { it.first }
    }
    val today = LocalDate.now()
    var openDate by remember { mutableStateOf<String?>(null) }
    PaperCard(
        label = "План недели",
        note = "${today.dayOfMonth}–" + Fmt.dayMonth(today.plusDays(6)),
        info = "Тап по дню — его комментарии из календаря intervals. План правится в чате с Claude " +
            "или в «Спорте» на компьютере.",
    ) {
        if (upcoming.isEmpty()) {
            KvCallout(
                "В intervals на эту неделю плана нет. Появится — приедет сам раз в сутки или по «Обновить».",
                buttons = { KvSmallButton(if (syncing) "Обновляю…" else "Обновить", onRefresh, soft = true, enabled = !syncing, icon = Glyphs.Refresh) },
            )
            return@PaperCard
        }
        upcoming.forEachIndexed { i, (date, events) ->
            val main = events.filterNot { it.charger }.sortedBy { it.time.ifBlank { "99:99" } }
            val charger = events.firstOrNull { it.charger }
            val d = runCatching { LocalDate.parse(date) }.getOrNull()
            val open = openDate == date
            KvListRow(
                title = main.joinToString("; ") { e -> e.name }.ifBlank { charger?.name ?: "—" },
                sub = buildList {
                    main.firstOrNull { it.time.isNotBlank() }?.let { add(it.time) }
                    val min = main.sumOf { it.minutes }
                    if (min > 0) add(Fmt.dur(min))
                    if (charger != null && main.isNotEmpty()) add("и зарядка")
                }.joinToString(" · "),
                lead = d?.let { Fmt.wd(it) + " " + it.dayOfMonth } ?: date,
                titleWeight = if (date == todayKey) FontWeight.SemiBold else FontWeight.Normal,
                titleLines = if (open) 4 else 2,
                divider = i > 0,
                onClick = { openDate = if (open) null else date },
                below = if (!open) null else ({
                    for (e in main) {
                        val comment = listOf(e.noteBefore(), e.noteAfter()).filter { it.isNotBlank() }.joinToString("\n")
                        if (comment.isNotBlank()) {
                            Text(
                                comment.take(600),
                                style = LocalPravkaType.current.meta,
                                color = Kv.muted,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }),
            )
        }
    }
}

/**
 * «Сегодня» — что приехало с часов (холст: галочка `--ok`, название,
 * «07:30 · пульс 130 · нагрузка 40», минуты справа). Строки встают в плашку
 * «Сегодня» под планом дня.
 */
@Composable
internal fun DoneTodayRowsV2(list: List<SportStore.Workout>, first: Boolean) {
    list.forEachIndexed { i, w ->
        KvListRow(
            title = workoutTitle(w),
            sub = workoutSub(w),
            icon = Glyphs.Check,
            iconTint = Kv.ok,
            value = Fmt.dur(w.minutes.toInt()).replace(" м", " мин"),
            divider = !(first && i == 0),
        )
    }
}

/** Неделя журнала на развороте и сложенном: заголовком — неделя, итогом — сколько, минут и нагрузки. */
internal fun weekNote(list: List<SportStore.Workout>): String =
    plural(list.size, "тренировка", "тренировки", "тренировок") + " · " + Fmt.dur(list.sumOf { it.minutes }.toInt()).replace(" м", " мин") + " · нагрузка ${list.sumOf { it.load }}"

/** Строка «Спросить про тренировки» под заголовком — что увидит Опус. */
internal const val SPORT_ASK_INFO = "Опус видит твои тренировки, сон, HRV, форму, еду за неделю и " +
    "чем ты был занят по ленте. Пустой вопрос = «как у меня дела»."
