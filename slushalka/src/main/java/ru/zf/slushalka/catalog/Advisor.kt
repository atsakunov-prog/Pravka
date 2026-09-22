package ru.zf.slushalka.catalog

import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.ClaudeClient
import ru.zf.slushalka.data.Ask

/**
 * Советник в каталоге: с кем посоветоваться, что почитать.
 *
 * Модель знает книги куда лучше, чем поиск Флибусты знает подстроки, - и
 * умеет то, чего каталог не умеет вовсе: с чего начать у автора, в каком
 * порядке читать серию, что подойдёт ребёнку, что о книге говорят. Контекст
 * ей дают по месту: на полке - список своих книг с тем, докуда дослушали, на
 * странице автора - его книги из каталога, на странице книги - аннотация.
 *
 * Ответ - живой текст плюс служебная последняя строка «ИСКАТЬ: Автор —
 * Название; …», которую человек не видит: из неё делаются кнопки, и каждая
 * запускает поиск по каталогу. Так совет не остаётся словами - за ним сразу
 * идёт книга.
 */
class Advisor(private val app: SlushalkaApp, private val client: ClaudeClient) {

    sealed interface Scope {
        /** Полка: своя библиотека как портрет вкуса. */
        data object Library : Scope

        /** Страница автора и его книги, какие успели загрузиться. */
        data class Author(val name: String, val titles: List<String>) : Scope

        /** Одна книга: то, что о ней написано в каталоге. */
        data class Book(val entry: OpdsEntry) : Scope
    }

    data class Suggestion(val author: String, val title: String) {
        val label get() = if (author.isBlank()) title else "$author — $title"
    }

    data class Answer(val text: String, val suggestions: List<Suggestion>, val costUsd: Double)

    /** Один вопрос и ответ на него - история разговора в пределах одного листа. */
    data class Turn(val question: String, val answer: Answer)

    /** Готовые вопросы под чипами - по месту, где спрашивают. */
    fun quickPrompts(scope: Scope): List<Pair<String, Boolean>> = when (scope) {
        Scope.Library -> listOf(
            "Что почитать дальше, судя по моей библиотеке?" to false,
            "Что дальше - по тому, что я дочитал, бросил и где сидел запоем?" to false,
            "Что-то похожее на то, что я дослушал до конца" to false,
            "Что почитать ребёнку семи лет?" to false,
            "Что почитать подростку тринадцати лет?" to false,
        )
        is Scope.Author -> listOf(
            "С чего начать у этого автора?" to false,
            "В каком порядке читать серии?" to false,
            "Самое известное у автора и почему" to false,
            "Что у автора подойдёт ребёнку?" to false,
        )
        is Scope.Book -> listOf(
            "О чём эта книга, без спойлеров?" to false,
            "Что о ней говорят читатели и критики?" to true,
            "Для кого она и стоит ли браться?" to false,
            "Что почитать после неё?" to false,
        )
    }

    fun scopeLine(scope: Scope): String = when (scope) {
        Scope.Library -> "По вашей библиотеке: книг ${app.state.books.value.size}"
        is Scope.Author -> "Автор: ${scope.name}" +
            if (scope.titles.isNotEmpty()) " · в каталоге ${scope.titles.size} книг" else ""
        is Scope.Book -> "Книга: «${scope.entry.title}»" +
            if (scope.entry.authorLine.isNotBlank()) ", ${scope.entry.authorLine}" else ""
    }

    /** Обрывает текущий ответ - тот же вызов, что у вопросов по книге: транспорт один. */
    fun cancel() = client.cancel()

    suspend fun ask(
        scope: Scope,
        history: List<Turn>,
        question: String,
        web: Boolean,
        onDelta: (String) -> Unit,
    ): Result<Answer> {
        val p = app.settings.now()
        val system = listOf(
            ClaudeClient.Block(RULES, cache = true),
            // Контекст кэшируется: за вечер вопросов бывает несколько, а
            // библиотека или список книг автора между ними не меняются.
            ClaudeClient.Block(context(scope), cache = true),
        )
        val turns = buildList {
            history.forEach { t ->
                add(ClaudeClient.Turn("user", t.question))
                add(ClaudeClient.Turn("assistant", raw(t.answer)))
            }
            add(ClaudeClient.Turn("user", question))
        }
        return client.chat(
            model = p.adviseModel,
            system = system,
            turns = turns,
            maxTokens = 1800,
            effort = p.adviseEffort,
            webSearch = web,
            onDelta = { partial -> onDelta(split(partial).first) },
        ).map { reply ->
            val (text, suggestions) = split(reply.text)
            val answer = Answer(text, suggestions, reply.costUsd)
            // В общий журнал расходов - под своим именем, чтобы «потрачено на
            // вопросы» в настройках считало и советника.
            app.askLog.add(
                LOG_KEY,
                Ask(System.currentTimeMillis(), 0L, question, reply.text, reply.costUsd),
            )
            answer
        }
    }

    // ------------------------------------------------------------- контекст

