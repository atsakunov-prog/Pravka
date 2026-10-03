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

// Разноска: наговор -> дела в Todoist или в Дела на домашнем сервере (заводская
// модель — Опус, меняется в настройках → «Модели»).
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
        val call = askSplit(PromptStore.PromptId.TASKS, transcript, dictBlock, catalogBlock)
        val (tasks, notes) = parseTasks(call.reply.text, knownLabels, resolveProject)
        call.result(tasks, notes)
    }
}

/**
 * Разноска для Дел на домашнем сервере (docs/dela-server.md): тот же разбор,
 * свой промпт. Модель называет проект и человека так, как они стоят в
 * справочнике синка; [snapshot] превращает названное в id — наружу выходит
 * то, что сервер примет. «Не дела» — поштучно, с проектом и людьми.
 */
suspend fun ClaudeProvider.splitTasksDela(
    transcript: String,
    dictBlock: String,
    catalogBlock: String,
    snapshot: ru.zf.pravka.core.Dela.Snapshot,
): Result<SplitResult> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val call = askSplit(PromptStore.PromptId.TASKS_DELA, transcript, dictBlock, catalogBlock)
        val (tasks, notes) = parseTasksDela(call.reply.text, snapshot)
        call.result(tasks, notes.joinToString("\n") { it.text }, notes)
    }
}

/** Ответ модели и всё, что нужно, чтобы сложить из него SplitResult. */
private class SplitCall(val reply: ApiReply, val model: String, val costUsd: Double, val started: Long) {
    fun result(tasks: List<ParsedTask>, notes: String, items: List<ru.zf.pravka.core.ParsedNote> = emptyList()) = SplitResult(
        tasks = tasks,
        notes = notes,
        costUsd = costUsd,
        tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
        tokensOut = reply.outputTokens,
        model = model,
        latencyMs = System.currentTimeMillis() - started,
        noteItems = items,
    )
}

private suspend fun ClaudeProvider.askSplit(
    promptId: PromptStore.PromptId,
    transcript: String,
    dictBlock: String,
    catalogBlock: String,
): SplitCall {
    val apiKey = settings.apiKey()
    if (apiKey.isBlank()) {
        throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
    }
    require(transcript.isNotBlank()) { "Пустой наговор — разбирать нечего." }
    val template = Prompts.speakerNote(author()) + promptStore.effective(promptId)
    val catalog = catalogBlock.ifBlank {
        "Каталог проектов не загружен — оставь project пустым, владелец выберет сам."
    }
    // Кэш (16.09.2026): правила и каталог проектов стабильны от наговора к
    // наговору — они голова под часовым кэшем; дата, словарь и сам наговор
    // — хвост. Граница — {TODAY}: в заводском шаблоне он стоит после
    // каталога. Если владелец в «Промптах» увёл словарь выше даты, голова
    // менялась бы каждый раз — тогда кэш не ставим, а не платим за запись.
    val cut = template.indexOf("{TODAY}")
    fun fill(s: String) = s
        .replace("{CATALOG}", catalog)
        .replace("{TODAY}", todayContext())
        .replace(Prompts.PLACEHOLDER_DICT, dictBlock.ifBlank { "—" })
    val headTemplate = if (cut > 0) template.substring(0, cut) else ""
    val head = fill(headTemplate)
    var tail = fill(if (cut > 0) template.substring(cut) else template)
    tail = if (tail.contains(Prompts.PLACEHOLDER_INPUT)) {
        tail.replace(Prompts.PLACEHOLDER_INPUT, transcript)
    } else {
        // Владелец отредактировал промпт и потерял {INPUT}: дописываем
        // наговор в конец, а не теряем его - то же правило, что в
        // Prompts.assemble().
        tail.trimEnd() + "\n\nНаговор:\n" + transcript
    }
    val parts = Prompts.PromptParts(
        stablePrefix = head,
        dictPart = tail,
        afterInput = "",
        cacheStableAlways = head.isNotBlank() && !headTemplate.contains(Prompts.PLACEHOLDER_DICT),
    )
    val started = System.currentTimeMillis()
    val choice = settings.modelChoice(ModelRoute.RAZNOSKA)
    val reply = requestWithOneRetry(
        apiKey, choice.model, parts, "", null,
        effortOverride = choice.effort,
        routeKey = ModelRoute.RAZNOSKA.key,
        paceChars = transcript.length,
    )
    return SplitCall(reply, choice.model, costUsd(choice.model, reply), started)
}

