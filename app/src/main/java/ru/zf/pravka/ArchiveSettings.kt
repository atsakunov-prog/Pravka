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
import ru.zf.pravka.data.ArchiveSync
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperRow
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.StatusDot
import ru.zf.pravka.ui.scanQr

// Архив на домашнем компе (01.10.2026, docs/arkhiv.md): каждый ввод — на
// сервер, к нему ходит Claude из claude.ai. Подключение — QR из
// `python -m pravka_archive pair` на компе: адрес и токен телефона.

/** Группа «Архив» в Подключениях. */
@Composable
internal fun ArchiveSettings(app: PravkaApp) {
    val context = LocalContext.current
    val link by app.archiveSync.link.collectAsState()
    val st by app.archiveSync.status.collectAsState()
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var askOut by remember { mutableStateOf(false) }
    var askAll by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    fun connect(u: String, t: String) {
        busy = true
        error = ""
        app.appScope.launch {
            app.archiveSync.connect(u, t)
                .onSuccess {
                    url = ""
                    token = ""
                    Feedback.toast(context, "Архив подключён — отправляю всё, что есть", long = true)
                }
                .onFailure { e -> error = e.message ?: e.javaClass.simpleName }
            busy = false
        }
    }

    PaperCard(
        label = "архив на компе",
        info = "Вся жизнь из Правки — на домашнем компе: лента, телефон по дням, еда, силовые и зарядка, " +
            "деньги, каждая диктовка, разговоры с тренером. Каждый ввод уходит через несколько секунд тишины, " +
            "не дошедшее — на тике службы. Телефон забывает (еда, тренировки, журналы по сроку), архив — нет. " +
            "К архиву ходит Claude из claude.ai коннектором «Правка». Адрес и токен — из QR, который печатает " +
            "на компе python -m pravka_archive pair; хранятся в закрытой памяти и с базой не едут.",
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
                        val pair = ArchiveSync.parsePairing(text)
                        if (pair == null) error = "Это не QR архива: нужен тот, что печатает pair на компе"
                        else connect(pair.first, pair.second)
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            PaperHint("Или вручную:")
            PaperField(
                url, { url = it; error = "" },
                label = "Адрес",
                placeholder = "https://server.<имя>.netcraze.pro:8443",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(6.dp))
            PaperField(
                token, { token = it; error = "" },
                label = "Токен телефона",
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(6.dp))
            PaperTextButton(
                "Подключить",
                enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                onClick = { connect(url, token) },
            )
        } else {
            val bad = st.lastError.isNotBlank() && st.lastErrorAt >= st.lastOk
            PaperRow(
                title = l.url.substringAfter("://").trimEnd('/'),
                hint = archiveHint(st, now),
                icon = Glyphs.Archive,
                trailing = { StatusDot(if (bad) false else if (st.lastOk > 0) true else null) },
                onClick = null,
            )
            if (bad) {
                Spacer(Modifier.height(4.dp))
                PaperHint("Не вышло: ${st.lastError}", MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            PaperButton(
                if (st.running) "Отправляю…" else "Отправить сейчас",
                icon = Glyphs.Upload,
                enabled = !st.running,
                modifier = Modifier.fillMaxWidth(),
                onClick = { app.appScope.launch { app.archiveSync.tick() } },
            )
            Row {
                PaperTextButton("Отправить всё заново", enabled = !st.running, onClick = { askAll = true })
                PaperTextButton("Отключить", onClick = { askOut = true })
            }
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            PaperHint(error, MaterialTheme.colorScheme.error)
        }
    }

    if (askAll) {
        PaperAlert(
            onDismiss = { askAll = false },
            title = "Отправить всё заново?",
            icon = Glyphs.Upload,
            dismiss = SheetAction("Не надо") { askAll = false },
            confirm = SheetAction("Отправить") {
                askAll = false
                app.appScope.launch { app.archiveSync.resendAll() }
            },
        ) {
            PaperHint(
                "Телефон забудет, что уже отправлено, и пошлёт всё, что у него есть. Повторы сервер отбросит " +
                    "сам — ничего не удвоится. Нужно, если на компе базу собирали заново."
            )
        }
    }

    if (askOut) {
        PaperAlert(
            onDismiss = { askOut = false },
            title = "Отключить архив?",
            icon = Glyphs.Archive,
            dismiss = SheetAction("Оставить") { askOut = false },
            destructive = SheetAction("Отключить") {
                askOut = false
                app.archiveSync.disconnect()
                Feedback.toast(context, "Архив отключён")
            },
        ) {
            PaperHint(
                "Телефон забудет адрес и токен. Всё, что уже в архиве, там и останется; подключишься снова — " +
                    "отправка продолжится с того же места."
            )
        }
    }
}

private fun archiveHint(st: ArchiveSync.Status, now: Long): String {
    val parts = mutableListOf<String>()
    when {
        st.running -> parts += "отправляю…"
        st.lastOk > 0 -> {
            val mins = ((now - st.lastOk) / 60_000L).toInt()
            parts += when {
                mins < 1 -> "отправлено только что"
                mins < 120 -> "отправлено $mins мин назад"
                else -> "отправлено " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(st.lastOk))
            }
        }
        else -> parts += "ещё ничего не отправлено"
    }
    if (st.pending > 0) parts += "ждёт ${st.pending}"
    if (st.sent > 0) parts += "принято всего ${st.sent}"
    return parts.joinToString(" · ")
}
