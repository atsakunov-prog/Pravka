package ru.zf.pravka

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import ru.zf.pravka.ui.combinedTap
import ru.zf.pravka.ui.glass
import ru.zf.pravka.ui.bottomFade
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.IconAction
import ru.zf.pravka.ui.IconActionRow
import ru.zf.pravka.ui.InfoButton
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperLabel
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperSlider
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.Segments
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.VoiceInput
import ru.zf.pravka.ui.scrollFade
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
// С 24.09.2026 это три части чипами сверху: «Сегодня» (1–3), «Путь» (форма,
// КПД, неделя, цели, вес) и «Журнал» (тренировки, вопрос, разборы,
// неразобранное). Пятнадцать плашек одной лентой утром прокручивались мимо
// нужного; теперь то, что смотришь каждый день, не делит экран с тем, что
// открываешь раз в неделю. Порядок внутри частей — прежний.
//
// Всё, кроме вопроса, рисуется из кэша на диске и считается на телефоне —
// вкладка открывается мгновенно и работает в самолёте, а он тренируется на
// даче каждое воскресенье.

private val talkTimeFormat = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
private val workoutDayFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
private val workoutTimeFormat = SimpleDateFormat("HH:mm", Locale.US)
private val bookStampFormat = SimpleDateFormat("d.MM HH:mm", Locale("ru"))

/** Части вкладки — чипами сверху; номер части хранится в rememberSaveable. */
private val PARTS = listOf("Сегодня", "Путь", "Журнал")
private const val PART_TODAY = 0
private const val PART_PATH = 1
private const val PART_JOURNAL = 2

/** Цвет тона сводки. */
@Composable
private fun toneColor(tone: Int): Color = when (tone) {
    // Правка 4.0: светофорных цветов нет (DESIGN §4) — «хорошо» ярким тоном
    // режима, «плохо» — тёплым предупреждением, «ровно» — подписью.
    -2, -1 -> ru.zf.pravka.ui.Ink.Warn
    1 -> ru.zf.pravka.ui.LocalMode.current.value
    else -> ru.zf.pravka.ui.LocalMode.current.label
}

