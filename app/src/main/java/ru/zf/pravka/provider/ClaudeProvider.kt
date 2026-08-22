package ru.zf.pravka.provider

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.ProofreadProvider
import ru.zf.pravka.core.ProofreadResult
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Settings

// Direct Anthropic Messages API client. The API key is entered by the owner
// in the app settings and lives only in on-device DataStore (agreed deviation
// from spec section 10 - no VPS proxy).
class ClaudeProvider(
    private val settings: Settings,
    private val promptStore: PromptStore,
    private val client: OkHttpClient,
    private val rulesStore: ru.zf.pravka.data.RulesStore,
) : ProofreadProvider {

    override val id = "claude"

    class ApiException(
        message: String,
        val retryable: Boolean = false,
        // 429 backs off longer than a 5xx blip - an instant retry during real
        // rate limiting just buys a second 429.
        val retryDelayMs: Long = 1500,
    ) : Exception(message)

    // runCatching would swallow CancellationException too - then a job killed
    // by "Сброс" finishes its epilogue as a Failed result and fights the job
    // that replaced it. Cancellation must stay cancellation.
    private inline fun <T> runCatchingApi(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    // In-flight HTTP calls, so "Сброс" can close the socket for real instead
    // of letting a zombie stream bill to completion in the background.
    private val activeCalls = java.util.concurrent.CopyOnWriteArraySet<okhttp3.Call>()

    /** Hard-cancels every in-flight API call. */
    fun cancelActive() {
        activeCalls.forEach { runCatching { it.cancel() } }
    }

    private data class ApiReply(
        val text: String,
        val inputTokens: Int,       // uncached, billed at full price
        val cacheWriteTokens: Int,  // billed at 2x (1h TTL cache write)
        val cacheReadTokens: Int,   // billed at 0.1x
        val outputTokens: Int,
    )

    override suspend fun proofread(
        input: String,
        mode: ProofreadMode,
        dictBlock: String,
        onDelta: ((String) -> Unit)?,
        directive: String,
        contextBefore: String,
        modelOverride: String?,
        conversationContext: String,
    ): Result<ProofreadResult> =
        withContext(Dispatchers.IO) {
            runCatchingApi {
                val apiKey = settings.apiKey()
                if (apiKey.isBlank()) {
                    throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
                }
                // Sonnet by default; redo chips pass Opus for extra quality.
                val model = modelOverride ?: Settings.MODEL_SONNET
                // ONE master template (CLEAN) for every mode; BUSINESS/SOFTEN
                // are style directives riding in the uncached slot, so all
                // modes share the same cached prefix.
                val template = promptStore.effective(ProofreadMode.CLEAN)
                val styleDirective = if (mode == ProofreadMode.CLEAN) "" else promptStore.effective(mode)
                // Fiction mode (settings toggle): the PROSE directive rides on
                // top of the plain CLEAN pass; explicit style modes win over it.
                val proseOn = mode == ProofreadMode.CLEAN && settings.proseModeFlow.first()
                val proseDirective =
                    if (proseOn) promptStore.effective(PromptStore.PromptId.PROSE) else ""
                val fullDirective = listOf(styleDirective, proseDirective, directive)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                // Approved learned rules ride in the same uncached slot as the
                // dictionary block, so the cached CLEAN prefix stays byte-stable.
                // In prose mode they are message-formatting advice fighting the
                // prose directive - skipped unless the owner enabled them there.
                val rulesBlock =
                    if (proseOn && !settings.rulesInProseFlow.first()) ""
                    else rulesStore.enabledBlock()
                val dictAndRules = listOf(dictBlock, rulesBlock)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                val parts = Prompts.assemble(template, dictAndRules, fullDirective, contextBefore, conversationContext)

                val started = System.currentTimeMillis()
                val reply = requestWithOneRetry(apiKey, model, parts, input, onDelta)
                ProofreadResult(
                    text = reply.text,
                    providerId = id,
                    latencyMs = System.currentTimeMillis() - started,
                    changed = reply.text.trim() != input.trim(),
                    appliedDictEntries = emptyList(),
                    modelId = model,
                    inputTokens = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                    outputTokens = reply.outputTokens,
                    costUsd = costUsd(model, reply),
                    cacheWriteTokens = reply.cacheWriteTokens,
                    cacheReadTokens = reply.cacheReadTokens,
                )
            }
        }

    /**
     * Free-form assist task (summarize / reply / translate): [instruction]
     * plus [content] in tags, NO CLEAN template. Returns a ProofreadResult so
     * the history journal and cost accounting reuse the same shape.
     */
    suspend fun assist(
        instruction: String,
        content: String,
        onDelta: ((String) -> Unit)? = null,
    ): Result<ProofreadResult> =
        withContext(Dispatchers.IO) {
            runCatchingApi {
                val apiKey = settings.apiKey()
                if (apiKey.isBlank()) {
                    throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
                }
                val model = Settings.MODEL_SONNET
                val prompt = instruction.trim() + "\n\n<текст>\n" + content + "\n</текст>"
                val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
                val started = System.currentTimeMillis()
                val reply = requestWithOneRetry(apiKey, model, parts, "", onDelta)
                ProofreadResult(
                    text = reply.text,
                    providerId = id,
                    latencyMs = System.currentTimeMillis() - started,
                    changed = true,
                    appliedDictEntries = emptyList(),
                    modelId = model,
                    inputTokens = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                    outputTokens = reply.outputTokens,
                    costUsd = costUsd(model, reply),
                    cacheWriteTokens = reply.cacheWriteTokens,
                    cacheReadTokens = reply.cacheReadTokens,
                )
            }
        }

    // ---- learning: the owner edited our output; Opus extracts what to keep ----

    data class LearnProposals(
        val dict: List<DictProposal>,
        val rules: List<RuleProposal>,
        // Cost accounting: learning runs on Opus and must be counted too.
        val costUsd: Double = 0.0,
        val tokensIn: Int = 0,
        val tokensOut: Int = 0,
    )

    data class DictProposal(val mode: String, val from: String, val to: String, val note: String)

    data class RuleProposal(val text: String, val before: String, val after: String)

    /**
     * Compares the recognizer's raw text, our cleaned output and the owner's
     * hand-corrected final. Returns dictionary proposals (recurring
     * recognition errors) and short prompt rules (systematic preferences).
     * Runs on Opus - this is rare, quality matters more than cost.
     */
    suspend fun learn(
        dictated: String,
        cleaned: String,
        final: String,
    ): Result<LearnProposals> = learnBatch(listOf(Triple(dictated, cleaned, final)))

    /** Batch flavor: the daily auto-capture analysis sends several edits at once. */
    suspend fun learnBatch(
        cases: List<Triple<String, String, String>>,
    ): Result<LearnProposals> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
            require(cases.isNotEmpty()) { "Нет правок для анализа." }
            // The analyst must SEE the current rule set, or it keeps
            // re-deriving rules the owner already approved (the gender
            // agreement rule came back every round before this).
            val existingRules = rulesStore.all().filter { it.enabled }
            val existingBlock = if (existingRules.isEmpty()) "" else buildString {
                append("УЖЕ ДЕЙСТВУЮЩИЕ правила (менять их не надо):\n")
                existingRules.forEachIndexed { i, r -> append(i + 1).append(". ").append(r.text).append('\n') }
                append(
                    "НЕ предлагай эти правила снова — ни дословно, ни перефразированными, " +
                        "ни их частные случаи. Если правка владельца лишь подтверждает " +
                        "действующее правило — пропусти её. Предлагай только то, чего в списке нет.\n\n"
                )
            }
            val casesBlock = buildString {
                cases.forEachIndexed { i, (dictated, cleaned, final) ->
                    append("СЛУЧАЙ ").append(i + 1).append(":\n")
                    append("<dictated>\n").append(dictated).append("\n</dictated>\n")
                    append("<cleaned>\n").append(cleaned).append("\n</cleaned>\n")
                    append("<final>\n").append(final).append("\n</final>\n\n")
                }
            }.trim()
            val prompt = """
Ниже случаи из системы диктовки. В каждом три версии одного текста:
- dictated: что выдало распознавание речи;
- cleaned: что сделала автоматическая чистка (модель);
- final: как в итоге поправил текст сам владелец. Это эталон.

Сравни cleaned и final в каждом случае и извлеки, чему стоит
научиться НАСОВСЕМ:

1. "dict" — словарные записи для ПОВТОРЯЕМЫХ ошибок распознавания
   (имена, термины, которые распознаватель пишет неверно):
   {"mode": "HARD" | "PROTECT", "from": "...", "to": "...", "note": "..."}
   HARD: from — неверная форма, to — верная. PROTECT: from — редкое
   правильное слово, to — пустая строка.

2. "rules" — правила для промпта чистки. Каждое правило:
   {"rule": "...", "before": "...", "after": "..."}
   - "rule": императив не длиннее 140 символов, по-русски. Обобщай
     НАМЕРЕНИЕ владельца и указывай УСЛОВИЕ применимости («в
     сообщениях-перечнях…», «в деловой переписке…»), а не буквальную
     подстановку слов. Разовая правка по смыслу правилом НЕ является.
   - "before"/"after": короткий фрагмент (до 120 символов) из правок
     владельца, показывающий правило в действии.

Ответ — СТРОГО JSON без пояснений:
{"dict": [...], "rules": [...]}
Если учиться нечему — пустые массивы.

$existingBlock$casesBlock
""".trimIndent()
            val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
            val reply = requestWithOneRetry(apiKey, Settings.MODEL_OPUS, parts, "", null)
            parseLearn(reply.text).copy(
                costUsd = costUsd(Settings.MODEL_OPUS, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
            )
        }
    }

    // ---- rule-set optimization: many accumulated rules -> a tight set ----

    data class OptimizedRules(
        val rules: List<RuleProposal>,
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
    )

    /**
     * Opus consolidates the accumulated rules: merges overlaps, generalizes,
     * drops contradictions, keeps the strongest example per rule. No hard
     * size cap (owner's call) - every surviving rule must carry its own
     * distinct meaning. Used by the manual preview button AND the weekly
     * auto-optimization.
     */
    suspend fun optimizeRules(
        rules: List<ru.zf.pravka.data.RulesStore.Rule>,
    ): Result<OptimizedRules> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
            require(rules.size >= 2) { "Оптимизировать нечего: правил меньше двух." }
            val listing = buildString {
                rules.forEachIndexed { i, r ->
                    append(i + 1).append(". ").append(r.text)
                    if (r.exampleBefore.isNotBlank()) {
                        append("\n   Пример: «").append(r.exampleBefore)
                            .append("» → «").append(r.exampleAfter).append("»")
                    }
                    append('\n')
                }
            }.trim()
            val prompt = """
Ниже — накопленные правила владельца для системы чистки диктовок.
Они добавлялись по одному и наверняка пересекаются. Приведи набор
в порядок:
— слей дубли и пересечения в одно правило (хороший пример — признак
  важности);
— обобщай НАМЕРЕНИЕ с условием применимости, а не буквальные слова;
— убери противоречия (оставь более конкретное и полезное);
— каждое правило — императив не длиннее 140 символов, по-русски,
  с лучшим примером из имеющихся (before/after до 120 символов);
— оставь столько правил, сколько реально нужно — искусственного
  лимита нет, но каждое должно нести отдельный смысл; самые важные
  первыми.

Ответ — СТРОГО JSON без пояснений:
{"rules": [{"rule": "...", "before": "...", "after": "..."}]}

$listing
""".trimIndent()
            val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
            val reply = requestWithOneRetry(apiKey, Settings.MODEL_OPUS, parts, "", null)
            val parsed = parseLearn(reply.text)
            require(parsed.rules.isNotEmpty()) { "Опус не вернул правил — набор не тронут." }
            OptimizedRules(
                rules = parsed.rules,
                costUsd = costUsd(Settings.MODEL_OPUS, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
            )
        }
    }

    // ---- Засечка: one dictated phrase -> a structured timesheet entry ----

    data class ZasechkaParse(
        val action: String,        // "new" | "edit" | "delete"
        val entryIndex: Int,       // 1-based index into today's list (edit/delete)
        val title: String,
        val category: String,      // "" when the model failed to pick one
        val client: String,
        val useful: Int,           // 0 = not rated
        val startOffsetMin: Int,   // how far back the activity started
        val startTime: String,     // edit: "HH:MM" new start, "" = keep
        val endTime: String,       // edit: "HH:MM" new end, "" = keep
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
    )

    /**
     * Sonnet turns "созвон с Ивановым по отчёту, последние полчаса" into
     * {title, category, client, useful, start_offset_min}. Cheap and
     * mechanical - same no-thinking Sonnet setup as CLEAN. The caller still
     * saves the raw text if this fails: a take must never be lost to a
     * network blip.
     */
    suspend fun zasechka(
        raw: String,
        // name -> hint ("что сюда относится"); the hint rides only in the
        // prompt, the reply must return the bare name.
        categories: List<Pair<String, String>>,
        clients: List<String>,
        nowLocal: String,
        previousTitle: String,
        // Numbered lines of today's entries - the edit/delete intents point
        // at one of them by its number.
        todayEntries: List<String>,
    ): Result<ZasechkaParse> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
            val categoriesBlock = categories.joinToString("\n") { (name, hint) ->
                "- «$name»" + (if (hint.isBlank()) "" else " — $hint")
            }
            val clientsBlock =
                if (clients.isEmpty()) "(список пуст)"
                else clients.joinToString("\n") { "- $it" }
            val previousBlock =
                if (previousTitle.isBlank()) "" else "Предыдущее дело владельца: «$previousTitle».\n"
            val todayBlock =
                if (todayEntries.isEmpty()) "(записей сегодня ещё нет)"
                else todayEntries.joinToString("\n")
            val prompt = """
Ты — секретарь личного тайм-трекера. Владелец наговорил фразу. Обычно это
«чем я сейчас занят», но иногда — просьба ИСПРАВИТЬ или УДАЛИТЬ уже
существующую запись.

Сейчас: $nowLocal.
$previousBlock
Записи сегодня (№ · время · категория · название):
$todayBlock

Категории (после тире — пояснение, что сюда относится; выбери РОВНО одну
и верни ТОЛЬКО её название — текст в «кавычках», без пояснения):
$categoriesBlock

Клиенты и проекты владельца:
$clientsBlock

Сначала определи намерение ("action"):
- "new" — владелец говорит, чем занят сейчас или был занят (обычный случай).
- "edit" — просит поменять существующую запись: «поменяй…», «исправь…»,
  «это была не …, а …», «переименуй…», «запись с 16:00 — это на самом
  деле …». Укажи "entry" — номер записи из списка выше (по времени или
  названию, которое он назвал). В ответ включай ТОЛЬКО те поля, которые
  он просит поменять; остальные — пустые ("" или 0). Если он называет
  новое время записи («еда была с 16:43 до 17:40», «началось в 13:00»,
  «закончилось в 15:20») — верни "start_time" и/или "end_time" в формате
  "ЧЧ:ММ"; пустая строка = время не менять.
- "delete" — просит удалить запись: «удали…», «убери запись…». Укажи "entry".
- Если сомневаешься между new и edit — выбирай "new": данные важнее.

Правила полей:
- "title": короткое название дела, 2–5 слов, ИМЕННОЙ группой, с большой
  буквы, без точки и без глаголов: «Поездка на дачу за детьми», а не
  «поехал на дачу» и не «едет с дачи»; «Приготовление еды», а не «готовлю
  еду»; «Обед», «Время с детьми», «Разбор почты». Одно и то же дело должно
  называться одинаково в разные дни — иначе неделя не сложится.
  ВАЖНО: если такое же дело уже есть в сегодняшнем списке выше — назови его
  ТОЧНО так же и той же категорией.
- "category": название категории из списка, БУКВА В БУКВУ (без «кавычек»
  и без пояснения). Если ничего не подходит — пустая строка.
- "client": имя из списка клиентов, если дело явно про него. Если владелец
  назвал клиента/проект не из списка — верни как услышано. Иначе пустая строка.
- "useful": целое 1–5, только если владелец сам оценил пользу («полезность
  четыре», «пустая трата времени» = 1, «очень продуктивно» = 5). Иначе 0.
- "start_offset_min" (только для new): на сколько минут НАЗАД от текущего
  времени дело НАЧАЛОСЬ. Владелец почти всегда говорит это вслух — ищи
  маркер внимательно, он бывает в любом месте фразы:
  · «последние сорок минут», «минут двадцать назад», «полчаса назад» = 40/20/30
  · «уже N минут», «лежу уже четыре минуты», «уже минут двадцать занят
    детьми», «час назад начал» — это ТОЖЕ смещение назад (4, 20, 60)
  · числительные словами считай числом: «минут двадцать» = 20,
    «полчаса» = 30, «часа полтора» = 90
  · «с 13:00», «с 7:39» — посчитай разницу с текущим временем
  Если о прошлом ничего не сказано = 0. Верхняя граница разумности — 12 часов.

Ответ — СТРОГО JSON без пояснений, по форме намерения:
new:    {"action": "new", "title": "...", "category": "...", "client": "...", "useful": 0, "start_offset_min": 0}
edit:   {"action": "edit", "entry": 7, "title": "...", "category": "...", "client": "...", "useful": 0, "start_time": "", "end_time": ""}
delete: {"action": "delete", "entry": 7}

Фраза владельца:
<фраза>
$raw
</фраза>
""".trimIndent()
            val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
            val reply = requestWithOneRetry(apiKey, Settings.MODEL_SONNET, parts, "", null)
            parseZasechka(reply.text).copy(
                costUsd = costUsd(Settings.MODEL_SONNET, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
            )
        }
    }

    private fun parseZasechka(raw: String): ZasechkaParse {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw ApiException("Модель вернула не тот формат.")
        val o = runCatching { JSONObject(text.substring(start, end + 1)) }
            .getOrElse { throw ApiException("Модель вернула не тот формат.") }
        return ZasechkaParse(
            action = o.optString("action", "new").trim().lowercase(java.util.Locale.US)
                .takeIf { it in listOf("new", "edit", "delete") } ?: "new",
            entryIndex = o.optInt("entry", 0),
            title = o.optString("title").trim(),
            // The prompt shows categories as «Название» - strip the quotes if
            // the model echoes them back.
            category = o.optString("category").trim().trim('«', '»').trim(),
            client = o.optString("client").trim(),
            useful = o.optInt("useful", 0).coerceIn(0, 5),
            // 12 hours is the sanity ceiling for "how far back" - anything
            // larger is a parse hallucination, not a real day.
            startOffsetMin = o.optInt("start_offset_min", 0).coerceIn(0, 12 * 60),
            startTime = clockField(o, "start_time"),
            endTime = clockField(o, "end_time"),
            costUsd = 0.0,
            tokensIn = 0,
            tokensOut = 0,
        )
    }

    /** "16:43" or "" - anything that is not a clock time is dropped. */
    private fun clockField(o: JSONObject, key: String): String {
        val v = o.optString(key).trim()
        return if (Regex("^\\d{1,2}:\\d{2}$").matches(v)) v else ""
    }

    private fun parseLearn(raw: String): LearnProposals {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return LearnProposals(emptyList(), emptyList())
        val o = runCatching { JSONObject(text.substring(start, end + 1)) }
            .getOrElse { throw ApiException("Модель вернула не тот формат — попробуй разобрать ещё раз.") }
        val dict = mutableListOf<DictProposal>()
        o.optJSONArray("dict")?.let { array ->
            for (i in 0 until array.length()) {
                val d = array.optJSONObject(i) ?: continue
                val mode = d.optString("mode")
                val from = d.optString("from").trim()
                if (from.isEmpty() || mode !in listOf("HARD", "PROTECT")) continue
                dict.add(DictProposal(mode, from, d.optString("to").trim(), d.optString("note").trim()))
            }
        }
        val rules = mutableListOf<RuleProposal>()
        o.optJSONArray("rules")?.let { array ->
            for (i in 0 until array.length()) {
                // Accept both the object form and a bare string.
                array.optJSONObject(i)?.let { r ->
                    val text = r.optString("rule").trim()
                    if (text.isNotEmpty() && text.length <= 240) {
                        rules.add(RuleProposal(text, r.optString("before").trim(), r.optString("after").trim()))
                    }
                } ?: array.optString(i).trim().takeIf { it.isNotEmpty() && it.length <= 240 }?.let {
                    rules.add(RuleProposal(it, "", ""))
                }
            }
        }
        return LearnProposals(dict, rules)
    }

    private fun costUsd(model: String, reply: ApiReply): Double = Pricing.costUsd(
        model,
        inputTokens = reply.inputTokens,
        outputTokens = reply.outputTokens,
        cacheWriteTokens = reply.cacheWriteTokens,
        cacheReadTokens = reply.cacheReadTokens,
    )

    private fun requestWithOneRetry(
        apiKey: String,
        model: String,
        parts: Prompts.PromptParts,
        input: String,
        onDelta: ((String) -> Unit)?,
    ): ApiReply {
        // Spec 6.1: one retry on network error or timeout; none on client 4xx.
        // Transient server blips (429/500/529 "overloaded") last seconds - one
        // short-backoff retry turns them from a user-visible failure into
        // nothing. A short pause before the network retry too: an instant
        // re-POST into the same dead socket just fails the same way.
        return try {
            request(apiKey, model, parts, input, onDelta)
        } catch (e: IOException) {
            Thread.sleep(1000)
            request(apiKey, model, parts, input, onDelta)
        } catch (e: ApiException) {
            if (!e.retryable) throw e
            Thread.sleep(e.retryDelayMs)
            request(apiKey, model, parts, input, onDelta)
        }
    }

    private fun request(
        apiKey: String,
        model: String,
        parts: Prompts.PromptParts,
        input: String,
        onDelta: ((String) -> Unit)?,
    ): ApiReply {
        // Rough token estimate for Russian text (~2.5 chars/token) + 30% headroom.
        // Counts BOTH slots: assist/learn tasks carry all their content in
        // dictPart with input="" - estimating from input alone collapsed their
        // budget to the 1024 floor, deterministically truncating long texts.
        val estimatedInputTokens = (parts.dictPart.length + input.length) / 2 + 1
        // Opus (redo chips) thinks adaptively by default, and thinking tokens
        // count toward max_tokens: without headroom a 350-char redo burned the
        // whole budget on thinking and died with stop_reason=max_tokens before
        // emitting a single word (owner saw an endless spinner, 2026-08-18).
        val thinkingHeadroom = if (model == Settings.MODEL_SONNET) 0 else 8000
        val maxTokens = (estimatedInputTokens * 13 / 10 + 300 + thinkingHeadroom)
            .coerceIn(1024, 16384)

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            // SSE streaming: first corrected words reach the ticker in well under
            // a second, and the 90s readTimeout becomes a per-chunk timeout
            // instead of a hard ceiling on total generation time.
            put("stream", true)
            // Proofreading is mechanical; thinking would only add latency and
            // cost, so it is disabled on Sonnet. On Opus (redo chips) the
            // parameter is omitted: adaptive thinking is its default, and an
            // explicit "disabled" there has documented failure modes.
            if (model == Settings.MODEL_SONNET) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray().apply {
                                // Cache breakpoint sits on the stable template prefix
                                // ONLY - the dict block varies per request and would
                                // invalidate the cache on every dictation. 1h TTL:
                                // the owner's real gaps between fixes run up to ~30
                                // min, which the default 5m TTL would keep missing.
                                // Below Sonnet's 1024-token minimum it is a no-op.
                                // Assist tasks have no stable prefix - an empty text
                                // block would be rejected by the API, so skip it.
                                if (parts.stablePrefix.isNotBlank()) put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", parts.stablePrefix)
                                        // Cache only the everyday Sonnet path: a
                                        // rare Opus redo would pay the 2x cache
                                        // write and likely never read it back.
                                        if (model == Settings.MODEL_SONNET) {
                                            put(
                                                "cache_control",
                                                JSONObject().put("type", "ephemeral").put("ttl", "1h"),
                                            )
                                        }
                                    }
                                )
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", parts.dictPart + input + parts.afterInput)
                                    }
                                )
                            }
                        )
                    }
                )
            )
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCalls.add(call)
        try {
            return executeStreaming(call, onDelta)
        } catch (e: IOException) {
            // "Сброс" closed the socket: that is a cancellation, not a network
            // error - it must NOT fall into the retry path and re-bill.
            if (call.isCanceled()) throw kotlin.coroutines.cancellation.CancellationException("Отменено")
            if (e is java.io.InterruptedIOException) {
                // Read timeout after 90s of silence: the server almost
                // certainly finished (and billed) the generation - a blind
                // re-POST doubles the cost for an answer the owner stopped
                // waiting for long ago. Fail honestly instead.
                throw ApiException("Сеть молчала до таймаута. Проверь интернет и попробуй ещё раз.")
            }
            throw e
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun executeStreaming(call: okhttp3.Call, onDelta: ((String) -> Unit)?): ApiReply {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                val transient = response.code == 429 || response.code in 500..599
                throw ApiException(
                    humanReadableError(response.code, responseBody),
                    retryable = transient,
                    retryDelayMs = if (response.code == 429) 4000 else 1500,
                )
            }
            val source = response.body?.source() ?: throw ApiException("Пустой ответ API.")

            // SSE: "event: ..." / "data: {json}" lines. Every data payload
            // carries its own "type", so the event: lines can be ignored.
            val sb = StringBuilder()
            var stopReason = ""
            var inputTokens = 0
            var cacheWrite = 0
            var cacheRead = 0
            var outputTokens = 0
            var lastEmit = 0L
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val event = runCatching { JSONObject(line.substring(6)) }.getOrNull() ?: continue
                when (event.optString("type")) {
                    "message_start" -> {
                        val usage = event.optJSONObject("message")?.optJSONObject("usage")
                        inputTokens = usage?.optInt("input_tokens") ?: 0
                        cacheWrite = usage?.optInt("cache_creation_input_tokens") ?: 0
                        cacheRead = usage?.optInt("cache_read_input_tokens") ?: 0
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta")
                        if (delta?.optString("type") == "text_delta") {
                            sb.append(delta.optString("text"))
                            // ~10 updates/sec is plenty for the ticker; emitting
                            // per delta would copy the growing reply hundreds of
                            // times.
                            if (onDelta != null) {
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 100) {
                                    lastEmit = now
                                    onDelta(sb.toString())
                                }
                            }
                        }
                    }
                    "message_delta" -> {
                        event.optJSONObject("delta")?.optString("stop_reason")
                            ?.takeIf { it.isNotEmpty() }?.let { stopReason = it }
                        event.optJSONObject("usage")?.let { outputTokens = it.optInt("output_tokens", outputTokens) }
                    }
                    "error" -> {
                        val err = event.optJSONObject("error")
                        // A mid-stream server error is the SSE face of a 5xx:
                        // same failures retry whether they arrive as an HTTP
                        // status or inside the stream.
                        val type = err?.optString("type").orEmpty()
                        val transient = type == "overloaded_error" || type == "api_error"
                        throw ApiException(
                            "Anthropic: ${err?.optString("message") ?: "ошибка стрима"}",
                            retryable = transient,
                        )
                    }
                    // ping / content_block_start / content_block_stop / message_stop
                }
            }
            when (stopReason) {
                "end_turn", "stop_sequence" -> Unit
                "max_tokens" -> throw ApiException("Ответ модели обрезан по длине. Попробуй ещё раз или сократи текст.")
                "refusal" -> throw ApiException("Модель отказалась обрабатывать этот текст.")
                // Clean connection drop before message_delta: same event as an
                // IOException mid-stream, so it retries the same way.
                "" -> throw ApiException("Соединение оборвалось на середине ответа.", retryable = true)
                else -> throw ApiException("Неожиданный ответ модели ($stopReason).")
            }
            if (sb.isEmpty()) throw ApiException("Модель вернула пустой ответ.")
            onDelta?.invoke(sb.toString())  // final full text, past the throttle
            return ApiReply(
                text = sb.toString(),
                inputTokens = inputTokens,
                cacheWriteTokens = cacheWrite,
                cacheReadTokens = cacheRead,
                outputTokens = outputTokens,
            )
        }
    }

    private fun humanReadableError(code: Int, body: String): String {
        val serverMessage = runCatching {
            JSONObject(body).getJSONObject("error").getString("message")
        }.getOrNull()
        return when (code) {
            401 -> "Неверный API-ключ. Проверь его в настройках Правки."
            403 -> "У ключа нет доступа к модели." + (serverMessage?.let { " ($it)" } ?: "")
            404 -> "Модель не найдена." + (serverMessage?.let { " ($it)" } ?: "")
            429 -> "Слишком много запросов, подожди немного."
            in 500..599 -> "Сервер Anthropic недоступен ($code), попробуй позже."
            else -> "Ошибка API $code" + (serverMessage?.let { ": $it" } ?: "")
        }
    }
}
