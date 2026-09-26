package ru.zf.pravka.data

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Копия всей базы раз в сутки — ночью, одним zip в `Documents/Pravka-backups`
 * (25.09.2026; владелец: «нужен бэкап раз в день, логично ночью, когда я
 * сплю дома»).
 *
 * Почасовые копии (`Backups`) лежат ВНУТРИ базы и спасают от бага кода, но не
 * от потери самой папки: удалил её, сломался телефон — копий нет вместе с
 * базой. Суточная копия живёт рядом, в своей папке, и сама по себе является
 * базой: распаковал в `Documents/Pravka`, «Открыть эту базу» — и всё на месте
 * (паспорт `pravka-db.json` в архиве есть всегда).
 *
 * Правила:
 * - ночь — с двух до шести, лучше на зарядке; к пяти утра — без зарядки тоже.
 *   Пропустил ночь (телефон был выключен) — копия снимается при первой
 *   возможности, как только прошло тридцать часов;
 * - архив пишется на writer-потоке, через который идут записи всех сторов, —
 *   он не застанет ленту посреди записи и не разойдётся по времени между
 *   файлами;
 * - в архив не идут почасовые копии (они и есть копии) и `.prev`; всё прочее
 *   из базы — идёт, снимки еды тоже;
 * - хранятся две недели каждый день и по одной копии на месяц за год. Имя —
 *   `pravka-<пользователь>-<дата>.zip`: копии разных людей в одной папке не
 *   путаются и чистятся каждая своей чередой.
 */
internal object DailyBackup {

    const val FOLDER_NAME = "Pravka-backups"
    private const val STATE = "daily-backup.json"

    /** Политика — чистые функции под тестами (`DailyBackupTest`). */
    object Policy {
        private const val HOUR_MS = 3_600_000L

        /**
         * Пора ли снимать копию. [hour] — местный час, [lastAt] — время прошлой
         * удачной копии (0 — копий не было: снимаем сразу).
         */
        fun due(now: Long, lastAt: Long, hour: Int, charging: Boolean): Boolean {
            val since = now - lastAt
            if (since < 20 * HOUR_MS) return false
            if (hour in 2..5 && (charging || hour >= 5)) return true
            return since >= 30 * HOUR_MS
        }

        fun fileName(user: String, day: LocalDate): String = "pravka-${safe(user)}-$day.zip"

        /** Имя пользователя в имени файла: латиница, цифры и дефис, остальное — «_». */
        fun safe(user: String): String =
            user.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '_' }
                .joinToString("").trim('_').ifBlank { "user" }

        private val NAME = Regex("""^pravka-(.+)-(\d{4}-\d{2}-\d{2})\.zip$""")

