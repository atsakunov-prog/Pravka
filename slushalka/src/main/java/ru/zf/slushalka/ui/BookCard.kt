package ru.zf.slushalka.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Note
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.text.BookMeta

/**
 * Карточка книги (16.11.2026, владелец: «нет карточки книги, чтобы
 * посмотреть, о чём она, и потом уже открыть»). Тап по книге на полке
 * открывает её, а не книгу: раньше тап сразу уводил в плеер, а всё, что
 * можно сделать с книгой, пряталось за долгим нажатием в окне-диалоге.
 *
 * Сверху - обложка на подложке её же цвета, название, автор, серия, жанр;
 * под ними две главные кнопки - читать и слушать, каждая со своим местом.
 * Дальше - что внутри, о чём книга (аннотация из fb2/epub, которую сервер
 * кладёт в оглавление, или разбор, или начало текста), заметки, разбор,
 * разговор, озвучка, скачать и - внизу, отдельно - удалить.
 */
@Composable
internal fun BookCard(
    app: SlushalkaApp,
    entry: ShelfEntry,
    progress: Progress,
    has: Contents,
    /** Где книга: скачана, текстом, не скачана; null - сервера нет. */
    where: String?,
    series: BookMeta.Series?,
    places: List<PlaceLine>,
    downloads: List<Pair<String, () -> Unit>>,
    downloadBusy: Boolean,
    onStopDownload: (() -> Unit)?,
    onUpload: (() -> Unit)?,
    onBack: () -> Unit,
    /** Читать; null - текста нет. */
    onRead: (() -> Unit)?,
    /** Слушать; null - звука нет. */
    onListen: (() -> Unit)?,
    onRemind: (() -> Unit)?,
    onRazbor: (() -> Unit)?,
    onTalk: (() -> Unit)?,
    onSeries: (String) -> Unit,
    /** Заметка - открыть книгу на её месте. */
    onNote: (Note) -> Unit,
    voice: (@Composable () -> Unit)?,
    /** Удалить копию с телефона; null - копии нет. */
    onDeletePhone: (() -> Unit)?,
    /** Удалить из библиотеки на сервере; null - книги там нет. */
    onRemoveLibrary: ((alsoPhone: Boolean) -> Unit)?,
    /** Сверка сказала, что книгу из библиотеки удалили (на другом устройстве). */
    removedNote: String?,
) {
    val book = entry.book
    val server = entry.server
    val st = app.state.stateOf(book.id)
    val c = MaterialTheme.colorScheme
    var askRemove by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(onBack = onBack)

    // Подложка шапки - цветом обложки: карточка узнаётся с первого взгляда.
    val tone by produceState<Color?>(null, book.id) {
        val tree = app.state.treeOf(book)
        val bmp: Bitmap? = Covers.cached(book.id)
            ?: tree?.let { runCatching { Covers.load(app, it, book, app.texts) }.getOrNull() }
        value = bmp?.let { b ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { coverTone(b) }
        }?.let { Color(it) }
    }
    val eink = app.state.prefs.collectAsState().value.readerEinkLow

    Box(Modifier.fillMaxSize().background(c.background)) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ---------------------------------------------------------- шапка
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    if (eink || tone == null) Brush.verticalGradient(listOf(c.surfaceVariant, c.background))
                    else Brush.verticalGradient(listOf(tone!!.copy(alpha = 0.55f), tone!!.copy(alpha = 0.18f), c.background)),
                )
                .statusBarsPadding(),
        ) {
            Column(Modifier.padding(start = 8.dp, end = 16.dp, bottom = 8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "На полку")
                }
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Box(
                        Modifier
                            .width(124.dp)
                            .aspectRatio(2f / 3f)
                            .shadow(if (eink) 0.dp else 14.dp, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (eink) Modifier.border(1.dp, c.onSurface, RoundedCornerShape(8.dp)) else Modifier),
                    ) {
                        CoverTile(app, book, Modifier.fillMaxSize())
                        if (progress.started || progress.done) {
                            ProgressStrip(if (progress.done) 1f else progress.share, Modifier.align(Alignment.BottomStart), 4.dp)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            shownTitle(book.title),
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (book.author.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(book.author, style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
                        }
                        if (series != null) {
                            Text(
                                "${series.name}" + (series.number?.let { " · книга $it" } ?: ""),
                                style = MaterialTheme.typography.labelLarge,
                                color = c.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onSeries(series.name) }
                                    .padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            // Категория и теги - строкой меток.
            val category = book.category ?: server?.category
            val tags = book.tags.ifEmpty { server?.tags.orEmpty() }
            if (category != null || tags.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    category?.let { Tag(it, strong = true) }
                    tags.forEach { Tag(it) }
                }
            }

            removedNote?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Эту книгу удалили из библиотеки - здесь осталась только копия на телефоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.error,
                )
            }

            // ----------------------------------------------------- где я
            if (progress.started || progress.done) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { if (progress.done) 1f else progress.share },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                )
                Spacer(Modifier.height(6.dp))
                val left = (book.totalMs - st.absMs).coerceAtLeast(0)
                Text(
                    when {
                        progress.done -> if (book.hasAudio) "Дослушана до конца" else "Прочитана до конца"
                        book.hasAudio && book.totalMs > 0 ->
                            "${(progress.share * 100).toInt()}% · осталось ${formatLeft(left, st.speed.takeIf { it > 0 } ?: 1f)}"
                        else -> "${(progress.share * 100).toInt()}%"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                places.forEach { p ->
                    Text(p.text, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }

            // ----------------------------------------------- главные кнопки
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Главная - та, что трогали последней: продолжают обычно тем же.
                val readFirst = onRead != null && (onListen == null || st.readAt > st.listenAt)
                val started = progress.started
                if (onListen != null) {
                    MainButton(
                        if (started && !readFirst && st.listenAt > 0) "Продолжить" else "Слушать",
                        Glyphs.Headphones,
                        primary = !readFirst,
                        modifier = Modifier.weight(1f),
                        onClick = onListen,
                    )
                }
                if (onRead != null) {
                    MainButton(
                        if (started && readFirst) "Продолжить" else "Читать",
                        Glyphs.AutoStories,
                        primary = readFirst,
                        modifier = Modifier.weight(1f),
                        onClick = onRead,
                    )
                }
            }
            if (onRemind != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onRemind, modifier = Modifier.fillMaxWidth()) {
                    Icon(Glyphs.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Напомнить, на чём остановился")
                }
            }

            // ---------------------------------------------------- что внутри
            Section("Что внутри")
            val facts = buildList {
                if (has.text) {
                    val t = server?.mainText
                    add(Fact(Glyphs.MenuBook, "Текст", t?.let { "${it.name.substringAfterLast('.', "").lowercase()} · ${formatBytes(it.size)}" }))
                }
                if (has.audio) {
                    val ms = book.totalMs.takeIf { it > 0 } ?: server?.audioMs ?: 0L
                    val bytes = server?.audioBytes?.takeIf { it > 0 }
                    add(
                        Fact(
                            if (has.machine) Glyphs.AutoAwesome else Glyphs.Headphones,
                            if (has.machine) "Озвучено нейросетью" else "Запись чтеца",
                            listOfNotNull(ms.takeIf { it > 0 }?.let(::formatSpan), bytes?.let(::formatBytes)).joinToString(" · ").ifBlank { null },
                        ),
                    )
                }
                if (has.razbor) add(Fact(Glyphs.Lightbulb, "Разбор книги", "о книге, главы, идеи, за 15 минут"))
                if (!has.audio && has.text) add(
                    Fact(
                        Glyphs.RecordVoiceOver, "Без записи",
                        if (app.readAloud.server.available()) "читает голос сервера - «Озвучить» в читалке"
                        else "читает голос телефона - «Озвучить» в читалке",
                    ),
                )
            }
            facts.forEach { f -> FactRow(f) }
            where?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }

            // ------------------------------------------------------ о книге
            val razbor = app.razbor.states.collectAsState().value[book.id]
            // Своя книга без сервера - аннотация из головы её fb2/epub на телефоне.
            val own = entry.local?.takeIf { it.onPhone }
            val fileAbout by produceState<String?>(null, own?.id) {
                val b = own ?: return@produceState
                val tree = app.state.treeOf(b) ?: return@produceState
                val doc = b.textDocId ?: return@produceState
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    BookMeta.read(app, ru.zf.slushalka.library.documentUri(tree, doc), b.textName.orEmpty())?.about
                }
            }
            val about = server?.about?.takeIf { it.isNotBlank() }
                ?: razbor?.card?.about?.takeIf { it.isNotBlank() }
                ?: fileAbout
            val opening = remember(book.id) { if (about == null) openingOf(app, book.id) else null }
            if (about != null || opening != null) {
                Section(if (about != null) "О книге" else "Начало")
                var open by remember { mutableStateOf(false) }
                Text(
                    about ?: opening!!,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = fontOf(Settings.FONT_BOOK),
                        fontStyle = if (about == null) FontStyle.Italic else FontStyle.Normal,
                    ),
                    maxLines = if (open) Int.MAX_VALUE else 7,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { open = !open },
                )
                if (!open && (about ?: opening!!).length > 360) {
                    TextButton(onClick = { open = true }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Дальше") }
                }
                razbor?.card?.let { card ->
                    val line = listOf(card.genre, card.era, card.age).filter { it.isNotBlank() }.joinToString(" · ")
                    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }

            // ------------------------------------------------------ заметки
            val notesRev by app.notes.revision.collectAsState()
            val notes = remember(notesRev, book.id) { app.notes.of(book.id) }
            if (notes.isNotEmpty()) {
                Section("Заметки · ${notes.size}")
                notes.sortedByDescending { it.updatedAt }.take(3).forEach { n ->
                    NoteLine(n) { onNote(n) }
                }
                if (notes.size > 3) {
                    Text(
                        "Все заметки, Word и конспект - в читалке, кнопка «Заметки».",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                }
            }

            // ---------------------------------------------------- ещё с книгой
            val more = listOfNotNull(
                onRazbor?.let { Action(Glyphs.Lightbulb, "Разбор книги", "о книге, идеи, линзы, книга за 15 минут", it) },
                onTalk?.let { Action(Glyphs.Forum, "Поговорить о книге", "темы от Claude, как после книжного клуба", it) },
                series?.let { s -> Action(Glyphs.LibraryBooks, "Вся серия", "«${s.name}» на полке по номерам") { onSeries(s.name) } },
            )
            if (more.isNotEmpty() || voice != null) {
                Section("Ещё")
                more.forEach { a -> ActionRow(a) }
                voice?.invoke()
            }

            // ------------------------------------------------------ скачать
            if (downloads.isNotEmpty() || onStopDownload != null || onUpload != null) {
                Section("На телефон")
                downloads.forEach { (label, action) ->
                    OutlinedButton(onClick = action, enabled = !downloadBusy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(Glyphs.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (downloads.isNotEmpty() && downloadBusy) {
                    Text("Идёт другая передача - эта начнётся, когда та кончится.", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
                if (onStopDownload != null) TextButton(onClick = onStopDownload) { Text("Остановить скачивание") }
                if (onUpload != null) {
                    OutlinedButton(onClick = onUpload, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(Glyphs.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Выгрузить в облако")
                    }
                }
            }

            // ------------------------------------------------------ удалить
            if (onDeletePhone != null || onRemoveLibrary != null) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = c.outlineVariant)
                Spacer(Modifier.height(8.dp))
                if (onDeletePhone != null) {
                    // Спрашивает прежний диалог полки: он знает, что пропадёт вместе с копией.
                    DangerRow(
                        "Удалить с телефона",
                        if (server != null) "Книга останется в библиотеке, место и заметки - тоже"
                        else "Её нет в библиотеке - удалится насовсем; место и заметки останутся",
                    ) { onDeletePhone() }
                }
                if (onRemoveLibrary != null) {
                    DangerRow("Удалить из библиотеки", "С полки на всех устройствах; сервер уберёт в «Удалённые»") { askRemove = true }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    // Под часами - подложка: прокрученный текст карточки не лезет под значки строки состояния.
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars)
            .background(c.background.copy(alpha = 0.94f)),
    )
    }

    if (askRemove && onRemoveLibrary != null) {
        val hasCopy = entry.local?.onPhone == true
        var alsoPhone by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { askRemove = false },
            title = { Text("Удалить из библиотеки?") },
            text = {
                Column {
                    Text(
                        "«${book.title}» пропадёт с полки на всех устройствах семьи. Насовсем сервер её не " +
                            "стирает - уносит в «Удалённые», оттуда её можно вернуть. Места, заметки и вопросы останутся.",
                    )
                    if (hasCopy) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { alsoPhone = !alsoPhone }.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = alsoPhone, onCheckedChange = { alsoPhone = it })
                            Text("И копию на этом телефоне")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { askRemove = false; onRemoveLibrary(alsoPhone && hasCopy) }) {
                    Text("Удалить", color = c.error)
                }
            },
            dismissButton = { TextButton(onClick = { askRemove = false }) { Text("Оставить") } },
        )
    }
}

/** Начало книги - когда аннотации нет, а текст уже разобран: первые абзацы, не заголовки. */
private fun openingOf(app: SlushalkaApp, bookId: String): String? {
    val t = app.texts.cached(bookId) ?: return null
    val heads = t.chapters.map { it.start }.toHashSet()
    val parts = t.blocks.asSequence()
        .filter { it.picture == null && it.start !in heads && it.text.length > 60 }
        .take(3)
        .map { it.text.trim() }
        .toList()
    return parts.joinToString("\n\n").take(700).takeIf { it.isNotBlank() }
}

@Composable
private fun MainButton(text: String, icon: ImageVector, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1)
    }
    if (primary) Button(onClick = onClick, modifier = modifier.height(52.dp), content = content)
    else OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp), content = content)
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Tag(text: String, strong: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (strong) c.onSecondaryContainer else c.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (strong) c.secondaryContainer else Color.Transparent)
            .border(0.7.dp, if (strong) Color.Transparent else c.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private data class Fact(val icon: ImageVector, val title: String, val detail: String?)

@Composable
private fun FactRow(f: Fact) {
    // Пояснение - под названием, а не в той же строке: длинное обрезалось на полуслове.
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(f.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(f.title, style = MaterialTheme.typography.bodyLarge)
            f.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class Action(val icon: ImageVector, val title: String, val hint: String, val run: () -> Unit)

@Composable
private fun ActionRow(a: Action) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = a.run).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(a.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodyLarge)
            Text(a.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Glyphs.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Заметка в карточке: цитата курсивом с линейкой и мысль под ней; тап - книга на её месте. */
@Composable
private fun NoteLine(n: Note, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(c.tertiary))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                n.quote.replace('\n', ' '),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontOf(Settings.FONT_BOOK), fontStyle = FontStyle.Italic),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (n.text.isNotBlank()) {
                Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DangerRow(title: String, hint: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Delete, contentDescription = null, tint = c.error, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.error)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}
