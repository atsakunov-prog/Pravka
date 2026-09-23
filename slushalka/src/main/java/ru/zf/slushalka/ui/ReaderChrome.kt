package ru.zf.slushalka.ui

import android.content.Intent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.speech.VoiceInput
import ru.zf.slushalka.data.Note
import ru.zf.slushalka.text.Block
import ru.zf.slushalka.text.BookText

// ------------------------------------------------------------------- жесты

/**
 * Сколько ждать следующего тапа, прежде чем счесть тап одиночным. Столько же у
 * системного двойного нажатия; меньше - и четыре тапа подряд не успеть.
 */
private const val MULTI_TAP_MS = 280L

/**
 * Тапы читалки: одиночный листает и прячет плашки, два подряд выделяют слово,
 * три - фразу, четыре - абзац, долгое нажатие - тоже абзац.
 *
 * Одиночный тап приходит с задержкой [MULTI_TAP_MS]: пока не ясно, не начало
 * ли это серии, страницу листать нельзя - иначе двойной тап по слову у края
 * перелистывал бы две страницы. Серия, наоборот, отдаётся сразу на каждом
 * тапе: слово выделяется под пальцем, третий тап расширяет его до фразы.
 * Смахивание пейджер и прокрутка съедают сами - тогда жест тут отменяется.
 */
suspend fun PointerInputScope.detectReaderTaps(
    onTap: (Offset) -> Unit,
    onTaps: (count: Int, Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
) = coroutineScope {
    var series = 0
    var lastUp = 0L
    var lastPos = Offset.Zero
    var single: Job? = null
    val near = 48.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        val joins = series > 0 &&
            down.uptimeMillis - lastUp < MULTI_TAP_MS &&
            (down.position - lastPos).getDistance() < near
        // Второй палец по тому же месту - первый тап уже не одиночный.
        if (joins) single?.cancel()
        var cancelled = false
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation().also { if (it == null) cancelled = true }
        }
        if (up == null) {
            series = 0
            if (!cancelled) {
                onLongPress(down.position)
                // Остаток жеста - тот же палец, пусть его не примут за смахивание.
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
            return@awaitEachGesture
        }
        up.consume()
        series = if (joins) series + 1 else 1
        lastUp = up.uptimeMillis
        lastPos = up.position
        val pos = up.position
        if (series == 1) {
            single = launch {
                delay(MULTI_TAP_MS)
                onTap(pos)
            }
        } else {
            onTaps(series.coerceAtMost(4), pos)
            // Пятый тап начинает заново, а не держит абзац вечно.
            if (series >= 4) series = 0
        }
    }
}

/** Слово под этим местом: буквы, цифры и дефис внутри слова («по-моему»). */
fun wordAt(plain: String, at: Int): IntRange? {
    if (plain.isEmpty()) return null
    val i = at.coerceIn(0, plain.length - 1)
    fun inWord(k: Int): Boolean {
        val c = plain[k]
        if (c.isLetterOrDigit()) return true
        // Дефис и апостроф - часть слова, только если с обеих сторон буквы.
        return (c == '-' || c == '\'' || c == '’') &&
            k > 0 && k < plain.length - 1 && plain[k - 1].isLetter() && plain[k + 1].isLetter()
    }
    var s = i
    if (!inWord(s)) {
        // Попали в пробел или знак: слово слева, если оно вплотную.
        if (s > 0 && inWord(s - 1)) s-- else return null
    }
    var e = s
    while (s > 0 && inWord(s - 1)) s--
    while (e < plain.length && inWord(e)) e++
    // Конец - за последней буквой: так же меряют подсветка и цитата.
    return if (e > s) s..e else null
}

/** Абзац, в котором это место, - без перевода строки в конце. */
fun paragraphAt(text: BookText, blocks: List<Block>, at: Int): IntRange? {
    val b = blocks.getOrNull(text.blockIndexAt(at))?.takeIf { it.picture == null && it.text.isNotBlank() }
        ?: return null
    return b.start..(b.start + b.text.length)
}

