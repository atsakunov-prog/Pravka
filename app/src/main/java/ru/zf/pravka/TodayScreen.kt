package ru.zf.pravka

import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.CalendarRules
import ru.zf.pravka.core.DayAssembler
import ru.zf.pravka.core.DayAssembler.DayItem
import ru.zf.pravka.core.DayReport
import ru.zf.pravka.core.TodayFold
import ru.zf.pravka.core.DayState
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaViews
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.core.WeatherDay
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.trigger.CalendarPilot
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.forkZasechka
import ru.zf.pravka.trigger.onZasechkaTap
import ru.zf.pravka.ui.Coin
import ru.zf.pravka.ui.DayHeader
import ru.zf.pravka.ui.DayHeaderCompact
import ru.zf.pravka.ui.DayNav
import ru.zf.pravka.ui.Dial
import ru.zf.pravka.ui.DialCompare
import ru.zf.pravka.ui.EmptyState
import ru.zf.pravka.ui.EventChip
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.HeaderIcon
import ru.zf.pravka.ui.IconLabel
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.LocalGlowDim
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.MiniStat
import ru.zf.pravka.ui.ModeDecor
import ru.zf.pravka.ui.ModeFrame
import ru.zf.pravka.ui.ModeTile
import ru.zf.pravka.ui.Modes
import ru.zf.pravka.ui.NowJump
import ru.zf.pravka.ui.OfflineChip
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.SayBar
import ru.zf.pravka.ui.SayIcon
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.TagChip
import ru.zf.pravka.ui.TaskAct
import ru.zf.pravka.ui.TileState
import ru.zf.pravka.ui.TimePlate
import ru.zf.pravka.ui.TimelineItem
import ru.zf.pravka.ui.lineColorAt
import ru.zf.pravka.ui.fadingScroll
import androidx.compose.ui.unit.sp
import ru.zf.pravka.ui.WeatherCell
import ru.zf.pravka.ui.WeatherRow
import ru.zf.pravka.ui.ZGroup
import ru.zf.pravka.ui.AvatarKey
import ru.zf.pravka.ui.scrollFade
import ru.zf.pravka.ui.bottomFade
import ru.zf.pravka.ui.twoPane
import java.time.LocalDate
import java.time.ZoneId

// «Сегодня» — главный экран Правки 4.0 (07.10.2026, DESIGN §1, §12.1–12.2).
// Хроника одного дня: лента Засечки, к которой прикреплены отметки всех
// режимов в тот момент, когда они случились, а после линии «сейчас» —
// будущее до сна: свободные окна, календарь, план тренировки, дела на
// сегодня одно за другим и сон. Режимы — линзы на этот день: плашки под
// погодой, пилюля «+84», тап по отметке, «С» → Ещё.
//
// Правило сборки — `core/DayAssembler.kt` (под тестами); здесь — данные из
// сторов, шапка (развёрнута, пока «сейчас» видно или выше окна, сжимается,
// когда листаем в прошлое — §11.2) и строка «сказать» внизу.

/** Куда «Сегодня» уводит: режим, Ещё, статистика, Засечка на записи, Еда на приёме. */
internal class TodayNav(
    val mode: (Tab) -> Unit,
    val more: () -> Unit,
    val stats: () -> Unit,
    val zasechkaEntry: (Long) -> Unit,
    val foodAction: (String) -> Unit,
    val delaTask: (String) -> Unit,
)

/** Всё, что «Сегодня» знает о дне. */
private class TodayModel(
    val day: LocalDate,
    val dayStart: Long,
    val result: DayAssembler.Result,
    val facts: DayFacts,
    val delaDone: Int,
    val delaTotal: Int,
    val sportDone: Int,
    val sportTotal: Int,
    val kcal: Int,
    val kcalGoal: Int,
    val spentKop: Long,
    val limitKop: Long,
    val tasksById: Map<String, Dela.Task>,
    val entries: Map<Long, ru.zf.pravka.data.ZasechkaStore.Entry>,
)

@Composable
internal fun TodayScreen(app: PravkaApp, nav: TodayNav) {
    ModeFrame(ModeDecor.TODAY) {
        TodayBody(app, nav)
    }
}

