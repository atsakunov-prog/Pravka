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
        val startTime: String,     // edit/insert: "HH:MM" new start, "" = keep
        val endTime: String,       // edit/insert: "HH:MM" new end, "" = keep
        val durationMin: Int,      // insert: длительность куска, 0 = не названа
        val say: String = "",      // none: почему ничего не записано
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
    )

    /**
     * Опус превращает «созвон с Ивановым по отчёту, последние полчаса» в
     * {action, title, category, ...}. Был Сонет — владелец: «очень плохо
     * получается у Сонета понимать, как работать с засечкой»: терялись
     * ретро-вставки, категории спорили с прецедентом, названия вырождались
     * в имя категории. Свод правил лежит в stablePrefix под часовым кэшем —
     * Опус ходит сюда десятки раз в день, и кэш окупается с двух вызовов.
     * Падение разбора не теряет сказанное: вызывающий пишет сырым.
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
        // The owner's own wording from the previous few days: the same дело
        // must come back under the same name and category, otherwise the week
        // never adds up.
        recentEntries: List<String> = emptyList(),
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
            val recentBlock =
                if (recentEntries.isEmpty()) ""
                else "\nКак владелец называл свои дела в предыдущие дни (его собственные\n" +
                    "формулировки, часть он правил руками — держись их):\n" +
                    recentEntries.joinToString("\n") + "\n"
            val stableRules = """
Ты — секретарь личного тайм-трекера. Владелец наговорил фразу голосом.
Лента непрерывна: сумма минут дня всегда 24 часа, дыры между записями
заполняются сами. Твоя работа — понять НАМЕРЕНИЕ и вернуть строгий JSON.

НАМЕРЕНИЯ ("action") — владелец говорит одно из пяти:
1. Чем занят СЕЙЧАС → "new": «пишу отчёт», «сел есть».
2. Чем занят сейчас, но НАЧАЛ РАНЬШЕ → "new" + "start_offset_min":
   «полчаса назад начал готовить», «с 13:00 программирую».
3. ВСПОМНИЛ про завершённый кусок В ПРОШЛОМ → "insert": «с 18:30 до
   18:50 ходил в туалет», «между делом позвонил маме минут на десять»,
   «утром ещё полчаса читал». Признаки insert: у куска есть КОНЕЦ
   (второе время, длительность или прошедшее время глагола) и владелец
   НЕ говорит, что занят этим сейчас. Обрамляющее дело НЕ трогай и не
   упоминай: приложение само разрежет его вокруг вставки и ПРОДОЛЖИТ
   после неё — текущая запись не убивается.
4. ПОПРАВИТЬ существующую запись → "edit": «в 16:45 был не быт, а
   передвижение», «это была медкомиссия, а не быт», «туалет был до
   18:50», «переименуй…», «еда была с 16:43 до 17:40». Найди запись в
   списке «Сегодня» по времени или названию — верни её номер в "entry".
   Меняй ТОЛЬКО названные поля, остальные пустые ("" или 0). Новые
   границы — "start_time"/"end_time" в «ЧЧ:ММ»; пустая строка = не менять.
5. УДАЛИТЬ запись → "delete": «удали…», «убери запись…», «отмени
   последнюю» (= последний номер списка «Сегодня»). Верни "entry".
6. ЗАКОНЧИЛ, нового дела пока нет → "stop": «всё, закончил», «на этом
   всё», «закончил десять минут назад» (тогда "start_offset_min" = 10)
   или «закончил в 18:50» (тогда "end_time"). Открытое дело закроется,
   дальше лента разметит сама.
7. НЕ ПРО ЛЕНТУ → "none": о будущем («через пять минут пойду бегать» —
   запишется, когда начнёт и скажет), случайный мусор распознавания,
   вопрос без намерения. В "say" — одной строкой почему не записал.

Ещё случаи, которые владелец говорит:
- «вернулся к программированию», «продолжаю уборку» → "new" с ТЕМ ЖЕ
  title и категорией, что у этого дела в списках.
- «раздели запись: первые двадцать минут это была готовка» → "insert"
  куска в начало той записи (границы возьми из списка «Сегодня»);
  остальное обрамление приложение дорежет само.
- «туалет занял пять минут, а не двадцать» → "edit" записи «туалет»:
  "end_time" = её старт из списка «Сегодня» плюс пять минут — посчитай
  сам и верни «ЧЧ:ММ».

Сомневаешься между new и insert: есть конец в прошлом — insert; занят
сейчас — new. Сомневаешься между new и edit — new: данные важнее.
Одна фраза — одно намерение: разбирай главное.

ПРАВИЛА ПОЛЕЙ:
- "title": КОНКРЕТНОЕ название дела, 3–8 слов, именной группой, с большой
  буквы, без глаголов: «Медкомиссия с Серёжей», «Поездка на дачу за
  детьми», «Звонок юристу по ипотеке». ЛУЧШЕ ДЛИННЕЕ, ЧЕМ КОРОЧЕ:
  причеши сказанное, но НЕ сжимай до одного-двух слов («Метка»,
  «Миссия», «Уборка» — плохо) — сохраняй детали: с кем, что именно, где
  («Уборка детской с Марианной»). НОРМАЛИЗУЙ ГРАММАТИКУ, НЕ СМЫСЛ:
  сказал «пошёл в кафе с Марианной» — пиши «Кафе с Марианной», а не
  обезличенное «Время с Марианной»; «готовлю завтрак» — «Приготовление
  завтрака», а не «Приготовление еды». Личная деталь (блюдо, место,
  человек) дороже единообразия: к прошлому имени приводи только когда
  сказанное — ровно то же дело без новых деталей. Через неделю владелец
  должен по title вспомнить этот час, не открывая заметку. НИКОГДА не называй дело
  именем категории: title «Быт», «Отдых», «Потери», «Семья» ЗАПРЕЩЁН —
  категория лежит в своём поле. Однословное название допустимо ТОЛЬКО
  когда это же дело уже ровно так называется в списках («Обед»,
  «Завтрак»): если такое же дело есть в «Сегодня» или «Прошлых днях» —
  назови ТОЧНО так же, буква в букву, одинаковые дела называются
  одинаково, иначе неделя не складывается.
- "category": название категории из списка, БУКВА В БУКВУ (без «кавычек»
  и без пояснения). ПРЕЦЕДЕНТ ПОБЕЖДАЕТ ТВОЮ ЛОГИКУ: если это же дело в
  списках уже лежит в какой-то категории — клади в неё же, не рассуждай
  заново («YouTube» шёл в Потери — значит Потери, даже если «Отдых»
  кажется тебе логичнее; «Лежу в кровати» = Отдых, а «Ничего не делаю» =
  Потери — его решения, не твои). «Не размечено» — техническая категория
  авто-заполнителя дыр: сам её НЕ выбирай никогда. Ничего не подходит —
  пустая строка.
- "client": имя из списка клиентов, если дело явно про него. Назвал не из
  списка — верни как услышано. Иначе пустая строка.
- "useful": целое 1–5, только если владелец сам оценил пользу («полезность
  четыре», «пустая трата времени» = 1, «очень продуктивно» = 5). Иначе 0.
- "start_offset_min" (new и insert): на сколько минут НАЗАД от текущего
  времени началось. Маркер бывает в любом месте фразы:
  · «последние сорок минут», «минут двадцать назад», «полчаса назад» = 40/20/30
  · «уже N минут», «уже минут двадцать занят детьми», «час назад начал» = N/20/60
  · числительные словами — числом: «минут двадцать» = 20, «полчаса» = 30,
    «часа полтора» = 90
  · «с 13:00», «с 7:39» — лучше верни это в "start_time"
  Ничего не сказано = 0. Верхняя граница разумности — 12 часов.
- "duration_min" (insert): длительность куска в минутах, если названа
  («минут на десять», «на полчаса»); иначе 0 и укажи "end_time".

Ответ — СТРОГО JSON без пояснений, по форме намерения:
new:    {"action": "new", "title": "...", "category": "...", "client": "...", "useful": 0, "start_offset_min": 0, "start_time": ""}
insert: {"action": "insert", "title": "...", "category": "...", "client": "...", "useful": 0, "start_time": "18:30", "end_time": "18:50", "start_offset_min": 0, "duration_min": 0}
edit:   {"action": "edit", "entry": 7, "title": "...", "category": "...", "client": "...", "useful": 0, "start_time": "", "end_time": ""}
delete: {"action": "delete", "entry": 7}
stop:   {"action": "stop", "end_time": "", "start_offset_min": 0}
none:   {"action": "none", "say": "..."}
""".trimIndent() + "\n\n"
            val varTail = """
Сейчас: $nowLocal.
$previousBlock
Записи сегодня (№ · время · категория · название):
$todayBlock
$recentBlock
Категории (после тире — пояснение, что сюда относится):
$categoriesBlock

Клиенты и проекты владельца:
$clientsBlock

Фраза владельца:
<фраза>
$raw
</фраза>
""".trimIndent()
            // Правила стабильны байт-в-байт — под часовым кэшем; всё живое
            // (время, лента, категории, фраза) — в переменном хвосте.
            val parts = Prompts.PromptParts(
                stablePrefix = stableRules,
                cacheStableAlways = true,
                dictPart = varTail,
                afterInput = "",
            )
            val reply = requestWithOneRetry(apiKey, Settings.MODEL_OPUS, parts, "", null)
            parseZasechka(reply.text).copy(
                costUsd = costUsd(Settings.MODEL_OPUS, reply),
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
                .takeIf { it in listOf("new", "insert", "edit", "delete", "stop", "none") } ?: "new",
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
            durationMin = o.optInt("duration_min", 0).coerceIn(0, 12 * 60),
            say = o.optString("say").trim(),
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

    /**
     * Разбирает один наговор на дела для Todoist. Работает на Опусе: трудное
     * здесь не формулировка, а суждение - что вообще является делом, у кого
     * мяч, в какой проект оно ложится.
     *
     * Проекты модель называет так, как они стоят в каталоге; [resolveProject]
     * превращает названное в настоящий id, поэтому наружу выходит уже то, что
     * Todoist примет. Промпт правится владельцем во вкладке «Промпты».
     */
    suspend fun splitTasks(
        transcript: String,
        dictBlock: String,
        catalogBlock: String,
        knownLabels: List<String>,
        // "Стеллар Групп / buy-side M&A" -> (id, путь как в каталоге)
        resolveProject: (String) -> Pair<String, String>?,
    ): Result<SplitResult> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) {
                throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
            }
            require(transcript.isNotBlank()) { "Пустой наговор — разбирать нечего." }
            val template = promptStore.effective(PromptStore.PromptId.TASKS)
            val catalog = catalogBlock.ifBlank {
                "Каталог проектов не загружен — оставь project пустым, владелец выберет сам."
            }
            var prompt = template
                .replace(Prompts.PLACEHOLDER_DICT, dictBlock.ifBlank { "—" })
                .replace("{CATALOG}", catalog)
                .replace("{TODAY}", todayContext())
            prompt = if (prompt.contains(Prompts.PLACEHOLDER_INPUT)) {
                prompt.replace(Prompts.PLACEHOLDER_INPUT, transcript)
            } else {
                // Владелец отредактировал промпт и потерял {INPUT}: дописываем
                // наговор в конец, а не теряем его - то же правило, что в
                // Prompts.assemble().
                prompt.trimEnd() + "\n\nНаговор:\n" + transcript
            }
            val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
            val started = System.currentTimeMillis()
            val reply = requestWithOneRetry(apiKey, Settings.MODEL_OPUS, parts, "", null)
            val (tasks, notes) = parseTasks(reply.text, knownLabels, resolveProject)
            SplitResult(
                tasks = tasks,
                notes = notes,
                costUsd = costUsd(Settings.MODEL_OPUS, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
                model = Settings.MODEL_OPUS,
                latencyMs = System.currentTimeMillis() - started,
            )
        }
    }

    /** «суббота, 22 августа 2026 (2026-08-22)»: модели нужны оба вида. */
    private fun todayContext(): String {
        val now = java.util.Date()
        val human = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale("ru")).format(now)
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(now)
        return "$human ($iso)"
    }

    private val isoDate = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    private fun parseTasks(
        raw: String,
        knownLabels: List<String>,
        resolveProject: (String) -> Pair<String, String>?,
    ): Pair<List<ParsedTask>, String> {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw ApiException("Модель ответила не JSON. Наговор сохранён — разбери ещё раз.")
        }
        val o = runCatching { JSONObject(text.substring(start, end + 1)) }
            .getOrElse { throw ApiException("Модель вернула не тот формат — разбери ещё раз.") }
        val out = mutableListOf<ParsedTask>()
        val array = o.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val content = t.optString("content").trim()
            if (content.isEmpty()) continue
            val labels = mutableListOf<String>()
            val rawLabels = t.optJSONArray("labels")
            if (rawLabels != null) {
                for (j in 0 until rawLabels.length()) {
                    val name = rawLabels.optString(j).trim().removePrefix("@")
                    if (name.isEmpty()) continue
                    // Только метки, которые в Todoist правда есть: выдуманная
                    // создалась бы на месте и засорила систему.
                    val known = knownLabels.firstOrNull { it.equals(name, ignoreCase = true) }
                    if (known != null) labels.add(known)
                    else if (knownLabels.isEmpty()) labels.add(name)
                }
            }
            val named = t.optString("project").trim()
            val project = resolveProject(named)
            val due = t.optString("due").trim()
            out.add(
                ParsedTask(
                    id = (i + 1).toLong(),
                    content = content,
                    description = t.optString("description").trim(),
                    projectId = project?.first.orEmpty(),
                    // Не нашли: оставляем сказанное моделью - в редакторе видно,
                    // что проект надо выбрать руками.
                    projectName = project?.second ?: named,
                    labels = labels.distinct(),
                    priority = ParsedTask.priorityOf(t.optString("priority")),
                    due = if (isoDate.matches(due)) due else "",
                    repeat = t.optString("repeat").trim().take(60),
                )
            )
        }
        return out to o.optString("notes").trim()
    }

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

    /**
     * Разбирает один приём пищи. Сонета здесь достаточно: трудное - узнать
     * блюдо и прикинуть порцию, а не рассудить; таблицы КБЖУ модель знает.
     *
     * [image] - снимок тарелки: тогда к промпту добавляется указание мерить
     * порции по посуде в кадре и читать этикетку, если она попала в кадр.
     * Текст при этом может быть пустым - фото само себе описание.
     */
    suspend fun parseFood(
        text: String,
        dictBlock: String,
        profileLine: String,
        image: ImagePart? = null,
        rationBlock: String = "—",
        recentBlock: String = "—",
    ): Result<FoodParse> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) {
                throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
            }
            require(text.isNotBlank() || image != null) { "Нечего разбирать: ни слов, ни снимка." }
            val template = promptStore.effective(PromptStore.PromptId.FOOD)
            var prompt = template
                .replace(Prompts.PLACEHOLDER_DICT, dictBlock.ifBlank { "—" })
                .replace("{NOW}", nowContext())
                .replace("{PROFILE}", profileLine.ifBlank { "вес и пороги неизвестны" })
                // Рацион статичен (assets) — уезжает в кэшируемую голову;
                // история приёмов живая — в переменном хвосте после словаря.
                .replace("{RATION}", rationBlock.ifBlank { "—" })
                .replace("{RECENT}", recentBlock.ifBlank { "—" })
            if (image != null) prompt = prompt.trimEnd() + "\n\n" + Prompts.FOOD_PHOTO_HINT
            val said = text.ifBlank { "(без слов — только снимок)" }
            prompt = if (prompt.contains(Prompts.PLACEHOLDER_INPUT)) {
                prompt.replace(Prompts.PLACEHOLDER_INPUT, said)
            } else {
                prompt.trimEnd() + "\n\nСъедено:\n" + said
            }
            // Правила еды стабильны — под кэш; словарь, время и вход в хвосте.
            // Сплит по маркеру словаря: правил его шаблон может и не иметь
            // (правится в «Промптах») — тогда весь текст уезжает хвостом.
            // Со снимком кэш не включаем: блок изображения стоит ПЕРЕД
            // текстом и ломает префикс — платили бы за запись впустую.
            val cut = prompt.indexOf("Словарь владельца")
            val parts = if (cut > 0) {
                Prompts.PromptParts(
                    stablePrefix = prompt.substring(0, cut),
                    dictPart = prompt.substring(cut),
                    afterInput = "",
                    cacheStableAlways = image == null,
                )
            } else {
                Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
            }
            val started = System.currentTimeMillis()
            val reply = requestWithOneRetry(
                apiKey, Settings.MODEL_OPUS, parts, "", null,
                images = listOfNotNull(image),
            )
            val parsed = parseFoodReply(reply.text)
            FoodParse(
                kind = parsed.kind,
                timeOfDay = parsed.timeOfDay,
                items = parsed.items,
                note = parsed.note,
                costUsd = costUsd(Settings.MODEL_OPUS, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
                model = Settings.MODEL_OPUS,
                latencyMs = System.currentTimeMillis() - started,
            )
        }
    }

    private class FoodReply(
        val kind: String,
        val timeOfDay: String,
        val items: List<ru.zf.pravka.core.MealItem>,
        val note: String,
    )

    private val clockOnly = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")

    private fun parseFoodReply(raw: String): FoodReply {
        val o = jsonObjectOf(raw, "Модель ответила не JSON. Скажи ещё раз или поправь руками.")
        val out = mutableListOf<ru.zf.pravka.core.MealItem>()
        val array = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val name = t.optString("name").trim()
            if (name.isEmpty()) continue
            val item = ru.zf.pravka.core.MealItem(
                name = name.take(120),
                grams = t.optInt("grams", 0).coerceIn(0, 5000),
                kcal = t.optInt("kcal", 0).coerceIn(0, 10_000),
                protein = t.optInt("protein", 0).coerceIn(0, 500),
                fat = t.optInt("fat", 0).coerceIn(0, 500),
                carbs = t.optInt("carbs", 0).coerceIn(0, 1000),
                fiber = t.optInt("fiber", 0).coerceIn(0, 200),
                sureness = t.optString("sure").trim().take(12),
            )
            // Калорий модель не дала, а макросы дала - считаем сами, чтобы
            // позиция не пришла в дневник нулевой.
            out.add(if (item.kcal == 0) item.copy(kcal = item.kcalFromMacros()) else item)
        }
        val time = o.optString("time").trim()
        return FoodReply(
            kind = o.optString("kind").trim().lowercase().take(20),
            timeOfDay = if (clockOnly.matches(time)) time else "",
            items = out,
            note = o.optString("note").trim(),
        )
    }

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

    /**
     * Разбирает одну фразу про тело: подходы, зарядку, еду, самочувствие или
     * вопрос. Роутер и разбор в ОДНОМ вызове — два подряд стоили бы вдвое и
     * тормозили вдвое, а классификация без разбора всё равно бесполезна.
     *
     * Справочники упражнений и рациона едут в КЭШИРУЕМОЙ части промпта: они
     * байт в байт одинаковы между запросами, поэтому платятся раз в час, а не
     * на каждую фразу. Именно из-за них распознавание становится выбором из
     * списка вместо угадывания.
     */
    suspend fun parseBody(
        text: String,
        dictBlock: String,
        exerciseBook: String,
        rationBook: String,
        planBlock: String,
        lastTimeBlock: String,
        /** Где сказано: «в карточке зарядки», «в карточке силовой» — смещает роутер. */
        whereSaid: String = "",
    ): Result<BodyParse> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) {
                throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
            }
            require(text.isNotBlank()) { "Пустая фраза — разбирать нечего." }
            val template = promptStore.effective(PromptStore.PromptId.BODY)
            // Стабильная часть — до маркера {VARS}: инструкция и оба
            // справочника. Она и уходит под точку кэша.
            val split = template.indexOf(Prompts.PLACEHOLDER_VARS)
            // Словарь варьируется от фразы к фразе, поэтому в заводском шаблоне
            // он стоит НИЖЕ {VARS}. Точка кэша сидит на конце head, и любой
            // изменившийся байт до неё стоил бы полной перезаписи кэша — один
            // сработавший hint делал бы дороже все наговоры дня. В head словарь
            // подменяем прочерком на случай, если владелец в правленом шаблоне
            // оставил {DICT} над {VARS}: пусть кэш живёт, а словарь ему уедет
            // в хвосте.
            val head = (if (split >= 0) template.substring(0, split) else template)
                .replace("{EXERCISES}", exerciseBook.ifBlank { "Справочник упражнений не загружен." })
                .replace("{RATION}", rationBook.ifBlank { "Справочник рациона не загружен." })
                .replace(Prompts.PLACEHOLDER_DICT, "—")
            var tail = if (split >= 0) template.substring(split + Prompts.PLACEHOLDER_VARS.length) else ""
            tail = tail
                .replace(Prompts.PLACEHOLDER_DICT, dictBlock.ifBlank { "—" })
                .replace("{NOW}", nowContext())
                .replace(
                    "{WHERE}",
                    if (whereSaid.isBlank()) ""
                    else "Владелец написал это $whereSaid — скорее всего, речь про неё.",
                )
                .replace("{PLAN}", planBlock)
                .replace("{LAST}", lastTimeBlock)
            tail = if (tail.contains(Prompts.PLACEHOLDER_INPUT)) {
                tail.replace(Prompts.PLACEHOLDER_INPUT, text)
            } else {
                tail.trimEnd() + "\n\nСказано:\n" + text
            }
            val parts = Prompts.PromptParts(
                stablePrefix = head,
                dictPart = tail,
                afterInput = "",
                // Роутер «Т» ходит много раз в день с одними справочниками —
                // кэш окупается и на Опусе.
                cacheStableAlways = true,
            )
            val started = System.currentTimeMillis()
            val reply = requestWithOneRetry(apiKey, Settings.MODEL_OPUS, parts, "", null)
            parseBodyReply(reply).copy(latencyMs = System.currentTimeMillis() - started)
        }
    }

    private fun parseBodyReply(reply: ApiReply): BodyParse {
        val o = jsonObjectOf(
            reply.text,
            "Модель ответила не JSON. Сказанное сохранено — можно разобрать заново.",
        )
        val kind = o.optString("kind").trim().lowercase().ifBlank { "unknown" }

        val strength = o.optJSONObject("strength")?.let { s ->
            val exercises = mutableListOf<ExerciseParse>()
            val array = s.optJSONArray("exercises") ?: JSONArray()
            for (i in 0 until array.length()) {
                val e = array.optJSONObject(i) ?: continue
                val name = e.optString("name").trim()
                if (name.isEmpty()) continue
                val rows = mutableListOf<SetParse>()
                val sets = e.optJSONArray("sets") ?: JSONArray()
                for (j in 0 until sets.length()) {
                    val r = sets.optJSONObject(j) ?: continue
                    val amount = r.optInt("amount", 0)
                    if (amount <= 0) continue
                    rows.add(
                        SetParse(
                            amount = amount.coerceAtMost(3000),
                            weightKg = r.optDouble("weight", 0.0).coerceIn(0.0, 300.0),
                            note = r.optString("note").trim().take(120),
                        )
                    )
                }
                if (rows.isEmpty()) continue
                exercises.add(
                    ExerciseParse(
                        name = name.take(120),
                        sets = rows,
                        note = e.optString("note").trim().take(200),
                    )
                )
            }
            StrengthParse(
                mode = if (s.optString("mode").trim().lowercase() == "add") "add" else "replace",
                exercises = exercises,
                feel = s.optInt("feel", 0).coerceIn(0, 5),
                rpe = s.optInt("rpe", 0).coerceIn(0, 10),
                minutes = s.optInt("minutes", 0).coerceIn(0, 600),
            )
        }

        val gtg = o.optJSONObject("gtg")?.let { g ->
            GtgParse(
                charged = g.optBoolean("charged", false),
                hangSec = g.optInt("hang", 0).coerceIn(0, 1200),
                negatives = g.optInt("negatives", 0).coerceIn(0, 100),
                scapular = g.optInt("scapular", 0).coerceIn(0, 100),
                pullups = g.optInt("pullups", 0).coerceIn(0, 100),
            )
        }

        val food = o.optJSONObject("food")?.let { f ->
            val items = mutableListOf<ru.zf.pravka.core.MealItem>()
            val array = f.optJSONArray("items") ?: JSONArray()
            for (i in 0 until array.length()) {
                val t = array.optJSONObject(i) ?: continue
                val name = t.optString("name").trim()
                if (name.isEmpty()) continue
                val item = ru.zf.pravka.core.MealItem(
                    name = name.take(120),
                    grams = t.optInt("grams", 0).coerceIn(0, 5000),
                    kcal = t.optInt("kcal", 0).coerceIn(0, 10_000),
                    protein = t.optInt("protein", 0).coerceIn(0, 500),
                    fat = t.optInt("fat", 0).coerceIn(0, 500),
                    carbs = t.optInt("carbs", 0).coerceIn(0, 1000),
                    fiber = t.optInt("fiber", 0).coerceIn(0, 200),
                    sureness = t.optString("sure").trim().take(12),
                )
                items.add(if (item.kcal == 0) item.copy(kcal = item.kcalFromMacros()) else item)
            }
            val clock = f.optString("time").trim()
            FoodParse(
                kind = f.optString("kind").trim().lowercase().take(20),
                timeOfDay = if (clockOnly.matches(clock)) clock else "",
                items = items,
                note = f.optString("note").trim(),
                costUsd = 0.0,
                tokensIn = 0,
                tokensOut = 0,
                model = Settings.MODEL_OPUS,
                latencyMs = 0L,
            )
        }

        val feel = o.optJSONObject("feel")?.let { f ->
            FeelParse(
                feel = f.optInt("feel", 0).coerceIn(0, 5),
                knee = f.optString("knee").trim().lowercase().take(16),
                note = f.optString("note").trim().take(300),
            )
        }

        return BodyParse(
            kind = kind,
            strength = strength?.takeIf { it.exercises.isNotEmpty() || it.feel > 0 || it.rpe > 0 },
            gtg = gtg?.takeIf { it.charged || it.hangSec > 0 || it.negatives > 0 || it.scapular > 0 },
            food = food?.takeIf { it.items.isNotEmpty() },
            feel = feel?.takeIf { it.feel > 0 || it.knee.isNotBlank() },
            question = o.optString("question").trim(),
            note = o.optString("note").trim(),
            costUsd = costUsd(Settings.MODEL_OPUS, reply),
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
            model = Settings.MODEL_OPUS,
            latencyMs = 0L,
        )
    }

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

    /**
     * Вынимает числа из страницы блока в Notion: потолок пульса, каденс, серую
     * зону, лимит пробежек. Раз в сутки — страница меняется раз в месяц.
     */
    suspend fun extractRules(pageText: String): Result<RulesParse> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
            require(pageText.isNotBlank()) { "Страница блока пуста." }
            val template = promptStore.effective(PromptStore.PromptId.RULES)
            // Стена текста режется: страница блока это две-три тысячи знаков, а
            // не роман, и платить за случайно вставленный роман незачем.
            val body = pageText.take(20_000)
            val prompt = if (template.contains(Prompts.PLACEHOLDER_INPUT)) {
                template.replace(Prompts.PLACEHOLDER_INPUT, body)
            } else {
                template.trimEnd() + "\n\nСтраница блока:\n" + body
            }
            val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
            val reply = requestWithOneRetry(apiKey, Settings.MODEL_SONNET, parts, "", null)
            val o = jsonObjectOf(reply.text, "Правила блока не разобрались — модель ответила не JSON.")
            val week = mutableListOf<Pair<String, String>>()
            o.optJSONArray("week")?.let { a ->
                for (i in 0 until a.length()) {
                    val w = a.optJSONObject(i) ?: continue
                    val day = w.optString("day").trim()
                    val session = w.optString("session").trim()
                    if (day.isNotBlank() && session.isNotBlank()) week.add(day to session)
                }
            }
            val extra = mutableListOf<String>()
            o.optJSONArray("extra")?.let { a ->
                for (i in 0 until a.length()) {
                    a.optString(i).trim().takeIf { it.isNotBlank() }?.let { extra.add(it.take(200)) }
                }
            }
            RulesParse(
                hrCeiling = o.optInt("hrCeiling", 0).coerceIn(0, 220),
                greyLow = o.optInt("greyLow", 0).coerceIn(0, 220),
                greyHigh = o.optInt("greyHigh", 0).coerceIn(0, 220),
                cadence = o.optInt("cadence", 0).coerceIn(0, 240),
                runsMax = o.optInt("runsMax", 0).coerceIn(0, 14),
                hoursBetween = o.optInt("hoursBetween", 0).coerceIn(0, 168),
                rampNeedsPositiveTsb = o.optBoolean("rampNeedsPositiveTsb", false),
                testPrep = o.optString("testPrep").trim().take(300),
                cancel = o.optString("cancel").trim().take(300),
                kneeGreen = o.optString("kneeGreen").trim().take(300),
                kneeYellow = o.optString("kneeYellow").trim().take(300),
                kneeRed = o.optString("kneeRed").trim().take(300),
                week = week,
                extra = extra.take(8),
                costUsd = costUsd(Settings.MODEL_SONNET, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
            )
        }
    }

    // ---- Спорт: вопрос по своим тренировкам (Опус) ----

    data class CoachAnswer(
        val text: String,
        val costUsd: Double,
        val tokensIn: Int,
        val tokensOut: Int,
        val model: String,
        val latencyMs: Long,
    )

    /**
     * Отвечает на вопрос о тренировках. Опус: считать тут нечего - все цифры
     * уже в [contextBlock], - а вот сказать «сегодня не грузись, и вот почему»
     * это суждение, и оно стоит своих денег.
     *
     * Ответ стримится в [onDelta], чтобы первые слова появлялись на экране
     * сразу, как в Правке.
     */
    suspend fun coach(
        question: String,
        contextBlock: String,
        onDelta: ((String) -> Unit)? = null,
    ): Result<CoachAnswer> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) {
                throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
            }
            val template = promptStore.effective(PromptStore.PromptId.COACH)
            var prompt = template
                .replace("{CONTEXT}", contextBlock.ifBlank { "Данных нет — выгрузка не удалась." })
                .replace("{TODAY}", todayContext())
            val asked = question.trim().ifBlank { "Как у меня дела?" }
            prompt = if (prompt.contains(Prompts.PLACEHOLDER_INPUT)) {
                prompt.replace(Prompts.PLACEHOLDER_INPUT, asked)
            } else {
                prompt.trimEnd() + "\n\nВопрос:\n" + asked
            }
            val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
            val started = System.currentTimeMillis()
            val reply = requestWithOneRetry(apiKey, Settings.MODEL_OPUS, parts, "", onDelta)
            CoachAnswer(
                text = reply.text.trim(),
                costUsd = costUsd(Settings.MODEL_OPUS, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
                model = Settings.MODEL_OPUS,
                latencyMs = System.currentTimeMillis() - started,
            )
        }
    }

    /**
     * Тренер-консультант: короткий вопрос про упражнение. Сонет, а не Опус:
     * техника отвечается карточкой движения и правилами недели, полная
     * телеметрия тут лишняя — и по деньгам, и по скорости между подходами.
     */
    suspend fun trainer(
        question: String,
        focusBlock: String,
        weekBlock: String,
        onDelta: ((String) -> Unit)? = null,
    ): Result<CoachAnswer> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) {
                throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
            }
            val template = promptStore.effective(PromptStore.PromptId.TRAINER)
            var prompt = template
                .replace("{FOCUS}", focusBlock.ifBlank { "Упражнение не из справочника." })
                .replace("{WEEK}", weekBlock.ifBlank { "Правил недели в кэше нет." })
            val asked = question.trim().ifBlank { "Как правильно делать это упражнение?" }
            prompt = if (prompt.contains(Prompts.PLACEHOLDER_INPUT)) {
                prompt.replace(Prompts.PLACEHOLDER_INPUT, asked)
            } else {
                prompt.trimEnd() + "\n\nВопрос:\n" + asked
            }
            val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
            val started = System.currentTimeMillis()
            val reply = requestWithOneRetry(apiKey, Settings.MODEL_SONNET, parts, "", onDelta)
            CoachAnswer(
                text = reply.text.trim(),
                costUsd = costUsd(Settings.MODEL_SONNET, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
                model = Settings.MODEL_SONNET,
                latencyMs = System.currentTimeMillis() - started,
            )
        }
    }

    /** «воскресенье, 23 августа 2026, 14:05» — еде нужен ещё и час. */
    private fun nowContext(): String {
        val now = java.util.Date()
        val human = java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", java.util.Locale("ru"))
            .format(now)
        return human
    }

    /** Достаёт JSON-объект из ответа, снимая markdown-обёртку, если она есть. */
    private fun jsonObjectOf(raw: String, complaint: String): JSONObject {
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

    /**
     * Разбор ПРЯМО СЕЙЧАС, обычным запросом. Владелец: «если сделать разбор
     * сейчас — он не батчем должен уходить, а то я сижу жду». Батч дешевле
     * вдвое, но отвечает когда захочет; ручная кнопка платит полную цену за
     * ответ через минуту. Запрос идёт потоком, поэтому 90-секундный
     * readTimeout становится таймаутом между кусками, а не потолком на всю
     * генерацию.
     */
    suspend fun analyzeNow(
        system: String,
        user: String,
        model: String,
        maxTokens: Int,
        effort: String = "high",
    ): Result<BatchAnswer> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
            val parts = Prompts.PromptParts(
                stablePrefix = system.trim() + "\n\n",
                dictPart = user,
                afterInput = "",
                // Свод правил и профиль владельца стабильны байт-в-байт —
                // ночной батч и ручной запуск греют один и тот же кэш.
                cacheStableAlways = true,
            )
            // Бюджет и глубину задаём явно теми же числами, что у батча:
            // иначе ручной разбор и ночной отвечали бы по-разному, и сравнить
            // их было бы нельзя.
            val reply = requestWithOneRetry(
                apiKey, model, parts, "", null,
                maxTokensOverride = maxTokens,
                effortOverride = effort,
            )
            BatchAnswer(
                text = reply.text.trim(),
                costUsd = costUsd(model, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
            )
        }
    }

    /** Заявка в батч. Возвращает id, по которому потом забирается ответ. */
    suspend fun submitBatch(
        system: String,
        user: String,
        model: String,
        maxTokens: Int,
        effort: String = "high",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
            val params = JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)
                put("output_config", JSONObject().put("effort", effort))
                put("system", JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", system)
                }))
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "text")
                        put("text", user)
                    }))
                }))
            }
            val body = JSONObject().put(
                "requests",
                JSONArray().put(JSONObject().apply {
                    put("custom_id", "analysis")
                    put("params", params)
                }),
            )
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages/batches")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException("Батч не принят: HTTP ${response.code} ${text.take(200)}")
                }
                JSONObject(text).optString("id").ifBlank {
                    throw ApiException("Батч принят, но без id")
                }
            }
        }
    }

    /**
     * Ответ батча, если он готов. null — ещё считается: это НЕ ошибка, опрос
     * просто повторится на следующем тике службы.
     */
    suspend fun batchAnswer(batchId: String, model: String): Result<BatchAnswer?> =
        withContext(Dispatchers.IO) {
            runCatchingApi {
                val apiKey = settings.apiKey()
                if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
                val head = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages/batches/" + batchId)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .get()
                    .build()
                val status = client.newCall(head).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw ApiException("Батч не читается: HTTP ${response.code}")
                    }
                    JSONObject(text)
                }
                if (status.optString("processing_status") != "ended") return@runCatchingApi null
                val resultsUrl = status.optString("results_url").ifBlank {
                    "https://api.anthropic.com/v1/messages/batches/" + batchId + "/results"
                }
                val results = Request.Builder()
                    .url(resultsUrl)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .get()
                    .build()
                client.newCall(results).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw ApiException("Результат не забрался: HTTP ${response.code}")
                    }
                    // Результаты приезжают построчным JSON (JSONL) и в любом
                    // порядке — у нас в батче одна заявка, берём первую строку
                    // с нашим custom_id.
                    val line = text.lineSequence()
                        .map { it.trim() }
                        .filter { it.startsWith("{") }
                        .firstOrNull { it.contains("\"analysis\"") }
                        ?: throw ApiException("Батч закончился, но результата нет")
                    val o = JSONObject(line)
                    val result = o.optJSONObject("result") ?: JSONObject()
                    when (result.optString("type")) {
                        "succeeded" -> {
                            val message = result.optJSONObject("message") ?: JSONObject()
                            val content = message.optJSONArray("content") ?: JSONArray()
                            val out = StringBuilder()
                            for (i in 0 until content.length()) {
                                val block = content.optJSONObject(i) ?: continue
                                if (block.optString("type") == "text") out.append(block.optString("text"))
                            }
                            val usage = message.optJSONObject("usage") ?: JSONObject()
                            val tin = usage.optInt("input_tokens") +
                                usage.optInt("cache_creation_input_tokens") +
                                usage.optInt("cache_read_input_tokens")
                            val tout = usage.optInt("output_tokens")
                            BatchAnswer(
                                text = out.toString().trim(),
                                // Батч стоит половину обычного вызова.
                                costUsd = Pricing.costUsd(
                                    model,
                                    inputTokens = usage.optInt("input_tokens"),
                                    outputTokens = tout,
                                    cacheWriteTokens = usage.optInt("cache_creation_input_tokens"),
                                    cacheReadTokens = usage.optInt("cache_read_input_tokens"),
                                ) / 2.0,
                                tokensIn = tin,
                                tokensOut = tout,
                            )
                        }
                        "errored" -> BatchAnswer(
                            "", 0.0, 0, 0,
                            error = result.optJSONObject("error")?.optString("message")
                                .orEmpty().ifBlank { "модель вернула ошибку" },
                        )
                        "expired" -> BatchAnswer("", 0.0, 0, 0, error = "батч просрочен, нужен новый")
                        else -> BatchAnswer("", 0.0, 0, 0, error = "батч отменён")
                    }
                }
            }
        }

    private fun costUsd(model: String, reply: ApiReply): Double = Pricing.costUsd(
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

    private fun requestWithOneRetry(
        apiKey: String,
        model: String,
        parts: Prompts.PromptParts,
        input: String,
        onDelta: ((String) -> Unit)?,
        images: List<ImagePart> = emptyList(),
        maxTokensOverride: Int = 0,
        effortOverride: String = "",
    ): ApiReply {
        // Spec 6.1: one retry on network error or timeout; none on client 4xx.
        // Transient server blips (429/500/529 "overloaded") last seconds - one
        // short-backoff retry turns them from a user-visible failure into
        // nothing. A short pause before the network retry too: an instant
        // re-POST into the same dead socket just fails the same way.
        return try {
            request(apiKey, model, parts, input, onDelta, images, maxTokensOverride, effortOverride)
        } catch (e: IOException) {
            Thread.sleep(1000)
            request(apiKey, model, parts, input, onDelta, images, maxTokensOverride, effortOverride)
        } catch (e: ApiException) {
            if (!e.retryable) throw e
            Thread.sleep(e.retryDelayMs)
            request(apiKey, model, parts, input, onDelta, images, maxTokensOverride, effortOverride)
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
        // Opus (redo chips) thinks adaptively by default, and thinking tokens
        // count toward max_tokens: without headroom a 350-char redo burned the
        // whole budget on thinking and died with stop_reason=max_tokens before
        // emitting a single word (owner saw an endless spinner, 2026-08-18).
        val thinkingHeadroom = if (model == Settings.MODEL_SONNET) 0 else 8000
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
                                        // Cache only the everyday Sonnet path: a
                                        // rare Opus redo would pay the 2x cache
                                        // write and likely never read it back.
                                        // cacheStableAlways — для Опус-путей,
                                        // которые ходят часто (Засечка).
                                        if (model == Settings.MODEL_SONNET || parts.cacheStableAlways) {
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
