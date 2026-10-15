package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Asks
import ru.zf.pravka.core.DayProfile
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaViews
import ru.zf.pravka.core.DigestNote
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.Lenta
import ru.zf.pravka.core.ShabbatTimes
import ru.zf.pravka.core.TodayAgenda
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.ui.Counter
import ru.zf.pravka.ui.DayMountain
import ru.zf.pravka.ui.DayNav
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.KvGlyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.ModeDecor
import ru.zf.pravka.ui.ModeTile
import ru.zf.pravka.ui.MountainValue
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.RibbonRow
import ru.zf.pravka.ui.SmallKey
import ru.zf.pravka.ui.TileState
import ru.zf.pravka.ui.bottomFade
import ru.zf.pravka.ui.categoryTone
import ru.zf.pravka.ui.twoPane
import java.time.LocalDate

// «Сегодня» вида v2 (Правка 5.0, этап 2; `docs/redesign-5-plan.md` §4, холст
// «Ковчег на телефоне», `Main.dc.html`). Сложенный — сверху вниз: «идёт
// сейчас» с горой дня, погода, «Сейчас», последний дайджест, «Новое» с «да» и
// «нет». Разложенный — «мини-компьютер»: слева плитки (Еда, Спорт, Деньги,
// Шаббат), «идёт сейчас» с горой и лента с вечера к утру; справа погода,
// «Сейчас», «Новое» и «Сегодня» (срок сегодня и встречи, завтра — строкой).
// Строка «сказать» — одна на всё, внизу (`TodaySay.kt`).
//
// Хроника 4.0 (`TodayFolded`, `TodayWide` в `TodayScreen.kt`) живёт для «Вида
// 4.0» как была: новая раскладка — только в v2.

/** Что видит «Сегодня» v2 сверх модели дня. */
internal class TodayV2Data(
    val model: TodayModel,
    val profile: DayProfile.Profile,
    val lenta: List<Lenta.Row>,
    /** Идёт сейчас; null — ничего. */
    val open: ZasechkaStore.Entry?,
    /** Последнее закрытое — когда ничего не идёт. */
    val last: ZasechkaStore.Entry?,
    val nowTasks: List<Dela.Task>,
    /**
     * «Подскажи» на «Сегодня» (`core/Asks.kt`, docs/dela-phone-23.md): с флагом `asks` у сервера — по фазе (до 15:00
     * строкой, с 15:00 — сгорающее, вечером — группами, с Пт 15:00 — тихо), без него — прежний список «Нового».
     */
    val queue: Asks.Queue,
    val todayRows: List<TodayAgenda.Row>,
    val tomorrow: String,
    val snap: Dela.Snapshot,
    val todayKey: String,
    val tiles: TodayTiles,
    /** Дела подключены: без них «Сейчас» и «Новое» не показываются. */
    val dela: Boolean,
    /** В ленте идёт или только что была «Еда», а в дневнике пусто (`MealGap`); null — подсказки нет. */
    val mealGap: ru.zf.pravka.core.MealGap.Gap? = null,
    /**
     * Напоминания в свой час и свои отметки календаря (захват, 15.10.2026, `TodayAgenda.reminderRows`): «12:00 ⏰
     * Отправить счёт в Контуре», пора — «✓ · +1 ч · завтра».
     */
    val reminders: List<TodayAgenda.RemindRow> = emptyList(),
    /** «Вернулось» — дела «потом», чей день настал (`TodayAgenda.backRows`). */
    val back: List<TodayAgenda.BackRow> = emptyList(),
    /** «Сейчас на завтра» (docs/dela-phone-27.md): строкой последней в «Новом»; null — строки нет. */
    val plan: DelaViews.Plan? = null,
)

/** Числа плиток разложенного: еда, спорт, деньги, Шаббат. */
internal class TodayTiles(
    val kcal: Int,
    val kcalGoal: Int,
    val sportValue: String?,
    val sportUnit: String?,
    val sportSub: String?,
    val moneyDayKop: Long,
    val moneyMonth: String?,
    val shabbat: ShabbatTimes.Times?,
)

/** Действия «Сегодня» v2: открыть дело, сделать, решить «Новое», остановить идущее. */
internal class TodayV2Actions(
    val done: (Dela.Task) -> Unit,
    val accept: (Dela.Suggestion) -> Unit,
    val reject: (Dela.Suggestion, String) -> Unit,
    val stop: () -> Unit,
    /** «Раньше» у дайджеста: сводки за двое суток без [currentId]; нет сети или старый сервер — исключение. */
    val earlier: suspend (currentId: String) -> List<DigestNote>,
    /** Подсказка «в ленте едим, а в дневнике пусто»: записать голосом или снимком — дороги Еды. */
    val mealVoice: () -> Unit,
    val mealPhoto: () -> Unit,
    val shabbat: () -> Unit,
    val lenta: (Lenta.Row) -> Unit,
    /** Ответ вариантом «Подскажи» («Не для CRM», «Никто»), где кнопок не видно, — `suggestion.decide` с `option`. */
    val answer: (Dela.Suggestion, DelaViews.Option) -> Unit = { sg, _ -> reject(sg, "") },
    /** Кнопка напоминания на «Сегодня» — та же, что в уведомлении: «Сделано», «Через час», «Завтра». */
    val remind: (taskId: String, ru.zf.pravka.trigger.Reminders.Act) -> Unit = { _, _ -> },
    /** «Вернулось»: «В „Сейчас“», «Сегодня», «Ещё потом», «Сделано». */
    val back: (taskId: String, BackTo) -> Unit = { _, _ -> },
)

/** Куда деть вернувшееся «потом» (`backBox` веба). */
internal enum class BackTo(val label: String) { NOW("В «Сейчас»"), TODAY("Сегодня"), LATER("Ещё потом"), DONE("Сделано") }

/**
 * «Вернулось» → куда (`backBox` веба): в «Сейчас» и на сегодня — «потом» снимается; «Ещё потом» — на понедельник;
 * «Сделано» — закрыть. Обычные операции очереди; что сказать — после ответа сервера и его словами.
 */
