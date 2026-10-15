package ru.zf.pravka.data

import android.content.Context
import java.io.File
import org.json.JSONObject
import ru.zf.pravka.core.RaznoskaCache

/**
 * Память правила кэша головы разбора надиктовки (`core/RaznoskaCache.kt`, аудит №67): когда был прошлый разбор, хэш
 * его головы и ставили ли кэш. Расходная: потеряли — следующий разбор просто пойдёт без кэша. Файл — в папке базы
 * (`DataRoot`), запись атомарная; читается и пишется на IO-потоке разбора.
 */
class RaznoskaCacheFile(private val context: Context) : RaznoskaCache.Memory {

    companion object {
        const val FILE_NAME = "raznoska-cache.json"
    }

    private val file: File get() = File(DataRoot.dir(context), FILE_NAME)

    override fun read(): RaznoskaCache.Last? = runCatching {
        if (!file.isFile) null else RaznoskaCache.fromJson(JSONObject(file.readText()))
    }.getOrNull()

    override fun write(last: RaznoskaCache.Last) {
        runCatching { StoreFiles.writeAtomic(file, RaznoskaCache.toJson(last).toString()) }
    }
}
