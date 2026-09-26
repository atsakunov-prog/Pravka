package ru.zf.pravka.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.pravka.provider.FamilyCloud

/**
 * Ночная копия базы — ещё и в облако семьи (25.09.2026; владелец:
 * «еженочный бэкап всех файлов моих, марианниных на этот гугл драйв в
 * отдельную папку»; с 26.09 облако — домашний сервер или Drive,
 * `provider/FamilyCloud.kt`).
 *
 * Суточная копия (`DailyBackup`) лежит на самом телефоне: спасает от бага и от
 * удалённой папки базы, но не от потерянного или разбитого телефона. Здесь
 * тот же архив, как только он снят, уезжает в `Правка/Копии базы/` облака:
 * имя несёт пользователя (`pravka-marianna-2026-09-25.zip`), так что копии
 * Саши и Марианны лежат рядом и не путаются, и каждый телефон чистит только
 * свои.
 *
 * Правила:
 * - едет последний снятый архив, один раз; уехал — до следующей ночи ничего;
 *   сменилось облако — последний архив едет и в новое (`to` в состоянии);
 * - по Wi-Fi: архив — десятки мегабайт. Нет Wi-Fi трое суток — едет и по
 *   мобильной сети, чтобы копия не застревала навсегда; кнопка «Выгрузить
 *   сейчас» не ждёт Wi-Fi;
 * - в облаке хранятся неделя каждый день и по копии на месяц за год (в Drive
 *   место общее с книгами Слушалки, 15 ГБ на всё);
 * - трогает только свои копии: в Drive доступ `drive.file`, на сервере —
 *   файлы со своим именем пользователя.
 *
 * Файл состояния — `drive-backup.json`, как с первого дня: переименование
 * потеряло бы, что уже уехало.
 */
