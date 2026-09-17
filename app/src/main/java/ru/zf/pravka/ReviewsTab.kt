package ru.zf.pravka

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.NightReviewPolicy
import ru.zf.pravka.core.ShadowPolicy
import ru.zf.pravka.data.NightReviewStore
import ru.zf.pravka.data.StoreFiles
import ru.zf.pravka.data.shareFileIntent
import ru.zf.pravka.ui.Feedback

// «Ещё → Разборы» (16–17.09.2026): ночной разбор диктовок и тень второй
// модели. Владелец, глядя на первые две версии (простыня изменений, потом
// группы по видам): «в самой плашке день сделаем овальные кнопки наверху:
// применено, предложено, идеи; в каждой можно отменять решение модели или
// принимать, причём если я принял — оно пропадает. Применено: у каждого
// крестик. Предложено: галка и крестик, наверху «принять все». Идеи: по
// каждому внизу совет; советы вместе с предложениями выгружать логом и
// отправлять в Claude Code». Так и сделано: чипы переключают список, строки
// уходят из списка по действию, лог — кнопкой.

private val dayTime = SimpleDateFormat("EEE dd.MM HH:mm", Locale("ru"))

private fun stageLabel(stage: String): String = when (stage) {
    "analysis" -> "первый проход"
    "check" -> "проверка"
    "audit" -> "согласование"
    ShadowPolicy.STAGE_CLEAN -> "вторая модель чистит"
    ShadowPolicy.STAGE_JUDGE -> "судья сравнивает"
    else -> stage
}

private fun kindLabel(run: NightReviewStore.Run): String = when {
    run.isShadow -> "тень"
    run.kind == NightReviewPolicy.WEEKLY -> "неделя"
    else -> "сутки"
}

@Composable
internal fun ReviewsTab(app: PravkaApp) {
    val runs by app.nightReviewStore.runsFlow.collectAsState()
    LaunchedEffect(Unit) { app.nightReviewStore.all() }
    var openHistory by remember { mutableStateOf(setOf<Long>()) }

    val daily = runs.filter { !it.isShadow && it.kind == NightReviewPolicy.DAILY }.maxByOrNull { it.startedAt }
    val weekly = runs.filter { !it.isShadow && it.kind == NightReviewPolicy.WEEKLY }.maxByOrNull { it.startedAt }
    val shadow = runs.filter { it.isShadow }.maxByOrNull { it.startedAt }
    val shown = setOfNotNull(daily?.id, weekly?.id, shadow?.id)
    val history = runs.filter { it.id !in shown }.sortedByDescending { it.startedAt }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ControlsCard(app, runs)
        if (daily != null) ReviewCard(app, "День", daily)
        else SectionCard(label = "День") { HintText("Разбора ещё не было: ночью в назначенный час или кнопкой «Сутки».") }
        if (weekly != null) ReviewCard(app, "Неделя", weekly)
        else SectionCard(label = "Неделя") { HintText("Недельный разбор идёт в ночь на пятницу; вручную — «Неделю».") }
        if (shadow != null) ShadowCard(shadow)
        else SectionCard(label = "Тень") { HintText("Тени ещё не было: первая ночь возьмёт до 200 диктовок за месяц.") }
        if (history.isNotEmpty()) {
            SectionCard(label = "История") {
                for (run in history) {
                    val open = run.id in openHistory
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${dayTime.format(Date(run.startedAt))} · ${kindLabel(run)} · ${statusLine(run)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { openHistory = if (open) openHistory - run.id else openHistory + run.id }) {
                            Text(if (open) "Скрыть" else "Открыть")
                        }
                    }
                }
            }
            for (run in history.filter { it.id in openHistory }) {
                if (run.isShadow) ShadowCard(run) else ReviewCard(app, if (run.kind == NightReviewPolicy.WEEKLY) "Неделя" else "День", run)
            }
        }
    }
}

