package ru.zf.slushalka.ask

import org.json.JSONObject
import ru.zf.slushalka.data.Ask
import ru.zf.slushalka.data.AskLog
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.BookText

/**
 * Поиск по смыслу: «где Анна впервые говорит про Лизл», «где был разговор про
 * балет». Обычный поиск по слову тут бессилен - нужных слов в тексте может и
 * не быть.
 *
 * Владелец попросил не отправлять сразу всю книгу, а сначала искать по
 * справочнику: он в десятки раз короче текста, и главу модель находит по
 * содержанию глав, заметкам о героях и хронологии за центы. Точное место
 * внутри главы ищется потом - сперва даром, по словам-приметам в тексте
 * главы, и только если не нашлось, спрашиваем модель про одну эту главу.
 * Искать по всему прочитанному тексту можно и без справочника, но это
 * отдельная кнопка с ценой: дорого, и пусть это будет видно.
 *
 * Барьер тот же, что у вопросов: справочник - только по дочитанным главам,
 * текст - только до места чтения.
 */
class MeaningSearch(
    private val client: ClaudeClient,
    private val settings: Settings,
    private val askLog: AskLog,
) {
    /** Найденное: глава (с единицы), почему она, приметы для точного места. */
    data class Hit(val chapter: Int, val why: String, val words: String, val charOffset: Int? = null)

    data class Found(val hits: List<Hit>, val costUsd: Double)

    /** Шаг первый: главы по справочнику, урезанному до дочитанного. */
    suspend fun byGuide(book: Book, text: BookText, guide: Guide, upTo: Int, query: String): Result<Found> {
        val p = settings.now()
        val system = listOf(
            ClaudeClient.Block(RULES_GUIDE),
            ClaudeClient.Block(Prompts.bookLine(book.title, book.author) + "\n\n" + guide.asText(upTo)),
        )
        return client.chat(
            model = p.askModel,
            system = system,
            turns = listOf(ClaudeClient.Turn("user", "Что ищет читатель: $query")),
            maxTokens = 1500,
            effort = "low",
        ).map { reply ->
            log(book, query, reply)
            val hits = parseHits(reply.text).filter { it.chapter in 1..upTo.coerceAtLeast(1) }
                .map { h -> h.copy(charOffset = locate(text, h.chapter, h.words)) }
            Found(hits, reply.costUsd)
        }
    }

    /** Во что обойдётся поиск по тексту прочитанного - чтобы цена стояла на кнопке. */
    fun textSearchUsd(text: BookText, cutoff: Int): Double {
        val chars = cutoff.coerceIn(0, TEXT_MAX)
        return ClaudeClient.costUsd(settings.now().askModel, (chars / 2.5).toInt() + 1500, 600)
    }

    /** Без справочника: по тексту до места чтения, сразу с дословными приметами. */
    suspend fun byText(book: Book, text: BookText, cutoff: Int, query: String): Result<Found> {
        val p = settings.now()
        val from = (cutoff - TEXT_MAX).coerceAtLeast(0)
        val body = buildString {
            val chapters = text.chapters.ifEmpty { listOf(ru.zf.slushalka.text.Chapter("", 0, text.length)) }
            chapters.forEachIndexed { i, c ->
                val a = maxOf(c.start, from)
                val b = minOf(c.end, cutoff)
                if (b <= a) return@forEachIndexed
                append(Prompts.chapterMark(i + 1, c.title.ifBlank { "без названия" }))
                append(text.plain, a, b)
            }
        }
        val system = listOf(
            ClaudeClient.Block(RULES_TEXT),
            ClaudeClient.Block(Prompts.bookLine(book.title, book.author) + "\n\n" + body),
        )
        return client.chat(
            model = p.askModel,
            system = system,
            turns = listOf(ClaudeClient.Turn("user", "Что ищет читатель: $query")),
            maxTokens = 1500,
            effort = "low",
        ).map { reply ->
            log(book, query, reply)
            val hits = parseHits(reply.text).mapNotNull { h ->
                val at = findQuote(text.plain, from, cutoff, h.words) ?: return@mapNotNull null
                h.copy(chapter = text.chapterIndexAt(at) + 1, charOffset = at)
            }
            Found(hits, reply.costUsd)
        }
    }

    /**
     * Шаг второй, если приметы не нашлись даром: модель читает одну главу и
     * возвращает дословную цитату, которую уже ищем в тексте сами.
     */
    suspend fun pinpoint(book: Book, text: BookText, hit: Hit, cutoff: Int, query: String): Result<Int?> {
        val c = text.chapters.getOrNull(hit.chapter - 1) ?: return Result.success(null)
        val end = minOf(c.end, maxOf(cutoff, c.start))
        if (end <= c.start) return Result.success(null)
        val p = settings.now()
        return client.chat(
            model = p.askModel,
            system = listOf(
                ClaudeClient.Block(RULES_PINPOINT),
                ClaudeClient.Block(Prompts.bookLine(book.title, book.author) + "\n\n" + text.plain.substring(c.start, end)),
            ),
            turns = listOf(ClaudeClient.Turn("user", "Что ищет читатель: $query\nПочему эта глава: ${hit.why}")),
            maxTokens = 400,
            effort = "low",
        ).map { reply ->
            log(book, query, reply)
            val quote = reply.text.trim().trim('«', '»', '"', ' ')
            if (quote.equals("НЕТ", ignoreCase = true)) null else findQuote(text.plain, c.start, end, quote)
        }
    }

    private fun log(book: Book, query: String, reply: ClaudeClient.Reply) {
        // В общий счёт расходов: поиск - тоже вопрос к модели.
        askLog.add(
            book.id,
            Ask(at = System.currentTimeMillis(), absMs = 0, question = "Поиск: $query", answer = reply.text, costUsd = reply.costUsd),
        )
    }

    companion object {
        /** Сколько прочитанного текста уезжает в поиск без справочника: последние ~240 тысяч токенов. */
        private const val TEXT_MAX = 600_000

        private val RULES_GUIDE = """
            Ты помогаешь читателю найти место в книге по смыслу. У тебя нет текста -
            только справочник по главам, которые он уже прочёл: краткое содержание
            глав, герои с заметками по главам и связями, места, словарь, хронология.

            Найди главы, где, судя по справочнику, есть то, что он ищет. Не больше
            трёх, самая вероятная первой. Если ничего похожего нет - пустой список.
            Не выдумывай: только то, что следует из справочника.

            Верни ТОЛЬКО JSON: {"hits": [{"chapter": 3, "why": "...", "words": "..."}]}
            chapter - номер главы из справочника; why - одна фраза, что там; words -
            три-шесть слов, которые почти наверняка стоят в тексте рядом с этим
            местом: имена, редкие слова, предметы. Через пробел, без знаков.
        """.trimIndent()

        private val RULES_TEXT = """
            Ты помогаешь читателю найти место в книге по смыслу. Тебе дают текст
            книги до места, где он сейчас, с пометками «=== ГЛАВА N ===».

            Найди места, где есть то, что он ищет. Не больше трёх, самое вероятное
            первым. Если ничего похожего нет - пустой список.

            Верни ТОЛЬКО JSON: {"hits": [{"chapter": 3, "why": "...", "words": "..."}]}
            why - одна фраза, что там; words - ДОСЛОВНАЯ цитата из текста, шесть-
            пятнадцать слов подряд, ровно как в книге: по ней место будут искать.
        """.trimIndent()

        private val RULES_PINPOINT = """
            Тебе дают текст одной главы книги и что ищет читатель. Найди это место
            и верни ТОЛЬКО дословную цитату из текста - шесть-пятнадцать слов подряд,
            ровно как в книге, без кавычек и пояснений. Если места в главе нет -
            верни одно слово НЕТ.
        """.trimIndent()

        internal fun parseHits(raw: String): List<Hit> {
            val a = raw.indexOf('{')
            val b = raw.lastIndexOf('}')
            if (a < 0 || b <= a) return emptyList()
            val arr = runCatching { JSONObject(raw.substring(a, b + 1)).optJSONArray("hits") }.getOrNull()
                ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Hit(
                    chapter = o.optInt("chapter", 0),
                    why = o.optString("why").trim(),
                    words = o.optString("words").trim(),
                )
            }.filter { it.why.isNotBlank() || it.words.isNotBlank() }
        }

        /**
         * Даром - по приметам: в главе ищется окно в полтысячи знаков, где
         * сходится больше всего разных слов-примет. Двух совпадений хватает,
         * одного - нет: одно имя встречается в главе на каждой странице.
         */
        internal fun locate(text: BookText, chapter: Int, words: String): Int? {
            val c = text.chapters.getOrNull(chapter - 1) ?: return null
            val region = text.plain.substring(c.start, c.end).lowercase().replace('ё', 'е')
            val keys = words.lowercase().replace('ё', 'е')
                .split(' ', ',', '.', ';', ':', '-', '—')
                .map { it.trim() }
                // Корень без окончания: «Лизл» и «Лизлом» - одно и то же слово.
                .filter { it.length >= 4 }
                .map { if (it.length > 6) it.dropLast(2) else it }
                .distinct()
            if (keys.size < 2) return null
            val hits = keys.flatMap { k ->
                val out = ArrayList<Pair<Int, String>>()
                var i = region.indexOf(k)
                while (i >= 0 && out.size < 400) { out += i to k; i = region.indexOf(k, i + 1) }
                out
            }.sortedBy { it.first }
            var best = -1
            var bestCount = 1
            var j = 0
            for (i in hits.indices) {
                while (hits[i].first - hits[j].first > WINDOW) j++
                val distinct = hits.subList(j, i + 1).mapTo(HashSet()) { it.second }.size
                if (distinct > bestCount) {
                    bestCount = distinct
                    best = hits[j].first
                }
            }
            return if (best >= 0) c.start + best else null
        }

        private const val WINDOW = 500

        /**
         * Дословная цитата в тексте - без учёта регистра, «ё», кавычек, тире и
         * лишних пробелов: модель переписывает их по-своему. Не нашлась целиком -
         * ищем по первым шести словам.
         */
        internal fun findQuote(plain: String, from: Int, to: Int, quote: String): Int? {
            if (quote.isBlank()) return null
            val a = from.coerceIn(0, plain.length)
            val b = to.coerceIn(a, plain.length)
            val norm = StringBuilder()
            val map = ArrayList<Int>()
            var space = true
            for (i in a until b) {
                val ch = plain[i].lowercaseChar().let { if (it == 'ё') 'е' else it }
                if (ch.isLetterOrDigit()) {
                    norm.append(ch); map.add(i); space = false
                } else if (!space) {
                    norm.append(' '); map.add(i); space = true
                }
            }
            fun normOf(s: String) = s.lowercase().replace('ё', 'е')
                .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
                .split(' ').filter { it.isNotBlank() }
            val words = normOf(quote)
            if (words.isEmpty()) return null
            val hay = norm.toString()
            for (n in listOf(words.size, 6, 4)) {
                if (n > words.size) continue
                val needle = words.take(n).joinToString(" ")
                val i = hay.indexOf(needle)
                if (i >= 0) return map[i]
            }
            return null
        }
    }
}

