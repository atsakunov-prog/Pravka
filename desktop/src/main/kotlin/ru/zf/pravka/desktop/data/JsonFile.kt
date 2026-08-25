package ru.zf.pravka.desktop.data

import java.io.File
import org.json.JSONObject
import ru.zf.pravka.data.DiskWriter
import ru.zf.pravka.data.StoreFiles

// Маленькое хранилище "ключ - значение" в JSON-файле, с той же дисциплиной
// записи, что и на телефоне (временный файл, fsync, переименование, копия
// .prev). Заменяет DataStore, которого на воркстанции нет.
class JsonFile(private val file: File) {

    private val lock = Any()
    private var root: JSONObject = StoreFiles.readOrQuarantine(file) { JSONObject(it) } ?: JSONObject()

    fun string(key: String, default: String = ""): String = synchronized(lock) {
        if (root.has(key)) root.optString(key, default) else default
    }

    fun boolean(key: String, default: Boolean): Boolean = synchronized(lock) {
        if (root.has(key)) root.optBoolean(key, default) else default
    }

    fun int(key: String, default: Int): Int = synchronized(lock) {
        if (root.has(key)) root.optInt(key, default) else default
    }

    fun long(key: String, default: Long): Long = synchronized(lock) {
        if (root.has(key)) root.optLong(key, default) else default
    }

    fun double(key: String, default: Double): Double = synchronized(lock) {
        if (root.has(key)) root.optDouble(key, default) else default
    }

    fun put(key: String, value: Any?) {
        val text = synchronized(lock) {
            if (value == null) root.remove(key) else root.put(key, value)
            root.toString(2)
        }
        DiskWriter.post { StoreFiles.writeAtomic(file, text) }
    }

    /** Несколько ключей одной записью - счётчики расхода меняются пачкой. */
    fun edit(block: (JSONObject) -> Unit) {
        val text = synchronized(lock) {
            block(root)
            root.toString(2)
        }
        DiskWriter.post { StoreFiles.writeAtomic(file, text) }
    }

    fun keys(): List<String> = synchronized(lock) { root.keys().asSequence().toList() }
}
