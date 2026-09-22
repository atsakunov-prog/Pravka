package ru.zf.slushalka.ask

import org.json.JSONObject
import ru.zf.slushalka.data.Ask
import ru.zf.slushalka.data.AskLog
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.BookText

/**
 * Разговор о книге: Claude предлагает темы, дальше говорим как два читателя.
 *
 * Дочитанную книгу обсуждают без барьера - спойлеров больше нет, можно про
 * финал и про то, зачем автор так сделал. Недочитанную - как книжный клуб
 * на полпути: только прочитанное, с теми же правилами, что у вопросов.
 *
 * Контекст - не вся книга: справочник (главы, герои, связи, хронология) и
 * кусок текста у места чтения - финал, если дочитано. Этого хватает, чтобы
 * говорить по существу, и стоит в десятки раз меньше романа целиком. Контекст
 * держится в кэше час: разговор из десятка реплик платит за него один раз.
 */
class BookTalk(
    private val client: ClaudeClient,
    private val settings: Settings,
    private val askLog: AskLog,
) {
    data class Topic(val title: String, val opener: String)

    /** Реплика: «user» или «assistant». */
    data class Line(val role: String, val text: String, val costUsd: Double = 0.0)

    class Ctx(val blocks: List<ClaudeClient.Block>, val finished: Boolean, val chapter: Int)

    fun context(book: Book, text: BookText, guide: Guide?, cutoff: Int, finished: Boolean): Ctx {
        // Дочитано - видно всё; нет - только дочитанные главы, текущая не в счёт.
        val upTo = if (finished) Int.MAX_VALUE else text.chapterIndexAt(cutoff)
        val end = if (finished) text.length else cutoff.coerceIn(0, text.length)
        val excerpt = text.slice((end - EXCERPT).coerceAtLeast(0), end)
        val blocks = buildList {
            add(ClaudeClient.Block(if (finished) RULES_DONE else RULES_READING, cache = true))
            val about = Prompts.bookLine(book.title, book.author) +
                if (finished) "\nЧитатель дочитал книгу до конца." else
                    "\nЧитатель прочёл глав: ${text.chapterIndexAt(cutoff)} из ${text.chapters.size}."
            val g = guide?.asText(upTo)
            add(ClaudeClient.Block(about + (if (g != null) "\n\nСПРАВОЧНИК ПО КНИГЕ\n$g" else ""), cache = true))
            add(
                ClaudeClient.Block(
                    (if (finished) "ФИНАЛ КНИГИ (последние страницы)\n\n" else "ТЕКСТ ПЕРЕД МЕСТОМ, ГДЕ ОН СЕЙЧАС\n\n") + excerpt,
                    cache = true,
                )
            )
        }
        return Ctx(blocks, finished, upTo)
    }

    /** Темы для разговора - пять, коротким списком, с первой репликой Claude к каждой. */
    suspend fun topics(book: Book, ctx: Ctx): Result<List<Topic>> {
        val p = settings.now()
        return client.chat(
            model = p.askModel,
            system = ctx.blocks,
            turns = listOf(ClaudeClient.Turn("user", TOPICS_ASK)),
            maxTokens = 1500,
            effort = "low",
        ).map { reply ->
            log(book, "Темы для разговора", reply)
            parseTopics(reply.text)
        }
    }

    /** Следующая реплика разговора. [history] - всё сказанное, начиная с первой реплики читателя. */
    suspend fun say(
        book: Book,
        ctx: Ctx,
        history: List<Line>,
        message: String,
        onDelta: (String) -> Unit,
    ): Result<Line> {
        val p = settings.now()
        val turns = history.map { ClaudeClient.Turn(it.role, it.text) } + ClaudeClient.Turn("user", message)
        return client.chat(
            model = p.askModel,
            system = ctx.blocks,
            turns = turns,
            effort = p.askEffort,
            onDelta = onDelta,
        ).map { reply ->
            log(book, message, reply)
            Line("assistant", reply.text, reply.costUsd)
        }
    }

    private fun log(book: Book, question: String, reply: ClaudeClient.Reply) {
        askLog.add(
            book.id,
            Ask(at = System.currentTimeMillis(), absMs = 0, question = "Разговор: $question", answer = reply.text, costUsd = reply.costUsd),
        )
    }

    companion object {
        /** Кусок текста у места чтения (или финал): около двадцати страниц. */
        private const val EXCERPT = 40_000

        private val COMMON = """
            Ты - начитанный собеседник, с которым читатель обсуждает книгу, как два
            друга после книжного клуба. Не лектор и не учитель литературы: у тебя
            есть своё мнение, ты споришь, соглашаешься, задаёшь встречные вопросы,
            замечаешь то, что читатель мог пропустить. Не льсти и не поддакивай.

            Тебе дают справочник по книге и кусок текста. Опирайся на них; знания о
            мире, эпохе, авторе и других его книгах использовать можно.

            Живой русский язык, без разметки - ни звёздочек, ни решёток, ни списков
            с маркерами: ответ могут читать вслух. Абзацы - через пустую строку.
            Обычно три-шесть предложений и вопрос к собеседнику в конце, если он к
            месту; длиннее - когда разговор того требует.
        """.trimIndent()

        private val RULES_DONE = COMMON + "\n\n" + """
            Книга дочитана: спойлеров больше нет. Можно про финал, про то, чем всё
            кончилось для каждого героя, про замысел автора и про то, что осталось
            недосказанным. Если ты знаешь книгу по памяти - пользуйся, но не
            выдумывай того, чего в ней нет.
        """.trimIndent()

        private val RULES_READING = COMMON + "\n\n" + """
            Книга НЕ дочитана. Железное правило: никаких спойлеров - ни прямо, ни
            намёком, ни «позже увидишь». Если знаешь книгу по памяти - забудь всё,
            что дальше присланного. Обсуждайте прочитанное: героев, их поступки,
            догадки читателя - но не подтверждай и не опровергай догадки о будущем.
        """.trimIndent()

        private val TOPICS_ASK = """
            Предложи пять тем для разговора об этой книге - разных: о героях, о
            поступке, который можно судить по-разному, о замысле автора, о том,
            что откликается сегодня, о сцене, которая держит книгу. К каждой - свою
            первую реплику: живую мысль и вопрос читателю, два-три предложения.

            Верни ТОЛЬКО JSON: {"topics": [{"title": "до восьми слов", "opener": "..."}]}
        """.trimIndent()

        internal fun parseTopics(raw: String): List<Topic> {
            val a = raw.indexOf('{')
            val b = raw.lastIndexOf('}')
            if (a < 0 || b <= a) return emptyList()
            val arr = runCatching { JSONObject(raw.substring(a, b + 1)).optJSONArray("topics") }.getOrNull()
                ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").trim()
                val opener = o.optString("opener").trim()
                if (title.isEmpty() || opener.isEmpty()) null else Topic(title, opener)
            }
        }
    }
}
