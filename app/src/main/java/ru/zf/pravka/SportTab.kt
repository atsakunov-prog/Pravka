package ru.zf.pravka

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.SportCoach
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperLabel

// Вкладка «Спорт»: тренировки, здоровье, тренированность и разбор.
//
// Порядок сверху вниз - это порядок вопросов, которые владелец задаёт себе
// утром: «как я сегодня?» (готовность), «куда я вообще иду?» (форма графиком),
// «что я делал?» (тренировки), «а вот объясни» (вопрос Опусу).
//
// Всё, кроме вопроса, рисуется из кэша на диске и считается на телефоне -
// вкладка открывается мгновенно и работает в самолёте. Токены здесь тратит
// только вопрос, и только когда его задали.

private val talkTimeFormat = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
private val workoutDayFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
private val workoutTimeFormat = SimpleDateFormat("HH:mm", Locale.US)

/** Цвет тона сводки: та же радуга, что у балла дня в Засечке. */
@Composable
private fun toneColor(tone: Int): Color = when (tone) {
    -2 -> Color(0xFFEF4444)
    -1 -> Color(0xFFF59E0B)
    1 -> Color(0xFF22C55E)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun SportTab(app: PravkaApp) {
    val store = app.sportStore
    val workouts by store.workoutsFlow.collectAsState()
    val health by store.healthFlow.collectAsState()
    val profile by store.profileFlow.collectAsState()
    val talks by store.talksFlow.collectAsState()

    var syncing by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    // Живой ответ: слова приезжают потоком и растут прямо на экране.
    var streaming by remember { mutableStateOf("") }
    var openWorkout by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(14) }

    LaunchedEffect(Unit) {
        store.load()
        // Первое открытие после установки: кэш пуст, и молчать об этом нельзя.
        runCatching { app.icuSportSync.refresh(force = store.workoutsFlow.value.isEmpty()) }
    }

    // Готовность считается на телефоне: перерисовывается сама, когда приехали
    // свежие дни здоровья.
    val readiness = remember(health, workouts) { app.sportCoach.readiness() }
    val shownWorkouts = remember(workouts, days) {
        val from = System.currentTimeMillis() - days * 86_400_000L
        workouts.filter { it.start >= from }
    }
    // Тело LazyColumn - не composable-контекст: remember считаем здесь, а
    // внутрь отдаём уже готовое.
    val byWeek = remember(shownWorkouts) { groupByWeek(shownWorkouts) }
    val weights = remember(health) { health.filter { it.weightKg > 0 }.take(60) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val refresh: () -> Unit = {
        if (!syncing) {
            syncing = true
            app.appScope.launch {
                val ok = runCatching { app.icuSportSync.refresh(force = true) }.getOrDefault(false)
                syncing = false
                if (!ok) {
                    val why = app.icuSportSync.lastError()
                    Feedback.toast(app, why.ifBlank { "Выгрузка не удалась" }, long = true)
                }
            }
        }
    }

    val ask: () -> Unit = ask@{
        val text = question.trim()
        if (asking) return@ask
        asking = true
        streaming = ""
        question = ""
        // Вопрос уезжает в app-scope: уйти со вкладки, пока модель думает,
        // не должно стоить ответа.
        app.appScope.launch {
            val answer = runCatching {
                app.sportCoach.ask(text) { delta -> streaming += delta }
            }.getOrElse { e ->
                SportCoach.Answer("", 0.0, e.message ?: "не вышло")
            }
            asking = false
            streaming = ""
            if (answer.error.isNotBlank()) {
                Feedback.toast(app, answer.error, long = true)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- Готовность ----
        item {
            PaperCard(
                label = "как я сегодня",
                labelColor = toneColor(readiness.tone),
                trailing = {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                        }
                    }
                },
            ) {
                Text(
                    readiness.verdict,
                    style = MaterialTheme.typography.headlineSmall,
                    color = toneColor(readiness.tone),
                )
                Spacer(Modifier.height(4.dp))
                PaperHint(readiness.detail)
                if (readiness.signals.isNotEmpty()) Spacer(Modifier.height(10.dp))
                for (signal in readiness.signals) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(signal.label, style = MaterialTheme.typography.bodyMedium)
                            PaperHint(signal.hint)
                        }
                        Text(
                            signal.value,
                            style = MaterialTheme.typography.titleMedium,
                            color = toneColor(signal.tone),
                        )
                    }
                }
                val syncedAt = store.lastSyncAt()
                if (syncedAt > 0) {
                    Spacer(Modifier.height(8.dp))
                    PaperHint("Выгружено " + talkTimeFormat.format(Date(syncedAt)))
                }
                val error = app.icuSportSync.lastError()
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ---- Тренированность графиком ----
        if (health.size >= 4) {
            item {
                PaperCard(label = "тренированность и усталость") {
                    FitnessChart(health.take(90).reversed())
                    Spacer(Modifier.height(8.dp))
                    val today = health.firstOrNull()
                    if (today != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            LegendValue("Тренированность", fmt1(today.ctl), CTL_COLOR)
                            LegendValue("Усталость", fmt1(today.atl), ATL_COLOR)
                            LegendValue(
                                "Форма",
                                signed(Math.round(today.tsb).toInt()),
                                toneColor(if (today.tsb < -10) -1 else if (today.tsb > 5) 1 else 0),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    PaperHint(
                        "Тренированность — накопленная нагрузка за шесть недель, " +
                            "усталость — за неделю. Их разница и есть форма: минус — " +
                            "работаешь в долг, плюс — свежий."
                    )
                }
            }
        }

        // ---- Вес и VO2max, если часы их знают ----
        if (weights.size >= 3) {
            item {
                PaperCard(label = "вес") {
                    WeightChart(weights.reversed())
                    Spacer(Modifier.height(8.dp))
                    val newest = weights.first()
                    val oldest = weights.last()
                    val delta = newest.weightKg - oldest.weightKg
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        LegendValue("Сейчас", fmt1(newest.weightKg) + " кг", CTL_COLOR)
                        LegendValue(
                            "За ${weights.size} замеров",
                            (if (delta > 0) "+" else "") + fmt1(delta) + " кг",
                            toneColor(if (delta > 1) -1 else if (delta < -1) 1 else 0),
                        )
                        val vo2 = health.firstOrNull { it.vo2max > 0 }?.vo2max ?: 0.0
                        if (vo2 > 0) LegendValue("VO₂max", fmt0(vo2), ATL_COLOR)
                    }
                }
            }
        }

        // ---- Тренировки ----
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaperLabel("тренировки · ${shownWorkouts.size}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in listOf(7, 14, 30, 90)) {
                        FilterChip(
                            selected = days == d,
                            onClick = { days = d },
                            label = { Text("$d дн.") },
                        )
                    }
                }
            }
        }
        if (shownWorkouts.isEmpty()) {
            item {
                PaperCard {
                    Text(
                        if (workouts.isEmpty()) "Тренировок в кэше нет."
                        else "За $days дней тренировок не было.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (workouts.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        PaperHint(
                            "Тренировки приезжают из intervals.icu. Проверь athlete id " +
                                "и ключ в настройках Засечки, потом нажми «Обновить»."
                        )
                    }
                }
            }
        } else {
            // Группируем по неделям: у недели есть свой итог, и это единица,
            // которой владелец про тренировки и думает.
            for ((weekLabel, list) in byWeek) {
                item(key = "w-$weekLabel") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PaperLabel(weekLabel)
                        PaperHint(
                            "${list.size} шт · ${list.sumOf { it.minutes }} мин · " +
                                "load ${list.sumOf { it.load }}"
                        )
                    }
                }
                items(list.size, key = { i -> list[i].id }) { i ->
                    val w = list[i]
                    WorkoutRow(
                        workout = w,
                        expanded = openWorkout == w.id,
                        onToggle = { openWorkout = if (openWorkout == w.id) null else w.id },
                        maxHr = profile.runMaxHr,
                    )
                }
            }
        }

        // ---- Вопрос ----
        item {
            PaperCard(label = "спросить про тренировки") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Стоит ли сегодня бежать интервалы?") },
                        minLines = 1,
                        maxLines = 4,
                        enabled = !asking,
                    )
                    IconButton(onClick = ask, enabled = !asking) {
                        if (asking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Send, contentDescription = "Спросить")
                    }
                }
                Spacer(Modifier.height(4.dp))
                PaperHint(
                    "Опус видит твои тренировки, сон, HRV, форму, еду за неделю и " +
                        "чем ты был занят по ленте. Пустой вопрос = «как у меня дела»."
                )
                if (streaming.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(streaming, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ---- Прошлые разборы ----
        if (talks.isNotEmpty()) {
            item { PaperLabel("прошлые разборы") }
            items(talks.size, key = { i -> talks[i].id }) { i ->
                val talk = talks[i]
                TalkCard(
                    talk = talk,
                    onDelete = { scope.launch { store.deleteTalk(talk.id) } },
                )
            }
        }

        // ---- Настройки ----
        item {
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "Скрыть настройки" else "Настройки спорта")
            }
        }
        if (showSettings) {
            item { SportSettings(app) }
        }
    }
}

