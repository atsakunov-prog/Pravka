package ru.zf.pravka.data

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Где живёт база приложения — одна папка на установку (24.09.2026).
 *
 * Владелец ставит Правку жене и хочет, чтобы у каждой установки была своя
 * база, а базу можно было скопировать руками: «файлы на телефоне в рабочей
 * папке… открывается пустое — считывает эти файлы». Приватная память
 * (`filesDir`) снаружи не видна — ни проводнику, ни компьютеру по кабелю, и
 * после переустановки её нет. Поэтому база переезжает в `Documents/Pravka`:
 * те же файлы и та же атомарная запись, но папку видно, её можно скопировать
 * целиком и положить на другой телефон, а переустановка её не стирает.
 *
 * Правила места:
 * - место решается ОДИН раз на процесс, в `PravkaApp.onCreate` до первого
 *   стора; все сторы спрашивают [dir], а не `filesDir` (за этим следит
 *   `DataRootGuardTest`). Сменить место — только перезапуском процесса:
 *   стор, открытый на старом месте, писал бы туда и дальше;
 * - переезд — только кнопкой владельца (Настройки → База данных), в два шага:
 *   копия на ходу, закрепление на следующем старте ([DbMove.commit]);
 * - не база и остаются в приватной памяти: модели Whisper (скачиваются
 *   заново), записи на повтор (пишутся в реальном времени во время тейка),
 *   черновик диктовки на лету (пятьдесят записей в минуту), служебные флаги
 *   `pravka_internal` (когда что последний раз напоминали);
 * - база переехала, а доступа к файлам нет (сняли разрешение) — место НЕ
 *   откатывается в приватную память: там лежала бы вчерашняя база, и новые
 *   записи разошлись бы с папкой. Приложение говорит об этом словами.
 */
internal object DataRoot {

    enum class Where {
        /** Приватная память приложения: снаружи не видно, скопировать нельзя. */
        PRIVATE,
        /** Папка `Documents/Pravka`: база видна и копируется. */
        FOLDER,
        /** База в папке, но доступа к файлам нет — читать и писать нечем. */
        FOLDER_NO_ACCESS,
    }

    /** Имя папки базы в «Документах». Латиницей: её увидят компьютер и облако. */
    const val FOLDER_NAME = "Pravka"

    /** В приватной памяти: «база переехала туда-то» (путь папки). */
    private const val MOVED = DbMove.MOVED
    /** Переезд подготовлен и ждёт закрепления на следующем старте. */
    private const val PENDING = DbMove.PENDING
    private const val ASIDE_PREFIX = DbMove.ASIDE_PREFIX

    /** Остаётся в приватной памяти всегда — список и причины в [DbMove.notDatabase]. */
    internal fun notDatabase(topName: String): Boolean = DbMove.notDatabase(topName)

    private fun privateOnly(topName: String): Boolean = DbMove.notDatabase(topName)

    private fun skipForCopy(rel: String): Boolean = privateOnly(rel.substringBefore('/'))

    @Volatile private var root: File? = null
    private val whereState = MutableStateFlow(Where.PRIVATE)
    private val accessState = MutableStateFlow(false)
    /** Что случилось с переездом на этом старте — строка для экрана и журнала, пусто — ничего. */
    @Volatile var startNote: String = ""
        private set

    val where: StateFlow<Where> get() = whereState
    /** Есть ли у приложения доступ к папкам телефона. Освежается в `MainActivity.onResume`. */
    val access: StateFlow<Boolean> get() = accessState

    /**
     * Папка базы этого процесса. Первое обращение решает место (обычно это
     * `PravkaApp.onCreate`); без переезда это две проверки файлов в приватной
     * памяти и один вопрос системе про доступ — дёшево, но один раз.
     */
    fun dir(context: Context): File = root ?: init(context)

    /** Решает, где база, и доделывает подготовленный переезд. Вызывать до первого стора. */
    @Synchronized
    fun init(context: Context): File {
        root?.let { return it }
        val app = context.applicationContext ?: context
        val priv = app.filesDir
        val access = runCatching { hasAccess(app) }.getOrDefault(false)
        accessState.value = access
        File(priv, PENDING).takeIf { it.exists() }?.let { finishPending(app, priv, it, access) }
        val moved = readMoved(priv)
        val dir = if (moved == null) priv else moved
        whereState.value = when {
            moved == null -> Where.PRIVATE
            access -> Where.FOLDER
            else -> Where.FOLDER_NO_ACCESS
        }
        startedWithoutAccess = whereState.value == Where.FOLDER_NO_ACCESS
        // Снимки тарелок в food/ — не для Галереи: `.nomedia` прячет папку от
        // сканера медиа. И у базы, открытой с другого телефона, тоже.
        if (whereState.value == Where.FOLDER) {
            runCatching { File(dir, ".nomedia").takeIf { !it.exists() }?.createNewFile() }
        }
        root = dir
        return dir
    }

