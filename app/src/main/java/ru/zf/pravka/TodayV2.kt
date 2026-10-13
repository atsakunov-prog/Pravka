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
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperSheet
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
    val asks: List<Dela.Suggestion>,
    val todayRows: List<TodayAgenda.Row>,
    val tomorrow: String,
    val snap: Dela.Snapshot,
    val todayKey: String,
    val tiles: TodayTiles,
    /** Дела подключены: без них «Сейчас» и «Новое» не показываются. */
    val dela: Boolean,
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
    val shabbat: () -> Unit,
    val lenta: (Lenta.Row) -> Unit,
)

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
    rejecting?.let { sg ->
        RejectReasonSheet(sg, onDismiss = { rejecting = null }, onReject = { reason ->
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
        c.weather?.takeIf { it.ahead.isNotEmpty() || it.hours.isNotEmpty() }?.let { WeatherCardV2(it, c.weatherOpen, c.onWeather) }
        if (c.data.dela) NowTasksCard(c, wide = false)
        c.digest?.let { DigestCard(it, c.digestOpen, c.onDigest) }
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
            c.digest?.let { DigestCard(it, c.digestOpen, c.onDigest) }
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
        if (small != null) Text(small, style = t.meta, color = Kv.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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

// ---- дайджест ----

/**
 * Последний дайджест — того же размера, что соседние карточки, растворяется
 * книзу; нажатие раскрывает целиком (`.dg`, `.dgb` холста).
 */
@Composable
private fun DigestCard(d: DigestNote, open: Boolean, onToggle: () -> Unit) {
    val t = LocalPravkaType.current
    PaperCard {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(KvShape.Control)).clickable(onClickLabel = if (open) "свернуть дайджест" else "раскрыть дайджест", onClick = onToggle)) {
            SecHead(
                kv("digest", Glyphs.Note),
                "Дайджест",
                small = listOf(Fmt.hm(d.at), d.title).filter { it.isNotBlank() }.joinToString(" · "),
                link = if (open) "свернуть" else "раскрыть",
                onLink = onToggle,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (open) Modifier else Modifier.heightIn(max = 92.dp).bottomFade(bar = 0.dp, fade = 60.dp)),
            ) {
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
        }
    }
}

// ---- Новое ----

/** Сколько строк «Нового» на «Сегодня»; остальное — во вкладке Дел. */
private const val NEW_MAX = 4

/**
 * «Новое» — то, что ждёт «да» владельца (`DelaViews.asks`, «Подскажи» вкладки
 * Дел): название, почему спрашивает или откуда; «да» — поставить как
 * предложено, «нет» — с причиной (материал промптов). Пусто — карточки нет.
 */
@Composable
private fun NewCard(c: TodayCards, wide: Boolean) {
    val t = LocalPravkaType.current
    val asks = c.data.asks
    if (asks.isEmpty()) return
    PaperCard {
        SecHead(kv("inbox", Glyphs.Note), "Новое", asks.size, hot = true, small = "ждёт твоего да", link = if (asks.size > NEW_MAX) "все" else null, onLink = { c.nav.mode(Tab.TODOIST) })
        asks.take(NEW_MAX).forEachIndexed { i, sg ->
            if (i > 0) ListLine()
            val txt = DelaViews.sugText(sg, c.data.snap, c.data.todayKey)
            val why = DelaViews.askWhy(sg, c.data.snap)
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
                SmallKey(kv("check", Glyphs.Check), if (wide) "Да" else null, "Да: ${txt.title}", { c.act.accept(sg) }, soft = true)
                SmallKey(kv("x", Glyphs.Close), null, "Нет: ${txt.title}", { c.onReject(sg) })
            }
        }
    }
}

/**
 * «Нет» с причиной: причина — материал для промптов разбора, поэтому её
 * просят, но не требуют; готовые — одним касанием (те же, что во вкладке Дел).
 */
@Composable
private fun RejectReasonSheet(sg: Dela.Suggestion, onDismiss: () -> Unit, onReject: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    PaperSheet(
        onDismiss = onDismiss,
        title = "Не надо",
        icon = Glyphs.Close,
        subtitle = sg.title.take(80),
        footer = {
            Spacer(Modifier.weight(1f))
            PaperButton("Не надо", icon = Glyphs.Close, primary = true, onClick = { onReject(reason.trim()) })
        },
    ) {
        ChipRow {
            for (r in listOf("это идея, не дело", "уже сделано", "дубль", "не моё", "не сейчас")) {
                PaperChip(r, selected = reason == r, onClick = { onReject(r) })
            }
        }
        PaperField(value = reason, onValueChange = { reason = it }, label = "Своя причина", singleLine = false, maxLines = 4)
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
    val asks = if (delaOn) DelaViews.asks(snap, me) else emptyList()
    val dueToday = if (delaOn) TodayAgenda.due(snap, me, dayKey, todayKey) else emptyList()
    val dueTomorrow = if (delaOn) TodayAgenda.due(snap, me, day.plusDays(1).toString(), todayKey) else emptyList()
    val todayRows = TodayAgenda.rows(dueToday, calendar, model.dayStart, now)
    val tomorrow = TodayAgenda.tomorrowLine(dueTomorrow, tomorrowCal)

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
    return TodayV2Data(
        model, profile, lenta, open, last, nowTasks, asks, todayRows, tomorrow, snap, todayKey, tiles, delaOn,
    )
}
