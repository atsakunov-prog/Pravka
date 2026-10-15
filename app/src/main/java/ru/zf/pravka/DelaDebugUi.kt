package ru.zf.pravka

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaDebug
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.finishDelaReason
import ru.zf.pravka.trigger.listenForDelaReason
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.RowRule

// Режим отладки Дел на телефоне (15.10.2026, docs/dela-phone-24.md): строка «Что не так?» вверху вкладки Дел и в
// карточке дела, лист «Отладка» — записки со статусом и ответом разбора. Правила снимка — core/DelaDebug.kt, дорога —
// data/DelaDebugSync.kt. Владелец: «наверху где-то прямо текстбокс в телефоне и в компе… они идут с тем, что у меня
// сейчас на экране».

/** Что было на экране, когда человек взялся за строку (фокус или микрофон): до клавиатуры, которая сдвинет список. */
private class Held(val at: Long, val screen: DelaDebug.Screen) {
    var shot: String? = null
}

private const val HOLD_MS = 5 * 60_000L

/**
 * Строка «Что не так? — запишу с экраном»: поле, микрофон (движок «Д» с заказом текста — `listenForDelaReason`, как
 * причина отказа и «🐞»), «записать» и номер последней записки — тапом список. [screen] — что на экране сейчас (его
 * собирает вкладка). Режим выключен или сервер старый — строки нет.
 */
@Composable
internal fun DelaDebugBar(app: PravkaApp, screen: () -> DelaDebug.Screen, modifier: Modifier = Modifier) {
    val snap by app.delaStore.view.collectAsState()
    val info by app.delaDebug.info.collectAsState()
    if (info?.on != true || DelaDebug.FEATURE !in snap.features) return
    val view = LocalView.current
    val conf = LocalConfiguration.current
    val wide = ru.zf.pravka.ui.twoPane()
    val t = LocalPravkaType.current
    var text by rememberSaveable { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<Held?>(null) }

    fun hold() {
        val now = System.currentTimeMillis()
        if (held?.let { now - it.at < HOLD_MS } == true) return
        val h = Held(now, runCatching(screen).getOrElse { DelaDebug.Screen("nav:?", "снимок не собрался: ${it.javaClass.simpleName}") })
        held = h
        app.appScope.launch { h.shot = DelaShot.take(view) }
    }

    fun send(said: String) {
        val body = said.trim()
        if (body.isEmpty() || sending) return
        sending = true
        val h = held?.takeIf { System.currentTimeMillis() - it.at < HOLD_MS }
            ?: Held(System.currentTimeMillis(), runCatching(screen).getOrElse { DelaDebug.Screen("nav:?", "снимок не собрался") })
        val version = "${BuildConfig.VERSION_CODE} ${BuildConfig.VERSION_NAME}".trim()
        val size = "${conf.screenWidthDp}×${conf.screenHeightDp} dp " + if (wide) "разворот" else "сложен"
        app.appScope.launch {
            val shot = h.shot ?: DelaShot.take(view)
            val events = runCatching { app.delaDebug.events() }.getOrDefault(emptyList())
            val now = OffsetDateTime.now()
            val snapJson = DelaDebug.snapshot(
                h.screen, app.delaStore.view.value, LocalDate.now().toString(), version, events,
                app.delaStore.queued.value.map { it.op },
            )
            val op = DelaDebug.addOp(
                Dela.newId(), Dela.newId(), body, version, size, now.toString(), snapJson,
                DelaDebug.actions(DelaDebug.recentOps(), events, now), shot,
            )
            val words = runCatching { app.delaDebug.send(op) }.getOrElse { "Не записал: ${it.message}" }
            // Отказ сервера — текст остаётся в поле: дописать и отправить ещё раз. Легло в очередь — поле пустое.
            if (!words.startsWith("Сервер не принял") && !words.startsWith("Не записал")) {
                text = ""
                held = null
            }
            sending = false
            Feedback.toast(app, words)
        }
    }

    fun mic() {
        val service = PravkaAccessibilityService.instance
        when {
            service == null -> Feedback.toast(app, app.getString(R.string.toast_no_service))
            listening -> if (!service.finishDelaReason(keep = true)) listening = false
            else -> {
                hold()
                val base = text.trim()
                listening = service.listenForDelaReason("Говори, что не так — нажми ещё раз, запишу") { said ->
                    listening = false
                    val full = (base + " " + said.trim()).trim()
                    text = full
                    // Сказанное вслух — записка (как строка Claude): ушла сразу.
                    if (said.isNotBlank()) send(full)
                }
            }
        }
    }

    val shape = RoundedCornerShape(KvShape.Control)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = "Режим отладки: что не так — запишу вместе с тем, что на экране" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Glyphs.Bug, contentDescription = null, tint = Kv.muted, modifier = Modifier.size(18.dp))
        Box(
            Modifier.weight(1f).height(36.dp).clip(shape).background(Kv.panel).border(1.dp, Kv.line, shape).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("Что не так? — запишу с экраном", style = t.body.copy(fontSize = 14.sp), color = Kv.faint, maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !sending,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(text) }),
                textStyle = t.body.copy(fontSize = 14.sp, color = Kv.text),
                cursorBrush = SolidColor(Kv.accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) hold() },
            )
        }
        GlyphButton(
            if (listening) Glyphs.Stop else Glyphs.Mic,
            if (listening) "хватит слушать — записать" else "сказать голосом",
            onClick = { mic() }, tint = if (listening) Kv.accent else Kv.icon, enabled = !sending, size = 36.dp,
        )
        if (text.isNotBlank() && !listening) GlyphButton(Glyphs.ArrowUp, "записать", onClick = { send(text) }, tint = Kv.accent, enabled = !sending, size = 36.dp)
        val i = info
        Text(
            if (i != null && i.last > 0) "№${i.last}" + if (i.new > 0 && wide) " · новых ${i.new}" else "" else "записки",
            style = t.meta,
            color = if ((i?.new ?: 0) > 0) Kv.accent else Kv.muted,
            maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(KvShape.Tag)).clickable { app.delaDebug.listOpen.value = true }.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}

