package ru.zf.slushalka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Cloud

/**
 * Облако: книги, которые лежат там, и книги с полки, которых там нет. Скачать
 * - на полку, выгрузить - туда. Передача одна за раз, с процентом и файлом;
 * оборванная продолжается с того файла, на котором оборвалась.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(app: SlushalkaApp, onBack: () -> Unit, onSettings: () -> Unit) {
    val prefs by app.state.prefs.collectAsState()
    val books by app.state.books.collectAsState()
    val transfer by app.cloudBooks.transfer.collectAsState()
    var items by remember { mutableStateOf<List<Cloud.Item>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(prefs.cloudReady, reload, transfer?.finished) {
        if (!prefs.cloudReady) return@LaunchedEffect
        error = null
        app.cloudBooks.list()
            .onSuccess { items = it }
            .onFailure { error = it.message ?: "Облако не ответило" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Облако")
                        if (prefs.cloudReady) {
                            Text(
                                (if (prefs.cloudDrive) "Google Drive · ${prefs.driveEmail}" else prefs.cloudUrl.substringAfter("://")) +
                                    " · " + prefs.cloudDir,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (prefs.cloudReady) IconButton(onClick = { reload++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Перечитать")
                    }
                },
            )
        },
    ) { padding ->
        if (!prefs.cloudReady) {
            Box(Modifier.fillMaxSize().padding(padding).padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Glyphs.Cloud, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Облако не настроено. Подойдёт семейный Google Drive (вход через браузер) или " +
                            "любой WebDAV. Через него поедут позиции, вопросы и пометки, а книги можно " +
                            "держать там и качать на полку по одной.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onSettings) { Text("Настроить") }
                }
            }
            return@Scaffold
        }

        val onShelf = remember(books) { books.map { it.id.substringAfterLast('/') }.toSet() }
        val there = items?.map { it.name }?.toSet().orEmpty()
        val local = remember(books, items) { books.filter { it.id.substringAfterLast('/') !in there } }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            transfer?.let { tr ->
                item(key = "transfer") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                (if (tr.upload) "Выгружаю: " else "Качаю: ") + tr.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { tr.share }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text(
                                when {
                                    tr.error != null -> tr.error
                                    tr.finished -> if (tr.upload) "Готово: книга в облаке" else "Готово: книга на полке"
                                    else -> "${mb(tr.doneBytes)} из ${mb(tr.totalBytes)}" +
                                        if (tr.file.isNotBlank()) " · ${tr.file}" else ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (tr.error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Row {
                                Spacer(Modifier.weight(1f))
                                if (tr.finished) TextButton(onClick = { app.cloudBooks.clear() }) { Text("Убрать") }
                                else TextButton(onClick = { app.cloudBooks.cancel() }) { Text("Остановить") }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            error?.let { e ->
                item(key = "error") {
                    Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
            }

            item(key = "cloud-head") { Head("В облаке", items?.size) }
            val list = items
            if (list == null && error == null) {
                item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else if (list != null && list.isEmpty()) {
                item(key = "cloud-empty") {
                    Text(
                        "Пусто. Выгрузи книгу с полки ниже - или положи папки книг в «${prefs.cloudDir}/${Cloud.BOOKS_DIR}» " +
                            "с компьютера: раскладка та же, что на полке.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(list.orEmpty(), key = { "c:" + it.name }) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.name in onShelf) Glyphs.CloudDone else Glyphs.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (item.name in onShelf) {
                            Text("уже на полке", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (item.name !in onShelf) {
                        TextButton(
                            enabled = !app.cloudBooks.busy,
                            onClick = { app.cloudBooks.download(item.name) },
                        ) { Text("Скачать") }
                    }
                }
                HorizontalDivider()
            }

            if (local.isNotEmpty() && items != null) {
                item(key = "local-head") {
                    Spacer(Modifier.height(16.dp))
                    Head("На полке, но не в облаке", local.size)
                    Text(
                        "Большую аудиокнигу облако принимает не быстро: Яндекс.Диск по WebDAV выгружает " +
                            "заметно медленнее, чем отдаёт. Качать обратно - быстро.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(local, key = { "l:" + it.id }) { book ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverTile(app, book, Modifier.width(36.dp).height(54.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val size = book.files.sumOf { it.size }
                            Text(
                                listOfNotNull(
                                    book.author.takeIf { it.isNotBlank() },
                                    if (size > 0) mb(size) else if (!book.hasAudio) "только текст" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            enabled = !app.cloudBooks.busy,
                            onClick = { app.cloudBooks.upload(book) },
                        ) { Text("Выгрузить") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Head(title: String, count: Int?) {
    Text(
        title.uppercase() + (count?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

private fun mb(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} МБ"
    else -> "${(bytes shr 10).coerceAtLeast(1)} КБ"
}
