package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import ru.zf.pravka.ui.glass
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaAsk
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.finishDelaReason
import ru.zf.pravka.trigger.listenForDelaReason
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.ThinkingLine

// Правка дел словами (05.10.2026, docs/dela-phone-2.md, этап 1, пункт 3):
// микрофон у дела и строка «Claude» над экраном вида. Делает сервер
// (`/api/ask`) — так же, как веб, с моделью и глубиной из настроек человека;
// телефон собирает, что на экране, ждёт задание и показывает итог с «Вернуть
// всё». Без сети команда не уходит — и текст остаётся в поле.

/** Что видит Claude с этого экрана: название и дела в порядке показа. */
internal data class AskScreen(
    val title: String,
    val scope: JSONObject,
    /** Карточка клиента, сделки или человека: Claude (Opus) видит её целиком и правит не только дела. */
    val card: Boolean = false,
)

/**
 * Разговор с Claude (09.10.2026, docs/dela-phone-9.md, п. 12; владелец: «он
 * регулярно просит ещё следующий вопрос — в его ответе тоже должен быть
 * текстбокс»): что видел экран в начале ([scope]), реплики, ждущая реплика
 * ([busy]), набранное в строке ответа и причина, если реплика не ушла.
 */
internal data class Talk(
    val scope: JSONObject,
    val turns: List<DelaAsk.Turn> = emptyList(),
    val busy: String = "",
    val text: String = "",
    val error: String = "",
)

/** Одна команда за раз на всю вкладку — из строки Claude внизу ([ASK_SCREEN]). */
internal class AskState {
    var running by mutableStateOf("")
    var errors by mutableStateOf(mapOf<String, String>())
    var talk by mutableStateOf<Talk?>(null)

    fun error(key: String): String = errors[key].orEmpty()
}

internal const val ASK_SCREEN = "screen"

/**
 * Микрофон команды: тот же движок и пилюля, что у «Д» (`listenForDelaReason`),
 * текст — в поле. Сказанное вслух — решение (26.09): кончилась запись с
 * текстом — команда уходит сама, как в вебе.
 */
@Composable
internal fun AskMic(listening: Boolean, onListening: (Boolean) -> Unit, onText: (String) -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    GlyphButton(
        if (listening) Glyphs.Stop else Glyphs.Mic,
        if (listening) "хватит слушать" else "сказать голосом",
        tint = MaterialTheme.colorScheme.primary,
        enabled = enabled,
        onClick = {
            val service = PravkaAccessibilityService.instance
            when {
                service == null -> Feedback.toast(context, context.getString(R.string.toast_no_service))
                listening -> if (!service.finishDelaReason(keep = true)) onListening(false)
                else -> onListening(service.listenForDelaReason { text ->
                    onListening(false)
                    onText(text)
                })
            }
        },
    )
}

/**
 * Что сейчас в окне (08.10.2026, docs/dela-phone-7.md, `pageScope` веба):
 * строки дел и плитки сделок отмечают, где они, — обрезанные окном списка
 * (`boundsInWindow`) и целиком. В миг отправки [visible] отдаёт те, от которых
 * видно хотя бы 60 % выше строки Claude. Не состояние Compose — просто записная
 * книжка: перерисовок она не вызывает. Ушедшая из списка строка себя стирает.
 */
internal class SeenRows {
    private val rows = HashMap<String, DelaAsk.Seen>()
    var top = 0f
    var bottom = Float.MAX_VALUE

    fun put(key: String, r: DelaAsk.Seen) { rows[key] = r }
    fun drop(key: String) { rows.remove(key) }

    /** Ключи с приставкой [prefix] («t:» — дела, «d:» — сделки), которые в окне, — без приставки. */
    fun visible(prefix: String): List<String> =
        DelaAsk.visible(rows.filterKeys { it.startsWith(prefix) }, top, bottom).map { it.removePrefix(prefix) }

    /** Все отмеченные с приставкой — сверху вниз (и те, что за краем окна, но ещё нарисованы). */
    fun all(prefix: String): List<String> =
        rows.entries.filter { it.key.startsWith(prefix) }.sortedWith(compareBy({ it.value.top }, { it.value.left })).map { it.key.removePrefix(prefix) }
}

internal val LocalSeenRows = androidx.compose.runtime.staticCompositionLocalOf<SeenRows?> { null }

/** Отметить строку или плитку в [SeenRows] под ключом [key] («t:<id>», «d:<id>»). */
@Composable
internal fun Modifier.seen(key: String): Modifier {
    val rows = LocalSeenRows.current ?: return this
    androidx.compose.runtime.DisposableEffect(key) { onDispose { rows.drop(key) } }
    return this.then(Modifier.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        rows.put(key, DelaAsk.Seen(b.top, b.bottom, b.left, b.right, c.size.height.toFloat(), c.size.width.toFloat()))
    })
}

