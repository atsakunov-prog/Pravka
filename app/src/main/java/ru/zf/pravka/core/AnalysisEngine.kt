package ru.zf.pravka.core

import java.util.Calendar
import ru.zf.pravka.data.AnalysisStore
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.provider.ClaudeProvider
import ru.zf.pravka.provider.analyzeNow
import ru.zf.pravka.provider.submitBatch
import ru.zf.pravka.provider.batchAnswer

/**
 * Ночная охота за паттернами.
 *
 * Движок родился как «Итоги» — ночной разбор дня и недели текстом. Владелец
 * его свернул: «очень плохо работает тема с итогами, но замечательно работает
 * тема с паттернами — давай оставим только ежедневный поиск по паттернам».
 * Разборы он делает сам в чате по выгруженному CSV, и это честнее: чат видит
 * его целиком и умеет спорить, а приложение хорошо умеет ровно одно —
 * методично, каждую ночь, искать повторы и помнить их годами.
 *
 * Что осталось от прежней конструкции и почему:
 * 1. ЧИСЛА СЧИТАЕТ КОД. [AnalysisBuilder] собирает агрегаты детерминированно.
 *    Модель ищет смысл, а не складывает четырёхзначные числа по тремстам
 *    строкам.
 * 2. ОТПРАВКА БАТЧЕМ. Половина цены за то, что ответ не нужен сию секунду.
 *    Ночью уходит заявка, результат забирается опросом на тике службы.
 * 3. РАСПИСАНИЕ. Раз в сутки, ночью, за вчера — но со всем окном <recent>:
 *    повтор не виден внутри одного дня по определению.
 */
