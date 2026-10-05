package ru.zf.pravka.trigger

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.core.CalEvent
import ru.zf.pravka.core.CalProject
import ru.zf.pravka.core.CalSeen
import ru.zf.pravka.core.CalendarRules
import ru.zf.pravka.data.ZasechkaStore

/** Календарь телефона — для списка с тумблерами в настройках автопилота. */
data class CalendarInfo(val id: Long, val name: String, val account: String, val primary: Boolean)

/**
 * Встречи из календаря телефона — в ленту как работа. Владелец (27.09.2026):
 * «надо брать мой календарь и ставить встречи как „работу“».
 *
 * Живёт на тике службы (раз в ~5 минут) рядом с автопилотом и говорит его
 * пушами. Читает `CalendarContract.Instances` — экземпляры событий, у
 * повторяющихся свой на каждый день, — только видимые календари и только те,
 * что владелец выбрал (с завода — основной календарь аккаунта: семейный с
 * «Боря — бассейн» работой быть не должен). Что решать — `core/CalendarRules.kt`,
 * под JVM-тестами; здесь — чтение, запись в ленту и слова.
 *
 * Встреча НАЧИНАЕТСЯ САМА с начала события и закрывает текущее дело — как
 * поездка по Bluetooth машины; по концу события (с пятиминутным запасом)
 * закрывается и ВОЗВРАЩАЕТ дело, которое прервала, — как туалет по метке NFC:
 * встреча посреди работы — перерыв, а не конец работы. Ошибку чинят кнопки
 * пуша: «Отменить» у начала, «Ещё идёт» у конца, «Сказать» — везде, с якорем
 * в момент шва. Что уже начато и что уже решено — в `pravka_internal`, чтобы
 * перезапуск службы не начал ту же встречу второй раз.
 *
 * С 05.10.2026 встреча узнаёт себя в словах владельца (`CalendarRules.sameMeeting`):
 * сказал «встречи в Птиц» до неё — новой записи нет, по концу закрывается его
 * запись; сказал то же самое после её начала — его запись забирает начало
 * встречи (`ZasechkaStore.absorb`). Клиент — проект Дел по имени или алиасу в
 * названии. Записи автопилота — со своим источником `calendar`.
 */