internal suspend fun backMove(app: PravkaApp, taskId: String, to: BackTo): String {
    app.delaStore.load()
    val snap = app.delaStore.view.value
    val t = snap.tasks[taskId] ?: return "Дела уже нет"
    val today = DayFacts.localDay(System.currentTimeMillis()).toString()
    val me = app.delaSync.link.value?.user.orEmpty()
    val clear = { o: org.json.JSONObject -> o.put("back_on", org.json.JSONObject.NULL).put("back_said", org.json.JSONObject.NULL) }
    val (set, said) = when (to) {
        BackTo.NOW -> {
            if (!DelaViews.nowRoom(snap, me, today, listOf(t.id))) return DelaViews.NOW_FULL
            clear(org.json.JSONObject().put("focus_on", today)) to "В «Сейчас»"
        }
        BackTo.TODAY -> clear(org.json.JSONObject().put("due_date", today)) to "Срок — сегодня"
        BackTo.LATER -> TodayAgenda.backLater(today).let { d ->
            org.json.JSONObject().put("back_on", d) to "Верну в понедельник ${DelaViews.ddmm(d, today)}"
        }
        BackTo.DONE -> return app.delaSend(listOf(Dela.statusOp("task.done", t.id)), "✓ ${t.title.take(40)}")
    }
    return app.delaSend(listOf(Dela.setOp(t.id, set, ru.zf.pravka.core.DelaGroups.was(t, set))), said)
}

@Composable
internal fun TodayV2(
    data: TodayV2Data,
    weather: ru.zf.pravka.data.WeatherStore.Result?,
    digest: DigestNote?,
    now: Long,
    dayOffset: Int,
    setDay: (Int) -> Unit,
    nav: TodayNav,
    head: TodayHead,
    act: TodayV2Actions,
    sayBar: @Composable (Modifier) -> Unit,
) {
    var weatherOpen by remember { mutableStateOf(false) }
    var digestOpen by remember { mutableStateOf(false) }
    var rejecting by remember { mutableStateOf<Dela.Suggestion?>(null) }
    var showNav by remember { mutableStateOf(false) }
    // «Подскажи», раскрытое тапом, — до смены фазы (`Asks.Queue.openKey`). Здесь, а не в карточке: сложил или разложил
    // телефон — карточка переезжает в другую колонку, а раскрытое остаётся раскрытым.
    var asksOpen by rememberSaveable { mutableStateOf<String?>(null) }
    val cards = TodayCards(
        data = data,
        weather = weather,
        weatherOpen = weatherOpen,
        onWeather = { weatherOpen = !weatherOpen },
        digest = digest,
        digestOpen = digestOpen,
        onDigest = { digestOpen = !digestOpen },
        now = now,
        today = dayOffset == 0,
        nav = nav,
        act = act,
        onReject = { rejecting = it },
        asksOpen = asksOpen,
        onAsksOpen = { asksOpen = it },
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = twoPane()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TodayHeaderV2(data.model, now, dayOffset, nav, head, onTitle = { showNav = !showNav }, onTitleDouble = { setDay(0) })
            if (dayOffset != 0 || showNav) {
                Box(Modifier.padding(horizontal = 12.dp)) {
                    DayNav(
                        title = Fmt.dayList(data.model.day),
                        onPrev = { setDay(dayOffset - 1) },
                        onNext = { setDay(dayOffset + 1) },
                        subtitle = "к сегодня",
                        onTitleClick = { setDay(0) },
                    )
                }
            }
            if (wide) WideBody(cards, Modifier.weight(1f)) else FoldedBody(cards, Modifier.weight(1f))
        }
        sayBar(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
        )
    }
    // «Нет» — тот же лист причины, что во вкладке Дел (`RejectSheet`): цитата, готовые причины, своя голосом.
    rejecting?.let { sg ->
        RejectSheet(sg, onDismiss = { rejecting = null }, onReject = { reason ->
            rejecting = null
            act.reject(sg, reason)
        })
    }
}

/** Карточки «Сегодня» — общие для обеих раскладок. */
private class TodayCards(
    val data: TodayV2Data,
    val weather: ru.zf.pravka.data.WeatherStore.Result?,
    val weatherOpen: Boolean,
    val onWeather: () -> Unit,
    val digest: DigestNote?,
    val digestOpen: Boolean,
    val onDigest: () -> Unit,
    val now: Long,
    val today: Boolean,
    val nav: TodayNav,
    val act: TodayV2Actions,
    val onReject: (Dela.Suggestion) -> Unit,
    /** «Подскажи» раскрыто тапом в этой фазе (`Asks.Queue.openKey`); null — нет. */
    val asksOpen: String?,
    val onAsksOpen: (String) -> Unit,
)

// ---------------------------------------------------------------------------
// Раскладки
// ---------------------------------------------------------------------------

/** Сложенный (412 dp): одна колонка карточек, строка «сказать» поверх низа. */
@Composable
private fun FoldedBody(c: TodayCards, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .bottomFade()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NowCard(c, wide = false)
        MealGapLine(c)
        c.weather?.takeIf { it.ahead.isNotEmpty() || it.hours.isNotEmpty() }?.let { WeatherCardV2(it, c.weatherOpen, c.onWeather) }
        if (c.data.dela) NowTasksCard(c, wide = false)
        // Захват (15.10.2026): напоминания в свой час и «Вернулось» — под «Сейчас», как панели веба.
        RemindCard(c)
        BackCard(c)
        c.digest?.let { DigestCard(it, c.digestOpen, c.onDigest, onNew = { digestNew(c, it) }, onEarlier = c.act.earlier) }
        if (c.data.dela) NewCard(c, wide = false)
    }
}

/**
 * Разложенный (≥ 600 dp, «мини-компьютер»): слева плитки — всегда видны, —
 * «идёт сейчас» и лента, растворяющаяся книзу; справа своя прокрутка.
 */
@Composable
private fun WideBody(c: TodayCards, modifier: Modifier) {
    Row(modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 76.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TilesGrid(c)
            NowCard(c, wide = true)
            MealGapLine(c)
            LentaCard(c, Modifier.weight(1f, fill = false))
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .bottomFade()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            c.weather?.takeIf { it.ahead.isNotEmpty() || it.hours.isNotEmpty() }?.let { WeatherCardV2(it, c.weatherOpen, c.onWeather) }
            if (c.data.dela) NowTasksCard(c, wide = true)
            RemindCard(c)
            BackCard(c)
            c.digest?.let { DigestCard(it, c.digestOpen, c.onDigest, onNew = { digestNew(c, it) }, onEarlier = c.act.earlier) }
            if (c.data.dela) NewCard(c, wide = true)
            TodayAgendaCard(c)
        }
    }
}

