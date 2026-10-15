package ru.zf.pravka.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import org.json.JSONObject

/**
 * Напоминания Дел — уведомлением телефона (захват, шаг 0 петли, 15.10.2026; задание — docs/dela-phone-22c.md, п. 4).
 *
 * Владелец: «напоминание — отдельно от кучи дел: стоит на „Сегодня“ в своё время, приходит уведомлением телефона (не
 * только ботом в Telegram — там я почти не отвечаю), после срабатывания — „Сделано / Через час / Завтра“ и исчезает».
 * С 06.10 напоминание шлёт только бот; с 02.10 по 15.10 он послал три, нажата одна кнопка. Теперь главный канал —
 * телефон: точный будильник на ближайший миг (`trigger/ReminderAlarms.kt`), показ — `trigger/Reminders.kt`; бот —
 * запасной, через 3 минуты, если телефон не отметил показ (`task.reminded {via: phone}`, флаг `remind_phone`).
 *
 * Здесь — чистое решение под JVM-тестом: что пора показать, когда будить в следующий раз, что написать и какие
 * операции у кнопок. Напоминание по месту («когда приеду домой») становится мигом здесь же: приезд ставит делу
 * `remind_at` = миг приезда (`DelaRemind.arrivalOps`), и план видит его как любое другое.
 */
object ReminderPlan {

    /** Пропущенное (телефон был выключен) показываем не старше суток — с «должно было в 12:00». */
    const val CATCH_UP_MS = 24 * 3_600_000L

    /** Показ позже срока больше чем на две минуты — уже «должно было»: будильник Android точен до секунд. */
    const val LATE_MS = 2 * 60_000L

    /** Память показанного на этом телефоне — последние ключи (`data/ReminderShown.kt`). */
    const val KEEP = 300

    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** Что пора показать: дело, его миг, ключ памяти, опоздали ли. */
    data class Due(val task: Dela.Task, val atMs: Long, val key: String, val late: Boolean)

    /** Миг напоминания в мс; не разобрать — null. */
    fun atMs(t: Dela.Task): Long? = runCatching { java.time.OffsetDateTime.parse(t.remindAt.trim()).toInstant().toEpochMilli() }.getOrNull()

    /**
     * Ключ показа — «дело@миг»: переставили («Через час», «Завтра») — новый ключ, напоминание придёт снова; тот же миг
     * второй раз не показывается (перезапуск, догон после загрузки, второй будильник).
     */
    fun key(t: Dela.Task): String = t.id + "@" + (atMs(t) ?: t.remindAt)

    /** Свои открытые дела и напоминания с мигом — то, что может прозвенеть. Цели не звенят. */
    fun candidates(s: Dela.Snapshot, me: String): List<Dela.Task> =
        s.tasks.values.filter { it.open && !it.goal && (me.isBlank() || it.ownerId.isBlank() || it.ownerId == me) && it.remindAt.isNotBlank() }

    /**
     * Что показать сейчас: миг наступил, не старше [CATCH_UP_MS] и на этом телефоне ещё не показано. Сначала ранние.
     * Сервер уже отметил показ ботом (`reminded_at`) — телефон всё равно показывает: бот сообщает в Telegram, где
     * владелец почти не отвечает, а строка уведомления — с кнопками здесь (без флага `remind_phone` придёт дважды —
     * так и записано в совместимости задания).
     */
    fun due(s: Dela.Snapshot, me: String, now: Long, shown: Set<String>): List<Due> =
        candidates(s, me).mapNotNull { t ->
            val at = atMs(t) ?: return@mapNotNull null
            val k = key(t)
            if (at > now || now - at > CATCH_UP_MS || k in shown) null else Due(t, at, k, late = now - at > LATE_MS)
        }.sortedWith(compareBy<Due>({ it.atMs }, { it.task.num }))

    /** Когда будить в следующий раз: ближайший будущий миг, ещё не показанный; нет — null. */
    fun next(s: Dela.Snapshot, me: String, now: Long, shown: Set<String>): Long? =
        candidates(s, me).mapNotNull { t -> atMs(t)?.takeIf { it > now && key(t) !in shown } }.minOrNull()