@Composable
internal fun SportTab(app: PravkaApp) {
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
    // Часть вкладки переживает поворот и уход на соседнюю вкладку: открыл
    // «Журнал», сходил в Засечку — вернулся в «Журнал», а не в «Сегодня».
    var part by rememberSaveable { mutableStateOf(PART_TODAY) }
    // Своя прокрутка у каждой части: вернулся в «Журнал» — там же, где был,
    // а переключился из середины длинного журнала — «Сегодня» с начала.
    val listStates = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState())

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

    val listState = listStates[part]

    // Правка 4.0 (`screens/09`): части вкладки сегментами сверху, строка
    // «Как тренировка?» — внизу, как у всех режимов (DESIGN §11.4). Тот же
    // роутер, что у «Т»: зарядка, подходы, ощущение — он сам поймёт.
    var sayDraft by remember { mutableStateOf("") }
    var sayBusy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxWidth()) {
        Segments(
            options = PARTS,
            selected = part,
            onSelect = { part = it },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        )
        // Разворот (DESIGN §13): две колонки — слева главное части, справа
        // остальное. [side]: 0 — всё одной лентой, 1 — левая, 2 — правая.
        val wide = ru.zf.pravka.ui.twoPane()
        val rightState = rememberLazyListState()
        val body: androidx.compose.foundation.lazy.LazyListScope.(Int) -> Unit = { side ->
            val left = side != 2
            val right = side != 1
            if (part == PART_TODAY) {
                if (left) {
                // ---- Готовность: светофор дня точками, почему и четыре числа ----
                item(key = "ready") { ReadinessCard(verdict, health.firstOrNull(), app.icuSportSync.lastError()) }

                // ---- Тренировка дня: план, тренер, упражнения, «Сделано» ----
                item(key = "plan") {
                    val mode = ru.zf.pravka.ui.LocalMode.current
                    val ty = ru.zf.pravka.ui.LocalPravkaType.current
                    val mainTasks = dayGroups.firstOrNull { it.first.eventId == mainPlan?.eventId }?.second.orEmpty()
                    Column(Modifier.fillMaxWidth()) {
                        ru.zf.pravka.ui.SectionHeader(
                            "тренировка" + (mainPlan?.time?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            trailing = "из intervals.icu",
                            info = "План" to "План приезжает из календаря intervals — ты его туда пушишь, " +
                                "когда собираешь блок. Правила блока читаются из Notion.",
                            action = {
                                if (syncing) {
                                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = mode.tint)
                                    }
                                } else {
                                    GlyphButton(Glyphs.Refresh, "Обновить", onClick = refresh, tint = mode.label)
                                }
                            },
                        )
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(22.dp), mode.glass)
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                        ) {
                            if (mainPlan == null) {
                                Text("В календаре intervals на сегодня ничего нет.", style = ty.bodyL, color = ru.zf.pravka.ui.Ink.Text)
                            } else {
                                Text(mainPlan.name, style = ty.titleM, color = ru.zf.pravka.ui.Ink.TextStrong)
                                // planLine — «название · параметры»; название уже выше.
                                val meta = listOf(
                                    verdict.planLine.substringAfter(mainPlan.name).trim(' ', '·'),
                                    if (mainTasks.isNotEmpty()) "${mainTasks.size} упр." else "",
                                ).filter { it.isNotBlank() }.joinToString(" · ")
                                if (meta.isNotBlank()) Text(meta, style = ty.meta, color = mode.meta, modifier = Modifier.padding(top = 2.dp))
                                // Его слова к сессии — до и после списка — плашкой «Тренер».
                                val coach = listOf(mainPlan.noteBefore(), mainPlan.noteAfter())
                                    .filter { it.isNotBlank() }.joinToString("\n")
                                if (coach.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    ru.zf.pravka.ui.CoachNote(coach.take(600))
                                }
                                // У кардио нумерованные строки — подсказки дня, без галочек.
                                if (!mainPlan.strength) {
                                    val cues = mainPlan.plannedLines()
                                    if (cues.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        for (cue in PlanLine.parseAll(cues, app.exerciseBook)) {
                                            ru.zf.pravka.ui.ExerciseRow(cue.name, cue.note.takeIf { it.isNotBlank() }, cue.dose.takeIf { it.isNotBlank() })
                                        }
                                    }
                                }
                                // Упражнения силовой — строками в той же плашке (`screens/09`).
                                if (mainTasks.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    mainTasks.forEachIndexed { i, task ->
                                        if (i > 0) ru.zf.pravka.ui.Hairline()
                                        ExerciseLine(app, task, todaySession, today, dayTasks, restSec,
                                            onAskCoach = { auto -> coachTopic = Triple(task.title, task.exercise, auto) },
                                            onFeel = { feelDialog = it })
                                    }
                                }
                                // Второстепенное дня — одной строкой, по часам.
                                val extras = app.planStore.dayOf(today)
                                    .filterNot { it.eventId == mainPlan.eventId || it.charger }
                                    .filterNot { e -> dayGroups.any { it.first.eventId == e.eventId } }
                                    .sortedBy { it.time.ifBlank { "99:99" } }
                                if (extras.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Ещё сегодня: " + extras.joinToString("; ") { e ->
                                            (if (e.time.isNotBlank()) e.time + " " else "") +
                                                e.name + (if (e.minutes > 0) " · ${ru.zf.pravka.core.Fmt.dur(e.minutes)}" else "")
                                        },
                                        style = ty.meta,
                                        color = mode.meta,
                                    )
                                }
                                // Кардио закрывают часы: активность нужного типа приехала — сделано фактом.
                                val arrivedToday = if (!mainPlan.strength) {
                                    workouts.firstOrNull {
                                        dayKey(it.start) == today &&
                                            (it.type.equals(mainPlan.type, true) ||
                                                (mainPlan.type.equals("Ride", true) && it.type.equals("VirtualRide", true)))
                                    }
                                } else null
                                if (arrivedToday != null) {
                                    Spacer(Modifier.height(8.dp))
                                    DoneLine(
                                        "Приехала с часов: " + buildList {
                                            if (arrivedToday.km >= 0.1) add(fmt1(arrivedToday.km) + " км")
                                            add(ru.zf.pravka.core.Fmt.dur(arrivedToday.minutes.toInt()))
                                            if (arrivedToday.avgHr > 0) add("пульс ${arrivedToday.avgHr}")
                                        }.joinToString(", ")
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                // «Сделано» — главная клавиша справа, под большим пальцем;
                                // «Самочувствие» — контуром слева.
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (todaySession?.done == true) DoneLine("Сделано")
                                        if (todaySession != null && todaySession.feel in 1..5) {
                                            Text("самочувствие ${todaySession.feel}/5", style = ty.meta, color = mode.meta)
                                        }
                                        if (todaySession != null && todaySession.feel !in 1..5) {
                                            ru.zf.pravka.ui.GhostKey("Самочувствие", { feelDialog = todaySession.id }, icon = Glyphs.Heart)
                                        }
                                    }
                                    if (todaySession?.done != true) {
                                        ru.zf.pravka.ui.PrimaryKey(
                                            "Сделано",
                                            onClick = {
                                                app.appScope.launch {
                                                    val session = app.strengthEngine.markDone(today, mainPlan.minutes)
                                                    feelDialog = session.id
                                                }
                                            },
                                            icon = Glyphs.Check,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- Вторая силовая дня (турник с прессом после гири) — своей плашкой ----
                for ((session, tasks) in dayGroups) {
                    if (session.eventId == mainPlan?.eventId) continue
                    item(key = "pl" + session.eventId) {
                        val mode = ru.zf.pravka.ui.LocalMode.current
                        val ty = ru.zf.pravka.ui.LocalPravkaType.current
                        val checkedCount = tasks.count { todaySession?.isChecked(it.id) == true }
                        Column(Modifier.fillMaxWidth()) {
                            ru.zf.pravka.ui.SectionHeader(
                                (if (session.time.isNotBlank()) session.time + " · " else "") + session.shortName.lowercase(),
                                trailing = "$checkedCount из ${tasks.size}",
                            )
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .glass(RoundedCornerShape(22.dp), mode.glass)
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                            ) {
                                val note = listOf(session.noteBefore(), session.noteAfter()).filter { it.isNotBlank() }.joinToString(" ")
                                if (note.isNotBlank()) Text(note.take(300), style = ty.meta, color = mode.meta, modifier = Modifier.padding(vertical = 6.dp))
                                tasks.forEachIndexed { i, task ->
                                    if (i > 0) ru.zf.pravka.ui.Hairline()
                                    ExerciseLine(app, task, todaySession, today, dayTasks, restSec,
                                        onAskCoach = { auto -> coachTopic = Triple(task.title, task.exercise, auto) },
                                        onFeel = { feelDialog = it })
                                }
                            }
                        }
                    }
                }

                // ---- Силовая сегодня: что записано и куда уехало ----
                if (todaySession != null && (!todaySession.empty || todaySession.done)) {
                    item { StrengthTodayCard(app, todaySession, onFeel = { feelDialog = todaySession.id }) }
                }

                }
                if (right) {
                // ---- Сделано сегодня: что приехало с часов ----
                val doneToday = workouts.filter { dayKey(it.start) == today }.sortedBy { it.start }
                if (doneToday.isNotEmpty()) {
                    item(key = "done") { DoneTodayCard(doneToday) }
                }

                // ---- Зарядка: чипами, тап — отметить, долгое — техника и заметка ----
                item(key = "zaryadka") {
                    val chargerPlan = remember(planDays, today) { app.planStore.chargerOf(today) }
                    ZaryadkaChecklist(app, gtgToday, chargerPlan, today)
                }

                // ---- Путь к первому подтягиванию ----
                item(key = "pullup") { PullupCard(app, gtgToday, streak, onRecord = { gtgDialog = true }) }

                // ---- Неделя: сделано против плана столбиками ----
                item(key = "week") { WeekBarsCard(app, workouts, planDays) }
                }
            }

            if (part == PART_PATH) {
                if (left) {
                // Пустая часть читается поломкой: до первой выгрузки intervals здесь
                // ничего бы не нарисовалось — скажем, чего ждём.
                if (health.isEmpty() && workouts.isEmpty()) {
                    item {
                        PaperCard {
                            Text(
                                "Графики появятся, когда intervals.icu пришлёт первые дни и тренировки.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                // ---- Тренированность графиком ----
                if (health.size >= 4) {
                    item {
                        PaperCard(
                            label = "тренированность и усталость",
                            info = "Тренированность — накопленная нагрузка за шесть недель, " +
                                "усталость — за неделю. Их разница и есть форма: минус — " +
                                "работаешь в долг, плюс — свежий.",
                        ) {
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

                }
                if (right) {
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
                }
            }

            if (part == PART_JOURNAL) {
                if (left) {
                // ---- Тренировки ----
                // Период — чипами ПОД подписью, а не в её строке: четыре чипа рядом с
                // «тренировки · 12» на внешнем экране Fold не вставали (24.09.2026).
                item {
                    Column(Modifier.fillMaxWidth()) {
                        PaperLabel("тренировки · ${shownWorkouts.size}")
                        ChipRow {
                            for (d in listOf(7, 14, 30, 90)) {
                                PaperChip("$d дн.", selected = days == d, onClick = { days = d })
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
                                // Не пояснение, а что чинить — поэтому на виду. Ключ с
                                // 24.09 живёт в «Подключениях», не в «Засечке».
                                Spacer(Modifier.height(6.dp))
                                PaperHint(
                                    "Тренировки приезжают из intervals.icu. Проверь athlete id " +
                                        "и ключ («Настройки» → «Подключения» → «intervals.icu»), " +
                                        "потом нажми «Обновить» в части «Сегодня»."
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

                }
                if (right) {
                // ---- Вопрос ----
                item {
                    PaperCard(
                        label = "спросить про тренировки",
                        info = "Опус видит твои тренировки, сон, HRV, форму, еду за неделю и " +
                            "чем ты был занят по ленте. Пустой вопрос = «как у меня дела».",
                    ) {
                        // Строка ввода — общая на все вкладки. Микрофона у этого поля
                        // не было, и строка без него. «Отправить» горит и на пустом
                        // поле: пустой вопрос — это «как у меня дела».
                        VoiceInput(
                            value = question,
                            onValueChange = { question = it },
                            placeholder = "Стоит ли сегодня бежать интервалы?",
                            onSend = ask,
                            sendEnabled = !asking,
                            enabled = !asking,
                            busy = asking,
                        )
                        if (asking && streaming.isBlank()) {
                            Spacer(Modifier.height(10.dp))
                            BusyLine("Думает…")
                        }
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
                                PaperButton(
                                    if (busy) "Разбираю…" else "Разобрать заново",
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
                                    icon = Glyphs.Refresh,
                                    enabled = !busy,
                                )
                            }
                        }
                    }
                }
                }
            }

            // Настройки режима — за шестерёнкой в шапке вкладки.
        }
        if (wide) {
            Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
                for ((side, st) in listOf(1 to listState, 2 to rightState)) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxHeight().bottomFade().scrollFade(st),
                        state = st,
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 110.dp),
                        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
                    ) { body(side) }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).bottomFade().scrollFade(listState),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
            ) { body(0) }
        }
    }
    // Микрофона у этой строки нет, как не было у поля: голосом про тело говорят
    // гарнитуре (развилка Засечки), здесь — набор.
    ru.zf.pravka.ui.SayBar(
        value = sayDraft,
        onValueChange = { sayDraft = it },
        placeholder = ru.zf.pravka.core.PillHint.say(app.profileStore.flow.collectAsState().value?.name, "как тренировка?"),
        onSend = {
            val text = sayDraft.trim()
            if (text.isNotBlank() && !sayBusy) {
                sayBusy = true
                sayDraft = ""
                app.appScope.launch {
                    val result = app.bodyEngine.hear(text, source = "text", whereSaid = "")
                    sayBusy = false
                    Feedback.toast(app, result.fold({ "✓ " + it.headline() }, { e -> e.message ?: "Не разобрал" }), long = true)
                }
            }
        },
        sendEnabled = !sayBusy && sayDraft.isNotBlank(),
        enabled = !sayBusy,
        maxLines = 3,
        busy = sayBusy,
        modifier = Modifier
            // На развороте строка — под правой колонкой, как в макете 08.
            .align(if (ru.zf.pravka.ui.twoPane()) Alignment.BottomEnd else Alignment.BottomCenter)
            .then(if (ru.zf.pravka.ui.twoPane()) Modifier.fillMaxWidth(0.5f) else Modifier)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
    )
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
    // Лист, а не окно по центру (24.09.2026): ответ длинный и растёт потоком,
    // ему нужна прокрутка во всю высоту, а строка вопроса — внизу, над
    // клавиатурой, как в любом разговоре. «Закрыть» ушло в крестик и свайп.
    PaperSheet(
        // Закрывается ВСЕГДА: запрос доживёт в app-scope и ляжет в «прошлые
        // разборы», а запертый диалог — это владелец без телефона.
        onDismiss = onClose,
        title = shortName,
        icon = Glyphs.Spark,
        subtitle = "тренер-консультант",
        footer = {
            VoiceInput(
                value = question,
                onValueChange = { question = it },
                placeholder = if (autoAsk) "Вопрос тренеру" else "Спроси про это упражнение…",
                onSend = send,
                modifier = Modifier.weight(1f),
                sendEnabled = !busy && question.isNotBlank(),
                enabled = !busy,
                maxLines = 3,
                busy = busy,
            )
        },
    ) {
        when {
            streaming.isNotBlank() -> Text(streaming, style = MaterialTheme.typography.bodyMedium)
            busy -> BusyLine("Думает…")
            // Пока ответа нет, лист не пустой: сказано, что тренер видит.
            else -> PaperHint(
                "Тренер-консультант видит карточку движения из твоего " +
                    "справочника, план дня и правила недели."
            )
        }
    }
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
    PaperAlert(
        onDismiss = onClose,
        title = "Комментарий в intervals",
        icon = Glyphs.Note,
        subtitle = SportCoach.sportName(workout.type) + " · " + fmtDay(workout.start),
        confirm = SheetAction(
            text = if (busy) "Отправляю…" else "Отправить",
            icon = Glyphs.Send,
            enabled = !busy && text.isNotBlank(),
        ) {
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
    ) {
        PaperField(
            value = text,
            onValueChange = { text = it },
            label = "Как прошло — своими словами",
            singleLine = false,
            minLines = 2,
            maxLines = 5,
        )
        // Куда уедет сказанное — не справка, а последствие кнопки: на виду.
        PaperHint(
            "Уедет в описание этой активности в intervals (свой блок, " +
                "твоё там не трогается) и останется на телефоне."
        )
    }
}

private fun fmtDay(ts: Long): String = workoutDayFormat.format(Date(ts))

/**
 * Упражнение дня: схема из справочника, прошлый раз, что уже сделано сегодня —
 * и техника с ошибками по тапу. Прошлый раз здесь главное: прогрессивная
 * перегрузка это «сегодня чуть больше», и «чуть больше чего» надо видеть В
 * МОМЕНТ подхода, а не вспоминать.
 */
@Composable
private fun ExerciseLine(
    app: PravkaApp,
    task: DayTask,
    todaySession: StrengthStore.Session?,
    today: String,
    dayTasks: List<DayTask>,
    restSec: Int,
    onAskCoach: (Boolean) -> Unit,
    onFeel: (Long) -> Unit,
) {
    val doneToday = todaySession?.exercises?.firstOrNull { it.exerciseId == task.id }
    PlannedExerciseCard(
        title = task.title,
        name = task.name,
        dose = task.dose,
        hint = task.hint,
        exercise = task.exercise,
        lastTime = task.lastTime,
        doneToday = doneToday,
        history = task.history,
        restSec = restSec,
        onAskCoach = onAskCoach,
        checked = todaySession?.isChecked(task.id) == true,
        onCheck = {
            app.appScope.launch {
                val before = app.strengthStore.sessionsOn(today).firstOrNull()?.done == true
                val updated = app.strengthEngine.toggleChecked(task.id, today, allIds = dayTasks.map { it.id })
                // Отметил последнее — сессия закрылась сама: осталось спросить самочувствие.
                if (updated != null && updated.done && !before) onFeel(updated.id)
            }
        },
        onRest = { seconds ->
            Feedback.toast(app, "Отдых $seconds сек — кнопка «Т» считает")
            ru.zf.pravka.trigger.PravkaAccessibilityService.instance?.startRestFromTab(seconds)
        },
    )
}

/**
 * Упражнение дня строкой в плашке тренировки (Правка 4.0, `screens/09`):
 * кольцо — «сделал по схеме», имя, под ним прошлый раз (или сегодняшнее —
 * ярче), справа доза плана. Тап — техника, прогрессия, таймер и тренер.
 * Прошлый раз здесь главное: прогрессивная перегрузка — это «сегодня чуть
 * больше», и «чуть больше чего» надо видеть В МОМЕНТ подхода.
 */
@Composable
private fun PlannedExerciseCard(
    title: String,
    name: String = "",
    dose: String = "",
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
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onCheck != null) {
                // «Ок» на задачу: галочка — «сделал по схеме», числа поверх
                // неё наговариваются как обычно и весят больше галочки.
                CheckDot(ticked, onCheck)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    name.ifBlank { title },
                    style = ty.bodyL,
                    color = if (ticked) mode.meta else ru.zf.pravka.ui.Ink.Text,
                )
                // Мелкой строкой — прошлый раз (или сегодняшнее) и его пояснение из плана.
                val hintText = when {
                    hint.isNotBlank() -> hint
                    exercise == null -> ""
                    !title.contains(exercise.name.substringBefore(" (").take(8), ignoreCase = true) -> exercise.name
                    else -> ""
                }
                val past = if (doneToday != null) "сегодня " + doneToday.compact()
                else lastTime?.let { "прошлый раз " + it.compact() }
                val meta = listOfNotNull(past, hintText.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = ty.meta.copy(fontWeight = if (doneToday != null) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (doneToday != null) mode.value else mode.meta,
                    )
                }
            }
            val target = dose.ifBlank { if (name.isBlank()) "" else title.substringAfterLast(" — ", "") }
            if (target.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(target, style = ty.valueS, color = ru.zf.pravka.ui.Ink.TextStrong, maxLines = 1)
            }
        }
        if (!open) return@Column
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
        // Ряд значков с подписями вместо пяти кнопок словами (24.09.2026):
        // «⏱ 60 · ⏱ 90 · ⏱ 120 · Как делать? · Спросить» в строку не вставали
        // и переносились лесенкой. Таймер один — тап запускает отдых из
        // настроек, долгое нажатие даёт выбрать другой.
        val context = androidx.compose.ui.platform.LocalContext.current
        var pickRest by remember { mutableStateOf(false) }
        IconActionRow {
            IconAction(
                Glyphs.Timer,
                "Таймер",
                onClick = { onRest(restSec) },
                onLongClick = { pickRest = true },
            )
            if (onAskCoach != null) {
                // «Как делать» — один тап, фиксированный вопрос; «Спросить» —
                // своё, с пустым полем. Обе — лёгкий тренер-консультант.
                IconAction(Glyphs.Book, "Как делать", onClick = { onAskCoach(true) })
                IconAction(Glyphs.Spark, "Спросить", onClick = { onAskCoach(false) })
            }
            if (exercise != null && exercise.videoQuery.isNotBlank()) {
                IconAction(Glyphs.Play, "Видео", onClick = {
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
                })
            }
        }
        if (pickRest) {
            RestSheet(
                restSec = restSec,
                onPick = { seconds ->
                    pickRest = false
                    onRest(seconds)
                },
                onDismiss = { pickRest = false },
            )
        }
    }
}

/**
 * Выбор отдыха по долгому нажатию на «Таймер»: прежние 60 и 120 рядом с
 * отдыхом из настроек. Тап по чипу сразу запускает отсчёт — лист для одного
 * решения, второго тапа «ОК» он не просит.
 */
@Composable
private fun RestSheet(restSec: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    PaperSheet(
        onDismiss = onDismiss,
        title = "Отдых",
        icon = Glyphs.Timer,
        subtitle = "по тапу на «Таймер» — $restSec сек",
    ) {
        ChipRow {
            for (seconds in listOf(60, 90, 120, restSec).distinct().sorted()) {
                PaperChip(
                    "$seconds сек",
                    selected = seconds == restSec,
                    onClick = { onPick(seconds) },
                    icon = Glyphs.Timer,
                )
            }
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
            PaperButton("Самочувствие", onClick = onFeel, icon = Glyphs.Heart)
        }

        Spacer(Modifier.height(12.dp))
        // Состояние дороги наружу — словами и значком на виду (договорённость:
        // молчание читается поломкой); как эта дорога устроена и что значит
        // «Без часов» — за «i» у строки (24.09.2026), раньше это были два
        // абзаца под ней.
        val routeInfo = listOfNotNull(
            route.hint.ifBlank { null },
            if (route.canNote) {
                "«Без часов» — когда силовая прошла без них вовсе: журнал ляжет " +
                    "заметкой сразу, не дожидаясь полутора суток."
            } else null,
        ).joinToString("\n\n")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    route.tone == 1 -> Glyphs.Check
                    route.tone < 0 -> Glyphs.Close
                    else -> Glyphs.Timer
                },
                contentDescription = null,
                tint = toneColor(route.tone),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                route.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = toneColor(route.tone),
                modifier = Modifier.weight(1f),
            )
            if (routeInfo.isNotBlank()) InfoButton("Куда уехала силовая", routeInfo)
        }
        if (route.canRetry || route.canNote) {
            Spacer(Modifier.height(8.dp))
            // С переносом: две кнопки со значками на внешнем экране Fold в
            // одну строку не встают.
            ChipRowButtons {
                if (route.canRetry) {
                    PaperButton(
                        if (busy) "Ищу…" else "Найти активность",
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
                        icon = Glyphs.Search,
                        enabled = !busy,
                    )
                }
                if (route.canNote) {
                    PaperButton(
                        "Без часов",
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
                        icon = Glyphs.Calendar,
                        enabled = !busy,
                    )
                }
            }
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
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    PaperAlert(
        onDismiss = onClose,
        title = "Зарядка и турник",
        icon = Glyphs.Sport,
        confirm = SheetAction("Записать", icon = Glyphs.Check) {
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
        },
    ) {
        PaperToggle(
            title = "Отметить зарядку сделанной",
            checked = charged,
            onCheckedChange = { charged = it },
        )
        // Как считаются числа — за «i» у подписи раздела (24.09.2026), а не
        // абзацем под чипами колена.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PaperHint("Турник, за день") }
            InfoButton(
                "Как записывается",
                "Записывается лучший результат дня: вечерняя попытка не портит " +
                    "утреннюю. Колено — наоборот, последнее сказанное.",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperField(
                value = hang,
                onValueChange = { hang = it.filter { c -> c.isDigit() }.take(4) },
                modifier = Modifier.weight(1f),
                label = "Вис, сек",
                keyboardOptions = number,
            )
            PaperField(
                value = negatives,
                onValueChange = { negatives = it.filter { c -> c.isDigit() }.take(3) },
                modifier = Modifier.weight(1f),
                label = "Негативы",
                keyboardOptions = number,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperField(
                value = scapular,
                onValueChange = { scapular = it.filter { c -> c.isDigit() }.take(3) },
                modifier = Modifier.weight(1f),
                label = "Лопаточные",
                keyboardOptions = number,
            )
            PaperField(
                value = pullups,
                onValueChange = { pullups = it.filter { c -> c.isDigit() }.take(3) },
                modifier = Modifier.weight(1f),
                label = "Подтягивания",
                keyboardOptions = number,
            )
        }
        PaperHint("Колено сегодня")
        ChipRow {
            for (option in listOf("зелёный", "жёлтый", "красный")) {
                PaperChip(
                    option,
                    selected = knee == option,
                    onClick = { knee = if (knee == option) "" else option },
                    warn = option == "красный",
                )
            }
        }
    }
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
    PaperAlert(
        onDismiss = onClose,
        title = "Как прошло",
        icon = Glyphs.Heart,
        confirm = SheetAction("Записать", icon = Glyphs.Check) {
            app.appScope.launch {
                app.strengthEngine.setFeel(sessionId, feel, rpe, note.trim())
                app.strengthEngine.syncPending(force = true)
                onClose()
            }
        },
    ) {
        // Шкала — подпись к чипам, без неё «1» и «5» не прочитать: на виду.
        PaperHint("Самочувствие: 1 отлично — 5 развалина (шкала intervals)")
        ChipRow {
            for (v in 1..5) {
                PaperChip("$v", selected = feel == v, onClick = { feel = if (feel == v) 0 else v })
            }
        }
        PaperHint("Как тяжело далось, RPE 1–10")
        ChipRow {
            for (v in listOf(3, 5, 7, 8, 9, 10)) {
                PaperChip("$v", selected = rpe == v, onClick = { rpe = if (rpe == v) 0 else v })
            }
        }
        PaperField(
            value = note,
            onValueChange = { note = it },
            label = "Заметка (уедет в intervals)",
            singleLine = false,
            maxLines = 3,
        )
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

// Правка 4.0: графики — шкалой краски Спорта (`Modes.Sport.ramp`), чужие
// цвета не примешиваются: тренированность — светлым, усталость — тёплым.
private val CTL_COLOR = Color(0xFFCDEBDB)
private val ATL_COLOR = Color(0xFFF27A45)
private val RUN_COLOR = Color(0xFF16A34A)
private val RIDE_COLOR = Color(0xFF2563EB)

/**
 * Круглая галочка чек-листа: пустой кружок кромкой → залитый краской режима
 * со значком. До 24.09.2026 была ярко-зелёной с «✓» шрифта — рядом с
 * кнопками набора она читалась чужой деталью.
 */
@Composable
private fun CheckDot(ticked: Boolean, onCheck: () -> Unit) {
    // Правка 4.0: кольцо 22 в зоне 40 краской режима; сделано — залито клавишей.
    val mode = ru.zf.pravka.ui.LocalMode.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCheck),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (ticked) mode.key else Color.Transparent)
                .border(2.dp, if (ticked) mode.key else mode.tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (ticked) Icon(Glyphs.Check, contentDescription = "сделано", tint = ru.zf.pravka.ui.Ink.KeyIcon, modifier = Modifier.size(15.dp))
        }
    }
}

/** «Сделано» строкой: значок галочки и слово, цветом «хорошо». */
@Composable
private fun DoneLine(text: String, modifier: Modifier = Modifier, big: Boolean = false) {
    val color = toneColor(1)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyphs.Check, contentDescription = null, tint = color, modifier = Modifier.size(if (big) 20.dp else 18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

/** Идёт запрос: крутилка и слово — на месте ответа, пока его нет. */
@Composable
private fun BusyLine(text: String) {
    // Версия 3: вместо колеса — искры и секунды до ответа, как у Правки.
    ru.zf.pravka.ui.ThinkingLine(text.removeSuffix("…"))
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
    today: String = dayKey(System.currentTimeMillis()),
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

    // Правка 4.0 (`screens/09`): упражнения — чипами. Тап — отметить, долгое
    // нажатие — лист с дозой, техникой, таймером, тренером и заметкой (то, что
    // раньше раскрывалось под строкой). «Не смог» и «частично» — значком в чипе.
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val doneCount = if (charged) items.size else allIds.count { it in doneIds }
    Column(Modifier.fillMaxWidth()) {
        ru.zf.pravka.ui.SectionHeader(
            "зарядка · $doneCount из ${items.size}",
            trailing = chargerPlan?.minutes?.takeIf { it > 0 }?.let { ru.zf.pravka.core.Fmt.dur(it) },
            info = "Зарядка" to "Тап по упражнению — отметить, долгое нажатие — техника, таймер и " +
                "заметка, как пошло. Отметишь всё — зарядка закроется сама.",
        )
        // Заметка дня из календаря: «сокращённая версия», «дачная — турник заменяется резинкой».
        val dayNote = chargerPlan?.noteBefore().orEmpty()
        if (dayNote.isNotBlank()) {
            Text(dayNote.take(400), style = ty.meta, color = mode.meta, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (task in items) {
                val ticked = task.id in doneIds || charged
                val report = gtgToday?.items?.firstOrNull { it.id == task.id }
                val mark = when {
                    report != null && report.status == "no" -> Glyphs.Close
                    report != null && report.status != "ok" -> Glyphs.Minus
                    ticked -> Glyphs.Check
                    else -> null
                }
                val warn = report != null && report.status != "ok"
                val shape = RoundedCornerShape(50)
                Row(
                    Modifier
                        .clip(shape)
                        .background(if (ticked || warn) mode.tint.copy(alpha = 0.12f) else Color.Transparent)
                        .border(1.dp, mode.tint.copy(alpha = if (ticked) 0.42f else 0.24f), shape)
                        .combinedTap(
                            enabled = true,
                            onClick = {
                                if (warn) noting = task
                                else app.appScope.launch { app.bodyEngine.toggleZaryadka(task.id, allIds = allIds) }
                            },
                            onLongClick = { openId = task.id },
                        )
                        .padding(start = if (mark != null) 12.dp else 16.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (mark != null) {
                        Icon(mark, contentDescription = null, tint = if (warn) ru.zf.pravka.ui.Ink.Warn else mode.value, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        listOf(task.name.ifBlank { task.title }, task.dose).filter { it.isNotBlank() }.joinToString(" "),
                        style = ty.label,
                        color = if (ticked) ru.zf.pravka.ui.Ink.Text else mode.label,
                    )
                }
            }
        }
        // Его текст ПОСЛЕ списка: «Минимум на плохое утро: 1 + 3 + шесть отжиманий».
        val afterNote = chargerPlan?.noteAfter().orEmpty()
        if (afterNote.isNotBlank()) {
            Text(afterNote.take(400), style = ty.meta, color = mode.meta, modifier = Modifier.padding(start = 8.dp, top = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
        // Отметить всю зарядку разом — одной клавишей; сделана — словами и «Отменить».
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (charged) {
                DoneLine("Зарядка сделана", modifier = Modifier.weight(1f).padding(start = 8.dp))
                PaperTextButton(
                    "Отменить",
                    onClick = { app.appScope.launch { app.bodyEngine.unchargeToday(today) } },
                    icon = Glyphs.Undo,
                )
            } else {
                Spacer(Modifier.weight(1f))
                ru.zf.pravka.ui.GhostKey("Зарядка сделана", { app.appScope.launch { app.bodyEngine.chargedToday() } }, icon = Glyphs.Check)
            }
        }
        // Самочувствие — в нативное поле feel зарядки-активности: intervals рисует по нему кривую.
        if (charged || doneIds.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("самочувствие · 1 отлично — 5 развалина (шкала intervals)", style = ty.meta, color = mode.meta, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.height(6.dp))
            ru.zf.pravka.ui.Segmented(
                options = (1..5).map { "$it" },
                selected = (gtgToday?.feel ?: 0) - 1,
                onSelect = { v -> app.appScope.launch { app.bodyEngine.putGtgNumbers(feel = v + 1) } },
            )
        }
        // Накопленные за день пометки — видно, что уедет в intervals вместе с итогом.
        val accumNote = gtgToday?.note.orEmpty()
        if (accumNote.isNotBlank()) {
            Text("Заметки дня: $accumNote", style = ty.meta, color = mode.meta, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
        }
    }
    // Лист упражнения по долгому нажатию: доза, его пояснение, отчёт, техника и действия.
    val opened = items.firstOrNull { it.id == openId }
    if (opened != null) {
        val exercise = opened.exercise
        val report = gtgToday?.items?.firstOrNull { it.id == opened.id }
        PaperSheet(
            onDismiss = { openId = null },
            title = opened.name.ifBlank { opened.title },
            icon = Glyphs.Sport,
            subtitle = listOf(opened.dose, opened.hint).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { exercise?.scheme },
        ) {
            if (report != null && (report.fact.isNotBlank() || report.note.isNotBlank() || report.status != "ok")) {
                PaperHint("✎ " + report.brief().substringAfter(": "))
            }
            if (exercise != null && exercise.how.isNotBlank()) {
                Text("Как делать", style = ty.label.copy(fontWeight = FontWeight.SemiBold), color = mode.label)
                Text(exercise.how, style = ty.body, color = ru.zf.pravka.ui.Ink.Text)
                Spacer(Modifier.height(8.dp))
            }
            if (exercise != null && exercise.mistakes.isNotBlank()) {
                Text("Главные ошибки", style = ty.label.copy(fontWeight = FontWeight.SemiBold), color = mode.label)
                Text(exercise.mistakes, style = ty.body, color = ru.zf.pravka.ui.Ink.Warn)
                Spacer(Modifier.height(8.dp))
            }
            IconActionRow {
                // «Планка — 40 сек»: секунды засечь нечем — таймер в одном тапе.
                val holdSec = HOLD_SEC.find(opened.title)?.groupValues?.get(1)?.toIntOrNull()
                if (holdSec != null) {
                    IconAction(Glyphs.Timer, "$holdSec сек", onClick = {
                        openId = null
                        Feedback.toast(app, "$holdSec сек пошли — считает кнопка «Т»")
                        ru.zf.pravka.trigger.PravkaAccessibilityService.instance?.startRestFromTab(holdSec)
                    })
                }
                IconAction(Glyphs.Book, "Как делать", onClick = { openId = null; asking = Triple(opened.title, exercise, true) })
                IconAction(Glyphs.Spark, "Спросить", onClick = { openId = null; asking = Triple(opened.title, exercise, false) })
                IconAction(Glyphs.Edit, "Как пошло", onClick = { openId = null; noting = opened })
            }
        }
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
    PaperAlert(
        onDismiss = onClose,
        title = name,
        icon = Glyphs.Edit,
        // План пункта — подзаголовком: с ним сверяют факт, он нужен на виду.
        subtitle = if (plan.isNotBlank()) "план: $plan" else null,
        confirm = SheetAction("Записать", icon = Glyphs.Check) {
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
        },
    ) {
        ChipRow {
            for ((key, label) in listOf("ok" to "Сделал", "part" to "Частично", "no" to "Не смог")) {
                PaperChip(label, selected = status == key, onClick = { status = key }, warn = key == "no")
            }
        }
        PaperField(
            value = fact,
            onValueChange = { fact = it },
            label = "Факт: «10», «12 из 15»…",
            singleLine = false,
        )
        PaperField(
            value = note,
            onValueChange = { note = it },
            label = "Ощущение: «тяжело», «легко»…",
            singleLine = false,
            maxLines = 3,
        )
        // Куда уедет запись — последствие кнопки, а не справка: на виду.
        PaperHint("Уедет строкой «факт/план» в комментарий intervals — по ней правится план.")
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
    PaperCard(
        label = "план недели",
        info = "Тап по дню — его комментарии из календаря. Правится в чате с Клодом.",
    ) {
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
    PaperCard(
        label = "кпд · темп и ватты на удар пульса",
        info = "Это твоя месячная контрольная, посчитанная сама: рост — база " +
            "строится, темп на том же пульсе ускоряется. Сравниваются " +
            "средние по четырём неделям, одиночные точки шумят.",
    ) {
        var shown = false
        if (runs.size >= 3) {
            EfficiencyRow("Бег", runs, RUN_COLOR)
            shown = true
        }
        if (rides.size >= 3) {
            if (shown) Spacer(Modifier.height(12.dp))
            EfficiencyRow("Вело", rides, RIDE_COLOR)
        }
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
            PaperButton("Комментарий в intervals", onClick = onComment, icon = Glyphs.Note)
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
    // Пятая зона красная, первая синяя: тот же язык, что у радуги Засечки.
    // Тон — ночной: приложение всегда тёмное (версия 3).
    val hue = (210f - index * 34f).coerceAtLeast(0f)
    return Color.hsv(hue, 0.55f, 0.92f)
}

@Composable
private fun sportColor(type: String): Color {
    val hue = when (type) {
        "Run", "TrailRun", "VirtualRun" -> 20f
        "Ride", "VirtualRide", "GravelRide", "MountainBikeRide" -> 200f
        "WeightTraining", "Workout", "Crossfit", "HIIT" -> 350f
        "Walk", "Hike" -> 100f
        "Swim" -> 185f
        else -> 265f
    }
    return Color.hsv(hue, 0.5f, 0.92f)
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
            GlyphButton(Glyphs.Close, "Убрать", onClick = onDelete)
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
 * Настройки спорта. С 24.09.2026 всё про Notion (токен, хаб, план, Дневник,
 * «Вся жизнь») — в «Подключениях → Notion» ([NotionSettings] ниже): это один
 * ключ на всё приложение, а не часть спорта.
 */
@Composable
internal fun BodySportSettings(app: PravkaApp) {
    // Группа открывается и без захода во вкладку «Спорт» — сторы могли
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
    val sessions by app.strengthStore.sessionsFlow.collectAsState()
    var sliderDays by remember(days) { mutableStateOf(days.toFloat()) }
    var sliderRest by remember(restSec) { mutableStateOf(restSec.toFloat()) }
    val goalWeight by app.settings.goalWeightFlow.collectAsState(
        initial = ru.zf.pravka.data.Settings.GOAL_WEIGHT_DEFAULT
    )
    var goalSlider by remember(goalWeight) { mutableStateOf(goalWeight.toFloat()) }
    val notify by app.settings.sportNotifyFlow.collectAsState(initial = true)

    Column(verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap)) {
        PaperCard(label = "тренировки") {
            PaperSlider(
                title = "Отдых между подходами",
                valueText = "${sliderRest.toInt()} сек",
                value = sliderRest,
                onValueChange = { sliderRest = it },
                onValueChangeFinished = { app.appScope.launch { app.settings.setRestSec(sliderRest.toInt()) } },
                valueRange = 30f..240f,
                info = "«Таймер» в карточке упражнения и на плашке запускает именно этот отдых.",
            )
            PaperSlider(
                title = "Глубина выгрузки",
                valueText = "${sliderDays.toInt()} дн.",
                value = sliderDays,
                onValueChange = { sliderDays = it },
                onValueChangeFinished = { app.appScope.launch { app.settings.setSportDays(sliderDays.toInt()) } },
                valueRange = 30f..400f,
                info = "Столько дней тренировок и здоровья держим на телефоне. " +
                    "Глубже — дольше первая выгрузка, но длиннее графики.",
            )
            PaperSlider(
                title = "Цель веса",
                valueText = "${goalSlider.toInt()} кг",
                value = goalSlider,
                onValueChange = { goalSlider = it },
                onValueChangeFinished = { app.appScope.launch { app.settings.setGoalWeight(goalSlider.toInt()) } },
                valueRange = 65f..95f,
                info = "К ней меряет дорогу карточка «Цели».",
            )
            PaperToggle(
                title = "Тренировка приехала — уведомление",
                checked = notify,
                onCheckedChange = { v -> app.appScope.launch { app.settings.setSportNotify(v) } },
                info = "Как только часы отдали тренировку: вердикт по твоим правилам " +
                    "и кнопки самочувствия 2/3/4 прямо в шторке.",
            )
        }

        PaperCard(label = "подходы в intervals") {
            val pending = sessions.count { it.pendingSync }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pending == 0) "Всё уехало" else "$pending ждут активность от часов",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                PaperButton("Донести", icon = Glyphs.Upload, onClick = {
                    app.appScope.launch {
                        val outcome = app.strengthEngine.syncPending(force = true)
                        Feedback.toast(
                            app,
                            "Отправлено ${outcome.sent}, ждут ${outcome.waiting}" +
                                (if (outcome.failed > 0) ", не вышло ${outcome.failed}" else ""),
                            long = true,
                        )
                    }
                })
            }
        }

        PaperCard(label = "пороги из intervals.icu", info = "Правятся в intervals.icu — здесь только видно.") {
            if (profile.known) {
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
            } else {
                PaperHint(
                    "Пороги ещё не приехали. Они тянутся при глубокой выгрузке — " +
                        "нажми «Обновить» на вкладке «Спорт»."
                )
            }
        }

        PaperCard(label = "правила блока", info = "Правятся в Notion — здесь только видно. Читаются раз в сутки; токен и хаб — в «Подключениях → Notion».") {
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
            } else {
                PaperHint("Правила ещё не приезжали — нужен токен Notion и доступ к странице блока (Подключения → Notion).")
            }
        }

        // Справочник упражнений: живой из базы Notion «Упражнения», файл сборки —
        // семя и запас без сети. Отсюда видно, ЧЕМ сейчас матчатся строки плана —
        // и почему «Суставы сверху вниз» вдруг без техники.
        val bookVersion by app.exerciseBook.versionFlow.collectAsState()
        var syncingBook by remember { mutableStateOf(false) }
        var bookError by remember { mutableStateOf(app.notionExerciseSync.lastError()) }
        PaperCard(
            label = "справочник упражнений",
            info = "Читается раз в сутки вместе с планом и по кнопке «Обновить» во " +
                "вкладке. Без сети — последнее прочитанное, без токена — файл сборки. " +
                "Голосовые имена движений — из файла сборки (tools/gen_reference.py).",
        ) {
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
                PaperHint(zaryadka.joinToString(" · ") { it.name.substringBefore(":") })
            }
            if (bookError.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(bookError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            PaperButton(
                if (syncingBook) "Читаю…" else "Перечитать из Notion",
                icon = Glyphs.Refresh,
                enabled = !syncingBook,
                onClick = {
                    syncingBook = true
                    app.appScope.launch {
                        val ok = runCatching { app.notionExerciseSync.refresh(force = true) }.getOrDefault(false)
                        syncingBook = false
                        bookError = if (ok) "" else app.notionExerciseSync.lastError()
                        Feedback.toast(
                            app,
                            if (ok) "Справочник обновлён: ${app.exerciseBook.all.size} движений"
                            else bookError.ifBlank { "Справочник не обновился" },
                            long = !ok,
                        )
                    }
                },
            )
        }

        if (talks.isNotEmpty()) {
            PaperCard(label = "разборы тренера") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Сохранено разборов: ${talks.size}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    PaperButton("Убрать все", icon = Glyphs.Delete, onClick = { app.appScope.launch { store.clearTalks() } })
                }
            }
        }
    }
}

/**
 * Notion — один ключ на всё приложение: план и правила блока для Спорта,
 * справочник упражнений, автогалочки в «Дневник», «Вся жизнь» в базы
 * «Правка: разборы». До 24.09.2026 это лежало в настройках «Тела» и
 * сохранялось тремя разными кнопками.
 */
@Composable
internal fun NotionSettings(app: PravkaApp) {
    LaunchedEffect(Unit) { runCatching { app.planStore.load() } }
    val rules by app.planStore.rulesFlow.collectAsState()
    val notionToken by app.settings.notionTokenFlow.collectAsState(initial = "")
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

    Column(verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap)) {
        PaperCard(
            label = "ключ и хаб",
            info = "Нужны две вещи: внутренний токен интеграции Notion и доступ этой " +
                "интеграции к странице «Тело: велоформа и сила» — страница → «…» в правом " +
                "верхнем углу → Connections → выбрать интеграцию. Доступ наследуется вниз: " +
                "страницу блока отдельно открывать не надо. В поле хаба можно вставить прямо " +
                "ссылку из «Copy link» — id из неё вынется сам. Хаб читается и сам: светофор " +
                "колена, правило отмены и потолок бега лежат на нём, а не на странице блока. " +
                "Для Дневника и «Всей жизни» интеграции нужны права на запись.",
        ) {
            PaperField(value = tokenDraft, onValueChange = { tokenDraft = it }, label = "Токен Notion (ntn_…)")
            PaperField(value = hubDraft, onValueChange = { hubDraft = it }, label = "Страница-хаб: ссылка или id")
            if (rules.sourceText.isNotBlank()) PaperHint("План прочитан и лежит на телефоне: ${rules.sourceText.length} зн.")
            if (notionError.isNotBlank()) {
                Text(notionError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (report.isNotBlank()) {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(6.dp))
            ChipRowButtons {
                // «Проверить доступ» — не про удобство, а про то, чтобы ошибка была
                // читаемой. «Не нашлось страниц» ничего не говорит о том, что чинить;
                // построчный отчёт («токен принят, хаб отдал 404») указывает пальцем.
                PaperButton(if (checking) "Проверяю…" else "Проверить", icon = Glyphs.Search, enabled = !checking, onClick = {
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
                })
                PaperButton(if (syncingPlan) "Читаю…" else "Прочитать план", icon = Glyphs.Download, enabled = !syncingPlan, onClick = {
                    syncingPlan = true
                    app.appScope.launch {
                        app.settings.setNotionToken(tokenDraft.trim())
                        app.settings.setNotionHub(hubDraft.trim())
                        val outcome = app.planSync.refresh(force = true)
                        syncingPlan = false
                        notionError = app.notionPlanSync.lastError()
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
                })
                PaperButton("Сохранить", primary = true,
                    enabled = tokenDraft.trim() != notionToken || hubDraft.trim() != notionHub,
                    onClick = {
                        app.appScope.launch {
                            app.settings.setNotionToken(tokenDraft.trim())
                            app.settings.setNotionHub(hubDraft.trim())
                            Feedback.toast(app, "Сохранено")
                        }
                    })
            }
        }

        val diary by app.settings.notionDiaryFlow.collectAsState(initial = true)
        var pushingDiary by remember { mutableStateOf(false) }
        var diaryStatus by remember { mutableStateOf("") }
        PaperCard(label = "дневник") {
            PaperToggle(
                title = "Автогалочки в «Дневник»",
                checked = diary,
                onCheckedChange = { v -> app.appScope.launch { app.settings.setNotionDiary(v) } },
                info = "Зарядка, «сделано», feel, колено, вес и еда сами уезжают в " +
                    "твою базу Notion. Галочки только ставятся, тексты пишутся " +
                    "лишь в пустые ячейки — твоё руками написанное не трогается.",
            )
            if (diary) {
                if (diaryStatus.isNotBlank()) PaperHint(diaryStatus)
                Spacer(Modifier.height(4.dp))
                PaperButton(if (pushingDiary) "Отправляю…" else "Отправить сейчас", icon = Glyphs.Upload, enabled = !pushingDiary, onClick = {
                    pushingDiary = true
                    app.appScope.launch {
                        val done = runCatching { app.notionDiarySync.sync(force = true) }.getOrDefault(false)
                        pushingDiary = false
                        diaryStatus = when {
                            app.notionDiarySync.lastError().isNotBlank() -> app.notionDiarySync.lastError()
                            done -> "Уехало: ${app.notionDiarySync.lastPushed()}"
                            else -> "Нечего отправлять или уже уехало"
                        }
                    }
                })
            }
        }

        // ---- Вся жизнь в Notion: лента, еда, спорт, дни ----
        val life by app.settings.notionLifeFlow.collectAsState(initial = true)
        val lifeHub by app.settings.notionLifeHubFlow.collectAsState(
            initial = ru.zf.pravka.data.NotionLifeSync.HUB_DEFAULT
        )
        var lifeHubDraft by remember(lifeHub) { mutableStateOf(lifeHub) }
        var pushingLife by remember { mutableStateOf(false) }
        val lifeStatus by app.notionLifeSync.statusFlow.collectAsState()
        PaperCard(label = "вся жизнь") {
            PaperToggle(
                title = "Вся жизнь — в «Правка: разборы»",
                checked = life,
                onCheckedChange = { v -> app.appScope.launch { app.settings.setNotionLife(v) } },
                info = "Раз в час лента Засечки, еда, тренировки, силовые, зарядка, " +
                    "телефон и форма по дням уезжают строками в свои базы. " +
                    "Разбор читает Notion. Чужие колонки («Дети дома», «Якорь утра») " +
                    "не трогаются. Первый заезд — вся история, шесть сотен строк: Notion " +
                    "пускает три запроса в секунду, поэтому займёт около часа и пойдёт " +
                    "пачками на каждом тике. Дальше — по паре десятков правок в час.",
            )
            if (life) {
                PaperField(value = lifeHubDraft, onValueChange = { lifeHubDraft = it }, label = "Хаб «Правка: разборы»: ссылка или id")
                PaperHint(
                    "Интеграции (тот же токен) нужен доступ к этой странице: " +
                        "… → Connections. Базы под ней находятся по названиям сами."
                )
                // Состояние словами и всегда с временем последней удачной записи:
                // «отправлено 12 ✓ · последняя запись 14:20». Пустая строка до
                // первого тика читалась как поломка — теперь она говорит, чего ждать.
                PaperHint(
                    lifeStatus.ifBlank {
                        "Состояние появится после первого тика службы — до пяти минут после запуска. " +
                            "Не терпится — «Синхронизировать»."
                    }
                )
                Spacer(Modifier.height(4.dp))
                ChipRowButtons {
                    PaperButton(if (pushingLife) "Отправляю…" else "Синхронизировать", icon = Glyphs.Refresh, enabled = !pushingLife, onClick = {
                        pushingLife = true
                        app.appScope.launch {
                            runCatching { app.notionLifeSync.sync(force = true) }
                            pushingLife = false
                            val err = app.notionLifeSync.lastError()
                            if (err.isNotBlank()) Feedback.toast(app, err, long = true)
                        }
                    })
                    PaperButton("Сбросить карту", icon = Glyphs.Undo, onClick = {
                        app.appScope.launch {
                            app.notionLifeSync.resetMaps()
                            Feedback.toast(app, "Карта страниц сброшена — следующий синк сверится с базами заново")
                        }
                    })
                    PaperButton("Сохранить", primary = true, enabled = lifeHubDraft.trim() != lifeHub, onClick = {
                        app.appScope.launch {
                            app.settings.setNotionLifeHub(lifeHubDraft.trim())
                            Feedback.toast(app, "Сохранено")
                        }
                    })
                }
            }
        }
    }
}

/** Кнопки плашки в ряд с переносом: на внешнем экране Fold три в строку не встают. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRowButtons(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
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

// ---------------------------------------------------------------- Правка 4.0: «Сегодня» Спорта (`screens/09`)

/**
 * Готовность: светофор дня тремя точками в краске режима (без светофорных
 * цветов), вердикт крупно, почему — строкой, его нарушения — тёплым, и числа
 * плитками: три светофора и пульс покоя, если часы его знают.
 */
@Composable
private fun ReadinessCard(verdict: TrafficLight.Verdict, health: SportStore.Health?, error: String) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), mode.glass)
            .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ГОТОВНОСТЬ", style = ty.overline, color = mode.label, modifier = Modifier.weight(1f))
            ru.zf.pravka.ui.InfoDot(
                "готовность",
                "Светофор считается на телефоне: сон, HRV и форма из intervals и правила блока " +
                    "из Notion. Три точки — по плану, две — осторожно, одна — восстановление.",
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
            val lit = when {
                verdict.tone >= 1 -> 3
                verdict.tone == 0 -> 2
                else -> 1
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { k ->
                    Box(Modifier.size(13.dp).clip(CircleShape).background(if (k < lit) mode.value else mode.tint.copy(alpha = 0.20f)))
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(verdict.headline, style = ty.titleS, color = ru.zf.pravka.ui.Ink.TextStrong)
        }
        if (verdict.because.isNotBlank()) {
            Text(verdict.because, style = ty.body, color = ru.zf.pravka.ui.Ink.TextSecondary, modifier = Modifier.padding(top = 6.dp, end = 10.dp))
        }
        for (w in verdict.warnings) {
            Row(Modifier.padding(top = 6.dp, end = 10.dp), verticalAlignment = Alignment.Top) {
                Icon(Glyphs.Error, contentDescription = null, tint = ru.zf.pravka.ui.Ink.Warn, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text(w, style = ty.label, color = ru.zf.pravka.ui.Ink.Warn)
            }
        }
        val tiles = verdict.numbers.take(3).map { Triple(it.label, it.value, it.hint to (it.tone < 0)) } +
            listOfNotNull(health?.restingHr?.takeIf { it > 0 }?.let { Triple("Пульс покоя", "$it", "" to false) })
        if (tiles.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value, sub) in tiles) {
                    ru.zf.pravka.ui.StatTile(label, value, Modifier.weight(1f), delta = sub.first.takeIf { it.isNotBlank() }, worse = sub.second)
                }
            }
        }
        if (error.isNotBlank()) {
            Text(error, style = ty.meta, color = ru.zf.pravka.ui.Ink.Warn, modifier = Modifier.padding(top = 8.dp, end = 10.dp))
        }
    }
}

/** Что приехало с часов сегодня: название, часы, четыре числа (`screens/09`). */
@Composable
private fun DoneTodayCard(list: List<SportStore.Workout>) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    Column(Modifier.fillMaxWidth()) {
        ru.zf.pravka.ui.SectionHeader(
            "сделано сегодня · ${list.size}",
            trailing = ru.zf.pravka.core.Fmt.dur(list.sumOf { it.minutes }.toInt()),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (w in list) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(22.dp), mode.glass)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(w.name.ifBlank { w.type }, style = ty.bodyStrong.copy(fontSize = ty.bodyL.fontSize), color = ru.zf.pravka.ui.Ink.TextStrong,
                            modifier = Modifier.weight(1f))
                        Text(
                            ru.zf.pravka.core.Fmt.range(w.start, w.start + w.seconds * 1000),
                            style = ty.meta,
                            color = mode.meta,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val cells = buildList {
                        if (w.km >= 0.1) add("Дистанция" to (fmt2(w.km) + " км"))
                        add("Время" to clock(w.movingSeconds.takeIf { it > 0 } ?: w.seconds))
                        if (w.paceSecPerKm > 0) add("Темп" to (pace(w.paceSecPerKm) + " /км"))
                        else if (w.avgWatts > 0) add("Мощность" to "${w.avgWatts} Вт")
                        if (w.avgHr > 0) add("Пульс" to "${w.avgHr}")
                    }
                    Row(Modifier.fillMaxWidth()) {
                        for ((k, v) in cells) {
                            Column(Modifier.weight(1f)) {
                                Text(k, style = ty.caption, color = mode.label, maxLines = 1)
                                Text(v, style = ty.valueS, color = ru.zf.pravka.ui.Ink.TextStrong, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fmt2(v: Double) = String.format(Locale("ru"), "%.2f", v)

/** «29:40», «1:05:12». */
private fun clock(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/**
 * Путь к первому подтягиванию: вис сегодня крупно, рекорд рядом, негативы
 * справа, полоса виса против рекорда и две недели зарядки точками; запись
 * чисел — клавишей «Записать вис, негативы, колено».
 */
@Composable
private fun PullupCard(app: PravkaApp, gtgToday: StrengthStore.GtgDay?, streak: Int, onRecord: () -> Unit) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val best = app.strengthStore.bestHang()
    val bestPull = app.strengthStore.bestPullups()
    val hang = gtgToday?.hangSec ?: 0
    val neg = gtgToday?.negatives ?: 0
    Column(Modifier.fillMaxWidth()) {
        ru.zf.pravka.ui.SectionHeader(
            "путь к первому подтягиванию",
            trailing = if (streak > 0) "серия " + plural(streak, "день", "дня", "дней") else null,
            info = "Турник" to "Турник — отдельные числа: зарядку они не отмечают. Точки — зарядка " +
                "за две недели, полоса — вис сегодня против рекорда.",
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), mode.glass)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            if ((gtgToday?.pullups ?: 0) > 0) {
                Text(
                    "Подтягивания сегодня: ${gtgToday?.pullups}" + (if (bestPull == gtgToday?.pullups) " — рекорд" else ""),
                    style = ty.titleS,
                    color = mode.value,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Вис", style = ty.label, color = mode.label)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (hang > 0) "$hang с" else "—", style = ty.displayL, color = ru.zf.pravka.ui.Ink.TextStrong)
                        if (best > 0) {
                            Text("рекорд $best с", style = ty.meta, color = mode.meta, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (bestPull > 0) "Подтягивания" else "Негативы", style = ty.label, color = mode.label)
                    Text(
                        if (bestPull > 0) "$bestPull" else if (neg > 0) "$neg" else "—",
                        style = ty.valueS,
                        color = ru.zf.pravka.ui.Ink.TextStrong,
                    )
                }
            }
            if (best > 0) {
                Spacer(Modifier.height(10.dp))
                // Шкала — рекорд с запасом: отметка рекорда видна, сегодняшний вис идёт к ней.
                val scale = maxOf(best, hang) * 1.25f
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    val r = size.height / 2
                    drawRoundRect(mode.tint.copy(alpha = 0.16f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
                    if (hang > 0) {
                        drawRoundRect(
                            mode.value,
                            size = androidx.compose.ui.geometry.Size(size.width * (hang / scale), size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                        )
                    }
                    val x = size.width * (best / scale)
                    drawLine(ru.zf.pravka.ui.Ink.Cream, Offset(x, -3f), Offset(x, size.height + 3f), strokeWidth = 2.5f)
                }
            }
            Spacer(Modifier.height(12.dp))
            // Две недели зарядки точками: сегодня — кольцом крупнее.
            val days = app.strengthStore.recentGtg(14)
            val done = days.filter { it.charged }.map { it.date }.toSet()
            val todayKey = dayKey(System.currentTimeMillis())
            val dates = remember(todayKey) {
                var cursor = todayKey
                val out = mutableListOf<String>()
                repeat(14) { out.add(cursor); cursor = ru.zf.pravka.data.dayBefore(cursor) }
                out.reversed()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("14 дней", style = ty.meta, color = mode.meta, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (d in dates) {
                        val on = d in done
                        val isToday = d == todayKey
                        Box(
                            Modifier
                                .size(if (isToday) 15.dp else 12.dp)
                                .clip(CircleShape)
                                .background(if (on) mode.value else Color.Transparent)
                                .border(if (isToday) 2.dp else 1.5.dp, if (on) mode.value else mode.tint.copy(alpha = 0.45f), CircleShape),
                        )
                    }
                }
            }
            if (gtgToday?.knee?.isNotBlank() == true) {
                Text("Колено сегодня: ${gtgToday.knee}", style = ty.meta, color = mode.meta, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            ru.zf.pravka.ui.GhostKey("Записать вис, негативы, колено", onRecord, icon = Glyphs.Edit)
        }
    }
}

/**
 * Неделя столбиками (`screens/09`): по дню — пунктиром план из календаря
 * intervals (без зарядки), заливкой — что приехало с часов. Сверху — «сделано
 * 45 м из 4 ч 5 м».
 */
@Composable
private fun WeekBarsCard(app: PravkaApp, workouts: List<SportStore.Workout>, planDays: List<PlanStore.PlanDay>) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val todayDate = java.time.LocalDate.now()
    val monday = todayDate.with(java.time.DayOfWeek.MONDAY)
    val week = todayDate.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
    val days = remember(planDays, workouts, monday) {
        (0 until 7).map { k ->
            val d = monday.plusDays(k.toLong())
            val key = d.toString()
            val plan = app.planStore.dayOf(key).filterNot { it.charger }.sumOf { it.minutes }
            val done = workouts.filter { dayKey(it.start) == key }.sumOf { it.minutes }.toInt()
            Triple(d, plan, done)
        }
    }
    val planSum = days.sumOf { it.second }
    val doneSum = days.sumOf { it.third }
    if (planSum == 0 && doneSum == 0) return
    val top = days.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth()) {
        ru.zf.pravka.ui.SectionHeader(
            "неделя $week",
            trailing = "сделано ${ru.zf.pravka.core.Fmt.dur(doneSum)} из ${ru.zf.pravka.core.Fmt.dur(planSum)}",
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), mode.glass)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((d, plan, done) in days) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                            val r = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx())
                            if (plan > 0) {
                                val h = size.height * plan / top
                                drawRoundRect(
                                    mode.tint.copy(alpha = 0.55f),
                                    topLeft = Offset(0f, size.height - h),
                                    size = androidx.compose.ui.geometry.Size(size.width, h),
                                    cornerRadius = r,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 1.5.dp.toPx(),
                                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                                    ),
                                )
                            }
                            if (done > 0) {
                                val h = size.height * done / top
                                drawRoundRect(
                                    mode.value,
                                    topLeft = Offset(0f, size.height - h),
                                    size = androidx.compose.ui.geometry.Size(size.width, h),
                                    cornerRadius = r,
                                )
                            } else if (plan == 0) {
                                drawRoundRect(
                                    mode.tint.copy(alpha = 0.22f),
                                    topLeft = Offset(0f, size.height - 3.dp.toPx()),
                                    size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            ru.zf.pravka.core.Fmt.wd(d),
                            style = ty.caption.copy(fontWeight = if (d == todayDate) FontWeight.Bold else FontWeight.Normal),
                            color = if (d == todayDate) ru.zf.pravka.ui.Ink.TextStrong else mode.label,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(mode.value))
                Text("сделано", style = ty.meta, color = mode.meta, modifier = Modifier.padding(start = 6.dp, end = 16.dp))
                Box(Modifier.size(12.dp).border(1.5.dp, mode.tint.copy(alpha = 0.55f), RoundedCornerShape(3.dp)))
                Text("план", style = ty.meta, color = mode.meta, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
