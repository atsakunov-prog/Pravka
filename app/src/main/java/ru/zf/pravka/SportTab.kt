package ru.zf.pravka

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.SportCoach
import ru.zf.pravka.core.TrafficLight
import ru.zf.pravka.data.ExerciseBook
import ru.zf.pravka.data.PlanStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperLabel

// Вкладка «Спорт»: сегодня, светофор, подходы, форма, разбор.
//
// Порядок сверху вниз — порядок вопросов утром, и он важнее красоты:
//
//   1. ЧТО Я ДЕЛАЮ СЕГОДНЯ. Сессия из плана с ключевыми параметрами и списком
//      упражнений, у каждого — прошлый раз. Это карточка дня, а не лента
//      вчерашнего: ретроспективу и в intervals видно.
//   2. СВЕТОФОР — одно решение вместо трёх графиков, плюс три числа мелким
//      шрифтом и нарушения его собственных правил.
//   3. ЗАРЯДКА — цепочка, которая не должна рваться, и вис в секундах.
//   4. Форма, тренировки, вопрос — то, что смотрят раз в неделю, а не каждый день.
//
// Всё, кроме вопроса, рисуется из кэша на диске и считается на телефоне —
// вкладка открывается мгновенно и работает в самолёте, а он тренируется на
// даче каждое воскресенье.

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
internal fun SportTab(app: PravkaApp, onOpenSettings: () -> Unit = {}) {
    val store = app.sportStore
    val workouts by store.workoutsFlow.collectAsState()
    val health by store.healthFlow.collectAsState()
    val profile by store.profileFlow.collectAsState()
    val talks by store.talksFlow.collectAsState()
    val planDays by app.planStore.daysFlow.collectAsState()
    val rules by app.planStore.rulesFlow.collectAsState()
    val sessions by app.strengthStore.sessionsFlow.collectAsState()
    val gtgDays by app.strengthStore.gtgFlow.collectAsState()
    val rawTakes by app.strengthStore.rawFlow.collectAsState()
    val restSec by app.settings.restSecFlow.collectAsState(initial = 90)

    var syncing by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    // Живой ответ: слова приезжают потоком и растут прямо на экране.
    var streaming by remember { mutableStateOf("") }
    var openWorkout by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf(14) }

    var gtgDialog by remember { mutableStateOf(false) }
    var feelDialog by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        store.load()
        app.strengthStore.load()
        app.planStore.load()
        app.exerciseBook.load()
        // Первое открытие после установки: кэш пуст, и молчать об этом нельзя.
        runCatching { app.icuSportSync.refresh(force = store.workoutsFlow.value.isEmpty()) }
        runCatching { app.planSync.refresh(force = planDays.isEmpty()) }
    }

    val today = remember(planDays, sessions) { dayKey(System.currentTimeMillis()) }
    // Светофор и карточка дня считаются на телефоне: перерисовываются сами,
    // когда приехали свежие дни здоровья или новый план.
    val verdict = remember(health, workouts, planDays, rules, gtgDays) {
        app.trafficLight.today(today)
    }
    val mainPlan = remember(planDays, today) { app.planStore.mainOf(today) }
    val todaySession = remember(sessions, today) {
        app.strengthStore.sessionsOn(today).firstOrNull()
    }
    val plannedExercises = remember(planDays, sessions, today) {
        val block = mainPlan?.block.orEmpty()
        if (block.isBlank()) emptyList() else app.strengthEngine.lastTimeFor(block, today)
    }
    val streak = remember(gtgDays) { app.strengthStore.streak(today) }
    val gtgToday = remember(gtgDays, today) { app.strengthStore.gtgOn(today) }
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
                runCatching { app.planSync.refresh(force = true) }
                runCatching { app.strengthEngine.syncPending(force = true) }
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
        // ---- Что я делаю сегодня ----
        item {
            PaperCard(
                label = "сегодня",
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
                if (mainPlan == null) {
                    Text(
                        "В календаре intervals на сегодня ничего нет.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    PaperHint(
                        "План приезжает из календаря intervals — ты его туда пушишь, " +
                            "когда собираешь блок. Правила блока читаются из Notion."
                    )
                } else {
                    Text(mainPlan.name, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    // planLine — «название · параметры»; название уже выше,
                    // поэтому в подсказку идёт только хвост с параметрами.
                    PaperHint(verdict.planLine.substringAfter(mainPlan.name).trim(' ', '·'))
                    // Комментарий владельца к сессии — первый абзац описания
                    // до нумерованного списка. Он там про смысл дня, и это
                    // ровно то, что стоит прочитать перед началом.
                    val comment = mainPlan.description.lines()
                        .takeWhile { !Regex("^\\d+[.)]\\s+\\S").containsMatchIn(it.trim()) }
                        .joinToString(" ")
                        .trim()
                    if (comment.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            comment.take(400),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val planned = mainPlan.plannedLines()
                    if (planned.isNotEmpty() && plannedExercises.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        for (line in planned) {
                            Text("· " + line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (todaySession?.done == true) {
                            Text(
                                "✓ Сделано",
                                style = MaterialTheme.typography.bodyMedium,
                                color = toneColor(1),
                            )
                        } else {
                            Button(onClick = {
                                app.appScope.launch {
                                    val session = app.strengthEngine.markDone(today, mainPlan.minutes)
                                    feelDialog = session.id
                                }
                            }) { Text("Сделано") }
                        }
                        if (todaySession != null && todaySession.feel in 1..5) {
                            PaperHint("самочувствие ${todaySession.feel}/5")
                        } else if (todaySession != null) {
                            OutlinedButton(onClick = { feelDialog = todaySession.id }) {
                                Text("Самочувствие")
                            }
                        }
                    }
                }
            }
        }

        // ---- Упражнения дня с прошлым разом ----
        if (plannedExercises.isNotEmpty()) {
            item { PaperLabel("упражнения · прошлый раз") }
            items(plannedExercises.size, key = { i -> "px" + plannedExercises[i].first.id }) { i ->
                val (exercise, last) = plannedExercises[i]
                val doneToday = todaySession?.exercises?.firstOrNull { it.exerciseId == exercise.id }
                PlannedExerciseCard(
                    exercise = exercise,
                    lastTime = last,
                    doneToday = doneToday,
                    restSec = restSec,
                    onRest = { seconds ->
                        Feedback.toast(app, "Отдых $seconds сек — кнопка «Т» считает")
                        ru.zf.pravka.trigger.PravkaAccessibilityService.instance
                            ?.startRestFromTab(seconds)
                    },
                )
            }
        }

        // ---- Светофор ----
        item {
            PaperCard(label = "светофор", labelColor = toneColor(verdict.tone)) {
                Text(
                    verdict.headline,
                    style = MaterialTheme.typography.headlineSmall,
                    color = toneColor(verdict.tone),
                )
                Spacer(Modifier.height(4.dp))
                Text(verdict.because, style = MaterialTheme.typography.bodyMedium)
                if (verdict.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    for (w in verdict.warnings) {
                        Text(
                            "⚠ " + w,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (verdict.numbers.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    // Три числа мелким шрифтом — чтобы можно было проверить, а
                    // не чтобы читать вместо вердикта. Больше трёх — дашборд.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        for (n in verdict.numbers) {
                            Column {
                                Text(
                                    n.label + " " + n.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = toneColor(n.tone),
                                )
                                if (n.hint.isNotBlank()) {
                                    Text(
                                        n.hint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                val error = app.icuSportSync.lastError()
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ---- Силовая сегодня: что записано и куда уехало ----
        if (todaySession != null && (!todaySession.empty || todaySession.done)) {
            item { StrengthTodayCard(app, todaySession, onFeel = { feelDialog = todaySession.id }) }
        }

        // ---- Зарядка и GTG ----
        item {
            PaperCard(
                label = "зарядка · путь к первому подтягиванию",
            ) {
                // Отметка зарядки — ГЛАВНАЯ кнопка во всю ширину, и она одна.
                // Раньше она была мелкой справа от стрика, а «+» в углу открывал
                // диалог с висами, который зарядку не отмечал вовсе — и было
                // непонятно, чем одно отличается от другого. Теперь порядок
                // такой: сверху «сделал», ниже мелким — числа турника.
                val charged = gtgToday?.charged == true
                if (charged) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "✓ Зарядка сделана",
                            style = MaterialTheme.typography.titleMedium,
                            color = toneColor(1),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            app.appScope.launch { app.bodyEngine.unchargeToday(today) }
                        }) { Text("Отменить") }
                    }
                } else {
                    Button(
                        onClick = { app.appScope.launch { app.bodyEngine.chargedToday() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Зарядка сделана") }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$streak",
                        style = MaterialTheme.typography.displaySmall,
                        color = if (streak > 0) toneColor(1) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (streak == 1) "день подряд" else "дней подряд",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        PaperHint(
                            if (charged) "сегодня отмечено"
                            else "сегодня ещё нет — цепочка не рвётся до полуночи"
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                GtgStrip(app.strengthStore.recentGtg(14))
                Spacer(Modifier.height(12.dp))
                PaperHint("Турник — отдельные числа, зарядку они не отмечают:")
                Spacer(Modifier.height(4.dp))
                val best = app.strengthStore.bestHang()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    LegendValue(
                        "Вис сегодня",
                        if ((gtgToday?.hangSec ?: 0) > 0) "${gtgToday?.hangSec} сек" else "—",
                        CTL_COLOR,
                    )
                    LegendValue("Лучший вис", if (best > 0) "$best сек" else "—", ATL_COLOR)
                    LegendValue(
                        "Негативы",
                        if ((gtgToday?.negatives ?: 0) > 0) "${gtgToday?.negatives}" else "—",
                        CTL_COLOR,
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { gtgDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Записать вис, негативы, колено") }
                if (gtgToday?.knee?.isNotBlank() == true) {
                    Spacer(Modifier.height(8.dp))
                    PaperHint("Колено сегодня: ${gtgToday.knee}")
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

        // ---- КПД: его же месячная контрольная, посчитанная сама ----
        // Правило владельца из Notion: «раз в месяц — темп бега на пульсе 150
        // и мощность на пульсе ~145. Первый вниз, вторая вверх». Это и есть
        // efficiency factor, который intervals считает каждой тренировке:
        // темп (или ватты) на удар пульса. Держать для этого отдельный ритуал
        // не нужно — данные уже в кэше, рисуем тренд и говорим словами.
        item { EfficiencyCard(workouts) }

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
                                "и ключ («Настройки» → «Засечка»), потом нажми «Обновить»."
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
                        rules = rules,
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

        // ---- Неразобранное: сказанное, что не стало ничем ----
        // Сырая надиктовка не удаляется никогда — но до сих пор её никто и не
        // ПОКАЗЫВАЛ: фраза, на которой модель споткнулась, лежала на диске
        // невидимой, а «можно переиграть» было обещанием без кнопки. Вот кнопка.
        run {
            val unparsed = rawTakes.filter { it.kind.isBlank() || it.kind == "unknown" }.take(5)
            if (unparsed.isNotEmpty()) {
                item { PaperLabel("неразобранное · ${unparsed.size}") }
                items(unparsed.size, key = { i -> "raw" + unparsed[i].id }) { i ->
                    val take = unparsed[i]
                    var busy by remember(take.id) { mutableStateOf(false) }
                    PaperCard {
                        Text("«${take.text}»", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        PaperHint(
                            rawDayFormat.format(Date(take.ts)) +
                                (if (take.error.isBlank()) "" else " · ${take.error.take(120)}")
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (!busy) {
                                    busy = true
                                    app.appScope.launch {
                                        val result = app.bodyEngine.rehear(take.id)
                                        busy = false
                                        Feedback.toast(
                                            app,
                                            result.fold(
                                                { "✓ " + it.headline() },
                                                { e -> e.message ?: "Опять не вышло" },
                                            ),
                                            long = true,
                                        )
                                    }
                                }
                            },
                            enabled = !busy,
                        ) { Text(if (busy) "Разбираю…" else "Разобрать заново") }
                    }
                }
            }
        }

        // ---- Настройки ----
        item { DigestSection(app) }

        // Настройки — в одной вкладке со всеми остальными, группой «Тело».
        item { SettingsLink("Настройки тела: правила, Notion, цели", onOpenSettings) }
    }

    if (gtgDialog) {
        GtgDialog(app = app, date = today, onClose = { gtgDialog = false })
    }
    val feelSession = feelDialog
    if (feelSession != null) {
        FeelDialog(app = app, sessionId = feelSession, onClose = { feelDialog = null })
    }
}

/**
 * Упражнение дня: схема из справочника, прошлый раз, что уже сделано сегодня —
 * и техника с ошибками по тапу. Прошлый раз здесь главное: прогрессивная
 * перегрузка это «сегодня чуть больше», и «чуть больше чего» надо видеть В
 * МОМЕНТ подхода, а не вспоминать.
 */
@Composable
private fun PlannedExerciseCard(
    exercise: ExerciseBook.Exercise,
    lastTime: StrengthStore.ExerciseLog?,
    doneToday: StrengthStore.ExerciseLog?,
    restSec: Int,
    onRest: (Int) -> Unit,
) {
    var open by remember(exercise.id) { mutableStateOf(false) }
    PaperCard {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .background(
                        if (doneToday != null) toneColor(1) else MaterialTheme.colorScheme.outlineVariant,
                        MaterialTheme.shapes.extraSmall,
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    exercise.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PaperHint(
                    listOfNotNull(
                        exercise.scheme.takeIf { it.isNotBlank() },
                        exercise.gear.firstOrNull(),
                    ).joinToString(" · ")
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (doneToday != null) {
                    Text(
                        doneToday.compact(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = toneColor(1),
                    )
                } else {
                    Text(
                        lastTime?.compact() ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PaperHint(if (doneToday != null) "сегодня" else "прошлый раз")
            }
        }
        if (!open) return@PaperCard
        Spacer(Modifier.height(10.dp))
        if (doneToday != null && lastTime != null) {
            Text(
                "Прошлый раз: " + lastTime.compact(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (exercise.how.isNotBlank()) {
            Text("Как делать", style = MaterialTheme.typography.labelMedium)
            Text(exercise.how, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        if (exercise.mistakes.isNotBlank()) {
            Text("Главные ошибки", style = MaterialTheme.typography.labelMedium)
            Text(
                exercise.mistakes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (exercise.progression.isNotBlank() && exercise.progression != "—") {
            Text("Прогрессия", style = MaterialTheme.typography.labelMedium)
            Text(exercise.progression, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (seconds in listOf(60, restSec, 120).distinct()) {
                OutlinedButton(onClick = { onRest(seconds) }) { Text("⏱ $seconds") }
            }
        }
        if (exercise.videoQuery.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            val context = androidx.compose.ui.platform.LocalContext.current
            TextButton(onClick = {
                // Не встроенный плеер, а поиск в ютубе: держать у себя ссылки
                // на чужие видео значит починять их каждый год.
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                "https://www.youtube.com/results?search_query=" +
                                    android.net.Uri.encode(exercise.videoQuery)
                            ),
                        )
                    )
                }
            }) { Text("▶ Видео: " + exercise.videoQuery.take(40)) }
        }
    }
}


/**
 * Силовая за сегодня: подходы, как они записаны, и — главное — КУДА они уехали.
 *
 * Про дорогу наружу владелец спросил прямо: «непонятно, как силовые делать, она
 * же будет отмечена ещё и на гармине, надо это совмещать». Совмещение работает
 * так: часы отдают силовую в intervals активностью WeightTraining, и журнал
 * подходов дописывается в ОПИСАНИЕ этой активности — одна запись за день, а не
 * телефонная рядом с часовой. Пока часы молчат, сессия ждёт; через полтора
 * суток журнал уходит отдельной заметкой, чтобы не пропасть.
 *
 * Всё это было и раньше, но молча, и молчание читалось как «ничего не
 * записалось». Поэтому карточка называет состояние словами и даёт две кнопки:
 * подтолкнуть поиск активности и не ждать вовсе.
 */
@Composable
private fun StrengthTodayCard(
    app: PravkaApp,
    session: StrengthStore.Session,
    onFeel: () -> Unit,
) {
    val route = remember(session) { app.strengthEngine.routeOf(session) }
    var busy by remember { mutableStateOf(false) }

    PaperCard(
        label = "силовая сегодня",
        trailing = {
            if (session.setCount > 0) {
                PaperHint("подходов ${session.setCount}")
            }
        },
    ) {
        if (session.title.isNotBlank()) {
            Text(session.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
        }
        for (log in session.exercises) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    log.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(log.compact(), style = MaterialTheme.typography.bodyMedium)
            }
            if (log.note.isNotBlank()) PaperHint(log.note)
        }
        if (session.volume > 0) {
            Spacer(Modifier.height(4.dp))
            PaperHint("объём ${fmt0(session.volume)} кг")
        }
        if (session.feel in 1..5) {
            Spacer(Modifier.height(4.dp))
            PaperHint("самочувствие ${session.feel}/5" +
                (if (session.rpe > 0) " · RPE ${session.rpe}" else ""))
        } else {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onFeel) { Text("Самочувствие") }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            (if (route.tone == 1) "✓ " else if (route.tone < 0) "⚠ " else "⏳ ") + route.headline,
            style = MaterialTheme.typography.bodyMedium,
            color = toneColor(route.tone),
        )
        if (route.hint.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            PaperHint(route.hint)
        }
        if (route.canRetry || route.canNote) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (route.canRetry) {
                    OutlinedButton(
                        onClick = {
                            if (!busy) {
                                busy = true
                                app.appScope.launch {
                                    val outcome = app.strengthEngine.syncPending(force = true)
                                    busy = false
                                    Feedback.toast(
                                        app,
                                        when {
                                            outcome.sent > 0 -> "Уехало"
                                            outcome.failed > 0 -> outcome.error.ifBlank { "Не вышло" }
                                            else -> "Активности с часов ещё нет"
                                        },
                                        long = true,
                                    )
                                }
                            }
                        },
                        enabled = !busy,
                    ) { Text(if (busy) "Ищу…" else "Найти активность") }
                }
                if (route.canNote) {
                    OutlinedButton(
                        onClick = {
                            if (!busy) {
                                busy = true
                                app.appScope.launch {
                                    val outcome = app.strengthEngine.pushAsNote(session.id)
                                    busy = false
                                    Feedback.toast(
                                        app,
                                        outcome.fold(
                                            { "Записал заметкой в календарь" },
                                            { e -> e.message ?: "Не вышло" },
                                        ),
                                        long = true,
                                    )
                                }
                            }
                        },
                        enabled = !busy,
                    ) { Text("Без часов") }
                }
            }
            if (route.canNote) {
                Spacer(Modifier.height(4.dp))
                PaperHint(
                    "«Без часов» — когда силовая прошла без них вовсе: журнал ляжет " +
                        "заметкой сразу, не дожидаясь полутора суток."
                )
            }
        }
    }
}

/** Полоска последних двух недель зарядки: цепочка, которую видно глазом. */
@Composable
private fun GtgStrip(days: List<StrengthStore.GtgDay>) {
    val done = days.filter { it.charged }.map { it.date }.toSet()
    val today = dayKey(System.currentTimeMillis())
    val dates = remember(today) {
        var cursor = today
        val out = mutableListOf<String>()
        repeat(14) {
            out.add(cursor)
            cursor = ru.zf.pravka.data.dayBefore(cursor)
        }
        out.reversed()
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (date in dates) {
            Box(
                Modifier
                    .weight(1f)
                    .height(18.dp)
                    .background(
                        if (date in done) toneColor(1)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.shapes.extraSmall,
                    )
            )
        }
    }
}

/** Вис, негативы, лопаточные и колено — руками, когда голосом неудобно. */
@Composable
private fun GtgDialog(app: PravkaApp, date: String, onClose: () -> Unit) {
    var hang by remember { mutableStateOf("") }
    var negatives by remember { mutableStateOf("") }
    var scapular by remember { mutableStateOf("") }
    var knee by remember { mutableStateOf("") }
    // Записал числа турника — значит зарядка была. Раньше диалог их не связывал,
    // и «вис 40 секунд» оставлял день неотмеченным: владелец видел цифры и
    // пустой стрик и не понимал, чего ещё от него хотят.
    var charged by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Зарядка и турник") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = charged, onCheckedChange = { charged = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Отметить зарядку сделанной", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = hang,
                        onValueChange = { hang = it.filter { c -> c.isDigit() }.take(4) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Вис, сек") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = negatives,
                        onValueChange = { negatives = it.filter { c -> c.isDigit() }.take(3) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Негативы") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = scapular,
                    onValueChange = { scapular = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Лопаточные") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(10.dp))
                PaperHint("Колено сегодня")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in listOf("зелёный", "жёлтый", "красный")) {
                        FilterChip(
                            selected = knee == option,
                            onClick = { knee = if (knee == option) "" else option },
                            label = { Text(option) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                PaperHint(
                    "Записывается лучший результат дня: вечерняя попытка не портит " +
                        "утреннюю. Колено — наоборот, последнее сказанное."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.appScope.launch {
                    app.bodyEngine.putGtgNumbers(
                        date = date,
                        charged = if (charged) true else null,
                        hangSec = hang.toIntOrNull(),
                        negatives = negatives.toIntOrNull(),
                        scapular = scapular.toIntOrNull(),
                        knee = knee.ifBlank { null },
                    )
                    onClose()
                }
            }) { Text("Записать") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

/**
 * Самочувствие после тренировки. Шкала перевёрнутая — 1 отлично, 5 развалина, —
 * потому что такая она в intervals.icu, и переворачивать её здесь значило бы
 * врать при записи назад.
 */
@Composable
private fun FeelDialog(app: PravkaApp, sessionId: Long, onClose: () -> Unit) {
    var feel by remember { mutableStateOf(0) }
    var rpe by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Как прошло") },
        text = {
            Column {
                PaperHint("Самочувствие: 1 отлично — 5 развалина (шкала intervals)")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (v in 1..5) {
                        FilterChip(
                            selected = feel == v,
                            onClick = { feel = if (feel == v) 0 else v },
                            label = { Text("$v") },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                PaperHint("Как тяжело далось, RPE 1–10")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (v in listOf(3, 5, 7, 8, 9, 10)) {
                        FilterChip(
                            selected = rpe == v,
                            onClick = { rpe = if (rpe == v) 0 else v },
                            label = { Text("$v") },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Заметка (уедет в intervals)") },
                    minLines = 1,
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.appScope.launch {
                    app.strengthEngine.setFeel(sessionId, feel, rpe, note.trim())
                    app.strengthEngine.syncPending(force = true)
                    onClose()
                }
            }) { Text("Записать") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
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

private val rawDayFormat = SimpleDateFormat("d MMMM, HH:mm", Locale("ru"))

private val CTL_COLOR = Color(0xFF0E7490)
private val ATL_COLOR = Color(0xFFEA580C)
private val RUN_COLOR = Color(0xFF16A34A)
private val RIDE_COLOR = Color(0xFF2563EB)

/**
 * Тренированность и усталость одной картинкой. Рисуем руками по Canvas, а не
 * библиотекой: две ломаные - это двадцать строк, а любая графическая
 * библиотека это ещё одна зависимость в приложении, где их пять.
 */
/**
 * Тренд КПД по бегу и вело за 90 дней: EF = темп (ватты) на удар пульса,
 * intervals отдаёт его готовым. Рост EF — база строится; сравниваем среднее
 * последних четырёх недель с четырьмя до них, а не два одиночных замера:
 * одиночные шумят погодой, сном и рельефом.
 */
@Composable
private fun EfficiencyCard(workouts: List<SportStore.Workout>) {
    val from = remember { System.currentTimeMillis() - 90L * 86_400_000L }
    val runs = remember(workouts) {
        workouts.filter {
            it.type.equals("Run", ignoreCase = true) && it.efficiency > 0 && it.start >= from
        }.sortedBy { it.start }
    }
    val rides = remember(workouts) {
        workouts.filter {
            (it.type.equals("Ride", ignoreCase = true) ||
                it.type.equals("VirtualRide", ignoreCase = true)) &&
                it.efficiency > 0 && it.start >= from
        }.sortedBy { it.start }
    }
    if (runs.size < 3 && rides.size < 3) return
    PaperCard(label = "кпд · темп и ватты на удар пульса") {
        var shown = false
        if (runs.size >= 3) {
            EfficiencyRow("Бег", runs, RUN_COLOR)
            shown = true
        }
        if (rides.size >= 3) {
            if (shown) Spacer(Modifier.height(12.dp))
            EfficiencyRow("Вело", rides, RIDE_COLOR)
        }
        Spacer(Modifier.height(8.dp))
        PaperHint(
            "Это твоя месячная контрольная, посчитанная сама: рост — база " +
                "строится, темп на том же пульсе ускоряется. Сравниваются " +
                "средние по четырём неделям, одиночные точки шумят."
        )
    }
}

@Composable
private fun EfficiencyRow(label: String, ascending: List<SportStore.Workout>, color: Color) {
    val now = System.currentTimeMillis()
    val recent = ascending.filter { it.start >= now - 28L * 86_400_000L }.map { it.efficiency }
    val before = ascending.filter { it.start < now - 28L * 86_400_000L }.map { it.efficiency }
    val trend: Pair<String, Int>? = if (recent.isNotEmpty() && before.isNotEmpty()) {
        val a = before.average()
        val b = recent.average()
        val pct = ((b - a) / a * 100).let { Math.round(it).toInt() }
        when {
            pct >= 2 -> "+$pct% за месяц" to 1
            pct <= -2 -> "$pct% за месяц" to -1
            else -> "ровно" to 0
        }
    } else null
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (trend != null) {
            Text(
                trend.first,
                style = MaterialTheme.typography.bodyMedium,
                color = toneColor(trend.second),
            )
        } else {
            PaperHint("${ascending.size} трен. — мало для сравнения месяцев")
        }
    }
    Spacer(Modifier.height(4.dp))
    val values = ascending.map { it.efficiency }
    val low = values.min()
    val high = values.max()
    val span = (high - low).coerceAtLeast(0.01)
    val line = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(46.dp)) {
        val w = size.width
        val h = size.height
        drawLine(line, Offset(0f, h), Offset(w, h), strokeWidth = 1f)
        var previous: Offset? = null
        values.forEachIndexed { i, v ->
            val x = if (values.size == 1) 0f else w * i / (values.size - 1).toFloat()
            val y = h - ((v - low) / span * h * 0.9).toFloat() - h * 0.05f
            val current = Offset(x, y)
            previous?.let { drawLine(color, it, current, strokeWidth = 3f) }
            drawCircle(color, radius = 3f, center = current)
            previous = current
        }
    }
}

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
    rules: PlanStore.Rules = PlanStore.Rules(),
) {
    val accent = sportColor(workout.type)
    // Пробежка против ЕГО правил, сразу в строке: «под потолком» или «серая
    // зона» видно без тапа. Правил в тексте нет — молчим, порог не выдумываем.
    val verdictRun = runRuleVerdict(workout, rules)
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
                if (verdictRun != null) {
                    Text(
                        verdictRun.first,
                        style = MaterialTheme.typography.bodySmall,
                        color = toneColor(verdictRun.second),
                    )
                }
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
            if (workout.cadence > 0 && workout.type.equals("Run", ignoreCase = true)) {
                add(
                    "Каденс" to "${workout.cadence}" +
                        (if (rules.cadenceMin > 0) {
                            if (workout.cadence >= rules.cadenceMin) " (цель ${rules.cadenceMin}+ ✓)"
                            else " (цель ${rules.cadenceMin}+)"
                        } else "")
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

/**
 * Пробежка против правил блока: пара «текст · тон» или null, если сказать
 * нечего. Выше серой зоны не ругаем: интервалы и тесты там и живут — про них
 * решает план, а не пост-фактум значок.
 */
private fun runRuleVerdict(
    workout: SportStore.Workout,
    rules: PlanStore.Rules,
): Pair<String, Int>? {
    if (!workout.type.equals("Run", ignoreCase = true)) return null
    if (workout.avgHr <= 0 || rules.runHrCeiling <= 0) return null
    val hr = workout.avgHr
    return when {
        hr <= rules.runHrCeiling -> "под потолком ${rules.runHrCeiling} ✓" to 1
        rules.greyZoneLow > 0 && rules.greyZoneHigh > 0 && hr in rules.greyZoneLow..rules.greyZoneHigh ->
            "серая зона ${rules.greyZoneLow}–${rules.greyZoneHigh} — пульс $hr" to -2
        rules.greyZoneHigh in 1 until hr -> "жёсткая: пульс $hr — если не по плану, это перебор" to -1
        else -> "выше потолка ${rules.runHrCeiling}: пульс $hr" to -1
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

/**
 * Сводка для чата: день или неделя одним текстом. Собирается на телефоне из
 * уже имеющихся сторов — ни запроса в сеть, ни токена. Дорого стоит совет, а
 * не его исходные данные.
 */
@Composable
private fun DigestSection(app: PravkaApp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf("") }

    val build: (Boolean) -> Unit = { weekly ->
        if (!busy) {
            busy = true
            app.appScope.launch {
                val text = runCatching {
                    if (weekly) app.digestBuilder.week() else app.digestBuilder.day()
                }.getOrElse { e -> "Сводка не собралась: ${e.message}" }
                busy = false
                preview = text
            }
        }
    }

    PaperCard(label = "сводка для чата") {
        PaperHint(
            "Таймшит, тренировки, подходы с прошлым разом, здоровье, зарядка и " +
                "еда — одним текстом. Отправляешь Клоду в чат, он советует."
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { build(false) }, enabled = !busy) { Text("За день") }
            Button(onClick = { build(true) }, enabled = !busy) { Text("За неделю") }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (preview.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Сводка", preview)
                    )
                    Feedback.toast(app, "Сводка в буфере — вставляй в чат")
                }) { Text("В буфер") }
                OutlinedButton(onClick = {
                    app.appScope.launch {
                        val intent = app.digestBuilder.shareIntent(preview, "pravka-svodka.txt")
                        runCatching {
                            context.startActivity(
                                android.content.Intent.createChooser(intent, "Сводка")
                            )
                        }
                    }
                }) { Text("Файлом") }
                OutlinedButton(onClick = { preview = "" }) { Text("Скрыть") }
            }
            Spacer(Modifier.height(10.dp))
            PaperHint("${preview.length} знаков")
            Spacer(Modifier.height(6.dp))
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

@Composable
internal fun BodySportSettings(app: PravkaApp) {
    // Группа «Тело» открывается и без захода во вкладку «Спорт» — сторы могли
    // быть не прочитаны, и счётчик «ждут отправки» показал бы ноль неправдой.
    LaunchedEffect(Unit) {
        runCatching { app.sportStore.load() }
        runCatching { app.strengthStore.load() }
        runCatching { app.planStore.load() }
        runCatching { app.exerciseBook.load() }
    }
    val store = app.sportStore
    val profile by store.profileFlow.collectAsState()
    val days by app.settings.sportDaysFlow.collectAsState(initial = 120)
    val restSec by app.settings.restSecFlow.collectAsState(initial = 90)
    val talks by store.talksFlow.collectAsState()
    val rules by app.planStore.rulesFlow.collectAsState()
    val notionToken by app.settings.notionTokenFlow.collectAsState(initial = "")
    val sessions by app.strengthStore.sessionsFlow.collectAsState()
    var sliderDays by remember(days) { mutableStateOf(days.toFloat()) }
    var sliderRest by remember(restSec) { mutableStateOf(restSec.toFloat()) }
    val notionHub by app.settings.notionHubFlow.collectAsState(
        initial = ru.zf.pravka.data.Settings.NOTION_HUB_DEFAULT
    )
    var tokenDraft by remember(notionToken) { mutableStateOf(notionToken) }
    var hubDraft by remember(notionHub) { mutableStateOf(notionHub) }
    var syncingPlan by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    // Ошибка Notion живёт не во Flow, а полем в синхронизаторе: перечитываем её
    // после каждой попытки, иначе на экране останется прошлая.
    var notionError by remember { mutableStateOf(app.notionPlanSync.lastError()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    PaperCard(label = "правила блока из notion") {
        if (rules.known) {
            Text(rules.blockTitle.ifBlank { "Блок" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val lines = buildList {
                if (rules.runHrCeiling > 0) add("Потолок лёгкого бега" to "${rules.runHrCeiling}")
                if (rules.greyZoneLow > 0 && rules.greyZoneHigh > 0) {
                    add("Серая зона" to "${rules.greyZoneLow}–${rules.greyZoneHigh}")
                }
                if (rules.cadenceMin > 0) add("Каденс" to "${rules.cadenceMin}+")
                if (rules.runsPerWeekMax > 0) add("Пробежек в неделю" to "не больше ${rules.runsPerWeekMax}")
                if (rules.hoursBetweenRuns > 0) add("Между пробежками" to "${rules.hoursBetweenRuns} ч")
                if (rules.rampNeedsPositiveTsb) add("Тест" to "только на плюсовом TSB")
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
            if (rules.cancelOrder.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                PaperHint("Отмена: ${rules.cancelOrder}")
            }
            if (rules.weekPlan.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Штатная неделя", style = MaterialTheme.typography.labelMedium)
                for ((day, session) in rules.weekPlan) {
                    Text("$day — $session", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            PaperHint("Правится в Notion — здесь только видно. Читается раз в сутки.")
        } else {
            PaperHint(
                "Правила ещё не приезжали. Нужны две вещи: внутренний токен " +
                    "интеграции Notion (только чтение) и доступ этой интеграции " +
                    "к странице «Тело: велоформа и сила» — страница → «…» в правом " +
                    "верхнем углу → Connections → выбрать интеграцию. Доступ " +
                    "наследуется вниз: страницу блока отдельно открывать не надо."
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            label = { Text("Токен Notion (ntn_…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = hubDraft,
            onValueChange = { hubDraft = it },
            label = { Text("Страница-хаб: ссылка или id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PaperHint(
            "Можно вставить прямо ссылку из «Copy link» — id из неё вынется сам. " +
                "Хаб читается и сам: светофор колена, правило отмены и потолок бега " +
                "лежат на нём, а не на странице блока."
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                app.appScope.launch {
                    app.settings.setNotionToken(tokenDraft.trim())
                    app.settings.setNotionHub(hubDraft.trim())
                    Feedback.toast(app, "Сохранено")
                }
            }) { Text("Сохранить") }
            OutlinedButton(
                onClick = {
                    if (!syncingPlan) {
                        syncingPlan = true
                        app.appScope.launch {
                            val outcome = app.planSync.refresh(force = true)
                            syncingPlan = false
                            notionError = app.notionPlanSync.lastError()
                            Feedback.toast(
                                app,
                                when {
                                    outcome.error.isNotBlank() -> outcome.error
                                    outcome.events && outcome.rules -> "План и правила обновлены"
                                    outcome.events -> "Календарь обновлён, правила — нет"
                                    outcome.rules -> "Правила обновлены, календарь — нет"
                                    else -> "Ничего не обновилось"
                                },
                                long = true,
                            )
                        }
                    }
                },
                enabled = !syncingPlan,
            ) { Text(if (syncingPlan) "Читаю…" else "Прочитать план") }
        }
        Spacer(Modifier.height(6.dp))
        // «Проверить доступ» — не про удобство, а про то, чтобы ошибка была
        // читаемой. «Не нашлось страниц» ничего не говорит о том, что чинить;
        // построчный отчёт («токен принят, хаб отдал 404») указывает пальцем.
        OutlinedButton(
            onClick = {
                if (!checking) {
                    checking = true
                    app.appScope.launch {
                        // Токен и хаб из полей — иначе проверяется прошлое,
                        // а владелец смотрит на новое.
                        app.settings.setNotionToken(tokenDraft.trim())
                        app.settings.setNotionHub(hubDraft.trim())
                        report = runCatching { app.notionPlanSync.diagnose() }
                            .getOrElse { e -> "Сорвалось: ${e.message ?: e.javaClass.simpleName}" }
                        checking = false
                    }
                }
            },
            enabled = !checking,
        ) { Text(if (checking) "Проверяю…" else "Проверить доступ") }
        if (report.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                report,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        if (notionError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                notionError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (rules.sourceText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            PaperHint("Прочитано и лежит на телефоне: ${rules.sourceText.length} зн.")
        }
    }

    Spacer(Modifier.height(14.dp))

    PaperCard(label = "настройки спорта") {
        Text("Отдых между подходами: ${sliderRest.toInt()} сек", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = sliderRest,
            onValueChange = { sliderRest = it },
            onValueChangeFinished = {
                app.appScope.launch { app.settings.setRestSec(sliderRest.toInt()) }
            },
            valueRange = 30f..240f,
        )
        PaperHint("Чип «⏱» в карточке и на плашке запускает именно этот отдых.")
        Spacer(Modifier.height(12.dp))
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
        val pending = sessions.count { it.pendingSync }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Подходы в intervals", style = MaterialTheme.typography.bodyMedium)
                PaperHint(
                    if (pending == 0) "всё уехало"
                    else "$pending ждут активность от часов"
                )
            }
            OutlinedButton(onClick = {
                app.appScope.launch {
                    val outcome = app.strengthEngine.syncPending(force = true)
                    Feedback.toast(
                        app,
                        "Отправлено ${outcome.sent}, ждут ${outcome.waiting}" +
                            (if (outcome.failed > 0) ", не вышло ${outcome.failed}" else ""),
                        long = true,
                    )
                }
            }) { Text("Донести") }
        }
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
                    "нажми «Обновить» на вкладке «Спорт»."
            )
        }
        Spacer(Modifier.height(12.dp))
        PaperHint(
            "Справочник: ${app.exerciseBook.all.size} упражнений, снимок " +
                app.exerciseBook.snapshotDate() + ". Лежит файлом в приложении — " +
                "работает без интернета. Правится в Notion, пересобирается скриптом."
        )
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
