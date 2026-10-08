package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaAsk
import ru.zf.pravka.core.DelaCrm
import ru.zf.pravka.data.DelaStore
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperRow
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.Segments
import ru.zf.pravka.ui.SummaryLine

// CRM на телефоне (05.10.2026, docs/dela-phone-2.md, этап 2): воронка,
// клиенты, связи, клиент, сделка, человек и хронология. Данные видов — с
// сервера (`/api/view/…`, логика общая с вебом), последний ответ каждого — на
// диске (`DelaStore.viewsFlow`): экран открывается без сети и обновляется.
// Правки — той же очередью с op_id, что у дел; своя неотправленная правка
// накладывается поверх ответа (`DelaCrm.overlay*`), чтобы касание было видно
// сразу. Деньги фирмы (гонорары, оплаты) — только кому открыты.

/** Состояние CRM-экранов, общее для их кусков и листов поверх вкладки. */
internal class CrmUiState {
    var staleOnly by mutableStateOf(false)
    var clientQuery by mutableStateOf("")
    var clientArchive by mutableStateOf(false)
    /** «Закрыть…» сделку: выбор итога и причина. */
    var closing by mutableStateOf<DelaCrm.Deal?>(null)
    /** «Поговорили» из «Связей»: запись в хронологию про человека. */
    var talk by mutableStateOf<TalkTarget?>(null)
    /** Убрать запись хронологии — с подтверждением: строка останется в журнале. */
    var deleting by mutableStateOf<Pair<DelaCrm.Interaction, List<String>>?>(null)
    /** Тап по плашке человека — «откуда он» и должность (`personPop` веба). */
    var personFor by mutableStateOf<String?>(null)
    /** «+ человек»: к клиенту, в люди клиента или в команду сделки. */
    var addTo by mutableStateOf<AddTarget?>(null)
    /** Крестик у человека клиента — с подтверждением: карточка человека остаётся. */
    var leaving by mutableStateOf<Pair<Dela.Person, String>?>(null)
    /** Вкладка карточки клиента и сделки: «Дела» или «Хронология» (`S.clientTab` веба). */
    var tab by mutableStateOf(TAB_TASKS)
    /** Поиск в «Людях». */
    var peopleQuery by mutableStateOf("")
}

internal const val TAB_TASKS = "tasks"
internal const val TAB_TIMELINE = "timeline"

/**
 * Куда «+ человек»: к клиенту ([clientId]: в его организацию) или в сделку
 * ([dealId] и поле `person_ids` / `team_ids`; нового человека в люди клиента —
 * сразу с организацией клиента [clientId]).
 */
internal data class AddTarget(
    val title: String,
    val exclude: Set<String>,
    val clientId: String = "",
    val dealId: String = "",
    val field: String = "",
    val ids: List<String> = emptyList(),
)

/** Куда ляжет запись хронологии: клиент, сделка, люди — и какие виды после неё обновить. */
internal data class TalkTarget(
    val title: String,
    val projectId: String = "",
    val dealId: String = "",
    val personIds: List<String> = emptyList(),
    val refresh: List<String> = emptyList(),
)

/** Всё, что нужно кускам CRM: копия, кто я, кэш видов, очередь, видны ли деньги, переходы. */
internal class DelaCrmContext(
    val app: PravkaApp,
    val snap: Dela.Snapshot,
    val me: String,
    val today: String,
    val views: Map<String, DelaStore.CachedView>,
    val ops: List<JSONObject>,
    val money: Boolean,
    val actions: DelaActions,
    val push: (DelaPage) -> Unit,
    val back: () -> Unit,
    val ui: CrmUiState = CrmUiState(),
    /** Клиенты с раскрытыми проектами — в ☰ и в «Клиентах»; помнится в `dela-favs.json`. */
    val clientOpen: Set<String> = emptySet(),
    /** Правка, которую можно вернуть: слова и операции «как было» — полоской «Вернуть» наверху вкладки. */
    val offerUndo: (said: String, back: List<JSONObject>, refresh: List<String>) -> Unit = { _, _, _ -> },
) {
    fun data(path: String): JSONObject? = views[path]?.data

    /** Раскрыть или свернуть проекты клиентов (один или «все»). */
    fun setClientOpen(ids: Collection<String>, open: Boolean) {
        app.appScope.launch { app.delaStore.setClientOpen(ids, open) }
    }

    /**
     * Правка с «Вернуть» (крестик, «откуда он», «+ человек»): в очередь и на
     * сервер, задетые виды — заново, и полоска со словами и «Вернуть», если
     * вернуть есть что; иначе — просто слова тостом.
     */
    fun runUndoable(u: DelaCrm.Undoable, refresh: List<String>) {
        run(u.ops, refresh)
        if (u.back.isNotEmpty()) offerUndo(u.said, u.back, refresh) else Feedback.toast(app, u.said)
    }

    /**
     * Правка CRM: в очередь (на диск сразу), на сервер — сейчас, и виды,
     * которых она касается, — заново: их считает сервер. Без сети правка
     * дождётся в очереди, а поверх кэша видна сразу.
     */
    fun run(ops: List<JSONObject>, refresh: List<String>, done: String = "") {
        if (ops.isEmpty()) return
        app.appScope.launch {
            app.delaStore.enqueue(ops)
            val sent = app.delaSync.pushNow(4_000L)
            if (done.isNotBlank()) Feedback.toast(app, if (sent) done else "$done — уйдёт, когда будет связь")
            if (sent) for (p in refresh.distinct()) app.delaSync.crmView(p)
        }
    }

    /**
     * Следующее дело сделки — из своей копии (там и своя только что поставленная
     * галка), а не из ответа вида, который мог устареть: открытое дело с
     * ближайшим сроком. Копия ещё не знает дел сделки — то, что сказал сервер.
     */
    fun nextFor(dealId: String, fromView: DelaCrm.NextTask?): DelaCrm.NextTask? {
        val mine = snap.tasks.values.filter { it.dealId == dealId }
        val open = mine.filter { it.open }.sortedWith(Dela.ORDER)
        open.firstOrNull()?.let { return DelaCrm.NextTask(it.id, it.num, it.title, it.dueDate, it.ownerId, it.ball) }
        if (fromView != null && mine.any { it.id == fromView.id }) return null
        return fromView
    }

    fun personName(id: String): String = snap.people[id]?.label.orEmpty()
}

// ---------------------------------------------------------------- люди плашками

/**
 * Люди плашками (06.10.2026, docs/dela-phone-4.md; владелец: «людей — круглыми
 * плашками, через крестик убрать, нажал — поменять, откуда он»): аватар,
 * имя, должность; крестик — [onRemove], тап — «откуда он» и должность,
 * «+ человек» — [onAdd]. Как `peopleChips` веба.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PeopleChips(
    ctx: DelaCrmContext,
    title: String,
    people: List<Dela.Person>,
    onRemove: ((Dela.Person) -> Unit)?,
    onAdd: (() -> Unit)?,
    empty: String = "никого",
) {
    val c = MaterialTheme.colorScheme
    PaperHint(title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (people.isEmpty()) Text(empty, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        for (p in people) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.outlineVariant, RoundedCornerShape(50))
                    .clickable { ctx.ui.personFor = p.id }.padding(start = 4.dp, end = if (onRemove != null) 2.dp else 12.dp, top = 3.dp, bottom = 3.dp),
            ) {
                Avatar(p)
                Spacer(Modifier.width(6.dp))
                Text(p.label + if (p.local) " ⏳" else "", style = MaterialTheme.typography.bodySmall, color = c.onSurface, maxLines = 1)
                if (p.role.isNotBlank()) {
                    Text(" · " + p.role, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp))
                }
                if (onRemove != null) GlyphButton(Glyphs.Close, "убрать отсюда", onClick = { onRemove(p) }, size = 26.dp)
            }
        }
        if (onAdd != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.outlineVariant, RoundedCornerShape(50))
                    .clickable(onClick = onAdd).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Glyphs.Plus, contentDescription = null, tint = c.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("человек", style = MaterialTheme.typography.bodySmall, color = c.primary)
            }
        }
    }
}

/** Кружок с инициалами — как `avatar` веба. */
@Composable
internal fun Avatar(p: Dela.Person, size: androidx.compose.ui.unit.Dp = 24.dp) {
    val c = MaterialTheme.colorScheme
    val ini = p.name.split(Regex("\\s+")).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }.take(2).joinToString("").uppercase()
    Box(Modifier.size(size).clip(CircleShape).background(c.primary.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(ini.ifBlank { "?" }, style = MaterialTheme.typography.labelSmall, color = c.primary, fontWeight = FontWeight.SemiBold)
    }
}

