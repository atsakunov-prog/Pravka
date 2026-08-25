package ru.zf.pravka.data

import java.io.File
import java.io.FileOutputStream

// Shared persistence discipline for the hand-rolled JSON stores (dictionary,
// rules, pending suggestions, edit watches, eval set, the timesheet):
// - writes are atomic (tmp + fsync + rename), so neither a process kill nor a
//   power loss mid-write can leave a truncated file where the real one was;
// - the version being replaced stays on disk as `<name>.prev` until the next
//   write, so there is always a second copy one step back;
// - a file that EXISTS but does not parse is quarantined (renamed to
//   .corrupt) instead of staying in place, where the next persist() would
//   silently overwrite it with an empty store, and `.prev` is tried before
//   the store gives up and comes up empty.
object StoreFiles {

    fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            // Без fsync переименование бывает долговечнее самих данных: файл
            // на месте, а внутри - хвост нулей после внезапной перезагрузки.
            runCatching { out.fd.sync() }
        }
        // Копия предыдущей версии - ДО подмены, копированием: сам файл при этом
        // ни на миг не исчезает (переименование поверх атомарно).
        if (file.exists()) {
            runCatching { file.copyTo(File(file.parentFile, file.name + ".prev"), overwrite = true) }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Reads and parses [file]. Missing file -> the `.prev` copy if there is
     * one, else null (fresh store). Parse failure -> the file moves to
     * `<name>.corrupt` (replacing an older quarantine), `.prev` is tried, and
     * only then null; the bytes always survive on disk either way.
     */
    fun <T> readOrQuarantine(file: File, parse: (String) -> T): T? {
        if (!file.exists()) return readPrev(file, parse)
        val text = runCatching { file.readText() }.getOrNull() ?: return readPrev(file, parse)
        return runCatching { parse(text) }.getOrElse {
            val bad = File(file.parentFile, file.name + ".corrupt")
            bad.delete()
            file.renameTo(bad)
            readPrev(file, parse)
        }
    }

    private fun <T> readPrev(file: File, parse: (String) -> T): T? {
        val prev = File(file.parentFile, file.name + ".prev")
        if (!prev.exists()) return null
        val text = runCatching { prev.readText() }.getOrNull() ?: return null
        return runCatching { parse(text) }.getOrNull()
    }
}
