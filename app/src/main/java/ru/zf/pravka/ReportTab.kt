package ru.zf.pravka

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.DayReport
import ru.zf.pravka.core.PhoneDaySummary
import ru.zf.pravka.core.SportCoach
import ru.zf.pravka.data.PhoneSweeper
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.phoneDayKey
import ru.zf.pravka.ui.CenteredBarRow
import ru.zf.pravka.ui.CenteredBars
import ru.zf.pravka.ui.ChartSlice
import ru.zf.pravka.ui.CompareRow
import ru.zf.pravka.ui.DayStripChart
import ru.zf.pravka.ui.DonutChart
import ru.zf.pravka.ui.DotRow
import ru.zf.pravka.ui.HourTicks
import ru.zf.pravka.ui.KpiTile
import ru.zf.pravka.ui.LegendRow
import ru.zf.pravka.ui.LineChart
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.SignedColumns
import ru.zf.pravka.ui.StackedColumn
import ru.zf.pravka.ui.StackedColumns
import ru.zf.pravka.ui.StripSegment

// Вкладка «Отчёт»: день в графиках.
//
// Владелец: «репорт по тому, что происходит сегодня, пай-чарты, столбики,
// что за неделю, сравнение этого дня с тем же днём прошлой недели, коэффициенты,
// оценки дня, сколько в телефоне сидел — и пускай там будут разные-разные
// графики». Засечка — лента и правка минут; здесь та же лента, телефон, тело и
// еда сложены в картину, которую можно окинуть одним взглядом.
//
// Порядок карточек — порядок вопросов вечером:
//   1. Каким был день (балл на радуге, место среди своих дней, тот же день
//      недели за пять недель).
//   2. Сутки в числах — плитки с дельтой к тому же дню неделю назад.
//   3. Из чего сложился день (донат) и что заняло больше всего.
//   4. День по часам — две полосы: сегодня и неделю назад.
//   5. Треугольник ценности: широко сверху, узко внизу — его цель.
//   6. Против того же дня неделю назад — по категориям, телефону, сну, еде.
//   7. Неделя: стопка по дням, семь суток по часам, балл за 28 дней.
//   8. Телефон, тело, еда, дела и Правка — каждый своей карточкой.
//   9. Коэффициенты — с формулой словами под каждым.
//
// Честность важнее красоты: идущий день сравнивается с прошлым до того же
// часа (core/DayReport.Window.shifted), базой служат его собственные дни,
// а порогов вроде «хорошо — это 8 часов сна» вкладка не выдумывает. Все
// числа считает core/DayReport.kt (JVM-тесты), модель к экрану не подходит.

private const val DAY = DayReport.DAY_MS
private val WEEKDAYS = listOf("вс", "пн", "вт", "ср", "чт", "пт", "сб")
private val reportDateFormat = SimpleDateFormat("d MMMM", Locale("ru"))
private val reportShortDate = SimpleDateFormat("d.MM", Locale("ru"))
private val reportIso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val reportClock = SimpleDateFormat("HH:mm", Locale.US)

private val GOOD = Color(0xFF22C55E)
private val BAD = Color(0xFFEF4444)
// Те же чернила, что во вкладке «Еда»: одна еда — один цвет во всём приложении.
private val KCAL_INK = Color(0xFFEA580C)
private val PROTEIN_INK = Color(0xFF0E7490)
private val FAT_INK = Color(0xFFCA8A04)
private val CARBS_INK = Color(0xFF16A34A)
private val SLEEP_INK = Color(0xFF6366F1)
private val STEPS_INK = Color(0xFF0E7490)
private val HRV_INK = Color(0xFF16A34A)
private val RHR_INK = Color(0xFFEA580C)
private val WEIGHT_INK = Color(0xFF0E7490)
private val COST_INK = Color(0xFFCA8A04)
private val PICKUP_INK = Color(0xFF0E7490)
private val GLANCE_INK = Color(0xFFEA580C)
// Ступени треугольника — остановки той же радуги, что у балла.
private val BUCKET_INKS = listOf(
    Color(0xFFEF4444), Color(0xFFF97316), Color(0xFF22C55E), Color(0xFF3B82F6), Color(0xFF8B5CF6),
)

private fun reportDayStart(offsetDays: Int): Long {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, -offsetDays)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun weekdayOf(ms: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    return WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
}

private fun isoOf(ms: Long): String = reportIso.format(Date(ms))
private fun clockOf(ms: Long): String = reportClock.format(Date(ms))
private fun dur(min: Long): String = DayReport.dur(min)
private fun signed(v: Int): String = DayReport.signed(v)
private fun pct(share: Double?): String = DayReport.pct(share)
private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

/** «8ч», «45м» — подпись над колонкой, куда «1 ч 10 м» не влезает. */
private fun hoursShort(min: Long): String = if (min >= 60) "${min / 60}ч" else "${min}м"

/** Доля, если знаменатель есть. */
private fun share(num: Long, den: Long): Double? = if (den <= 0L) null else num.toDouble() / den

/** Всё, что карточкам нужно знать про один день: окно, срезы, балл, фокус, сон. */
private class Frame(
    val dayStart: Long,
    val window: DayReport.Window,
    val slices: List<DayReport.CatSlice>,
    val balance: DayReport.Balance,
    val awakeMs: Long,
    val focus: DayReport.Focus,
    val nightSleepMs: Long,
    val rhythm: DayReport.Rhythm,
) {
    val awakeMin: Long get() = DayReport.msToMin(awakeMs)
    val full: Boolean get() = window.to >= dayStart + DAY
    fun minutes(filter: (String) -> Boolean): Long =
        DayReport.msToMin(slices.filter { filter(it.category) }.sumOf { it.ms })
    fun minutesOf(category: String): Long {
        val key = category.trim().lowercase()
        return DayReport.msToMin(slices.filter { it.category.trim().lowercase() == key }.sumOf { it.ms })
    }
}

private fun frameOf(
    pool: List<ZasechkaStore.Entry>,
    dayStart: Long,
    window: DayReport.Window,
    now: Long,
    worthOf: (String) -> Int,
): Frame = Frame(
    dayStart = dayStart,
    window = window,
    slices = DayReport.byCategory(pool, window, now, worthOf),
    balance = DayReport.balance(pool, window, now, worthOf),
    awakeMs = DayReport.awakeMs(pool, window, now),
    focus = DayReport.focus(pool, window, now),
    nightSleepMs = DayReport.nightSleepMs(pool, dayStart, now),
    rhythm = DayReport.rhythm(pool, dayStart, now),
)

private class DayScore(val dayStart: Long, val net: Int, val full: Boolean)

private class WeekDay(
    val dayStart: Long,
    val slices: List<DayReport.CatSlice>,
    val strip: List<DayReport.Segment>,
)

/**
 * Цвет дельты: лучше — зелёный, хуже — красный, ноль или «не про лучше» —
 * [neutral]. Обычная функция, не composable: её зовут и из локальных
 * помощников, собирающих плитки.
 */
private fun deltaColor(diff: Long, higherIsBetter: Boolean?, neutral: Color): Color = when {
    diff == 0L || higherIsBetter == null -> neutral
    (diff > 0L) == higherIsBetter -> GOOD
    else -> BAD
}

/** «8 123» — тысячи тонким пробелом, без локали и её сюрпризов. */
private fun thousands(n: Int): String =
    n.toString().reversed().chunked(3).joinToString("\u2009").reversed()

/** Цвета видов спорта — те же оттенки, что у строк тренировок во вкладке «Тело». */
@Composable
private fun sportInk(type: String): Color {
    val dark = isSystemInDarkTheme()
    val hue = when (type) {
        "Run", "TrailRun", "VirtualRun" -> 20f
        "Ride", "VirtualRide", "GravelRide", "MountainBikeRide" -> 200f
        "WeightTraining", "Workout", "Crossfit", "HIIT" -> 350f
        "Walk", "Hike" -> 100f
        "Swim" -> 185f
        else -> 265f
    }
    return if (dark) Color.hsv(hue, 0.55f, 0.92f) else Color.hsv(hue, 0.66f, 0.68f)
}