/** Кусок по числу тапов: два - слово, три - фраза, четыре - абзац. */
fun selectionFor(count: Int, text: BookText, blocks: List<Block>, at: Int): IntRange? = when (count) {
    2 -> wordAt(text.plain, at)
    3 -> text.sentenceAt(at).let { r ->
        // Фраза без хвостовых пробелов: выделение не должно висеть в пустоте.
        var e = r.last
        while (e > r.first && text.plain.getOrNull(e - 1)?.isWhitespace() == true) e--
        r.first..e
    }
    else -> paragraphAt(text, blocks, at)
}?.takeIf { it.last > it.first }

// ------------------------------------------------------------- кнопки листания

/** Просьба перелистнуть кнопкой: направление и номер - два нажатия подряд оба срабатывают. */
data class KeyTurn(val dir: Int, val seq: Int)

/**
 * Листание кнопками: у электронных книг они физические (PageUp/PageDown, у
 * некоторых - стрелки), на телефоне - громкость. Activity ловит клавишу и,
 * если читалка открыта ([listener]), отдаёт ей направление.
 */
object PageKeys {
    @Volatile
    var listener: ((Int) -> Unit)? = null

    /** +1 - вперёд, -1 - назад, 0 - не наша клавиша. Громкость - только если [volumeKeys]. */
    fun direction(keyCode: Int, volumeKeys: Boolean): Int = when (keyCode) {
        android.view.KeyEvent.KEYCODE_PAGE_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_SPACE -> +1
        android.view.KeyEvent.KEYCODE_PAGE_UP,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeys) +1 else 0
        android.view.KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeys) -1 else 0
        else -> 0
    }
}

// ------------------------------------------------------------------ краска

/**
 * Что лежит поверх букв: найденная фраза (гаснет), выделение и пометки.
 * Одним набором, чтобы страница и прокрутка рисовали одно и то же.
 */
data class TextInk(
    val highlight: IntRange? = null,
    val highlightAlpha: Float = 0f,
    val selection: IntRange? = null,
    val notes: List<IntRange> = emptyList(),
    val selectionColor: Color = Color.Transparent,
    val noteColor: Color = Color.Transparent,
    /** Подчёркивать пометки - на e-ink, где цветной маркер выходит бледно-серым. */
    val underlineNotes: Boolean = false,
)

/**
 * Цвета выделения и пометок под бумагу. Пометка - как маркер на полях, тёплая
 * и полупрозрачная: текст под ней читается. Выделение - холодное, чтобы с
 * пометкой не путалось.
 */
fun inkColors(palette: ReaderPalette, eink: Boolean = false): Pair<Color, Color> {
    val dark = palette.bg.luminance() < 0.5f
    // На электронной бумаге цвета нет: выделение - ощутимо серое, пометка -
    // светло-серая плашка плюс подчёркивание (см. TextInk.underlineNotes).
    if (eink) return palette.fg.copy(alpha = 0.28f) to palette.fg.copy(alpha = 0.10f)
    val selection = Color(0xFF4A7BD0).copy(alpha = if (dark) 0.38f else 0.26f)
    val note = Color(0xFFE2B84A).copy(alpha = if (dark) 0.26f else 0.34f)
    return selection to note
}

// ----------------------------------------------------------------- плашки

/** Отступ плашки от края экрана, когда у страницы своего поля нет. */
val BAR_INSET = 10.dp

private val BAR_SHAPE = RoundedCornerShape(18.dp)

// Плашки выезжают из-за края, к которому прижаты, а не проявляются на месте:
// так видно, откуда они и куда уйдут. Уход короче появления - ждать его незачем.
fun barEnter(fromTop: Boolean, eink: Boolean = false): androidx.compose.animation.EnterTransition =
    if (eink) androidx.compose.animation.EnterTransition.None
    else slideInVertically(tween(260, easing = FastOutSlowInEasing)) { if (fromTop) -it else it } +
        fadeIn(tween(180))