// ---------------------------------------------------------------------------
// Детали
// ---------------------------------------------------------------------------

/** Заголовок карточки — `.sec` холста: значок, название, счётчик, мелкая подпись, справа ссылка. */
@Composable
private fun SecHead(
    icon: ImageVector?,
    title: String,
    count: Int? = null,
    hot: Boolean = false,
    small: String? = null,
    link: String? = null,
    onLink: (() -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, tint = Kv.icon, modifier = Modifier.size(18.dp))
        Text(title, style = t.bodyStrong, color = Kv.text, maxLines = 1)
        if (count != null && count > 0) Counter(count.toString(), hot = hot)
        // Подпись — переносом по словам, а не «…» (14.10.2026): у дайджеста в ней время и счёт чатов, обрезанные
        // они теряли смысл. Число держится за своё слово, как в шапке (`HeadLine.wrap`).
        if (small != null) Text(ru.zf.pravka.core.HeadLine.wrap(small), style = t.meta, color = Kv.faint, modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        if (link != null && onLink != null) {
            Text(
                link,
                style = t.label,
                color = Kv.accent,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(KvShape.Tag)).clickable(role = Role.Button, onClick = onLink).padding(horizontal = 2.dp, vertical = 2.dp),
            )
        }
    }
}

/** Значок набора по имени веба; нет — запасной. */
private fun kv(key: String, fallback: ImageVector): ImageVector = KvGlyphs.byKey(key) ?: fallback

/** Надстрочник — `.eyebrow`: 12/600, бледно. */
@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, style = LocalPravkaType.current.overline, color = Kv.faint, maxLines = 1, modifier = modifier)
}

/** Тонкая линия между строками списка (`.li` + `.li`). */
@Composable
private fun ListLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(Kv.lineSoft))

// ---- идёт сейчас ----

/**
 * «Идёт сейчас» крупно (название 20/600, таймер 30/600, «Стоп») и гора дня
 * под ним. Ничего не идёт — что было последним; другой день — гора этого дня.
 */
@Composable
private fun NowCard(c: TodayCards, wide: Boolean) {
    val t = LocalPravkaType.current
    val e = c.data.open.takeIf { c.today }
    PaperCard {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(KvShape.Control)).clickable(onClickLabel = "Засечка") { c.nav.mode(Tab.ZASECHKA) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(
                    when {
                        !c.today -> "Засечка · ${Fmt.dayList(c.data.model.day)}"
                        e != null -> "Засечка · идёт"
                        else -> "Засечка · ничего не идёт"
                    },
                    Modifier.weight(1f),
                )
                if (wide) Text("Засечка", style = t.label, color = Kv.accent, maxLines = 1)
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val title = when {
                        e != null -> e.title.ifBlank { e.raw.take(60) }.ifBlank { e.category }
                        !c.today -> "Итог дня"
                        else -> "Скажи, чем занят"
                    }
                    Text(title, style = t.valueL.copy(fontSize = 20.sp, lineHeight = 24.sp), color = Kv.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        e != null -> listOf(e.category, "с " + Fmt.hm(e.start)).filter { it.isNotBlank() }.joinToString(" · ")
                        c.today -> c.data.last?.let { l -> "последнее — «${l.title.ifBlank { l.category }}» до ${Fmt.hm(l.end)}" }
                        else -> null
                    }
                    if (sub != null) {
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (e != null && e.category.isNotBlank()) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(categoryTone(e.category)))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(sub, style = t.caption, color = Kv.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (e != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(timer(c.now - e.start), style = t.valueXL.copy(fontSize = 30.sp, lineHeight = 32.sp), color = Kv.text, maxLines = 1)
                    if (wide) {
                        Spacer(Modifier.width(8.dp))
                        SmallKey(kv("stop", Glyphs.Stop), null, "Стоп: ${e.title}", c.act.stop)
                    }
                }
            }
            DayMountain(c.data.profile, Modifier.padding(top = 10.dp), height = if (wide) 58.dp else 50.dp)
            MountainValue(
                c.data.profile,
                Modifier.padding(top = 6.dp),
                trailing = if (e != null && !wide) ({ SmallKey(kv("stop", Glyphs.Stop), "Стоп", "Стоп: ${e.title}", c.act.stop) }) else null,
            )
        }
    }
}

/**
 * «Сейчас в ленте «Обед», а в дневнике пусто. Записать?» — под «идёт сейчас»: про
 * то же самое, что идёт (доводка 5.0). Только сегодня; «не надо» прячет до перезапуска.
 */
@Composable
private fun MealGapLine(c: TodayCards) {
    val g = c.data.mealGap?.takeIf { c.today && it.entryId !in MealGapDismissed.ids } ?: return
    MealGapCallout(g, onVoice = c.act.mealVoice, onPhoto = c.act.mealPhoto)
}

/** «0:08» — сколько идёт дело: часы и минуты. */
private fun timer(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0L)
    return "${m / 60}:" + "%02d".format(m % 60)
}

// ---- Сейчас ----

