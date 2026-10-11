package ru.zf.pravka

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.LifeStats
import ru.zf.pravka.core.LifeStats.DayPick
import ru.zf.pravka.core.LifeStats.Group
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.data.PhoneSweeper
import ru.zf.pravka.ui.BarsChart
import ru.zf.pravka.ui.BigStat
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.HBar
import ru.zf.pravka.ui.HBars
import ru.zf.pravka.ui.Hairline
import ru.zf.pravka.ui.HoursChart
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.LifeInk
import ru.zf.pravka.ui.LifeLegend
import ru.zf.pravka.ui.LifeSeries
import ru.zf.pravka.ui.LinesChart
import ru.zf.pravka.ui.LoadingLine
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.Readout
import ru.zf.pravka.ui.ReadoutRow
import ru.zf.pravka.ui.RefLine
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.Segments
import ru.zf.pravka.ui.SeriesToggles
import ru.zf.pravka.ui.ShareBar
import ru.zf.pravka.ui.StackBarsChart
import ru.zf.pravka.ui.ValueList
import ru.zf.pravka.ui.WhyNote
import ru.zf.pravka.ui.scrollFade
import ru.zf.pravka.ui.tap
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

// Общая статистика, издание «Куда уходит день» (11.10.2026). Владелец принёс
// разбор, который Claude собрал по архиву Правки за 43 дня, и сказал: «я
// никогда не заглядывал в нашу статистику… давай уберём нашу, а поставим вот
// ровно такую». Прежняя вкладка (день в графиках, тот же день неделю назад,
// треугольник, коэффициенты) снята целиком; здесь — разделы того разбора по
// порядку, только живые и за выбранный отрезок:
//
//   средний день по часам (выбор: будни, выходные, любой день недели) ·
//   средние сутки · четыре больших числа · окно будней 10–17 с календарём ·
//   недели качелями и цена в теле · мастерская против работы · встречи ·
//   план тренировок против факта, борьба, вис, пробежки · вес и белок · еда на
//   главном рецепте, БЖУ и вещества · деньги по месяцам, баланс, ЗФ ·
//   телефон по неделям · как это читать.
//
// Все числа — `core/LifeStats.kt` (JVM-тесты), графики — `ui/LifeCharts.kt`.
// Гипотез «почему так» модель здесь не пишет: вывод «Что видно» — из чисел,
// без толкований. Отрезок кончается вчера: сегодняшний неполный день не в счёте.

private val PERIODS = listOf(4 to "4 недели", 6 to "6 недель", 13 to "3 месяца")
private const val MONEY_MONTHS = 8
private val MONTHS = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private val MONTHS_FULL = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
private val PHONE_INKS = listOf(LifeInk.Sys, LifeInk.Work, LifeInk.Loss, LifeInk.Other)

/** Всё, что вкладка знает об отрезке; считается не на главном потоке. */
private class LifeModel(
    val from: LocalDate,
    val to: LocalDate,
    val weeks: Int,
    val days: Map<LocalDate, LifeStats.DayRibbon>,
    val fullDays: Int,
    val avgDay: List<LifeStats.DayLine>,
    val window: LifeStats.WindowStats,
    val weekRows: List<LifeStats.Week>,
    val cumulative: LifeStats.Cumulative,
    val catMin: Map<String, Double>,
    val plan: LifeStats.PlanFact,
    val bjj: List<LifeStats.WorkoutIn>,
    val hang: List<LifeStats.GtgIn>,
    val runs: List<LifeStats.WorkoutIn>,
    val weight: List<Pair<LocalDate, Double>>,
    val food: LifeStats.Food,
    val phone: LifeStats.Phone,
    val money: LifeStats.Money,
) {
    val workMin: Double get() = cumulative.workDay.sum()
    val sysMin: Double get() = cumulative.sysDay.sum()
}

@Composable
internal fun ReportTab(app: PravkaApp) {
    val context = LocalContext.current
    val entries by app.zasechkaStore.entriesFlow.collectAsState()
    val phoneDays by app.phoneStore.daysFlow.collectAsState()
    val labels by app.phoneStore.labelsFlow.collectAsState()
    val meals by app.foodStore.mealsFlow.collectAsState()
    val health by app.sportStore.healthFlow.collectAsState()
    val workouts by app.sportStore.workoutsFlow.collectAsState()
    val planDays by app.planStore.daysFlow.collectAsState()
    val gtg by app.strengthStore.gtgFlow.collectAsState()
    val sessions by app.strengthStore.sessionsFlow.collectAsState()
    val money by app.moneyStore.stateFlow.collectAsState()
    val proteinGoal by app.settings.foodProteinFlow.collectAsState(initial = 0)
    val goalWeight by app.settings.goalWeightFlow.collectAsState(initial = 0)
    var usageGranted by remember { mutableStateOf(PhoneSweeper.hasUsageAccess(context)) }

    // Сторы поднимаются лениво: первый читатель и грузит. Ошибка любого из
    // них не валит экран — статистика покажет то, что есть.
    LaunchedEffect(Unit) {
        runCatching { app.zasechkaStore.all() }
        runCatching { app.foodStore.load() }
        runCatching { app.sportStore.load() }
        runCatching { app.planStore.load() }
        runCatching { app.strengthStore.load() }
        runCatching { app.moneyStore.load() }
        runCatching { app.phoneStore.trackedApps() }
        usageGranted = PhoneSweeper.hasUsageAccess(context)
        if (usageGranted) runCatching { app.phoneSweeper.sweep() }
    }

    var periodIx by remember { mutableIntStateOf(1) }
    val weeks = PERIODS[periodIx].first
    val model by produceState<LifeModel?>(null, entries, phoneDays, meals, health, workouts, planDays, gtg, sessions, money, proteinGoal, weeks) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                buildModel(app, weeks, entries, phoneDays, meals, health, workouts, planDays, gtg, sessions, money.entries, proteinGoal)
            }.getOrNull()
        }
    }

    val wide = ru.zf.pravka.ui.twoPane()
    val leftState = rememberLazyListState()
    val rightState = rememberLazyListState()
    val body: LazyListScope.(Int) -> Unit = { side ->
        val left = side != 2
        val right = side != 1
        val m = model
        if (left) {
            item {
                Segments(PERIODS.map { it.second }, periodIx, { periodIx = it })
            }
        }
        if (m == null) {
            if (left) item { LoadingLine() }
        } else {
            if (left) {
                item { PeriodLine(m) }
                item { HoursCard(m) }
                item { AverageDayCard(m) }
                item { BigNumbers(m, usageGranted) }
                item { WindowCard(m) }
                item { WeeksCard(m) }
                item { WorkshopCard(m) }
            }
            if (right) {
                item { WorkCard(m) }
                item { SportCard(m) }
                item { BodyCard(m, proteinGoal, goalWeight) }
                item { FoodCard(m, proteinGoal) }
                item { MoneyCard(m) }
                item {
                    PhoneCard(m, labels, usageGranted) {
                        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        usageGranted = PhoneSweeper.hasUsageAccess(context)
                    }
                }
                item { HowToReadCard() }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            for ((side, st) in listOf(1 to leftState, 2 to rightState)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight().scrollFade(st),
                    state = st,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
                ) { body(side) }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().scrollFade(leftState),
            state = leftState,
            contentPadding = ScreenPad.Padding,
            verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
        ) { body(0) }
    }
}

