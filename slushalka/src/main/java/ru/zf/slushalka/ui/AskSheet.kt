package ru.zf.slushalka.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.AskEngine
import ru.zf.slushalka.ask.Prompts
import ru.zf.slushalka.speech.VoiceInput
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
@OptIn(ExperimentalLayoutApi::class)
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
    /**
     * Спросили голосом из шторки или с виджета: микрофон включается сразу,
     * услышанное уходит само, ответ читается вслух - телефон в кармане, в
     * экран не смотрят.
     */
    handsFree: Boolean = false,
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
    var setupOpen by remember { mutableStateOf(false) }
    // Место фиксируется на момент открытия окна: пока набираешь вопрос,
    // книга уже стоит, и «сейчас» никуда не уезжает.
    // У книги без записи плеер может держать чужую книгу - её секунды сюда не берём.
    val askedAtMs = remember { if (book?.hasAudio == false) 0L else play.absMs }

    val voice = remember { VoiceInput(app) }
    val scroll = rememberScrollState()

    // Озвучка книги без записи тоже замолкает на вопрос: иначе синтез читал
    // бы книгу прямо в микрофон и поверх ответа.
    var ttsPausedByUs by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (prefs.pauseWhileAsking) pausedByUs = app.player.pauseForAsking()
        if (prefs.pauseWhileAsking && app.readAloud.state.value.speaking) {
            app.readAloud.pause()
            ttsPausedByUs = true
        }
    }

    fun resumeBook() {
        if (pausedByUs) {
            app.player.resumeAfterAsking()
            pausedByUs = false
        }
        if (ttsPausedByUs) {
            app.readAloud.resume()
            ttsPausedByUs = false
        }
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
        resumeBook()
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
                if (prefs.speakAnswers || handsFree) {
                    app.speaker.speak(turn.answer) {
                        // Дочитали вслух - книга сама продолжается: за рулём
                        // в телефон уже не потянешься.
                        resumeBook()
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
            if (heard.isNotBlank()) {
                question = heard
                // Без рук - без кнопки «Спросить»: договорил, и вопрос ушёл.
                if (handsFree) send(heard)
            }
        }
        voice.onError = { listening = false; error = it }
        voice.start()
        listening = true
    }

    // Без рук: слушать, как только собрался контекст, - раньше отправлять некуда.
    var handsStarted by remember { mutableStateOf(false) }
    LaunchedEffect(ctx, handsFree) {
        if (handsFree && !handsStarted && ctx != null) {
            handsStarted = true
            startVoice()
        }
    }

    val clipboard = LocalClipboardManager.current
    // «Назад» - то же, что крестик: книга, поставленная на паузу ради
    // вопроса, продолжается. Раньше его ловил общий обработчик и просто
    // закрывал окно, а книга так и стояла.
    androidx.activity.compose.BackHandler { finish() }
    val t = book?.textDocId
    PaperScreen(
        app = app,
        icon = Glyphs.QuestionAnswer,
        title = if (quote != null) "Спросить о фрагменте" else "Спросить по книге",
        subtitle = ctx?.let {
            buildString {
                if (it.chapter.isNotBlank()) append(it.chapter).append(" · ")
                append("${it.percent}%")
                if (it.elapsed.isNotBlank()) append(" · ").append(it.elapsed)
            }
        } ?: book?.title,
        onClose = { finish() },
        bottom = {
            if (t != null && ctx != null) {
                ChatInput(
                    value = question,
                    onValue = { question = it },
                    placeholder = when {
                        turns.isNotEmpty() -> "Уточнить или спросить про ответ"
                        quote != null -> "Что спросить про этот кусок?"
                        else -> "Спроси о прочитанном"
                    },
                    listening = listening,
                    enabled = !busy,
                    onMic = { startVoice() },
                    onSend = { send(question) },
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 18.dp),
        ) {
            if (t == null) {
                NoTextNote()
                return@Column
            }
            if (ctx == null) {
                PaperBusy("Разбираю текст книги…")
                return@Column
            }
            Spacer(Modifier.height(10.dp))

            quote?.let {
                PaperQuote(it)
                Spacer(Modifier.height(6.dp))
            }

            // Рукоятки - одной строкой: кто отвечает, сколько книги видит, во
            // что обойдётся. Раньше они стояли простынёй над вопросом и
            // заслоняли главное; теперь раскрываются тапом.
            SetupLine(
                text = buildString {
                    append(Settings.modelLabel(model)).append(" · ").append(scope.short)
                    if (cache) append(" · кэш")
                    if (spoilers) append(" · спойлеры")
                    val spent = turns.sumOf { it.costUsd }
                    if (spent > 0) append(" · потрачено %.2f $".format(spent))
                    else append(" · ≈ %.2f $".format(ctx.estFirstUsd(model, cache)))
                },
                open = setupOpen,
                warn = spoilers,
                onClick = { setupOpen = !setupOpen },
            )
            if (setupOpen) {
                PaperCard {
                    Text("Кто отвечает", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Settings.MODELS.forEach { m ->
                            PaperChip(Settings.modelLabel(m), selected = model == m) {
                                model = m
                                coroutine.launch { state.settings.setAskModel(m) }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сколько книги показать", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(scope.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(
                        value = scope.ordinal.toFloat(),
                        onValueChange = { scope = AskEngine.Scope.entries[it.roundToInt().coerceIn(0, AskEngine.Scope.entries.lastIndex)] },
                        onValueChangeFinished = { coroutine.launch { state.settings.setAskScope(scope.name) } },
                        valueRange = 0f..AskEngine.Scope.entries.lastIndex.toFloat(),
                        steps = AskEngine.Scope.entries.size - 2,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            activeTickColor = MaterialTheme.colorScheme.onPrimary,
                            inactiveTickColor = MaterialTheme.colorScheme.outline,
                        ),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaperChip("Кэш на час", selected = cache, icon = Glyphs.Speed) {
                            cache = !cache
                            coroutine.launch { state.settings.setAskCache(cache) }
                        }
                        PaperChip(
                            if (spoilers) "Спойлеры можно" else "Без спойлеров",
                            selected = spoilers,
                            icon = if (spoilers) Glyphs.LockOpen else Icons.Default.Lock,
                            warn = true,
                        ) { spoilers = !spoilers }
                        PaperChip("Ответ вслух", selected = prefs.speakAnswers, icon = Glyphs.VolumeUp) {
                            coroutine.launch { state.settings.setSpeakAnswers(!prefs.speakAnswers) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PaperNote(costLine(ctx, model, cache))
                    if (spoilers) {
                        Spacer(Modifier.height(4.dp))
                        PaperNote(
                            "Барьер снят: модель ответит и о том, что будет дальше. Только на этот разговор.",
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (turns.isEmpty() && pending == null) {
                PaperLabel("С чего начать")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (quote != null) Prompts.FRAGMENT_PRESETS else Prompts.PRESETS).forEach { (label, prompt) ->
                        PaperChip(label, selected = false, enabled = !busy) { send(prompt) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                PaperNote(
                    if (handsFree) "Говори - вопрос уйдёт сам, ответ прочитаю вслух."
                    else "Или спроси своё снизу - словами или голосом. Отвечу без спойлеров: только по тому, что уже прочитано.",
                )
            }

            turns.forEachIndexed { i, turn ->
                val last = i == turns.lastIndex
                ChatBubble(turn.shown, mine = true)
                Spacer(Modifier.height(8.dp))
                ChatBubble(turn.answer, mine = false) {
                    MiniAction(Glyphs.VolumeUp, "Вслух") { app.speaker.speak(turn.answer) }
                    MiniAction(Glyphs.StopCircle, "Тише") { app.speaker.stop() }
                    MiniAction(Glyphs.ContentCopy, "Копия") { clipboard.setText(AnnotatedString(turn.answer)) }
                }
                if (turn.truncated) {
                    PaperNote(
                        "Ответ упёрся в потолок длины. Напиши «продолжи» - договорит.",
                        Modifier.padding(start = 6.dp, top = 4.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (last) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PaperButton(if (book?.hasAudio == false) "Дальше читать" else "Дальше слушать", icon = Glyphs.Headphones) { finish() }
                    }
                    if (remember(turn.answer) { Love.rarely() }) {
                        LoveLine(alpha = 0.4f, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            pending?.let { q ->
                ChatBubble(q, mine = true)
                Spacer(Modifier.height(8.dp))
                if (partial.isNotBlank()) ChatBubble(partial, mine = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PaperBusy(if (partial.isBlank()) "Думаю…" else "Пишу…") }
                    Spacer(Modifier.width(8.dp))
                    MiniAction(Glyphs.StopCircle, "Хватит") { app.ask.cancel() }
                }
                Spacer(Modifier.height(8.dp))
            }

            error?.let { PaperError(it) }

            // Прошлые разговоры по этой книге - без тех реплик, что уже на экране.
            val history = book?.let { app.askLog.of(it.id) }.orEmpty()
                .filter { it.answer.isNotBlank() }
                .dropLast(turns.size)
            if (history.isNotEmpty()) {
                PaperLabel("Спрашивали раньше", Modifier.padding(top = 8.dp))
                history.takeLast(6).reversed().forEach { a ->
                    var open by remember(a) { mutableStateOf(false) }
                    PaperCard(onClick = { open = !open }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Glyphs.History, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                a.question,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = if (open) 6 else 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (a.absMs > 0) {
                                Text(formatClock(a.absMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            a.answer,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (open) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Свёрнутые рукоятки: значок, сводка, стрелка. Предупреждение о спойлерах - тёплым. */
@Composable
private fun SetupLine(text: String, open: Boolean, warn: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.Tune, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (warn) c.tertiary else c.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (warn) c.tertiary else c.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (open) "Свернуть" else "Настроить",
            tint = c.onSurfaceVariant,
        )
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
