package ru.zf.pravka

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
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.NightReviewPolicy
import ru.zf.pravka.core.ShadowPolicy
import ru.zf.pravka.data.NightReviewStore
import ru.zf.pravka.ui.Feedback

// «Ещё → Разборы» (16.09.2026): ночной разбор диктовок и тень второй модели —
// тумблеры, ручной запуск, отчёты прогонов с кнопками под каждым изменением и
// текстбокс «ответить по-человечески». Владелец: «каждое утро и каждую
// пятницу записывал, что он добавил… а я мог нормальным языком в текстбоксе
// ответить, что надо переправить обратно»; и в тот же день — «это не в
// Ещё → Разборы, как я просил»: первая версия лежала в настройках Правки.

private val dayTime = SimpleDateFormat("EEE dd.MM HH:mm", Locale("ru"))

private fun stageLabel(stage: String): String = when (stage) {
    "analysis" -> "первый проход"
    "check" -> "проверка"
    "audit" -> "согласование"
    ShadowPolicy.STAGE_CLEAN -> "вторая модель чистит"
    ShadowPolicy.STAGE_JUDGE -> "судья сравнивает"
    else -> stage
}

@Composable
internal fun ReviewsTab(app: PravkaApp) {
    val context = LocalContext.current
    val scope = app.appScope
    val settings = app.settings
    val enabled by settings.nightReviewEnabledFlow.collectAsState(initial = true)
    val shadowOn by settings.shadowRunEnabledFlow.collectAsState(initial = true)
    val hour by settings.nightReviewHourFlow.collectAsState(initial = 3)
    val runs by app.nightReviewStore.runsFlow.collectAsState()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.nightReviewStore.all() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(label = "Ночной разбор") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { on -> scope.launch { settings.setNightReviewEnabled(on) } })
                Spacer(Modifier.width(8.dp))
                Text("Разбирать журналы каждую ночь", style = MaterialTheme.typography.bodyMedium)
            }
            HintText(
                "Три прохода Fable 5.1 батчем (вдвое дешевле): первый читает чистки и правки руками " +
                    "за сутки плюс счётчики повторов за неделю, словарь и правила целиком; второй " +
                    "проверяет каждую находку; третий смотрит на итог против прошлых решений. " +
                    "Высоковероятное и подтверждённое применяется само, остальное лежит " +
                    "предложениями. Утром — отчёт, по пятницам — за неделю. Промпт разбор не трогает. " +
                    "Модели и усилие — в настройках, группа «Модели»."
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
            val running = runs.any { it.active && !it.isShadow }
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
            runs.firstOrNull { it.active && !it.isShadow }?.let { run -> ActiveLine(run) }
        }

        SectionCard(label = "Тень второй модели") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = shadowOn, onCheckedChange = { on -> scope.launch { settings.setShadowRunEnabled(on) } })
                Spacer(Modifier.width(8.dp))
                Text("Сравнивать дневную модель со второй", style = MaterialTheme.typography.bodyMedium)
            }
            HintText(
                "В тот же час вторая модель (заводское — Опус 5) чистит батчем те же диктовки, что днём " +
                    "чистила дневная (Сонет 5), а Fable 5.1 слепым судьёй сравнивает различающиеся пары, " +
                    "не зная, где чья. Первая ночь берёт до 200 диктовок за месяц, дальше — за сутки. " +
                    "Утром здесь: счёт, из-за чего кто проигрывал, во сколько это обошлось бы днём, примеры. " +
                    "Само ничего не меняет — переход на другую модель за тобой, в группе «Модели»."
            )
            Spacer(Modifier.height(6.dp))
            val shadowRunning = runs.any { it.active && it.isShadow }
            Button(
                enabled = !busy && !shadowRunning,
                onClick = {
                    busy = true
                    scope.launch {
                        app.shadowRun.start(manual = true)
                            .onSuccess { Feedback.toast(context, if (it.active) "Отправлено батчем: сначала чистка, потом судья — обычно в течение часа-двух" else it.summary) }
                            .onFailure { Feedback.toast(context, "Не запустилась: ${it.message}") }
                        busy = false
                    }
                },
            ) { Text("Тень сейчас") }
            runs.firstOrNull { it.active && it.isShadow }?.let { run -> ActiveLine(run) }
        }

        for (run in runs.sortedByDescending { it.startedAt }.take(12)) {
            RunCard(app, run)
        }
    }
}

@Composable
private fun ActiveLine(run: NightReviewStore.Run) {
    Spacer(Modifier.height(4.dp))
    Text(
        "Идёт: ${stageLabel(run.stage)} · отправлено ${dayTime.format(Date(run.startedAt))} · статус спрашивается раз в 10 минут",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RunCard(app: PravkaApp, run: NightReviewStore.Run) {
    val context = LocalContext.current
    var replyText by remember(run.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val kindLabel = when {
        run.isShadow -> "тень"
        run.kind == NightReviewPolicy.WEEKLY -> "неделя"
        else -> "сутки"
    }
    SectionCard(label = "${dayTime.format(Date(run.startedAt))} · $kindLabel${if (run.manual) " · вручную" else ""}") {
        val status = when {
            run.active -> "идёт ${stageLabel(run.stage)}"
            run.stage == "failed" -> "не удался"
            else -> "готово" + if (run.costUsd > 0) " · $" + "%.3f".format(Locale.US, run.costUsd) else ""
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (run.error.isNotBlank()) {
            // Причина целиком (правило 6), но одной строкой на ошибку — не простынёй.
            Text(run.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (run.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(run.summary, style = MaterialTheme.typography.bodyMedium)
        }
        val examples = run.changes.filter { it.kind == ShadowPolicy.KIND }
        if (examples.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Примеры", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        for (c in run.changes) {
            if (c.kind == ShadowPolicy.KIND) ShadowRow(c) else ChangeRow(app, run, c)
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
            // Пока прогон идёт, кнопок нет: следующий проход перепишет список
            // изменений своей копией и решение владельца потерялось бы.
            if (!run.active) Row {
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
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "$head — ${c.verdictWhy}" + (if (c.why.isNotBlank()) " (${c.why})" else ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row {
            TextButton(onClick = { open = !open }) { Text(if (open) "Скрыть тексты" else "Показать тексты") }
        }
        if (open) {
            Text("Надиктовано: ${c.from}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text("${c.mode}: ${c.to}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(3.dp))
            Text("${c.toMode}: ${c.note}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