private fun buildModel(
    app: PravkaApp,
    weeks: Int,
    entries: List<ru.zf.pravka.data.ZasechkaStore.Entry>,
    phoneDays: Map<String, ru.zf.pravka.data.PhoneStore.Day>,
    meals: List<ru.zf.pravka.data.FoodStore.Meal>,
    health: List<ru.zf.pravka.data.SportStore.Health>,
    workouts: List<ru.zf.pravka.data.SportStore.Workout>,
    planDays: List<ru.zf.pravka.data.PlanStore.PlanDay>,
    gtg: List<ru.zf.pravka.data.StrengthStore.GtgDay>,
    sessions: List<ru.zf.pravka.data.StrengthStore.Session>,
    moneyEntries: List<ru.zf.pravka.core.MoneyEntry>,
    proteinGoal: Int,
): LifeModel {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = DayFacts.localDay(now, zone)
    val (from, to) = LifeStats.period(today, weeks)
    val days = LifeStats.ribbon(entries, from, to, now, zone)
    val ws = workouts.map { w ->
        LifeStats.WorkoutIn(
            date = DayFacts.localDay(w.start, zone), type = w.type, name = w.name, minutes = w.minutes.toInt(),
            load = w.load, hr = w.avgHr, km = w.km, paceSec = w.paceSecPerKm, rpe = w.rpe,
        )
    }
    fun inRange(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
    val gtgIn = gtg.mapNotNull { g -> runCatching { LocalDate.parse(g.date) }.getOrNull()?.let { LifeStats.GtgIn(it, g.charged, g.hangSec) } }
    val healthIn = health.mapNotNull { h ->
        runCatching { LocalDate.parse(h.date) }.getOrNull()?.let { LifeStats.HealthIn(it, h.sleepHours, h.restingHr, h.steps, h.weightKg) }
    }
    val plan = planDays.mapNotNull { p ->
        runCatching { LocalDate.parse(p.date) }.getOrNull()?.let { LifeStats.PlanIn(it, p.name, p.type, p.charger, p.strength) }
    }
    val strength = sessions.mapNotNull { s -> runCatching { LocalDate.parse(s.date) }.getOrNull()?.let { LifeStats.StrengthIn(it, s.block) } }
    val bjjDates = ws.filter { it.bjj }.map { it.date }.toSet()
    val family = app.moneyEngine.scope(personal = true, zf = false)
    val zf = app.moneyEngine.scope(personal = false, zf = true)
    return LifeModel(
        from = from,
        to = to,
        weeks = weeks,
        days = days,
        fullDays = days.values.count { it.full },
        avgDay = LifeStats.averageDay(days),
        window = LifeStats.window(days, bjjDates),
        weekRows = LifeStats.weeks(days, healthIn, from, to),
        cumulative = LifeStats.cumulative(days.filterValues { it.full }),
        catMin = LifeStats.categoryMinutes(days.filterValues { it.full }),
        plan = LifeStats.planFact(plan, ws, gtgIn, strength, from, to),
        bjj = ws.filter { it.bjj && inRange(it.date) }.sortedBy { it.date },
        hang = gtgIn.filter { it.hangSec > 0 && inRange(it.date) }.sortedBy { it.date },
        runs = ws.filter { it.type.contains("run", true) && inRange(it.date) }.sortedBy { it.date },
        weight = healthIn.filter { it.weightKg > 0 && !it.date.isBefore(today.minusDays(90)) }.sortedBy { it.date }.map { it.date to it.weightKg },
        food = LifeStats.food(meals, from, to, proteinGoal, zone),
        phone = LifeStats.phone(phoneDays, from, to, furniture = ::isFurniturePkg),
        money = LifeStats.money(moneyEntries, today, MONEY_MONTHS, family, zf),
    )
}

// ---------------------------------------------------------------------------
// Форматы
// ---------------------------------------------------------------------------

private fun f1(v: Double): String {
    val r = (v * 10).roundToInt() / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else String.format(Locale.US, "%.1f", r).replace('.', ',')
}

private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v).replace('.', ',').trimEnd('0').trimEnd(',')
private fun pct(v: Double): String = "${(v * 100).roundToInt()}%"
private fun mins(v: Double): String = "${v.roundToInt()} мин"
private fun dm(d: LocalDate): String = "%02d.%02d".format(d.dayOfMonth, d.monthValue)
private fun rubShort(kop: Long): String {
    val rub = kop / 100.0
    return when {
        kotlin.math.abs(rub) >= 1_000_000 -> f2(rub / 1_000_000) + " млн"
        kotlin.math.abs(rub) >= 1_000 -> "${(rub / 1000).roundToInt()} тыс."
        else -> "${rub.roundToInt()} ₽"
    }
}

private fun weekRange(monday: LocalDate, from: LocalDate, to: LocalDate): String {
    val a = if (monday.isBefore(from)) from else monday
    val b = monday.plusDays(6).let { if (it.isAfter(to)) to else it }
    return if (a.month == b.month) "${a.dayOfMonth}–${Fmt.dayMonth(b)}" else "${Fmt.dayMonth(a)} — ${Fmt.dayMonth(b)}"
}

/** Неделя коротко для таблицы: «7–13 сен», «28 сен – 4 окт». */
private fun weekShort(monday: LocalDate, from: LocalDate, to: LocalDate): String {
    val a = if (monday.isBefore(from)) from else monday
    val b = monday.plusDays(6).let { if (it.isAfter(to)) to else it }
    return if (a.month == b.month) "${a.dayOfMonth}–${Fmt.dayMon(b)}" else "${Fmt.dayMon(a)} – ${Fmt.dayMon(b)}"
}

