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
 *    крестиком. Галочка или молчание — дело в ленту вживую с начала сидения.
 *    Крестик — молчим до конца сидения, пока впереди эти приложения;
 *  - после «да» (14.10.2026): экран погас на полминуты — дело закрыто в миг
 *    погасания и продолжается то, что шло до него; гас меньше — поминутное
 *    наблюдение; горит — подтверждение раз в пять минут одним снимком. То же
 *    приложение и тот же предмет (хэш первых строк текста экрана) —
 *    подтверждено без запроса, другое — проверка и вопрос. Погасание и
 *    зажигание экрана — из той же статистики использования, новых разрешений
 *    нет;
 *  - запрос Сонету: правила, категории, клиенты и названия за неделю — под
 *    часовым кэшем, сидение и снимки — после (`PhoneSit.phoneParts`);
 *  - сидение, кончившееся до вопроса (7–10 минут), — как в первом заходе:
 *    врезкой задним числом и тихим пушем с «Убрать»;
 *  - слово владельца и охрана ленты — про своё время (14.10.2026, «сказал
 *    «Завтрак» — и двадцать минут в телефоне тишина»): сидение от порога
 *    после них — новая проверка; конец проверки без вопроса — строкой в
 *    журнал; удар автомата целиком — `PhoneLive.beat`, файл — `toJson`/
 *    `fromJson`: тесты гонят тот же путь, включая файл прежней сборки;
 *  - состояние автомата и последние решения — `phone-sit.json` в базе (текста
 *    экрана там нет: подтверждённое хранится хэшем).
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

        /** Снимок этой минуты: служба кладёт его прямо перед минутой автомата. */
        private const val SNAP_FRESH_MS = 20_000L

        /** Приложение впереди по статистике — если его кусок идёт сейчас. */
        private const val FRONT_FRESH_MS = 5_000L
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

    /**
     * Снимок нужен в эту минуту: идёт проверка куска или пора подтвердить
     * стоящее дело (раз в пять минут, после короткого погасания — каждую).
     * Служба смотрит ТОЛЬКО тогда. Зависит от времени — поэтому считается при
     * чтении, а не зеркалом состояния.
     */
    val wantsSnap: Boolean get() = live.snapDue(System.currentTimeMillis())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val snaps = ArrayDeque<PhoneSit.Snap>()
    @Volatile private var loaded = false
    @Volatile private var live = PhoneLive.State()

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
                    pulseLocked(minute = true)
                }
            }
        }
    }

    /** Пятиминутный тик службы: экран погашен — дело закрывается и отсюда. */
    suspend fun tick() = withContext(Dispatchers.IO) {
        guarded("тик") {
            lock.withLock {
                ensureLoaded()
                pulseLocked(minute = false)
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

    private suspend fun pulseLocked(minute: Boolean) {
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
            set(PhoneLive.ownerTook(live, now))
            eventLog.add("телефон в ленте: «$title» больше не веду — ты переключился сам; дальше сидение от порога — посмотрю снова")
        }
        val minMs = settings.zPhoneMinFlow.first().coerceIn(3, 30) * 60_000L
        val events = eventsNow(now)
        val spans = spansOf(events, now)
        // Сказал что-то в «З», пока шла проверка, — его слово про это время, не спрашиваем.
        if (live.watching) {
            ownerSpokeSince(live.watchFrom)?.let { e ->
                val apps = segment(spans, live.watchFrom, now).spans.map { it.pkg }.toSet()
                set(PhoneLive.guarded(live, apps, now))
                eventLog.add("телефон в ленте: ты сказал «${e.title}» в ${hm(e.createdAt)} — не спрашиваю")
            }
        }
        // Минута экрана (снимок в счёт проверки или сверка стоящего дела) и пульс — одним ударом автомата.
        val before = live
        val step = PhoneLive.beat(live, events, spans, if (minute) seenNow(spans, now) else null, minute, now, minMs)
        set(step.state)
        if (!before.watching && live.watching) startedWatch(spans, now)
        for (d in step.dos) perform(d, spans, minMs)
    }

    /**
     * Что впереди в эту минуту: снимок, взятый службой только что, — приложение
     * и предмет (хэш первых строк текста; сам текст остаётся в памяти снимков);
     * снимка нет (не нужен, текст выключен, Fold складывали) — приложение, чей
     * кусок идёт сейчас по статистике, без предмета. Впереди рабочий стол — null.
     */
    private fun seenNow(spans: List<PhoneSit.Span>, now: Long): PhoneLive.Mark? {
        val snap = synchronized(snaps) { snaps.lastOrNull() }?.takeIf { now - it.at in 0..SNAP_FRESH_MS }
        if (snap != null) return PhoneLive.Mark(snap.pkg, PhoneLive.subject(snap.text))
        val front = spans.maxByOrNull { it.end }?.takeIf { now - it.end <= FRONT_FRESH_MS } ?: return null
        return PhoneLive.Mark(front.pkg, null)
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
            // Охрана — про своё время: дальше сидение от порога — новая проверка.
            set(PhoneLive.guarded(live, apps, now))
            eventLog.add(
                "телефон в ленте: на экран не смотрю — ${g.why}" +
                    (if (live.base == PhoneLive.Base.OFF) "; дальше сидение от порога — посмотрю снова" else "")
            )
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
            is PhoneLive.Note -> eventLog.add("телефон в ленте: ${d.text}")
        }
    }

    /** Сонет про идущий кусок: то же, что в ленте, — молчим; другое — вопрос на плашку. */
    private suspend fun classifyLive(seg: PhoneSit.Stretch) {
        val apps = seg.spans.map { it.pkg }.toSet()
        val categories = zasechkaStore.categories()
        val clients = zasechkaStore.clients()
        val (stable, context) = prompt(seg, LIVE_PART_MS, categories, clients, live = true)
        val reply = claude().zasechkaPhone(stable, context)
        val r = reply.getOrNull()
        if (r == null) {
            lock.withLock { if (live.thinking) set(PhoneLive.failed(live, System.currentTimeMillis())) }
            eventLog.add("телефон в ленте: Сонет не ответил — ${reply.exceptionOrNull()?.message}; ещё три минуты и спрошу снова")
            return
        }
        spent(r)
        val parsed = PhoneSit.parse(r.raw, seg, categories.map { it.name }, LIVE_PART_MS)
        if (parsed == null) eventLog.add("телефон в ленте: ответ Сонета не прочитан: ${r.raw.take(200)}")
        // Что он делает СЕЙЧАС — последний кусок, если он доходит до конца проверки.
        val part = parsed?.parts?.lastOrNull()?.takeIf { seg.end - it.end <= PhoneSit.GAP_MS }
        val guess = part?.let {
            PhoneLive.Guess(it.title, it.category, clients.firstOrNull { c -> c.equals(it.client, ignoreCase = true) }.orEmpty(), it.start)
        }
        val shownApps = PhoneSit.appsLine(seg, ::label)
        val ask = lock.withLock {
            // Ответа уже не ждали (проверка кончилась, Сонет думал дольше
            // THINK_MS) — ни записи, ни плашки: галочка на ней ничего бы не поставила.
            val awaited = live.watching && live.thinking
            val step = PhoneLive.classified(live, guess, apps, System.currentTimeMillis())
            set(step.state)
            if (!awaited) {
                for (d in step.dos) perform(d, emptyList(), 0L)
                return@withLock false
            }
            if (guess == null) {
                record(seg, shownApps, "то же дело — ${parsed?.why?.ifBlank { null } ?: "Сонет не режет"}; молчу, пока впереди эти приложения")
                return@withLock false
            }
            record(seg, shownApps, "спрашиваю: «${guess.title}» [${guess.category}] — ${parsed?.why.orEmpty()}")
            true
        }
        if (ask && guess != null) question(guess)
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
                    val wasOn = live.base == PhoneLive.Base.ON
                    val now = System.currentTimeMillis()
                    val step = PhoneLive.answer(live, yes, now = now)
                    set(step.state)
                    if (!yes) {
                        record(PhoneSit.Stretch(g.from, now, emptyList(), true), appsNames(apps),
                            "нет — «${g.title}» не ставлю, верно то, что в ленте" +
                                (if (wasOn) "; «${live.title}» веду дальше, по экрану больше не сверяю" else ""))
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
            set(PhoneLive.ownerTook(live.copy(apps = live.apps + d.apps), System.currentTimeMillis()))
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
        set(
            PhoneLive.opened(
                live, entry.id, entry.title, d.apps, prev?.id ?: 0L,
                since = d.since, mark = d.mark, now = System.currentTimeMillis(),
            )
        )
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

    /**
     * Экран погас на полминуты (или телефоном не пользуются пять минут): дело
     * закрыто в миг погасания (последнего касания), и продолжается прежнее.
     */
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
            "«${closed.title}» до ${hm(d.at)} — " +
                (if (d.dark) "экран погас" else "телефоном не пользовались пять минут") +
                (back?.let { ", снова «${it.title}»" } ?: ""),
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
        val (stable, context) = prompt(piece, minMs, categories, clients, live = false)
        val reply = claude().zasechkaPhone(stable, context).getOrElse {
            record(piece, apps, "не разобрано — ${it.message ?: it.javaClass.simpleName}")
            return
        }
        spent(reply)
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

    /**
     * Запрос Сонету двумя частями (14.10.2026, деньги): постоянная — категории,
     * клиенты, названия за неделю до сегодня — идёт под часовой кэш вслед за
     * правилами; переменная — время, сидение, лента, приложения по минутам,
     * снимки экрана, кого из своих видно, сегодняшние новые названия. Своих
     * людей целиком в постоянную не кладём: их сотни (люди Дел), а нужны те,
     * кого видно в тексте, — их телефон находит сам, бесплатно.
     */
    private suspend fun prompt(
        s: PhoneSit.Stretch,
        minMs: Long,
        categories: List<ZasechkaStore.Category>,
        clients: List<String>,
        live: Boolean,
    ): Pair<String, String> {
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
        val named = zasechkaStore.forRange(dayStart - RECENT_DAYS * 86_400_000L, now)
            .filter { it.source != "gap" && it.source != "auto" && it.title.isNotBlank() }
            .map { PhoneSit.Named(it.title, it.category, it.start) }
        val stable = PhoneSit.stableBlock(
            categories.map { it.name to it.hint },
            clients,
            PhoneSit.weekNames(named, dayStart, RECENT_DAYS.toInt()),
        )
        val today = PhoneSit.todayNames(named, dayStart, RECENT_DAYS.toInt())
        val ownPhoneEntry = live && this.live.base == PhoneLive.Base.ON
        val context = buildString {
            append("Сейчас: ").append(nowFormat.format(Date(now))).append(".\n")
            append("Сидение: ${hm(s.start)}–${hm(s.end)}, телефон в руках ${PhoneSit.minutes(s.useMs)}. ")
            if (live) {
                append(
                    "Сидение ещё идёт: последний кусок — то, чем он занят сейчас; его спросят вопросом " +
                        "«кажется, это …?». Здесь это продолжение долгого сидения, кусок — от минуты."
                )
                if (ownPhoneEntry) {
                    append(
                        " В ленте сейчас идёт дело, поставленное по телефону и подтверждённое владельцем; " +
                            "на экране поменялось приложение или предмет — реши, то же ли это дело."
                    )
                }
                append("\n\n")
            } else {
                append("Порог владельца — ${minMs / 60_000L} мин.\n\n")
            }
            append("В ленте на это время:\n")
            append(if (inLane.isEmpty()) "(ничего не записано)" else inLane.joinToString("\n")).append("\n\n")
            append("По минутам:\n").append(PhoneSit.timeline(s, ::label)).append("\n\n")
            append("Снимки экрана (раз в минуту, видимый текст):\n")
            append(if (snapLines.isEmpty()) "(текст экрана не читался — суди по приложениям)" else snapLines.joinToString("\n"))
            if (who.isNotEmpty()) append("\n\nКого из своих видно в тексте:\n").append(who.joinToString("\n") { "- $it" })
            if (today.isNotEmpty()) append("\n\nСегодня он ещё называл дела:\n").append(today.joinToString("\n"))
        }
        return stable to context
    }

    /**
     * Деньги вызова: в статистику — как у всех дорог (`stats.recordAux`, цена
     * уже с кэшем: запись на час вдвое, чтение — десятая часть), в журнал —
     * сколько прочитано из кэша и записано в него: так видно, что кэш работает
     * (из кэша 0 на втором вызове за час — что-то в постоянной части пляшет).
     */
    private suspend fun spent(r: ClaudeProvider.ZasechkaForkReply) {
        runCatching { stats.recordAux(r.costUsd, r.tokensIn, r.tokensOut, route = ModelRoute.ZASECHKA_PHONE.key) }
        eventLog.add(
            "телефон в ленте: Сонет — вход ${r.tokensIn} (из кэша ${r.cacheReadTokens}, в кэш ${r.cacheWriteTokens}), " +
                "выход ${r.tokensOut}, $" + String.format(Locale.US, "%.4f", r.costUsd)
        )
    }

    /** Кусок [from, to) из кусков переднего плана. */
    private fun segment(spans: List<PhoneSit.Span>, from: Long, to: Long): PhoneSit.Stretch =
        PhoneSit.Stretch(from, to, spans, finished = false).clip(from, to)

    /** События экрана за последние часы — из статистики использования. */
    private fun eventsNow(now: Long): List<PhoneSit.Ev> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val from = maxOf(live.handled, now - PhoneSit.LOOKBACK_MS) - 3600_000L
        return readEvents(usm, from, now)
    }

    /** Куски переднего плана после разобранного. */
    private fun spansOf(events: List<PhoneSit.Ev>, now: Long): List<PhoneSit.Span> {
        val home = homePackages()
        return PhoneSit.spans(events, now) { PhoneSit.furniture(it, home) }
            .filter { it.end > live.handled }
    }

    private fun appsNames(apps: Set<String>): String = apps.joinToString(" · ") { label(it) }

    /** Новое состояние автомата: зеркало для настроек, файл — если поменялось решённое. */
    private fun set(s: PhoneLive.State) {
        val old = live
        live = s
        _status.value = statusLine(s)
        if (old.base != s.base || old.apps != s.apps || old.entryId != s.entryId || old.handled != s.handled ||
            old.resumeId != s.resumeId || old.title != s.title || old.since != s.since || old.marks != s.marks ||
            old.litAt != s.litAt || old.freshFrom != s.freshFrom
        ) persist()
    }

    private fun statusLine(s: PhoneLive.State): String = when {
        s.guess != null -> "спросил: «${s.guess.title}»?"
        s.thinking -> "Сонет смотрит, что это"
        s.watching -> "смотрю на экран с ${hm(s.watchFrom)} — снимок ${s.snaps} из ${PhoneLive.ASK_SNAPS}"
        s.base == PhoneLive.Base.ON && s.everyMinute ->
            "веду «${s.title}» — экран гас, сверяю каждую минуту (то же ${s.sameRun} из ${PhoneLive.MINUTE_RUN})"
        s.base == PhoneLive.Base.ON && s.marks.isNotEmpty() ->
            "веду «${s.title}» — сверяю экран раз в пять минут" +
                (if (s.confirmAt > 0L) ", следующий раз в ${hm(s.confirmAt)}" else "") +
                "; экран погаснет на полминуты — дело кончится"
        s.base == PhoneLive.Base.ON ->
            "веду «${s.title}» — на экран не смотрю, пока впереди ${appsNames(s.apps)}; экран погаснет на полминуты — дело кончится"
        s.base == PhoneLive.Base.OFF && s.freshFrom > 0L ->
            "молчу, пока впереди ${appsNames(s.apps)}; сидение от порога после ${hm(s.freshFrom)} — посмотрю снова"
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
                // Константа с Android 9 (вписывается при сборке, как у `PhoneSweeper`);
                // на восьмёрке события нет — погасание тянется до конца, и дело
                // кончается по полуминуте темноты, как и должно.
                UsageEvents.Event.SCREEN_INTERACTIVE -> out += PhoneSit.Lit(e.timeStamp)
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
                // Состояние — тем же разбором, что в тестах (`PhoneLive.fromJson`): обновление посреди сидения.
                live = PhoneLive.fromJson(o, System.currentTimeMillis())
            }
            _status.value = statusLine(live)
            loaded = true
        }
    }

    private fun persist() {
        // Подтверждённое — приложение и ХЭШ предмета: текст экрана на диск не пишется никогда.
        val o = PhoneLive.toJson(live)
            .put("format", "pravka-phone-sit")
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
