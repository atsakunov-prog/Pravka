package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.NightReviewPolicy
import ru.zf.pravka.core.ShadowPolicy
import ru.zf.pravka.data.NightReviewStore
import ru.zf.pravka.ui.Feedback

// «Ещё → Разборы» (16–17.09.2026): ночной разбор диктовок и тень второй
// модели. Владелец, глядя на первую версию (хронологическая простыня
// изменений с кнопками): «это должно быть как-то более системно показано…
// выводить наверх группами: День — правки в словарь (HARD, HINT), правила
// обработки, идеи по приложению; Неделя — то же самое». Поэтому экран — не
// список прогонов, а четыре плашки: последний дневной разбор по группам,
// последний недельный, тень, история. Что применилось само, что предложено
// — видно значком у строки; подробности и кнопки — по тапу.

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
        else SectionCard(label = "День") { HintText("Разбора ещё не было: ночью в назначенный час или кнопкой «Разобрать сутки».") }
        if (weekly != null) ReviewCard(app, "Неделя", weekly)
        else SectionCard(label = "Неделя") { HintText("Недельный разбор идёт в ночь на пятницу; вручную — «Разобрать неделю».") }
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

/** Тумблеры и ручной запуск — одной плашкой, коротко: спецификация в docs/pravka.md. */
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
        HintText("Высоковероятное применяется само (кроме отклонённого проверкой), низковероятное — предложением. Промпт не трогает.")
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
            OutlinedButton(
                enabled = !busy && !shadowRunning,
                onClick = {
                    busy = true
                    scope.launch {
                        app.shadowRun.start(manual = true)
                            .onSuccess { Feedback.toast(context, if (it.active) "Отправлено: сначала чистка, потом судья — час-два" else it.summary) }
                            .onFailure { Feedback.toast(context, "Не запустилась: ${it.message}") }
                        busy = false
                    }
                },
            ) { Text("Тень") }
        }
        for (run in runs.filter { it.active }.sortedBy { it.startedAt }) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Идёт ${kindLabel(run)}: ${stageLabel(run.stage)} · отправлено ${dayTime.format(Date(run.startedAt))} · статус раз в 10 минут",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun statusLine(run: NightReviewStore.Run): String {
    if (run.active) return "идёт ${stageLabel(run.stage)}"
    if (run.stage == "failed") return "не удался"
    if (run.isShadow) return "готово" + if (run.costUsd > 0) " · $" + "%.2f".format(Locale.US, run.costUsd) else ""
    val notes = run.changes.count { it.isNote }
    val parts = ArrayList<String>()
    parts += "применено ${run.applied()}"
    parts += "предложено ${run.proposed()}"
    if (run.rejected() > 0) parts += "отклонено ${run.rejected()}"
    if (run.changes.any { it.status == "reverted" }) parts += "возвращено ${run.changes.count { it.status == "reverted" }}"
    if (notes > 0) parts += "идей $notes"
    val money = if (run.costUsd > 0) " · $" + "%.2f".format(Locale.US, run.costUsd) else ""
    return parts.joinToString(", ") + money
}

/** Сводка без первой строки-заголовка («Применено N…»): её карточка считает сама и показывает живьём. */
private fun summaryBody(summary: String): String =
    summary.split("\n\n").filterIndexed { i, p -> !(i == 0 && p.startsWith("Применено ")) }.joinToString("\n\n").trim()

/** Порядок и подписи групп словаря: добавления по виду, потом выключенное и переведённое. */
private fun dictGroup(c: NightReviewStore.Change): String = when (c.kind) {
    "dict_add" -> c.mode.ifBlank { "?" }
    "dict_disable" -> "Выключено"
    "dict_mode" -> "Вид изменён"
    else -> ""
}

private val dictGroupOrder = listOf("HARD", "HINT", "PROTECT", "Выключено", "Вид изменён")

