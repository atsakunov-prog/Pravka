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

// Разноска: наговор -> дела в Todoist (заводская модель — Опус, меняется в настройках → «Модели»).
// Расширения ClaudeProvider: транспорт там, разбор здесь.

/**
 * Разбирает один наговор на дела для Todoist. Работает на Опусе: трудное
 * здесь не формулировка, а суждение - что вообще является делом, у кого
 * мяч, в какой проект оно ложится.
 *
 * Проекты модель называет так, как они стоят в каталоге; [resolveProject]
 * превращает названное в настоящий id, поэтому наружу выходит уже то, что
 * Todoist примет. Промпт правится владельцем во вкладке «Промпты».
 */
suspend fun ClaudeProvider.splitTasks(
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
        val choice = settings.modelChoice(ModelRoute.RAZNOSKA)
        val reply = requestWithOneRetry(
            apiKey, choice.model, parts, "", null,
            effortOverride = choice.effort,
        )
        val (tasks, notes) = parseTasks(reply.text, knownLabels, resolveProject)
        SplitResult(
            tasks = tasks,
            notes = notes,
            costUsd = costUsd(choice.model, reply),
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
            model = choice.model,
            latencyMs = System.currentTimeMillis() - started,
        )
    }
}

private fun ClaudeProvider.parseTasks(
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
