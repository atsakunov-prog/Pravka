package ru.zf.slushalka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import ru.zf.slushalka.ask.GuideState
import ru.zf.slushalka.ask.MeaningSearch
import ru.zf.slushalka.speech.VoiceInput
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.BookText

/**
 * Найти по смыслу. Сначала - по справочнику, за центы; точное место в главе -
 * по приметам даром, иначе одним коротким запросом про эту главу. Без
 * справочника - по тексту прочитанного, с ценой на кнопке (см. MeaningSearch).
 */
@Composable
fun SearchSheet(
    app: SlushalkaApp,
    book: Book,
    text: BookText,
    /** Место чтения: дальше него не ищем - там спойлеры. */
    cutoff: Int,
    hasMic: () -> Boolean,
    onNeedMic: () -> Unit,
    onGo: (charOffset: Int) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by app.guide.states.collectAsState()
    val guide = states[book.id]?.takeIf { it.status == GuideState.Status.READY }?.guide
    // Дочитанные главы: справочник по текущей рассказал бы её конец.
    val readChapters = text.chapterIndexAt(cutoff)

    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<MeaningSearch.Hit>?>(null) }
    var byText by remember { mutableStateOf(false) }
    var spent by remember { mutableStateOf(0.0) }
    var listening by remember { mutableStateOf(false) }
    val voice = remember { VoiceInput(context) }
    DisposableEffect(Unit) { onDispose { voice.cancel() } }
    val textUsd = remember(text, cutoff) { app.search.textSearchUsd(text, cutoff) }

    fun runGuide() {
        val g = guide ?: return
        val q = query.trim()
        if (q.isEmpty() || busy != null) return
        busy = "Листаю справочник…"
        error = null
        byText = false
        scope.launch {
            app.search.byGuide(book, text, g, readChapters, q)
                .onSuccess { hits = it.hits; spent += it.costUsd }
                .onFailure { error = it.message ?: "Не вышло" }
            busy = null
        }
    }

    fun runText() {
        val q = query.trim()
        if (q.isEmpty() || busy != null) return
        busy = "Читаю прочитанное…"
        error = null
        byText = true
        scope.launch {
            app.search.byText(book, text, cutoff, q)
                .onSuccess { hits = it.hits; spent += it.costUsd }
                .onFailure { error = it.message ?: "Не вышло" }
            busy = null
        }
    }

    fun go(hit: MeaningSearch.Hit) {
        hit.charOffset?.let { return onGo(it) }
        // Приметы в главе не сошлись - спросить про одну эту главу.
        busy = "Ищу место в главе ${hit.chapter}…"
        scope.launch {
            val at = app.search.pinpoint(book, text, hit, cutoff, query.trim()).getOrNull()
            busy = null
            onGo(at ?: text.chapters.getOrNull(hit.chapter - 1)?.start ?: 0)
        }
    }

    fun toggleVoice() {
        if (listening) {
            voice.stop()
            return
        }
        if (!hasMic()) return onNeedMic()
        voice.onText = { query = it }
        voice.onEnd = { heard ->
            listening = false
            if (heard.isNotBlank()) {
                query = heard
                if (guide != null && readChapters > 0) runGuide()
            }
        }
        voice.onError = { listening = false; error = it }
        voice.start()
        listening = true
    }

    val c = MaterialTheme.colorScheme
    PaperSheet(
        app = app,
        onClose = onClose,
        icon = Glyphs.ManageSearch,
        title = "Найти по смыслу",
        subtitle = "Только в прочитанном",
    ) {
        Spacer(Modifier.height(14.dp))
        // Поле - такое же, как строка вопроса: бумага, микрофон рядом.
        PaperField(
            value = query,
            onValue = { query = it },
            placeholder = if (listening) "Слушаю…" else "Где был разговор про балет…",
            leading = Glyphs.ManageSearch,
        ) {
            PaperIconButton(Glyphs.Mic, if (listening) "Хватит" else "Сказать", active = listening) { toggleVoice() }
        }
        Spacer(Modifier.height(12.dp))
        when {
            guide != null && readChapters > 0 -> PaperButton(
                "Найти по справочнику",
                icon = Glyphs.MenuBook,
                primary = true,
                enabled = busy == null && query.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { runGuide() }
            guide != null -> PaperNote("Справочник откроется, когда дочитаешь первую главу. Пока можно искать по тексту.")
            else -> PaperNote(
                "Справочника по этой книге ещё нет, а поиск по нему в десятки раз дешевле: " +
                    "закажи его в «Справочнике». Пока - по тексту прочитанного.",
            )
        }

        busy?.let { PaperBusy(it) }
        error?.let { PaperError(it) }

        hits?.let { list ->
            if (list.isEmpty()) {
                PaperNote(
                    if (byText) "В прочитанном ничего похожего не нашлось."
                    else "По справочнику ничего похожего - попробуй по тексту.",
                    Modifier.padding(top = 12.dp),
                )
            } else {
                PaperLabel("Нашлось")
            }
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { h ->
                    val ch = text.chapters.getOrNull(h.chapter - 1)
                    PaperCard(onClick = if (busy == null) ({ go(h) }) else null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Глава ${h.chapter}" + (ch?.title?.takeIf { it.isNotBlank() }?.let { ". $it" } ?: ""),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Glyphs.ChevronRight, contentDescription = null, tint = c.onSurfaceVariant)
                        }
                        if (h.why.isNotBlank()) Text(h.why, style = MaterialTheme.typography.bodyMedium)
                        PaperNote(if (h.charOffset != null) "к этому месту" else "найти место в главе")
                    }
                }
            }
        }

        // По тексту - отдельной кнопкой и с ценой: это уже не центы.
        if (cutoff > 0 && (guide == null || hits != null || readChapters == 0)) {
            Spacer(Modifier.height(12.dp))
            PaperButton(
                "По тексту прочитанного · ≈ %.2f $".format(textUsd),
                icon = Glyphs.AutoStories,
                primary = guide == null || readChapters == 0,
                enabled = busy == null && query.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { runText() }
        }
        if (spent > 0) {
            Text(
                "потрачено %.3f $".format(spent),
                style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(top = 6.dp),
            )
        }
    }
}
