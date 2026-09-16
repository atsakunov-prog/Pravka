package ru.zf.pravka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.TranscriptionLog
import ru.zf.pravka.data.dayStartMs
import ru.zf.pravka.target.PlainTextTarget
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperLabel

// Вкладка «Правка»: текстбокс для чужого текста, нерасшифрованные записи,
// восстановленный черновик, последние расшифровки. Владелец (15.09.2026):
// «наверху над всеми
// расшифровками должны появляться записи, которые не расшифровались — с утра
// кнопкой расшифровать; не должно быть бесконечной ленты — последние, а внизу
// кнопка „показать всё“; выгрузки — в статистику». Название и служебные значки
// рисует общая шапка (ui/Frame.kt), здесь только содержимое.

/** Сколько расшифровок показывать, пока не нажали «Показать всё». */
private const val RECENT_TAKES = 20

@Composable
internal fun PravkaTab(app: PravkaApp, serviceEnabled: Boolean) {
    val context = LocalContext.current
    val transcriptionLog = app.transcriptionLog
    val liveDraft = app.liveDraft
    var showAll by remember { mutableStateOf(false) }
    // Reading (and JSON-parsing) these files is real disk work; doing it during
    // composition blocked the first frame of the tab.
    var log by remember { mutableStateOf<List<TranscriptionLog.Entry>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showAll) {
        val limit = if (showAll) 2000 else RECENT_TAKES
        val loaded = withContext(Dispatchers.IO) {
            transcriptionLog.readLast(limit + 1) to liveDraft.read()
        }
        hasMore = loaded.first.size > limit
        log = loaded.first.take(limit)
        draft = loaded.second
    }
    val ruLoc = remember { Locale.forLanguageTag("ru") }

    fun copy(text: String) {
        putClipboard(context, text)
        Feedback.toast(context, context.getString(R.string.transcript_copied))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Текстбокс для чужого текста — самым первым: владелец (16.09.2026)
        // «наверху должен быть текстбокс… над всеми правками».
        item { CleanBox(app) }

        // Записи, которые не расшифровались: то, что ждёт действия, — «с утра
        // кнопкой расшифровать». Пусто — раздела нет.
        item { RecordingsSection(app.recordings, serviceEnabled) }

        // Recovery: text from a Google take that was interrupted before it
        // could be inserted (phone died / app killed mid-dictation).
        draft?.let { d ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            stringResource(R.string.draft_header),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(d, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { copy(d) }) { Text(stringResource(R.string.draft_copy)) }
                            TextButton(onClick = { liveDraft.clear(); draft = null }) {
                                Text(stringResource(R.string.draft_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        item { SectionLabel(if (showAll) "все расшифровки" else "последние расшифровки") }
        if (log.isEmpty()) {
            item { Text(stringResource(R.string.transcripts_empty), style = MaterialTheme.typography.bodyMedium) }
        }
        items(log, key = { it.ts + it.chars }) { entry ->
            TranscriptCard(entry, ruLoc, onCopy = { copy(entry.text) })
        }
        if (hasMore && !showAll) {
            item {
                OutlinedButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Показать всё")
                }
            }
        }
    }
}

private fun putClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Правка", text))
}

// ---------------------------------------------------------------------------
// Текстбокс для чужого текста. Владелец (16.09.2026): «наверху должен быть
// текстбокс, в который можно скопировать текст, он его вычистит моделью и
// скопирует в конце в буфер обмена». Тот же движок и тот же режим CLEAN, что
// у кнопки «П»: словарь, история, статистика и деньги считаются как обычно;
// цель — PlainTextTarget, и на Applied движок сам кладёт результат в буфер.
// Состояние живёт вне композиции: запрос идёт секунды, владелец за это время
// может уйти на другую вкладку — вернувшись, он должен увидеть результат, а
// не пустое поле. Запрос — в appScope по той же причине: уход с экрана не
// должен обрывать работу, за которую уже заплачено.
// ---------------------------------------------------------------------------

private object CleanBoxState {
    val text = mutableStateOf("")
    val result = mutableStateOf<String?>(null)
    val streaming = mutableStateOf("")
    val error = mutableStateOf<String?>(null)
    val busy = mutableStateOf(false)
}

@Composable
private fun CleanBox(app: PravkaApp) {
    val context = LocalContext.current
    var text by CleanBoxState.text
    var result by CleanBoxState.result
    var streaming by CleanBoxState.streaming
    var error by CleanBoxState.error
    var busy by CleanBoxState.busy

    fun clipboardText(): String {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }

    fun clean() {
        val input = text.trim()
        if (input.isEmpty() || busy) return
        busy = true
        result = null
        error = null
        streaming = ""
        app.appScope.launch {
            val target = PlainTextTarget(input, explicit = true)
            // Дельты приходят с IO-потока; состояние Compose трогаем на главном,
            // и не чаще раза в 100 мс — пересобирать длинный текст на каждый
            // токен незачем.
            var lastAt = 0L
            val outcome = runCatching {
                app.engine.proofread(target, ProofreadMode.CLEAN, onDelta = { partial ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastAt >= 100) {
                        lastAt = now
                        app.appScope.launch { CleanBoxState.streaming.value = partial }
                    }
                })
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                ProofreadEngine.Outcome.Failed(e.message ?: "Неизвестная ошибка")
            }
            when (outcome) {
                is ProofreadEngine.Outcome.Applied -> {
                    // Буфер уже заполнил движок (clipboardFallback на Applied).
                    result = target.result ?: input
                    Feedback.toast(context, "Готово — результат в буфере обмена")
                }
                is ProofreadEngine.Outcome.CopiedToClipboard -> {
                    result = target.result ?: input
                    Feedback.toast(context, "Готово — результат в буфере обмена")
                }
                is ProofreadEngine.Outcome.Unchanged -> {
                    // Движок на «без изменений» буфер не трогает, а владелец ждёт
                    // текст в буфере в любом случае.
                    result = input
                    putClipboard(context, input)
                    Feedback.toast(context, "Текст уже чистый — положил в буфер как есть")
                }
                ProofreadEngine.Outcome.Rejected -> error = "Пустой текст — причёсывать нечего."
                is ProofreadEngine.Outcome.Failed -> error = outcome.message
            }
            busy = false
        }
    }

    PaperCard(label = "причесать текст") {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 12,
            enabled = !busy,
            placeholder = { Text("Вставь текст — причешу и положу в буфер") },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val fromClip = clipboardText()
                    if (fromClip.isBlank()) {
                        Feedback.toast(context, "Буфер обмена пуст")
                    } else {
                        text = fromClip
                        result = null
                        error = null
                    }
                },
            ) { Text("Из буфера") }
            Button(onClick = { clean() }, enabled = text.isNotBlank() && !busy) {
                Text(if (busy) "Правлю…" else "Причесать")
            }
            Spacer(Modifier.weight(1f))
            if (text.isNotEmpty() && !busy) {
                TextButton(onClick = { text = ""; result = null; error = null; streaming = "" }) {
                    Text("Очистить")
                }
            }
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            if (streaming.isBlank()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text(
                    streaming,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        result?.let { r ->
            Spacer(Modifier.height(10.dp))
            PaperLabel("результат — уже в буфере")
            SelectionContainer {
                Text(r, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                putClipboard(context, r)
                Feedback.toast(context, context.getString(R.string.transcript_copied))
            }) { Text("Скопировать ещё раз") }
        }
    }
}

private fun engineLabel(engine: String): String = when (engine) {
    Settings.SPEECH_GOOGLE -> "Google"
    Settings.SPEECH_GOOGLE_NET -> "Google (сеть)"
    Settings.SPEECH_WHISPER_SMALL -> "Whisper small"
    Settings.SPEECH_WHISPER_BASE -> "Whisper base"
    else -> engine
}

/** Одна расшифровка: строка метрик, время, текст; тап — текст в буфер. */
@Composable
private fun TranscriptCard(entry: TranscriptionLog.Entry, ruLoc: Locale, onCopy: () -> Unit) {
    Card(
        onClick = { if (entry.text.isNotBlank()) onCopy() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            // Metrics line: engine · audio · transcription time · chars.
            val meta = buildString {
                append(entry.ts.replace('T', ' ').substring(5, 16))
                append(" · ")
                append(engineLabel(entry.engine))
                append(" · ")
                append(String.format(ruLoc, "%.1f", entry.audioMs / 1000.0)).append(" с")
                // Whisper reports its transcription time; the Google
                // live engine is realtime, so it logs 0 - skip it there.
                if (entry.transcribeMs > 0) {
                    append(" · расшифровка ")
                    append(String.format(ruLoc, "%.1f", entry.transcribeMs / 1000.0)).append(" с")
                }
                append(" · ")
                append(entry.chars).append(" симв.")
            }
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = if (!entry.ok) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (entry.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(entry.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Статистика диктовки: сколько наговорил, сколько поправлено, метрики движка.
// Открывается значком статистики в шапке Правки. Деньги здесь не живут — у
// них свой экран за долларом, общий на всё приложение.
// ---------------------------------------------------------------------------

private class TakeStats(
    val count: Int,
    val audioMs: Long,
    val chars: Long,
    val words: Long,
    val failed: Int,
) {
    val avgChars: Int get() = if (count > 0) (chars / count).toInt() else 0
}

private fun statsOf(list: List<TranscriptionLog.Entry>) = TakeStats(
    count = list.size,
    audioMs = list.sumOf { it.audioMs },
    chars = list.sumOf { it.chars.toLong() },
    words = list.sumOf { it.words.toLong() },
    failed = list.count { !it.ok },
)

private fun fmtMinutes(ms: Long): String {
    val min = (ms + 30_000L) / 60_000L
    return if (min >= 60) "${min / 60} ч ${min % 60} м" else "$min м"
}

@Composable
internal fun DictationStatsTab(app: PravkaApp, exportRequested: Boolean, onExportHandled: () -> Unit) {
    val context = LocalContext.current
    val snapshot by app.stats.snapshotFlow.collectAsState(initial = null)
    val ruLoc = remember { Locale.forLanguageTag("ru") }
    var today by remember { mutableStateOf<TakeStats?>(null) }
    var week by remember { mutableStateOf<TakeStats?>(null) }
    var month by remember { mutableStateOf<TakeStats?>(null) }
    var all by remember { mutableStateOf<TakeStats?>(null) }
    var byEngine by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val entries = app.transcriptionLog.readLast(100_000)
            val now = System.currentTimeMillis()
            val dayStart = dayStartMs(now)
            fun since(from: Long) = entries.filter { app.transcriptionLog.tsMillis(it) >= from }
            val t = statsOf(since(dayStart))
            val w = statsOf(since(dayStart - 6 * 86_400_000L))
            val m = statsOf(since(dayStart - 29 * 86_400_000L))
            val a = statsOf(entries)
            val e = entries.groupBy { engineLabel(it.engine) }.map { it.key to it.value.size }
                .sortedByDescending { it.second }
            today = t; week = w; month = m; all = a; byEngine = e
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PaperCard(label = "наговорено") {
            val t = today
            if (t == null) {
                PaperHint("Считаю…")
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        fmtMinutes(t.audioMs),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "сегодня · ${t.count} записей · ${"%,d".format(ruLoc, t.chars)} симв.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                for ((label, s) in listOf("7 дней" to week, "30 дней" to month, "всё время" to all)) {
                    if (s != null) {
                        StatLine(
                            label,
                            "${fmtMinutes(s.audioMs)} · ${s.count} зап. · ${"%,d".format(ruLoc, s.words)} слов",
                        )
                    }
                }
                val a = all
                if (a != null && a.count > 0) {
                    Spacer(Modifier.height(6.dp))
                    StatLine("Средняя запись", "${a.avgChars} симв.")
                    if (a.failed > 0) StatLine("Не расшифровалось", a.failed.toString())
                    if (byEngine.isNotEmpty()) {
                        StatLine("Движки", byEngine.joinToString(" · ") { "${it.first} ${it.second}" })
                    }
                }
            }
        }

        snapshot?.let { s ->
            PaperCard(label = "правки текста") {
                StatLine(stringResource(R.string.stats_total), s.total.toString())
                StatLine(stringResource(R.string.stats_clean), s.clean.toString())
                StatLine(stringResource(R.string.stats_business), s.business.toString())
                StatLine(stringResource(R.string.stats_soften), s.soften.toString())
                StatLine(stringResource(R.string.stats_unchanged), s.unchanged.toString())
                StatLine(stringResource(R.string.stats_errors), s.errors.toString())
                StatLine(stringResource(R.string.stats_chars), "%,d".format(ruLoc, s.charsProcessed))
                StatLine(stringResource(R.string.stats_tokens), "%,d / %,d".format(ruLoc, s.tokensIn, s.tokensOut))
                StatLine(
                    stringResource(R.string.stats_latency),
                    String.format(ruLoc, "%.1f с", s.averageLatencyMs / 1000.0),
                )
            }
        }
    }

    if (exportRequested) {
        DictationExportDialog(app, onDismiss = onExportHandled)
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
// Выгрузка: что и за какой период. Владелец: «если это весь лог диктовки,
// надо при нажатии выдавать окно с выбором, за какой период: за день, неделю,
// месяц, кастом». Три файла на выбор — расшифровки (JSON), метрики (CSV),
// лог событий диктовки (TXT); период — сегодня, 7 и 30 дней, всё, свой.
// ---------------------------------------------------------------------------

private enum class ExportWhat(val title: String) {
    TAKES("Расшифровки (JSON)"),
    METRICS("Метрики диктовки (CSV)"),
    EVENTS("Лог событий диктовки"),
    HISTORY("История правок (JSONL)"),
    CORRECTIONS("Правки руками: надиктовано · модель · ты (CSV)"),
    REQUESTS("Запросы к Claude (отладка)"),
}

private enum class Period(val title: String) { DAY("Сегодня"), WEEK("7 дней"), MONTH("30 дней"), ALL("Всё"), CUSTOM("Свой") }

private val customDate = SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { isLenient = false }

@Composable
private fun DictationExportDialog(app: PravkaApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var what by remember { mutableStateOf(ExportWhat.TAKES) }
    var period by remember { mutableStateOf(Period.DAY) }
    var fromText by remember { mutableStateOf(customDate.format(Date(System.currentTimeMillis() - 6 * 86_400_000L))) }
    var toText by remember { mutableStateOf(customDate.format(Date())) }
    var error by remember { mutableStateOf("") }

    fun range(): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        val dayStart = dayStartMs(now)
        return when (period) {
            Period.DAY -> dayStart to Long.MAX_VALUE
            Period.WEEK -> (dayStart - 6 * 86_400_000L) to Long.MAX_VALUE
            Period.MONTH -> (dayStart - 29 * 86_400_000L) to Long.MAX_VALUE
            Period.ALL -> 0L to Long.MAX_VALUE
            Period.CUSTOM -> {
                val from = runCatching { customDate.parse(fromText.trim())?.time }.getOrNull()
                val to = runCatching { customDate.parse(toText.trim())?.time }.getOrNull()
                if (from == null || to == null) return null
                from to (to + 86_400_000L)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выгрузить") },
        text = {
            Column {
                for (w in ExportWhat.entries) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = what == w, onClick = { what = w })
                        Text(w.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (what != ExportWhat.HISTORY && what != ExportWhat.REQUESTS) {
                    Spacer(Modifier.height(8.dp))
                    Text("Период", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in Period.entries) {
                            FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.title) })
                        }
                    }
                    if (period == Period.CUSTOM) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = fromText, onValueChange = { fromText = it },
                                label = { Text("с") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = toText, onValueChange = { toText = it },
                                label = { Text("по") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                        }
                        PaperHint("Даты как 01.09.2026, обе включительно")
                    }
                }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val r = range()
                if (r == null) { error = "Не разобрал даты"; return@Button }
                val (from, to) = r
                if (what == ExportWhat.CORRECTIONS) {
                    // shareCsvIntent — suspend: собирается в области приложения.
                    onDismiss()
                    app.appScope.launch {
                        val intent = runCatching { app.corrections.shareCsvIntent(from, to) }.getOrNull()
                        if (intent == null) Feedback.toast(context, "Не собралась")
                        else runCatching { context.startActivity(android.content.Intent.createChooser(intent, what.title)) }
                    }
                    return@Button
                }
                val intent = runCatching {
                    when (what) {
                        ExportWhat.TAKES ->
                            if (period == Period.ALL) app.transcriptionLog.shareJsonIntent()
                            else app.transcriptionLog.shareJsonIntent(from, to)
                        ExportWhat.METRICS -> app.transcriptionLog.shareMetricsCsvIntent(from, to)
                        ExportWhat.EVENTS ->
                            if (period == Period.ALL) app.eventLog.shareIntent()
                            else app.eventLog.shareRangeIntent(from, to)
                        ExportWhat.HISTORY -> app.historyLog.shareIntent()
                        ExportWhat.REQUESTS -> app.requestLog.shareIntent()
                        ExportWhat.CORRECTIONS -> throw IllegalStateException()
                    }
                }.getOrElse { e -> error = "Не собралась: ${e.message}"; return@Button }
                onDismiss()
                runCatching {
                    context.startActivity(android.content.Intent.createChooser(intent, what.title))
                }.onFailure { Feedback.toast(context, "Файл пуст") }
            }) { Text("Выгрузить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