@Composable
private fun LegendValue(label: String, value: String, color: Color) {
    Column {
        PaperHint(label)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

private val CTL_COLOR = Color(0xFF0E7490)
private val ATL_COLOR = Color(0xFFEA580C)

/**
 * Тренированность и усталость одной картинкой. Рисуем руками по Canvas, а не
 * библиотекой: две ломаные - это двадцать строк, а любая графическая
 * библиотека это ещё одна зависимость в приложении, где их пять.
 */
@Composable
private fun FitnessChart(ascending: List<SportStore.Health>) {
    val points = ascending.filter { it.ctl > 0 || it.atl > 0 }
    if (points.size < 2) return
    val line = MaterialTheme.colorScheme.outlineVariant
    val maxValue = points.maxOf { maxOf(it.ctl, it.atl) }.coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width
        val h = size.height
        // Нулевая линия внизу: у нагрузки нет отрицательных значений, и
        // растягивать шкалу от минимума значит врать про масштаб роста.
        drawLine(line, Offset(0f, h), Offset(w, h), strokeWidth = 1f)
        fun path(of: (SportStore.Health) -> Double, color: Color) {
            var previous: Offset? = null
            points.forEachIndexed { i, p ->
                val x = w * i / (points.size - 1).toFloat()
                val y = h - (of(p) / maxValue * h).toFloat()
                val current = Offset(x, y)
                previous?.let { drawLine(color, it, current, strokeWidth = 3f) }
                previous = current
            }
        }
        path({ it.ctl }, CTL_COLOR)
        path({ it.atl }, ATL_COLOR)
    }
}

/** Вес: та же ломаная, но шкала от минимума — колебание в кило важно видеть. */
@Composable
private fun WeightChart(ascending: List<SportStore.Health>) {
    val points = ascending.filter { it.weightKg > 0 }
    if (points.size < 2) return
    val color = CTL_COLOR
    val line = MaterialTheme.colorScheme.outlineVariant
    val values = points.map { it.weightKg }
    val low = values.min() - 0.5
    val high = values.max() + 0.5
    val span = (high - low).coerceAtLeast(0.5)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val w = size.width
        val h = size.height
        drawLine(line, Offset(0f, h), Offset(w, h), strokeWidth = 1f)
        var previous: Offset? = null
        points.forEachIndexed { i, p ->
            val x = w * i / (points.size - 1).toFloat()
            val y = h - ((p.weightKg - low) / span * h).toFloat()
            val current = Offset(x, y)
            previous?.let { drawLine(color, it, current, strokeWidth = 3f) }
            previous = current
        }
    }
}