@Composable
private fun TodayBody(app: PravkaApp, nav: TodayNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- время: минута в минуту, без анимации (DESIGN §10) ----
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
            now = System.currentTimeMillis()
        }
    }
    var dayOffset by remember { mutableIntStateOf(0) }
    val today = DayFacts.localDay(now)
    val day = today.plusDays(dayOffset.toLong())

    // ---- сторы ----
    LaunchedEffect(Unit) {
        runCatching { app.zasechkaStore.all(); app.zasechkaStore.categories() }
        runCatching { app.foodStore.load() }
        runCatching { app.moneyStore.load() }
        runCatching { app.delaStore.load() }
    }
    val entries by app.zasechkaStore.entriesFlow.collectAsState()
    val categories by app.zasechkaStore.categoriesFlow.collectAsState()
    val meals by app.foodStore.mealsFlow.collectAsState()
    val money by app.moneyStore.stateFlow.collectAsState()
    val workouts by app.sportStore.workoutsFlow.collectAsState()
    val planDays by app.planStore.daysFlow.collectAsState()
    val gtg by app.strengthStore.gtgFlow.collectAsState()
    val snap by app.delaStore.view.collectAsState()
    val link by app.delaSync.link.collectAsState()
    val onServer by app.delaServer.collectAsState()
    val bedtime by app.settings.todayBedtimeFlow.collectAsState(initial = ru.zf.pravka.data.Settings.TODAY_BEDTIME_DEFAULT)
    val marksOn by app.settings.todayMarksFlow.collectAsState(initial = setOf("sport", "food", "money", "dela"))
    val kcalGoal by app.settings.foodKcalFlow.collectAsState(initial = 0)
    val budget by app.settings.moneyMonthBudgetFlow.collectAsState(initial = 0L)
    val city by app.settings.weatherCityFlow.collectAsState(initial = "")
    val todayCals by app.settings.todayCalendarsFlow.collectAsState(initial = null)
    val autoCals by app.settings.autoCalendarsFlow.collectAsState(initial = null)
    val pOn by app.settings.mScopePersonalFlow.collectAsState(initial = true)
    val zOn by app.settings.mScopeZfFlow.collectAsState(initial = false)
    val profile by app.profileStore.flow.collectAsState()
    val health by app.sportStore.healthFlow.collectAsState()
    val stateOn by app.settings.todayStateFlow.collectAsState(initial = true)

    // Календарь — раз в пять минут и при смене дня: чтение провайдера — не на главном потоке.
    val calendar by produceState(emptyList<ru.zf.pravka.core.CalEvent>(), day, todayCals, autoCals) {
        while (true) {
            val zone = ZoneId.systemDefault()
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val watched = todayCals ?: autoCals
            value = withContext(Dispatchers.IO) {
                runCatching { CalendarPilot.query(context, from, from + DayFacts.DAY) }.getOrDefault(emptyList())
                    .filter { CalendarRules.eligible(it, watched) }
            }
            delay(5 * 60_000L)
        }
    }
    // Погода — Open-Meteo, кэш час; без сети — последний кэш с «нет связи».
    val weather by produceState<ru.zf.pravka.data.WeatherStore.Result?>(null, day, city) {
        value = runCatching { app.weatherStore.today(city, day) }.getOrNull()
    }

    // Тихая строка состояния под днём (баг №13): светофор Спорта и три его
    // числа — только сегодняшние: вчерашний HRV на главном экране врал бы.
    val state = remember(health, planDays, stateOn, day, today) {
        if (!stateOn || day != today) null
        else health.firstOrNull()?.takeIf { it.date == today.toString() }?.let {
            runCatching { DayState.of(app.trafficLight.today(today.toString())) }.getOrNull()
        }
    }
    var weatherOpen by remember { mutableStateOf(false) }

    val me = link?.user ?: app.delaStore.me
    val model = remember(entries, categories, meals, money, workouts, planDays, gtg, snap, calendar, now, day, bedtime, marksOn, kcalGoal, budget, pOn, zOn, onServer) {
        buildModel(app, day, now, entries, categories, calendar, bedtime, marksOn, kcalGoal, budget, pOn, zOn, me, onServer)
    }

    // ---- действия ----
    var sheetEntry by remember { mutableStateOf<Long?>(null) }
    var taskMenu by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val busy = sending || ru.zf.pravka.ui.rememberRouteBusy(app.liveWork, "zasechka", "zasechka_fork")
    val say: () -> Unit = {
        val text = draft.trim()
        if (text.isNotBlank()) {
            draft = ""
            val service = PravkaAccessibilityService.instance
            if (service != null) {
                // Та же развилка, что у кнопки «З» и гарнитуры: лента, мысль к делу, еда или дела.
                service.forkZasechka(text, "text", spoken = false)
            } else {
                sending = true
                app.appScope.launch {
                    runCatching { app.zasechkaEngine.record(text, "text") }
                    sending = false
                }
            }
        }
    }
    val mic: () -> Unit = {
        val service = PravkaAccessibilityService.instance
        if (service == null) Feedback.toast(context, context.getString(R.string.toast_no_service))
        else service.onZasechkaTap()
    }
    val onItem = ItemActions(
        entry = { sheetEntry = it.id },
        mark = { m -> openMark(m.ref, nav) },
        pending = { p, confirm ->
            if (confirm) scope.launch { runCatching { app.foodEngine.confirm(p.ref.removePrefix("meal:").toLong()) } }
            else openMark(p.ref, nav)
        },
        task = { t, act ->
            when (act) {
                TaskAct.DONE -> scope.launch {
                    app.delaDo(listOf(Dela.statusOp("task.done", t.id)))
                    Feedback.toast(app, "✓ ${t.title.take(40)}")
                }
                TaskAct.OPEN -> nav.delaTask(t.id)
                TaskAct.MENU -> taskMenu = t.id
            }
        },
        planned = { nav.mode(Tab.SPORT) },
    )

    val owner = profile?.name?.takeIf { it.isNotBlank() } ?: "Саша"
    val initial = owner.first().uppercase()
    val sayBar: @Composable (Modifier) -> Unit = { m ->
        SayBar(
            value = draft,
            onValueChange = { draft = it },
            placeholder = ru.zf.pravka.core.PillHint.say(profile?.name, "что у тебя?"),
            onSend = say,
            onMic = mic,
            busy = busy,
            busyLabel = "Разбираю",
            cream = true,
            maxLines = 3,
            trailing = { SayIcon(Glyphs.Camera, "фото еды", { nav.foodAction("photo") }) },
            modifier = m,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = twoPane()
        val head = TodayHead(state, onWeather = { weatherOpen = true })
        if (wide) {
            TodayWide(model, weather, now, dayOffset, { dayOffset = it }, initial, nav, onItem, sayBar, head)
        } else {
            TodayFolded(model, weather, now, dayOffset, { dayOffset = it }, initial, nav, onItem, sayBar, head)
        }
    }
    val w = weather
    if (weatherOpen && w != null) {
        WeatherSheet(w, day, today, onDismiss = { weatherOpen = false })
    }

    // ---- лист записи (DESIGN §12.12, `screens/13`) ----
    val opened = sheetEntry?.let { model.entries[it] }
    if (opened != null) {
        EntrySheet4(app, opened, model, nav, onDismiss = { sheetEntry = null })
    }
    val menuTask = taskMenu?.let { model.tasksById[it] }
    if (menuTask != null) {
        PaperSheet(onDismiss = { taskMenu = null }, title = menuTask.title, subtitle = menuTask.numLabel) {
            Row {
                IconLabel(Glyphs.Play, "Начать в Засечке", {
                    taskMenu = null
                    scope.launch {
                        val e = runCatching { app.zasechkaEngine.startTask(menuTask) }.getOrNull()
                        Feedback.toast(app, if (e != null) "⏱ ${e.title}" else "Не смог записать дело")
                    }
                })
                IconLabel(Glyphs.Mic, "Поправить словами", {
                    taskMenu = null
                    nav.delaTask(menuTask.id)
                })
                IconLabel(Glyphs.Check, "Сделано", {
                    taskMenu = null
                    scope.launch { app.delaDo(listOf(Dela.statusOp("task.done", menuTask.id))) }
                })
            }
        }
    }
}

