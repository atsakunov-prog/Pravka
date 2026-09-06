package ru.zf.slushalka.ask

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.data.Ask
import ru.zf.slushalka.data.AskLog
import ru.zf.slushalka.data.GuideStore
import ru.zf.slushalka.data.Saf
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.documentUri
import ru.zf.slushalka.text.BookText
import ru.zf.slushalka.text.Chapter

/**
 * Справочник по книге: главы, герои, места, словарь - по всей книге разом.
 *
 * Считается пакетным запросом (Message Batches): книга целиком уезжает
 * Опусу, ответ приходит обычно в течение часа, платится половина цены. Пока
 * пакет считается, состояние лежит файлом; при следующем открытии книги или
 * листа справочника оно проверяется ([sync]).
 *
 * Готовый справочник ложится ещё и **в папку книги** файлом
 * `слушалка-справочник.json` - как разметка. Значит, он переезжает вместе с
 * книгой: второй читатель на своём телефоне получает его даром, без второго
 * заказа. При открытии книги без своего справочника файл из папки
 * подхватывается, если он сделан к тому же тексту.
 *
 * Спойлер-барьер здесь устроен иначе, чем у вопроса: модель видит всю книгу,
 * но каждую запись привязывает к главе, а читателю показывают только записи о
 * дочитанных главах (см. [GuideEntry.visibleAt]).
 */
