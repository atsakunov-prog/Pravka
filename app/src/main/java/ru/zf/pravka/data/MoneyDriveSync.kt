package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.pravka.BuildConfig
import ru.zf.pravka.core.MoneySync
import ru.zf.pravka.core.SyncFlat
import ru.zf.pravka.provider.GoogleAuth
import ru.zf.pravka.provider.GoogleDrive

/**
 * Обмен Деньгами через семейный Google Drive (25.09.2026). Логика слияния —
 * `core/MoneySync.kt`; здесь файлы, Drive и порядок шагов.
 *
 * Раскладка:
 *  - в папке базы `money-sync/` — журналы ВСЕХ телефонов кусками
 *    (`<устройство>.000001.jsonl`): свой пишется здесь, чужие — копии из
 *    Drive. Едут вместе с базой, в копии и при переезде;
 *  - в Drive `Правка/Деньги/` — те же куски и паспорта устройств
 *    (`<устройство>.device.json`: чей телефон). Телефон пишет только свои
 *    файлы — у одного файла никогда не бывает двух писателей;
 *  - имя устройства — в закрытой памяти (`DataRoot.secrets`), не в базе:
 *    базу копируют на другой телефон, и два телефона под одним именем писали
 *    бы в один журнал.
 *
 * Шаги одного обмена (под замком, по одному за раз):
 *  1. снимок базы → чем она отличается от сложенных журналов → свои события
 *     дописываются в свой кусок (writer-поток, ждём записи);
 *  2. свои изменившиеся куски — в Drive;
 *  3. чужие изменившиеся куски — из Drive, складываются в память;
 *  4. база приводится к сложенному (записи только добавляются и меняются);
 *  5. скачанные куски ложатся на диск ПОСЛЕ записи базы — той же очередью.
 *     Умер процесс между 4 и 5 — следующий обмен скачает их снова, и
 *     ничего не потеряется; наоборот (куски есть, база старая) было бы хуже:
 *     старые поля базы ушли бы как «свежие правки» и перекрыли чужие;
 *  6. сверка — у каждого телефона своя, по общим данным.
 */
