package ru.zf.pravka.core

import ru.zf.pravka.data.PhoneStore

/**
 * Телефон за день одной строкой: сколько на YouTube, Telegram, Claude, сколько
 * звонков и минут разговоров, сколько экрана.
 *
 * Это то, что пришло на смену параллельному треку. Владелец: «эксперимент
 * оказался неудачным, засоряет ленту. просто давай считать каждый день,
 * сколько на Клод, телеграм, звонки, сколько на ютуб». Лента больше не знает
 * о телефоне ничего, кроме сна; телефонный слой считает свои суммы по дням,
 * и здесь они складываются в сводку для вкладки и для «Дней» в Notion.
 *
 * Чистый Kotlin: проверяется JVM-тестом.
 */
object PhoneDaySummary {

    /** Написания одного и того же приложения — чтобы «Ютуб» и YouTube были одним. */
    private val ALIASES = mapOf(
        "ютьюб" to "youtube", "ю-туб" to "youtube", "ютуб" to "youtube",
        "телеграм" to "telegram", "телега" to "telegram",
        "клауд" to "claude", "клод" to "claude",
    )

    data class AppMinutes(val pkg: String, val label: String, val category: String, val minutes: Long)

    data class Summary(
        val screenMin: Long,
        val pickups: Int,
        val glances: Int,
        /** Только отмеченные владельцем приложения, по убыванию минут. */
        val apps: List<AppMinutes>,
        val callsMin: Long,
        val calls: Int,
        /** Собеседники по убыванию минут, до пяти. */
        val callers: List<String>,
    ) {
        val empty: Boolean get() = screenMin == 0L && apps.isEmpty() && calls == 0
    }

    /** Нормальная форма названия: строчными, кириллица приведена к латинице. */
    fun normal(s: String): String {
        var t = s.trim().lowercase()
        for ((ru, en) in ALIASES) t = t.replace(ru, en)
        return t
    }

    /** Приложение узнаётся и по пакету, и по имени: «com.google.android.youtube» и «YouTube». */
    fun isApp(pkg: String, label: String, key: String): Boolean {
        val k = normal(key)
        return normal(pkg).contains(k) || normal(label).contains(k)
    }

    /** Минуты одного приложения за день по ключу («youtube», «telegram», «claude»). */
    fun minutesOf(day: PhoneStore.Day?, labels: Map<String, String>, key: String): Long {
        if (day == null) return 0L
        val ms = day.apps.entries
            .filter { (pkg, _) -> isApp(pkg, labels[pkg].orEmpty(), key) }
            .sumOf { it.value }
        return (ms + 30_000L) / 60_000L
    }

    /**
     * Сводка за день. [tracked] — приложения, которые владелец отметил
     * считать (пакет → категория), с выключенными не в счёт.
     */
    fun of(day: PhoneStore.Day?, tracked: Map<String, String>, labels: Map<String, String>): Summary {
        if (day == null) return Summary(0, 0, 0, emptyList(), 0, 0, emptyList())
        val apps = tracked.keys
            .map { pkg ->
                // Один Телеграм ставится двумя пакетами (обычный и web):
                // складываем по нормальному имени, чтобы строка была одна.
                pkg
            }
            .groupBy { pkg -> normal(labels[pkg] ?: pkg.substringAfterLast('.')) }
            .map { (_, pkgs) ->
                val ms = pkgs.sumOf { day.apps[it] ?: 0L }
                val head = pkgs.first()
                AppMinutes(
                    pkg = head,
                    label = labels[head] ?: head.substringAfterLast('.'),
                    category = tracked[head].orEmpty(),
                    minutes = (ms + 30_000L) / 60_000L,
                )
            }
            .filter { it.minutes > 0 }
            .sortedByDescending { it.minutes }
        val callers = day.callers.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .filter { it.isNotBlank() }
            .take(5)
        return Summary(
            screenMin = (day.screenMs + 30_000L) / 60_000L,
            pickups = day.pickups,
            glances = day.glances,
            apps = apps,
            callsMin = (day.callsMs + 30_000L) / 60_000L,
            calls = day.calls,
            callers = callers,
        )
    }

    /** «1 ч 10 м», «47 м». */
    fun dur(min: Long): String {
        val h = min / 60
        val m = min % 60
        return when {
            h > 0 && m > 0 -> "$h ч $m м"
            h > 0 -> "$h ч"
            else -> "$m м"
        }
    }

    /**
     * Строка для вкладки: «YouTube 47 м · Telegram 32 м · Claude 1 ч 10 м ·
     * звонки 3 · 18 м (Мама, Петя) · экран 3 ч 12 м». Пусто — данных нет.
     */
    fun line(s: Summary): String {
        if (s.empty) return ""
        val parts = ArrayList<String>()
        for (a in s.apps) parts.add("${a.label} ${dur(a.minutes)}")
        if (s.calls > 0) {
            parts.add(
                "звонки ${s.calls} · ${dur(s.callsMin)}" +
                    (if (s.callers.isNotEmpty()) " (${s.callers.take(3).joinToString(", ")})" else ""),
            )
        }
        if (s.screenMin > 0) parts.add("экран ${dur(s.screenMin)}")
        return parts.joinToString(" · ")
    }

    /** Телефон за день в форме для строки «Дней» Notion. */
    fun forNotion(day: PhoneStore.Day?, labels: Map<String, String>): NotionLifeSchema.PhoneDay {
        if (day == null) return NotionLifeSchema.PhoneDay()
        val callers = day.callers.entries.sortedByDescending { it.value }.map { it.key }.filter { it.isNotBlank() }
        return NotionLifeSchema.PhoneDay(
            screenMin = (day.screenMs + 30_000L) / 60_000L,
            pickups = day.pickups,
            glances = day.glances,
            youtubeMin = minutesOf(day, labels, "youtube"),
            telegramMin = minutesOf(day, labels, "telegram"),
            claudeMin = minutesOf(day, labels, "claude"),
            callsMin = (day.callsMs + 30_000L) / 60_000L,
            calls = day.calls,
            callers = callers.take(8).joinToString(", "),
        )
    }
}