/**
 * Строка Claude внизу — одна на всю вкладку (08–09.10.2026, docs/dela-phone-7.md,
 * поправки задания 10): микрофон слева — нажал, говоришь сколько угодно, нажал
 * ещё раз — команда ушла; стрелка справа (пока поле пусто — приглушена). Пока
 * Claude думает — команда серым в поле и заливка слева направо, ничего не
 * вращается. Над строкой — о чём она: дела на экране, открытое дело или
 * карточка («Opus · видит карточку»); пока слушает — как закончить.
 */
@Composable
internal fun DelaSayBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    about: String,
    card: Boolean,
    listening: Boolean,
    busy: Boolean,
    note: String,
    noteError: Boolean,
    onMic: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    // Заливка «Claude думает»: растёт к краю, но не доходит — без крутилки и пульса.
    var progress by remember { mutableStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(busy) {
        progress = 0f
        val start = System.currentTimeMillis()
        while (busy) {
            val sec = (System.currentTimeMillis() - start) / 1000f
            progress = 1f - kotlin.math.exp(-sec / 8f) * 0.95f
            kotlinx.coroutines.delay(200)
        }
    }
    val shape = RoundedCornerShape(31.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        val head = when {
            listening -> "Слушаю — говори сколько нужно. Нажми микрофон ещё раз — отдам Claude"
            busy -> "Claude думает… Можно уходить на другие разделы — изменения появятся сами"
            else -> "Скажи, что сделать $about"
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .glass(shape, mode.glass.copy(inkAlpha = maxOf(mode.glass.inkAlpha, 0.9f)))
                .clip(shape)
                .drawBehind {
                    if (busy) drawRect(mode.tint.copy(alpha = 0.25f), size = androidx.compose.ui.geometry.Size(size.width * progress, size.height))
                }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // «Стоп» — в цвете Дел, без красного: это «готово», а не тревога.
            GlyphButton(
                if (listening) Glyphs.Stop else Glyphs.Mic,
                if (listening) "готово — отдать Claude" else "сказать голосом: нажми, говори, нажми ещё раз",
                onClick = onMic,
                tint = if (listening) mode.value else mode.label,
                size = 46.dp,
                enabled = !busy,
            )
            // Над полем, внутри стекла, — о чём строка (или «слушаю», «думает») и что ответил Claude.
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(head)
                        if (card && !listening && !busy) {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = mode.meta))
                            append(" · Opus · видит карточку")
                            pop()
                        }
                    },
                    style = ty.meta,
                    color = mode.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.isNotBlank()) Text(note, style = ty.meta, color = if (noteError) ru.zf.pravka.ui.Ink.Warn else mode.value)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = !busy,
                    textStyle = ty.input.copy(color = if (busy) mode.meta else ru.zf.pravka.ui.Ink.Text),
                    cursorBrush = SolidColor(mode.tint),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).semantics { contentDescription = placeholder },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) Text(placeholder, style = ty.input, color = mode.placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
            }
            val canSend = value.isNotBlank() && !busy
            Box(Modifier.alpha(if (canSend) 1f else 0.35f)) {
                ru.zf.pravka.ui.Key(Glyphs.ArrowUp, "отдать Claude", onClick = { if (canSend) onSend() }, size = 46.dp, enabled = canSend)
            }
        }
    }
}

/**
 * Окно разговора (`showTalk` веба): реплики сверху вниз — что сказал (пузырём
 * справа), ответ Claude, что сделано (дела открываются карточкой), ошибки и
 * «Вернуть» у каждой реплики; внизу — своя строка ответа: микрофон слева,
 * стрелка справа. Ответ уходит с `scope.history`. «Хорошо» или крестик —
 * разговор окончен; новая команда из строки внизу вкладки — новый разговор.
 */