internal class MoneyDriveSync(
    private val context: Context,
    private val store: MoneyStore,
    private val auth: GoogleAuth,
    private val drive: GoogleDrive,
    private val profile: () -> Profile?,
    private val reconcile: suspend () -> Unit,
    private val log: (String) -> Unit,
) {

    companion object {
        const val DIR = "money-sync"
        val PATH = listOf("Правка", "Деньги")
        private const val DEVICE_FILE = "money-sync-device.txt"
        private const val MIME = "application/json"
    }

    data class Status(
        /** Последний удачный обмен; 0 — не было. */
        val at: Long = 0L,
        val error: String = "",
        val running: Boolean = false,
        /** Своих событий ушло в последний раз. */
        val sent: Int = 0,
        /** Сколько записей и правил поменяли чужие журналы в последний раз: телефон → число. */
        val from: Map<String, Int> = emptyMap(),
        val added: Int = 0,
        val changed: Int = 0,
        /** Телефоны в общей папке: устройство → имя владельца. */
        val devices: Map<String, String> = emptyMap(),
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val mutex = Mutex()
    private var merged: MoneySync.Merged? = null
    /** md5 локальных копий по (имя, размер, время): куски не перечитываются каждый обмен. */
    private val md5s = HashMap<String, Triple<Long, Long, String>>()
    private var lastLoggedError = ""

    private val dir: File get() = File(DataRoot.dir(context), DIR)

    /** Имя этого телефона в журналах: `sasha-3f9a2c`. Заводится один раз. */
    val device: String by lazy {
        val f = File(DataRoot.secrets(context), DEVICE_FILE)
        runCatching { f.readText().trim() }.getOrNull()
            ?.takeIf { MoneySync.parseChunk(MoneySync.chunkName(it, 1)) != null }
            ?: MoneySync.deviceId(profile()?.id ?: "user", java.util.UUID.randomUUID().toString().replace("-", "").take(6))
                .also { StoreFiles.writeAtomic(f, it) }
    }

    /** Можно ли меняться: вход есть, база читается, Деньги включены в профиле. */
    fun ready(): Boolean =
        auth.account.value != null &&
            DataRoot.where.value != DataRoot.Where.FOLDER_NO_ACCESS &&
            profile()?.has(Profile.Mode.MONEY) == true

    /**
     * Один обмен. Второй, пока идёт первый, ничего не делает (null). [reason] —
     * для журнала: «тик», «правка», «кнопка».
     */
    suspend fun sync(reason: String): Status? {
        if (!ready()) return null
        if (!mutex.tryLock()) return null
        _status.value = _status.value.copy(running = true)
        try {
            val s = withContext(Dispatchers.IO) { exchange() }
            _status.value = s
            val got = s.from.values.sum()
            if (s.sent > 0 || got > 0 || s.added + s.changed > 0) {
                val names = s.from.filterValues { it > 0 }.entries.joinToString { (d, n) -> "${s.devices[d] ?: d} $n" }
                log(
                    "деньги·drive ($reason): отправлено событий ${s.sent}" +
                        (if (names.isNotEmpty()) ", получено: $names" else "") +
                        (if (s.added + s.changed > 0) " — записей добавлено ${s.added}, поправлено ${s.changed}" else "")
                )
            }
            lastLoggedError = ""
            return s
        } catch (e: Throwable) {
            // Что бы ни сорвалось, сложенное в памяти могло уйти дальше базы:
            // следующий обмен перечитает журналы с диска, где лежит только
            // то, что уже легло в базу.
            merged = null
            if (e is kotlinx.coroutines.CancellationException) throw e
            val why = when (e) {
                is GoogleAuth.AuthException, is GoogleDrive.DriveException -> e.message.orEmpty()
                is java.net.UnknownHostException -> "нет сети (${e.message})"
                is java.net.SocketTimeoutException -> "Google не ответил вовремя (${e.message})"
                else -> "${e.javaClass.simpleName}: ${e.message}"
            }
            _status.value = _status.value.copy(running = false, error = why)
            // Ошибка раз в пять минут одна и та же — в журнал один раз.
            if (why != lastLoggedError) {
                log("деньги·drive ($reason): не вышло — $why")
                lastLoggedError = why
            }
            return _status.value
        } finally {
            if (_status.value.running) _status.value = _status.value.copy(running = false)
            mutex.unlock()
        }
    }

    private suspend fun exchange(): Status {
        val me = device
        dir.mkdirs()
        // Через ту же очередь записи: скачанные куски прошлого обмена могли
        // ещё стоять в ней — читать надо после них.
        val m = merged ?: (DiskWriter.call(60_000) { load() } ?: throw java.io.IOException("журналы обмена не прочитались за минуту"))
            .also { merged = it }

        // 1. Своё.
        val snapshot: SyncFlat = store.exchange { s -> null to local(s).flat() }
        val mine = MoneySync.diff(snapshot, m, System.currentTimeMillis(), me)
        if (mine.isNotEmpty()) {
            DiskWriter.call(60_000) { append(me, mine); true }
                ?: throw java.io.IOException("журнал обмена не записался за минуту")
            m.fold(mine)
            if (m.cells.size == mine.size) log("деньги·drive: первый обмен — в журнал ушло ${mine.size} записей и правил")
        }

        // 2. В Drive — свои изменившиеся куски и паспорт.
        val folder = drive.folder(PATH)
        val remote = drive.list(folder)
        val byName = remote.associateBy { it.name }
        for (f in chunks().filter { MoneySync.parseChunk(it.name)?.device == me }) {
            val r = byName[f.name]
            if (r != null && r.md5 == md5(f)) continue
            val bytes = f.readBytes()
            if (r == null) drive.create(folder, f.name, bytes, MIME) else drive.update(r.id, bytes, MIME)
        }
        val passport = passport(me).toByteArray()
        val pName = MoneySync.deviceFileName(me)
        byName[pName].let { r ->
            if (r == null) drive.create(folder, pName, passport, MIME)
            else if (r.md5 != md5(passport)) drive.update(r.id, passport, MIME)
        }

        // 3. Чужое — только изменившееся. Складывается, когда скачано ВСЁ:
        // оборвалась сеть на втором куске — первый не остаётся в памяти
        // несложенным в базу (иначе следующий обмен отправил бы старые поля
        // базы как свежие правки и откатил чужое).
        val fresh = ArrayList<Pair<File, ByteArray>>()
        for (r in remote) {
            val dev = MoneySync.parseChunk(r.name)?.device ?: MoneySync.parseDeviceFile(r.name) ?: continue
            if (dev == me) continue
            val copy = File(dir, r.name)
            if (copy.isFile && md5(copy) == r.md5) continue
            fresh.add(copy to drive.download(r.id))
        }
        val from = HashMap<String, Int>()
        for ((f, bytes) in fresh) {
            val dev = MoneySync.parseChunk(f.name)?.device ?: continue
            val touched = m.fold(MoneySync.decodeAll(String(bytes, Charsets.UTF_8)))
            from[dev] = (from[dev] ?: 0) + touched.size
        }

        // 4. База — к сложенному.
        val applied = store.exchange { s ->
            val a = MoneySync.apply(local(s), snapshot, m)
            val next = if (!a.any) null else s.copy(
                entries = a.local.entries,
                rules = a.local.rules,
                balances = a.local.balances,
                zfAccounts = a.local.zf,
                notZfAccounts = a.local.notZf,
            )
            next to a
        }

        // 5. Скачанное — на диск после базы, той же очередью.
        for ((f, bytes) in fresh) DiskWriter.post { writeAtomic(f, bytes) }

        // 6. Сверка по общему.
        if (applied.any) runCatching { reconcile() }

        return Status(
            at = System.currentTimeMillis(),
            sent = mine.size,
            from = from,
            added = applied.added,
            changed = applied.changed,
            devices = devices(me, fresh),
        )
    }

    private fun local(s: MoneyStore.State) =
        MoneySync.Local(s.entries, s.rules, s.balances, s.zfAccounts, s.notZfAccounts)

    /** Все куски, что лежат в папке базы. */
    private fun chunks(): List<File> =
        dir.listFiles { f -> f.isFile && MoneySync.parseChunk(f.name) != null }?.sortedBy { it.name }.orEmpty()

    /** Сложить все журналы с диска — один раз на процесс. */
    private fun load(): MoneySync.Merged {
        val m = MoneySync.Merged()
        for (f in chunks()) m.fold(MoneySync.decodeAll(f.readText()))
        return m
    }

    /** Дописать свои события в последний кусок; перевалил за размер — новый. */
    private fun append(me: String, events: List<MoneySync.Event>) {
        val own = chunks().mapNotNull { f -> MoneySync.parseChunk(f.name)?.takeIf { it.device == me }?.let { it.n to f } }
        val (n, last) = own.maxByOrNull { it.first } ?: (1 to File(dir, MoneySync.chunkName(me, 1)))
        val file = if (last.isFile && last.length() >= MoneySync.CHUNK_BYTES) File(dir, MoneySync.chunkName(me, n + 1)) else last
        FileOutputStream(file, true).use { out ->
            // Оборванная прошлая строка не должна склеиться с первой новой.
            if (file.length() > 0 && !endsWithNewline(file)) out.write('\n'.code)
            for (e in events) {
                out.write(MoneySync.encode(e).toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
            }
            out.flush()
            runCatching { out.fd.sync() }
        }
    }

    private fun endsWithNewline(f: File): Boolean = runCatching {
        java.io.RandomAccessFile(f, "r").use { r -> r.seek(f.length() - 1); r.read() == '\n'.code }
    }.getOrDefault(true)

    private fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    private fun md5(f: File): String {
        val key = Pair(f.length(), f.lastModified())
        md5s[f.name]?.let { (len, mod, sum) -> if (len == key.first && mod == key.second) return sum }
        val sum = md5(f.readBytes())
        md5s[f.name] = Triple(key.first, key.second, sum)
        return sum
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Паспорт устройства: чей телефон — для строки «с телефона Марианны». */
    private fun passport(me: String): String {
        val p = profile()
        return JSONObject()
            .put("device", me)
            .put("profile", p?.id.orEmpty())
            .put("name", p?.name.orEmpty())
            .put("model", android.os.Build.MODEL.orEmpty())
            .put("version", BuildConfig.VERSION_NAME)
            .toString()
    }

    /** Имена телефонов из паспортов: скачанных сейчас и лежащих с прошлого раза. */
    private fun devices(me: String, fresh: List<Pair<File, ByteArray>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out[me] = profile()?.name ?: "этот телефон"
        val texts = HashMap<String, String>()
        dir.listFiles { f -> MoneySync.parseDeviceFile(f.name) != null }?.forEach { f -> texts[f.name] = runCatching { f.readText() }.getOrDefault("") }
        for ((f, bytes) in fresh) if (MoneySync.parseDeviceFile(f.name) != null) texts[f.name] = String(bytes, Charsets.UTF_8)
        for ((name, text) in texts) {
            val dev = MoneySync.parseDeviceFile(name) ?: continue
            if (dev == me) continue
            out[dev] = runCatching { JSONObject(text).optString("name") }.getOrDefault("").ifBlank { dev }
        }
        // Телефоны, чьи куски есть, а паспорта ещё нет.
        val names = chunks().map { it.name } + fresh.map { it.first.name }
        for (n in names) MoneySync.parseChunk(n)?.device?.let { d -> if (d !in out) out[d] = d }
        return out
    }
}