/** Тумблеры, ручной запуск, идущие прогоны с прогрессом и кнопками; лог за месяц. */
@Composable
private fun ControlsCard(app: PravkaApp, runs: List<NightReviewStore.Run>) {
    val context = LocalContext.current
    val scope = app.appScope
    val settings = app.settings
    val enabled by settings.nightReviewEnabledFlow.collectAsState(initial = true)
    val shadowOn by settings.shadowRunEnabledFlow.collectAsState(initial = true)
    val hour by settings.nightReviewHourFlow.collectAsState(initial = 3)
    var busy by remember { mutableStateOf(false) }
    val reviewRunning = runs.any { it.active && !it.isShadow }
    val shadowRunning = runs.any { it.active && it.isShadow }

    SectionCard(label = "Ночью") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = { on -> scope.launch { settings.setNightReviewEnabled(on) } })
            Spacer(Modifier.width(8.dp))
            Text("Разбор журналов: Fable 5.1 батчем, три прохода", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        HintText("Высоковероятное применяется само (кроме отклонённого проверкой), низковероятное отбрасывается. Промпт не трогает.")
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = shadowOn, onCheckedChange = { on -> scope.launch { settings.setShadowRunEnabled(on) } })
            Spacer(Modifier.width(8.dp))
            Text("Тень: вторая модель чистит те же диктовки, судья сравнивает слепо", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        HintText("Ничего не меняет — только счёт, изъяны и деньги. Модели — в настройках, группа «Модели».")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Запуск в ${"%02d".format(hour)}:00", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { scope.launch { settings.setNightReviewHour((hour + 23) % 24) } }) { Text("−") }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = { scope.launch { settings.setNightReviewHour((hour + 1) % 24) } }) { Text("+") }
        }
        Spacer(Modifier.height(6.dp))
        fun launchShadow(big: Boolean) {
            busy = true
            scope.launch {
                app.shadowRun.start(manual = true, big = big)
                    .onSuccess { Feedback.toast(context, if (it.active) "Отправлено: сначала чистка, потом судья — час-два" else it.summary) }
                    .onFailure { Feedback.toast(context, "Не запустилась: ${it.message}") }
                busy = false
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fun launchReview(kind: String) {
                busy = true
                scope.launch {
                    app.nightReview.start(kind, manual = true)
                        .onSuccess { Feedback.toast(context, if (it.active) "Отправлено батчем, результат обычно в течение часа" else it.summary) }
                        .onFailure { Feedback.toast(context, "Не запустился: ${it.message}") }
                    busy = false
                }
            }
            Button(enabled = !busy && !reviewRunning, onClick = { launchReview(NightReviewPolicy.DAILY) }) { Text("Сутки") }
            OutlinedButton(enabled = !busy && !reviewRunning, onClick = { launchReview(NightReviewPolicy.WEEKLY) }) { Text("Неделю") }
            OutlinedButton(enabled = !busy && !shadowRunning, onClick = { launchShadow(big = false) }) { Text("Тень") }
        }
        // Большой кусок (месяц, до 200) — только по явной просьбе: сам он идёт один раз, при первом запуске.
        Row {
            TextButton(enabled = !busy && !shadowRunning, onClick = { launchShadow(big = true) }) { Text("Тень за месяц (до 200 диктовок)") }
        }
        for (run in runs.filter { it.active }.sortedBy { it.startedAt }) {
            Spacer(Modifier.height(6.dp))
            // Возраст батча цифрой: «застряла» или «идёт» решается по нему, а не по ощущению.
            val ageMin = ((System.currentTimeMillis() - run.startedAt) / 60_000L).coerceAtLeast(0)
            val age = if (ageMin < 60) "$ageMin мин" else "${ageMin / 60} ч ${ageMin % 60} мин"
            Text(
                "Идёт ${kindLabel(run)}: ${stageLabel(run.stage)} · уже $age · " +
                    run.progress.ifBlank { "отправлено ${dayTime.format(Date(run.startedAt))}, статус раз в 10 минут" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row {
                TextButton(onClick = {
                    scope.launch {
                        val r = if (run.isShadow) app.shadowRun.pollNow(run.id) else app.nightReview.pollNow(run.id)
                        r.onSuccess { Feedback.toast(context, it) }.onFailure { Feedback.toast(context, "Опрос не прошёл: ${it.message}") }
                    }
                }) { Text("Проверить сейчас") }
                TextButton(onClick = {
                    scope.launch {
                        val r = if (run.isShadow) app.shadowRun.cancel(run.id) else app.nightReview.cancel(run.id)
                        r.onSuccess { Feedback.toast(context, "Отменено") }.onFailure { Feedback.toast(context, "Не отменилось: ${it.message}") }
                    }
                }) { Text("Отменить", color = MaterialTheme.colorScheme.error) }
            }
        }
        val month = runs.filter { it.stage == "done" && it.startedAt > System.currentTimeMillis() - 30 * 86_400_000L }
        if (month.isNotEmpty()) {
            Row {
                TextButton(onClick = { exportLog(context, app, month, "Лог разборов за 30 дней") }) { Text("Лог за 30 дней для Claude Code") }
            }
        }
    }
}

private fun statusLine(run: NightReviewStore.Run): String {
    if (run.active) return "идёт ${stageLabel(run.stage)}"
    if (run.stage == "failed") return "не удался"
    val money = if (run.costUsd > 0) " · $" + "%.2f".format(Locale.US, run.costUsd) else ""
    if (run.isShadow) return "готово$money"
    val parts = ArrayList<String>()
    parts += "применено ${run.applied()}"
    val waiting = run.changes.count { it.status == "proposed" || it.status == "reverted" }
    if (waiting > 0) parts += "предложено $waiting"
    val ideas = run.changes.count { it.kind == "note" }
    if (ideas > 0) parts += "идей $ideas"
    return parts.joinToString(" · ") + money
}

/** Сводка без первой строки-заголовка («Применено N…»): её карточка считает сама и показывает живьём. */
private fun summaryBody(summary: String): String =
    summary.split("\n\n").filterIndexed { i, p -> !(i == 0 && p.startsWith("Применено ")) }.joinToString("\n\n").trim()

/** Подзаголовки внутри списка: добавления по виду, потом выключенное, переведённое, правила. */
private fun groupOf(c: NightReviewStore.Change): String = when (c.kind) {
    "dict_add" -> c.mode.ifBlank { "?" }
    "dict_disable" -> "Выключено"
    "dict_mode" -> "Вид изменён"
    "rule_enable", "rule_disable" -> "Правила обработки"
    else -> ""
}

private val groupOrder = listOf("HARD", "HINT", "PROTECT", "Выключено", "Вид изменён", "Правила обработки")

private fun exportLog(context: Context, app: PravkaApp, runs: List<NightReviewStore.Run>, title: String) {
    app.appScope.launch {
        runCatching {
            val text = NightReviewPolicy.exportLog(runs, dateOf = { dayTime.format(Date(it)) })
            val file = File(context.filesDir, "night-review-log.md")
            withContext(Dispatchers.IO) { StoreFiles.writeAtomic(file, text) }
            context.startActivity(Intent.createChooser(shareFileIntent(context, file, "text/markdown"), title))
        }.onFailure { Feedback.toast(context, "Лог не собрался: ${it.message}") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(app: PravkaApp, title: String, run: NightReviewStore.Run) {
    val context = LocalContext.current
    var replyText by remember(run.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showSummary by remember(run.id) { mutableStateOf(false) }
    val applied = run.changes.filter { it.status == "applied" }
    // Возвращённое — снова к решению: принять ещё раз или отклонить (17.09: «возвращено — это что?»).
    val proposed = run.changes.filter { it.status == "proposed" || it.status == "reverted" }
    val ideas = run.changes.filter { it.kind == "note" }
    var tab by remember(run.id, proposed.isNotEmpty()) {
        mutableStateOf(if (proposed.isNotEmpty()) "proposed" else if (applied.isNotEmpty()) "applied" else "ideas")
    }
    SectionCard(label = "$title · ${dayTime.format(Date(run.startedAt))}${if (run.manual) " · вручную" else ""}") {
        Text(
            if (run.active) "идёт ${stageLabel(run.stage)}" + (if (run.progress.isNotBlank()) " · ${run.progress}" else "")
            else if (run.stage == "failed") "не удался"
            else "готово" + (if (run.costUsd > 0) " · $" + "%.2f".format(Locale.US, run.costUsd) else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (run.error.isNotBlank()) {
            // Причина целиком (правило 6), но одной строкой на сбой — не простынёй.
            Text(run.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = tab == "applied", onClick = { tab = "applied" }, label = { Text("Применено ${applied.size}") })
            FilterChip(selected = tab == "proposed", onClick = { tab = "proposed" }, label = { Text("Предложено ${proposed.size}") })
            FilterChip(selected = tab == "ideas", onClick = { tab = "ideas" }, label = { Text("Идеи ${ideas.size}") })
        }
        Spacer(Modifier.height(4.dp))
        when (tab) {
            "applied" -> {
                if (applied.isEmpty()) HintText("Само ничего не применилось.")
                else GroupedRows(app, run, applied, proposedTab = false)
            }
            "proposed" -> {
                if (proposed.isEmpty()) HintText("Решать нечего.")
                else {
                    if (!run.active) Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            app.appScope.launch {
                                app.nightReview.applyAll(run.id)
                                    .onSuccess { Feedback.toast(context, "Принято: $it") }
                                    .onFailure { Feedback.toast(context, "Не получилось: ${it.message}") }
                            }
                        }) { Text("Принять все") }
                    }
                    GroupedRows(app, run, proposed, proposedTab = true)
                }
            }
            else -> {
                if (ideas.isEmpty()) HintText("Заметок про модель и распознаватель нет.")
                else for (c in ideas) NoteRow(c)
            }
        }
        Row {
            if (run.summary.isNotBlank()) {
                TextButton(onClick = { showSummary = !showSummary }) { Text(if (showSummary) "Скрыть сводку" else "Сводка") }
            }
            TextButton(onClick = { exportLog(context, app, listOf(run), "Лог разбора") }) { Text("Лог для Claude Code") }
        }
        if (showSummary) {
            Text(summaryBody(run.summary), style = MaterialTheme.typography.bodySmall)
            for (p in run.replies.takeLast(3)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ты: ${p.text}\nРазбор: ${p.result}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (run.stage == "done" && (applied.isNotEmpty() || proposed.isNotEmpty())) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = replyText,
                onValueChange = { replyText = it },
                label = { Text("Ответить по-человечески: что вернуть, что применить") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Row {
                Button(
                    enabled = replyText.isNotBlank() && !sending,
                    onClick = {
                        sending = true
                        val text = replyText
                        app.appScope.launch {
                            app.nightReview.reply(run.id, text)
                                .onSuccess { Feedback.toast(context, it); replyText = "" }
                                .onFailure { Feedback.toast(context, "Не получилось: ${it.message}") }
                            sending = false
                        }
                    },
                ) { Text(if (sending) "Думаю…" else "Отправить") }
            }
        }
    }
}

@Composable
private fun GroupedRows(app: PravkaApp, run: NightReviewStore.Run, items: List<NightReviewStore.Change>, proposedTab: Boolean) {
    val byGroup = items.groupBy { groupOf(it) }
    for (g in groupOrder + (byGroup.keys - groupOrder.toSet())) {
        val list = byGroup[g] ?: continue
        Text(
            "$g · ${list.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        for (c in list) ChangeRow(app, run, c, proposedTab)
    }
}

/**
 * Строка изменения: слева действия (в «Предложено» — галка и крестик, в
 * «Применено» — крестик), справа слово и причина в две строки, тап раскрывает.
 */
@Composable
private fun ChangeRow(app: PravkaApp, run: NightReviewStore.Run, c: NightReviewStore.Change, proposedTab: Boolean) {
    val context = LocalContext.current
    var open by remember(c.id) { mutableStateOf(false) }
    fun act(action: String) {
        app.appScope.launch {
            app.nightReview.act(run.id, c.id, action)
                .onFailure { Feedback.toast(context, "Не получилось: ${it.message}") }
        }
    }
    // Вид уже назван подзаголовком группы — в строке само слово.
    val title = when (c.kind) {
        "dict_add", "dict_disable" -> c.from + if (c.to.isNotBlank()) " → ${c.to}" else ""
        "dict_mode" -> "${c.from}: ${c.mode} → ${c.toMode}"
        else -> c.title()
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        // Пока прогон идёт, кнопок нет: следующий проход перепишет список
        // изменений своей копией и решение владельца потерялось бы.
        if (!run.active) {
            if (proposedTab) {
                IconButton(onClick = { act("apply") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = "Принять", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { act("reject") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Отклонить", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(onClick = { act("revert") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Вернуть", tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f).clickable { open = !open }) {
            Text(
                (if (c.status == "reverted") "↩ " else "") + title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (c.status == "applied") FontWeight.SemiBold else FontWeight.Normal,
            )
            val details = listOf(
                if (c.status == "reverted") "ты вернул — принять снова или отклонить" else "",
                c.why,
                if (c.verdict == "hold") "согласование придержало: ${c.verdictWhy}" else "",
                if (open && c.verdict.isNotBlank() && c.verdict != "hold") "проверка: ${c.verdict}${if (c.verdictWhy.isNotBlank()) " — ${c.verdictWhy}" else ""}" else "",
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (details.isNotBlank()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (open) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Идея: наблюдение разбора и совет разработчику — что поменять в промпте, словаре, распознавателе. */
@Composable
private fun NoteRow(c: NightReviewStore.Change) {
    var open by remember(c.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { open = !open }) {
        Text(
            "· ${c.why}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (open) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (c.note.isNotBlank()) {
            Text(
                "Совет: ${c.note}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun ShadowCard(run: NightReviewStore.Run) {
    val context = LocalContext.current
    val app = context.applicationContext as PravkaApp
    SectionCard(label = "Тень · ${dayTime.format(Date(run.startedAt))}${if (run.manual) " · вручную" else ""}") {
        Text(
            statusLine(run) + (if (run.active && run.progress.isNotBlank()) " · ${run.progress}" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (run.error.isNotBlank()) {
            Text(run.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (run.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(run.summary, style = MaterialTheme.typography.bodyMedium)
        }
        val examples = run.changes.filter { it.kind == ShadowPolicy.KIND }
        if (examples.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Примеры", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            for (c in examples) ShadowRow(c)
        }
        if (run.stage == "done") Row {
            TextButton(onClick = { exportLog(context, app, listOf(run), "Лог тени") }) { Text("Лог для Claude Code") }
        }
    }
}

/** Пример тени: кто лучше и почему; тексты — по тапу, чтобы карточка не разъезжалась. */
@Composable
private fun ShadowRow(c: NightReviewStore.Change) {
    var open by remember(c.id) { mutableStateOf(false) }
    val head = when (c.verdict) {
        "tie", "same" -> "равноценно"
        "both_bad" -> "оба плохо"
        "unjudged" -> "без вердикта"
        else -> "лучше ${c.verdict}"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { open = !open }) {
        Text(
            "$head — ${c.verdictWhy}" + (if (c.why.isNotBlank()) " (${c.why})" else ""),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (open) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (open) {
            Text("Надиктовано: ${c.from}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text("${c.mode}: ${c.to}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(3.dp))
            Text("${c.toMode}: ${c.note}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
