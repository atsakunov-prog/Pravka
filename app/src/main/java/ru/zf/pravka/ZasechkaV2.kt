package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DayProfile
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaGroups
import ru.zf.pravka.core.DelaViews
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.Lenta
import ru.zf.pravka.core.ZasechkaDay
import ru.zf.pravka.core.ZasechkaTasks
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.ui.DayMountain
import ru.zf.pravka.ui.DayNav
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvBarRow
import ru.zf.pravka.ui.KvGlyphs
import ru.zf.pravka.ui.KvLink
import ru.zf.pravka.ui.KvListRow
import ru.zf.pravka.ui.KvSetRow
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.RibbonRow
import ru.zf.pravka.ui.SmallKey
import ru.zf.pravka.ui.Toggle
import ru.zf.pravka.ui.bottomFade
import ru.zf.pravka.ui.categoryTone
import ru.zf.pravka.ui.twoPane
import java.time.LocalDate

// Засечка вида v2 (Правка 5.0, этап 3; `docs/redesign-5-plan.md` §4, холст
// «Ковчег на телефоне», лист «Засечка»). Сложенный — сверху вниз: «Сейчас»
// крупно (что идёт, таймер, «Стоп», «Поправить», откуда записано) и «Дальше»
// (дела с ▶), профиль дня с легендой категорий, лента с вечера к утру
// (идущее и шесть последних отрезков; «Весь день» — целиком). Разложенный
// (≥ 600 dp): слева «Сейчас» с «Дальше», «Итог дня» по категориям и автопилот
// строками настройки; справа «Весь день» — гора и лента целиком. Внизу —
// строка «сказать» Засечки: тот же тап «З» и тот же разбор набранного.
//
// Логика не тронута — это перекладка экрана. Лента, листы записи, дыры, дела с
// ▶ и тумблеры автопилота — прежние (`ZasechkaTab.kt`, `ZasechkaEngine`,
// `Settings`). Гора — `ui/Mountain.kt`; лента строками — `core/Lenta.kt` из
// хроники дня «Сегодня» (`buildModel`: те же отметки еды, спорта, дел, денег
// и сна, второго сбора нет); цвет категории — «чей это» (`categoryTone`).
// «Вид 4.0» — прежняя вкладка (`Zasechka4Body` в `ZasechkaTab.kt`).

/** Что видит Засечка v2 сверх ленты: гора, лента строками, итог, идущее. */
internal class ZasechkaV2Data(
    val day: LocalDate,
    /** Смотрим сегодня: только тогда есть «Сейчас», «Дальше» и строка «сказать». */
    val today: Boolean,
    /** Балл дня — тот же, что «очки» в шапке «Сегодня». */
    val score: Int,
    val profile: DayProfile.Profile,
    val lenta: List<Lenta.Row>,
    val totals: List<ZasechkaDay.Total>,
    /** Идёт сейчас (живая дыра — тоже: её «разметить»); null — ничего. */
    val open: ZasechkaStore.Entry?,
    /** Последнее закрытое — когда ничего не идёт. */
    val last: ZasechkaStore.Entry?,
)

/** Действия экрана — дороги прежней вкладки, ни одной новой. */
internal class ZasechkaV2Actions(
    /** Остановить идущее (`ZasechkaEngine.closeOpen`). */
    val stop: () -> Unit,
    /** «Поправить» — редактор записи (у разрезанного дела — всей цепочки). */
    val edit: (ZasechkaStore.Entry) -> Unit,
    /** Тап по записи — её лист; по «не размечено» — «что это было». */
    val entry: (Long) -> Unit,
    /** Тап по событию в ленте — в его режим, как на «Сегодня». */
    val event: (Lenta.Row.Event) -> Unit,
    /** Карточка дела из «Дальше» — та же, что во вкладке «Дела». */
    val task: (Dela.Task) -> Unit,
    /** Настройки Засечки (автопилот целиком); null — некуда. */
    val settings: (() -> Unit)?,
)

/**
 * Данные v2 поверх модели дня «Сегодня»: гора — из ленты, лента строками — из
 * хроники (`model.result`), итог — из `DayFacts` (сутки, сон — его часть).
 */