/** «Дела» / «Хронология» — вкладки карточки клиента и сделки, как в вебе. */
@Composable
internal fun CardTabs(ctx: DelaCrmContext) {
    Segments(
        options = listOf("Дела", "Хронология"),
        selected = if (ctx.ui.tab == TAB_TIMELINE) 1 else 0,
        onSelect = { ctx.ui.tab = if (it == 1) TAB_TIMELINE else TAB_TASKS },
    )
}

// ---------------------------------------------------------------- свежесть

/**
 * Строка свежести вида и его загрузчик: старше 30 секунд — спросить сервер
 * заново, в ответ на это время виден прежний ответ. Не вышло — причина целиком.
 */
@Composable
internal fun Freshness(ctx: DelaCrmContext, path: String) {
    var loading by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf("") }
    val cached = ctx.views[path]
    val scope = ctx.app.appScope
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            ctx.app.delaSync.crmView(path).onSuccess { error = "" }.onFailure { error = it.message.orEmpty() }
            loading = false
        }
    }
    LaunchedEffect(path) {
        if (cached == null || System.currentTimeMillis() - cached.at > 30_000L) load()
    }
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        val line = when {
            loading && cached == null -> "загружаю с сервера…"
            loading -> "обновляю…"
            error.isNotBlank() -> error + if (cached != null) " · показываю ответ от " + ago(cached.at) else ""
            cached != null -> "обновлено " + ago(cached.at)
            else -> "ещё не загружалось"
        }
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (error.isNotBlank() && !loading) c.error else c.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        GlyphButton(Glyphs.Refresh, "обновить с сервера", enabled = !loading, onClick = { load() }, size = 30.dp)
    }
}

private fun ago(at: Long): String {
    val mins = ((System.currentTimeMillis() - at) / 60_000L).toInt()
    return when {
        mins < 1 -> "только что"
        mins < 120 -> "$mins мин назад"
        else -> java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru")).format(java.util.Date(at))
    }
}

// ---------------------------------------------------------------- воронка

internal fun LazyListScope.crmPipeline(ctx: DelaCrmContext) {
    val raw = ctx.data(DelaCrm.PIPELINE)?.let(DelaCrm::pipeline)
    val v = raw?.let { p -> p.copy(deals = p.deals.map { DelaCrm.overlayDeal(it, ctx.ops, ctx.today) }) }
    val money = ctx.money && v?.money == true
    item(key = "crm:pipe") {
        PaperCard(
            label = "воронка",
            info = "Сделки по стадиям: лид → КП → мандат → в работе → закрытие. У каждой — следующее дело или красное «нет следующего дела»; " +
                "застывшая — без открытого дела или месяц без движения. Новая сделка — со страницы клиента в вебе.",
        ) {
            Freshness(ctx, DelaCrm.PIPELINE)
            if (v != null) {
                val bits = mutableListOf(plural(v.live, "живая сделка", "живые сделки", "живых сделок"))
                if (money) {
                    v.pipelineKop?.let { bits += "воронка взвешенно " + DelaCrm.rubShort(it) }
                    v.toGetKop?.let { bits += "подписано, получить " + DelaCrm.rubShort(it) }
                }
                PaperHint(bits.joinToString(" · "))
                if (v.stale > 0) {
                    ChipRow {
                        PaperChip("Застывшие · ${v.stale}", selected = ctx.ui.staleOnly, warn = true, onClick = { ctx.ui.staleOnly = !ctx.ui.staleOnly })
                    }
                }
            }
        }
    }
    if (v == null) return
    val stages = v.byStage(onlyStale = ctx.ui.staleOnly)
    for ((stage, deals) in stages) {
        item(key = "crm:st:$stage") {
            val sum = if (money) v.stages.firstOrNull { it.stage == stage }?.feeKop?.takeIf { it > 0 }?.let { " · " + DelaCrm.rubShort(it) }.orEmpty() else ""
            PaperCard(label = (DelaCrm.STAGE[stage] ?: stage) + " · ${deals.size}" + sum) {
                deals.forEachIndexed { i, d ->
                    if (i > 0) RowRule()
                    DealRow(ctx, d, money)
                }
            }
        }
    }
    val closed = v.closed.filter { !ctx.ui.staleOnly || it.stale }
    if (closed.isNotEmpty()) {
        item(key = "crm:closed") {
            var open by remember { mutableStateOf(false) }
            PaperCard {
                SummaryLine(title = "Закрыты за 90 дней", summary = closed.size.toString(), expanded = open, onToggle = { open = !open }) {
                    closed.forEachIndexed { i, d ->
                        if (i > 0) RowRule()
                        DealRow(ctx, d, money)
                    }
                }
            }
        }
    }
    if (stages.isEmpty() && closed.isEmpty()) {
        item(key = "crm:pipe:empty") {
            Box(Modifier.padding(start = 4.dp)) {
                PaperHint(if (ctx.ui.staleOnly) "Застывших нет." else "Сделок нет. Новая — со страницы клиента в вебе, кнопкой «+ Сделка».")
            }
        }
    }
}

/**
 * Сделка строкой: название, клиент и тип, кто ведёт, гонорар (если деньги
 * открыты), «тишина N дн.»; ниже — следующее дело или красное «нет
 * следующего дела». У закрытой — итог, дата и причина.
 */
@Composable
private fun DealRow(ctx: DelaCrmContext, d: DelaCrm.Deal, money: Boolean) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { ctx.push(DelaPage.Deal(d.id)) }.padding(vertical = 6.dp)) {
        Text(d.name + if (d.local) " ⏳" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        val meta = listOfNotNull(
            (d.projectName.ifBlank { ctx.snap.projects[d.projectId]?.name.orEmpty() } + if (d.dealType.isNotBlank()) " · ${d.dealType}" else "").takeIf { it.isNotBlank() },
            ctx.personName(d.leadPersonId).takeIf { it.isNotBlank() },
            if (money) d.feeKop?.takeIf { it > 0 }?.let {
                DelaCrm.rubShort(it) + if (d.pEff != null && d.stage in setOf("lead", "proposal")) " · ${d.pEff}%" else ""
            } else null,
            DelaCrm.hours(d.minutes30).takeIf { it.isNotBlank() }?.let { "$it за 30 дн." },
        ).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        if (d.closed) {
            val out = listOf(d.stageWord, d.closedOn.takeIf { it.isNotBlank() }?.let { DelaAsk.ddmm(it, ctx.today) }.orEmpty(), d.lostReason)
                .filter { it.isNotBlank() }.joinToString(" · ")
            Text(out, style = MaterialTheme.typography.bodySmall, color = if (d.outcome == "won") c.primary else c.onSurfaceVariant, maxLines = 2)
        } else {
            val next = ctx.nextFor(d.id, d.nextTask)
            val warn = listOfNotNull(
                "застыла".takeIf { d.stale },
                "тишина ${d.quietDays} дн.".takeIf { d.quiet },
            ).joinToString(" · ")
            if (next != null) {
                Text(
                    "→ #${next.num} ${next.title}" + if (next.dueDate.isNotBlank()) " · " + DelaAsk.ddmm(next.dueDate, ctx.today) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (next.dueDate.isNotBlank() && next.dueDate < ctx.today) c.error else c.onSurface,
                )
            } else {
                Text("нет следующего дела", style = MaterialTheme.typography.bodySmall, color = c.error)
            }
            if (warn.isNotBlank()) Text(warn, style = MaterialTheme.typography.bodySmall, color = c.error)
        }
    }
}

// ---------------------------------------------------------------- клиенты