/** «Сейчас» — дела, отмеченные владельцем: квадрат-галка, название, срок и кто. */
@Composable
private fun NowTasksCard(c: TodayCards, wide: Boolean) {
    val t = LocalPravkaType.current
    val list = c.data.nowTasks
    PaperCard {
        SecHead(kv("bolt", Glyphs.Bolt), "Сейчас", list.size, hot = true, small = "отмечено тобой", link = "Дела", onLink = { c.nav.mode(Tab.TODOIST) })
        if (list.isEmpty()) {
            Text("Ничего не отмечено — «Сейчас» ставится в Делах.", style = t.meta, color = Kv.faint)
        }
        list.forEachIndexed { i, task ->
            if (i > 0) ListLine()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KvShape.Control))
                    .clickable { c.nav.delaTask(task.id) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Квадрат-галка `.chk` и зазор 10 до названия; касание — вся высота строки.
                Box(
                    Modifier
                        .size(width = 28.dp, height = 40.dp)
                        .clip(RoundedCornerShape(KvShape.Control))
                        .clickable(role = Role.Checkbox, onClickLabel = "сделано") { c.act.done(task) }
                        .semantics { contentDescription = "сделано: ${task.title}" },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(Kv.panel).border(1.5.dp, Kv.check, RoundedCornerShape(5.dp)))
                }
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = t.body.copy(fontSize = if (wide) 14.sp else 14.5.sp), color = Kv.text, maxLines = if (wide) 2 else 1, overflow = TextOverflow.Ellipsis)
                    val due = DelaViews.due(task, c.data.todayKey)
                    val who = task.personShort.ifBlank { task.projectName }
                    val rest = listOfNotNull(
                        task.estimateMin.takeIf { it > 0 && due == null }?.let { Fmt.dur(it) },
                        if (task.ball == Dela.WAITING) "жду" else null,
                        who.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (due != null || rest.isNotBlank()) {
                        Text(
                            androidx.compose.ui.text.buildAnnotatedString {
                                if (due != null) {
                                    pushStyle(androidx.compose.ui.text.SpanStyle(color = if (due.late) Kv.warn else Kv.accent))
                                    append(if (due.late) "просрочено · " + due.text.substringAfter(" · ") else due.text)
                                    pop()
                                    if (rest.isNotBlank()) append(" · ")
                                }
                                append(rest)
                            },
                            style = t.meta,
                            color = Kv.faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---- Напоминания и «Вернулось» (захват, 15.10.2026) ----

/**
 * «Напоминания» — в свой час, отдельно от кучи дел (владелец: «напоминание — отдельно от кучи дел: стоит на „Сегодня“
 * в своё время… после срабатывания — „Сделано / Через час / Завтра“ и исчезает»). Строка: время ⏰, что, подпись;
 * пора — кнопки «✓ · +1 ч · завтра» (те же, что в уведомлении). Своя отметка календаря («Свободен») — строкой
 * «отметка»: встречей её Засечка не считает. Пусто — карточки нет. Только сегодня.
 */
@Composable
private fun RemindCard(c: TodayCards) {
    val rows = c.data.reminders
    if (!c.today || rows.isEmpty()) return
    val t = LocalPravkaType.current
    PaperCard {
        SecHead(kv("clock", Glyphs.Bell), "Напоминания", rows.count { !it.mark }, small = remindSub(c.data.snap), link = "Дела", onLink = { c.nav.mode(Tab.TODOIST) })
        rows.forEachIndexed { i, r ->
            if (i > 0) ListLine()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KvShape.Control))
                    .then(if (r.taskId != null) Modifier.clickable { c.nav.delaTask(r.taskId) } else Modifier)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    r.whenText + if (r.mark) "" else " ⏰",
                    style = t.time,
                    color = if (r.due && !r.mark) Kv.accent else Kv.faint,
                    maxLines = 2,
                    modifier = Modifier.width(72.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = t.label.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Normal), color = Kv.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (r.sub.isNotBlank()) Text(r.sub, style = t.caption, color = Kv.faint, maxLines = 1)
                }
                val id = r.taskId
                if (id != null && r.due) {
                    SmallKey(kv("check", Glyphs.Check), null, "Сделано: ${r.title}", { c.act.remind(id, ru.zf.pravka.trigger.Reminders.Act.DONE) }, soft = true)
                    SmallKey(null, "+1 ч", "Напомнить через час: ${r.title}", { c.act.remind(id, ru.zf.pravka.trigger.Reminders.Act.HOUR) })
                    SmallKey(null, "завтра", "Напомнить завтра в 9:00: ${r.title}", { c.act.remind(id, ru.zf.pravka.trigger.Reminders.Act.TOMORROW) })
                }
            }
        }
    }
}

/**
 * «Вернулось» — дела «потом», чей день настал («позвонить Ивану на неделе» → в пятницу здесь): «↩ Позвонить Ивану ·
 * ты сказал «на неделе»», тап — выбор «В „Сейчас“ · Сегодня · Ещё потом · Сделано». Не тронул за день — сервер ночью
 * двигает его на понедельник: ничего не пропадает.
 */
@Composable
private fun BackCard(c: TodayCards) {
    val rows = c.data.back
    if (!c.today || rows.isEmpty()) return
    val t = LocalPravkaType.current
    var open by remember { mutableStateOf<TodayAgenda.BackRow?>(null) }
    PaperCard {
        SecHead(kv("refresh", Glyphs.Refresh), "Вернулось", rows.size, small = "ты отложил «потом» — пора решить")
        rows.forEachIndexed { i, r ->
            if (i > 0) ListLine()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KvShape.Control))
                    .clickable(onClickLabel = "что с ним") { open = r }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("↩", style = t.time, color = Kv.accent)
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = t.label.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Normal), color = Kv.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(r.sub, style = t.caption, color = Kv.faint, maxLines = 1)
                }
            }
        }
    }
    open?.let { r ->
        ru.zf.pravka.ui.PaperSheet(onDismiss = { open = null }, title = r.title, subtitle = r.sub) {
            for (to in BackTo.entries) {
                ru.zf.pravka.ui.PaperRow(title = to.label, onClick = { open = null; c.act.back(r.taskId, to) })
            }
            ru.zf.pravka.ui.PaperRow(title = "Открыть дело", onClick = { open = null; c.nav.delaTask(r.taskId) })
        }
    }
}

// ---- дайджест ----

/** «Открыть «Новое»» — к пачке этой сводки (`dela.batch`), а не во всё «Новое»; пачки нет — вкладка Дел. */
private fun digestNew(c: TodayCards, d: DigestNote) {
    if (d.batch.isNotBlank()) c.nav.delaBatch(d.batch) else c.nav.mode(Tab.TODOIST)
}

/**
 * Последний дайджест — того же размера, что соседние карточки, растворяется
 * книзу; нажатие раскрывает целиком (`.dg`, `.dgb` холста). Раскрытая — ещё
 * «Раньше»: сводки за двое суток видом сервера, по запросу при раскрытии.
 */
