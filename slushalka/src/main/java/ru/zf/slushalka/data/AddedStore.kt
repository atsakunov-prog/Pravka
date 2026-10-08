package ru.zf.slushalka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Когда книга легла на полку - для порядка «Добавленные».
 *
 * Своей даты у книги нет: у сервера в оглавлении только `modified` - когда
 * менялась папка, а меняется она всякий раз, как туда ложится разметка,
 * справочник или разбор. По нему книга, которую сейчас читают, вставала бы
 * наверх «добавленных». Поэтому дата запоминается при первой встрече и
 * дальше не трогается: у книги сервера - её `modified` на тот день (в первую
 * встречу это лучшая догадка), у своей без сервера - «сейчас». Если сервер
 * когда-нибудь положит в оглавление `added`, полка возьмёт его, а не это.
 */
class AddedStore(context: Context) {

    private val file = File(context.filesDir, "added.json")
    private val _dates = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Ключ книги - когда она появилась на полке, мс. */
    val dates: StateFlow<Map<String, Long>> = _dates

    init {
        Store.readOrQuarantine(file) { text ->
            val o = JSONObject(text)
            o.keys().asSequence().associateWith { o.optLong(it) }.filterValues { it > 0 }
        }?.let { _dates.value = it }
    }

    /** Догадки для книг, которых ещё не видели; виденные не трогаются. */
    @Synchronized
    fun note(guesses: Map<String, Long>) {
        val cur = _dates.value
        val fresh = guesses.filter { (k, v) -> v > 0 && k !in cur }
        if (fresh.isEmpty()) return
        _dates.value = cur + fresh
        persist()
    }

    /** Книга переехала на ключ сервера: остаётся более ранняя из двух дат. */
    @Synchronized
    fun rekey(old: String, new: String) {
        if (old == new) return
        val cur = _dates.value
        val from = cur[old] ?: return
        val to = cur[new]
        _dates.value = cur - old + (new to if (to != null) minOf(from, to) else from)
        persist()
    }

    private fun persist() {
        val o = JSONObject()
        _dates.value.forEach { (k, v) -> o.put(k, v) }
        val text = o.toString()
        Store.post { Store.writeAtomic(file, text) }
    }
}
