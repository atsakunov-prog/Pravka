package ru.zf.pravka

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import ru.zf.pravka.data.MoneyCloudSync
import ru.zf.pravka.provider.FamilyCloud
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperRow
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.StatusDot

// Облако семьи (26.09.2026): домашний сервер владельца по WebDAV — главное,
// семейный Google Drive — запасное (его клиент Google однажды просто
// выключил: «ужасно глючная штука»). Задан сервер — Деньги и копии едут
// туда (`PravkaApp.familyCloud()`).

/** Группа «Облако семьи» в Подключениях. */
@Composable
internal fun FamilyCloudSettings(app: PravkaApp) {
    val home by app.homeServer.saved.collectAsState()
    val acc by app.googleAuth.account.collectAsState()
    HomeServerCard(app)
    val profile by app.profileStore.flow.collectAsState()
    if (profile?.has(ru.zf.pravka.data.Profile.Mode.MONEY) == true) MoneySyncCard(app)
    if (home != null || acc != null) CloudBackupCard(app)
    GoogleDriveCard(app)
}

/** Домашний сервер: адрес, свой вход телефона, проверка. */
@Composable
private fun HomeServerCard(app: PravkaApp) {
    val context = LocalContext.current
    val saved by app.homeServer.saved.collectAsState()
    val sync by app.moneyCloudSync.status.collectAsState()
    var url by remember { mutableStateOf(saved?.url.orEmpty()) }
    var user by remember { mutableStateOf(saved?.user.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var askOut by remember { mutableStateOf(false) }

    PaperCard(
        label = "домашний сервер",
        info = "Всегда включённый комп дома: на нём rclone отдаёт папку по WebDAV, снаружи его " +
            "пускает роутер (KeenDNS, HTTPS делает роутер). Сюда ездят общие Деньги (папка " +
            "«Правка/Деньги») и ночные копии базы («Правка/Копии базы»), рядом — книги Слушалки. " +
            "У каждого телефона свой вход (sasha, marianna): потерялся телефон — на сервере убирают " +
            "одну строку. Пароль хранится в закрытой памяти приложения и не уезжает с копией базы. " +
            "Пока сервер задан, Google Drive не используется.",
    ) {
        val s = saved
        if (s == null) {
            PaperField(
                url, { url = it; error = "" },
                label = "Адрес",
                placeholder = "https://webdav.<имя>.netcraze.pro:8443/",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(6.dp))
            PaperField(user, { user = it; error = "" }, label = "Логин этого телефона")
            Spacer(Modifier.height(6.dp))
            PaperField(
                pass, { pass = it; error = "" },
                label = "Пароль",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(8.dp))
            PaperButton(
                if (busy) "Проверяю…" else "Подключить",
                icon = Glyphs.Link,
                primary = true,
                enabled = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    error = ""
                    app.appScope.launch {
                        app.homeServer.connect(url, user, pass)
                            .onSuccess {
                                pass = ""
                                Feedback.toast(context, "Домашний сервер подключён", long = true)
                                app.appScope.launch { app.moneyCloudSync.sync("подключение") }
                            }
                            .onFailure { e -> error = FamilyCloud.why(e, "Домашний сервер") }
                        busy = false
                    }
                },
            )
            PaperHint("Адрес, логин и пароль — из отчёта сервера. Подключится, только если сервер ответил и папка «Правка» на нём есть или завелась.")
        } else {
            val bad = error.isNotBlank() || sync.error.isNotBlank()
            PaperRow(
                title = s.url.substringAfter("://").trimEnd('/'),
                hint = "вход ${s.user} · подключён " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(s.at)),
                icon = Glyphs.Cloud,
                trailing = { StatusDot(if (bad) false else true) },
                onClick = null,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                PaperTextButton(
                    if (busy) "Проверяю…" else "Проверить",
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = ""
                        note = ""
                        app.appScope.launch {
                            app.homeServer.check()
                                .onSuccess { n -> note = "Сервер на связи: в «Правке» ${n} ${if (n == 1) "папка или файл" else "папок и файлов"}." }
                                .onFailure { e -> error = FamilyCloud.why(e, "Домашний сервер") }
                            busy = false
                        }
                    },
                )
                PaperTextButton("Отключить", onClick = { askOut = true })
            }
            if (note.isNotBlank()) PaperHint(note)
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            PaperHint(error, MaterialTheme.colorScheme.error)
        }
    }

    if (askOut) {
        PaperAlert(
            onDismiss = { askOut = false },
            title = "Отключить домашний сервер?",
            icon = Glyphs.Cloud,
            dismiss = SheetAction("Оставить") { askOut = false },
            destructive = SheetAction("Отключить") {
                askOut = false
                app.homeServer.disconnect()
                note = ""
                error = ""
                pass = ""
                Feedback.toast(context, "Домашний сервер отключён")
            },
        ) {
            PaperHint(
                "Телефон забудет адрес и пароль. Всё, что уже есть на телефоне и на сервере, останется; " +
                    "подключишься снова — обмен продолжится с того же места. Если вошли в Google Drive — " +
                    "обмен пойдёт туда."
            )
        }
    }
}