@Composable
internal fun ReportTab(app: PravkaApp) {
    val context = LocalContext.current
    val entries by app.zasechkaStore.entriesFlow.collectAsState()
    val categories by app.zasechkaStore.categoriesFlow.collectAsState()
    val phoneDays by app.phoneStore.daysFlow.collectAsState()
    val immersive by app.phoneStore.immersiveFlow.collectAsState()
    val offApps by app.phoneStore.offFlow.collectAsState()
    val audioApps by app.phoneStore.audioFlow.collectAsState()
    val labels by app.phoneStore.labelsFlow.collectAsState()
    val meals by app.foodStore.mealsFlow.collectAsState()
    val health by app.sportStore.healthFlow.collectAsState()
    val workouts by app.sportStore.workoutsFlow.collectAsState()
    val gtg by app.strengthStore.gtgFlow.collectAsState()
    val sessions by app.strengthStore.sessionsFlow.collectAsState()
    val tasks by app.todoistStore.tasksFlow.collectAsState()
    val kcalTarget by app.settings.foodKcalFlow.collectAsState(initial = 0)
    val proteinTarget by app.settings.foodProteinFlow.collectAsState(initial = 0)
    val goalWeight by app.settings.goalWeightFlow.collectAsState(initial = 0)

    var costs by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var dictations by remember { mutableStateOf<Map<String, Pair<Int, Long>>>(emptyMap()) }
    var usageGranted by remember { mutableStateOf(PhoneSweeper.hasUsageAccess(context)) }

    // Сторы поднимаются лениво: первый читатель и грузит. Ошибка любого из них
    // не должна валить экран — отчёт покажет то, что есть.
    LaunchedEffect(Unit) {
        runCatching { app.zasechkaStore.all() }
        runCatching { app.foodStore.load() }
        runCatching { app.sportStore.load() }
        runCatching { app.strengthStore.load() }
        runCatching { app.todoistStore.load() }
        runCatching { app.phoneStore.trackedApps() }
        usageGranted = PhoneSweeper.hasUsageAccess(context)
        if (usageGranted) runCatching { app.phoneSweeper.sweep() }
        costs = runCatching { app.stats.dailyCosts(28).toMap() }.getOrDefault(emptyMap())
        dictations = withContext(Dispatchers.IO) {
            runCatching {
                app.transcriptionLog.readLast(3000)
                    .groupBy { it.ts.take(10) }
                    .mapValues { (_, list) -> list.size to list.sumOf { it.audioMs } }
            }.getOrDefault(emptyMap())
        }
    }

    var dayOffset by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val dayStart = remember(dayOffset) { reportDayStart(dayOffset) }
    val isToday = dayOffset == 0
    val dateKey = isoOf(dayStart)
    val refStart = dayStart - 7 * DAY
    val refKey = isoOf(refStart)

    val worthByCat = remember(categories) { categories.associate { it.name.trim().lowercase() to it.value } }
    val worthOf: (String) -> Int = { worthByCat[it.trim().lowercase()] ?: 0 }

    // Записи за пять недель до дня включительно — всё, что может задеть его
    // самого, его базу и 28 дней истории. Дальше все счёты идут по этому пулу.
    val pool = remember(entries, dayStart) {
        val from = dayStart - 35 * DAY
        val to = dayStart + DAY
        entries.filter { it.start < to && (it.open || it.end > from) }
    }
    val window = DayReport.dayWindow(dayStart, now)
    val frame = remember(pool, window, worthByCat) { frameOf(pool, dayStart, window, now, worthOf) }
    // Тот же день недели неделю назад, обрезанный до того же часа.
    val refFrame = remember(pool, window, worthByCat) {
        frameOf(pool, refStart, window.shifted(7), now, worthOf)
    }
    val yesterdayNet = remember(pool, window, worthByCat) {
        DayReport.balance(pool, window.shifted(1), now, worthOf).net
    }
    // Балл каждого из 28 дней до дня включительно — полные сутки, как в
    // Засечке; сегодняшний — сколько его прошло.
    val history = remember(pool, window, worthByCat) {
        (27 downTo 0).map { k ->
            val ds = dayStart - k * DAY
            val w = DayReport.dayWindow(ds, now)
            DayScore(ds, DayReport.balance(pool, w, now, worthOf).net, w.to >= ds + DAY)
        }
    }
    // Те же 27 дней, обрезанные до этого же часа, — для места и медианы.
    val sameHour = remember(pool, window, worthByCat) {
        (1..27).map { k -> DayReport.balance(pool, window.shifted(k), now, worthOf).net }
    }
    val weekdayRefs = remember(pool, window, worthByCat) {
        (1..4).map { k -> DayReport.balance(pool, window.shifted(7 * k), now, worthOf).net }
    }
    val weekDays = remember(pool, window, worthByCat) {
        (6 downTo 0).map { k ->
            val ds = dayStart - k * DAY
            WeekDay(ds, DayReport.byCategory(pool, DayReport.dayWindow(ds, now), now, worthOf), DayReport.strip(pool, ds, now))
        }
    }

    val tracked = remember(immersive, offApps) { immersive.filterKeys { it !in offApps } }
    val phone = PhoneDaySummary.of(phoneDays[phoneDayKey(dayStart)], tracked, labels)
    val refPhone = PhoneDaySummary.of(phoneDays[phoneDayKey(refStart)], tracked, labels)
    val healthByDate = remember(health) { health.associateBy { it.date } }
    val todayHealth = healthByDate[dateKey]
    val refHealth = healthByDate[refKey]

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- шапка и день ----
        item {
            ScreenTitle("Отчёт")
            HintText(
                "День в графиках: лента, балл, телефон, тело, еда — и рядом тот же день " +
                    "недели неделю назад, обрезанный до этого же часа. Числа считает телефон, " +
                    "модель сюда не ходит.",
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { dayOffset += 1 }) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "раньше")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val title = when (dayOffset) {
                        0 -> "сегодня"
                        1 -> "вчера"
                        else -> weekdayOf(dayStart) + ", " + reportDateFormat.format(Date(dayStart))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    PaperHint(
                        weekdayOf(dayStart) + ", " + reportDateFormat.format(Date(dayStart)) +
                            (if (isToday) " · до ${clockOf(now)}" else " · целиком"),
                    )
                }
                IconButton(
                    onClick = { dayOffset = (dayOffset - 1).coerceAtLeast(0) },
                    enabled = dayOffset > 0,
                ) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "позже")
                }
            }
        }

        // ---- 1. оценка дня ----
        item {
            ScoreCard(frame, refFrame, yesterdayNet, sameHour, weekdayRefs, isToday)
        }

        // ---- 2. сутки в числах ----
        item {
            KpiCard(frame, refFrame, phone, refPhone, todayHealth, refHealth, isToday)
        }

        // ---- 3. из чего сложился день ----
        item {
            CompositionCard(frame, pool, now)
        }

        // ---- 4. день по часам ----
        item {
            StripsCard(
                today = DayReport.strip(pool, dayStart, now),
                ref = DayReport.strip(pool, refStart, now),
                refLabel = weekdayOf(refStart) + ", " + reportShortDate.format(Date(refStart)),
                nowFrac = if (isToday) ((now - dayStart).toFloat() / DAY).coerceIn(0f, 1f) else null,
            )
        }

        // ---- 5. треугольник ----
        item {
            TriangleCard(frame, refFrame)
        }

        // ---- 6. против того же дня неделю назад ----
        item {
            CompareCard(
                frame, refFrame, phone, refPhone, todayHealth, refHealth,
                food = app.foodStore.dayTotal(dateKey), refFood = app.foodStore.dayTotal(refKey),
                worthOf = worthOf, isToday = isToday, now = now,
            )
        }

        // ---- 7. неделя ----
        item {
            WeekCard(weekDays, history, dayStart, isToday, now)
        }
        item {
            HistoryCard(history)
        }

        // ---- 8. телефон ----
        item {
            PhoneCard(
                context = context,
                usageGranted = usageGranted,
                onGranted = { usageGranted = it },
                days = (6 downTo 0).map { k -> dayStart - k * DAY },
                phoneDays = phoneDays,
                tracked = tracked,
                audioApps = audioApps,
                labels = labels,
                phone = phone,
                refPhone = refPhone,
                awakeMin = frame.awakeMin,
                isToday = isToday,
            )
        }

        // ---- тело ----
        item {
            BodyCard(
                app = app,
                dayStart = dayStart,
                dateKey = dateKey,
                pool = pool,
                now = now,
                healthByDate = healthByDate,
                workouts = workouts,
                gtgDays = gtg,
                sessions = sessions,
                goalWeight = goalWeight,
                isToday = isToday,
            )
        }

        // ---- еда ----
        item {
            FoodCard(app, dayStart, kcalTarget, proteinTarget, isToday, meals.size)
        }

        // ---- дела и Правка ----
        item {
            TasksAppCard(
                context = context,
                pool = pool,
                window = window,
                dayStart = dayStart,
                isToday = isToday,
                now = now,
                tasks = tasks,
                costs = costs,
                dictations = dictations,
            )
        }

        // ---- 9. коэффициенты ----
        item {
            RatiosCard(frame, refFrame, phone, refPhone, isToday)
        }
    }
}

