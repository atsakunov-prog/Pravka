package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.pravka.data.FeedbackStore
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.onFeedbackText
import ru.zf.pravka.trigger.startFeedbackTake
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.LocalMode
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.bottomFade
import ru.zf.pravka.ui.glass
import ru.zf.pravka.ui.scrollFade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// «Ещё → Баги и предложения» (07.10.2026): что сказано с кнопок пунктом
// «🐞 Баг или предложение», с номерами. Раз в день — «Скопировать новые»
// одним блоком в чат с Claude, или разбор сам читает их с компа
// (`docs/feedback.md`). Сделанное отмечает приехавшая сборка
// (`assets/feedback_done.txt`); руками — «сделано», «отложить», «вернуть».

private val stampFormat = SimpleDateFormat("d.MM, HH:mm", Locale("ru"))

@Composable
internal fun FeedbackTab(app: PravkaApp) {
    val context = LocalContext.current
    val items by app.feedbackStore.flow.collectAsState()
    LaunchedEffect(Unit) { app.feedbackStore.load() }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf("") }
    val scope = app.appScope
    val open = items.filter { it.open }
    val shown = when (filter) {
        0 -> open
        1 -> items.filter { !it.open }
        else -> items
    }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().bottomFade().scrollFade(listState),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ru.zf.pravka.ui.Segmented(
                        options = listOf("Новые · ${open.size}", "Разобранные · ${items.size - open.size}", "Все · ${items.size}"),
                        selected = filter,
                        onSelect = { filter = it },
                    )
                    if (open.isNotEmpty()) {
                        // Одним блоком — вставить в чат с Claude: номер, когда, откуда, что.
                        ru.zf.pravka.ui.PrimaryKey(
                            "Скопировать новые · ${open.size}",
                            onClick = {
                                copy(context, digest(open))
                                Feedback.toast(context, "Скопировал ${open.size} — вставь в чат с Claude")
                            },
                            icon = Glyphs.Copy,
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    ru.zf.pravka.ui.EmptyState(
                        if (filter == 0) "Новых нет. Долгое нажатие на любую кнопку → «🐞 Баг или предложение»"
                        else "Пока пусто",
                        icon = Glyphs.Bug,
                    )
                }
            }
            items(shown, key = { it.num }) { item ->
                FeedbackRow(
                    item,
                    onDone = { scope.launch { app.feedbackStore.setStatus(item.num, FeedbackStore.DONE) } },
                    onSkip = { scope.launch { app.feedbackStore.setStatus(item.num, FeedbackStore.SKIP) } },
                    onReopen = { scope.launch { app.feedbackStore.setStatus(item.num, FeedbackStore.NEW) } },
                    onCopy = {
                        copy(context, digest(listOf(item)))
                        Feedback.toast(context, "Скопировал №${item.num}")
                    },
                )
            }
        }
        // Записать прямо отсюда: набранное — тем же путём, что сказанное с кнопки;
        // микрофон — наговор движком «Д» с подсказкой «баг или предложение?».
        ru.zf.pravka.ui.SayBar(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "Баг или предложение…",
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    draft = ""
                    val service = PravkaAccessibilityService.instance
                    if (service != null) service.onFeedbackText(text, "приложение")
                    else scope.launch {
                        val saved = app.feedbackStore.add(text, "приложение", BuildConfig.VERSION_NAME)
                        Feedback.toast(context, "Записал №${saved.num}")
                    }
                }
            },
            onMic = {
                val service = PravkaAccessibilityService.instance
                if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
                else service.startFeedbackTake("приложение")
            },
            sendEnabled = draft.isNotBlank(),
            maxLines = 5,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
        )
    }
}

/** Одна запись: «№7 · 7.10, 18:40 · с «Д»», текст, что сделано; тап — сказанное как есть. */
@Composable
private fun FeedbackRow(
    item: FeedbackStore.Item,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    onReopen: () -> Unit,
    onCopy: () -> Unit,
) {
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    var raw by remember(item.num) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), mode.glass)
            .clickable(enabled = item.text.isNotBlank() && item.text != item.raw) { raw = !raw }
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "№${item.num} · ${stampFormat.format(Date(item.ts))} · с «${item.origin}»" +
                    (if (!item.open) " · " + FeedbackStore.statusWord(item.status) else ""),
                style = ty.meta,
                color = if (item.open) mode.label else mode.meta,
                modifier = Modifier.weight(1f),
            )
            GlyphButton(Glyphs.Copy, "скопировать", onClick = onCopy, size = 40.dp, tint = mode.label)
        }
        Text(
            item.shown,
            style = ty.bodyL,
            color = if (item.open) Ink.Text else mode.meta,
            modifier = Modifier.padding(end = 10.dp),
        )
        if (raw) {
            Text("Сказано: «${item.raw}»", style = ty.meta, color = mode.meta, modifier = Modifier.padding(top = 6.dp, end = 10.dp))
        }
        if (item.note.isNotBlank() || item.build > 0) {
            Text(
                listOfNotNull(
                    item.note.takeIf { it.isNotBlank() },
                    item.build.takeIf { it > 0 }?.let { "сборка $it" },
                ).joinToString(" · "),
                style = ty.label,
                color = mode.value,
                modifier = Modifier.padding(top = 6.dp, end = 10.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (item.open) {
                ru.zf.pravka.ui.PaperTextButton("Отложить", onClick = onSkip)
                Spacer(Modifier.width(4.dp))
                ru.zf.pravka.ui.PaperTextButton("Сделано", icon = Glyphs.Check, onClick = onDone)
            } else {
                ru.zf.pravka.ui.PaperTextButton("Вернуть в новые", icon = Glyphs.Undo, onClick = onReopen)
            }
        }
    }
}