@Composable
internal fun TalkSheet(
    talk: Talk,
    snap: Dela.Snapshot,
    onText: (String) -> Unit,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onUndo: (Int) -> Unit,
) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    val busy = talk.busy.isNotBlank()
    PaperSheet(
        onDismiss = { if (listening) PravkaAccessibilityService.instance?.finishDelaReason(keep = false); onDismiss() },
        title = "Claude",
        subtitle = talk.turns.size.takeIf { it > 1 }?.let { plural(it, "реплика", "реплики", "реплик") },
        footer = {
            GlyphButton(
                if (listening) Glyphs.Stop else Glyphs.Mic,
                if (listening) "готово — отдать Claude" else "ответить голосом: нажми, говори, нажми ещё раз",
                tint = if (listening) mode.value else mode.label,
                enabled = !busy,
                onClick = {
                    val service = PravkaAccessibilityService.instance
                    when {
                        service == null -> Feedback.toast(context, context.getString(R.string.toast_no_service))
                        listening -> if (!service.finishDelaReason(keep = true)) listening = false
                        else -> {
                            val base = talk.text.trim()
                            listening = service.listenForDelaReason("Говори — нажми микрофон ещё раз, отдам Claude") { said ->
                                listening = false
                                val full = (base + " " + said.trim()).trim()
                                onText(full)
                                if (said.isNotBlank()) onSend(full)
                            }
                        }
                    }
                },
            )
            Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                PaperField(
                    value = talk.text,
                    onValueChange = onText,
                    placeholder = if (busy) "Claude думает…" else if (listening) "Слушаю…" else "Ответить Claude…",
                    singleLine = false,
                    maxLines = 4,
                    enabled = !busy,
                )
            }
            Box(Modifier.alpha(if (talk.text.isNotBlank() && !busy) 1f else 0.35f)) {
                ru.zf.pravka.ui.Key(Glyphs.ArrowUp, "ответить Claude", onClick = { if (talk.text.isNotBlank() && !busy) onSend(talk.text.trim()) }, size = 44.dp)
            }
            PaperTextButton("Хорошо", onClick = onDismiss)
        },
    ) {
        talk.turns.forEachIndexed { i, t ->
            if (i > 0) RowRule()
            TurnView(t, snap, onOpen = onOpen, onUndo = { onUndo(i) })
        }
        if (busy) {
            if (talk.turns.isNotEmpty()) RowRule()
            Said(talk.busy)
            Text("Claude думает…", style = ty.label, color = mode.meta)
        }
        if (talk.error.isNotBlank()) PaperHint(talk.error, ru.zf.pravka.ui.Ink.Warn)
    }
}

/** Что сказал — пузырём справа. */
@Composable
private fun Said(text: String) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            style = ru.zf.pravka.ui.LocalPravkaType.current.body,
            color = ru.zf.pravka.ui.Ink.Text,
            modifier = Modifier.padding(start = 40.dp, top = 6.dp, bottom = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(mode.tint.copy(alpha = 0.18f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Реплика (`turnView` веба): что сказал, ответ, что сделано, ошибки и «Вернуть». Номеров дел нет. */
@Composable
private fun TurnView(t: DelaAsk.Turn, snap: Dela.Snapshot, onOpen: (String) -> Unit, onUndo: () -> Unit) {
    val r = t.result
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    Said(t.said)
    val nothing = !r.did && r.errors.isEmpty()
    if (r.reply.isNotBlank()) Text(r.reply, style = ty.body, color = ru.zf.pravka.ui.Ink.Text)
    else if (nothing) Text("Ничего не поменял.", style = ty.label, color = mode.meta)
    for (x in r.decided) {
        AskLine(DelaAsk.decidedWord(x), if (x.accept) "" else x.reason) { if (x.accept && x.id.isNotBlank()) onOpen(x.id) }
    }
    for (ch in r.changed) {
        val title = ch.after.optString("title").takeIf { ch.after.has("title") && it.isNotBlank() } ?: ch.title
        AskLine(title, DelaAsk.describe(ch, snap)) { onOpen(ch.id) }
    }
    if (r.tasks.isNotEmpty()) {
        PaperHint(if (r.isNew) "Записал:" else "Новые:")
        for (x in r.tasks) {
            val meta = listOfNotNull(
                snap.projects[x.projectId]?.name ?: x.projectName.takeIf { it.isNotBlank() },
                x.personId.takeIf { it.isNotBlank() }?.let { pid ->
                    (if (x.ball == Dela.WAITING) "жду " else if (x.ball == Dela.AGENDA) "повестка " else "") + (snap.people[pid]?.label ?: x.who)
                },
                x.dueDate.takeIf { it.isNotBlank() }?.let { "срок " + DelaAsk.ddmm(it, snap.today) },
                x.estimateMin.takeIf { it > 0 }?.let { "$it мин" },
            ).joinToString(" · ")
            AskLine(x.title, meta) { onOpen(x.id) }
        }
    }
    if (r.notes.isNotEmpty()) {
        PaperHint("В хронологию:")
        for (n in r.notes) PaperHint(n.summary + (snap.projects[n.projectId]?.name?.let { " · $it" } ?: ""), ru.zf.pravka.ui.Ink.Text)
    }
    // Правки карточки — проекты, люди, хронология (`crm[]`): словами, как их назвал сервер.
    if (r.crm.isNotEmpty()) {
        PaperHint("Проекты, люди, хронология:")
        for (cr in r.crm) PaperHint(cr.what, ru.zf.pravka.ui.Ink.Text)
    }
    if (r.errors.isNotEmpty()) PaperHint("Не вышло: " + r.errors.joinToString("; "), ru.zf.pravka.ui.Ink.Warn)
    when {
        t.undone -> Text("Вернул как было", style = ty.label, color = mode.meta)
        r.undoable -> Row {
            Spacer(Modifier.weight(1f))
            val dropped = r.decided.any { !it.accept }
            PaperTextButton(if (dropped) "Вернуть принятое" else "Вернуть", icon = Glyphs.Undo, onClick = onUndo)
        }
    }
}

@Composable
private fun AskLine(title: String, meta: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** «1 дело», «3 дела», «5 дел». */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}