// На e-ink плашки появляются и уходят разом: выезд - дюжина перерисовок с
// шлейфом на электронной бумаге.
fun barExit(fromTop: Boolean, eink: Boolean = false): androidx.compose.animation.ExitTransition =
    if (eink) androidx.compose.animation.ExitTransition.None
    else slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { if (fromTop) -it else it } +
        fadeOut(tween(160))

/** Читалка в режиме e-ink: плашки без теней и анимаций, кромка чёткая. */
val LocalEink = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Плашка читалки: карточка над страницей с тенью, а не полоса поперёк неё.
 * В тёмной теме тень не видна, поэтому карточку там поднимает подсветка фона
 * и кромка; в светлой кромка еле заметна, работает тень.
 */
@Composable
fun BarCard(
    palette: ReaderPalette,
    inner: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = palette.bg.luminance() < 0.5f
    val eink = LocalEink.current
    val face = if (dark && !eink) lerp(palette.bg, palette.fg, 0.07f) else palette.bg
    Column(
        Modifier
            .fillMaxWidth()
            // Тень на электронной бумаге - серое пятно: там карточку держит
            // чёткая чёрная кромка.
            .then(
                if (eink) Modifier else Modifier.shadow(
                    elevation = if (dark) 6.dp else 12.dp,
                    shape = BAR_SHAPE,
                    ambientColor = Color.Black.copy(alpha = 0.25f),
                    spotColor = Color.Black.copy(alpha = 0.35f),
                )
            )
            .background(face, BAR_SHAPE)
            .border(
                if (eink) 1.5.dp else 0.5.dp,
                if (eink) palette.fg else palette.fg.copy(alpha = if (dark) 0.16f else 0.08f),
                BAR_SHAPE,
            )
            .then(inner),
        content = content,
    )
}

/**
 * Кнопка нижней плашки: значок и подпись под ним. Подписи оставлены нарочно -
 * значки у «Справочника» и «Пометок» без слов не угадать.
 */
@Composable
fun RowScope.BarAction(
    icon: ImageVector,
    label: String,
    palette: ReaderPalette,
    badge: Int = 0,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) palette.fg.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(icon, contentDescription = label, tint = palette.fg, modifier = Modifier.size(22.dp))
            if (badge > 0) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    color = palette.bg,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 14.dp)
                        .clip(CircleShape)
                        .background(palette.fg.copy(alpha = 0.75f))
                        .padding(horizontal = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = palette.fg, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Значок верхней плашки: без подписи, их там мало и они привычные. */
@Composable
fun BarIcon(icon: ImageVector, label: String, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Icon(icon, contentDescription = label, tint = palette.fg, modifier = Modifier.size(22.dp))
    }
}

/**
 * Где я в книге - полоской: заполнено прочитанное, засечки - начала глав.
 * Цифры рядом, а полоска для глаза: сколько осталось, видно без счёта.
 */
@Composable
fun BookProgress(text: BookText, offset: Int, palette: ReaderPalette, modifier: Modifier = Modifier) {
    val length = text.length.coerceAtLeast(1)
    val share = (offset.toFloat() / length).coerceIn(0f, 1f)
    val ticks = remember(text) { text.chapters.map { it.start.toFloat() / length } }
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val y = size.height / 2
        val h = 3.dp.toPx()
        val round = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
        drawRoundRect(
            palette.fg.copy(alpha = 0.14f),
            topLeft = Offset(0f, y - h / 2),
            size = androidx.compose.ui.geometry.Size(size.width, h),
            cornerRadius = round,
        )
        drawRoundRect(
            palette.fg.copy(alpha = 0.55f),
            topLeft = Offset(0f, y - h / 2),
            size = androidx.compose.ui.geometry.Size(size.width * share, h),
            cornerRadius = round,
        )
        // Засечки глав - только если их не частокол: у сборника рассказов на
        // сотню глав полоска превратилась бы в штрихкод.
        if (ticks.size in 2..60) {
            val tick = 1.dp.toPx()
            ticks.drop(1).forEach { t ->
                drawRect(
                    palette.bg.copy(alpha = 0.9f),
                    topLeft = Offset(size.width * t - tick / 2, y - h / 2),
                    size = androidx.compose.ui.geometry.Size(tick, h),
                )
            }
        }
    }
}

