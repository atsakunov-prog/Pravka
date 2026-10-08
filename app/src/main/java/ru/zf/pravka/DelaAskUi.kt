package ru.zf.pravka

import androidx.compose.foundation.clickable
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

/** Итог команды — на лист поверх вкладки; ошибка у закрытой карточки — сюда же, с текстом. */
internal data class AskShown(
    val key: String,
    val text: String,
    val scope: JSONObject,
    val result: DelaAsk.Result? = null,
    val error: String = "",
)

/** Одна команда за раз на всю вкладку — из строки Claude внизу ([ASK_SCREEN]). */
internal class AskState {
    var running by mutableStateOf("")
    var errors by mutableStateOf(mapOf<String, String>())
    var shown by mutableStateOf<AskShown?>(null)

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
 * Итог команды: слова Claude, что поменялось («срок 06.10, в «Сейчас»»), новые
 * дела и заметки — и «Вернуть всё» одним движением (контракт, `ask.undo`).
 * Ошибка у команды, чья карточка уже закрыта, — тоже здесь: с текстом и «Ещё раз».
 */
@Composable
internal fun AskResultSheet(
    shown: AskShown,
    snap: Dela.Snapshot,
    running: Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onUndo: (DelaAsk.Result) -> Unit,
    onRetry: (String) -> Unit,
) {
    val r = shown.result
    val c = MaterialTheme.colorScheme
    var retry by remember(shown) { mutableStateOf(shown.text) }
    val head = when {
        r == null -> "Claude: не вышло"
        r.isNew -> {
            val said = listOfNotNull(
                plural(r.tasks.size, "дело", "дела", "дел").takeIf { r.tasks.isNotEmpty() },
                plural(r.notes.size, "заметка", "заметки", "заметок").takeIf { r.notes.isNotEmpty() },
            ).joinToString(" и ")
            if (said.isBlank()) "Claude не нашёл тут дел" else "Claude записал $said"
        }
        r.count > 0 || r.crm.isNotEmpty() -> "Claude: " + listOfNotNull(
            plural(r.count, "дело", "дела", "дел").takeIf { r.count > 0 },
            "карточка: ${r.crm.size}".takeIf { r.crm.isNotEmpty() },
        ).joinToString(", ")
        else -> "Claude ничего не менял"
    }
    PaperSheet(
        onDismiss = onDismiss,
        title = head,
        icon = Glyphs.Ask,
        subtitle = shown.scope.optString("title").takeIf { it.isNotBlank() },
        footer = {
            // «Вернуть всё» — и дела, и правки карточки (crm[].undo, 06.10.2026), как в вебе.
            if (r != null && r.undoable) {
                PaperTextButton(if (r.isNew && r.changed.isEmpty() && r.crm.isEmpty()) "Отменить дела" else "Вернуть всё", icon = Glyphs.Undo, onClick = { onUndo(r) })
            }
            Spacer(Modifier.weight(1f))
            if (r == null) PaperButton("Ещё раз", icon = Glyphs.Ask, primary = true, enabled = retry.isNotBlank() && !running, onClick = { onRetry(retry.trim()) })
            else PaperButton("Хорошо", icon = Glyphs.Check, primary = true, onClick = onDismiss)
        },
    ) {
        if (r == null) {
            PaperHint(shown.error, c.error)
            PaperField(value = retry, onValueChange = { retry = it }, label = "Команда", singleLine = false, maxLines = 4, enabled = !running)
            if (running) ThinkingLine("Claude правит…")
            return@PaperSheet
        }
        if (r.reply.isNotBlank()) Text(r.reply, style = MaterialTheme.typography.bodyMedium)
        r.changed.forEachIndexed { i, ch ->
            if (i > 0 || r.reply.isNotBlank()) RowRule()
            val title = ch.after.optString("title").takeIf { ch.after.has("title") && it.isNotBlank() } ?: ch.title
            AskLine("#${ch.num} $title", DelaAsk.describe(ch, snap)) { onOpen(ch.id) }
        }
        if (r.tasks.isNotEmpty()) {
            PaperHint(if (r.changed.isEmpty()) "Заведено:" else "Новые:")
            for (t in r.tasks) {
                val meta = listOfNotNull(
                    snap.projects[t.projectId]?.name ?: t.projectName.takeIf { it.isNotBlank() },
                    t.personId.takeIf { it.isNotBlank() }?.let { pid ->
                        (if (t.ball != Dela.MINE) (if (t.ball == Dela.WAITING) "жду " else "повестка ") else "") + (snap.people[pid]?.label ?: t.who)
                    },
                    t.dueDate.takeIf { it.isNotBlank() }?.let { "срок " + DelaAsk.ddmm(it, snap.today) },
                    t.estimateMin.takeIf { it > 0 }?.let { "$it мин" },
                ).joinToString(" · ")
                AskLine("#${t.num} ${t.title}", meta) { onOpen(t.id) }
            }
        }
        if (r.notes.isNotEmpty()) {
            PaperHint("В хронологию:")
            for (n in r.notes) {
                PaperHint(n.summary + (snap.projects[n.projectId]?.name?.let { " · $it" } ?: ""), c.onSurface)
            }
        }
        // Правки карточки — хронология, люди, сделки (`crm[]`): словами, как их назвал сервер.
        if (r.crm.isNotEmpty()) {
            PaperHint("Проекты, люди, хронология:")
            for (cr in r.crm) PaperHint(cr.what, c.onSurface)
        }
        if (r.errors.isNotEmpty()) PaperHint("Не вышло: " + r.errors.joinToString("; "), c.error)
        if (r.count == 0 && r.crm.isEmpty() && r.errors.isEmpty() && r.reply.isBlank()) PaperHint("Ничего не поменялось.")
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
