package ru.zf.pravka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.NightReviewPolicy
import ru.zf.pravka.data.NightReviewStore
import ru.zf.pravka.ui.Feedback

// Ночной разбор в настройках Правки (16.09.2026): тумблер, час запуска,
// ручной запуск, отчёты прогонов с кнопками под каждым изменением и текстбокс
// «ответить по-человечески». Владелец: «каждое утро и каждую пятницу
// записывал, что он добавил, в этой же вкладке, а я мог нормальным языком в
// текстбоксе ответить, что надо переправить обратно».

private val dayTime = SimpleDateFormat("EEE dd.MM HH:mm", Locale("ru"))

private fun stageLabel(stage: String): String = when (stage) {
    "analysis" -> "первый проход"
    "check" -> "проверка"
    "audit" -> "согласование"
    else -> stage
}

@Composable
internal fun NightReviewSection(app: PravkaApp) {
    val context = LocalContext.current
    val scope = app.appScope
    val settings = app.settings
    val enabled by settings.nightReviewEnabledFlow.collectAsState(initial = true)
    val hour by settings.nightReviewHourFlow.collectAsState(initial = 3)
    val runs by app.nightReviewStore.runsFlow.collectAsState()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.nightReviewStore.all() }

    Spacer(Modifier.height(18.dp))
    Text("Ночной разбор", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = enabled, onCheckedChange = { on -> scope.launch { settings.setNightReviewEnabled(on) } })
        Spacer(Modifier.width(8.dp))
        Text("Разбирать журналы каждую ночь", style = MaterialTheme.typography.bodyMedium)
    }
    HintText(
        "Три прохода Fable 5.1 батчем (вдвое дешевле обычного запроса): первый читает " +
            "чистки и правки руками за сутки плюс счётчики повторов за неделю, словарь и правила " +
            "целиком; второй проверяет каждую находку; третий смотрит на итог целиком против " +
            "прошлых решений — что применяли, что ты возвращал. Высоковероятное, подтверждённое и " +
            "согласованное применяется само, остальное лежит предложениями. Отчёт каждое утро, " +
            "по пятницам — за неделю (тексты за 7 дней, повторы за 30). Промпт разбор не трогает. " +
            "Модели и усилие — в группе «Модели»."
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Запуск в ${"%02d".format(hour)}:00", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { scope.launch { settings.setNightReviewHour((hour + 23) % 24) } }) { Text("−") }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(onClick = { scope.launch { settings.setNightReviewHour((hour + 1) % 24) } }) { Text("+") }
    }
    Spacer(Modifier.height(6.dp))
    val running = runs.any { it.active }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fun launch(kind: String) {
            busy = true
            scope.launch {
                app.nightReview.start(kind, manual = true)
                    .onSuccess { Feedback.toast(context, if (it.active) "Отправлено батчем, результат обычно в течение часа" else it.summary) }
                    .onFailure { Feedback.toast(context, "Не запустился: ${it.message}") }
                busy = false
            }
        }
        Button(enabled = !busy && !running, onClick = { launch(NightReviewPolicy.DAILY) }) { Text("Разобрать сутки") }
        OutlinedButton(enabled = !busy && !running, onClick = { launch(NightReviewPolicy.WEEKLY) }) { Text("Разобрать неделю") }
    }
    runs.firstOrNull { it.active }?.let { run ->
        Spacer(Modifier.height(4.dp))
        Text(
            "Идёт: ${stageLabel(run.stage)} · отправлено ${dayTime.format(Date(run.startedAt))} · статус спрашивается раз в 10 минут",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(10.dp))
    for (run in runs.sortedByDescending { it.startedAt }.take(8)) {
        RunCard(app, run)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RunCard(app: PravkaApp, run: NightReviewStore.Run) {
    val context = LocalContext.current
    var replyText by remember(run.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val kindLabel = if (run.kind == NightReviewPolicy.WEEKLY) "неделя" else "сутки"
    SectionCard(label = "${dayTime.format(Date(run.startedAt))} · $kindLabel${if (run.manual) " · вручную" else ""}") {
        val status = when (run.stage) {
            "analysis", "check", "audit" -> "идёт ${stageLabel(run.stage)}"
            "failed" -> "не удался"
            else -> "готово" + if (run.costUsd > 0) " · $" + "%.3f".format(Locale.US, run.costUsd) else ""
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (run.error.isNotBlank()) {
            Text(run.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (run.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(run.summary, style = MaterialTheme.typography.bodyMedium)
        }
        for (c in run.changes) ChangeRow(app, run, c)
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
            for (p in run.replies.takeLast(3)) {
                Text(
                    "Ты: ${p.text}\nРазбор: ${p.result}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChangeRow(app: PravkaApp, run: NightReviewStore.Run, c: NightReviewStore.Change) {
    val context = LocalContext.current
    val mark = when (c.status) {
        "applied" -> "✓"
        "proposed" -> "•"
        "rejected" -> "✗"
        "reverted" -> "↩"
        "failed" -> "!"
        "skipped" -> "="
        else -> "·"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            if (c.isNote) "· ${c.why}" else "$mark ${c.title()}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (c.status == "applied") FontWeight.SemiBold else FontWeight.Normal,
        )
        if (!c.isNote) {
            val details = listOf(
                c.why,
                if (c.verdict.isNotBlank()) "проверка: ${c.verdict}${if (c.verdictWhy.isNotBlank()) " — ${c.verdictWhy}" else ""}" else "",
                c.statusNote,
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (details.isNotBlank()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            fun act(action: String) {
                app.appScope.launch {
                    app.nightReview.act(run.id, c.id, action)
                        .onFailure { Feedback.toast(context, "Не получилось: ${it.message}") }
                }
            }
            Row {
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
}