@Composable
private fun DigestCard(
    d: DigestNote,
    open: Boolean,
    onToggle: () -> Unit,
    onNew: () -> Unit,
    onEarlier: suspend (String) -> List<DigestNote>,
) {
    val t = LocalPravkaType.current
    PaperCard {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(KvShape.Control)).clickable(onClickLabel = if (open) "свернуть дайджест" else "раскрыть дайджест", onClick = onToggle)) {
            SecHead(
                kv("digest", Glyphs.Note),
                "Дайджест",
                small = listOf(d.title.ifBlank { Fmt.hm(d.at) }, d.counts).filter { it.isNotBlank() }.joinToString(" · "),
                link = if (open) "свернуть" else "раскрыть",
                onLink = onToggle,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (open) Modifier else Modifier.heightIn(max = 92.dp).bottomFade(bar = 0.dp, fade = 60.dp)),
            ) {
                DigestBlocks(d)
            }
            // Раскрытая: что ушло в Дела из окна и где это ждёт; текст сводки — в буфер (docs/digest-phone.md, п. 2).
            if (open) {
                if (d.dela.isNotBlank()) {
                    Text(d.dela, style = t.meta, color = Kv.faint, modifier = Modifier.padding(top = 8.dp))
                }
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val context = LocalContext.current
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d.dela.isNotBlank()) SmallKey(kv("inbox", Glyphs.Note), "Открыть «Новое»", "Открыть «Новое» этой сводки в Делах", onNew)
                    if (d.text.isNotBlank()) SmallKey(kv("copy", Glyphs.Copy), "Скопировать", "Скопировать сводку", {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(d.text))
                        Feedback.toast(context, "Сводка скопирована")
                    })
                }
            }
        }
        if (open) DigestEarlier(d, onEarlier)
    }
}

/** Тело сводки: разделы надстрочником, пункты — «Кто (чат):» жирным и что. */
@Composable
private fun DigestBlocks(d: DigestNote) {
    val t = LocalPravkaType.current
    for (b in d.blocks()) {
        if (b.heading) {
            Eyebrow(b.text, Modifier.padding(top = 6.dp))
        } else {
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    if (b.lead.isNotBlank()) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold))
                        append(b.lead)
                        pop()
                        append(" ")
                    }
                    append(b.text)
                },
                style = t.label.copy(fontSize = 13.5.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Normal),
                color = Kv.text,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/**
 * «Раньше» раскрытого дайджеста (`docs/digest-phone.md`, п. 2): вид сервера
 * `digest?days=2` — по запросу при раскрытии, только в сети, без кэша (это
 * чужая переписка: на диск и в журнал не ложится). Сводка — свёрнутой строкой
 * «подпись · первые слова», нажатие раскрывает её на месте. Без сети — «только
 * последний»; старый сервер или отказ — причина словами (правило 6).
 */
