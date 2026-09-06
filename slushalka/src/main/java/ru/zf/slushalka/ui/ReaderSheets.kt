package ru.zf.slushalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.GuideState
import ru.zf.slushalka.ask.Prompts
import ru.zf.slushalka.text.Block
import ru.zf.slushalka.text.BookText

/**
 * Содержание: главы книги, тап - переход. Раньше под этим словом жил
 * пересказ; теперь он зовётся «Напомнить», а содержание - это содержание.
 */
@Composable
fun ContentsSheet(
    text: BookText,
    currentOffset: Int,
    onPick: (charOffset: Int) -> Unit,
    onClose: () -> Unit,
) {
    val here = text.chapterIndexAt(currentOffset)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (here - 2).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Содержание") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), state = listState) {
                itemsIndexed(text.chapters) { i, ch ->
                    val current = i == here
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onPick(ch.start) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ch.title.ifBlank { "Глава ${i + 1}" },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "стр. ${text.pageOf(ch.start)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}

/**
 * Долгое нажатие на абзац. Абзац разложен на фразы: тап по фразе выделяет её,
 * вопрос уходит про выделенное (или про абзац целиком, если ничего не
 * выделено). Тут же - справочник по упомянутым героям и, у аудиокниги,
 * прежние «Я тут» и «Слушать отсюда».
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ParagraphSheet(
    app: SlushalkaApp,
    text: BookText,
    block: Block,
    hasAudio: Boolean,
    playAbsMs: Long,
    onAsk: (atChar: Int, question: String?, quote: String) -> Unit,
    onAnchor: () -> Unit,
    onListen: () -> Unit,
    onGuide: (query: String) -> Unit,
    onClose: () -> Unit,
) {
    val book by app.state.current.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sentences = remember(block) { splitSentences(block.text) }
    var selected by remember { mutableStateOf(setOf<Int>()) }

    val selection = remember(selected, sentences) {
        (if (selected.isEmpty()) block.text else selected.sorted().joinToString(" ") { sentences[it] })
            .take(QUOTE_MAX)
    }
    // Вопрос про кусок - с контекстом до конца этого абзаца: то, что на
    // странице, читатель уже видит, а дальше заглядывать незачем.
    val at = block.end.coerceAtMost(text.length)

    // Герои из справочника, упомянутые в выделенном, - если справочник готов.
    val mentioned = remember(selection, book?.id) {
        val b = book ?: return@remember emptyList()
        val st = app.guide.state(b.id)
        if (st?.status != GuideState.Status.READY) return@remember emptyList()
        val upTo = text.chapterIndexAt(block.start) + 1
        st.guide?.all.orEmpty()
            .filter { it.visibleAt(upTo) != null && it.mentionedIn(selection) }
            .take(6)
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Этот кусок", style = MaterialTheme.typography.titleLarge)
            Text(
                if (sentences.size > 1) "Тапни фразы, о которых спросить, - или спрашивай обо всём абзаце."
                else "Спросить про этот абзац.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                sentences.forEachIndexed { i, s ->
                    val on = i in selected
                    Text(
                        s,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (on) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable(enabled = sentences.size > 1) {
                                selected = if (on) selected - i else selected + i
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(10.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Prompts.FRAGMENT_PRESETS.forEach { (label, prompt) ->
                    AssistChip(onClick = { onAsk(at, prompt, selection) }, label = { Text(label) })
                }
                AssistChip(onClick = { onAsk(at, null, selection) }, label = { Text("Свой вопрос…") })
            }

            if (mentioned.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "В справочнике:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    mentioned.forEach { e ->
                        AssistChip(onClick = { onGuide(e.name) }, label = { Text(e.name) })
                    }
                }
            }

            if (hasAudio) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (playAbsMs > 0)
                        "«Я тут» скажет карте, что на ${formatClock(playAbsMs)} записи читают это место - " +
                            "и переходы со звука станут точными."
                    else "Запись ещё не играла, сверять не с чем.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    if (playAbsMs > 0) TextButton(onClick = onAnchor) { Text("Я тут") }
                    TextButton(onClick = onListen) { Text("Слушать отсюда") }
                }
            }
        }
    }
}

/** Длиннее в вопрос не уезжает: контекст модель и так получает целиком. */
private const val QUOTE_MAX = 1500

/**
 * Абзац на фразы: конец фразы - точка, восклицательный, вопросительный или
 * многоточие, за которыми пробел (иначе инициалы и сокращения рвали бы
 * фразу). Совсем короткие обрывки («Да.», «Нет!») приклеиваются к предыдущей:
 * отдельно спрашивать о них нечего.
 */
internal fun splitSentences(text: String): List<String> {
    val out = ArrayList<String>()
    var start = 0
    var i = 0
    while (i < text.length) {
        if (text[i] in ".!?…") {
            var j = i + 1
            while (j < text.length && text[j] in "»\"')") j++
            if (j >= text.length || text[j].isWhitespace()) {
                val s = text.substring(start, j).trim()
                if (s.isNotEmpty()) {
                    if (s.length < 8 && out.isNotEmpty()) out[out.lastIndex] = out.last() + " " + s else out.add(s)
                }
                start = j
                i = j
                continue
            }
        }
        i++
    }
    val tail = text.substring(start).trim()
    if (tail.isNotEmpty()) {
        if (tail.length < 8 && out.isNotEmpty()) out[out.lastIndex] = out.last() + " " + tail else out.add(tail)
    }
    return out.ifEmpty { listOf(text) }
}
