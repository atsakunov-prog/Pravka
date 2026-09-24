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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.pravka.data.MoneyDriveSync
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

// Семейный Google Drive (25.09.2026): вход и обмен Деньгами. Владелец завёл
// отдельный аккаунт под Правку — «не надо личный»; на телефонах этот аккаунт
// не нужен, вход идёт через браузер (provider/GoogleAuth.kt).

/** Группа «Google Drive» в Подключениях. */
@Composable
internal fun GoogleDriveSettings(app: PravkaApp) {
    val context = LocalContext.current
    val acc by app.googleAuth.account.collectAsState()
    val waiting by app.googleAuth.waiting.collectAsState()
    var error by remember { mutableStateOf("") }
    var askOut by remember { mutableStateOf(false) }

    PaperCard(
        label = "семейный google drive",
        info = "Сюда Правка кладёт общие Деньги: у каждого телефона свой журнал правок, телефоны читают " +
            "журналы друг друга и складывают одну и ту же базу. Входить нужно под семейным аккаунтом — " +
            "тем же на всех телефонах. Аккаунт в сам телефон добавлять не нужно: вход идёт через браузер. " +
            "Правка видит в Drive только свои файлы (папка «Правка»), чужие документы ей не видны. " +
            "Ключ входа хранится в закрытой памяти приложения и не уезжает с копией базы.",
    ) {
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
                PaperHint("Жду ответа из браузера: войди под семейным аккаунтом и нажми «Разрешить»…")
                Spacer(Modifier.height(4.dp))
                PaperTextButton("Отменить вход", onClick = { app.googleAuth.cancel() })
            } else {
                PaperButton(
                    "Подключить Google Drive",
                    icon = Glyphs.Link,
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        error = ""
                        app.appScope.launch {
                            app.googleAuth.signIn { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure { e -> error = "Не открылся браузер: ${e.message}" }
                            }.onSuccess { acc ->
                                app.googleDrive.forget()
                                Feedback.toast(context, "Подключено: ${acc.email.ifBlank { "аккаунт Google" }}", long = true)
                                // Назад в Правку: браузер остался сверху.
                                runCatching {
                                    context.startActivity(
                                        Intent(context, MainActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                    )
                                }
                                app.appScope.launch { app.moneyDriveSync.sync("вход") }
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

    if (!app.googleAuth.secretFromBuild) SecretCard(app)

    val profile by app.profileStore.flow.collectAsState()
    if (profile?.has(ru.zf.pravka.data.Profile.Mode.MONEY) == true) MoneySyncCard(app)
    if (acc != null) DriveBackupCard(app)

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
                    app.googleDrive.forget()
                    Feedback.toast(context, "Google Drive отключён")
                }
            },
        ) {
            PaperHint(
                "Общие Деньги перестанут меняться с другими телефонами. Всё, что уже есть на этом " +
                    "телефоне, останется; журналы в Drive тоже. Подключишься снова — обмен продолжится с того же места."
            )
        }
    }
}

/**
 * Секрет клиента руками — запасной путь, если сборка пришла без него
 * (в секретах GitHub нет GOOGLE_CLIENT_SECRET). Вписывается один раз на телефон.
 */
@Composable
private fun SecretCard(app: PravkaApp) {
    var secret by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(app.googleAuth.clientSecret.isNotBlank()) }
    PaperCard(
        label = "секрет клиента",
        info = "Вход через браузер требует секрет клиента Google (тип «Desktop app»). Обычно он приходит " +
            "со сборкой: в репозитории на GitHub, Settings → Secrets and variables → Actions, секрет " +
            "GOOGLE_CLIENT_SECRET. Эта сборка пришла без него — можно вписать здесь один раз.",
    ) {
        PaperHint(
            if (saved) "Вписан руками на этом телефоне."
            else "Сборка без секрета: положи GOOGLE_CLIENT_SECRET в секреты GitHub или впиши его здесь.",
            if (saved) null else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
        PaperField(
            value = secret,
            onValueChange = { secret = it },
            label = "Client secret",
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Spacer(Modifier.weight(1f))
            PaperButton("Сохранить", icon = if (saved) Glyphs.Check else null, primary = true, enabled = secret.isNotBlank(), onClick = {
                app.googleAuth.saveSecret(secret)
                secret = ""
                saved = true
            })
        }
    }
}

/** Общие Деньги: когда был обмен, что пришло, с какими телефонами, кнопка «сейчас». */
@Composable
internal fun MoneySyncCard(app: PravkaApp) {
    val acc by app.googleAuth.account.collectAsState()
    val st by app.moneyDriveSync.status.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = System.currentTimeMillis()
        }
    }
    PaperCard(
        label = "общие деньги",
        info = "Правки Денег уходят в семейный Drive через полминуты, а чужие приходят на тике службы " +
            "(раз в пять минут) и когда открываешь вкладку Деньги. Операции сливаются по номеру, " +
            "решения — по последней правке, но категорию, поставленную человеком, справочник и модель " +
            "не перебивают. Записи не удаляются никогда. Черновики до «ОК» остаются на своём телефоне.",
    ) {
        if (acc == null) {
            PaperHint("Семейный Google Drive не подключён: Настройки → Подключения → Google Drive.")
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
            PaperHint("Телефоны: " + st.devices.entries.joinToString { (d, name) -> if (d == app.moneyDriveSync.device) "$name (этот)" else name })
        }
        Spacer(Modifier.height(6.dp))
        PaperButton(
            if (st.running) "Меняюсь…" else "Синхронизировать сейчас",
            icon = Glyphs.Refresh,
            enabled = !st.running,
            modifier = Modifier.fillMaxWidth(),
            onClick = { app.appScope.launch { app.moneyDriveSync.sync("кнопка") } },
        )
    }
}