/** Блок для чата: номер, когда, откуда, сборка, что; сказанное — если чистка его поменяла. */
internal fun digest(items: List<FeedbackStore.Item>): String = items.sortedBy { it.num }.joinToString("\n\n") { i ->
    buildString {
        append("№${i.num} · ${stampFormat.format(Date(i.ts))} · с «${i.origin}» · ${i.version}\n")
        append(i.shown)
        if (i.text.isNotBlank() && i.text != i.raw) append("\n(сказано: «${i.raw}»)")
    }
}

private fun copy(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Правка: баги и предложения", text))
}

// ---------------------------------------------------------------------------
// «Настройки → Баги»: история починок
// ---------------------------------------------------------------------------

private val dayFormat = SimpleDateFormat("d MMMM", Locale("ru"))

/**
 * История: что ждёт ночного разбора и что уже починено — сборками, свежая
 * сверху (владелец, 07.10.2026: «в самом приложении тоже нужна история, что он
 * починил по каждому багу (в настройках, думаю, „Баги“)»). В строке — что
 * сделано и, вторым тоном, что просил; тап — просьба целиком. Отмечает
 * записи сама приехавшая сборка (`assets/feedback_done.txt`), список правок
 * руками — «Ещё → Баги и предложения».
 */
@Composable
internal fun FeedbackHistory(app: PravkaApp) {
    val items by app.feedbackStore.flow.collectAsState()
    LaunchedEffect(Unit) { app.feedbackStore.load() }
    val open = remember(items) { items.filter { it.open }.sortedBy { it.num } }
    val history = remember(items) { FeedbackStore.history(items) }
    ru.zf.pravka.ui.PaperCard(
        label = "ждут разбора · ${open.size}",
        info = "Каждую ночь в 3:57 Claude читает новые записи с компа, чинит и выкладывает сборку. " +
            "Сборка приехала на телефон — запись уходит в историю ниже, с тем, что сделано.",
    ) {
        if (open.isEmpty()) {
            ru.zf.pravka.ui.PaperHint("Всё разобрано. Новое — долгое нажатие на любую кнопку, «🐞 Баг или предложение».")
        }
        open.forEachIndexed { k, i ->
            if (k > 0) ru.zf.pravka.ui.Hairline()
            HistoryRow(i, waiting = true)
        }
    }
    if (history.isEmpty()) {
        ru.zf.pravka.ui.PaperHint("Починенного пока нет.")
    }
    for (b in history) {
        ru.zf.pravka.ui.PaperCard(
            label = if (b.build > 0) "сборка ${b.build} · ${dayFormat.format(Date(b.at))} · ${b.items.size}"
            else "отмечено руками · ${b.items.size}",
        ) {
            b.items.forEachIndexed { k, i ->
                if (k > 0) ru.zf.pravka.ui.Hairline()
                HistoryRow(i, waiting = false)
            }
        }
    }
}

/** «№7 · сделано», что сделано; вторым тоном — что просил (тап — целиком и сказанное). */
@Composable
private fun HistoryRow(item: FeedbackStore.Item, waiting: Boolean) {
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    var full by remember(item.num) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { full = !full }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "№${item.num} · " + if (waiting) "${stampFormat.format(Date(item.ts))} · с «${item.origin}»"
            else FeedbackStore.statusWord(item.status),
            style = ty.meta,
            color = if (item.status == FeedbackStore.SKIP) Ink.Warn else mode.label,
        )
        if (!waiting) {
            Text(item.note.ifBlank { "без пометки" }, style = ty.body, color = Ink.Text)
        }
        Text(
            if (waiting) item.shown else "просил: ${item.shown}",
            style = if (waiting) ty.body else ty.meta,
            color = if (waiting) Ink.Text else mode.meta,
            maxLines = if (full || waiting) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (full && item.text.isNotBlank() && item.text != item.raw) {
            Text("Сказано: «${item.raw}»", style = ty.meta, color = mode.meta)
        }
    }
}