    /**
     * Первый запуск (памяти ещё нет): прошедшее не звенит задним числом — после обновления телефон не должен вывалить
     * разом всё, что бот уже прислал за сутки. Будущее — звенит.
     */
    fun seed(s: Dela.Snapshot, me: String, now: Long): Set<String> =
        candidates(s, me).filter { (atMs(it) ?: Long.MAX_VALUE) <= now }.map { key(it) }.toSet()

    /** Память показанного — новые ключи в конец, старые уходят, не больше [KEEP]. */
    fun remember(have: List<String>, add: Collection<String>): List<String> {
        val out = LinkedHashSet(have)
        for (k in add) { out.remove(k); out.add(k) }
        return out.toList().takeLast(KEEP)
    }

    // ------------------------------------------------------------ слова

    /** Заголовок уведомления — само напоминание. */
    fun title(t: Dela.Task): String = t.title.ifBlank { "Напоминание" }

    /**
     * Строка под заголовком: «12:00 · Дела», опоздали — «должно было в 12:00 · Дела» (вчера — «вчера в 12:00»),
     * по месту — «ты дома · Дела» / «приехал: Офис · Дела». Дело со своим сроком — ещё и «дело на пт».
     */
    fun body(d: Due, now: Long, zone: ZoneId): String {
        val t = d.task
        val at = Instant.ofEpochMilli(d.atMs).atZone(zone)
        val day = at.toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val hm = HM.format(at)
        val whenWords = when {
            // По месту миг — это миг приезда: «ты дома» правда и минутой позже.
            t.remindPlace.isNotBlank() -> placeWords(t.remindPlace)
            !d.late -> hm
            day == today -> "должно было в $hm"
            day == today.minusDays(1) -> "должно было вчера в $hm"
            else -> "должно было ${DelaAsk.ddmm(day.toString(), today.toString())} в $hm"
        }
        val tail = if (!t.remind && t.dueDate.isNotBlank()) " · дело на " + dueWords(t.dueDate, today) else ""
        return "$whenWords$tail · Дела"
    }

    /** «ты дома», «приехал: Офис» — как сказано место автопилота. */
    fun placeWords(place: String): String =
        if (Dela.norm(place) in setOf("дом", "дома", "домой")) "ты дома" else "приехал: " + place.trim()

    private fun dueWords(iso: String, today: LocalDate): String {
        val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
        return when (ChronoUnit.DAYS.between(today, d)) {
            0L -> "сегодня"
            1L -> "завтра"
            else -> DelaAsk.ddmm(d.toString(), today.toString())
        }
    }

    /**
     * Время строки на «Сегодня» (`remindWhen` веба): «12:00», по месту — «дома» / место, другой день — «завтра 9:00»,
     * прошедшее неотмеченное — «вчера 12:00 · не отмечено».
     */
    fun whenOnToday(t: Dela.Task, today: String, zone: ZoneId): String {
        val at = atMs(t)
        if (at == null) return if (Dela.norm(t.remindPlace) in setOf("дом", "дома", "домой")) "дома" else t.remindPlace.trim()
        val z = Instant.ofEpochMilli(at).atZone(zone)
        val day = z.toLocalDate()
        val hm = HM.format(z)
        val td = runCatching { LocalDate.parse(today.take(10)) }.getOrNull() ?: return hm
        return when {
            day == td -> hm
            day.isAfter(td) -> (if (day == td.plusDays(1)) "завтра " else DelaAsk.ddmm(day.toString(), today) + " ") + hm
            day == td.minusDays(1) -> "вчера $hm · не отмечено"
            else -> DelaAsk.ddmm(day.toString(), today) + " $hm · не отмечено"
        }
    }

    // ------------------------------------------------------------ перед встречей (идеи)

    /** За сколько до встречи напомнить её идеи (spec-0 §7.6: «за 10 мин — уведомление»). */
    const val MEET_AHEAD_MS = 10 * 60_000L

    /** Встреча календаря, у клиента или человека которой есть живые идеи; [atMs] — когда сказать, [key] — память. */
    data class Meet(val event: CalEvent, val ideas: List<Dela.Idea>, val atMs: Long, val key: String)