// --------------------------------------------------------------- лист Claude

/** Что умеет Claude из читалки - одним листом, а не россыпью кнопок на плашке. */
data class ClaudeAction(val icon: ImageVector, val title: String, val hint: String, val run: () -> Unit)

@Composable
fun ClaudeSheet(app: SlushalkaApp, actions: List<ClaudeAction>, onClose: () -> Unit) {
    PaperSheet(
        app = app,
        onClose = onClose,
        icon = Glyphs.AutoAwesome,
        title = "Claude",
        subtitle = "Знает книгу до этой страницы - и не знает, что дальше",
    ) {
        Spacer(Modifier.height(6.dp))
        actions.forEach { a ->
            PaperRow(a.icon, a.title, a.hint) { onClose(); a.run() }
        }
        Spacer(Modifier.height(8.dp))
        PaperNote("Выдели слово двумя тапами, фразу - тремя, абзац - четырьмя, и спроси прямо про кусок.")
    }
}

// -------------------------------------------------------------- пометки

/**
 * Пометка на полях: выделенный кусок сверху, мысль под ним - словами или
 * голосом. Голос дописывается к набранному, а не затирает его: начал пальцем,
 * договорил вслух.
 */
@Composable
fun NoteEditor(
    app: SlushalkaApp,
    note: Note,
    hasMic: () -> Boolean,
    onNeedMic: () -> Unit,
    /** Открыли кнопкой микрофона - слушать сразу. */
    listenAtOnce: Boolean = false,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onAsk: ((String) -> Unit)?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(note.text) }
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val voice = remember { VoiceInput(context) }
    DisposableEffect(Unit) { onDispose { voice.cancel() } }

    fun toggleVoice() {
        if (listening) {
            voice.stop()
            return
        }
        if (!hasMic()) return onNeedMic()
        val base = text.trimEnd()
        fun joined(heard: String) = if (base.isBlank()) heard else "$base $heard"
        voice.onText = { text = joined(it) }
        voice.onEnd = { heard ->
            listening = false
            if (heard.isNotBlank()) text = joined(heard)
        }
        voice.onError = { listening = false; error = it }
        error = null
        voice.start()
        listening = true
    }
    LaunchedEffect(Unit) { if (listenAtOnce) toggleVoice() }

    PaperSheet(
        app = app,
        onClose = onClose,
        icon = Glyphs.EditNote,
        title = if (note.text.isBlank()) "Пометка" else "Пометка на полях",
        actions = {
            if (onDelete != null) PaperIconButton(Icons.Default.Delete, "Удалить", onClick = onDelete)
        },
    ) {
        Spacer(Modifier.height(10.dp))
        PaperQuote(note.quote.take(400) + if (note.quote.length > 400) "…" else "", maxLines = 5)
        Spacer(Modifier.height(12.dp))
        val c = MaterialTheme.colorScheme
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surfaceContainerHigh)
                    .border(if (LocalEink.current) 1.dp else 0.5.dp, c.outlineVariant, RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                if (text.isEmpty()) {
                    Text(if (listening) "Слушаю…" else "Что подумалось…", style = bookBody(), color = c.onSurfaceVariant)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = bookBody().copy(color = c.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            PaperIconButton(Glyphs.Mic, if (listening) "Хватит" else "Надиктовать", active = listening) { toggleVoice() }
        }
        if (listening) PaperNote("Слушаю… тапни микрофон, когда договоришь.", Modifier.padding(top = 6.dp))
        error?.let { PaperError(it) }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onAsk != null) PaperButton("Спросить", icon = Glyphs.QuestionAnswer) { voice.cancel(); onAsk(text) }
            Spacer(Modifier.weight(1f))
            PaperButton("Сохранить", icon = Glyphs.Bookmark, primary = true) { voice.cancel(); onSave(text.trim()) }
        }
    }
}

