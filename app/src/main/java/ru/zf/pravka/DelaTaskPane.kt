package ru.zf.pravka

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.ui.GhostKey
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Hairline
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.Key
import ru.zf.pravka.ui.LocalMode
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.SectionHeader
import ru.zf.pravka.ui.bottomFade
import ru.zf.pravka.ui.glass
import ru.zf.pravka.ui.scrollFade
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
) {
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
                // «#61 Бета Групп»: номер — капсулой, проект — вторым тоном.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.numLabel,
                        style = ty.valueS,
                        color = mode.label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, mode.tint.copy(alpha = 0.28f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    val project = snap.projects[task.projectId]?.name ?: task.projectName
                    if (project.isNotBlank()) {
                        Text(project, style = ty.bodyL, color = mode.meta, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text(task.title, style = ty.titleL, color = Ink.TextStrong)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.open) GhostKey("Начать в Засечке", onStart, icon = Glyphs.Play)
                    Spacer(Modifier.width(10.dp))
                    GhostKey("Поправить", onEdit, icon = Glyphs.Edit)
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
                val ball = when (task.ball) {
                    Dela.WAITING -> "жду" + (if (who.isNotBlank()) " $who" else "")
                    Dela.AGENDA -> "при встрече" + (if (who.isNotBlank()) " с $who" else "")
                    else -> "у меня"
                }
                val since = task.waitingSince.ifBlank { task.createdAt }.take(10)
                val days = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(since), today).toInt() }.getOrNull()
                add(Triple("Мяч", ball, days?.takeIf { it > 0 }?.let { plural(it, "день", "дня", "дней") }.orEmpty()))
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
        // История: заметки к делу и журнал правок сервера — по времени.
        val comments = snap.commentsOf(task.id)
        val lines = comments.map { it.createdAt.take(16).replace('T', ' ') to it.text } +
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
