package ru.zf.pravka

import androidx.compose.foundation.clickable
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
}

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
) {
    fun data(path: String): JSONObject? = views[path]?.data

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
        Text(d.name + if (d.local) " ⏳" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(
            (d.projectName.ifBlank { ctx.snap.projects[d.projectId]?.name.orEmpty() } + if (d.dealType.isNotBlank()) " · ${d.dealType}" else "").takeIf { it.isNotBlank() },
            ctx.personName(d.leadPersonId).takeIf { it.isNotBlank() },
            if (money) d.feeKop?.takeIf { it > 0 }?.let {
                DelaCrm.rubShort(it) + if (d.pEff != null && d.stage in setOf("lead", "proposal")) " · ${d.pEff}%" else ""
            } else null,
            DelaCrm.hours(d.minutes30).takeIf { it.isNotBlank() }?.let { "$it за 30 дн." },
        ).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
    item(key = "crm:clients") {
        PaperCard(label = "клиенты", info = "Последний контакт, следующее дело и стадии сделок. Контакт старше полутора месяцев — красным.") {
            Freshness(ctx, DelaCrm.CLIENTS)
            PaperField(value = ctx.ui.clientQuery, onValueChange = { ctx.ui.clientQuery = it }, label = "Найти клиента")
            ChipRow { PaperChip("и архив", selected = ctx.ui.clientArchive, onClick = { ctx.ui.clientArchive = !ctx.ui.clientArchive }) }
        }
    }
    if (v == null) return
    val rows = v.find(ctx.ui.clientQuery, ctx.ui.clientArchive)
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

@Composable
private fun ClientRow(ctx: DelaCrmContext, cl: DelaCrm.Client, money: Boolean) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { ctx.push(DelaPage.Project(cl.id)) }.padding(vertical = 6.dp)) {
        val tail = listOfNotNull(
            cl.org.takeIf { it.isNotBlank() && Dela.norm(it) != Dela.norm(cl.name) },
            "архив".takeIf { !cl.live },
        ).joinToString(" · ")
        Text(cl.name + if (tail.isNotBlank()) " · $tail" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val next = cl.nextTask
        if (next != null) {
            Text("→ #${next.num} ${next.title}" + if (next.dueDate.isNotBlank()) " · " + DelaAsk.ddmm(next.dueDate, ctx.today) else "",
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else if (cl.liveDeals > 0) {
            Text("нет следующего дела", style = MaterialTheme.typography.bodySmall, color = c.error)
        }
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
            Text(p.name + if (p.org.isNotBlank()) " · ${p.org}" else "", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val since = p.sinceDays?.let { if (it == 0) "контакт сегодня" else "контакт $it дн. назад" } ?: "контактов не записано"
            val bits = listOfNotNull(
                DelaCrm.CADENCE[p.cadence],
                "хаб".takeIf { p.hub },
                since,
                p.brought.takeIf { it > 0 }?.let { "привёл сделок: $it" },
                p.agenda.takeIf { it > 0 }?.let { "повестка: $it" },
                p.birthdayIn?.takeIf { it <= 14 }?.let { if (it == 0) "день рождения сегодня" else "день рождения через $it дн." },
            ).joinToString(" · ")
            Text(bits, style = MaterialTheme.typography.bodySmall, color = if (p.due) c.error else c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        PaperTextButton("Поговорили", onClick = {
            ctx.ui.talk = TalkTarget(p.name, personIds = listOf(p.id), refresh = listOf(DelaCrm.TIES, DelaCrm.dossierPath(p.id)))
        })
    }
}

// ---------------------------------------------------------------- клиент

/** Клиент на странице проекта: сделки, люди, хронология — над делами проекта из своей копии. */
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
            if (v != null && v.people.isNotEmpty()) {
                PaperHint("Люди")
                ChipRow {
                    for (p in v.people) PaperChip(p.name + if (p.role.isNotBlank()) " (${p.role})" else "", selected = false, onClick = { ctx.push(DelaPage.Person(p.id)) })
                }
            }
        }
    }
    // Сделки: из ответа вида (с посчитанным), а пока его нет — из копии синка.
    val deals = (v?.deals ?: ctx.snap.allDealsOf(projectId).map { fromCopy(it, project?.name.orEmpty()) })
        .map { DelaCrm.overlayDeal(it, ctx.ops, ctx.today) }
        .sortedWith(compareBy<DelaCrm.Deal>({ it.closed }, { Dela.STAGES.indexOf(it.stage) }, { it.name.lowercase() }))
    item(key = "crm:client:deals") {
        PaperCard(label = "сделки · ${deals.size}") {
            if (deals.isEmpty()) PaperHint("Сделок нет. Новая — в вебе, кнопкой «+ Сделка».")
            deals.forEachIndexed { i, d -> if (i > 0) RowRule(); DealRow(ctx, d, money) }
        }
    }
    item(key = "crm:client:tl") {
        val items = DelaCrm.overlayTimeline(v?.timeline.orEmpty(), ctx.ops, ctx.snap) { it.projectId == projectId }
        TimelineCard(ctx, items, TalkTarget(project?.name.orEmpty(), projectId = projectId, refresh = listOf(path)), loaded = v != null, showDeal = true)
    }
}

/** Сделка из копии синка — пока вид не пришёл: без посчитанного сервером, но с именем, стадией и итогом. */
private fun fromCopy(d: Dela.Deal, projectName: String): DelaCrm.Deal = DelaCrm.Deal(
    id = d.id, projectId = d.projectId, projectName = projectName, name = d.name, stage = d.stage, outcome = d.outcome,
    lostReason = d.lostReason, closedOn = d.closedOn, dealType = d.dealType, leadPersonId = d.leadPersonId,
    teamIds = d.teamIds, personIds = d.personIds, feeKop = d.feeKop, local = d.local,
)

// ---------------------------------------------------------------- сделка

/**
 * Карточка сделки: стадия переключателем, «Закрыть…» с итогом, «Вернуть в
 * работу»; следующий шаг — открытые дела сделки и «Следующее дело…»;
 * оплаты, если деньги открыты («Счёт выставлен», «Оплачено»); хронология.
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
    val d = DelaCrm.overlayDeal(base, ctx.ops, ctx.today)
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
            val props = listOfNotNull(
                d.dealType.takeIf { it.isNotBlank() }?.let { "тип: $it" },
                ctx.personName(d.leadPersonId).takeIf { it.isNotBlank() }?.let { "ведёт: $it" },
                d.teamIds.map { ctx.personName(it) }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { "команда: " + it.joinToString(", ") },
                d.personIds.map { ctx.personName(it) }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { "люди клиента: " + it.joinToString(", ") },
            )
            for (line in props) PaperHint(line)
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

    // Следующий шаг — открытое дело сделки (правило 12 сервера), из своей копии.
    val open = ctx.snap.tasks.values.filter { it.dealId == dealId && it.open }.sortedWith(Dela.ORDER)
        .ifEmpty { v?.open.orEmpty() }
    item(key = "crm:deal:next") {
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

    if (money) {
        val pays = DelaCrm.overlayPayments(v?.payments ?: ctx.snap.paymentsOf(dealId), ctx.ops)
        item(key = "crm:deal:money") { DealMoney(ctx, d, pays, listOf(path)) }
    }

    item(key = "crm:deal:tl") {
        val items = DelaCrm.overlayTimeline(v?.timeline.orEmpty(), ctx.ops, ctx.snap) { it.dealId == dealId }
        TimelineCard(
            ctx,
            items,
            TalkTarget(d.name, projectId = d.projectId, dealId = d.id, personIds = d.personIds, refresh = listOf(path, DelaCrm.clientPath(d.projectId))),
            loaded = v != null,
        )
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
 * Деньги сделки — только кому открыты: гонорар, сколько получено, счета,
 * план, и оплаты с «Счёт выставлен» и «Оплачено». План оплат заводится в
 * вебе (этап 3 телефона — после слова владельца).
 */
@Composable
private fun DealMoney(ctx: DelaCrmContext, d: DelaCrm.Deal, pays: List<Dela.Payment>, refresh: List<String>) {
    val c = MaterialTheme.colorScheme
    val live = pays.filter { it.live }
    val paid = live.filter { it.paidOn.isNotBlank() }.sumOf { it.amountKop }
    val inv = live.filter { it.paidOn.isBlank() && it.invoicedOn.isNotBlank() }.sumOf { it.amountKop }
    val plan = live.filter { it.paidOn.isBlank() && it.invoicedOn.isBlank() }.sumOf { it.amountKop }
    PaperCard(label = "деньги") {
        val fee = d.feeKop ?: 0L
        val sum = listOfNotNull(
            DelaCrm.rub(fee).takeIf { it.isNotBlank() }?.let { "гонорар $it" },
            DelaCrm.rub(paid).takeIf { it.isNotBlank() }?.let { "получено $it" },
            DelaCrm.rub(inv).takeIf { it.isNotBlank() }?.let { "счета $it" },
            DelaCrm.rub(plan).takeIf { it.isNotBlank() }?.let { "план $it" },
            if (fee > 0 && d.stage in setOf("mandate", "active", "closing")) DelaCrm.rub(maxOf(fee - paid, 0L)).takeIf { it.isNotBlank() }?.let { "осталось $it" } else null,
        ).joinToString(" · ").ifBlank { "оплат пока нет" }
        PaperHint(sum)
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

/** Листы CRM поверх вкладки: «Закрыть…» сделку, «Поговорили», убрать запись. */
@Composable
internal fun CrmSheets(ctx: DelaCrmContext) {
    val ui = ctx.ui
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
