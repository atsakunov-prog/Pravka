package ru.zf.pravka.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Метка NFC: наклейка в туалете, на кухне, на велике, в машине. Касание
 * телефоном ставит засечку — без разблокировки, без диктовки, без экрана.
 *
 * ЧТО ЛЕЖИТ НА САМОЙ МЕТКЕ — только идентификатор. Не название, не
 * категория, не действие. Причина простая и практическая: метку клеят один
 * раз, а передумывают часто. «Что она делает» правится в настройках и
 * начинает работать со следующего касания — переклеивать и перезаписывать
 * ничего не надо. Второй довод — размер: у самых дешёвых наклеек (NTAG213)
 * всего 144 байта, и снимок действия по-русски их бы съел.
 */
data class NfcTag(
    /** Восемь шестнадцатеричных знаков; ровно это и записано на метке. */
    val id: String,
    /** Имя для списка в настройках: «Туалет», «Велик». */
    val name: String,
    val act: String,
    /** Что писать в ленту. Пусто — берём [name]. */
    val title: String,
    val category: String,
    /**
     * Закрыв дело по метке, вернуться к тому, что шло до него. Туалет,
     * кухня и звонок — это ПЕРЕРЫВЫ, и после них человек возвращается к
     * работе, а не в пустоту: без этого лента получала бы дыру, которую
     * заполнитель пометит «Не размечено».
     */
    val resume: Boolean = true,
    /** Когда её реально записали на наклейку (0 — ещё нет). */
    val written: Long = 0L,
) {
    /** Название для ленты: своё, а если не задано — имя метки. */
    fun entryTitle(): String = title.trim().ifBlank { name.trim() }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("act", act)
        .put("title", title)
        .put("cat", category)
        .put("resume", resume)
        .put("written", written)

    companion object {
        /** Касание начинает дело, повторное касание той же метки — закрывает. */
        const val ACT_TOGGLE = "toggle"
        /** Всегда начинает новое дело. */
        const val ACT_START = "start"
        /** Всегда закрывает открытое. */
        const val ACT_STOP = "stop"

        /**
         * Свой MIME-тип: по нему Android и понимает, что метку надо отдать
         * Правке. Коротко — потому что тип целиком лежит на наклейке.
         */
        const val MIME = "application/vnd.pravka"

        /** Что пишем в метку: версия формата и идентификатор. */
        fun payload(id: String): ByteArray = "1|$id".toByteArray(Charsets.US_ASCII)

        /** Обратно: из полезной нагрузки метки — идентификатор. */
        fun idFromPayload(bytes: ByteArray?): String {
            val s = bytes?.toString(Charsets.US_ASCII).orEmpty().trim()
            val i = s.indexOf('|')
            val id = if (i >= 0) s.substring(i + 1) else s
            return id.filter { it.isLetterOrDigit() }.take(32)
        }

        fun newId(): String = java.lang.Long.toHexString(
            (System.currentTimeMillis() shl 12) xor (Math.random() * 1e12).toLong()
        ).takeLast(8)

        fun actLabel(act: String): String = when (act) {
            ACT_START -> "начать дело"
            ACT_STOP -> "закрыть открытое"
            else -> "начать / закрыть"
        }

        fun fromJson(o: JSONObject): NfcTag? {
            val id = o.optString("id").orEmpty()
            if (id.isBlank()) return null
            return NfcTag(
                id = id,
                name = o.optString("name"),
                act = o.optString("act").ifBlank { ACT_TOGGLE },
                title = o.optString("title"),
                category = o.optString("cat"),
                resume = o.optBoolean("resume", true),
                written = o.optLong("written"),
            )
        }

        fun listFromJson(raw: String): List<NfcTag> {
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { fromJson(it) } }
            }.getOrDefault(emptyList())
        }

        fun listToJson(tags: List<NfcTag>): String =
            JSONArray().apply { tags.forEach { put(it.toJson()) } }.toString()
    }
}
