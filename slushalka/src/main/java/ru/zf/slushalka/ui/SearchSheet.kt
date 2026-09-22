package ru.zf.slushalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.GuideState
import ru.zf.slushalka.ask.MeaningSearch
import ru.zf.slushalka.ask.VoiceInput
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.BookText

/**
 * Найти по смыслу. Сначала - по справочнику, за центы; точное место в главе -
 * по приметам даром, иначе одним коротким запросом про эту главу. Без
 * справочника - по тексту прочитанного, с ценой на кнопке (см. MeaningSearch).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Найти по смыслу", style = MaterialTheme.typography.titleLarge)
            Text(
                "«Где Анна впервые говорит про Лизл», «где был разговор про балет». Только в прочитанном.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Что ищешь") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { toggleVoice() }) {
                        Icon(
                            Glyphs.Mic,
                            contentDescription = if (listening) "Хватит" else "Сказать",
                            tint = if (listening) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            when {
                guide != null && readChapters > 0 -> {
                    Button(
                        enabled = busy == null && query.isNotBlank(),
                        onClick = { runGuide() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Найти по справочнику") }
                }
                guide != null -> Text(
                    "Справочник откроется, когда дочитаешь первую главу. Пока можно искать по тексту.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "Справочника по этой книге ещё нет, а поиск по нему в десятки раз дешевле: " +
                        "закажи его в «Справочнике». Пока - по тексту прочитанного.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            busy?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            hits?.let { list ->
                Spacer(Modifier.height(12.dp))
                if (list.isEmpty()) {
                    Text(
                        if (byText) "В прочитанном ничего похожего не нашлось."
                        else "По справочнику ничего похожего - попробуй по тексту.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.heightIn(max = 420.dp)) {
                    list.forEach { h ->
                        val ch = text.chapters.getOrNull(h.chapter - 1)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(enabled = busy == null) { go(h) }
                                .padding(12.dp),
                        ) {
                            Text(
                                "Глава ${h.chapter}" + (ch?.title?.takeIf { it.isNotBlank() }?.let { ". $it" } ?: ""),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (h.why.isNotBlank()) Text(h.why, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (h.charOffset != null) "тап - к этому месту" else "тап - найти место в главе",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // По тексту - отдельной кнопкой и с ценой: это уже не центы.
            if (cutoff > 0 && (guide == null || hits != null || readChapters == 0)) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    enabled = busy == null && query.isNotBlank(),
                    onClick = { runText() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Искать по тексту прочитанного · ≈ %.2f $".format(textUsd)) }
            }
            if (spent > 0) {
                Row {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "потрачено %.3f $".format(spent),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
