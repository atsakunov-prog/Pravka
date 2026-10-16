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

/** Цитата из разбора v3: сама цитата, чем хороша и где она - [ref] по её первым словам. */
data class RazborQuote(val ref: RazborRef?, val text: String, val why: String)

/**
 * «Что читать дальше» (разбор v3): [library] - папки `Книги/` домашней
 * библиотеки (сервер сверил, что они есть) с «почему»; [outside] - книги вне
 * её, «Автор — Название» и «почему»: их можно заказать на Флибусте файлом
 * заказа сервера.
 */
data class RazborNext(val library: List<Pair<String, String>>, val outside: List<Triple<String, String, String>>) {
    val isEmpty: Boolean get() = library.isEmpty() && outside.isEmpty()
}

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
    /**
     * «Книга за 30 и за 60 минут» (v3, 17.10.2026, владелец: «выбирать, сколько
     * читать книгу. 15-30-60»): только текст, звука у них нет. У короткой книги
     * сервер их не делает - часовой пересказ двухчасовой пьесы бессмыслен.
     */
    val summary30: String = "",
    val summary60: String = "",
    val quotes: List<RazborQuote> = emptyList(),
    val next: RazborNext = RazborNext(emptyList(), emptyList()),
) {
    fun chapter(n: Int): RazborChapter? = chapters.firstOrNull { it.chapter == n }

    /** Разбор до v3: заказ ещё раз посчитает только новое (30/60, цитаты, «дальше», линзы) - кнопка «Дополнить». */
    val isOld: Boolean get() = version < VERSION

    /** Пересказы, какие есть: минуты и текст. */
    val summaries: List<Pair<Int, String>>
        get() = listOf(15 to summary15, 30 to summary30, 60 to summary60).filter { it.second.isNotBlank() }

    val isEmpty: Boolean
        get() = card.about.isBlank() && chapters.isEmpty() && summary15.isBlank() && ideas.isEmpty() && lenses.isEmpty()

    companion object {

        /** Версия разбора сервера, которую приложение показывает целиком. */
        const val VERSION = 3

        /**
         * Линзы, которые лист умеет показать, - в этом порядке и под этими
         * именами. Незнакомые ключи пропускаются: сервер может завести новую
         * линзу раньше, чем приложение научится её рисовать.
         */
        private val LENSES = listOf(
            "evenings" to "Вечера: читать вслух",
            "finance" to "Деньги и экономика",
            "myths" to "Миф и факт",
            "world" to "Тем временем в мире",
            "rulers" to "Правители",
            "science" to "Наука: правда или выдумка",
            "print" to "Что напечатать",
            "kids.questions" to "Для разговора с детьми",
            "kids.words" to "Трудные слова",
            Lens.SCARY to "Что может напугать",
            // v3, 17.10.2026: нон-фикшн, биографии, романы
            "practice" to "Как применить",
            "check" to "Проверка на прочность",
            "forks" to "Развилки",
            "themes" to "Темы",
            "discuss" to "Вопросы для разговора",
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
                summary30 = o.optJSONObject("summary30")?.str("text").orEmpty(),
                summary60 = o.optJSONObject("summary60")?.str("text").orEmpty(),
                quotes = objects(o.optJSONArray("quotes")).mapNotNull { q ->
                    q.str("text").takeIf { it.isNotBlank() }?.let { RazborQuote(RazborRef.of(q), it, q.str("why")) }
                },
                next = o.optJSONObject("next").let { n ->
                    RazborNext(
                        library = objects(n?.optJSONArray("library")).mapNotNull { x ->
                            x.str("folder").takeIf { it.isNotBlank() }?.let { it to x.str("why") }
                        },
                        outside = objects(n?.optJSONArray("outside")).mapNotNull { x ->
                            x.str("title").takeIf { it.isNotBlank() }?.let { Triple(x.str("author"), it, x.str("why")) }
                        },
                    )
                },
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
                // Вечер начинается с [ref] - «К месту» и есть «начать отсюда»; минуты вслух считает сервер.
                "evenings" -> LensItem(
                    ref, o.str("title"), "",
                    lines(
                        "Вслух" to o.optInt("minutes", 0).let { if (it > 0) "около $it мин" else "" },
                        "На завтра" to o.str("teaser"),
                        "Вопрос" to o.str("question"),
                    ),
                )
                "practice" -> LensItem(
                    ref, o.str("title"),
                    strings(o.optJSONArray("steps")).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
                    lines("Когда помогает" to o.str("when"), "Когда нет" to o.str("limits")),
                )
                "check" -> LensItem(ref, o.str("claim"), o.str("evidence"), verdict = o.str("verdict"))
                "forks" -> LensItem(
                    ref, o.str("when"), o.str("situation"),
                    lines("Выбор" to o.str("choice"), "Итог" to o.str("outcome"), "Себе" to o.str("lesson")),
                )
                "themes" -> LensItem(ref, o.str("theme"), o.str("text"))
                "discuss" -> LensItem(ref, "", o.str("question"))
                else -> null
            }?.takeIf { it.title.isNotBlank() || it.text.isNotBlank() || it.lines.isNotEmpty() }
        }

        private fun JSONObject.str(key: String): String =
            if (isNull(key)) "" else optString(key).trim()

        private fun objects(arr: JSONArray?): List<JSONObject> =
            (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }

        /** Строки массива (шаги «Как применить»), пустые - мимо. */
        private fun strings(arr: JSONArray?): List<String> =
            (0 until (arr?.length() ?: 0)).map { arr!!.optString(it).trim() }.filter { it.isNotBlank() }
    }
}