/** Что делать по тапу на строку хроники. */
private class ItemActions(
    val entry: (DayItem.Entry) -> Unit,
    val mark: (DayItem.Mark) -> Unit,
    val pending: (DayItem.Pending, Boolean) -> Unit,
    val task: (DayItem.Task, TaskAct) -> Unit,
    val planned: (DayItem.Planned) -> Unit,
)

/** Тап по отметке — режим на этой записи (DESIGN §11.5). */
private fun openMark(ref: String, nav: TodayNav) {
    when {
        ref.startsWith("meal:") -> nav.foodAction("edit:" + ref.removePrefix("meal:"))
        ref.startsWith("money") -> nav.mode(Tab.MONEY)
        ref.startsWith("sport") -> nav.mode(Tab.SPORT)
        ref.startsWith("dela:task:") -> nav.delaTask(ref.removePrefix("dela:task:"))
        ref.startsWith("dela") -> nav.mode(Tab.TODOIST)
        else -> Unit
    }
}

// ---------------------------------------------------------------------------
// Сложенный (DESIGN §12.1, `screens/01–03`)
// ---------------------------------------------------------------------------

@Composable
private fun TodayFolded(
    model: TodayModel,
    weather: ru.zf.pravka.data.WeatherStore.Result?,
    now: Long,
    dayOffset: Int,
    setDay: (Int) -> Unit,
    initial: String,
    nav: TodayNav,
    act: ItemActions,
    sayBar: @Composable (Modifier) -> Unit,
    head: TodayHead,
) {
    val r = model.result
    val items = r.items
    // Начальная позиция — запись перед текущей (DESIGN §11.5); прошлый день — с начала.
    val start = remember(model.day) {
        val cur = items.indexOfFirst { it is DayItem.Entry && it.current }
        val prev = if (cur > 0) items.subList(0, cur).indexOfLast { it is DayItem.Entry } else -1
        (if (prev >= 0) prev else cur).coerceAtLeast(0)
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    // Шапка развёрнута, пока «сейчас» видно в окне или выше него; сжимается,
    // когда «сейчас» ушло ниже окна — смотрим прошлое (§11.2). С памятью
    // (`core/TodayFold.kt`, баг №2): развернуться — только если «сейчас»
    // останется видно и после разворота, иначе шапка прыгала у линии.
    var auto by remember(model.day) { mutableStateOf(false) }
    // Двойной тап по дню недели — шапка до минимума, пока не тапнут дату
    // в сжатой (баг №6): своё решение владельца, лента его не перебивает.
    var forced by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var tallPx by remember { mutableIntStateOf(0) }
    var shortPx by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sayPx = with(density) { 96.dp.roundToPx() }
    val growGuess = with(density) { 230.dp.roundToPx() }
    var nowSeen by remember { mutableStateOf(true) }
    LaunchedEffect(list, r.nowIndex, dayOffset) {
        androidx.compose.runtime.snapshotFlow {
            val info = list.layoutInfo
            val vis = info.visibleItemsInfo
            val now = vis.firstOrNull { it.index == r.nowIndex }
            TodayFold.View(
                first = list.firstVisibleItemIndex,
                firstOffset = list.firstVisibleItemScrollOffset,
                last = vis.lastOrNull()?.index ?: -1,
                nowTop = now?.offset,
                nowBottom = now?.let { it.offset + it.size },
                // Нижние 96 dp закрыты строкой «сказать»: там «сейчас» глазу не видно.
                viewEnd = info.viewportEndOffset - sayPx,
            )
        }.collect { v ->
            val grow = (tallPx - shortPx).takeIf { tallPx > 0 && shortPx > 0 && it > 0 } ?: growGuess
            auto = TodayFold.next(auto, r.nowIndex, v, grow, today = dayOffset == 0)
            nowSeen = r.nowIndex >= 0 && r.nowIndex in v.first..v.last &&
                (v.nowTop == null || v.nowTop < v.viewEnd) && (v.nowBottom == null || v.nowBottom > 0)
        }
    }
    val compact = forced || auto
    val dim = LocalGlowDim.current
    LaunchedEffect(compact) { dim.floatValue = if (compact) 0.65f else 1f }
    var showNav by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth()) {
                androidx.compose.animation.AnimatedContent(
                    targetState = compact,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "dayHeader",
                ) { c ->
                    if (c) {
                        CompactHeader(
                            model, weather?.summary?.line, nav,
                            modifier = Modifier.onSizeChanged { shortPx = it.height },
                            onDate = {
                                // Свернул сам — тап разворачивает; сжалась сама — тап ведёт к «сейчас».
                                if (forced) forced = false
                                else scope.launch { list.animateScrollToItem(if (r.nowIndex >= 0) (r.nowIndex - 2).coerceAtLeast(0) else 0) }
                                Unit
                            },
                        )
                    } else {
                        Column(Modifier.onSizeChanged { tallPx = it.height }) {
                            DayHeader(
                                overline = Fmt.overline(model.day, model.day.minusDays(dayOffset.toLong())),
                                weekday = Fmt.weekdayTitle(model.day),
                                score = Fmt.points(r.score),
                                onScore = { nav.mode(Tab.ZASECHKA) },
                                onStats = nav.stats,
                                avatar = initial,
                                onAvatar = nav.more,
                                onWeekday = { showNav = !showNav },
                                onWeekdayDouble = { showNav = false; forced = true },
                                status = head.state?.let { st -> { DayStateLine(st, onClick = { nav.mode(Tab.SPORT) }) } },
                            )
                            if (dayOffset != 0 || showNav) {
                                DayNav(
                                    title = Fmt.dayList(model.day),
                                    onPrev = { setDay(dayOffset - 1) },
                                    onNext = { setDay(dayOffset + 1) },
                                    subtitle = "к сегодня",
                                    onTitleClick = { setDay(0) },
                                )
                            }
                            WeatherBlock(weather, head.onWeather)
                            TilesRow(model, nav, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth().bottomFade().scrollFade(list),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 14.dp, bottom = 96.dp),
            ) {
                if (items.isEmpty()) {
                    item { EmptyState("Записей за этот день нет", icon = Glyphs.Calendar) }
                }
                items(items.size, key = { items[it].key }) { i ->
                    TimelineItem(
                        items[i], lineColorAt(items, i), i < items.lastIndex && items[i + 1] is DayItem.Now, now,
                        onEntry = act.entry, onMark = act.mark, onPending = act.pending, onTask = act.task, onPlanned = act.planned,
                    )
                }
            }
        }
        // «↓ сейчас» — справа над строкой «сказать», ровно когда шапка сжата.
        AnimatedVisibility(
            visible = compact && r.nowIndex >= 0 && !nowSeen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 96.dp),
        ) {
            NowJump(Fmt.hm(now), onClick = { scope.launch { list.animateScrollToItem((r.nowIndex - 2).coerceAtLeast(0)) } })
        }
        sayBar(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
        )
    }
}