class CalendarPilot(
    private val service: PravkaAccessibilityService,
    private val app: PravkaApp,
    private val scope: CoroutineScope,
    private val pilot: AutoPilot,
) {

    companion object {
        private const val PREFS = "pravka_internal"
        private const val KEY_STATE = "cal_pilot_state"
        /** Смотрим события, начавшиеся не раньше шести часов назад (длиннее — не встречи). */
        private const val LOOK_BACK_MS = 6 * 3_600_000L
        /** И на четверть часа вперёд: тик раз в пять минут, начало не пропустим. */
        private const val LOOK_AHEAD_MS = 15 * 60_000L
        /** Память о событии — сутки после его конца. */
        private const val KEEP_MS = 24 * 3_600_000L
        /**
         * Возвращаем прерванное дело, только если конец заметили вовремя:
         * телефон спал час после встречи — что было потом, лента не знает.
         */
        private const val RESUME_LATE_MS = 30 * 60_000L

        fun hasPermission(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

        /**
         * Календари телефона. «Основной» — тот, чей владелец и есть аккаунт
         * (у Google так устроен личный календарь; праздники, дни рождения и
         * семейный — с другими владельцами).
         */
        fun calendars(ctx: Context): List<CalendarInfo> {
            if (!hasPermission(ctx)) return emptyList()
            val proj = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.OWNER_ACCOUNT,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.IS_PRIMARY,
            )
            val out = ArrayList<CalendarInfo>()
            runCatching {
                ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(4) != 1) continue
                        val account = c.getString(2).orEmpty()
                        val owner = c.getString(3).orEmpty()
                        val primary = c.getInt(5) == 1 ||
                            (account.isNotBlank() && account.equals(owner, ignoreCase = true))
                        out.add(CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), account, primary))
                    }
                }
            }
            return out.sortedWith(compareByDescending<CalendarInfo> { it.primary }.thenBy { it.name })
        }

        /** Экземпляры событий, пересекающие [from, to], только из видимых календарей. */
        fun query(ctx: Context, from: Long, to: Long): List<CalEvent> {
            if (!hasPermission(ctx)) return emptyList()
            val primaryById = calendars(ctx).associate { it.id to it.primary }
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uri, from)
            ContentUris.appendId(uri, to)
            val proj = arrayOf(
                CalendarContract.Instances._ID,
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                CalendarContract.Instances.AVAILABILITY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.VISIBLE,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.STATUS,
            )
            val out = ArrayList<CalEvent>()
            ctx.contentResolver.query(uri.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")
                ?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(9) != 1) continue
                        if (c.getInt(11) == CalendarContract.Events.STATUS_CANCELED) continue
                        val calId = c.getLong(10)
                        out.add(
                            CalEvent(
                                id = c.getLong(0),
                                eventId = c.getLong(1),
                                title = c.getString(2).orEmpty(),
                                start = c.getLong(3),
                                end = c.getLong(4),
                                allDay = c.getInt(5) == 1,
                                declined = c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                                free = c.getInt(7) == CalendarContract.Events.AVAILABILITY_FREE,
                                calendar = c.getString(8).orEmpty(),
                                primary = primaryById[calId] ?: false,
                            )
                        )
                    }
                }
            return out
        }

        /**
         * Что автопилот видит сегодня — строкой в настройки: молчащий календарь
         * читается как поломка (владелец, 05.10.2026: «я давно просил…» — а
         * встречи с 27.09 ни разу не легли, и по экрану не понять почему).
         */
        fun todayLine(ctx: Context, watched: Set<String>?): String {
            if (!hasPermission(ctx)) return "нет доступа к календарю"
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val from = cal.timeInMillis
            val all = runCatching { query(ctx, from, from + 86_400_000L) }.getOrElse { e ->
                return "календарь не прочитался: ${e.javaClass.simpleName}: ${e.message}"
            }
            val mine = all.filter { CalendarRules.eligible(it, watched) && it.start >= from }
            if (all.isEmpty()) return "сегодня в календарях телефона событий нет — если в Google они есть, " +
                "проверь, что аккаунт синхронизирует календарь на телефоне"
            if (mine.isEmpty()) return "сегодня событий ${all.size}, но в выбранных календарях встреч нет"
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            return "сегодня: " + mine.joinToString(" · ") { e ->
                hm.format(java.util.Date(e.start)) + " " + CalendarRules.entryTitle(e)
            }
        }
    }

    private val jobs = mutableListOf<Job>()
    @Volatile private var on = true
    @Volatile private var category = CalendarRules.DEFAULT_CATEGORY
    /** null — не выбирали: только основной календарь (см. `Settings.autoCalendarsFlow`). */
    @Volatile private var watched: Set<String>? = null
    private val running = AtomicBoolean(false)
    /** Последнее, что сделал, — в строку состояния настроек. */
    @Volatile var lastFire: String = ""
        private set

    fun start() {
        jobs += scope.launch { app.settings.autoCalOnFlow.collect { on = it } }
        jobs += scope.launch { app.settings.autoCalCategoryFlow.collect { category = it } }
        jobs += scope.launch { app.settings.autoCalendarsFlow.collect { watched = it } }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** Тик службы. Сам себя не накладывает; без доступа к календарю молчит. */
    fun tick() {
        if (!on || !hasPermission(service)) return
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { sweep() }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                runCatching { app.eventLog.add("календарь: тик упал: ${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * Что помним о событии: [entryId] — запись встречи (начатая автопилотом
     * или узнанная запись владельца — тогда [adopted]), [prevId] — что шло до
     * неё, [resumedId] — что автопилот вернул после неё, [start]/[end] и
     * [event] — время и название события (событие могли удалить из календаря,
     * а решать по нему ещё надо), [done] — решено, больше не смотрим.
     */
    private data class Mark(
        val entryId: Long,
        val prevId: Long,
        val start: Long,
        val end: Long,
        val title: String,
        val event: String,
        val done: Boolean,
        val adopted: Boolean = false,
        val resumedId: Long = 0L,
    )

    private fun load(): MutableMap<String, Mark> {
        val raw = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, "").orEmpty()
        val out = HashMap<String, Mark>()
        if (raw.isBlank()) return out
        runCatching {
            val o = JSONObject(raw)
            for (key in o.keys()) {
                val m = o.optJSONObject(key) ?: continue
                out[key] = Mark(
                    entryId = m.optLong("entry"),
                    prevId = m.optLong("prev"),
                    // Метки прежней сборки начала не помнят: ключ — «событие@начало».
                    start = m.optLong("start").takeIf { it > 0L } ?: key.substringAfter('@').toLongOrNull() ?: 0L,
                    end = m.optLong("end"),
                    title = m.optString("title"),
                    event = m.optString("event").ifBlank { m.optString("title") },
                    done = m.optBoolean("done"),
                    adopted = m.optBoolean("adopted"),
                    resumedId = m.optLong("resumed"),
                )
            }
        }
        return out
    }

    private fun save(marks: Map<String, Mark>) {
        val o = JSONObject()
        for ((key, m) in marks) {
            o.put(
                key,
                JSONObject().put("entry", m.entryId).put("prev", m.prevId).put("start", m.start).put("end", m.end)
                    .put("title", m.title).put("event", m.event).put("done", m.done)
                    .put("adopted", m.adopted).put("resumed", m.resumedId),
            )
        }
        service.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATE, o.toString()).apply()
    }

    private fun ZasechkaStore.Entry.seen() = CalSeen(id, start, title, category, client, source)

    /** Что идёт: заполнитель «не размечено» — не дело. */
    private suspend fun openDeal(): ZasechkaStore.Entry? =
        app.zasechkaStore.openEntry()?.takeIf { it.source != "gap" }

    private suspend fun sweep() {
        val now = System.currentTimeMillis()
        val events = query(service, now - LOOK_BACK_MS, now + LOOK_AHEAD_MS)
        val marks = load()

        // Сначала идущие встречи: слова владельца поверх догадки и концы.
        for ((key, m0) in marks.toList()) {
            if (now - m0.end > KEEP_MS) {
                marks.remove(key)
                continue
            }
            if (m0.done) continue
            var mk = m0
            // Событие могли подвинуть — время берём свежее, если оно ещё видно.
            val ev = events.firstOrNull { it.key == key }
                ?: CalEvent(0L, 0L, mk.event, mk.start, mk.end, false, false, false, "", true)
            mk = mk.copy(end = ev.end)
            if (!mk.adopted) mk = absorbIfSaid(mk, ev)
            val open = openDeal()
            when (CalendarRules.endVerdict(ev, ev.end, now, open?.seen(), mk.entryId)) {
                CalendarRules.End.CLOSE -> {
                    val adopted = mk.adopted || open?.id != mk.entryId
                    val resumed = closeMeeting(mk.copy(adopted = adopted), ev, now)
                    marks[key] = mk.copy(done = true, adopted = adopted, resumedId = resumed)
                }
                CalendarRules.End.NONE -> {
                    app.eventLog.add("календарь: «${mk.title}» — в ленте уже другое, конец не трогаю")
                    marks[key] = mk.copy(done = true)
                }
                CalendarRules.End.WAIT -> marks[key] = mk
            }
        }

        // Потом начала.
        val watch = watched
        val own = marks.values.flatMap { listOf(it.entryId, it.resumedId) }.filter { it > 0L }.toSet()
        for (e in events.filter { CalendarRules.eligible(it, watch) }.sortedBy { it.start }) {
            if (marks.containsKey(e.key)) continue
            val open = openDeal()
            // Сказал владелец — не сам автопилот: его встречи и возвращённые
            // после них дела словами владельца не считаются (иначе встреча
            // в 12:00 сразу после встречи в 11:00 решила бы, что ты «уже сказал»).
            val latest = app.zasechkaStore.all()
                .filter { it.source != "gap" && it.source != "auto" && it.source != CalendarRules.SOURCE && it.id !in own }
                .maxOfOrNull { it.start } ?: 0L
            // Идущая встреча автопилота (или возвращённое им дело) — не слова
            // владельца: две встречи с ПТИЦ подряд — две записи, а не одна.
            val view = open?.takeIf { it.id !in own }?.seen()
            when (CalendarRules.startVerdict(e, now, view, latest)) {
                CalendarRules.Start.START -> {
                    val entry = startMeeting(e, open, now)
                    marks[e.key] = Mark(entry.id, open?.id ?: 0L, e.start, e.end, entry.title, e.title, done = false)
                }
                CalendarRules.Start.ADOPT -> {
                    val o = open ?: continue
                    lastFire = "«${e.title}» уже идёт твоим «${o.title}» — закрою в ${pilot.timeHm(e.end)}"
                    app.eventLog.add(
                        "календарь: «${e.title}» ${pilot.timeHm(e.start)} — уже в ленте твоим «${o.title}», " +
                            "второй записи нет; по концу события закрою её"
                    )
                    marks[e.key] = Mark(o.id, 0L, e.start, e.end, o.title, e.title, done = false, adopted = true)
                }
                CalendarRules.Start.SKIP -> {
                    app.eventLog.add(
                        "календарь: «${e.title}» ${pilot.timeHm(e.start)} — пропущена: " +
                            CalendarRules.skipWhy(e, now, view)
                    )
                    marks[e.key] = Mark(0L, 0L, e.start, e.end, e.title, e.title, done = true)
                }
                CalendarRules.Start.WAIT -> {}
            }
        }
        save(marks)
    }

    /**
     * Встреча началась сама, а владелец сказал то же своими словами («созвон с
     * птицами» в 11:07 к «ПТИЦ - ЗФ» с 11:00): его запись забирает начало
     * встречи, догадка уходит. Владелец сказал «с 11» — догадка уже погибла
     * нулевым куском, а встреча — его запись: она узнаётся по концу события.
     */
    private suspend fun absorbIfSaid(mk: Mark, ev: CalEvent): Mark {
        if (mk.entryId <= 0L) return mk
        val all = app.zasechkaStore.all()
        val guess = all.firstOrNull { it.id == mk.entryId } ?: return mk
        if (guess.open) return mk
        val said = all.firstOrNull {
            it.id != guess.id && it.source != "gap" && it.source != "auto" &&
                kotlin.math.abs(it.start - guess.end) < 60_000L
        } ?: return mk
        if (!CalendarRules.absorbs(ev, guess.seen(), guess.end, said.seen(), ev.end)) return mk
        val merged = app.zasechkaStore.absorb(guess.id, said.id) ?: return mk
        app.zasechkaSync.kickSoon(scope)
        lastFire = "«${merged.title}» — твоими словами с ${pilot.timeHm(merged.start)}"
        app.eventLog.add(
            "календарь: «${guess.title}» с ${pilot.timeHm(guess.start)} — ты сказал «${said.title}», " +
                "это та же встреча: твоя запись теперь с ${pilot.timeHm(merged.start)}"
        )
        return mk.copy(entryId = merged.id, title = merged.title, adopted = true)
    }

    private suspend fun startMeeting(e: CalEvent, open: ZasechkaStore.Entry?, now: Long): ZasechkaStore.Entry {
        val title = CalendarRules.entryTitle(e)
        // Владелец однажды поправил категорию этой встречи — так и дальше
        // (регулярная по понедельникам приезжает с одним и тем же именем).
        val learned = app.zasechkaStore.all()
            .lastOrNull {
                it.title.equals(title, ignoreCase = true) && it.category.isNotBlank() &&
                    it.source != "gap" && it.source != "auto"
            }?.category
        val cat = learned ?: CalendarRules.entryCategory(e, category)
        val project = if (CalendarRules.kind(e) == CalendarRules.Kind.MEETING) projectOf(e) else null
        val entry = app.zasechkaStore.startEntry(
            start = e.start,
            raw = "",
            title = title,
            category = cat,
            client = project?.name.orEmpty(),
            useful = 0,
            // Свой источник: дело владельца (обвязка ленты считает его ручным,
            // как поездку), но автопилот узнаёт свои записи и не принимает их
            // за «владелец уже сказал».
            source = CalendarRules.SOURCE,
            project = project?.id.orEmpty(),
        )
        app.zasechkaSync.kickSoon(scope)
        lastFire = "встреча «${entry.title}» с ${pilot.timeHm(e.start)}"
        pilot.notify(
            "📅 ${entry.title}",
            "С ${pilot.timeHm(e.start)} по календарю, до ${pilot.timeHm(e.end)} [$cat]" +
                (project?.let { ", клиент ${it.name}" } ?: "") + "." +
                (open?.let { " «${it.title}» закрыто в ${pilot.timeHm(e.start)}, ${it.durationMin(e.start)} мин." } ?: "") +
                " Не встреча — «Отменить»; другое — скажи, запишу с ${pilot.timeHm(e.start)}.",
            listOf(
                pilot.action("Отменить", AutoPilot.WHAT_UNDO, now, "", id = entry.id, prevId = open?.id ?: 0L),
                pilot.sayAction(e.start),
            ),
        )
        app.eventLog.add(
            "календарь: началась «${entry.title}» с ${pilot.timeHm(e.start)} [$cat]" +
                (project?.let { " · ${it.name}" } ?: "") +
                (open?.let { ", закрыто «${it.title}»" } ?: "")
        )
        return entry
    }

    /** Проект Дел по названию встречи: только клиенты, только когда Дела на своём сервере. */
    private fun projectOf(e: CalEvent): CalProject? {
        if (!app.delaServer.value) return null
        val projects = app.delaStore.view.value.liveProjects()
            .filter { it.sphere == "work" && it.kind == "client" }
            .map { CalProject(it.id, it.name, it.aliases) }
        return CalendarRules.projectOf(e.title, projects)
    }

    /**
     * Конец встречи: закрыть её концом события. Встречу, которую начал сам
     * автопилот, — с возвратом дела, которое она прервала (то, что кончилось
     * ровно там, где встреча началась); узнанную запись владельца — без
     * возврата: к ней он перешёл сам. Нечего возвращать — просто закрыть;
     * «Ещё идёт» откроет встречу обратно. Возвращает id возвращённого дела.
     */
    private suspend fun closeMeeting(mk: Mark, ev: CalEvent, now: Long): Long {
        val end = ev.end
        val closed = app.zasechkaStore.closeOpen(end) ?: return 0L
        val all = app.zasechkaStore.all()
        val before = all
            .filter { !it.open && it.id != closed.id && it.end <= closed.start + 60_000L }
            .maxByOrNull { it.end }
        val resumed = before
            ?.takeIf {
                !mk.adopted && CalendarRules.kind(ev) == CalendarRules.Kind.MEETING &&
                    now - end <= RESUME_LATE_MS &&
                    CalendarRules.resumable(it.title, it.category, it.source, it.end, closed.start, closed.title)
            }
            ?.let {
                app.zasechkaStore.startEntry(
                    end, "", it.title, it.category, it.client, it.useful,
                    source = it.source, task = it.task, project = it.project,
                )
            }
        app.zasechkaSync.kickSoon(scope)
        lastFire = "встреча «${closed.title}» закрыта ${pilot.timeHm(end)}"
        pilot.notify(
            "📅 «${closed.title}» закончилась",
            "По календарю в ${pilot.timeHm(end)}, ${closed.durationMin()} мин." +
                (resumed?.let { " Снова «${it.title}» с ${pilot.timeHm(end)}." } ?: " Открытого дела нет.") +
                " Ещё идёт — «Ещё идёт»; другое — скажи, запишу с ${pilot.timeHm(end)}.",
            listOf(
                if (resumed != null) {
                    pilot.action("Ещё идёт", AutoPilot.WHAT_MEETING_ON, now, "", id = resumed.id, prevId = closed.id)
                } else {
                    pilot.action("Ещё идёт", AutoPilot.WHAT_REOPEN, now, "", id = closed.id)
                },
                pilot.sayAction(end),
            ),
        )
        app.eventLog.add(
            "календарь: «${closed.title}» закрыта концом события ${pilot.timeHm(end)}" +
                (if (mk.adopted) " (твоя запись — ты не переключился)" else "") +
                (resumed?.let { ", снова «${it.title}»" } ?: "")
        )
        return resumed?.id ?: 0L
    }
}