/** Одна тренировка: строка, а по тапу — все её цифры. */
@Composable
private fun WorkoutRow(
    workout: SportStore.Workout,
    expanded: Boolean,
    onToggle: () -> Unit,
    maxHr: Int,
) {
    val accent = sportColor(workout.type)
    PaperCard {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .background(accent, MaterialTheme.shapes.extraSmall)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    SportCoach.sportName(workout.type) +
                        (if (workout.name.isNotBlank() &&
                                !workout.name.equals(workout.type, true)
                        ) " · ${workout.name}" else ""),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PaperHint(
                    workoutDayFormat.format(Date(workout.start)) + ", " +
                        workoutTimeFormat.format(Date(workout.start))
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${workout.minutes} мин",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                PaperHint(
                    listOfNotNull(
                        if (workout.km >= 0.1) fmt1(workout.km) + " км" else null,
                        if (workout.load > 0) "load ${workout.load}" else null,
                    ).joinToString(" · ")
                )
            }
        }
        if (!expanded) return@PaperCard
        Spacer(Modifier.height(10.dp))
        val facts = buildList {
            if (workout.paceSecPerKm > 0) add("Темп" to pace(workout.paceSecPerKm) + "/км")
            if (workout.gapSecPerKm > 0 &&
                kotlin.math.abs(workout.gapSecPerKm - workout.paceSecPerKm) > 8
            ) {
                add("Темп по рельефу" to pace(workout.gapSecPerKm) + "/км")
            }
            if (workout.avgHr > 0) {
                add(
                    "Пульс" to "${workout.avgHr}" +
                        (if (workout.maxHr > 0) " / макс. ${workout.maxHr}" else "") +
                        (if (maxHr > 0) " (${workout.avgHr * 100 / maxHr}% от макс.)" else "")
                )
            }
            if (workout.avgWatts > 0) {
                add(
                    "Мощность" to "${workout.avgWatts} Вт" +
                        (if (workout.normWatts > 0) " (нормированная ${workout.normWatts})" else "")
                )
            }
            if (workout.elevationM >= 10) add("Набор высоты" to "${workout.elevationM.toInt()} м")
            if (workout.intensity > 0) add("Интенсивность" to "${workout.intensity}% от порога")
            if (workout.decoupling != 0.0) {
                add("Расхождение пульса и темпа" to fmt1(workout.decoupling) + "%")
            }
            if (workout.efficiency > 0) add("Эффективность" to fmt1(workout.efficiency))
            if (workout.calories > 0) add("Сожжено" to "${workout.calories} ккал")
            if (workout.rpe > 0) add("Как тяжело (RPE)" to "${workout.rpe}/10")
            if (workout.feel > 0) add("Самочувствие" to "${workout.feel}/5")
        }
        for ((label, value) in facts) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PaperHint(label)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val zones = workout.zoneMinutes
        if (zones.any { it > 0 }) {
            Spacer(Modifier.height(10.dp))
            PaperHint("По пульсовым зонам, минут")
            Spacer(Modifier.height(4.dp))
            ZoneBars(zones)
        }
        if (facts.isEmpty() && zones.none { it > 0 }) {
            PaperHint("Кроме времени и расстояния, часы ничего не записали.")
        }
    }
}

