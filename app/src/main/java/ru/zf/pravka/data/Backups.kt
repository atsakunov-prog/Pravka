package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Копии на диск раз в час — для всего, что нельзя восстановить руками.
 *
 * The owner lost a day of timesheet to a single file going empty, and a
 * timesheet built by voice over months has no source to rebuild it from. So
 * every irreplaceable store gets a copy per clock hour, in the app's own
 * private storage (no permissions, no network, survives an APK update because
 * the signature never changes).
 *
 * The file NAME is the throttle: a snapshot is `zasechka-2026-08-22-20.json`,
 * so a service restart, five ticks an hour or a phone that was off simply
 * cannot produce more than one copy per store per hour. Retention keeps every
 * hour of the last two days and the last hour of every day for a month -
 * enough to walk back to any point where the data was still there.
 */
internal object Backups {

    // Незаменимое: лента с категориями, словарь Правки, выученные правила,
    // ждущие подтверждения находки, следилки за правками, телефонный слой.
    // Логи (history.jsonl, transcriptions.jsonl) сюда НЕ идут: они большие и
    // сами держат прошлую копию рядом.
    private val STORES = listOf(
        "zasechka.json",
        "dictionary.json",
        "pravka-rules.json",
        "pravka-learn-pending.json",
        "pravka-edit-watch.json",
        "phone.json",
    )

    private const val DIR = "backups"
    private const val HOURLY_KEEP_MS = 48 * 3_600_000L
    private const val DAILY_KEEP_MS = 30 * 86_400_000L

    private val NAME_RE = Regex("""^(.+)-(\d{4}-\d{2}-\d{2})-(\d{2})\.([A-Za-z0-9]+)$""")

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** Копии одного стора, свежие сверху. */
    fun snapshotsOf(context: Context, storeName: String): List<File> {
        val base = storeName.substringBeforeLast('.') + "-"
        return (dir(context).listFiles() ?: return emptyList())
            .filter { it.name.startsWith(base) && NAME_RE.matches(it.name) }
            .sortedByDescending { it.name }
    }

    @Volatile private var loggedDay = ""

    /**
     * Дёргается из пятиминутного тика службы. Сама работа уходит на writer-поток
     * (тот же, что пишет сторы) — копия никогда не встаёт в очередь перед
     * настоящей записью и не может застать файл недописанным: запись атомарна.
     */
    fun tick(context: Context, log: ((String) -> Unit)? = null) {
        DiskWriter.post { runCatching { copyAll(context, log) } }
    }

    private fun copyAll(context: Context, log: ((String) -> Unit)?) {
        val dir = dir(context)
        val now = Date()
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH", Locale.US).format(now)
        val day = stamp.substringBeforeLast('-')
        var made = 0
        var bytes = 0L
        for (name in STORES) {
            val src = File(context.filesDir, name)
            if (!src.exists() || src.length() == 0L) continue
            val dst = File(dir, name.substringBeforeLast('.') + "-" + stamp + "." + name.substringAfterLast('.', "json"))
            if (dst.exists()) continue  // этот час уже снят
            dir.mkdirs()
            runCatching { src.copyTo(dst, overwrite = true) }
                .onSuccess { made++; bytes += dst.length() }
        }
        if (made == 0) return
        prune(dir)
        // Раз в день в журнал, чтобы было видно, что копии живые, но не 24 строки.
        if (loggedDay != day) {
            loggedDay = day
            val total = (dir.listFiles()?.sumOf { it.length() } ?: 0L) / 1024
            log?.invoke("копии: снято $made файлов (${bytes / 1024} КБ), всего копий $total КБ")
        }
    }

    /** Каждый час за двое суток + последний час каждого дня за месяц. */
    private fun prune(dir: File) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles() ?: return
        val keptPerDay = HashMap<String, File>()
        for (f in files) {
            val m = NAME_RE.matchEntire(f.name) ?: continue
            val age = now - f.lastModified()
            if (age > DAILY_KEEP_MS) {
                f.delete()
                continue
            }
            if (age <= HOURLY_KEEP_MS) continue
            val key = m.groupValues[1] + "|" + m.groupValues[2]
            val previous = keptPerDay[key]
            if (previous == null) {
                keptPerDay[key] = f
                continue
            }
            // Из старого дня остаётся поздний час — состояние на конец дня.
            val newer = if (previous.name < f.name) f else previous
            val older = if (previous.name < f.name) previous else f
            keptPerDay[key] = newer
            older.delete()
        }
    }
}
