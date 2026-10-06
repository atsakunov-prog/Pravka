package ru.zf.pravka.core

import ru.zf.pravka.data.ZasechkaStore

/**
 * Дела в Засечке (05.10.2026; владелец: «должен быть список дел из моих дел, и
 * рядом с каждым — значок Play, когда я просто начинаю заниматься этим делом.
 * И это надо не просто как дело ставить, а понимать, что этим делом я
 * занимаюсь. И можно ещё сделать какой-то комментарий»).
 *
 * ▶ у дела заводит запись ленты с id дела (`ZasechkaEngine.startTask`) — так
 * было и раньше, во вкладке «Дела». Теперь связь видна в обе стороны: у дела —
 * «идёт 12 мин» и «в ленте 1 ч 20 мин» (сколько времени на него ушло, по всем
 * записям с его id), у записи — номер дела; комментарий к такой записи
 * дописывается и в само дело. Здесь — решения без Android, под тестом
 * `ZasechkaTasksTest`; плашка — `ZasechkaTab`, строки Дел — `DelaTab`.
 */
object ZasechkaTasks {

    /** Недавно начатое из дела — в списке «взяться» неделю. */
    const val RECENT_MS = 7 * 86_400_000L

    /** Сколько времени в ленте у каждого дела: сумма записей с его id. */
    fun spent(entries: List<ZasechkaStore.Entry>, now: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (e in entries) {
            if (e.task.isBlank()) continue
            out[e.task] = (out[e.task] ?: 0L) + e.durationMs(now)
        }
        return out
    }

    /** Дело, которым владелец занят сейчас: идущая запись начата из него. "" — никаким. */
    fun running(entries: List<ZasechkaStore.Entry>): String =
        entries.lastOrNull { it.open }?.task.orEmpty()

    /**
     * Короткий список «взяться за дело»: то, что идёт; отмеченное на сегодня
     * («Сейчас», до пяти — во вкладке и в вебе оно первым разделом); начатое
     * из ленты за неделю (свежее выше — к нему обычно и
     * возвращаются); моё на сегодня и просроченное. Только открытые дела
     * владельца, без повторов. Жду и «при встрече» — не то, за что берутся
     * руками, если не отмечены на сегодня.
     */
    fun shortlist(
        s: Dela.Snapshot,
        me: String,
        today: String,
        entries: List<ZasechkaStore.Entry>,
        now: Long,
    ): List<Dela.Task> {
        val mine = s.tasks.values.filter { it.open && (me.isBlank() || it.ownerId == me) }
        val byId = mine.associateBy { it.id }
        val out = LinkedHashMap<String, Dela.Task>()
        fun add(t: Dela.Task?) {
            if (t != null && t.id !in out) out[t.id] = t
        }
        add(byId[running(entries)])
        mine.filter { it.focusOn == today }.sortedWith(Dela.ORDER).forEach(::add)
        entries.asSequence()
            .filter { it.task.isNotBlank() && it.start >= now - RECENT_MS }
            .sortedByDescending { it.start }
            .forEach { add(byId[it.task]) }
        mine.filter { it.ball == Dela.MINE && it.dueDate.isNotBlank() && it.dueDate <= today }
            .sortedWith(Dela.ORDER)
            .forEach(::add)
        return out.values.toList()
    }

    /**
     * Тот же список по частям (06.10.2026, docs/dela-phone-3.md; владелец:
     * «сначала делаю их, потом всё остальное»): идущее — первым, если оно не
     * из «Сейчас»; дела «Сейчас» — под своим заголовком (идущее среди них —
     * первым); ниже — остальное в порядке [shortlist].
     */
    data class Parts(val running: Dela.Task?, val now: List<Dela.Task>, val rest: List<Dela.Task>) {
        val all: List<Dela.Task> get() = listOfNotNull(running) + now + rest
    }

    fun parts(
        s: Dela.Snapshot,
        me: String,
        today: String,
        entries: List<ZasechkaStore.Entry>,
        now: Long,
    ): Parts {
        val list = shortlist(s, me, today, entries, now)
        val runId = running(entries)
        val head = list.firstOrNull { it.id == runId && it.focusOn != today }
        return Parts(
            running = head,
            now = list.filter { it.focusOn == today },
            rest = list.filter { it.focusOn != today && it.id != head?.id },
        )
    }

    /**
     * Что из нового комментария уехать в дело: дописанное после прежнего
     * текста (мысли к делу ложатся строкой ниже) или весь текст, если раньше
     * было пусто. Правка старых строк и стирание в дело не едут: там они уже
     * лежат отдельными комментариями. null — везти нечего.
     */
    fun added(before: String, after: String): String? {
        val b = before.trim()
        val a = after.trim()
        if (a.isEmpty() || a == b) return null
        if (b.isEmpty()) return a
        if (!a.startsWith(b)) return null
        return a.removePrefix(b).trim().ifEmpty { null }
    }

    /** «25 мин», «1 ч 20 мин», «3 ч». */
    fun label(ms: Long): String {
        val min = (ms + 30_000L) / 60_000L
        return when {
            min < 60 -> "$min мин"
            min % 60 == 0L -> "${min / 60} ч"
            else -> "${min / 60} ч ${min % 60} мин"
        }
    }
}