/** Ночная копия базы в Drive: что уехало, сколько копий там лежит, «выгрузить сейчас». */
@Composable
private fun DriveBackupCard(app: PravkaApp) {
    val st by app.driveBackup.status.collectAsState()
    PaperCard(
        label = "копия базы в drive",
        info = "Каждую ночь телефон снимает zip всей базы (Настройки → База данных → копия раз в " +
            "сутки), и как только он снят — по Wi-Fi уезжает в папку «Правка/Копии базы» семейного " +
            "Drive. Копии каждого человека — со своим именем в названии, телефон чистит только свои: " +
            "в Drive остаётся неделя каждый день и по копии на месяц за год. Нет Wi-Fi трое суток — " +
            "едет и по мобильной сети. Внутри архива — и ключи (Anthropic, Todoist, Notion, " +
            "intervals): семейный аккаунт стоит держать под двухфакторной защитой.",
    ) {
        PaperRow(
            title = if (st.sentAt > 0) "Последняя: " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(st.sentAt))
            else "Ещё не выгружалась",
            hint = when {
                st.running -> "выгружаю…"
                st.waiting -> "свежая копия ждёт Wi-Fi"
                st.sentAt > 0 -> "${st.sent} · ${mb(st.bytes)} · своих копий в Drive ${st.copies}, ${mb(st.copiesBytes)}"
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
            onClick = { app.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { app.driveBackup.tick(force = true) } },
        )
        PaperHint("Выгружает последнюю снятую копию и по мобильной сети. Свежую снимает «Сделать копию сейчас» в «Базе данных».")
    }
}

private fun mb(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale("ru"), "%.1f МБ", bytes / 1024.0 / 1024.0) else "${bytes / 1024} КБ"

/** Строка под переключателем вкладки Деньги: видно, что общие и когда был обмен. */
@Composable
internal fun MoneySyncLine(app: PravkaApp) {
    val acc by app.googleAuth.account.collectAsState()
    if (acc == null) return
    val st by app.moneyDriveSync.status.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        // Вкладку открыли — забрать чужое, если давно не менялись.
        if (System.currentTimeMillis() - app.moneyDriveSync.status.value.at > 60_000) {
            app.appScope.launch { app.moneyDriveSync.sync("вкладка") }
        }
        while (true) {
            delay(20_000)
            now = System.currentTimeMillis()
        }
    }
    val text = when {
        st.running -> "общие деньги · меняюсь с Drive…"
        st.error.isNotBlank() -> "общие деньги · не вышло: ${st.error}"
        else -> "общие деньги · " + syncTitle(st, now) + syncHint(st).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }
    PaperHint(text, if (st.error.isNotBlank() && !st.running) MaterialTheme.colorScheme.error else null)
}

private fun syncTitle(st: MoneyDriveSync.Status, now: Long): String {
    if (st.at == 0L) return if (st.running) "первый обмен…" else "обмена ещё не было"
    val min = (now - st.at) / 60_000
    return "синхронизировано " + when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 24 * 60 -> "${min / 60} ч назад"
        else -> SimpleDateFormat("d MMMM", Locale("ru")).format(Date(st.at))
    }
}

private fun syncHint(st: MoneyDriveSync.Status): String {
    if (st.at == 0L) return ""
    val parts = ArrayList<String>()
    if (st.sent > 0) parts.add("отправлено ${st.sent}")
    for ((d, n) in st.from) if (n > 0) parts.add("${st.devices[d] ?: d} +$n")
    return parts.joinToString(" · ").ifEmpty { "изменений не было" }
}