internal fun buildZasechkaV2(
    model: TodayModel,
    entries: List<ZasechkaStore.Entry>,
    categories: List<ZasechkaStore.Category>,
    now: Long,
    today: Boolean,
): ZasechkaV2Data {
    val open = if (today) entries.lastOrNull { it.open } else null
    val last = if (today && open == null) {
        entries.filter { !it.open && it.source != "gap" && it.end <= now }.maxByOrNull { it.end }
    } else null
    return ZasechkaV2Data(
        day = model.day,
        today = today,
        score = model.result.score,
        profile = DayProfile.ofEntries(entries, categories, model.dayStart, now),
        lenta = Lenta.rows(
            model.result.items,
            sourceOf = { id -> model.entries[id]?.source.orEmpty() },
            rawOf = { id -> model.entries[id]?.raw.orEmpty() },
        ),
        totals = ZasechkaDay.totals(model.facts.categories.map { ZasechkaDay.CatIn(it.name, it.minutes, it.points) }),
        open = open,
        last = last,
    )
}

/**
 * Экран Засечки v2. Шапку рисует [header] хозяина (значок Ковчега, название и
 * значки режима) — подпись «день · очки» и навигатор дня знает только экран:
 * тап по названию — навигатор, двойной — сегодня (как у «Сегодня»). [phone] —
 * прежняя плашка «Телефон» (что считать по дням), [sayBar] — строка «сказать».
 */
@Composable
internal fun ZasechkaV2Screen(
    app: PravkaApp,
    entries: List<ZasechkaStore.Entry>,
    categories: List<ZasechkaStore.Category>,
    day: LocalDate,
    dayOffset: Int,
    setDay: (Int) -> Unit,
    now: Long,
    header: (@Composable (subtitle: String, onTitle: () -> Unit, onTitleDouble: () -> Unit) -> Unit)?,
    act: ZasechkaV2Actions,
    phone: @Composable () -> Unit,
    sayBar: @Composable (Modifier) -> Unit,
) {
    // Отметки ленты — из сторов режимов, как на «Сегодня»: прочитать, если ещё не прочитаны.
    LaunchedEffect(Unit) {
        runCatching { app.foodStore.load() }
        runCatching { app.moneyStore.load() }
        runCatching { app.delaStore.load() }
    }
    val meals by app.foodStore.mealsFlow.collectAsState()
    val money by app.moneyStore.stateFlow.collectAsState()
    val workouts by app.sportStore.workoutsFlow.collectAsState()
    val health by app.sportStore.healthFlow.collectAsState()
    val gtg by app.strengthStore.gtgFlow.collectAsState()
    val snap by app.delaStore.view.collectAsState()
    val link by app.delaSync.link.collectAsState()
    val onServer by app.delaServer.collectAsState()
    val bedtime by app.settings.todayBedtimeFlow.collectAsState(initial = ru.zf.pravka.data.Settings.TODAY_BEDTIME_DEFAULT)
    val marksOn by app.settings.todayMarksFlow.collectAsState(initial = setOf("sport", "food", "money", "dela"))
    val pOn by app.settings.mScopePersonalFlow.collectAsState(initial = true)
    val zOn by app.settings.mScopeZfFlow.collectAsState(initial = false)
    val me = link?.user ?: app.delaStore.me
    val today = dayOffset == 0
    val model = remember(entries, categories, meals, money, workouts, health, gtg, snap, now, day, bedtime, marksOn, pOn, zOn, onServer, me) {
        // Календарь, план и бюджет сюда не нужны: лента кончается на «сейчас», будущего в ней нет.
        buildModel(app, day, now, entries, categories, emptyList(), bedtime, marksOn, 0, 0L, pOn, zOn, me, onServer, v2 = true)
    }
    val data = remember(model, entries, categories, now, today) { buildZasechkaV2(model, entries, categories, now, today) }
    var showNav by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = twoPane()
        Column(Modifier.fillMaxSize()) {
            header?.invoke(
                ZasechkaDay.subtitle(data.day, data.score, data.profile.unmarkedMin, wide),
                { showNav = !showNav },
                { setDay(0) },
            )
            if (dayOffset != 0 || showNav) {
                Box(Modifier.padding(horizontal = 12.dp)) {
                    DayNav(
                        title = Fmt.dayList(day),
                        onPrev = { setDay(dayOffset + 1) },
                        onNext = if (dayOffset > 0) ({ setDay(dayOffset - 1) }) else null,
                        subtitle = if (dayOffset > 0) "к сегодня" else null,
                        onTitleClick = { setDay(0) },
                    )
                }
            }
            if (wide) WideBody(app, data, entries, now, act, phone, Modifier.weight(1f))
            else FoldedBody(app, data, entries, now, act, phone, Modifier.weight(1f))
        }
        if (today) {
            sayBar(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Раскладки
// ---------------------------------------------------------------------------

/** Сложенный (412 dp): «Сейчас» с «Дальше», профиль дня, лента; строка «сказать» поверх низа. */
@Composable
private fun FoldedBody(
    app: PravkaApp,
    data: ZasechkaV2Data,
    entries: List<ZasechkaStore.Entry>,
    now: Long,
    act: ZasechkaV2Actions,
    phone: @Composable () -> Unit,
    modifier: Modifier,
) {
    // «Весь день» помнится поворотом экрана: раскрыл — смотришь дальше.
    var all by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier.fillMaxWidth().then(if (data.today) Modifier.bottomFade() else Modifier),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (data.today) item(key = "now") { NowCard(app, data, entries, now, act, wide = false) }
        item(key = "profile") { ProfileCard(data) }
        item(key = "lenta") {
            // Прошлый день — целиком сразу: «Сейчас» у него нет, лента и есть день.
            val open = all || !data.today
            LentaCard(
                rows = if (open) data.lenta else ZasechkaDay.recent(data.lenta),
                link = when {
                    !data.today || !ZasechkaDay.hasMore(data.lenta) -> null
                    all -> "Свернуть"
                    else -> "Весь день"
                },
                onLink = { all = !all },
                act = act,
            )
        }
        item(key = "phone") { phone() }
    }
}

/**
 * Разложенный (≥ 600 dp): слева «Сейчас» с «Дальше», «Итог дня» и автопилот —
 * своей прокруткой; справа «Весь день» — гора и лента целиком, книзу тает.
 */
@Composable
private fun WideBody(
    app: PravkaApp,
    data: ZasechkaV2Data,
    entries: List<ZasechkaStore.Entry>,
    now: Long,
    act: ZasechkaV2Actions,
    phone: @Composable () -> Unit,
    modifier: Modifier,
) {
    Row(modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Левая колонка — 290, как `.cols.c23` холста: сейчас, итог и автопилот читаются узкой колонкой.
        // Тает под строкой «сказать» только то, что под неё уходит: всё влезло — таять нечему (и слой
        // со срезом не нужен — на снимке Robolectric пустой низ слоя прорезал фон до прозрачного).
        val scroll = rememberScrollState()
        Column(
            Modifier
                .width(LEFT_W)
                .fillMaxHeight()
                .then(if (data.today && scroll.maxValue > 0) Modifier.bottomFade() else Modifier)
                .verticalScroll(scroll)
                .padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (data.today) NowCard(app, data, entries, now, act, wide = true)
            TotalsCard(data, now)
            AutopilotCard(app, act.settings)
            phone()
        }
        WholeDayCard(data, act, Modifier.weight(1f).fillMaxHeight().padding(bottom = if (data.today) 76.dp else 12.dp))
    }
}

private val LEFT_W = 290.dp

// ---------------------------------------------------------------------------
// Детали
// ---------------------------------------------------------------------------

/** Значок набора по имени веба; нет — запасной. */
private fun kv(key: String, fallback: ImageVector): ImageVector = KvGlyphs.byKey(key) ?: fallback

/** Надстрочник `.eyebrow`: 12/600, бледно. */
@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, style = LocalPravkaType.current.overline, color = Kv.faint, maxLines = 1, modifier = modifier)
}

