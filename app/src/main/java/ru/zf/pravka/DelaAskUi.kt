package ru.zf.pravka

import androidx.compose.foundation.clickable
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

/**
 * Одна команда за раз на всю вкладку. Ключ — откуда она: «screen» (строка над
 * видом) или «task:<id>» (микрофон в карточке): по нему каждая сторона видит
 * своё «правит…» и свою ошибку.
 */
internal class AskState {
    var running by mutableStateOf("")
    var errors by mutableStateOf(mapOf<String, String>())
    var shown by mutableStateOf<AskShown?>(null)

    fun error(key: String): String = errors[key].orEmpty()
}

internal const val ASK_SCREEN = "screen"
internal fun askTaskKey(id: String) = "task:$id"

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
 * Строка «Claude» над видом: команда на всё, что на экране. Пока Claude
 * правит — поле не трогается; не вышло (нет сети, сервер отказал) — причина
 * целиком красным, текст остаётся в поле: «Ещё раз» — тем же касанием.
 */
@Composable
internal fun AskBar(
    screen: AskScreen,
    text: String,
    onText: (String) -> Unit,
    running: Boolean,
    error: String,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    var listening by remember { mutableStateOf(false) }
    PaperCard(
        label = "Claude · " + screen.title.take(40),
        info = if (screen.card) {
            // Карточка (06.10.2026, docs/dela-phone-4.md): сервер берёт Opus 5.5 и даёт ему её целиком.
            "Claude видит эту карточку целиком — сделки, людей, хронологию — и правит не только дела: " +
                "«созвонились, ждут КП к пятнице», «Иван теперь CFO», «сделка — в мандат». Новые дела лягут сюда. " +
                "Правит сервер, в карточке — всегда Opus 5.5; «Вернуть всё» — в итоге."
        } else {
            "Команда на дела этого экрана — Claude видит их в том же порядке. «Все просроченные — на завтра», " +
                "«бюджет первым делом, сегодня», «это Наташе». Если команда про новые дела — Claude их заведёт" +
                " (на странице проекта или человека — туда). Правит сервер, модель — в его «Настройках»."
        },
        trailing = { GlyphButton(Glyphs.Close, "убрать строку Claude", onClick = onClose, size = 30.dp) },
    ) {
        PaperField(
            value = text,
            onValueChange = onText,
            placeholder = if (listening) "Слушаю — говори команду…" else if (screen.card) "Скажи, что сделать с карточкой" else "«все просроченные — на завтра»",
            singleLine = false,
            maxLines = 4,
            enabled = !running,
        )
        if (running) ThinkingLine("Claude правит…", Modifier.padding(top = 6.dp))
        if (error.isNotBlank()) PaperHint(error, MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AskMic(listening, { listening = it }, enabled = !running, onText = { said ->
                val full = (text.trim() + " " + said.trim()).trim()
                onText(full)
                if (full.isNotBlank()) onSend(full)
            })
            Spacer(Modifier.weight(1f))
            PaperButton(
                if (error.isNotBlank()) "Ещё раз" else "Отдать Claude",
                icon = Glyphs.Ask,
                primary = true,
                enabled = text.isNotBlank() && !running,
                onClick = { onSend(text.trim()) },
            )
        }
    }
}

/**
 * Поле команды под строкой дела — как `askBox` веба: что сказано (правится и
 * руками), «Claude правит…», причина отказа красным. Текст не пропадает, пока
 * Claude не ответил: не вышло — «Ещё раз» тем же касанием.
 */
@Composable
internal fun AskInline(
    text: String,
    onText: (String) -> Unit,
    listening: Boolean,
    running: Boolean,
    error: String,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, bottom = 6.dp)) {
        PaperField(
            value = text,
            onValueChange = onText,
            placeholder = if (listening) "Слушаю — что сделать с делом…" else "«сделано», «на пятницу», «это Наташе», «это не моё»",
            singleLine = false,
            maxLines = 3,
            enabled = !running,
        )
        if (running) ThinkingLine("Claude правит…", Modifier.padding(top = 4.dp))
        if (error.isNotBlank() && !running) PaperHint(error, c.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(
                if (listening) Glyphs.Stop else Glyphs.Mic,
                if (listening) "хватит слушать" else "сказать голосом",
                tint = c.primary,
                enabled = !running,
                onClick = onMic,
                size = 34.dp,
            )
            Spacer(Modifier.weight(1f))
            GlyphButton(Glyphs.Close, "закрыть", onClick = onClose, size = 34.dp)
            PaperTextButton(
                if (error.isNotBlank()) "Ещё раз" else "Отдать Claude",
                icon = Glyphs.Ask,
                enabled = text.isNotBlank() && !running,
                onClick = { onSend(text.trim()) },
            )
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
            PaperHint("В карточке:")
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
