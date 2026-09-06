package ru.zf.slushalka.ask

import org.json.JSONArray
import org.json.JSONObject

/** Запись о главе: что с героем (местом) случилось именно в ней. */
data class GuideNote(val chapter: Int, val text: String)

/** Краткое содержание главы - абзац-полтора, чтобы вспомнить прочитанное. */
data class GuideChapter(val chapter: Int, val title: String, val summary: String) {
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() || title.lowercase().contains(q) || summary.lowercase().contains(q)
    }

    fun toJson(): JSONObject = JSONObject().put("c", chapter).put("t", title).put("s", summary)

    companion object {
        fun fromJson(o: JSONObject): GuideChapter? {
            val summary = o.optString("s").ifBlank { o.optString("summary") }.trim()
            if (summary.isEmpty()) return null
            return GuideChapter(
                chapter = o.optInt("c", o.optInt("chapter", 1)).coerceAtLeast(1),
                title = o.optString("t").ifBlank { o.optString("title") }.trim(),
                summary = summary,
            )
        }
    }
}

/**
 * Статья справочника: герой, место или слово. [chapter] - глава первого
 * появления, [role] - кто это на момент появления, [notes] - по главам.
 */
data class GuideEntry(
    val name: String,
    val aliases: List<String>,
    val chapter: Int,
    val role: String,
    val notes: List<GuideNote>,
) {
    /** Все имена, под которыми статью можно найти. */
    val names: List<String> get() = listOf(name) + aliases

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return names.any { it.lowercase().contains(q) } || role.lowercase().contains(q)
    }

    /**
     * Статья глазами читателя, дочитавшего главы по [upTo] включительно:
     * заметки о более поздних главах спрятаны, а сама статья пропадает, если
     * герой ещё не появлялся. Это и есть спойлер-барьер справочника.
     */
    fun visibleAt(upTo: Int): GuideEntry? {
        if (chapter > upTo) return null
        return copy(notes = notes.filter { it.chapter <= upTo })
    }

    /** Упоминается ли кто-то из имён в этом куске текста - для кнопок под абзацем. */
    fun mentionedIn(text: String): Boolean {
        val low = text.lowercase()
        return names.any { n -> n.length >= 3 && low.contains(n.lowercase()) }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("n", name)
        .put("a", JSONArray(aliases))
        .put("c", chapter)
        .put("r", role)
        .put("notes", JSONArray().apply { notes.forEach { put(JSONObject().put("c", it.chapter).put("t", it.text)) } })

    companion object {
        fun fromJson(o: JSONObject): GuideEntry? {
            val name = o.optString("n").ifBlank { o.optString("name") }.trim()
            if (name.isEmpty()) return null
            val aliases = o.optJSONArray("a") ?: o.optJSONArray("aliases")
            val notes = o.optJSONArray("notes")
            return GuideEntry(
                name = name,
                aliases = strings(aliases).filter { it.isNotBlank() && !it.equals(name, ignoreCase = true) },
                chapter = o.optInt("c", o.optInt("chapter", 1)).coerceAtLeast(1),
                role = o.optString("r").ifBlank { o.optString("role") }.trim(),
                notes = (0 until (notes?.length() ?: 0)).mapNotNull { i ->
                    val n = notes!!.optJSONObject(i) ?: return@mapNotNull null
                    val text = n.optString("t").ifBlank { n.optString("text") }.trim()
                    if (text.isEmpty()) null else GuideNote(n.optInt("c", n.optInt("chapter", 1)).coerceAtLeast(1), text)
                }.sortedBy { it.chapter },
            )
        }

        private fun strings(arr: JSONArray?): List<String> =
            (0 until (arr?.length() ?: 0)).map { arr!!.optString(it).trim() }
    }
}

