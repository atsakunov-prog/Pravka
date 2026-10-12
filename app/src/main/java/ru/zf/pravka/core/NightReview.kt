package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.prompts.PromptsReview
import ru.zf.pravka.data.CorrectionsLog
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.NightReviewStore
import ru.zf.pravka.data.NightReviewStore.Change
import ru.zf.pravka.data.NightReviewStore.Run
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.RulesStore
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.TranscriptionLog
import ru.zf.pravka.provider.ClaudeBatches
import ru.zf.pravka.provider.RequestPolicy

/**
 * Ночной разбор (16.09.2026). Владелец: «чтобы он каждую ночь делал всё, что
 * ты сейчас сделал: сам правил и применял то, что высоковероятно, а
 * низковероятное предлагал; и чтобы ещё один Fable его проверял».
 *
 * Конвейер на батчах: первый проход одним запросом (три задачи — словарь и
 * ослышки · поведение модели · правила — над одним пакетом свидетельств,
 * который лежит системным блоком под часовым кэшем); когда батч готов —
 * согласование в коде (NightReviewPolicy.consistencyHolds: противоречия
 * внутри набора и отмена своих же недавних решений придерживаются) и батч
 * проверки по тем же свидетельствам (они читаются из кэша); потом высокое,
 * не отклонённое проверкой, применяется, остальное ложится предложениями,
 * утром владелец читает отчёт и отвечает текстом. Всё состояние — в
 * NightReviewStore: процесс может умереть на любой стадии, тик службы
 * продолжит с того же места.
 *
 * Третий проход — согласование моделью — снят 18.09.2026: за первые двое суток
 * ночь стоила две трети трат приложения, а всё, что ловило согласование
 * (одно слово в двух изменениях, включить и выключить одно правило, отмена
 * вчерашнего применённого), считается без модели и бесплатно.
 *
 * С 12.10.2026 разбор ничего не оставляет владельцу (владелец: «всё должно
 * идти автоматом, я там не нужен»): одобренное проверкой применяется, всё
 * остальное — «не применено» с причиной. Обрезанный по длине проход
 * повторяется на ступень ниже усилия; сбой сети не закрывает прогон.
 */