@Composable
private fun DigestEarlier(d: DigestNote, load: suspend (String) -> List<DigestNote>) {
    val t = LocalPravkaType.current
    var list by remember(d.id) { mutableStateOf<List<DigestNote>?>(null) }
    var error by remember(d.id) { mutableStateOf("") }
    var opened by remember(d.id) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(d.id) {
        try {
            list = load(d.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Нет кода HTTP — не дошли до сервера: это «без сети», а не поломка.
            error = if (e is ru.zf.pravka.data.DelaSync.DelaException && e.code > 0) e.message.orEmpty().ifBlank { "не вышло" }
            else "без сети — только последний"
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        ListLine()
        Eyebrow("Раньше", Modifier.padding(top = 8.dp))
        val l = list
        when {
            error.isNotEmpty() -> Text(error, style = t.meta, color = Kv.faint, modifier = Modifier.padding(top = 4.dp))
            l == null -> Text("загружаю сводки за двое суток…", style = t.meta, color = Kv.faint, modifier = Modifier.padding(top = 4.dp))
            l.isEmpty() -> Text("За двое суток других сводок нет.", style = t.meta, color = Kv.faint, modifier = Modifier.padding(top = 4.dp))
            else -> l.forEachIndexed { i, x ->
                if (i > 0) ListLine()
                val isOpen = opened == x.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(KvShape.Control))
                        .clickable(onClickLabel = if (isOpen) "свернуть сводку" else "раскрыть сводку") { opened = if (isOpen) null else x.id }
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = Kv.text))
                            append(x.title.ifBlank { Fmt.hm(x.at) })
                            pop()
                            if (!isOpen) {
                                append(" · ")
                                append(x.preview())
                            } else if (x.counts.isNotBlank()) {
                                append(" · ")
                                append(x.counts)
                            }
                        },
                        style = t.label.copy(fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
                        color = Kv.faint,
                        maxLines = if (isOpen) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isOpen) {
                        DigestBlocks(x)
                        if (x.dela.isNotBlank()) Text(x.dela, style = t.meta, color = Kv.faint, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

// ---- Новое ----

/** Сколько строк «Нового» на «Сегодня» без флага `asks`; остальное — во вкладке Дел. */
private const val NEW_MAX = 4

/**
 * «Подскажи» — то, что ждёт «да» владельца: название, почему спрашивает или откуда; «да» — поставить как предложено,
 * «нет» — с причиной (материал промптов). С флагом `asks` у сервера (шаг 2 петли, docs/dela-phone-23.md) карточка
 * раскрыта по фазе — раскладку решает `Asks.card` (под JVM-тестом), здесь только рисуется:
 * - до 15:00, в тишине выходных и с 15:00 без сгорающего — одна строка «Подскажи · к 15:00: 1 · к вечеру: 6», тап —
 *   раскрыть всё до смены фазы;
 * - с 15:00 — только сгорающее (до `today_max`), под ним «к вечеру: M»;
 * - вечером (или раскрыто) — всё группами «ООО Ромашка · 2» до `evening_max`, дальше «ещё K — в «Новом»».
 * Без флага — как раньше: «Новое», четыре свежих сверху. Последней строкой — «Сейчас на завтра» (docs/dela-phone-27.md):
 * кандидаты на завтра в «Подскажи» не входят. Вопросов нет и плана нет — карточки нет.
 */
@Composable
private fun NewCard(c: TodayCards, wide: Boolean) {
    val q = c.data.queue
    val plan = c.data.plan
    val card = Asks.card(q, c.asksOpen, NEW_MAX)
    if (card == null && plan == null) return
    val all = { c.nav.mode(Tab.TODOIST) }
    PaperCard {
        if (card != null) when (card.mode) {
            Asks.Mode.LEGACY -> {
                SecHead(kv("inbox", Glyphs.Note), "Новое", card.count, hot = true, small = card.sub, link = if (card.more > 0) "все" else null, onLink = all)
                card.rows.forEachIndexed { i, sg ->
                    if (i > 0) ListLine()
                    AskRow(c, sg, wide)
                }
            }
            Asks.Mode.FOLD -> AsksFold(card.fold.orEmpty(), lead = true) { c.onAsksOpen(q.openKey) }
            Asks.Mode.URGENT -> {
                SecHead(kv("inbox", Glyphs.Note), "Подскажи", card.count, hot = true, small = card.sub, link = "все", onLink = all)
                card.rows.forEachIndexed { i, sg ->
                    if (i > 0) ListLine()
                    AskRow(c, sg, wide)
                }
                if (card.more > 0) {
                    ListLine()
                    AsksMore("ещё сгорает ${card.more} — в «Новом»", all)
                }
                card.fold?.let {
                    ListLine()
                    AsksFold(it, lead = false) { c.onAsksOpen(q.openKey) }
                }
            }
            Asks.Mode.GROUPED -> {
                SecHead(kv("inbox", Glyphs.Note), "Подскажи", card.count, hot = true, small = card.sub, link = "все", onLink = all)
                card.groups.forEachIndexed { gi, (g, items) ->
                    AsksGroupHead(g, top = gi > 0)
                    items.forEachIndexed { i, sg ->
                        if (i > 0) ListLine()
                        AskRow(c, sg, wide)
                    }
                }
                if (card.more > 0) {
                    ListLine()
                    AsksMore("ещё ${card.more} — в «Новом»", all)
                }
            }
        }
        if (plan != null) {
            if (card != null) ListLine()
            PlanRow(c, plan)
        }
    }
}

/** Один вопрос «Подскажи»: что предложено, почему или откуда; «Да» (или «Ответить» в Делах) и «Нет». */
@Composable
private fun AskRow(c: TodayCards, sg: Dela.Suggestion, wide: Boolean) {
    val t = LocalPravkaType.current
    val txt = DelaViews.sugText(sg, c.data.snap, c.data.todayKey)
    val why = DelaViews.askWhy(sg, c.data.snap) ?: DelaViews.heldLine(sg, null)?.takeIf { DelaViews.choiceOnly(sg) }
    val from = sg.batchTitle.ifBlank { DelaViews.mark(sg.source).word }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(txt.title, style = t.body.copy(fontSize = if (wide) 14.sp else 14.5.sp), color = Kv.text, maxLines = if (wide) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(
                why ?: listOf(DelaViews.askYes(sg.kind).lowercase().let { if (sg.kind == "create") "новое дело" else it }, txt.hint, from)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = t.meta,
                color = if (why != null) Kv.warn else Kv.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        // Вопрос со своими ответами или выбором человека (аудит №1, №25): «Да» сервер не примет — ответ в Делах,
        // где варианты кнопками. «Нет» — вариантом «нет» вопроса («Никто» и т. п.), у остального — лист причины.
        // У вопроса о человеке — всегда лист: «нет» там — навсегда (служба больше не спросит), а маленький «✕»
        // легко задеть, и ушедший ответ из приложения не вернуть (проверка 15.10.2026).
        if (DelaViews.plainYes(sg, c.data.snap)) {
            SmallKey(kv("check", Glyphs.Check), if (wide) "Да" else null, "Да: ${txt.title}", { c.act.accept(sg) }, soft = true)
        } else {
            SmallKey(kv("arrow-right", Glyphs.Forward), "Ответить", "Ответить в Делах: ${txt.title}", {
                if (sg.batchRef.isNotBlank()) c.nav.delaBatch(sg.batchRef) else c.nav.mode(Tab.TODOIST)
            }, soft = true)
        }
        val no = DelaViews.rejectOption(sg, c.data.snap)
        SmallKey(kv("x", Glyphs.Close), null, "Нет: ${txt.title}", {
            when {
                sg.kind == "person" -> c.onReject(sg)
                no != null -> c.act.answer(sg, no)
                else -> c.onReject(sg)
            }
        })
    }
}

/**
 * Свёрнутая очередь одной строкой: «Подскажи · к 15:00: 1 · к вечеру: 6» (или «к вечеру: M» под сгорающим) и
 * «раскрыть» — тап раскрывает всё до смены фазы. [lead] — значок «Подскажи» слева (когда строка — вся карточка).
 */
@Composable
private fun AsksFold(text: String, lead: Boolean, onOpen: () -> Unit) {
    val t = LocalPravkaType.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KvShape.Control))
            .clickable(role = Role.Button, onClickLabel = "раскрыть «Подскажи»", onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead) {
            Icon(kv("help", Glyphs.Ask), null, tint = Kv.warn, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(ru.zf.pravka.core.HeadLine.wrap(text), style = if (lead) t.body else t.meta, color = Kv.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Text("раскрыть", style = t.label, color = Kv.accent, maxLines = 1)
    }
}

/** Подзаголовок группы вечером: «ООО Ромашка · 2», сгорающее в ней — «· сгорает 1» тоном внимания. */
@Composable
private fun AsksGroupHead(g: Asks.Group, top: Boolean) {
    val t = LocalPravkaType.current
    val hot = Asks.groupUrgent(g)
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append(Asks.groupHead(g))
            if (hot != null) {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Kv.warn))
                append(" · $hot")
                pop()
            }
        },
        style = t.caption,
        color = Kv.faint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = if (top) 8.dp else 0.dp, bottom = 2.dp),
    )
}

/** «ещё K — в «Новом»» — тап во вкладку Дел, где «Новое» показывает всё всегда. */
@Composable
private fun AsksMore(text: String, onOpen: () -> Unit) {
    val t = LocalPravkaType.current
    Text(
        text,
        style = t.meta,
        color = Kv.accent,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KvShape.Control))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = 6.dp),
    )
}

/**
 * «Сейчас на завтра: предложено 7 · отмечено 2 из 5 · Открыть» — одна строка в «Новом» (docs/dela-phone-27.md):
 * тап — пачка кандидатов `plan:<день>` во вкладке Дел, там «☆ На завтра · ✓ Уже сделал · Не надо» у каждого.
 */
