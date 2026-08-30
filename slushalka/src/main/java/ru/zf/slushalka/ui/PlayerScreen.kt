package ru.zf.slushalka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Bookmark

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    app: SlushalkaApp,
    onBack: () -> Unit,
    onAsk: () -> Unit,
    onRead: () -> Unit,
    onSettings: () -> Unit,
) {
    val state = app.state
    val book by state.current.collectAsState()
    val play by app.player.state.collectAsState()
    val prefs by state.prefs.collectAsState()
    val busy by state.busy.collectAsState()
    val recapOffer by state.recapOffer.collectAsState()
    val alignment by state.alignment.collectAsState()
    val markup by state.markupProgress.collectAsState()
    val bookText by state.text.collectAsState()
    val scope = rememberCoroutineScope()

    var showChapters by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showMarks by remember { mutableStateOf(false) }
    var showRecap by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var fullPicture by remember { mutableStateOf<java.io.File?>(null) }

    val b = book
    if (b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Книга не выбрана") }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        b.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К библиотеке")
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
    ) { padding ->
        // Картинка этого места книги: становится текущей за страницу до того,
        // как до неё дойдёт текст. Нет такой - на месте остаётся обложка.
        val livePicture = remember(bookText, play.absMs / 5000, alignment) {
            val at = alignment?.charAt(play.absMs)
            if (at == null) null else bookText?.pictureAt(at)?.takeIf { it.file.isNotBlank() }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Разложенный Fold - две колонки: обложке не место в узкой полоске
            // сверху, когда рядом полэкрана пустует.
            val wide = maxWidth > 620.dp
            val controls: @Composable () -> Unit = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 520.dp),
                ) {
                    Text(
                        b.title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (b.author.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            b.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${play.fileName.substringBeforeLast('.')} · " +
                            "${play.fileIndex + 1} из ${b.files.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(14.dp))
                    PositionBar(app, play.absMs, b.totalMs, play.speed)

                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        SkipButton(prefs.skipSec, forward = false) { app.player.skip(-prefs.skipSec) }
                        PlayPauseButton(play.playing) { app.player.playPause() }
                        SkipButton(prefs.skipSec, forward = true) { app.player.skip(prefs.skipSec) }
                    }

                    Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { showSpeed = true },
                            label = { Text(formatSpeed(play.speed)) },
                        )
                        AssistChip(
                            onClick = { showSleep = true },
                            label = {
                                Text(
                                    if (play.sleepLeftMs > 0)
                                        "сон ${(play.sleepLeftMs / 60_000) + 1} м"
                                    else "сон"
                                )
                            },
                        )
                        AssistChip(
                            onClick = { showChapters = true },
                            label = { Text("главы") },
                        )
                        AssistChip(
                            onClick = { showMarks = true },
                            label = { Text("закладки") },
                        )
                        if (b.textDocId != null) {
                            AssistChip(
                                onClick = { showRecap = true },
                                label = { Text("что там было") },
                            )
                        }
                        if (state.picturesOnDisk() > 0) {
                            AssistChip(
                                onClick = { showGallery = true },
                                label = { Text("картинки") },
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onAsk,
                            modifier = Modifier.weight(1f).height(54.dp),
                        ) {
                            Text(
                                if (b.textDocId != null) "Спросить" else "Спросить (нет текста)",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (b.textDocId != null) {
                            OutlinedButton(
                                onClick = onRead,
                                modifier = Modifier.weight(1f).height(54.dp),
                            ) {
                                Text("Читать", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }

                    // Разметка: без неё переход между звуком и текстом
                    // приблизительный, с ней - мгновенный и точный.
                    val needsMarkup = b.textDocId != null && alignment != null &&
                        !state.isMarkedUp() && app.recognizer.supported
                    if (markup != null || needsMarkup) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                val m = markup
                                when {
                                    m != null && m.running -> {
                                        Text(
                                            "Размечаю книгу: ${m.done} из ${m.total}, " +
                                                "нашлось ${m.hits}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = {
                                                if (m.total > 0) m.done.toFloat() / m.total else 0f
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        TextButton(onClick = { state.cancelMarkup() }) {
                                            Text("Остановить")
                                        }
                                    }
                                    m != null && m.note.isNotBlank() -> {
                                        Text(m.note, style = MaterialTheme.typography.bodyMedium)
                                        Row {
                                            TextButton(onClick = { state.dismissMarkupNote() }) {
                                                Text("Понятно")
                                            }
                                            TextButton(onClick = { state.testProbe() }) {
                                                Text("Ещё проба")
                                            }
                                        }
                                    }
                                    else -> {
                                        Text(
                                            "Книга не размечена: переход между звуком и текстом " +
                                                "попадёт примерно. Разметка пройдёт по записи " +
                                                "пробами прямо на телефоне и ляжет файлом рядом " +
                                                "с книгой.",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Row {
                                            TextButton(onClick = { state.markupBook() }) {
                                                Text("Разметить книгу")
                                            }
                                            TextButton(onClick = { state.testProbe() }) {
                                                Text("Одна проба")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (recapOffer) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    "Возвращаешься после перерыва. Напомнить, чем кончилось?",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row {
                                    TextButton(onClick = {
                                        showRecap = true
                                        state.dismissRecap()
                                    }) { Text("Напомни") }
                                    TextButton(onClick = { state.dismissRecap() }) { Text("Не надо") }
                                }
                            }
                        }
                    }

                    play.error?.let { err ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    busy?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (wide) {
                Row(
                    Modifier.fillMaxSize().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    BookArt(
                        app, b, livePicture,
                        Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.large),
                        textSize = 20,
                        onTap = {
                            val f = livePicture?.let { app.texts.pictureFile(b.id, it.file) }
                            if (f != null && f.exists()) fullPicture = f
                            else if (state.picturesOnDisk() > 0) showGallery = true
                        },
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { controls() }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BookArt(
                        app, b, livePicture,
                        Modifier
                            .fillMaxWidth(0.72f)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.large),
                        textSize = 18,
                        onTap = {
                            val f = livePicture?.let { app.texts.pictureFile(b.id, it.file) }
                            if (f != null && f.exists()) fullPicture = f
                            else if (state.picturesOnDisk() > 0) showGallery = true
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    controls()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showChapters) {
        ChaptersDialog(app, onDismiss = { showChapters = false })
    }
    if (showSpeed) {
        SpeedDialog(app, play.speed, onDismiss = { showSpeed = false })
    }
    if (showSleep) {
        SleepDialog(app, play.sleepLeftMs, onDismiss = { showSleep = false })
    }
    if (showMarks) {
        BookmarksDialog(app, onDismiss = { showMarks = false })
    }
    if (showRecap) {
        val cutoff = alignment?.charAt(play.absMs) ?: 0
        RecapSheet(app, cutoffChar = cutoff, absMs = play.absMs, onClose = { showRecap = false })
    }
    if (showGallery) {
        PictureGallery(app) { showGallery = false }
    }
    fullPicture?.let { f -> ImageViewer(f) { fullPicture = null } }
}

/** Полоса позиции: тянется пальцем, во время перетаскивания тик не мешает. */
@Composable
private fun PositionBar(app: SlushalkaApp, absMs: Long, totalMs: Long, speed: Float) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else absMs.toFloat()
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = value.coerceIn(0f, totalMs.toFloat().coerceAtLeast(1f)),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                app.player.seekTo(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..totalMs.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(value.toLong()), style = MaterialTheme.typography.labelMedium)
            Text(
                "осталось ${formatLeft((totalMs - value.toLong()).coerceAtLeast(0), speed)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChaptersDialog(app: SlushalkaApp, onDismiss: () -> Unit) {
    val book = app.state.current.collectAsState().value ?: return
    val play by app.player.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Главы и файлы") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                itemsIndexed(book.files) { i, f ->
                    val here = i == play.fileIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { app.player.jumpToFile(i); onDismiss() }) {
                            Text(
                                text = f.name.substringBeforeLast('.'),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (here) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatSpan(f.durationMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun SpeedDialog(app: SlushalkaApp, current: Float, onDismiss: () -> Unit) {
    var v by remember { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скорость") },
        text = {
            Column {
                Text(formatSpeed(v), style = MaterialTheme.typography.headlineSmall)
                Slider(
                    value = v,
                    onValueChange = { v = (Math.round(it * 20f) / 20f) },
                    valueRange = 0.5f..3.0f,
                    onValueChangeFinished = { app.player.setSpeed(v) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.0f, 1.2f, 1.4f, 1.6f, 2.0f).forEach { preset ->
                        TextButton(onClick = { v = preset; app.player.setSpeed(preset) }) {
                            Text(formatSpeed(preset))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun SleepDialog(app: SlushalkaApp, leftMs: Long, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Уснуть") },
        text = {
            Column {
                if (leftMs > 0) {
                    Text(
                        "Осталось ${(leftMs / 60_000) + 1} мин. Последние двадцать секунд " +
                            "звук уходит в тишину.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { app.player.addSleep(5) }) { Text("+5 мин") }
                        TextButton(onClick = { app.player.setSleep(0); onDismiss() }) {
                            Text("Отменить")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(15, 30, 45, 60).forEach { m ->
                            TextButton(onClick = { app.player.setSleep(m); onDismiss() }) {
                                Text("$m")
                            }
                        }
                    }
                    TextButton(onClick = {
                        app.player.setSleep(0, untilChapterEnd = true)
                        onDismiss()
                    }) { Text("До конца файла") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun BookmarksDialog(app: SlushalkaApp, onDismiss: () -> Unit) {
    val book = app.state.current.collectAsState().value ?: return
    val play by app.player.state.collectAsState()
    var marks by remember { mutableStateOf(app.bookmarks.of(book.id)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Закладки") },
        text = {
            Column {
                TextButton(onClick = {
                    // Кусок текста рядом с меткой кладётся в саму закладку:
                    // через месяц «2:14:30» не говорит ничего, а две строки
                    // текста возвращают сцену целиком.
                    val text = app.state.text.value
                    val align = app.state.alignment.value
                    val quote = if (text != null && align != null) {
                        val at = align.charAt(play.absMs)
                        text.slice((at - 400).coerceAtLeast(0), at).takeLast(220)
                    } else ""
                    app.bookmarks.add(
                        book.id,
                        Bookmark(play.absMs, System.currentTimeMillis(), "", quote),
                    )
                    marks = app.bookmarks.of(book.id)
                }) { Text("Заложить это место (${formatClock(play.absMs)})") }

                if (marks.isEmpty()) {
                    Text(
                        "Пока пусто.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    itemsIndexed(marks) { _, m ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { app.player.seekTo(m.absMs); onDismiss() }) {
                                    Text(formatClock(m.absMs))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    formatAgo(m.at),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    app.bookmarks.remove(book.id, m)
                                    marks = app.bookmarks.of(book.id)
                                }) { Text("✕") }
                            }
                            if (m.quote.isNotBlank()) {
                                Text(
                                    "…${m.quote}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/**
 * Обложка книги - или картинка того места, где мы сейчас. Иллюстрация встаёт
 * на место обложки за страницу до того, как о ней зайдёт речь, и уходит, когда
 * подходит следующая. Подпись лежит полосой понизу, как на конверте пластинки.
 */
@Composable
private fun BookArt(
    app: SlushalkaApp,
    book: ru.zf.slushalka.library.Book,
    picture: ru.zf.slushalka.text.Picture?,
    modifier: Modifier = Modifier,
    textSize: Int = 18,
    onTap: () -> Unit,
) {
    val file = picture?.let { app.texts.pictureFile(book.id, it.file) }?.takeIf { it.exists() }
    Box(modifier.clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        if (file == null) {
            CoverImage(app, book, Modifier.fillMaxSize(), textSize = textSize)
            return@Box
        }
        val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
            Pictures.cached(file), file.path,
        ) {
            if (value == null) value = Pictures.load(file)
        }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                androidx.compose.foundation.Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = picture.caption,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (picture.caption.isNotBlank()) {
            Text(
                picture.caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xB3000000))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