@Composable
private fun Lead(text: String) {
    Text(text, style = LocalPravkaType.current.body, color = Ink.Text, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun Cap(text: String) {
    Text(text, style = LocalPravkaType.current.caption, color = Ink.TextMeta, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SubTitle(text: String) {
    Text(
        text,
        style = LocalPravkaType.current.bodyStrong.copy(fontSize = 16.sp),
        color = Ink.TextStrong,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
    )
}

// ---------------------------------------------------------------------------
// Шапка отрезка, средний день по часам, средние сутки, большие числа
// ---------------------------------------------------------------------------

@Composable
private fun PeriodLine(m: LifeModel) {
    val total = LifeStats.daysBetween(m.from, m.to) + 1
    PaperHint(
        "С ${Fmt.dayMonth(m.from)} по ${Fmt.dayMonth(m.to)}: $total дней, в счёте ${m.fullDays} — " +
            "сутки, где лента закрыла хотя бы 20 часов. Сегодняшний неполный день не учитывается.",
    )
}

@Composable
private fun HoursCard(m: LifeModel) {
    var pick by remember { mutableStateOf(DayPick.WEEKDAYS) }
    val profile = remember(m, pick) { LifeStats.hourProfile(m.days, pick) }
    val weekendish = pick == DayPick.WEEKENDS || pick == DayPick.SAT || pick == DayPick.SUN
    PaperCard(label = "Средний день по часам") {
        DayPickChips(pick) { pick = it }
        Spacer(Modifier.height(10.dp))
        if (profile.empty) {
            PaperHint("За этот отрезок нет полных суток: ${pick.many}.")
            return@PaperCard
        }
        Cap(
            "${pick.many.replaceFirstChar { it.uppercase() }} в среднем, " +
                plural(profile.days, "сутки", "суток", "суток") + ": сколько минут каждого часа ушло на что." +
                if (!weekendish) " Обведено окно 10–17." else "",
        )
        HoursChart(
            profile,
            window = if (weekendish) null else LifeStats.WINDOW_FROM_H until LifeStats.WINDOW_TO_H,
        )
        LifeLegend(HOURS_LEGEND)
    }
}

/** Легенда часов — в порядке отчёта: сначала то, что ищешь глазами. */
internal val HOURS_LEGEND: List<Pair<String, Color>> = listOf(
    Group.SOLO, Group.WORK, Group.SYS, Group.LOSS, Group.LIFE, Group.FAMILY, Group.SPORT, Group.SLEEP, Group.GAP, Group.OTHER,
).map { it.label to LifeInk.of(it) }

/** Выбор дней: все, будни, выходные, любой день недели. */
@Composable
internal fun DayPickChips(pick: DayPick, onPick: (DayPick) -> Unit) {
    ChipRow {
        DayPick.entries.forEach { p -> PaperChip(p.chip, p == pick, { onPick(p) }) }
    }
}

@Composable
private fun AverageDayCard(m: LifeModel) {
    if (m.avgDay.isEmpty()) return
    PaperCard(label = "Средние сутки, все " + plural(m.fullDays, "день", "дня", "дней")) {
        ShareBar(m.avgDay.map { it.hours.toFloat() to LifeInk.of(it.color) })
        ValueList(m.avgDay.map { Triple(it.label, f1(it.hours) + " ч", LifeInk.of(it.color)) })
    }
}

@Composable
private fun BigNumbers(m: LifeModel, phoneOn: Boolean) {
    PaperCard {
        val weeksWord = plural(m.weeks, "неделю", "недели", "недель")
        Row(Modifier.fillMaxWidth()) {
            BigStat("${(m.workMin / 60).roundToInt()} ч", "работы за $weeksWord, во всех рабочих категориях", Modifier.weight(1f).padding(end = 8.dp))
            BigStat("${(m.sysMin / 60).roundToInt()} ч", "мастерской: Правка, Засечка, настройка", Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            BigStat(m.plan.share?.let { pct(it) } ?: "—", "исполнения тренировочного плана", Modifier.weight(1f).padding(end = 8.dp))
            BigStat(
                if (phoneOn && m.phone.days > 0) "${f1(m.phone.screenMin / 60)} ч" else "—",
                "экрана телефона в день",
                Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Окно будней 10–17
// ---------------------------------------------------------------------------

private val ACCUSATIVE = mapOf("работа" to "работу", "еда" to "еду", "мастерская" to "мастерскую", "семья" to "семью")

@Composable
private fun WindowCard(m: LifeModel) {
    val w = m.window
    if (w.days == 0) return
    PaperCard(label = "Окно: будни, дома, с 10 до 17") {
        val gap = w.rows.firstOrNull { it.color == Group.GAP }
        val spent = w.rows.filter { it.color != Group.GAP }.take(8)
        Lead(
            "В семичасовом окне будней в среднем " +
                spent.mapIndexed { i, r ->
                    val what = ACCUSATIVE[r.label] ?: r.label
                    if (i == 0) "${r.minutes.roundToInt()} минут уходит на $what" else "${r.minutes.roundToInt()} на $what"
                }.joinToString(", ") +
                (gap?.let { ", ${it.minutes.roundToInt()} остаются неразмеченными" } ?: "") + ".",
        )
        HBars(
            w.rows.take(10).map { r ->
                HBar(r.label, mins(r.minutes), (r.minutes / (w.rows.first().minutes.coerceAtLeast(1.0))).toFloat(), LifeInk.of(r.color))
            },
        )
        SubTitle("Все " + plural(w.days, "будний день", "будних дня", "будних дней"))
        WeekdayCalendar(w, m.from, m.to)
        SubTitle("Доля дней с соло")
        val home = w.home
        val out = w.outside
        val bjj = w.bjjDays
        fun row(label: String, list: List<LifeStats.WeekdayMark>): HBar {
            val s = list.count { it.solo }
            val share = if (list.isEmpty()) 0.0 else s.toDouble() / list.size
            return HBar("$label: $s из ${list.size}", if (list.isEmpty()) "—" else pct(share), share.toFloat(), LifeInk.Solo)
        }
        HBars(
            listOfNotNull(
                home.takeIf { it.isNotEmpty() }?.let { row("Весь день дома", it) },
                out.takeIf { it.isNotEmpty() }?.let { row("С выходом из дома", it) },
                bjj.takeIf { it.isNotEmpty() }?.let { row("Дни борьбы", it) },
            ),
        )
        if (home.isNotEmpty() && out.isNotEmpty()) {
            val sh = home.count { it.solo }.toDouble() / home.size
            val so = out.count { it.solo }.toDouble() / out.size
            Spacer(Modifier.height(12.dp))
            WhyNote(
                "Что видно",
                when {
                    sh - so >= 0.2 -> "Соло в ${pct(sh)} будних дней дома и в ${pct(so)} дней с выходом из дома между 10 и 16. " +
                        "Связь может быть и обратной: встречи переставляются, чтобы остаться дома."
                    so - sh >= 0.2 -> "Соло чаще в дни с выходом из дома: ${pct(so)} против ${pct(sh)} дома."
                    else -> "На этом отрезке выход из дома почти не меняет долю дней с соло: ${pct(sh)} дома, ${pct(so)} с выходом."
                },
            )
        }
    }
}

private val dashed = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))

@Composable
private fun WeekdayCalendar(w: LifeStats.WindowStats, from: LocalDate, to: LocalDate) {
    val t = LocalPravkaType.current
    var info by remember(w) { mutableStateOf<String?>(null) }
    val byDate = w.marks.associateBy { it.date }
    // Недели — от первого будня ленты до последнего: пустые недели до начала ленты не рисуются.
    val first = w.marks.minOfOrNull { it.date } ?: from
    val last = w.marks.maxOfOrNull { it.date } ?: to
    val firstMonday = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val rows = generateSequence(firstMonday) { it.plusDays(7) }.takeWhile { !it.isAfter(last) }.toList()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт").forEach {
                Text(it, style = t.caption, color = Ink.TextMeta, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        for (monday in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in 0 until 5) {
                    val d = monday.plusDays(k.toLong())
                    val mark = byDate[d]
                    Box(Modifier.weight(1f).aspectRatio(1.2f)) {
                        if (mark != null) CalendarCell(mark) { info = calendarInfo(mark) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KeyBox(solid = true, fill = null)
            Text("выходил между 10 и 16", style = t.caption, color = Ink.TextMeta)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KeyBox(solid = false, fill = null)
            Text("весь день дома", style = t.caption, color = Ink.TextMeta)
            KeyBox(solid = false, fill = LifeInk.Solo.copy(alpha = 0.30f))
            Text("было соло", style = t.caption, color = Ink.TextMeta)
            Text("BJJ", style = t.caption.copy(fontWeight = FontWeight.Bold), color = LifeInk.Sport)
            Text("борьба", style = t.caption, color = Ink.TextMeta)
        }
        Text(info ?: "Нажми на день — детали здесь.", style = t.body, color = if (info == null) Ink.TextMeta else Ink.Text, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun calendarInfo(m: LifeStats.WeekdayMark): String =
    "${Fmt.dayMonth(m.date)}: " + (if (m.out) "выходил из дома в середине дня" else "весь день дома") +
        (if (m.meetings > 0) ", встреч ${m.meetings}" else "") +
        (if (m.bjj) ", борьба" else "") +
        (if (m.solo) ", было соло" else "") +
        ", работа в ленте ${m.workMin} мин."

@Composable
private fun KeyBox(solid: Boolean, fill: Color?) {
    Box(
        Modifier
            .padding(top = 2.dp)
            .height(13.dp)
            .aspectRatio(1.25f)
            .clip(RoundedCornerShape(4.dp))
            .background(fill ?: Color.Transparent)
            .drawBehind {
                drawRoundRect(
                    if (solid) Ink.TextStrong else Ink.TextMeta,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = if (solid) null else dashed),
                )
            },
    )
}

@Composable
private fun CalendarCell(mark: LifeStats.WeekdayMark, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(9.dp))
            .background(if (mark.solo) LifeInk.Solo.copy(alpha = 0.30f) else Ink.Text.copy(alpha = 0.04f))
            .drawBehind {
                drawRoundRect(
                    if (mark.out) Ink.TextStrong else Ink.TextMeta,
                    cornerRadius = CornerRadius(9.dp.toPx()),
                    style = Stroke(2.dp.toPx(), pathEffect = if (mark.out) null else dashed),
                )
            }
            .tap(onClick, label = calendarInfo(mark))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text("${mark.date.dayOfMonth}", style = t.label, color = Ink.TextStrong)
        if (mark.bjj) {
            Text(
                "BJJ",
                style = t.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                color = LifeInk.Sport,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Недели
// ---------------------------------------------------------------------------

@Composable
private fun WeeksCard(m: LifeModel) {
    val ws = m.weekRows.filter { it.days > 0 }
    if (ws.size < 2) return
    PaperCard(label = "Недели") {
        Cap("Минут в день, средние по неделям. Нажми на название — включить или выключить линию.")
        var series by remember(ws) {
            mutableStateOf(
                LifeStats.Line.entries.map { line ->
                    LifeSeries(
                        line.label,
                        if (line == LifeStats.Line.MARIANNA) LifeInk.Other else LifeInk.of(line.color),
                        ws.map { it.perDay.getValue(line).toFloat() },
                        hidden = line == LifeStats.Line.FAMILY || line == LifeStats.Line.MARIANNA,
                    )
                },
            )
        }
        SeriesToggles(series, { i -> series = series.mapIndexed { k, s -> if (k == i) s.copy(hidden = !s.hidden) else s } })
        LinesChart(
            labels = ws.map { dm(it.monday) },
            series = series,
            tip = { i ->
                val w = ws[i]
                Readout(
                    weekRange(w.monday, m.from, m.to),
                    series.filter { !it.hidden }.map { s -> ReadoutRow(s.color, "${s.name} ${s.data[i]?.roundToInt() ?: 0} мин") } +
                        listOfNotNull(
                            ReadoutRow(null, "встреч ${w.meetings}"),
                            w.sleepH?.let { ReadoutRow(null, "сон ${f1(it)} ч") },
                        ),
                )
            },
            height = 230.dp,
        )
        // Качели: неделя с наибольшей работой и неделя с наибольшей мастерской.
        val maxWork = ws.maxBy { it.perDay.getValue(LifeStats.Line.WORK) }
        val maxSys = ws.maxBy { it.perDay.getValue(LifeStats.Line.SYS) }
        Spacer(Modifier.height(10.dp))
        WhyNote(
            "Что видно",
            "Больше всего работы — неделя ${weekRange(maxWork.monday, m.from, m.to)}: " +
                "${maxWork.perDay.getValue(LifeStats.Line.WORK).roundToInt()} мин в день, встреч ${maxWork.meetings}" +
                (maxWork.sleepH?.let { ", сон ${f1(it)} ч, ночей короче шести часов — ${maxWork.shortNights}" } ?: "") +
                ", соло ${maxWork.perDay.getValue(LifeStats.Line.SOLO).roundToInt()} мин в день. " +
                "Больше всего мастерской — ${weekRange(maxSys.monday, m.from, m.to)}: " +
                "${maxSys.perDay.getValue(LifeStats.Line.SYS).roundToInt()} мин в день, соло " +
                "${maxSys.perDay.getValue(LifeStats.Line.SOLO).roundToInt()}, работа ${maxSys.perDay.getValue(LifeStats.Line.WORK).roundToInt()}.",
        )
        SubTitle("Цена в теле")
        BodyTable(ws, m)
    }
}

@Composable
private fun BodyTable(ws: List<LifeStats.Week>, m: LifeModel) {
    val t = LocalPravkaType.current
    val rhrAvg = ws.mapNotNull { it.rhr }.takeIf { it.isNotEmpty() }?.average()
    @Composable
    fun androidx.compose.foundation.layout.RowScope.cell(text: String, weight: Float, hot: Boolean = false, head: Boolean = false) {
        Text(
            text,
            style = if (head) t.caption else t.label.copy(fontWeight = if (hot) FontWeight.Bold else FontWeight.Normal),
            color = when {
                head -> Ink.TextMeta
                hot -> LifeInk.Warn
                else -> Ink.Text
            },
            modifier = Modifier.weight(weight),
            maxLines = 1,
        )
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            cell("Неделя", 1.6f, head = true)
            cell("Сон, ч", 0.8f, head = true)
            cell("Ночи < 6 ч", 1f, head = true)
            cell("Пульс", 0.8f, head = true)
            cell("Шаги", 0.9f, head = true)
        }
        ws.forEach { w ->
            Hairline(alpha = 0.07f)
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                cell(weekShort(w.monday, m.from, m.to), 1.6f)
                cell(w.sleepH?.let { f1(it) } ?: "—", 0.8f, hot = (w.sleepH ?: 9.0) < 6.2)
                cell(if (w.nights > 0) "${w.shortNights} из ${w.nights}" else "—", 1f, hot = w.shortNights >= 3)
                cell(w.rhr?.let { f1(it) } ?: "—", 0.8f, hot = rhrAvg != null && (w.rhr ?: 0.0) >= rhrAvg + 1.5)
                cell(w.steps?.let { Fmt.num(it.roundToInt()) } ?: "—", 0.9f)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Мастерская и работа
// ---------------------------------------------------------------------------

@Composable
private fun WorkshopCard(m: LifeModel) {
    val c = m.cumulative
    if (c.dates.size < 2) return
    PaperCard(label = "Мастерская против работы") {
        Lead("За отрезок ${f1(m.workMin / 60)} ч работы и ${f1(m.sysMin / 60)} ч мастерской (Систематизация: Правка, Засечка, настройка).")
        Cap("Накопительно, часов. Подсвечена последняя неделя.")
        val work = c.workHours
        val sys = c.sysHours
        LinesChart(
            labels = c.dates.map { dm(it) },
            series = listOf(
                LifeSeries("Работа", LifeInk.Work, work.map { it.toFloat() }),
                LifeSeries("Мастерская", LifeInk.Sys, sys.map { it.toFloat() }),
            ),
            tip = { i ->
                Readout(
                    Fmt.dayMonth(c.dates[i]),
                    listOf(
                        ReadoutRow(LifeInk.Work, "работа ${f1(work[i])} ч (+${c.workDay[i].roundToInt()} мин)"),
                        ReadoutRow(LifeInk.Sys, "мастерская ${f1(sys[i])} ч (+${c.sysDay[i].roundToInt()} мин)"),
                    ),
                )
            },
            dots = false,
            endLabels = true,
            endFmt = { f1(it.toDouble()) },
            bands = listOf((c.dates.size - 7).coerceAtLeast(0)..c.dates.lastIndex),
            hint = "Нажми на день — числа ниже.",
        )
        val ws = m.weekRows.filter { it.days > 0 }
        if (ws.isNotEmpty()) {
            SubTitle("Сколько записей в ленте в день")
            BarsChart(
                labels = ws.map { dm(it.monday) },
                values = ws.map { it.entriesPerDay.toFloat() },
                colorOf = { _, _ -> LifeInk.Sleep },
                tip = { i ->
                    val w = ws[i]
                    Readout(
                        weekRange(w.monday, m.from, m.to),
                        listOf(
                            ReadoutRow(null, "записей в день ${f1(w.entriesPerDay)}"),
                            ReadoutRow(null, "средний блок ${w.avgBlockMin.roundToInt()} мин"),
                            ReadoutRow(null, "не размечено ${f1(w.gapShare * 100)}%"),
                        ),
                    )
                },
                valueFmt = { f1(it.toDouble()) },
            )
            val a = ws.first()
            val b = ws.last()
            if (a !== b) {
                PaperHint(
                    "Неделя ${weekRange(a.monday, m.from, m.to)}: ${f1(a.entriesPerDay)} записей в день, средний блок " +
                        "${a.avgBlockMin.roundToInt()} мин, неразмеченного ${f1(a.gapShare * 100)}%. Последняя: ${f1(b.entriesPerDay)}, " +
                        "блок ${b.avgBlockMin.roundToInt()} мин, неразмеченного ${f1(b.gapShare * 100)}%. Меньше записей — длиннее блоки и грубее измерение.",
                )
            }
        }
    }
}

@Composable
private fun WorkCard(m: LifeModel) {
    val ws = m.weekRows.filter { it.days > 0 }
    if (ws.isEmpty()) return
    PaperCard(label = "Работа") {
        val total = ws.sumOf { it.meetings }
        val peak = ws.maxBy { it.meetings }
        val current = (m.catMin.entries.filter { it.key.trim().lowercase().startsWith("работа: текущ") }.sumOf { it.value }) / m.fullDays.coerceAtLeast(1)
        val lead = m.catMin.entries.filter { it.key.trim().lowercase().startsWith("работа: привлеч") }.sumOf { it.value }
        Lead(
            plural(total, "встреча", "встречи", "встреч") + " за отрезок" +
                (if (peak.meetings > 0) ", пик ${peak.meetings} в неделю ${weekRange(peak.monday, m.from, m.to)}" else "") +
                ". Текущая работа руками — ${current.roundToInt()} мин в день, в «привлечение» попало ${Fmt.dur(lead.roundToInt())}.",
        )
        Cap("Рабочих встреч и созвонов в неделю (блоки «Работа: звонки» от 10 минут).")
        BarsChart(
            labels = ws.map { dm(it.monday) },
            values = ws.map { it.meetings.toFloat() },
            colorOf = { _, _ -> LifeInk.Work },
            tip = { i ->
                val w = ws[i]
                Readout(
                    weekRange(w.monday, m.from, m.to),
                    listOf(ReadoutRow(LifeInk.Work, "встреч ${w.meetings}"), ReadoutRow(null, "работа в ленте ${w.perDay.getValue(LifeStats.Line.WORK).roundToInt()} мин в день")),
                )
            },
            valueFmt = { it.roundToInt().toString() },
            height = 170.dp,
        )
    }
}

// ---------------------------------------------------------------------------
// Спорт, тело, еда
// ---------------------------------------------------------------------------

@Composable
private fun SportCard(m: LifeModel) {
    val t = LocalPravkaType.current
    PaperCard(label = "Спорт: план и факт") {
        val p = m.plan
        if (p.planned > 0) {
            Lead(plural(p.planned, "пункт", "пункта", "пунктов") + " в плане, сделано ${p.done} — ${pct(p.share ?: 0.0)}.")
            HBars(
                p.rows.map { r -> HBar(r.kind.label, "${r.done} из ${r.planned}", r.done.toFloat() / r.planned, LifeInk.Sport) },
            )
            if (p.bjjUnplanned > 0) {
                PaperHint("Борьбы без плана: ещё ${p.bjjUnplanned}.")
            }
        } else {
            PaperHint("Плана из intervals за этот отрезок нет — телефон хранит календарь на 40 дней назад.")
        }
        if (m.bjj.isNotEmpty()) {
            SubTitle("BJJ: нагрузка по тренировкам")
            val perWeek = m.bjj.size / (m.weeks.toDouble())
            Cap(plural(m.bjj.size, "тренировка", "тренировки", "тренировок") + " за отрезок, ${f1(perWeek)} в неделю.")
            BarsChart(
                labels = m.bjj.map { dm(it.date) },
                values = m.bjj.map { it.load.toFloat() },
                colorOf = { _, _ -> LifeInk.Sport },
                tip = { i ->
                    val w = m.bjj[i]
                    Readout(Fmt.dayMonth(w.date), listOf(ReadoutRow(LifeInk.Sport, "нагрузка ${w.load}"), ReadoutRow(null, "пульс ${w.hr}"), ReadoutRow(null, "${w.minutes} мин")))
                },
                valueFmt = { it.roundToInt().toString() },
                height = 170.dp,
            )
        }
        if (m.hang.isNotEmpty()) {
            SubTitle("Вис на турнике, секунд")
            BarsChart(
                labels = m.hang.map { dm(it.date) },
                values = m.hang.map { it.hangSec.toFloat() },
                colorOf = { _, _ -> LifeInk.Family },
                tip = { i -> Readout(Fmt.dayMonth(m.hang[i].date), listOf(ReadoutRow(LifeInk.Family, "вис ${m.hang[i].hangSec} с"))) },
                valueFmt = { it.roundToInt().toString() },
                height = 160.dp,
            )
        }
        if (m.runs.isNotEmpty()) {
            SubTitle("Пробежки")
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    listOf("Дата" to 1f, "Км" to 0.8f, "Темп" to 0.9f, "Пульс" to 0.8f, "RPE" to 0.6f).forEach { (h, w) ->
                        Text(h, style = t.caption, color = Ink.TextMeta, modifier = Modifier.weight(w))
                    }
                }
                m.runs.forEach { r ->
                    Hairline(alpha = 0.07f)
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(dm(r.date), style = t.label, color = Ink.Text, modifier = Modifier.weight(1f))
                        Text(f1(r.km), style = t.label, color = Ink.Text, modifier = Modifier.weight(0.8f))
                        Text(if (r.paceSec > 0) "%d:%02d".format(r.paceSec / 60, r.paceSec % 60) else "—", style = t.label, color = Ink.Text, modifier = Modifier.weight(0.9f))
                        Text(if (r.hr > 0) "${r.hr}" else "—", style = t.label, color = Ink.Text, modifier = Modifier.weight(0.8f))
                        Text(if (r.rpe > 0) "${r.rpe}" else "—", style = t.label, color = Ink.Text, modifier = Modifier.weight(0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BodyCard(m: LifeModel, proteinGoal: Int, goalWeight: Int) {
    val f = m.food
    if (m.weight.isEmpty() && f.recordedDays == 0) return
    PaperCard(label = "Тело") {
        if (m.weight.size >= 2) {
            val first = m.weight.first()
            val last = m.weight.last()
            Lead(
                "Вес ${f1(last.second)} кг на ${Fmt.dayMonth(last.first)}; за три месяца от ${f1(m.weight.minOf { it.second })} " +
                    "до ${f1(m.weight.maxOf { it.second })}, с ${Fmt.dayMonth(first.first)} " +
                    (if (last.second <= first.second) "минус " else "плюс ") + "${f1(kotlin.math.abs(last.second - first.second))} кг" +
                    (if (goalWeight > 0) ". Цель — $goalWeight кг." else "."),
            )
            val lo = m.weight.minOf { it.second }.toFloat()
            val hi = m.weight.maxOf { it.second }.toFloat()
            LinesChart(
                labels = m.weight.map { dm(it.first) },
                series = listOf(LifeSeries("Вес", LifeInk.Sleep, m.weight.map { it.second.toFloat() })),
                tip = { i -> Readout(Fmt.dayMonth(m.weight[i].first), listOf(ReadoutRow(LifeInk.Sleep, "${f1(m.weight[i].second)} кг"))) },
                yMin = kotlin.math.floor(lo - 0.5f),
                yMax = kotlin.math.ceil(hi + 0.5f),
                endLabels = true,
                endFmt = { f1(it.toDouble()) + " кг" },
                height = 200.dp,
            )
        }
        if (f.recordedDays > 0) {
            SubTitle("Белок по дням, г")
            val goal = proteinGoal.takeIf { it > 0 }
            Cap(if (goal != null) "Линия — цель $goal г, ниже неё тёплым." else "Цели белка в настройках Еды нет.")
            BarsChart(
                labels = f.days.map { dm(it.date) },
                values = f.days.map { d -> if (d.kcal > 0 || d.protein > 0) d.protein.toFloat() else null },
                colorOf = { _, v -> if (goal != null && v < goal) LifeInk.Warn else LifeInk.Family },
                tip = { i ->
                    val d = f.days[i]
                    if (d.kcal == 0 && d.protein == 0) Readout(Fmt.dayMonth(d.date), listOf(ReadoutRow(null, "еда не записана")))
                    else Readout(Fmt.dayMonth(d.date), listOf(ReadoutRow(LifeInk.Family, "белок ${d.protein} г"), ReadoutRow(null, "${Fmt.num(d.kcal)} ккал")))
                },
                refs = goal?.let { listOf(RefLine(it.toFloat(), "$it г")) } ?: emptyList(),
                valueFmt = null,
                fill = 0.72f,
                height = 200.dp,
            )
        }
    }
}

@Composable
private fun FoodCard(m: LifeModel, proteinGoal: Int) {
    val f = m.food
    if (f.recordedDays == 0) return
    val t = LocalPravkaType.current
    PaperCard(label = "Еда") {
        val r = f.recipe
        if (r != null) {
            Text(
                (if (r.kcalShare >= 0.35 || r.proteinShare >= 0.4) "Еда держится на одном рецепте: " else "Главный рецепт: ") +
                    "${pct(r.kcalShare)} калорий и ${pct(r.proteinShare)} белка",
                style = t.titleS.copy(fontSize = 20.sp, lineHeight = 25.sp),
                color = Ink.TextStrong,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Lead("Около ${Fmt.num(r.kcal)} ккал и ${r.protein} г белка за раз, в ${r.days} днях из ${f.recordedDays}.")
            ChipRow {
                r.items.forEach { item ->
                    Text(
                        item.name + if (item.grams > 0) " ${item.grams} г" else "",
                        style = t.label,
                        color = Ink.Text,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink.Text.copy(alpha = 0.07f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            BigStat(
                "${f.avgProtein.roundToInt()} г",
                "белка в среднем" + if (proteinGoal > 0) ", $proteinGoal+ в ${f.proteinGoalDays} днях из ${f.recordedDays}" else "",
                Modifier.weight(1f).padding(end = 8.dp),
            )
            BigStat(Fmt.num(f.avgKcal.roundToInt()), "ккал в среднем по записанному", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            BigStat("${f.avgFiber.roundToInt()} г", "клетчатки, 30+ только в ${f.fiberDays30} днях", Modifier.weight(1f).padding(end = 8.dp))
            val wk = f.weekdayKcal
            val we = f.weekendKcal
            if (wk != null && we != null && wk > 0) {
                val d = we / wk - 1
                BigStat(
                    (if (d >= 0) "+" else "−") + pct(kotlin.math.abs(d)),
                    "калорий в выходные: ${Fmt.num(we.roundToInt())} против ${Fmt.num(wk.roundToInt())}",
                    Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        if (r != null) {
            SubTitle("Калории по дням: рецепт и всё остальное")
            var stacks by remember(f) {
                mutableStateOf(
                    listOf(
                        LifeSeries("Рецепт", LifeInk.Sys, f.days.map { d -> d.recipeKcal.toFloat().takeIf { d.kcal > 0 } }),
                        LifeSeries("Всё остальное", LifeInk.Sleep, f.days.map { d -> (d.kcal - d.recipeKcal).toFloat().takeIf { d.kcal > 0 } }),
                    ),
                )
            }
            SeriesToggles(stacks, { i -> stacks = stacks.mapIndexed { k, s -> if (k == i) s.copy(hidden = !s.hidden) else s } })
            StackBarsChart(
                labels = f.days.map { dm(it.date) },
                stacks = stacks,
                tip = { i ->
                    val d = f.days[i]
                    if (d.kcal == 0) Readout(Fmt.dayMonth(d.date), listOf(ReadoutRow(null, "еда не записана")))
                    else Readout(
                        Fmt.dayMonth(d.date),
                        listOf(
                            ReadoutRow(LifeInk.Sys, "рецепт ${Fmt.num(d.recipeKcal)} ккал"),
                            ReadoutRow(LifeInk.Sleep, "остальное ${Fmt.num(d.kcal - d.recipeKcal)} ккал"),
                            ReadoutRow(null, "белок ${d.protein} г"),
                        ),
                    )
                },
                fill = 0.74f,
            )
        }
        SubTitle("Белки, жиры, углеводы — доля калорий")
        val pk = f.avgProtein * 4
        val fk = f.avgFat * 9
        val ck = f.avgCarbs * 4
        val sum = (pk + fk + ck).coerceAtLeast(1.0)
        ShareBar(listOf(pk.toFloat() to LifeInk.Work, fk.toFloat() to LifeInk.Sys, ck.toFloat() to LifeInk.Family))
        ValueList(
            listOf(
                Triple("Белок ${f.avgProtein.roundToInt()} г", pct(pk / sum), LifeInk.Work),
                Triple("Жиры ${f.avgFat.roundToInt()} г", pct(fk / sum), LifeInk.Sys),
                Triple("Углеводы ${f.avgCarbs.roundToInt()} г", pct(ck / sum), LifeInk.Family),
            ),
        )
        if (f.micro.isNotEmpty()) {
            SubTitle("Вещества, средний процент нормы")
            Cap("За " + plural(f.microDays, "сутки", "суток", "суток") + " с посчитанными веществами, еда и таблетки вместе. Черта — 100%; у натрия это потолок, а не цель.")
            HBars(
                f.micro.map { (n, share) ->
                    val pctV = (share * 100).roundToInt()
                    HBar(
                        if (n.limit) "${n.name}, потолок" else n.name,
                        "$pctV%",
                        (share / 3.0).toFloat(),
                        when {
                            n.limit -> LifeInk.Loss
                            share < 0.7 -> LifeInk.Warn
                            share < 1.0 -> LifeInk.Loss
                            else -> LifeInk.Family
                        },
                    )
                },
                marker = 1f / 3f,
            )
            val low = f.micro.filter { !it.first.limit && it.second < 0.7 }.map { it.first.name.replaceFirstChar { c -> c.lowercase() } }
            if (low.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                WhyNote("Что видно", "Ниже 70% нормы: ${low.joinToString(", ")}. Считается по записанному: недописанная еда тянет цифры вниз.")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Деньги
// ---------------------------------------------------------------------------

private val MONEY_INKS = mapOf(
    MoneyCategories.G_HOME to LifeInk.Life,
    MoneyCategories.G_KIDS to LifeInk.Family,
    MoneyCategories.G_LIFE to LifeInk.Work,
    MoneyCategories.G_HEALTH to LifeInk.Sport,
)

@Composable
private fun MoneyCard(m: LifeModel) {
    val mo = m.money
    if (mo.empty) return
    val months = mo.months
    val labels = months.map { MONTHS[it.month.monthValue - 1] + if (it.partial) "*" else "" }
    val full = months.filter { !it.partial && it.spent > 0 }
    PaperCard(label = "Деньги семьи") {
        if (full.isNotEmpty()) {
            val spent = full.map { it.spent }
            val income = full.map { it.income }
            val avg = spent.average().toLong()
            val days = full.sumOf { it.month.lengthOfMonth() }
            Lead(
                "За " + plural(full.size, "полный месяц", "полных месяца", "полных месяцев") +
                    " траты ${rubShort(spent.sum())}, доход ${rubShort(income.sum())}: покрыто ${pct(income.sum().toDouble() / spent.sum().coerceAtLeast(1))}.",
            )
            Row(Modifier.fillMaxWidth()) {
                BigStat(rubShort(avg), "траты семьи в месяц", Modifier.weight(1f).padding(end = 8.dp))
                BigStat(rubShort(spent.sum() / days.coerceAtLeast(1)), "в день в среднем", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        Cap("Траты по месяцам, тыс. ₽, и линия дохода. * — месяц идёт.")
        var stacks by remember(mo) {
            mutableStateOf(LifeStats.MONEY_GROUPS.map { g -> LifeSeries(g, MONEY_INKS[g] ?: LifeInk.Other, months.map { (it.groups[g] ?: 0L) / 100_000f }) })
        }
        var lineOn by remember(mo) { mutableStateOf(true) }
        val line = LifeSeries("Доход", LifeInk.Sys, months.map { it.income / 100_000f }, hidden = !lineOn)
        SeriesToggles(stacks + line, { i ->
            if (i == stacks.size) lineOn = !lineOn
            else stacks = stacks.mapIndexed { k, s -> if (k == i) s.copy(hidden = !s.hidden) else s }
        })
        StackBarsChart(
            labels = labels,
            stacks = stacks,
            line = line,
            tip = { i ->
                val mm = months[i]
                Readout(
                    MONTHS_FULL[mm.month.monthValue - 1] + if (mm.partial) " (идёт)" else "",
                    listOf(ReadoutRow(null, "траты ${rubShort(mm.spent)}")) +
                        LifeStats.MONEY_GROUPS.map { g -> ReadoutRow(MONEY_INKS[g], "${g.lowercase()} ${rubShort(mm.groups[g] ?: 0L)}") } +
                        ReadoutRow(LifeInk.Sys, "доход ${rubShort(mm.income)}: ЗФ ${rubShort(mm.incomeZf)}, Марианна ${rubShort(mm.incomeMarianna)}"),
                )
            },
            yFmt = { v -> if (v >= 1000) f1(v / 1000.0) + "м" else v.roundToInt().toString() },
            xEvery = 1,
            height = 240.dp,
        )
        SubTitle("Доход минус траты, накопительно, млн ₽")
        val run = mo.running
        LinesChart(
            labels = labels,
            series = listOf(LifeSeries("Баланс", LifeInk.Work, run.map { it / 100_000_000f })),
            tip = { i ->
                val mm = months[i]
                Readout(
                    MONTHS_FULL[mm.month.monthValue - 1],
                    listOf(ReadoutRow(null, "доход ${rubShort(mm.income)}"), ReadoutRow(null, "траты ${rubShort(mm.spent)}"), ReadoutRow(LifeInk.Work, "с начала ${rubShort(run[i])}")),
                )
            },
            yMin = kotlin.math.floor(minOf(0f, run.minOrNull()?.div(100_000_000f) ?: 0f)),
            yMax = kotlin.math.ceil(maxOf(0.5f, run.maxOrNull()?.div(100_000_000f) ?: 0f)),
            yFmt = { f2(it.toDouble()).ifBlank { "0" } },
            xEvery = 1,
            endLabels = true,
            endFmt = { f2(it.toDouble()) + " млн" },
            height = 200.dp,
        )
        if (mo.categories.isNotEmpty()) {
            SubTitle("Куда уходит, в среднем за месяц")
            val groups = LifeStats.MONEY_GROUPS.map { g -> g to months.sumOf { it.groups[g] ?: 0L } }.filter { it.second > 0 }
            val gt = groups.sumOf { it.second }.coerceAtLeast(1)
            ShareBar(groups.map { it.second.toFloat() to (MONEY_INKS[it.first] ?: LifeInk.Other) })
            ValueList(groups.map { Triple(it.first, pct(it.second.toDouble() / gt), MONEY_INKS[it.first]) })
            Spacer(Modifier.height(12.dp))
            val top = mo.categories.filter { it.second > 0 }.take(18)
            val peak = top.maxOfOrNull { it.second }?.toFloat()?.coerceAtLeast(1f) ?: 1f
            HBars(
                top.map { (key, kop) ->
                    val g = MoneyCategories.of(key)?.group
                    HBar(MoneyCategories.title(key).replaceFirstChar { it.uppercase() }, rubShort(kop), kop / peak, MONEY_INKS[g] ?: LifeInk.Other)
                },
            )
        }
        if (months.any { it.zfRevenue > 0 }) {
            SubTitle("ЗФ: выручка и команда, млн ₽")
            var zs by remember(mo) {
                mutableStateOf(
                    listOf(
                        LifeSeries("Выручка", LifeInk.Family, months.map { it.zfRevenue / 100_000_000f }),
                        LifeSeries("Команда и подрядчики", LifeInk.Loss, months.map { it.zfTeam / 100_000_000f }),
                    ),
                )
            }
            SeriesToggles(zs, { i -> zs = zs.mapIndexed { k, s -> if (k == i) s.copy(hidden = !s.hidden) else s } })
            LinesChart(
                labels = labels,
                series = zs,
                tip = { i ->
                    val mm = months[i]
                    Readout(MONTHS_FULL[mm.month.monthValue - 1], listOf(ReadoutRow(LifeInk.Family, "выручка ${rubShort(mm.zfRevenue)}"), ReadoutRow(LifeInk.Loss, "команда ${rubShort(mm.zfTeam)}")))
                },
                yFmt = { f1(it.toDouble()) },
                xEvery = 1,
                height = 200.dp,
            )
            val rev = months.sumOf { it.zfRevenue }
            val team = months.sumOf { it.zfTeam }
            if (rev > 0) PaperHint("${rubShort(rev)} выручки за " + plural(months.size, "месяц", "месяца", "месяцев") + ", команда и подрядчики — ${pct(team.toDouble() / rev)}.")
        }
    }
}

// ---------------------------------------------------------------------------
// Телефон и «как читать»
// ---------------------------------------------------------------------------

@Composable
private fun PhoneCard(m: LifeModel, labels: Map<String, String>, granted: Boolean, onGrant: () -> Unit) {
    PaperCard(label = "Телефон") {
        if (!granted) {
            PaperHint("Дай Правке доступ к статистике использования — появятся экран по неделям и главные приложения.")
            Spacer(Modifier.height(8.dp))
            Row {
                Spacer(Modifier.weight(1f))
                PaperButton("Дать доступ к статистике", icon = Glyphs.Key, primary = true, onClick = onGrant)
            }
            return@PaperCard
        }
        val p = m.phone
        if (p.days == 0) {
            PaperHint("За этот отрезок телефон ещё ничего не насчитал.")
            return@PaperCard
        }
        Lead(
            "В среднем ${f1(p.screenMin / 60)} ч экрана и ${p.pickups.roundToInt()} подъёмов в день. " +
                p.top.joinToString(", ") { pkg -> "${appLabelOf(labels, pkg)} ${Fmt.dur((p.topMin[pkg] ?: 0.0).roundToInt())}" } + ".",
        )
        val ws = p.weeks.filter { it.days > 0 }
        if (ws.size >= 2) {
            Cap("Минут в день по неделям.")
            var series by remember(p, labels) {
                mutableStateOf(p.top.mapIndexed { i, pkg -> LifeSeries(appLabelOf(labels, pkg), PHONE_INKS[i % PHONE_INKS.size], ws.map { (it.apps[pkg] ?: 0.0).toFloat() }) })
            }
            SeriesToggles(series, { i -> series = series.mapIndexed { k, s -> if (k == i) s.copy(hidden = !s.hidden) else s } })
            LinesChart(
                labels = ws.map { dm(it.monday) },
                series = series,
                tip = { i ->
                    val w = ws[i]
                    Readout(
                        weekRange(w.monday, m.from, m.to),
                        series.filter { !it.hidden }.map { s -> ReadoutRow(s.color, "${s.name} ${s.data[i]?.roundToInt() ?: 0} мин") } +
                            ReadoutRow(null, "экран ${f1(w.screenMin / 60)} ч, подъёмов ${w.pickups.roundToInt()}"),
                    )
                },
                height = 220.dp,
            )
        }
        PaperHint("Телефон с лентой не складывается: это параллельный счёт.")
    }
}

@Composable
private fun HowToReadCard() {
    val t = LocalPravkaType.current
    PaperCard(label = "Как это читать") {
        listOf(
            "Блок ленты тянется до следующей засечки: когда засечек мало, длинные блоки завышены.",
            "В счёте только сутки, где лента закрыла хотя бы 20 часов; время без записи внутри них — «не размечено».",
            "Окно и календарь — только будни. «Выход из дома» — любая поездка или проход (Передвижение), начатые между 10 и 16.",
            "Мастерская — категория «Систематизация»; соло — «Секс: соло»; утечка — потери, отдых, соло и дыры вместе.",
            "Сон в средних сутках — по ленте (время в кровати); в таблице недель — с часов, из intervals.",
            "Встречи — блоки «Работа: звонки» от 10 минут; созвоны внутри другой работы не видны.",
            "План тренировок — календарь intervals, который телефон хранит на 40 дней назад; сделано — в тот же день.",
            "Еда — только подтверждённые приёмы, дни без записей в средние не входят; рецепт — приём, повторившийся в трети записанных дней и чаще.",
            "Деньги — подключённые счета и пуши банков; переводы между своими, наличные и займы не траты. Месяц со звёздочкой идёт.",
        ).forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("·", style = t.body, color = Ink.TextMeta, modifier = Modifier.padding(end = 8.dp))
                Text(line, style = t.body, color = Ink.TextSecondary)
            }
        }
    }
}