internal fun LazyListScope.crmClients(ctx: DelaCrmContext) {
    val v = ctx.data(DelaCrm.CLIENTS)?.let(DelaCrm::clients)
    val money = ctx.money && v?.money == true
    val rows = v?.find(ctx.ui.clientQuery, ctx.ui.clientArchive).orEmpty()
    // Проекты клиента (сделки CRM) раскрываются под его строкой — так же, как в ☰ (06.10.2026).
    val withDeals = rows.filter { DelaCrm.clientDeals(ctx.snap, it.id).isNotEmpty() }.map { it.id }
    val allOpen = withDeals.isNotEmpty() && withDeals.all { it in ctx.clientOpen }
    item(key = "crm:clients") {
        PaperCard(
            label = "клиенты",
            info = "Последний контакт, следующее дело и стадии сделок. Контакт старше полутора месяцев — красным. " +
                "Стрелка у клиента — его проекты (сделки) прямо здесь; раскрытое помнится.",
        ) {
            Freshness(ctx, DelaCrm.CLIENTS)
            PaperField(value = ctx.ui.clientQuery, onValueChange = { ctx.ui.clientQuery = it }, label = "Найти клиента")
            ChipRow {
                PaperChip("и архив", selected = ctx.ui.clientArchive, onClick = { ctx.ui.clientArchive = !ctx.ui.clientArchive })
                if (withDeals.isNotEmpty()) {
                    PaperChip(if (allOpen) "Свернуть проекты" else "Раскрыть проекты", selected = allOpen, onClick = { ctx.setClientOpen(withDeals, !allOpen) })
                }
            }
        }
    }
    if (v == null) return
    item(key = "crm:clients:list") {
        PaperCard(label = plural(rows.count { it.live }, "клиент", "клиента", "клиентов")) {
            if (rows.isEmpty()) PaperHint("Не нашлось.")
            rows.forEachIndexed { i, cl ->
                if (i > 0) RowRule()
                ClientRow(ctx, cl, money)
            }
        }
    }
}

/** Цвет стадии — точкой и словом, как `st-*` веба: лид бледный, КП янтарный, мандат и работа — зелёные, закрытие — краской режима. */
@Composable
internal fun stageColor(stage: String): Color {
    val c = MaterialTheme.colorScheme
    return when (stage) {
        "proposal" -> Color(0xFFE0A33A)
        "mandate", "active" -> Color(0xFF3FBF8F)
        "closing" -> c.primary
        else -> c.outline
    }
}

@Composable
internal fun StageDot(stage: String) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(stageColor(stage)))
}

/**
 * Проект клиента строкой под ним (`dealLine` веба): стадия цветом, короткое
 * имя, следующее дело или красное «нет следующего дела». Тап — страница сделки.
 */
@Composable
private fun ClientDealLine(ctx: DelaCrmContext, d: Dela.Deal, client: Dela.Project?) {
    val c = MaterialTheme.colorScheme
    val next = ctx.snap.tasks.values.filter { it.dealId == d.id && it.open }.sortedWith(Dela.ORDER).firstOrNull()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { ctx.push(DelaPage.Deal(d.id)) }.padding(start = 30.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StageDot(d.stage)
            Spacer(Modifier.width(6.dp))
            Text(
                (if (d.closed && d.outcome.isNotBlank()) DelaCrm.OUTCOME[d.outcome] ?: d.outcome else DelaCrm.STAGE[d.stage] ?: d.stage) + " · ",
                style = MaterialTheme.typography.bodySmall, color = stageColor(d.stage),
            )
            Text(DelaCrm.dealShort(d.name, client), style = MaterialTheme.typography.bodyMedium)
        }
        if (next != null) {
            Text(
                "→ #${next.num} ${next.title}" + if (next.dueDate.isNotBlank()) " · " + DelaAsk.ddmm(next.dueDate, ctx.today) else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (next.dueDate.isNotBlank() && next.dueDate < ctx.today) c.error else c.onSurfaceVariant,
            )
        } else if (!d.closed) {
            Text("нет следующего дела", style = MaterialTheme.typography.bodySmall, color = c.error)
        }
    }
}

@Composable
private fun ClientRow(ctx: DelaCrmContext, cl: DelaCrm.Client, money: Boolean) {
    val c = MaterialTheme.colorScheme
    val deals = DelaCrm.clientDeals(ctx.snap, cl.id)
    val open = cl.id in ctx.clientOpen
    Row(verticalAlignment = Alignment.Top) {
        // Стрелка — проекты клиента прямо здесь; у клиента без проектов — пустое место, строки не прыгают.
        if (deals.isNotEmpty()) {
            GlyphButton(
                if (open) Glyphs.ChevronUp else Glyphs.ChevronDown,
                if (open) "свернуть проекты" else "проекты клиента: ${deals.size}",
                onClick = { ctx.setClientOpen(listOf(cl.id), !open) },
                size = 30.dp,
            )
        } else {
            Spacer(Modifier.width(30.dp))
        }
        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { ctx.push(DelaPage.Project(cl.id)) }.padding(vertical = 6.dp)) {
            val tail = listOfNotNull(
                cl.org.takeIf { it.isNotBlank() && Dela.norm(it) != Dela.norm(cl.name) },
                "архив".takeIf { !cl.live },
                plural(deals.size, "проект", "проекта", "проектов").takeIf { deals.isNotEmpty() },
            ).joinToString(" · ")
            Text(cl.name + if (tail.isNotBlank()) " · $tail" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val stages = if (cl.stages.isNotEmpty()) cl.stages.joinToString(", ") { DelaCrm.STAGE[it] ?: it } else if (cl.allDeals > 0) "сделки в архиве" else "без сделок"
            val late = (DelaCrm.daysSince(cl.lastTouch, ctx.today) ?: 0) > 45
            val touch = if (cl.lastTouch.isNotBlank()) "контакт " + DelaCrm.ago(cl.lastTouch, ctx.today) else "контактов нет"
            val moneyBits = if (money) listOfNotNull(
                cl.paidYearKop?.takeIf { it > 0 }?.let { "за год " + DelaCrm.rubShort(it) },
                cl.invoicedKop?.takeIf { it > 0 }?.let { "ждём " + DelaCrm.rubShort(it) },
            ) else emptyList()
            Text(
                (listOf(stages, touch) + moneyBits + listOfNotNull(DelaCrm.hours(cl.minutes90).takeIf { it.isNotBlank() }?.let { "$it за 90 дн." })).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (late) c.error else c.onSurfaceVariant,
            )
            val next = cl.nextTask
            if (open) {
                // Раскрыто — следующее дело видно у каждого проекта ниже.
            } else if (next != null) {
                Text("→ #${next.num} ${next.title}" + if (next.dueDate.isNotBlank()) " · " + DelaAsk.ddmm(next.dueDate, ctx.today) else "",
                    style = MaterialTheme.typography.bodySmall)
            } else if (cl.liveDeals > 0) {
                Text("нет следующего дела", style = MaterialTheme.typography.bodySmall, color = c.error)
            }
        }
    }
    if (open) {
        val client = ctx.snap.projects[cl.id]
        val shown = deals.filter { !it.closed || ctx.ui.clientArchive }
        for (d in shown) ClientDealLine(ctx, d, client)
        if (deals.size > shown.size) {
            Text("и в архиве: ${deals.size - shown.size}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp, bottom = 4.dp))
        }
    }
}

// ---------------------------------------------------------------- люди

/**
 * «Люди» по компаниям (06.10.2026, docs/dela-phone-4.md, `renderPeople` веба;
 * владелец: «в людях обязательно нужна группировка по компаниям»): «Команда»
 * сверху, организации (клиент — стрелкой на его страницу), «Без компании»
 * внизу. У строки — «откуда…»: компания и должность, не заходя в карточку.
 * Всё — из своей копии: виден и только что поправленный.
 */
internal fun LazyListScope.crmPeople(ctx: DelaCrmContext) {
    val groups = DelaCrm.peopleByCompany(ctx.snap, ctx.ui.peopleQuery)
    val openBy = HashMap<String, Int>()
    val waitBy = HashMap<String, Int>()
    for (t in ctx.snap.tasks.values) {
        if (!t.open || t.personId.isBlank()) continue
        openBy[t.personId] = (openBy[t.personId] ?: 0) + 1
        if (t.ball == Dela.WAITING) waitBy[t.personId] = (waitBy[t.personId] ?: 0) + 1
    }
    val total = groups.sumOf { it.people.size }
    item(key = "crm:people") {
        PaperCard(
            label = "люди",
            info = "По компаниям: команда, клиенты, другие организации. «откуда…» у человека — компания и должность. " +
                "Сказать словами — «Иван теперь CFO в Бете» — строкой Claude наверху.",
        ) {
            PaperField(value = ctx.ui.peopleQuery, onValueChange = { ctx.ui.peopleQuery = it }, label = "Найти человека или компанию")
            PaperHint(plural(total, "человек", "человека", "человек") + " · по компаниям: команда, клиенты, другие организации")
        }
    }
    for (g in groups) {
        item(key = "crm:pg:" + g.key) {
            PaperCard(
                label = "${g.title} · ${g.people.size}",
                trailing = if (g.clientId.isNotBlank()) {
                    { GlyphButton(Glyphs.Forward, "карточка клиента", onClick = { ctx.push(DelaPage.Project(g.clientId)) }, size = 30.dp) }
                } else null,
            ) {
                g.people.forEachIndexed { i, p ->
                    if (i > 0) RowRule()
                    PersonLine(ctx, p, openBy[p.id] ?: 0, waitBy[p.id] ?: 0)
                }
            }
        }
    }
    if (groups.isEmpty()) {
        item(key = "crm:people:none") { Box(Modifier.padding(start = 4.dp)) { PaperHint("Не нашлось.") } }
    }
}

@Composable
private fun PersonLine(ctx: DelaCrmContext, p: Dela.Person, open: Int, waiting: Int) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Avatar(p, 28.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { ctx.push(DelaPage.Person(p.id)) }.padding(vertical = 6.dp)) {
            Text(p.name + (if (p.short.isNotBlank() && p.short != p.name) " · ${p.short}" else "") + if (p.local) " ⏳" else "",
                style = MaterialTheme.typography.bodyMedium)
            val meta = listOfNotNull(
                p.role.takeIf { it.isNotBlank() },
                plural(open, "дело", "дела", "дел").takeIf { open > 0 },
                "жду: $waiting".takeIf { waiting > 0 },
            ).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        PaperTextButton("откуда…", onClick = { ctx.ui.personFor = p.id })
    }
}

