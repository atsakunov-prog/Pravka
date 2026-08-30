package ru.zf.slushalka.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.zf.slushalka.BuildConfig

/**
 * Обновление приложения изнутри него самого.
 *
 * Сборка каждого коммита уезжает в ветку `apk-builds`, и там же лежит файлик с
 * номером версии. Приложение читает этот номер, и если он больше своего -
 * предлагает обновиться: качает APK и отдаёт системному установщику. Второму
 * слушателю больше не надо ничего «закидывать» - достаточно нажать «Обновить».
 *
 * APK подписан тем же ключом, что и установленное приложение, поэтому система
 * ставит его поверх, сохраняя все данные: позиции, закладки, разметку.
 */
class Updater(private val context: Context, private val settings: Settings) {

    data class Available(
        val versionCode: Int,
        val versionName: String,
        val builtAt: String,
        val apkUrl: String,
    )

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        data class Ready(val update: Available) : Status
        data class Downloading(val percent: Int) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private var lastCheck = 0L

    fun dismiss() {
        _status.value = Status.Idle
    }

    /** [manual] - проверка по кнопке: она игнорирует «недавно уже смотрели». */
    suspend fun check(manual: Boolean) {
        val prefs = settings.now()
        if (!manual && !prefs.updateAuto) return
        val now = System.currentTimeMillis()
        if (!manual && now - lastCheck < QUIET_MS) return
        if (_status.value is Status.Downloading) return
        lastCheck = now

        val infoUrl = prefs.updateUrl.trim().ifBlank { Settings.DEFAULT_UPDATE_URL }
        _status.value = Status.Checking
        _status.value = withContext(Dispatchers.IO) {
            runCatching {
                // Raw-адреса кэшируются, поэтому спрашиваем с меткой времени.
                val text = fetch("$infoUrl?t=$now")
                val fields = text.lineSequence()
                    .mapNotNull { line ->
                        val i = line.indexOf('=')
                        if (i <= 0) null else line.take(i).trim() to line.drop(i + 1).trim()
                    }
                    .toMap()
                val code = fields["versionCode"]?.toIntOrNull()
                    ?: return@runCatching Status.Failed("В файле версий нет versionCode")
                if (code <= BuildConfig.VERSION_CODE) return@runCatching Status.UpToDate
                Status.Ready(
                    Available(
                        versionCode = code,
                        versionName = fields["slushalka"] ?: code.toString(),
                        builtAt = fields["builtAt"].orEmpty(),
                        // Имя файла приезжает из того же build-info: переименование
                        // сборки не потребует новой версии приложения.
                        apkUrl = infoUrl.substringBeforeLast('/') + "/" +
                            (fields["apk"]?.takeIf { it.isNotBlank() } ?: APK_NAME),
                    )
                )
            }.getOrElse { Status.Failed(readable(it)) }
        }
    }

    suspend fun downloadAndInstall(update: Available) {
        _status.value = Status.Downloading(0)
        val file = withContext(Dispatchers.IO) {
            runCatching { download(update.apkUrl) }.getOrElse {
                _status.value = Status.Failed(readable(it))
                return@withContext null
            }
        } ?: return
        install(file, update)
    }

    private fun download(url: String): File {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        // Качаем во временный файл и переименовываем: оборванная закачка не
        // должна выглядеть как готовый APK.
        val part = File(dir, "$APK_NAME.part")
        val target = File(dir, APK_NAME)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Пустой ответ")
            val total = body.contentLength()
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            _status.value = Status.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
            }
        }
        if (part.length() < 1_000_000) throw IllegalStateException("Файл оказался слишком мал")
        target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("Не удалось сохранить файл")
        return target
    }

    /** Умеет ли приложение ставить APK - на Android 8+ это отдельное разрешение. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun install(file: File, update: Available) {
        if (!canInstall()) {
            _status.value = Status.Failed(
                "Разреши установку: приложение просит доступ «Установка неизвестных приложений»"
            )
            openInstallPermission()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    )
            )
            _status.value = Status.Ready(update)
        }.onFailure { _status.value = Status.Failed(readable(it)) }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun readable(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Нет сети"
        is java.net.SocketTimeoutException -> "Сервер не ответил"
        else -> e.message ?: "Не вышло проверить обновления"
    }

    private companion object {
        /** Запасное имя - для случая, когда в build-info его не указали. */
        const val APK_NAME = "slushalka.apk"
        /** Чаще, чем раз в полчаса, спрашивать про новую версию незачем. */
        const val QUIET_MS = 30 * 60_000L
    }
}
