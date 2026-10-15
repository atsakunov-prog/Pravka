package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.ReminderPlan

/**
 * Какие напоминания Дел этот телефон уже показал (захват, 15.10.2026, `core/ReminderPlan.kt`): ключи «дело@миг»,
 * последние [ReminderPlan.KEEP]. Без памяти перезапуск службы, загрузка телефона и второй будильник показали бы одно
 * и то же напоминание снова. Файл — в `DataRoot.dir` (правило 1 карты: база — одна папка на установку).
 *
 * Нет файла — первый запуск: [seeded] = false, и тот, кто показывает, сперва помечает прошедшее показанным
 * (`ReminderPlan.seed`), чтобы обновление не вывалило разом всё, что бот уже прислал.
 */
class ReminderShown(private val context: Context) {

    companion object {
        const val FILE_NAME = "dela-reminders-shown.json"
    }

    private val mutex = Mutex()
    private val file: File get() = File(DataRoot.dir(context), FILE_NAME)
    private var loaded = false
    private var keys = mutableListOf<String>()
    private var exists = false

    /** Память уже была (не первый запуск). */
    suspend fun seeded(): Boolean = mutex.withLock { ensure(); exists }

    suspend fun all(): Set<String> = mutex.withLock { ensure(); keys.toSet() }

    /** Запомнить показанное — на диск сразу: память короткая, а повторный звон хуже лишней записи. */
    suspend fun add(add: Collection<String>) = mutex.withLock {
        ensure()
        keys = ReminderPlan.remember(keys, add).toMutableList()
        exists = true
        val text = JSONObject().put("v", 1).put("keys", JSONArray(keys)).toString()
        withContext(Dispatchers.IO) { StoreFiles.writeAtomic(file, text) }
    }

    private suspend fun ensure() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            exists = file.isFile
            StoreFiles.readOrQuarantine(file) { JSONObject(it) }?.optJSONArray("keys")?.let { a ->
                keys = (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.toMutableList()
            }
        }
        loaded = true
    }
}
