package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import ru.zf.pravka.data.AnalysisStore
import ru.zf.pravka.ui.Feedback

/**
 * Итоги: разборы жизненного лога, которые пишет Опус по ночам.
 *
 * Экран нарочно скучный: список разборов, тап — раскрыть текст. Вся работа
 * происходит без него — ночью уходит заявка батчем, утром здесь лежит готовый
 * текст. Кнопки сверху нужны на случай «хочу сейчас» и для первого запуска.
 */
@Composable
internal fun ItogiTab(app: PravkaApp) {
    val context = LocalContext.current
    val scope = app.appScope
    val reports by app.analysisStore.reportsFlow.collectAsState()
    val nightly by app.settings.analysisNightlyFlow.collectAsState(initial = true)
    val savedContext by app.settings.analysisContextFlow.collectAsState(initial = "")
    var busy by remember { mutableStateOf(false) }
    val patterns by app.analysisStore.patternsFlow.collectAsState()
    var openId by remember { mutableStateOf<Long?>(null) }
    var contextDraft by remember(savedContext) { mutableStateOf(savedContext) }

    LaunchedEffect(Unit) { app.analysisStore.load() }

    // force = true: кнопки «сейчас» нажимают руками, и они обязаны
    // срабатывать даже если разбор за этот период уже есть.
    fun request(what: String) {
        if (busy) return
        busy = true
        scope.launch {
            // immediate = true: кнопка «сейчас» ждёт ответа здесь и сейчас,
            // полной ценой. Батч остаётся ночному расписанию.
            val outcome = when (what) {
                "daily" -> app.analysisEngine.requestDaily(force = true, immediate = true)
                "today" -> app.analysisEngine.requestDaily(
                    date = app.analysisEngine.today(), force = true, immediate = true,
                )
                "weekly" -> app.analysisEngine.requestWeekly(force = true, immediate = true)
                else -> app.analysisEngine.requestDeep(30, force = true, immediate = true)
            }
            busy = false
            outcome.fold(
                onSuccess = {
                    Feedback.toast(app, "Готово — разбор ниже", long = true)
                },
                onFailure = { e -> Feedback.toast(app, e.message ?: "Не вышло", long = true) },
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenTitle(stringRes(R.string.tab_itogi))
            HintText(
                "Опус разбирает твой лог: структура суток, дыры, сон, тело, еда, " +
                    "семья — и ходы в конце. Все числа считает приложение, модель " +
                    "их только читает. Отправка батчем — половина цены за то, что " +
                    "ответ не нужен сию секунду."
            )
        }

        item {
            // Ночью разбор собирается сам; эти кнопки — «хочу сейчас» и
            // «проверить, что всё работает».
            Button(
                onClick = { request("daily") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сделать ежедневный разбор сейчас") }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { request("weekly") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сделать еженедельный разбор сейчас") }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { request("today") }, enabled = !busy) {
                    Text("Сегодня")
                }
                OutlinedButton(onClick = { request("deep") }, enabled = !busy) {
                    Text("За месяц")
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            HintText(
                "Кнопки считают СРАЗУ и по полной цене — минута ожидания. Ночной " +
                    "разбор уходит батчем, вдвое дешевле. Ежедневный — за вчера, " +
                    "еженедельный — за последние семь дней; «Сегодня» — день до " +
                    "текущей минуты (удобно проверить, но выводы по неполному дню слабее)."
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = nightly,
                    onCheckedChange = { on -> scope.launch { app.settings.setAnalysisNightly(on) } },
                )
                Spacer(Modifier.width(8.dp))
                Text("Ночной разбор", style = MaterialTheme.typography.bodyMedium)
            }
            HintText(
                "После четырёх утра уходит разбор вчерашнего дня, в воскресенье — " +
                    "ещё и недельный. Готовое приезжает уведомлением."
            )
        }

        item {
            OutlinedTextField(
                value = contextDraft,
                onValueChange = { contextDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Контекст периода: каникулы, отпуск, болезнь…") },
                minLines = 1,
                maxLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        app.settings.setAnalysisContext(contextDraft)
                        Feedback.toast(app, "Контекст сохранён")
                    }
                }) { Text("Сохранить контекст") }
            }
            HintText(
                "Уезжает в разбор: правило промпта — сначала контекст, потом " +
                    "диагноз, иначе каникулы читаются как развал."
            )
        }

        if (patterns.isNotEmpty()) {
            item {
                HintText(
                    "Помнит паттернов: ${patterns.size}. Они уезжают в каждый " +
                        "следующий разбор — модель обязана сказать по каждому: " +
                        "подтвердился, ослаб или исчез."
                )
            }
        }

        if (reports.isEmpty()) {
            item {
                HintText("Разборов пока нет. Нажми «За вчера» — или подожди ночи.")
            }
        }

        items(reports.size) { i ->
            val report = reports[i]
            ReportCard(
                report = report,
                expanded = openId == report.id,
                onToggle = { openId = if (openId == report.id) null else report.id },
                onShare = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, report.text)
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, report.title())
                                },
                                "Разбор",
                            )
                        )
                    }
                },
                onDelete = { scope.launch { app.analysisStore.delete(report.id) } },
            )
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ReportCard(
    report: AnalysisStore.Report,
    expanded: Boolean,
    onToggle: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onToggle)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                report.title(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    report.pending -> "ждёт"
                    report.ready -> SimpleDateFormat("dd.MM HH:mm", Locale.US)
                        .format(Date(report.createdAt))
                    else -> "не вышло"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (report.pending) {
            Spacer(Modifier.height(4.dp))
            HintText("Батч считается. Обычно минуты, по договору — до суток.")
        }
        if (report.error.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                report.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (report.ready) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (expanded) report.text else report.text.take(180).trim() + "…",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            HintText(
                "${report.tokensIn / 1000} тыс. вход · ${report.tokensOut} выход · " +
                    String.format(Locale.US, "%.3f", report.costUsd) + " USD (батч, −50%)"
            )
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onShare) { Text("Поделиться") }
                    TextButton(onClick = onDelete) { Text("Удалить") }
                }
            }
        }
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
