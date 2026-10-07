package ru.zf.pravka

import androidx.compose.foundation.layout.fillMaxHeight
import ru.zf.pravka.ui.glass
import ru.zf.pravka.ui.bottomFade
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.TakeHealth
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
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.fadingScroll
import ru.zf.pravka.ui.scrollFade

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
    // Чей звук ещё лежит (`TakeAudio`): проверка файлов — диск, не композиция.
    var withAudio by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Переразбор сохранённого звука (30.09.2026): что идёт, насколько, итог.
    var replaying by remember { mutableStateOf<String?>(null) }
    var replayShare by remember { mutableStateOf(0f) }
    var replayOut by remember { mutableStateOf<ReplayOut?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(showAll, reload) {
        val limit = if (showAll) 2000 else RECENT_TAKES
        val loaded = withContext(Dispatchers.IO) {
            val entries = transcriptionLog.readLast(limit + 1)
            val audio = entries.mapNotNull { e -> e.audio?.takeIf { app.takeAudio.file(it) != null } }.toSet()
            Triple(entries, liveDraft.read(), audio)
        }
        hasMore = loaded.first.size > limit
        log = loaded.first.take(limit)
        draft = loaded.second
        withAudio = loaded.third
    }
    val ruLoc = remember { Locale.forLanguageTag("ru") }

    fun copy(text: String) {
        putClipboard(context, text)
        Feedback.toast(context, context.getString(R.string.transcript_copied))
    }

    // Разобрать фразу заново из её звука (владелец, 30.09.2026: «если на него
    // нажимаешь, то просто ещё раз разбирается эта фраза»): тем же путём, с тем
    // же словарём, что живой тейк; итог — в лист и отдельной строкой в журнал.
    fun replay(entry: TranscriptionLog.Entry) {
        if (replaying != null) return
        val name = entry.audio ?: return
        replaying = name
        replayShare = 0f
        app.appScope.launch {
            val file = withContext(Dispatchers.IO) { app.takeAudio.file(name) }
            if (file == null) {
                replaying = null
                withAudio = withAudio - name
                Feedback.toast(context, "Звука этой фразы уже нет — ушёл, чтобы уложиться в 300 МБ")
                return@launch
            }
            val network = app.settings.speechNetworkFlow.first()
            val formatting = app.settings.speechFormattingFlow.first()
            val biasing = if (app.settings.speechBiasingFlow.first()) {
                runCatching {
                    val store = app.dictionaryStore
                    ru.zf.pravka.core.BiasingList.build(store.all(), isSeed = store::isSeed).strings
                }.getOrDefault(emptyList())
            } else emptyList()
            val job = ru.zf.pravka.provider.SpeechReplay(
                context.applicationContext, file, network, biasing, formatting,
                log = { line -> app.eventLog.add(line) },
            )
            job.start(onProgress = { replayShare = it }) { result ->
                replaying = null
                val text = result.getOrNull().orEmpty()
                val error = result.exceptionOrNull()?.message
                app.transcriptionLog.append(
                    engine = Settings.SPEECH_GOOGLE_REPLAY,
                    audioMs = job.audioMs,
                    transcribeMs = job.elapsedMs,
                    text = text,
                    error = error ?: if (text.isBlank()) "пустой результат" else null,
                    audio = name,
                )
                replayOut = ReplayOut(was = entry.text, now = text, error = error, audioMs = job.audioMs, tookMs = job.elapsedMs)
                reload++
            }
        }
    }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    // Разворот (DESIGN §13): слева «Последнее», записи и черновик, справа —
    // расшифровки. [side]: 0 — всё одной лентой, 1 — левая, 2 — правая.
    val wide = ru.zf.pravka.ui.twoPane()
    val rightState = rememberLazyListState()
    val body: androidx.compose.foundation.lazy.LazyListScope.(Int) -> Unit = { side ->
        val left = side != 2
        val right = side != 1
        if (left) {
        // «Последнее» (Правка 4.0, `screens/12`): что сказано и что вышло —
        // свежая чистка из нижней строки или последняя чистка «П».
        item(key = "last") { LastCard(app) }

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

        }
        if (right) {
        item(key = "tr:h") {
            // «24 сегодня · 18 м голоса» — сколько тейков сегодня и сколько в них голоса.
            val dayFrom = remember(log) { dayStartMs(System.currentTimeMillis()) }
            val todays = log.filter { transcriptionLog.tsMillis(it) >= dayFrom }
            ru.zf.pravka.ui.SectionHeader(
                if (showAll) "все расшифровки" else "последние расшифровки",
                trailing = if (todays.isEmpty()) null
                else "${todays.size} сегодня · " + ru.zf.pravka.core.Fmt.durMs(todays.sumOf { it.audioMs }) + " голоса",
            )
        }
        if (log.isEmpty()) {
            item { ru.zf.pravka.ui.EmptyState(stringResource(R.string.transcripts_empty), icon = Glyphs.Wave) }
        }
        // Расшифровки — одной плашкой строками (`screens/12`); «Показать всё» —
        // каждая своей плашкой: тысяча строк в одном элементе ленты — тяжело.
        if (!showAll && log.isNotEmpty()) {
            item(key = "tr") {
                val mode = ru.zf.pravka.ui.LocalMode.current
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(22.dp), mode.glass)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    log.forEachIndexed { k, entry ->
                        if (k > 0) ru.zf.pravka.ui.Hairline()
                        val audio = entry.audio?.takeIf { it in withAudio }
                        TranscriptRow(
                            entry, ruLoc,
                            onCopy = { copy(entry.text) },
                            onReplay = if (audio != null) ({ replay(entry) }) else null,
                            replayShare = if (audio != null && audio == replaying) replayShare else null,
                        )
                    }
                }
            }
        }
        if (showAll) items(log, key = { it.ts + it.chars + it.engine }) { entry ->
            val audio = entry.audio?.takeIf { it in withAudio }
            val mode = ru.zf.pravka.ui.LocalMode.current
            Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), mode.glass).padding(horizontal = 18.dp)) {
                TranscriptRow(
                    entry, ruLoc,
                    onCopy = { copy(entry.text) },
                    onReplay = if (audio != null) ({ replay(entry) }) else null,
                    replayShare = if (audio != null && audio == replaying) replayShare else null,
                )
            }
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
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            for ((side, st) in listOf(1 to listState, 2 to rightState)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight().bottomFade().scrollFade(st),
                    state = st,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = if (side == 2) 110.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
                ) { body(side) }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().bottomFade().scrollFade(listState),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
        ) { body(0) }
    }
    // На развороте строка — под правой колонкой, как в макете 08.
    CleanBar(
        app,
        if (wide) Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.5f) else Modifier.align(Alignment.BottomCenter),
    )
    }

    replayOut?.let { out ->
        ReplaySheet(out, ruLoc, onCopy = { copy(out.now) }, onDismiss = { replayOut = null })
    }
}

