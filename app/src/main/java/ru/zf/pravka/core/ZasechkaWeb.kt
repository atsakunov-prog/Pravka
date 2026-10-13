package ru.zf.pravka.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.data.ZasechkaStore

/**
 * Команды Засечки из веба (14.10.2026; веб-двойник экрана «Засечка», `docs/zasechka.md` «Команды из веба», контракт
 * `server/contract/dela-zasechka.json`). Владелец на компьютере нажимает «Начать», «Стоп» или «Поправить» — веб
 * день в архив не пишет (правда ленты — телефон, архив — её копия: следующая отправка телефона перезатёрла бы
 * правку веба), а ставит команду на сервере Дел. Телефон забирает её синком Дел и применяет тем же путём, что свои
 * кнопки: `ZasechkaStore.startEntry`, `closeOpen`, `update`. Здесь — только решение без Android: что сделать с
 * командой при нынешней ленте, под тестом `ZasechkaWebTest`; дорога и память — `data/ZasechkaWebCommands.kt`.
 *
 * Правило «человек против человека» (`docs/zasechka.md`, железные правила ленты): веб и телефон — оба владелец, и
 * свежее слово — телефона. Если после нажатия в вебе на телефоне уже начали другое или поправили ту же запись,
 * команда не применяется, а телефон отвечает словами, почему.
 */
object ZasechkaWeb {

    /** Старше — не применяется: «Начать» вчера вечером утром уже неправда (сервер гасит такие сам). */
    const val MAX_AGE_MS = 12 * 3_600_000L

    /** Та же секунда начала — та же запись: повтор после обрыва связи не заводит дело второй раз. */
    private const val SAME_MS = 1_500L

    data class Cmd(
        val id: String,
        val kind: String,
        val args: JSONObject,
        val saidAt: Long,
        val status: String,
        val takenBy: String,
    )

