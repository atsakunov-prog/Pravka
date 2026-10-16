package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.ui.GhostKey
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Hairline
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.Key
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalMode
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.SectionHeader
import ru.zf.pravka.ui.bottomFade
import ru.zf.pravka.ui.glass
import ru.zf.pravka.ui.scrollFade
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

// Карточка дела правой половиной разворота (Правка 4.0, `screens/08`): на
// разложенном Fold дело открывается не листом поверх списка, а рядом с ним —
// номер и проект, название крупно, «Начать в Засечке» · «Поправить» · ✓,
// плашка «Срок · Оценка · Мяч · Источник», история и человек. Правка — тем же
// листом `DelaTaskSheet` («Поправить»), здесь только чтение и три действия.

@Composable
internal fun DelaTaskPane(
    app: PravkaApp,
    task: Dela.Task?,
    snap: Dela.Snapshot,
    /** Сколько дело уже идёт в ленте (мс) — «в Засечке уже 1 ч 45 м». */
    spentMs: Long,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onPerson: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** «Место в дереве»: тап по цели — она справа вместо дела; по проекту (▣) — его страница. */
    onTask: (Dela.Task) -> Unit = {},
    onProject: (String) -> Unit = {},
    /** Кто смотрит: место в дереве — только у своего дела. */
    me: String = "",
    /**
     * Молния «В «Сейчас»» / «Из «Сейчас»» (вид v2, 13.10.2026); null — кнопки нет. С 14.10.2026 — единственная
     * отметка: солнца «На сегодня» / «На завтра» нет (его принимали за «Сейчас», а оно двигало срок).
     */
    onNow: (() -> Unit)? = null,
    /** «Перенести» — лист сроков, как «Перенести все»; null — кнопки нет. */
    onMove: (() -> Unit)? = null,
    /** «Для этого» (15.10.2026): новое дело внутри этого — листом нового дела; null — кнопки нет. */
    onStep: (() -> Unit)? = null,
) {
    if (ru.zf.pravka.ui.Look.v2) {
        DelaTaskPaneV2(app, task, snap, spentMs, onStart, onEdit, onDone, onPerson, modifier, onTask, onProject, me, onNow, onMove, onStep)
        return
    }
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    if (task == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ru.zf.pravka.ui.EmptyState("Тап по делу слева — оно откроется здесь", icon = Glyphs.Delo)
        }
        return
    }
    val today = LocalDate.now()
    // Журнал правок — с сервера, один раз на дело; нет связи — словами, без молчания.
    var journal by remember(task.id) { mutableStateOf<List<String>?>(null) }
    var journalNote by remember(task.id) { mutableStateOf("") }
    LaunchedEffect(task.id) {
        app.delaSync.card(task.id)
            .onSuccess { c -> journal = c.history.map { historyPaneLine(it, snap) } }
            .onFailure { e -> journalNote = "журнал не прочитался: ${e.message}" }
    }
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = modifier.fillMaxSize().bottomFade().scrollFade(listState),
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Проект — капсулой (номера дел нет нигде с 09.10.2026).
                val project = snap.projects[task.projectId]?.name ?: task.projectName
                if (project.isNotBlank()) {
                    Text(
                        project,
                        style = ty.valueS,
                        color = mode.label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, mode.tint.copy(alpha = 0.28f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                Text(task.title, style = ty.titleL, color = Ink.TextStrong)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.open) GhostKey("Начать в Засечке", onStart, icon = Glyphs.Play)
                    Spacer(Modifier.width(10.dp))
                    GhostKey("Карточка", onEdit, icon = Glyphs.Edit)
                    Spacer(Modifier.weight(1f))
                    Key(
                        Glyphs.Check,
                        if (task.open) "сделано" else "открыть снова",
                        onDone,
                        size = 52.dp,
                    )
                }
            }
        }
        item(key = "facts") {
            val rows = buildList {
                if (task.dueDate.isNotBlank()) {
                    val d = runCatching { LocalDate.parse(task.dueDate.take(10)) }.getOrNull()
                    if (d != null) {
                        val k = ChronoUnit.DAYS.between(today, d).toInt()
                        val rel = when {
                            k == 0 -> "сегодня"
                            k == 1 -> "завтра"
                            k > 1 -> "через " + plural(k, "день", "дня", "дней")
                            else -> "просрочено ${-k} дн"
                        }
                        add(Triple("Срок", Fmt.dayList(d) + (if (task.dueTime.isNotBlank()) ", ${task.dueTime.take(5)}" else ""), rel))
                    }
                }
                if (task.estimateMin > 0 || spentMs > 0) {
                    add(Triple(
                        "Оценка",
                        if (task.estimateMin > 0) Fmt.dur(task.estimateMin) else "без оценки",
                        if (spentMs > 0) "в Засечке уже ${Fmt.durMs(spentMs)}" else "",
                    ))
                }
                val who = snap.people[task.personId]?.label ?: task.who
                // «Группа» вместо «Чьё» (10.10.2026): отбить, запустить, мониторить — и кто ждёт.
                val g = ru.zf.pravka.core.DelaGroups.grpOf(task)
                val wait = ru.zf.pravka.core.DelaGroups.wait(task, who, today.toString(), personGrouped = false)?.text
                add(Triple("Группа", g.title, wait.orEmpty()))
                val created = task.createdAt.take(10).takeIf { it.isNotBlank() }?.let { runCatching { Fmt.dayShort(LocalDate.parse(it)) }.getOrNull() }
                val src = listOfNotNull(sourcePaneName(task.source), created).joinToString(", ")
                if (src.isNotBlank()) add(Triple("Источник", src, ""))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), mode.glass)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                rows.forEachIndexed { i, (k, v, sub) ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(k, style = ty.body, color = mode.meta, modifier = Modifier.width(110.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                androidx.compose.ui.text.buildAnnotatedString {
                                    append(v)
                                    if (sub.isNotBlank()) {
                                        pushStyle(androidx.compose.ui.text.SpanStyle(color = mode.meta))
                                        append(" · $sub")
                                        pop()
                                    }
                                },
                                style = ty.bodyL,
                                color = Ink.Text,
                            )
                            if (k == "Источник" && task.notes.isNotBlank()) {
                                Text(
                                    "«${task.notes.take(240)}»",
                                    style = ty.body.copy(fontStyle = FontStyle.Italic),
                                    color = Ink.TextSecondary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        // Место в дереве жизни (13.10.2026, `placeBlock` веба): зачем это дело и что будет, когда сделаешь.
        val dirs = ru.zf.pravka.core.DelaKrug.directions()
        if (ru.zf.pravka.core.DelaKrug.on(snap, dirs) && (me.isBlank() || task.ownerId.isBlank() || task.ownerId == me)) {
            item(key = "place") {
                PlaceBlock(task, ru.zf.pravka.core.DelaKrug.place(task, snap, today.toString(), me), dirs, snap, onTask, onProject)
            }
        }
        // История: заметки к делу и журнал правок сервера — по времени.
        val comments = snap.commentsOf(task.id)
        // Приватный комментарий (аудит №8) — с пометкой: команда, которая видит дело, его не видит.
        val lines = comments.map { it.createdAt.take(16).replace('T', ' ') to (if (it.private) "только тебе · " else "") + it.text } +
            journal.orEmpty().map { it.substringBefore(" · ") to it.substringAfter(" · ") }
        item(key = "history:h") {
            SectionHeader("история", trailing = lines.size.takeIf { it > 0 }?.toString())
        }
        if (lines.isEmpty()) {
            item(key = "history:empty") {
                Text(
                    if (journal == null && journalNote.isBlank()) "читаю журнал…" else journalNote.ifBlank { "правок нет" },
                    style = ty.meta,
                    color = mode.meta,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else {
            item(key = "history") {
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((at, text) in lines.sortedBy { it.first }) {
                        Row {
                            Text(paneStamp(at), style = ty.body, color = mode.meta, modifier = Modifier.width(110.dp))
                            Text(text, style = ty.bodyL, color = Ink.Text, modifier = Modifier.weight(1f))
                        }
                    }
                    if (journalNote.isNotBlank()) Text(journalNote, style = ty.meta, color = mode.meta)
                }
            }
        }
        val person = snap.people[task.personId]
        if (person != null) {
            item(key = "person") {
                val open = snap.tasks.values.count { it.open && it.personId == person.id }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(22.dp), mode.glass)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { onPerson(person.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    val initials = person.label.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).border(1.5.dp, mode.tint.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(initials, style = ty.valueS, color = mode.label) }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(person.label, style = ty.bodyStrong, color = Ink.Text)
                        Text(
                            "открытых дел $open",
                            style = ty.meta,
                            color = mode.meta,
                        )
                    }
                }
            }
        }
    }
}

private fun sourcePaneName(source: String): String? = when (source) {
    "voice" -> "Голосом"
    "web" -> "Из веба"
    "bot" -> "Из бота"
    "telegram" -> "Telegram"
    "meeting" -> "Встреча"
    "mcp" -> "От Claude"
    "import" -> "Перенесено"
    "manual" -> "Руками"
    else -> null
}

/** «2026-10-05 10:41» → «5 окт 10:41». */
private fun paneStamp(at: String): String = runCatching {
    val d = LocalDate.parse(at.take(10))
    "${d.dayOfMonth} ${Fmt.dayShort(d).substringAfterLast(' ')}" + if (at.length >= 16) " " + at.substring(11, 16) else ""
}.getOrDefault(at)

/** Строка журнала: «когда · кто: что поменялось». */
private fun historyPaneLine(o: org.json.JSONObject, snap: Dela.Snapshot): String {
    val at = o.optString("at").take(16).replace('T', ' ')
    val who = o.optString("actor").takeIf { it.isNotBlank() && it != "null" }?.let { snap.users[it]?.name ?: it }
    val op = when (o.optString("op")) { "insert" -> "заведено"; "update" -> "поправлено"; else -> o.optString("op") }
    val before = o.optJSONObject("before")
    val after = o.optJSONObject("after")
    val changed = if (before != null && after != null) {
        after.keys().asSequence().filter { k ->
            k !in setOf("updated_at", "rev", "seq", "search") && before.opt(k)?.toString() != after.opt(k)?.toString()
        }.take(4).joinToString(", ")
    } else ""
    return "$at · $op" + (if (changed.isNotBlank()) ": $changed" else "") + (who?.let { " — $it" } ?: "")
}

// ---------------------------------------------------------------- вид v2

/**
 * Карточка дела справа в виде v2 (этап 4 «Правки 5.0», лист «Дела» холста; карточка веба Дел): панелью с
 * линией — надстрочник «Дело» и группа, название 17/600, метки (проект, сделка, «в «Сейчас»», свои), свойства
 * строками через тонкую линию (кто ждёт, срок, напомнить, оценка, откуда), заметки и ряд действий:
 * «Сделано» акцентом, молния «В «Сейчас»», «Перенести», «Начать», «Карточка» (вся правка — тем же листом).
 * Ниже — место в дереве (Круг) и журнал: заметки к делу и правки сервера по времени. Номера дела нет нигде.
 */
@Composable
private fun DelaTaskPaneV2(
    app: PravkaApp,
    task: Dela.Task?,
    snap: Dela.Snapshot,
    spentMs: Long,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onPerson: (String) -> Unit,
    modifier: Modifier,
    onTask: (Dela.Task) -> Unit,
    onProject: (String) -> Unit,
    me: String,
    onNow: (() -> Unit)?,
    onMove: (() -> Unit)?,
    onStep: (() -> Unit)?,
) {
    val ty = LocalPravkaType.current
    if (task == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ru.zf.pravka.ui.EmptyState("Тап по делу слева — оно откроется здесь", icon = Glyphs.Delo)
        }
        return
    }
    val today = LocalDate.now()
    val iso = today.toString()
    var journal by remember(task.id) { mutableStateOf<List<String>?>(null) }
    var journalNote by remember(task.id) { mutableStateOf("") }
    LaunchedEffect(task.id) {
        app.delaSync.card(task.id)
            .onSuccess { c -> journal = c.history.map { historyPaneLine(it, snap) } }
            .onFailure { e -> journalNote = "журнал не прочитался: ${e.message}" }
    }
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = modifier.fillMaxSize().scrollFade(listState),
        state = listState,
        contentPadding = PaddingValues(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "card") {
            PanelV2 {
                val g = ru.zf.pravka.core.DelaGroups.grpOf(task)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Дело" + when (task.status) { Dela.DONE -> " · сделано"; Dela.CANCELLED -> " · отменено"; else -> "" },
                        style = ty.overline,
                        color = Kv.faint,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (task.ball == Dela.AGENDA) "При встрече" else g.title,
                        style = ty.meta,
                        color = if (g == ru.zf.pravka.core.DelaGroups.Grp.OWED) Kv.warn else Kv.faint,
                        fontWeight = if (g == ru.zf.pravka.core.DelaGroups.Grp.OWED) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    )
                }
                Text(
                    task.title,
                    style = ty.bodyStrong.copy(fontSize = 17.sp, lineHeight = 22.sp),
                    color = Kv.text,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // Метки (`.tag` холста): проект — акцентом и ссылкой на страницу, сделка, «в «Сейчас»», свои.
                val project = snap.projects[task.projectId]?.name ?: task.projectName.takeIf { task.projectId.isNotBlank() }.orEmpty()
                val deal = snap.deals[task.dealId]?.name.orEmpty()
                val isNow = ru.zf.pravka.core.DelaGroups.isNow(task, iso)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    if (project.isNotBlank()) KvTag(project, accent = true, onClick = { onProject(task.projectId) })
                    else KvTag("без проекта")
                    if (deal.isNotBlank()) KvTag(deal)
                    if (isNow) KvTag("в «Сейчас»", accent = true)
                    for (l in task.labels) KvTag(l)
                    // Дело внутри другого (15.10.2026): «для: <дело>» — тап открывает его справа.
                    ru.zf.pravka.core.DelaSteps.parentOf(task, snap)?.let { par -> KvTag("для: " + par.title.take(40), accent = true, onClick = { onTask(par) }) }
                }
                // Свойства — строками через тонкую линию (`.set` холста): подпись мелко, значение строкой.
                val who = snap.people[task.personId]?.label ?: task.who
                val props = buildList {
                    val wait = ru.zf.pravka.core.DelaGroups.wait(task, who, iso, personGrouped = false)?.text
                    when {
                        g == ru.zf.pravka.core.DelaGroups.Grp.OWED -> add(Triple("Ждёт", wait ?: "ждут", false))
                        task.ball == Dela.WAITING -> add(Triple("Жду от", wait ?: "жду", false))
                        task.ball == Dela.AGENDA -> add(Triple("При встрече", who.ifBlank { "человек не выбран" }, false))
                        who.isNotBlank() -> add(Triple("Человек", who, false))
                    }
                    val d = runCatching { LocalDate.parse(task.dueDate.take(10)) }.getOrNull()
                    if (d != null) {
                        val k = ChronoUnit.DAYS.between(today, d).toInt()
                        val rel = when {
                            k == 0 -> "сегодня"
                            k == 1 -> "завтра"
                            k > 1 -> "через " + plural(k, "день", "дня", "дней")
                            else -> "просрочено ${-k} дн."
                        }
                        add(Triple("Срок", Fmt.dayList(d) + (if (task.dueTime.isNotBlank()) ", ${task.dueTime.take(5)}" else "") + " · " + rel, k < 0 && task.open))
                    }
                    if (task.ball == Dela.WAITING && task.nudgeOn.isNotBlank()) {
                        add(Triple("Напомнить ему", runCatching { Fmt.dayList(LocalDate.parse(task.nudgeOn.take(10))) }.getOrDefault(task.nudgeOn), false))
                    }
                    if (task.estimateMin > 0 || spentMs > 0) {
                        add(Triple(
                            "Оценка",
                            (if (task.estimateMin > 0) Fmt.dur(task.estimateMin) else "без оценки") +
                                (if (spentMs > 0) " · в Засечке уже ${Fmt.durMs(spentMs)}" else ""),
                            false,
                        ))
                    }
                    val created = task.createdAt.take(10).takeIf { it.isNotBlank() }?.let { runCatching { Fmt.dayShort(LocalDate.parse(it)) }.getOrNull() }
                    val src = listOfNotNull(sourcePaneName(task.source), created).joinToString(", ")
                    if (src.isNotBlank()) add(Triple("Откуда", src, false))
                }
                Column(Modifier.padding(top = 8.dp)) {
                    props.forEachIndexed { i, (k, v, warn) ->
                        if (i > 0) ru.zf.pravka.ui.Hairline()
                        val person = k in setOf("Ждёт", "Жду от", "При встрече", "Человек") && task.personId.isNotBlank()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(KvShape.Tag))
                                .then(if (person) Modifier.clickable { onPerson(task.personId) } else Modifier)
                                .padding(vertical = 8.dp),
                        ) {
                            Text(k, style = ty.meta, color = Kv.faint)
                            Text(v + if (person) " ›" else "", style = ty.body.copy(fontSize = 14.sp), color = if (warn) Kv.warn else Kv.text)
                        }
                    }
                }
                if (task.notes.isNotBlank()) {
                    Text(
                        task.notes.take(600),
                        style = ty.label.copy(fontWeight = null, lineHeight = 19.sp),
                        color = Kv.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // Действия (`.btn.sm` холста): «Сделано» — акцент, «Сейчас» — мягким акцентом, когда взято.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    KvSmallKey(if (task.open) "Сделано" else "Вернуть в работу", if (task.open) Glyphs.Check else Glyphs.Undo, kind = if (task.open) 2 else 0, onClick = onDone)
                    if (task.open) {
                        // Молния — сразу, срок не трогает; срок — «Перенести». Кнопка есть, только если её можно нажать.
                        if (onNow != null && ru.zf.pravka.core.DelaGroups.canNow(task, me)) {
                            KvSmallKey(if (isNow) "Из «Сейчас»" else "В «Сейчас»", Glyphs.Bolt, kind = if (isNow) 1 else 0, onClick = onNow)
                        }
                        if (onMove != null) KvSmallKey("Перенести", Glyphs.Calendar, kind = 0, onClick = onMove)
                        if (onStep != null && task.deed) KvSmallKey("Для этого", Glyphs.Plus, kind = 0, onClick = onStep)
                        KvSmallKey("Начать", Glyphs.Play, kind = 0, onClick = onStart)
                    }
                    KvSmallKey("Карточка", Glyphs.Edit, kind = 0, onClick = onEdit)
                }
            }
        }
        // «Внутри» (15.10.2026, `insideBox` веба): открытые дела внутри по месту — тап открывает справа, квадрат — «сделано».
        val inside = ru.zf.pravka.core.DelaSteps.insideOf(task.id, snap)
        if (inside.isNotEmpty() && task.deed) {
            item(key = "inside") {
                PanelV2 {
                    Text("Внутри · ${inside.size}", style = ty.overline, color = Kv.faint)
                    inside.forEachIndexed { i, k ->
                        if (i > 0) ru.zf.pravka.ui.Hairline()
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Box(
                                Modifier.size(34.dp).clip(RoundedCornerShape(KvShape.Tag)).clickable(onClickLabel = "сделано") {
                                    val up = if (snap.nestOn) ru.zf.pravka.core.DelaSteps.rise(k, snap, iso) else null
                                    app.appScope.launch { app.delaDo(listOf(Dela.statusOp("task.done", k.id))) }
                                    ru.zf.pravka.ui.Feedback.toast(app, "✓ ${k.title.take(40)}" + (up?.let { " · " + ru.zf.pravka.core.DelaSteps.riseWords(it) } ?: ""))
                                },
                                contentAlignment = Alignment.Center,
                            ) { Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).border(1.5.dp, Kv.check, RoundedCornerShape(5.dp))) }
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(KvShape.Tag)).clickable { onTask(k) }.padding(vertical = 6.dp)) {
                                Text(k.title, style = ty.body.copy(fontSize = 14.sp), color = Kv.text,
                                    fontWeight = if (i == 0) androidx.compose.ui.text.font.FontWeight.SemiBold else null)
                                ru.zf.pravka.core.DelaViews.due(k, iso)?.let { Text(it.text, style = ty.meta, color = Kv.faint) }
                            }
                        }
                    }
                }
            }
        }
        // Место в дереве жизни (`placeBlock` веба): зачем это дело и что будет, когда сделаешь.
        val dirs = ru.zf.pravka.core.DelaKrug.directions()
        if (ru.zf.pravka.core.DelaKrug.on(snap, dirs) && (me.isBlank() || task.ownerId.isBlank() || task.ownerId == me)) {
            item(key = "place") {
                PlaceBlock(task, ru.zf.pravka.core.DelaKrug.place(task, snap, iso, me), dirs, snap, onTask, onProject)
            }
        }
        // Журнал (`.eyebrow` и `.lr` холста): заметки к делу и правки сервера — по времени.
        val comments = snap.commentsOf(task.id)
        // Приватный комментарий (аудит №8) — с пометкой: команда, которая видит дело, его не видит.
        val lines = comments.map { it.createdAt.take(16).replace('T', ' ') to (if (it.private) "только тебе · " else "") + it.text } +
            journal.orEmpty().map { it.substringBefore(" · ") to it.substringAfter(" · ") }
        item(key = "journal") {
            PanelV2 {
                Text("Журнал", style = ty.overline, color = Kv.faint)
                if (lines.isEmpty()) {
                    Text(
                        if (journal == null && journalNote.isBlank()) "читаю журнал…" else journalNote.ifBlank { "правок нет" },
                        style = ty.meta,
                        color = Kv.faint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    lines.sortedBy { it.first }.forEachIndexed { i, (at, text) ->
                        if (i > 0) ru.zf.pravka.ui.Hairline()
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                            Text(paneStamp(at), style = ty.meta, color = Kv.faint, modifier = Modifier.width(84.dp))
                            Text(text, style = ty.label.copy(fontWeight = null), color = Kv.text, modifier = Modifier.weight(1f))
                        }
                    }
                    if (journalNote.isNotBlank()) Text(journalNote, style = ty.meta, color = Kv.faint)
                }
            }
        }
    }
}