    private fun finishPending(context: Context, priv: File, file: File, access: Boolean) {
        val p = DbMove.readPending(file)
        if (p == null || !access) {
            // Без доступа закреплять нечем: база остаётся на прежнем месте,
            // скопированное в папку ничего не ломает (паспорта у папки нет).
            startNote = if (p == null) "переезд базы не прочитался — база осталась в памяти приложения"
            else "переезд базы отменён: нет доступа к файлам — база осталась в памяти приложения"
            file.delete()
            return
        }
        val outcome = runCatching {
            DbMove.commit(
                priv = priv,
                p = p,
                skip = ::skipForCopy,
                keepPrivate = ::privateOnly,
                device = deviceName(),
                now = System.currentTimeMillis(),
                writeMoved = { folder -> writeMoved(priv, folder) },
            )
        }.getOrElse { e ->
            startNote = "переезд базы оборвался: ${e.javaClass.simpleName}: ${e.message} — база осталась на прежнем месте"
            // PENDING не удаляется: следующий старт попробует доделать.
            return
        }
        startNote = when (outcome) {
            DbMove.Outcome.MOVED -> if (p.mode == DbMove.Pending.ADOPT) "база открыта из папки ${p.folder}; прежние файлы — в ${p.aside}"
            else "база переехала в ${p.folder}; прежние файлы — в ${p.aside}"
            DbMove.Outcome.ABORTED_FOLDER_TAKEN -> "переезд отменён: в папке ${p.folder} появилась другая база — её не трогал"
            DbMove.Outcome.ABORTED_NO_DB -> "открыть базу не вышло: в папке ${p.folder} нет паспорта базы"
            DbMove.Outcome.ABORTED_BAD_PENDING -> "переезд базы не прочитался — база осталась в памяти приложения"
        }
        file.delete()
    }

    /**
     * Куда переехала база. Отметка есть, но не читается — база всё равно в
     * папке (заводской путь): вернуться в приватную память значило бы открыть
     * пустое место (всё отложено при переезде) и писать новое мимо базы.
     */
    private fun readMoved(priv: File): File? {
        val f = File(priv, MOVED)
        if (!f.exists()) return null
        val path = runCatching { JSONObject(f.readText()).optString("folder") }.getOrNull()
        return path?.takeIf { it.isNotBlank() }?.let(::File) ?: runCatching { folder() }.getOrNull()
    }

    private fun writeMoved(priv: File, folder: File) {
        val o = JSONObject()
            .put("folder", folder.absolutePath)
            .put("at", System.currentTimeMillis())
        StoreFiles.writeAtomic(File(priv, MOVED), o.toString())
    }

    // --- папка и доступ ------------------------------------------------------

    /** `Documents/Pravka` на внутренней памяти телефона. */
    @Suppress("DEPRECATION")
    fun folder(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        FOLDER_NAME,
    )

    /**
     * Доступ к файлам телефона. Android 11+ — «Доступ ко всем файлам»: базу
     * после переустановки или с другого телефона приложение иначе не прочтёт
     * (своими система считает только файлы, созданные этой же установкой).
     */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** С каким местом процесс начал: без доступа или с ним. */
    @Volatile private var startedWithoutAccess = false

    /**
     * Освежить доступ. Возвращает true, если процесс начинал с недоступной
     * базой, а доступ вернулся: тогда процесс надо перезапустить. Сторы при
     * старте прочли папку пустой (чужие файлы без доступа не читаются), а
     * писать поверх теперь им уже можно — первая же запись положила бы эту
     * пустоту поверх настоящей базы.
     */
    fun refreshAccess(context: Context): Boolean {
        val now = runCatching { hasAccess(context) }.getOrDefault(false)
        accessState.value = now
        if (whereState.value != Where.PRIVATE) {
            whereState.value = if (now) Where.FOLDER else Where.FOLDER_NO_ACCESS
        }
        return startedWithoutAccess && now
    }