class NightReview(
    private val settings: Settings,
    private val batches: ClaudeBatches,
    private val store: NightReviewStore,
    private val dictionary: DictionaryStore,
    private val rules: RulesStore,
    private val history: HistoryLog,
    private val transcripts: TranscriptionLog,
    private val corrections: CorrectionsLog,
    private val prompts: PromptStore,
    private val stats: Stats,
    private val log: EventLog,
) {
    companion object {
        /** Статус батча спрашиваем не чаще чем раз в десять минут: он идёт до часа. */
        const val POLL_MS = 10 * 60_000L
        /** Окно счётчиков повторов: неделя у дневного прогона, месяц у недельного. */
        const val DAILY_AGG_MS = 7 * 86_400_000L
        const val WEEKLY_AGG_MS = 30 * 86_400_000L
        /** Память решений, которую видят оба прохода. */
        const val LEDGER_MS = 30 * 86_400_000L
        /** Батч живёт сутки; сутки с запасом без результата — прогон провален. */
        const val EXPIRE_MS = 26 * 3600_000L
        /** Окно «разбор сам положил на этой неделе»: выключать такое без владельца нельзя. */
        const val RECENT_ADDS_MS = 7 * 86_400_000L
        private const val OUT_TOKENS = 8000
        /** Усилие, на котором ушёл текущий батч прохода, — в evidence прогона: повтор спускается от него. */
        private const val EFFORT_ANALYSIS = "effort_analysis"
        private const val EFFORT_CHECK = "effort_check"
        /** Заметки о повторах на ступень ниже — в сводку прогона. */
        private const val RETRIES = "retries"
    }

    private val mutex = Mutex()
    private val dayFmt = SimpleDateFormat("dd.MM", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    private fun date(ms: Long) = dayFmt.format(Date(ms))

    /** Тик службы, раз в несколько минут: продвинуть активный прогон или запустить назревший. */
    suspend fun tick(nowMs: Long = System.currentTimeMillis()) {
        if (!settings.nightReviewEnabledFlow.first()) return
        if (settings.apiKey().isBlank()) return
        if (!mutex.tryLock()) return
        try {
            // Тень и правка промпта живут в том же сторе, но ведут их ShadowRun и PromptTuner.
            val runs = store.all().filter { it.isReview }
            val active = runs.filter { it.active }
            for (run in active) {
                if (nowMs - run.lastPollAt < POLL_MS) continue
                runCatching { poll(run, nowMs) }.onFailure { failOrWait(run, it) }
            }
            if (active.isEmpty()) {
                val hour = settings.nightReviewHourFlow.first()
                val lastDaily = runs.filter { it.kind == NightReviewPolicy.DAILY }.maxOfOrNull { it.startedAt } ?: 0L
                val weeklyRuns = runs.filter { it.kind == NightReviewPolicy.WEEKLY }
                val friday = NightReviewPolicy.lastMark(nowMs, hour, java.util.Calendar.FRIDAY)
                val sinceFriday = weeklyRuns.filter { it.startedAt >= friday && it.error != "отменено владельцем" }
                // Упавший недельный этой недели — ещё одна попытка, но одна: второй
                // провал подряд — это уже не случайность, кругами деньги не жжём.
                val lastWeekly = if (sinceFriday.size == 1 && NightBoard.failed(sinceFriday[0])) 0L
                else weeklyRuns.maxOfOrNull { it.startedAt } ?: 0L
                // Один прогон за тик; в пятницу идёт только недельный — сутки в нём и так есть.
                val dailyOn = settings.nightDailyEnabledFlow.first()
                NightReviewPolicy.dueKinds(nowMs, hour, lastDaily, lastWeekly, dailyOn).firstOrNull()?.let { kind ->
                    // Потолок дня для автоматов: расход по приложению за сутки выше — не
                    // стартуем сами. Сутки — вчера или сегодня, не «с полуночи»: в три
                    // ночи сегодняшний счёт пуст, и потолок не ловил ничего.
                    val budget = settings.nightBudgetUsdFlow.first()
                    val spent = stats.costRecentDayUsd()
                    if (spent > budget) {
                        // Раз в сутки, а не каждые пять минут: 25.09 одна и та же
                        // строка легла в журнал 224 раза и вытеснила всё остальное.
                        // Пропущенную неделю догонит следующий день (dueKinds).
                        logOncePerDay("budget-$kind", nowMs) {
                            "ночной разбор ($kind): не стартую — расход за сутки $" + "%.2f".format(Locale.US, spent) +
                                " выше потолка $$budget; догоню, когда расход опустится"
                        }
                        return@let
                    }
                    start(kind, manual = false, nowMs = nowMs).onFailure {
                        logOncePerDay("start-$kind", nowMs) { "ночной разбор: не запустился ($kind): ${it.message} — попробую на следующих тиках" }
                    }
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Запуск прогона: собрать свидетельства, отправить батч первого прохода. */
    suspend fun start(kind: String, manual: Boolean, nowMs: Long = System.currentTimeMillis()): Result<Run> = runCatching {
        val (from, to) = if (kind == NightReviewPolicy.NOW) NightReviewPolicy.windowNow(nowMs, lastReviewedTo())
        else NightReviewPolicy.window(kind, nowMs)
        val evidence = gather(NightReviewPolicy.weekLike(kind, from, to), from, to)
        if (evidence.isEmpty()) {
            // День без диктовок тоже фиксируется — иначе тик пробовал бы запуск до вечера.
            val empty = Run(
                id = nowMs, kind = kind, startedAt = nowMs, fromMs = from, toMs = to, stage = "done", manual = manual,
                summary = "За период не было ни чисток, ни правок — разбирать нечего.", finishedAt = nowMs,
            )
            store.save(empty)
            return@runCatching empty
        }
        val choice = settings.modelChoice(ModelRoute.NIGHT_REVIEW)
        val batchId = submitAnalysis(evidence, choice.model, choice.effort)
        val run = Run(
            id = nowMs, kind = kind, startedAt = nowMs, fromMs = from, toMs = to, stage = "analysis", manual = manual,
            analysisBatchId = batchId, evidence = evidence + (EFFORT_ANALYSIS to choice.effort), lastPollAt = nowMs,
        )
        store.save(run)
        log.add("ночной разбор ($kind): батч $batchId, ${choice.model} ${choice.effort}, свидетельств ${evidence["common"].orEmpty().length} зн.")
        run
    }

    /**
     * Батч первого прохода. Свидетельства — системным блоком под часовым кэшем:
     * проверка и повтор на ступень ниже читают их же из кэша. Инструкции и
     * специфика ночи — в пользовательском сообщении.
     */
    private suspend fun submitAnalysis(evidence: Map<String, String>, model: String, effort: String): String {
        val user = listOf(PromptsReview.ANALYZE_SYSTEM, PromptsReview.ANALYZE_TASKS, evidence["specific"].orEmpty())
            .filter { it.isNotBlank() }.joinToString("\n\n")
        return batches.create(
            listOf("all" to ClaudeBatches.params(model, effort, OUT_TOKENS, evidence["common"].orEmpty(), user, cacheSystem = true))
        )
    }

    /** Конец окна последнего удачного разбора — откуда начинает «Разобрать сейчас». */
    private suspend fun lastReviewedTo(): Long =
        store.all().filter { it.isReview && it.stage == "done" && !NightBoard.failed(it) }.maxOfOrNull { it.toMs } ?: 0L

    private val loggedDay = HashMap<String, String>()

    /** Повторяющаяся строка журнала — раз в сутки на ключ. */
    private fun logOncePerDay(key: String, nowMs: Long, line: () -> String) {
        val day = date(nowMs)
        if (loggedDay[key] == day) return
        loggedDay[key] = day
        log.add(line())
    }

    // ---- свидетельства ----

    private suspend fun gather(weekly: Boolean, from: Long, to: Long): Map<String, String> {
        val pairs = history.readEntries(from, to)
        val corr = corrections.all().filter { it.ts in from until to }
        if (pairs.isEmpty() && corr.isEmpty()) return emptyMap()
        val takes = transcripts.readRange(from, to)
        val dict = dictionary.all()
        val ruleList = rules.all()
        // Повторы — за окно длиннее сырых текстов, счётчиками (см. NightReviewEvidence).
        val aggFrom = to - if (weekly) WEEKLY_AGG_MS else DAILY_AGG_MS
        val agg = NightReviewEvidence.aggregate(history.readEntries(aggFrom, to).map { it.input to it.output })
        val aggBlock = NightReviewEvidence.render(agg, "ПОВТОРЫ за ${if (weekly) 30 else 7} дней (что во что модель заменяла, сколько раз)")
        val ledger = ledgerBlock(dict)
        val days = ((to - from + 43_200_000L) / 86_400_000L).coerceAtLeast(1)
        val period = "Период: ${date(from)}–${date(to)} (${if (days == 1L) "сутки" else if (days == 7L) "неделя" else "$days дн."}). " +
            "Диктовок ${takes.size} (пустых ${takes.count { !it.ok }}), чисток моделью ${pairs.size}, правок руками ${corr.size}."
        val pairsBlock = block("ЧИСТКИ — <d> надиктовано, <m> что сделала модель", pairs, if (weekly) 90_000 else 40_000) {
            "<d>${it.input}</d>\n<m>${it.output}</m>"
        }
        val corrBlock = block("ПРАВКИ РУКАМИ — <d> надиктовано, <m> модель, <o> как поправил владелец (эталон)", corr, 30_000) {
            "<d>${it.dictated}</d>\n<m>${it.cleaned}</m>\n<o>${it.edited}</o>"
        }
        val dictBlock = "СЛОВАРЬ (id|вид|from|to|срабатываний|вкл|заметка):\n" +
            dict.joinToString("\n") { "${it.id}|${it.mode}|${it.from}|${it.to}|${it.hits}|${if (it.enabled) 1 else 0}|${it.note.take(60)}" }
        val rulesOn = settings.rulesInPromptFlow.first()
        val rulesBlock = if (ruleList.isEmpty()) "" else
            "ПРАВИЛА ОБУЧЕНИЯ (id|вкл|текст). Блок правил в промпт сейчас ${if (rulesOn) "ВКЛЮЧЁН" else "ВЫКЛЮЧЕН тумблером владельца"}:\n" +
                ruleList.joinToString("\n") { "${it.id}|${if (it.enabled) 1 else 0}|${it.text}" }
        val glitches = mixedScript(takes)
        // Общее для первого прохода и проверки — самое большое и едет дважды,
        // поэтому под кэш (system с часовым сроком): период, повторы, память,
        // тексты. Специфика — словарь, правила, промпт — в пользовательском
        // сообщении: она тоже одинаковая, но кэш держит одну точку на блок.
        val common = listOf(
            "СВИДЕТЕЛЬСТВА НОЧНОГО РАЗБОРА", period, aggBlock, ledger,
            if (glitches.isBlank()) "" else "Слова со смешанным алфавитом у распознавателя: $glitches",
            pairsBlock, corrBlock,
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        // Правила — состояние, которое меняется редко: каждую ночь их гонять
        // незачем, если блок в промпте выключен. Недельный смотрит всегда.
        val state = listOf(dictBlock, if (weekly || rulesOn) rulesBlock else "").filter { it.isNotBlank() }.joinToString("\n\n")
        // Промпт CLEAN нужен только первому проходу (заметки про поведение
        // модели); проверке — словарь и правила без него: восемь тысяч токенов
        // промпта каждую ночь второй раз — деньги впустую.
        val specific = listOf(
            state,
            "ПРОМПТ CLEAN, действующий сейчас (не правь, только заметки):\n" + prompts.effective(ProofreadMode.CLEAN),
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        // Тексты окна отдельно — по ним дневной прогон отсеивает правки записей
        // словаря, чьих слов в этот день не было (NightReviewPolicy.mentioned).
        return mapOf("common" to common, "specific" to specific, "state" to state, "texts" to pairsBlock + "\n" + corrBlock)
    }

    /** Память решений для промптов: прошлые прогоны + сколько раз сработало применённое. */
    private suspend fun ledgerBlock(dict: List<DictEntry>): String {
        val runs = store.all()
        val since = System.currentTimeMillis() - LEDGER_MS
        val byId = dict.associateBy { it.id }
        return NightReviewEvidence.ledger(runs, since, hitsSince = { id -> byId[id]?.hits })
    }

    private fun <T> block(title: String, items: List<T>, maxChars: Int, render: (T) -> String): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder(title).append(" — ").append(items.size).append(":\n")
        // Свежее важнее: режем с начала периода, если не влезает.
        val rendered = items.map(render)
        var used = 0
        val kept = ArrayList<String>()
        for (s in rendered.asReversed()) {
            if (used + s.length > maxChars) break
            kept.add(s); used += s.length
        }
        if (kept.size < rendered.size) sb.append("(показаны последние ${kept.size})\n")
        kept.asReversed().forEach { sb.append(it).append("\n\n") }
        return sb.toString().trimEnd()
    }

    private fun mixedScript(takes: List<TranscriptionLog.Entry>): String {
        val re = Regex("\\S+")
        val found = LinkedHashSet<String>()
        for (t in takes) for (w in re.findAll(t.text)) {
            val s = w.value
            if (s.any { it in 'А'..'я' } && s.any { it in 'A'..'z' }) found.add(s)
            if (found.size >= 30) break
        }
        return found.joinToString(", ")
    }

    // ---- продвижение прогона ----

    private suspend fun poll(run: Run, nowMs: Long) {
        var r = run.copy(lastPollAt = nowMs)
        store.save(r)
        if (nowMs - r.startedAt > EXPIRE_MS) throw IllegalStateException("батч не завершился за сутки")
        if (r.stage == "audit") {
            // Прогон прежней сборки застал снятие третьего прохода: его батч гасим
            // (что успел — оплачено), а вердикты проверки у него уже есть — применяем.
            runCatching { batches.cancel(r.auditBatchId) }
            log.add("ночной разбор (${r.kind}): согласование моделью снято — батч ${r.auditBatchId} отменён, применяю по вердиктам проверки")
            store.save(applyAndFinish(r))
            return
        }
        val batchId = if (r.stage == "analysis") r.analysisBatchId else r.checkBatchId
        val st = batches.status(batchId)
        if (!st.ended) {
            log.add("ночной разбор: батч $batchId ещё идёт (в работе ${st.inFlight})")
            store.save(r.copy(progress = "батч у Anthropic: ${if (st.inFlight > 0) "в работе" else st.processing} · опрос ${timeFmt.format(Date(nowMs))}"))
            return
        }
        val url = st.resultsUrl
            ?: throw IllegalStateException("батч завершён без результатов (ошибок ${st.errored}, истекло ${st.expired})")
        val items = batches.results(url)
        val route = if (r.stage == "analysis") ModelRoute.NIGHT_REVIEW else ModelRoute.NIGHT_CHECK
        val choice = settings.modelChoice(route)
        val cost = ClaudeBatches.costUsd(items, choice.model)
        val tokensIn = items.sumOf { it.inputTokens + it.cacheRead + it.cacheWrite }
        val cacheRead = items.sumOf { it.cacheRead }
        val cacheWrite = items.sumOf { it.cacheWrite }
        stats.recordAux(cost, tokensIn, items.sumOf { it.outputTokens }, route = route.key)
        stats.recordCache(cacheRead, cacheWrite)
        stats.recordRouteUsage(route.key, tokensIn, cacheRead)
        log.add("ночной разбор (${r.stage}): вход $tokensIn токенов, из кэша $cacheRead, записано в кэш $cacheWrite")
        r = r.copy(costUsd = r.costUsd + cost, inputTokens = r.inputTokens + tokensIn, cacheReadTokens = r.cacheReadTokens + cacheRead)
        r = if (r.stage == "analysis") afterAnalysis(r, items) else afterCheck(r, items)
        store.save(r)
    }

    private suspend fun afterAnalysis(run: Run, items: List<ClaudeBatches.Item>): Run {
        val summaries = ArrayList<String>()
        val errors = ArrayList<String>()
        val changes = ArrayList<Change>()
        val item = items.firstOrNull { it.customId == "all" } ?: items.firstOrNull()
        if (item != null && item.truncated) {
            // Мысли съели весь потолок — повтор на ступень ниже по тем же
            // свидетельствам (из кэша), а не пустой прогон (02.10 и 09.10).
            val used = run.evidence[EFFORT_ANALYSIS] ?: settings.modelChoice(ModelRoute.NIGHT_REVIEW).effort
            val lower = RequestPolicy.lowerEffort(used)
            if (lower != null) {
                val model = settings.modelChoice(ModelRoute.NIGHT_REVIEW).model
                val batchId = submitAnalysis(run.evidence, model, lower)
                val note = "первый проход на $used обрезан по длине — повторён на $lower"
                log.add("ночной разбор (${run.kind}): $note, батч $batchId")
                return run.copy(
                    analysisBatchId = batchId,
                    evidence = run.evidence + (EFFORT_ANALYSIS to lower) + (RETRIES to listOf(run.evidence[RETRIES].orEmpty(), note).filter { it.isNotBlank() }.joinToString("; ")),
                    progress = "повтор на $lower",
                )
            }
        }
        if (item == null || !item.ok) errors += "первый проход: ${item?.failure ?: "нет ответа"}"
        else runCatching { NightReviewPolicy.parseAnalysis(item.text, "n") }
            .onSuccess { a -> if (a.summary.isNotBlank()) summaries += a.summary; changes += a.changes }
            .onFailure { errors += "первый проход: ответ не разобрался (${NightReviewPolicy.shortReason(it)})" }
        // Память: что владелец вернул или отклонил за месяц, снова не предлагается —
        // даже если модель не послушала промпт.
        val blocked = NightReviewEvidence.blockedKeys(store.all(), System.currentTimeMillis() - LEDGER_MS)
        val daily = !NightReviewPolicy.weekLike(run.kind, run.fromMs, run.toMs)
        val texts = run.evidence["texts"].orEmpty()
        val remembered = changes.map { c ->
            when {
                c.isNote -> c
                NightReviewEvidence.key(c) in blocked ->
                    c.copy(status = "skipped", statusNote = "владелец уже вернул или отклонил такое — не предлагаю снова")
                // Владелец (17.09): «низковероятные просто отбрасывай, иначе каша».
                // Отброшенное до проверки — ещё и не стоит токенов второго прохода.
                c.confidence != "high" -> c.copy(status = "dropped", statusNote = "низкая уверенность — отброшено")
                // Дневной прогон — про день: запись словаря правится, только если
                // её слово было в текстах окна; остальное — недельному.
                daily && (c.kind == "dict_disable" || c.kind == "dict_mode") && !NightReviewPolicy.mentioned(c.from, texts) ->
                    c.copy(status = "skipped", statusNote = "за сутки слово не встречалось — оставлено недельному разбору")
                else -> c
            }
        }
        // Согласование — в коде, до проверки: придержанное не применяется
        // (с причиной) и токенов второго прохода не стоит.
        val reconciled = NightReviewPolicy.consistencyHolds(remembered, recentNightAdds()).map { c ->
            if (!c.isNote && c.status == "proposed" && c.verdict == "hold") c.copy(status = "skipped", statusNote = c.verdictWhy) else c
        }
        val toCheck = reconciled.filter { !it.isNote && it.status == "proposed" }
        val partial = run.copy(summary = summaries.joinToString("\n\n"), error = errors.joinToString("; "), changes = reconciled)
        if (toCheck.isEmpty()) return finish(partial)
        val choice = settings.modelChoice(ModelRoute.NIGHT_CHECK)
        val batchId = submitCheck(run, toCheck, choice.model, choice.effort)
        log.add("ночной разбор: проверка, батч $batchId, изменений ${toCheck.size}")
        return partial.copy(stage = "check", checkBatchId = batchId, evidence = partial.evidence + (EFFORT_CHECK to choice.effort))
    }

    /**
     * Батч проверки. Тот же системный блок свидетельств байт-в-байт — читается
     * из кэша первого прохода, если проверка успела за час. В сообщении —
     * словарь и правила (state), без промпта CLEAN: проверке он не нужен.
     */
    private suspend fun submitCheck(run: Run, toCheck: List<Change>, model: String, effort: String): String {
        val list = JSONArray().apply { for (c in toCheck) put(changeJson(c)) }
        val user = listOf(PromptsReview.CHECK_SYSTEM, run.evidence["state"].orEmpty(), "ПРЕДЛОЖЕНИЯ ПЕРВОГО АУДИТОРА:\n$list")
            .filter { it.isNotBlank() }.joinToString("\n\n")
        return batches.create(
            listOf("check" to ClaudeBatches.params(model, effort, OUT_TOKENS, run.evidence["common"].orEmpty(), user, cacheSystem = true))
        )
    }

    private fun changeJson(c: Change): JSONObject = JSONObject().apply {
        put("id", c.id); put("kind", c.kind); put("mode", c.mode)
        if (c.toMode.isNotBlank()) put("to_mode", c.toMode)
        put("from", c.from); put("to", c.to); put("note", c.note)
        if (c.ruleId != 0L) put("rule_id", c.ruleId)
        put("why", c.why); put("confidence", c.confidence)
    }

    private suspend fun afterCheck(run: Run, items: List<ClaudeBatches.Item>): Run {
        if (items.any { it.truncated }) {
            val used = run.evidence[EFFORT_CHECK] ?: settings.modelChoice(ModelRoute.NIGHT_CHECK).effort
            val lower = RequestPolicy.lowerEffort(used)
            if (lower != null) {
                val toCheck = run.changes.filter { !it.isNote && it.status == "proposed" }
                val batchId = submitCheck(run, toCheck, settings.modelChoice(ModelRoute.NIGHT_CHECK).model, lower)
                val note = "проверка на $used обрезана по длине — повторена на $lower"
                log.add("ночной разбор (${run.kind}): $note, батч $batchId")
                return run.copy(
                    checkBatchId = batchId,
                    evidence = run.evidence + (EFFORT_CHECK to lower) + (RETRIES to listOf(run.evidence[RETRIES].orEmpty(), note).filter { it.isNotBlank() }.joinToString("; ")),
                    progress = "повтор проверки на $lower",
                )
            }
        }
        val verdicts = HashMap<String, Pair<String, String>>()
        val errors = ArrayList<String>()
        for (item in items) {
            if (!item.ok) { errors += "${item.customId}: ${item.failure}"; continue }
            runCatching { NightReviewPolicy.parseVerdicts(item.text) }
                .onSuccess { verdicts.putAll(it) }
                .onFailure { errors += "${item.customId}: вердикты не разобрались (${NightReviewPolicy.shortReason(it)})" }
        }
        // Проверка не ответила вовсе (отказ, обрыв, не JSON) — это не «не
        // возразила»: без единственного модельного фильтра само не ложится
        // ничего. Владельцу это не оставляется (12.10.2026) — «не применено»
        // с причиной, а сильные находки вернутся в следующем разборе.
        val checkFailed = verdicts.isEmpty() && errors.isNotEmpty()
        val updated = run.changes.map { c ->
            when {
                c.isNote || c.status != "proposed" || c.verdict == "hold" -> c
                checkFailed -> c.copy(status = "skipped", statusNote = "проверка не ответила — без неё само не ложится", verdictWhy = errors.joinToString("; ").take(200))
                else -> {
                    val (verdict, why) = verdicts[c.id] ?: ("unsure" to "проверка не дала вердикта")
                    val x = c.copy(verdict = verdict, verdictWhy = why)
                    if (verdict == "reject") x.copy(status = "rejected") else x
                }
            }
        }
        val allErrors = listOf(run.error, errors.joinToString("; ")).filter { it.isNotBlank() }.joinToString("; ")
        return applyAndFinish(run.copy(changes = updated, error = allErrors), apply = !checkFailed)
    }

    /**
     * Итог ночи: high без reject и без hold ложится само, до AUTO_CAP за прогон.
     * Остальное — «не применено» с причиной: с 12.10.2026 владелец ничего не
     * решает («я там не нужен»), и висящее предложение — это просто мусор в
     * отчёте. Раньше перед этим шёл третий батч — согласование моделью; его
     * вопросы решены в коде ещё до проверки (NightReviewPolicy.consistencyHolds).
     */
    private suspend fun applyAndFinish(run: Run, apply: Boolean = true): Run {
        var applied = 0
        val updated = if (!apply) run.changes else run.changes.map { c ->
            if (c.isNote || c.status != "proposed") return@map c
            when {
                !NightReviewPolicy.autoApply(c) -> c.copy(
                    status = "skipped",
                    statusNote = if (c.kind == "dict_add" && c.mode == "HARD" && NightReviewPolicy.shortCyrillicWord(c.from))
                        "HARD на короткое слово сам не ложится — слишком легко сломать живое слово"
                    else "не прошло проверку",
                )
                applied >= NightReviewPolicy.AUTO_CAP -> c.copy(status = "skipped", statusNote = "сверх ${NightReviewPolicy.AUTO_CAP} изменений за прогон — вернётся в следующем разборе")
                else -> {
                    val done = applyChange(c, run)
                    if (done.status == "applied") applied++
                    done
                }
            }
        }
        return finish(run.copy(changes = updated))
    }

    /** Слова, которые разбор сам добавил в словарь за неделю: выключать их без владельца нельзя. */
    private suspend fun recentNightAdds(): Set<String> {
        val since = System.currentTimeMillis() - RECENT_ADDS_MS
        return store.all().filter { it.isReview && it.startedAt >= since }
            .flatMap { it.changes }
            .filter { it.kind == "dict_add" && it.status == "applied" }
            .map { it.from.trim().lowercase() }
            .toSet()
    }

    private fun finish(run: Run): Run {
        val cache = if (run.inputTokens > 0) {
            " Вход ${run.inputTokens} токенов, из кэша ${run.cacheReadTokens} (${100 * run.cacheReadTokens / run.inputTokens}%)."
        } else ""
        val head = NightReviewPolicy.headline(
            run.applied(), run.proposed(), run.rejected(), run.changes.count { it.isNote }, run.changes.count { it.status == "dropped" },
            run.changes.count { !it.isNote && it.status == "skipped" },
        ) + cache + run.evidence[RETRIES].orEmpty().let { if (it.isBlank()) "" else " Повтор: $it." }
        log.add("ночной разбор (${run.kind}): $head стоил $" + "%.3f".format(Locale.US, run.costUsd))
        return run.copy(
            stage = "done",
            finishedAt = System.currentTimeMillis(),
            evidence = emptyMap(),
            progress = "",
            summary = listOf(head, run.summary).filter { it.isNotBlank() }.joinToString("\n\n"),
        )
    }

    /** Кнопка «Проверить сейчас» под идущим прогоном: опрос батча без десятиминутной паузы. */
    suspend fun pollNow(runId: Long): Result<String> = runCatching {
        mutex.withLock {
            // Читать под замком: снимок до ожидания устарел бы и повторил чужую работу.
            val run = store.get(runId) ?: error("Прогон не найден")
            if (!run.active) return@runCatching "Прогон уже завершён"
            runCatching { poll(run, System.currentTimeMillis()) }.onFailure { failOrWait(run, it) }.getOrThrow()
        }
        val after = store.get(runId)
        if (after == null || !after.active) "Готово" else after.progress.ifBlank { "Ещё идёт" }
    }

    /** Кнопка «Отменить»: батч отменяется у Anthropic (что успел — оплачено), прогон закрывается с причиной. */
    suspend fun cancel(runId: Long): Result<Unit> = runCatching {
        val run = store.get(runId) ?: error("Прогон не найден")
        if (!run.active) return@runCatching
        val batchId = when (run.stage) {
            "analysis" -> run.analysisBatchId
            "check" -> run.checkBatchId
            else -> run.auditBatchId  // стадия прежних сборок
        }
        runCatching { batches.cancel(batchId) }.onFailure { log.add("ночной разбор: отмена батча $batchId не прошла: ${it.message}") }
        store.save(run.copy(stage = "failed", error = "отменено владельцем", finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = ""))
        log.add("ночной разбор (${run.kind}): отменён владельцем на стадии ${run.stage}")
    }

    /** Сбой опроса: проходящий (сеть, перегрузка, диск) — ждём следующего тика, остальное — провал прогона. */
    private suspend fun failOrWait(run: Run, e: Throwable) {
        if (NightReviewPolicy.isTransient(e)) {
            logOncePerDay("wait-${run.id}", System.currentTimeMillis()) { "ночной разбор (${run.kind}): опрос не прошёл (${e.message}) — прогон жив, спрошу на следующем тике" }
            return
        }
        fail(run, e)
    }

    private suspend fun fail(run: Run, e: Throwable) {
        // Правило 6: причина целиком, а не «что-то пошло не так».
        val msg = e.message ?: e.javaClass.simpleName
        log.add("ночной разбор (${run.kind}) НЕ УДАЛСЯ: $msg")
        stats.recordError()
        store.save(run.copy(stage = "failed", error = msg, finishedAt = System.currentTimeMillis(), evidence = emptyMap()))
    }

    // ---- применение и откат ----

    private suspend fun findEntry(c: Change) = dictionary.all().firstOrNull {
        it.from.equals(c.from, ignoreCase = true) && (c.mode.isBlank() || it.mode.name == c.mode)
    }

    private fun undo(op: String, id: Long, extra: JSONObject.() -> Unit = {}): String =
        JSONObject().put("op", op).put("id", id).apply(extra).toString()

    private suspend fun applyChange(c: Change, run: Run): Change = try {
        when (c.kind) {
            "dict_add" -> {
                val mode = runCatching { DictMode.valueOf(c.mode) }.getOrNull()
                    ?: return c.copy(status = "failed", statusNote = "непонятный вид «${c.mode}»")
                val existing = findEntry(c)
                when {
                    existing != null && existing.enabled -> c.copy(status = "skipped", statusNote = "уже в словаре")
                    existing != null -> {
                        dictionary.update(existing.copy(enabled = true))
                        c.copy(status = "applied", undo = undo("disable", existing.id))
                    }
                    else -> {
                        val e = dictionary.add(c.from, c.to, mode, "ночной разбор ${date(run.startedAt)}: ${c.why.take(120)}".trim())
                        c.copy(status = "applied", undo = undo("delete", e.id))
                    }
                }
            }
            "dict_disable" -> {
                val e = findEntry(c)
                if (e == null) c.copy(status = "failed", statusNote = "нет такой записи")
                else if (!e.enabled) c.copy(status = "skipped", statusNote = "уже выключена")
                else { dictionary.update(e.copy(enabled = false)); c.copy(status = "applied", undo = undo("enable", e.id)) }
            }
            "dict_mode" -> {
                val e = findEntry(c)
                val newMode = runCatching { DictMode.valueOf(c.toMode) }.getOrNull()
                if (e == null) c.copy(status = "failed", statusNote = "нет такой записи")
                else if (newMode == null) c.copy(status = "failed", statusNote = "непонятный вид ${c.toMode}")
                else {
                    dictionary.update(e.copy(mode = newMode, note = c.note.ifBlank { e.note }))
                    c.copy(status = "applied", undo = undo("restore", e.id) { put("mode", e.mode.name); put("note", e.note) })
                }
            }
            "rule_disable", "rule_enable" -> {
                val r = rules.all().firstOrNull { it.id == c.ruleId }
                if (r == null) c.copy(status = "failed", statusNote = "нет правила ${c.ruleId}")
                else {
                    rules.setEnabled(c.ruleId, c.kind == "rule_enable")
                    c.copy(status = "applied", undo = undo("rule", c.ruleId) { put("enabled", r.enabled) })
                }
            }
            else -> c
        }
    } catch (e: Exception) {
        c.copy(status = "failed", statusNote = e.message ?: e.javaClass.simpleName)
    }

    private suspend fun revertChange(c: Change): Change = try {
        val u = JSONObject(c.undo)
        val id = u.optLong("id")
        when (u.optString("op")) {
            "delete" -> dictionary.delete(id)
            "disable" -> dictionary.all().firstOrNull { it.id == id }?.let { dictionary.update(it.copy(enabled = false)) }
            "enable" -> dictionary.all().firstOrNull { it.id == id }?.let { dictionary.update(it.copy(enabled = true)) }
            "restore" -> dictionary.all().firstOrNull { it.id == id }?.let {
                dictionary.update(it.copy(mode = DictMode.valueOf(u.optString("mode")), note = u.optString("note")))
            }
            "rule" -> rules.setEnabled(id, u.optBoolean("enabled"))
            else -> throw IllegalStateException("нечего откатывать")
        }
        c.copy(status = "reverted", statusNote = "возвращено")
    } catch (e: Exception) {
        c.copy(statusNote = "откат не удался: ${e.message}")
    }

    /** Кнопки под изменением: apply · reject · revert. */
    suspend fun act(runId: Long, changeId: String, action: String): Result<Unit> = runCatching {
        val run = store.get(runId) ?: error("Прогон не найден")
        val c = run.changes.firstOrNull { it.id == changeId } ?: error("Изменение не найдено")
        val updated = ownerAction(c, run, action)
        store.save(run.copy(changes = run.changes.map { if (it.id == changeId) updated else it }))
        log.add("ночной разбор: владелец — $action ${c.title()} → ${updated.status}")
    }

    /**
     * Действие владельца над изменением. Принять можно предложенное, отклонённое
     * и ВОЗВРАЩЁННОЕ (17.09: «нажимаю принять, потом вернуть — снова принять не
     * даёт»); отклонить — предложенное и возвращённое; вернуть — применённое.
     */
    private suspend fun ownerAction(c: Change, run: Run, action: String): Change = when (action) {
        "apply" -> if (c.status in APPLICABLE) applyChange(c, run) else c
        "reject" -> if (c.status == "proposed" || c.status == "reverted") c.copy(status = "rejected", statusNote = "отклонил владелец") else c
        "revert" -> if (c.status == "applied") revertChange(c) else c
        else -> c
    }

    private val APPLICABLE = setOf("proposed", "rejected", "reverted")
}