/** Что шапке «Сегодня» нужно сверх модели дня: строка состояния и тап по погоде. */
internal class TodayHead(val state: DayState?, val onWeather: () -> Unit)

@Composable
private fun WeatherBlock(weather: ru.zf.pravka.data.WeatherStore.Result?, onClick: () -> Unit) {
    val s = weather?.summary ?: return
    Box {
        WeatherRow(s.cells.map { c -> WeatherCell(skyIcon(c.sky), c.label, c.value, c.dim, c.feels) }, offline = weather.stale, onClick = onClick)
        if (weather.stale) OfflineChip(Modifier.align(Alignment.TopEnd).padding(end = 20.dp))
    }
}

internal fun skyIcon(s: WeatherDay.Sky) = when (s) {
    WeatherDay.Sky.CLEAR -> Glyphs.Sunny
    WeatherDay.Sky.PARTLY -> Glyphs.PartlyCloudy
    WeatherDay.Sky.CLOUD -> Glyphs.Cloud
    WeatherDay.Sky.FOG -> Glyphs.Foggy
    WeatherDay.Sky.RAIN -> Glyphs.Rainy
    WeatherDay.Sky.SNOW -> Glyphs.Snowy
    WeatherDay.Sky.STORM -> Glyphs.Thunder
    WeatherDay.Sky.NIGHT -> Glyphs.Bedtime
}.let { icon -> if (s == WeatherDay.Sky.RAIN) icon else icon }

/** Четыре плашки режимов: Дела, Спорт, Еда, Деньги (DESIGN §12.1). */
@Composable
private fun TilesRow(model: TodayModel, nav: TodayNav, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeTile(
            ModeDecor.DELA, Glyphs.Delo, "Дела",
            "${model.delaDone} из ${model.delaTotal}", { nav.mode(Tab.TODOIST) }, Modifier.weight(1f),
            progress = if (model.delaTotal > 0) model.delaDone / model.delaTotal.toFloat() else 0f,
            state = if (model.delaTotal == 0) TileState.EMPTY else TileState.NORMAL,
        )
        ModeTile(
            ModeDecor.SPORT, Glyphs.Sport, "Спорт",
            if (model.sportTotal > 0) "${model.sportDone} из ${model.sportTotal}" else "${model.sportDone}",
            { nav.mode(Tab.SPORT) }, Modifier.weight(1f),
            progress = if (model.sportTotal > 0) model.sportDone / model.sportTotal.toFloat() else null,
            state = if (model.sportTotal == 0 && model.sportDone == 0) TileState.EMPTY else TileState.NORMAL,
        )
        ModeTile(
            ModeDecor.FOOD, Glyphs.Food, "Еда", Fmt.num(model.kcal), { nav.mode(Tab.FOOD) }, Modifier.weight(1f),
            progress = if (model.kcalGoal > 0) model.kcal / model.kcalGoal.toFloat() else null,
            state = if (model.kcal == 0) TileState.EMPTY else TileState.NORMAL,
        )
        ModeTile(
            ModeDecor.MONEY, Glyphs.Money, "Деньги", Fmt.rub(model.spentKop), { nav.mode(Tab.MONEY) }, Modifier.weight(1f),
            progress = if (model.limitKop > 0) (-model.spentKop).toFloat() / model.limitKop else null,
            state = if (model.spentKop == 0L) TileState.EMPTY else TileState.NORMAL,
        )
    }
}

/** Сжатая шапка: «пн, 5 окт», погода строкой и пять мини-плашек (DESIGN §11.2). */
@Composable
private fun CompactHeader(model: TodayModel, weatherLine: String?, nav: TodayNav, modifier: Modifier = Modifier, onDate: (() -> Unit)? = null) {
    DayHeaderCompact(Fmt.dayShort(model.day), weatherLine, modifier = modifier, onDate = onDate, minis = {
        MiniStat(Fmt.points(model.result.score), Modes.Zasechka, "Засечка ${Fmt.points(model.result.score)}", { nav.mode(Tab.ZASECHKA) })
        MiniStat("${model.delaDone}/${model.delaTotal}", Modes.Dela, "Дела", { nav.mode(Tab.TODOIST) })
        MiniStat("${model.sportDone}/${model.sportTotal}", Modes.Sport, "Спорт", { nav.mode(Tab.SPORT) })
        MiniStat(Fmt.num(model.kcal), Modes.Food, "Еда", { nav.mode(Tab.FOOD) })
        MiniStat(Fmt.rub(model.spentKop), Modes.Money, "Деньги", { nav.mode(Tab.MONEY) })
    })
}

// ---------------------------------------------------------------------------
// Разворот (DESIGN §12.2, `screens/05`)
// ---------------------------------------------------------------------------

