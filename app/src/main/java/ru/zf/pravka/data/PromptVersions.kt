package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Версии промпта CLEAN, которые ставил недельный подбор (core/PromptTuner.kt),
 * с метриками, по которым каждая принималась, и тем, как она себя показала
 * потом. Владелец (17.09.2026): «промпты бы хранились внутри приложения…
 * если хуже, он бы возвращал всё обратно». Без этой памяти нечего было бы
 * возвращать и не с чем сравнивать. Сам действующий текст живёт в
 * PromptStore (override), здесь — история и метрики.
 */
class PromptVersions(private val context: Context) {

    data class Version(
        val id: Long,
        val at: Long,
        /** tuner — поставил подбор; revert — возврат к прежнему; rejected — предложение, не прошедшее измерение (текст хранится для памяти). */
        val source: String,
        val text: String,
        /** Что изменено — словами подбора. */
        val note: String,
        /** Хэш заводского текста, на котором строилась версия: изменился в сборке — подбор должен вобрать новое. */
        val factoryHash: Int = 0,
        val judgeBetter: Int = 0,
        val judgeWorse: Int = 0,
        val judgeTie: Int = 0,
        /** Близость к правкам владельца: старый и новый промпт, по парам с правкой руками. */
        val simOld: Double = 0.0,
        val simNew: Double = 0.0,
        val ownerPairs: Int = 0,
        /** active · superseded · reverted · rejected */
        val status: String,
        val statusNote: String = "",
        /** Доля чисток с правкой руками до и после смены — заполняется через неделю. */
        val corrBefore: Double = -1.0,
        val corrAfter: Double = -1.0,
    )

    companion object {
        private const val FILE_NAME = "prompt-versions.json"
        private const val KEEP = 20
    }

    private val mutex = Mutex()
    private var loaded = false
    private var list = mutableListOf<Version>()
    private val _flow = MutableStateFlow<List<Version>>(emptyList())
    val flow: StateFlow<List<Version>> = _flow

    private fun file() = File(DataRoot.dir(context), FILE_NAME)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        list = (StoreFiles.readOrQuarantine(file()) { text -> parse(JSONArray(text)) } ?: mutableListOf())
        _flow.value = list.toList()
    }

    suspend fun all(): List<Version> = withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded(); list.toList() } }

    suspend fun active(): Version? = all().firstOrNull { it.status == "active" }

    suspend fun save(v: Version) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val i = list.indexOfFirst { it.id == v.id }
            if (i >= 0) list[i] = v else list.add(v)
            list.sortBy { it.at }
            // Отклонённые предложения — первые под нож: их текст нужен только как память «это уже пробовали».
            while (list.size > KEEP) {
                val victim = list.indexOfFirst { it.status == "rejected" }.takeIf { it >= 0 } ?: 0
                list.removeAt(victim)
            }
            StoreFiles.writeAtomic(file(), toJson(list).toString())
            _flow.value = list.toList()
        }
    }

    private fun parse(array: JSONArray): MutableList<Version> {
        val out = mutableListOf<Version>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out.add(
                Version(
                    id = o.optLong("id"), at = o.optLong("at"), source = o.optString("source", "tuner"),
                    text = o.optString("text"), note = o.optString("note"), factoryHash = o.optInt("factoryHash"),
                    judgeBetter = o.optInt("judgeBetter"), judgeWorse = o.optInt("judgeWorse"), judgeTie = o.optInt("judgeTie"),
                    simOld = o.optDouble("simOld", 0.0), simNew = o.optDouble("simNew", 0.0), ownerPairs = o.optInt("ownerPairs"),
                    status = o.optString("status", "superseded"), statusNote = o.optString("statusNote"),
                    corrBefore = o.optDouble("corrBefore", -1.0), corrAfter = o.optDouble("corrAfter", -1.0),
                )
            )
        }
        return out
    }

    private fun toJson(items: List<Version>): JSONArray = JSONArray().apply {
        for (v in items) put(
            JSONObject().apply {
                put("id", v.id); put("at", v.at); put("source", v.source); put("text", v.text); put("note", v.note)
                put("factoryHash", v.factoryHash); put("judgeBetter", v.judgeBetter); put("judgeWorse", v.judgeWorse)
                put("judgeTie", v.judgeTie); put("simOld", v.simOld); put("simNew", v.simNew); put("ownerPairs", v.ownerPairs)
                put("status", v.status); put("statusNote", v.statusNote); put("corrBefore", v.corrBefore); put("corrAfter", v.corrAfter)
            }
        )
    }
}
