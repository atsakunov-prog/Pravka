package ru.zf.pravka.core

/**
 * Защита от флуда (13.10.2026). Владелец: «если он сломался и начал слать кучу
 * запросов, то приложение дёргает рубильник и говорит, что защита от флуда
 * сработала. И там тоже галка или крестик. Крестик — значит всё ок».
 *
 * Деньги утекают не от одного дорогого запроса, а от петли: автомат, который
 * шлёт запрос каждую минуту вместо раза в сидение, — это Опус на xhigh сотни
 * раз за час. Поэтому меряется ЧАСТОТА — по дороге (`ModelRoute.key`) за
 * десять минут и всего за десять минут и за час.
 *
 * Пороги — от настоящей жизни, а не на глаз (архив, 60 дней до 13.10.2026):
 * самые плотные десять минут — 17 диктовок «П» (и 17 событий всего, у
 * Засечки каждое — два запроса: развилка и разбор), самый плотный час — 27.
 * Дорога — 30 за десять минут (запас почти вдвое), всё вместе — 80 за десять
 * минут и 250 за час. Телефон в ленте ходит раз в сидение — ему 12. Сравнение
 * моделей и эвал гоняют пачки по кнопке — им 100. «Всё ок» на вопросе —
 * пороги втрое на час: своя пачка дойдёт, настоящая петля всё равно упрётся.
 *
 * Считает всё, что уходит POST на api.anthropic.com, в одной точке —
 * перехватчике общего http-клиента (`data/FloodBreaker.kt`): и дороги
 * `ClaudeProvider`, и батчи, и поиск словаря. Здесь — только счёт.
 */
object FloodGuard {

    const val WINDOW_MS = 10 * 60_000L
    const val HOUR_MS = 60 * 60_000L
    const val TOTAL_WINDOW = 80
    const val TOTAL_HOUR = 250
    const val RELAX = 3
    const val RELAX_MS = HOUR_MS

    /** Дорога запроса — тегом OkHttp: наружу не уходит, перехватчик читает. */
    data class Route(val key: String)

    /** Запрос остановлен рубильником: ошибка ввода-вывода, чтобы очереди «нет сети» дождались включения. */
    class Blocked(message: String) : java.io.IOException(message)

    data class Hit(val at: Long, val route: String)

    /**
     * Почему сработало: дорога (у [total] — та, что слала больше всех),
     * сколько за окно, само окно и кто слал больше всех.
     */
    data class Trip(val route: String, val count: Int, val windowMin: Int, val top: List<Pair<String, Int>>, val total: Boolean = false)

    /** Сколько одной дороге можно за десять минут. */
    fun routeLimit(route: String): Int = when (route) {
        "zasechka_phone" -> 12
        "compare", "eval" -> 100
        else -> 30
    }

    /**
     * Сработает ли рубильник, если [route] сейчас пошлёт ещё один запрос.
     * [hits] — запросы за последний час (до этого). [relaxed] — владелец
     * сказал «всё ок» меньше часа назад: пороги втрое.
     */
    fun check(hits: List<Hit>, route: String, now: Long, relaxed: Boolean): Trip? {
        val k = if (relaxed) RELAX else 1
        val window = hits.filter { now - it.at < WINDOW_MS } + Hit(now, route)
        val hour = hits.filter { now - it.at < HOUR_MS } + Hit(now, route)
        val top = window.groupingBy { it.route }.eachCount().toList().sortedByDescending { it.second }.take(3)
        val mine = window.count { it.route == route }
        return when {
            mine > routeLimit(route) * k -> Trip(route, mine, 10, top)
            window.size > TOTAL_WINDOW * k -> Trip(top.first().first, window.size, 10, top, total = true)
            hour.size > TOTAL_HOUR * k -> {
                val hourTop = hour.groupingBy { it.route }.eachCount().toList().sortedByDescending { it.second }.take(3)
                Trip(hourTop.first().first, hour.size, 60, hourTop, total = true)
            }
            else -> null
        }
    }

    /** Старше часа — уже не считается. */
    fun prune(hits: List<Hit>, now: Long): List<Hit> = hits.filter { now - it.at < HOUR_MS }

    /**
     * Словами для плашки и журнала: ««Телефон в ленте» — 13 запросов за 10
     * минут (ещё: «Чистка диктовки» 4)» или «81 запрос за 10 минут, больше
     * всего — «Чистка диктовки» 40».
     */
    fun words(t: Trip, name: (String) -> String): String {
        val n = "${t.count} ${requests(t.count)} за ${t.windowMin} минут"
        if (t.total) {
            val lead = t.top.firstOrNull()
            return n + (lead?.let { ", больше всего — «${name(it.first)}» ${it.second}" } ?: "")
        }
        val rest = t.top.filter { it.first != t.route }
        return "«${name(t.route)}» — $n" +
            if (rest.isEmpty()) "" else " (ещё: " + rest.joinToString(", ") { "«${name(it.first)}» ${it.second}" } + ")"
    }

    private fun requests(n: Int): String {
        val d = n % 100
        return when {
            d in 11..14 -> "запросов"
            n % 10 == 1 -> "запрос"
            n % 10 in 2..4 -> "запроса"
            else -> "запросов"
        }
    }
}