/** Все пометки книги: по порядку текста, тап - перейти, отсюда же - поделиться всеми. */
@Composable
fun NotesSheet(
    app: SlushalkaApp,
    text: BookText,
    notes: List<Note>,
    title: String,
    onGo: (Note) -> Unit,
    onEdit: (Note) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val book = app.state.current.value

    /** Пометки (или конспект) - в Word и сразу отдать: почта, мессенджер, Диск. */
    fun toWord(digest: String?) {
        val file = Share.file(context, (if (digest != null) "Конспект - " else "Пометки - ") + title + ".docx")
        ru.zf.slushalka.data.Docx.write(file, Share.notesDocx(text, title, notes, digest))
        Share.send(context, file, Share.DOCX, (if (digest != null) "Конспект: " else "Пометки: ") + title)
    }

    fun digest() {
        val b = book ?: return
        busy = "Claude собирает конспект…"
        error = null
        scope.launch {
            app.ask.digest(b, text, notes)
                .onSuccess { (digest, _) -> toWord(digest) }
                .onFailure { error = it.message ?: "Не вышло" }
            busy = null
        }
    }

    fun asText() {
        val body = notesAsText(text, notes, title)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Пометки: $title")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Поделиться пометками")) }
    }

    PaperSheet(
        app = app,
        onClose = onClose,
        icon = Glyphs.EditNote,
        title = "Пометки",
        subtitle = if (notes.isEmpty()) title else "$title · ${notes.size}",
        tall = notes.size > 4,
        scroll = false,
    ) {
        if (notes.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            PaperNote(
                "Пока пусто. Выдели кусок - двойной тап по слову, тройной по фразе, четверной по " +
                    "абзацу - и нажми «Пометка». Её можно надиктовать.",
            )
            return@PaperSheet
        }
        // Выгрузки - одной строкой кнопок под шапкой: что сделать со всеми разом.
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton("В Word", icon = Glyphs.Notes, enabled = busy == null, modifier = Modifier.weight(1f)) { toWord(null) }
            PaperButton("Конспект", icon = Glyphs.AutoAwesome, enabled = busy == null && book != null, modifier = Modifier.weight(1f)) { digest() }
            PaperButton("Текстом", icon = Icons.Default.Share, enabled = busy == null, modifier = Modifier.weight(1f)) { asText() }
        }
        busy?.let { PaperBusy(it) }
        error?.let { PaperError(it) }
        if (busy == null) {
            PaperNote(
                "«В Word» - пометки как есть. «Конспект» - Claude свяжет цитаты и твои мысли в текст по главам.",
                Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.fillMaxWidth().then(if (notes.size > 4) Modifier.weight(1f) else Modifier.heightIn(max = 560.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(notes, key = { it.id }) { n ->
                PaperCard(onClick = { onGo(n) }) {
                    PaperQuote(n.quote, maxLines = 3)
                    if (n.text.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(n.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text.chapterAt(n.start)?.title?.takeIf { it.isNotBlank() }
                                ?.let { "$it · " }.orEmpty() + "${percentOf(text, n.start)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MiniAction(Icons.Default.Edit, "Править") { onEdit(n) }
                    }
                }
            }
        }
    }
}

private fun percentOf(text: BookText, at: Int): Int =
    (at * 100L / text.length.coerceAtLeast(1)).toInt().coerceIn(0, 100)

/** Пометки простым текстом - в мессенджер, в заметки, себе на почту. */
fun notesAsText(text: BookText, notes: List<Note>, title: String): String = buildString {
    append(title)
    if (text.author.isNotBlank()) append(" — ").append(text.author)
    append("\n\n")
    notes.forEach { n ->
        text.chapterAt(n.start)?.title?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
        append("«").append(n.quote.trim()).append("»\n")
        if (n.text.isNotBlank()) append("— ").append(n.text.trim()).append('\n')
        append('\n')
    }
}.trimEnd()
