package ru.zf.pravka

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

// Облако семьи (26.09.2026) — домашний сервер владельца по WebDAV. Google
// Drive был первым и снят: Google выключил клиент Правки, владелец: «Не надо
// с drive. Только с личным облаком».

/** Группа «Облако семьи» в Подключениях. */
@Composable
internal fun FamilyCloudSettings(app: PravkaApp) {
    val home by app.homeServer.saved.collectAsState()
    HomeServerCard(app)
    val profile by app.profileStore.flow.collectAsState()
    if (profile?.has(ru.zf.pravka.data.Profile.Mode.MONEY) == true) MoneySyncCard(app)
    if (home != null) CloudBackupCard(app)
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
            "одну строку. Пароль хранится в закрытой памяти приложения и не уезжает с копией базы.",
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
            PaperHint("Адрес — с https:// и портом 8443 (http:// Правка сама заменит на https://). Логин и пароль — из отчёта сервера. Подключится, только если сервер ответил и папка «Правка» на нём есть или завелась.")
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
                    "подключишься снова — обмен продолжится с того же места."
            )
        }
    }
}

/** Общие Деньги: когда был обмен, что пришло, с какими телефонами, кнопка «сейчас». */
@Composable
internal fun MoneySyncCard(app: PravkaApp) {
    val home by app.homeServer.saved.collectAsState()
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
        if (home == null) {
            PaperHint("Облако семьи не подключено: задай домашний сервер выше.")
            return@PaperCard
        }
        PaperRow(
            title = syncTitle(st, now),
            hint = syncHint(st),
            icon = Glyphs.Refresh,
            trailing = { StatusDot(if (st.error.isNotBlank() || st.idle.isNotBlank()) false else if (st.at > 0) true else null) },
            onClick = null,
        )
        if (st.error.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            PaperHint("Не вышло: ${st.error}", MaterialTheme.colorScheme.error)
        }
        if (st.idle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            PaperHint("Обмен стоит: ${st.idle}", MaterialTheme.colorScheme.error)
        }
        // Куда телефон выкладывает свой журнал — сменился адрес сервера, и это видно здесь.
        if (st.server.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            PaperHint(
                "Сервер: ${st.server}" + if (st.uploadedAt > 0) " · свой журнал там с " +
                    SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(st.uploadedAt)) else ""
            )
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
            "intervals): пароли входа на сервер стоит беречь.",
    ) {
        PaperRow(
            title = if (st.sentAt > 0) "Последняя: " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(st.sentAt))
            else "Ещё не выгружалась",
            hint = when {
                st.running -> "выгружаю…"
                st.waiting -> "свежая копия ждёт Wi-Fi"
                st.sentAt > 0 -> "${st.sent} · ${mb(st.bytes)} · своих копий на сервере ${st.copies}, ${mb(st.copiesBytes)}"
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
    if (home == null) {
        // Облака нет, а свой журнал обмена есть — телефон уже менялся, и вход
        // пропал: это поломка, о ней строка и скажет. Нет журнала — не
        // настраивали, и строки нет.
        var lost by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { lost = withContext(Dispatchers.IO) { app.moneyCloudSync.hasOwnJournal() } }
        if (lost) PaperHint(
            "общие деньги · облако семьи отключено — обмен стоит. Подключи: Настройки → Подключения → Облако семьи",
            MaterialTheme.colorScheme.error,
        )
        return
    }
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
    val busyMin = if (st.running && st.startedAt > 0) (now - st.startedAt) / 60_000 else 0
    val text = when {
        st.running -> "общие деньги · меняюсь с сервером…" + if (busyMin >= 1) " уже $busyMin мин" else ""
        st.idle.isNotBlank() -> "общие деньги · обмен стоит: ${st.idle}"
        st.error.isNotBlank() -> "общие деньги · не вышло: ${st.error}" + st.server.takeIf { it.isNotBlank() }?.let { " · сервер $it" }.orEmpty()
        else -> "общие деньги · " + syncTitle(st, now) + syncHint(st).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() +
            st.server.takeIf { it.isNotBlank() }?.let { " · сервер $it" }.orEmpty()
    }
    val alarm = !st.running && (st.error.isNotBlank() || st.idle.isNotBlank()) || busyMin >= 3
    PaperHint(text, if (alarm) MaterialTheme.colorScheme.error else null)
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
    // Сервер подтвердил свои куски того же размера — журнал правда там, а не «запрос ушёл».
    if (st.uploadedAt > 0) parts.add("свой журнал на сервере")
    for ((d, n) in st.from) if (n > 0) parts.add("${st.devices[d] ?: d} +$n")
    return parts.joinToString(" · ").ifEmpty { "изменений не было" }
}