/** JSON из ответа модели: без markdown-обёртки, от первой «{» до последней «}». */
private fun replyJson(raw: String): JSONObject {
    var text = raw.trim()
    if (text.startsWith("```")) {
        text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) {
        throw ApiException("Модель ответила не JSON. Наговор сохранён — разбери ещё раз.")
    }
    return runCatching { JSONObject(text.substring(start, end + 1)) }
        .getOrElse { throw ApiException("Модель вернула не тот формат — разбери ещё раз.") }
}

/**
 * Ответ разбора для Дел — в дела с id справочника. Чистая — под тестом.
 * id дела и op_id ставятся здесь, с первой секунды: повтор отправки уйдёт
 * с теми же ключами. Человек без проекта тянет проект своей организации
 * не здесь, а на сервере — телефон не угадывает за справочник.
 */
internal fun parseTasksDela(raw: String, snapshot: ru.zf.pravka.core.Dela.Snapshot): Pair<List<ParsedTask>, List<ru.zf.pravka.core.ParsedNote>> {
    val dela = ru.zf.pravka.core.Dela
    val o = replyJson(raw)
    val known = snapshot.labels
    val out = mutableListOf<ParsedTask>()
    val array = o.optJSONArray("tasks") ?: JSONArray()
    for (i in 0 until array.length()) {
        val t = array.optJSONObject(i) ?: continue
        val title = t.optString("title").ifBlank { t.optString("content") }.trim()
        if (title.isEmpty()) continue
        val named = t.optString("project").trim()
        val project = dela.findProject(snapshot, named)
        val personNamed = t.optString("person").trim()
        val person = dela.findPerson(snapshot, personNamed)
        var ball = t.optString("ball").trim().lowercase().takeIf { it in setOf(dela.MINE, dela.WAITING, dela.AGENDA) } ?: dela.MINE
        // «Жду» и «повестка» без человека — это никого не ждать: дело остаётся моим.
        if (ball != dela.MINE && person == null && personNamed.isBlank()) ball = dela.MINE
        val labels = mutableListOf<String>()
        t.optJSONArray("labels")?.let { la ->
            for (j in 0 until la.length()) {
                val name = la.optString(j).trim().removePrefix("@")
                // Только метки, которые на сервере правда есть: свободные контексты.
                known.firstOrNull { it.equals(name, ignoreCase = true) }?.let { labels += it }
            }
        }
        val due = t.optString("due").trim()
        val money = t.optString("money").trim().lowercase().takeIf { it == "paid" || it == "potential" }.orEmpty()
        out += ParsedTask(
            id = (i + 1).toLong(),
            content = title,
            description = t.optString("notes").ifBlank { t.optString("description") }.trim(),
            projectId = project?.id.orEmpty(),
            // Не нашли — сказанное моделью остаётся: в редакторе видно, что проект выбрать руками.
            projectName = project?.name ?: named,
            labels = labels.distinct(),
            due = if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(due)) due else "",
            ball = ball,
            personId = person?.id.orEmpty(),
            personName = person?.label ?: personNamed,
            estimateMin = t.optInt("estimate_min", 0).coerceIn(0, 24 * 60),
            money = money,
            want = t.optBoolean("want", false),
            delaId = dela.newId(),
            opId = dela.newId(),
        )
    }
    val notes = mutableListOf<ru.zf.pravka.core.ParsedNote>()
    when (val n = o.opt("notes")) {
        is JSONArray -> for (i in 0 until n.length()) {
            val item = n.opt(i)
            val text: String
            var projectName = ""
            var personName = ""
            if (item is JSONObject) {
                text = item.optString("text").trim()
                projectName = item.optString("project").trim()
                personName = item.optString("person").trim()
            } else {
                text = item?.toString()?.trim().orEmpty()
            }
            if (text.isEmpty() || text == "null") continue
            val project = dela.findProject(snapshot, projectName)
            val person = dela.findPerson(snapshot, personName)
            notes += ru.zf.pravka.core.ParsedNote(
                text = text,
                projectId = project?.id.orEmpty(),
                projectName = project?.name.orEmpty(),
                personIds = listOfNotNull(person?.id),
                id = dela.newId(),
                opId = dela.newId(),
            )
        }
        is String -> n.lines().map { it.trim().removePrefix("—").removePrefix("-").trim() }.filter { it.isNotEmpty() }
            .forEach { notes += ru.zf.pravka.core.ParsedNote(text = it, id = dela.newId(), opId = dela.newId()) }
    }
    return out to notes
}

private fun ClaudeProvider.parseTasks(
    raw: String,
    knownLabels: List<String>,
    resolveProject: (String) -> Pair<String, String>?,
): Pair<List<ParsedTask>, String> {
    val o = replyJson(raw)
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