@Composable
private fun PlanRow(c: TodayCards, plan: DelaViews.Plan) {
    val t = LocalPravkaType.current
    val open = { c.nav.delaBatch(plan.batch) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KvShape.Control))
            .clickable(onClickLabel = "открыть кандидатов", onClick = open)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.Bolt, null, tint = Kv.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(ru.zf.pravka.core.HeadLine.wrap(DelaViews.planLine(plan)), style = t.meta, color = Kv.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        SmallKey(kv("arrow-right", Glyphs.Forward), "Открыть", "Открыть «Сейчас ${plan.label}»", open, soft = true)
    }
}

// ---- Сегодня (разложенный) ----

/** «Сегодня» — срок сегодня и встречи календаря по времени; завтрашнее — строкой под ними. */
@Composable
private fun TodayAgendaCard(c: TodayCards) {
    val t = LocalPravkaType.current
    val rows = c.data.todayRows
    PaperCard {
        SecHead(kv("sun", Glyphs.Sunny), "Сегодня", rows.size, small = "срок и встречи на сегодня")
        if (rows.isEmpty()) Text("Сроков и встреч на сегодня нет.", style = t.meta, color = Kv.faint)
        rows.forEachIndexed { i, r ->
            if (i > 0) ListLine()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KvShape.Control))
                    .then(if (r.taskId != null) Modifier.clickable { c.nav.delaTask(r.taskId) } else Modifier)
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(r.whenText, style = t.time, color = Kv.faint, maxLines = 1, modifier = Modifier.width(84.dp))
                Text(r.title, style = t.label.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Normal), color = if (r.past) Kv.faint else Kv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (r.sub.isNotBlank()) Text(r.sub, style = t.caption, color = Kv.faint, maxLines = 1)
            }
        }
        if (c.data.tomorrow.isNotBlank()) {
            Text(c.data.tomorrow, style = t.caption, color = Kv.faint, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ---- плитки и лента (разложенный) ----

/** Плитки — Еда, Спорт, Деньги, Шаббат, два на два (`.tiles` холста). */
@Composable
private fun TilesGrid(c: TodayCards) {
    val x = c.data.tiles
    val nav = c.nav
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeTile(
                ModeDecor.FOOD, Glyphs.Food, "Еда", Fmt.num(x.kcal), { nav.mode(Tab.FOOD) }, Modifier.weight(1f),
                progress = if (x.kcalGoal > 0) x.kcal / x.kcalGoal.toFloat() else null,
                state = if (x.kcal == 0) TileState.EMPTY else TileState.NORMAL,
                unit = if (x.kcalGoal > 0) "из ${Fmt.num(x.kcalGoal)}" else "ккал",
                sub = "ккал сегодня",
            )
            ModeTile(
                ModeDecor.SPORT, Glyphs.Sport, "Спорт", x.sportValue ?: "—", { nav.mode(Tab.SPORT) }, Modifier.weight(1f),
                state = if (x.sportValue == null) TileState.EMPTY else TileState.NORMAL,
                unit = x.sportUnit,
                sub = x.sportSub ?: "тренировок сегодня нет",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeTile(
                ModeDecor.MONEY, Glyphs.Money, "Деньги", Fmt.rub(x.moneyDayKop), { nav.mode(Tab.MONEY) }, Modifier.weight(1f),
                state = if (x.moneyDayKop == 0L) TileState.EMPTY else TileState.NORMAL,
                unit = "₽ сегодня",
                sub = x.moneyMonth ?: "",
            )
            val sh = x.shabbat
            ModeTile(
                ModeDecor.TODAY, kv("candles", Glyphs.Calendar), "Шаббат",
                when {
                    sh == null -> "—"
                    sh.now -> Fmt.hm(sh.exit)
                    else -> Fmt.hm(sh.candles)
                },
                c.act.shabbat, Modifier.weight(1f),
                state = if (sh == null) TileState.EMPTY else TileState.NORMAL,
                unit = when {
                    sh == null -> null
                    sh.now -> "исход"
                    else -> shabbatDay(sh.candles, c.now) + ", свечи"
                },
                sub = when {
                    sh == null -> "время — на странице Шаббата"
                    sh.now -> "Шаббат шалом"
                    else -> "исход сб " + Fmt.hm(sh.exit)
                },
            )
        }
    }
}

/** «пт», а в саму пятницу — «сегодня». */
private fun shabbatDay(candles: Long, now: Long): String =
    if (TodayAgenda.dayOf(candles) == TodayAgenda.dayOf(now)) "сегодня" else "пт"

/** Лента дня — с вечера к утру, «от и до», события внутри отрезков; книзу растворяется. */
@Composable
private fun LentaCard(c: TodayCards, modifier: Modifier) {
    val rows = c.data.lenta
    val shape = RoundedCornerShape(KvShape.Panel)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Kv.panel)
            .border(1.dp, Kv.line, shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp),
    ) {
        SecHead(null, "Лента", small = "с вечера к утру")
        if (rows.isEmpty()) {
            Text("Записей за этот день нет.", style = LocalPravkaType.current.meta, color = Kv.faint, modifier = Modifier.padding(bottom = 12.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().bottomFade(bar = 0.dp, fade = 72.dp)) {
            itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                RibbonRow(r, first = i == 0, onSeg = c.act.lenta, onEvent = c.act.lenta)
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Сборка
// ---------------------------------------------------------------------------

/** Город с завода (Москва) — если у погоды координат нет: Шаббат считается по ним. */
private const val LAT_DEFAULT = 55.7558
private const val LON_DEFAULT = 37.6173

/**
 * Данные «Сегодня» v2 поверх модели дня: гора, лента с вечера, «идёт сейчас»,
 * «Сейчас», «Новое», «Сегодня» с завтра и числа плиток. Хроника дня уже
 * собрана (`buildModel` → `DayAssembler`) — лента берёт строки оттуда.
 */
internal fun buildV2(
    app: PravkaApp,
    model: TodayModel,
    entries: List<ZasechkaStore.Entry>,
    categories: List<ZasechkaStore.Category>,
    snap: Dela.Snapshot,
    me: String,
    calendar: List<ru.zf.pravka.core.CalEvent>,
    tomorrowCal: List<ru.zf.pravka.core.CalEvent>,
    now: Long,
    weather: ru.zf.pravka.data.WeatherStore.Result?,
    delaOn: Boolean,
    kcalGoal: Int,
    pOn: Boolean,
    zOn: Boolean,
    /** Свои отметки календаря дня («Свободен», `CalendarRules.mark`) — строкой «отметка» в «Напоминаниях». */
    marks: List<ru.zf.pravka.core.CalEvent> = emptyList(),
): TodayV2Data {
    val day = model.day
    val todayKey = DayFacts.localDay(now).toString()
    val dayKey = day.toString()
    val profile = DayProfile.ofEntries(entries, categories, model.dayStart, now)
    val lenta = Lenta.rows(
        model.result.items,
        sourceOf = { id -> model.entries[id]?.source.orEmpty() },
        rawOf = { id -> model.entries[id]?.raw.orEmpty() },
    )
    val open = entries.firstOrNull { it.open }
    val last = if (open == null) entries.filter { !it.open && it.source != "gap" && it.end <= now }.maxByOrNull { it.end } else null
    val nowTasks = if (delaOn) TodayAgenda.now(snap, me, todayKey) else emptyList()
    // «Подскажи» по фазе — по часам Москвы, Свод `asks.rhythm`/`day.quiet` (`core/Asks.kt`); минутный тик «Сегодня»
    // пересобирает данные — фаза сменится сама. Без Дел — пустая копия: карточки нет.
    val queue = Asks.queue(if (delaOn) snap else Dela.Snapshot(), me, now)
    // Кандидаты «Сейчас на завтра» в «Новое» не входят (`Dela.newOnes`) — у них одна строка внизу карточки.
    val plan = if (delaOn) DelaViews.plan(snap, me, todayKey, now)?.takeIf { it.visible } else null
    // Дело-созвон с часом и его событие в календаре (`gcal.py` ставит созвоны из Дел) — одной строкой: событием.
    val dueToday = if (delaOn) TodayAgenda.dropTwinCalls(TodayAgenda.due(snap, me, dayKey, todayKey), calendar, model.dayStart) else emptyList()
    val dueTomorrow = if (delaOn) TodayAgenda.due(snap, me, day.plusDays(1).toString(), todayKey) else emptyList()
    // Идеи к встрече (ответ 5а, захват 15.10.2026): «встреча · 💡 2» — у клиента или человека встречи есть живые идеи.
    val ideasOn = delaOn && snap.ideasOn && snap.ideas.isNotEmpty()
    val todayRows = TodayAgenda.rows(dueToday, calendar, model.dayStart, now) { e -> if (ideasOn) TodayAgenda.meetingIdeas(e, snap).size else 0 }
    val tomorrow = TodayAgenda.tomorrowLine(dueTomorrow, tomorrowCal)
    // Захват (15.10.2026): напоминания в свой час (и свои отметки календаря), «Вернулось» — только на сегодня.
    val isToday = dayKey == todayKey
    // Без флага `capture` у сервера — по-старому: напоминаний отдельной карточкой нет (дело с напоминанием — в своём списке).
    val reminders = if (isToday) TodayAgenda.reminderRows(if (delaOn && snap.captureOn) snap else Dela.Snapshot(), me, todayKey, now, marks) else emptyList()
    val back = if (delaOn && isToday) TodayAgenda.backRows(snap, me, todayKey) else emptyList()

    // Спорт: минуты тренировок дня с часов и самая длинная из них словом; ниже — сон и форма.
    val done = app.sportStore.workoutsFlow.value.filter { ru.zf.pravka.data.dayKey(it.start) == dayKey }
    val main = done.maxByOrNull { it.minutes }
    val health = app.sportStore.healthFlow.value.firstOrNull { it.date == dayKey }
    val sportSub = health?.let { h ->
        listOfNotNull(
            h.sleepHours.takeIf { it > 0 }?.let { "сон " + String.format(java.util.Locale.forLanguageTag("ru"), "%.1f", it) + " ч" },
            h.tsb.takeIf { h.ctl > 0 }?.let { "форма " + String.format(java.util.Locale.forLanguageTag("ru"), "%+.1f", it).replace('-', Fmt.MINUS) },
        ).joinToString(" · ").ifBlank { null }
    }
    // Деньги: сегодня и месяц — тем же счётом, что вкладка Денег (по Москве, своя область).
    val ms = app.moneyStore.stateFlow.value
    val monthPeriod = ru.zf.pravka.core.MoneyStats.of(ru.zf.pravka.core.MoneyStats.Kind.MONTH, day)
    val monthKop = ru.zf.pravka.core.MoneyStats.spending(ms.entries, monthPeriod, app.moneyEngine.scope(pOn, zOn)).sumOf { it.rubKop }
    val monthName = java.time.format.DateTimeFormatter.ofPattern("LLLL", java.util.Locale.forLanguageTag("ru")).format(day)
    val lat = weather?.lat?.takeIf { !it.isNaN() } ?: LAT_DEFAULT
    val lon = weather?.lon?.takeIf { !it.isNaN() } ?: LON_DEFAULT
    val tiles = TodayTiles(
        kcal = model.kcal,
        kcalGoal = kcalGoal,
        sportValue = main?.let { done.sumOf { it.minutes }.toString() },
        sportUnit = main?.let { "мин " + it.name.ifBlank { ru.zf.pravka.core.LifeSchema.sportKind(it.type) } },
        sportSub = sportSub,
        moneyDayKop = -model.spentKop,
        moneyMonth = if (monthKop != 0L) "$monthName ${Fmt.rub(-monthKop)} ₽" else null,
        shabbat = ShabbatTimes.next(now, lat, lon),
    )
    // Еда в ленте без приёма в дневнике — только сегодня и только если Еда включена в профиле.
    val mealGap = if (day == DayFacts.localDay(now) && app.profileStore.has(ru.zf.pravka.data.Profile.Mode.FOOD)) {
        ru.zf.pravka.core.MealGap.find(entries, app.foodStore.mealsFlow.value.map { it.ts }, now)
    } else null
    return TodayV2Data(
        model, profile, lenta, open, last, nowTasks, queue, todayRows, tomorrow, snap, todayKey, tiles, delaOn, mealGap,
        reminders = reminders, back = back, plan = plan,
    )
}
