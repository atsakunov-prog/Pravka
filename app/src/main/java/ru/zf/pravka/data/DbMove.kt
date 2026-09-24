package ru.zf.pravka.data

import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Переезд базы между двумя папками — чистые операции над файлами, без Android.
 *
 * Отдельно от [DataRoot], потому что здесь живут незаменимые данные: ленту,
 * дневник еды и журнал подходов переносят именно эти функции, и каждая
 * ветка (докопировать, закрепить, отложить прежнее) проверяется JVM-тестом на
 * временных папках, а не на телефоне владельца.
 *
 * Три правила, ради которых код такой:
 * - файл в новом месте появляется атомарно (временный, `fsync`, переименование),
 *   с тем же временем изменения — по нему копии (`Backups.prune`) считают возраст,
 *   а докопирование узнаёт, что файл не менялся;
 * - прежняя папка ничего не теряет: её файлы не удаляются, а откладываются в
 *   подпапку `before-move-…` рядом (правило 1 — сырое не удаляется никогда);
 * - в новой папке удаляется только то, что сами туда скопировали, — чужие
 *   файлы в папке не трогаются.
 */
internal object DbMove {

    /** Паспорт базы в её папке: есть он — папка является базой Правки. */
    const val PASSPORT = "pravka-db.json"

    /**
     * Копирует дерево [src] в [dst]: всё, кроме того, что [skip] отвергает по
     * относительному пути, и кроме временных `.tmp`. Файл, у которого в [dst]
     * тот же размер и то же время изменения, не копируется — так второй проход
     * докопирует только изменившееся за время первого. [onFile] — ход для экрана.
     * Возвращает относительные пути всех файлов, которые теперь есть в [dst] из [src].
     */
    fun mirror(
        src: File,
        dst: File,
        skip: (String) -> Boolean,
        onFile: ((done: Int, total: Int) -> Unit)? = null,
    ): List<String> {
        val files = listFiles(src, skip)
        dst.mkdirs()
        files.forEachIndexed { i, rel ->
            val from = File(src, rel)
            val to = File(dst, rel)
            if (!same(from, to)) copyAtomic(from, to)
            onFile?.invoke(i + 1, files.size)
        }
        return files
    }