/** Семейный Google Drive — запасное облако. */
@Composable
private fun GoogleDriveCard(app: PravkaApp) {
    val context = LocalContext.current
    val acc by app.googleAuth.account.collectAsState()
    val home by app.homeServer.saved.collectAsState()
    val waiting by app.googleAuth.waiting.collectAsState()
    var error by remember { mutableStateOf("") }
    var askOut by remember { mutableStateOf(false) }

    PaperCard(
        label = "семейный google drive",
        info = "Запасное облако: работает, пока не задан домашний сервер. Входить нужно под семейным " +
            "аккаунтом — тем же на всех телефонах: браузер сначала предложит аккаунт телефона — выбери " +
            "«другой аккаунт». Аккаунт в сам телефон добавлять не нужно: вход идёт через браузер. " +
            "Правка видит в Drive только свои файлы (папка «Правка»), чужие документы ей не видны. " +
            "Ключ входа хранится в закрытой памяти приложения и не уезжает с копией базы.",
    ) {
        if (home != null) {
            PaperHint("Сейчас Деньги и копии ездят через домашний сервер — Drive не используется.")
            Spacer(Modifier.height(6.dp))
        }
        val a = acc
        if (a == null) {
            PaperRow(
                title = "Не подключено",
                hint = "вход через браузер под семейным аккаунтом",
                icon = Glyphs.Cloud,
                trailing = { StatusDot(null) },
                onClick = null,
            )
            Spacer(Modifier.height(6.dp))
            if (waiting) {
                PaperHint("Жду ответа из браузера: войди под СЕМЕЙНЫМ аккаунтом (не своим личным) и нажми «Разрешить»…")
                Spacer(Modifier.height(4.dp))
                PaperTextButton("Отменить вход", onClick = { app.googleAuth.cancel() })
            } else {
                PaperButton(
                    "Подключить Google Drive",
                    icon = Glyphs.Link,
                    primary = home == null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        error = ""
                        app.appScope.launch {
                            app.googleAuth.signIn { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure { e -> error = "Не открылся браузер: ${e.message}" }
                            }.onSuccess { acc ->
                                app.driveCloud.forget()
                                Feedback.toast(context, "Подключено: ${acc.email.ifBlank { "аккаунт Google" }}", long = true)
                                // Назад в Правку: браузер остался сверху.
                                runCatching {
                                    context.startActivity(
                                        Intent(context, MainActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                    )
                                }
                                app.appScope.launch { app.moneyCloudSync.sync("вход") }
                            }.onFailure { e -> error = e.message ?: e.javaClass.simpleName }
                        }
                    },
                )
            }
        } else {
            PaperRow(
                title = a.email.ifBlank { "аккаунт Google" },
                hint = "подключено " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(a.at)),
                icon = Glyphs.Cloud,
                trailing = { StatusDot(true) },
                onClick = null,
            )
            Spacer(Modifier.height(4.dp))
            PaperTextButton("Отключить", onClick = { askOut = true })
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            PaperHint(error, MaterialTheme.colorScheme.error)
        }
    }

    if (askOut) {
        PaperAlert(
            onDismiss = { askOut = false },
            title = "Отключить Google Drive?",
            icon = Glyphs.Cloud,
            dismiss = SheetAction("Оставить") { askOut = false },
            destructive = SheetAction("Отключить") {
                askOut = false
                app.appScope.launch {
                    app.googleAuth.signOut()
                    app.driveCloud.forget()
                    Feedback.toast(context, "Google Drive отключён")
                }
            },
        ) {
            PaperHint(
                "Если домашний сервер не задан, общие Деньги перестанут меняться с другими телефонами. " +
                    "Всё, что уже есть на этом телефоне, останется; журналы в Drive тоже."
            )
        }
    }
}

/** Общие Деньги: когда был обмен, что пришло, с какими телефонами, кнопка «сейчас». */
@Composable
internal fun MoneySyncCard(app: PravkaApp) {
    val home by app.homeServer.saved.collectAsState()
    val acc by app.googleAuth.account.collectAsState()
    val st by app.moneyCloudSync.status.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = System.currentTimeMillis()
        }
    }
    PaperCard(
        label = "общие деньги",
        info = "Правки Денег уходят в облако семьи через полминуты, а чужие приходят на тике службы " +
            "(раз в пять минут) и когда открываешь вкладку Деньги. Операции сливаются по номеру, " +
            "решения — по последней правке, но категорию, поставленную человеком, справочник и модель " +
            "не перебивают. Записи не удаляются никогда. Черновики до «ОК» остаются на своём телефоне.",
    ) {
        if (home == null && acc == null) {
            PaperHint("Облако семьи не подключено: задай домашний сервер выше (или войди в Google Drive ниже).")
            return@PaperCard
        }
        PaperRow(
            title = syncTitle(st, now),
            hint = syncHint(st),
            icon = Glyphs.Refresh,
            trailing = { StatusDot(if (st.error.isNotBlank()) false else if (st.at > 0) true else null) },
            onClick = null,
        )
        if (st.error.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            PaperHint("Не вышло: ${st.error}", MaterialTheme.colorScheme.error)
        }
        if (st.devices.size > 1) {
            Spacer(Modifier.height(4.dp))
            PaperHint("Телефоны: " + st.devices.entries.joinToString { (d, name) -> if (d == app.moneyCloudSync.device) "$name (этот)" else name })
        }
        Spacer(Modifier.height(6.dp))
        PaperButton(
            if (st.running) "Меняюсь…" else "Синхронизировать сейчас",
            icon = Glyphs.Refresh,
            enabled = !st.running,
            modifier = Modifier.fillMaxWidth(),
            onClick = { app.appScope.launch { app.moneyCloudSync.sync("кнопка") } },
        )
    }
}

