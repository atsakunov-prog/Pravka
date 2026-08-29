package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import ru.zf.pravka.ui.MarkdownText

/**
 * Паттерны: повторы, которые Опус ищет по всему логу каждую ночь.
 *
 * Экран был списком ночных разборов текстом. Владелец их снял — «очень плохо
 * работает тема с итогами, но замечательно работает тема с паттернами» — и
 * теперь разборы он делает сам в чате по выгруженному CSV, а здесь остаётся
 * то единственное, что приложение умеет лучше чата: методично, каждую ночь,
 * искать повторы и помнить их годами вместе с ЕГО вердиктом по каждому.
 *
 * Список разборов внизу остался журналом запусков: когда искали, сколько
 * нашли, во что обошлось. Читать там нечего — крестик и есть его назначение.
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
    // Список паттернов растёт без потолка, и он не должен отодвигать разборы
    // на два экрана вниз. Раскрыт, пока есть неразобранные: они и есть
    // просьба к владельцу. Разобрал все — складка закрывается сама.
    var patternsOpen by remember(patterns.count { !it.judged } > 0) {
        mutableStateOf(patterns.any { !it.judged })
    }
    var rejectedOpen by remember { mutableStateOf(false) }
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
            val date =
                if (what == "today") app.analysisEngine.today() else app.analysisEngine.yesterday()
            val outcome = app.analysisEngine.huntPatterns(date, force = true, immediate = true)
            busy = false
            outcome.fold(
                onSuccess = { Feedback.toast(app, "Готово — паттерны обновлены", long = true) },
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
                "Каждую ночь Опус прогоняет весь лог и ищет повторы: лента и еда " +
                    "из Правки, сон, HRV и тренировки с часов, план из intervals, " +
                    "дела из Todoist, дневник в Notion и расход на модель — плюс " +
                    "твои последние три недели по суткам, потому что внутри одного " +
                    "дня повтора не бывает. Ответ — только строки ниже, текстов он " +
                    "больше не пишет. Разбор ты делаешь в чате: «Ещё → Выгрузки», " +
                    "там CSV и кнопка «Скопировать запрос для чата» с этими же " +
                    "паттернами."
            )
        }

        item {
            // Ночью разбор собирается сам; эти кнопки — «хочу сейчас» и
            // «проверить, что всё работает».
            Button(
                onClick = { request("yesterday") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Поискать паттерны сейчас") }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { request("today") }, enabled = !busy) {
                    Text("Считая сегодня")
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (busy) {
                // Опус думает минутами, и молчащий экран читается как зависший.
                // Уйти со вкладки теперь можно: сборка данных ушла с главного
                // потока, а запрос живёт в appScope и переживёт уход.
                HintText(
                    "Ищет. Опус думает несколько минут — можно уйти на другую " +
                        "вкладку, паттерны обновятся сами."
                )
            }
            HintText(
                "Кнопка считает СРАЗУ и по полной цене. Ночью то же самое уходит " +
                    "батчем, вдвое дешевле. Ищет по вчерашнему дню и трём неделям " +
                    "вокруг; «Считая сегодня» добавляет незакрытый день — полезно " +
                    "проверить, что всё работает."
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
            // Несудённые сверху: это единственное, что просит его внимания.
            // Отклонённые — вниз и под отдельную складку: они остались только
            // чтобы модель не предлагала их снова.
            val fresh = patterns.filter { !it.judged }
            val accepted = patterns.filter { it.accepted }
            val rejected = patterns.filter { it.rejected }
            val shown = fresh + accepted

            item {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { patternsOpen = !patternsOpen }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (patternsOpen) "▾" else "▸",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Найдено · ${patterns.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (fresh.isEmpty()) "все разобраны" else "новых ${fresh.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fresh.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (patternsOpen) {
                item {
                    HintText(
                        "Это нашла модель — но она видит цифры, а не тебя. Скажи по " +
                            "каждому «про меня» или «не про меня»: твой ответ уезжает " +
                            "в следующий разбор и весит больше её уверенности. " +
                            "Отклонённый не вернётся, пока не появятся новые точки. " +
                            "Тап по той же кнопке снимает ответ."
                    )
                }
                items(shown.size) { i ->
                    val pattern = shown[i]
                    PatternCard(
                        pattern = pattern,
                        onVerdict = { verdict ->
                            scope.launch {
                                app.analysisStore.setVerdict(
                                    pattern.key(), verdict, app.analysisEngine.today(),
                                )
                            }
                        },
                    )
                }
                if (rejected.isNotEmpty()) {
                    item {
                        Text(
                            (if (rejectedOpen) "▾ " else "▸ ") +
                                "отклонённые · ${rejected.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { rejectedOpen = !rejectedOpen }
                                .padding(vertical = 6.dp),
                        )
                    }
                    if (rejectedOpen) {
                        items(rejected.size) { i ->
                            val pattern = rejected[i]
                            PatternCard(
                                pattern = pattern,
                                onVerdict = { verdict ->
                                    scope.launch {
                                        app.analysisStore.setVerdict(
                                            pattern.key(), verdict, app.analysisEngine.today(),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Журнал поисков",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                HintText("Когда искали и во что обошлось. Читать нечего — крестик убирает.")
            }
        }

        if (reports.isEmpty()) {
            item {
                HintText("Ещё не искали. Нажми кнопку выше — или подожди ночи.")
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

/**
 * Паттерн с вердиктом владельца.
 *
 * Владелец: «это паттерны, которые я увидел, модель, но может быть не увидел,
 * ну может быть я не подтвержу». В этом весь смысл экрана: модель видит
 * повтор в цифрах и всё равно может ошибаться в том, что он значит, а
 * проверить это может только он. Его слово — единственная в системе оценка
 * модели человеком, и она уезжает в каждый следующий разбор.
 */