// ---------------------------------------------------------------- связи

internal fun LazyListScope.crmTies(ctx: DelaCrmContext) {
    val v = ctx.data(DelaCrm.TIES)?.let(DelaCrm::ties)
    item(key = "crm:ties") {
        PaperCard(
            label = "связи",
            info = "Кому пора напомнить о себе: по теплоте из карточки человека (раз в месяц, квартал, год) и последнему контакту в хронологии. " +
                "Теплота и «хаб» ставятся на странице человека; сюда попадают и те, кто приводил сделки.",
        ) { Freshness(ctx, DelaCrm.TIES) }
    }
    if (v == null) return
    if (v.birthdays.isNotEmpty()) {
        item(key = "crm:bd") {
            PaperCard(label = "дни рождения в ближайшие две недели · ${v.birthdays.size}") {
                v.birthdays.forEachIndexed { i, p -> if (i > 0) RowRule(); TieRow(ctx, p) }
            }
        }
    }
    item(key = "crm:due") {
        PaperCard(label = "пора напомнить о себе · ${v.due.size}", labelColor = if (v.due.isNotEmpty()) MaterialTheme.colorScheme.error else null) {
            if (v.due.isEmpty()) PaperHint("Со всеми на связи.")
            v.due.forEachIndexed { i, p -> if (i > 0) RowRule(); TieRow(ctx, p) }
        }
    }
    if (v.rest.isNotEmpty()) {
        item(key = "crm:rest") {
            var open by remember { mutableStateOf(false) }
            PaperCard {
                SummaryLine(title = "На связи", summary = v.rest.size.toString(), expanded = open, onToggle = { open = !open }) {
                    v.rest.forEachIndexed { i, p -> if (i > 0) RowRule(); TieRow(ctx, p) }
                }
            }
        }
    }
}

@Composable
private fun TieRow(ctx: DelaCrmContext, p: DelaCrm.Tie) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { ctx.push(DelaPage.Person(p.id)) }.padding(vertical = 6.dp)) {
            Text(p.name + if (p.org.isNotBlank()) " · ${p.org}" else "", style = MaterialTheme.typography.bodyMedium)
            val since = p.sinceDays?.let { if (it == 0) "контакт сегодня" else "контакт $it дн. назад" } ?: "контактов не записано"
            val bits = listOfNotNull(
                DelaCrm.CADENCE[p.cadence],
                "хаб".takeIf { p.hub },
                since,
                p.brought.takeIf { it > 0 }?.let { "привёл сделок: $it" },
                p.agenda.takeIf { it > 0 }?.let { "повестка: $it" },
                p.birthdayIn?.takeIf { it <= 14 }?.let { if (it == 0) "день рождения сегодня" else "день рождения через $it дн." },
            ).joinToString(" · ")
            Text(bits, style = MaterialTheme.typography.bodySmall, color = if (p.due) c.error else c.onSurfaceVariant)
        }
        PaperTextButton("Поговорили", onClick = {
            ctx.ui.talk = TalkTarget(p.name, personIds = listOf(p.id), refresh = listOf(DelaCrm.TIES, DelaCrm.dossierPath(p.id)))
        })
    }
}

// ---------------------------------------------------------------- клиент

/**
 * Клиент на странице проекта: люди плашками, сделки, вкладки «Дела» /
 * «Хронология» (06.10.2026, как `renderProject` веба) — дела проекта под
 * вкладкой «Дела» рисует вкладка из своей копии.
 */
internal fun LazyListScope.crmClientBlock(ctx: DelaCrmContext, projectId: String) {
    val path = DelaCrm.clientPath(projectId)
    val v = ctx.data(path)?.let(DelaCrm::client)
    val project = ctx.snap.projects[projectId] ?: v?.project
    val money = ctx.money && v?.money == true
    item(key = "crm:client") {
        PaperCard(label = "клиент") {
            Text(project?.name ?: "клиент не найден", style = MaterialTheme.typography.titleMedium)
            if (project != null && project.aliases.isNotEmpty()) PaperHint("ещё зовут: " + project.aliases.joinToString(", "))
            if (project != null && project.note.isNotBlank()) PaperHint(project.note)
            Freshness(ctx, path)
            // Люди — плашками из своей копии (крестик и «+ человек» видны сразу); пока копия
            // не знает людей клиента — что сказал сервер.
            val people = DelaCrm.clientPeople(ctx.snap, projectId).ifEmpty {
                v?.people.orEmpty().map { r -> ctx.snap.people[r.id] ?: Dela.Person(r.id, r.name, short = r.short, role = r.role) }
            }
            PeopleChips(
                ctx, "Люди", people,
                onRemove = { p -> ctx.ui.leaving = p to projectId },
                onAdd = { ctx.ui.addTo = AddTarget("К «${project?.name ?: "клиенту"}»", people.map { it.id }.toSet(), clientId = projectId) },
                empty = "пока никого",
            )
        }
    }
    // Сделки: из ответа вида (с посчитанным), а пока его нет — из копии синка.
    val deals = (v?.deals ?: ctx.snap.allDealsOf(projectId).map { fromCopy(it, project?.name.orEmpty()) })
        .map { DelaCrm.overlayDeal(it, ctx.ops, ctx.today) }
        .sortedWith(compareBy<DelaCrm.Deal>({ it.closed }, { Dela.STAGES.indexOf(it.stage) }, { it.name.lowercase() }))
    item(key = "crm:client:deals") {
        PaperCard(label = "проекты · ${deals.size}") {
            if (deals.isEmpty()) PaperHint("Проектов (сделок) нет. Новый — в вебе, кнопкой «+ Сделка».")
            deals.forEachIndexed { i, d -> if (i > 0) RowRule(); DealRow(ctx, d, money) }
        }
    }
    item(key = "crm:client:tabs") { CardTabs(ctx) }
    if (ctx.ui.tab == TAB_TIMELINE) {
        item(key = "crm:client:tl") {
            val items = DelaCrm.overlayTimeline(v?.timeline.orEmpty(), ctx.ops, ctx.snap) { it.projectId == projectId }
            TimelineCard(ctx, items, TalkTarget(project?.name.orEmpty(), projectId = projectId, refresh = listOf(path)), loaded = v != null, showDeal = true)
        }
    }
}

/** Сделка из копии синка — пока вид не пришёл: без посчитанного сервером, но с именем, стадией и итогом. */
private fun fromCopy(d: Dela.Deal, projectName: String): DelaCrm.Deal = DelaCrm.Deal(
    id = d.id, projectId = d.projectId, projectName = projectName, name = d.name, stage = d.stage, outcome = d.outcome,
    lostReason = d.lostReason, closedOn = d.closedOn, dealType = d.dealType, leadPersonId = d.leadPersonId,
    teamIds = d.teamIds, personIds = d.personIds, feeKop = d.feeKop, local = d.local,
    sourcePersonId = d.sourcePersonId, probability = d.probability, expectedOn = d.expectedOn, deadline = d.deadline,
)

// ---------------------------------------------------------------- сделка

/**
 * Страница сделки — как в вебе (06.10.2026, docs/dela-phone-4.md; владелец:
 * «если я нажимаю на проект, он должен быть не сайдбаром, а таким же, как
 * клиент и человек»): наверху пилюля Claude (рисует вкладка), стадия
 * переключателем, «Закрыть…» с итогом, «Вернуть в работу»; люди клиента и
 * команда — плашками; вкладки «Дела» (следующий шаг — открытые дела сделки и
 * «Следующее дело…») и «Хронология»; «что за сделка» и деньги — свёрнутыми
 * строками ниже.
 */
