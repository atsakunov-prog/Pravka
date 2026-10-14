package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Долгое сидение в телефоне режет дело (13.10.2026, docs/zasechka.md
 * «Телефон в ленте»).
 *
 * Владелец: «если написано, что я делаю завтрак, а я на самом деле сижу уже
 * минут семь в Клоде, то это систематизация, а не приготовление. А если сижу
 * с Борей, делаем математику, вспомнил, что по работе надо кому-то написать,
 * быстро написал — то это не работа».
 *
 * Дважды телефон в ленте уже был и ушёл (`docs/agreements.md`, «Параллельного
 * трека нет»): в августе приложения резали дело врезками — «засоряет ленту, и
 * не всегда это потеря»; потом лежали вторым треком — «засоряет». Чем этот раз
 * другой — тем же, чем звонки 06.10:
 *  - категорию решает не приложение, а то, ЧТО в нём: Сонет читает название
 *    приложения и видимый текст (переписка с клиентом — работа, с Марианной —
 *    семья, Правка в Клоде — систематизация);
 *  - короткое не режет: порог владельца — семь минут в телефоне почти подряд;
 *    ответил и убрал — дело идёт дальше;
 *  - то же дело не режет: Сонет видит, что идёт в ленте, и переписка с
 *    клиентом посреди работы по нему остаётся работой;
 *  - после сидения дело продолжается само, «Убрать» в пуше сшивает обратно.
 *
 * Здесь — то, что не знает ни про Android, ни про Claude: сидения из событий
 * экрана, что охраняет ленту, строки для промпта, разбор ответа, чистка текста.
 */
object PhoneSit {

    /** Порог владельца: «не пять, а там семь минут». */
    const val MIN_DEFAULT = 7
    val MIN_CHOICES = listOf(5, 7, 10, 15)

    /** Пауза внутри одного сидения: перевернул яичницу, глянул в окно — то же сидение. */
    const val GAP_MS = 90_000L

    /** Столько без телефона — сидение кончилось, можно разбирать. */
    const val QUIET_MS = 3 * 60_000L

    /** Длинное сидение разбирается кусками: через два часа это уже не «только что». */
    const val CHUNK_MS = 120 * 60_000L

    /** Дальше назад не смотрим: отметка отстала сильнее — служба спала, а гадать о давнем незачем. */
    const val LOOKBACK_MS = 6 * 3600_000L

    /** Подсмотр раз в минуту, пока экран включён и открыт. */
    const val PEEK_EVERY_MS = 60_000L

    /** Метка комментария у записи сидения — по ней видно, откуда запись и из чего. */
    const val COMMENT_PREFIX = "Телефон: "

    const val TEXT_CAP = 900
    private const val PIECE_CAP = 160
    private const val SNAPS_IN_PROMPT = 12

    /** Модель называет время до минуты — край сидения ловится с запасом. */
    private const val SNAP_MS = 2 * 60_000L

    /** Эти категории сидение не пишет: сон — дело ночи, «Не размечено» — заполнитель ленты. */
    private val FORBIDDEN = setOf("сон", "не размечено")

    // ------------------------------------------------------------ сидения

    /** Событие экрана из статистики использования — без Android. */
    sealed interface Ev { val at: Long }

    /** Приложение вышло на экран (ACTIVITY_RESUMED). */
    data class Front(override val at: Long, val pkg: String) : Ev

    /** Приложение ушло с экрана (ACTIVITY_PAUSED). */
    data class Back(override val at: Long, val pkg: String) : Ev

    /** Экран погас (SCREEN_NON_INTERACTIVE). */
    data class Dark(override val at: Long) : Ev

    /**
     * Экран зажёгся (SCREEN_INTERACTIVE, 14.10.2026): с ним видно, сколько экран
     * был тёмным — полминуты темноты кончают дело из телефона (`PhoneLive.OFF_MS`).
     */
    data class Lit(override val at: Long) : Ev

    data class Span(val pkg: String, val start: Long, val end: Long) {
        val ms: Long get() = end - start
    }

    data class Stretch(val start: Long, val end: Long, val spans: List<Span>, val finished: Boolean) {
        val ms: Long get() = end - start

        /** Сколько из этого времени телефон был в руках на самом деле (без пауз). */
        val useMs: Long get() = spans.sumOf { it.ms }

