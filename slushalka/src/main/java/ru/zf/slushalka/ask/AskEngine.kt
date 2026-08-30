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

    /** Готовый контекст вопроса - его же показываем в окошке «что уедет». */
    data class Ctx(
        val fragment: String,
        /** Книга с начала и до текущей главы: уезжает только в режиме «вся книга». */
        val prefix: String,
        val chapter: String,
        val percent: Int,
        val cutoffChar: Int,
        val elapsed: String,
    ) {
        val pages: Int get() = (fragment.length + prefix.length) / Settings.PAGE_CHARS
        /** Прикидка расхода: ~2.5 знака на токен русского текста. */
        val estUsd: Double
            get() {
                val tokens = (fragment.length + prefix.length) / 2.5
                return ClaudeClient.costUsd(Settings.MODEL_OPUS, tokens.toInt(), 500)
            }
    }

    /**
     * Режет текст по месту слушания.
     *
     * Отрезается не «где мы сейчас», а на [Settings.Prefs.spoilerMarginSec]
     * раньше: привязка приблизительная, и ошибаться она обязана в сторону уже
     * услышанного. Лучше не дорассказать, чем проговориться.
     */
    fun context(book: Book, text: BookText, align: Alignment, absMs: Long): Ctx {
        val p = settings.now()
        val cutoffMs = (absMs - p.spoilerMarginSec * 1000L).coerceAtLeast(0L)
        return contextAt(book, text, align.charAt(cutoffMs), absMs)
    }

    /**
     * То же, но место задано точкой в тексте. Так спрашивают из читалки: там
     * запас против спойлера не нужен - страница перед глазами, и что прочитано,
     * известно точно.
     */
    fun contextAt(book: Book, text: BookText, cutoffChar: Int, absMs: Long): Ctx {
        val p = settings.now()
        val cutoff = cutoffChar.coerceIn(0, text.length)
        val want = p.contextPages * Settings.PAGE_CHARS
        val from = (cutoff - want).coerceAtLeast(0)
        val chapter = text.chapterAt(cutoff)

        val prefix = if (p.wholeBookContext) {
            // Кэш-блок обрывается на границе главы: пока слушаешь главу, он
            // не меняется, и час кэша отрабатывает по-настоящему.
            val chStart = chapter?.start ?: 0
            if (chStart > 400) text.slice(0, chStart) else ""
        } else ""

        val fragStart = if (prefix.isNotEmpty()) (chapter?.start ?: 0) else from
        return Ctx(
            fragment = text.slice(fragStart, cutoff),
            prefix = prefix,
            chapter = chapter?.title.orEmpty(),
            percent = if (text.length > 0) (cutoff * 100 / text.length) else 0,
            cutoffChar = cutoff,
            elapsed = formatClock(absMs),
        )
    }

    suspend fun ask(
        book: Book,
        ctx: Ctx,
        question: String,
        absMs: Long,
        onDelta: (String) -> Unit,
    ): Result<Ask> {
        val place = Prompts.place(book.title, book.author, ctx.chapter, ctx.percent, ctx.elapsed)
        val blocks = buildList {
            add(ClaudeClient.Block(Prompts.RULES, cache = true))
            if (ctx.prefix.isNotBlank()) {
                add(ClaudeClient.Block(Prompts.beforeThat(ctx.prefix), cache = true))
            }
            add(ClaudeClient.Block(place + "\n\n" + Prompts.fragment(ctx.fragment)))
        }
        return client.ask(
            model = Settings.MODEL_OPUS,
            system = blocks,
            question = question,
            onDelta = onDelta,
        ).map { reply ->
            val ask = Ask(
                at = System.currentTimeMillis(),
                absMs = absMs,
                question = question,
                answer = reply.text,
                costUsd = reply.costUsd,
            )
            askLog.add(book.id, ask)
            ask
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
     * «Что там было»: пересказ последних глав. Сонетом - он справляется и
     * стоит вчетверо дешевле Опуса. Спойлеров тут не бывает по построению:
     * дальше текущего места модели просто нечего не показали.
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
        return client.ask(
            model = Settings.MODEL_SONNET,
            system = listOf(
                ClaudeClient.Block(Prompts.RECAP_RULES, cache = true),
                ClaudeClient.Block(
                    Prompts.place(book.title, book.author, chapter, 0, formatClock(absMs)) +
                        "\n\n" + Prompts.fragment(fragment)
                ),
            ),
            question = "Напомни, что было в этом куске.",
            maxTokens = 1100,
            onDelta = onDelta,
        ).map { it.text }
    }

    fun cancel() = client.cancel()

    private companion object {
        /** Потолок пересказа: тридцать страниц - это уже не «напомни», а чтение заново. */
        const val MAX_RECAP_CHARS = 54_000
    }
}
