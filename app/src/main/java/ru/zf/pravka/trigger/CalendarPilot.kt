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
import ru.zf.pravka.core.CalendarRules

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
     * Что помним о событии: [entryId] — запись, которую начали, [prevId] —
     * что шло до неё, [end] — конец события, [done] — решено (закрыта или
     * пропущена), больше не смотрим.
     */
    private data class Mark(val entryId: Long, val prevId: Long, val end: Long, val title: String, val done: Boolean)

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
                    end = m.optLong("end"),
                    title = m.optString("title"),
                    done = m.optBoolean("done"),
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
                JSONObject().put("entry", m.entryId).put("prev", m.prevId).put("end", m.end)
                    .put("title", m.title).put("done", m.done),
            )
        }
        service.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATE, o.toString()).apply()
    }

    private suspend fun sweep() {
        val now = System.currentTimeMillis()
        val events = query(now - LOOK_BACK_MS, now + LOOK_AHEAD_MS)
        val marks = load()

        // Сначала концы: встреча, которую начали, кончилась по календарю.
        for ((key, mk) in marks.toList()) {
            if (now - mk.end > KEEP_MS) {
                marks.remove(key)
                continue
            }
            if (mk.done) continue
            // Событие могли подвинуть — конец берём свежий, если оно ещё видно.
            val end = events.firstOrNull { it.key == key }?.end ?: mk.end
            val open = app.zasechkaStore.openEntry()
            when (CalendarRules.endVerdict(end, now, open?.id, mk.entryId)) {
                CalendarRules.End.CLOSE -> {
                    closeMeeting(mk, end, now)
                    marks[key] = mk.copy(end = end, done = true)
                }
                CalendarRules.End.NONE -> {
                    app.eventLog.add("календарь: «${mk.title}» — в ленте уже другое, конец не трогаю")
                    marks[key] = mk.copy(end = end, done = true)
                }
                CalendarRules.End.WAIT -> {}
            }
        }

        // Потом начала.
        val watch = watched
        for (e in events.filter { CalendarRules.eligible(it, watch) }.sortedBy { it.start }) {
            if (marks.containsKey(e.key)) continue
            val open = app.zasechkaStore.openEntry()
            val latest = app.zasechkaStore.lastEntry()?.start ?: 0L
            when (CalendarRules.startVerdict(e, now, open?.title, open?.category, latest)) {
                CalendarRules.Start.START -> {
                    val entry = startMeeting(e, open, now)
                    marks[e.key] = Mark(entry.id, open?.id ?: 0L, e.end, entry.title, done = false)
                }
                CalendarRules.Start.SKIP -> {
                    app.eventLog.add(
                        "календарь: «${e.title}» ${pilot.timeHm(e.start)} — пропущена: " +
                            when {
                                now - e.start > CalendarRules.LATE_MS -> "началась давно"
                                open != null && ru.zf.pravka.core.AutoPilotRules.travelish(open.title, open.category) -> "в дороге"
                                else -> "ты уже сказал, что делаешь («${open?.title ?: "—"}»)"
                            }
                    )
                    marks[e.key] = Mark(0L, 0L, e.end, e.title, done = true)
                }
                CalendarRules.Start.WAIT -> {}
            }
        }
        save(marks)
    }

    private suspend fun startMeeting(e: CalEvent, open: ru.zf.pravka.data.ZasechkaStore.Entry?, now: Long): ru.zf.pravka.data.ZasechkaStore.Entry {
        val entry = app.zasechkaStore.startEntry(
            start = e.start,
            raw = "",
            title = CalendarRules.entryTitle(e),
            category = category,
            client = "",
            useful = 0,
            // Владельческий источник, как у поездки и дела места: встреча —
            // дело владельца, робот лишь угадал название по календарю.
            source = "voice",
        )
        app.zasechkaSync.kickSoon(scope)
        lastFire = "встреча «${entry.title}» с ${pilot.timeHm(e.start)}"
        pilot.notify(
            "📅 ${entry.title}",
            "С ${pilot.timeHm(e.start)} по календарю, до ${pilot.timeHm(e.end)} [$category]." +
                (open?.let { " «${it.title}» закрыто в ${pilot.timeHm(e.start)}, ${it.durationMin(e.start)} мин." } ?: "") +
                " Не встреча — «Отменить»; другое — скажи, запишу с ${pilot.timeHm(e.start)}.",
            listOf(
                pilot.action("Отменить", AutoPilot.WHAT_UNDO, now, "", id = entry.id, prevId = open?.id ?: 0L),
                pilot.sayAction(e.start),
            ),
        )
        app.eventLog.add(
            "календарь: началась «${entry.title}» с ${pilot.timeHm(e.start)} [$category]" +
                (open?.let { ", закрыто «${it.title}»" } ?: "")
        )
        return entry
    }

    /**
     * Конец встречи: закрыть её концом события и вернуть дело, которое она
     * прервала, — то, что кончилось ровно там, где встреча началась. Нечего
     * возвращать — просто закрыть; «Ещё идёт» откроет встречу обратно.
     */
    private suspend fun closeMeeting(mk: Mark, end: Long, now: Long) {
        val closed = app.zasechkaStore.closeOpen(end) ?: return
        val all = app.zasechkaStore.all()
        val before = all
            .filter { !it.open && it.id != closed.id && it.end <= closed.start + 60_000L }
            .maxByOrNull { it.end }
        val resumed = before
            ?.takeIf { CalendarRules.resumable(it.title, it.category, it.source, it.end, closed.start, closed.title) }
            ?.let { app.zasechkaStore.startEntry(end, "", it.title, it.category, it.client, it.useful, "voice") }
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
                (resumed?.let { ", снова «${it.title}»" } ?: "")
        )
    }

    /** Экземпляры событий, пересекающие [from, to], только из видимых календарей. */
    private fun query(from: Long, to: Long): List<CalEvent> {
        val primaryById = calendars(service).associate { it.id to it.primary }
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
        service.contentResolver.query(uri.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")
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
}