class GuideEngine(
    private val context: Context,
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
        val outTokens = (text.length / 30).coerceIn(6_000, 60_000)
        return Estimate(
            parts = parts.size,
            pages = text.pages,
            usd = ClaudeClient.costUsd(model, inTokens, outTokens) * ClaudeClient.BATCH_DISCOUNT,
        )
    }

    /** Заказать справочник: пакет уезжает, состояние - «готовится». */
    suspend fun start(book: Book, text: BookText): Result<GuideState> = lock.withLock {
        val p = settings.now()
        val model = p.guideModel
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
                    // Мысли считаются в тот же бюджет, а JSON по толстому роману с
                    // содержанием глав тянет на десятки тысяч токенов. Запас
                    // ничего не стоит: платится только написанное.
                    .put("max_tokens", MAX_OUTPUT_TOKENS)
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
                by = p.profile.ifBlank { "без имени" },
            )
            put(book.id, state)
            state
        }
    }

    /**
     * Сверить справочник книги с миром: своего нет - поискать файл в папке
     * книги; пакет считается - проверить; готов, а файла рядом с книгой нет -
     * положить. Вызывается при открытии книги и листа справочника.
     */
    suspend fun sync(book: Book, text: BookText?): Result<GuideState?> = lock.withLock {
        val local = state(book.id)
        when (local?.status) {
            null -> {
                val imported = withContext(Dispatchers.IO) { readFromBook(book) } ?: return@withLock Result.success(null)
                if (text != null && !imported.second.fits(text)) return@withLock Result.success(null)
                Result.success(put(book.id, imported.first))
            }
            GuideState.Status.PENDING -> refreshLocked(book, local)
            GuideState.Status.READY -> {
                withContext(Dispatchers.IO) {
                    if (!existsInBook(book)) writeToBook(book, local, text)
                }
                Result.success(local)
            }
            GuideState.Status.FAILED -> Result.success(local)
        }
    }

    /**
     * Проверить пакет, если он ещё считается: кончился - забрать результаты,
     * собрать справочник и записать. Не кончился - просто отметить время.
     */
    suspend fun refresh(book: Book, text: BookText? = null): Result<GuideState?> = lock.withLock {
        val current = state(book.id) ?: return@withLock Result.success(null)
        if (current.status != GuideState.Status.PENDING) return@withLock Result.success(current)
        refreshLocked(book, current, text)
    }

    private suspend fun refreshLocked(book: Book, current: GuideState, text: BookText? = null): Result<GuideState?> =
        client.batch(current.batchId).mapCatching { batch ->
            if (!batch.ended) {
                return@mapCatching put(book.id, current.copy(checkedAt = System.currentTimeMillis()))
            }
            val url = batch.resultsUrl ?: throw ClaudeClient.ApiException("Пакет закончился, но результатов нет")
            val lines = client.batchResults(url).getOrThrow()
            var guide = Guide.EMPTY
            var cost = 0.0
            val notes = ArrayList<String>()
            for (line in lines) {
                val result = line.optJSONObject("result") ?: continue
                val part = "часть " + (line.optString("custom_id").substringAfter("part-").toIntOrNull()?.plus(1) ?: "?")
                when (result.optString("type")) {
                    "succeeded" -> {
                        val message = result.optJSONObject("message")
                        val content = message?.optJSONArray("content")
                        val text = (0 until (content?.length() ?: 0))
                            .mapNotNull { content!!.optJSONObject(it) }
                            .filter { it.optString("type") == "text" }
                            .joinToString("\n") { it.optString("text") }
                        val stop = message?.optString("stop_reason").orEmpty()
                        val usage = message?.optJSONObject("usage")
                        cost += ClaudeClient.costUsd(
                            current.model,
                            usage?.optInt("input_tokens") ?: 0,
                            usage?.optInt("output_tokens") ?: 0,
                            usage?.optInt("cache_creation_input_tokens") ?: 0,
                            usage?.optInt("cache_read_input_tokens") ?: 0,
                        ) * ClaudeClient.BATCH_DISCOUNT
                        val parsed = Guide.parse(text)
                        when {
                            stop == "refusal" -> notes.add("$part: модель отказалась отвечать")
                            parsed == null -> notes.add(
                                "$part: ответ не разобрался как JSON" +
                                    (if (stop == "max_tokens") " (обрезан по длине, ${usage?.optInt("output_tokens") ?: 0} токенов)" else "") +
                                    "; конец ответа: «…" + text.takeLast(240).replace('\n', ' ') + "»"
                            )
                            parsed.repaired -> {
                                notes.add(
                                    "$part: ответ обрезан по длине" +
                                        (if (stop.isNotBlank()) " ($stop)" else "") +
                                        ", хвост восстановлен - последние записи могли потеряться"
                                )
                                guide = guide.merge(parsed.guide)
                            }
                            else -> guide = guide.merge(parsed.guide)
                        }
                    }
                    "errored" -> notes.add(
                        "$part: " +
                            (result.optJSONObject("error")?.optJSONObject("error")?.optString("message")
                                ?.takeIf { it.isNotBlank() }
                                ?: result.optJSONObject("error")?.optString("type") ?: "ошибка")
                    )
                    "expired" -> notes.add("$part: не успел за сутки")
                    "canceled" -> notes.add("$part: отменён")
                }
            }
            if (cost > 0) {
                // В общий счёт «потрачено на вопросы», как советник - под своим именем.
                askLog.add(LOG_KEY, Ask(System.currentTimeMillis(), 0L, "Справочник: «${book.title}»", "", cost))
            }
            val now = System.currentTimeMillis()
            val state = if (guide.isEmpty) {
                current.copy(
                    status = GuideState.Status.FAILED,
                    error = notes.joinToString("\n").ifBlank { "Пустой ответ пакета" },
                    costUsd = cost,
                    checkedAt = now,
                )
            } else {
                current.copy(
                    status = GuideState.Status.READY,
                    guide = guide,
                    error = notes.joinToString("\n"),
                    costUsd = cost,
                    checkedAt = now,
                )
            }
            put(book.id, state)
            if (state.status == GuideState.Status.READY) {
                withContext(Dispatchers.IO) { writeToBook(book, state, text) }
            }
            state
        }

    /** Забыть справочник - и свой, и файл в папке книги, - чтобы заказать заново. */
    fun forget(book: Book) {
        store.delete(book.id)
        _states.value = _states.value - book.id
        val tree = treeUri() ?: return
        Thread {
            runCatching {
                Saf.findChild(context, tree, book.folderDocId, FILE)?.let { docId ->
                    DocumentsContract.deleteDocument(context.contentResolver, documentUri(tree, docId))
                }
            }
        }.start()
    }

    private fun put(bookId: String, state: GuideState): GuideState {
        store.save(bookId, state)
        _states.value = _states.value + (bookId to state)
        return state
    }

    // ------------------------------------------------------- файл у книги

    /** К какому тексту сделан справочник: другое издание нумерует главы иначе. */
    data class Fit(val chars: Int, val chapters: Int) {
        fun fits(text: BookText): Boolean =
            chapters == text.chapters.size || kotlin.math.abs(chars - text.length) * 50 < text.length
    }

    private fun treeUri(): Uri? = settings.now().libraryUri.takeIf { it.isNotBlank() }?.let(Uri::parse)

    private fun existsInBook(book: Book): Boolean {
        val tree = treeUri() ?: return true
        return Saf.findChild(context, tree, book.folderDocId, FILE) != null
    }

    private fun writeToBook(book: Book, state: GuideState, text: BookText?): Boolean {
        val tree = treeUri() ?: return false
        val docId = Saf.ensureChild(context, tree, book.folderDocId, FILE, "application/json") ?: return false
        val body = state.toJson().apply {
            put("книга", book.title)
            put("text", book.textName.orEmpty())
            if (text != null) {
                put("chars", text.length)
                put("главы", text.chapters.size)
            }
        }.toString()
        return Saf.writeText(context, tree, docId, body)
    }

    private fun readFromBook(book: Book): Pair<GuideState, Fit>? {
        val tree = treeUri() ?: return null
        val docId = Saf.findChild(context, tree, book.folderDocId, FILE) ?: return null
        val raw = Saf.readText(context, tree, docId) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val state = GuideState.fromJson(o)
            if (state.status != GuideState.Status.READY || state.guide == null) return null
            state to Fit(o.optInt("chars", -1), o.optInt("главы", -1))
        }.getOrNull()
    }

    /**
     * Главы режутся на части не длиннее [PART_CHARS]: одна часть - один запрос
     * пакета. Части нарочно не самые большие: чем длиннее часть, тем длиннее
     * ответ, а обрезанный по длине JSON - главный способ потерять справочник.
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

        /** Имя файла в папке книги - рядом с `слушалка-разметка.json`. */
        const val FILE = "слушалка-справочник.json"

        /** Восемьсот тысяч знаков - около 320 тысяч токенов на часть. */
        private const val PART_CHARS = 800_000

        /** Потолок ответа на часть: у Опуса и Сонета 128 тысяч, берём с запасом под мысли. */
        private const val MAX_OUTPUT_TOKENS = 96_000
    }
}