internal fun LazyListScope.crmDealPage(ctx: DelaCrmContext, dealId: String) {
    val path = DelaCrm.dealPath(dealId)
    val v = ctx.data(path)?.let(DelaCrm::dealView)
    val base = v?.deal ?: ctx.snap.deals[dealId]?.let { fromCopy(it, ctx.snap.projects[it.projectId]?.name.orEmpty()) }
    if (base == null) {
        item(key = "crm:deal:none") {
            PaperCard(label = "сделка") {
                Freshness(ctx, path)
                PaperHint("Сделка не видна — может быть, её закрыли от тебя или она ещё не пришла с сервера.")
            }
        }
        return
    }
    // Люди и команда — из своей копии, если она знает сделку: там и только что убранный крестиком.
    val copy = ctx.snap.deals[dealId]
    val d = DelaCrm.overlayDeal(base, ctx.ops, ctx.today).let { o -> if (copy != null) o.copy(personIds = copy.personIds, teamIds = copy.teamIds) else o }
    val refreshDeal = listOf(path, DelaCrm.clientPath(d.projectId))
    // Деньги — кому открыты; сервер сам пометил ответ (`money`). Гонорара может не быть, а оплаты — быть.
    val money = ctx.money && (v?.money ?: true)
    item(key = "crm:deal:head") {
        PaperCard(label = "сделка · " + d.stageWord + if (d.local) " ⏳" else "") {
            Text(d.name, style = MaterialTheme.typography.titleMedium)
            PaperRow(
                title = d.projectName.ifBlank { ctx.snap.projects[d.projectId]?.name ?: "клиент" },
                hint = "клиент",
                icon = Glyphs.Layers,
                onClick = { ctx.push(DelaPage.Project(d.projectId)) },
            )
            Freshness(ctx, path)
            if (d.closed) {
                val out = listOf(d.stageWord, d.closedOn.takeIf { it.isNotBlank() }?.let { DelaAsk.ddmm(it, ctx.today) }.orEmpty(), d.lostReason)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                Text(out, style = MaterialTheme.typography.bodyMedium, color = if (d.outcome == "won") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                PaperButton("Вернуть в работу", icon = Glyphs.Undo, onClick = {
                    ctx.run(listOf(DelaCrm.reopenOp(d.id)), listOf(path, DelaCrm.PIPELINE, DelaCrm.clientPath(d.projectId)), "Сделка снова в работе")
                })
            } else {
                PaperHint("Стадия")
                ChipRow {
                    for (st in Dela.OPEN_STAGES) {
                        PaperChip(DelaCrm.STAGE[st] ?: st, selected = d.stage == st, onClick = {
                            if (d.stage != st) ctx.run(listOf(DelaCrm.stageOp(d.id, st)), listOf(path, DelaCrm.PIPELINE, DelaCrm.clientPath(d.projectId)))
                        })
                    }
                }
                Row {
                    Spacer(Modifier.weight(1f))
                    PaperTextButton("Закрыть…", icon = Glyphs.Check, onClick = { ctx.ui.closing = d })
                }
            }
        }
    }

    // Люди клиента и команда — плашками: крестик убирает из сделки, «+ человек» — из справочника или новый.
    item(key = "crm:deal:people") {
        PaperCard {
            DealPeople(ctx, d, "person_ids", "Люди клиента", d.personIds, refreshDeal)
            DealPeople(ctx, d, "team_ids", "Команда", d.teamIds, refreshDeal)
        }
    }
    item(key = "crm:deal:tabs") { CardTabs(ctx) }

    // Следующий шаг — открытое дело сделки (правило 12 сервера), из своей копии.
    val open = ctx.snap.tasks.values.filter { it.dealId == dealId && it.open }.sortedWith(Dela.ORDER)
        .ifEmpty { v?.open.orEmpty() }
    if (ctx.ui.tab != TAB_TIMELINE) item(key = "crm:deal:next") {
        var next by remember(dealId) { mutableStateOf("") }
        PaperCard(label = "следующий шаг · ${open.size}") {
            if (open.isNotEmpty()) TaskRows(open, ctx.actions)
            else if (!d.closed) PaperHint("Нет следующего дела — сделка без шага застынет.", MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaperField(value = next, onValueChange = { next = it }, label = "Следующее дело…", singleLine = false, maxLines = 3, modifier = Modifier.weight(1f))
                GlyphButton(Glyphs.Send, "завести дело по сделке", enabled = next.isNotBlank(), onClick = {
                    val title = next.trim()
                    next = ""
                    ctx.run(listOf(DelaCrm.nextTaskOp(title, d.projectId, d.id, ctx.me)), listOf(path, DelaCrm.PIPELINE), "✓ следующее дело: ${title.take(40)}")
                })
            }
            val done = v?.done.orEmpty()
            if (done.isNotEmpty()) {
                var shown by remember { mutableStateOf(false) }
                SummaryLine(title = "Сделано по сделке", summary = done.size.toString(), expanded = shown, onToggle = { shown = !shown }) {
                    for (t in done) PaperHint("#${t.num} ${t.title}" + if (t.completedAt.isNotBlank()) " · " + DelaAsk.ddmm(t.completedAt, ctx.today) else "")
                }
            }
        }
    }

    if (ctx.ui.tab == TAB_TIMELINE) {
        item(key = "crm:deal:tl") {
            val items = DelaCrm.overlayTimeline(v?.timeline.orEmpty(), ctx.ops, ctx.snap) { it.dealId == dealId }
            TimelineCard(
                ctx,
                items,
                TalkTarget(d.name, projectId = d.projectId, dealId = d.id, personIds = d.personIds, refresh = refreshDeal),
                loaded = v != null,
            )
        }
    }

    // «Что за сделка» — свёрнутой строкой под вкладками: тип, кто ведёт, кто привёл, вероятность, сроки.
    item(key = "crm:deal:about") {
        var shown by remember { mutableStateOf(false) }
        val lines = listOf(
            "Тип" to d.dealType,
            "Ведёт" to ctx.personName(d.leadPersonId),
            "Привёл" to ctx.personName(d.sourcePersonId),
            "Вероятность" to (d.probability?.let { "$it %" } ?: d.pEff?.let { "$it % (по стадии)" }.orEmpty()),
            "Решение ждём" to d.expectedOn.takeIf { it.isNotBlank() }?.let { DelaAsk.ddmm(it, ctx.today) }.orEmpty(),
            "Дедлайн" to d.deadline.takeIf { it.isNotBlank() }?.let { DelaAsk.ddmm(it, ctx.today) }.orEmpty(),
        ).filter { it.second.isNotBlank() }
        PaperCard {
            SummaryLine(
                title = "Что за сделка",
                summary = lines.take(2).joinToString(" · ") { it.second }.ifBlank { "тип, кто ведёт, вероятность, сроки" },
                expanded = shown,
                onToggle = { shown = !shown },
            ) {
                if (lines.isEmpty()) PaperHint("Ничего не записано. Скажи Claude наверху: «тип — M&A, ведёт Иван, решение ждём к 20-му».")
                for ((k, value) in lines) PaperHint("$k: $value", MaterialTheme.colorScheme.onSurface)
            }
        }
    }

    if (money) {
        val pays = DelaCrm.overlayPayments(v?.payments ?: ctx.snap.paymentsOf(dealId), ctx.ops)
        item(key = "crm:deal:money") { DealMoney(ctx, d, pays, listOf(path)) }
    }

    val minutes = DelaCrm.hours(v?.minutesAll)
    if (d.myView.isNotBlank() || minutes.isNotBlank() || v?.history?.isNotEmpty() == true) {
        item(key = "crm:deal:more") {
            var shown by remember { mutableStateOf(false) }
            PaperCard {
                if (minutes.isNotBlank()) PaperHint("Время из Засечки по делам сделки: $minutes")
                if (d.myView.isNotBlank()) PaperHint("Как я вижу: " + d.myView)
                val history = v?.history.orEmpty()
                if (history.isNotEmpty()) {
                    SummaryLine(title = "Журнал сделки", summary = history.size.toString(), expanded = shown, onToggle = { shown = !shown }) {
                        for (h in history.takeLast(30).reversed()) PaperHint(h)
                    }
                }
            }
        }
    }
}

/**
 * Деньги сделки — только кому открыты, свёрнутой строкой (как `details` веба):
 * гонорар, сколько получено, счета, план; раскрыл — оплаты с «Счёт выставлен»
 * и «Оплачено». План оплат заводится в вебе (этап 3 телефона — после слова владельца).
 */
@Composable
private fun DealMoney(ctx: DelaCrmContext, d: DelaCrm.Deal, pays: List<Dela.Payment>, refresh: List<String>) {
    val c = MaterialTheme.colorScheme
    val live = pays.filter { it.live }
    val paid = live.filter { it.paidOn.isNotBlank() }.sumOf { it.amountKop }
    val inv = live.filter { it.paidOn.isBlank() && it.invoicedOn.isNotBlank() }.sumOf { it.amountKop }
    val plan = live.filter { it.paidOn.isBlank() && it.invoicedOn.isBlank() }.sumOf { it.amountKop }
    var shown by remember { mutableStateOf(false) }
    val fee = d.feeKop ?: 0L
    val sum = listOfNotNull(
        DelaCrm.rub(fee).takeIf { it.isNotBlank() }?.let { "гонорар $it" },
        DelaCrm.rub(paid).takeIf { it.isNotBlank() }?.let { "получено $it" },
        DelaCrm.rub(inv).takeIf { it.isNotBlank() }?.let { "счета $it" },
        DelaCrm.rub(plan).takeIf { it.isNotBlank() }?.let { "план $it" },
        if (fee > 0 && d.stage in setOf("mandate", "active", "closing")) DelaCrm.rub(maxOf(fee - paid, 0L)).takeIf { it.isNotBlank() }?.let { "осталось $it" } else null,
    ).joinToString(" · ").ifBlank { "оплат пока нет" }
    PaperCard {
        SummaryLine(title = "Деньги", summary = sum, expanded = shown, onToggle = { shown = !shown }) {
            pays.forEachIndexed { i, p ->
                if (i > 0) RowRule()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            DelaCrm.rub(p.amountKop) + " " + (DelaCrm.PAY_KIND[p.kind] ?: p.kind) + (if (p.title.isNotBlank()) " · ${p.title}" else "") + if (p.local) " ⏳" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val late = p.paidOn.isBlank() && p.invoicedOn.isNotBlank() && (p.dueOn.ifBlank { p.invoicedOn }) < ctx.today
                        val st = when {
                            !p.live -> "отменено"
                            p.paidOn.isNotBlank() -> "получено " + DelaAsk.ddmm(p.paidOn, ctx.today)
                            p.invoicedOn.isNotBlank() -> "счёт " + DelaAsk.ddmm(p.invoicedOn, ctx.today) + if (p.dueOn.isNotBlank()) ", срок " + DelaAsk.ddmm(p.dueOn, ctx.today) else ""
                            p.dueOn.isNotBlank() -> "ждём " + DelaAsk.ddmm(p.dueOn, ctx.today)
                            else -> "без даты"
                        }
                        Text(st + if (p.note.isNotBlank()) " · ${p.note}" else "", style = MaterialTheme.typography.bodySmall,
                            color = if (late) c.error else if (p.paidOn.isNotBlank()) c.primary else c.onSurfaceVariant)
                    }
                    if (p.live && p.paidOn.isBlank()) {
                        Column(horizontalAlignment = Alignment.End) {
                            if (p.invoicedOn.isBlank()) PaperTextButton("Счёт выставлен", onClick = { ctx.run(listOf(DelaCrm.invoicedOp(p.id, ctx.today)), refresh, "Счёт выставлен") })
                            PaperTextButton("Оплачено", icon = Glyphs.Check, color = c.primary, onClick = { ctx.run(listOf(DelaCrm.paidOp(p.id, ctx.today)), refresh, "Оплата получена") })
                        }
                    }
                }
            }
            if (pays.isEmpty()) PaperHint("План оплат (аванс, этапы, ретейнер) заводится в вебе.")
        }
    }
}

