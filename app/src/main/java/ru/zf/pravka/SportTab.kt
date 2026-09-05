package ru.zf.pravka

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
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
import ru.zf.pravka.core.PlanLine
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
import ru.zf.pravka.trigger.startRestFromTab

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
private val bookStampFormat = SimpleDateFormat("d.MM HH:mm", Locale("ru"))

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
    var commenting by remember { mutableStateOf<SportStore.Workout?>(null) }
    // Вопрос тренеру про конкретное упражнение: заголовок задачи и карточка
    // справочника уезжают фокусом, ответ стримится в диалоге. Третий элемент —
    // «спросить сразу»: кнопка «Как делать?» шлёт фиксированный вопрос без
    // редактирования.
    var coachTopic by remember { mutableStateOf<Triple<String, ExerciseBook.Exercise?, Boolean>?>(null) }
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
        // Календарь старше суток — подтянуть; свежее не трогаем: «поменял
        // план в чате — сам и обновлю» (кнопка «Обновить» идёт в сеть сразу).
        runCatching { app.planSync.refreshEventsIfStale() }
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
    // Задачи дня. ПЕРВОИСТОЧНИК — нумерованный список события в календаре:
    // владелец пушит его из чата, и в нём живут замены недели («мосты вместо
    // RDL» на спина-протоколе). Статический блок справочника — только запасной
    // вариант, когда события без списка: показать по блоку RDL, который на
    // этой неделе запрещён, значило бы спорить с его же планом.
    //
    // Силовых в день бывает ДВЕ — гиря и сразу за ней турник с прессом (план
    // v3), и у каждой свой список. Раньше чек-лист строился только по главной,
    // а вторая жила строкой «Ещё сегодня» без единой галочки: владелец видел
    // половину своих задач. Теперь группа на сессию, главная первой.
    //
    // Чек-лист упражнений — ТОЛЬКО у силовых. У Zwift и бега нумерованные
    // строки — это подсказки по посадке и пульсу («руки на верх руля»,
    // «каждые 15 мин из седла»): галочки на них не нужны, а стемминг на
    // такой прозе матчил суперсет рук. Они показываются в карточке дня.
    val bookVersion by app.exerciseBook.versionFlow.collectAsState()
    val dayGroups = remember(planDays, sessions, today, bookVersion) {
        app.planStore.strengthOf(today).map { session ->
            val lines = session.plannedLines()
            val tasks = if (lines.isNotEmpty()) {
                PlanLine.parseAll(lines, app.exerciseBook).map { line ->
                    DayTask(
                        id = line.id,
                        name = line.canonical,
                        dose = line.dose,
                        title = line.title,
                        exercise = line.exercise,
                        lastTime = line.exercise?.let { app.strengthStore.lastTime(it.id, today)?.second },
                        history = line.exercise?.let { app.strengthStore.history(it.id, 10) }.orEmpty(),
                        hint = line.note,
                    )
                }
            } else {
                val block = session.block
                if (block.isBlank()) emptyList()
                else app.strengthEngine.lastTimeFor(block, today).map { (exercise, last) ->
                    DayTask(
                        id = exercise.id,
                        name = exercise.name,
                        dose = exercise.scheme,
                        title = exercise.name + (if (exercise.scheme.isBlank()) "" else " — ${exercise.scheme}"),
                        exercise = exercise,
                        lastTime = last,
                        history = app.strengthStore.history(exercise.id, 10),
                    )
                }
            }
            session to tasks
        }.filter { it.second.isNotEmpty() }
    }
    // Все задачи дня одним списком: по нему считается «всё отмечено → сделано».
    val dayTasks = remember(dayGroups) { dayGroups.flatMap { it.second } }
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
                // onDelta приносит НАКОПЛЕННЫЙ текст (см. executeStreaming), не
                // приращение: += склеивал повторы в кашу.
                app.sportCoach.ask(text) { grown -> streaming = grown }
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
                    val comment = mainPlan.noteBefore()
                    if (comment.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            comment.take(400),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // Список упражнений силовой здесь не дублируем: он живёт
                    // ниже карточками-задачами. А у кардио нумерованные строки —
                    // подсказки дня («руки на верх руля», «каждые 15 мин из
                    // седла»), их место здесь, без галочек.
                    if (!mainPlan.strength) {
                        val cues = mainPlan.plannedLines()
                        if (cues.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            // Подсказки кардио — его же слова, не имена из
                            // справочника: матчить «нагрудный ремень» незачем.
                            for (cue in PlanLine.parseAll(cues, app.exerciseBook)) {
                                Text(
                                    "· " + (if (cue.dose.isBlank()) cue.name else "${cue.name} — ${cue.dose}"),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (cue.note.isNotBlank()) {
                                    Text(
                                        "   " + cue.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    // Его текст ПОСЛЕ списка — «Отдых минута. Задача дня — не
                    // устать, а понять, как гиря лежит в руках». Раньше терялся.
                    val afterNote = mainPlan.noteAfter()
                    if (afterNote.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            afterNote.take(400),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Второстепенное дня — одной строкой, по часам: турник
                    // после гири, Zwift днём. Зарядка — в своей карточке.
                    val extras = app.planStore.dayOf(today)
                        .filterNot { it.eventId == mainPlan.eventId || it.charger }
                        .sortedBy { it.time.ifBlank { "99:99" } }
                    if (extras.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        PaperHint(
                            "Ещё сегодня: " + extras.joinToString("; ") { e ->
                                (if (e.time.isNotBlank()) e.time + " " else "") +
                                    e.name + (if (e.minutes > 0) " · ${e.minutes} мин" else "")
                            }
                        )
                    }
                    // Кардио закрывают часы: активность нужного типа приехала —
                    // задача дня выполнена фактом, никакую кнопку жать не надо.
                    val arrivedToday = if (!mainPlan.strength) {
                        workouts.firstOrNull {
                            dayKey(it.start) == today &&
                                (it.type.equals(mainPlan.type, true) ||
                                    (mainPlan.type.equals("Ride", true) &&
                                        it.type.equals("VirtualRide", true)))
                        }
                    } else null
                    if (arrivedToday != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "✓ Приехала с часов: " + buildList {
                                if (arrivedToday.km >= 0.1) add(fmt1(arrivedToday.km) + " км")
                                add("${arrivedToday.minutes} мин")
                                if (arrivedToday.avgHr > 0) add("пульс ${arrivedToday.avgHr}")
                            }.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = toneColor(1),
                        )
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

        // ---- Зарядка: чек-лист на утро ----
        item {
            val chargerPlan = remember(planDays, today) { app.planStore.chargerOf(today) }
            ZaryadkaChecklist(app, gtgToday, chargerPlan)
        }

        // ---- Упражнения дня с прошлым разом: группа на каждую силовую ----
        for ((session, tasks) in dayGroups) {
            item(key = "pl" + session.eventId) {
                val checkedCount = tasks.count { todaySession?.isChecked(it.id) == true }
                val label = if (dayGroups.size == 1) "упражнения" else {
                    (if (session.time.isNotBlank()) session.time + " · " else "") +
                        session.shortName.lowercase()
                }
                PaperLabel("$label · $checkedCount из ${tasks.size}")
                // У второй сессии дня комментарий владельца иначе не виден:
                // карточка «сегодня» показывает только главную.
                if (session.eventId != mainPlan?.eventId) {
                    val note = listOf(session.noteBefore(), session.noteAfter())
                        .filter { it.isNotBlank() }.joinToString(" ")
                    if (note.isNotBlank()) PaperHint(note.take(300))
                }
            }
            items(tasks.size, key = { i -> "px" + session.eventId + "-" + tasks[i].id }) { i ->
                val task = tasks[i]
                val doneToday = todaySession?.exercises?.firstOrNull { it.exerciseId == task.id }
                PlannedExerciseCard(
                    title = task.title,
                    hint = task.hint,
                    exercise = task.exercise,
                    lastTime = task.lastTime,
                    doneToday = doneToday,
                    history = task.history,
                    restSec = restSec,
                    onAskCoach = { auto -> coachTopic = Triple(task.title, task.exercise, auto) },
                    checked = todaySession?.isChecked(task.id) == true,
                    onCheck = {
                        app.appScope.launch {
                            val before = app.strengthStore.sessionsOn(today).firstOrNull()?.done == true
                            val updated = app.strengthEngine.toggleChecked(
                                task.id, today, allIds = dayTasks.map { it.id },
                            )
                            // Отметил последнее — сессия закрылась сама:
                            // осталось спросить самочувствие, как у «Сделано».
                            if (updated != null && updated.done && !before) {
                                feelDialog = updated.id
                            }
                        }
                    },
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

        // Итог силовой словами — рядом с её упражнениями, а не в другой
        // вкладке: «гоблет четыре по десять шестнадцать, последний тяжело,
        // очень доволен» — числа в журнал, комментарий в заметку сессии.
        if (dayTasks.isNotEmpty()) {
            item {
                PaperCard {
                    BodyTalkBox(
                        app = app,
                        hint = "Подходы и как прошло — словами…",
                        whereSaid = "в карточке силовой",
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
                val bestPull = app.strengthStore.bestPullups()
                if ((gtgToday?.pullups ?: 0) > 0) {
                    Text(
                        "Подтягивания сегодня: ${gtgToday?.pullups}" +
                            (if (bestPull == gtgToday?.pullups) " — рекорд" else ""),
                        style = MaterialTheme.typography.titleMedium,
                        color = toneColor(1),
                    )
                    Spacer(Modifier.height(8.dp))
                }
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
                        if (bestPull > 0) "Подтягивания" else "Негативы",
                        if (bestPull > 0) "$bestPull"
                        else if ((gtgToday?.negatives ?: 0) > 0) "${gtgToday?.negatives}" else "—",
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

        // ---- План недели: что впереди, с его же комментариями ----
        item { WeekPlanCard(app, planDays) }

        // ---- Цели октября ----
        item { GoalsCard(app, health, gtgDays, rules) }

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
                        onComment = { commenting = w },
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
    commenting?.let { workout ->
        WorkoutCommentDialog(app, workout, onClose = { commenting = null })
    }
    coachTopic?.let { (title, exercise, auto) ->
        CoachDialog(app, title, exercise, autoAsk = auto, onClose = { coachTopic = null })
    }
}

/**
 * Тренер в кармане упражнения: «как правильно вис?» — и Опус отвечает, видя
 * ЕГО справочник этого движения, строку плана дня, спина-протокол недели и все
 * данные. Ответ стримится сюда же и остаётся в «прошлых разборах».
 */
@Composable
private fun CoachDialog(
    app: PravkaApp,
    taskTitle: String,
    exercise: ExerciseBook.Exercise?,
    autoAsk: Boolean = false,
    onClose: () -> Unit,
) {
    val shortName = exercise?.name ?: taskTitle.take(40)
    var question by remember {
        mutableStateOf(if (autoAsk) "Как правильно делать: $shortName?" else "")
    }
    var streaming by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val send: () -> Unit = {
        if (!busy && question.isNotBlank()) {
            busy = true
            streaming = ""
            app.appScope.launch {
                val answer = runCatching {
                    app.sportCoach.askTrainer(
                        question = question,
                        focus = SportCoach.exerciseFocus(exercise, taskTitle),
                        // Накопленный текст, не приращение — иначе каша с повторами.
                    ) { grown -> streaming = grown }
                }.getOrElse { e ->
                    SportCoach.Answer("", 0.0, e.message ?: "не вышло")
                }
                busy = false
                if (answer.error.isNotBlank()) {
                    streaming = answer.error
                } else if (answer.text.isNotBlank()) {
                    streaming = answer.text
                }
            }
        }
    }
    // «Как делать?» не заставляет редактировать вопрос: диалог открылся —
    // ответ уже пошёл.
    LaunchedEffect(Unit) { if (autoAsk) send() }
    AlertDialog(
        // Закрывается ВСЕГДА: запрос доживёт в app-scope и ляжет в «прошлые
        // разборы», а запертый диалог — это владелец без телефона.
        onDismissRequest = onClose,
        title = { Text(shortName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text(if (autoAsk) "Вопрос тренеру" else "Спроси про это упражнение…") },
                    minLines = 1,
                    maxLines = 3,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                PaperHint(
                    "Тренер-консультант видит карточку движения из твоего " +
                        "справочника, план дня и правила недели."
                )
                if (streaming.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(streaming, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = send,
                enabled = !busy && question.isNotBlank(),
            ) { Text(if (busy) "Думает…" else "Спросить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}

/**
 * Комментарий к тренировке с часов: сказанное вклеивается своим блоком в
 * описание этой активности в intervals и остаётся сырой записью на телефоне —
 * попадёт и в сводку, и в CSV всей жизни.
 */
@Composable
private fun WorkoutCommentDialog(
    app: PravkaApp,
    workout: SportStore.Workout,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(SportCoach.sportName(workout.type) + " · " + fmtDay(workout.start)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Как прошло — своими словами") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                PaperHint(
                    "Уедет в описание этой активности в intervals (свой блок, " +
                        "твоё там не трогается) и останется на телефоне."
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!busy && text.isNotBlank()) {
                        busy = true
                        app.appScope.launch {
                            val outcome = app.strengthEngine.commentWorkout(workout.id, text)
                            busy = false
                            Feedback.toast(
                                app,
                                outcome.fold(
                                    { "✓ Уехало в intervals" },
                                    { e -> (e.message ?: "Не уехало") + " — текст сохранён" },
                                ),
                                long = true,
                            )
                            onClose()
                        }
                    }
                },
                enabled = !busy && text.isNotBlank(),
            ) { Text(if (busy) "Отправляю…" else "Отправить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

private fun fmtDay(ts: Long): String = workoutDayFormat.format(Date(ts))

/**
 * Упражнение дня: схема из справочника, прошлый раз, что уже сделано сегодня —
 * и техника с ошибками по тапу. Прошлый раз здесь главное: прогрессивная
 * перегрузка это «сегодня чуть больше», и «чуть больше чего» надо видеть В
 * МОМЕНТ подхода, а не вспоминать.
 */
@Composable
private fun PlannedExerciseCard(
    title: String,
    hint: String = "",
    exercise: ExerciseBook.Exercise?,
    lastTime: StrengthStore.ExerciseLog?,
    doneToday: StrengthStore.ExerciseLog?,
    history: List<Pair<String, StrengthStore.ExerciseLog>> = emptyList(),
    restSec: Int,
    checked: Boolean = false,
    onCheck: (() -> Unit)? = null,
    onAskCoach: ((Boolean) -> Unit)? = null,
    onRest: (Int) -> Unit,
) {
    var open by remember(title) { mutableStateOf(false) }
    val ticked = checked || doneToday != null
    PaperCard {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onCheck != null) {
                // «Ок» на задачу: галочка — «сделал по схеме», числа поверх
                // неё наговариваются как обычно и весят больше галочки.
                CheckDot(ticked, onCheck)
                Spacer(Modifier.width(10.dp))
            }
            Box(
                Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .background(
                        if (ticked) toneColor(1) else MaterialTheme.colorScheme.outlineVariant,
                        MaterialTheme.shapes.extraSmall,
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // Мелкой строкой — его же пояснение из плана («зачем движение»);
                // нет пояснения — имя из справочника, если строка зовёт его иначе.
                val hintText = when {
                    hint.isNotBlank() -> hint
                    exercise == null -> ""
                    !title.contains(exercise.name.substringBefore(" (").take(8), ignoreCase = true) ->
                        exercise.name
                    else -> exercise.gear.firstOrNull().orEmpty()
                }
                if (hintText.isNotBlank()) PaperHint(hintText)
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
        if (exercise == null) {
            PaperHint("Движение недели — техники в справочнике нет, спроси тренера ниже.")
            Spacer(Modifier.height(8.dp))
        }
        if (doneToday != null && lastTime != null) {
            Text(
                "Прошлый раз: " + lastTime.compact(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (history.size >= 2) {
            // Прогрессия — столбики объёма по сессиям. «Мышцы растут от
            // прогрессии, не от усталости» — его принцип №2, вот она глазом.
            Text("Прогрессия · объём по сессиям", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            ProgressBars(history)
            Spacer(Modifier.height(8.dp))
        }
        if (exercise != null && exercise.how.isNotBlank()) {
            Text("Как делать", style = MaterialTheme.typography.labelMedium)
            Text(exercise.how, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        if (exercise != null && exercise.mistakes.isNotBlank()) {
            Text("Главные ошибки", style = MaterialTheme.typography.labelMedium)
            Text(
                exercise.mistakes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (exercise != null && exercise.progression.isNotBlank() && exercise.progression != "—") {
            Text("Прогрессия", style = MaterialTheme.typography.labelMedium)
            Text(exercise.progression, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (seconds in listOf(60, restSec, 120).distinct()) {
                OutlinedButton(onClick = { onRest(seconds) }) { Text("⏱ $seconds") }
            }
            if (onAskCoach != null) {
                // «Как делать?» — один тап, фиксированный вопрос; «Спросить» —
                // своё, с пустым полем. Обе — лёгкий тренер-консультант.
                OutlinedButton(onClick = { onAskCoach(true) }) { Text("Как делать?") }
                OutlinedButton(onClick = { onAskCoach(false) }) { Text("Спросить") }
            }
        }
        if (exercise != null && exercise.videoQuery.isNotBlank()) {
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
    var pullups by remember { mutableStateOf("") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = scapular,
                        onValueChange = { scapular = it.filter { c -> c.isDigit() }.take(3) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Лопаточные") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = pullups,
                        onValueChange = { pullups = it.filter { c -> c.isDigit() }.take(3) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Подтягивания") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
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
                        pullups = pullups.toIntOrNull(),
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

/**
 * Одна задача дня: строка плана + узнанное по ней упражнение справочника.
 * Разбор строки («Название доза: пояснение», « — », скобки) — в
 * `core/PlanLine.kt`, один на вкладку и на досыл в intervals.
 */
private data class DayTask(
    val id: String,
    val title: String,
    val exercise: ExerciseBook.Exercise?,
    val lastTime: StrengthStore.ExerciseLog?,
    val history: List<Pair<String, StrengthStore.ExerciseLog>>,
    /** Его пояснение из строки плана: зачем движение, как пошло. */
    val hint: String = "",
    /** Имя как в Notion (или как в плане, если движение не узнано). */
    val name: String = "",
    /** Доза из строки плана: «2×6», «×2 до предела», «~3 мин». */
    val dose: String = "",
)

private val CTL_COLOR = Color(0xFF0E7490)
private val ATL_COLOR = Color(0xFFEA580C)
private val RUN_COLOR = Color(0xFF16A34A)
private val RIDE_COLOR = Color(0xFF2563EB)

/**
 * Тренированность и усталость одной картинкой. Рисуем руками по Canvas, а не
 * библиотекой: две ломаные - это двадцать строк, а любая графическая
 * библиотека это ещё одна зависимость в приложении, где их пять.
 */
/** Круглая галочка чек-листа: пустой круг → зелёная точка с ✓. */
@Composable
private fun CheckDot(ticked: Boolean, onCheck: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .background(
                if (ticked) toneColor(1) else MaterialTheme.colorScheme.surface,
                CircleShape,
            )
            .border(
                width = 2.dp,
                color = if (ticked) toneColor(1) else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onCheck),
        contentAlignment = Alignment.Center,
    ) {
        if (ticked) {
            Text("✓", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Чек-лист зарядки: что именно делать сегодня утром, по упражнению на строку,
 * с «ок» на каждом. Список — блок «Зарядка» справочника (правится в Notion,
 * пересобирается скриптом). Отметил все — день закрывается сам: charged
 * встаёт, цепочка растёт, отдельную кнопку жать не надо.
 *
 * Это ответ на «у меня должна быть задача на день, и мне надо понимать, что
 * именно делать в зарядке»: схема — в строке, техника — по тапу.
 */
@Composable
private fun ZaryadkaChecklist(
    app: PravkaApp,
    gtgToday: StrengthStore.GtgDay?,
    chargerPlan: PlanStore.PlanDay? = null,
) {
    var loaded by remember { mutableStateOf(app.exerciseBook.loaded) }
    LaunchedEffect(Unit) {
        if (!loaded) {
            app.exerciseBook.load()
            loaded = true
        }
    }
    // Список зарядки — из СОБЫТИЯ календаря, ровно как у силовой: владелец
    // правит дозы и состав неделя к неделе («9 пунктов, дозы конечные»), и
    // показывать вместо этого статический блок из 15 позиций значило бы
    // спорить с его же планом. Справочник — запасной вариант и источник
    // техники для узнанных строк. Список в одну строку («Дача. 1. Суставы.
    // 2. Осанка…») — тоже список: см. PlanDay.plannedLines().
    val bookVersion by app.exerciseBook.versionFlow.collectAsState()
    val planLines = remember(chargerPlan) { chargerPlan?.plannedLines().orEmpty() }
    val items = remember(planLines, loaded, bookVersion) {
        if (!loaded) emptyList()
        else if (planLines.isNotEmpty()) {
            // Имя — каноническое из справочника (как в Notion): так же
            // подпишется строка в комментарии intervals, глазам и чату
            // не приходится сводить два названия одного движения.
            PlanLine.parseAll(planLines, app.exerciseBook, suffix = "-z").map { line ->
                DayTask(
                    id = line.id,
                    name = line.canonical,
                    dose = line.dose,
                    title = line.title,
                    exercise = line.exercise,
                    lastTime = null,
                    history = emptyList(),
                    hint = line.note,
                )
            }
        } else {
            app.exerciseBook.ofBlock("Зарядка").map { exercise ->
                DayTask(
                    id = exercise.id,
                    name = exercise.name,
                    dose = exercise.scheme,
                    title = exercise.name,
                    exercise = exercise,
                    lastTime = null,
                    history = emptyList(),
                )
            }
        }
    }
    if (items.isEmpty()) return
    val allIds = remember(items) { items.map { it.id } }
    val doneIds = gtgToday?.doneIds ?: emptyList()
    val charged = gtgToday?.charged == true
    var openId by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<Triple<String, ExerciseBook.Exercise?, Boolean>?>(null) }
    var noting by remember { mutableStateOf<DayTask?>(null) }

    PaperCard(
        label = "зарядка сегодня",
        trailing = {
            PaperHint(
                if (charged) "✓ сделана"
                else "${allIds.count { it in doneIds }} из ${items.size}"
            )
        },
    ) {
        // Заметка дня из календаря: «сокращённая версия», «дачная — турник
        // заменяется резинкой», «добавка недели — bird dog». Владелец пушит
        // её из чата вместе с планом, и меняется она чаще справочника.
        // Нумерованный список из заметки не показываем: он и есть чек-лист
        // ниже, дублировать его текстом сверху — читать одно дважды.
        val dayNote = chargerPlan?.noteBefore().orEmpty()
        if (dayNote.isNotBlank()) {
            Text(dayNote.take(400), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
        }
        for (task in items) {
            val ticked = task.id in doneIds || charged
            val report = gtgToday?.items?.firstOrNull { it.id == task.id }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (report != null && report.status != "ok") {
                    // «Не смог» и «частично» — не галочка и не пустота: видно
                    // без раскрытия. Тап открывает тот же ✎-отчёт.
                    Text(
                        if (report.status == "no") "✗" else "◐",
                        style = MaterialTheme.typography.titleMedium,
                        color = toneColor(if (report.status == "no") -2 else -1),
                        modifier = Modifier
                            .clickable { noting = task }
                            .padding(horizontal = 5.dp),
                    )
                } else {
                    CheckDot(ticked) {
                        app.appScope.launch {
                            app.bodyEngine.toggleZaryadka(task.id, allIds = allIds)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { openId = if (openId == task.id) null else task.id }
                ) {
                    // Сверху — имя как в Notion, под ним доза и его пояснение:
                    // «Осанка: подбородок назад · скольжения по стене · грудь в
                    // проёме» с дозой в той же строке не читалось бы вовсе.
                    Text(
                        task.name.ifBlank { task.title },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ticked) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    val exercise = task.exercise
                    // Доза («2×6», «×2 до предела») и его пояснение («секунды в
                    // заметку») — мелкой строкой; запасному списку без строк
                    // плана — схема из справочника.
                    val detail = listOf(task.dose, task.hint).filter { it.isNotBlank() }.joinToString(" · ")
                    when {
                        detail.isNotBlank() -> PaperHint(detail)
                        exercise != null && exercise.scheme.isNotBlank() -> PaperHint(exercise.scheme)
                    }
                    if (report != null &&
                        (report.fact.isNotBlank() || report.note.isNotBlank() || report.status != "ok")
                    ) {
                        PaperHint("✎ " + report.brief().substringAfter(": "))
                    }
                    if (openId == task.id) {
                        if (exercise != null && exercise.how.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(exercise.how, style = MaterialTheme.typography.bodySmall)
                        }
                        if (exercise != null && exercise.mistakes.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                exercise.mistakes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { asking = Triple(task.title, exercise, true) }) {
                                Text("Как делать?")
                            }
                            OutlinedButton(onClick = { asking = Triple(task.title, exercise, false) }) {
                                Text("Спросить")
                            }
                        }
                    }
                }
                // «Планка — 40 сек»: секунды засечь нечем — intervals не
                // передаёт Garmin шаги силовых, часы на зарядке пишут только
                // пульс. Таймер поэтому здесь, в одном тапе от строки.
                val holdSec = HOLD_SEC.find(task.title)?.groupValues?.get(1)?.toIntOrNull()
                if (holdSec != null && !ticked) {
                    Text(
                        "⏱$holdSec",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                Feedback.toast(app, "$holdSec сек пошли — считает кнопка «Т»")
                                ru.zf.pravka.trigger.PravkaAccessibilityService.instance
                                    ?.startRestFromTab(holdSec)
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
                IconButton(onClick = { noting = task }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Комментарий к упражнению",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Его текст ПОСЛЕ списка: «Минимум на плохое утро: 1 + 3 + шесть
        // отжиманий», «Затем прогулка с семьёй». Раньше не показывался вовсе.
        val afterNote = chargerPlan?.noteAfter().orEmpty()
        if (afterNote.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                afterNote.take(400),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Самочувствие — в нативное поле feel зарядки-активности: intervals
        // сам рисует по нему кривую, из слова «ужас» её не построишь.
        if (charged || doneIds.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PaperHint("самочувствие")
                for (v in 1..5) {
                    FilterChip(
                        selected = gtgToday?.feel == v,
                        onClick = {
                            app.appScope.launch { app.bodyEngine.putGtgNumbers(feel = v) }
                        },
                        label = { Text("$v") },
                    )
                }
            }
            PaperHint("1 отлично — 5 развалина (шкала intervals)")
        }
        Spacer(Modifier.height(4.dp))
        PaperHint(
            if (charged) "Числа виса и негативов — в карточке зарядки ниже."
            else "Тап по названию — техника, ✎ — как пошло. Отметишь всё — зарядка закроется сама."
        )
        // Накопленные за день пометки — здесь же, чтобы было видно, что уедет
        // в intervals вместе с итогом.
        val accumNote = gtgToday?.note.orEmpty()
        if (accumNote.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Заметки дня: $accumNote",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        // Итог словами вместо тапов: «всё сделал, чувствовал себя хорошо,
        // подтягивания два». Разбирает тот же роутер, что у кнопки «Т»:
        // charged, числа, заметка — и всё это уедет в intervals и в Дневник.
        BodyTalkBox(
            app = app,
            hint = "Итог: всё сделал, вис 40, подтягивания 2…",
            whereSaid = "в карточке зарядки",
        )
    }
    asking?.let { (title, exercise, auto) ->
        CoachDialog(app, title, exercise, autoAsk = auto, onClose = { asking = null })
    }
    noting?.let { task ->
        ZaryadkaReportDialog(
            app = app,
            task = task,
            existing = gtgToday?.items?.firstOrNull { it.id == task.id },
            allIds = allIds,
            onClose = { noting = null },
        )
    }
}

/** «2×30 сек», «40 сек каждая нога» — число прямо перед «сек». */
private val HOLD_SEC = Regex("""(\d+)\s*сек""")

/**
 * Отчёт по одному пункту зарядки — строка «таблицы выполнения»: статус
 * (сделал/частично/не смог), факт и ощущение. Копится по дням в GtgDay.items —
 * из этого потом графики; сегодня — строка «факт/план» в комментарии
 * intervals, по которой чат правит следующие дни.
 */
@Composable
private fun ZaryadkaReportDialog(
    app: PravkaApp,
    task: DayTask,
    existing: StrengthStore.GtgItem?,
    allIds: List<String>,
    onClose: () -> Unit,
) {
    val name = task.name.ifBlank { task.title.substringBefore(" — ") }
    val plan = task.dose.ifBlank { task.title.substringAfter(" — ", "") }
    var status by remember { mutableStateOf(existing?.status ?: "ok") }
    var fact by remember { mutableStateOf(existing?.fact.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                if (plan.isNotBlank()) PaperHint("план: $plan")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((key, label) in listOf("ok" to "Сделал", "part" to "Частично", "no" to "Не смог")) {
                        FilterChip(
                            selected = status == key,
                            onClick = { status = key },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = fact,
                    onValueChange = { fact = it },
                    label = { Text("Факт: «10», «12 из 15»…") },
                    minLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Ощущение: «тяжело», «легко»…") },
                    minLines = 1,
                    maxLines = 3,
                )
                Spacer(Modifier.height(6.dp))
                PaperHint("Уедет строкой «факт/план» в комментарий intervals — по ней правится план.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.appScope.launch {
                    app.bodyEngine.reportZaryadka(
                        StrengthStore.GtgItem(
                            id = task.id,
                            name = name,
                            plan = plan,
                            status = status,
                            fact = fact.trim(),
                            note = note.trim(),
                        ),
                        allIds = allIds,
                    )
                    Feedback.toast(app, "✓ В таблице дня — уедет в intervals")
                }
                onClose()
            }) { Text("Записать") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

/**
 * Поле «скажи итог словами» — одно на зарядку и силовую. Текст идёт через тот
 * же роутер, что кнопка «Т»: сырая запись на диск навсегда, разбор в числа,
 * дороги в intervals и Дневник — все прежние. Поле просто ближе, чем кнопка.
 */
@Composable
private fun BodyTalkBox(app: PravkaApp, hint: String, whereSaid: String) {
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            label = { Text(hint) },
            minLines = 1,
            maxLines = 3,
            enabled = !busy,
        )
        IconButton(
            onClick = {
                val text = draft.trim()
                if (text.isNotBlank() && !busy) {
                    busy = true
                    draft = ""
                    app.appScope.launch {
                        val result = app.bodyEngine.hear(text, source = "text", whereSaid = whereSaid)
                        busy = false
                        Feedback.toast(
                            app,
                            result.fold({ "✓ " + it.headline() }, { e -> e.message ?: "Не разобрал" }),
                            long = true,
                        )
                    }
                }
            },
            enabled = !busy,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Send, contentDescription = "Записать")
        }
    }
}

/** Столбики объёма упражнения по сессиям, старые слева. */
@Composable
private fun ProgressBars(history: List<Pair<String, StrengthStore.ExerciseLog>>) {
    val ascending = history.reversed()
    val values = ascending.map { it.second.volume }
    val peak = values.maxOrNull()?.coerceAtLeast(1.0) ?: return
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for ((i, v) in values.withIndex()) {
            val grew = i > 0 && v > values[i - 1] + 0.01
            Box(
                Modifier
                    .weight(1f)
                    .height(((v / peak) * 44).dp.coerceAtLeast(3.dp))
                    .background(
                        if (grew) toneColor(1) else CTL_COLOR,
                        MaterialTheme.shapes.extraSmall,
                    )
            )
        }
    }
    Spacer(Modifier.height(2.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        PaperHint(shortDate(ascending.first().first))
        val first = values.first()
        val last = values.last()
        if (first > 0) {
            val pct = Math.round((last - first) / first * 100)
            PaperHint((if (pct >= 0) "+" else "") + "$pct% за ${values.size} сессий")
        }
        PaperHint(shortDate(ascending.last().first))
    }
}

private fun shortDate(date: String): String =
    date.split('-').let { if (it.size == 3) "${it[2]}.${it[1]}" else date }

/**
 * Неделя вперёд, как её запушил владелец: день → сессии → его же комментарии
 * из описаний событий. Свёрнута в строки; тап по дню раскрывает тексты. Всё
 * из кэша плана — офлайн, без сети и токенов.
 */
@Composable
private fun WeekPlanCard(app: PravkaApp, planDays: List<PlanStore.PlanDay>) {
    val today = remember { dayKey(System.currentTimeMillis()) }
    val upcoming = remember(planDays, today) {
        app.planStore.upcoming(7).groupBy { it.date }.toList().sortedBy { it.first }
    }
    if (upcoming.isEmpty()) return
    var openDate by remember { mutableStateOf<String?>(null) }
    PaperCard(label = "план недели") {
        for ((date, events) in upcoming) {
            // По часам: зарядка утром, гиря, турник, Zwift днём. Турник — не
            // зарядка (см. PlanDay.charger), поэтому он здесь, среди сессий.
            val main = events.filterNot { it.charger }.sortedBy { it.time.ifBlank { "99:99" } }
            val charger = events.filter { it.charger }.minByOrNull { it.time.ifBlank { "99:99" } }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { openDate = if (openDate == date) null else date }
                    .padding(vertical = 6.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        weekDayTitle(date),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (date == today) FontWeight.Bold else FontWeight.SemiBold,
                        modifier = Modifier.width(74.dp),
                    )
                    Text(
                        main.joinToString("; ") { e ->
                            e.name + (if (e.minutes > 0) " · ${e.minutes}м" else "")
                        }.ifBlank { charger?.name ?: "—" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (openDate == date) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (charger != null && main.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        PaperHint("+зарядка")
                    }
                }
                if (openDate == date) {
                    for (e in main) {
                        // Его комментарий — текст описания вокруг нумерованного
                        // списка, без структурных Warmup-строк для Garmin.
                        val comment = listOf(e.noteBefore(), e.noteAfter())
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                        if (comment.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                (if (e.time.isNotBlank()) e.time + " · " else "") + e.name,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                comment.take(600),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        PaperHint("Тап по дню — его комментарии из календаря. Правится в чате с Клодом.")
    }
}

private val weekDayTitleFormat = SimpleDateFormat("EE d.MM", Locale("ru"))
private fun weekDayTitle(date: String): String = runCatching {
    weekDayTitleFormat.format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!)
}.getOrDefault(date)

/**
 * Дорога к его трём целям октября — из дорожной карты в Notion: вес к 80,
 * первое подтягивание (вис и негативы), честный рамп-тест. Всё считается на
 * телефоне из уже имеющихся данных; чего нет — про то молчим.
 */
@Composable
private fun GoalsCard(
    app: PravkaApp,
    health: List<SportStore.Health>,
    gtgDays: List<StrengthStore.GtgDay>,
    rules: PlanStore.Rules,
) {
    val goalWeight by app.settings.goalWeightFlow.collectAsState(
        initial = ru.zf.pravka.data.Settings.GOAL_WEIGHT_DEFAULT
    )
    val now = remember { System.currentTimeMillis() }

    // Вес: скорость за последний месяц и честный прогноз по ней.
    val weights = remember(health) { health.filter { it.weightKg > 0 } }
    val current = weights.firstOrNull()?.weightKg ?: 0.0
    val monthAgoKey = remember(now) { dayKey(now - 28L * 86_400_000L) }
    val past = remember(weights, monthAgoKey) {
        weights.firstOrNull { it.date <= monthAgoKey } ?: weights.lastOrNull()
    }
    // Вис и негативы: последние две недели против двух до них.
    val fortnight = remember(now) { dayKey(now - 14L * 86_400_000L) }
    val monthKey = remember(now) { dayKey(now - 28L * 86_400_000L) }
    val hangNow = gtgDays.filter { it.date >= fortnight }.maxOfOrNull { it.hangSec } ?: 0
    val hangPrev = gtgDays.filter { it.date in monthKey..fortnight }.maxOfOrNull { it.hangSec } ?: 0
    val negBest = gtgDays.maxOfOrNull { it.negatives } ?: 0
    val bestHang = remember(gtgDays) { app.strengthStore.bestHang() }
    val tsb = health.firstOrNull()?.tsb ?: 0.0

    val deadline = "2026-10-31"
    val today = dayKey(now)
    val weeksLeft = remember(today) {
        val days = runCatching {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            ((f.parse(deadline)!!.time - f.parse(today)!!.time) / 86_400_000L).toInt()
        }.getOrDefault(-1)
        if (days >= 0) (days + 6) / 7 else -1
    }

    if (current <= 0 && bestHang == 0 && negBest == 0) return

    PaperCard(
        label = "цели октября",
        trailing = { if (weeksLeft >= 0) PaperHint("осталось $weeksLeft нед.") },
    ) {
        // №1-бис: вес к 80 — половина пути уже пройдена (было 93).
        if (current > 0) {
            val rate: Double? = past?.takeIf { it.date < today }?.let { p ->
                val days = runCatching {
                    val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    ((f.parse(today)!!.time - f.parse(p.date)!!.time) / 86_400_000L).toInt()
                }.getOrDefault(0)
                if (days >= 7) (current - p.weightKg) / (days / 7.0) else null
            }
            GoalRowLine(
                title = "Вес → $goalWeight кг",
                value = fmt1(current),
                tone = if (current <= goalWeight) 1 else 0,
            )
            when {
                current <= goalWeight -> PaperHint("Дошёл. Дальше — удержать.")
                rate == null -> PaperHint("Скорость станет видна, когда наберётся месяц замеров.")
                rate < -0.05 -> {
                    val weeks = Math.round((current - goalWeight) / -rate).toInt()
                    PaperHint(
                        fmt1(-rate) + " кг/нед — так к цели через $weeks нед." +
                            (if (weeksLeft in 0 until weeks) " (позже октября)" else "")
                    )
                    if (rate < -0.8) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Быстрее 0,8 кг/нед — твоё же правило: добавь углеводный слот.",
                            style = MaterialTheme.typography.bodySmall,
                            color = toneColor(-1),
                        )
                    }
                }
                else -> PaperHint("За месяц вес стоит — дефицита нет.")
            }
            Spacer(Modifier.height(10.dp))
        }

        // №2: первое подтягивание — вис и негативы, а когда случилось — салют.
        val bestPull = remember(gtgDays) { app.strengthStore.bestPullups() }
        if (bestPull > 0) {
            GoalRowLine(
                title = "Первое подтягивание",
                value = "есть ✓ · лучшее $bestPull",
                tone = 1,
            )
            PaperHint("Цель №2 взята. Дальше — «отжимания 20+, гиря легка, разница в зеркале».")
            Spacer(Modifier.height(10.dp))
        } else if (bestHang > 0 || negBest > 0) {
            GoalRowLine(
                title = "Путь к подтягиванию",
                value = if (bestHang > 0) "вис $bestHang сек" else "негативы $negBest",
                tone = if (hangNow > hangPrev && hangPrev > 0) 1 else 0,
            )
            PaperHint(
                buildString {
                    if (hangNow > 0) {
                        append("Вис за две недели: $hangNow сек")
                        if (hangPrev > 0) {
                            val d = hangNow - hangPrev
                            append(" (")
                            append(if (d >= 0) "+" else "")
                            append("$d к прошлым двум")
                            append(")")
                        }
                    } else {
                        append("Виса за две недели не записано")
                    }
                    if (negBest > 0) append(" · негативы лучшее $negBest")
                }
            )
            Spacer(Modifier.height(10.dp))
        }

        // №1: честный FTP — тест только на плюсовом TSB (его правило).
        if (rules.rampNeedsPositiveTsb && health.isNotEmpty()) {
            GoalRowLine(
                title = "Рамп-тест",
                value = "TSB " + signed(Math.round(tsb).toInt()),
                tone = if (tsb >= 0) 1 else -1,
            )
            PaperHint(
                if (tsb >= 0) "Форма в плюсе — тест можно планировать."
                else "Твоё правило: тест только на плюсовом TSB. Пока рано."
            )
        }
    }
}

@Composable
private fun GoalRowLine(title: String, value: String, tone: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = toneColor(tone))
    }
    Spacer(Modifier.height(2.dp))
}

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
    onComment: (() -> Unit)? = null,
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
        if (onComment != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onComment) { Text("Комментарий в intervals") }
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
    // Быстрая пробежка НЕ криминал: по его 80/20 одна качественная в неделю
    // запланирована. Криминал — серая зона (ни легко, ни быстро) и «лёгкая»,
    // уползшая выше потолка. Выше серой зоны — нейтрально: судит план.
    return when {
        hr <= rules.runHrCeiling -> "под потолком ${rules.runHrCeiling} ✓" to 1
        rules.greyZoneLow > 0 && rules.greyZoneHigh > 0 && hr in rules.greyZoneLow..rules.greyZoneHigh ->
            "серая зона ${rules.greyZoneLow}–${rules.greyZoneHigh} — пульс $hr" to -2
        rules.greyZoneHigh in 1 until hr -> "быстрая: пульс $hr — ок, если это плановая качественная" to 0
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
        Spacer(Modifier.height(8.dp))
        // «Фактически вся моя жизнь, всеобъемлющий файл» — его словами.
        // Таймшит, еда, тренировки, силовые, зарядка и комментарии, строка на
        // событие, хронологически, за всю глубину хранения.
        @Composable
        fun lifeCsvButton(label: String) {
            OutlinedButton(onClick = {
                app.appScope.launch {
                    val intent = runCatching { app.digestBuilder.lifeCsvIntent() }
                        .getOrNull()
                    if (intent == null) {
                        Feedback.toast(app, "Не собрался — посмотри Логи")
                    } else {
                        runCatching {
                            context.startActivity(
                                android.content.Intent.createChooser(intent, "CSV всей жизни")
                            )
                        }
                    }
                }
            }) { Text(label) }
        }
        Text("CSV всей жизни", style = MaterialTheme.typography.titleSmall)
        lifeCsvButton("Выгрузить CSV")
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

    // Справочник упражнений: живой из базы Notion «Упражнения», файл сборки —
    // семя и запас без сети. Отсюда видно, ЧЕМ сейчас матчатся строки плана —
    // и почему «Суставы сверху вниз» вдруг без техники.
    val bookVersion by app.exerciseBook.versionFlow.collectAsState()
    var syncingBook by remember { mutableStateOf(false) }
    var bookError by remember { mutableStateOf(app.notionExerciseSync.lastError()) }
    PaperCard(label = "справочник упражнений") {
        val book = app.exerciseBook
        val zaryadka = remember(bookVersion) { book.ofBlock("Зарядка") }
        Text(
            if (book.fromNotion) "Из Notion, прочитан " + bookStampFormat.format(Date(book.fetchedAt))
            else "Файл сборки от ${book.snapshotDate().ifBlank { "—" }} — Notion ещё не читался",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        PaperHint("Движений: ${book.all.size} · в блоке «Зарядка»: ${zaryadka.size}")
        if (zaryadka.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                zaryadka.joinToString(" · ") { it.name.substringBefore(":") },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (!syncingBook) {
                    syncingBook = true
                    app.appScope.launch {
                        app.settings.setNotionToken(tokenDraft.trim())
                        app.settings.setNotionHub(hubDraft.trim())
                        val ok = runCatching { app.notionExerciseSync.refresh(force = true) }
                            .getOrDefault(false)
                        syncingBook = false
                        bookError = if (ok) "" else app.notionExerciseSync.lastError()
                        Feedback.toast(
                            app,
                            if (ok) "Справочник обновлён: ${app.exerciseBook.all.size} движений"
                            else bookError.ifBlank { "Справочник не обновился" },
                            long = !ok,
                        )
                    }
                }
            },
            enabled = !syncingBook,
        ) { Text(if (syncingBook) "Читаю…" else "Перечитать из Notion") }
        if (bookError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                bookError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(6.dp))
        PaperHint(
            "Читается раз в сутки вместе с планом и по кнопке «Обновить» во " +
                "вкладке. Без сети — последнее прочитанное, без токена — файл сборки. " +
                "Голосовые имена движений — из файла сборки (tools/gen_reference.py)."
        )
    }
    Spacer(Modifier.height(12.dp))

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
                if (rules.testPrep.isNotBlank()) add("Перед тестом" to rules.testPrep.take(60))
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
                            bookError = app.notionExerciseSync.lastError()
                            val fresh = listOfNotNull(
                                if (outcome.events) "календарь" else null,
                                if (outcome.rules) "правила" else null,
                                if (outcome.exercises) "справочник" else null,
                            )
                            Feedback.toast(
                                app,
                                when {
                                    outcome.error.isNotBlank() -> outcome.error
                                    fresh.isEmpty() -> "Ничего не обновилось"
                                    else -> "Обновлено: " + fresh.joinToString(", ")
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
                        report += "\n" + runCatching { app.notionExerciseSync.diagnose() }
                            .getOrElse { e -> "Справочник: сорвалось — ${e.message ?: e.javaClass.simpleName}" }
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

        Spacer(Modifier.height(14.dp))
        val diary by app.settings.notionDiaryFlow.collectAsState(initial = true)
        var pushingDiary by remember { mutableStateOf(false) }
        var diaryStatus by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Автогалочки в «Дневник»", style = MaterialTheme.typography.bodyMedium)
                PaperHint(
                    "Зарядка, «сделано», feel, колено, вес и еда сами уезжают в " +
                        "твою базу Notion. Галочки только ставятся, тексты пишутся " +
                        "лишь в пустые ячейки — твоё руками написанное не трогается. " +
                        "Интеграции нужны права на запись."
                )
            }
            Switch(checked = diary, onCheckedChange = { v ->
                app.appScope.launch { app.settings.setNotionDiary(v) }
            })
        }
        if (diary) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    if (!pushingDiary) {
                        pushingDiary = true
                        app.appScope.launch {
                            val done = runCatching { app.notionDiarySync.sync(force = true) }
                                .getOrDefault(false)
                            pushingDiary = false
                            diaryStatus = when {
                                app.notionDiarySync.lastError().isNotBlank() ->
                                    app.notionDiarySync.lastError()
                                done -> "Уехало: ${app.notionDiarySync.lastPushed()}"
                                else -> "Нечего отправлять или уже уехало"
                            }
                        }
                    }
                },
                enabled = !pushingDiary,
            ) { Text(if (pushingDiary) "Отправляю…" else "Отправить в Дневник сейчас") }
            if (diaryStatus.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                PaperHint(diaryStatus)
            }
        }

        // ---- Вся жизнь в Notion: лента, еда, спорт, дни, паттерны ----
        Spacer(Modifier.height(14.dp))
        val life by app.settings.notionLifeFlow.collectAsState(initial = true)
        val lifeHub by app.settings.notionLifeHubFlow.collectAsState(
            initial = ru.zf.pravka.data.NotionLifeSync.HUB_DEFAULT
        )
        var lifeHubDraft by remember(lifeHub) { mutableStateOf(lifeHub) }
        var pushingLife by remember { mutableStateOf(false) }
        val lifeStatus by app.notionLifeSync.statusFlow.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Вся жизнь — в «Правка: разборы»", style = MaterialTheme.typography.bodyMedium)
                PaperHint(
                    "Раз в час лента Засечки, еда, тренировки, силовые, зарядка, " +
                        "телефон и форма по дням уезжают строками в свои базы, " +
                        "паттерны ночного поиска и твои вердикты по ним — в " +
                        "«Паттерны» и «Подтверждения». CSV больше не нужен: разбор " +
                        "читает Notion. Чужие колонки («Дети дома», «Якорь утра», " +
                        "статусы паттернов) не трогаются."
                )
            }
            Switch(checked = life, onCheckedChange = { v ->
                app.appScope.launch { app.settings.setNotionLife(v) }
            })
        }
        if (life) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = lifeHubDraft,
                onValueChange = { lifeHubDraft = it },
                label = { Text("Хаб «Правка: разборы»: ссылка или id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PaperHint(
                "Интеграции (тот же токен, что выше) нужен доступ к этой странице: " +
                    "… → Connections. Базы под ней находятся по названиям сами."
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    app.appScope.launch {
                        app.settings.setNotionLifeHub(lifeHubDraft.trim())
                        Feedback.toast(app, "Сохранено")
                    }
                }) { Text("Сохранить") }
                OutlinedButton(
                    onClick = {
                        if (!pushingLife) {
                            pushingLife = true
                            app.appScope.launch {
                                runCatching { app.notionLifeSync.sync(force = true) }
                                pushingLife = false
                                val err = app.notionLifeSync.lastError()
                                if (err.isNotBlank()) Feedback.toast(app, err, long = true)
                            }
                        }
                    },
                    enabled = !pushingLife,
                ) { Text(if (pushingLife) "Отправляю…" else "Синхронизировать сейчас") }
            }
            if (lifeStatus.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                PaperHint(lifeStatus)
            }
            TextButton(onClick = {
                app.appScope.launch {
                    app.notionLifeSync.resetMaps()
                    Feedback.toast(app, "Карта страниц сброшена — следующий синк сверится с базами заново")
                }
            }) { Text("Сбросить карту страниц") }
            PaperHint(
                "Первый заезд — вся история, шесть сотен строк: Notion пускает три " +
                    "запроса в секунду, поэтому займёт около часа и пойдёт пачками на " +
                    "каждом тике. Дальше — по паре десятков правок в час."
            )
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
        val goalWeight by app.settings.goalWeightFlow.collectAsState(
            initial = ru.zf.pravka.data.Settings.GOAL_WEIGHT_DEFAULT
        )
        var goalSlider by remember(goalWeight) { mutableStateOf(goalWeight.toFloat()) }
        Text("Цель веса: ${goalSlider.toInt()} кг", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = goalSlider,
            onValueChange = { goalSlider = it },
            onValueChangeFinished = {
                app.appScope.launch { app.settings.setGoalWeight(goalSlider.toInt()) }
            },
            valueRange = 65f..95f,
        )
        PaperHint("К ней меряет дорогу карточка «Цели октября».")
        Spacer(Modifier.height(12.dp))
        val notify by app.settings.sportNotifyFlow.collectAsState(initial = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Тренировка приехала — уведомление", style = MaterialTheme.typography.bodyMedium)
                PaperHint(
                    "Как только часы отдали тренировку: вердикт по твоим правилам " +
                        "и кнопки самочувствия 2/3/4 прямо в шторке."
                )
            }
            Switch(checked = notify, onCheckedChange = { v ->
                app.appScope.launch { app.settings.setSportNotify(v) }
            })
        }
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
