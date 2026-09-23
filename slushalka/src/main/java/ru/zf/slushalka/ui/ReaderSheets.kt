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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.GuideState
import ru.zf.slushalka.ask.Prompts
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.text.Block
import ru.zf.slushalka.text.BookText

/**
 * Содержание: главы книги, тап - переход. Раньше под этим словом жил
 * пересказ; теперь он зовётся «Напомнить», а содержание - это содержание.
 */
@Composable
fun ContentsSheet(
    app: SlushalkaApp,
    text: BookText,
    currentOffset: Int,
    onPick: (charOffset: Int) -> Unit,
    onClose: () -> Unit,
) {
    val here = text.chapterIndexAt(currentOffset)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (here - 2).coerceAtLeast(0))
    val c = MaterialTheme.colorScheme
    PaperSheet(
        app = app,
        onClose = onClose,
        icon = Glyphs.Toc,
        title = "Содержание",
        subtitle = text.title.takeIf { it.isNotBlank() }?.let { "$it · ${text.chapters.size} гл." },
        tall = text.chapters.size > 10,
        scroll = false,
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().then(if (text.chapters.size > 10) Modifier.weight(1f) else Modifier.heightIn(max = 520.dp)),
            state = listState,
        ) {
            itemsIndexed(text.chapters) { i, ch ->
                val current = i == here
                // Как оглавление в конце книги: название, отточие, страница; своя
                // глава - закрашена, прочитанные - тише.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (current) c.primaryContainer else Color.Transparent)
                        .clickable { onPick(ch.start) }
                        .padding(vertical = 12.dp, horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ch.title.ifBlank { "Глава ${i + 1}" },
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = fontOf(Settings.FONT_BOOK)),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (i < here) c.onSurfaceVariant else c.onSurface,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${text.pageOf(ch.start)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = c.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Долгое нажатие на абзац. Абзац разложен на фразы: тап по фразе выделяет её,
 * вопрос уходит про выделенное (или про абзац целиком, если ничего не
 * выделено). Тут же - справочник по упомянутым героям и, у аудиокниги,
 * прежние «Я тут» и «Слушать отсюда».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParagraphSheet(
    app: SlushalkaApp,
    text: BookText,
    block: Block,
    hasAudio: Boolean,
    playAbsMs: Long,
    /** Выделенный кусок (тапами или обводкой): он уже выбран, фразы не предлагаются. */
    lasso: String? = null,
    lassoEnd: Int? = null,
    onAsk: (atChar: Int, question: String?, quote: String) -> Unit,
    onAnchor: () -> Unit,
    onListen: () -> Unit,
    onGuide: (query: String) -> Unit,
    /** Цитата картинкой - нарисовать выделенное карточкой и отдать. */
    onQuoteCard: ((String) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val book by app.state.current.collectAsState()
    val sentences = remember(block) { splitSentences(block.text) }
    var selected by remember { mutableStateOf(setOf<Int>()) }

    val selection = remember(selected, sentences, lasso) {
        (lasso ?: if (selected.isEmpty()) block.text else selected.sorted().joinToString(" ") { sentences[it] })
            .trim()
            .take(QUOTE_MAX)
    }
    // Вопрос про кусок - с контекстом до конца этого абзаца (или обводки): то,
    // что на странице, читатель уже видит, а дальше заглядывать незачем.
    val at = (lassoEnd ?: block.end).coerceAtMost(text.length)

    // Герои из справочника, упомянутые в выделенном, - если справочник готов.
    val mentioned = remember(selection, book?.id) {
        val b = book ?: return@remember emptyList()
        val st = app.guide.state(b.id)
        if (st?.status != GuideState.Status.READY) return@remember emptyList()
        // Видны только дочитанные главы: текущая ещё не кончилась, и запись о
        // ней рассказала бы то, что на этой странице ещё впереди.
        val upTo = text.chapterIndexAt(block.start)
        st.guide?.all.orEmpty()
            .filter { it.visibleAt(upTo) != null && it.mentionedIn(selection) }
            .take(6)
    }

    val c = MaterialTheme.colorScheme
    PaperSheet(
        app = app,
        onClose = onClose,
        icon = Glyphs.FormatQuote,
        title = if (lasso != null) "Выделенное" else "Этот кусок",
        subtitle = text.chapterAt(block.start)?.title?.takeIf { it.isNotBlank() },
    ) {
        Spacer(Modifier.height(12.dp))
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            if (lasso != null) {
                PaperQuote(selection, maxLines = 12)
            } else {
                if (sentences.size > 1) PaperNote("Тапни фразы, о которых спросить, - или спрашивай обо всём абзаце.", Modifier.padding(bottom = 6.dp))
                sentences.forEachIndexed { i, s ->
                    val on = i in selected
                    Text(
                        s,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontOf(Settings.FONT_BOOK)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) c.primaryContainer else Color.Transparent)
                            .clickable(enabled = sentences.size > 1) {
                                selected = if (on) selected - i else selected + i
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        PaperLabel("Спросить")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Prompts.FRAGMENT_PRESETS.forEach { (label, prompt) ->
                PaperChip(label, selected = false) { onAsk(at, prompt, selection) }
            }
            PaperChip("Свой вопрос…", selected = false, icon = Glyphs.QuestionAnswer) { onAsk(at, null, selection) }
        }

        if (mentioned.isNotEmpty()) {
            PaperLabel("В справочнике")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mentioned.forEach { e ->
                    PaperChip(e.name, selected = false, icon = Glyphs.MenuBook) { onGuide(e.name) }
                }
            }
        }

        PaperLabel("Ещё")
        if (onQuoteCard != null) {
            PaperRow(Glyphs.Image, "Цитата картинкой", "Карточкой на бумаге книги - в мессенджер или сторис") { onQuoteCard(selection) }
        }
        if (hasAudio) {
            PaperRow(Glyphs.Headphones, "Слушать отсюда", "Запись с этого места") { onListen() }
            if (playAbsMs > 0) {
                PaperRow(
                    Icons.Default.Place,
                    "Я тут",
                    "На ${formatClock(playAbsMs)} записи читают это место - переходы со звука станут точнее",
                ) { onAnchor() }
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
