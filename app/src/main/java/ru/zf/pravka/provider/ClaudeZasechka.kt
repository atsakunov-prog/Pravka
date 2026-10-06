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

import ru.zf.pravka.provider.ClaudeProvider.ApiException
import ru.zf.pravka.provider.ClaudeProvider.ApiReply
import ru.zf.pravka.provider.ClaudeProvider.ImagePart
import ru.zf.pravka.provider.ClaudeProvider.LearnProposals
import ru.zf.pravka.provider.ClaudeProvider.DictProposal
import ru.zf.pravka.provider.ClaudeProvider.RuleProposal
import ru.zf.pravka.provider.ClaudeProvider.OptimizedRules
import ru.zf.pravka.provider.ClaudeProvider.ZasechkaParse
import ru.zf.pravka.provider.ClaudeProvider.ZasechkaForkReply
import ru.zf.pravka.provider.ClaudeProvider.SplitResult
import ru.zf.pravka.provider.ClaudeProvider.FoodParse
import ru.zf.pravka.provider.ClaudeProvider.BodyParse
import ru.zf.pravka.provider.ClaudeProvider.SetParse
import ru.zf.pravka.provider.ClaudeProvider.ExerciseParse
import ru.zf.pravka.provider.ClaudeProvider.StrengthParse
import ru.zf.pravka.provider.ClaudeProvider.GtgParse
import ru.zf.pravka.provider.ClaudeProvider.FeelParse
import ru.zf.pravka.provider.ClaudeProvider.RulesParse
import ru.zf.pravka.provider.ClaudeProvider.CoachAnswer
import ru.zf.pravka.provider.ClaudeProvider.BatchAnswer

// Засечка: одна надиктованная фраза -> структурная запись ленты (заводская модель — Опус,
// меняется в настройках → «Модели»; правила под часовым кэшем),
// плюс самообучение: поправки владельца -> правила промпта батчем.
//
// Расширения ClaudeProvider: транспорт (запрос, стрим, кэш, деньги) живёт в нём,
// разбор режима — здесь, чтобы правка промпта Засечки не заставляла читать чужие.

/**
 * Опус превращает «созвон с Ивановым по отчёту, последние полчаса» в
 * {action, title, category, ...}. Был Сонет — владелец: «очень плохо
 * получается у Сонета понимать, как работать с засечкой»: терялись
 * ретро-вставки, категории спорили с прецедентом, названия вырождались
 * в имя категории. Свод правил лежит в stablePrefix под часовым кэшем —
 * Опус ходит сюда десятки раз в день, и кэш окупается с двух вызовов.
 * Падение разбора не теряет сказанное: вызывающий пишет сырым.
 */