/** Пять-семь столбиков «сколько минут в какой зоне», от синего к красному. */
@Composable
private fun ZoneBars(zones: List<Int>) {
    val peak = zones.max().coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        zones.forEachIndexed { i, minutes ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (minutes > 0) {
                    Text(
                        "$minutes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((34 * minutes / peak).coerceAtLeast(2).dp)
                        .background(zoneColor(i), MaterialTheme.shapes.extraSmall)
                )
                Text(
                    "z${i + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun zoneColor(index: Int): Color {
    val dark = isSystemInDarkTheme()
    // Пятая зона красная, первая синяя: тот же язык, что у радуги Засечки.
    val hue = (210f - index * 34f).coerceAtLeast(0f)
    return if (dark) Color.hsv(hue, 0.55f, 0.92f) else Color.hsv(hue, 0.66f, 0.68f)
}

@Composable
private fun sportColor(type: String): Color {
    val dark = isSystemInDarkTheme()
    val hue = when (type) {
        "Run", "TrailRun", "VirtualRun" -> 20f
        "Ride", "VirtualRide", "GravelRide", "MountainBikeRide" -> 200f
        "WeightTraining", "Workout", "Crossfit", "HIIT" -> 350f
        "Walk", "Hike" -> 100f
        "Swim" -> 185f
        else -> 265f
    }
    return if (dark) Color.hsv(hue, 0.5f, 0.92f) else Color.hsv(hue, 0.62f, 0.66f)
}

@Composable
private fun TalkCard(talk: SportStore.Talk, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    PaperCard {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    talk.question.ifBlank { "Как у меня дела" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (open) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PaperHint(
                    talkTimeFormat.format(Date(talk.ts)) +
                        (if (talk.costUsd > 0) " · " +
                            String.format(Locale.US, "%.3f", talk.costUsd) + " USD" else "")
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Clear, contentDescription = "Убрать")
            }
        }
        if (talk.error.isNotBlank()) {
            Text(
                talk.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (open && talk.answer.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(talk.answer, style = MaterialTheme.typography.bodyMedium)
        } else if (!open && talk.answer.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                talk.answer,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SportSettings(app: PravkaApp) {
    val store = app.sportStore
    val profile by store.profileFlow.collectAsState()
    val days by app.settings.sportDaysFlow.collectAsState(initial = 120)
    val talks by store.talksFlow.collectAsState()
    var sliderDays by remember(days) { mutableStateOf(days.toFloat()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    PaperCard(label = "настройки спорта") {
        Text("Глубина выгрузки: ${sliderDays.toInt()} дн.", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = sliderDays,
            onValueChange = { sliderDays = it },
            onValueChangeFinished = {
                app.appScope.launch { app.settings.setSportDays(sliderDays.toInt()) }
            },
            valueRange = 30f..400f,
        )
        PaperHint(
            "Столько дней тренировок и здоровья держим на телефоне. " +
                "Глубже — дольше первая выгрузка, но длиннее графики."
        )
        Spacer(Modifier.height(12.dp))
        if (profile.known) {
            Text("Пороги из intervals.icu", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val lines = buildList {
                if (profile.weightKg > 0) add("Вес" to fmt1(profile.weightKg) + " кг")
                if (profile.restingHr > 0) add("Пульс покоя" to "${profile.restingHr}")
                if (profile.runThresholdPaceSecPerKm > 0) {
                    add("Порог бега" to pace(profile.runThresholdPaceSecPerKm) + "/км")
                }
                if (profile.runFtp > 0) add("FTP бега" to "${profile.runFtp} Вт")
                if (profile.runLthr > 0) add("ЛПАНО" to "${profile.runLthr}")
                if (profile.runMaxHr > 0) add("Макс. пульс" to "${profile.runMaxHr}")
                if (profile.rideFtp > 0) add("FTP вело" to "${profile.rideFtp} Вт")
                if (profile.swimThresholdPer100m > 0) {
                    add("Порог плавания" to pace(profile.swimThresholdPer100m) + "/100 м")
                }
            }
            for ((label, value) in lines) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    PaperHint(label)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            PaperHint("Правятся в intervals.icu — здесь только видно.")
        } else {
            PaperHint(
                "Пороги ещё не приехали. Они тянутся при глубокой выгрузке — " +
                    "нажми «Обновить» наверху."
            )
        }
        if (talks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { scope.launch { store.clearTalks() } }) {
                Text("Убрать все разборы (${talks.size})")
            }
        }
    }
}

// ---- Мелочи ----

private fun groupByWeek(
    workouts: List<SportStore.Workout>,
): List<Pair<String, List<SportStore.Workout>>> {
    val now = System.currentTimeMillis()
    return workouts.groupBy { w ->
        val weeksAgo = ((now - w.start) / (7 * 86_400_000L)).toInt()
        when (weeksAgo) {
            0 -> "эта неделя"
            1 -> "неделя назад"
            else -> "$weeksAgo недель назад"
        }
    }.toList()
}

private fun fmt0(v: Double) = String.format(Locale.US, "%.0f", v)
private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
private fun pace(secPerKm: Int): String =
    "${secPerKm / 60}:" + String.format(Locale.US, "%02d", secPerKm % 60)
