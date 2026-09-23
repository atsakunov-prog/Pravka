package ru.zf.slushalka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.BookTalk
import ru.zf.slushalka.ask.GuideState
import ru.zf.slushalka.speech.VoiceInput

/**
 * Разговор о книге: сначала пять тем от Claude, тап по теме - его первая
 * реплика, дальше говорим. Можно и сразу своим вопросом, и голосом.
 * Дочитанную книгу обсуждаем без барьера, недочитанную - только прочитанное.
 */
@Composable
fun TalkSheet(
    app: SlushalkaApp,
    /** Место чтения; null - посчитать по позиции книги. */
    cutoff: Int?,
    /** Дочитана ли; null - посчитать по позиции. */
    finished: Boolean?,
    hasMic: () -> Boolean,
    onNeedMic: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val state = app.state
    val book by state.current.collectAsState()
    val text by state.text.collectAsState()
    val alignment by state.alignment.collectAsState()
    val prefs by state.prefs.collectAsState()
    val states by app.guide.states.collectAsState()
    val scope = rememberCoroutineScope()

    var topics by remember { mutableStateOf<List<BookTalk.Topic>?>(null) }
    var lines by remember { mutableStateOf(listOf<BookTalk.Line>()) }
    var input by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showTopics by remember { mutableStateOf(true) }
    var listening by remember { mutableStateOf(false) }
    var spent by remember { mutableStateOf(0.0) }
    val voice = remember { VoiceInput(context) }
    val scroll = rememberScrollState()

    DisposableEffect(Unit) {
        onDispose {
            voice.cancel()
            app.speaker.stop()
        }
    }

    val b = book
    val t = text
    // Где читатель и дочитал ли - от того, откуда позвали; с полки - по позиции.
    val ctx = remember(b?.id, t, alignment, states, cutoff, finished) {
        if (b == null || t == null) return@remember null
        val st = state.stateOf(b.id)
        val at = cutoff ?: when {
            b.hasAudio && alignment != null -> alignment!!.charAt(st.absMs)
            st.readChar >= 0 -> st.readChar
            else -> 0
        }
        val done = finished ?: (st.finished || at >= t.length * 0.97)
        val guide = states[b.id]?.takeIf { it.status == GuideState.Status.READY }?.guide
        app.talk.context(b, t, guide, at, done)
    }

    LaunchedEffect(ctx) {
        val c = ctx ?: return@LaunchedEffect
        val bk = b ?: return@LaunchedEffect
        if (topics != null) return@LaunchedEffect
        busy = "Придумываю, о чём поговорить…"
        app.talk.topics(bk, c)
            .onSuccess { topics = it; if (it.isEmpty()) error = "Темы не придумались - спроси своё" }
            .onFailure { error = it.message ?: "Не вышло" }
        busy = null
    }

    LaunchedEffect(lines.size, partial.isNotEmpty()) { scroll.animateScrollTo(scroll.maxValue) }

    fun speak(line: BookTalk.Line) {
        if (prefs.speakAnswers) app.speaker.speak(line.text)
    }

    fun send(message: String) {
        val c = ctx ?: return
        val bk = b ?: return
        val m = message.trim()
        if (m.isEmpty() || busy != null) return
        val history = lines
        lines = lines + BookTalk.Line("user", m)
        input = ""
        partial = ""
        showTopics = false
        busy = "Думаю…"
        error = null
        scope.launch {
            app.talk.say(bk, c, history, m) { partial = it }
                .onSuccess { line ->
                    lines = lines + line
                    spent += line.costUsd
                    speak(line)
                }
                .onFailure {
                    error = it.message ?: "Не вышло"
                    // Не ушло - реплика возвращается в поле, чтобы не набирать заново.
                    lines = history
                    input = m
                }
            partial = ""
            busy = null
        }
    }

    fun pick(topic: BookTalk.Topic) {
        // Тема - это реплика читателя и готовый ответ Claude: платить второй раз
        // за то, что уже написано в списке тем, незачем.
        val opener = BookTalk.Line("assistant", topic.opener)
        lines = lines + BookTalk.Line("user", "Давай поговорим: ${topic.title}.") + opener
        showTopics = false
        speak(opener)
    }

    fun toggleVoice() {
        if (listening) {
            voice.stop()
            return
        }
        if (!hasMic()) return onNeedMic()
        voice.onText = { input = it }
        voice.onEnd = { heard ->
            listening = false
            if (heard.isNotBlank()) send(heard)
        }
        voice.onError = { listening = false; error = it }
        voice.start()
        listening = true
    }

    PaperScreen(
        app = app,
        icon = Glyphs.Forum,
        title = "Разговор о книге",
        subtitle = when {
            ctx == null -> "разбираю текст…"
            ctx.finished -> "дочитана - говорим без оглядки на спойлеры"
            else -> "только о прочитанном, без спойлеров"
        },
        onClose = onClose,
        actions = {
            if (!showTopics && !topics.isNullOrEmpty()) {
                PaperIconButton(Glyphs.Lightbulb, "Темы") { showTopics = true }
            }
        },
        bottom = {
            ChatInput(
                value = input,
                onValue = { input = it },
                placeholder = "Своя мысль или вопрос",
                listening = listening,
                enabled = busy == null && ctx != null,
                onMic = { toggleVoice() },
                onSend = { send(input) },
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            b?.let {
                Column {
                    Text(it.title, style = MaterialTheme.typography.titleMedium)
                    if (it.author.isNotBlank()) PaperNote(it.author)
                }
            }
            lines.forEach { line ->
                ChatBubble(line.text, mine = line.role == "user", footer = if (line.role == "user") null else ({
                    MiniAction(Glyphs.VolumeUp, "Вслух") { app.speaker.speak(line.text) }
                    MiniAction(Glyphs.StopCircle, "Тише") { app.speaker.stop() }
                }))
            }
            if (partial.isNotEmpty()) ChatBubble(partial, mine = false)
            val list = topics
            if (showTopics && list != null && list.isNotEmpty()) {
                PaperLabel(if (lines.isEmpty()) "О чём поговорим" else "Другие темы")
                list.forEach { tp ->
                    PaperCard(onClick = if (busy == null) ({ pick(tp) }) else null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Glyphs.Lightbulb, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(tp.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tp.opener,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            busy?.let { PaperBusy(it) }
            error?.let { PaperError(it) }
            if (spent > 0) {
                Text(
                    "разговор: %.3f $".format(spent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