suspend fun ClaudeProvider.zasechka(
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
    // Одобренные владельцем правила Засечки: как он говорит о своём
    // времени. Едут в переменный хвост, а не под кэш: список живой.
    ownerRules: String = "",
    // Микрофон в редакторе записи: какую строку владелец правит. Пусто —
    // обычный тап по «З».
    editTargetLine: String = "",
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
        // Свод правил — в core/prompts (заводской) и в Своде (`prompt.zasechka`, docs/svod-phone.md).
        val stableRules = ru.zf.pravka.core.Svod.text(ru.zf.pravka.core.prompts.PromptsZasechka.SVOD_KEY, ru.zf.pravka.core.prompts.PromptsZasechka.RULES).trimEnd() + "\n\n"
        val editBlock = if (editTargetLine.isBlank()) "" else "\n$editTargetLine\n"
        val varTail = """
Сейчас: $nowLocal.
$previousBlock
Записи дня (№ · время · категория · название):
$todayBlock
$editBlock$recentBlock
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
        //
        // Выученные правила владельца живут ЗДЕСЬ, в конце стабильной
        // части, а не в хвосте. Две причины. Читаются они как часть свода
        // правил, а не как ещё одно поле рядом с фразой, — и модель
        // относится к ним соответственно. И меняются они раз в несколько
        // дней, а фраза приходит десятки раз в день: под кэшем они почти
        // всегда бесплатны, в хвосте платились бы каждый раз.
        // Не владелец (профиль): кто диктует — первой строкой свода. Свод
        // написан про Сашу, а лента — того, кто говорит.
        val stableWithOwner = Prompts.speakerNote(author()) +
            (if (ownerRules.isBlank()) stableRules else stableRules + ownerRules + "\n\n")
        val parts = Prompts.PromptParts(
            stablePrefix = stableWithOwner,
            cacheStableAlways = true,
            dictPart = varTail,
            afterInput = "",
        )
        val choice = settings.modelChoice(ModelRoute.ZASECHKA)
        val reply = requestWithOneRetry(
            apiKey, choice.model, parts, "", null,
            effortOverride = choice.effort,
            routeKey = ModelRoute.ZASECHKA.key,
            paceChars = raw.length,
        )
        parseZasechka(reply.text).copy(
            costUsd = costUsd(choice.model, reply),
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
        )
    }
}

/**
 * Развилка Засечки (26.09.2026): лента, мысль к текущему делу, еда или дела —
 * до разбора Опусом. Сонет на low без размышлений: ответ — одно слово в JSON,
 * и секунда здесь дороже глубины. Ответ читает `ZasechkaIntent.fromModel`;
 * сырой текст уходит наверх, чтобы непрочитанный ответ был виден в журнале
 * целиком, а не общей фразой. [context] — «Сейчас: … Идёт: …», хвост после
 * {NOW}: правила над ним стабильны и стоят под кэшем.
 */
suspend fun ClaudeProvider.zasechkaFork(raw: String, context: String): Result<ZasechkaForkReply> =
    withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) {
                throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
            }
            require(raw.isNotBlank()) { "Пустая фраза — развилке нечего решать." }
            val template = Prompts.speakerNote(author()) + promptStore.effective(PromptStore.PromptId.ZASECHKA_FORK)
            val cut = template.indexOf("{NOW}")
            val head = if (cut > 0) template.substring(0, cut) else ""
            var tail = (if (cut > 0) template.substring(cut) else template).replace("{NOW}", context)
            tail = if (tail.contains(Prompts.PLACEHOLDER_INPUT)) {
                tail.replace(Prompts.PLACEHOLDER_INPUT, raw)
            } else {
                // Владелец потерял {INPUT} в «Промптах»: фраза дописывается в
                // конец, а не теряется — то же правило, что у Разноски.
                tail.trimEnd() + "\n\nФраза:\n" + raw
            }
            val parts = Prompts.PromptParts(
                stablePrefix = head,
                dictPart = tail,
                afterInput = "",
                cacheStableAlways = head.isNotBlank(),
            )
            val started = System.currentTimeMillis()
            val choice = settings.modelChoice(ModelRoute.ZASECHKA_FORK)
            val reply = requestWithOneRetry(
                apiKey, choice.model, parts, "", null,
                effortOverride = choice.effort,
                routeKey = ModelRoute.ZASECHKA_FORK.key,
                paceChars = raw.length,
            )
            ZasechkaForkReply(
                raw = reply.text,
                costUsd = costUsd(choice.model, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
                latencyMs = System.currentTimeMillis() - started,
            )
        }
    }

private fun ClaudeProvider.parseZasechka(raw: String): ZasechkaParse {
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
        // Старый разбор мог ответить "parallel" — второго трека больше нет,
        // такой ответ читается как «фон, не записываю».
        action = o.optString("action", "new").trim().lowercase(java.util.Locale.US)
            .let { if (it == "parallel") "none" else it }
            .takeIf { it in listOf("new", "insert", "edit", "delete", "stop", "none") }
            ?: "new",
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
        say = o.optString("say").trim().ifBlank { if (o.optString("action") == "parallel") "фон не записываю" else "" },
        startTime = clockField(o, "start_time"),
        endTime = clockField(o, "end_time"),
        costUsd = 0.0,
        tokensIn = 0,
        tokensOut = 0,
    )
}

/** "16:43" or "" - anything that is not a clock time is dropped. */
private fun ClaudeProvider.clockField(o: JSONObject, key: String): String {
    val v = o.optString(key).trim()
    return if (Regex("^\\d{1,2}:\\d{2}$").matches(v)) v else ""
}
