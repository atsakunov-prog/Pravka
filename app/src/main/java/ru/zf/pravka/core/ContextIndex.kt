package ru.zf.pravka.core

import org.json.JSONObject

/**
 * Контекст по делу на телефоне (аудит 15.10.2026, №67; контракт — `server/contract/dela-context.json`, флаг
 * `context_index`). Голова разбора надиктовки несла весь справочник людей — 400 строк, ~20 тыс. знаков в каждом разборе,
 * и росла с каждой карточкой. Модели нужны не все, а связанные с делами и названные в наговоре.
 *
 * Сервер отдаёт видом `context_index` две вещи: [related] — кто связан с делами (одна правда о «связан» — на сервере,
 * `focus.related`), и ключи имён людей и проектов. Матчер ([forms], [peopleIn]) — тот же, что у сервера
 * (`focus.forms`/`focus.found`) и службы встреч: таблицы окончаний, букв окончаний и транслитерации приходят в ответе,
 * своих копий здесь нет. Правило [RULE]: другой номер — другой матчер, телефон такой вид не берёт (каталог как был).
 *
 * Чистый класс, под тестом `ContextIndexTest`: разбор ответа и матчер.
 */
class ContextIndex(
    val rule: Int,
    val days: Int,
    /** Связанные с делами живые карточки, видимые этому пользователю. */
    val related: Set<String>,
    /** id человека → его ключи имён (основы, уменьшительные, транслит). */
    val people: Map<String, List<String>>,
    /** id проекта → ключи его имени и других имён (без общих слов). */
    val projects: Map<String, List<String>>,
    private val endings: List<String>,
    private val letters: Set<Char>,
    private val translit: List<Pair<String, String>>,
    private val translitYEnd: Boolean,
) {
    private val peopleByKey: Map<String, Set<String>> by lazy { reverse(people) }
    private val projectsByKey: Map<String, Set<String>> by lazy { reverse(projects) }

    /**
     * Формы слова текста (`focus.forms`): само слово (нижний регистр, ё → е, без «ь» и «ъ», латиница — транслитом); оно
     * без каждого подходящего окончания, если остаётся ≥ 3 буквы; каждая из них без 1–2 последних букв окончаний, если
     * остаётся ≥ 4. Человек назван, если хоть одна форма хоть одного слова — его ключ.
     */
    fun forms(word: String): Set<String> {
        var w = norm(word)
        if (LATIN.containsMatchIn(w)) w = latin(w)
        w = clean(w)
        val out = linkedSetOf(w)
        for (e in endings) {
            if (e.isNotEmpty() && w.endsWith(e) && w.length - e.length >= 3) out += w.dropLast(e.length)
        }
        for (f in out.toList()) {
            for (n in 1..2) {
                if (f.length - n >= 4 && f.takeLast(n).all { it in letters }) out += f.dropLast(n)
            }
        }
        return out
    }

    /** Люди, названные в тексте (наговор): id из [people]. */
    fun peopleIn(text: String): Set<String> = found(text, peopleByKey)

    /** Проекты, названные в тексте: id из [projects]. */
    fun projectsIn(text: String): Set<String> = found(text, projectsByKey)

    /**
     * Кого класть в голову разбора: связанные ([related]) ∪ команда (`users[].person_id`) ∪ живые люди копии, которых
     * индекс не знает (заведены после него или с именем из двух букв — ключа у них нет): такие иначе не нашлись бы ни
     * в голове, ни матчером. Слитые — их живая карточка.
     */
    fun keepFor(s: Dela.Snapshot): Set<String> {
        val out = LinkedHashSet<String>()
        for (id in related) s.person(id)?.let { out += it.id }
        for (u in s.users.values) if (u.personId.isNotBlank()) s.person(u.personId)?.let { out += it.id }
        for (p in s.people.values) if (p.live && p.id !in people) out += p.id
        return out
    }

    /** Названные в [text] живые люди копии, которых нет в [keep], — строками в хвост разбора. */
    fun namedOutside(s: Dela.Snapshot, text: String, keep: Set<String>): Set<String> =
        peopleIn(text).mapNotNull { s.person(it)?.takeIf { p -> p.live }?.id }.filter { it !in keep }.toCollection(LinkedHashSet())

    private fun found(text: String, rev: Map<String, Set<String>>): Set<String> {
        if (rev.isEmpty() || text.isBlank()) return emptySet()
        val got = LinkedHashSet<String>()
        for (w in WORD.split(norm(text))) {
            if (w.isEmpty()) continue
            for (f in forms(w)) rev[f]?.let { got += it }
        }
        return got
    }

    /**
     * Латиница — в кириллицу правилом сервера (`people.translit`): «y» на конце после согласной — «iy» («Dmitry» —
     * «дмитрий»), дальше пары по порядку, первая подошедшая в каждой точке.
     */
    private fun latin(s: String): String {
        var x = s
        if (translitYEnd) x = Y_END.replace(x) { it.groupValues[1] + "iy" }
        val out = StringBuilder()
        var i = 0
        while (i < x.length) {
            val hit = translit.firstOrNull { (lat, _) -> lat.isNotEmpty() && x.startsWith(lat, i) }
            if (hit != null) {
                out.append(hit.second)
                i += hit.first.length
            } else {
                out.append(x[i])
                i++
            }
        }
        return out.toString()
    }

    companion object {
        /** Номер правила матчера, которое знает этот код (`focus.RULE` сервера). */
        const val RULE = 1
        /** Путь вида на сервере и ключ кэша видов Дел (`DelaStore.viewsFlow`). */
        const val PATH = "context_index"
        /** Флаг сервера (`features` синка): без него вида нет, и телефон его не спрашивает. */
        const val FEATURE = "context_index"
        /** Свежее шести часов — берётся из кэша как есть; старше — берётся и обновляется фоном. */
        const val FRESH_MS = 6 * 3_600_000L
        /**
         * Старше трёх суток — не берётся вовсе: связанные и ключи новых людей могли уйти далеко, а голова без человека
         * хуже прежней длинной (тогда каталог как был, вид обновится фоном).
         */
        const val STALE_MS = 3 * 24 * 3_600_000L

        private val WORD = Regex("[^0-9a-zа-я]+")
        private val LATIN = Regex("[a-z]")
        // Как `(?<=[bcdfghklmnprstvz])y\b` сервера: слово текста — уже одно слово из букв и цифр, «конец слова» — его конец
        // или не-буква после «y».
        private val Y_END = Regex("([bcdfghklmnprstvz])y(?![0-9a-zа-я])")

        /** Как `people.norm`: нижний регистр, ё → е, пробелы схлопнуты. */
        fun norm(s: String): String = s.replace('ё', 'е').replace('Ё', 'Е').lowercase().replace(Regex("\\s+"), " ").trim()

        private fun clean(s: String): String = s.replace("ь", "").replace("ъ", "")

        private fun reverse(index: Map<String, List<String>>): Map<String, Set<String>> {
            val out = HashMap<String, MutableSet<String>>()
            for ((id, keys) in index) for (k in keys) out.getOrPut(k) { LinkedHashSet() } += id
            return out
        }

        private fun keysOf(o: JSONObject?): Map<String, List<String>> {
            o ?: return emptyMap()
            val out = LinkedHashMap<String, List<String>>()
            for (id in o.keys()) {
                val a = o.optJSONArray(id) ?: continue
                out[id] = (0 until a.length()).mapNotNull { i -> a.optString(i).trim().takeIf { it.isNotEmpty() } }
            }
            return out
        }

        /**
         * Ответ вида — в индекс. null — не наш: другое правило матчера, нет ключей или таблиц (старый сервер ответил бы
         * «нет такого вида» ещё раньше). Тогда разбор берёт каталог как был.
         */
        fun parse(o: JSONObject?): ContextIndex? {
            o ?: return null
            if (o.optInt("rule", 0) != RULE) return null
            val people = o.optJSONObject("people") ?: return null
            val endings = o.optJSONArray("endings") ?: return null
            val tr = o.optJSONArray("translit")
            return ContextIndex(
                rule = RULE,
                days = o.optInt("days", 90),
                related = o.optJSONArray("related")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.toSet() }
                    ?: emptySet(),
                people = keysOf(people),
                projects = keysOf(o.optJSONObject("projects")),
                endings = (0 until endings.length()).map { endings.optString(it) }.filter { it.isNotEmpty() },
                letters = o.optString("letters").toSet(),
                translit = if (tr == null) emptyList() else (0 until tr.length()).mapNotNull { i ->
                    val p = tr.optJSONArray(i) ?: return@mapNotNull null
                    val lat = p.optString(0)
                    if (lat.isEmpty()) null else lat to p.optString(1)
                },
                translitYEnd = o.optBoolean("translit_y_end", true),
            )
        }

        /**
         * Индекс из кэша видов: ответ не старше [STALE_MS] и того правила; иначе null. [stale] у кэша — пора обновить
         * (старше [FRESH_MS] или его нет).
         */
        fun usable(cachedAt: Long, nowMs: Long): Boolean = cachedAt > 0 && nowMs - cachedAt in 0 until STALE_MS

        fun stale(cachedAt: Long?, nowMs: Long): Boolean = cachedAt == null || nowMs - cachedAt !in 0 until FRESH_MS
    }
}