    private fun context(scope: Scope): String = when (scope) {
        Scope.Library -> buildString {
            // Не только что есть, но и как читалось: дочитал, бросил на трети,
            // сидел запоем, делал пометки - по этому вкус виден точнее, чем по
            // названиям. Всё из журнала подходов и пометок, в сеть не ходит.
            val report = runCatching { ru.zf.slushalka.data.Stats.report(app.journal.all()) }.getOrNull()
            val now = System.currentTimeMillis()
            append("БИБЛИОТЕКА ЧЕЛОВЕКА И КАК ОН ЕЁ ЧИТАЛ (в скобках: докуда дошёл, сколько времени провёл, ")
            append("давно ли открывал, пометки на полях):\n")
            val books = app.state.books.value.take(120)
            if (books.isEmpty()) append("(пока пусто)\n")
            books.forEach { b ->
                val st = app.state.stateOf(b.id)
                val share = when {
                    b.hasAudio && b.totalMs > 0 -> st.absMs.toFloat() / b.totalMs
                    else -> st.readShare
                }
                val stat = report?.book(b.id)
                val idleDays = if (st.updatedAt > 0) ((now - st.updatedAt) / 86_400_000L).toInt() else -1
                val progress = when {
                    st.finished || share >= 0.98f -> "дочитано"
                    share > 0.02f && idleDays > 30 -> "брошена на ${(share * 100).toInt()}%, не открывал $idleDays дн"
                    share > 0.02f -> "${(share * 100).toInt()}%"
                    !b.hasAudio && st.readChar > 0 -> "читает"
                    else -> "не начата"
                }
                val bits = buildList {
                    add(progress)
                    stat?.let { s ->
                        val hours = s.totals.activeMs / 3_600_000.0
                        if (hours >= 0.5) add("%.0f ч".format(java.util.Locale.US, hours))
                        // Запоем: много часов за мало дней.
                        if (s.days in 1..7 && hours >= 6) add("запоем")
                    }
                    val notes = app.notes.count(b.id)
                    if (notes > 0) add("пометок: $notes")
                }
                append("- ")
                if (b.author.isNotBlank()) append(b.author).append(" — ")
                append(b.title).append(" (").append(bits.joinToString(", ")).append(")\n")
            }
        }
        is Scope.Author -> buildString {
            append("АВТОР: ").append(scope.name).append('\n')
            if (scope.titles.isNotEmpty()) {
                append("ЕГО КНИГИ В КАТАЛОГЕ (те, что видны на странице; в каталоге может быть больше):\n")
                scope.titles.take(80).forEach { append("- ").append(it).append('\n') }
            }
        }
        is Scope.Book -> buildString {
            val e = scope.entry
            append("КНИГА: «").append(e.title).append("»")
            if (e.authorLine.isNotBlank()) append(", ").append(e.authorLine)
            append('\n')
            if (e.issued.isNotBlank()) append("ГОД: ").append(e.issued).append('\n')
            if (e.series.isNotBlank()) append("СЕРИЯ: ").append(e.series).append('\n')
            if (e.categories.isNotEmpty()) append("ЖАНРЫ: ").append(e.categories.joinToString(", ")).append('\n')
            if (e.summary.isNotBlank()) append("АННОТАЦИЯ ИЗ КАТАЛОГА:\n").append(e.summary.take(3000)).append('\n')
        }
    }

    // --------------------------------------------------------------- разбор

    /** Текст ответа и служебная строка порознь: строку человек не видит, из неё - кнопки. */
    private fun split(full: String): Pair<String, List<Suggestion>> {
        val lines = full.lines()
        val idx = lines.indexOfLast { it.trim().startsWith(MARKER, ignoreCase = true) }
        if (idx < 0) return full.trim() to emptyList()
        val text = lines.take(idx).joinToString("\n").trim()
        val payload = lines[idx].trim().substring(MARKER.length).trim()
        val suggestions = payload.split(';', '\n')
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotBlank() }
            .map { item ->
                val m = Regex("^(.+?)\\s+[—–-]\\s+(.+)$").find(item)
                if (m != null) Suggestion(m.groupValues[1].trim(), m.groupValues[2].trim())
                else Suggestion("", item)
            }
            .distinctBy { it.label.lowercase() }
            .take(8)
        return text to suggestions
    }

    /** Ответ как его написала модель - для истории разговора, вместе со служебной строкой. */
    private fun raw(a: Answer): String =
        if (a.suggestions.isEmpty()) a.text
        else a.text + "\n" + MARKER + " " + a.suggestions.joinToString("; ") { it.label }

    companion object {
        const val LOG_KEY = "советник"
        private const val MARKER = "ИСКАТЬ:"

        val RULES = """
            Ты - книжный советник внутри читалки одной семьи. Каталог, из которого
            они берут книги, - Флибуста; библиотека - их собственные книги, тебе
            её покажут. Спрашивать могут и взрослые, и дети: если возраст читателя
            назван - учитывай его; если нет - не считай читателя ребёнком.

            Как отвечать:
            - по-русски, живо и по делу: обычно четыре-восемь предложений или
              короткий список строками, без вступлений и без пересказа вопроса;
            - без спойлеров сверх того, что написали бы на обороте обложки;
            - честно различай, что знаешь твёрдо, а что предполагаешь; если книга
              малоизвестная и ты в ней не уверен - скажи об этом;
            - если тебе доступен поиск в интернете и вопрос про то, что о книге
              говорят, - поищи и передай суть в двух-трёх фразах, не цитируя длинно;
            - имена авторов пиши как в русских каталогах: Фамилия Имя;
            - никакой разметки: ни заголовков, ни жирного, ни звёздочек; списки -
              просто строками с тире; абзацы по смыслу, через пустую строку, а не
              простынёй.

            В самом конце, если ты советуешь конкретные книги, добавь ОДНУ
            служебную строку ровно такого вида:
            ИСКАТЬ: Фамилия Имя — Название; Фамилия Имя — Название
            Не больше восьми книг, только те, что действительно существуют, и
            только те, что советуешь. Если конкретных книг не советуешь - строку
            не пиши. Ничего после этой строки не добавляй.
        """.trimIndent()
    }
}
