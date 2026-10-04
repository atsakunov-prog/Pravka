package ru.zf.slushalka.ask

import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.text.BookText

/**
 * Ссылка из разбора в книгу. [chapter] - глава с единицы, как в BookText;
 * [pos] - знак в нашем тексте (UTF-16, как место чтения и разметка); [quote]
 * - цитата, если сервер нашёл место по ней: тогда [pos] - её начало, то есть
 * «страница». Без цитаты [pos] - начало главы.
 */
data class RazborRef(val chapter: Int, val pos: Int, val quote: String) {

    /**
     * Знак в этом тексте. Разбор сделан к тому же тексту (см. [Razbor.fit]),
     * но разборщик сервера и наш могли разойтись на пару знаков у края главы:
     * знак вне своей главы - значит, промах, и надёжнее начало главы.
     */
    fun at(text: BookText): Int {
        val ch = text.chapters.getOrNull(chapter - 1)
        if (ch != null && (pos < ch.start || pos >= ch.end)) return ch.start
        return pos.coerceIn(0, (text.length - 1).coerceAtLeast(0))
    }

    companion object {
        /** Ссылка из записи разбора; нет ни главы, ни знака - запись ни к чему не привязана. */
        fun of(o: JSONObject): RazborRef? {
            if (!o.has("c") && !o.has("pos")) return null
            return RazborRef(
                chapter = o.optInt("c", 1).coerceAtLeast(1),
                pos = o.optInt("pos", 0).coerceAtLeast(0),
                quote = o.optString("q").trim(),
            )
        }
    }
}

/** Карточка книги: жанр, для кого, эпоха, серия и абзац «о чём». [lens] - по нему сервер выбирал линзы. */
data class RazborCard(
    val genre: String,
    val lens: String,
    val age: String,
    val series: String,
    val era: String,
    val about: String,
)

/** Глава разбора: что в ней ([summary]) и что было до неё ([before], у первой пусто). */
data class RazborChapter(val chapter: Int, val title: String, val summary: String, val before: String, val pos: Int)

/** Идея книги: суть, почему неочевидна, вопрос для размышления и места, где она видна. */
data class RazborIdea(
    val title: String,
    val idea: String,
    val why: String,
    val question: String,
    val links: List<RazborRef>,
)

/**
 * Запись линзы, приведённая к одному виду: у «Миф и факт» и «Правителей»
 * поля разные, а листу нужно одно - заголовок, текст, подписанные строки и
 * вердикт. [ref] null - запись ни к какому месту не привязана («Тем временем
 * в мире», «Что напечатать») и спойлером быть не может.
 */
data class LensItem(
    val ref: RazborRef?,
    val title: String,
    val text: String,
    val lines: List<Pair<String, String>> = emptyList(),
    val verdict: String = "",
)

/** Линза разбора: свой угол зрения на книгу - деньги, наука, разговор с детьми. */
data class Lens(val key: String, val title: String, val items: List<LensItem>) {
    /**
     * «Что может напугать» смотрит вперёд нарочно: родителю надо знать
     * заранее, а не после страшной главы. Такие записи не прячутся барьером,
     * а сворачиваются - видно, в какой главе, а что там, - по тапу.
     */
    val warnsAhead: Boolean get() = key == SCARY

    companion object {
        const val SCARY = "kids.scary"
    }
}

/**
 * Разбор книги сервером (`слушалка-разбор.json` в папке книги): всё, чего нет
 * в справочнике, - карточка, «что было раньше» по главам, книга за 15 минут
 * (текстом и звуком), идеи, линзы по жанру. Справочник сервер кладёт рядом
 * своим файлом в формате справочника - его читает GuideEngine.
 *
 * Делает его Опус на домашнем ПК по книге целиком, текст - нашим же разбором
 * (`slushalka-text.jar`), поэтому главы и знаки в ссылках - наши.
 */
