package ru.zf.slushalka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.SlushalkaApp
import kotlinx.coroutines.launch
import ru.zf.slushalka.library.Book

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    app: SlushalkaApp,
    onPickTree: () -> Unit,
    onOpen: (Book) -> Unit,
    onSettings: () -> Unit,
) {
    val state = app.state
    val books by state.books.collectAsState()
    val busy by state.busy.collectAsState()
    val others by state.others.collectAsState()
    val offer by state.resumeOffer.collectAsState()
    val rev by state.positionsRev.collectAsState()
    val prefs by state.prefs.collectAsState()
    val update by app.updater.status.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Слушалка") },
                actions = {
                    IconButton(onClick = { state.rescan() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Перечитать папку")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
        bottomBar = { busy?.let { BusyBar(it) } },
    ) { padding ->
        if (!prefs.loaded) return@Scaffold
        if (prefs.libraryUri.isBlank()) {
            Welcome(onPickTree, Modifier.padding(padding))
            return@Scaffold
        }

        // Последняя книга - наверх и крупно: в девяти случаях из десяти
        // приложение открывают, чтобы продолжить именно её.
        val lastId = app.positions.lastBook()
        val last = books.firstOrNull { it.id == lastId }
        val rest = books.filter { it.id != last?.id }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Новая версия - первой строкой: чтобы обновиться, не надо ничего
            // никуда закидывать, довольно одной кнопки.
            (update as? ru.zf.slushalka.data.Updater.Status.Ready)?.let { ready ->
                item(key = "update") {
                    UpdateCard(ready.update.versionName) {
                        scope.launch { app.updater.downloadAndInstall(ready.update) }
                    }
                }
            }
            (update as? ru.zf.slushalka.data.Updater.Status.Downloading)?.let { d ->
                item(key = "downloading") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("Качаю новую версию: ${d.percent}%", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { d.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (last != null) {
                item(key = "continue") {
                    ContinueCard(app, last, rev, onOpen)
                }
            }
            if (books.isEmpty()) {
                item {
                    Text(
                        "В выбранной папке книг не нашлось. Книга - это папка с mp3 внутри; " +
                            "текст (fb2 или epub) и обложку клади туда же.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rest, key = { it.id }) { book ->
                BookRow(
                    app = app,
                    book = book,
                    rev = rev,
                    others = others[book.id].orEmpty(),
                    onClick = { onOpen(book) },
                )
            }
        }
    }

    offer?.let { o ->
        val book = books.firstOrNull { it.id == o.bookId }
        AlertDialog(
            onDismissRequest = { state.declineResume() },
            title = { Text("Продолжить с другого устройства?") },
            text = {
                Text(
                    "«${book?.title ?: o.bookId}» — там остановились на ${formatClock(o.absMs)} " +
                        "(${formatAgo(o.at)}). Здесь место другое."
                )
            },
            confirmButton = {
                TextButton(onClick = { state.acceptResume() }) { Text("Перейти туда") }
            },
            dismissButton = {
                TextButton(onClick = { state.declineResume() }) { Text("Остаться здесь") }
            },
        )
    }
}

@Composable
private fun Welcome(onPickTree: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Где книги?", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "Выбери папку на телефоне, в которой лежат книги. Каждая книга - своя папка: " +
                    "mp3 внутри, рядом обложка и текст в fb2 или epub.\n\n" +
                    "Всё читается прямо оттуда, ничего никуда не копируется.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onPickTree) { Text("Выбрать папку") }
        }
    }
}

@Composable
private fun BusyBar(text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Открывает книгу на своём месте, но не заводит: пуск - рукой, как у любой другой. */
@Composable
private fun ContinueCard(app: SlushalkaApp, book: Book, rev: Int, onOpen: (Book) -> Unit) {
    val st = app.state.stateOf(book.id)
    val left = (book.totalMs - st.absMs).coerceAtLeast(0)
    Card(
        onClick = { onOpen(book) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                app, book,
                Modifier.size(96.dp).clip(MaterialTheme.shapes.medium),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("ПРОДОЛЖИТЬ", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (book.totalMs > 0) {
                        "${formatClock(st.absMs)} · осталось ${formatLeft(left, st.speed.takeIf { it > 0 } ?: 1f)}"
                    } else "длительности ещё не измерены",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (book.totalMs > 0) st.absMs.toFloat() / book.totalMs else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BookRow(
    app: SlushalkaApp,
    book: Book,
    rev: Int,
    others: List<Pair<String, Long>>,
    onClick: () -> Unit,
) {
    val st = app.state.stateOf(book.id)
    val percent = if (book.totalMs > 0) (st.absMs * 100 / book.totalMs).toInt() else 0
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            app, book,
            Modifier.size(64.dp).aspectRatio(1f).clip(MaterialTheme.shapes.small),
            textSize = 10,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val bits = buildList {
                if (book.author.isNotBlank()) add(book.author)
                if (book.totalMs > 0) add(formatSpan(book.totalMs))
                add("${book.files.size} ф.")
                if (book.textDocId == null) add("без текста")
            }
            Text(
                bits.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (st.absMs > 0 || others.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val mine = if (st.finished) "дослушано" else if (st.absMs > 0) "$percent%" else ""
                val theirs = others.joinToString(" · ") { (who, ms) ->
                    val p = if (book.totalMs > 0) (ms * 100 / book.totalMs).toInt() else 0
                    "$who $p%"
                }
                Text(
                    listOf(mine, theirs).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** «Есть новая версия» - одна кнопка, дальше система сама поставит поверх. */
@Composable
private fun UpdateCard(versionName: String, onUpdate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Есть новая версия $versionName",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUpdate) { Text("Обновить") }
        }
    }
}
