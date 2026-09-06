package ru.zf.slushalka.ask

import ru.zf.slushalka.data.Ask
import ru.zf.slushalka.data.AskLog
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.Alignment
import ru.zf.slushalka.text.BookText
import ru.zf.slushalka.ui.formatClock

/**
 * Вопрос по книге: где мы в тексте, что показать модели и во что это обойдётся.
 */
class AskEngine(
    private val settings: Settings,
    private val client: ClaudeClient,
    private val askLog: AskLog,
) {

    /**
     * Сколько книги показать модели. Ползунок в окне вопроса: от трёх страниц
     * до всей книги с начала. Страницы - для «что сейчас происходит», главы -
     * для «кто этот человек», вся книга - для героя из первой главы.
     */
    enum class Scope(val label: String, val short: String, val pages: Int, val chapters: Int) {
        PAGES_3("последние 3 страницы", "3 стр.", 3, 0),
        PAGES_5("последние 5 страниц", "5 стр.", 5, 0),
        PAGES_10("последние 10 страниц", "10 стр.", 10, 0),
        CHAPTER("текущая глава", "глава", 0, 1),
        TWO_CHAPTERS("две последние главы", "2 главы", 0, 2),
        WHOLE("вся книга до этого места", "вся книга", 0, Int.MAX_VALUE);

        companion object {
            fun of(name: String): Scope = entries.firstOrNull { it.name == name } ?: PAGES_5
        }
    }

    /** Одна реплика разговора в окне вопроса: что показали человеку, что ушло модели, что вернулось. */
    data class Turn(
        val shown: String,
        val prompt: String,
        val answer: String,
        val costUsd: Double,
        /** Ответ упёрся в потолок длины - человеку стоит об этом сказать. */
        val truncated: Boolean = false,
    )

    /**
     * Готовый контекст вопроса - его же показываем в окошке «что уедет».
     *
     * Текст лежит двумя блоками. [stable] - главы до текущей: пока читаешь
     * главу, он не меняется, и кэш на него отрабатывает по-настоящему.
     * [recent] - текущая глава до места (или просто последние страницы).
     */
    data class Ctx(
        val stable: String,
        val recent: String,
        val chapter: String,
        val percent: Int,
        val cutoffChar: Int,
        val elapsed: String,
        val scope: Scope,
    ) {
        val chars: Int get() = stable.length + recent.length
        val pages: Int get() = (chars + Settings.PAGE_CHARS / 2) / Settings.PAGE_CHARS

        /** Прикидка токенов: ~2.5 знака на токен русского текста плюс правила. */
        val tokens: Int get() = (chars / 2.5).toInt() + RULES_TOKENS

        /**
         * Во что обойдётся первый вопрос. С кэшем текст пишется в кэш на час -
         * это вдвое дороже обычного входа; окупается со второго-третьего вопроса.
         */
        fun estFirstUsd(model: String, cache: Boolean): Double =
            if (cache) ClaudeClient.costUsd(model, QUESTION_TOKENS, ANSWER_TOKENS, cacheWriteTokens = tokens)
            else ClaudeClient.costUsd(model, tokens + QUESTION_TOKENS, ANSWER_TOKENS)

        /** Во что обойдётся каждый следующий вопрос в том же разговоре. */
        fun estNextUsd(model: String, cache: Boolean): Double =
            if (cache) ClaudeClient.costUsd(model, QUESTION_TOKENS * 3, ANSWER_TOKENS, cacheReadTokens = tokens)
            else ClaudeClient.costUsd(model, tokens + QUESTION_TOKENS * 3, ANSWER_TOKENS)
    }

    /**
     * Режет текст по месту слушания.
     *
     * Отрезается не «где мы сейчас», а на [Settings.Prefs.spoilerMarginSec]
     * раньше: привязка приблизительная, и ошибаться она обязана в сторону уже
     * услышанного. Лучше не дорассказать, чем проговориться.
     */
    fun context(book: Book, text: BookText, align: Alignment, absMs: Long, scope: Scope): Ctx {
        val p = settings.now()
        val cutoffMs = (absMs - p.spoilerMarginSec * 1000L).coerceAtLeast(0L)
        return contextAt(book, text, align.charAt(cutoffMs), absMs, scope)
    }

    /**
     * То же, но место задано точкой в тексте. Так спрашивают из читалки: там
     * запас против спойлера не нужен - страница перед глазами, и что прочитано,
     * известно точно.
     */
    fun contextAt(book: Book, text: BookText, cutoffChar: Int, absMs: Long, scope: Scope): Ctx {
        val cutoff = cutoffChar.coerceIn(0, text.length)
        val chapter = text.chapterAt(cutoff)
        val chIndex = text.chapterIndexAt(cutoff)

        var stable = ""
        val recent: String
        if (scope.chapters == 0) {
            recent = text.slice((cutoff - scope.pages * Settings.PAGE_CHARS).coerceAtLeast(0), cutoff)
        } else {
            val chStart = chapter?.start ?: 0
            val first = if (scope.chapters == Int.MAX_VALUE) 0 else (chIndex - (scope.chapters - 1)).coerceAtLeast(0)
            val from = text.chapters.getOrNull(first)?.start ?: 0
            if (from < chStart - 400) stable = text.slice(from, chStart)
            // Глава только началась: одной её строки модели мало - добираем
            // пару страниц из предыдущей, чтобы было о чём говорить.
            val start = if (stable.isEmpty() && cutoff - chStart < Settings.PAGE_CHARS)
                (cutoff - 2 * Settings.PAGE_CHARS).coerceAtLeast(0) else chStart
            recent = text.slice(start, cutoff)
        }
        return Ctx(
            stable = stable,
            recent = recent,
            chapter = chapter?.title.orEmpty(),
            percent = if (text.length > 0) (cutoff * 100 / text.length) else 0,
            cutoffChar = cutoff,
            elapsed = if (book.hasAudio) formatClock(absMs) else "",
            scope = scope,
        )
    }

    /**
     * Вопрос - или уточнение к прежнему ответу: [history] уезжает как разговор.
     *
     * [cache] - держать текст книги в кэше час: первый вопрос дороже, каждый
     * следующий в том же разговоре - копейки. [spoilers] снимает барьер: правила
     * меняются на [Prompts.RULES_SPOILERS], и модель отвечает по всей книге.
     * [quote] - кусок, выделенный в читалке; идёт в реплику, а не в систему.
     */
    suspend fun ask(
        book: Book,
        ctx: Ctx,
        history: List<Turn>,
        question: String,
        quote: String?,
        model: String,
        cache: Boolean,
        spoilers: Boolean,
        absMs: Long,
        onDelta: (String) -> Unit,
    ): Result<Turn> {
        val place = Prompts.place(book.title, book.author, ctx.chapter, ctx.percent, ctx.elapsed)
        val system = buildList {
            add(ClaudeClient.Block(if (spoilers) Prompts.RULES_SPOILERS else Prompts.RULES, cache = cache))
            if (ctx.stable.isNotBlank()) add(ClaudeClient.Block(Prompts.beforeThat(ctx.stable), cache = cache))
            add(ClaudeClient.Block(place + "\n\n" + Prompts.fragment(ctx.recent), cache = cache))
        }
        val prompt = if (quote.isNullOrBlank()) question else Prompts.quoted(quote, question)
        val turns = buildList {
            history.forEach { t ->
                add(ClaudeClient.Turn("user", t.prompt))
                add(ClaudeClient.Turn("assistant", t.answer))
            }
            add(ClaudeClient.Turn("user", prompt))
        }
        val p = settings.now()
        return client.chat(
            model = model,
            system = system,
            turns = turns,
            effort = p.askEffort,
            onDelta = onDelta,
        ).map { reply ->
            askLog.add(
                book.id,
                Ask(
                    at = System.currentTimeMillis(),
                    absMs = absMs,
                    question = question,
                    answer = reply.text,
                    costUsd = reply.costUsd,
                ),
            )
            Turn(shown = question, prompt = prompt, answer = reply.text, costUsd = reply.costUsd, truncated = reply.truncated)
        }
    }

    /** Насколько глубоко напоминать. */
    enum class Depth(val label: String, val chapters: Int) {
        CHAPTER("текущая глава", 1),
        TWO("две главы", 2),
        THREE("три главы", 3),
    }

    /**
     * Границы пересказа: от начала главы, отстоящей на [depth] назад, и до
     * текущего места. По главам, а не по минутам, потому что «что там было»
     * человек помнит именно главами.
     *
     * Длина подрезается сверху: три главы толстого романа - это уже сотня
     * страниц, а пересказ нужен короткий и дешёвый.
     */
    fun recapRange(text: BookText, cutoffChar: Int, depth: Depth): IntRange {
        val here = text.chapterIndexAt(cutoffChar)
        val firstChapter = (here - (depth.chapters - 1)).coerceAtLeast(0)
        val from = text.chapters.getOrNull(firstChapter)?.start ?: 0
        val capped = (cutoffChar - MAX_RECAP_CHARS).coerceAtLeast(0)
        return maxOf(from, capped)..cutoffChar.coerceIn(0, text.length)
    }

    /**
     * «Что там было»: пересказ последних глав. Заводская модель — Сонет
     * (меняется в настройках, раздел «Модели»): он справляется и стоит
     * заметно дешевле Опуса. Спойлеров тут не бывает по построению: дальше
     * текущего места модели просто нечего не показали.
     */
    suspend fun recap(
        book: Book,
        text: BookText,
        range: IntRange,
        absMs: Long,
        onDelta: (String) -> Unit = {},
    ): Result<String> {
        val fragment = text.slice(range.first, range.last)
        if (fragment.length < 400) return Result.success("")
        val chapter = text.chapterAt(range.last)?.title.orEmpty()
        val p = settings.now()
        return client.ask(
            model = p.recapModel,
            system = listOf(
                ClaudeClient.Block(Prompts.RECAP_RULES, cache = true),
                ClaudeClient.Block(
                    Prompts.place(book.title, book.author, chapter, 0, if (book.hasAudio) formatClock(absMs) else "") +
                        "\n\n" + Prompts.fragment(fragment)
                ),
            ),
            question = "Напомни, что было в этом куске.",
            maxTokens = 4000,
            effort = p.recapEffort,
            onDelta = onDelta,
        ).map { it.text }
    }

    fun cancel() = client.cancel()

    private companion object {
        /** Потолок пересказа: тридцать страниц - это уже не «напомни», а чтение заново. */
        const val MAX_RECAP_CHARS = 54_000

        /** Токенов в правилах и служебных строках. */
        const val RULES_TOKENS = 700
        /** Сколько занимает вопрос и сколько - ответ, для прикидки цены. */
        const val QUESTION_TOKENS = 80
        const val ANSWER_TOKENS = 400
    }
}