internal class CloudBackup(
    private val context: Context,
    private val cloud: () -> FamilyCloud?,
    /** Когда подключили это облако: трое суток без Wi-Fi считаются и от входа. */
    private val connectedAt: () -> Long,
    private val user: () -> String,
    private val log: (String) -> Unit,
) {

    companion object {
        val PATH = listOf("Правка", "Копии базы")
        private const val STATE = "drive-backup.json"
        private const val MIME = "application/zip"
        const val KEEP_DAYS = 7
        const val KEEP_MONTHS = 12
    }

    /** Решение — чистая функция под тестом. */
    object Policy {
        private const val DAY = 86_400_000L

        enum class Do { NOTHING, WAIT_WIFI, UPLOAD }

        /**
         * [local] — имя последнего снятого архива (пусто — копий нет),
         * [sent] — последний уехавший, [sentAt] — когда. Мобильная сеть — ждём
         * Wi-Fi, но не больше трёх суток с прошлой выгрузки.
         */
        fun decide(local: String, sent: String, sentAt: Long, now: Long, metered: Boolean, force: Boolean): Do = when {
            local.isEmpty() || local == sent -> Do.NOTHING
            force || !metered -> Do.UPLOAD
            now - sentAt >= 3 * DAY -> Do.UPLOAD
            else -> Do.WAIT_WIFI
        }

        /** Свои копии в папке облака, которые пора убрать: неделя каждый день, месяц — по одной. */
        fun prune(names: List<String>, user: String, today: LocalDate): List<String> {
            val mine = "pravka-" + DailyBackup.Policy.safe(user) + "-"
            return DailyBackup.Policy.prune(names.filter { it.startsWith(mine) }, today, KEEP_DAYS, KEEP_MONTHS)
        }
    }

    data class Status(
        val sentAt: Long = 0L,
        val sent: String = "",
        val bytes: Long = 0L,
        val error: String = "",
        val errorAt: Long = 0L,
        /** Архив есть, ждёт Wi-Fi. */
        val waiting: Boolean = false,
        val running: Boolean = false,
        /** Сколько своих копий в облаке и сколько они весят — после последней выгрузки. */
        val copies: Int = 0,
        val copiesBytes: Long = 0L,
        /** Облако, куда уехала последняя ([FamilyCloud.id]); пусто — Drive, как было до 26.09. */
        val to: String = "",
        val toTitle: String = "",
    )

    private val _status = MutableStateFlow(read())
    val status: StateFlow<Status> = _status

    private val mutex = Mutex()

    private fun file(): File = File(DataRoot.dir(context), STATE)

    private fun read(): Status = runCatching {
        StoreFiles.readOrQuarantine(file()) { text ->
            val o = JSONObject(text)
            Status(
                sentAt = o.optLong("sentAt"), sent = o.optString("sent"), bytes = o.optLong("bytes"),
                error = o.optString("error"), errorAt = o.optLong("errorAt"),
                copies = o.optInt("copies"), copiesBytes = o.optLong("copiesBytes"),
                to = o.optString("to"), toTitle = o.optString("toTitle"),
            )
        }
    }.getOrNull() ?: Status()

    private fun save(s: Status) {
        val o = JSONObject()
            .put("sentAt", s.sentAt).put("sent", s.sent).put("bytes", s.bytes)
            .put("error", s.error).put("errorAt", s.errorAt)
            .put("copies", s.copies).put("copiesBytes", s.copiesBytes)
            .put("to", s.to).put("toTitle", s.toTitle)
        runCatching { StoreFiles.writeAtomic(file(), o.toString()) }
    }

    /**
     * Из тика службы и кнопкой ([force] — не ждать Wi-Fi). Без облака и при
     * недоступной базе молчит. Ошибка повторяется не чаще раза в час.
     */
    suspend fun tick(force: Boolean = false) {
        val c = cloud() ?: return
        if (DataRoot.where.value == DataRoot.Where.FOLDER_NO_ACCESS) return
        if (!mutex.tryLock()) return
        try {
            val prev = _status.value
            val now = System.currentTimeMillis()
            if (!force && prev.errorAt > 0 && now - prev.errorAt < 3_600_000L) return
            val local = withContext(Dispatchers.IO) { DailyBackup.state(context) }
            val archive = File(DailyBackup.folder(), local.lastFile)
            val name = if (local.lastFile.isNotEmpty() && archive.isFile) local.lastFile else ""
            // «Трое суток без Wi-Fi» считаются и от входа: первая копия после
            // подключения днём не едет сразу по мобильной сети.
            val since = maxOf(prev.sentAt, connectedAt())
            // Уехала в другое облако — для этого ещё не уехала.
            // Пустое `to` — состояние до 26.09, тогда облако было одно: Drive.
            val sentHere = if (prev.to == c.id || (prev.to.isEmpty() && c.id.startsWith("drive:"))) prev.sent else ""
            when (Policy.decide(name, sentHere, since, now, metered(), force)) {
                Policy.Do.NOTHING -> {
                    if (prev.waiting) _status.value = prev.copy(waiting = false)
                    return
                }
                Policy.Do.WAIT_WIFI -> {
                    if (!prev.waiting) _status.value = prev.copy(waiting = true)
                    return
                }
                Policy.Do.UPLOAD -> Unit
            }
            _status.value = prev.copy(running = true, waiting = false)
            val next = try {
                upload(c, archive, now)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val why = FamilyCloud.why(e, c.title)
                log("копия базы (${c.title}) не уехала: $why")
                prev.copy(error = why, errorAt = now)
            }
            save(next)
            _status.value = next.copy(running = false)
        } finally {
            if (_status.value.running) _status.value = _status.value.copy(running = false)
            mutex.unlock()
        }
    }

    private suspend fun upload(c: FamilyCloud, archive: File, now: Long): Status {
        val there = c.list(PATH)
        val item = c.upload(PATH, archive.name, archive, MIME)
        // Неделя каждый день и по копии на месяц — только свои, чужие копии не наши.
        val today = LocalDate.now()
        val names = (there.map { it.name } + item.name).distinct()
        val drop = Policy.prune(names, user(), today).toSet() - item.name
        var dropped = 0
        for (f in there) if (f.name in drop) {
            runCatching { c.delete(PATH, f.name) }.onSuccess { dropped++ }
        }
        val mine = "pravka-" + DailyBackup.Policy.safe(user()) + "-"
        val left = there.filter { it.name.startsWith(mine) && it.name !in drop && it.name != item.name } + item
        log(
            "копия базы (${c.title}): ${archive.name}, ${archive.length() / 1024} КБ" +
                (if (dropped > 0) ", старых убрано $dropped" else "") +
                ", своих копий там ${left.size}"
        )
        return Status(
            sentAt = now, sent = archive.name, bytes = archive.length(),
            copies = left.size, copiesBytes = left.sumOf { it.size },
            to = c.id, toTitle = c.title,
        )
    }

    /** Мобильная сеть (или нет сети вовсе) — архив ждёт Wi-Fi. */
    private fun metered(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(true)
}
