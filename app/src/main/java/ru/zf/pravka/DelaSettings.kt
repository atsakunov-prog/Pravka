package ru.zf.pravka

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.pravka.data.DelaSync
import ru.zf.pravka.data.Settings
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperRow
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.Segments
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.StatusDot
import ru.zf.pravka.ui.scanQr

// Дела на домашнем сервере (03.10.2026, docs/dela-server.md): «Подключения →
// Дела». Подключение — QR из `python -m pravka_dela pair` на компе (префикс
// `pravka-dela:`): адрес и токен устройства, в закрытой памяти. Здесь же
// выбор «Todoist / Дела» — Todoist пока не удалён, чтобы было куда откатиться.

/** Группа «Дела» в Подключениях. */
@Composable
internal fun DelaServerSettings(app: PravkaApp) {
    val context = LocalContext.current
    val link by app.delaSync.link.collectAsState()
    val st by app.delaSync.status.collectAsState()
    val queued by app.delaStore.queued.collectAsState()
    val snap by app.delaStore.view.collectAsState()
    val backend by app.settings.delaBackendFlow.collectAsState(initial = Settings.DELA_BACKEND_TODOIST)
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var askOut by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        app.delaStore.load()
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    fun connect(u: String, t: String) {
        busy = true
        error = ""
        app.appScope.launch {
            app.delaSync.connect(u, t)
                .onSuccess { l ->
                    url = ""
                    token = ""
                    // Отсканировал QR — значит, переезжает: режим «Дела» сразу на сервер.
                    app.settings.setDelaBackend(Settings.DELA_BACKEND_SERVER)
                    Feedback.toast(context, "Дела подключены (${l.name}) — беру всё с сервера", long = true)
                    runCatching { app.delaSync.sync("подключение") }
                }
                .onFailure { e -> error = e.message ?: e.javaClass.simpleName }
            busy = false
        }
    }

    PaperCard(
        label = "куда ходят дела",
        info = "Todoist — как было: список, Разноска и время в комментарий задачи. Дела — свой сервер на домашнем " +
            "компе: Утро, Новое, Жду, Неделя, карточка дела, Разноска пишет туда, запись Засечки помнит дело. " +
            "Todoist пока не удалён: переключатель — дорога назад, если с сервером что-то не так.",
    ) {
        Segments(
            options = listOf("Todoist", "Дела (свой сервер)"),
            selected = if (backend == Settings.DELA_BACKEND_SERVER) 1 else 0,
            onSelect = { i ->
                app.appScope.launch {
                    app.settings.setDelaBackend(if (i == 1) Settings.DELA_BACKEND_SERVER else Settings.DELA_BACKEND_TODOIST)
                }
            },
        )
        if (backend == Settings.DELA_BACKEND_SERVER && link == null) {
            Spacer(Modifier.height(6.dp))
            PaperHint("Сервер не подключён: вкладка покажет только копию телефона, правки встанут в очередь.", MaterialTheme.colorScheme.error)
        }
    }

    PaperCard(
        label = "сервер дел",
        info = "Адрес и токен — из QR, который печатает на компе python -m pravka_dela pair. Хранятся в закрытой " +
            "памяти и с базой не едут. Правки уходят через десять секунд тишины и на пятиминутном тике службы; " +
            "без сети копятся в очереди и уходят сами — повтор сервер не удваивает.",
    ) {
        val l = link
        if (l == null) {
            PaperButton(
                if (busy) "Проверяю…" else "Сканировать QR с компа",
                icon = Glyphs.Barcode,
                primary = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scanQr(context, onFail = { error = it }) { text ->
                        val pair = DelaSync.parsePairing(text)
                        if (pair == null) error = "Это не QR Дел: нужен тот, что печатает python -m pravka_dela pair"
                        else connect(pair.first, pair.second)
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            PaperHint("Или вручную:")
            PaperField(
                url, { url = it; error = "" },
                label = "Адрес",
                placeholder = "https://dela.<имя>.netcraze.pro:8443",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(6.dp))
            PaperField(token, { token = it; error = "" }, label = "Токен устройства", visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(6.dp))
            PaperTextButton("Подключить", enabled = !busy && url.isNotBlank() && token.isNotBlank(), onClick = { connect(url, token) })
        } else {
            val bad = st.lastError.isNotBlank() && st.lastErrorAt >= st.lastOk
            PaperRow(
                title = l.url.substringAfter("://").trimEnd('/'),
                hint = delaHint(l, st, queued.size, snap, now),
                icon = Glyphs.Delo,
                trailing = { StatusDot(if (bad) false else if (st.lastOk > 0 || snap.syncedAt > 0) true else null) },
                onClick = null,
            )
            if (bad) {
                Spacer(Modifier.height(4.dp))
                PaperHint("Не вышло: ${st.lastError}", MaterialTheme.colorScheme.error)
            }
            if (queued.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                PaperHint("В очереди ${queued.size}: " + queued.take(3).joinToString("; ") { ru.zf.pravka.core.Dela.describe(it.op, snap) } +
                    if (queued.size > 3) "…" else "")
            }
            Spacer(Modifier.height(6.dp))
            PaperButton(
                if (st.running) "Связываюсь…" else "Отправить и обновить сейчас",
                icon = Glyphs.Refresh,
                enabled = !st.running,
                modifier = Modifier.fillMaxWidth(),
                onClick = { app.appScope.launch { app.delaSync.sync("руками") } },
            )
            Row { PaperTextButton("Отключить", onClick = { askOut = true }) }
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            PaperHint(error, MaterialTheme.colorScheme.error)
        }
    }

    if (askOut) {
        PaperAlert(
            onDismiss = { askOut = false },
            title = "Отключить Дела?",
            icon = Glyphs.Delo,
            dismiss = SheetAction("Оставить") { askOut = false },
            destructive = SheetAction("Отключить") {
                askOut = false
                app.delaSync.disconnect()
                Feedback.toast(context, "Дела отключены")
            },
        ) {
            PaperHint(
                "Телефон забудет адрес и токен. Копия и очередь останутся: подключишься снова — " +
                    "очередь уйдёт, копия соберётся заново." +
                    if (queued.isNotEmpty()) " Сейчас в очереди ${queued.size} — до подключения их никто не увидит." else ""
            )
        }
    }
}

private fun delaHint(l: DelaSync.Link, st: DelaSync.Status, queued: Int, snap: ru.zf.pravka.core.Dela.Snapshot, now: Long): String {
    val parts = mutableListOf<String>()
    parts += l.name.ifBlank { l.user }
    val last = maxOf(st.lastOk, snap.syncedAt)
    when {
        st.running -> parts += "связываюсь…"
        last > 0 -> {
            val mins = ((now - last) / 60_000L).toInt()
            parts += when {
                mins < 1 -> "обновлено только что"
                mins < 120 -> "обновлено $mins мин назад"
                else -> "обновлено " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(last))
            }
        }
        else -> parts += "ещё не обновлялось"
    }
    parts += "дел ${snap.tasks.values.count { it.open }}"
    if (queued > 0) parts += "в очереди $queued"
    return parts.joinToString(" · ")
}
