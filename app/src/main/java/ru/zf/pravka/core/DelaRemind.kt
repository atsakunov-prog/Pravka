package ru.zf.pravka.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import org.json.JSONObject

/**
 * Напоминания дел в Telegram (06.10.2026, контракт — server/contract/dela-remind.json,
 * задание серверу — docs/dela-server-remind.md).
 *
 * Владелец: «чтобы у меня шли напоминания через мой Ковчег-бот в Телеграме…
 * „напомни мне в 11:00“ или „напомни, когда я приеду домой“». Напоминание —
 * два поля дела на сервере: `remind_at` (момент) и `remind_place` (место по
 * приезду). Шлёт его сервер через бота; телефон его ставит (голосом и
 * карточкой) и — единственный, кто знает, где владелец, — превращает место в
 * момент: увидел приезд домой — ставит делу `remind_at` = миг приезда, а
 * сервер отправляет его тем же путём, что и напоминание по времени.
 *
 * Здесь — то, что не знает ни про сеть, ни про Android, и живёт под тестом.
 */
object DelaRemind {

    /** Сервер хранит timestamptz и отдаёт «2026-10-06T11:00:00+03:00»: та же форма — без ложных правок. */
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DM: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")
    private val WD = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    /** Слова без часов — те же, что в общем промпте разбора (server/contract/prompts/raznoska.txt). */
    const val MORNING = "09:00"
    const val EVENING = "19:00"

    // ------------------------------------------------------------ время

    /**
     * Ответ модели «2026-10-06 11:00» (по местному времени владельца) — в ISO
     * со смещением этого часового пояса. Принимает и «T», и секунды, и уже
     * готовое смещение. Не время — пустая строка: лучше без напоминания,
     * чем напоминание в выдуманный миг.
     */
    fun iso(said: String, zone: ZoneId): String {
        val s = said.trim()
        if (s.isEmpty()) return ""
        runCatching { return ISO.format(OffsetDateTime.parse(s).truncatedTo(ChronoUnit.SECONDS)) }
        val m = Regex("^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{1,2})[:.](\\d{2})(?::\\d{2})?$").find(s) ?: return ""
        return runCatching {
            val date = LocalDate.parse(m.groupValues[1])
            val h = m.groupValues[2].toInt()
            val mi = m.groupValues[3].toInt()
            if (h > 23 || mi > 59) return ""
            ISO.format(date.atTime(h, mi).atZone(zone).toOffsetDateTime())
        }.getOrDefault("")
    }

    /** Миг напоминания в местном времени; не разобрать — null. */
    fun local(remindAt: String, zone: ZoneId): LocalDateTime? =
        runCatching { OffsetDateTime.parse(remindAt.trim()).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()

    /** День и время местного напоминания — в ISO: карточка правит их по отдельности. */
    fun at(date: LocalDate, time: String, zone: ZoneId): String {
        val t = Dela.normTime(time) ?: MORNING
        val (h, mi) = t.split(":").map { it.toInt() }
        return ISO.format(date.atTime(h, mi).atZone(zone).toOffsetDateTime())
    }

    /** Миг «сейчас» в той же форме — так телефон отмечает приезд. */
    fun now(nowMs: Long, zone: ZoneId): String =
        ISO.format(java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS))

    /** Быстрые чипы карточки: через час, вечером (если вечер ещё впереди), завтра утром. */
    data class Quick(val label: String, val iso: String)

    fun quick(now: ZonedDateTime): List<Quick> {
        val zone = now.zone
        val out = mutableListOf<Quick>()
        val hour = now.plusHours(1).truncatedTo(ChronoUnit.MINUTES)
        out += Quick("через час", ISO.format(hour.toOffsetDateTime()))
        val evening = now.toLocalDate().atTime(19, 0)
        if (now.toLocalDateTime().isBefore(evening.minusMinutes(30))) {
            out += Quick("вечером 19:00", ISO.format(evening.atZone(zone).toOffsetDateTime()))
        }
        out += Quick("завтра 09:00", at(now.toLocalDate().plusDays(1), MORNING, zone))
        return out
    }

    // ------------------------------------------------------------ места

    /**
     * Место, как его назвала модель или человек, — в имя места автопилота.
     * «дома», «домой» — это «дом», «на даче» — «дача»: сверяем без регистра,
     * «ё» и падежного хвоста. Нет такого места — пустая строка.
     */
    fun matchPlace(named: String, places: Collection<String>): String {
        val n = Dela.norm(named) ?: return ""
        places.firstOrNull { Dela.norm(it) == n }?.let { return it }
        val st = stem(n)
        if (st.length < 3) return ""
        return places.firstOrNull { p -> Dela.norm(p)?.let { stem(it) } == st }.orEmpty()
    }

    /** Падежный хвост: до двух гласных, «й» и «ь» с конца. «домой» → «дом», «даче» → «дач». */
    private fun stem(s: String): String {
        var out = s
        repeat(2) { if (out.length > 3 && out.last() in "аеиоуыэюяйь") out = out.dropLast(1) }
        return out
    }