/** Квадратик категории «чей это» (`.sq`). */
@Composable
private fun Sq(category: String, side: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(Modifier.size(side).clip(RoundedCornerShape(2.dp)).background(categoryTone(category)))
}

/** Запись — заполнитель «не размечено» (живая дыра): её не останавливают, а размечают. */
private fun ZasechkaStore.Entry.isGap(): Boolean =
    source == "gap" || category.trim().equals(DayProfile.UNMARKED, ignoreCase = true)

// ---- Сейчас и Дальше ----

/**
 * «Сейчас» крупно: что идёт (20/600), категория и с какого времени, таймер
 * (30/600), «Стоп» и «Поправить», мелко — откуда записано (на сложенном).
 * Тап по названию — лист записи (там и «Заметка», и «Удалить»). Живая дыра —
 * «Разметить»; ничего не идёт — что было последним. Ниже — «Дальше».
 */
@Composable
private fun NowCard(
    app: PravkaApp,
    data: ZasechkaV2Data,
    entries: List<ZasechkaStore.Entry>,
    now: Long,
    act: ZasechkaV2Actions,
    wide: Boolean,
) {
    val t = LocalPravkaType.current
    val e = data.open
    val gap = e?.isGap() == true
    PaperCard {
        Eyebrow("Сейчас")
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(KvShape.Control))
                    .then(if (e != null) Modifier.clickable(onClickLabel = "лист записи") { act.entry(e.id) } else Modifier),
            ) {
                val title = when {
                    e == null -> "Ничего не идёт"
                    gap -> "Не размечено"
                    else -> e.title.ifBlank { e.raw.take(60) }.ifBlank { e.category }
                }
                Text(
                    title,
                    style = t.valueL.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    color = if (e == null || gap) Kv.muted else Kv.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = when {
                    e == null -> data.last?.let { l -> "последнее — «${l.title.ifBlank { l.category }}» до ${Fmt.hm(l.end)}" }
                        ?: "скажи, чем занят — запишу в ленту"
                    gap -> "с ${Fmt.hm(e.start)} — скажи, что это было"
                    else -> listOf(e.category, "с " + Fmt.hm(e.start)).filter { it.isNotBlank() }.joinToString(" · ")
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (e != null && !gap && e.category.isNotBlank()) {
                        Sq(e.category)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(sub, style = t.meta, color = Kv.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (e != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    ZasechkaDay.timer(now - e.start),
                    style = t.valueXL.copy(fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
                    color = if (gap) Kv.muted else Kv.text,
                    maxLines = 1,
                )
            }
        }
        if (e != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (gap) {
                    SmallKey(kv("edit", Glyphs.Edit), "Разметить", "Разметить: что это было", { act.entry(e.id) })
                } else {
                    SmallKey(kv("stop", Glyphs.Stop), "Стоп", "Стоп: ${e.title}", act.stop)
                    SmallKey(kv("edit", Glyphs.Edit), "Поправить", "Поправить: ${e.title}", { act.edit(e) })
                }
                Spacer(Modifier.weight(1f))
                if (!wide) {
                    val said = ZasechkaDay.said(e.source, e.category, e.raw)
                    if (said.isNotBlank()) Text(said, style = t.meta, color = Kv.faint, maxLines = 1)
                }
            }
        }
        NextTasks(app, entries, now, running = e?.task.orEmpty(), onOpen = act.task)
    }
}

/** Свёрнутый «Дальше» — до трёх строк, как у прежней плашки «дела»; отмеченное «Сейчас» — всё. */
private const val NEXT_COLLAPSED = 3

/**
 * «Дальше» — дела с ▶ (`.li` холста): тот же список, что у прежней плашки
 * «дела» Засечки (`ZasechkaTasks.parts`: дела дня — «Сейчас», потом срок
 * сегодня, — и начатое за неделю), без того, что уже идёт: оно — в «Сейчас».
 * ▶ — запись ленты из дела (`ZasechkaEngine.startTask`), тап по строке —
 * карточка дела. Дел не на своём сервере или браться не за что — блока нет.
 */
@Composable
private fun NextTasks(
    app: PravkaApp,
    entries: List<ZasechkaStore.Entry>,
    now: Long,
    running: String,
    onOpen: (Dela.Task) -> Unit,
) {
    val delaOn by app.delaServer.collectAsState()
    val profile by app.profileStore.flow.collectAsState()
    if (!delaOn || profile?.has(ru.zf.pravka.data.Profile.Mode.DELA) == false) return
    val snap by app.delaStore.view.collectAsState()
    val link by app.delaSync.link.collectAsState()
    val me = link?.user ?: app.delaStore.me
    val today = LocalDate.now().toString()
    val parts = remember(snap, entries, me, today) { ZasechkaTasks.parts(snap, me, today, entries, now) }
    val list = (parts.now + parts.rest).filter { it.id != running }
    if (list.isEmpty()) return
    val spent = remember(entries, now) { ZasechkaTasks.spent(entries, now) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var starting by remember { mutableStateOf("") }
    // Свёрнуто — отмеченное «Сейчас» видно всегда (владелец, 06.10: «сначала делаю их, потом всё
    // остальное»), остальное — до трёх строк всего: на сложенном ниже ещё гора и лента (холст — две строки).
    val focus = list.filter { DelaGroups.isNow(it, today) }.take(DelaViews.NOW_MAX)
    val collapsed = focus + list.filter { it !in focus }.take((NEXT_COLLAPSED - focus.size).coerceAtLeast(0))
    val shown = if (expanded) list else collapsed
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow("Дальше", Modifier.weight(1f))
        if (list.size > collapsed.size) {
            KvLink(if (expanded) "свернуть" else "ещё ${list.size - collapsed.size}", { expanded = !expanded })
        }
    }
    shown.forEach { task ->
        val sub = listOfNotNull(
            "дело ${task.numLabel}".takeIf { task.num > 0 },
            task.estimateMin.takeIf { it > 0 }?.let { Fmt.dur(it) },
            // «просрочено 2 дн.», как на холсте; срок сегодня не пишем — «Дальше» и так про сегодня.
            DelaViews.due(task, today)?.let { d -> if (d.late) "просрочено " + d.text.substringAfter(" · ").removeSuffix(" назад") else d.text.takeIf { !d.today } },
            if (task.ball == Dela.WAITING) "жду" else null,
            task.personShort.ifBlank { task.projectName }.takeIf { it.isNotBlank() },
            spent[task.id]?.takeIf { it > 0L }?.let { "в ленте " + ZasechkaTasks.label(it) },
        ).joinToString(" · ")
        KvListRow(
            title = task.title,
            sub = sub.ifBlank { null },
            titleLines = 2,
            subLines = 1,
            onClick = { onOpen(task) },
            trailing = {
                if (starting == task.id) {
                    Text("…", style = LocalPravkaType.current.body, color = Kv.faint, modifier = Modifier.padding(horizontal = 12.dp))
                } else {
                    SmallKey(kv("play", Glyphs.Play), null, "Начать в ленте: ${task.title}", {
                        if (starting.isBlank()) {
                            starting = task.id
                            app.appScope.launch {
                                val entry = runCatching { app.zasechkaEngine.startTask(task) }.getOrNull()
                                Feedback.toast(app, if (entry != null) "⏱ ${entry.title}" else "Не смог записать дело")
                                starting = ""
                            }
                        }
                    }, soft = true)
                }
            },
        )
    }
}

// ---- Профиль дня ----

/** Сколько категорий в легенде профиля на сложенном — шесть самых длинных (холст). */
private const val LEGEND_MAX = 6

/**
 * «Профиль дня» сложенного: справа «очки · пик», гора, полоса суток, метки
 * часов и легенда — шесть самых длинных категорий с временем.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(data: ZasechkaV2Data) {
    val t = LocalPravkaType.current
    PaperCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Профиль дня", Modifier.weight(1f))
            Text(ZasechkaDay.profileNote(data.profile), style = t.meta, color = Kv.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DayMountain(data.profile, Modifier.padding(top = 8.dp), height = 56.dp)
        val legend = data.totals.take(LEGEND_MAX)
        if (legend.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                legend.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Sq(c.category)
                        Spacer(Modifier.width(5.dp))
                        Text("${c.label} ${Fmt.dur(c.minutes)}", style = t.meta, color = Kv.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---- Лента ----

/**
 * Лента сложенного (`.card` с `.sec` «Лента · с вечера к утру», справа
 * «Весь день»): строки `RibbonRow` — отрезок «от–до» с «категория · откуда» и
 * события внутри него; идущее — первым, мягким акцентом.
 */
@Composable
private fun LentaCard(rows: List<Lenta.Row>, link: String?, onLink: () -> Unit, act: ZasechkaV2Actions) {
    PaperCard(
        label = "Лента",
        note = "с вечера к утру",
        trailing = link?.let { l -> { KvLink(l, onLink) } },
    ) {
        if (rows.isEmpty()) {
            Text("Записей за этот день нет.", style = LocalPravkaType.current.meta, color = Kv.faint)
        }
        rows.forEachIndexed { i, r ->
            RibbonRow(r, first = i == 0, onSeg = { act.entry(it.entryId) }, onEvent = act.event)
        }
    }
}

/**
 * «Весь день» разложенного: подпись «с вечера к утру · очки · пик», гора
 * (70), полоса суток и метки часов стоят; лента целиком под ними листается и
 * книзу тает.
 */
@Composable
private fun WholeDayCard(data: ZasechkaV2Data, act: ZasechkaV2Actions, modifier: Modifier) {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(KvShape.Panel)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Kv.panel)
            .border(1.dp, Kv.line, shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Весь день", style = t.bodyStrong, color = Kv.text, maxLines = 1)
            Text(
                "с вечера к утру · " + ZasechkaDay.profileNote(data.profile),
                style = t.meta,
                color = Kv.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
        DayMountain(data.profile, Modifier.padding(top = 8.dp, bottom = 8.dp), height = 70.dp)
        if (data.lenta.isEmpty()) {
            Text("Записей за этот день нет.", style = t.meta, color = Kv.faint, modifier = Modifier.padding(bottom = 12.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().bottomFade(bar = 0.dp, fade = 72.dp)) {
            itemsIndexed(data.lenta, key = { _, r -> r.key }) { i, r ->
                RibbonRow(r, first = i == 0, onSeg = { act.entry(it.entryId) }, onEvent = act.event)
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// ---- Итог дня (разложенный) ----

/**
 * «Итог дня» — строки с полосой (`.cr`): квадратик «чей это», верхний уровень
 * категории, полоса долей от самой длинной, время. Сутки целиком: сон и «не
 * размечено» — тоже строки. Подпись — до какого часа счёт.
 */
@Composable
private fun TotalsCard(data: ZasechkaV2Data, now: Long) {
    PaperCard(label = "Итог дня", note = if (data.today) "до ${Fmt.hm(now)}" else "сутки целиком") {
        val max = data.totals.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
        if (data.totals.isEmpty()) {
            Text("Записей за этот день нет.", style = LocalPravkaType.current.meta, color = Kv.faint)
        }
        data.totals.forEach { c ->
            KvBarRow(
                label = c.label,
                fraction = c.minutes / max.toFloat(),
                value = Fmt.dur(c.minutes),
                labelWidth = 116.dp,
                valueWidth = 60.dp,
                dot = categoryTone(c.category),
            )
        }
    }
}

// ---- Автопилот (разложенный) ----

/**
 * «Автопилот» строками настройки (`.set` с `.sw`): прежние тумблеры — приезд
 * в место по Wi-Fi, поездка по машине, встречи из календаря, звонки в ленту,
 * сон сам — с тем же смыслом и теми же ключами `Settings`; «Отбой» — время сна
 * «Сегодня». Места, машина, календари, семья и вопросы — в настройках Засечки
 * («Настройки» справа): здесь только то, что включают и выключают на ходу.
 */
@Composable
private fun AutopilotCard(app: PravkaApp, onSettings: (() -> Unit)?) {
    val s = app.settings
    val scope = app.appScope
    val places by s.autoPlacesFlow.collectAsState(initial = emptyMap<String, String>())
    val carBt by s.autoCarBtFlow.collectAsState(initial = "")
    val arrive by s.autoArriveFlow.collectAsState(initial = true)
    val carStart by s.autoCarStartFlow.collectAsState(initial = true)
    val calOn by s.autoCalOnFlow.collectAsState(initial = true)
    val callsCut by s.zCallsCutFlow.collectAsState(initial = true)
    val bedOn by s.autoBedtimeFlow.collectAsState(initial = true)
    val bedtime by s.todayBedtimeFlow.collectAsState(initial = ru.zf.pravka.data.Settings.TODAY_BEDTIME_DEFAULT)
    PaperCard(
        label = "Автопилот",
        trailing = onSettings?.let { f -> { KvLink("Настройки", f) } },
    ) {
        val names = places.values.filter { it.isNotBlank() }.distinct()
        AutoRow("Места по Wi-Fi", if (names.isEmpty()) "мест нет — в настройках" else "приезд: " + names.joinToString(", "), arrive, first = true) {
            scope.launch { s.setAutoArrive(it) }
        }
        AutoRow("Поездка по машине", if (carBt.isBlank()) "машина не выбрана" else "$carBt — старт сам", carStart) {
            scope.launch { s.setAutoCarStart(it) }
        }
        AutoRow("Встречи из календаря", "в ленту сами", calOn) { scope.launch { s.setAutoCalOn(it) } }
        AutoRow("Звонки в ленту", "категория — по собеседнику", callsCut) { scope.launch { s.setZCallsCut(it) } }
        AutoRow("Сон сам", "зарядка и полчаса без движения", bedOn) { scope.launch { s.setAutoBedtime(it) } }
        KvSetRow(title = "Отбой", value = Fmt.hmOfMin(bedtime), chevron = false)
    }
}

/** Строка настройки с тумблером: тап по всей строке переключает, как `PaperToggle`. */
@Composable
private fun AutoRow(title: String, sub: String?, on: Boolean, first: Boolean = false, onChange: (Boolean) -> Unit) {
    KvSetRow(
        title = title,
        sub = sub,
        divider = !first,
        chevron = false,
        onClick = { onChange(!on) },
        trailing = { Toggle(checked = on, onCheckedChange = onChange) },
    )
}