        /**
         * Какие файлы из [names] удалить: у каждого пользователя остаются
         * последние [keepDays] дней и самая ранняя копия каждого месяца за
         * [keepMonths] месяцев. Чужие файлы (не наши имена) не трогаются.
         */
        fun prune(names: List<String>, today: LocalDate, keepDays: Int = 14, keepMonths: Int = 12): List<String> {
            val parsed = names.mapNotNull { n ->
                val m = NAME.matchEntire(n) ?: return@mapNotNull null
                val day = runCatching { LocalDate.parse(m.groupValues[2]) }.getOrNull() ?: return@mapNotNull null
                Triple(n, m.groupValues[1], day)
            }
            val firstDaily = today.minusDays((keepDays - 1).toLong())
            val firstMonth = today.withDayOfMonth(1).minusMonths((keepMonths - 1).toLong())
            val drop = ArrayList<String>()
            for ((_, list) in parsed.groupBy { it.second }) {
                val monthly = list.filter { it.third < firstDaily && it.third >= firstMonth }
                    .groupBy { it.third.withDayOfMonth(1) }
                    .mapValues { (_, v) -> v.minByOrNull { it.third }!!.first }
                    .values.toSet()
                for ((name, _, day) in list) {
                    val keep = day >= firstDaily || name in monthly
                    if (!keep) drop.add(name)
                }
            }
            return drop
        }
    }

    // --- состояние ------------------------------------------------------------

    class State(
        val lastAt: Long = 0L,
        val lastFile: String = "",
        val lastBytes: Long = 0L,
        val error: String = "",
        val errorAt: Long = 0L,
    )

    fun state(context: Context): State {
        val f = File(DataRoot.dir(context), STATE)
        return StoreFiles.readOrQuarantine(f) { text ->
            val o = JSONObject(text)
            State(
                lastAt = o.optLong("lastAt"),
                lastFile = o.optString("lastFile"),
                lastBytes = o.optLong("lastBytes"),
                error = o.optString("error"),
                errorAt = o.optLong("errorAt"),
            )
        } ?: State()
    }

    private fun save(context: Context, s: State) {
        val o = JSONObject()
            .put("lastAt", s.lastAt)
            .put("lastFile", s.lastFile)
            .put("lastBytes", s.lastBytes)
            .put("error", s.error)
            .put("errorAt", s.errorAt)
        runCatching { StoreFiles.writeAtomic(File(DataRoot.dir(context), STATE), o.toString()) }
    }

    @Suppress("DEPRECATION")
    fun folder(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        FOLDER_NAME,
    )

    // --- снятие ---------------------------------------------------------------

    /**
     * Из тика службы: решение и сама копия — на writer-потоке, главный поток
     * службы не ждёт ни чтения состояния, ни архива.
     */
    fun tick(context: Context, user: String, log: (String) -> Unit) {
        DiskWriter.post {
            val now = System.currentTimeMillis()
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val s = state(context)
            // Ошибка не повторяется каждые пять минут: следующая попытка — через час.
            if (s.errorAt > 0 && now - s.errorAt < 3_600_000L) return@post
            if (!Policy.due(now, s.lastAt, hour, charging(context))) return@post
            make(context, user, log)
        }
    }

    /** Кнопка «Сделать копию сейчас»: ждёт writer-поток. Звать не на главном. Возвращает ошибку или null. */
    fun runNow(context: Context, user: String, log: (String) -> Unit): String? {
        // Коробка, а не голый String?: удача тут — null, и её не спутать с «не дождались».
        val done = DiskWriter.call(10 * 60_000L) { Done(make(context, user, log)) }
            ?: return "копия не успела за десять минут"
        return done.error
    }

    private class Done(val error: String?)

    /** Снимает копию. Только на writer-потоке. Возвращает текст ошибки или null. */
    private fun make(context: Context, user: String, log: (String) -> Unit): String? {
        val now = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val dir = folder()
        val out = File(dir, Policy.fileName(user, day))
        val root = DataRoot.dir(context)
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) error("не создаётся папка ${dir.absolutePath}")
            val passport = DbMove.readPassport(root) ?: DbMove.Passport(
                token = "backup-$day",
                createdAt = now,
                device = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).joinToString(" "),
            )
            val files = zip(root, out, passport)
            val drop = Policy.prune(dir.list()?.toList().orEmpty(), day)
            drop.forEach { File(dir, it).delete() }
            save(context, State(lastAt = now, lastFile = out.name, lastBytes = out.length()))
            log("копия базы: ${out.name}, $files файлов, ${out.length() / 1024} КБ" +
                if (drop.isNotEmpty()) ", старых убрано ${drop.size}" else "")
            null
        }.getOrElse { e ->
            val text = "${e.javaClass.simpleName}: ${e.message}"
            val prev = state(context)
            save(context, State(prev.lastAt, prev.lastFile, prev.lastBytes, error = text, errorAt = now))
            log("копия базы не снялась: $text")
            text
        }
    }

    /**
     * Архив базы [root] в [out]: временный файл, `fsync`, переименование. Паспорт
     * кладётся всегда — у базы в памяти приложения его нет, а без него
     * распакованный архив не открыть кнопкой «Открыть эту базу».
     */
    internal fun zip(root: File, out: File, passport: DbMove.Passport): Int {
        val files = DbMove.listFiles(root, ::skip)
        val tmp = File(out.parentFile, out.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            ZipOutputStream(fos).use { zip ->
                for (rel in files) {
                    val f = File(root, rel)
                    val entry = ZipEntry(rel).apply { time = f.lastModified() }
                    zip.putNextEntry(entry)
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                if (DbMove.PASSPORT !in files) {
                    zip.putNextEntry(ZipEntry(DbMove.PASSPORT))
                    zip.write(DbMove.passportText(passport).toByteArray())
                    zip.closeEntry()
                }
                zip.finish()
                fos.flush()
                runCatching { fos.fd.sync() }
            }
        }
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        return files.size
    }

    /** Не в архив: почасовые копии, `.prev` и всё, что не база (модели, WAV, черновик). */
    internal fun skip(rel: String): Boolean {
        val top = rel.substringBefore('/')
        return top == "backups" || top == "zasechka-backups" || rel.endsWith(".prev") || DbMove.notDatabase(top)
    }

    private fun charging(context: Context): Boolean =
        runCatching { context.getSystemService(BatteryManager::class.java).isCharging }.getOrDefault(false)
}