    /**
     * Блок МЕСТА для общего промпта разбора ({PLACES}): места автопилота
     * Засечки, по которым телефон узнаёт приезд. Мест нет — модель так и
     * узнаёт: напоминание по месту ставить некуда.
     */
    fun placesBlock(places: Collection<String>): String {
        val list = places.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { Dela.norm(it) }
        return if (list.isEmpty()) {
            "МЕСТА: телефон пока не знает ни одного места — remind_place оставляй пустым, место словами пиши в notes."
        } else {
            "МЕСТА (remind_place — ровно одно имя из списка; телефон узнаёт приезд по Wi-Fi):\n" + list.joinToString(", ")
        }
    }

    // ------------------------------------------------------------ слова

    /** Подпись в строке дела: «⏰ 11:00», «⏰ завтра 09:00», «⏰ чт 09:00», «⏰ 12.10 09:00», «⏰ дом». Отправлено — пусто. */
    fun chip(t: Dela.Task, today: LocalDate, zone: ZoneId): String {
        if (t.remindedAt.isNotBlank() || !t.open) return ""
        if (t.remindAt.isBlank()) return if (t.remindPlace.isNotBlank()) "⏰ " + t.remindPlace else ""
        val at = local(t.remindAt, zone) ?: return ""
        return "⏰ " + whenWords(at, today)
    }

    /** «11:00», «завтра 09:00», «чт 09:00» (до недели вперёд), иначе «12.10 09:00». */
    fun whenWords(at: LocalDateTime, today: LocalDate): String {
        val d = at.toLocalDate()
        val hm = HM.format(at)
        val days = ChronoUnit.DAYS.between(today, d)
        return when {
            days == 0L -> hm
            days == 1L -> "завтра $hm"
            days in 2..6 -> WD[d.dayOfWeek.value - 1] + " " + hm
            else -> DM.format(d) + " " + hm
        }
    }

    /** Состояние напоминания словами — для карточки. Нет напоминания — пусто. */
    fun state(t: Dela.Task, today: LocalDate, zone: ZoneId): String {
        val sent = local(t.remindedAt, zone)
        if (sent != null) return "отправлено в Telegram " + whenWords(sent, today)
        if (t.remindAt.isNotBlank()) {
            val at = local(t.remindAt, zone) ?: return "напоминание: ${t.remindAt}"
            return (if (t.remindPlace.isNotBlank()) "приехал «${t.remindPlace}» — " else "") +
                "напомню в Telegram " + whenWords(at, today)
        }
        if (t.remindPlace.isNotBlank()) return "напомню в Telegram, когда приедешь: ${t.remindPlace}"
        return ""
    }

    /**
     * Сервер напоминаний ещё не знает (нет `features: remind`) — напоминание
     * не теряется молча, а встаёт строкой в заметки дела: старый сервер
     * отверг бы `task.create` с незнакомым полем целиком, вместе с делом.
     */
    fun notesLine(remindAt: String, remindPlace: String, today: LocalDate, zone: ZoneId): String {
        val at = local(remindAt, zone)
        return when {
            at != null -> "⏰ Напомнить: " + whenWords(at, today) + " (сервер Дел пока не напоминает)"
            remindPlace.isNotBlank() -> "⏰ Напомнить, когда приеду: $remindPlace (сервер Дел пока не напоминает)"
            else -> ""
        }
    }

    // ------------------------------------------------------------ приезд

    /**
     * Настоящий ли это приезд, а не мигнувший роутер. Та же мера, что у
     * автопилота Засечки (`AutoPilotRules.arrival`): был зафиксирован отъезд,
     * и место сменилось или сети не было дольше получаса. Без отъезда (служба
     * только поднялась, сеть дома видна с утра) — не приезд: напоминание
     * «когда приеду домой», сказанное дома, должно дождаться возвращения.
     */
    fun realArrival(place: String, nowMs: Long, leftPlace: String, leftAtMs: Long): Boolean {
        if (leftAtMs <= 0L || leftAtMs > nowMs) return false
        return Dela.norm(leftPlace) != Dela.norm(place) || nowMs - leftAtMs >= AutoPilotRules.BLINK_MS
    }

    /**
     * Приехал в [place]: мои открытые дела с этим местом и без напоминания по
     * времени получают `remind_at` = миг приезда — дальше сервер отправит их,
     * как любое напоминание. Отправленное (`reminded_at`) и уже взведённое не
     * трогаем: напоминание по месту срабатывает один раз.
     */
    fun arrivalOps(s: Dela.Snapshot, me: String, place: String, atIso: String): List<JSONObject> {
        if (!s.remindOn || place.isBlank()) return emptyList()
        val n = Dela.norm(place)
        return s.tasks.values
            .filter { it.open && (me.isBlank() || it.ownerId == me) }
            .filter { it.remindPlace.isNotBlank() && it.remindAt.isBlank() && it.remindedAt.isBlank() }
            .filter { Dela.norm(matchPlace(it.remindPlace, listOf(place))) == n }
            .sortedBy { it.num }
            .map { t ->
                Dela.setOp(
                    t.id,
                    JSONObject().put("remind_at", atIso),
                    JSONObject().put("remind_at", JSONObject.NULL),
                    opId = Dela.stableId("remind-arrive-${t.id}-$atIso"),
                )
            }
    }
}
