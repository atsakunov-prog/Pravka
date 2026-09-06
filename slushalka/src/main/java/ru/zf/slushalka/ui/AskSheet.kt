package ru.zf.slushalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.AskEngine
import ru.zf.slushalka.ask.Prompts
import ru.zf.slushalka.ask.VoiceInput
import ru.zf.slushalka.data.Settings

/**
 * Вопрос по книге.
 *
 * Книга встаёт на паузу, вопрос наговаривается или набирается, в промпт
 * уезжает то, что уже прочитано, - и ответ приходит без единого спойлера
 * (см. Prompts). Здесь же выбираются модель, объём текста и кэш, и здесь же
 * виден расход. После ответа разговор продолжается: уточнить, спросить про
 * ответ - реплики уезжают вместе, а книга-контекст с кэшем платится один раз.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AskSheet(
    app: SlushalkaApp,
    hasMic: () -> Boolean,
    onNeedMic: () -> Unit,
    onClose: () -> Unit,
    /** Место в тексте, если спрашивают из читалки; иначе считается по записи. */
    atChar: Int? = null,
    /** Готовый вопрос (например, про картинку) - уходит сам, без лишнего тапа. */
    initialQuestion: String? = null,
    /** Кусок, выделенный в читалке: вопрос - про него. */
    quote: String? = null,
) {
    val state = app.state
    val book by state.current.collectAsState()
    val text by state.text.collectAsState()
    val alignment by state.alignment.collectAsState()
    val play by app.player.state.collectAsState()
    val prefs by state.prefs.collectAsState()
    val coroutine = rememberCoroutineScope()

    var question by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pausedByUs by remember { mutableStateOf(false) }
    var turns by remember { mutableStateOf(listOf<AskEngine.Turn>()) }
    var pending by remember { mutableStateOf<String?>(null) }
    var partial by remember { mutableStateOf("") }
    // Модель, объём и кэш - здесь, под рукой, и помнятся в настройках.
    // Спойлеры - нет: барьер снимается на один разговор и руками.
    var model by remember { mutableStateOf(prefs.askModel) }
    var scope by remember { mutableStateOf(AskEngine.Scope.of(prefs.askScope)) }
    var cache by remember { mutableStateOf(prefs.askCache) }
    var spoilers by remember { mutableStateOf(false) }
    var setupOpen by remember { mutableStateOf(true) }
    // Место фиксируется на момент открытия окна: пока набираешь вопрос,
    // книга уже стоит, и «сейчас» никуда не уезжает.
    // У книги без записи плеер может держать чужую книгу - её секунды сюда не берём.
    val askedAtMs = remember { if (book?.hasAudio == false) 0L else play.absMs }

    val voice = remember { VoiceInput(app) }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) {
        if (prefs.pauseWhileAsking) pausedByUs = app.player.pauseForAsking()
    }

    DisposableEffect(Unit) {
        onDispose {
            voice.cancel()
            app.speaker.stop()
            app.ask.cancel()
        }
    }

    val ctx: AskEngine.Ctx? = remember(
        book?.id, text, alignment, askedAtMs, atChar, scope, prefs.spoilerMarginSec,
    ) {
        val b = book
        val t = text
        val a = alignment
        when {
            b == null || t == null -> null
            atChar != null -> app.ask.contextAt(b, t, atChar, askedAtMs, scope)
            a == null -> null
            else -> app.ask.context(b, t, a, askedAtMs, scope)
        }
    }

    fun finish() {
        voice.cancel()
        app.speaker.stop()
        if (pausedByUs) app.player.resumeAfterAsking()
        onClose()
    }

    fun send(q: String) {
        val b = book ?: return
        val c = ctx ?: return
        val textQ = q.trim()
        if (textQ.isBlank() || busy) return
        busy = true
        error = null
        pending = textQ
        partial = ""
        question = ""
        val first = turns.isEmpty()
        coroutine.launch {
            val result = app.ask.ask(
                book = b,
                ctx = c,
                history = turns,
                question = textQ,
                // Выделенный кусок уезжает с первым вопросом; дальше он уже в истории.
                quote = if (first) quote else null,
                model = model,
                cache = cache,
                spoilers = spoilers,
                absMs = askedAtMs,
            ) { partial = it }
            busy = false
            result.onSuccess { turn ->
                turns = turns + turn
                pending = null
                partial = ""
                setupOpen = false
                if (prefs.speakAnswers) {
                    app.speaker.speak(turn.answer) {
                        // Дочитали вслух - книга сама продолжается: за рулём
                        // в телефон уже не потянешься.
                        if (pausedByUs) {
                            app.player.resumeAfterAsking()
                            pausedByUs = false
                        }
                    }
                }
            }.onFailure {
                error = it.message ?: "Не вышло спросить"
                pending = null
                partial = ""
            }
        }
    }

    // Вопрос, заданный кнопкой («расскажи про картинку»), отправляется сам,
    // как только контекст собран: тапать «Спросить» ещё раз незачем.
    var autoSent by remember { mutableStateOf(false) }
    LaunchedEffect(ctx, initialQuestion) {
        if (!autoSent && initialQuestion != null && ctx != null) {
            autoSent = true
            send(initialQuestion)
        }
    }

    // Новая реплика - лист прокручивается к ней, как в мессенджере.
    LaunchedEffect(turns.size, pending) {
        if (turns.isNotEmpty() || pending != null) scroll.animateScrollTo(scroll.maxValue)
    }

    fun startVoice() {
        if (listening) {
            voice.stop()
            return
        }
        if (!hasMic()) return onNeedMic()
        voice.onText = { question = it }
        voice.onEnd = { heard ->
            listening = false
            if (heard.isNotBlank()) question = heard
        }
        voice.onError = { listening = false; error = it }
        voice.start()
        listening = true
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (quote != null) "Вопрос о фрагменте" else "Вопрос по книге") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(scroll)
                    .padding(horizontal = 18.dp),
            ) {
                if (book?.textDocId == null) {
                    NoTextNote()
                    return@Column
                }
                if (ctx == null) {
                    Text(
                        "Разбираю текст книги…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                Text(
                    buildString {
                        if (ctx.chapter.isNotBlank()) append(ctx.chapter).append(" · ")
                        append("${ctx.percent}%")
                        if (ctx.elapsed.isNotBlank()) append(" · ").append(ctx.elapsed)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                quote?.let { q ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "«" + q.trim() + "»",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (turns.isEmpty()) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        placeholder = { Text(if (quote != null) "Что спросить про этот кусок?" else "Наговори или набери вопрос") },
                        label = null,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { startVoice() }) { Text(if (listening) "Стоп" else "Наговорить") }
                        Button(
                            enabled = question.isNotBlank() && !busy,
                            onClick = { send(question) },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (busy) "Спрашиваю…" else "Спросить") }
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (if (quote != null) Prompts.FRAGMENT_PRESETS else Prompts.PRESETS).forEach { (label, prompt) ->
                            AssistChip(
                                enabled = !busy,
                                onClick = { send(prompt) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // Модель, объём, кэш и цена. После первого ответа сворачивается в
                // строку: разговор важнее рукояток, но они в одном тапе.
                if (setupOpen) {
                    Text("Кто отвечает", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Settings.MODELS.forEach { m ->
                            FilterChip(
                                selected = model == m,
                                onClick = {
                                    model = m
                                    coroutine.launch { state.settings.setAskModel(m) }
                                },
                                label = { Text(Settings.modelLabel(m)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Что показать модели: ${scope.label}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = scope.ordinal.toFloat(),
                        onValueChange = { scope = AskEngine.Scope.entries[it.roundToInt().coerceIn(0, AskEngine.Scope.entries.lastIndex)] },
                        onValueChangeFinished = { coroutine.launch { state.settings.setAskScope(scope.name) } },
                        valueRange = 0f..AskEngine.Scope.entries.lastIndex.toFloat(),
                        steps = AskEngine.Scope.entries.size - 2,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = cache,
                            onClick = {
                                cache = !cache
                                coroutine.launch { state.settings.setAskCache(cache) }
                            },
                            label = { Text("держать в кэше час") },
                        )
                        FilterChip(
                            selected = spoilers,
                            onClick = { spoilers = !spoilers },
                            label = { Text(if (spoilers) "спойлеры разрешены" else "спойлеры") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        )
                        FilterChip(
                            selected = prefs.speakAnswers,
                            onClick = { coroutine.launch { state.settings.setSpeakAnswers(!prefs.speakAnswers) } },
                            label = { Text("вслух") },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        costLine(ctx, model, cache),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (spoilers) {
                        Text(
                            "Барьер снят: модель ответит и о том, что будет дальше. Выключается тем же чипом.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (turns.isNotEmpty()) {
                        TextButton(onClick = { setupOpen = false }) { Text("Свернуть") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append(Settings.modelLabel(model)).append(" · ").append(scope.short)
                                if (cache) append(" · кэш")
                                if (spoilers) append(" · спойлеры")
                                val spent = turns.sumOf { it.costUsd }
                                if (spent > 0) append(" · %.2f $".format(spent))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { setupOpen = true }) { Text("Изменить") }
                    }
                }

                Spacer(Modifier.height(8.dp))

                turns.forEachIndexed { i, turn ->
                    QuestionBubble(turn.shown)
                    val last = i == turns.lastIndex
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(turn.answer, style = MaterialTheme.typography.bodyLarge)
                            if (turn.truncated) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Ответ упёрся в потолок длины. Напиши «продолжи» - договорит.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row {
                                TextButton(onClick = { app.speaker.speak(turn.answer) }) { Text("Вслух") }
                                TextButton(onClick = { app.speaker.stop() }) { Text("Тише") }
                                Spacer(Modifier.weight(1f))
                                if (last) TextButton(onClick = { finish() }) { Text("Дальше слушать") }
                            }
                            if (last && remember(turn.answer) { Love.rarely() }) {
                                LoveLine(alpha = 0.4f, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                pending?.let { q ->
                    QuestionBubble(q)
                    if (partial.isBlank()) {
                        Text(
                            "Думаю…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(partial, style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { app.ask.cancel() }) { Text("Хватит") }
                    Spacer(Modifier.height(8.dp))
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }

                if (turns.isNotEmpty()) {
                    // Разговор продолжается: уточнить, спросить про ответ. Реплики
                    // уезжают вместе с историей, книга-контекст - из кэша.
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        placeholder = { Text("Уточнить или спросить про ответ") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 4,
                        trailingIcon = {
                            IconButton(onClick = { send(question) }, enabled = !busy && question.isNotBlank()) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Спросить")
                            }
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !busy, onClick = { startVoice() }) {
                            Text(if (listening) "Стоп" else "Наговорить")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "следующий ≈ %.2f $".format(ctx.estNextUsd(model, cache)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Прошлые разговоры по этой книге - без тех реплик, что уже на экране.
                val history = book?.let { app.askLog.of(it.id) }.orEmpty()
                    .filter { it.answer.isNotBlank() }
                    .dropLast(turns.size)
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Спрашивали раньше", style = MaterialTheme.typography.labelMedium)
                    history.takeLast(6).reversed().forEach { a ->
                        Spacer(Modifier.height(8.dp))
                        Column {
                            Text(
                                (if (a.absMs > 0) formatClock(a.absMs) + " · " else "") + a.question,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(a.answer, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** Строка про расход: сколько уедет и во что обойдётся первый вопрос и следующие. */
private fun costLine(ctx: AskEngine.Ctx, model: String, cache: Boolean): String {
    val first = ctx.estFirstUsd(model, cache)
    val next = ctx.estNextUsd(model, cache)
    return if (cache) {
        "В промпт уедет %d стр. · первый вопрос ≈ %.2f $, следующие ≈ %.2f $".format(ctx.pages, first, next)
    } else {
        "В промпт уедет %d стр. · каждый вопрос ≈ %.2f $".format(ctx.pages, first)
    }
}

@Composable
private fun QuestionBubble(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun NoTextNote() {
    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        Text(
            "Чтобы спрашивать по книге, рядом с аудио должен лежать её текст - " +
                "fb2 или epub в той же папке. Тогда в вопрос уедут последние страницы " +
                "того, что ты уже услышал.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