    /** Относительные пути файлов дерева, без отвергнутых и без `.tmp`. */
    fun listFiles(root: File, skip: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (f in children.sortedBy { it.name }) {
                val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                if (skip(rel)) continue
                if (f.isDirectory) walk(f, rel)
                else if (f.isFile && !f.name.endsWith(".tmp")) out.add(rel)
            }
        }
        walk(root, "")
        return out
    }

    /**
     * Тот же размер и ТО ЖЕ время изменения, без допуска: правка «12:30» на
     * «12:35» длину не меняет, и допуск в секунду принял бы такую ленту за
     * прежнюю. Где система время огрубляет, файл просто копируется лишний раз.
     */
    fun same(a: File, b: File): Boolean =
        b.isFile && a.length() == b.length() && a.lastModified() == b.lastModified()

    /** Копия файла: временный рядом, `fsync`, переименование поверх, время изменения — как у источника. */
    fun copyAtomic(from: File, to: File) {
        to.parentFile?.mkdirs()
        val tmp = File(to.parentFile, to.name + ".tmp")
        from.inputStream().use { input ->
            FileOutputStream(tmp).use { out ->
                input.copyTo(out)
                out.flush()
                runCatching { out.fd.sync() }
            }
        }
        if (!tmp.renameTo(to)) {
            tmp.copyTo(to, overwrite = true)
            tmp.delete()
        }
        runCatching { to.setLastModified(from.lastModified()) }
    }

    /**
     * Откладывает прежнюю базу в [root]/[asideName]: каждый верхний элемент,
     * кроме тех, что [keep] оставляет на месте, переезжает переименованием (та же
     * файловая система — мгновенно, без копии). Повторный вызов докладывает
     * оставшееся в ту же подпапку. Ничего не удаляется. Возвращает, сколько
     * элементов отложено.
     */
    fun setAside(root: File, asideName: String, keep: (String) -> Boolean): Int {
        val aside = File(root, asideName)
        var moved = 0
        for (f in root.listFiles().orEmpty()) {
            if (f.name == asideName || keep(f.name)) continue
            aside.mkdirs()
            val target = File(aside, f.name)
            if (target.exists()) continue  // уже отложено прошлым заходом — не перетираем
            if (f.renameTo(target)) moved++
        }
        return moved
    }

    // --- паспорт базы -------------------------------------------------------

    class Passport(
        /** Метка переезда, который эту папку сделал базой: по ней старт узнаёт свой недоделанный переезд. */
        val token: String,
        val createdAt: Long,
        val device: String,
        val format: Int = 1,
    )

    fun readPassport(folder: File): Passport? {
        val f = File(folder, PASSPORT)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            Passport(
                token = o.optString("token"),
                createdAt = o.optLong("createdAt"),
                device = o.optString("device"),
                format = o.optInt("format", 1),
            )
        }.getOrNull()
    }

    fun writePassport(folder: File, p: Passport) {
        val o = JSONObject()
            .put("app", "Правка")
            .put("format", p.format)
            .put("token", p.token)
            .put("createdAt", p.createdAt)
            .put("device", p.device)
            .put("note", "Папка — база приложения Правка. Скопировать базу — скопировать папку целиком.")
        folder.mkdirs()
        StoreFiles.writeAtomic(File(folder, PASSPORT), o.toString(2))
    }

    // --- подготовленный переезд ---------------------------------------------

    /**
     * Переезд, подготовленный на ходу и ждущий закрепления на следующем старте
     * процесса. [paths] — что скопировано первым проходом (только это можно
     * удалить из новой папки, если в прежней оно успело исчезнуть).
     */
    class Pending(
        val mode: String,
        val folder: String,
        val token: String,
        val aside: String,
        val paths: List<String> = emptyList(),
    ) {
        companion object {
            const val COPY = "copy"
            const val ADOPT = "adopt"
        }
    }

    fun writePending(file: File, p: Pending) {
        val o = JSONObject()
            .put("mode", p.mode)
            .put("folder", p.folder)
            .put("token", p.token)
            .put("aside", p.aside)
            .put("paths", JSONArray(p.paths))
        StoreFiles.writeAtomic(file, o.toString())
    }

    fun readPending(file: File): Pending? {
        if (!file.isFile) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("paths") ?: JSONArray()
            Pending(
                mode = o.getString("mode"),
                folder = o.getString("folder"),
                token = o.optString("token"),
                aside = o.getString("aside"),
                paths = (0 until arr.length()).map { arr.getString(it) },
            )
        }.getOrNull()
    }

    /** Чем кончилось закрепление. */
    enum class Outcome { MOVED, ABORTED_FOLDER_TAKEN, ABORTED_NO_DB, ABORTED_BAD_PENDING }

    /**
     * Закрепляет подготовленный переезд — на старте процесса, ДО первого стора,
     * пока никто не пишет ни в одну из папок. Порядок выбран так, что обрыв в
     * любой точке (убили процесс, сел телефон) на следующем старте доделывается,
     * а не портит:
     * 1. докопировать изменившееся после первого прохода и убрать из новой папки
     *    скопированное тогда, но с тех пор исчезнувшее (например, снятую копию);
     * 2. паспорт в новую папку — с меткой ЭТОГО переезда;
     * 3. [writeMoved] — отметка «база там» в прежней папке (с этого места старт
     *    читает новую папку);
     * 4. прежние файлы — в подпапку `before-move-…`.
     * Если паспорт уже стоит с нашей меткой — шаги 1–2 пройдены, повторять их
     * нельзя: после шага 4 «исчезнувшим» показалось бы всё отложенное.
     */
    fun commit(
        priv: File,
        p: Pending,
        skip: (String) -> Boolean,
        keepPrivate: (String) -> Boolean,
        device: String,
        now: Long,
        writeMoved: (File) -> Unit,
    ): Outcome {
        val folder = File(p.folder)
        if (p.folder.isBlank() || p.aside.isBlank()) return Outcome.ABORTED_BAD_PENDING
        val passport = readPassport(folder)
        when (p.mode) {
            Pending.COPY -> if (passport?.token != p.token) {
                // Паспорт с чужой меткой: между подготовкой и стартом в папку
                // положили другую базу. Затирать её нельзя — переезд отменяется.
                if (passport != null) return Outcome.ABORTED_FOLDER_TAKEN
                val now2 = mirror(priv, folder, skip).toHashSet()
                for (rel in p.paths) if (rel !in now2) File(folder, rel).delete()
                writePassport(folder, Passport(token = p.token, createdAt = now, device = device))
            }
            Pending.ADOPT -> if (passport == null) return Outcome.ABORTED_NO_DB
            else -> return Outcome.ABORTED_BAD_PENDING
        }
        writeMoved(folder)
        setAside(priv, p.aside, keepPrivate)
        return Outcome.MOVED
    }

    /** Сколько файлов и байт в дереве — для строки «в папке 212 файлов, 38 МБ». */
    fun measure(root: File, skip: (String) -> Boolean = { false }): Pair<Int, Long> {
        val files = listFiles(root, skip)
        return files.size to files.sumOf { File(root, it).length() }
    }
}
