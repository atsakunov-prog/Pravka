package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
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
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.ThinkingLine
import ru.zf.pravka.ui.PillAction
import ru.zf.pravka.ui.VoiceInput
import ru.zf.pravka.ui.bevel
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperLabel
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.SheetAction

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
        contentPadding = ScreenPad.Padding,
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        // Текстбокс для чужого текста — самым первым: владелец (16.09.2026)
        // «наверху должен быть текстбокс… над всеми правками».
        item { CleanBox(app) }

        // Записи, которые не расшифровались: то, что ждёт действия, — «с утра
        // кнопкой расшифровать». Пусто — раздела нет.
        item { RecordingsSection(app.recordings, serviceEnabled) }

        // Recovery: text from a Google take that was interrupted before it
        // could be inserted (phone died / app killed mid-dictation).
        // До 24.09.2026 — голая карточка вторичного цвета, единственная такая
        // во вкладке; теперь та же плашка, что у всех, с кнопками набора.
        draft?.let { d ->
            item {
                PaperCard(
                    label = "черновик после сбоя",
                    info = stringResource(R.string.draft_header) + ". Диктовка оборвалась до " +
                        "вставки — сел телефон или система закрыла приложение посреди тейка, — " +
                        "а текст успел сохраниться. Скопируй его или удали.",
                ) {
                    Text(d, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PaperTextButton(
                            stringResource(R.string.draft_delete),
                            icon = Glyphs.Delete,
                            color = MaterialTheme.colorScheme.error,
                            onClick = { liveDraft.clear(); draft = null },
                        )
                        Spacer(Modifier.weight(1f))
                        PaperButton(
                            stringResource(R.string.draft_copy),
                            icon = Glyphs.Copy,
                            primary = true,
                            onClick = { copy(d) },
                        )
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
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PaperTextButton("Показать всё", icon = Glyphs.ChevronDown, onClick = { showAll = true })
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
    /** Что ушло на чистку — пузырём справа; поле пилюли после отправки пустое, как у Gemini. */
    val sent = mutableStateOf("")
}

@Composable
private fun CleanBox(app: PravkaApp) {
    val context = LocalContext.current
    var text by CleanBoxState.text
    var result by CleanBoxState.result
    var streaming by CleanBoxState.streaming
    var error by CleanBoxState.error
    var busy by CleanBoxState.busy
    var sent by CleanBoxState.sent

    fun clipboardText(): String {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }

    fun clean() {
        val input = text.trim()
        if (input.isEmpty() || busy) return
        busy = true
        sent = input
        text = ""
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

    // Версия 3, второй заход (26.09.2026, вечер): «в Правке там написано
    // „вставь текст — причешу, положу в буфер“ — и вот это и должно быть в
    // обычной плашке, такой же, как вылезает, когда нажимаем на кнопку».
    // Поле — сама пилюля наверху вкладки: слева «из буфера» (или ✕, когда
    // текст есть), кружок — «причесать». Отправленное уходит пузырём в
    // плашку ниже, под ним искры с секундами и ответ; пилюля снова пустая.
    Column(verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap)) {
        VoiceInput(
            value = text,
            onValueChange = { text = it },
            placeholder = "Вставь текст — причешу",
            onSend = { clean() },
            sendEnabled = text.isNotBlank() && !busy,
            enabled = !busy,
            maxLines = 8,
            busy = busy,
            leading = {
                if (text.isEmpty()) {
                    PillAction(Glyphs.Paste, "вставить из буфера", onClick = {
                        val fromClip = clipboardText()
                        if (fromClip.isBlank()) Feedback.toast(context, "Буфер обмена пуст")
                        else text = fromClip
                    })
                } else {
                    PillAction(Glyphs.Close, "очистить", onClick = { text = "" })
                }
            },
        )
        if (busy || result != null || error != null) {
            PaperCard(
                label = if (result != null) "результат — уже в буфере" else "причесать текст",
                trailing = if (!busy) {
                    { PaperTextButton("Убрать", onClick = { sent = ""; result = null; error = null; streaming = "" }) }
                } else null,
            ) {
                // Тап по пузырю — исходник снова в пилюлю: поправить и причесать ещё раз.
                if (sent.isNotBlank()) {
                    SourceBubble(sent, onEdit = if (busy) null else ({ text = sent; result = null; error = null; streaming = "" }))
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    ThinkingLine("Причёсываю")
                    if (streaming.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        StreamingText(streaming)
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                result?.let { r ->
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer {
                        Text(r, style = MaterialTheme.typography.bodyMedium)
                    }
                    PaperTextButton("Скопировать ещё раз", icon = Glyphs.Copy, onClick = {
                        putClipboard(context, r)
                        Feedback.toast(context, context.getString(R.string.transcript_copied))
                    })
                }
            }
        }
    }
}

/**
 * Вставленный текст пузырём справа — реплика владельца, как у Gemini
 * (версия 3). Стекло на просвет, скругление у «хвоста» меньше. Длинное
 * сворачивается до восьми строк: читать исходник целиком незачем, он в поле
 * по тапу. [onEdit] = null — тап не действует (идёт правка).
 */
@Composable
private fun SourceBubble(text: String, onEdit: (() -> Unit)?) {
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.08f))
                .bevel(shape)
                .then(if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Ответ, который ещё пишется: текст и каретка краской режима в его конце. */
@Composable
private fun StreamingText(text: String) {
    val caret = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = caret, fontWeight = FontWeight.Bold)) { append(" ▍") }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun engineLabel(engine: String): String = when (engine) {
    Settings.SPEECH_GOOGLE -> "Google"
    Settings.SPEECH_GOOGLE_NET -> "Google (сеть)"
    Settings.SPEECH_WHISPER_SMALL -> "Whisper small"
    Settings.SPEECH_WHISPER_BASE -> "Whisper base"
    else -> engine
}

/**
 * Плашка, которую можно нажать целиком. В наборе у `PaperCard` нажатия нет,
 * а расшифровка копируется тапом по всей плашке с июля — отнимать это
 * ради значка было бы шагом назад (24.09.2026). Рябь обрезана по форме
 * плашки: без подписи плашка — ровно её `Card`.
 */
@Composable
private fun TapPaperCard(onClick: () -> Unit, enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        PaperCard(content = content)
    }
}

/** Одна расшифровка: строка метрик, время, текст; тап — текст в буфер. */
@Composable
private fun TranscriptCard(entry: TranscriptionLog.Entry, ruLoc: Locale, onCopy: () -> Unit) {
    val canCopy = entry.text.isNotBlank()
    TapPaperCard(onClick = { if (canCopy) onCopy() }, enabled = canCopy) {
        Column {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!entry.ok) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Значок — подсказка, что тап копирует; делает то же самое.
                if (canCopy) GlyphButton(Glyphs.Copy, "скопировать", onClick = onCopy, size = 30.dp)
            }
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
            .padding(ScreenPad.Padding),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        PaperCard(
            label = "наговорено",
            info = "Минуты — длина записей по журналу расшифровок, не время у телефона. " +
                "«Сегодня» — с полуночи; 7 и 30 дней — вместе с сегодняшним. Средняя " +
                "запись и «не расшифровалось» — за всё время, движки — сколько записей " +
                "распознал каждый.",
        ) {
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
            PaperCard(
                label = "правки текста",
                info = "Правки текста по видам — чистка, деловой стиль, мягче — и то, что " +
                    "модель вернула без изменений. Деньги здесь не живут: у них свой " +
                    "экран за «$» в шапке, общий на всё приложение.",
            ) {
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
internal fun DictationExportDialog(app: PravkaApp, onDismiss: () -> Unit) {
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

    fun export() {
        val r = range()
        if (r == null) { error = "Не разобрал даты"; return }
        val (from, to) = r
        if (what == ExportWhat.CORRECTIONS) {
            // shareCsvIntent — suspend: собирается в области приложения.
            onDismiss()
            app.appScope.launch {
                val intent = runCatching { app.corrections.shareCsvIntent(from, to) }.getOrNull()
                if (intent == null) Feedback.toast(context, "Не собралась")
                else runCatching { context.startActivity(android.content.Intent.createChooser(intent, what.title)) }
            }
            return
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
        }.getOrElse { e -> error = "Не собралась: ${e.message}"; return }
        onDismiss()
        runCatching {
            context.startActivity(android.content.Intent.createChooser(intent, what.title))
        }.onFailure { Feedback.toast(context, "Файл пуст") }
    }

    // Лист вместо AlertDialog (24.09.2026): «Выгрузить» — справа под пальцем,
    // «Отмена» не нужна — закрывают крестик и свайп, выгрузка ничего не портит.
    PaperAlert(
        onDismiss = onDismiss,
        title = "Выгрузить",
        icon = Glyphs.Export,
        confirm = SheetAction("Выгрузить", icon = Glyphs.Export, onClick = { export() }),
    ) {
        Column {
            for (w in ExportWhat.entries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { what = w },
                ) {
                    RadioButton(selected = what == w, onClick = { what = w })
                    Text(w.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (what != ExportWhat.HISTORY && what != ExportWhat.REQUESTS) {
            PaperLabel("период")
            ChipRow {
                for (p in Period.entries) {
                    PaperChip(p.title, selected = period == p, onClick = { period = p })
                }
            }
            if (period == Period.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperField(
                        value = fromText, onValueChange = { fromText = it },
                        label = "с", modifier = Modifier.weight(1f),
                    )
                    PaperField(
                        value = toText, onValueChange = { toText = it },
                        label = "по", modifier = Modifier.weight(1f),
                    )
                }
                PaperHint("Даты как 01.09.2026, обе включительно")
            }
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
