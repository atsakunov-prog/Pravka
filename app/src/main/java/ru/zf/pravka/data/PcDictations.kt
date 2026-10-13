package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import ru.zf.pravka.core.Dictations

/**
 * Диктовки компьютера («Диктовка» на ПК) — копия с сервера для экрана «Правка» (Правка 5.0,
 * этап 5, 13.10.2026). Правда — архив на компе: Диктовка пишет туда `pravka.take` и
 * `pravka.clean` с ключом «pc-<мс>», телефон забирает их `POST /sync` (`ArchiveSync.pull`) и
 * кладёт сюда. Курсор живёт в квитанциях архива; повтор той же записи — норма (курсор с
 * запасом назад), поэтому [apply] просто кладёт снимок поверх.
 *
 * Файл — `pc-dictations.json` в папке базы (`DataRoot.dir`, железное правило 1): после
 * переезда базы копия едет вместе с ней. Пропал — не беда: следующий забор без курсора
 * («Отправить всё заново» стирает квитанции) вернёт всё.
 */
internal class PcDictations(private val context: Context) {

    private val file: File get() = File(DataRoot.dir(context), FILE)

    private var loaded = false
    private val takes = LinkedHashMap<String, JSONObject>()
    private val cleans = LinkedHashMap<String, JSONObject>()

    /** Меняется, когда пришло новое: экран перечитывает список. */
    private val _rev = MutableStateFlow(0)
    val rev: StateFlow<Int> = _rev

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val o = StoreFiles.readOrQuarantine(file) { JSONObject(it) } ?: return
        o.optJSONObject("takes")?.let { m -> for (k in m.keys()) m.optJSONObject(k)?.let { takes[k] = it } }
        o.optJSONObject("cleans")?.let { m -> for (k in m.keys()) m.optJSONObject(k)?.let { cleans[k] = it } }
    }

    /**
     * Записи `/sync` — `{kind, key, op, data?}`: `put` кладёт снимок, `del` убирает. Чужие виды
     * пропускаются. Возвращает, сколько записей поменялось; пишет файл, только если что-то есть.
     */
    @Synchronized
    fun apply(records: List<JSONObject>): Int {
        load()
        var changed = 0
        for (r in records) {
            val map = when (r.optString("kind")) {
                TAKE -> takes
                CLEAN -> cleans
                else -> continue
            }
            val key = r.optString("key").takeIf { it.isNotBlank() } ?: continue
            when (r.optString("op")) {
                "put" -> {
                    val d = r.optJSONObject("data") ?: continue
                    if (map[key]?.toString() != d.toString()) { map[key] = d; changed++ }
                }
                "del" -> if (map.remove(key) != null) changed++
            }
        }
        if (changed > 0) {
            val o = JSONObject().put("v", 1)
                .put("takes", JSONObject().apply { for ((k, v) in takes) put(k, v) })
                .put("cleans", JSONObject().apply { for ((k, v) in cleans) put(k, v) })
            StoreFiles.writeAtomic(file, o.toString())
            _rev.value = _rev.value + 1
        }
        return changed
    }

    /** Диктовки компа для экрана. Читает файл один раз — потом из памяти. */
    @Synchronized
    fun items(): List<Dictations.Item> {
        load()
        return Dictations.pc(HashMap(takes), HashMap(cleans))
    }

    companion object {
        const val FILE = "pc-dictations.json"
        /** Имя забора в `ArchiveSync` — ключ курсора. */
        const val PULL = "pc-dictations"
        const val TAKE = "pravka.take"
        const val CLEAN = "pravka.clean"
        /** Что забирать с сервера — виды диктовок; сервер отдаёт по ним только записанное службами компа. */
        val KINDS = listOf(TAKE, CLEAN)
    }
}