    /** Поле `zasechka` синка → команды по порядку нажатия; кривые пропускаются. */
    fun parse(a: JSONArray?): List<Cmd> {
        if (a == null) return emptyList()
        val out = ArrayList<Cmd>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val at = instant(o.optString("said_at")) ?: continue
            out += Cmd(
                id = id,
                kind = o.optString("kind"),
                args = o.optJSONObject("args") ?: JSONObject(),
                saidAt = at,
                status = o.optString("status"),
                takenBy = o.optString("taken_by").takeIf { o.has("taken_by") && !o.isNull("taken_by") }.orEmpty(),
            )
        }
        return out.sortedBy { it.saidAt }
    }

    /** Миг ISO с поясом («2026-10-14T10:00:00+03:00», и с долями секунды); не разобрался — null. */
    fun instant(s: String?): Long? = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    /** Что сделать с командой. */
    sealed class Plan {
        data class Start(
            val at: Long,
            val title: String,
            val category: String,
            val client: String,
            val task: String,
            val project: String,
        ) : Plan()

        data class Stop(val entryId: Long, val at: Long) : Plan()

        /** [entry] — запись целиком в новом виде (как её сохраняет «Поправить» телефона); [what] — что поменялось словами. */
        data class Edit(val entry: ZasechkaStore.Entry, val what: List<String>) : Plan()

        /** Уже сделано — повтор после обрыва связи: применять нечего, только ответить. */
        data class Already(val entryId: Long, val note: String) : Plan()

        data class Refuse(val why: String) : Plan()
    }

    /**
     * Решение по команде [c] при ленте [entries] на миг [now]. [categories] — справочник телефона: категорию из веба
     * пишем буква в букву, как в списке (веб берёт её из того же справочника через архив).
     */
    fun plan(c: Cmd, entries: List<ZasechkaStore.Entry>, categories: List<String>, now: Long, zone: ZoneId): Plan {
        if (now - c.saidAt > MAX_AGE_MS) return Plan.Refuse("устарела: нажата больше 12 часов назад")
        val at = minOf(c.saidAt, now)
        val a = c.args
        return when (c.kind) {
            "start" -> start(a, at, entries, categories, zone)
            "stop" -> stop(a, at, entries, zone)
            "edit" -> edit(a, entries, categories, now, zone)
            else -> Plan.Refuse("телефон не знает команду «${c.kind}» — обнови Правку")
        }
    }

    private fun start(a: JSONObject, at: Long, entries: List<ZasechkaStore.Entry>, categories: List<String>, zone: ZoneId): Plan {
        val title = a.optString("title").trim().take(120)
        if (title.isEmpty()) return Plan.Refuse("пустое название")
        entries.firstOrNull { kotlin.math.abs(it.start - at) <= SAME_MS && it.title.trim() == title }
            ?.let { return Plan.Already(it.id, "уже начато «$title» с ${hm(it.start, zone)}") }
        val open = entries.lastOrNull { it.open }
        if (open != null && open.start > at) {
            return Plan.Refuse("на телефоне уже начато «${open.title.ifBlank { open.category }}» в ${hm(open.start, zone)} — позже нажатия в вебе")
        }
        return Plan.Start(
            at = at,
            title = title,
            category = canonical(a.optString("category").trim(), categories),
            client = a.optString("client").trim(),
            task = a.optString("task_id").takeIf { it != "null" }.orEmpty(),
            project = a.optString("project_id").takeIf { it != "null" }.orEmpty(),
        )
    }

    private fun stop(a: JSONObject, at: Long, entries: List<ZasechkaStore.Entry>, zone: ZoneId): Plan {
        val id = a.optLong("entry_id")
        val e = entries.firstOrNull { it.id == id } ?: return Plan.Refuse("этой записи на телефоне нет — ленту уже поправили")
        if (!e.open) {
            return if (kotlin.math.abs(e.end - at) <= 60_000L) Plan.Already(e.id, "уже остановлено «${e.title}» в ${hm(e.end, zone)}")
            else Plan.Refuse("«${e.title.ifBlank { e.category }}» уже остановлено на телефоне в ${hm(e.end, zone)}")
        }
        if (at < e.start) return Plan.Refuse("нажато раньше, чем началось «${e.title}»")
        return Plan.Stop(e.id, at)
    }

    private fun edit(a: JSONObject, entries: List<ZasechkaStore.Entry>, categories: List<String>, now: Long, zone: ZoneId): Plan {
        val id = a.optLong("entry_id")
        val e = entries.firstOrNull { it.id == id } ?: return Plan.Refuse("этой записи на телефоне нет — ленту уже поправили")
        val day = runCatching { LocalDate.parse(a.optString("day")) }.getOrNull() ?: return Plan.Refuse("нет суток записи")
        val was = a.optJSONObject("was") ?: JSONObject()
        val set = a.optJSONObject("set") ?: JSONObject()
        val cat = if (set.has("category")) canonical(set.optString("category").trim(), categories) else e.category
        val title = if (set.has("title")) set.optString("title").trim().take(120) else e.title
        val start = if (set.has("start")) at(day, set.optString("start"), zone) ?: return Plan.Refuse("начало — не время") else e.start
        val end = when {
            !set.has("end") -> e.end
            e.open -> return Plan.Refuse("у идущего дела конца нет — его ставит «Стоп»")
            else -> at(day, set.optString("end"), zone, endOfDay = true) ?: return Plan.Refuse("конец — не время")
        }
        // Уже так — повтор после обрыва: ответить, не трогая.
        if (title == e.title && cat == e.category && start == e.start && end == e.end) {
            return Plan.Already(e.id, "уже поправлено «${e.title}»")
        }
        // Поправили на телефоне после того, как веб прочёл запись, — свежее слово телефона.
        val same = was.optString("title").trim() == e.title.trim() &&
            was.optString("category").trim().equals(e.category.trim(), ignoreCase = true) &&
            was.optString("start") == hm(e.start, zone) &&
            was.optString("end") == (if (e.open) "" else hm(e.end, zone))
        if (!same) return Plan.Refuse("запись уже поправили на телефоне — свежее слово его")
        if (start > now) return Plan.Refuse("начало позже, чем «сейчас»")
        if (!e.open && end <= start) return Plan.Refuse("конец раньше начала")
        val what = listOfNotNull(
            "название".takeIf { title != e.title },
            "категория".takeIf { cat != e.category },
            "начало".takeIf { start != e.start },
            "конец".takeIf { end != e.end },
        )
        return Plan.Edit(e.copy(title = title, category = cat, start = start, end = end, source = "edit"), what)
    }

    /** Ответ серверу словами — что сделано: «начато «Тизер» с 10:00», «остановлено «Сон» в 07:05». */
    fun note(p: Plan, zone: ZoneId): String = when (p) {
        is Plan.Start -> "начато «${p.title}» с ${hm(p.at, zone)}"
        is Plan.Stop -> "остановлено в ${hm(p.at, zone)}"
        is Plan.Edit -> "поправлено «${p.entry.title}»: " + p.what.joinToString(", ")
        is Plan.Already -> p.note
        is Plan.Refuse -> p.why
    }

    /** Категория буква в букву, как в справочнике; нет такой — как прислали (Засечка пишет и свободную). */
    fun canonical(named: String, known: List<String>): String = known.firstOrNull { it.equals(named, ignoreCase = true) } ?: named

    /** «ЧЧ:ММ» местного времени суток [day]; «24:00» и (для конца) «00:00» — полночь в конце суток. */
    fun at(day: LocalDate, hm: String, zone: ZoneId, endOfDay: Boolean = false): Long? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(hm.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (min > 59 || h > 24 || (h == 24 && min != 0)) return null
        if (h == 24 || (endOfDay && h == 0 && min == 0)) return day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return day.atTime(LocalTime.of(h, min)).atZone(zone).toInstant().toEpochMilli()
    }

    /** «ЧЧ:ММ» мига в поясе телефона — так их видит веб (start_local архива). */
    fun hm(ms: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }
}
