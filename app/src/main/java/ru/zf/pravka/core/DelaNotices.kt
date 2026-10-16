package ru.zf.pravka.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Плашка «Сервер ответил» вкладки Дел — то, что владелец должен узнать словами: отказ сервера, спор правок,
 * недописанное имя. Хранит `DelaStore` (в `dela-outbox.json`, рядом с очередью), рисует `DelaTab`; здесь — чистая
 * часть под тестом `DelaNoticesTest`: что показывать, время строкой, текст отказа, запись и чтение.
 *
 * Отладка 16.10.2026, записи 1–2 (docs/dela.md, «Сервер ответил»): отказ от 14.10 провисел над «Новым» 41 час — без времени,
 * гас только по «Понятно», а оно на развороте сплющилось в чёрточку, — и владелец связал его со своим ответом двумя
 * днями позже («сервер не принял принять предложение»). Теперь у строки время, заметка старше суток гаснет сама, а то,
 * что уже сказано тостом сразу после тапа ([Notice.shown]), второй раз в плашке не висит.
 */
object DelaNotices {

    /**
     * Заметка. [opId] — чья: тост после отправки показывает заметку своей операции, а не первую свежую из той же
     * пачки (проверка 15.10.2026). [shown] — уже сказана тостом: в плашке её нет, в файле — остаётся (журнал).
     */
    data class Notice(
        val at: Long,
        val text: String,
        val error: Boolean,
        val opId: String = "",
        val shown: Boolean = false,
    )

    /** Сколько заметка висит в плашке: дальше — не новость, а путаница с сегодняшним (запись 1). */
    const val TTL_MS = 24L * 60 * 60 * 1000
    /** Строк в плашке — не больше. */
    const val SHOW_MAX = 5
    /** Начало текста отказа: по нему записка «Что не так?» оставляет текст в поле (`DelaDebugUi.send`). */
    const val REFUSED = "Не прошло"

    private val STAMP = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /** Что показать в плашке: не сказанное тостом и моложе суток, свежие первыми (как лежат), до [SHOW_MAX]. */
    fun visible(list: List<Notice>, now: Long): List<Notice> =
        list.filter { !it.shown && now - it.at < TTL_MS }.take(SHOW_MAX)

    /** Когда — «14.10 15:25» по часам телефона: без даты вчерашний отказ читается как ответ на сегодняшний тап. */
    fun stamp(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = STAMP.format(Instant.ofEpochMilli(at).atZone(zone))

    /** Отказ сервера словами: «Не прошло: «Да» на «…» — уже решено раньше…» ([what] — `Dela.describe`). */
    fun refused(what: String, error: String): String = "$REFUSED: $what — ${error.trim().ifBlank { "без причины" }}"

    /** Пометить сказанными тостом [told] — по операции, времени и тексту (у одной операции бывает и вторая заметка). */
    fun markShown(list: List<Notice>, told: Collection<Notice>): List<Notice> {
        if (told.isEmpty()) return list
        val keys = told.map { Triple(it.opId, it.at, it.text) }.toSet()
        return list.map { if (!it.shown && Triple(it.opId, it.at, it.text) in keys) it.copy(shown = true) else it }
    }

    fun toJson(list: List<Notice>): JSONArray = JSONArray().apply {
        for (n in list) {
            val o = JSONObject().put("at", n.at).put("text", n.text).put("error", n.error).put("op_id", n.opId)
            if (n.shown) o.put("shown", true)
            put(o)
        }
    }

    /** Из `dela-outbox.json`; файл до 16.10.2026 — без `shown`: такие заметки не сказаны (старше суток и так не видны). */
    fun fromJson(a: JSONArray?): List<Notice> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val n = a.optJSONObject(i) ?: return@mapNotNull null
            Notice(n.optLong("at"), n.optString("text"), n.optBoolean("error"), n.optString("op_id"), n.optBoolean("shown", false))
        }
    }
}
