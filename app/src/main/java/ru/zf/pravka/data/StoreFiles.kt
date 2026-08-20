package ru.zf.pravka.data

import java.io.File

// Shared persistence discipline for the hand-rolled JSON stores (dictionary,
// rules, pending suggestions, edit watches, eval set):
// - writes are atomic (tmp + rename), so a process kill mid-write can never
//   leave a truncated file where the real one was;
// - a file that EXISTS but does not parse is quarantined (renamed to
//   .corrupt) instead of staying in place, where the next persist() would
//   silently overwrite it with an empty store. The quarantined copy keeps
//   the owner's data recoverable by hand.
internal object StoreFiles {

    fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Reads and parses [file]. Missing file -> null (fresh store). Parse
     * failure -> the file moves to `<name>.corrupt` (replacing an older
     * quarantine) and null is returned; the store starts empty but the bytes
     * survive on disk.
     */
    fun <T> readOrQuarantine(file: File, parse: (String) -> T): T? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { parse(text) }.getOrElse {
            val bad = File(file.parentFile, file.name + ".corrupt")
            bad.delete()
            file.renameTo(bad)
            null
        }
    }
}
