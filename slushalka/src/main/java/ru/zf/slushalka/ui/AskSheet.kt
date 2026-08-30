package ru.zf.slushalka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.AskEngine
import ru.zf.slushalka.ask.Prompts
import ru.zf.slushalka.ask.VoiceInput

/**
 * Вопрос по книге.
 *
 * Книга встаёт на паузу, вопрос наговаривается или набирается, в промпт
 * уезжает то, что уже прослушано, - и ответ приходит без единого спойлера
 * (см. Prompts). После ответа книга продолжается с того же места.
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
) {
    val state = app.state
    val book by state.current.collectAsState()
    val text by state.text.collectAsState()
    val alignment by state.alignment.collectAsState()
    val play by app.player.state.collectAsState()
    val prefs by state.prefs.collectAsState()
    val scope = rememberCoroutineScope()

    var question by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pausedByUs by remember { mutableStateOf(false) }
    // Место фиксируется на момент открытия окна: пока набираешь вопрос,
    // книга уже стоит, и «сейчас» никуда не уезжает.
    val askedAtMs = remember { play.absMs }

    val voice = remember { VoiceInput(app) }

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
        book?.id, text, alignment, askedAtMs, atChar,
        prefs.contextPages, prefs.wholeBookContext, prefs.spoilerMarginSec,
    ) {
        val b = book
        val t = text
        val a = alignment
        when {
            b == null || t == null -> null
            atChar != null -> app.ask.contextAt(b, t, atChar, askedAtMs)
            a == null -> null
            else -> app.ask.context(b, t, a, askedAtMs)
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
        if (q.isBlank() || busy) return
        busy = true
        error = null
        answer = ""
        scope.launch {
            val result = app.ask.ask(b, c, q, askedAtMs) { partial -> answer = partial }
            busy = false
            result.onSuccess { ask ->
                answer = ask.answer
                if (prefs.speakAnswers) {
                    app.speaker.speak(ask.answer) {
                        // Дочитали вслух - книга сама продолжается: за рулём
                        // в телефон уже не потянешься.
                        if (pausedByUs) {
                            app.player.resumeAfterAsking()
                            pausedByUs = false
                        }
                    }
                }
            }.onFailure { error = it.message ?: "Не вышло спросить" }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Вопрос по книге") },
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
                    .verticalScroll(rememberScrollState())
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
                        append("${ctx.percent}% · ").append(formatClock(askedAtMs))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    placeholder = { Text("Наговори или набери вопрос") },
                    label = null,
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            if (listening) {
                                voice.stop()
                            } else {
                                if (!hasMic()) return@Button onNeedMic()
                                voice.onText = { question = it }
                                voice.onEnd = { heard ->
                                    listening = false
                                    if (heard.isNotBlank()) question = heard
                                }
                                voice.onError = { listening = false; error = it }
                                voice.start()
                                listening = true
                            }
                        },
                    ) { Text(if (listening) "Стоп" else "Наговорить") }

                    Button(
                        enabled = question.isNotBlank() && !busy,
                        onClick = { send(question.trim()) },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy) "Спрашиваю…" else "Спросить") }
                }

                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Prompts.PRESETS.forEach { (label, prompt) ->
                        AssistChip(
                            onClick = { question = prompt; send(prompt) },
                            label = { Text(label) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = prefs.speakAnswers,
                        onClick = { scope.launch { state.settings.setSpeakAnswers(!prefs.speakAnswers) } },
                        label = { Text("вслух") },
                    )
                    FilterChip(
                        selected = prefs.wholeBookContext,
                        onClick = {
                            scope.launch { state.settings.setWholeBookContext(!prefs.wholeBookContext) }
                        },
                        label = { Text("вся книга до этого места") },
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "В промпт уедет %d стр., примерно %.2f \u0024".format(ctx.pages, ctx.estUsd),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                if (answer.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(answer, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(6.dp))
                            Row {
                                TextButton(onClick = { app.speaker.speak(answer) }) { Text("Вслух") }
                                TextButton(onClick = { app.speaker.stop() }) { Text("Тише") }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { finish() }) { Text("Дальше слушать") }
                            }
                        }
                    }
                }

                val history = book?.let { app.askLog.of(it.id) }.orEmpty()
                    .filter { it.answer.isNotBlank() }
                if (history.size > 1) {
                    Spacer(Modifier.height(18.dp))
                    Text("Спрашивали раньше", style = MaterialTheme.typography.labelMedium)
                    history.dropLast(1).takeLast(6).reversed().forEach { a ->
                        Spacer(Modifier.height(8.dp))
                        Column {
                            Text(
                                "${formatClock(a.absMs)} · ${a.question}",
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
