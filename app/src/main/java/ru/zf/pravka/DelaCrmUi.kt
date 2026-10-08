package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.onFocusChanged
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
    /** «Счёт выставлен…» и «записать» у счёта: когда, кому и как — и какие виды обновить. */
    var invoicing by mutableStateOf<Pair<Dela.Payment, List<String>>?>(null)
    /** Долгое нажатие на проект Воронки — меню «Стадия» (перетаскивания на телефоне нет). */
    var stageFor by mutableStateOf<DelaCrm.Deal?>(null)
    /** Плашка человека в команде проекта: в листе «откуда он» — ещё «Ведёт проект». */
    var leadDeal by mutableStateOf("")
    /** Запись хронологии, раскрытая целиком, и хронология, показанная вся (`S.tlOpen`, `S.tlAll` веба). */
    var tlOpen by mutableStateOf("")
    var tlAll by mutableStateOf("")
    /** Фильтр «Поддержание отношений» у «Клиентов». */
    var clientRelations by mutableStateOf(false)
    /** Убрать запись хронологии — с подтверждением: строка останется в журнале. */
    var deleting by mutableStateOf<Pair<DelaCrm.Interaction, List<String>>?>(null)
    /** Тап по плашке человека — «откуда он» и должность (`personPop` веба). */
    var personFor by mutableStateOf<String?>(null)
    /** «+ человек»: к клиенту, в люди клиента или в команду сделки. */
    var addTo by mutableStateOf<AddTarget?>(null)
    /** Крестик у человека клиента — с подтверждением: карточка человека остаётся. */
    var leaving by mutableStateOf<Pair<Dela.Person, String>?>(null)
    /** Поиск в «Людях». */
    var peopleQuery by mutableStateOf("")
}


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
    /** Свои — кого показать до набора: команда ЗФ или люди этого клиента (`personSearchPop` веба). */
    val base: List<String> = emptyList(),
    /** В команду клиента: его живые проекты — один — в него, несколько — выбрать, в какой. */
    val teamDeals: List<String> = emptyList(),
    /** У проекта нет ведущего — первый в команде станет ведущим. */
    val leadEmpty: Boolean = false,
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
    /** Пилюли карточки: пролистать список к разделу по ключу (`sec:about`, `sec:tasks`…). */
    val scrollTo: (String) -> Unit = {},
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
    /** Пометка вместо должности — «ведёт» у ведущего проекта. */
    mark: (Dela.Person) -> String = { "" },
    onTap: ((Dela.Person) -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    PaperHint(title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (people.isEmpty()) Text(empty, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        for (p in people) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.outlineVariant, RoundedCornerShape(50))
                    .clickable { if (onTap != null) onTap(p) else { ctx.ui.leadDeal = ""; ctx.ui.personFor = p.id } }.padding(start = 4.dp, end = if (onRemove != null) 2.dp else 12.dp, top = 3.dp, bottom = 3.dp),
            ) {
                Avatar(p)
                Spacer(Modifier.width(6.dp))
                Text(p.label + if (p.local) " ⏳" else "", style = MaterialTheme.typography.bodySmall, color = c.onSurface, maxLines = 1)
                val m = mark(p)
                if (m.isNotBlank()) {
                    Text(" · $m", style = MaterialTheme.typography.bodySmall, color = c.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                } else if (p.role.isNotBlank()) {
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DealRow(ctx: DelaCrmContext, d: DelaCrm.Deal, money: Boolean) {
    val c = MaterialTheme.colorScheme
    // Сделка на экране (08.10.2026, docs/dela-phone-8.md): Claude видит её в `deal_ids` и `visible_deal_ids`.
    // Долгое нажатие — меню «Стадия» (п. 9 задания 9: на телефоне вместо перетаскивания по доске).
    Column(Modifier.fillMaxWidth().seen("d:" + d.id).clip(RoundedCornerShape(10.dp))
        .combinedClickable(onClick = { ctx.push(DelaPage.Deal(d.id)) }, onLongClick = { if (!d.closed) ctx.ui.stageFor = d })
        .padding(vertical = 6.dp)) {
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
                    "→ ${next.title}" + if (next.dueDate.isNotBlank()) " · " + DelaAsk.ddmm(next.dueDate, ctx.today) else "",
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
    // Порядок — сервера: свежие (последнее дело заведено или закрыто, контакт) сверху (09.10.2026).
    val rows = v?.find(ctx.ui.clientQuery, ctx.ui.clientArchive, ctx.ui.clientRelations).orEmpty()
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
                PaperChip("Поддержание отношений", selected = ctx.ui.clientRelations, onClick = { ctx.ui.clientRelations = !ctx.ui.clientRelations })
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
    Column(Modifier.fillMaxWidth().seen("d:" + d.id).clip(RoundedCornerShape(10.dp)).clickable { ctx.push(DelaPage.Deal(d.id)) }.padding(start = 30.dp, top = 4.dp, bottom = 4.dp)) {
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
                "→ ${next.title}" + if (next.dueDate.isNotBlank()) " · " + DelaAsk.ddmm(next.dueDate, ctx.today) else "",
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
            val stages = listOfNotNull(
                "поддержание отношений".takeIf { cl.relations },
                if (cl.stages.isNotEmpty()) cl.stages.joinToString(", ") { DelaCrm.STAGE[it] ?: it } else if (cl.relations) null else if (cl.allDeals > 0) "проекты в архиве" else "без проектов",
            ).joinToString(" · ")
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
                Text("→ ${next.title}" + if (next.dueDate.isNotBlank()) " · " + DelaAsk.ddmm(next.dueDate, ctx.today) else "",
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
    }
}

// ---------------------------------------------------------------- клиент и проект: карточки (09.10.2026)

/*
 * Карточки клиента и проекта — одно устройство, одно под другим (09.10.2026,
 * docs/dela-phone-9.md, п. 4–8; веб — `renderClientCard`, `renderDeal`):
 * «О клиенте / О проекте» (описание полем и статус), люди клиента и команда,
 * проекты плитками (у клиента), дела, хронология под делами (не вкладкой),
 * деньги, файлы. Пилюли в шапке — разделы карточки: нажатие листает к разделу.
 * Номеров дел, текстов Notion и слова «мяч» нет; своей формы хронологии нет —
 * пишет Claude из строки внизу.
 */

/** Пилюли разделов карточки — листают к разделу (`sec-*` веба). */
@Composable
private fun CardPills(ctx: DelaCrmContext, sections: List<Pair<String, String>>) {
    ChipRow {
        for ((key, title) in sections) PaperChip(title, selected = false, onClick = { ctx.scrollTo(key) })
    }
}

/**
 * Описание полем (`aboutBox` веба): растёт по тексту и сохраняется, когда из
 * него вышел (или по «Сохранить», если поле не отпускают).
 */
@Composable
private fun AboutField(value: String, placeholder: String, save: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    PaperField(
        value = text,
        onValueChange = { text = it },
        placeholder = placeholder,
        singleLine = false,
        maxLines = 12,
        modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
            if (focused && !f.isFocused && text.trim() != value.trim()) save(text.trim())
            focused = f.isFocused
        },
    )
    if (text.trim() != value.trim()) {
        Row {
            Spacer(Modifier.weight(1f))
            PaperTextButton("Сохранить", icon = Glyphs.Check, onClick = { save(text.trim()) })
        }
    }
}

/** Заголовок раздела карточки — как `sec-h` веба. */
@Composable
private fun SecHead(title: String, n: Int = 0) {
    Text(
        title + if (n > 0) " · $n" else "",
        style = MaterialTheme.typography.labelLarge,
        color = ru.zf.pravka.ui.LocalMode.current.label,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

/**
 * Клиент на странице проекта — верх карточки: шапка с пилюлями, «О клиенте»
 * (описание — `note`, статус: проекты по стадиям и «Поддержание отношений»),
 * люди клиента и команда (ведущие и команды его живых проектов) одна под
 * другой, проекты плитками. Дела рисует вкладка, низ — [crmClientBottom].
 */
internal fun LazyListScope.crmClientTop(ctx: DelaCrmContext, projectId: String) {
    val path = DelaCrm.clientPath(projectId)
    val v = ctx.data(path)?.let(DelaCrm::client)
    val project = ctx.snap.projects[projectId] ?: v?.project
    val money = ctx.money && v?.money == true
    val copyDeals = ctx.snap.allDealsOf(projectId)
    val live = copyDeals.filter { !it.closed }
    item(key = "crm:client") {
        PaperCard {
            Text(project?.name ?: "клиент не найден", style = MaterialTheme.typography.titleMedium)
            if (project != null && project.aliases.isNotEmpty()) PaperHint("ещё зовут: " + project.aliases.joinToString(", "))
            Freshness(ctx, path)
            CardPills(ctx, listOfNotNull("sec:about" to "О клиенте", "sec:deals" to "Проекты", "sec:tasks" to "Дела", "sec:timeline" to "Хронология",
                if (money) "sec:money" to "Деньги" else null, "sec:files" to "Файлы"))
        }
    }
    item(key = "sec:about") {
        PaperCard(label = "О клиенте") {
            if (project != null) {
                AboutField(project.note, "Кто они, чем занимаются, как пришли, о чём договорённость. Можно сказать словами Claude внизу.") { x ->
                    ctx.runUndoable(
                        DelaCrm.Undoable(
                            listOf(DelaCrm.projectSetOp(projectId, JSONObject().put("note", Dela.nul(x)))),
                            listOf(DelaCrm.projectSetOp(projectId, JSONObject().put("note", Dela.nul(project.note)))),
                            "Описание сохранено",
                        ),
                        listOf(path),
                    )
                }
                // Статус: проекты по стадиям и «Поддержание отношений» — уже не лид, но и не сделка.
                val stages = Dela.OPEN_STAGES.filter { st -> live.any { it.stage == st } }.map { DelaCrm.STAGE[it] ?: it }
                PaperHint(if (stages.isNotEmpty()) "Статус — проекты: " + stages.joinToString(", ") else if (project.status != DelaCrm.RELATIONS) "Статус — проектов в работе нет" else "Статус")
                ChipRow {
                    val on = project.status == DelaCrm.RELATIONS
                    PaperChip("Поддержание отношений", selected = on, onClick = {
                        ctx.runUndoable(
                            DelaCrm.Undoable(
                                listOf(DelaCrm.projectSetOp(projectId, JSONObject().put("status", if (on) JSONObject.NULL else DelaCrm.RELATIONS))),
                                listOf(DelaCrm.projectSetOp(projectId, JSONObject().put("status", Dela.nul(project.status)))),
                                if (on) "Статус — по проектам" else "Поддержание отношений",
                            ),
                            listOf(path, DelaCrm.CLIENTS),
                        )
                    })
                }
            }
        }
    }
    item(key = "crm:client:people") {
        PaperCard {
            // Люди клиента — из своей копии (крестик и «+ человек» видны сразу); пока копия не знает — что сказал сервер.
            val people = DelaCrm.clientPeople(ctx.snap, projectId).ifEmpty {
                v?.people.orEmpty().map { r -> ctx.snap.people[r.id] ?: Dela.Person(r.id, r.name, short = r.short, role = r.role) }
            }
            PeopleChips(
                ctx, "Люди клиента", people,
                onRemove = { p -> ctx.ui.leaving = p to projectId },
                onAdd = {
                    ctx.ui.addTo = AddTarget("Кто из «${project?.name ?: "клиента"}»", people.map { it.id }.toSet(), clientId = projectId, base = people.map { it.id })
                },
                empty = "пока никого",
            )
            val teamIds = DelaCrm.clientTeam(copyDeals)
            PeopleChips(
                ctx, "Команда", teamIds.mapNotNull { ctx.snap.people[it] },
                onRemove = null,
                onAdd = if (live.isNotEmpty()) ({
                    ctx.ui.addTo = AddTarget("Кто из команды", teamIds.toSet(), field = "team_ids", base = DelaCrm.teamPeople(ctx.snap).map { it.id },
                        teamDeals = live.map { it.id })
                }) else null,
                empty = if (live.isNotEmpty()) "никого" else "появится с проектом",
                mark = { p -> if (live.any { it.leadPersonId == p.id }) "ведёт" else "" },
            )
            if (live.size > 1) PaperHint("Команда — по всем живым проектам; убрать — в карточке проекта.")
        }
    }
    // Проекты (сделки) плитками: из ответа вида (с посчитанным), а пока его нет — из копии синка.
    val deals = (v?.deals ?: copyDeals.map { fromCopy(it, project?.name.orEmpty()) })
        .map { DelaCrm.overlayDeal(it, ctx.ops, ctx.today) }
        .sortedWith(compareBy<DelaCrm.Deal>({ it.closed }, { Dela.STAGES.indexOf(it.stage) }, { it.name.lowercase() }))
    item(key = "sec:deals") {
        PaperCard(label = "Проекты · ${deals.count { !it.closed }}") {
            if (deals.isEmpty()) PaperHint("Проектов нет. Новый — скажи Claude внизу: «новый проект — финмодель».")
            deals.forEachIndexed { i, d -> if (i > 0) RowRule(); DealRow(ctx, d, money) }
        }
    }
}

/** Низ карточки клиента: хронология (записи, счета и оплаты всех проектов), деньги, файлы. */
internal fun LazyListScope.crmClientBottom(ctx: DelaCrmContext, projectId: String) {
    val path = DelaCrm.clientPath(projectId)
    val v = ctx.data(path)?.let(DelaCrm::client)
    val project = ctx.snap.projects[projectId] ?: v?.project
    val money = ctx.money && v?.money == true
    val pays = DelaCrm.overlayPayments(v?.payments.orEmpty(), ctx.ops)
    item(key = "sec:timeline") {
        val items = DelaCrm.overlayTimeline(v?.timeline.orEmpty(), ctx.ops, ctx.snap) { it.projectId == projectId }
        TimelineBlock(ctx, "c:$projectId", items, if (money) pays else emptyList(), loaded = v != null, refresh = listOf(path), showDeal = true)
    }
    if (money) item(key = "sec:money") { ClientMoney(ctx, pays, listOf(path)) }
    if (project != null) item(key = "sec:files") {
        FilesCard(ctx, project.folderUrl, project.files) { set, msg -> ctx.run(listOf(DelaCrm.projectSetOp(projectId, set)), listOf(path), msg) }
    }
}

/** Сделка из копии синка — пока вид не пришёл: без посчитанного сервером, но с именем, стадией и итогом. */
private fun fromCopy(d: Dela.Deal, projectName: String): DelaCrm.Deal = DelaCrm.Deal(
    id = d.id, projectId = d.projectId, projectName = projectName, name = d.name, stage = d.stage, outcome = d.outcome,
    lostReason = d.lostReason, closedOn = d.closedOn, dealType = d.dealType, leadPersonId = d.leadPersonId,
    teamIds = d.teamIds, personIds = d.personIds, feeKop = d.feeKop, local = d.local,
    sourcePersonId = d.sourcePersonId, probability = d.probability, expectedOn = d.expectedOn, deadline = d.deadline,
)

/**
 * Проект клиента (сделка) страницей — как клиент (09.10.2026, `renderDeal` веба):
 * «О проекте» (описание полем и короткие факты), статус («Закрыть…» с итогом,
 * «Вернуть в работу»), люди клиента и команда (ведущий первым, «ведёт»), дела,
 * хронология, деньги, файлы; «Как я вижу» и «Идеи» — свёрнуто и только если в
 * них что-то есть. Тексты Notion («Следующий шаг», «Мяч», «Лог») не показываются.
 */
internal fun LazyListScope.crmDealPage(ctx: DelaCrmContext, dealId: String) {
    val path = DelaCrm.dealPath(dealId)
    val v = ctx.data(path)?.let(DelaCrm::dealView)
    val base = v?.deal ?: ctx.snap.deals[dealId]?.let { fromCopy(it, ctx.snap.projects[it.projectId]?.name.orEmpty()) }
    if (base == null) {
        item(key = "crm:deal:none") {
            PaperCard(label = "проект") {
                Freshness(ctx, path)
                PaperHint("Проект не виден — может быть, его закрыли от тебя или он ещё не пришёл с сервера.")
            }
        }
        return
    }
    // Люди, команда, описание, файлы — из своей копии, если она знает сделку: там и только что поправленное.
    val copy = ctx.snap.deals[dealId]
    val d = DelaCrm.overlayDeal(base, ctx.ops, ctx.today).let { o ->
        if (copy != null) o.copy(personIds = copy.personIds, teamIds = copy.teamIds, leadPersonId = copy.leadPersonId) else o
    }
    val refreshDeal = listOf(path, DelaCrm.clientPath(d.projectId))
    // Деньги — кому открыты; сервер сам пометил ответ (`money`).
    val money = ctx.money && (v?.money ?: true)
    item(key = "crm:deal:head") {
        PaperCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Монета — портфель, не воронка (п. 4 задания 9).
                Icon(Glyphs.Layers, contentDescription = "проект", tint = ru.zf.pravka.ui.LocalMode.current.label, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(d.name + if (d.local) " ⏳" else "", style = MaterialTheme.typography.titleMedium)
            }
            PaperRow(
                title = d.projectName.ifBlank { ctx.snap.projects[d.projectId]?.name ?: "клиент" },
                hint = "клиент · " + d.stageWord,
                icon = Glyphs.Groups,
                onClick = { ctx.push(DelaPage.Project(d.projectId)) },
            )
            Freshness(ctx, path)
            CardPills(ctx, listOfNotNull("sec:about" to "О проекте", "sec:tasks" to "Дела", "sec:timeline" to "Хронология",
                if (money) "sec:money" to "Деньги" else null, "sec:files" to "Файлы"))
        }
    }
    item(key = "sec:about") {
        PaperCard(label = "О проекте") {
            AboutField(copy?.description.orEmpty(), "Что за проект: суть, цель, о чём договорились. Можно сказать словами Claude внизу.") { x ->
                ctx.runUndoable(
                    DelaCrm.Undoable(
                        listOf(DelaCrm.dealSetOp(d.id, JSONObject().put("description", Dela.nul(x)))),
                        listOf(DelaCrm.dealSetOp(d.id, JSONObject().put("description", Dela.nul(copy?.description.orEmpty())))),
                        "Описание сохранено",
                    ),
                    refreshDeal,
                )
            }
            val facts = listOf(
                "Тип" to d.dealType,
                "Вероятность" to (d.probability?.let { "$it %" } ?: d.pEff?.let { "$it % (по стадии)" }.orEmpty()),
                "Решение ждём" to d.expectedOn.takeIf { it.isNotBlank() }?.let { DelaAsk.ddmm(it, ctx.today) }.orEmpty(),
                "Дедлайн" to d.deadline.takeIf { it.isNotBlank() }?.let { DelaAsk.ddmm(it, ctx.today) }.orEmpty(),
                "Привёл" to ctx.personName(d.sourcePersonId),
            ).filter { it.second.isNotBlank() }
            if (facts.isNotEmpty()) PaperHint(facts.joinToString(" · ") { "${it.first}: ${it.second}" }, ru.zf.pravka.ui.Ink.Text)
            else PaperHint("Тип, вероятность, сроки — скажи Claude внизу: «тип — M&A, решение ждём к 20-му».")
            // «Как я вижу» и «Идеи» — старые заметки владельца: свёрнуто и только если в них что-то есть.
            val notes = listOf("Как я вижу (только мне)" to d.myView, "Идеи" to d.ideas).filter { it.second.isNotBlank() }
            if (notes.isNotEmpty()) {
                var shown by remember(d.id) { mutableStateOf(false) }
                SummaryLine(title = notes.joinToString(" и ") { it.first.removeSuffix(" (только мне)").lowercase() }, summary = "", expanded = shown, onToggle = { shown = !shown }) {
                    for ((k, x) in notes) PaperHint("$k: $x", ru.zf.pravka.ui.Ink.Text)
                }
            }
        }
    }
    item(key = "crm:deal:stage") {
        PaperCard(label = "Статус") {
            if (d.closed) {
                val out = listOf(d.stageWord, d.closedOn.takeIf { it.isNotBlank() }?.let { DelaAsk.ddmm(it, ctx.today) }.orEmpty(), d.lostReason)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                Text(out, style = MaterialTheme.typography.bodyMedium, color = if (d.outcome == "won") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                PaperButton("Вернуть в работу", icon = Glyphs.Undo, onClick = {
                    ctx.run(listOf(DelaCrm.reopenOp(d.id)), listOf(path, DelaCrm.PIPELINE, DelaCrm.clientPath(d.projectId)), "Проект снова в работе")
                })
            } else {
                ChipRow {
                    for (st in Dela.OPEN_STAGES) {
                        PaperChip(DelaCrm.STAGE[st] ?: st, selected = d.stage == st, onClick = {
                            if (d.stage != st) ctx.run(listOf(DelaCrm.stageOp(d.id, st)), listOf(path, DelaCrm.PIPELINE, DelaCrm.clientPath(d.projectId)))
                        })
                    }
                    PaperChip("Закрыть…", selected = false, onClick = { ctx.ui.closing = d })
                }
            }
        }
    }
    // Люди клиента и команда — одна под другой; ведущий — первым, с пометкой «ведёт».
    item(key = "crm:deal:people") {
        PaperCard {
            val client = ctx.snap.projects[d.projectId]
            PeopleChips(
                ctx, "Люди клиента", d.personIds.mapNotNull { ctx.snap.people[it] },
                onRemove = { p ->
                    ctx.runUndoable(
                        DelaCrm.Undoable(listOf(DelaCrm.dealPeopleOp(d.id, "person_ids", d.personIds - p.id)), listOf(DelaCrm.dealPeopleOp(d.id, "person_ids", d.personIds)), "${p.label} больше не от клиента"),
                        refreshDeal,
                    )
                },
                onAdd = {
                    ctx.ui.addTo = AddTarget("Кто от клиента", d.personIds.toSet(), clientId = d.projectId, dealId = d.id, field = "person_ids", ids = d.personIds,
                        base = if (client != null) DelaCrm.clientPeople(ctx.snap, client.id).map { it.id } else emptyList())
                },
            )
            val team = (listOf(d.leadPersonId) + d.teamIds).filter { it.isNotBlank() }.distinct()
            PeopleChips(
                ctx, "Команда", team.mapNotNull { ctx.snap.people[it] },
                onRemove = { p ->
                    val (go, back) = if (p.id == d.leadPersonId) {
                        DelaCrm.dealSetOp(d.id, JSONObject().put("lead_person_id", JSONObject.NULL)) to DelaCrm.dealSetOp(d.id, JSONObject().put("lead_person_id", d.leadPersonId))
                    } else {
                        DelaCrm.dealPeopleOp(d.id, "team_ids", d.teamIds - p.id) to DelaCrm.dealPeopleOp(d.id, "team_ids", d.teamIds)
                    }
                    ctx.runUndoable(DelaCrm.Undoable(listOf(go), listOf(back), "${p.label} больше не в команде"), refreshDeal)
                },
                onAdd = {
                    ctx.ui.addTo = AddTarget("Кто из команды", team.toSet(), dealId = d.id, field = "team_ids", ids = d.teamIds,
                        base = DelaCrm.teamPeople(ctx.snap).map { it.id }, leadEmpty = d.leadPersonId.isBlank())
                },
                empty = "никого — первый станет ведущим",
                mark = { p -> if (p.id == d.leadPersonId) "ведёт" else "" },
                onTap = { p -> ctx.ui.leadDeal = if (p.id != d.leadPersonId) d.id else ""; ctx.ui.personFor = p.id },
            )
        }
    }
    // Дела проекта: следующий шаг — его открытое дело (правило 12 сервера), из своей копии.
    val open = ctx.snap.tasks.values.filter { it.dealId == dealId && it.open }.sortedWith(Dela.ORDER)
        .ifEmpty { v?.open.orEmpty() }
    item(key = "sec:tasks") {
        PaperCard(label = "Дела · ${open.size}") {
            if (open.isNotEmpty()) TaskRows(open, ctx.actions)
            else if (!d.closed) PaperHint("Нет следующего дела — проект без шага застынет. Скажи его Claude внизу.", MaterialTheme.colorScheme.error)
            val done = v?.done.orEmpty()
            if (done.isNotEmpty()) {
                var shown by remember { mutableStateOf(false) }
                SummaryLine(title = "Сделано по проекту", summary = done.size.toString(), expanded = shown, onToggle = { shown = !shown }) {
                    for (t in done) PaperHint(t.title + if (t.completedAt.isNotBlank()) " · " + DelaAsk.ddmm(t.completedAt, ctx.today) else "")
                }
            }
        }
    }
    val pays = DelaCrm.overlayPayments(v?.payments ?: ctx.snap.paymentsOf(dealId), ctx.ops)
    item(key = "sec:timeline") {
        val items = DelaCrm.overlayTimeline(v?.timeline.orEmpty(), ctx.ops, ctx.snap) { it.dealId == dealId }
        TimelineBlock(ctx, "d:$dealId", items, if (money) pays else emptyList(), loaded = v != null, refresh = refreshDeal)
    }
    if (money) item(key = "sec:money") { DealMoney(ctx, d, pays, listOf(path)) }
    if (copy != null) item(key = "sec:files") {
        FilesCard(ctx, copy.folderUrl, copy.files) { set, msg -> ctx.run(listOf(DelaCrm.dealSetOp(d.id, set)), refreshDeal, msg) }
    }
    val minutes = DelaCrm.hours(v?.minutesAll)
    val history = v?.history.orEmpty()
    if (minutes.isNotBlank() || history.isNotEmpty()) {
        item(key = "crm:deal:more") {
            var shown by remember { mutableStateOf(false) }
            PaperCard {
                if (minutes.isNotBlank()) PaperHint("Время из Засечки по делам проекта: $minutes")
                if (history.isNotEmpty()) {
                    SummaryLine(title = "Журнал проекта", summary = history.size.toString(), expanded = shown, onToggle = { shown = !shown }) {
                        for (h in history.takeLast(30).reversed()) PaperHint(h)
                    }
                }
            }
        }
    }
}

/**
 * Деньги проекта — кому открыты (`renderDeal` веба): гонорар, получено, счета,
 * план, осталось; оплаты строками — «Счёт выставлен» спрашивает, когда, кому и
 * как; «Оплачено». План оплат заводится в вебе или словами Claude.
 */
@Composable
private fun DealMoney(ctx: DelaCrmContext, d: DelaCrm.Deal, pays: List<Dela.Payment>, refresh: List<String>) {
    val live = pays.filter { it.live }
    val paid = live.filter { it.paidOn.isNotBlank() }.sumOf { it.amountKop }
    val inv = live.filter { it.paidOn.isBlank() && it.invoicedOn.isNotBlank() }.sumOf { it.amountKop }
    val plan = live.filter { it.paidOn.isBlank() && it.invoicedOn.isBlank() }.sumOf { it.amountKop }
    val fee = d.feeKop ?: 0L
    val sum = listOfNotNull(
        DelaCrm.rub(fee).takeIf { it.isNotBlank() }?.let { "гонорар $it" },
        DelaCrm.rub(paid).takeIf { it.isNotBlank() }?.let { "получено $it" },
        DelaCrm.rub(inv).takeIf { it.isNotBlank() }?.let { "счета $it" },
        DelaCrm.rub(plan).takeIf { it.isNotBlank() }?.let { "план $it" },
        if (fee > 0 && d.stage in setOf("mandate", "active", "closing")) DelaCrm.rub(maxOf(fee - paid, 0L)).takeIf { it.isNotBlank() }?.let { "осталось $it" } else null,
    ).joinToString(" · ").ifBlank { "оплат пока нет" }
    PaperCard(label = "Деньги") {
        PaperHint(sum, ru.zf.pravka.ui.Ink.Text)
        PayRows(ctx, pays, refresh)
        if (pays.isEmpty()) PaperHint("План оплат (аванс, этапы, ретейнер) — в вебе или словами Claude внизу.")
    }
}

/** Деньги клиента: оплаты всех его проектов — что пришло, что выставлено, чего ждём. */
@Composable
private fun ClientMoney(ctx: DelaCrmContext, pays: List<Dela.Payment>, refresh: List<String>) {
    val live = pays.filter { it.live }
    fun sum(f: (Dela.Payment) -> Boolean) = live.filter(f).sumOf { it.amountKop }
    PaperCard(label = "Деньги") {
        if (live.isEmpty()) PaperHint("Оплат пока нет — их заводят в карточке проекта.")
        else {
            PaperHint(listOfNotNull(
                DelaCrm.rub(sum { it.paidOn.isNotBlank() }).takeIf { it.isNotBlank() }?.let { "получено $it" },
                DelaCrm.rub(sum { it.paidOn.isBlank() && it.invoicedOn.isNotBlank() }).takeIf { it.isNotBlank() }?.let { "счета $it" },
                DelaCrm.rub(sum { it.paidOn.isBlank() && it.invoicedOn.isBlank() }).takeIf { it.isNotBlank() }?.let { "план $it" },
            ).joinToString(" · "), ru.zf.pravka.ui.Ink.Text)
            PayRows(ctx, live, refresh, withDeal = true)
        }
    }
}

/** Оплаты строками: сумма, вид, проект; статус; «Счёт выставлен…» и «Оплачено» у ждущей. */
@Composable
private fun PayRows(ctx: DelaCrmContext, pays: List<Dela.Payment>, refresh: List<String>, withDeal: Boolean = false) {
    val c = MaterialTheme.colorScheme
    pays.forEachIndexed { i, p ->
        if (i > 0) RowRule()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(DelaCrm.rub(p.amountKop), DelaCrm.PAY_KIND[p.kind] ?: p.kind, p.title.takeIf { it.isNotBlank() },
                        ctx.snap.deals[p.dealId]?.name?.takeIf { withDeal }).joinToString(" · ") + if (p.local) " ⏳" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val late = p.paidOn.isBlank() && p.invoicedOn.isNotBlank() && (p.dueOn.ifBlank { p.invoicedOn }) < ctx.today
                val st = when {
                    !p.live -> "отменено"
                    p.paidOn.isNotBlank() -> "получено " + DelaAsk.ddmm(p.paidOn, ctx.today)
                    p.invoicedOn.isNotBlank() -> "счёт " + DelaAsk.ddmm(p.invoicedOn, ctx.today) + (if (p.dueOn.isNotBlank()) ", срок " + DelaAsk.ddmm(p.dueOn, ctx.today) else "") +
                        listOf(p.sentTo.takeIf { it.isNotBlank() }?.let { "кому: $it" }, p.sentVia.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ").let { if (it.isNotBlank()) " · $it" else "" }
                    p.dueOn.isNotBlank() -> "ждём " + DelaAsk.ddmm(p.dueOn, ctx.today)
                    else -> "без даты"
                }
                Text(st, style = MaterialTheme.typography.bodySmall, color = if (late) c.error else if (p.paidOn.isNotBlank()) c.primary else c.onSurfaceVariant)
            }
            if (p.live && p.paidOn.isBlank()) {
                Column(horizontalAlignment = Alignment.End) {
                    if (p.invoicedOn.isBlank()) PaperTextButton("Счёт выставлен…", onClick = { ctx.ui.invoicing = p to refresh })
                    PaperTextButton("Оплачено", icon = Glyphs.Check, color = c.primary, onClick = { ctx.run(listOf(DelaCrm.paidOp(p.id, ctx.today)), refresh, "Оплата получена") })
                }
            }
        }
    }
}

/**
 * Хронология под делами (`timelineBlock` веба): записи, счета и оплаты вперемешку,
 * свежие сверху, первые 10 и «Показать всё». У записи встречи — тема строкой,
 * итог в три строки (касание — целиком), договорённости списком по две, «встреча →».
 * Своей формы нет — пишет Claude: «созвонились с Иваном, ждут модель к пятнице».
 */
@Composable
private fun TimelineBlock(
    ctx: DelaCrmContext,
    key: String,
    items: List<DelaCrm.Interaction>,
    pays: List<Dela.Payment>,
    loaded: Boolean,
    refresh: List<String>,
    showDeal: Boolean = false,
) {
    val rows = DelaCrm.timelineRows(items, pays)
    val all = ctx.ui.tlAll == key
    PaperCard(label = "Хронология" + if (rows.isNotEmpty()) " · ${rows.size}" else "") {
        if (rows.isEmpty()) PaperHint(if (loaded) "Пока пусто. Скажи Claude внизу: «созвонились с Иваном, ждут модель к пятнице» — запишу сюда." else "Загружаю с сервера…")
        val shown = if (all) rows else rows.take(DelaCrm.TL_SHOWN)
        shown.forEachIndexed { i, r ->
            if (i > 0) RowRule()
            if (r.item != null) TlItem(ctx, r.item, refresh, showDeal) else r.pay?.let { TlPay(ctx, it, r.paid, refresh, showDeal) }
        }
        if (rows.size > DelaCrm.TL_SHOWN) {
            PaperTextButton(if (all) "Свернуть" else "Показать всё · ${rows.size}", onClick = { ctx.ui.tlAll = if (all) "" else key })
        }
    }
}

@Composable
private fun TlItem(ctx: DelaCrmContext, it: DelaCrm.Interaction, refresh: List<String>, showDeal: Boolean) {
    val c = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val open = ctx.ui.tlOpen == it.id
    val (topic, body) = DelaCrm.meetingParts(it.summary)
    val steps = DelaCrm.steps(it.nextStep)
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .clickable { ctx.ui.tlOpen = if (open) "" else it.id }.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                DelaAsk.ddmm(it.day, ctx.today) + " · " + it.kindWord + if (it.local) " ⏳" else "",
                style = MaterialTheme.typography.bodySmall,
                color = c.primary,
            )
            if (topic != null) Text(topic, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            for (st in if (open) steps else steps.take(2)) Text("• $st", style = MaterialTheme.typography.bodySmall)
            if (!open && steps.size > 2) Text("ещё ${steps.size - 2}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            val meta = listOfNotNull(
                it.dealName.takeIf { n -> showDeal && n.isNotBlank() },
                it.personIds.map { id -> ctx.personName(id) }.filter { n -> n.isNotBlank() }.takeIf { l -> l.isNotEmpty() }?.joinToString(", "),
                it.durationMin?.takeIf { m -> m > 0 }?.let { m -> "$m мин" },
                "из встречи".takeIf { _ -> it.source == "meeting" },
            ).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                if (it.sourceRef.startsWith("https://")) {
                    Text(
                        (if (meta.isNotBlank()) " · " else "") + "встреча →",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.primary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openLink(context, it.sourceRef) },
                    )
                }
            }
        }
        if (!it.local) GlyphButton(Glyphs.Close, "убрать из хронологии", onClick = { ctx.ui.deleting = it to refresh }, size = 30.dp)
    }
}

@Composable
private fun TlPay(ctx: DelaCrmContext, p: Dela.Payment, paid: Boolean, refresh: List<String>, showDeal: Boolean) {
    val c = MaterialTheme.colorScheme
    val what = listOfNotNull(DelaCrm.rub(p.amountKop), DelaCrm.PAY_KIND[p.kind] ?: p.kind, p.title.takeIf { it.isNotBlank() },
        ctx.snap.deals[p.dealId]?.name?.takeIf { showDeal }).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(DelaAsk.ddmm(if (paid) p.paidOn else p.invoicedOn, ctx.today) + " · " + if (paid) "оплата пришла" else "счёт", style = MaterialTheme.typography.bodySmall, color = c.primary)
        Text(what, style = MaterialTheme.typography.bodyMedium)
        if (!paid) {
            val sent = listOfNotNull(p.sentTo.takeIf { it.isNotBlank() }?.let { "кому: $it" }, p.sentVia.takeIf { it.isNotBlank() }).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sent.ifBlank { "кому и как отправили — не записано" }, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                PaperTextButton(if (sent.isNotBlank()) "поправить" else "записать", onClick = { ctx.ui.invoicing = p to refresh })
            }
        }
    }
}