class AnalysisEngine(
    private val claude: ClaudeProvider,
    private val builder: AnalysisBuilder,
    private val store: AnalysisStore,
    private val prompts: PromptStore,
    private val settings: Settings,
    private val stats: Stats,
    private val eventLog: EventLog,
) {

    companion object {
        // Ночью: и день закрыт, и лента уже нормализована после полуночи.
        private const val NIGHT_HOUR = 4
        // Модель и усилие — настройка владельца (ModelRoute.PATTERNS;
        // заводские Опус и high), читаются при каждой отправке.
        // Ответ — шесть строк, но модель думает адаптивно, и мысли тоже
        // считаются в max_tokens. Это ПОТОЛОК, а не смета: платим только за
        // выданное, поэтому скупиться незачем — на тесном потолке модель
        // упиралась в него, не дойдя до строк, и владелец получал «ответ
        // обрезан по длине» вместо паттернов.
        private const val MAX_TOKENS = 32000
        // Опрос батча — не чаще раза в пять минут (тик службы) и не дольше
        // суток: столько живёт заявка по договору.
        private const val PENDING_TTL_MS = 26 * 3_600_000L
        // Столько живёт строка «ждёт» у разбора «сейчас». Дольше — значит
        // процесс убили посреди запроса, и ждать больше нечего.
        private const val IMMEDIATE_TTL_MS = 15 * 60_000L
    }

    /**
     * Охота за вчера. [force] — кнопка «сейчас» обязана срабатывать, даже если
     * за этот день уже искали: перечитать день по свежим правкам ленты бывает
     * нужно и просто так, иначе кнопку нечем проверить.
     */
    suspend fun huntPatterns(
        date: String = builder.yesterday(),
        force: Boolean = false,
        immediate: Boolean = false,
    ): Result<String> = submit("daily", date, date, force, immediate)

    private suspend fun submit(
        mode: String,
        from: String,
        to: String,
        force: Boolean = false,
        /**
         * true — ответ нужен сейчас: обычный запрос, полная цена, минута
         * ожидания. false — ночная заявка батчем: вдвое дешевле, но приезжает
         * когда захочет. Владелец: «если сделать разбор сейчас, он не батчем
         * должен уходить, а то я сижу жду».
         */
        immediate: Boolean = false,
    ): Result<String> {
        store.load()
        val already = store.reportsFlow.value.any {
            it.mode == mode && it.from == from && it.to == to && (it.pending || it.ready)
        }
        if (already && !force) {
            return Result.failure(IllegalStateException("Разбор за этот период уже есть"))
        }
        // Две заявки на один период в очереди — деньги на ветер: батч уже
        // считается, дождись его. Но сперва подметаем мёртвые, иначе одна
        // оборванная строка «ждёт» блокирует кнопку до завтра.
        sweepStuck()
        if (force && store.pending().any { it.mode == mode && it.from == from && it.to == to }) {
            return Result.failure(
                IllegalStateException(
                    "Такой разбор уже считается. Если он завис — удали его крестиком в списке"
                )
            )
        }
        val built = runCatching {
            builder.build(mode, from, to, context = settings.analysisContext())
        }.getOrElse {
            eventLog.add("итоги: не собрались данные — ${it.message}")
            return Result.failure(it)
        }
        if (built.text.length < 400) {
            return Result.failure(IllegalStateException("За этот период почти нет данных"))
        }
        val system = prompts.effective(PromptStore.PromptId.PATTERNS)
        // Модель пишется в отчёт: ответ батча придёт через часы, и цена
        // должна считаться по той модели, что отвечала, а не по нынешней.
        val choice = settings.modelChoice(ModelRoute.PATTERNS)
        if (immediate) {
            val report = store.addPending(
                mode, from, to, "", choice.model, built.hash, built.chars, source = "вручную",
            )
            val answer = claude.analyzeNow(
                system, built.text, choice.model, MAX_TOKENS, choice.effort,
            ).getOrElse { e ->
                store.fail(report.id, e.message ?: "не вышло")
                eventLog.add("итоги: разбор сейчас не вышел — ${e.message}")
                return Result.failure(e)
            }
            if (answer.text.isBlank()) {
                store.fail(report.id, "пустой ответ")
                return Result.failure(IllegalStateException("Модель вернула пустой разбор"))
            }
            runCatching { stats.recordAux(answer.costUsd, answer.tokensIn, answer.tokensOut) }
            finish(report.id, to, answer)
            eventLog.add(
                "итоги: разбор $mode $from — $to сделан сразу, ${answer.text.length} зн., " +
                    String.format(java.util.Locale.US, "%.3f", answer.costUsd) + " USD"
            )
            return Result.success("сразу")
        }
        val outcome = claude.submitBatch(system, built.text, choice.model, MAX_TOKENS, choice.effort)
        return outcome.fold(
            onSuccess = { batchId ->
                store.addPending(
                    mode, from, to, batchId, choice.model, built.hash, built.chars,
                    source = if (immediate) "вручную" else "ночью",
                )
                eventLog.add(
                    "итоги: заявка $mode $from — $to ушла батчем " +
                        "(${built.chars / 1000} тыс. знаков, батч $batchId)"
                )
                Result.success(batchId)
            },
            onFailure = { e ->
                eventLog.add("итоги: заявка не ушла — ${e.message}")
                Result.failure(e)
            },
        )
    }

    /**
     * Тик службы: сначала забрать готовое, потом — если пора — отправить
     * ночную заявку. Возвращает текст готового разбора, если он приехал
     * прямо сейчас: служба покажет по нему уведомление.
     */
    suspend fun tick(): AnalysisStore.Report? {
        store.load()
        val done = collect()
        if (done == null) runCatching { scheduleNightly() }
        return done
    }

    /**
     * Заявки, которые уже никогда не ответят, — в «не вышло».
     *
     * Разбор «сейчас» идёт без батча: строка «ждёт» появляется до запроса, а
     * закрывает её сам вызов. Если приложение в этот момент убили, строка
     * остаётся висеть вечно — и блокирует кнопку сообщением «такой разбор уже
     * считается», хотя не считается ничто. Владелец на это и наткнулся.
     * Отличаем такую строку по пустому batchId: у настоящей заявки он есть.
     */
    private suspend fun sweepStuck() {
        val now = System.currentTimeMillis()
        for (report in store.pending()) {
            val age = now - report.createdAt
            val dead = when {
                // Батч по договору живёт сутки.
                report.batchId.isNotBlank() -> age > PENDING_TTL_MS
                // Разбор «сейчас» не бывает дольше четверти часа даже на
                // самом длинном месяце: значит, его оборвали.
                else -> age > IMMEDIATE_TTL_MS
            }
            if (!dead) continue
            store.fail(
                report.id,
                if (report.batchId.isNotBlank()) "батч не ответил за сутки"
                else "оборвалось на середине — приложение закрыли во время разбора",
            )
            eventLog.add("итоги: снял зависшую заявку ${report.mode} ${report.from}")
        }
    }

    /** Опрос батчей: готово — сохраняем, просрочено — помечаем. */
    private suspend fun collect(): AnalysisStore.Report? {
        sweepStuck()
        val pending = store.pending()
        if (pending.isEmpty()) return null
        for (report in pending) {
            // Строка без batchId — это разбор «сейчас», который ещё идёт в
            // этом же процессе. Спрашивать про него батч-API бессмысленно:
            // получим 404 и будем получать его каждый тик.
            if (report.batchId.isBlank()) continue
            val answer = claude.batchAnswer(report.batchId, report.model).getOrElse { e ->
                eventLog.add("итоги: опрос батча не вышел — ${e.message}")
                null
            } ?: continue
            if (answer.error.isNotBlank()) {
                store.fail(report.id, answer.error)
                eventLog.add("итоги: разбор не получился — ${answer.error}")
                continue
            }
            if (answer.text.isBlank()) {
                store.fail(report.id, "пустой ответ")
                continue
            }
            runCatching { stats.recordAux(answer.costUsd, answer.tokensIn, answer.tokensOut) }
            val saved = finish(report.id, report.to, answer)
            eventLog.add(
                "итоги: готов разбор ${report.mode} ${report.from} — ${report.to}, " +
                    "${answer.text.length} зн., " +
                    String.format(java.util.Locale.US, "%.3f", answer.costUsd) + " USD (батч)"
            )
            return saved
        }
        return null
    }

    /**
     * Разложить ответ: строки #patterns уходят в память паттернов, а в журнал
     * запусков ложится короткая запись о том, что нашли. Раньше здесь
     * сохранялся текст разбора; текстов модель больше не пишет, и хранить
     * сырой машинный блок как «разбор» значило бы показывать владельцу
     * решётки вместо смысла.
     */
    private suspend fun finish(
        id: Long,
        date: String,
        answer: ClaudeProvider.BatchAnswer,
    ): ru.zf.pravka.data.AnalysisStore.Report? {
        val fresh = parsePatterns(answer.text)
        if (fresh.isNotEmpty()) {
            runCatching { store.mergePatterns(fresh, date) }
            eventLog.add("итоги: запомнил паттернов ${fresh.size}")
        }
        val record = buildString {
            append(if (fresh.isEmpty()) "Повторов не набралось." else "Нашёл повторов: " + fresh.size + ".")
            fresh.forEach { pt ->
                append("\n- ").append(pt.text)
                append(" — точек ").append(pt.points)
                if (pt.confidence.isNotBlank()) append(", уверенность ").append(pt.confidence)
            }
            // Если модель вдруг написала что-то помимо блока — сохраним и это,
            // иначе диагностировать сорванный ответ будет нечем.
            val extra = answer.text.substringBefore("#patterns").trim()
            if (extra.isNotBlank()) append("\n\n").append(extra.take(1500))
        }
        return store.complete(
            id,
            record,
            answer.costUsd,
            answer.tokensIn,
            answer.tokensOut,
        )
    }

    /** «формулировка | точек | уверенность» из хвоста ответа. */
    private fun parsePatterns(text: String): List<ru.zf.pravka.data.AnalysisStore.Pattern> {
        val tail = text.substringAfter("#patterns", "").trim()
        if (tail.isBlank() || tail.lowercase().startsWith("нет")) return emptyList()
        return tail.lineSequence()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.contains('|') }
            .take(6)
            .mapNotNull { line ->
                val parts = line.split('|').map { it.trim() }
                val body = parts.getOrNull(0).orEmpty()
                if (body.length < 8) return@mapNotNull null
                ru.zf.pravka.data.AnalysisStore.Pattern(
                    text = body.take(200),
                    firstSeen = "",
                    lastSeen = "",
                    times = 1,
                    points = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                    confidence = parts.getOrNull(2).orEmpty().take(20),
                )
            }
            .toList()
    }

    /**
     * Ночью после [NIGHT_HOUR]: разбор вчерашнего дня, а по воскресеньям ещё
     * и недельный. Ключ идемпотентности — период разбора: если за вчера уже
     * есть запись (готовая или в очереди), второй раз не отправляем.
     */
    private suspend fun scheduleNightly() {
        if (!settings.analysisNightly()) return
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < NIGHT_HOUR) return
        val yesterday = builder.yesterday()
        val haveDaily = store.reportsFlow.value.any {
            it.mode == "daily" && it.from == yesterday && (it.pending || it.ready)
        }
        if (!haveDaily) huntPatterns(yesterday)
    }

    /** Сегодняшняя дата — для подписи «за какой день считаем». */
    fun today(): String = dayKey(System.currentTimeMillis())

    fun yesterday(): String = builder.yesterday()
}
