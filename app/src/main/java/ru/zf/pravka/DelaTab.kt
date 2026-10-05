package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Dela
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.finishDelaReason
import ru.zf.pravka.trigger.listenForDelaReason
import ru.zf.pravka.trigger.onRaznoskaTap
import ru.zf.pravka.trigger.onRaznoskaText
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperIconButton
import ru.zf.pravka.ui.PaperRow
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.Segments
import ru.zf.pravka.ui.SummaryLine
import ru.zf.pravka.ui.VoiceInput
import ru.zf.pravka.ui.scrollFade

// Вкладка «Дела» на домашнем сервере (03.10.2026, docs/dela-server.md,
// «Задание телефону», пункт 3). Центр системы — ежедневный и еженедельный
// разбор («Утро», «Неделя»), а не ввод: ввода у владельца и так в избытке
// (321 задача из сентябрьских встреч лежала без дела). Поэтому вкладка
// открывается на «Утре», а ввод — та же пилюля «говори дела», что у «Д».
//
// Всё считается из копии на телефоне (`DelaStore.view`) — вкладка открывается
// мгновенно и работает без сети; правка ложится в очередь и видна сразу.
// Карточка дела правит всё и закрывает (Todoist-вкладка только читала).
// «Новое» — одним движением: принять, поправить, отклонить с причиной.

private val VIEWS = listOf("Утро", "Новое", "Жду", "Неделя", "Все")
private val SPHERES = listOf("work" to "Работа", "home" to "Дом", "all" to "Всё")

/** Страница поверх видов: человек или проект целиком. */
private sealed class DelaPage {
    data class Person(val id: String) : DelaPage()
    data class Project(val id: String) : DelaPage()
}