/** Лист «Отладка» — один на вкладку: открывают строка (номер) и пункт боковины. */
@Composable
internal fun DelaDebugHost(app: PravkaApp) {
    val open by app.delaDebug.listOpen.collectAsState()
    if (open) DelaDebugSheet(app) { app.delaDebug.listOpen.value = false }
}

@Composable
private fun DelaDebugSheet(app: PravkaApp, onDismiss: () -> Unit) {
    val info by app.delaDebug.info.collectAsState()
    var filter by rememberSaveable { mutableStateOf("open") }
    var notes by remember { mutableStateOf<List<DelaDebug.Note>?>(null) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val today = LocalDate.now().toString()
    LaunchedEffect(filter, reload, info?.answered) {
        app.delaDebug.list(filter).onSuccess { notes = it; error = "" }.onFailure { error = it.message.orEmpty().ifBlank { "сервер не ответил" } }
    }
    val on = info?.on == true
    PaperSheet(
        onDismiss = onDismiss,
        title = "Отладка",
        icon = Glyphs.Bug,
        subtitle = listOfNotNull(if (on) "строка вверху включена" else "строка выключена", info?.new?.takeIf { it > 0 }?.let { "новых $it" }).joinToString(" · "),
        actions = {
            PaperTextButton(if (on) "Выключить" else "Включить", onClick = {
                app.appScope.launch { Feedback.toast(app, app.delaDebug.setOn(!on)) }
            })
        },
    ) {
        PaperHint("Записки о багах — с тем, что было на экране, и последними действиями. Разбирает сессия Claude на компе: статус и ответ появляются здесь.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperChip("Новое и разбираю", selected = filter == "open", onClick = { filter = "open" })
            PaperChip("Все", selected = filter == "all", onClick = { filter = "all" })
        }
        if (error.isNotBlank()) PaperHint("Список не пришёл: $error", Kv.bad)
        val list = notes
        when {
            list == null && error.isBlank() -> PaperHint("Загружаю…")
            list != null && list.isEmpty() -> PaperHint(if (filter == "open") "Новых записок нет." else "Записок нет.")
            list != null -> for (n in list) {
                RowRule()
                NoteRow(n, today) { status ->
                    app.appScope.launch {
                        Feedback.toast(app, app.delaDebug.setStatus(n.id, status))
                        reload++
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(n: DelaDebug.Note, today: String, onStatus: (String) -> Unit) {
    val t = LocalPravkaType.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            listOf("№${n.num}", DelaDebug.whenWords(n.at, today), if (n.source == "phone") "телефон" else "веб", n.title)
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = t.meta, color = Kv.muted,
        )
        Text(n.text, style = t.body, color = Kv.text)
        if (n.answer.isNotBlank()) Text(
            "Ответ" + (if (n.statusBy.isNotBlank()) " (${n.statusBy})" else "") + ": " + n.answer,
            style = t.body.copy(fontSize = 14.sp),
            color = Kv.text,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(KvShape.Tag)).background(Kv.accentSoft)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for ((k, w) in DelaDebug.STATUS) {
                val on = n.status == k
                Text(
                    w,
                    style = t.meta.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (on) Kv.accent else Kv.muted,
                    modifier = Modifier.clip(RoundedCornerShape(KvShape.Tag))
                        .background(if (on) Kv.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled = !on) { onStatus(k) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * Картинка экрана к записке (по желанию, как разрешил контракт): окно, где стоит строка (лист — его окно, вкладка —
 * окно приложения), `PixelCopy` — без программной перерисовки (у неё бывают «железные» картинки, которых программный
 * холст не рисует). Сжато JPEG до ширины 540 и ~150 КБ. Не вышло за 2 с или стало больше — без картинки, записка
 * всё равно уходит.
 */
internal object DelaShot {
    private const val WIDTH = 540
    private const val MAX_BYTES = 150_000

    suspend fun take(view: View): String? = withTimeoutOrNull(2_000L) { runCatching { capture(view) }.getOrNull() }

    private suspend fun capture(view: View): String? {
        val window = windowOf(view) ?: return null
        val decor = window.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { c ->
            PixelCopy.request(window, bmp, { r -> if (c.isActive) c.resume(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }
        if (!ok) {
            bmp.recycle()
            return null
        }
        return withContext(Dispatchers.Default) { encode(bmp) }
    }

    private fun encode(src: Bitmap): String? {
        val k = minOf(1f, WIDTH.toFloat() / src.width)
        val bmp = if (k < 1f) Bitmap.createScaledBitmap(src, WIDTH, (src.height * k).toInt().coerceAtLeast(1), true) else src
        try {
            for (q in intArrayOf(55, 40, 28)) {
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= MAX_BYTES) {
                    return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                }
            }
            return null
        } finally {
            if (bmp !== src) bmp.recycle()
            src.recycle()
        }
    }

    private fun windowOf(view: View): Window? {
        var p = view.parent
        while (p != null) {
            if (p is DialogWindowProvider) return p.window
            p = p.parent
        }
        var c: Context = view.context
        while (c is ContextWrapper) {
            if (c is Activity) return c.window
            c = c.baseContext
        }
        return null
    }
}