// ---------------------------------------------------------------------------
// 1. Оценка дня
// ---------------------------------------------------------------------------

@Composable
private fun ScoreCard(
    frame: Frame,
    refFrame: Frame,
    yesterdayNet: Int,
    sameHour: List<Int>,
    weekdayRefs: List<Int>,
    isToday: Boolean,
) {
    val b = frame.balance
    PaperCard(label = "Каким был день") {
        RainbowScoreBar(b.net, weekMode = false)
        Spacer(Modifier.height(8.dp))
        Text(
            "Баланс ${signed(b.net)} = +${b.plus.roundToInt()} и ${b.minus.roundToInt()}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        val median4 = DayReport.median(weekdayRefs.map { it.toDouble() })
        val rank = DayReport.rank(b.net.toDouble(), sameHour.map { it.toDouble() })
        val hour = if (isToday && !frame.full) " к этому часу" else ""
        Text(
            "Тот же день недели неделю назад$hour: ${signed(refFrame.balance.net)} · вчера$hour: ${signed(yesterdayNet)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Медиана этого дня недели за четыре недели: " +
                (median4?.let { signed(it.roundToInt()) } ?: "—") +
                " · место среди 28 дней$hour: $rank из 28",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        PaperHint("Этот день недели, пять недель подряд$hour")
        val values = (weekdayRefs.reversed() + b.net).map { it.toFloat() }
        SignedColumns(
            values = values,
            colorOf = { scoreColor(it, 100f) },
            labels = listOf("−4 нед", "−3 нед", "−2 нед", "−1 нед", "этот"),
            height = 84.dp,
            hollow = if (frame.full) emptySet() else setOf(4),
            valueText = { signed(it.roundToInt()) },
        )
        PaperHint(
            "Балл — часы × ценность часа категории, как в Засечке. Сотня — сильный день: " +
                "восемь часов работы по +8 и час спорта. Место считается среди 27 предыдущих " +
                "дней, обрезанных как этот.",
        )
    }
}

// ---------------------------------------------------------------------------
// 2. Сутки в числах
// ---------------------------------------------------------------------------

private class Kpi(
    val label: String,
    val value: String,
    val delta: String?,
    val deltaColor: Color,
    val hint: String?,
)

@Composable
private fun KpiCard(
    frame: Frame,
    refFrame: Frame,
    phone: PhoneDaySummary.Summary,
    refPhone: PhoneDaySummary.Summary,
    health: SportStore.Health?,
    refHealth: SportStore.Health?,
    isToday: Boolean,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val partial = isToday && !frame.full
    val refNote = if (partial) "неделю назад к этому часу" else "неделю назад"

    fun minutesKpi(label: String, cur: Long, ref: Long, higherIsBetter: Boolean?, hint: String? = null): Kpi =
        Kpi(label, dur(cur), DayReport.delta(cur, ref, "м") + " · $refNote", deltaColor(cur - ref, higherIsBetter, neutral), hint)

    val work = frame.minutes(DayReport::isWork)
    val refWork = refFrame.minutes(DayReport::isWork)
    val sport = frame.minutes(DayReport::isSport)
    val refSport = refFrame.minutes(DayReport::isSport)
    val family = frame.minutesOf("Семья")
    val refFamily = refFrame.minutesOf("Семья")
    val holes = frame.minutes(DayReport::isHole)
    val refHoles = refFrame.minutes(DayReport::isHole)

    // Сон: ночь из ленты; пока лента про сон молчит — часы (Garmin через intervals).
    val sleepMin = DayReport.msToMin(frame.nightSleepMs).takeIf { it > 0 }
        ?: health?.sleepHours?.takeIf { it > 0 }?.let { (it * 60).roundToLong() } ?: 0L
    val refSleepMin = DayReport.msToMin(refFrame.nightSleepMs).takeIf { it > 0 }
        ?: refHealth?.sleepHours?.takeIf { it > 0 }?.let { (it * 60).roundToLong() } ?: 0L
    val sleepSource = when {
        frame.nightSleepMs > 0 -> "ночь по ленте"
        sleepMin > 0 -> "по часам"
        else -> "сна в ленте нет"
    }
    val steps = health?.steps ?: 0
    val refSteps = refHealth?.steps ?: 0
    val phoneNote = if (partial) "неделю назад — за весь день" else refNote

    val tiles = listOf(
        Kpi(
            "Балл", signed(frame.balance.net),
            DayReport.delta(frame.balance.net.toLong(), refFrame.balance.net.toLong()) + " · $refNote",
            deltaColor((frame.balance.net - refFrame.balance.net).toLong(), true, neutral),
            "бодрствование ${dur(frame.awakeMin)}",
        ),
        minutesKpi("Работа", work, refWork, true, "глубоких блоков ${frame.focus.deepBlocks} · ${dur(frame.focus.deepMin)}"),
        minutesKpi("Спорт", sport, refSport, true),
        minutesKpi("Семья", family, refFamily, true),
        minutesKpi("Потери и дыры", holes, refHoles, false, "потери и не размечено"),
        Kpi(
            "Сон", if (sleepMin > 0) dur(sleepMin) else "—",
            if (sleepMin > 0 && refSleepMin > 0) DayReport.delta(sleepMin, refSleepMin, "м") + " · неделю назад" else null,
            deltaColor(sleepMin - refSleepMin, true, neutral), sleepSource,
        ),
        Kpi(
            "Экран", if (phone.screenMin > 0) dur(phone.screenMin) else "—",
            if (phone.screenMin > 0 || refPhone.screenMin > 0) DayReport.delta(phone.screenMin, refPhone.screenMin, "м") + " · $phoneNote" else null,
            deltaColor(phone.screenMin - refPhone.screenMin, false, neutral),
            "подъёмов ${phone.pickups}" + (if (phone.calls > 0) " · звонков ${phone.calls}" else ""),
        ),
        Kpi(
            "Отвлечения", if (phone.screenMin > 0) "${phone.glances}" else "—",
            if (phone.screenMin > 0 || refPhone.screenMin > 0) DayReport.delta(phone.glances.toLong(), refPhone.glances.toLong()) + " · $phoneNote" else null,
            deltaColor((phone.glances - refPhone.glances).toLong(), false, neutral),
            "взял и убрал быстрее двух минут",
        ),
        Kpi(
            "Шаги", if (steps > 0) thousands(steps) else "—",
            if (steps > 0 && refSteps > 0) DayReport.delta(steps.toLong(), refSteps.toLong()) + " · неделю назад" else null,
            deltaColor((steps - refSteps).toLong(), true, neutral),
            if (health != null && health.restingHr > 0) "пульс покоя ${health.restingHr}" + (if (health.hrv > 0) " · HRV ${health.hrv}" else "") else "по часам",
        ),
        Kpi(
            "Подъём",
            frame.rhythm.wakeMs?.let { clockOf(it) } ?: "—",
            frame.rhythm.bedMs?.let { "отбой " + clockOf(it) } ?: (if (isToday) "отбоя ещё нет" else "отбой не виден"),
            neutral,
            refFrame.rhythm.wakeMs?.let { "неделю назад подъём " + clockOf(it) },
        ),
    )
    PaperCard(label = "Сутки в числах") {
        for (pair in tiles.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in pair) {
                    KpiTile(
                        label = t.label, value = t.value, modifier = Modifier.weight(1f),
                        delta = t.delta, deltaColor = t.deltaColor, hint = t.hint,
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        PaperHint(
            "Зелёная дельта — лучше, чем неделю назад, красная — хуже; телефон считается " +
                "по дням, поэтому у экрана и отвлечений сравнение с целым днём.",
        )
    }
}

// ---------------------------------------------------------------------------
// 3. Из чего сложился день
// ---------------------------------------------------------------------------

@Composable
private fun CompositionCard(frame: Frame, pool: List<ZasechkaStore.Entry>, now: Long) {
    val ordered = frame.slices.sortedBy { categoryHue(it.category) }
    val elapsed = frame.window.elapsedMs
    PaperCard(label = "Из чего сложился день") {
        if (ordered.isEmpty()) {
            PaperHint("В ленте за этот день пусто.")
            return@PaperCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                slices = ordered.map {
                    ChartSlice(it.category.ifBlank { "без категории" }, it.ms.toFloat(), categoryColor(it.category))
                },
                size = 150.dp,
                thickness = 22.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dur(DayReport.msToMin(elapsed)), style = MaterialTheme.typography.titleMedium)
                    PaperHint("прошло")
                    Text(
                        signed(frame.balance.net),
                        style = MaterialTheme.typography.titleSmall,
                        color = scoreColor(frame.balance.net.toFloat(), 100f),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val shown = ordered.take(9)
                for (s in shown) {
                    LegendRow(
                        color = categoryColor(s.category),
                        label = s.category.ifBlank { "без категории" },
                        value = dur(s.minutes),
                        sub = pct(share(s.ms, elapsed)),
                    )
                }
                if (ordered.size > shown.size) {
                    val rest = ordered.drop(shown.size).sumOf { it.ms }
                    LegendRow(
                        color = MaterialTheme.colorScheme.outline,
                        label = "ещё ${ordered.size - shown.size}",
                        value = dur(DayReport.msToMin(rest)),
                        dim = true,
                    )
                }
            }
        }
        val top = DayReport.topTitles(pool, frame.window, now, 5)
        if (top.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Что заняло больше всего", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val peak = top.first().ms.coerceAtLeast(1L)
            for (t in top) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(4.dp).height(18.dp)
                            .background(categoryColor(t.category), RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(
                            Modifier.fillMaxWidth(0.98f).height(4.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
                        ) {
                            Box(
                                Modifier.fillMaxWidth((t.ms.toFloat() / peak).coerceIn(0.02f, 1f)).height(4.dp)
                                    .background(categoryColor(t.category), RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                    Text(
                        dur(t.minutes),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. День по часам
// ---------------------------------------------------------------------------

@Composable
private fun StripsCard(
    today: List<DayReport.Segment>,
    ref: List<DayReport.Segment>,
    refLabel: String,
    nowFrac: Float?,
) {
    PaperCard(label = "День по часам") {
        PaperHint("этот день")
        DayStripChart(today.map { StripSegment(it.startFrac, it.endFrac, categoryColor(it.category)) }, height = 18.dp, nowFrac = nowFrac)
        Spacer(Modifier.height(8.dp))
        PaperHint("неделю назад · $refLabel")
        DayStripChart(ref.map { StripSegment(it.startFrac, it.endFrac, categoryColor(it.category)) }, height = 18.dp)
        Spacer(Modifier.height(2.dp))
        HourTicks()
        Spacer(Modifier.height(6.dp))
        PaperHint(
            "Сутки слева направо, цвета — радуга категорий Засечки; риска — сейчас. " +
                "Пустое — не размечено или ещё не наступило. Так видно не «сколько», а «когда».",
        )
    }
}

// ---------------------------------------------------------------------------
// 5. Треугольник ценности
// ---------------------------------------------------------------------------

@Composable
private fun TriangleCard(frame: Frame, refFrame: Frame) {
    val buckets = DayReport.buckets(frame.slices)
    val refBuckets = DayReport.buckets(refFrame.slices)
    val t = DayReport.triangle(frame.slices)
    val rt = DayReport.triangle(refFrame.slices)
    PaperCard(label = "Треугольник ценности") {
        CenteredBars(
            rows = buckets.mapIndexed { i, b ->
                CenteredBarRow(
                    label = b.label,
                    sub = b.categories.take(2).joinToString(", ") { it.lowercase() },
                    value = b.ms.toFloat(),
                    ref = refBuckets[i].ms.toFloat(),
                    color = BUCKET_INKS[i],
                    valueText = dur(b.minutes),
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Верх (от +6) ${dur(DayReport.msToMin(t.topMs))} · низ (до −2) ${dur(DayReport.msToMin(t.bottomMs))} · " +
                "индекс ${pct(t.index)}, неделю назад ${pct(rt.index)}",
            style = MaterialTheme.typography.bodySmall,
        )
        PaperHint(
            "Цель — перевёрнутый треугольник: широко сверху, узко внизу. Бодрствование " +
                "разложено по ценности часа категории, сон не в счёт. Тонкая тёмная черта " +
                "под полосой — тот же день неделю назад к этому же часу. Индекс — доля верха " +
                "в сумме верха и низа.",
        )
    }
}

// ---------------------------------------------------------------------------
// 6. Против того же дня неделю назад
// ---------------------------------------------------------------------------

@Composable
private fun CompareCard(
    frame: Frame,
    refFrame: Frame,
    phone: PhoneDaySummary.Summary,
    refPhone: PhoneDaySummary.Summary,
    health: SportStore.Health?,
    refHealth: SportStore.Health?,
    food: ru.zf.pravka.data.FoodStore.DayTotal,
    refFood: ru.zf.pravka.data.FoodStore.DayTotal,
    worthOf: (String) -> Int,
    isToday: Boolean,
    now: Long,
) {
    val partial = isToday && !frame.full
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    PaperCard(label = "Против того же дня неделю назад") {
        PaperHint(
            if (partial) "Оба дня — до ${clockOf(now)}: половина дня против целого проигрывала бы всегда."
            else "Оба дня целиком.",
        )
        Spacer(Modifier.height(6.dp))
        val cats = (frame.slices + refFrame.slices)
            .map { it.category }
            .distinctBy { it.trim().lowercase() }
            .sortedBy { categoryHue(it) }
        var shownAny = false
        for (c in cats) {
            val cur = frame.minutesOf(c)
            val ref = refFrame.minutesOf(c)
            if (maxOf(cur, ref) < 5L) continue
            shownAny = true
            val worth = worthOf(c)
            CompareRow(
                label = c.ifBlank { "без категории" },
                cur = cur.toFloat(), ref = ref.toFloat(), max = maxOf(cur, ref).toFloat(),
                color = categoryColor(c),
                curText = dur(cur), refText = dur(ref),
                deltaText = DayReport.delta(cur, ref),
                deltaColor = deltaColor(cur - ref, if (worth > 0) true else if (worth < 0) false else null, neutral),
            )
        }
        if (!shownAny) PaperHint("По ленте сравнивать пока нечего.")
        Spacer(Modifier.height(8.dp))
        val refPhoneText = if (partial) " (весь день)" else ""
        if (phone.screenMin > 0 || refPhone.screenMin > 0) {
            CompareRow(
                "Экран$refPhoneText", phone.screenMin.toFloat(), refPhone.screenMin.toFloat(),
                maxOf(phone.screenMin, refPhone.screenMin).toFloat(), MaterialTheme.colorScheme.tertiary,
                dur(phone.screenMin), dur(refPhone.screenMin), DayReport.delta(phone.screenMin, refPhone.screenMin),
                deltaColor(phone.screenMin - refPhone.screenMin, false, neutral),
            )
        }
        val sleep = DayReport.msToMin(frame.nightSleepMs).takeIf { it > 0 }
            ?: health?.sleepHours?.takeIf { it > 0 }?.let { (it * 60).roundToLong() } ?: 0L
        val refSleep = DayReport.msToMin(refFrame.nightSleepMs).takeIf { it > 0 }
            ?: refHealth?.sleepHours?.takeIf { it > 0 }?.let { (it * 60).roundToLong() } ?: 0L
        if (sleep > 0 || refSleep > 0) {
            CompareRow(
                "Сон, ночь", sleep.toFloat(), refSleep.toFloat(), maxOf(sleep, refSleep).toFloat(), SLEEP_INK,
                dur(sleep), dur(refSleep), DayReport.delta(sleep, refSleep), deltaColor(sleep - refSleep, true, neutral),
            )
        }
        val steps = (health?.steps ?: 0).toLong()
        val refSteps = (refHealth?.steps ?: 0).toLong()
        if (steps > 0 || refSteps > 0) {
            CompareRow(
                "Шаги$refPhoneText", steps.toFloat(), refSteps.toFloat(), maxOf(steps, refSteps).toFloat(), STEPS_INK,
                thousands(steps.toInt()), thousands(refSteps.toInt()), DayReport.delta(steps, refSteps), deltaColor(steps - refSteps, true, neutral),
            )
        }
        if (food.kcal > 0 || refFood.kcal > 0) {
            CompareRow(
                "Ккал$refPhoneText", food.kcal.toFloat(), refFood.kcal.toFloat(), maxOf(food.kcal, refFood.kcal).toFloat(), KCAL_INK,
                "${food.kcal}", "${refFood.kcal}", DayReport.delta(food.kcal.toLong(), refFood.kcal.toLong()),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompareRow(
                "Белок$refPhoneText", food.protein.toFloat(), refFood.protein.toFloat(),
                maxOf(food.protein, refFood.protein).toFloat(), PROTEIN_INK,
                "${food.protein} г", "${refFood.protein} г", DayReport.delta(food.protein.toLong(), refFood.protein.toLong()),
                deltaColor((food.protein - refFood.protein).toLong(), true, neutral),
            )
        }
        Spacer(Modifier.height(4.dp))
        PaperHint("Толстая полоска — этот день, тонкая серая — неделю назад; в скобках — те числа, что считаются только целыми сутками.")
    }
}

// ---------------------------------------------------------------------------
// 7. Неделя
// ---------------------------------------------------------------------------

@Composable
private fun WeekCard(
    weekDays: List<WeekDay>,
    history: List<DayScore>,
    dayStart: Long,
    isToday: Boolean,
    now: Long,
) {
    PaperCard(label = "Неделя") {
        // Стопка бодрствования по дням: потери внизу, работа сверху — тот же
        // порядок, что в радуге, и та же цель (перевёрнутый треугольник).
        val columns = weekDays.map { d ->
            val awake = d.slices.filter { !DayReport.isSleep(it.category) }
                .sortedByDescending { categoryHue(it.category) }
            StackedColumn(
                label = weekdayOf(d.dayStart),
                parts = awake.map { ChartSlice(it.category, it.minutes.toFloat(), categoryColor(it.category)) },
                faded = d.dayStart == dayStart && isToday && now < dayStart + DAY,
            )
        }
        StackedColumns(columns, height = 150.dp, valueText = { hoursShort(it.roundToLong()) }, highlight = 6)
        Spacer(Modifier.height(4.dp))
        PaperHint("Бодрствование по дням, стопкой по категориям: работа сверху, потери внизу. Сон — в карточке «Тело».")
        Spacer(Modifier.height(8.dp))
        val weekTotals = weekDays.flatMap { it.slices }
            .filter { !DayReport.isSleep(it.category) }
            .groupBy { it.category.trim().lowercase() }
            .map { (_, list) -> list.first().category to list.sumOf { it.ms } }
            .sortedByDescending { it.second }
        val weekAwakeMs = weekTotals.sumOf { it.second }
        for ((name, ms) in weekTotals.take(8)) {
            LegendRow(
                color = categoryColor(name),
                label = name.ifBlank { "без категории" },
                value = dur(DayReport.msToMin(ms)),
                sub = pct(share(ms, weekAwakeMs)),
            )
        }
        val weekNet = history.takeLast(7).sumOf { it.net }
        val prevWeekNet = history.dropLast(7).takeLast(7).sumOf { it.net }
        Spacer(Modifier.height(6.dp))
        Text(
            "Балл семи дней ${signed(weekNet)} · предыдущих семи ${signed(prevWeekNet)}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text("Семь суток по часам", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        for (d in weekDays) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val today = d.dayStart == dayStart
                Text(
                    weekdayOf(d.dayStart),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
                DayStripChart(
                    d.strip.map { StripSegment(it.startFrac, it.endFrac, categoryColor(it.category)) },
                    height = 12.dp,
                    nowFrac = if (today && isToday) ((now - dayStart).toFloat() / DAY).coerceIn(0f, 1f) else null,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(28.dp))
            Box(Modifier.weight(1f)) { HourTicks() }
        }
        Spacer(Modifier.height(4.dp))
        PaperHint("Семь суток друг под другом: в какие часы работа, в какие потери, и где ночь съезжает.")
    }
}

@Composable
private fun HistoryCard(history: List<DayScore>) {
    PaperCard(label = "Балл по дням, четыре недели") {
        SignedColumns(
            values = history.map { it.net.toFloat() },
            colorOf = { scoreColor(it, 100f) },
            labels = history.map { if (weekdayOf(it.dayStart) == "пн") "пн" else "" },
            height = 128.dp,
            hollow = history.indices.filter { !history[it].full }.toSet(),
        )
        Spacer(Modifier.height(6.dp))
        val full = history.filter { it.full }
        val last7 = full.takeLast(7)
        val avg7 = if (last7.isEmpty()) null else last7.sumOf { it.net }.toDouble() / last7.size
        val avg28 = if (full.isEmpty()) null else full.sumOf { it.net }.toDouble() / full.size
        val best = full.maxByOrNull { it.net }
        val worst = full.minByOrNull { it.net }
        Text(
            "Среднее за семь полных дней " + (avg7?.let { signed(it.roundToInt()) } ?: "—") +
                " · за 28 " + (avg28?.let { signed(it.roundToInt()) } ?: "—"),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (best != null && worst != null) {
            Text(
                "Лучший — ${weekdayOf(best.dayStart)} ${reportShortDate.format(Date(best.dayStart))} ${signed(best.net)}, " +
                    "худший — ${weekdayOf(worst.dayStart)} ${reportShortDate.format(Date(worst.dayStart))} ${signed(worst.net)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PaperHint("Полные сутки, как в Засечке; контуром — день, который ещё идёт. Подписи стоят под понедельниками.")
    }
}

// ---------------------------------------------------------------------------
// 8. Телефон
// ---------------------------------------------------------------------------

@Composable
private fun PhoneCard(
    context: Context,
    usageGranted: Boolean,
    onGranted: (Boolean) -> Unit,
    days: List<Long>,
    phoneDays: Map<String, ru.zf.pravka.data.PhoneStore.Day>,
    tracked: Map<String, String>,
    audioApps: Set<String>,
    labels: Map<String, String>,
    phone: PhoneDaySummary.Summary,
    refPhone: PhoneDaySummary.Summary,
    awakeMin: Long,
    isToday: Boolean,
) {
    PaperCard(label = "Телефон") {
        if (!usageGranted) {
            PaperHint(
                "Дай Правке доступ к статистике использования — появятся экран по дням, " +
                    "приложения, подъёмы, отвлечения и звонки.",
            )
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                onGranted(PhoneSweeper.hasUsageAccess(context))
            }) { Text("Дать доступ к статистике") }
            return@PaperCard
        }
        val summaries = days.map { PhoneDaySummary.of(phoneDays[phoneDayKey(it)], tracked, labels) }
        // Отмеченные приложения — своим цветом категории («Потери» у YouTube —
        // фиолетовый, «Систематизация» у Claude — жёлтый), остальное серое.
        val appLabels = summaries.flatMap { it.apps }.map { it.label }.distinct()
        val appCategory = HashMap<String, String>()
        for (s in summaries) for (a in s.apps) appCategory.putIfAbsent(a.label, a.category)
        val other = MaterialTheme.colorScheme.outline
        val audioLabels = audioApps.map { PhoneDaySummary.normal(labels[it] ?: it.substringAfterLast('.')) }.toSet()
        val columns = summaries.mapIndexed { i, s ->
            val parts = ArrayList<ChartSlice>()
            var accounted = 0L
            for (a in s.apps) {
                parts.add(ChartSlice(a.label, a.minutes.toFloat(), categoryColor(a.category)))
                // Слушалка играет с погашенным экраном — её минуты не часть экрана.
                if (PhoneDaySummary.normal(a.label) !in audioLabels) accounted += a.minutes
            }
            val rest = (s.screenMin - accounted).coerceAtLeast(0L)
            if (rest > 0) parts.add(ChartSlice("прочее", rest.toFloat(), other))
            StackedColumn(weekdayOf(days[i]), parts, faded = isToday && i == days.lastIndex)
        }
        StackedColumns(columns, height = 130.dp, valueText = { hoursShort(it.roundToLong()) }, highlight = 6)
        Spacer(Modifier.height(4.dp))
        PaperHint("Минуты по дням: отмеченные приложения цветом своей категории, остальной экран серым.")
        Spacer(Modifier.height(8.dp))
        for (label in appLabels) {
            val today = phone.apps.firstOrNull { it.label == label }?.minutes ?: 0L
            val week = summaries.sumOf { s -> s.apps.firstOrNull { it.label == label }?.minutes ?: 0L }
            LegendRow(
                color = categoryColor(appCategory[label].orEmpty()),
                label = label,
                value = dur(today),
                sub = "за неделю ${dur(week)}",
            )
        }
        if (phone.screenMin > 0) {
            LegendRow(color = other, label = "экран всего", value = dur(phone.screenMin), sub = "за неделю ${dur(summaries.sumOf { it.screenMin })}")
        }
        Spacer(Modifier.height(10.dp))
        // Донат дня по всем приложениям, не только отмеченным.
        val day = phoneDays[phoneDayKey(days.last())]
        if (day != null && day.screenMs > 0) {
            val top = day.apps.entries
                .filter { !isFurniturePkg(it.key) }
                .sortedByDescending { it.value }
            val head = top.take(6)
            val restMs = top.drop(6).sumOf { it.value }
            val greys = listOf(0.9f, 0.72f, 0.58f, 0.46f, 0.36f, 0.28f)
            val slices = head.mapIndexed { i, (pkg, ms) ->
                val cat = tracked[pkg]
                ChartSlice(
                    appLabelOf(labels, pkg),
                    ms.toFloat(),
                    if (cat != null) categoryColor(cat) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = greys[i]),
                )
            } + (if (restMs > 0) listOf(ChartSlice("прочее", restMs.toFloat(), other.copy(alpha = 0.5f))) else emptyList())
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(slices, size = 120.dp, thickness = 18.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dur(phone.screenMin), style = MaterialTheme.typography.titleSmall)
                        PaperHint("экран")
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    for (s in slices) LegendRow(s.color, s.label, dur(DayReport.msToMin(s.value.toLong())))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        // Подъёмы и отвлечения по дням — две ломаные.
        val pickups = summaries.map { if (it.screenMin > 0) it.pickups.toFloat() else null }
        val glances = summaries.map { if (it.screenMin > 0) it.glances.toFloat() else null }
        if (pickups.any { it != null }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Подъёмы", style = MaterialTheme.typography.labelMedium, color = PICKUP_INK)
                Text("Отвлечения", style = MaterialTheme.typography.labelMedium, color = GLANCE_INK)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    LineChart(pickups, PICKUP_INK, height = 64.dp, fromZero = true, valueText = { "${it.roundToInt()}" })
                }
                Box(Modifier.weight(1f)) {
                    LineChart(glances, GLANCE_INK, height = 64.dp, fromZero = true, valueText = { "${it.roundToInt()}" })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(weekdayOf(days.first()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(weekdayOf(days.last()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        val callsLine = if (phone.calls > 0) {
            "Звонки: ${phone.calls} · ${dur(phone.callsMin)}" +
                (if (phone.callers.isNotEmpty()) " · " + phone.callers.take(4).joinToString(", ") else "")
        } else "Звонков не было"
        Text(callsLine, style = MaterialTheme.typography.bodySmall)
        Text(
            "Экран к бодрствованию ${pct(share(phone.screenMin, awakeMin))} · " +
                "отвлечений в час ${if (awakeMin > 0) fmt1(phone.glances * 60.0 / awakeMin) else "—"} · " +
                "неделю назад экран ${dur(refPhone.screenMin)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Тело
// ---------------------------------------------------------------------------

@Composable
private fun BodyCard(
    app: PravkaApp,
    dayStart: Long,
    dateKey: String,
    pool: List<ZasechkaStore.Entry>,
    now: Long,
    healthByDate: Map<String, SportStore.Health>,
    workouts: List<SportStore.Workout>,
    gtgDays: List<ru.zf.pravka.data.StrengthStore.GtgDay>,
    sessions: List<ru.zf.pravka.data.StrengthStore.Session>,
    goalWeight: Int,
    isToday: Boolean,
) {
    val days7 = (6 downTo 0).map { dayStart - it * DAY }
    val days14 = (13 downTo 0).map { dayStart - it * DAY }
    val days28 = (27 downTo 0).map { dayStart - it * DAY }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    PaperCard(label = "Тело") {
        // ---- сон ----
        fun sleepMin(ds: Long): Long {
            val ribbon = DayReport.msToMin(DayReport.nightSleepMs(pool, ds, now))
            if (ribbon > 0) return ribbon
            val h = healthByDate[isoOf(ds)]?.sleepHours ?: 0.0
            return if (h > 0) (h * 60).roundToLong() else 0L
        }
        val sleep7 = days7.map { sleepMin(it) }
        val sleep28 = days28.map { sleepMin(it) }.filter { it > 0 }
        val sleepMedian = DayReport.median(sleep28.map { it.toDouble() })
        Text("Сон", style = MaterialTheme.typography.titleSmall)
        if (sleep7.any { it > 0 }) {
            StackedColumns(
                columns = days7.mapIndexed { i, ds ->
                    StackedColumn(weekdayOf(ds), listOf(ChartSlice("сон", sleep7[i].toFloat(), SLEEP_INK)))
                },
                height = 96.dp,
                target = sleepMedian?.toFloat(),
                valueText = { fmt1(it / 60.0) },
                highlight = 6,
            )
            val todaySleep = sleep7.last()
            val score = healthByDate[dateKey]?.sleepScore ?: 0
            Text(
                "Эта ночь ${if (todaySleep > 0) dur(todaySleep) else "—"} · медиана 28 ночей " +
                    (sleepMedian?.let { dur(it.roundToLong()) } ?: "—") +
                    (if (score > 0) " · счёт сна Garmin $score" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            PaperHint("Часы за ночь по ленте (сон с часов, разрезанный полуночью, склеен обратно), без ленты — по Garmin. Пунктир — медиана 28 ночей.")
        } else {
            PaperHint("Сна за эти дни ни в ленте, ни в часах нет.")
        }
        Spacer(Modifier.height(12.dp))

        // ---- нагрузка ----
        Text("Тренировки", style = MaterialTheme.typography.titleSmall)
        val byDay = days7.map { ds -> workouts.filter { it.start >= ds && it.start < ds + DAY } }
        if (byDay.any { it.isNotEmpty() }) {
            StackedColumns(
                columns = byDay.mapIndexed { i, list ->
                    StackedColumn(
                        weekdayOf(days7[i]),
                        list.sortedBy { it.start }.map { ChartSlice(SportCoach.sportName(it.type), it.load.toFloat(), sportInk(it.type)) },
                    )
                },
                height = 96.dp,
                valueText = { "${it.roundToInt()}" },
                highlight = 6,
            )
            val weekList = byDay.flatten()
            val prevList = workouts.filter { it.start >= dayStart - 13 * DAY && it.start < dayStart - 6 * DAY }
            val types = weekList.groupBy { SportCoach.sportName(it.type) }
            for ((name, list) in types.entries.sortedByDescending { it.value.sumOf { w -> w.minutes } }) {
                val km = list.sumOf { it.km }
                LegendRow(
                    color = sportInk(list.first().type),
                    label = name,
                    value = dur(list.sumOf { it.minutes }),
                    sub = "${list.size}× · load ${list.sumOf { it.load }}" + (if (km >= 0.5) " · ${fmt1(km)} км" else ""),
                )
            }
            Text(
                "Load семи дней ${weekList.sumOf { it.load }} · предыдущих семи ${prevList.sumOf { it.load }} · " +
                    "тренировок ${weekList.size}",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
            )
            PaperHint("Столбики — тренировочная нагрузка (load intervals.icu) по дням, цветом вида спорта.")
        } else {
            PaperHint("Тренировок с часов за эти семь дней нет.")
        }
        val todaySessions = sessions.filter { it.date == dateKey }
        if (todaySessions.isNotEmpty()) {
            val sets = todaySessions.sumOf { it.setCount }
            val checked = todaySessions.sumOf { it.checkedIds.size }
            Text(
                "Силовые за день: сессий ${todaySessions.size} · подходов $sets" +
                    (if (checked > 0) " · галочек $checked" else "") +
                    (todaySessions.firstOrNull { it.feel > 0 }?.let { " · самочувствие ${it.feel}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---- зарядка ----
        val gtgByDate = gtgDays.associateBy { it.date }
        val charged = days7.map { gtgByDate[isoOf(it)]?.charged == true }
        val streak = app.strengthStore.streak(dateKey)
        Text("Зарядка", style = MaterialTheme.typography.titleSmall)
        DotRow(labels = days7.map { weekdayOf(it) }, on = charged, color = GOOD, highlight = 6)
        val todayGtg = gtgByDate[dateKey]
        Text(
            "Цепочка $streak дн. · за неделю ${charged.count { it }} из 7" +
                (todayGtg?.takeIf { it.hangSec > 0 }?.let { " · вис ${it.hangSec} с" } ?: "") +
                (todayGtg?.takeIf { it.pullups > 0 }?.let { " · подтягиваний ${it.pullups}" } ?: "") +
                (todayGtg?.knee?.takeIf { it.isNotBlank() }?.let { " · колено $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))

        // ---- HRV и пульс покоя ----
        val hrv14 = days14.map { healthByDate[isoOf(it)]?.hrv?.takeIf { v -> v > 0 }?.toFloat() }
        val rhr14 = days14.map { healthByDate[isoOf(it)]?.restingHr?.takeIf { v -> v > 0 }?.toFloat() }
        val hrvBase = days28.mapNotNull { healthByDate[isoOf(it)]?.hrv?.takeIf { v -> v > 0 } }.let { if (it.isEmpty()) null else it.average().toFloat() }
        val rhrBase = days28.mapNotNull { healthByDate[isoOf(it)]?.restingHr?.takeIf { v -> v > 0 } }.let { if (it.isEmpty()) null else it.average().toFloat() }
        if (hrv14.any { it != null } || rhr14.any { it != null }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("HRV, 14 дней", style = MaterialTheme.typography.labelMedium, color = HRV_INK)
                Text("Пульс покоя, 14 дней", style = MaterialTheme.typography.labelMedium, color = RHR_INK)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    LineChart(hrv14, HRV_INK, height = 64.dp, baseline = hrvBase, valueText = { "${it.roundToInt()}" })
                }
                Box(Modifier.weight(1f)) {
                    LineChart(rhr14, RHR_INK, height = 64.dp, baseline = rhrBase, valueText = { "${it.roundToInt()}" })
                }
            }
            PaperHint("Пунктир — среднее за 28 дней. HRV выше базы и пульс ниже — восстановлен; наоборот — день полегче.")
            Spacer(Modifier.height(12.dp))
        }

        // ---- шаги ----
        val steps7 = days7.map { (healthByDate[isoOf(it)]?.steps ?: 0).toFloat() }
        if (steps7.any { it > 0f }) {
            Text("Шаги", style = MaterialTheme.typography.titleSmall)
            StackedColumns(
                columns = days7.mapIndexed { i, ds -> StackedColumn(weekdayOf(ds), listOf(ChartSlice("шаги", steps7[i], STEPS_INK))) },
                height = 80.dp,
                valueText = { fmt1(it / 1000.0) + "к" },
                highlight = 6,
            )
            Spacer(Modifier.height(12.dp))
        }

        // ---- вес и форма ----
        val weight28 = days28.map { healthByDate[isoOf(it)]?.weightKg?.takeIf { v -> v > 0 }?.toFloat() }
        val lastWeight = weight28.lastOrNull { it != null }
        val firstWeight = weight28.firstOrNull { it != null }
        if (lastWeight != null) {
            Text("Вес, 28 дней", style = MaterialTheme.typography.titleSmall)
            LineChart(
                weight28, WEIGHT_INK, height = 72.dp,
                baseline = goalWeight.takeIf { it > 0 }?.toFloat(),
                valueText = { fmt1(it.toDouble()) },
                labels = listOf(reportShortDate.format(Date(days28.first())), reportShortDate.format(Date(days28.last()))),
            )
            Text(
                "Сейчас ${fmt1(lastWeight.toDouble())} кг" +
                    (if (firstWeight != null && firstWeight != lastWeight) " · за 28 дней ${fmt1((lastWeight - firstWeight).toDouble()).let { if (it.startsWith("-")) it.replace("-", "−") else "+$it" }}" else "") +
                    (if (goalWeight > 0) " · до цели $goalWeight: ${fmt1((lastWeight - goalWeight).toDouble())}" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            PaperHint("Пунктир — цель из настроек Тела.")
        }
        val h = healthByDate[dateKey] ?: healthByDate.values.filter { it.date < dateKey }.maxByOrNull { it.date }
        if (h != null && (h.ctl > 0 || h.atl > 0)) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Форма на ${if (h.date == dateKey) "этот день" else h.date}: тренированность ${fmt1(h.ctl)} · " +
                    "усталость ${fmt1(h.atl)} · свежесть ${fmt1(h.tsb)}" +
                    (if (h.readiness > 0) " · готовность ${h.readiness}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Еда
// ---------------------------------------------------------------------------

@Composable
private fun FoodCard(
    app: PravkaApp,
    dayStart: Long,
    kcalTarget: Int,
    proteinTarget: Int,
    isToday: Boolean,
    mealsSize: Int,
) {
    val days7 = (6 downTo 0).map { dayStart - it * DAY }
    val totals = days7.map { app.foodStore.dayTotal(isoOf(it)) }
    val today = totals.last()
    PaperCard(label = "Еда") {
        if (totals.all { it.empty } || mealsSize == 0) {
            PaperHint("Приёмов за эти семь дней в дневнике нет.")
            return@PaperCard
        }
        val neutral = MaterialTheme.colorScheme.onSurfaceVariant
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KpiTile(
                "Ккал", if (today.kcal > 0) "${today.kcal}" else "—", Modifier.weight(1f),
                delta = if (kcalTarget > 0 && today.kcal > 0) DayReport.delta(today.kcal.toLong(), kcalTarget.toLong()) + " к цели $kcalTarget" else null,
                deltaColor = neutral,
                hint = "приёмов ${today.meals}",
                valueColor = KCAL_INK,
            )
            KpiTile(
                "Белок", if (today.protein > 0) "${today.protein} г" else "—", Modifier.weight(1f),
                delta = if (proteinTarget > 0 && today.protein > 0) DayReport.delta(today.protein.toLong(), proteinTarget.toLong(), "г") + " к цели $proteinTarget" else null,
                deltaColor = deltaColor((today.protein - proteinTarget).toLong(), if (proteinTarget > 0) true else null, neutral),
                hint = "жиры ${today.fat} г · углеводы ${today.carbs} г" + (if (today.fiber > 0) " · клетчатка ${today.fiber} г" else ""),
                valueColor = PROTEIN_INK,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("Калории по дням", style = MaterialTheme.typography.labelMedium, color = KCAL_INK)
        StackedColumns(
            columns = days7.mapIndexed { i, ds ->
                StackedColumn(weekdayOf(ds), listOf(ChartSlice("ккал", totals[i].kcal.toFloat(), KCAL_INK)), faded = isToday && i == 6)
            },
            height = 96.dp,
            target = kcalTarget.takeIf { it > 0 }?.toFloat(),
            valueText = { "${it.roundToInt()}" },
            highlight = 6,
        )
        Spacer(Modifier.height(8.dp))
        Text("Белок по дням", style = MaterialTheme.typography.labelMedium, color = PROTEIN_INK)
        StackedColumns(
            columns = days7.mapIndexed { i, ds ->
                StackedColumn(weekdayOf(ds), listOf(ChartSlice("белок", totals[i].protein.toFloat(), PROTEIN_INK)), faded = isToday && i == 6)
            },
            height = 80.dp,
            target = proteinTarget.takeIf { it > 0 }?.toFloat(),
            valueText = { "${it.roundToInt()}" },
            highlight = 6,
        )
        PaperHint("Пунктир — цели из настроек Тела. Считаются только подтверждённые приёмы.")
        if (today.kcal > 0) {
            Spacer(Modifier.height(10.dp))
            val p = today.protein * 4f
            val f = today.fat * 9f
            val c = today.carbs * 4f
            val sum = (p + f + c).coerceAtLeast(1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    listOf(ChartSlice("белки", p, PROTEIN_INK), ChartSlice("жиры", f, FAT_INK), ChartSlice("углеводы", c, CARBS_INK)),
                    size = 110.dp, thickness = 16.dp,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${today.kcal}", style = MaterialTheme.typography.titleSmall)
                        PaperHint("ккал")
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    LegendRow(PROTEIN_INK, "Белки", "${today.protein} г", sub = pct((p / sum).toDouble()))
                    LegendRow(FAT_INK, "Жиры", "${today.fat} г", sub = pct((f / sum).toDouble()))
                    LegendRow(CARBS_INK, "Углеводы", "${today.carbs} г", sub = pct((c / sum).toDouble()))
                    PaperHint("доли по калориям: белки и углеводы ×4, жиры ×9")
                }
            }
        }
        val fed = totals.filter { !it.empty }
        if (fed.size >= 2) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Среднее за ${fed.size} дн. с записями: ${fed.sumOf { it.kcal } / fed.size} ккал · " +
                    "белок ${fed.sumOf { it.protein } / fed.size} г",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Дела и Правка
// ---------------------------------------------------------------------------

@Composable
private fun TasksAppCard(
    context: Context,
    pool: List<ZasechkaStore.Entry>,
    window: DayReport.Window,
    dayStart: Long,
    isToday: Boolean,
    now: Long,
    tasks: List<ru.zf.pravka.data.TodoistStore.Task>,
    costs: Map<String, Double>,
    dictations: Map<String, Pair<Int, Long>>,
) {
    val dateKey = isoOf(dayStart)
    val todayKey = isoOf(now)
    PaperCard(label = "Дела и Правка") {
        val starts = DayReport.todoistStarts(pool, window)
        val dueToday = tasks.count { it.due == dateKey }
        val overdue = tasks.count { it.due.isNotBlank() && it.due < todayKey }
        Text(
            "Дел из Todoist запущено в ленте: $starts" +
                (if (isToday) " · в списке на сегодня $dueToday · просрочено $overdue" else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        // Помидоры считает служба в своих настройках — по дню.
        val prefs = context.getSharedPreferences("pravka_internal", Context.MODE_PRIVATE)
        val pomoFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val pomoToday = prefs.getInt("z_pomo_n_" + pomoFmt.format(Date(dayStart)), 0)
        val pomoWeek = (0..6).sumOf { prefs.getInt("z_pomo_n_" + pomoFmt.format(Date(dayStart - it * DAY)), 0) }
        if (pomoToday > 0 || pomoWeek > 0) {
            Text("Помидоров 🍅 за день $pomoToday · за семь дней $pomoWeek", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))
        val days14 = (13 downTo 0).map { dayStart - it * DAY }
        val costs14 = days14.map { (costs[isoOf(it)] ?: 0.0).toFloat() }
        if (costs14.any { it > 0f }) {
            Text("Деньги на модель, 14 дней, USD", style = MaterialTheme.typography.labelMedium, color = COST_INK)
            StackedColumns(
                columns = days14.mapIndexed { i, ds ->
                    StackedColumn(if (i % 2 == 1) weekdayOf(ds) else "", listOf(ChartSlice("usd", costs14[i], COST_INK)))
                },
                height = 80.dp,
                valueText = { if (it >= 0.995f) String.format(Locale.US, "%.1f", it) else String.format(Locale.US, "%.2f", it).removePrefix("0") },
                highlight = 13,
            )
            val today = costs[dateKey] ?: 0.0
            val week = (0..6).sumOf { costs[isoOf(dayStart - it * DAY)] ?: 0.0 }
            val month = costs.values.sum()
            Text(
                "За день ${String.format(Locale.US, "%.2f", today)} · за семь дней ${String.format(Locale.US, "%.2f", week)} · " +
                    "за 28 ${String.format(Locale.US, "%.2f", month)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val d = dictations[dateKey]
        val weekDict = (0..6).mapNotNull { dictations[isoOf(dayStart - it * DAY)] }
        if (d != null || weekDict.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Диктовок за день ${d?.first ?: 0}" +
                    (d?.let { " · речи ${dur(DayReport.msToMin(it.second))}" } ?: "") +
                    " · за семь дней ${weekDict.sumOf { it.first }}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PaperHint("Сколько Правка сама стоила и сколько было наговорено — то, чего в ленте нет: там это лежит как «Систематизация».")
    }
}

// ---------------------------------------------------------------------------
// 9. Коэффициенты
// ---------------------------------------------------------------------------

@Composable
private fun RatioRow(name: String, cur: String, ref: String, formula: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            PaperHint(formula)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            Text(cur, style = MaterialTheme.typography.titleMedium)
            Text(
                ref,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RatiosCard(
    frame: Frame,
    refFrame: Frame,
    phone: PhoneDaySummary.Summary,
    refPhone: PhoneDaySummary.Summary,
    isToday: Boolean,
) {
    val partial = isToday && !frame.full
    val refWord = if (partial) "неделю назад к этому часу" else "неделю назад"
    fun ref(text: String) = "$refWord $text"
    val awake = frame.awakeMin
    val refAwake = refFrame.awakeMin
    val awakeH = awake / 60.0
    val refAwakeH = refAwake / 60.0
    val plusMin = DayReport.msToMin(frame.slices.filter { it.worth > 0 }.sumOf { it.ms })
    val refPlusMin = DayReport.msToMin(refFrame.slices.filter { it.worth > 0 }.sumOf { it.ms })
    val holes = frame.minutes(DayReport::isHole)
    val refHoles = refFrame.minutes(DayReport::isHole)
    val t = DayReport.triangle(frame.slices)
    val rt = DayReport.triangle(refFrame.slices)
    PaperCard(label = "Коэффициенты") {
        RatioRow(
            "КПД дня", pct(frame.balance.efficiency), ref(pct(refFrame.balance.efficiency)),
            "плюсовые очки к обороту: плюс ÷ (плюс + |минус|)",
        )
        RatioRow(
            "Плюсовое время", pct(share(plusMin, awake)), ref(pct(share(refPlusMin, refAwake))),
            "минуты в категориях с ценностью выше нуля ÷ бодрствование",
        )
        RatioRow(
            "Дыры", pct(share(holes, awake)), ref(pct(share(refHoles, refAwake))),
            "потери и не размечено ÷ бодрствование",
        )
        RatioRow(
            "Телефон", pct(share(phone.screenMin, awake)),
            (if (partial) "неделю назад за весь день " else "$refWord ") + pct(share(refPhone.screenMin, refAwake)),
            "экран ÷ бодрствование (телефон считается по дням)",
        )
        RatioRow(
            "Отвлечений в час", if (awakeH > 0) fmt1(phone.glances / awakeH) else "—",
            ref(if (refAwakeH > 0) fmt1(refPhone.glances / refAwakeH) else "—"),
            "взял и убрал быстрее двух минут, на час бодрствования",
        )
        RatioRow(
            "Дел в час", if (awakeH > 0) fmt1(frame.focus.entries / awakeH) else "—",
            ref(if (refAwakeH > 0) fmt1(refFrame.focus.entries / refAwakeH) else "—"),
            "своих дел (без автоматики, заполнителей и сна) на час бодрствования · средняя длина ${dur(frame.focus.avgEntryMin)}, самое долгое ${dur(frame.focus.longestMin)}",
        )
        RatioRow(
            "Глубокая работа", pct(frame.focus.deepShare) + " · ${frame.focus.deepBlocks} бл.",
            ref(pct(refFrame.focus.deepShare) + " · ${refFrame.focus.deepBlocks} бл."),
            "доля работы в блоках от 45 минут без смены дела",
        )
        RatioRow(
            "Треугольник", pct(t.index), ref(pct(rt.index)),
            "минуты от +6 ÷ (от +6 плюс до −2): чем выше, тем шире верх",
        )
        PaperHint(
            "Порогов тут нет нарочно: что «хорошо», решают его собственные дни, а не таблица. " +
                "Формула под каждым числом — чтобы можно было проверить.",
        )
    }
}
