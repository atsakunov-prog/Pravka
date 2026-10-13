package ru.zf.pravka.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.AutoWitness
import ru.zf.pravka.core.PhoneLive
import ru.zf.pravka.core.PhoneSit
import ru.zf.pravka.provider.ClaudeProvider
import ru.zf.pravka.provider.zasechkaPhone

/**
 * Телефон в ленте (13.10.2026, docs/zasechka.md «Телефон в ленте»): долгое
 * сидение в телефоне — дело в ленте. Правила — `core/PhoneSit.kt` (почему это
 * не августовские «пожиратели»), автомат «смотрим — спросили — да/нет» —
 * `core/PhoneLive.kt`; здесь — Android и дороги:
 *
 *  - сидения — из статистики использования (та же, что «Телефон по дням»);
 *  - снимки экрана берёт служба (`trigger/ScreenPeek.kt`) ТОЛЬКО когда автомат
 *    проверяет кусок: с седьмой минуты сидения три снимка, после ответа — ни
 *    одного, пока впереди те же приложения. Текст — только в памяти процесса,
 *    на диск не пишется никогда, наружу — лишь Сонету при разборе;
 *  - три снимка — Сонету (`ClaudeProvider.zasechkaPhone`), его догадка —
 *    вопросом на плашке «З»: «Кажется, это «Claude: Правка»?» с галочкой и
 *    крестиком. Галочка или молчание — дело в ленту вживую с начала сидения,
 *    отложил телефон на пять минут — закрыто в миг, когда отложил, и
 *    продолжается то, что шло до него. Крестик — молчим до конца сидения,
 *    пока впереди эти приложения;
 *  - сидение, кончившееся до вопроса (7–10 минут), — как в первом заходе:
 *    врезкой задним числом и тихим пушем с «Убрать»;
 *  - состояние автомата и последние решения — `phone-sit.json` в базе.
 */