/**
 * Люди сделки ([field] = `person_ids`) или её команда (`team_ids`) плашками:
 * крестик — убрать из сделки (с «Вернуть»), «+ человек» — из справочника или
 * новый (в люди клиента — сразу с организацией клиента).
 */
@Composable
private fun DealPeople(ctx: DelaCrmContext, d: DelaCrm.Deal, field: String, title: String, ids: List<String>, refresh: List<String>) {
    val people = ids.mapNotNull { ctx.snap.people[it] }
    val where = if (field == "team_ids") "в команде" else "в людях сделки"
    PeopleChips(
        ctx, title, people,
        onRemove = { p ->
            ctx.runUndoable(
                DelaCrm.Undoable(listOf(DelaCrm.dealPeopleOp(d.id, field, ids - p.id)), listOf(DelaCrm.dealPeopleOp(d.id, field, ids)), "${p.label} больше не $where"),
                refresh,
            )
        },
        onAdd = {
            ctx.ui.addTo = AddTarget(
                "$title — «${d.name}»", ids.toSet(), clientId = if (field == "person_ids") d.projectId else "", dealId = d.id, field = field, ids = ids,
            )
        },
    )
}

// ---------------------------------------------------------------- человек

/** Человек в CRM: теплота и «хаб», его сделки и хронология (`dossier`). */
internal fun LazyListScope.crmPersonBlock(ctx: DelaCrmContext, personId: String) {
    val path = DelaCrm.dossierPath(personId)
    val v = ctx.data(path)?.let(DelaCrm::dossier)
    val p = ctx.snap.people[personId]
    item(key = "crm:person") {
        PaperCard(label = "связь", info = "Теплота — как часто напоминать о себе: по ней и последнему контакту человек попадает в «Связи». Хаб — через него идут темы.") {
            PaperHint("Теплота")
            ChipRow {
                for ((k, word) in DelaCrm.CADENCE) {
                    PaperChip(word, selected = p?.cadence == k, onClick = {
                        ctx.run(listOf(DelaCrm.personOp(personId, cadence = if (p?.cadence == k) "" else k)), listOf(DelaCrm.TIES))
                    })
                }
            }
            PaperToggle(
                "Хаб",
                checked = p?.hub == true,
                onCheckedChange = { on -> ctx.run(listOf(DelaCrm.personOp(personId, hub = on)), listOf(DelaCrm.TIES)) },
                hint = "через него идут темы",
            )
            Freshness(ctx, path)
        }
    }
    // Одна карточка (06.10.2026, docs/svod-phone.md, 3.6): другие имена и номера —
    // по ним звонок, встреча и Telegram узнают того же человека.
    if (p != null && ru.zf.pravka.core.Svod.FEATURE_PEOPLE in ctx.snap.features) {
        item(key = "crm:person:names") { PersonNamesCard(ctx, p, path) }
    }
    val deals = v?.deals.orEmpty().map { DelaCrm.overlayDeal(it, ctx.ops, ctx.today) }
    if (deals.isNotEmpty()) {
        item(key = "crm:person:deals") {
            PaperCard(label = "сделки · ${deals.size}") {
                deals.forEachIndexed { i, d -> if (i > 0) RowRule(); DealRow(ctx, d, ctx.money) }
            }
        }
    }
    item(key = "crm:person:tl") {
        val items = DelaCrm.overlayTimeline(v?.timeline.orEmpty(), ctx.ops, ctx.snap) { personId in it.personIds }
        TimelineCard(ctx, items, TalkTarget(p?.name.orEmpty(), personIds = listOf(personId), refresh = listOf(path, DelaCrm.TIES)), loaded = v != null, showDeal = true)
    }
}

/**
 * «Другие имена» и «Номера» человека — показать и дописать (`person.add`);
 * «Это тот же, что…» — слить дубль в живую карточку (`person.merge`) после
 * подтверждения листом: слияние уводит дубль в архив, ссылки переезжают.
 */