/** Ночная копия базы в облаке семьи: что уехало, сколько копий там лежит, «выгрузить сейчас». */
@Composable
private fun CloudBackupCard(app: PravkaApp) {
    val st by app.cloudBackup.status.collectAsState()
    PaperCard(
        label = "копия базы в облаке",
        info = "Каждую ночь телефон снимает zip всей базы (Настройки → База данных → копия раз в " +
            "сутки), и как только он снят — по Wi-Fi уезжает в папку «Правка/Копии базы» облака " +
            "семьи. Копии каждого человека — со своим именем в названии, телефон чистит только свои: " +
            "там остаётся неделя каждый день и по копии на месяц за год. Нет Wi-Fi трое суток — " +
            "едет и по мобильной сети. Внутри архива — и ключи (Anthropic, Todoist, Notion, " +
            "intervals): доступ к серверу и к семейному аккаунту стоит беречь.",
    ) {
        PaperRow(
            title = if (st.sentAt > 0) "Последняя: " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(st.sentAt))
            else "Ещё не выгружалась",
            hint = when {
                st.running -> "выгружаю…"
                st.waiting -> "свежая копия ждёт Wi-Fi"
                st.sentAt > 0 -> "${st.sent} · ${mb(st.bytes)} · своих копий (${st.toTitle.ifBlank { "Google Drive" }}) ${st.copies}, ${mb(st.copiesBytes)}"
                else -> "уедет после ближайшей ночной копии"
            },
            icon = Glyphs.Archive,
            trailing = { StatusDot(if (st.error.isNotBlank()) false else if (st.sentAt > 0) true else null) },
            onClick = null,
        )
        if (st.error.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            PaperHint("Не вышло: ${st.error}", MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(6.dp))
        PaperButton(
            if (st.running) "Выгружаю…" else "Выгрузить сейчас",
            icon = Glyphs.Upload,
            enabled = !st.running,
            modifier = Modifier.fillMaxWidth(),
            onClick = { app.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { app.cloudBackup.tick(force = true) } },
        )
        PaperHint("Выгружает последнюю снятую копию и по мобильной сети. Свежую снимает «Сделать копию сейчас» в «Базе данных».")
    }
}

private fun mb(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale("ru"), "%.1f МБ", bytes / 1024.0 / 1024.0) else "${bytes / 1024} КБ"

/** Строка под переключателем вкладки Деньги: видно, что общие и когда был обмен. */
@Composable
internal fun MoneySyncLine(app: PravkaApp) {
    val home by app.homeServer.saved.collectAsState()
    val acc by app.googleAuth.account.collectAsState()
    if (home == null && acc == null) return
    val st by app.moneyCloudSync.status.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        // Вкладку открыли — забрать чужое, если давно не менялись.
        if (System.currentTimeMillis() - app.moneyCloudSync.status.value.at > 60_000) {
            app.appScope.launch { app.moneyCloudSync.sync("вкладка") }
        }
        while (true) {
            delay(20_000)
            now = System.currentTimeMillis()
        }
    }
    val text = when {
        st.running -> "общие деньги · меняюсь с ${if (home != null) "сервером" else "Drive"}…"
        st.error.isNotBlank() -> "общие деньги · не вышло: ${st.error}"
        else -> "общие деньги · " + syncTitle(st, now) + syncHint(st).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }
    PaperHint(text, if (st.error.isNotBlank() && !st.running) MaterialTheme.colorScheme.error else null)
}

private fun syncTitle(st: MoneyCloudSync.Status, now: Long): String {
    if (st.at == 0L) return if (st.running) "первый обмен…" else "обмена ещё не было"
    val min = (now - st.at) / 60_000
    return "синхронизировано " + when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 24 * 60 -> "${min / 60} ч назад"
        else -> SimpleDateFormat("d MMMM", Locale("ru")).format(Date(st.at))
    }
}

private fun syncHint(st: MoneyCloudSync.Status): String {
    if (st.at == 0L) return ""
    val parts = ArrayList<String>()
    if (st.sent > 0) parts.add("отправлено ${st.sent}")
    for ((d, n) in st.from) if (n > 0) parts.add("${st.devices[d] ?: d} +$n")
    return parts.joinToString(" · ").ifEmpty { "изменений не было" }
}