@Composable
private fun TodayWide(
    model: TodayModel,
    weather: ru.zf.pravka.data.WeatherStore.Result?,
    now: Long,
    dayOffset: Int,
    setDay: (Int) -> Unit,
    initial: String,
    nav: TodayNav,
    act: ItemActions,
    sayBar: @Composable (Modifier) -> Unit,
    head: TodayHead,
) {
    val t = LocalPravkaType.current
    val f = model.facts
    val r = model.result
    Row(Modifier.fillMaxSize().statusBarsPadding()) {
        // Слева — обзор: день с ‹ ›, погода, плашки, круг, плашка времени.
        Column(
            Modifier.width(386.dp).fillMaxHeight().fadingScroll().padding(start = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.weekdayTitle(model.day), style = t.titleL, color = Ink.TextStrong, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(Fmt.dayMon(model.day), style = t.valueS.copy(fontWeight = FontWeight.Normal, fontSize = 14.sp), color = Ink.TextSecondary, modifier = Modifier.weight(1f).padding(top = 8.dp))
                HeaderIcon(Glyphs.Back, "день назад", { setDay(dayOffset - 1) })
                HeaderIcon(Glyphs.Forward, "день вперёд", { setDay(dayOffset + 1) }, tint = if (dayOffset >= 0) Ink.TextDisabled else null)
            }
            head.state?.let { st -> DayStateLine(st, onClick = { nav.mode(Tab.SPORT) }) }
            Box(Modifier.padding(start = 0.dp)) {
                weather?.summary?.let { s ->
                    WeatherRow(
                        s.cells.map { c -> WeatherCell(skyIcon(c.sky), c.label, c.value, c.dim, c.feels) },
                        Modifier.padding(start = 0.dp), offline = weather.stale, onClick = head.onWeather,
                    )
                }
            }
            TilesRow(model, nav, Modifier.padding(top = 14.dp))
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Dial(
                    sectors = f.sectors,
                    nowMin = f.nowMin,
                    score = Fmt.points(f.score),
                    size = 256.dp,
                    wide = true,
                    compare = f.weekAgo?.let { w -> { DialCompare(f.score - w, Fmt.dayShort(f.weekAgoDate)) } },
                    onSector = { id -> r.items.filterIsInstance<DayItem.Entry>().firstOrNull { it.id == id }?.let(act.entry) },
                    modifier = Modifier.clickable { nav.mode(Tab.ZASECHKA) },
                )
            }
            TimePlate(
                big = f.big, sub = f.sub,
                right = if (f.aheadMin > 0) Fmt.durFuture(f.aheadMin) else null,
                rightSub = if (f.aheadMin > 0) "до сна в ${Fmt.hm(f.bedtime)}" else null,
                parts = f.barParts, legend = f.legend,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                wide = true,
            )
        }
        Spacer(Modifier.width(24.dp))
        // Справа — «Хроника» и лента, строка «сказать» внизу колонки.
        Box(Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Хроника", style = t.titleL, color = Ink.TextStrong, modifier = Modifier.weight(1f))
                    HeaderIcon(Glyphs.Stats, "статистика", nav.stats)
                    AvatarKey(initial, nav.more)
                }
                val list = rememberLazyListState(
                    initialFirstVisibleItemIndex = (r.items.indexOfFirst { it is DayItem.Entry && it.current } - 3).coerceAtLeast(0),
                )
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth().bottomFade().scrollFade(list),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                ) {
                    items(r.items.size, key = { r.items[it].key }) { i ->
                        TimelineItem(
                            r.items[i], lineColorAt(r.items, i), i < r.items.lastIndex && r.items[i + 1] is DayItem.Now, now,
                            onEntry = act.entry, onMark = act.mark, onPending = act.pending, onTask = act.task, onPlanned = act.planned,
                        )
                    }
                }
            }
            sayBar(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 18.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Лист записи (DESIGN §12.12, `screens/13`)
// ---------------------------------------------------------------------------

@Composable
private fun EntrySheet4(
    app: PravkaApp,
    e: ru.zf.pravka.data.ZasechkaStore.Entry,
    model: TodayModel,
    nav: TodayNav,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val t = LocalPravkaType.current
    val item = model.result.items.filterIsInstance<DayItem.Entry>().firstOrNull { it.id == e.id }
    val marks = model.result.items.filterIsInstance<DayItem.Mark>().filter { it.entryId == e.id }
    var noteOpen by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    ModeFrame(ModeDecor.ZASECHKA) {
        PaperSheet(
            onDismiss = onDismiss,
            title = e.title.ifBlank { e.raw.take(60) }.ifBlank { "без названия" },
            subtitle = (if (e.open) "с ${Fmt.hm(e.start)} · идёт" else "${Fmt.range(e.start, e.end)} · ${Fmt.durMs(e.end - e.start)}") +
                " · запись ленты, ${Fmt.dayList(model.day)}",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val g = ZGroup.of(e.category, item?.worth ?: 0)
                if (e.category.isNotBlank()) TagChip(e.category, g.text, g.fill.copy(alpha = 0.16f))
                if (e.client.isNotBlank()) TagChip(e.client, Ink.PlanText, androidx.compose.ui.graphics.Color.Transparent, border = Modes.Zasechka.tint.copy(alpha = 0.30f))
                if (e.useful > 0) TagChip("★${e.useful}", ru.zf.pravka.ui.PointsPlus, androidx.compose.ui.graphics.Color.Transparent, border = Modes.Zasechka.tint.copy(alpha = 0.30f))
                val p = item?.points
                if (p != null && p != 0) TagChip("${Fmt.points(p)} очков", ru.zf.pravka.ui.pointsColor(p), androidx.compose.ui.graphics.Color(0xFFFFC261).copy(alpha = 0.16f))
            }
            if (e.comment.isNotBlank()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .then(Modifier.padding(0.dp)),
                ) {
                    Text("Заметка", style = t.caption.copy(fontWeight = FontWeight.SemiBold), color = Modes.Zasechka.label)
                    Text(e.comment, style = t.body.copy(fontSize = 15.sp), color = Ink.Text)
                }
            }
            marks.forEach { m -> EventChip(m.source, m.text, { onDismiss(); openMark(m.ref, nav) }) }
            ru.zf.pravka.ui.Hairline(alpha = 0.14f)
            Row {
                IconLabel(Glyphs.Edit, "Поправить", { onDismiss(); nav.zasechkaEntry(e.id) })
                IconLabel(Glyphs.Note, "Заметка", { noteOpen = true }, active = e.comment.isNotBlank())
                IconLabel(Glyphs.Delete, "Удалить", { askDelete = true })
            }
        }
        if (noteOpen) {
            var note by remember { mutableStateOf(e.comment) }
            PaperAlert(
                onDismiss = { noteOpen = false },
                title = "Заметка",
                subtitle = e.title,
                confirm = SheetAction("Сохранить") {
                    noteOpen = false
                    scope.launch { app.zasechkaStore.setComment(e.id, note.trim()) }
                },
            ) {
                PaperField(note, { note = it }, label = "Заметка", singleLine = false, minLines = 3, maxLines = 8)
            }
        }
        if (askDelete) {
            PaperAlert(
                onDismiss = { askDelete = false },
                title = "Удалить запись?",
                subtitle = e.title,
                confirm = SheetAction("Удалить", Glyphs.Delete) {
                    askDelete = false
                    onDismiss()
                    scope.launch { app.zasechkaStore.delete(e.id) }
                },
                dismiss = SheetAction("Оставить") { askDelete = false },
            ) {
                Text("Время записи отойдёт соседям или станет «не размечено».", style = t.body, color = Ink.TextSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Сборка модели дня
// ---------------------------------------------------------------------------

private fun buildModel(
    app: PravkaApp,
    day: LocalDate,
    now: Long,
    entries: List<ru.zf.pravka.data.ZasechkaStore.Entry>,
    categories: List<ru.zf.pravka.data.ZasechkaStore.Category>,
    calendar: List<ru.zf.pravka.core.CalEvent>,
    bedtime: Int,
    marksOn: Set<String>,
    kcalGoal: Int,
    budgetRub: Long,
    pOn: Boolean,
    zOn: Boolean,
    me: String,
    delaOnServer: Boolean,
): TodayModel {
    val zone = ZoneId.systemDefault()
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = dayStart + DayFacts.DAY
    val dateKey = day.toString()
    val worthBy = categories.associate { it.name.trim().lowercase() to it.value }
    fun worth(c: String) = worthBy[c.trim().lowercase()] ?: 0

    val dayEntries = entries.filter { it.start < dayEnd && (it.open || it.end > dayStart - 12 * 3_600_000L) }
    val inputs = dayEntries.map { e ->
        DayAssembler.EntryIn(
            id = e.id, start = e.start, end = e.end,
            title = e.title.ifBlank { e.raw.take(60) }.ifBlank { e.category },
            category = e.category, worth = worth(e.category),
            client = e.client, useful = e.useful, comment = e.comment,
            gap = e.source == "gap",
        )
    }

    val marks = ArrayList<DayAssembler.MarkIn>()
    val pending = ArrayList<DayAssembler.PendingIn>()
    // Еда: подтверждённые приёмы и ждущие «Записать».
    val meals = app.foodStore.mealsOn(dateKey)
    if ("food" in marksOn) {
        meals.filter { !it.supplement }.forEach { m ->
            marks += DayAssembler.MarkIn(m.ts, DayAssembler.Source.FOOD, "${m.shortList.ifBlank { m.kind }} · ${m.kcal} ккал · Б ${m.protein}", "meal:${m.id}")
        }
    }
    app.foodStore.pending().filter { dayKey(it.ts) == dateKey }.forEach { m ->
        pending += DayAssembler.PendingIn(m.ts, DayAssembler.Source.FOOD, "${m.shortList.ifBlank { m.kind }} · ≈ ${m.kcal} ккал", "meal:${m.id}")
    }
    val kcal = meals.sumOf { it.kcal }

    // Деньги: траты дня — по Москве, как вся вкладка Денег.
    val ms = app.moneyStore.stateFlow.value
    val scope = app.moneyEngine.scope(pOn, zOn)
    val period = MoneyStats.Period(MoneyStats.Kind.WEEK, MoneyStats.startOf(day), MoneyStats.startOf(day.plusDays(1)), day, 1)
    val spent = MoneyStats.spending(ms.entries, period, scope)
    val spentKop = spent.sumOf { it.rubKop }
    if ("money" in marksOn) {
        spent.filter { it.timeKnown }.forEach { e ->
            val what = e.what.ifBlank { e.note }.ifBlank { ru.zf.pravka.core.MoneyCategories.of(e.category)?.title.orEmpty() }
            marks += DayAssembler.MarkIn(e.ts, DayAssembler.Source.MONEY, "${Fmt.rub(e.rubKop)} ₽" + (if (what.isNotBlank()) " · $what" else ""), "money:${e.id}")
        }
    }
    val monthLen = java.time.YearMonth.from(day).lengthOfMonth()
    val limitKop = if (budgetRub > 0) budgetRub * 100 / monthLen else 0L

    // Спорт: сделанное с часов, готовность, зарядка; план — в будущее.
    val done = app.sportStore.workoutsFlow.value.filter { dayKey(it.start) == dateKey }
    if ("sport" in marksOn) {
        done.forEach { w ->
            val parts = buildList {
                if (w.km >= 0.3) add(String.format(java.util.Locale.forLanguageTag("ru"), "%.2f км", w.km))
                if (w.paceSecPerKm > 0) add("%d:%02d /км".format(w.paceSecPerKm / 60, w.paceSecPerKm % 60))
                if (w.avgHr > 0) add("пульс ${w.avgHr}")
                if (isEmpty()) add("${w.name.ifBlank { w.type }} · ${Fmt.dur(w.minutes.toInt())}")
            }
            marks += DayAssembler.MarkIn(w.start, DayAssembler.Source.SPORT, parts.joinToString(" · "), "sport:${w.id}")
        }
        if (day == DayFacts.localDay(now)) {
            val v = runCatching { app.trafficLight.today(dateKey) }.getOrNull()
            if (v != null && v.headline.isNotBlank()) {
                val nums = v.numbers.take(2).joinToString(" · ") { "${it.label} ${it.value}" }
                val wake = DayReport.rhythm(entries, dayStart, now).wakeMs ?: (dayStart + 60_000L)
                marks += DayAssembler.MarkIn(wake - 60_000L, DayAssembler.Source.SPORT, "Готовность: ${v.headline.lowercase()}" + (if (nums.isNotBlank()) " · $nums" else ""), "sport:ready")
            }
        }
        app.strengthStore.gtgOn(dateKey)?.takeIf { it.any && it.ts > 0 }?.let { g ->
            val n = g.doneIds.size
            marks += DayAssembler.MarkIn(g.ts, DayAssembler.Source.SPORT, "Зарядка" + (if (n > 0) " · $n упр." else "") + (if (g.charged) " · сделана" else ""), "sport:gtg")
        }
    }
    val plan = app.planStore.dayOf(dateKey)
    val workoutsIn = plan.filterNot { it.charger }.map { p ->
        val start = p.time.takeIf { it.matches(Regex("""\d{1,2}:\d{2}""")) }?.let { hm ->
            val (h, m) = hm.split(':').map { it.toInt() }
            dayStart + (h * 60 + m) * 60_000L
        }
        DayAssembler.WorkoutIn(start, p.minutes.coerceAtLeast(15), p.shortName.ifBlank { p.name }, "", "sport:plan")
    }.filter { w -> w.start != null || done.isEmpty() }
    val sportTotal = plan.size
    val sportDone = minOf(done.size + (if (app.strengthStore.gtgOn(dateKey)?.charged == true) 1 else 0), maxOf(sportTotal, done.size))

    // Дела: «Сейчас» — дела на сегодня в их порядке; нет — просроченное и на сегодня.
    val snap = app.delaStore.view.value
    val todayKey = DayFacts.localDay(now).toString()
    val tasksById = HashMap<String, Dela.Task>()
    val tasksIn = ArrayList<DayAssembler.TaskIn>()
    var delaDone = 0
    var delaTotal = 0
    if (delaOnServer) {
        val nowList = DelaViews.nowTasks(snap, me, dateKey)
        val list = nowList.ifEmpty { DelaViews.now(snap, me, dateKey, "").pick.take(DelaViews.NOW_MAX) }
        list.forEach { t ->
            tasksById[t.id] = t
            val fixed = if (t.dueDate == dateKey && t.dueTime.isNotBlank()) {
                runCatching {
                    val (h, m) = t.dueTime.split(':').take(2).map { it.toInt() }
                    dayStart + (h * 60 + m) * 60_000L
                }.getOrNull()
            } else null
            val second = listOf(t.numLabel.takeIf { t.num > 0 }, t.projectName.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
            tasksIn += DayAssembler.TaskIn(t.id, t.title, second, t.estimateMin, fixed)
        }
        val doneToday = snap.tasks.values.filter { it.status == Dela.DONE && it.completedAt.take(10) == dateKey && (me.isBlank() || it.ownerId == me) }
        delaDone = doneToday.count { it.focusOn == dateKey }
        delaTotal = nowList.size + delaDone
        if (delaTotal == 0) {
            delaTotal = list.size + doneToday.size
            delaDone = doneToday.size
        }
        if ("dela" in marksOn) {
            doneToday.forEach { t ->
                val at = runCatching { java.time.OffsetDateTime.parse(t.completedAt).toInstant().toEpochMilli() }.getOrNull() ?: return@forEach
                tasksById[t.id] = t
                marks += DayAssembler.MarkIn(at, DayAssembler.Source.DELA, "Сделано: ${t.title}", "dela:task:${t.id}")
            }
            // Новое из встреч, почты и Telegram — пачкой: одна отметка на пачку.
            snap.suggestions.values
                .filter { it.createdAt.take(10) == dateKey && (me.isBlank() || it.forUser == me) && it.kind == "create" }
                .groupBy { it.batchRef.ifBlank { it.id } }
                .values
                .forEach { batch ->
                    val first = batch.minBy { it.createdAt }
                    val at = runCatching { java.time.OffsetDateTime.parse(first.createdAt).toInstant().toEpochMilli() }.getOrNull() ?: return@forEach
                    val from = when (first.source) {
                        "meeting" -> "встречи"
                        "telegram", "userbot" -> "Telegram"
                        "bot" -> "бота"
                        else -> "почты"
                    }
                    val text = if (batch.size > 1) "Новое из $from · ${batch.size} · разобрать"
                    else "Из $from: ${first.title}"
                    marks += DayAssembler.MarkIn(at, DayAssembler.Source.DELA, text, "dela:new")
                }
        }
    }

    val result = DayAssembler.assemble(
        DayAssembler.Input(
            dayStart = dayStart,
            now = now,
            entries = inputs,
            marks = marks,
            pending = pending,
            calendar = calendar.map { DayAssembler.CalendarIn(it.start, it.end, CalendarRules.entryTitle(it), it.calendar.takeIf { c -> c.isNotBlank() && !it.primary }.orEmpty()) },
            workouts = workoutsIn,
            tasks = tasksIn,
            bedtimeMin = bedtime,
        ),
    )
    val facts = DayFacts.of(entries, categories, day, now, bedtime, zone)
    return TodayModel(
        day, dayStart, result, facts,
        delaDone, delaTotal, sportDone, sportTotal,
        kcal, kcalGoal, spentKop, limitKop,
        tasksById, dayEntries.associateBy { it.id },
    )
}

/**
 * Настройки «Сегодня» (Правка 4.0, DESIGN §12.11): время сна — для «до сна»
 * и «дела не влезают на N м»; город погоды; какие календари показывать в
 * хронике (пусто — те же, что у автопилота Засечки); какие отметки режимов
 * прикреплять к ленте; месячный бюджет — без него полоса на плашке «Деньги»
 * не рисуется.
 */
@Composable
internal fun TodaySettings(app: PravkaApp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = app.appScope
    val s = app.settings
    val bedtime by s.todayBedtimeFlow.collectAsState(initial = ru.zf.pravka.data.Settings.TODAY_BEDTIME_DEFAULT)
    var bedDraft by remember(bedtime) { mutableStateOf(bedtime.toFloat()) }
    ru.zf.pravka.ui.PaperCard(label = "день") {
        // Шаг — четверть часа, с 21:00 до 02:00 следующих суток: после полуночи
        // хранится как минуты сверх суток (01:00 — 1500), чтобы сон оставался в этом дне.
        ru.zf.pravka.ui.PaperSlider(
            title = "Время сна",
            valueText = ru.zf.pravka.core.Fmt.hmOfMin(bedDraft.toInt() % 1440),
            value = bedDraft.coerceIn(21 * 60f, 26 * 60f),
            onValueChange = { v -> bedDraft = (v.toInt() / 15 * 15).toFloat() },
            onValueChangeFinished = { scope.launch { s.setTodayBedtime(bedDraft.toInt()) } },
            valueRange = (21 * 60f)..(26 * 60f),
            steps = (5 * 60) / 15 - 1,
            info = "Отсюда «до сна» на плашке времени и строка «дела не влезают на N м» в хронике.",
        )
    }
    val city by s.weatherCityFlow.collectAsState(initial = ru.zf.pravka.data.Settings.WEATHER_CITY_DEFAULT)
    var cityText by remember(city) { mutableStateOf(city) }
    ru.zf.pravka.ui.PaperCard(
        label = "погода",
        info = "Прогноз Open-Meteo без ключа: город один раз переводится в координаты, прогноз живёт час. " +
            "Пустой город — ряд погоды скрыт.",
    ) {
        ru.zf.pravka.ui.PaperField(
            value = cityText,
            onValueChange = { cityText = it },
            label = "Город",
            placeholder = "Москва",
        )
        if (cityText.trim() != city) {
            Spacer(Modifier.height(8.dp))
            ru.zf.pravka.ui.GhostKey("Сохранить город", { scope.launch { s.setWeatherCity(cityText) } }, icon = Glyphs.Check)
        }
    }
    // Календари: права нет — просьба; выбор пуст — как у автопилота.
    var permTick by remember { mutableStateOf(0) }
    val askPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { permTick++ }
    val chosen by s.todayCalendarsFlow.collectAsState(initial = null)
    val auto by s.autoCalendarsFlow.collectAsState(initial = null)
    ru.zf.pravka.ui.PaperCard(
        label = "календари в хронике",
        info = "Встречи календаря встают в хронику «Сегодня» после «сейчас». Не выбирал — те же " +
            "календари, что у автопилота Засечки (или основной календарь аккаунта).",
    ) {
        val granted = remember(permTick) {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            ru.zf.pravka.ui.GhostKey("Дать доступ к календарю", { askPermission.launch(android.Manifest.permission.READ_CALENDAR) }, icon = Glyphs.Calendar)
        } else {
            val cals = remember(permTick) { ru.zf.pravka.trigger.CalendarPilot.calendars(context) }
            if (cals.isEmpty()) {
                ru.zf.pravka.ui.PaperHint("Календарей на телефоне не видно.")
            } else {
                val effective = chosen ?: auto ?: cals.filter { it.primary }.map { it.name }.toSet()
                for (c in cals) {
                    ru.zf.pravka.ui.PaperToggle(
                        title = c.name + if (c.primary) " · основной" else "",
                        checked = c.name in effective,
                        onCheckedChange = { on ->
                            scope.launch { s.setTodayCalendars(if (on) effective + c.name else effective - c.name) }
                        },
                    )
                }
                if (chosen != null) {
                    ru.zf.pravka.ui.PaperTextButton("Как у автопилота", icon = Glyphs.Undo, onClick = { scope.launch { s.setTodayCalendars(null) } })
                }
            }
        }
    }
    val marks by s.todayMarksFlow.collectAsState(initial = ru.zf.pravka.data.Settings.TODAY_MARKS_DEFAULT.split(',').toSet())
    ru.zf.pravka.ui.PaperCard(
        label = "отметки в ленте",
        info = "Отметка — монета режима у записи Засечки, в чьё время случилось событие: тренировка, " +
            "приём еды, трата, сделанное дело.",
    ) {
        for ((key, title) in listOf("sport" to "Спорт", "food" to "Еда", "money" to "Деньги", "dela" to "Дела")) {
            ru.zf.pravka.ui.PaperToggle(
                title = title,
                checked = key in marks,
                onCheckedChange = { on -> scope.launch { s.setTodayMarks(if (on) marks + key else marks - key) } },
            )
        }
    }
    val stateOn by s.todayStateFlow.collectAsState(initial = true)
    ru.zf.pravka.ui.PaperCard(
        label = "состояние",
        info = "Строка под днём недели: светофор Спорта точками, сон, HRV и форма из intervals — " +
            "только за сегодня. Нет свежих данных — строки нет. Тап — Спорт.",
    ) {
        ru.zf.pravka.ui.PaperToggle(
            title = "Сон, HRV и форма под днём",
            checked = stateOn,
            onCheckedChange = { on -> scope.launch { s.setTodayState(on) } },
        )
    }
    val budget by s.moneyMonthBudgetFlow.collectAsState(initial = 0L)
    var budgetText by remember(budget) { mutableStateOf(if (budget > 0) budget.toString() else "") }
    ru.zf.pravka.ui.PaperCard(
        label = "бюджет месяца",
        info = "Сколько рублей в месяц на личные траты. Есть — на плашке «Деньги» полоса дня: " +
            "бюджет, делённый на дни месяца. Пусто — полосы нет.",
    ) {
        ru.zf.pravka.ui.PaperField(
            value = budgetText,
            onValueChange = { v -> budgetText = v.filter { it.isDigit() }.take(9) },
            label = "Рублей в месяц",
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        )
        val parsed = budgetText.toLongOrNull() ?: 0L
        if (parsed != budget) {
            Spacer(Modifier.height(8.dp))
            ru.zf.pravka.ui.GhostKey("Сохранить", { scope.launch { s.setMoneyMonthBudget(parsed) } }, icon = Glyphs.Check)
        }
    }
}