@Composable
private fun PersonNamesCard(ctx: DelaCrmContext, p: Dela.Person, path: String) {
    var text by remember(p.id) { mutableStateOf("") }
    var picking by remember(p.id) { mutableStateOf(false) }
    var query by remember(p.id) { mutableStateOf("") }
    var into by remember(p.id) { mutableStateOf<Dela.Person?>(null) }
    PaperCard(
        label = "имена и номера",
        info = "По другим именам и номерам человека узнают звонок, встреча и Telegram: «Женя Соколов» из контакта — " +
            "тот же «Евгений Соколов». Дописанное уходит на сервер для всех.",
    ) {
        PaperHint("Другие имена: " + p.aliases.joinToString(", ").ifBlank { "нет" })
        PaperHint("Номера: " + p.phones.joinToString(", ").ifBlank { "нет" })
        PaperField(value = text, onValueChange = { text = it }, placeholder = "имя или номер")
        Row {
            Spacer(Modifier.weight(1f))
            val t = text.trim()
            val isPhone = t.count { it.isDigit() } >= 7 && t.none { it.isLetter() }
            PaperTextButton(if (isPhone) "Дописать номер" else "Дописать имя", icon = Glyphs.Plus, onClick = {
                val op = if (isPhone) DelaCrm.personAddOp(p.id, phones = listOf(t)) else DelaCrm.personAddOp(p.id, aliases = listOf(t))
                if (op != null) ctx.run(listOf(op), listOf(path), if (isPhone) "Номер дописан" else "Имя дописано")
                text = ""
            })
        }
        RowRule()
        PaperTextButton(if (picking) "Не сливать" else "Это тот же, что…", onClick = { picking = !picking })
        if (picking) {
            PaperField(value = query, onValueChange = { query = it }, placeholder = "найти карточку")
            val n = Dela.normName(query)
            val found = ctx.snap.livePeople().filter { it.id != p.id && n.length >= 2 && Dela.normName(it.name + " " + it.short + " " + it.aliases.joinToString(" ")).contains(n) }.take(6)
            for (o in found) {
                PaperTextButton(o.name + if (o.short.isNotBlank()) " (${o.short})" else "", onClick = { into = o })
            }
        }
    }
    into?.let { o ->
        PaperAlert(
            onDismiss = { into = null },
            title = "Слить «${p.name}» с «${o.name}»?",
            icon = Glyphs.Check,
            subtitle = "«${p.name}» уйдёт в архив; дела, сделки и хронология переедут к «${o.name}», имена и номера допишутся.",
            confirm = SheetAction("Слить", icon = Glyphs.Check) {
                ctx.run(listOf(DelaCrm.personMergeOp(p.id, o.id)), listOf(path, DelaCrm.TIES), "Слито с «${o.name}»")
                into = null
                picking = false
            },
        ) {}
    }
}

// ---------------------------------------------------------------- хронология

/**
 * Хронология: добавить запись (вид, дата — сегодня или выбранная, суть) и
 * убрать (строка не удаляется — сервер ставит `deleted_at`). Свежие сверху,
 * своя неотправленная — сразу, с ⏳.
 */
@Composable
private fun TimelineCard(ctx: DelaCrmContext, items: List<DelaCrm.Interaction>, target: TalkTarget, loaded: Boolean, showDeal: Boolean = false) {
    val c = MaterialTheme.colorScheme
    var adding by remember(target) { mutableStateOf(false) }
    var all by remember(target) { mutableStateOf(false) }
    PaperCard(
        label = "хронология · ${items.size}",
        trailing = { GlyphButton(if (adding) Glyphs.Close else Glyphs.Plus, if (adding) "не записывать" else "записать в хронологию", onClick = { adding = !adding }, size = 30.dp) },
    ) {
        if (adding) {
            InteractionForm(ctx, target, onDone = { adding = false })
            RowRule()
        }
        if (items.isEmpty()) PaperHint(if (loaded) "Пока пусто." else "Загружаю с сервера…")
        val shown = if (all) items else items.take(20)
        shown.forEachIndexed { i, it ->
            if (i > 0) RowRule()
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        DelaAsk.ddmm(it.day, ctx.today) + " · " + it.kindWord + if (it.local) " ⏳" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.primary,
                    )
                    Text(it.summary, style = MaterialTheme.typography.bodyMedium)
                    if (it.nextStep.isNotBlank()) Text("→ " + it.nextStep, style = MaterialTheme.typography.bodySmall)
                    val meta = listOfNotNull(
                        it.dealName.takeIf { n -> showDeal && n.isNotBlank() },
                        it.personIds.map { id -> ctx.personName(id) }.filter { n -> n.isNotBlank() }.takeIf { l -> l.isNotEmpty() }?.joinToString(", "),
                        it.durationMin?.takeIf { m -> m > 0 }?.let { m -> "$m мин" },
                        when (it.source) { "meeting" -> "из встречи"; "notion" -> "Notion"; "phone" -> "с телефона"; else -> null },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                if (!it.local) GlyphButton(Glyphs.Close, "убрать из хронологии", onClick = { ctx.ui.deleting = it to target.refresh }, size = 30.dp)
            }
        }
        if (items.size > 20 && !all) PaperTextButton("Ещё ${items.size - 20}", onClick = { all = true })
    }
}

/** Новая запись: вид чипами, когда, что было — набором или голосом (тот же движок, что у «Д»). */
@Composable
private fun InteractionForm(ctx: DelaCrmContext, target: TalkTarget, onDone: () -> Unit, defaultKind: String = "call") {
    var kind by remember(target) { mutableStateOf(defaultKind) }
    var day by remember(target) { mutableStateOf(ctx.today) }
    var text by remember(target) { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val todayDate = runCatching { LocalDate.parse(ctx.today) }.getOrNull() ?: LocalDate.now()
    ChipRow {
        for ((k, word) in DelaCrm.IKIND) PaperChip(word, selected = kind == k, onClick = { kind = k })
    }
    ChipRow {
        PaperChip("сегодня", selected = day == todayDate.toString(), onClick = { day = todayDate.toString() })
        PaperChip("вчера", selected = day == todayDate.minusDays(1).toString(), onClick = { day = todayDate.minusDays(1).toString() })
        PaperChip("позавчера", selected = day == todayDate.minusDays(2).toString(), onClick = { day = todayDate.minusDays(2).toString() })
    }
    var dayText by remember(day) { mutableStateOf(day) }
    PaperField(
        value = dayText,
        onValueChange = { v -> dayText = v; if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(v) && v <= ctx.today) day = v },
        label = "Когда ГГГГ-ММ-ДД · " + delaDate(day),
    )
    PaperField(
        value = text,
        onValueChange = { text = it },
        label = if (listening) "Слушаю — что было…" else "Что было: суть и договорённости",
        singleLine = false,
        maxLines = 6,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        AskMic(listening, { listening = it }, onText = { said -> text = (text.trim() + " " + said.trim()).trim() })
        Spacer(Modifier.weight(1f))
        PaperButton("Записать", icon = Glyphs.Check, primary = true, enabled = text.isNotBlank(), onClick = {
            val op = DelaCrm.interactionOp(kind, text, DelaCrm.atIso(day, ctx.today), target.projectId, target.dealId, target.personIds)
            ctx.run(listOf(op), target.refresh, "Записано в хронологию")
            text = ""
            onDone()
        })
    }
}

/** Листы CRM поверх вкладки: «Закрыть…» сделку, «Поговорили», убрать запись, «откуда он», «+ человек», убрать из клиента. */
@Composable
internal fun CrmSheets(ctx: DelaCrmContext) {
    val ui = ctx.ui
    ui.personFor?.let { id -> ctx.snap.people[id]?.let { PersonWhereSheet(ctx, it) } }
    ui.addTo?.let { AddPersonSheet(ctx, it) }
    ui.leaving?.let { (p, projectId) ->
        val name = ctx.snap.projects[projectId]?.name ?: "клиента"
        PaperSheet(
            onDismiss = { ui.leaving = null },
            title = "Убрать из «$name»?",
            icon = Glyphs.Delete,
            subtitle = p.name,
            footer = {
                Spacer(Modifier.weight(1f))
                PaperButton("Убрать", icon = Glyphs.Delete, primary = true, onClick = {
                    ui.leaving = null
                    val u = DelaCrm.leaveClient(ctx.snap, p, projectId)
                    if (u == null) Feedback.toast(ctx.app, "${p.label} связан с «$name» иначе — поправь в его карточке")
                    else ctx.runUndoable(u, listOf(DelaCrm.clientPath(projectId)))
                })
            },
        ) {
            PaperHint("Карточка человека останется: он уйдёт из организации клиента и из людей его сделок. Передумаешь — «Вернуть».")
        }
    }
    ui.closing?.let { d ->
        var outcome by remember(d.id) { mutableStateOf("won") }
        var reason by remember(d.id) { mutableStateOf("") }
        PaperSheet(
            onDismiss = { ui.closing = null },
            title = "Закрыть сделку",
            icon = Glyphs.Check,
            subtitle = d.name,
            footer = {
                Spacer(Modifier.weight(1f))
                PaperButton("Закрыть", icon = Glyphs.Check, primary = true, onClick = {
                    ui.closing = null
                    ctx.run(
                        listOf(DelaCrm.closeOp(d.id, outcome, if (outcome == "won") "" else reason)),
                        listOf(DelaCrm.dealPath(d.id), DelaCrm.PIPELINE, DelaCrm.clientPath(d.projectId)),
                        "Сделка закрыта: " + (DelaCrm.OUTCOME[outcome] ?: outcome),
                    )
                })
            },
        ) {
            ChipRow {
                for ((k, word) in DelaCrm.OUTCOME) PaperChip(word, selected = outcome == k, onClick = { outcome = k })
            }
            if (outcome != "won") {
                PaperField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = if (outcome == "lost") "Почему проиграли? (учит воронку, можно пусто)" else "Почему заморозили? (можно пусто)",
                    singleLine = false,
                    maxLines = 3,
                )
            }
        }
    }
    ui.talk?.let { t ->
        PaperSheet(onDismiss = { ui.talk = null }, title = "Поговорили", icon = Glyphs.Phone, subtitle = t.title) {
            InteractionForm(ctx, t, onDone = { ui.talk = null }, defaultKind = "note")
        }
    }
    ui.deleting?.let { (i, refresh) ->
        PaperSheet(
            onDismiss = { ui.deleting = null },
            title = "Убрать из хронологии?",
            icon = Glyphs.Delete,
            subtitle = DelaAsk.ddmm(i.day, ctx.today) + " · " + i.kindWord,
            footer = {
                Spacer(Modifier.weight(1f))
                PaperButton("Убрать", icon = Glyphs.Delete, primary = true, onClick = {
                    ui.deleting = null
                    ctx.run(listOf(DelaCrm.interactionDeleteOp(i.id)), refresh, "Запись убрана — в журнале она осталась")
                })
            },
        ) {
            PaperHint(i.summary)
            PaperHint("Строка не удаляется: сервер помечает её убранной, журнал помнит.")
        }
    }
}