@Composable
private fun ReviewCard(app: PravkaApp, title: String, run: NightReviewStore.Run) {
    val context = LocalContext.current
    var replyText by remember(run.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showSummary by remember(run.id) { mutableStateOf(false) }
    SectionCard(label = "$title · ${dayTime.format(Date(run.startedAt))}${if (run.manual) " · вручную" else ""}") {
        Text(statusLine(run), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (run.error.isNotBlank()) {
            // Причина целиком (правило 6), но одной строкой на сбой — не простынёй.
            Text(run.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        val dict = run.changes.filter { it.kind.startsWith("dict_") }
        val rules = run.changes.filter { it.kind.startsWith("rule_") }
        val notes = run.changes.filter { it.kind == "note" }

        if (dict.isNotEmpty()) {
            GroupTitle("Правки в словарь")
            val byGroup = dict.groupBy { dictGroup(it) }
            for (g in dictGroupOrder + (byGroup.keys - dictGroupOrder.toSet())) {
                val items = byGroup[g] ?: continue
                Text(
                    "$g · ${items.count { it.status == "applied" }} применено, ${items.count { it.status == "proposed" }} предложено",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                for (c in items) ChangeRow(app, run, c)
            }
        }
        if (rules.isNotEmpty()) {
            GroupTitle("Правила обработки")
            for (c in rules) ChangeRow(app, run, c)
        }
        if (notes.isNotEmpty()) {
            GroupTitle("Идеи по приложению")
            for (c in notes) NoteRow(c)
        }
        if (run.summary.isNotBlank() || run.replies.isNotEmpty()) {
            Row {
                TextButton(onClick = { showSummary = !showSummary }) { Text(if (showSummary) "Скрыть сводку разбора" else "Сводка разбора") }
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
        }
        if (run.stage == "done" && run.changes.any { !it.isNote }) {
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
private fun GroupTitle(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ChangeRow(app: PravkaApp, run: NightReviewStore.Run, c: NightReviewStore.Change) {
    val context = LocalContext.current
    var open by remember(c.id) { mutableStateOf(false) }
    val mark = when (c.status) {
        "applied" -> "✓"
        "proposed" -> "•"
        "rejected" -> "✗"
        "reverted" -> "↩"
        "failed" -> "!"
        "skipped" -> "="
        else -> "·"
    }
    // В группе вид уже назван — в строке остаётся само слово.
    val title = when (c.kind) {
        "dict_add", "dict_disable" -> c.from + if (c.to.isNotBlank()) " → ${c.to}" else ""
        "dict_mode" -> "${c.from}: ${c.mode} → ${c.toMode}"
        else -> c.title()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { open = !open }) {
        Text(
            "$mark $title",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (c.status == "applied") FontWeight.SemiBold else FontWeight.Normal,
        )
        val details = listOf(
            c.why,
            if (c.verdict.isNotBlank()) "проверка: ${c.verdict}${if (c.verdictWhy.isNotBlank()) " — ${c.verdictWhy}" else ""}" else "",
            c.statusNote,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        if (details.isNotBlank()) {
            // Свёрнуто — две строки, тап раскрывает целиком: причины нужны, простыня — нет.
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        fun act(action: String) {
            app.appScope.launch {
                app.nightReview.act(run.id, c.id, action)
                    .onFailure { Feedback.toast(context, "Не получилось: ${it.message}") }
            }
        }
        // Пока прогон идёт, кнопок нет: следующий проход перепишет список
        // изменений своей копией и решение владельца потерялось бы. Кнопки —
        // под предложенным всегда, под применённым — по тапу.
        if (!run.active && (c.status == "proposed" || open)) Row {
            when (c.status) {
                "proposed" -> {
                    TextButton(onClick = { act("apply") }) { Text("Применить") }
                    TextButton(onClick = { act("reject") }) { Text("Отклонить", color = MaterialTheme.colorScheme.error) }
                }
                "applied" -> TextButton(onClick = { act("revert") }) { Text("Вернуть") }
                "rejected" -> TextButton(onClick = { act("apply") }) { Text("Всё же применить") }
                else -> Unit
            }
        }
    }
}

/** Заметка разбора про модель или распознаватель: три строки, тап раскрывает. */
@Composable
private fun NoteRow(c: NightReviewStore.Change) {
    var open by remember(c.id) { mutableStateOf(false) }
    Text(
        "· ${c.why}",
        style = MaterialTheme.typography.bodySmall,
        maxLines = if (open) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { open = !open },
    )
}

@Composable
private fun ShadowCard(run: NightReviewStore.Run) {
    SectionCard(label = "Тень · ${dayTime.format(Date(run.startedAt))}${if (run.manual) " · вручную" else ""}") {
        Text(statusLine(run), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (run.error.isNotBlank()) {
            Text(run.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (run.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(run.summary, style = MaterialTheme.typography.bodyMedium)
        }
        val examples = run.changes.filter { it.kind == ShadowPolicy.KIND }
        if (examples.isNotEmpty()) {
            GroupTitle("Примеры")
            for (c in examples) ShadowRow(c)
        }
    }
}

/** Пример тени: кто лучше и почему; тексты — по кнопке, чтобы карточка не разъезжалась. */
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