/**
 * Справочник текстом для промпта: главы, герои с заметками и связями, места,
 * словарь, хронология - только до главы [upTo] включительно.
 */
fun Guide.asText(upTo: Int): String = buildString {
    append("СОДЕРЖАНИЕ ГЛАВ\n")
    chapters.filter { it.chapter <= upTo }.forEach {
        append("Глава ").append(it.chapter)
        if (it.title.isNotBlank()) append(" («").append(it.title).append("»)")
        append(": ").append(it.summary.replace("\n\n", " ")).append('\n')
    }
    fun section(name: String, list: List<GuideEntry>) {
        val vis = list.mapNotNull { it.visibleAt(upTo) }
        if (vis.isEmpty()) return
        append('\n').append(name).append('\n')
        vis.forEach { e ->
            append("- ").append(e.name)
            if (e.aliases.isNotEmpty()) append(" (").append(e.aliases.joinToString(", ")).append(")")
            append(", с гл. ").append(e.chapter).append(": ").append(e.role).append('\n')
            e.links.forEach { append("  связь: ").append(it.kind).append(" - ").append(it.to).append(" (гл. ").append(it.chapter).append(")\n") }
            e.notes.forEach { append("  гл. ").append(it.chapter).append(": ").append(it.text.replace("\n\n", " ")).append('\n') }
        }
    }
    section("ГЕРОИ", characters)
    section("МЕСТА", places)
    section("СЛОВАРЬ", terms)
    val ev = events.filter { it.chapter <= upTo }
    if (ev.isNotEmpty()) {
        append("\nХРОНОЛОГИЯ\n")
        ev.forEach {
            append("- гл. ").append(it.chapter)
            if (it.whenText.isNotBlank()) append(", ").append(it.whenText)
            append(": ").append(it.text).append('\n')
        }
    }
}