/** Справочник целиком: главы, герои, места, словарь. */
data class Guide(
    val characters: List<GuideEntry>,
    val places: List<GuideEntry>,
    val terms: List<GuideEntry>,
    val chapters: List<GuideChapter> = emptyList(),
) {
    val isEmpty get() = characters.isEmpty() && places.isEmpty() && terms.isEmpty() && chapters.isEmpty()

    val all: List<GuideEntry> get() = characters + places + terms

    /** 0 - герой, 1 - место, 2 - слово: от этого зависят готовые вопросы. */
    fun kindOf(e: GuideEntry): Int = when {
        characters.contains(e) -> 0
        places.contains(e) -> 1
        else -> 2
    }

    fun toJson(): JSONObject = JSONObject()
        .put("chapters", JSONArray(chapters.map { it.toJson() }))
        .put("characters", JSONArray(characters.map { it.toJson() }))
        .put("places", JSONArray(places.map { it.toJson() }))
        .put("terms", JSONArray(terms.map { it.toJson() }))

    /**
     * Склейка частей: книга длиннее лимита уезжает несколькими запросами, и
     * один герой приходит из каждой части своей статьёй. Статьи с одним именем
     * (или именем, совпавшим с чужим псевдонимом) сливаются: роль - от самой
     * ранней, заметки - все.
     */
    fun merge(other: Guide): Guide = Guide(
        characters = mergeEntries(characters + other.characters),
        places = mergeEntries(places + other.places),
        terms = mergeEntries(terms + other.terms),
        chapters = (chapters + other.chapters).distinctBy { it.chapter }.sortedBy { it.chapter },
    )

    /** Что вышло из ответа модели: сам справочник и признак, что JSON пришлось починить. */
    data class Parsed(val guide: Guide, val repaired: Boolean)

    companion object {
        val EMPTY = Guide(emptyList(), emptyList(), emptyList())

        // Повторы склеиваются уже при чтении: модель случается описывает
        // одного человека дважды - по имени и по прозвищу.
        fun fromJson(o: JSONObject): Guide = Guide(
            characters = mergeEntries(entries(o.optJSONArray("characters"))),
            places = mergeEntries(entries(o.optJSONArray("places"))),
            terms = mergeEntries(entries(o.optJSONArray("terms"))),
            chapters = chapters(o.optJSONArray("chapters")),
        )

        /**
         * Из ответа модели. JSON просят «и только», но пояснение до или после
         * случается - берётся кусок от первой фигурной скобки до последней. А
         * ответ, обрезанный по длине, чинится: хвост до последней целой записи
         * отбрасывается, скобки закрываются, - лучше справочник без пары
         * последних слов, чем никакого.
         */
        fun parse(text: String): Parsed? {
            val a = text.indexOf('{')
            if (a < 0) return null
            val b = text.lastIndexOf('}')
            if (b > a) {
                runCatching { JSONObject(text.substring(a, b + 1)) }.getOrNull()?.let { o ->
                    return fromJson(o).takeUnless { it.isEmpty }?.let { Parsed(it, repaired = false) }
                }
            }
            val fixed = repairJson(text.substring(a)) ?: return null
            val o = runCatching { JSONObject(fixed) }.getOrNull() ?: return null
            return fromJson(o).takeUnless { it.isEmpty }?.let { Parsed(it, repaired = true) }
        }

        /**
         * Обрезанный JSON: идём по тексту, помня открытые скобки (строки и
         * экранирование учитываются), и запоминаем каждое место, где закрылась
         * целая запись - глубина после закрытия не больше двух: сам объект и
         * список внутри него. Всё после последнего такого места отбрасывается,
         * открытые скобки закрываются.
         */
        internal fun repairJson(raw: String): String? {
            val stack = ArrayList<Char>()
            var inString = false
            var escaped = false
            var cutAt = -1
            var cutStack: List<Char> = emptyList()
            for (i in raw.indices) {
                val c = raw[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> stack.add(c)
                    '}', ']' -> {
                        if (stack.isEmpty()) return null
                        stack.removeAt(stack.lastIndex)
                        if (stack.size <= 2) {
                            cutAt = i + 1
                            cutStack = stack.toList()
                        }
                    }
                }
            }
            if (cutAt < 0) return null
            val head = raw.substring(0, cutAt).trimEnd().trimEnd(',')
            val tail = cutStack.reversed().joinToString("") { if (it == '{') "}" else "]" }
            return head + tail
        }

        private fun entries(arr: JSONArray?): List<GuideEntry> =
            (0 until (arr?.length() ?: 0)).mapNotNull { i -> arr!!.optJSONObject(i)?.let { GuideEntry.fromJson(it) } }

        private fun chapters(arr: JSONArray?): List<GuideChapter> =
            (0 until (arr?.length() ?: 0)).mapNotNull { i -> arr!!.optJSONObject(i)?.let { GuideChapter.fromJson(it) } }
                .distinctBy { it.chapter }
                .sortedBy { it.chapter }

        fun mergeEntries(list: List<GuideEntry>): List<GuideEntry> {
            val out = ArrayList<GuideEntry>()
            for (e in list.sortedWith(compareBy({ it.chapter }, { it.name }))) {
                val i = out.indexOfFirst { known -> known.names.any { n -> e.names.any { it.equals(n, ignoreCase = true) } } }
                if (i < 0) {
                    out.add(e)
                } else {
                    val k = out[i]
                    out[i] = k.copy(
                        aliases = (k.aliases + e.names).filter { !it.equals(k.name, ignoreCase = true) }
                            .distinctBy { it.lowercase() },
                        role = k.role.ifBlank { e.role },
                        notes = (k.notes + e.notes).distinctBy { it.chapter to it.text }.sortedBy { it.chapter },
                    )
                }
            }
            return out
        }
    }
}

/**
 * Состояние справочника книги: заказан пакетом и ждём, готов, или не вышло.
 * Живёт файлом на телефоне - пакет считается до суток, приложение за это
 * время не раз закроют. Готовый справочник ложится ещё и в папку книги
 * (см. GuideEngine), чтобы достался второму читателю.
 */
data class GuideState(
    val status: Status,
    val batchId: String,
    val createdAt: Long,
    val model: String,
    val parts: Int,
    val guide: Guide?,
    val error: String = "",
    val costUsd: Double = 0.0,
    val checkedAt: Long = 0L,
    /** Кто заказывал - имя дорожки из настроек; в файле рядом с книгой видно, чей это справочник. */
    val by: String = "",
) {
    enum class Status { PENDING, READY, FAILED }

    fun toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("batch", batchId)
        .put("created", createdAt)
        .put("model", model)
        .put("parts", parts)
        .put("error", error)
        .put("usd", costUsd)
        .put("checked", checkedAt)
        .put("by", by)
        .apply { guide?.let { put("guide", it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject): GuideState = GuideState(
            status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.FAILED),
            batchId = o.optString("batch"),
            createdAt = o.optLong("created"),
            model = o.optString("model"),
            parts = o.optInt("parts", 1),
            guide = o.optJSONObject("guide")?.let { Guide.fromJson(it) },
            error = o.optString("error"),
            costUsd = o.optDouble("usd", 0.0),
            checkedAt = o.optLong("checked"),
            by = o.optString("by"),
        )
    }
}