        /** Тот же кусок, обрезанный по [from, to): края — по оставшимся приложениям. */
        fun clip(from: Long, to: Long): Stretch {
            val cut = spans.mapNotNull { s ->
                val a = maxOf(s.start, from)
                val b = minOf(s.end, to)
                if (b > a) s.copy(start = a, end = b) else null
            }
            if (cut.isEmpty()) return Stretch(from, from, emptyList(), finished)
            return Stretch(cut.minOf { it.start }, cut.maxOf { it.end }, cut, finished)
        }
    }

    /**
     * Не телефон, а мебель: рабочий стол, шторка, заставка на подставке,
     * звонилка (разговор — своя врезка, `CallRules`). То же, что не считает
     * `PhoneSweeper`, — кроме самой Правки: десять минут в ленте и деньгах —
     * это и есть систематизация.
     */
    fun furniture(pkg: String, home: Set<String> = emptySet()): Boolean {
        if (pkg in home || pkg == "android" || pkg == "com.android.systemui") return true
        val p = pkg.lowercase()
        return listOf("launcher", "hubui", "dream", "dialer", "incallui", "telecom").any { p.contains(it) }
    }

    /**
     * Текст этих приложений не читается никогда — только название: банки,
     * пароли, Госуслуги (паспорт и СНИЛС Сонету не нужны: «Госуслуги» и так —
     * быт), своя Правка (лента о ленте ничего не скажет).
     */
    fun privateText(pkg: String): Boolean {
        val p = pkg.lowercase()
        return listOf(
            "tinkoff", "tbank", "alfabank", "ru.mkb", "sber", "vtb", "raiffeisen", "gazprombank", "bank",
            "gosuslugi", "password", "bitwarden", "keepass", "lastpass", "authenticator", "ru.zf.pravka",
        ).any { p.contains(it) }
    }

    /**
     * Куски переднего плана из событий экрана. Новое приложение закрывает
     * прежнее, погасший экран — любое; что идёт сейчас — до [now]. Мебель
     * выпадает и остаётся паузой: «Телеграм — рабочий стол — Клод» — одно
     * сидение, если рабочий стол был минутку.
     */
    fun spans(events: List<Ev>, now: Long, furniture: (String) -> Boolean): List<Span> {
        val out = ArrayList<Span>()
        var cur: String? = null
        var since = 0L
        fun close(at: Long) {
            val p = cur ?: return
            if (at > since && !furniture(p)) out += Span(p, since, at)
            cur = null
        }
        for (e in events.sortedBy { it.at }) {
            when (e) {
                is Front -> if (e.pkg != cur) {
                    close(e.at)
                    cur = e.pkg
                    since = e.at
                }
                is Back -> if (e.pkg == cur) close(e.at)
                is Dark -> close(e.at)
                // Зажёгся экран — куска ещё нет: он начнётся с приложения впереди.
                is Lit -> {}
            }
        }
        close(now)
        return out
    }

    /**
     * Сидения: куски с паузами не длиннее [GAP_MS] — одно сидение. Кончилось
     * — если [QUIET_MS] телефона не было. Длиннее двух часов — кусками по два
     * часа: первый разбирается, не дожидаясь конца.
     */
    fun stretches(spans: List<Span>, now: Long): List<Stretch> {
        val groups = ArrayList<MutableList<Span>>()
        var end = Long.MIN_VALUE
        for (s in spans.filter { it.end > it.start }.sortedBy { it.start }) {
            if (groups.isEmpty() || s.start - end > GAP_MS) {
                groups += mutableListOf(s)
                end = s.end
            } else {
                groups.last() += s
                end = maxOf(end, s.end)
            }
        }
        val out = ArrayList<Stretch>()
        for (g in groups) {
            val gEnd = g.maxOf { it.end }
            var rest = Stretch(g.first().start, gEnd, g, now - gEnd >= QUIET_MS)
            while (rest.ms > CHUNK_MS) {
                out += rest.clip(rest.start, rest.start + CHUNK_MS).copy(finished = true)
                rest = rest.clip(rest.start + CHUNK_MS, rest.end)
            }
            if (rest.spans.isNotEmpty()) out += rest
        }
        return out
    }

    // ------------------------------------------------------------ что охраняет ленту

    /** Запись ленты, которую сидение задевает. [end] = 0 — идёт. */
    data class Seen(
        val start: Long,
        val end: Long,
        val title: String,
        val category: String,
        val source: String,
        val raw: String = "",
        val createdAt: Long = 0L,
    )

    /** Свободные от охраны куски сидения (не короче порога) и почему остальное не тронуто. */
    data class Guard(val pieces: List<Stretch>, val why: String)