    /**
     * Встречи с идеями (ответ владельца 5а: «всплывают перед встречей»): что из [events] (уже отобранных как встречи —
     * `CalendarRules.eligible`) связано с идеями копии (`TodayAgenda.meetingIdeas`).
     */
    fun meetings(events: List<CalEvent>, s: Dela.Snapshot): List<Meet> =
        events.filter { !it.allDay && !it.declined }.mapNotNull { e ->
            val ideas = TodayAgenda.meetingIdeas(e, s)
            if (ideas.isEmpty()) null else Meet(e, ideas, e.start - MEET_AHEAD_MS, "meet@" + e.key)
        }

    /** Пора сказать: за десять минут и до начала встречи, ещё не сказано. Началась — поздно. */
    fun meetDue(meets: List<Meet>, now: Long, shown: Set<String>): List<Meet> =
        meets.filter { it.atMs <= now && now < it.event.start && it.key !in shown }

    /** Когда будить ради встречи: ближайшее «за десять минут» впереди. */
    fun meetNext(meets: List<Meet>, now: Long, shown: Set<String>): Long? =
        meets.filter { it.atMs > now && it.key !in shown }.minOfOrNull { it.atMs }

    /** «Через 10 мин: Ромашка: квартальный» — заголовок; ниже — идеи строками. */
    fun meetTitle(m: Meet, now: Long): String {
        val min = ((m.event.start - now) / 60_000L).coerceAtLeast(1L)
        return "Через $min мин: " + CalendarRules.entryTitle(m.event)
    }

    fun meetBody(m: Meet): String =
        "💡 ${m.ideas.size}: " + m.ideas.take(3).joinToString("; ") { it.text.take(80) } + if (m.ideas.size > 3) "…" else ""

    // ------------------------------------------------------------ кнопки

    /** Через час от сейчас (минуты без секунд) — как «Через час» бота (`remind.act`). */
    fun hourLater(now: Long, zone: ZoneId): String =
        ISO.format(Instant.ofEpochMilli(now).atZone(zone).plusHours(1).truncatedTo(ChronoUnit.MINUTES).toOffsetDateTime())

    /** Завтра в 9:00 — как «Завтра 9:00» бота. */
    fun tomorrowMorning(now: Long, zone: ZoneId): String =
        DelaRemind.at(Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1), DelaRemind.MORNING, zone)

    /** «Сделано»: обычная операция очереди. */
    fun doneOp(t: Dela.Task, opId: String = Dela.newId()): JSONObject = Dela.statusOp("task.done", t.id, opId)

    /** «Через час» и «Завтра»: новый миг, место снимается (как у кнопок бота) — напоминание придёт снова. */
    fun moveOp(t: Dela.Task, iso: String, opId: String = Dela.newId()): JSONObject =
        Dela.setOp(
            t.id,
            JSONObject().put("remind_at", iso).put("remind_place", JSONObject.NULL),
            JSONObject().put("remind_at", Dela.field(t, "remind_at")).put("remind_place", Dela.field(t, "remind_place")),
            opId,
        )

    /**
     * Отметка показа (`task.reminded {via: phone}`): сервер ставит `reminded_at`, `reminded_via = phone`, и бот молчит.
     * `remind_at` — что показано: переставили за это время — отметка «stale», бот не замолчит зря. op_id — от ключа:
     * повтор (догон, второй будильник) не плодит отметок.
     */
    fun shownOp(d: Due, now: Long, zone: ZoneId): JSONObject =
        JSONObject().put("op", "task.reminded").put("op_id", Dela.stableId("reminded-phone-" + d.key))
            .put("id", d.task.id).put("via", "phone").put("remind_at", d.task.remindAt)
            .put("at", DelaRemind.now(now, zone))

    /** Отметка «телефон показывает напоминания» для сервера (`user.settings.remind_phone`). */
    fun settingsOp(on: Boolean, opId: String = Dela.newId()): JSONObject =
        JSONObject().put("op", "user.settings").put("op_id", opId)
            .put("settings", JSONObject().put("remind_phone", JSONObject().put("on", on)))
}
