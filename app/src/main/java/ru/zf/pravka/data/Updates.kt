package ru.zf.pravka.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.zf.pravka.BuildConfig
import ru.zf.pravka.R

// Самообновление, как в Слушалке (slushalka/data/Updater.kt) и как у Телеграма:
// приложение само раз в сутки смотрит, нет ли сборки свежее, само её тянет и
// говорит «готово, ставить?». Play Store в этой истории нет и не будет.
//
// Источник - ветка `apk-builds`: CI после каждого пуша кладёт туда APK и
// `build-info.txt` рядом. Репозиторий публичный, raw.githubusercontent отдаёт
// оба файла без токена - нового секрета в приложении не завелось.
//
// ГЛАВНАЯ ОСТОРОЖНОСТЬ. Ветка `apk-builds` одна на весь репозиторий, и
// форс-пушит её КАЖДАЯ ветка: Правка, Слушалка, любая случайная. Номер сборки
// там - номер запуска workflow, а не место в истории, поэтому чужая сборка
// всегда «новее» по числу и при этом не содержит твоей работы. Три замка:
//   1. ветка. Обновляемся только на сборку из СВОЕЙ линии (BUILD_BRANCH);
//   2. имя файла. Берётся из build-info («apk=…»), а не угадывается;
//   3. сам APK. Перед установкой проверяется, что это ru.zf.pravka с тем самым
//      versionCode - пока качали, в ветку мог приехать следующий билд. Если
//      приехал именно следующий из нашей же линии (свежий build-info это
//      подтверждает) - берём его, а не выбрасываем 17 мегабайт с ошибкой.
//
// Подпись у всех сборок одна (keystore/pravka.jks), поэтому APK ставится
// поверх, не стирая данные. Это же и защита: чужой APK система не примет.
class Updates(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: Settings,
    private val eventLog: EventLog,
) {

    companion object {
        const val DEFAULT_INFO_URL =
            "https://raw.githubusercontent.com/atsakunov-prog/Pravka/apk-builds/build-info.txt"
        private const val FALLBACK_APK = "pravka-debug.apk"

        /** Раз в сутки - ровно то, что просил владелец. */
        const val CHECK_PERIOD_MS = 24 * 3_600_000L
        // Неудачная закачка (нет места, сеть отвалилась) не должна повторяться
        // каждые пять минут вместе с тиком службы.
        private const val RETRY_MS = 3_600_000L

        private const val NOTIF_ID = 46
        private const val CHANNEL = "pravka-update"
        private const val PREFS = "pravka_internal"
        private const val KEY_LAST_CHECK = "upd_last_check"
        private const val KEY_TOLD = "upd_told"       // «124:ready» - о чём уже сказали
        private const val KEY_INFO = "upd_info"       // последний build-info целиком
        private const val KEY_TRY_AT = "upd_try_at"
        private const val DIR = "update"
    }

    /** Что лежит в `apk-builds` прямо сейчас. */
    data class Build(
        val versionName: String,
        val versionCode: Int,
        val commit: String,
        val branch: String,
        val builtAt: String,
        val apkName: String,
    )

    /**
     * Своя линия работы? Сборка соседней ветки имеет номер больше, но она не
     * «новее»: в ней нет коммитов этой. Пустая ветка с любой стороны - старый
     * формат или локальная сборка, тогда не придираемся.
     */
    fun sameLine(build: Build): Boolean =
        build.branch.isBlank() || line.isBlank() || build.branch.equals(line, ignoreCase = true)

    fun isNewer(build: Build): Boolean =
        build.versionCode > BuildConfig.VERSION_CODE && sameLine(build)

    /**
     * Ветка, из которой принимаем обновления: своя, если владелец не назвал
     * другую. Обновляется на каждой проверке и на каждом тике - настройку
     * читать синхронно нельзя, а показывать карточку надо сразу.
     */
    @Volatile
    var line: String = BuildConfig.BUILD_BRANCH
        private set

    data class State(
        val latest: Build? = null,      // null = ещё не смотрели или не дошло
        val checking: Boolean = false,
        val progress: Int = -1,         // 0..100 пока качаем, -1 = не качаем
        val ready: File? = null,        // скачанный и проверенный APK
        val lastCheck: Long = 0L,
        val error: String = "",
    )

    // Состояние переживает смерть процесса: тап по уведомлению может прийти
    // через час, когда от прошлого запуска не осталось ничего. Что видели в
    // ветке - в prefs, скачанный APK - на диске.
    private val _state = MutableStateFlow(
        State(
            latest = parseInfo(prefs().getString(KEY_INFO, "").orEmpty()),
            lastCheck = prefs().getLong(KEY_LAST_CHECK, 0L),
        )
    )
    val state: StateFlow<State> = _state

    private val checking = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir() = File(context.cacheDir, DIR)

    // ---- проверка ----

    /**
     * Спрашивает у ветки, что там за сборка. [force] - кнопка «Проверить»
     * (игнорирует тумблер). Возвращает null, если не дошло: сети нет, ветки
     * нет, файл битый - причина ложится в state.error, следующий тик повторит.
     */
    suspend fun check(force: Boolean = false): Build? {
        if (!force && !settings.updAutoFlow.first()) return null
        if (!checking.compareAndSet(false, true)) return _state.value.latest
        _state.value = _state.value.copy(checking = true, error = "")
        try {
            line = settings.updBranchFlow.first().trim().ifBlank { BuildConfig.BUILD_BRANCH }
            val url = settings.updUrlFlow.first().trim().ifBlank { DEFAULT_INFO_URL }
            val now = System.currentTimeMillis()
            val body = withContext(Dispatchers.IO) {
                // Fastly держит raw пять минут - и заголовком, и меткой времени
                // (как в Слушалке): ручная кнопка обязана видеть правду сразу.
                val request = Request.Builder()
                    .url(if (url.contains('?')) "$url&t=$now" else "$url?t=$now")
                    .header("Cache-Control", "no-cache")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            }
            val build = parseInfo(body) ?: throw java.io.IOException("build-info.txt не разобрался")
            prefs().edit()
                .putLong(KEY_LAST_CHECK, now)
                .putString(KEY_INFO, body.take(1000))
                .apply()
            _state.value = _state.value.copy(
                latest = build,
                checking = false,
                lastCheck = now,
                ready = resolveReady(build),
                error = "",
            )
            if (isNewer(build)) {
                eventLog.add("обновление: есть ${build.versionName} (у нас ${BuildConfig.VERSION_NAME})")
            } else if (build.versionCode > BuildConfig.VERSION_CODE) {
                eventLog.add(
                    "обновление пропущено: в ветке лежит сборка из «${build.branch}», " +
                        "а ждём из «$line»"
                )
            }
            return build
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Молча: без сети проверка падает каждый день, журнал бы утонул.
            _state.value = _state.value.copy(checking = false, error = readable(e))
            return null
        } finally {
            checking.set(false)
        }
    }

    private fun parseInfo(text: String): Build? {
        val map = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val i = line.indexOf('=')
            if (i > 0) map[line.take(i).trim()] = line.substring(i + 1).trim()
        }
        val code = map["versionCode"]?.toIntOrNull() ?: return null
        // Общая с Слушалкой ветка пишет «pravka=», своя - «versionName=».
        val name = map["pravka"]?.takeIf { it.isNotBlank() }
            ?: map["versionName"]?.takeIf { it.isNotBlank() }
            ?: "2.0.$code"
        return Build(
            versionName = name,
            versionCode = code,
            commit = map["commit"].orEmpty().take(7),
            branch = map["branch"].orEmpty(),
            builtAt = map["builtAt"].orEmpty(),
            // Имя файла приезжает из build-info (как в Слушалке): переименование
            // сборки не потребует новой версии приложения. Но «apk=» на общей
            // ветке принадлежит Слушалке - её мы себе не ставим.
            apkName = map["apk"]?.takeIf { it.isNotBlank() && it.contains("pravka") } ?: FALLBACK_APK,
        )
    }

    private suspend fun apkUrl(build: Build): String {
        val info = settings.updUrlFlow.first().trim().ifBlank { DEFAULT_INFO_URL }
        return info.substringBeforeLast('/') + "/" + build.apkName
    }

    // ---- скачивание ----

    /**
     * Тянет APK в кэш и проверяет, что скачалось именно то. `apk-builds` -
     * одна форс-пушимая точка, и пока мы качали, туда мог приехать следующий
     * билд (или вообще чужой). Файл не про нас - удаляется.
     */
    suspend fun download(build: Build): File? {
        if (!downloading.compareAndSet(false, true)) return null
        try {
            val url = apkUrl(build)
            return withContext(Dispatchers.IO) {
                val target = File(dir(), "pravka-${build.versionCode}.apk")
                if (verify(target, build)) return@withContext target
                dir().mkdirs()
                val part = File(dir(), "pravka-${build.versionCode}.apk.part")
                part.delete()
                _state.value = _state.value.copy(progress = 0, error = "")
                // Тот же ?t=, что у build-info: иначе Fastly пять минут отдаёт
                // прошлый APK к свежему build-info, и проверка кода его отбросит.
                val request = Request.Builder()
                    .url(if (url.contains('?')) "$url&t=${System.currentTimeMillis()}" else "$url?t=${System.currentTimeMillis()}")
                    .header("Cache-Control", "no-cache")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    val body = response.body ?: throw java.io.IOException("пустой ответ")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var shown = -1
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != shown) {
                                        shown = pct
                                        _state.value = _state.value.copy(progress = pct)
                                    }
                                }
                            }
                        }
                    }
                }
                target.delete()
                if (!part.renameTo(target)) throw java.io.IOException("не переименовался")
                var file = target
                var got = build
                val code = archiveCode(file)
                if (code != build.versionCode) {
                    // Ветку перезаписали, пока качали: build-info обещал одну
                    // сборку, а приехала следующая (владелец: «говорит, что
                    // скачался не тот APK» - ровно через пять минут после
                    // пуша в ту же линию). Если свежий build-info подтверждает
                    // приехавшую и она из нашей линии - это и есть обновление,
                    // качать те же мегабайты второй раз незачем.
                    val fresh = if (code != null && code > build.versionCode) check(force = true) else null
                    if (fresh != null && fresh.versionCode == code && sameLine(fresh)) {
                        val renamed = File(dir(), "pravka-$code.apk")
                        renamed.delete()
                        if (!file.renameTo(renamed)) throw java.io.IOException("не переименовался")
                        file = renamed
                        got = fresh
                        eventLog.add("обновление: пока качали, в ветку приехала ${fresh.versionName} - берём её")
                    } else {
                        file.delete()
                        throw java.io.IOException(
                            if (code == null) "скачался не тот APK"
                            else "в ветке уже сборка $code, а ждали ${build.versionCode} - нажми «Проверить» ещё раз"
                        )
                    }
                }
                // В кэше держим только свежий: APK весит десятки мегабайт.
                dir().listFiles()?.forEach { if (it != file) it.delete() }
                eventLog.add("обновление: скачано ${got.versionName} (${file.length() / 1_048_576} МБ)")
                _state.value = _state.value.copy(ready = file, progress = -1)
                file
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            eventLog.add("обновление не скачалось: ${readable(e)}")
            _state.value = _state.value.copy(progress = -1, error = readable(e))
            return null
        } finally {
            downloading.set(false)
        }
    }

    /** Файл на диске годится, только если это Правка с ожидаемым кодом. */
    private fun verify(file: File, build: Build): Boolean = archiveCode(file) == build.versionCode

    /** versionCode Правки внутри APK; null - файла нет, он битый или это не наш пакет. */
    private fun archiveCode(file: File): Int? {
        if (!file.exists() || file.length() < 1_000_000L) return null
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return null
        if (info.packageName != BuildConfig.APPLICATION_ID) return null
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        return code
    }

    private fun readyFile(build: Build): File? {
        if (!isNewer(build)) return null
        val f = File(dir(), "pravka-${build.versionCode}.apk")
        return if (verify(f, build)) f else null
    }

    // Разбор манифеста в APK - чтение с диска, а тик живёт на главном потоке
    // службы доступности (там ничего тяжёлого быть не должно, см. README).
    private suspend fun resolveReady(build: Build): File? =
        withContext(Dispatchers.IO) { readyFile(build) }

    /**
     * То же самое, но без корутины: трамплин за уведомлением обязан отдать
     * файл прямо сейчас (после смерти процесса состояние пустое, а установщик
     * из фона уже не запустишь). Это своя активити, не служба.
     */
    fun readyNow(): File? {
        val ready = _state.value.ready
        if (ready != null && ready.exists()) return ready
        val build = _state.value.latest ?: return null
        return readyFile(build)?.also { _state.value = _state.value.copy(ready = it) }
    }

    // ---- установка ----

    /** Разрешение «ставить неизвестные приложения» - без него установщик молчит. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun allowInstallIntent(): Intent = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        android.net.Uri.parse("package:${BuildConfig.APPLICATION_ID}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- суточный тик ----

    /**
     * Живёт на пятиминутном тике службы: сам решает, пора ли смотреть (раз в
     * сутки), сам тянет APK и сам показывает уведомление - по одному на сборку.
     * По мобильной сети без разрешения не качает: сборка весит десятки мегабайт.
     */
    suspend fun tick() {
        if (!settings.updAutoFlow.first()) return
        line = settings.updBranchFlow.first().trim().ifBlank { BuildConfig.BUILD_BRANCH }
        val now = System.currentTimeMillis()
        if (now - prefs().getLong(KEY_LAST_CHECK, 0L) >= CHECK_PERIOD_MS) check(force = true)
        val build = _state.value.latest ?: return
        if (!isNewer(build)) {
            // Обновились - APK в кэше больше не нужен, это десятки мегабайт.
            withContext(Dispatchers.IO) { dir().listFiles()?.forEach { it.delete() } }
            return
        }
        var ready = resolveReady(build)
        if (ready == null && mayDownloadNow() &&
            now - prefs().getLong(KEY_TRY_AT, 0L) >= RETRY_MS
        ) {
            prefs().edit().putLong(KEY_TRY_AT, now).apply()
            ready = download(build)
        }
        // Качалка могла взять следующую сборку вместо обещанной - уведомление
        // должно называть ту, что лежит в кэше, а не ту, с которой начинали.
        val got = _state.value.latest ?: build
        if (ready != null) {
            tell("${got.versionCode}:ready") {
                notify(
                    context.getString(R.string.upd_notif_ready_title, got.versionName),
                    context.getString(R.string.upd_notif_ready_text),
                    ru.zf.pravka.trigger.UpdateActivity.W_INSTALL,
                )
            }
        } else {
            // Сеть платная (или качалка не смогла) - показываем находку и
            // ждём тапа: он и качает, и ставит.
            tell("${build.versionCode}:found") {
                notify(
                    context.getString(R.string.upd_notif_found_title, build.versionName),
                    context.getString(R.string.upd_notif_found_text),
                    ru.zf.pravka.trigger.UpdateActivity.W_DOWNLOAD,
                )
            }
        }
    }

    private suspend fun mayDownloadNow(): Boolean {
        if (settings.updMobileFlow.first()) return true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return !cm.isActiveNetworkMetered
    }

    private fun told(): String = prefs().getString(KEY_TOLD, "").orEmpty()

    private inline fun tell(mark: String, block: () -> Unit) {
        if (told() == mark) return
        prefs().edit().putString(KEY_TOLD, mark).apply()
        block()
    }

    private fun readable(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Нет сети"
        is java.net.SocketTimeoutException -> "Сервер не ответил"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun notify(title: String, text: String, what: String) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        context.getString(R.string.upd_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            val tap = PendingIntent.getActivity(
                context, 7,
                Intent(context, ru.zf.pravka.trigger.UpdateActivity::class.java)
                    .putExtra(ru.zf.pravka.trigger.UpdateActivity.EXTRA_WHAT, what)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                NOTIF_ID,
                Notification.Builder(context, CHANNEL)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    fun dismissNotification() {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }
    }
}