    /**
     * Где сидение ленту не режет — время и так занято тем, чем заняты руки или
     * голова владельца: сон, встреча из календаря, дорога, тренировка, факт
     * робота (звонок, сон, прошлое сидение). И главное — человек сильнее
     * робота: сказал что-то в «З», пока сидел (или сразу после), — его словам
     * про это время и верим. Остальное время сидения — к Сонету, если хватает
     * до порога.
     */
    fun guard(s: Stretch, seen: List<Seen>, minMs: Long, now: Long): Guard {
        var pieces = listOf(s)
        val whys = ArrayList<String>()
        for (e in seen.sortedBy { it.start }) {
            val eEnd = if (e.end == 0L) now else e.end
            if (eEnd <= s.start || e.start >= s.end) continue
            val why = protects(e, s) ?: continue
            whys += why
            pieces = pieces.flatMap { minus(it, e.start, eEnd) }
        }
        return Guard(pieces.filter { it.useMs >= minMs }, whys.distinct().joinToString("; "))
    }

    private fun protects(e: Seen, s: Stretch): String? {
        if (e.source == "gap") return null
        if (DayReport.isSleep(e.category)) return "идёт сон"
        if (e.source == "auto") return "время занято «${e.title}»"
        if (e.source == CalendarRules.SOURCE) return "встреча из календаря «${e.title}»"
        if (AutoPilotRules.travelish(e.title, e.category)) return "дорога «${e.title}»"
        if (AutoPilotRules.sporty(e.category)) return "тренировка «${e.title}»"
        if (e.raw.isNotBlank() && e.createdAt in s.start..(s.end + QUIET_MS)) {
            return "ты сам сказал «${e.title}» в ${hm(e.createdAt)}"
        }
        return null
    }

    private fun minus(p: Stretch, bs: Long, be: Long): List<Stretch> {
        if (be <= p.start || bs >= p.end) return listOf(p)
        return listOfNotNull(
            if (bs > p.start) p.clip(p.start, bs) else null,
            if (be < p.end) p.clip(be, p.end) else null,
        ).filter { it.spans.isNotEmpty() }
    }

    // ------------------------------------------------------------ строки для промпта

    /** Снимок экрана словами: когда, какое приложение, что было видно. */
    data class Snap(val at: Long, val pkg: String, val text: String)

