package ru.zf.slushalka.ask

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.data.Ask
import ru.zf.slushalka.data.AskLog
import ru.zf.slushalka.data.GuideStore
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.BookText
import ru.zf.slushalka.text.Chapter

/**
 * Справочник по книге: герои, места, словарь - по всей книге разом.
 *
 * Считается пакетным запросом (Message Batches): книга целиком уезжает
 * Опусу, ответ приходит обычно в течение часа, платится половина цены. Пока
 * пакет считается, состояние лежит файлом; при следующем открытии книги или
 * листа справочника оно проверяется ([refresh]).
 *
 * Спойлер-барьер здесь устроен иначе, чем у вопроса: модель видит всю книгу,
 * но каждую запись привязывает к главе, а читателю показывают только записи о
 * главах до текущей (см. [GuideEntry.visibleAt]).
 */
class GuideEngine(
    private val settings: Settings,
    private val client: ClaudeClient,
    private val store: GuideStore,
    private val askLog: AskLog,
) {

    private val _states = MutableStateFlow<Map<String, GuideState>>(emptyMap())
    /** Состояния по книгам - те, что уже прочитаны с диска или менялись в этой сессии. */
    val states: StateFlow<Map<String, GuideState>> = _states

    private val lock = Mutex()

    /** Состояние справочника книги; с диска читается один раз. */
    fun state(bookId: String): GuideState? {
        _states.value[bookId]?.let { return it }
        val loaded = store.load(bookId) ?: return null
        _states.value = _states.value + (bookId to loaded)
        return loaded
    }

    data class Estimate(val parts: Int, val pages: Int, val usd: Double)

    /** Во что обойдётся справочник: половина обычной цены за вход книги и за длинный JSON на выходе. */
    fun estimate(text: BookText, model: String = settings.now().guideModel): Estimate {
        val parts = split(text)
        val inTokens = (text.length / 2.5).toInt() + parts.size * 2500
        val outTokens = (text.length / 40).coerceIn(4_000, 40_000)
        return Estimate(
            parts = parts.size,
            pages = text.pages,
            usd = ClaudeClient.costUsd(model, inTokens, outTokens) * ClaudeClient.BATCH_DISCOUNT,
        )
    }

    /** Заказать справочник: пакет уезжает, состояние - «готовится». */
    suspend fun start(book: Book, text: BookText): Result<GuideState> = lock.withLock {
        val model = settings.now().guideModel
        val chapters = chaptersOf(text)
        val parts = split(text)
        val requests = parts.mapIndexed { i, range ->
            val body = buildString {
                append(Prompts.guideTask(book.title, book.author, i, parts.size, range.first + 1, range.last + 1))
                for (ch in range) {
                    val c = chapters[ch]
                    append(Prompts.chapterMark(ch + 1, c.title.ifBlank { "без названия" }))
                    append(text.plain, c.start, c.end)
                }
            }
            JSONObject()
                .put("custom_id", "part-$i")
                .put("params", JSONObject()
                    .put("model", model)
                    // Мысли считаются в тот же бюджет, а JSON по толстому роману
                    // сам тянет на десятки тысяч токенов - запас нужен большой.
                    .put("max_tokens", 64_000)
                    .put("system", JSONArray().put(JSONObject().put("type", "text").put("text", Prompts.GUIDE_RULES)))
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", body))))
        }
        client.createBatch(requests).map { batch ->
            val state = GuideState(
                status = GuideState.Status.PENDING,
                batchId = batch.id,
                createdAt = System.currentTimeMillis(),
                model = model,
                parts = parts.size,
                guide = null,
                checkedAt = System.currentTimeMillis(),
            )
            put(book.id, state)
            state
        }
    }

    /**
     * Проверить пакет, если он ещё считается: кончился - забрать результаты,
     * собрать справочник и записать. Не кончился - просто отметить время.
     */
    suspend fun refresh(book: Book): Result<GuideState?> = lock.withLock {
        val current = state(book.id) ?: return@withLock Result.success(null)
        if (current.status != GuideState.Status.PENDING) return@withLock Result.success(current)
        client.batch(current.batchId).mapCatching { batch ->
            if (!batch.ended) {
                return@mapCatching put(book.id, current.copy(checkedAt = System.currentTimeMillis()))
            }
            val url = batch.resultsUrl ?: throw ClaudeClient.ApiException("Пакет закончился, но результатов нет")
            val lines = client.batchResults(url).getOrThrow()
            var guide = Guide.EMPTY
            var cost = 0.0
            val errors = ArrayList<String>()
            for (line in lines) {
                val result = line.optJSONObject("result") ?: continue
                when (result.optString("type")) {
                    "succeeded" -> {
                        val message = result.optJSONObject("message")
                        val content = message?.optJSONArray("content")
                        val text = (0 until (content?.length() ?: 0))
                            .mapNotNull { content!!.optJSONObject(it) }
                            .filter { it.optString("type") == "text" }
                            .joinToString("\n") { it.optString("text") }
                        val usage = message?.optJSONObject("usage")
                        cost += ClaudeClient.costUsd(
                            current.model,
                            usage?.optInt("input_tokens") ?: 0,
                            usage?.optInt("output_tokens") ?: 0,
                            usage?.optInt("cache_creation_input_tokens") ?: 0,
                            usage?.optInt("cache_read_input_tokens") ?: 0,
                        ) * ClaudeClient.BATCH_DISCOUNT
                        val part = Guide.parse(text)
                        if (part == null) errors.add("${line.optString("custom_id")}: ответ не разобрался как JSON")
                        else guide = guide.merge(part)
                    }
                    "errored" -> errors.add(
                        line.optString("custom_id") + ": " +
                            (result.optJSONObject("error")?.optJSONObject("error")?.optString("message")
                                ?: result.optJSONObject("error")?.optString("type") ?: "ошибка")
                    )
                    "expired" -> errors.add(line.optString("custom_id") + ": не успел за сутки")
                    "canceled" -> errors.add(line.optString("custom_id") + ": отменён")
                }
            }
            if (cost > 0) {
                // В общий счёт «потрачено на вопросы», как советник - под своим именем.
                askLog.add(LOG_KEY, Ask(System.currentTimeMillis(), 0L, "Справочник: «${book.title}»", "", cost))
            }
            val state = if (guide.isEmpty) {
                current.copy(
                    status = GuideState.Status.FAILED,
                    error = errors.joinToString("; ").ifBlank { "Пустой ответ" },
                    costUsd = cost,
                    checkedAt = System.currentTimeMillis(),
                )
            } else {
                current.copy(
                    status = GuideState.Status.READY,
                    guide = guide,
                    error = errors.joinToString("; "),
                    costUsd = cost,
                    checkedAt = System.currentTimeMillis(),
                )
            }
            put(book.id, state)
        }
    }

    /** Забыть справочник - чтобы заказать заново. */
    fun forget(bookId: String) {
        store.delete(bookId)
        _states.value = _states.value - bookId
    }

    private fun put(bookId: String, state: GuideState): GuideState {
        store.save(bookId, state)
        _states.value = _states.value + (bookId to state)
        return state
    }

    /**
     * Главы режутся на части не длиннее [PART_CHARS]: одна часть - один запрос
     * пакета. Романы почти всегда влезают в одну.
     */
    private fun split(text: BookText): List<IntRange> {
        val chapters = chaptersOf(text)
        val out = ArrayList<IntRange>()
        var start = 0
        var size = 0
        chapters.forEachIndexed { i, c ->
            if (size > 0 && size + c.length > PART_CHARS) {
                out.add(start until i)
                start = i
                size = 0
            }
            size += c.length
        }
        if (start <= chapters.lastIndex) out.add(start..chapters.lastIndex)
        return out
    }

    /** Книга без разметки глав - одна глава на всю книгу, чтобы было к чему привязывать записи. */
    private fun chaptersOf(text: BookText): List<Chapter> =
        text.chapters.ifEmpty { listOf(Chapter("Книга", 0, text.length)) }

    companion object {
        const val LOG_KEY = "справочник"

        /** Полтора миллиона знаков - около 600 тысяч токенов: с запасом до окна модели. */
        private const val PART_CHARS = 1_500_000
    }
}
