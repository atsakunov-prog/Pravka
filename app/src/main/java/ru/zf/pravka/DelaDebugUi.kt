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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import ru.zf.pravka.core.DelaDebug
import ru.zf.pravka.ui.Feedback
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
// сейчас на экране». С 16.10.2026 строка — на всех экранах (DebugBar.kt, общий снимок — ScreenDebugUi.kt); здесь —
// её снимок Дел, лист «Отладка» и картинка экрана.

/**
 * Строка «Что не так? — запишу с экраном» вкладки Дел и карточки дела: та же строка, что на всех экранах
 * (`DebugBar`, DebugBar.kt — поле, микрофон, «записать», номер последней записки), со снимком Дел данными. [screen] —
 * что на экране сейчас (его собирает вкладка); снимок и действия достраиваются при отправке — как до 16.10.2026, когда
 * строка жила только здесь.
 */
@Composable
internal fun DelaDebugBar(app: PravkaApp, screen: () -> DelaDebug.Screen, modifier: Modifier = Modifier) {
    DebugBar(app, capture = { delaCapture(app, screen) }, modifier = modifier)
}

/** Снимок Дел: экран вкладки — в миг «взялся за строку», копия, журнал и очередь — в миг записки. */
private fun delaCapture(app: PravkaApp, screen: () -> DelaDebug.Screen): DebugCapture {
    val sc = runCatching(screen).getOrElse { DelaDebug.Screen("nav:?", "снимок не собрался: ${it.javaClass.simpleName}") }
    return DebugCapture { version, events, now ->
        DelaDebug.snapshot(
            sc, app.delaStore.view.value, LocalDate.now().toString(), version, events,
            app.delaStore.queued.value.map { it.op },
        ) to DelaDebug.actions(DelaDebug.recentOps(), events, now, DelaDebug.recentNotes(), app.delaStore.view.value)
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
    // Раскрытая записка: снимок и действия под ней (одна за раз — лист не раздувается).
    var openId by rememberSaveable { mutableStateOf("") }
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
                NoteRow(app, n, today, open = openId == n.id, onOpen = { openId = if (openId == n.id) "" else n.id }) { status ->
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
private fun NoteRow(app: PravkaApp, n: DelaDebug.Note, today: String, open: Boolean, onOpen: () -> Unit, onStatus: (String) -> Unit) {
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
        Text(
            if (open) "Свернуть" else "Снимок и действия" + if (n.hasShot) " · картинка" else "",
            style = t.meta,
            color = Kv.accent,
            modifier = Modifier.clip(RoundedCornerShape(KvShape.Tag)).clickable(onClick = onOpen).padding(horizontal = 4.dp, vertical = 5.dp),
        )
        if (open) NoteDetail(app, n)
    }
}

/**
 * Записка целиком под строкой листа: что было на экране и последние действия — теми же словами, что видит Claude
 * (`DelaDebug.snapshotLines` / `actionLines`, как `debug show` сервера), и картинка экрана, если она есть.
 */
@Composable
private fun NoteDetail(app: PravkaApp, n: DelaDebug.Note) {
    val t = LocalPravkaType.current
    var note by remember(n.id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(n.id) { mutableStateOf("") }
    LaunchedEffect(n.id) {
        app.delaDebug.one(n.id).onSuccess { note = it }.onFailure { error = it.message.orEmpty().ifBlank { "сервер не ответил" } }
    }
    val x = note
    when {
        error.isNotBlank() -> PaperHint("Записка не пришла: $error", Kv.bad)
        x == null -> PaperHint("Загружаю снимок…")
        else -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(KvShape.Tag)).background(Kv.panel).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            val small = t.meta.copy(fontSize = 12.sp)
            Text("Что было на экране", style = t.meta.copy(fontWeight = FontWeight.SemiBold), color = Kv.muted)
            for (line in DelaDebug.snapshotLines(x.optJSONObject("snapshot"))) Text(line, style = small, color = Kv.text)
            Text("Последние действия", style = t.meta.copy(fontWeight = FontWeight.SemiBold), color = Kv.muted, modifier = Modifier.padding(top = 6.dp))
            for (line in DelaDebug.actionLines(x.optJSONArray("actions"))) Text(line, style = small, color = Kv.text)
            val shot = x.optString("shot").takeIf { it.startsWith("data:image/") }
            val bmp = remember(shot) {
                shot?.let {
                    runCatching {
                        val b = android.util.Base64.decode(it.substringAfter(","), android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                    }.getOrNull()
                }
            }
            if (bmp != null) androidx.compose.foundation.Image(bmp, contentDescription = "Экран в миг записки",
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
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