/** Панель вида v2 (`.card` холста): белая, линия, радиус 10, поля 12/14. */
@Composable
private fun PanelV2(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(KvShape.Panel)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Kv.panel)
            .border(1.dp, Kv.line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

/** Метка (`.tag` холста): прямоугольник 5; [accent] — мягким акцентом. */
@Composable
private fun KvTag(text: String, accent: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = LocalPravkaType.current.caption.copy(lineHeight = 19.sp),
        color = if (accent) Kv.accent else Kv.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(KvShape.Tag))
            .background(if (accent) Kv.accentSoft else Kv.hover2)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp),
    )
}

/**
 * Маленькая кнопка (`.btn.sm` холста): [kind] 0 — панель с линией, 1 — мягкий акцент (`.soft`: включено),
 * 2 — акцент (`.p`: главное действие).
 */
@Composable
private fun KvSmallKey(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, kind: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    val (bg, ink) = when (kind) {
        2 -> Kv.accent to Kv.accentInk
        1 -> Kv.accentSoft to Kv.accent
        else -> Kv.panel to Kv.text
    }
    Row(
        Modifier
            .clip(shape)
            .background(bg)
            .then(if (kind == 0) Modifier.border(1.dp, Kv.line, shape) else Modifier)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = if (kind == 0) Kv.icon else ink, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = LocalPravkaType.current.label.copy(fontSize = 13.sp, fontWeight = if (kind == 0) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
        )
    }
}
