package ru.zf.pravka

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.data.DataRoot
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.StatusDot

// Группа «База данных» (24.09.2026). Владелец: «чтобы у каждого приложения
// была своя база… файлы я должен иметь возможность скопировать. Если
// приложение открывается пустое, то оно считывает эти файлы». Здесь видно,
// где база сейчас и сколько весит, и отсюда она переезжает в папку
// Documents/Pravka — или открывается из неё на новом телефоне. Механика
// места — data/DataRoot.kt, сами операции над файлами — data/DbMove.kt.

@Composable
internal fun DataSettings(app: PravkaApp) {
    val context = LocalContext.current
    val where by DataRoot.where.collectAsState()
    val access by DataRoot.access.collectAsState()
    // Вес базы и что лежит в папке — с диска, на фоне; ключ — место и доступ,
    // чтобы вернувшись с системного экрана доступа сразу увидеть папку.
    var size by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var folder by remember { mutableStateOf<DataRoot.FolderInfo?>(null) }
    LaunchedEffect(where, access) {
        size = withContext(Dispatchers.IO) { runCatching { DataRoot.measureCurrent(context) }.getOrNull() }
        folder = withContext(Dispatchers.IO) {
            if (access) runCatching { DataRoot.inspect(context) }.getOrNull() else null
        }
    }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf<Ask?>(null) }

    PaperCard(label = "где база") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when (where) {
                    DataRoot.Where.FOLDER -> true
                    DataRoot.Where.FOLDER_NO_ACCESS -> false
                    DataRoot.Where.PRIVATE -> null
                }
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when (where) {
                    DataRoot.Where.PRIVATE -> "В памяти приложения"
                    DataRoot.Where.FOLDER -> "В папке Documents/${DataRoot.FOLDER_NAME}"
                    DataRoot.Where.FOLDER_NO_ACCESS -> "В папке, но доступа к файлам нет"
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        PaperHint(
            when (where) {
                DataRoot.Where.PRIVATE ->
                    "Снаружи её не видно: ни проводнику, ни компьютеру по кабелю. Скопировать " +
                        "нельзя, а удаление приложения стирает её вместе с ним."
                DataRoot.Where.FOLDER ->
                    "Скопировать базу — скопировать папку целиком. Удаление приложения её не трогает; " +
                        "на новом телефоне приложение откроет её отсюда же."
                DataRoot.Where.FOLDER_NO_ACCESS ->
                    "Разрешение «Доступ ко всем файлам» снято — лента, еда и деньги не читаются и " +
                        "не пишутся. Файлы в папке целы: верни доступ, и всё вернётся."
            },
            color = if (where == DataRoot.Where.FOLDER_NO_ACCESS) MaterialTheme.colorScheme.error else null,
        )
        size?.let { (files, bytes) ->
            PaperHint("${DataRoot.dir(context).absolutePath} · ${files} ${filesWord(files)} · ${megabytes(bytes)}")
        }
        DataRoot.startNote.takeIf { it.isNotBlank() }?.let { PaperHint("На этом старте: $it") }
        if (where == DataRoot.Where.FOLDER_NO_ACCESS) {
            Spacer(Modifier.height(8.dp))
            PaperButton("Вернуть доступ к файлам", onClick = { openAccess(context) }, icon = Glyphs.Key, primary = true)
        }
    }

    if (where == DataRoot.Where.PRIVATE) {
        PaperCard(
            label = "база в папку",
            info = "В папку переезжает всё, что приложение знает: лента и категории, словарь и " +
                "правила, дневник еды со снимками, силовые, деньги, дела, телефон, разборы, " +
                "журналы, копии по часам, свои промпты, настройки и ключи. В памяти приложения " +
                "остаются только модели Whisper (скачиваются заново), записи на повтор, " +
                "черновик тейка на лету и служебные отметки «когда что напоминали».\n\n" +
                "Переезд в два шага: сначала копия на ходу, потом перезапуск — на старте " +
                "докопируется то, что успело измениться, и база закрепится в папке. Прежние " +
                "файлы не удаляются: они откладываются в подпапку before-move-… в памяти " +
                "приложения.",
        ) {
            val info = folder
            when {
                !access -> {
                    PaperHint(
                        "Чтобы база стала файлами в папке, приложению нужен «Доступ ко всем файлам»: " +
                            "иначе после переустановки или на другом телефоне оно не прочтёт даже " +
                            "собственную базу — система считает её чужой."
                    )
                    Spacer(Modifier.height(8.dp))
                    PaperButton("Дать доступ к файлам", onClick = { openAccess(context) }, icon = Glyphs.Key, primary = true)
                }
                info?.passport != null -> {
                    val p = info.passport
                    Text(
                        "В Documents/${DataRoot.FOLDER_NAME} уже лежит база",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PaperHint(
                        listOfNotNull(
                            p.device.takeIf { it.isNotBlank() }?.let { "с телефона $it" },
                            p.createdAt.takeIf { it > 0 }?.let { "заведена ${stamp(it)}" },
                            "${info.files} ${filesWord(info.files)} · ${megabytes(info.bytes)}",
                        ).joinToString(" · ")
                    )
                    Spacer(Modifier.height(8.dp))
                    PaperButton(
                        "Открыть эту базу",
                        onClick = { ask = Ask.ADOPT },
                        icon = Glyphs.Archive,
                        primary = true,
                        enabled = progress == null,
                    )
                }
                info != null -> {
                    PaperHint(
                        if (info.exists) "Папка ${info.path} есть, базы в ней нет — база переедет туда."
                        else "Папки ${info.path} ещё нет — приложение её создаст."
                    )
                    Spacer(Modifier.height(8.dp))
                    val p = progress
                    PaperButton(
                        if (p == null) "Перенести базу в папку" else "Копирую ${p.first} из ${p.second}…",
                        onClick = { ask = Ask.COPY },
                        icon = Glyphs.Upload,
                        primary = true,
                        enabled = p == null,
                    )
                }
                else -> PaperHint("Смотрю папку…")
            }
            if (error.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    PaperCard(label = "как копировать") {
        PaperHint(
            "Папка — Documents/${DataRoot.FOLDER_NAME} во внутренней памяти: «Мои файлы» → " +
                "Внутренняя память → Documents, или телефон по кабелю на компьютере. Копируй " +
                "папку целиком — файлы связаны друг с другом.\n\n" +
                "Новый телефон или переустановка: положи папку на место, поставь приложение, " +
                "дай доступ к файлам — здесь появится «Открыть эту базу».\n\n" +
                "Внутри лежат и ключи (Anthropic, Todoist, Notion, intervals) — в datastore/" +
                "settings.preferences_pb. Чужому телефону папку не отдавай: у каждой установки " +
                "своя база и свои ключи. Файлы руками не правь, пока приложение работает."
        )
    }

    when (ask) {
        Ask.COPY -> PaperAlert(
            onDismiss = { ask = null },
            title = "Перенести базу в папку?",
            icon = Glyphs.Archive,
            confirm = SheetAction("Перенести", icon = Glyphs.Upload) {
                ask = null
                error = ""
                progress = 0 to 0
                app.appScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        DataRoot.prepareCopy(context) { done, total -> progress = done to total }
                    }
                    if (err != null) {
                        progress = null
                        error = "Не перенёс: $err"
                    } else {
                        app.eventLog.add("база: скопирована в папку, перезапуск для закрепления")
                        context.findActivity()?.let(DataRoot::restart)
                            ?: run { progress = null; error = "Скопировал — закроется при следующем запуске приложения" }
                    }
                }
            },
            dismiss = SheetAction("Отмена") { ask = null },
        ) {
            Text(
                "Все файлы базы скопируются в Documents/${DataRoot.FOLDER_NAME}, затем приложение " +
                    "перезапустится и дальше будет читать и писать только там. Прежние файлы " +
                    "останутся в памяти приложения, в подпапке before-move-…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Ask.ADOPT -> PaperAlert(
            onDismiss = { ask = null },
            title = "Открыть базу из папки?",
            icon = Glyphs.Archive,
            confirm = SheetAction("Открыть", icon = Glyphs.Archive) {
                ask = null
                error = ""
                app.appScope.launch {
                    val err = withContext(Dispatchers.IO) { DataRoot.prepareAdopt(context) }
                    if (err != null) {
                        error = "Не открыл: $err"
                    } else {
                        app.eventLog.add("база: открываю из папки, перезапуск")
                        context.findActivity()?.let(DataRoot::restart)
                            ?: run { error = "Откроется при следующем запуске приложения" }
                    }
                }
            },
            dismiss = SheetAction("Отмена") { ask = null },
        ) {
            Text(
                "Приложение перезапустится и будет работать с базой из папки. То, что лежит " +
                    "сейчас в памяти приложения, с ней НЕ сливается — отложится в подпапку " +
                    "before-move-… и останется на телефоне.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        null -> Unit
    }
}

private enum class Ask { COPY, ADOPT }

/** Системный экран доступа к файлам; если прошивка не знает экрана приложения — общий. */
private fun openAccess(context: Context) {
    runCatching { context.startActivity(DataRoot.accessIntent(context)) }
        .onFailure { runCatching { context.startActivity(DataRoot.accessIntentFallback()) } }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun filesWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "файл"
        m10 in 2..4 && m100 !in 12..14 -> "файла"
        else -> "файлов"
    }
}

private fun megabytes(bytes: Long): String =
    if (bytes < 1024 * 1024) "${(bytes / 1024).coerceAtLeast(1)} КБ"
    else String.format(Locale.forLanguageTag("ru"), "%.1f МБ", bytes / 1024.0 / 1024.0)

private fun stamp(ms: Long): String =
    SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru")).format(Date(ms))