@Composable
fun DelaTab(app: PravkaApp) {
    val snap by app.delaStore.view.collectAsState()
    val queued by app.delaStore.queued.collectAsState()
    val notices by app.delaStore.noticesFlow.collectAsState()
    val link by app.delaSync.link.collectAsState()
    val st by app.delaSync.status.collectAsState()
    val ribbon by app.zasechkaStore.entriesFlow.collectAsState()
    val ownerName = app.profileStore.flow.collectAsState().value?.name
    val talking = ru.zf.pravka.ui.rememberRouteBusy(app.liveWork, "raznoska")
    val scope = app.appScope

    var view by rememberSaveable { mutableStateOf(0) }
    var sphere by rememberSaveable { mutableStateOf("all") }
    var draft by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<DelaPage?>(null) }
    var openTask by remember { mutableStateOf<Dela.Task?>(null) }
    var newTask by remember { mutableStateOf(false) }
    var editingSuggestion by remember { mutableStateOf<Dela.Suggestion?>(null) }
    var rejecting by remember { mutableStateOf<Dela.Suggestion?>(null) }
    var starting by remember { mutableStateOf("") }

    LaunchedEffect(link) {
        app.delaStore.load()
        // Открытие вкладки — хороший момент отдать очередь и взять новое.
        if (link != null) runCatching { app.delaSync.tick() }
    }

    val me = link?.user ?: app.delaStore.me
    val today = LocalDate.now().toString()
    val now = System.currentTimeMillis()
    val running = ribbon.firstOrNull { it.open }

    val actions = DelaActions(
        open = { openTask = it },
        start = { t ->
            if (starting.isBlank()) {
                starting = t.id
                scope.launch {
                    val entry = runCatching { app.zasechkaEngine.startTask(t) }.getOrNull()
                    Feedback.toast(app, if (entry != null) "⏱ ${entry.title}" else "Не смог записать дело")
                    starting = ""
                }
            }
        },
        done = { t ->
            scope.launch {
                app.delaDo(listOf(Dela.statusOp(if (t.open) "task.done" else "task.reopen", t.id)))
                Feedback.toast(app, if (t.open) "✓ ${t.title.take(40)}" else "Дело снова открыто")
            }
        },
        person = { id -> if (id.isNotBlank()) page = DelaPage.Person(id) },
        project = { id -> if (id.isNotBlank()) page = DelaPage.Project(id) },
        starting = starting,
        // Связь с лентой видна у дела (05.10.2026): что идёт сейчас и сколько уже ушло.
        running = if (running != null) running.task else "",
        runningMs = running?.durationMs(now) ?: 0L,
        spent = remember(ribbon, now) { ru.zf.pravka.core.ZasechkaTasks.spent(ribbon, now) },
        stop = { scope.launch { app.zasechkaEngine.closeOpen() } },
    )

    val listState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().scrollFade(listState),
        state = listState,
        contentPadding = ScreenPad.Padding,
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        // Пилюля «говори дела» — та же, что выезжает у «Д»: голос и набор идут
        // одним разбором Разноски и сразу в Дела.
        item {
            VoiceInput(
                value = draft,
                onValueChange = { draft = it },
                placeholder = if (talking) "Разбираю…" else ru.zf.pravka.core.PillHint.say(ownerName, "говори дела"),
                onSend = {
                    val text = draft.trim()
                    val service = PravkaAccessibilityService.instance
                    if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
                    else if (text.isNotEmpty()) {
                        draft = ""
                        service.onRaznoskaText(text)
                    }
                },
                onMic = {
                    val service = PravkaAccessibilityService.instance
                    if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
                    else service.onRaznoskaTap()
                },
                sendEnabled = draft.isNotBlank(),
                maxLines = 4,
                busy = talking,
            )
        }

        item {
            Column(Modifier.fillMaxWidth()) {
                if (running != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                        Icon(Glyphs.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Сейчас идёт: ${running.title.ifBlank { "без названия" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton(
                        Glyphs.Refresh,
                        "отправить очередь и обновить",
                        enabled = link != null && !st.running,
                        onClick = { scope.launch { app.delaSync.sync("руками") } },
                    )
                    val bad = st.lastError.isNotBlank() && st.lastErrorAt >= st.lastOk
                    Text(
                        delaStatusLine(link != null, st, queued.size, snap.syncedAt, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    GlyphButton(Glyphs.Plus, "новое дело руками", onClick = { newTask = true })
                    GlyphButton(
                        if (searching) Glyphs.Close else Glyphs.Search,
                        if (searching) "закрыть поиск" else "поиск по делам",
                        onClick = {
                            if (searching) query = ""
                            searching = !searching
                        },
                    )
                }
                if (searching || query.isNotEmpty()) PaperField(value = query, onValueChange = { query = it }, label = "Поиск по делам")
                if (link == null) {
                    Text(
                        "Дела не подключены — отсканируй QR сервера: «Настройки → Подключения → Дела». " +
                            "Пока показываю, что лежит в копии телефона.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        if (notices.isNotEmpty()) {
            item(key = "notices") {
                PaperCard(
                    label = "сервер ответил",
                    trailing = { PaperTextButton("Понятно", onClick = { scope.launch { app.delaStore.dismissNotices() } }) },
                ) {
                    for (n in notices.take(5)) {
                        PaperHint(n.text, if (n.error) MaterialTheme.colorScheme.error else null)
                    }
                }
            }
        }

        val pg = page
        if (pg != null) {
            item(key = "back") {
                PaperRow(title = "Назад к видам", icon = Glyphs.Back, onClick = { page = null })
            }
            when (pg) {
                is DelaPage.Person -> personPage(Dela.person(snap, pg.id), actions)
                is DelaPage.Project -> projectPage(Dela.project(snap, pg.id), actions)
            }
            return@LazyColumn
        }

        val needle = query.trim()
        if (needle.isNotEmpty()) {
            val found = Dela.search(snap, needle, withClosed = true)
            item(key = "found") {
                PaperCard(label = "найдено · ${found.size}") {
                    if (found.isEmpty()) PaperHint("Ни одно дело этого не содержит.")
                    TaskRows(found, actions)
                }
            }
            return@LazyColumn
        }

        item(key = "views") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val fresh = Dela.newOnes(snap, me).size
                Segments(
                    options = VIEWS.mapIndexed { i, v -> if (i == 1 && fresh > 0) "$v · $fresh" else v },
                    selected = view,
                    onSelect = { view = it },
                )
                if (view != 1) {
                    Segments(
                        options = SPHERES.map { it.second },
                        selected = SPHERES.indexOfFirst { it.first == sphere }.coerceAtLeast(0),
                        onSelect = { sphere = SPHERES[it].first },
                    )
                }
            }
        }

        // Разобранные наговоры, которые ещё ждут решения, — над видами.
        item(key = "raznoska") { RaznoskaSection(app) }

        when (view) {
            0 -> morningView(Dela.morning(snap, me, today, sphere, now), Dela.quick(snap, me, sphere), actions) { view = 1 }
            1 -> newView(
                batches = Dela.newBatches(snap, me),
                snap = snap,
                onAccept = { items ->
                    scope.launch {
                        app.delaDo(items.map { Dela.decideOp(it.id, accept = true) })
                        Feedback.toast(app, if (items.size == 1) "✓ принято" else "✓ принято: ${items.size}")
                    }
                },
                onEdit = { editingSuggestion = it },
                onReject = { rejecting = it },
            )
            2 -> waitingView(Dela.waiting(snap, me, sphere), today, actions)
            3 -> weekView(Dela.week(snap, me, today, sphere, now), snap, actions) { sg ->
                // Погасшее неразобранным — одним тапом в дела: предложение уже
                // не принять (оно не pending), дело создаётся заново.
                scope.launch {
                    val t = Dela.draftOf(sg, snap, me).copy(id = Dela.newId(), source = "manual", sourceRef = sg.sourceRef)
                    app.delaDo(listOf(Dela.createOp(t)))
                    Feedback.toast(app, "✓ в делах: ${t.title.take(40)}")
                }
            }
            else -> allView(Dela.byProject(snap, me, sphere), actions)
        }

        if (snap.empty && link != null) {
            item { Box(Modifier.padding(start = 4.dp)) { PaperHint("Копия пустая — нажми «обновить» слева вверху.") } }
        }
    }

    val t = openTask
    if (t != null) {
        // Правка считается от того, что владелец видел, открывая карточку (`was`):
        // синк, пришедший пока она открыта, не откатывается чужим старым значением.
        val fresh = t
        DelaTaskSheet(
            app = app,
            task = fresh,
            snap = snap,
            isNew = false,
            onDismiss = { openTask = null },
            onSave = { after ->
                openTask = null
                val (set, was) = Dela.diff(fresh, after)
                if (set.length() > 0) scope.launch { app.delaDo(listOf(Dela.setOp(fresh.id, set, was))) }
            },
            onStatus = { op ->
                openTask = null
                scope.launch { app.delaDo(listOf(Dela.statusOp(op, fresh.id))) }
            },
            onStart = { openTask = null; actions.start(fresh) },
        )
    }
    if (newTask) {
        val blank = remember { Dela.Task(id = Dela.newId(), title = "", ownerId = me, source = "manual") }
        DelaTaskSheet(
            app = app,
            task = blank,
            snap = snap,
            isNew = true,
            onDismiss = { newTask = false },
            onSave = { after ->
                newTask = false
                if (after.title.isNotBlank()) scope.launch { app.delaDo(listOf(Dela.createOp(after))) }
            },
            onStatus = { newTask = false },
            onStart = { newTask = false },
        )
    }
    val sg = editingSuggestion
    if (sg != null) {
        val proposed = remember(sg.id) { Dela.draftOf(sg, snap, me) }
        DelaTaskSheet(
            app = app,
            task = proposed,
            snap = snap,
            isNew = true,
            title = "Предложение",
            saveText = "Принять",
            quote = sg.quote,
            onDismiss = { editingSuggestion = null },
            onSave = { after ->
                editingSuggestion = null
                // В set — только поправленное: остальное сервер возьмёт из
                // предложения сам (имена — в id по своему справочнику).
                val (set, _) = Dela.diff(proposed, after)
                scope.launch {
                    app.delaDo(listOf(Dela.decideOp(sg.id, accept = true, set = set)))
                    Feedback.toast(app, "✓ принято с поправкой")
                }
            },
            onStatus = { editingSuggestion = null },
            onStart = { editingSuggestion = null },
        )
    }
    val rj = rejecting
    if (rj != null) {
        RejectSheet(
            suggestion = rj,
            onDismiss = { rejecting = null },
            onReject = { reason ->
                rejecting = null
                scope.launch {
                    app.delaDo(listOf(Dela.decideOp(rj.id, accept = false, reason = reason)))
                    Feedback.toast(app, "Отклонено")
                }
            },
        )
    }
}

/** Что умеет строка дела — одним набором на все виды. */
private class DelaActions(
    val open: (Dela.Task) -> Unit,
    val start: (Dela.Task) -> Unit,
    val done: (Dela.Task) -> Unit,
    val person: (String) -> Unit,
    val project: (String) -> Unit,
    val starting: String,
    /** Дело, которое сейчас идёт в ленте; "" — никакое. */
    val running: String = "",
    val runningMs: Long = 0L,
    /** Время в ленте по id дела. */
    val spent: Map<String, Long> = emptyMap(),
    val stop: () -> Unit = {},
)

/** «обновлено 20:42 · в очереди 2» или причина целиком — молчаливая очередь читается как поломка. */
private fun delaStatusLine(
    connected: Boolean,
    st: ru.zf.pravka.data.DelaSync.Status,
    queued: Int,
    syncedAt: Long,
    now: Long,
): String {
    val parts = mutableListOf<String>()
    when {
        !connected -> parts += "не подключено"
        st.running -> parts += "связываюсь…"
        st.lastError.isNotBlank() && st.lastErrorAt >= st.lastOk -> parts += st.lastError
        syncedAt > 0 -> {
            val mins = ((now - syncedAt) / 60_000L).toInt()
            parts += if (mins < 1) "обновлено только что"
            else if (mins < 120) "обновлено $mins мин назад"
            else "обновлено " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(syncedAt))
        }
        else -> parts += "ещё не обновлялось"
    }
    if (queued > 0) parts += "в очереди $queued — уйдут сами, «обновить» — сейчас"
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------- виды

private fun LazyListScope.section(key: String, label: String, list: List<Dela.Task>, actions: DelaActions, hint: String? = null) {
    if (list.isEmpty()) return
    item(key = key) {
        PaperCard(label = "$label · ${list.size}", info = hint) { TaskRows(list, actions) }
    }
}

private fun LazyListScope.morningView(
    m: Dela.Morning,
    quick: List<Dela.Task>,
    actions: DelaActions,
    openNew: () -> Unit,
) {
    if (m.newCount > 0) {
        item(key = "m:new") {
            PaperRow(
                title = "Новое: ${m.newCount}",
                hint = "предложения встреч и чатов ждут решения",
                icon = Glyphs.Spark,
                onClick = openNew,
            )
        }
    }
    section("m:now", "сейчас", m.now, actions, "Дела, отмеченные «Сейчас» на сегодня. Завтра отметка гаснет сама.")
    section("m:today", "сегодня и просрочено", m.today, actions)
    section("m:nudge", "напомнить", m.nudge, actions, "Мяч у человека, и пора напомнить: настал день напоминания или срок.")
    section("m:paid", "оплачено, без срока", m.paidUndated, actions, "Моё по платным проектам, у которого нет срока: деньги без даты.")
    section("m:others", "от других", m.fromOthers, actions, "Дела, которые поставили тебе другие за последние три дня.")
    if (quick.isNotEmpty()) {
        item(key = "m:quick") {
            var open by remember { mutableStateOf(false) }
            PaperCard {
                SummaryLine(title = "Быстрое, до 10 минут", summary = quick.size.toString(), expanded = open, onToggle = { open = !open }) {
                    TaskRows(quick, actions)
                }
            }
        }
    }
    if (m.total == 0 && m.newCount == 0) {
        item(key = "m:empty") { Box(Modifier.padding(start = 4.dp)) { PaperHint("На утро ничего: ни сроков, ни напоминаний.") } }
    }
}

private fun LazyListScope.newView(
    batches: List<Dela.Batch>,
    snap: Dela.Snapshot,
    onAccept: (List<Dela.Suggestion>) -> Unit,
    onEdit: (Dela.Suggestion) -> Unit,
    onReject: (Dela.Suggestion) -> Unit,
) {
    if (batches.isEmpty()) {
        item(key = "n:empty") { Box(Modifier.padding(start = 4.dp)) { PaperHint("Нового нет — всё разобрано.") } }
        return
    }
    for (b in batches) {
        item(key = "n:" + (b.ref.ifBlank { b.items.first().id })) {
            val many = b.items.size > 1
            PaperCard(label = (b.title.ifBlank { sourceWord(b.items.first().source) }).take(40) + if (many) " · ${b.items.size}" else "") {
                b.items.forEachIndexed { i, sg ->
                    if (i > 0) RowRule()
                    SuggestionRow(sg, snap, onAccept = { onAccept(listOf(sg)) }, onEdit = { onEdit(sg) }, onReject = { onReject(sg) })
                }
                if (many) {
                    // Встреча — одна карточка: «мои обещания / всё / по одному».
                    val mine = b.items.filter { val p = it.payloadObj(); p.optString("ball").let { x -> x.isBlank() || x == "null" || x == Dela.MINE } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mine.isNotEmpty() && mine.size < b.items.size) {
                            PaperTextButton("Мои обещания · ${mine.size}", onClick = { onAccept(mine) })
                        }
                        Spacer(Modifier.weight(1f))
                        PaperButton("Принять все", icon = Glyphs.Check, primary = true, onClick = { onAccept(b.items) })
                    }
                }
            }
        }
    }
}

private fun sourceWord(source: String): String = when (source) {
    "meeting" -> "со встречи"
    "telegram", "userbot" -> "из Telegram"
    "bot" -> "из бота"
    "mcp" -> "от Claude"
    "phone" -> "с телефона"
    "user" -> "от человека"
    else -> "предложение"
}

@Composable
private fun SuggestionRow(
    sg: Dela.Suggestion,
    snap: Dela.Snapshot,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onReject: () -> Unit,
) {
    val p = sg.payloadObj()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onEdit)) {
            val head = when (sg.kind) {
                "close" -> "закрыть: " + (snap.tasks[sg.taskId]?.title ?: sg.title)
                "assign" -> "взять: " + (snap.tasks[sg.taskId]?.title ?: sg.title)
                "update" -> "поправить: " + (snap.tasks[sg.taskId]?.title ?: sg.title)
                else -> sg.title
            }
            Text(head, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val meta = listOf(
                p.optString("project_name").takeIf { it.isNotBlank() && it != "null" }?.let { "#$it" },
                p.optString("person_name").takeIf { it.isNotBlank() && it != "null" },
                p.optString("due_date").takeIf { it.isNotBlank() && it != "null" }?.let { "срок " + shortDate(it) },
            ).filterNotNull().joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (sg.quote.isNotBlank()) {
                Text("«${sg.quote.take(160)}»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        GlyphButton(Glyphs.Close, "отклонить", onClick = onReject, size = 36.dp)
        GlyphButton(Glyphs.Edit, "поправить и принять", onClick = onEdit, size = 36.dp)
        GlyphButton(Glyphs.Check, "принять", onClick = onAccept, size = 36.dp, tint = MaterialTheme.colorScheme.primary)
    }
}

private fun LazyListScope.waitingView(groups: List<Dela.Waiting>, today: String, actions: DelaActions) {
    if (groups.isEmpty()) {
        item(key = "w:empty") { Box(Modifier.padding(start = 4.dp)) { PaperHint("Ни от кого ничего не жду.") } }
        return
    }
    for (g in groups) {
        item(key = "w:" + g.personId.ifBlank { "-" }) {
            PaperCard(
                label = g.person.take(30) + " · ${g.items.size}",
                trailing = {
                    if (g.personId.isNotBlank()) {
                        GlyphButton(Glyphs.Forward, "всё по человеку", onClick = { actions.person(g.personId) }, size = 30.dp)
                    }
                },
            ) { TaskRows(g.items, actions, today = today) }
        }
    }
}

private fun LazyListScope.weekView(
    w: Dela.Week,
    snap: Dela.Snapshot,
    actions: DelaActions,
    revive: (Dela.Suggestion) -> Unit,
) {
    section("wk:stale", "залежалось", w.stale, actions, "Срок прошёл больше двух недель назад или дело не трогали три недели: сделать, перенести или отменить.")
    section("wk:wait", "жду дольше недели", w.waitingStale, actions, "Мяч у человека больше недели, и напомнить не назначено.")
    if (w.noNextStep.isNotEmpty()) {
        item(key = "wk:next") {
            PaperCard(label = "деньги без следующего шага · ${w.noNextStep.size}", info = "Платные и перспективные проекты, где нет ни одного моего открытого дела.") {
                w.noNextStep.forEachIndexed { i, p ->
                    if (i > 0) RowRule()
                    PaperRow(
                        title = p.name,
                        hint = if (p.moneyDefault == "paid") "оплачено" else "развитие",
                        onClick = { actions.project(p.id) },
                    )
                }
            }
        }
    }
    if (w.expired.isNotEmpty()) {
        item(key = "wk:expired") {
            PaperCard(label = "погасло в «Новом» · ${w.expired.size}", info = "Неразобранное за неделю гаснет само. Если всё-таки дело — «в дела».") {
                w.expired.forEachIndexed { i, sg ->
                    if (i > 0) RowRule()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sg.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (sg.kind == "create") PaperTextButton("в дела", onClick = { revive(sg) })
                    }
                }
            }
        }
    }
    if (w.stale.isEmpty() && w.waitingStale.isEmpty() && w.noNextStep.isEmpty() && w.expired.isEmpty()) {
        item(key = "wk:empty") { Box(Modifier.padding(start = 4.dp)) { PaperHint("Неделя чистая: ничего не залежалось.") } }
    }
}

private fun LazyListScope.allView(groups: List<Pair<Dela.Project?, List<Dela.Task>>>, actions: DelaActions) {
    if (groups.isEmpty()) {
        item(key = "a:empty") { Box(Modifier.padding(start = 4.dp)) { PaperHint("Открытых дел нет.") } }
        return
    }
    for ((p, list) in groups) {
        val key = "a:" + (p?.id ?: list.first().projectId.ifBlank { "inbox" })
        item(key = key) {
            var open by rememberSaveable(key) { mutableStateOf(p == null) }
            val title = p?.name ?: list.first().projectName.ifBlank { "Входящие" }
            PaperCard(
                label = (if (title.length > 28) title.take(27).trimEnd() + "…" else title) + " · ${list.size}",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p != null) GlyphButton(Glyphs.Forward, "проект целиком", onClick = { actions.project(p.id) }, size = 30.dp)
                        GlyphButton(
                            Glyphs.ChevronDown,
                            if (open) "свернуть" else "раскрыть",
                            onClick = { open = !open },
                            size = 30.dp,
                            modifier = Modifier.rotate(if (open) 180f else 0f),
                        )
                    }
                },
            ) {
                if (open) TaskRows(list, actions)
                else Text(
                    list.take(4).joinToString(" · ") { it.title },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private fun LazyListScope.personPage(v: Dela.PersonView, actions: DelaActions) {
    item(key = "p:head") {
        PaperCard(label = "человек") {
            Text(v.person?.name ?: "человек не найден", style = MaterialTheme.typography.titleMedium)
            val more = v.person?.let { p -> listOfNotNull(p.short.takeIf { it.isNotBlank() && it != p.name }) + p.aliases }.orEmpty()
            if (more.isNotEmpty()) PaperHint("зовут: " + more.joinToString(", "))
            v.person?.phones?.takeIf { it.isNotEmpty() }?.let { PaperHint(it.joinToString(", ")) }
        }
    }
    section("p:agenda", "поднять при встрече", v.agenda, actions)
    section("p:waiting", "жду от него", v.waiting, actions)
    section("p:asked", "он просил", v.asked, actions)
    section("p:mine", "моё про него", v.mineAbout, actions)
    if (v.agenda.isEmpty() && v.waiting.isEmpty() && v.asked.isEmpty() && v.mineAbout.isEmpty()) {
        item(key = "p:empty") { Box(Modifier.padding(start = 4.dp)) { PaperHint("Открытых дел с этим человеком нет.") } }
    }
}

private fun LazyListScope.projectPage(v: Dela.ProjectView, actions: DelaActions) {
    item(key = "pr:head") {
        PaperCard(label = "проект") {
            Text(v.project?.name ?: "проект не найден", style = MaterialTheme.typography.titleMedium)
            val p = v.project
            if (p != null) {
                val bits = listOf(
                    if (p.sphere == "home") "дом" else "работа",
                    when (p.kind) { "client" -> "клиент"; "internal" -> "служебный"; else -> "личный" },
                    when (p.moneyDefault) { "paid" -> "оплачено"; "potential" -> "развитие"; else -> "" },
                ).filter { it.isNotBlank() }
                PaperHint(bits.joinToString(" · ") + if (p.aliases.isNotEmpty()) " · зовут: " + p.aliases.joinToString(", ") else "")
                if (p.note.isNotBlank()) PaperHint(p.note)
            }
            if (v.deals.isNotEmpty()) PaperHint("сделки: " + v.deals.joinToString(" · ") { it.name + if (it.stage.isNotBlank()) " (${stageWord(it.stage)})" else "" })
        }
    }
    section("pr:open", "открытые", v.open, actions)
    if (v.done.isNotEmpty()) {
        item(key = "pr:done") {
            var open by remember { mutableStateOf(false) }
            PaperCard {
                SummaryLine(title = "Закрытые", summary = v.done.size.toString(), expanded = open, onToggle = { open = !open }) {
                    TaskRows(v.done, actions)
                }
            }
        }
    }
}

private fun stageWord(stage: String): String = when (stage) {
    "lead" -> "лид"
    "proposal" -> "предложение"
    "mandate" -> "мандат"
    "active" -> "в работе"
    "closing" -> "закрытие"
    "archive" -> "архив"
    else -> stage
}

// ---------------------------------------------------------------- строки

@Composable
private fun TaskRows(list: List<Dela.Task>, actions: DelaActions, today: String = LocalDate.now().toString()) {
    list.forEachIndexed { i, t ->
        if (i > 0) RowRule()
        TaskRow(t, actions, today)
    }
}

/**
 * Дело строкой: кружок — закрыть одним касанием (как в Todoist), сама строка —
 * карточка, ▶ — запись в Засечке с id дела и проекта. Под названием — номер,
 * проект, у кого мяч, срок, оценка и деньги.
 */
@Composable
private fun TaskRow(t: Dela.Task, actions: DelaActions, today: String) {
    val c = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { actions.open(t) }.padding(vertical = 7.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (t.moneyEff == "paid" && t.open) c.error else c.outline, CircleShape)
                .clickable { actions.done(t) },
            contentAlignment = Alignment.Center,
        ) {
            if (!t.open) Icon(Glyphs.Check, contentDescription = "закрыто", tint = c.primary, modifier = Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (t.open) c.onSurface else c.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val live = t.id == actions.running
            val spentMs = actions.spent[t.id] ?: 0L
            val meta = listOfNotNull(
                ("идёт " + ru.zf.pravka.core.ZasechkaTasks.label(actions.runningMs)).takeIf { live },
                taskMeta(t, today).takeIf { it.isNotBlank() },
                ("в ленте " + ru.zf.pravka.core.ZasechkaTasks.label(spentMs)).takeIf { spentMs > 0L },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        live -> c.primary
                        t.open && t.dueDate.isNotBlank() && t.dueDate < today -> c.error
                        else -> c.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actions.starting == t.id) {
            Text("…", style = MaterialTheme.typography.bodyMedium, color = c.primary)
        } else if (t.open && t.id == actions.running) {
            // Идёт сейчас — стоп вместо ▶: второй раз начинать нечего.
            Icon(
                Glyphs.Stop,
                contentDescription = "остановить в ленте",
                tint = c.error,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { actions.stop() }.padding(4.dp),
            )
        } else if (t.open) {
            Icon(
                Glyphs.Play,
                contentDescription = "начать в ленте",
                tint = c.primary,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { actions.start(t) }.padding(4.dp),
            )
        }
    }
}

private fun taskMeta(t: Dela.Task, today: String): String {
    val parts = mutableListOf<String>()
    parts += t.numLabel + if (t.local) " ⏳" else ""
    if (t.projectName.isNotBlank()) parts += t.projectName
    when (t.ball) {
        Dela.WAITING -> parts += "жду: " + t.who.ifBlank { "?" } + if (t.waitingSince.isNotBlank()) " с " + shortDate(t.waitingSince) else ""
        Dela.AGENDA -> parts += "при встрече: " + t.who.ifBlank { "?" }
        else -> if (t.who.isNotBlank()) parts += "для: " + t.who
    }
    if (t.dueDate.isNotBlank()) parts += (if (t.dueDate < today && t.open) "просрочено " else "срок ") + shortDate(t.dueDate) +
        if (t.dueTime.isNotBlank()) " " + t.dueTime.take(5) else ""
    if (t.ball == Dela.WAITING && t.nudgeOn.isNotBlank()) parts += "напомнить " + shortDate(t.nudgeOn)
    if (t.estimateMin > 0) parts += "${t.estimateMin} мин"
    if (t.moneyEff == "paid") parts += "₽" else if (t.moneyEff == "potential") parts += "₽?"
    if (t.focusOn == today) parts += "сейчас"
    if (t.status == Dela.DONE) parts += "закрыто" else if (t.status == Dela.CANCELLED) parts += "отменено"
    return parts.joinToString(" · ")
}

private fun shortDate(iso: String): String = runCatching {
    LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("d MMM", Locale("ru")))
}.getOrDefault(iso)

// ---------------------------------------------------------------- карточка

/**
 * Карточка дела: всё правится — название, заметки, проект и сделка, мяч и
 * человек, срок и напоминание, оценка, деньги, «хочу сам», «сейчас», метки,
 * комментарии; дело закрывается, отменяется и возвращается. Сохранение — одна
 * операция `task.set` только с поменявшимися полями и тем, что телефон видел
 * до правки (`was`): если поле успели поменять другие, сервер скажет о споре.
 * Журнал правок — только с сервера, по кнопке.
 */
@Composable
internal fun DelaTaskSheet(
    app: PravkaApp,
    task: Dela.Task,
    snap: Dela.Snapshot,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Dela.Task) -> Unit,
    onStatus: (String) -> Unit,
    onStart: () -> Unit,
    title: String? = null,
    saveText: String = "Сохранить",
    quote: String = "",
    /** Черновик Разноски: «убрать это дело» корзиной, без «Сейчас» (у черновика его нет). */
    onDrop: (() -> Unit)? = null,
    warn: String = "",
) {
    var f by remember(task.id) { mutableStateOf(task) }
    var picking by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<String>?>(null) }
    var historyNote by remember { mutableStateOf("") }
    val scope = app.appScope
    val today = LocalDate.now()
    val comments = snap.commentsOf(task.id)
    val existing = !isNew

    PaperSheet(
        onDismiss = onDismiss,
        title = title ?: if (isNew) "Новое дело" else "Дело ${task.numLabel}",
        icon = Glyphs.Delo,
        subtitle = if (existing) listOfNotNull(
            task.createdAt.take(10).takeIf { it.isNotBlank() }?.let { "заведено " + shortDate(it) },
            sourceName(task.source),
        ).joinToString(" · ") else null,
        actions = {
            if (existing && task.open) GlyphButton(Glyphs.Play, "начать в ленте", onClick = onStart, tint = MaterialTheme.colorScheme.primary)
        },
        footer = {
            if (existing && task.open) {
                PaperIconButton(Glyphs.Delete, "отменить дело", onClick = { onStatus("task.cancel") }, tint = MaterialTheme.colorScheme.error)
                PaperButton("Закрыть", icon = Glyphs.Check, onClick = { onStatus("task.done") })
            } else if (existing) {
                PaperButton("Вернуть", icon = Glyphs.Undo, onClick = { onStatus("task.reopen") })
            } else if (onDrop != null) {
                PaperIconButton(Glyphs.Delete, "убрать это дело", onClick = onDrop, tint = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            PaperButton(saveText, primary = true, enabled = f.title.isNotBlank(), onClick = { onSave(f.copy(title = f.title.trim(), notes = f.notes.trim())) })
        },
    ) {
        if (quote.isNotBlank()) PaperHint("«${quote.take(300)}»")
        if (warn.isNotBlank()) PaperHint(warn, MaterialTheme.colorScheme.error)
        PaperField(value = f.title, onValueChange = { f = f.copy(title = it) }, label = "Кто: что сделать", singleLine = false)
        PaperField(value = f.notes, onValueChange = { f = f.copy(notes = it) }, label = "Заметки", singleLine = false, maxLines = 6)

        PaperRow(
            title = f.projectId.let { id -> if (id.isBlank()) "Входящие (без проекта)" else snap.projects[id]?.name ?: f.projectName.ifBlank { "проект" } },
            hint = "проект",
            icon = Glyphs.ListLines,
            onClick = { picking = "project" },
        )
        if (f.projectId.isNotBlank() && snap.dealsOf(f.projectId).isNotEmpty()) {
            PaperRow(
                title = snap.deals[f.dealId]?.name ?: "без сделки",
                hint = "сделка",
                icon = Glyphs.Layers,
                onClick = { picking = "deal" },
            )
        }

        PaperHint("У кого мяч")
        ChipRow {
            PaperChip("Моё", selected = f.ball == Dela.MINE, onClick = { f = f.copy(ball = Dela.MINE) })
            PaperChip("Жду", selected = f.ball == Dela.WAITING, onClick = { f = f.copy(ball = Dela.WAITING) })
            PaperChip("При встрече", selected = f.ball == Dela.AGENDA, onClick = { f = f.copy(ball = Dela.AGENDA) })
        }
        PaperRow(
            title = snap.people[f.personId]?.label ?: "без человека",
            hint = when (f.ball) { Dela.WAITING -> "жду от"; Dela.AGENDA -> "поднять с"; else -> "для кого / про кого" },
            icon = Glyphs.Phone,
            onClick = { picking = "person" },
        )
        if (f.ball != Dela.MINE && f.personId.isBlank()) {
            PaperHint("Без человека «жду» и «при встрече» некого ждать — выбери, у кого мяч.", MaterialTheme.colorScheme.error)
        }

        DateLine("Срок", f.dueDate, today) { f = f.copy(dueDate = it, dueTime = if (it.isBlank()) "" else f.dueTime) }
        if (f.ball == Dela.WAITING) DateLine("Напомнить", f.nudgeOn, today) { f = f.copy(nudgeOn = it) }

        PaperHint("Сколько займёт")
        ChipRow {
            for (m in listOf(5, 10, 30, 60, 120)) {
                PaperChip(if (m < 60) "$m мин" else "${m / 60} ч", selected = f.estimateMin == m, onClick = { f = f.copy(estimateMin = if (f.estimateMin == m) 0 else m) })
            }
        }
        PaperHint("Деньги")
        ChipRow {
            val inherited = snap.projects[f.projectId]?.moneyDefault ?: "none"
            PaperChip("как у проекта" + if (inherited == "paid") " (₽)" else if (inherited == "potential") " (₽?)" else "", selected = f.money.isBlank(), onClick = { f = f.copy(money = "") })
            PaperChip("оплачено", selected = f.money == "paid", onClick = { f = f.copy(money = "paid") })
            PaperChip("развитие", selected = f.money == "potential", onClick = { f = f.copy(money = "potential") })
            PaperChip("без денег", selected = f.money == "none", onClick = { f = f.copy(money = "none") })
        }
        if (onDrop == null) PaperToggle(
            "Сейчас — на сегодня",
            checked = f.focusOn == today.toString(),
            onCheckedChange = { on -> f = f.copy(focusOn = if (on) today.toString() else "") },
            hint = "в «Утре» первым; завтра отметка гаснет сама",
        )
        PaperToggle("Хочу сам", checked = f.want, onCheckedChange = { f = f.copy(want = it) }, hint = "не потому, что попросили")
        PaperRow(
            title = if (f.labels.isEmpty()) "метки" else f.labels.joinToString(" ") { "@$it" },
            hint = "свободные контексты: звонок, …",
            icon = Glyphs.Tag,
            onClick = { picking = "labels" },
        )

        if (existing) {
            Spacer(Modifier.height(4.dp))
            PaperHint("Комментарии")
            for (cm in comments) {
                Text(
                    (snap.users[cm.authorId]?.name ?: cm.authorId).ifBlank { "—" } + " · " + shortDate(cm.createdAt) + (if (cm.local) " ⏳" else "") + "\n" + cm.text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaperField(value = comment, onValueChange = { comment = it }, label = "Комментарий", singleLine = false, maxLines = 4, modifier = Modifier.weight(1f))
                GlyphButton(Glyphs.Send, "добавить комментарий", enabled = comment.isNotBlank(), onClick = {
                    val text = comment.trim()
                    comment = ""
                    scope.launch { app.delaDo(listOf(Dela.commentOp(task.id, text))) }
                })
            }
            val h = history
            if (h == null) {
                PaperTextButton(historyNote.ifBlank { "Журнал правок (с сервера)" }, icon = Glyphs.Scroll, onClick = {
                    historyNote = "Читаю журнал…"
                    scope.launch {
                        app.delaSync.card(task.id)
                            .onSuccess { card -> history = card.history.map { historyLine(it, snap) } }
                            .onFailure { e -> historyNote = "Журнал не прочитался: ${e.message}" }
                    }
                })
            } else {
                PaperHint("Журнал")
                if (h.isEmpty()) PaperHint("Правок нет.")
                for (line in h) Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    when (picking) {
        "project" -> PickSheet(
            title = "Проект",
            none = "Входящие (без проекта)",
            items = snap.liveProjects().map { Triple(it.id, it.name, it.aliases.joinToString(", ")) },
            selected = f.projectId,
            onDismiss = { picking = "" },
        ) { id -> f = f.copy(projectId = id, dealId = ""); picking = "" }
        "deal" -> PickSheet(
            title = "Сделка",
            none = "без сделки",
            items = snap.dealsOf(f.projectId).map { Triple(it.id, it.name, stageWord(it.stage)) },
            selected = f.dealId,
            onDismiss = { picking = "" },
        ) { id -> f = f.copy(dealId = id); picking = "" }
        "person" -> PickSheet(
            title = "Человек",
            none = "без человека",
            items = snap.livePeople().map { Triple(it.id, it.label, (listOf(it.name).filter { n -> n != it.label } + it.aliases).joinToString(", ")) },
            selected = f.personId,
            onDismiss = { picking = "" },
        ) { id -> f = f.copy(personId = id); picking = "" }
        "labels" -> LabelsSheet(snap.labels, f.labels, onDismiss = { picking = "" }) { f = f.copy(labels = it) }
    }
}

private fun sourceName(source: String): String? = when (source) {
    "voice" -> "голосом"
    "web" -> "из веба"
    "bot" -> "из бота"
    "telegram" -> "из Telegram"
    "meeting" -> "со встречи"
    "mcp" -> "от Claude"
    "import" -> "перенесено"
    else -> null
}

/** Строка журнала: когда, кто, что поменялось — по-человечески, коротко. */
private fun historyLine(o: org.json.JSONObject, snap: Dela.Snapshot): String {
    val at = o.optString("at").let { a -> runCatching { a.take(16).replace('T', ' ') }.getOrDefault(a) }
    val who = o.optString("actor").takeIf { it.isNotBlank() && it != "null" }?.let { snap.users[it]?.name ?: it } ?: "?"
    val op = when (o.optString("op")) { "insert" -> "завёл"; "update" -> "поправил"; else -> o.optString("op") }
    val before = o.optJSONObject("before")
    val after = o.optJSONObject("after")
    val changed = if (before != null && after != null) {
        after.keys().asSequence().filter { k ->
            k !in setOf("updated_at", "rev", "seq", "search") && before.opt(k)?.toString() != after.opt(k)?.toString()
        }.take(4).joinToString(", ")
    } else ""
    return "$at · $who · $op" + if (changed.isNotBlank()) ": $changed" else ""
}

/** Дата строкой ГГГГ-ММ-ДД и быстрые чипы: сегодня, завтра, пятница, через неделю, убрать. */
@Composable
private fun DateLine(label: String, value: String, today: LocalDate, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    PaperField(
        value = text,
        onValueChange = { v ->
            text = v
            if (v.isBlank()) onChange("") else if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(v)) onChange(v)
        },
        label = "$label ГГГГ-ММ-ДД" + if (value.isNotBlank()) " · " + shortDate(value) else "",
    )
    ChipRow {
        val friday = today.plusDays(((5 - today.dayOfWeek.value + 7) % 7).toLong().let { if (it == 0L) 7L else it })
        for ((name, d) in listOf("сегодня" to today, "завтра" to today.plusDays(1), "пятница" to friday, "через неделю" to today.plusDays(7))) {
            PaperChip(name, selected = value == d.toString(), onClick = { onChange(d.toString()) })
        }
        if (value.isNotBlank()) PaperChip("убрать", selected = false, onClick = { onChange("") })
    }
}

/** Выбор из справочника с поиском: тап выбирает и закрывает. */
@Composable
private fun PickSheet(
    title: String,
    none: String,
    items: List<Triple<String, String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    PaperSheet(onDismiss = onDismiss, title = title, icon = Glyphs.ListLines) {
        if (items.size > 8) PaperField(value = q, onValueChange = { q = it }, label = "Найти")
        PickLine(none, "", selected.isBlank()) { onPick("") }
        val n = Dela.norm(q)
        for ((id, name, more) in items) {
            if (n != null && Dela.norm("$name $more")?.contains(n) != true) continue
            PickLine(name, more, id == selected) { onPick(id) }
        }
    }
}

@Composable
private fun PickLine(text: String, more: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (more.isNotBlank()) Text(more, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Glyphs.Check, contentDescription = "выбрано", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/** Метки: из справочника сервера отметками, новая — строкой (свободный контекст без пробелов). */
@Composable
private fun LabelsSheet(all: List<String>, chosen: List<String>, onDismiss: () -> Unit, onChange: (List<String>) -> Unit) {
    var set by remember { mutableStateOf(chosen.toSet()) }
    var fresh by remember { mutableStateOf("") }
    PaperSheet(
        onDismiss = { onChange(set.toList()); onDismiss() },
        title = "Метки",
        icon = Glyphs.Tag,
        footer = {
            Spacer(Modifier.weight(1f))
            PaperButton("Готово", icon = Glyphs.Check, primary = true, onClick = { onChange(set.toList()); onDismiss() })
        },
    ) {
        ChipRow {
            for (l in (all + set).distinct()) {
                PaperChip("@$l", selected = l in set, onClick = { set = if (l in set) set - l else set + l })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperField(value = fresh, onValueChange = { fresh = it.replace(Regex("[\\s,#@]"), "") }, label = "Новая метка", modifier = Modifier.weight(1f))
            GlyphButton(Glyphs.Plus, "добавить метку", enabled = fresh.isNotBlank(), onClick = {
                set = set + fresh.take(40)
                fresh = ""
            })
        }
    }
}

/**
 * «Отклонить» предложение: причина — материал для промптов, поэтому её
 * просят, но не требуют. Готовые причины — одним касанием; своя — словами
 * или голосом (микрофон — тот же движок, что у «Д»).
 */
@Composable
private fun RejectSheet(suggestion: Dela.Suggestion, onDismiss: () -> Unit, onReject: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    PaperSheet(
        onDismiss = onDismiss,
        title = "Отклонить",
        icon = Glyphs.Close,
        subtitle = suggestion.title.take(80),
        actions = {
            GlyphButton(
                if (listening) Glyphs.Stop else Glyphs.Mic,
                if (listening) "готово" else "причина голосом",
                tint = MaterialTheme.colorScheme.primary,
                onClick = {
                    val service = PravkaAccessibilityService.instance
                    when {
                        service == null -> Feedback.toast(context, context.getString(R.string.toast_no_service))
                        listening -> if (!service.finishDelaReason(keep = true)) listening = false
                        else -> listening = service.listenForDelaReason { text ->
                            listening = false
                            reason = (reason.trim() + " " + text.trim()).trim()
                        }
                    }
                },
            )
        },
        footer = {
            Spacer(Modifier.weight(1f))
            PaperButton("Отклонить", icon = Glyphs.Close, primary = true, onClick = { onReject(reason.trim()) })
        },
    ) {
        if (suggestion.quote.isNotBlank()) PaperHint("«${suggestion.quote.take(300)}»")
        ChipRow {
            for (r in listOf("это идея, не дело", "уже сделано", "дубль", "не моё", "не сейчас")) {
                PaperChip(r, selected = reason == r, onClick = { onReject(r) })
            }
        }
        PaperField(
            value = reason,
            onValueChange = { reason = it },
            label = if (listening) "Слушаю — говори причину…" else "Своя причина",
            singleLine = false,
            maxLines = 4,
        )
    }
}