    /** Системный экран, где выдаётся доступ к файлам. */
    fun accessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Запасной экран, если прошивка не знает экрана своего приложения. */
    fun accessIntentFallback(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        } else {
            Intent(android.provider.Settings.ACTION_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // --- что в папке ---------------------------------------------------------

    /** Сводка папки для экрана: есть ли там база, от кого и сколько в ней. */
    class FolderInfo(
        val path: String,
        val exists: Boolean,
        val passport: DbMove.Passport?,
        val files: Int,
        val bytes: Long,
    )

    /** Читает папку базы (или приватную память) — с диска, звать не на главном потоке. */
    fun inspect(context: Context): FolderInfo {
        val current = dir(context)
        val target = if (whereState.value == Where.PRIVATE) folder() else current
        val exists = runCatching { target.isDirectory }.getOrDefault(false)
        val passport = if (exists) DbMove.readPassport(target) else null
        val (files, bytes) = if (exists) DbMove.measure(target) else (0 to 0L)
        return FolderInfo(target.absolutePath, exists, passport, files, bytes)
    }

    /** Сколько весит база там, где она сейчас. */
    fun measureCurrent(context: Context): Pair<Int, Long> =
        DbMove.measure(dir(context), ::skipForCopy)

    // --- переезд -------------------------------------------------------------

    /**
     * Шаг первый: копия приватной базы в папку на ходу. Сторы продолжают писать
     * в прежнее место — то, что они успеют записать до перезапуска, докопирует
     * закрепление на старте. Звать на фоне; [onFile] — ход для экрана.
     * Возвращает текст ошибки или null.
     */
    fun prepareCopy(context: Context, onFile: (Int, Int) -> Unit): String? {
        if (whereState.value != Where.PRIVATE) return "база уже в папке"
        if (!hasAccess(context)) return "нет доступа к файлам"
        val priv = context.applicationContext.filesDir
        val target = folder()
        if (DbMove.readPassport(target) != null) return "в папке уже есть база — её можно открыть, а не затирать"
        return runCatching {
            if (!target.isDirectory && !target.mkdirs()) error("не создаётся папка ${target.absolutePath}")
            val paths = DbMove.mirror(priv, target, ::skipForCopy, onFile)
            DbMove.writePending(
                File(priv, PENDING),
                DbMove.Pending(
                    mode = DbMove.Pending.COPY,
                    folder = target.absolutePath,
                    token = UUID.randomUUID().toString(),
                    aside = asideName(),
                    paths = paths,
                ),
            )
            null
        }.getOrElse { e -> "${e.javaClass.simpleName}: ${e.message}" }
    }

    /**
     * Открыть базу, которая уже лежит в папке (скопирована с другого телефона
     * или осталась после переустановки). Приватные файлы не сливаются с ней —
     * они откладываются в `before-move-…` и остаются на телефоне.
     */
    fun prepareAdopt(context: Context): String? {
        if (whereState.value != Where.PRIVATE) return "база уже в папке"
        if (!hasAccess(context)) return "нет доступа к файлам"
        val target = folder()
        if (DbMove.readPassport(target) == null) return "в папке нет базы (нет файла ${DbMove.PASSPORT})"
        return runCatching {
            DbMove.writePending(
                File(context.applicationContext.filesDir, PENDING),
                DbMove.Pending(
                    mode = DbMove.Pending.ADOPT,
                    folder = target.absolutePath,
                    token = "",
                    aside = asideName(),
                ),
            )
            null
        }.getOrElse { e -> "${e.javaClass.simpleName}: ${e.message}" }
    }

    /**
     * Перезапуск процесса: место базы меняется только так. Сначала дописывается
     * очередь записи — последнее сказанное не должно остаться в памяти убитого
     * процесса. Служба доступности поднимется системой сама.
     */
    fun restart(activity: Activity) {
        // Процесс без доступа: в очереди — записи состояния, прочитанного
        // пустым; дописывать их нельзя (см. refreshAccess).
        if (!startedWithoutAccess) DiskWriter.drain(3_000L)
        val intent = Intent.makeRestartActivityTask(ComponentName(activity, ru.zf.pravka.MainActivity::class.java))
        activity.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    /**
     * Уведомление «база недоступна» — на старте процесса без доступа к папке.
     * Служба работает и без открытого приложения, а пустая лента без слов
     * читается как «всё стёрлось» (правило 6). Тап — системный экран доступа.
     */
    fun notifyNoAccess(context: Context) {
        runCatching {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            val channelId = "pravka-db"
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "База данных недоступна",
                        android.app.NotificationManager.IMPORTANCE_HIGH,
                    )
                )
            }
            val open = android.app.PendingIntent.getActivity(
                context, 47, accessIntent(context),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = android.app.Notification.Builder(context, channelId)
                .setContentTitle("Правка: база недоступна")
                .setContentText("Нет доступа к файлам — лента, еда и деньги не читаются. Файлы в папке целы.")
                .setStyle(
                    android.app.Notification.BigTextStyle().bigText(
                        "База лежит в Documents/$FOLDER_NAME, а разрешение «Доступ ко всем файлам» " +
                            "снято. Файлы в папке целы — верни доступ, и всё вернётся.",
                    )
                )
                .setSmallIcon(ru.zf.pravka.R.drawable.ic_tile)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(47, notif)
        }
    }

    private fun asideName(): String =
        ASIDE_PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")

    // --- DataStore на месте базы ---------------------------------------------

    private val stores = HashMap<String, DataStore<Preferences>>()

    /**
     * Секреты установки — вход в Google, имя телефона в журнале обмена. Живут
     * в приватной памяти, НЕ в папке базы: папку копируют на другой телефон,
     * она уезжает в суточную копию — с ней уехал бы вход в семейный Drive, а
     * два телефона под одним именем писали бы в один журнал.
     */
    fun secrets(context: Context): File =
        File((context.applicationContext ?: context).filesDir, DbMove.SECRETS).apply { mkdirs() }

    /**
     * DataStore настроек в папке базы. Заводской `preferencesDataStore` пишет
     * в `filesDir/datastore` намертво — поэтому свой, по тому же имени файла.
     * Один экземпляр на файл на процесс: второй на тот же файл DataStore
     * запрещает (и правильно — два писателя разошлись бы).
     */
    @Synchronized
    fun preferences(context: Context, name: String): DataStore<Preferences> =
        stores.getOrPut(name) {
            val base = dir(context)
            PreferenceDataStoreFactory.create(
                produceFile = { File(base, "datastore/$name.preferences_pb") },
            )
        }
}
