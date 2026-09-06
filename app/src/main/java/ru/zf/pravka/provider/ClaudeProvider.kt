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
import ru.zf.pravka.core.ParsedTask
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.ProofreadProvider
import ru.zf.pravka.core.ProofreadResult
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Settings

// Direct Anthropic Messages API client. The API key is entered by the owner
// in the app settings and lives only in on-device DataStore (agreed deviation
// from spec section 10 - no VPS proxy).
//
// Здесь — ТРАНСПОРТ и Правка: запрос, SSE-стрим, кэш промпта, ретраи, деньги,
// чистка текста, обучение на правках владельца. Разборы режимов живут рядом
// расширениями этого класса: ClaudeZasechka.kt, ClaudeRaznoska.kt, ClaudeFood.kt,
// ClaudeBody.kt, ClaudeAnalysis.kt. Их контракты (data class ...Parse) остаются
// вложенными здесь, чтобы вызовы `claude.parseBody(...)` и типы
// `ClaudeProvider.StrengthParse` не менялись по всему коду. Правка промпта Еды
// теперь не заставляет открывать файл про таймшит.
class ClaudeProvider(
    internal val settings: Settings,
    internal val promptStore: PromptStore,
    internal val client: OkHttpClient,
    internal val rulesStore: ru.zf.pravka.data.RulesStore,
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
    internal inline fun <T> runCatchingApi(block: () -> T): Result<T> =
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

    internal data class ApiReply(
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
        strong: Boolean,
        conversationContext: String,
    ): Result<ProofreadResult> =
        withContext(Dispatchers.IO) {
            runCatchingApi {
                val apiKey = settings.apiKey()
                if (apiKey.isBlank()) {
                    throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
                }
                // Две дороги владельца (настройки → «Модели»): повседневная
                // чистка и «сильнее» для чипов переделки. Заводские — Сонет и
                // Опус, но обе меняются на телефоне без новой сборки.
                val everyday = settings.modelChoice(ModelRoute.PRAVKA)
                val choice = if (strong) settings.modelChoice(ModelRoute.PRAVKA_STRONG) else everyday
                val model = choice.model
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
                    // Кэш стабильного префикса — на повседневной модели: там он
                    // читается с каждой диктовки. Переделка на другой модели —
                    // другое пространство кэша, и запись за 2x ушла бы впустую;
                    // на той же модели это тот же кэш, пусть читает.
                    .copy(cacheStableAlways = model == everyday.model)

                val started = System.currentTimeMillis()
                val reply = requestWithOneRetry(
                    apiKey, model, parts, input, onDelta,
                    effortOverride = choice.effort,
                )
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
                val choice = settings.modelChoice(ModelRoute.PRAVKA)
                val model = choice.model
                val prompt = instruction.trim() + "\n\n<текст>\n" + content + "\n</текст>"
                val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
                val started = System.currentTimeMillis()
                val reply = requestWithOneRetry(
                    apiKey, model, parts, "", onDelta,
                    effortOverride = choice.effort,
                )
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
            val choice = settings.modelChoice(ModelRoute.PRAVKA_LEARN)
            val reply = requestWithOneRetry(
                apiKey, choice.model, parts, "", null,
                effortOverride = choice.effort,
            )
            parseLearn(reply.text).copy(
                costUsd = costUsd(choice.model, reply),
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
            val choice = settings.modelChoice(ModelRoute.PRAVKA_LEARN)
            val reply = requestWithOneRetry(
                apiKey, choice.model, parts, "", null,
                effortOverride = choice.effort,
            )
            val parsed = parseLearn(reply.text)
            require(parsed.rules.isNotEmpty()) { "Модель не вернула правил — набор не тронут." }
            OptimizedRules(
                rules = parsed.rules,
                costUsd = costUsd(choice.model, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
            )
        }
    }

    // ---- Засечка: one dictated phrase -> a structured timesheet entry ----

    data class ZasechkaParse(
        val action: String,        // "new" | "insert" | "edit" | "delete" | "stop" | "none"
        val entryIndex: Int,       // 1-based index into today's list (edit/delete)
        val title: String,
        val category: String,      // "" when the model failed to pick one
        val client: String,
        val useful: Int,           // 0 = not rated
        val startOffsetMin: Int,   // how far back the activity started
        val startTime: String,     // edit/insert: "HH:MM" new start, "" = keep
        val endTime: String,       // edit/insert: "HH:MM" new end, "" = keep
        val durationMin: Int,      // insert: длительность куска, 0 = не названа
        val say: String = "",      // none: почему ничего не записано
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
    )

    // ---- Разноска: наговор -> дела в Todoist (Опус) ----

    data class SplitResult(
        val tasks: List<ParsedTask>,
        val notes: String,
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
        val model: String,
        val latencyMs: Long,
    )

    /** «суббота, 22 августа 2026 (2026-08-22)»: модели нужны оба вида. */
    internal fun todayContext(): String {
        val now = java.util.Date()
        val human = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale("ru")).format(now)
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(now)
        return "$human ($iso)"
    }

    internal val isoDate = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    // ---- Еда: сказанное (и снятое) -> КБЖУ (Сонет) ----

    data class FoodParse(
        val kind: String,
        val timeOfDay: String,   // «ЧЧ:ММ» со слов владельца, иначе пусто
        val items: List<ru.zf.pravka.core.MealItem>,
        val note: String,
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
        val model: String,
        val latencyMs: Long,
    )

    internal val clockOnly = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")

    // ---- Тело: один вызов на роутер и разбор (Сонет) ----

    /** Что владелец сказал: вид намерения и разобранная начинка. */
    data class BodyParse(
        val kind: String,                     // strength | gtg | food | feel | question | unknown
        val strength: StrengthParse?,
        val gtg: GtgParse?,
        val food: FoodParse?,
        val feel: FeelParse?,
        val question: String,
        val note: String,
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
        val model: String,
        val latencyMs: Long,
    )

    data class SetParse(val amount: Int, val weightKg: Double, val note: String)

    data class ExerciseParse(
        val name: String,
        val sets: List<SetParse>,
        val note: String,
    )

    data class StrengthParse(
        val mode: String,                     // replace | add
        val exercises: List<ExerciseParse>,
        val feel: Int,
        val rpe: Int,
        val minutes: Int,
    )

    data class GtgParse(
        val charged: Boolean,
        val hangSec: Int,
        val negatives: Int,
        val scapular: Int,
        val pullups: Int,
    )

    data class FeelParse(val feel: Int, val knee: String, val note: String)

    // ---- Правила блока: проза Notion -> числа (Сонет, раз в сутки) ----

    data class RulesParse(
        val hrCeiling: Int,
        val greyLow: Int,
        val greyHigh: Int,
        val cadence: Int,
        val runsMax: Int,
        val hoursBetween: Int,
        val rampNeedsPositiveTsb: Boolean,
        val testPrep: String,
        val cancel: String,
        val kneeGreen: String,
        val kneeYellow: String,
        val kneeRed: String,
        val week: List<Pair<String, String>>,
        val extra: List<String>,
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
    )

    // ---- Спорт: вопрос по своим тренировкам (Опус) ----

    data class CoachAnswer(
        val text: String,
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
        val model: String,
        val latencyMs: Long,
    )

    /** «воскресенье, 23 августа 2026, 14:05» — еде нужен ещё и час. */
    internal fun nowContext(): String {
        val now = java.util.Date()
        val human = java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", java.util.Locale("ru"))
            .format(now)
        return human
    }

    /** Достаёт JSON-объект из ответа, снимая markdown-обёртку, если она есть. */
    internal fun jsonObjectOf(raw: String, complaint: String): JSONObject {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw ApiException(complaint)
        return runCatching { JSONObject(text.substring(start, end + 1)) }
            .getOrElse { throw ApiException(complaint) }
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

    // ---- Итоги: разбор жизненного лога БАТЧЕМ ----
    //
    // Batches API берёт половину цены за то, что ответ не нужен немедленно:
    // заявка уходит ночью, результат забирается опросом (обычно минуты, по
    // договору — до суток). Для ночного разбора это ровно тот случай, когда
    // ждать нечего и скидка достаётся даром.
    //
    // temperature здесь НЕ передаётся сознательно: на Opus 5 и Sonnet 5
    // параметры сэмплирования удалены и запрос с ними отвергается с 400.
    // Глубину задаёт output_config.effort, а не температура.

    data class BatchAnswer(
        val text: String,
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
        val error: String = "",
    )

    internal fun costUsd(model: String, reply: ApiReply): Double = Pricing.costUsd(
        model,
        inputTokens = reply.inputTokens,
        outputTokens = reply.outputTokens,
        cacheWriteTokens = reply.cacheWriteTokens,
        cacheReadTokens = reply.cacheReadTokens,
    )

    /**
     * Снимок для мультимодального запроса: base64 и его тип. Картинка едет
     * ПЕРЕД текстом - так рекомендует Anthropic, и так модель точно видит её
     * до инструкции, что с ней делать.
     */
    data class ImagePart(val mediaType: String, val base64: String)

    internal fun requestWithOneRetry(
        apiKey: String,
        model: String,
        parts: Prompts.PromptParts,
        input: String,
        onDelta: ((String) -> Unit)?,
        images: List<ImagePart> = emptyList(),
        maxTokensOverride: Int = 0,
        effortOverride: String = "",
        tolerateTruncation: Boolean = false,
    ): ApiReply {
        // Spec 6.1: one retry on network error or timeout; none on client 4xx.
        // Transient server blips (429/500/529 "overloaded") last seconds - one
        // short-backoff retry turns them from a user-visible failure into
        // nothing. A short pause before the network retry too: an instant
        // re-POST into the same dead socket just fails the same way.
        return try {
            request(apiKey, model, parts, input, onDelta, images, maxTokensOverride, effortOverride, tolerateTruncation)
        } catch (e: IOException) {
            Thread.sleep(1000)
            request(apiKey, model, parts, input, onDelta, images, maxTokensOverride, effortOverride, tolerateTruncation)
        } catch (e: ApiException) {
            if (!e.retryable) throw e
            Thread.sleep(e.retryDelayMs)
            request(apiKey, model, parts, input, onDelta, images, maxTokensOverride, effortOverride, tolerateTruncation)
        }
    }

    private fun request(
        apiKey: String,
        model: String,
        parts: Prompts.PromptParts,
        input: String,
        onDelta: ((String) -> Unit)?,
        images: List<ImagePart> = emptyList(),
        maxTokensOverride: Int = 0,
        effortOverride: String = "",
        /**
         * true — «обрезан по длине» не ошибка, а неполный ответ. Для правки
         * текста обрезанный ответ опасен (получишь полтекста вместо текста), а
         * для разбора «Итогов» девяносто процентов разбора несравнимо лучше
         * красной надписи вместо него.
         */
        tolerateTruncation: Boolean = false,
    ): ApiReply {
        // Rough token estimate for Russian text (~2.5 chars/token) + 30% headroom.
        // Counts BOTH slots: assist/learn tasks carry all their content in
        // dictPart with input="" - estimating from input alone collapsed their
        // budget to the 1024 floor, deterministically truncating long texts.
        // Снимок тарелки - это ещё ~1600 токенов на картинку (1568x1568 max):
        // без этой прибавки бюджет ответа сжимается до пола и разбор еды по
        // фото обрывается на середине JSON.
        val estimatedInputTokens =
            (parts.dictPart.length + input.length) / 2 + 1 + images.size * 1600
        // Модели с адаптивными размышлениями считают мысли в тот же
        // max_tokens: без запаса переделка в 350 знаков сжигала бюджет на
        // мыслях и умирала с stop_reason=max_tokens, не выдав ни слова
        // (владелец видел бесконечный спиннер, 18.08.2026). Кому мысли
        // выключены — решает RequestPolicy, а не имя модели в этом файле.
        val thinkingOff = RequestPolicy.thinkingOff(model, effortOverride)
        val thinkingHeadroom = RequestPolicy.thinkingHeadroom(model, effortOverride)
        // Оценка по длине входа врёт там, где длинный вход просит короткий
        // ответ и наоборот (разбор «Итогов»): такой вызов задаёт бюджет сам.
        val maxTokens = if (maxTokensOverride > 0) maxTokensOverride
        else (estimatedInputTokens * 13 / 10 + 300 + thinkingHeadroom).coerceIn(1024, 16384)

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            if (effortOverride.isNotBlank()) {
                put("output_config", JSONObject().put("effort", effortOverride))
            }
            // SSE streaming: first corrected words reach the ticker in well under
            // a second, and the 90s readTimeout becomes a per-chunk timeout
            // instead of a hard ceiling on total generation time.
            put("stream", true)
            // Правка — механика: на Сонете размышления выключены, они только
            // добавляют секунды и деньги. Вне Сонета параметр опускается:
            // адаптивные мысли — поведение по умолчанию, явное «disabled» на
            // Опусе имеет документированные сбои, а на Fable — это 400.
            // Усилие xhigh/max включает мысли и Сонету (см. RequestPolicy).
            if (thinkingOff) {
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
                                // Картинки первыми: так у модели сначала кадр,
                                // потом инструкция, что с ним делать.
                                for (img in images) put(
                                    JSONObject().apply {
                                        put("type", "image")
                                        put(
                                            "source",
                                            JSONObject().apply {
                                                put("type", "base64")
                                                put("media_type", img.mediaType)
                                                put("data", img.base64)
                                            }
                                        )
                                    }
                                )
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
                                        // Кэшировать или нет — решает вызывающий
                                        // (cacheStableAlways): чистка ставит точку
                                        // на повседневной модели, разборы режимов —
                                        // всегда, редкая переделка на чужой модели —
                                        // нет: запись за 2x никто бы не прочитал.
                                        if (parts.cacheStableAlways) {
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

        // Опус думает адаптивно, и на разборе «Итогов» пауза между кусками
        // потока доходит до минут: 90-секундный таймаут общего клиента рвёт
        // ровно те запросы, ради которых он и заводился длинным.
        val callClient =
            if (maxTokensOverride > 30_000) {
                client.newBuilder()
                    .readTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
                    .build()
            } else client
        val call = callClient.newCall(request)
        activeCalls.add(call)
        try {
            return executeStreaming(call, onDelta, tolerateTruncation)
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

    private fun executeStreaming(
        call: okhttp3.Call,
        onDelta: ((String) -> Unit)?,
        tolerateTruncation: Boolean = false,
    ): ApiReply {
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
                "max_tokens" ->
                    // Обрезано, но текст есть и он длинный — отдаём как есть.
                    // Разбор без последнего абзаца полезнее красной надписи
                    // вместо разбора.
                    if (!tolerateTruncation || sb.length < 500) {
                        throw ApiException("Ответ модели обрезан по длине. Попробуй ещё раз или сократи текст.")
                    }
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