data class Razbor(
    val version: Int,
    val created: Long,
    val model: String,
    val usd: Double,
    val card: RazborCard,
    val chapters: List<RazborChapter>,
    /** Изложение для слуха, около двух тысяч слов. */
    val summary15: String,
    /** Озвученное изложение - путь от корня облака; пусто - не озвучено. */
    val audio: String,
    val ideas: List<RazborIdea>,
    val lenses: List<Lens>,
    /** К какому тексту сделан: так же, как у справочника. */
    val fit: GuideEngine.Fit,
) {
    fun chapter(n: Int): RazborChapter? = chapters.firstOrNull { it.chapter == n }

    val isEmpty: Boolean
        get() = card.about.isBlank() && chapters.isEmpty() && summary15.isBlank() && ideas.isEmpty() && lenses.isEmpty()

    companion object {

        /**
         * Линзы, которые лист умеет показать, - в этом порядке и под этими
         * именами. Незнакомые ключи пропускаются: сервер может завести новую
         * линзу раньше, чем приложение научится её рисовать.
         */
        private val LENSES = listOf(
            "finance" to "Деньги и экономика",
            "myths" to "Миф и факт",
            "world" to "Тем временем в мире",
            "rulers" to "Правители",
            "science" to "Наука: правда или выдумка",
            "print" to "Что напечатать",
            "kids.questions" to "Для разговора с детьми",
            "kids.words" to "Трудные слова",
            Lens.SCARY to "Что может напугать",
        )

        /** null - не JSON или пустой разбор. */
        fun parse(raw: String): Razbor? = runCatching {
            val o = JSONObject(raw)
            val card = o.optJSONObject("card") ?: JSONObject()
            val summary = o.optJSONObject("summary15")
            val lensesJson = o.optJSONObject("lenses") ?: JSONObject()
            Razbor(
                version = o.optInt("версия", 1),
                created = o.optLong("created"),
                model = o.optString("model"),
                usd = o.optDouble("usd", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                card = RazborCard(
                    genre = card.str("genre"),
                    lens = card.str("lens"),
                    age = card.str("age"),
                    series = card.str("series"),
                    era = card.str("era"),
                    about = card.str("about"),
                ),
                chapters = objects(o.optJSONArray("chapters")).mapNotNull { c ->
                    val n = c.optInt("c", -1)
                    if (n < 1) return@mapNotNull null
                    RazborChapter(n, c.str("title"), c.str("summary"), c.str("before"), c.optInt("pos", -1))
                }.distinctBy { it.chapter }.sortedBy { it.chapter },
                summary15 = summary?.str("text").orEmpty(),
                audio = summary?.str("audio").orEmpty().trim('/'),
                ideas = objects(o.optJSONArray("ideas")).mapNotNull { i ->
                    val idea = i.str("idea")
                    val title = i.str("title")
                    if (idea.isBlank() && title.isBlank()) return@mapNotNull null
                    RazborIdea(
                        title = title,
                        idea = idea,
                        why = i.str("why"),
                        question = i.str("question"),
                        links = objects(i.optJSONArray("links")).mapNotNull { RazborRef.of(it) },
                    )
                },
                lenses = LENSES.mapNotNull { (key, title) ->
                    val arr = if (key.startsWith("kids.")) {
                        lensesJson.optJSONObject("kids")?.optJSONArray(key.removePrefix("kids."))
                    } else lensesJson.optJSONArray(key)
                    val items = objects(arr).mapNotNull { item(key, it) }
                    if (items.isEmpty()) null else Lens(key, title, items)
                },
                fit = GuideEngine.Fit(o.optInt("chars", -1), o.optInt("главы", -1), o.optString("text")),
            ).takeUnless { it.isEmpty }
        }.getOrNull()

        /** Запись линзы в общий вид; пустая (без главного поля) - пропускается. */
        private fun item(key: String, o: JSONObject): LensItem? {
            val ref = RazborRef.of(o)
            fun lines(vararg pairs: Pair<String, String>) = pairs.filter { it.second.isNotBlank() }
            return when (key) {
                "finance" -> LensItem(ref, o.str("topic"), o.str("text"), lines("Урок" to o.str("lesson")))
                "myths" -> LensItem(ref, "", "", lines("Принято считать" to o.str("common"), "У автора" to o.str("author")))
                "world" -> LensItem(null, o.str("when"), o.str("event"))
                "rulers" -> LensItem(
                    null,
                    o.str("name") + o.str("years").let { if (it.isBlank()) "" else " ($it)" },
                    o.str("relation"),
                )
                "science" -> LensItem(ref, o.str("claim"), "", lines("На деле" to o.str("reality")), o.str("verdict"))
                "print" -> LensItem(null, o.str("object"), o.str("why"))
                "kids.questions" -> LensItem(ref, "", o.str("question"))
                "kids.words" -> LensItem(ref, o.str("word"), o.str("explanation"))
                Lens.SCARY -> LensItem(ref, "", o.str("what"))
                else -> null
            }?.takeIf { it.title.isNotBlank() || it.text.isNotBlank() || it.lines.isNotEmpty() }
        }

        private fun JSONObject.str(key: String): String =
            if (isNull(key)) "" else optString(key).trim()

        private fun objects(arr: JSONArray?): List<JSONObject> =
            (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
    }
}