@Composable
private fun PatternCard(
    pattern: AnalysisStore.Pattern,
    onVerdict: (String) -> Unit,
) {
    val accent = when {
        pattern.accepted -> MaterialTheme.colorScheme.primary
        pattern.rejected -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.tertiary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = if (pattern.rejected) 0.18f else 0.4f,
                ),
                RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
    ) {
        Text(
            pattern.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (pattern.accepted) FontWeight.Medium else FontWeight.Normal,
            // Отклонённый не исчезает, но и не лезет в глаза: он остался,
            // чтобы модель не предлагала его снова.
            color = if (pattern.rejected) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append("точек ").append(pattern.points)
                    append(" · разборов ").append(pattern.times)
                    if (pattern.confidence.isNotBlank()) {
                        append(" · уверенность ").append(pattern.confidence)
                    }
                    append(" · с ").append(pattern.firstSeen)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (pattern.judged) {
                Text(
                    if (pattern.accepted) "про меня" else "не про меня",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VerdictButton(
                label = "Про меня",
                chosen = pattern.accepted,
                onClick = { onVerdict(AnalysisStore.VERDICT_YES) },
            )
            VerdictButton(
                label = "Не про меня",
                chosen = pattern.rejected,
                onClick = { onVerdict(AnalysisStore.VERDICT_NO) },
            )
        }
    }
}

@Composable
private fun VerdictButton(label: String, chosen: Boolean, onClick: () -> Unit) {
    if (chosen) {
        Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
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
            Column(Modifier.weight(1f)) {
                // Заголовок отвечает на «во сколько ушёл поиск и какой».
                // Владелец: «ночной разбор не виден, надо показывать, что в
                // 4:00 ушёл разбор». Время запуска стоит теперь и у неудачных
                // — по ним как раз и видно, что расписание сработало, а
                // споткнулась сеть.
                Text(
                    (if (report.source == "ночью") "Ночью " else "Вручную ") +
                        SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(report.createdAt)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "по дню " + report.from,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    report.pending -> "ждёт"
                    report.ready -> "готово"
                    else -> "не вышло"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (report.ready) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
            // Крестик виден ВСЕГДА, в том числе у «ждёт». Раньше удалить
            // можно было только раскрытый готовый разбор — а убрать нужно
            // ровно зависший, и именно он такой возможности не имел.
            Spacer(Modifier.width(4.dp))
            Text(
                "✕",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (report.pending) {
            Spacer(Modifier.height(4.dp))
            HintText(
                if (report.batchId.isNotBlank())
                    "Батч считается. Обычно минуты, по договору — до суток."
                else "Считается прямо сейчас. Если приложение закрыть — оборвётся, " +
                    "и тогда сними крестиком."
            )
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
            // Первая строка записи — «Нашёл повторов: 3», дальше сами
            // строки. В свёрнутом виде нужна первая, в раскрытом — все.
            Text(
                if (expanded) report.text else report.text.lineSequence().first().trim(),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            HintText(
                "${report.tokensIn / 1000} тыс. вход · ${report.tokensOut} выход · " +
                    String.format(Locale.US, "%.3f", report.costUsd) + " USD" +
                    (if (report.source == "ночью") " (батч, −50%)" else "")
            )
            if (expanded) {
                TextButton(onClick = onShare) { Text("Поделиться") }
            }
        }
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