/** Итог переразбора: что было в тейке, что вышло теперь. */
private class ReplayOut(val was: String, val now: String, val error: String?, val audioMs: Long, val tookMs: Long)

@Composable
private fun ReplaySheet(out: ReplayOut, ruLoc: Locale, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val sub = String.format(ruLoc, "%.1f с звука · разобрано за %.1f с", out.audioMs / 1000.0, out.tookMs / 1000.0)
    PaperSheet(
        onDismiss = onDismiss,
        title = if (out.error == null) "Разобрано заново" else "Не разобралось",
        icon = Glyphs.Wave,
        subtitle = sub,
        footer = if (out.now.isNotBlank()) {
            {
                Spacer(Modifier.weight(1f))
                PaperButton("Скопировать", icon = Glyphs.Copy, primary = true, onClick = { onCopy(); onDismiss() })
            }
        } else null,
    ) {
        when {
            out.error != null -> Text(out.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            out.now.isBlank() -> Text("Распознаватель не разобрал ни слова.", style = MaterialTheme.typography.bodyMedium)
            else -> SelectionContainer { Text(out.now, style = MaterialTheme.typography.bodyLarge) }
        }
        if (out.was.isNotBlank() && out.was != out.now) {
            PaperLabel("было в тейке")
            Text(out.was, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    /** Что ушло на чистку — пузырём справа; поле строки после отправки пустое, как у Gemini. */
    val sent = mutableStateOf("")
    val startedAt = mutableStateOf(0L)
    /** Сколько шла чистка — «2,1 с» в «Последнем». */
    val tookMs = mutableStateOf(0L)
}

/**
 * Причесать текст из нижней строки: тот же движок и режим CLEAN, что у «П»;
 * результат — в буфер (на Applied движок кладёт его сам). Запрос — в
 * appScope: уход со вкладки не обрывает работу, за которую уже заплачено.
 */
private fun startClean(app: PravkaApp, context: android.content.Context) {
    val input = CleanBoxState.text.value.trim()
    if (input.isEmpty() || CleanBoxState.busy.value) return
    CleanBoxState.busy.value = true
    CleanBoxState.sent.value = input
    CleanBoxState.text.value = ""
    CleanBoxState.result.value = null
    CleanBoxState.error.value = null
    CleanBoxState.streaming.value = ""
    CleanBoxState.startedAt.value = android.os.SystemClock.elapsedRealtime()
    app.appScope.launch {
        val target = PlainTextTarget(input, explicit = true)
        // Дельты приходят с IO-потока; состояние Compose трогаем на главном,
        // и не чаще раза в 100 мс — пересобирать длинный текст на каждый токен незачем.
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
        CleanBoxState.tookMs.value = android.os.SystemClock.elapsedRealtime() - CleanBoxState.startedAt.value
        when (outcome) {
            is ProofreadEngine.Outcome.Applied, is ProofreadEngine.Outcome.CopiedToClipboard -> {
                CleanBoxState.result.value = target.result ?: input
                Feedback.toast(context, "Готово — результат в буфере обмена")
            }
            is ProofreadEngine.Outcome.Unchanged -> {
                // Движок на «без изменений» буфер не трогает, а владелец ждёт текст в буфере в любом случае.
                CleanBoxState.result.value = input
                putClipboard(context, input)
                Feedback.toast(context, "Текст уже чистый — положил в буфер как есть")
            }
            ProofreadEngine.Outcome.Rejected -> CleanBoxState.error.value = "Пустой текст — причёсывать нечего."
            is ProofreadEngine.Outcome.Failed -> CleanBoxState.error.value = outcome.message
        }
        CleanBoxState.busy.value = false
    }
}

/**
 * Нижняя строка Правки «Вставь или скажи текст» (`screens/12`): справа —
 * «из буфера» (или ✕, когда текст есть), кружок — «причесать». Сказать —
 * кнопкой «П» в это поле, как в любое другое.
 */
@Composable
private fun CleanBar(app: PravkaApp, modifier: Modifier) {
    val context = LocalContext.current
    var text by CleanBoxState.text
    val busy by CleanBoxState.busy
    fun clipboardText(): String {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }
    ru.zf.pravka.ui.SayBar(
        value = text,
        onValueChange = { text = it },
        placeholder = "Вставь или скажи текст",
        onSend = { startClean(app, context) },
        sendEnabled = text.isNotBlank() && !busy,
        enabled = !busy,
        maxLines = 8,
        busy = busy,
        busyLabel = "Причёсываю",
        trailing = {
            if (text.isEmpty()) {
                ru.zf.pravka.ui.SayIcon(Glyphs.Paste, "вставить из буфера", onClick = {
                    val fromClip = clipboardText()
                    if (fromClip.isBlank()) Feedback.toast(context, "Буфер обмена пуст") else text = fromClip
                })
            } else {
                ru.zf.pravka.ui.SayIcon(Glyphs.Close, "очистить", onClick = { text = "" })
            }
        },
        modifier = modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
    )
}

/**
 * «Последнее» (`screens/12`): пузырём справа — что сказано или вставлено,
 * под ним плашка с тем, что вышло, «2,1 с · 118 → 74 зн» и «Скопировать
 * ещё раз». Свежая чистка из нижней строки — она (с искрами, пока идёт);
 * иначе — последняя чистка «П» из журнала.
 */
@Composable
private fun LastCard(app: PravkaApp) {
    val context = LocalContext.current
    val result by CleanBoxState.result
    val streaming by CleanBoxState.streaming
    val error by CleanBoxState.error
    val busy by CleanBoxState.busy
    val sent by CleanBoxState.sent
    val took by CleanBoxState.tookMs
    var last by remember { mutableStateOf<Pair<ru.zf.pravka.data.HistoryLog.Entry, Long>?>(null) }
    LaunchedEffect(result) { last = withContext(Dispatchers.IO) { app.historyLog.lastClean() } }
    val fresh = busy || result != null || error != null
    val input = if (fresh) sent else last?.first?.input.orEmpty()
    val output = if (fresh) result else last?.first?.output
    if (!fresh && last == null) return
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val at = if (fresh) System.currentTimeMillis() else last?.first?.tsMs ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ru.zf.pravka.ui.SectionHeader(
            "последнее · " + ru.zf.pravka.core.Fmt.hm(at),
            trailing = if (output != null && !busy) "в буфере" else null,
            action = if (fresh && !busy) {
                { GlyphButton(Glyphs.Close, "убрать", onClick = {
                    CleanBoxState.sent.value = ""; CleanBoxState.result.value = null
                    CleanBoxState.error.value = null; CleanBoxState.streaming.value = ""
                }, tint = mode.label) }
            } else null,
        )
        // Тап по пузырю свежей чистки — исходник снова в строку: поправить и причесать ещё раз.
        if (input.isNotBlank()) {
            SourceBubble(input, onEdit = if (fresh && !busy) ({
                CleanBoxState.text.value = sent; CleanBoxState.result.value = null
                CleanBoxState.error.value = null; CleanBoxState.streaming.value = ""
            }) else null)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), mode.glass)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            if (busy) {
                ThinkingLine("Причёсываю")
                if (streaming.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    StreamingText(streaming)
                }
            }
            error?.let { Text(it, style = ty.body, color = ru.zf.pravka.ui.Ink.Warn) }
            output?.let { r ->
                SelectionContainer {
                    Text(r, style = ty.bodyL.copy(fontSize = 17.sp, lineHeight = 24.sp), color = ru.zf.pravka.ui.Ink.TextStrong)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ms = if (fresh) took else last?.second ?: 0L
                    Text(
                        listOfNotNull(
                            ms.takeIf { it > 0 }?.let { String.format(Locale.forLanguageTag("ru"), "%.1f с", it / 1000.0) },
                            if (input.isNotBlank()) "${input.length} → ${r.length} зн" else null,
                        ).joinToString(" · "),
                        style = ty.meta,
                        color = mode.meta,
                        modifier = Modifier.weight(1f),
                    )
                    ru.zf.pravka.ui.PrimaryKey("Скопировать ещё раз", onClick = {
                        putClipboard(context, r)
                        Feedback.toast(context, context.getString(R.string.transcript_copied))
                    }, icon = Glyphs.Copy)
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
            style = ru.zf.pravka.ui.LocalPravkaType.current.body,
            color = ru.zf.pravka.ui.Ink.Text,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(shape)
                .background(ru.zf.pravka.ui.LocalMode.current.key.copy(alpha = 0.30f))
                .border(1.dp, ru.zf.pravka.ui.LocalMode.current.tint.copy(alpha = 0.30f), shape)
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
    Settings.SPEECH_GOOGLE_REPLAY -> "Google заново"
    Settings.SPEECH_WHISPER_SMALL -> "Whisper small"
    Settings.SPEECH_WHISPER_BASE -> "Whisper base"
    else -> engine
}

/**
 * Одна расшифровка строкой плашки (Правка 4.0, `screens/12`): «16:05 · Whisper
 * · 0:41 · 402 зн», текст до трёх строк, справа — «скопировать» (тап по
 * строке делает то же, как с июля). [onReplay] — звук тейка лежит: значок
 * волны разбирает фразу заново; [replayShare] — разбор идёт, доля звука.
 */
@Composable
private fun TranscriptRow(
    entry: TranscriptionLog.Entry,
    ruLoc: Locale,
    onCopy: () -> Unit,
    onReplay: (() -> Unit)? = null,
    replayShare: Float? = null,
) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val canCopy = entry.text.isNotBlank()
    Row(
        Modifier.fillMaxWidth().clickable(enabled = canCopy, onClick = onCopy).padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            // Метрики: время · движок · длина звука · (расшифровка) · знаки.
            val meta = buildString {
                append(entry.ts.replace('T', ' ').substring(11, 16))
                append(" · ").append(engineLabel(entry.engine))
                val sec = (entry.audioMs / 1000).toInt()
                append(" · ").append(sec / 60).append(':').append(String.format(ruLoc, "%02d", sec % 60))
                // Whisper сообщает время расшифровки; живой Google — в реальном времени, у него 0.
                if (entry.transcribeMs > 0) {
                    append(" · расшифровка ").append(String.format(ruLoc, "%.1f", entry.transcribeMs / 1000.0)).append(" с")
                }
                append(" · ").append(entry.chars).append(" зн")
            }
            Text(meta, style = ty.meta, color = if (!entry.ok) ru.zf.pravka.ui.Ink.Warn else mode.meta)
            entry.error?.let { Text(it, style = ty.label, color = ru.zf.pravka.ui.Ink.Warn) }
            // Глухие окна, долгий старт, гарнитура (28.09.2026): пропало ли слово в
            // распознавании или его вовсе никто не слушал.
            TakeHealth.cardLine(
                entry.startupMs, entry.deafMs, entry.deafGaps, entry.mic, ruLoc,
                stuck = entry.stuck, offline = entry.offline != null,
            )?.let { line ->
                val withErrors = if (entry.errors.isNotEmpty() && entry.deafMs >= TakeHealth.SHOW_DEAF_MS) "$line · ошибки ${entry.errors}" else line
                Text(
                    withErrors,
                    style = ty.caption,
                    color = if (entry.deafMs >= TakeHealth.SHOW_DEAF_MS || entry.stuck > 0) ru.zf.pravka.ui.Ink.Warn else mode.meta,
                )
            }
            if (entry.text.isNotBlank()) {
                Text(entry.text, style = ty.bodyL, color = ru.zf.pravka.ui.Ink.Text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            if (replayShare != null) {
                Text("разбираю заново… ${(replayShare * 100).toInt()} %", style = ty.label, color = mode.label, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (onReplay != null && replayShare == null) GlyphButton(Glyphs.Wave, "разобрать заново", onClick = onReplay, size = 40.dp, tint = mode.label)
        if (canCopy) GlyphButton(Glyphs.Copy, "скопировать", onClick = onCopy, size = 40.dp, tint = mode.label)
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
            .fadingScroll()
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
