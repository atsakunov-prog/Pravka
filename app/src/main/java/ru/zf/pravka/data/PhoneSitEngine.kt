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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.AutoWitness
import ru.zf.pravka.core.PhoneSit
import ru.zf.pravka.provider.ClaudeProvider
import ru.zf.pravka.provider.zasechkaPhone

/**
 * Телефон в ленте (13.10.2026, docs/zasechka.md «Телефон в ленте»): долгое
 * сидение в телефоне режет дело врезкой, как звонок. Правила — `core/PhoneSit.kt`
 * (почему этот раз не августовские «пожиратели»), здесь — Android и дороги:
 *
 *  - сидения — из статистики использования (та же, что считает «Телефон по
 *    дням»): система помнит её сама, и тик, пропущенный спящей службой,
 *    ничего не теряет;
 *  - что было на экране — снимки службы раз в минуту (`trigger/ScreenPeek.kt`),
 *    ТОЛЬКО в памяти процесса: на диск текст экрана не пишется никогда, уходит
 *    лишь Сонету при разборе. Служба перезапустилась — Сонет судит по
 *    приложениям;
 *  - кончившееся сидение от порога — Сонету (`ClaudeProvider.zasechkaPhone`),
 *    его куски — в ленту `ZasechkaStore.insertInterruption`: дело режется на
 *    начале и продолжается после, «Убрать» в пуше сшивает обратно;
 *  - отметка «разобрано до» и последние решения — `phone-sit.json` в базе.
 *
 * Тикает пятиминутным тиком службы; включили — смотрит с этого момента, не
 * задним числом (как звонки 06.10).
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
    /** Автопилот службы: пуш «📱 … — Убрать». Выключен — без пуша, как у звонков. */
    private val witness: () -> AutoWitness? = { null },
    /** Свои люди — имя и что о нём сказать Сонету («работа, клиент Ромашка», «семья»). */
    private val people: suspend () -> List<Pair<String, String>> = { emptyList() },
) {

    companion object {
        private const val FILE_NAME = "phone-sit.json"
        private const val SNAPS_MAX = 480
        private const val LOG_MAX = 40
        private const val RECENT_DAYS = 7L
    }

    /** Что решено про одно сидение — строкой в настройки и в журнал. */
    data class Decision(val at: Long, val from: Long, val to: Long, val apps: String, val verdict: String)

    private val _recent = MutableStateFlow<List<Decision>>(emptyList())
    val recent: StateFlow<List<Decision>> = _recent

    /** Когда служба последний раз видела экран — «смотрит ли вообще» для настроек. */
    @Volatile var lastPeekAt: Long = 0L
        private set

    private val snaps = ArrayDeque<PhoneSit.Snap>()
    private val running = AtomicBoolean(false)
    @Volatile private var loaded = false
    private var done = 0L

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

    /** Пятиминутный тик службы: разобрать кончившиеся сидения. Безопасно звать часто. */
    suspend fun tick() {
        if (!running.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) { doTick() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { eventLog.add("телефон в ленте: тик упал: ${e.javaClass.simpleName}: ${e.message}") }
        } finally {
            running.set(false)
        }
    }

    private suspend fun doTick() {
        ensureLoaded()
        val now = System.currentTimeMillis()
        if (!on()) {
            // Выключено — отметки нет: включат — смотрим с того момента, а не
            // задним числом за всё время, пока тумблер стоял.
            if (done != 0L) {
                done = 0L
                persist()
            }
            return
        }
        if (!PhoneSweeper.hasUsageAccess(context)) return
        if (done <= 0L) {
            done = now
            persist()
            eventLog.add("телефон в ленте: включён — смотрю сидения с ${hm(now)}")
            return
        }
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        var mark = maxOf(done, now - PhoneSit.LOOKBACK_MS)
        // Час до отметки — чтобы знать, что было на экране в её миг.
        val events = readEvents(usm, mark - 3600_000L, now)
        val home = homePackages()
        val spans = PhoneSit.spans(events, now) { PhoneSit.furniture(it, home) }
        val minMs = settings.zPhoneMinFlow.first().coerceIn(3, 30) * 60_000L
        for (d in PhoneSit.due(PhoneSit.stretches(spans, now), mark, minMs)) {
            if (d.long) {
                val failed = runCatching { place(d.stretch, minMs, now) }.exceptionOrNull()
                if (failed is kotlin.coroutines.cancellation.CancellationException) throw failed
                if (failed != null) {
                    if (now - d.stretch.end < PhoneSit.GIVE_UP_MS) {
                        eventLog.add(
                            "телефон в ленте: сидение ${hm(d.stretch.start)}–${hm(d.stretch.end)} не разобрано — " +
                                "${failed.message ?: failed.javaClass.simpleName}; попробую следующим тиком"
                        )
                        break
                    }
                    record(d.stretch, PhoneSit.appsLine(d.stretch, ::label), "три часа не разбиралось — бросил: ${failed.message}")
                }
            }
            mark = d.stretch.end
        }
        if (mark != done) {
            done = mark
            persist()
        }
    }

    /** Сидение от порога: что охраняет ленту — не трогаем, остальное — Сонету. */
    private suspend fun place(s: PhoneSit.Stretch, minMs: Long, now: Long) {
        val seen = zasechkaStore.forRange(s.start, s.end).map {
            PhoneSit.Seen(it.start, it.end, it.title, it.category, it.source, it.raw, it.createdAt)
        }
        val g = PhoneSit.guard(s, seen, minMs, now)
        if (g.pieces.isEmpty()) {
            record(s, PhoneSit.appsLine(s, ::label), "дело не режу — ${g.why}")
            return
        }
        for (piece in g.pieces) classify(piece, minMs)
    }

    private suspend fun classify(piece: PhoneSit.Stretch, minMs: Long) {
        val apps = PhoneSit.appsLine(piece, ::label)
        val categories = zasechkaStore.categories()
        val clients = zasechkaStore.clients()
        val reply = claude().zasechkaPhone(promptTail(piece, minMs, categories, clients)).getOrThrow()
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
                "«${entry.title}» [${entry.category}]" +
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
    ): String {
        val now = System.currentTimeMillis()
        val inLane = zasechkaStore.forRange(s.start, s.end).sortedBy { it.start }.map { e ->
            val end = if (e.open) "…" else hm(e.end)
            val who = when (e.source) {
                "gap" -> "дыра, никто не записал"
                "auto" -> "факт робота"
                "calendar" -> "встреча из календаря"
                "task" -> "начато из дела"
                else -> if (e.raw.isNotBlank()) "записал сам: «${e.raw.take(160)}»" else "продолжение после врезки или автопилот"
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
            append("Порог владельца — ${minMs / 60_000L} мин.\n\n")
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
                done = o.optLong("done", 0L)
                val arr = o.optJSONArray("log") ?: JSONArray()
                _recent.value = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let {
                        Decision(it.optLong("at"), it.optLong("from"), it.optLong("to"), it.optString("apps"), it.optString("verdict"))
                    }
                }
            }
            loaded = true
        }
    }

    private fun persist() {
        val o = JSONObject()
            .put("format", "pravka-phone-sit")
            .put("done", done)
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
            .onFailure { eventLog.add("телефон в ленте: отметка не записалась — ${it.message}") }
    }

    private val nowFormat = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"))

    private fun hm(ms: Long): String = PhoneSit.hm(ms)
}