/**
 * Файлы (`filesBlock` веба): ссылка на папку и документы ссылками — вид,
 * название, ссылка, дата; «+ Документ» и крестик. Ссылка без схемы — https://.
 */
@Composable
private fun FilesCard(ctx: DelaCrmContext, folderUrl: String, files: List<Dela.FileRef>, save: (JSONObject, String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var folder by remember(folderUrl) { mutableStateOf(folderUrl) }
    var kind by remember { mutableStateOf("contract") }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    PaperCard(label = "Файлы" + if (files.isNotEmpty()) " · ${files.size}" else "") {
        if (folderUrl.isNotBlank()) PaperTextButton("Открыть папку ↗", icon = Glyphs.Link, onClick = { openLink(context, folderUrl) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PaperField(value = folder, onValueChange = { folder = it }, placeholder = "Ссылка на папку: Яндекс Диск, Google Drive…") }
            if (folder.trim() != folderUrl) GlyphButton(Glyphs.Check, "записать папку", onClick = {
                val u = DelaCrm.withScheme(folder)
                save(JSONObject().put("folder_url", Dela.nul(u)), "Папка: " + if (u.isNotBlank()) "записал" else "убрал")
            })
        }
        for ((k, f) in files.withIndex()) {
            RowRule()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(DelaCrm.FILE_KIND[f.kind] ?: "Документ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(70.dp))
                Text(
                    f.title.ifBlank { runCatching { java.net.URI(f.url).host?.removePrefix("www.") }.getOrNull() ?: f.url },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { openLink(context, f.url) },
                )
                if (f.at.isNotBlank()) Text(DelaAsk.ddmm(f.at, ctx.today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GlyphButton(Glyphs.Close, "убрать из файлов", onClick = {
                    save(JSONObject().put("files", Dela.json(files.filterIndexed { j, _ -> j != k })), "Убрал: " + f.title.ifBlank { DelaCrm.FILE_KIND[f.kind] ?: "документ" })
                }, size = 30.dp)
            }
        }
        RowRule()
        ChipRow { for ((k, w) in DelaCrm.FILE_KIND) PaperChip(w, selected = kind == k, onClick = { kind = k }) }
        PaperField(value = title, onValueChange = { title = it }, placeholder = "название: «Договор №12», «Счёт за октябрь»")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PaperField(value = url, onValueChange = { url = it }, placeholder = "ссылка на файл") }
            PaperTextButton("+ Документ", enabled = url.isNotBlank(), onClick = {
                val u = DelaCrm.withScheme(url)
                val add = Dela.FileRef(kind, title.trim(), u, ctx.today)
                save(JSONObject().put("files", Dela.json(files + add)), title.trim().ifBlank { DelaCrm.FILE_KIND[kind] ?: "Документ" } + " — в файлах")
                title = ""
                url = ""
            })
        }
    }
}

internal fun openLink(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
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
            PaperCard(label = "проекты · ${deals.size}") {
                deals.forEachIndexed { i, d -> if (i > 0) RowRule(); DealRow(ctx, d, ctx.money) }
            }
        }
    }
    item(key = "crm:person:tl") {
        val items = DelaCrm.overlayTimeline(v?.timeline.orEmpty(), ctx.ops, ctx.snap) { personId in it.personIds }
        TimelineBlock(ctx, "h:$personId", items, emptyList(), loaded = v != null, refresh = listOf(path, DelaCrm.TIES), showDeal = true)
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
    ui.invoicing?.let { (p, refresh) -> InvoiceSheet(ctx, p, refresh) }
    ui.stageFor?.let { d ->
        PaperSheet(onDismiss = { ui.stageFor = null }, title = "Стадия", icon = Glyphs.Layers, subtitle = d.name) {
            for (st in Dela.OPEN_STAGES) {
                ChoiceLine(DelaCrm.STAGE[st] ?: st, "", d.stage == st) {
                    ui.stageFor = null
                    if (d.stage != st) ctx.run(listOf(DelaCrm.stageOp(d.id, st)), listOf(DelaCrm.dealPath(d.id), DelaCrm.PIPELINE, DelaCrm.clientPath(d.projectId)),
                        "${d.name.take(40)}: ${DelaCrm.STAGE[st] ?: st}")
                }
            }
            ChoiceLine("Закрыть…", "выиграли, проиграли или заморожено", false) { ui.stageFor = null; ui.closing = d }
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
            // В команде проекта — «Ведёт проект» (`personPop` веба): прежний ведущий уходит в команду.
            ctx.snap.deals[ui.leadDeal]?.takeIf { it.leadPersonId != p.id }?.let { d ->
                PaperTextButton("Ведёт проект", onClick = {
                    ui.personFor = null
                    val team = (d.teamIds + d.leadPersonId).filter { it.isNotBlank() && it != p.id }.distinct()
                    ctx.runUndoable(
                        DelaCrm.Undoable(
                            listOf(DelaCrm.dealSetOp(d.id, JSONObject().put("lead_person_id", p.id).put("team_ids", Dela.arr(team)))),
                            listOf(DelaCrm.dealSetOp(d.id, JSONObject().put("lead_person_id", Dela.nul(d.leadPersonId)).put("team_ids", Dela.arr(d.teamIds)))),
                            "${p.label} ведёт «${d.name.take(40)}»",
                        ),
                        listOf(DelaCrm.dealPath(d.id), DelaCrm.clientPath(d.projectId), DelaCrm.PIPELINE),
                    )
                })
            }
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
 * «+ человек» — поиск, а не список (09.10.2026, `personSearchPop` веба): сразу —
 * свои ([AddTarget.base]: для команды — команда ЗФ, для людей клиента — люди
 * этого клиента); набираешь — ищет среди всех по имени, компании, должности
 * (`DelaCrm.searchPeople`); последней строкой — «Новый: «…»». К клиенту — в его
 * организацию; в проект — в люди клиента или команду (без ведущего — первый
 * станет ведущим); в команду клиента — проект один — в него, несколько — выбрать.
 */
@Composable
private fun AddPersonSheet(ctx: DelaCrmContext, t: AddTarget) {
    var q by remember(t) { mutableStateOf("") }
    // В команду клиента с несколькими проектами: кто выбран — ждём, в какой проект.
    var picked by remember(t) { mutableStateOf<Pair<Dela.Person?, String>?>(null) }
    val base = t.base.mapNotNull { ctx.snap.people[it] }
    val list = DelaCrm.searchPeople(ctx.snap, q, base, t.exclude)
    val client = ctx.snap.projects[t.clientId]
    fun toDeal(dealId: String, p: Dela.Person?, name: String) {
        val d = ctx.snap.deals[dealId] ?: return
        val refresh = listOf(DelaCrm.dealPath(dealId), DelaCrm.clientPath(d.projectId))
        val where = if (t.field == "team_ids") "в команде «${d.name.take(40)}»" else "от клиента в «${d.name.take(40)}»"
        val id = p?.id ?: Dela.newId()
        val create = if (p == null) listOf(DelaCrm.personCreateOp(id, name, if (t.field == "person_ids") client?.orgId.orEmpty() else "")) else emptyList()
        val (go, back) = when {
            t.field != "team_ids" -> DelaCrm.dealPeopleOp(dealId, "person_ids", d.personIds + id) to DelaCrm.dealPeopleOp(dealId, "person_ids", d.personIds)
            d.leadPersonId.isBlank() -> DelaCrm.dealSetOp(dealId, JSONObject().put("lead_person_id", id)) to DelaCrm.dealSetOp(dealId, JSONObject().put("lead_person_id", JSONObject.NULL))
            id == d.leadPersonId || id in d.teamIds -> { Feedback.toast(ctx.app, "${p?.label ?: name} уже в команде"); return }
            else -> DelaCrm.dealPeopleOp(dealId, "team_ids", d.teamIds + id) to DelaCrm.dealPeopleOp(dealId, "team_ids", d.teamIds)
        }
        ctx.runUndoable(DelaCrm.Undoable(create + go, listOf(back), (if (p == null) "Завёл: ${name.trim()}" else p.label) + " — $where"), refresh)
    }
    fun add(p: Dela.Person?, name: String) {
        when {
            t.dealId.isNotBlank() -> { ctx.ui.addTo = null; toDeal(t.dealId, p, name) }
            t.teamDeals.size == 1 -> { ctx.ui.addTo = null; toDeal(t.teamDeals.first(), p, name) }
            t.teamDeals.size > 1 -> picked = p to name
            else -> {
                ctx.ui.addTo = null
                val u = DelaCrm.joinClient(ctx.snap, t.clientId, p, name)
                if (u == null) Feedback.toast(ctx.app, "${p?.label ?: name} уже здесь") else ctx.runUndoable(u, listOf(DelaCrm.clientPath(t.clientId)))
            }
        }
    }
    val pk = picked
    if (pk != null) {
        PaperSheet(onDismiss = { ctx.ui.addTo = null }, title = "${pk.first?.label ?: pk.second} — в команду какого проекта?", icon = Glyphs.Plus) {
            for (id in t.teamDeals) {
                val d = ctx.snap.deals[id] ?: continue
                ChoiceLine(DelaCrm.dealShort(d.name, ctx.snap.projects[d.projectId]), DelaCrm.STAGE[d.stage] ?: d.stage, false) {
                    ctx.ui.addTo = null
                    toDeal(id, pk.first, pk.second)
                }
            }
        }
        return
    }
    PaperSheet(onDismiss = { ctx.ui.addTo = null }, title = t.title, icon = Glyphs.Plus) {
        PaperField(value = q, onValueChange = { q = it }, label = "Имя, компания, должность…")
        for (p in list) {
            val org = DelaCrm.orgLabel(ctx.snap, p.orgId)?.name.orEmpty()
            ChoiceLine(
                p.name,
                listOf(p.short.takeIf { it != p.name }.orEmpty(), p.role, org).filter { it.isNotBlank() }.joinToString(" · "),
                false,
            ) { add(p, "") }
        }
        if (list.isEmpty() && q.isBlank()) PaperHint("Набери имя — найду среди всех людей.")
        if (q.isNotBlank()) {
            ChoiceLine("Новый: «${q.trim()}»", if (client != null && t.field != "team_ids") "завести — из «${client.name}»" else "завести в справочнике", false) {
                add(null, q.trim())
            }
        }
    }
}

/**
 * «Счёт выставлен» (`invoicePop` веба): когда выставили, кому и как отправили —
 * встаёт в хронологию строкой «счёт · кому: … · почтой».
 */
@Composable
private fun InvoiceSheet(ctx: DelaCrmContext, p: Dela.Payment, refresh: List<String>) {
    var day by remember(p.id) { mutableStateOf(p.invoicedOn.ifBlank { ctx.today }) }
    var to by remember(p.id) { mutableStateOf(p.sentTo) }
    var via by remember(p.id) { mutableStateOf(p.sentVia) }
    val todayDate = runCatching { LocalDate.parse(ctx.today) }.getOrNull() ?: LocalDate.now()
    PaperSheet(
        onDismiss = { ctx.ui.invoicing = null },
        title = "Счёт: " + DelaCrm.rub(p.amountKop),
        icon = Glyphs.Check,
        subtitle = ctx.snap.deals[p.dealId]?.name,
        footer = {
            Spacer(Modifier.weight(1f))
            PaperButton("Записать", icon = Glyphs.Check, primary = true, onClick = {
                ctx.ui.invoicing = null
                ctx.run(listOf(DelaCrm.invoiceOp(p.id, day, to, via)), refresh, "Счёт: " + to.trim().ifBlank { "записал" })
            })
        },
    ) {
        PaperHint("Когда выставили")
        ChipRow {
            PaperChip("сегодня", selected = day == todayDate.toString(), onClick = { day = todayDate.toString() })
            PaperChip("вчера", selected = day == todayDate.minusDays(1).toString(), onClick = { day = todayDate.minusDays(1).toString() })
            PaperChip(delaDate(day), selected = false, onClick = {})
        }
        PaperField(value = to, onValueChange = { to = it }, label = "Кому: бухгалтерия, Иван…")
        PaperHint("Как")
        ChipRow { for (w in DelaCrm.SENT_VIA) PaperChip(w, selected = via == w, onClick = { via = if (via == w) "" else w }) }
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