class PhoneSitEngine(
    private val context: Context,
    private val zasechkaStore: ZasechkaStore,
    private val phoneStore: PhoneStore,
    private val settings: Settings,
    private val stats: Stats,
    private val eventLog: EventLog,
    private val claude: () -> ClaudeProvider,
    /** Режет ли сидение дело сейчас: тумблер (или профиль) и Засечка не молчит. */
    private val on: suspend () -> Boolean,
    /** Автопилот службы: тихий пуш «📱 … — Убрать». Выключен — без пуша, как у звонков. */
    private val witness: () -> AutoWitness? = { null },
    /** Свои люди — имя и что о нём сказать Сонету («работа, клиент Ромашка», «семья»). */
    private val people: suspend () -> List<Pair<String, String>> = { emptyList() },
    /**
     * Вопрос «да или нет» плашкой «З» (зовётся на главном потоке): вопрос,
     * подпись, галочка, крестик. false — плашка не встала (замок, пилюля
     * занята словами): тогда молчание — «да».
     */
    private val ask: (question: String, detail: String, onYes: () -> Unit, onNo: () -> Unit) -> Boolean = { _, _, _, _ -> false },
) {

    companion object {
        private const val FILE_NAME = "phone-sit.json"
        private const val SNAPS_MAX = 480
        private const val LOG_MAX = 40
        private const val RECENT_DAYS = 7L

        /** Кусок живой проверки — от минуты: после переключения он короче порога. */
        private const val LIVE_PART_MS = 60_000L
    }

    /** Что решено про одно сидение — строкой в настройки и в журнал. */
    data class Decision(val at: Long, val from: Long, val to: Long, val apps: String, val verdict: String)

    private val _recent = MutableStateFlow<List<Decision>>(emptyList())
    val recent: StateFlow<List<Decision>> = _recent

    /** Что автомат делает сейчас — строкой для настроек. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    /** Когда служба последний раз видела экран — «смотрит ли вообще» для настроек. */
    @Volatile var lastPeekAt: Long = 0L
        private set

    /** Снимок нужен: идёт проверка куска (служба смотрит ТОЛЬКО тогда). */
    @Volatile var wantsSnap: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val snaps = ArrayDeque<PhoneSit.Snap>()
    @Volatile private var loaded = false
    private var live = PhoneLive.State()

    private val file: File get() = File(DataRoot.dir(context), FILE_NAME)

    /** Снимок экрана от службы (её фоновый поток): только в память. */
    fun addSnap(s: PhoneSit.Snap) {
        lastPeekAt = s.at
        synchronized(snaps) {
            snaps.addLast(s)
            val cut = s.at - PhoneSit.LOOKBACK_MS
            while (snaps.isNotEmpty() && (snaps.first().at < cut || snaps.size > SNAPS_MAX)) snaps.removeFirst()
        }
    }

    private fun snapsNow(): List<PhoneSit.Snap> = synchronized(snaps) { snaps.toList() }

    /** Прочитать решения для настроек: вкладка открыта раньше первого тика. */
    suspend fun load() = withContext(Dispatchers.IO) { ensureLoaded() }

    /** Минута подсмотра (служба, экран открыт): снимок уже взят, если был нужен. */
    fun minute() {
        scope.launch {
            guarded("минута") {
                lock.withLock {
                    ensureLoaded()
                    live = PhoneLive.minute(live)
                    pulseLocked()
                }
            }
        }
    }

    /** Пятиминутный тик службы: экран погашен — дело закрывается и отсюда. */
    suspend fun tick() = withContext(Dispatchers.IO) {
        guarded("тик") {
            lock.withLock {
                ensureLoaded()
                pulseLocked()
            }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { eventLog.add("телефон в ленте: $what упал: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private suspend fun pulseLocked() {
        val now = System.currentTimeMillis()
        if (!on()) {
            // Выключено — автомата нет: включат — смотрим с того момента, а не
            // задним числом за всё время, пока тумблер стоял.
            if (live != PhoneLive.State()) set(PhoneLive.State())
            return
        }
        if (!PhoneSweeper.hasUsageAccess(context)) return
        if (live.handled <= 0L) {
            set(PhoneLive.State(handled = now))
            eventLog.add("телефон в ленте: включён — смотрю сидения с ${hm(now)}")
            return
        }
        // Владелец сам переключился (сказал в «З», убрал запись) — его слово сильнее.
        if (live.base == PhoneLive.Base.ON && zasechkaStore.openEntry()?.id != live.entryId) {
            val title = live.title
            set(PhoneLive.ownerTook(live))
            eventLog.add("телефон в ленте: «$title» больше не веду — ты переключился сам")
        }
        val minMs = settings.zPhoneMinFlow.first().coerceIn(3, 30) * 60_000L
        val spans = spansNow(now)
        // Сказал что-то в «З», пока шла проверка, — его слово про это время, не спрашиваем.
        if (live.watching) {
            ownerSpokeSince(live.watchFrom)?.let { e ->
                val apps = segment(spans, live.watchFrom, now).spans.map { it.pkg }.toSet()
                set(
                    if (live.base == PhoneLive.Base.IDLE) live.unwatched().copy(base = PhoneLive.Base.OFF, apps = apps)
                    else live.unwatched().copy(apps = live.apps + apps)
                )
                eventLog.add("телефон в ленте: ты сказал «${e.title}» в ${hm(e.createdAt)} — не спрашиваю")
            }
        }
        val sittings = PhoneSit.stretches(spans.filter { it.end > live.handled }, now)
            .map { if (it.start < live.handled) it.clip(live.handled, it.end) else it }
            .filter { it.spans.isNotEmpty() }
        val view = PhoneLive.View(
            sitting = sittings.lastOrNull(),
            lastUse = spans.maxOfOrNull { it.end } ?: 0L,
            outsideSince = PhoneLive.outsideSince(spans, live.apps, now),
        )
        val before = live
        val step = PhoneLive.pulse(live, view, now, minMs)
        set(step.state)
        if (!before.watching && live.watching) startedWatch(spans, now)
        for (d in step.dos) perform(d, spans, minMs)
    }

    /**
     * Проверка куска началась: время, которое охраняет лента (сон, встреча,
     * дорога, тренировка, сказанное тобой в «З»), — не смотрим вовсе.
     */
    private suspend fun startedWatch(spans: List<PhoneSit.Span>, now: Long) {
        val from = live.watchFrom
        val seg = segment(spans, from, now)
        val seen = zasechkaStore.forRange(from, now)
            .filter { it.id != live.entryId }
            .map { PhoneSit.Seen(it.start, it.end, it.title, it.category, it.source, it.raw, it.createdAt) }
        val g = PhoneSit.guard(seg, seen, 0L, now)
        val reachesNow = g.pieces.any { now - it.end <= PhoneSit.GAP_MS }
        val apps = seg.spans.map { it.pkg }.toSet()
        if (g.why.isNotBlank() && !reachesNow) {
            set(
                if (live.base == PhoneLive.Base.IDLE) live.unwatched().copy(base = PhoneLive.Base.OFF, apps = apps)
                else live.unwatched().copy(apps = live.apps + apps)
            )
            eventLog.add("телефон в ленте: на экран не смотрю — ${g.why}")
            return
        }
        eventLog.add("телефон в ленте: с ${hm(from)} в телефоне — ${PhoneSit.appsLine(seg, ::label)}; смотрю на экран три минуты")
    }

    private suspend fun perform(d: PhoneLive.Do, spans: List<PhoneSit.Span>, minMs: Long) {
        when (d) {
            is PhoneLive.Classify -> {
                val seg = segment(spans, d.from, d.to)
                scope.launch { guarded("разбор") { classifyLive(seg) } }
            }
            is PhoneLive.Behind -> {
                val seg = segment(spans, d.from, d.to)
                if (seg.spans.isNotEmpty()) scope.launch { guarded("разбор задним числом") { place(seg, minMs) } }
            }
            is PhoneLive.Close -> close(d)
            is PhoneLive.Open -> open(d)
        }
    }

    /** Сонет про идущий кусок: то же, что в ленте, — молчим; другое — вопрос на плашку. */
    private suspend fun classifyLive(seg: PhoneSit.Stretch) {
        val apps = seg.spans.map { it.pkg }.toSet()
        val categories = zasechkaStore.categories()
        val clients = zasechkaStore.clients()
        val reply = claude().zasechkaPhone(promptTail(seg, LIVE_PART_MS, categories, clients, live = true))
        val r = reply.getOrNull()
        if (r == null) {
            lock.withLock { set(PhoneLive.failed(live).copy(snaps = 0)) }
            eventLog.add("телефон в ленте: Сонет не ответил — ${reply.exceptionOrNull()?.message}; ещё три минуты и спрошу снова")
            return
        }
        runCatching { stats.recordAux(r.costUsd, r.tokensIn, r.tokensOut, route = ModelRoute.ZASECHKA_PHONE.key) }
        val parsed = PhoneSit.parse(r.raw, seg, categories.map { it.name }, LIVE_PART_MS)
        if (parsed == null) eventLog.add("телефон в ленте: ответ Сонета не прочитан: ${r.raw.take(200)}")
        // Что он делает СЕЙЧАС — последний кусок, если он доходит до конца проверки.
        val part = parsed?.parts?.lastOrNull()?.takeIf { seg.end - it.end <= PhoneSit.GAP_MS }
        val guess = part?.let {
            PhoneLive.Guess(it.title, it.category, clients.firstOrNull { c -> c.equals(it.client, ignoreCase = true) }.orEmpty(), it.start)
        }
        val shownApps = PhoneSit.appsLine(seg, ::label)
        lock.withLock {
            set(PhoneLive.classified(live, guess, apps, System.currentTimeMillis()).state)
            if (guess == null) {
                record(seg, shownApps, "то же дело — ${parsed?.why?.ifBlank { null } ?: "Сонет не режет"}; молчу, пока впереди эти приложения")
                return@withLock
            }
            record(seg, shownApps, "спрашиваю: «${guess.title}» [${guess.category}] — ${parsed?.why.orEmpty()}")
        }
        if (guess != null) question(guess)
    }

    /** Плашка «Кажется, это …?» на «З». Не встала — молчание, «да» по сроку. */
    private suspend fun question(g: PhoneLive.Guess) {
        val open = zasechkaStore.openEntry()
        val instead = open?.takeIf { it.source != "gap" && it.title.isNotBlank() }?.let { " · вместо «${it.title}»" }.orEmpty()
        val shown = withContext(Dispatchers.Main) {
            runCatching {
                ask(
                    "Кажется, это «${g.title}»?",
                    "с ${hm(g.from)} · ${g.category}$instead",
                    { answer(true) },
                    { answer(false) },
                )
            }.getOrDefault(false)
        }
        if (!shown) eventLog.add("телефон в ленте: плашка не встала — молчание через полминуты будет «да»")
    }

    /** Галочка или крестик на плашке (главный поток). */
    fun answer(yes: Boolean) {
        scope.launch {
            guarded("ответ") {
                lock.withLock {
                    ensureLoaded()
                    val g = live.guess ?: return@withLock
                    val apps = live.segApps
                    val step = PhoneLive.answer(live, yes)
                    set(step.state)
                    if (!yes) {
                        record(PhoneSit.Stretch(g.from, System.currentTimeMillis(), emptyList(), true), appsNames(apps),
                            "нет — «${g.title}» не ставлю, верно то, что в ленте")
                    }
                    for (d in step.dos) perform(d, emptyList(), 0L)
                }
            }
        }
    }

    /** Запись, которую владелец сам сказал (или набрал) после [from]: его слово про это время. */
    private suspend fun ownerSpokeSince(from: Long): ZasechkaStore.Entry? =
        zasechkaStore.forRange(from, Long.MAX_VALUE).firstOrNull {
            it.raw.isNotBlank() && it.createdAt >= from && it.source != "auto" && it.source != "gap"
        }

    /** «Да» (или молчание): дело в ленту вживую с начала куска. */
    private suspend fun open(d: PhoneLive.Open) {
        val g = d.guess
        ownerSpokeSince(g.from)?.let { e ->
            set(PhoneLive.ownerTook(live.copy(apps = live.apps + d.apps)))
            eventLog.add("телефон в ленте: «${g.title}» не ставлю — ты сам сказал «${e.title}» в ${hm(e.createdAt)}")
            return
        }
        val prev = zasechkaStore.openEntry()?.takeIf { it.source != "gap" }
        val apps = appsNames(d.apps)
        val entry = zasechkaStore.startEntry(
            start = g.from,
            raw = "",
            title = g.title,
            category = g.category,
            client = g.client,
            useful = 0,
            // Владельческий источник: галочка (или молчание на вопрос) — его слово,
            // как у поездки автопилота; ложное чинит «Убрать».
            source = "voice",
        )
        zasechkaStore.setComment(entry.id, PhoneSit.COMMENT_PREFIX + apps)
        set(PhoneLive.opened(live, entry.id, entry.title, d.apps, prev?.id ?: 0L))
        record(
            PhoneSit.Stretch(g.from, System.currentTimeMillis(), emptyList(), false), apps,
            (if (d.silent) "молчание — «да»: " else "да: ") + "«${g.title}» [${g.category}] с ${hm(g.from)}" +
                (prev?.let { ", «${it.title}» закрыто" } ?: ""),
        )
        if (d.silent) {
            runCatching {
                witness()?.phoneInRibbon(entry.id, entry.title, entry.category, g.from, 0L, prev?.title.orEmpty(), apps, prev?.id ?: 0L)
            }
        }
    }

    /** Отложил телефон: дело закрыто в миг, когда отложил, и продолжается прежнее. */
    private suspend fun close(d: PhoneLive.Close) {
        val open = zasechkaStore.openEntry()
        if (open == null || open.id != d.entryId) return
        val closed = zasechkaStore.closeOpen(d.at) ?: return
        val back = d.resumeId.takeIf { it > 0L }?.let { zasechkaStore.entryById(it) }
            ?.takeIf { !it.open && it.source != "gap" }
            ?.let {
                zasechkaStore.startEntry(
                    d.at, "", it.title, it.category, it.client, it.useful,
                    source = it.source, task = it.task, project = it.project,
                )
            }
        record(
            PhoneSit.Stretch(closed.start, d.at, emptyList(), true), closed.comment.removePrefix(PhoneSit.COMMENT_PREFIX),
            "«${closed.title}» до ${hm(d.at)} — телефон отложен" + (back?.let { ", снова «${it.title}»" } ?: ""),
        )
    }

    /** Сидение кончилось до вопроса: что охраняет ленту — не трогаем, остальное — Сонету и врезкой. */
    private suspend fun place(s: PhoneSit.Stretch, minMs: Long) {
        val now = System.currentTimeMillis()
        val seen = zasechkaStore.forRange(s.start, s.end).map {
            PhoneSit.Seen(it.start, it.end, it.title, it.category, it.source, it.raw, it.createdAt)
        }
        val g = PhoneSit.guard(s, seen, minMs, now)
        if (g.pieces.isEmpty()) {
            record(s, PhoneSit.appsLine(s, ::label), "дело не режу — ${g.why}")
            return
        }
        for (piece in g.pieces) behind(piece, minMs)
    }

    private suspend fun behind(piece: PhoneSit.Stretch, minMs: Long) {
        val apps = PhoneSit.appsLine(piece, ::label)
        val categories = zasechkaStore.categories()
        val clients = zasechkaStore.clients()
        val reply = claude().zasechkaPhone(promptTail(piece, minMs, categories, clients, live = false)).getOrElse {
            record(piece, apps, "не разобрано — ${it.message ?: it.javaClass.simpleName}")
            return
        }
        runCatching {
            stats.recordAux(reply.costUsd, reply.tokensIn, reply.tokensOut, route = ModelRoute.ZASECHKA_PHONE.key)
        }
        val parsed = PhoneSit.parse(reply.raw, piece, categories.map { it.name }, minMs)
        if (parsed == null) {
            // Не сеть, а ответ: повтор дал бы то же самое за те же деньги.
            record(piece, apps, "ответ Сонета не прочитан: ${reply.raw.take(200)}")
            return
        }
        if (parsed.dropped.isNotEmpty()) {
            eventLog.add("телефон в ленте: из ответа не взято — ${parsed.dropped.joinToString("; ")}")
        }
        if (parsed.parts.isEmpty()) {
            record(piece, apps, "то же дело — ${parsed.why.ifBlank { "Сонет не режет" }}")
            return
        }
        for (part in parsed.parts) {
            val partApps = PhoneSit.appsLine(piece.clip(part.start, part.end), ::label).ifBlank { apps }
            val cut = zasechkaStore.forRange(part.start, part.end)
                .lastOrNull { it.source != "gap" && it.start <= part.start }
            val client = clients.firstOrNull { it.equals(part.client, ignoreCase = true) }.orEmpty()
            val entry = zasechkaStore.insertInterruption(
                start = part.start,
                end = part.end,
                title = part.title,
                category = part.category,
                resumePrevious = true,
                client = client,
                comment = PhoneSit.COMMENT_PREFIX + partApps,
            )
            if (entry == null) {
                record(piece.clip(part.start, part.end), partApps, "«${part.title}» не легла — такая врезка уже есть")
                continue
            }
            record(
                piece.clip(entry.start, entry.end), partApps,
                "«${entry.title}» [${entry.category}] задним числом" +
                    (if (entry.client.isNotBlank()) " · ${entry.client}" else "") +
                    (cut?.let { ", разрезал «${it.title}»" } ?: "") +
                    (if (parsed.why.isNotBlank()) " — ${parsed.why}" else ""),
            )
            runCatching {
                witness()?.phoneInRibbon(
                    entry.id, entry.title, entry.category, entry.start, entry.end, cut?.title.orEmpty(), partApps,
                )
            }
        }
    }

    /** Хвост промпта: сидение, лента, снимки, свои люди, категории, его названия. */
    private suspend fun promptTail(
        s: PhoneSit.Stretch,
        minMs: Long,
        categories: List<ZasechkaStore.Category>,
        clients: List<String>,
        live: Boolean,
    ): String {
        val now = System.currentTimeMillis()
        val inLane = zasechkaStore.forRange(s.start, s.end).sortedBy { it.start }.map { e ->
            val end = if (e.open) "…" else hm(e.end)
            val who = when {
                e.source == "gap" -> "дыра, никто не записал"
                e.source == "auto" -> "факт робота"
                e.source == "calendar" -> "встреча из календаря"
                e.source == "task" -> "начато из дела"
                e.comment.startsWith(PhoneSit.COMMENT_PREFIX) -> "поставлено по телефону, владелец подтвердил"
                e.raw.isNotBlank() -> "записал сам: «${e.raw.take(160)}»"
                else -> "продолжение после врезки или автопилот"
            }
            "- ${hm(e.start)}–$end «${e.title.ifBlank { "без названия" }}» [${e.category.ifBlank { "без категории" }}], $who" +
                (if (e.comment.isNotBlank()) "; заметка: ${e.comment.take(160)}" else "")
        }
        val all = snapsNow()
        val snapLines = PhoneSit.snapLines(s, all, ::label)
        val text = all.filter { it.at in s.start..s.end }.joinToString(" · ") { it.text }
        val who = PhoneSit.whoInText(text, runCatching { people() }.getOrDefault(emptyList()))
        val dayStart = dayStartMs(now)
        val recent = zasechkaStore.forRange(dayStart - RECENT_DAYS * 86_400_000L, now)
            .filter { it.source != "gap" && it.source != "auto" && it.title.isNotBlank() }
            .groupBy { "${it.title.trim().lowercase()}|${it.category.trim().lowercase()}" }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.start }?.let { it to group.size } }
            .sortedByDescending { (last, _) -> last.start }
            .take(30)
            .map { (last, times) -> "- «${last.title}» [${last.category}]" + (if (times > 1) " ×$times" else "") }
        return buildString {
            append("Сейчас: ").append(nowFormat.format(Date(now))).append(".\n")
            append("Сидение: ${hm(s.start)}–${hm(s.end)}, телефон в руках ${PhoneSit.minutes(s.useMs)}. ")
            if (live) {
                append(
                    "Сидение ещё идёт: последний кусок — то, чем он занят сейчас; его спросят вопросом " +
                        "«кажется, это …?». Здесь это продолжение долгого сидения, кусок — от минуты.\n\n"
                )
            } else {
                append("Порог владельца — ${minMs / 60_000L} мин.\n\n")
            }
            append("В ленте на это время:\n")
            append(if (inLane.isEmpty()) "(ничего не записано)" else inLane.joinToString("\n")).append("\n\n")
            append("По минутам:\n").append(PhoneSit.timeline(s, ::label)).append("\n\n")
            append("Снимки экрана (раз в минуту, видимый текст):\n")
            append(if (snapLines.isEmpty()) "(текст экрана не читался — суди по приложениям)" else snapLines.joinToString("\n"))
            append("\n\n")
            if (who.isNotEmpty()) append("Кого из своих видно в тексте:\n").append(who.joinToString("\n") { "- $it" }).append("\n\n")
            append("Категории (после тире — что сюда относится):\n")
            append(categories.joinToString("\n") { c -> "- «${c.name}»" + (if (c.hint.isBlank()) "" else " — ${c.hint}") })
            append("\n\nКлиенты и проекты владельца:\n")
            append(if (clients.isEmpty()) "(список пуст)" else clients.joinToString("\n") { "- $it" })
            if (recent.isNotEmpty()) {
                append("\n\nКак владелец называл свои дела в последние дни:\n").append(recent.joinToString("\n"))
            }
        }
    }

    /** Кусок [from, to) из кусков переднего плана. */
    private fun segment(spans: List<PhoneSit.Span>, from: Long, to: Long): PhoneSit.Stretch =
        PhoneSit.Stretch(from, to, spans, finished = false).clip(from, to)

    /** Куски переднего плана за последние часы — из статистики использования. */
    private fun spansNow(now: Long): List<PhoneSit.Span> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val from = maxOf(live.handled, now - PhoneSit.LOOKBACK_MS) - 3600_000L
        val home = homePackages()
        return PhoneSit.spans(readEvents(usm, from, now), now) { PhoneSit.furniture(it, home) }
            .filter { it.end > live.handled }
    }

    private fun appsNames(apps: Set<String>): String = apps.joinToString(" · ") { label(it) }

    /** Новое состояние автомата: зеркала для службы и настроек, файл — если поменялось решённое. */
    private fun set(s: PhoneLive.State) {
        val old = live
        live = s
        wantsSnap = s.wantsSnap
        _status.value = statusLine(s)
        if (old.base != s.base || old.apps != s.apps || old.entryId != s.entryId || old.handled != s.handled ||
            old.resumeId != s.resumeId || old.title != s.title
        ) persist()
    }

    private fun statusLine(s: PhoneLive.State): String = when {
        s.guess != null -> "спросил: «${s.guess.title}»?"
        s.thinking -> "Сонет смотрит, что это"
        s.watching -> "смотрю на экран с ${hm(s.watchFrom)} — снимок ${s.snaps} из ${PhoneLive.ASK_SNAPS}"
        s.base == PhoneLive.Base.ON -> "веду «${s.title}» — на экран не смотрю, пока впереди ${appsNames(s.apps)}"
        s.base == PhoneLive.Base.OFF -> "молчу до конца сидения, пока впереди ${appsNames(s.apps)}"
        else -> "жду сидения от порога — на экран не смотрю"
    }

    private suspend fun record(s: PhoneSit.Stretch, apps: String, verdict: String) {
        val d = Decision(System.currentTimeMillis(), s.start, s.end, apps, verdict)
        _recent.value = (listOf(d) + _recent.value).take(LOG_MAX)
        persist()
        eventLog.add("телефон в ленте: ${hm(s.start)}–${hm(s.end)} ${apps.ifBlank { "" }} → $verdict")
    }

    private fun readEvents(usm: UsageStatsManager, from: Long, to: Long): List<PhoneSit.Ev> {
        val out = ArrayList<PhoneSit.Ev>()
        val q = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        while (q.hasNextEvent()) {
            q.getNextEvent(e)
            val pkg = e.packageName.orEmpty()
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (pkg.isNotBlank()) out += PhoneSit.Front(e.timeStamp, pkg)
                UsageEvents.Event.ACTIVITY_PAUSED -> if (pkg.isNotBlank()) out += PhoneSit.Back(e.timeStamp, pkg)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> out += PhoneSit.Dark(e.timeStamp)
            }
        }
        return out
    }

    private fun homePackages(): Set<String> = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        setOfNotNull(
            context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName,
        )
    }.getOrDefault(emptySet())

    /** Имя приложения по-человечески: из телефонного слоя, иначе у системы, иначе пакет. */
    fun label(pkg: String): String {
        phoneStore.labelsFlow.value[pkg]?.takeIf { it.isNotBlank() }?.let { return it }
        if (pkg == context.packageName) return "Правка"
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            StoreFiles.readOrQuarantine(file) { JSONObject(it) }?.let { o ->
                val arr = o.optJSONArray("log") ?: JSONArray()
                _recent.value = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let {
                        Decision(it.optLong("at"), it.optLong("from"), it.optLong("to"), it.optString("apps"), it.optString("verdict"))
                    }
                }
                val appsArr = o.optJSONArray("apps") ?: JSONArray()
                live = PhoneLive.State(
                    base = runCatching { PhoneLive.Base.valueOf(o.optString("base", "IDLE")) }.getOrDefault(PhoneLive.Base.IDLE),
                    apps = (0 until appsArr.length()).map { appsArr.optString(it) }.filter { it.isNotBlank() }.toSet(),
                    entryId = o.optLong("entry", 0L),
                    title = o.optString("title"),
                    resumeId = o.optLong("resume", 0L),
                    // Первый заход хранил «разобрано до» полем done.
                    handled = o.optLong("handled", o.optLong("done", 0L)),
                )
            }
            wantsSnap = live.wantsSnap
            _status.value = statusLine(live)
            loaded = true
        }
    }

    private fun persist() {
        val s = live
        val o = JSONObject()
            .put("format", "pravka-phone-sit")
            .put("base", s.base.name)
            .put("apps", JSONArray(s.apps.toList()))
            .put("entry", s.entryId)
            .put("title", s.title)
            .put("resume", s.resumeId)
            .put("handled", s.handled)
            .put(
                "log",
                JSONArray().apply {
                    _recent.value.forEach { d ->
                        put(
                            JSONObject().put("at", d.at).put("from", d.from).put("to", d.to)
                                .put("apps", d.apps).put("verdict", d.verdict)
                        )
                    }
                },
            )
        runCatching { StoreFiles.writeAtomic(file, o.toString()) }
            .onFailure { eventLog.add("телефон в ленте: состояние не записалось — ${it.message}") }
    }

    private val nowFormat = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"))

    private fun hm(ms: Long): String = PhoneSit.hm(ms)
}