// ---------------------------------------------------------------- откуда он, + человек

/**
 * «Откуда он» и должность — лист по тапу на плашку или «откуда…» (`personPop`
 * веба): компания — клиенты по имени проекта и другие организации, должность
 * — строкой; «Открыть карточку». Каждая правка — с «Вернуть».
 */
@Composable
private fun PersonWhereSheet(ctx: DelaCrmContext, p: Dela.Person) {
    val ui = ctx.ui
    var role by remember(p.id) { mutableStateOf(p.role) }
    var q by remember(p.id) { mutableStateOf("") }
    val choices = DelaCrm.orgChoices(ctx.snap)
    val now = DelaCrm.orgLabel(ctx.snap, p.orgId)
    // Виды, где человек виден: клиент, откуда он был и куда уходит, «Связи», его досье.
    fun refresh(orgId: String): List<String> = listOfNotNull(
        now?.clientId?.takeIf { it.isNotBlank() }?.let { DelaCrm.clientPath(it) },
        DelaCrm.orgLabel(ctx.snap, orgId)?.clientId?.takeIf { it.isNotBlank() }?.let { DelaCrm.clientPath(it) },
        DelaCrm.TIES, DelaCrm.dossierPath(p.id),
    )
    fun setOrg(orgId: String) {
        ui.personFor = null
        if (orgId == p.orgId) return
        val word = DelaCrm.orgLabel(ctx.snap, orgId)?.name?.let { "откуда — $it" } ?: "без компании"
        ctx.runUndoable(
            DelaCrm.Undoable(
                listOf(DelaCrm.personSetOp(p.id, JSONObject().put("org_id", Dela.nul(orgId)))),
                listOf(DelaCrm.personSetOp(p.id, JSONObject().put("org_id", Dela.nul(p.orgId)))),
                "${p.label}: $word",
            ),
            refresh(orgId),
        )
    }
    PaperSheet(
        onDismiss = { ui.personFor = null },
        title = p.name,
        icon = Glyphs.Phone,
        subtitle = listOf(now?.name ?: "откуда — не знаю", p.role).filter { it.isNotBlank() }.joinToString(" · "),
        footer = {
            PaperTextButton("Открыть карточку", icon = Glyphs.Forward, onClick = { ui.personFor = null; ctx.push(DelaPage.Person(p.id)) })
            Spacer(Modifier.weight(1f))
            PaperButton("Должность", icon = Glyphs.Check, primary = true, enabled = role.trim() != p.role, onClick = {
                ui.personFor = null
                val r = role.trim()
                ctx.runUndoable(
                    DelaCrm.Undoable(
                        listOf(DelaCrm.personSetOp(p.id, JSONObject().put("role", Dela.nul(r)))),
                        listOf(DelaCrm.personSetOp(p.id, JSONObject().put("role", Dela.nul(p.role)))),
                        "${p.label}: " + r.ifBlank { "без должности" },
                    ),
                    refresh(p.orgId),
                )
            })
        },
    ) {
        PaperField(value = role, onValueChange = { role = it }, label = "Должность: CFO, партнёр, юрист…")
        PaperHint("Откуда он")
        if (choices.size > 8) PaperField(value = q, onValueChange = { q = it }, label = "Найти компанию")
        ChoiceLine("ни откуда", "", p.orgId.isBlank()) { setOrg("") }
        val n = Dela.norm(q)
        for (o in choices) {
            if (n != null && Dela.norm(o.name)?.contains(n) != true) continue
            ChoiceLine(o.name, if (o.client) "клиент" else "компания", o.orgId == p.orgId) { setOrg(o.orgId) }
        }
    }
}

/**
 * «+ человек» (`addPersonPop` веба): выбрать из справочника или завести нового.
 * К клиенту — в его организацию (у старого клиента без неё — заводим её); в
 * сделку — в её людей или команду; новый человек в люди клиента — сразу с
 * организацией клиента. id нового даёт телефон: плашка видна до ответа.
 */
@Composable
private fun AddPersonSheet(ctx: DelaCrmContext, t: AddTarget) {
    var q by remember(t) { mutableStateOf("") }
    val n = Dela.norm(q)
    val list = ctx.snap.livePeople().filter { p ->
        p.id !in t.exclude && (n == null || Dela.norm(listOf(p.name, p.short, p.aliases.joinToString(" "), p.role).joinToString(" "))?.contains(n) == true)
    }.take(40)
    val client = ctx.snap.projects[t.clientId]
    fun add(p: Dela.Person?, name: String) {
        ctx.ui.addTo = null
        if (t.dealId.isBlank()) {
            val u = DelaCrm.joinClient(ctx.snap, t.clientId, p, name)
            if (u == null) Feedback.toast(ctx.app, "${p?.label ?: name} уже здесь") else ctx.runUndoable(u, listOf(DelaCrm.clientPath(t.clientId)))
            return
        }
        val refresh = listOf(DelaCrm.dealPath(t.dealId), DelaCrm.clientPath(t.clientId.ifBlank { ctx.snap.deals[t.dealId]?.projectId.orEmpty() }))
        val back = listOf(DelaCrm.dealPeopleOp(t.dealId, t.field, t.ids))
        val where = if (t.field == "team_ids") "в команде" else "в людях сделки"
        if (p != null) {
            ctx.runUndoable(DelaCrm.Undoable(listOf(DelaCrm.dealPeopleOp(t.dealId, t.field, t.ids + p.id)), back, "${p.label} — $where"), refresh)
        } else {
            val id = Dela.newId()
            val ops = listOf(DelaCrm.personCreateOp(id, name, client?.orgId.orEmpty()), DelaCrm.dealPeopleOp(t.dealId, t.field, t.ids + id))
            ctx.runUndoable(DelaCrm.Undoable(ops, back, "Завёл: ${name.trim()} — $where"), refresh)
        }
    }
    PaperSheet(onDismiss = { ctx.ui.addTo = null }, title = "Кто ещё", icon = Glyphs.Plus, subtitle = t.title) {
        PaperField(value = q, onValueChange = { q = it }, label = "Имя — найти или завести нового")
        if (q.isNotBlank()) {
            ChoiceLine("Новый: ${q.trim()}", if (client != null && (t.dealId.isBlank() || t.field == "person_ids")) "завести — из «${client.name}»" else "завести", false) {
                add(null, q.trim())
            }
        }
        for (p in list) {
            val org = DelaCrm.orgLabel(ctx.snap, p.orgId)?.name.orEmpty()
            ChoiceLine(p.name + if (p.short.isNotBlank() && p.short != p.name) " · ${p.short}" else "", listOf(p.role, org).filter { it.isNotBlank() }.joinToString(" · "), false) {
                add(p, "")
            }
        }
        if (list.isEmpty() && q.isBlank()) PaperHint("В справочнике никого — набери имя, и он появится.")
    }
}

/** Строка выбора в листе: название, подпись, галочка у выбранного. */
@Composable
private fun ChoiceLine(text: String, more: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (selected) c.primary else c.onSurface)
            if (more.isNotBlank()) Text(more, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        if (selected) Icon(Glyphs.Check, contentDescription = "выбрано", tint = c.primary, modifier = Modifier.size(18.dp))
    }
}