    /** Сидение по минутам: соседние куски одного приложения — одной строкой. */
    fun timeline(s: Stretch, label: (String) -> String): String {
        val merged = ArrayList<Span>()
        for (sp in s.spans.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && last.pkg == sp.pkg && sp.start - last.end <= GAP_MS) {
                merged[merged.size - 1] = last.copy(end = maxOf(last.end, sp.end))
            } else {
                merged += sp
            }
        }
        return merged.joinToString("\n") { "${hm(it.start)}–${hm(it.end)} ${label(it.pkg)} (${minutes(it.ms)})" }
    }

    /** «Claude 12 мин · Telegram 3 мин» — комментарий записи и строка пуша. */
    fun appsLine(s: Stretch, label: (String) -> String): String =
        s.spans.groupBy { it.pkg }
            .map { (pkg, list) -> label(pkg) to list.sumOf { it.ms } }
            .filter { it.second >= 30_000L }
            .sortedByDescending { it.second }
            .take(3)
            .joinToString(" · ") { (name, ms) -> "$name ${(ms + 30_000L) / 60_000L} мин" }

    /**
     * Снимки внутри сидения для промпта: подряд одинаковые — один раз, больше
     * двенадцати — ровно по сидению, чтобы и начало, и конец были видны.
     */
    fun snapLines(s: Stretch, snaps: List<Snap>, label: (String) -> String): List<String> {
        val distinct = ArrayList<Snap>()
        for (x in snaps.filter { it.at in s.start..s.end && it.text.isNotBlank() }.sortedBy { it.at }) {
            val last = distinct.lastOrNull()
            if (last != null && last.pkg == x.pkg && last.text == x.text) continue
            distinct += x
        }
        val pick = if (distinct.size <= SNAPS_IN_PROMPT) distinct
        else (0 until SNAPS_IN_PROMPT).map { i -> distinct[i * (distinct.size - 1) / (SNAPS_IN_PROMPT - 1)] }.distinct()
        return pick.map { "${hm(it.at)} · ${label(it.pkg)} · ${it.text}" }
    }

    /**
     * Текст экрана — в одну строку для Сонета: без пустого, без времени и
     * счётчиков, без повторов, длинные куски обрезаны, номера карт, телефонов
     * и коды — многоточием (их смысл Сонету не нужен, а наружу они уходят).
     */
    fun clean(pieces: List<String>, cap: Int = TEXT_CAP): String {
        val seen = HashSet<String>()
        val out = StringBuilder()
        for (raw in pieces) {
            var t = raw.replace(Regex("\\s+"), " ").trim()
            if (t.length < 2) continue
            if (Regex("^[\\d\\s:.,%+\\-−/]+$").matches(t)) continue
            t = Regex("\\d[\\d \\-]{4,}\\d").replace(t, "…")
            if (t.length > PIECE_CAP) t = t.take(PIECE_CAP - 1) + "…"
            if (!seen.add(t.lowercase())) continue
            val add = if (out.isEmpty()) t else " · $t"
            if (out.length + add.length > cap) break
            out.append(add)
        }
        return out.toString()
    }

    /**
     * Кого из своих людей видно в тексте экрана: [people] — имя и что о нём
     * сказать («Женя Соколов» → «работа, клиент Ромашка»). Имя — целиком или с
     * другим окончанием, как у звонков (`CallRules.sameName`). Пять первых:
     * в списке чатов Телеграма видно десяток имён, подсказка не должна стать
     * списком.
     */
    fun whoInText(text: String, people: List<Pair<String, String>>): List<String> {
        if (text.isBlank()) return emptyList()
        return people.filter { (name, _) -> name.isNotBlank() && CallRules.sameName(name, text) }
            .map { (name, note) -> if (note.isBlank()) name else "$name — $note" }
            .distinct()
            .take(5)
    }

    // ------------------------------------------------------------ запрос: постоянное под кэш, переменное после

    /** Дело ленты для списков «как владелец называл свои дела». */
    data class Named(val title: String, val category: String, val start: Long)

    private const val DAY_MS = 86_400_000L

    private fun nameKey(n: Named): String = "${n.title.trim().lowercase()}|${n.category.trim().lowercase()}"

    private fun nameLine(n: Named): String = "- «${n.title.trim()}» [${n.category.trim()}]"

    /**
     * Названия владельца за [days] дней ДО сегодняшнего — в постоянную часть
     * запроса (14.10.2026, деньги): весь день она байт в байт одна, и Сонет
     * читает её из кэша. Порядок — по частоте, потом по алфавиту: прежний
     * «свежие сверху» с сегодняшними менялся с каждой новой записью ленты и
     * ломал кэш на каждом вызове. Сегодняшние новые — в переменной части
     * ([todayNames]).
     */
    fun weekNames(entries: List<Named>, dayStart: Long, days: Int = 7, cap: Int = 30): List<String> =
        entries.filter { it.title.isNotBlank() && it.start >= dayStart - days * DAY_MS && it.start < dayStart }
            .groupBy(::nameKey)
            .values
            .map { group -> group.maxBy { it.start } to group.size }
            .sortedWith(compareByDescending<Pair<Named, Int>> { it.second }.thenBy { nameKey(it.first) })
            .take(cap)
            .map { (n, times) -> nameLine(n) + (if (times > 1) " ×$times" else "") }

    /** Сегодняшние названия, которых не было за неделю, — в переменную часть, по порядку дня. */
    fun todayNames(entries: List<Named>, dayStart: Long, days: Int = 7, cap: Int = 15): List<String> {
        val week = entries.filter { it.start >= dayStart - days * DAY_MS && it.start < dayStart }.map(::nameKey).toSet()
        return entries.filter { it.title.isNotBlank() && it.start >= dayStart && nameKey(it) !in week }
            .sortedBy { it.start }
            .distinctBy(::nameKey)
            .take(cap)
            .map(::nameLine)
    }

    /**
     * Постоянная часть запроса после правил: категории с подсказками, клиенты,
     * названия за неделю. Меняется, только когда владелец правит категории или
     * клиентов, и раз в сутки — названиями.
     */
    fun stableBlock(categories: List<Pair<String, String>>, clients: List<String>, week: List<String>): String =
        buildString {
            append("Категории (после тире — что сюда относится):\n")
            append(categories.joinToString("\n") { (name, hint) -> "- «$name»" + (if (hint.isBlank()) "" else " — $hint") })
            append("\n\nКлиенты и проекты владельца:\n")
            append(if (clients.isEmpty()) "(список пуст)" else clients.joinToString("\n") { "- $it" })
            if (week.isNotEmpty()) append("\n\nКак владелец называл свои дела в последние дни:\n").append(week.joinToString("\n"))
        }

    private const val NOW_MARK = "{NOW}"

    /**
     * Запрос Сонету (14.10.2026, деньги): правила промпта (всё до {NOW}) и
     * постоянный блок [stable] — одной головой под кэш на час (`cache_control`
     * ставит `ClaudeProvider` по `cacheStableAlways`), переменное [context] —
     * сидение, лента, снимки экрана, время — после неё. Час, а не пять минут:
     * Сонета зовут, только когда на экране поменялось, и между вызовами
     * обычно больше пяти минут. Владелец потерял {NOW} в «Промптах» — весь
     * текст правил идёт головой, переменное дописывается после, а не теряется.
     */
    fun phoneParts(template: String, stable: String, context: String): Prompts.PromptParts {
        val cut = template.indexOf(NOW_MARK)
        val head = if (cut >= 0) template.substring(0, cut) else template.trimEnd() + "\n\n"
        val after = if (cut >= 0) template.substring(cut + NOW_MARK.length) else ""
        val prefix = if (stable.isBlank()) head else head + stable.trim() + "\n\n"
        return Prompts.PromptParts(
            stablePrefix = prefix,
            dictPart = context + after,
            afterInput = "",
            cacheStableAlways = prefix.isNotBlank(),
        )
    }

    // ------------------------------------------------------------ ответ Сонета

    data class Part(val start: Long, val end: Long, val title: String, val category: String, val client: String)

    /** [parts] — что режет ленту (пусто — «так и есть»), [dropped] — что из ответа не взято и почему. */
    data class Reply(val parts: List<Part>, val why: String, val dropped: List<String>)

    /**
     * Ответ Сонета: `{"parts": [{"from","to","category","title","client"}], "why"}`.
     * Категория — только из списка владельца (кроме сна и заполнителя),
     * время — внутри сидения (край в пределах двух минут прилипает к краю
     * сидения), куски не налезают друг на друга, короче порога — выпадают:
     * «быстро ответил» не режет и внутри длинного сидения. null — ответ не
     * прочитался.
     */
    fun parse(text: String, s: Stretch, categories: List<String>, minMs: Long): Reply? {
        var body = text.trim()
        if (body.startsWith("```")) body = body.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = body.indexOf('{')
        val b = body.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val o = runCatching { org.json.JSONObject(body.substring(a, b + 1)) }.getOrNull() ?: return null
        val why = o.optString("why").trim()
        val arr = o.optJSONArray("parts") ?: org.json.JSONArray()
        val cats = categories.associateBy { norm(it) }
        val dropped = ArrayList<String>()
        val raw = ArrayList<Part>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val asked = p.optString("category").trim().trim('«', '»', '"').trim()
            val cat = cats[norm(asked)]
            if (cat == null || norm(cat) in FORBIDDEN) {
                dropped += "категория «$asked» не из списка"
                continue
            }
            val from = clock(p.optString("from"), s) ?: s.start
            val to = clock(p.optString("to"), s) ?: s.end
            val title = p.optString("title").trim().trim('«', '»').trim().ifBlank { cat }
            if (to > from) raw += Part(from, to, title, cat, p.optString("client").trim())
        }
        val fixed = ArrayList<Part>()
        for (p in raw.sortedBy { it.start }) {
            val prev = fixed.lastOrNull()
            var q = p
            if (prev != null && q.start - prev.end <= SNAP_MS) q = q.copy(start = prev.end)
            if (q.end > q.start) fixed += q
        }
        val kept = ArrayList<Part>()
        for (p in fixed) {
            // Модель считает до минуты: «7 минут» на пороге — это порог.
            if (p.end - p.start >= minMs - 60_000L) kept += p
            else dropped += "«${p.title}» ${minutes(p.end - p.start)} — короче порога"
        }
        return Reply(kept, why, dropped)
    }

    /** «08:10» внутри сидения [s]: ближайшие сутки, край прилипает, за край — обрезка. */
    private fun clock(v: String, s: Stretch): Long? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(v.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        val c = Calendar.getInstance().apply {
            timeInMillis = s.start
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var at = c.timeInMillis
        if (at < s.start - 12 * 3600_000L) at += 86_400_000L
        if (at > s.end + 12 * 3600_000L) at -= 86_400_000L
        if (kotlin.math.abs(at - s.start) <= SNAP_MS) at = s.start
        if (kotlin.math.abs(at - s.end) <= SNAP_MS) at = s.end
        return at.coerceIn(s.start, s.end)
    }

    private fun norm(s: String): String = s.trim().lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ")

    fun minutes(ms: Long): String = if (ms < 30_000L) "меньше минуты" else "${(ms + 30_000L) / 60_000L} мин"

    fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
}
