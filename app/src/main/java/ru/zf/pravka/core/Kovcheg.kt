package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Меню Ковчега на телефоне (Правка 5.0, план §3, решение владельца 13.10.2026):
 * значок Ковчега в шапке каждого экрана открывает все сервисы семьи — три
 * большие кнопки (Сегодня, Засечка, Правка) и восемь направлений Круга жизни
 * столбиками, «Творчество» с Claude строкой ниже. Раскладка — та же, что у
 * веба Дел (`SVC_DIRS` в `server/pravka_dela/static/app.js`): направление
 * сервиса узнаётся по его ЗНАЧКУ, названий и адресов в коде нет.
 *
 * Что есть в приложении — открывает свой экран (Спорт, Еда, Дела, Деньги,
 * CRM — раздел Дел). Чего нет (Встречи, Дети, Шаббат, Книги, Кино, Дом, VPN)
 * — веб-страница; адреса — из ответа сервера Дел `/api/me` → `look.nav`
 * («название|значок|адрес» из настроек сервера на компе — репозиторий
 * публичный). Сервис без адреса в меню не попадает. До Свода `ui.menu`
 * (этап 7) список направлений живёт здесь.
 */
object Kovcheg {

    /** Куда ведёт пункт. */
    sealed class Target {
        /** Экран приложения: [key] — «today», «zasechka», «pravka», «sport», «food», «dela», «money», «crm». */
        data class Screen(val key: String) : Target()

        /** Страница сервиса в браузере. */
        data class Web(val url: String) : Target()

        /** Приложение Claude (нет его — claude.ai). */
        data object Claude : Target()
    }

    /** Пункт меню: название, значок набора Ковчега (имя веба) и куда ведёт. */
    data class Item(val name: String, val icon: String, val target: Target, val sub: String = "")

    /** Направление Круга жизни: название, подпись из формулы и сервисы. */
    data class Direction(val title: String, val sub: String, val items: List<Item>)

    /** Сервис из настроек сервера (`look.nav`). */
    data class Nav(val name: String, val icon: String, val url: String)

    const val TODAY = "today"
    const val ZASECHKA = "zasechka"
    const val PRAVKA = "pravka"
    const val SPORT = "sport"
    const val FOOD = "food"
    const val DELA = "dela"
    const val MONEY = "money"
    const val CRM = "crm"

    /** Три большие кнопки сверху: «день целиком», «что делаю сейчас», «все диктовки». */
    val BIG: List<Item> = listOf(
        Item("Сегодня", "sun", Target.Screen(TODAY), "день целиком"),
        Item("Засечка", "clock", Target.Screen(ZASECHKA), "что делаю сейчас"),
        Item("Правка", "edit", Target.Screen(PRAVKA), "все диктовки"),
    )

    /**
     * Направления по порядку (план §3) — название, подпись и значки их
     * сервисов в порядке показа. Слово в слово `SVC_DIRS` веба.
     */
    val DIRECTIONS: List<Triple<String, String, List<String>>> = listOf(
        Triple("Здоровье", "сильное тело, ясная голова", listOf("activity", "food")),
        Triple("Ум", "много знаю и узнаю", listOf("book")),
        Triple("Дело", "растёт вглубь и вширь", listOf("dela", "mic")),
        Triple("Деньги", "деньги — не ограничение", listOf("wallet")),
        Triple("Круг", "знаю многих", listOf("briefcase")),
        Triple("Родительство", "сыновья растут сильными", listOf("kids", "candles")),
        Triple("Пара", "близкая живая пара", listOf("film")),
        Triple("Быт", "дома хорошо всем пятерым", listOf("home", "shield")),
    )

    /** «Творчество» — не в таблице, а строкой ниже: надпись с подписью и Claude рядом. */
    val CREATE = Direction("Творчество", "создаю новое", listOf(Item("Claude", "claude", Target.Claude)))

    /**
     * Свои экраны приложения в меню — их значки. Запись сервера с таким
     * значком (веб «Спорта» или «Еды» на компе) в меню телефона не идёт:
     * экран приложения и есть этот сервис.
     */
    private val OWN = setOf("activity", "food", "dela", "wallet", "briefcase", "claude")

    /**
     * Свои пункты приложения: режимы, включённые в профиле ([modes] —
     * ключи `SPORT`, `FOOD`, `DELA`, `MONEY`), и CRM, если она открыта ([crm]).
     */
    fun own(modes: Set<String>, crm: Boolean): List<Item> = buildList {
        if (SPORT in modes) add(Item("Спорт", "activity", Target.Screen(SPORT)))
        if (FOOD in modes) add(Item("Еда", "food", Target.Screen(FOOD)))
        if (DELA in modes) add(Item("Дела", "dela", Target.Screen(DELA)))
        if (MONEY in modes) add(Item("Деньги", "wallet", Target.Screen(MONEY)))
        if (crm && DELA in modes) add(Item("CRM", "briefcase", Target.Screen(CRM)))
    }

    /**
     * Направления меню: свои пункты [own] и сервисы сервера [nav] (адрес «/»
     * — сами Дела, относительный — от адреса Дел [base]). Направление без
     * единого пункта не показывается; сервис с незнакомым значком — в «Ещё»
     * последним, как в вебе.
     */
    fun directions(own: List<Item>, nav: List<Nav>, base: String?): List<Direction> {
        val web = nav.mapNotNull { n ->
            if (n.icon in OWN || n.url == "/") return@mapNotNull null
            val url = absolute(n.url, base) ?: return@mapNotNull null
            Item(n.name, n.icon, Target.Web(url))
        }
        val all = own + web
        val used = HashSet<Item>()
        val out = DIRECTIONS.mapNotNull { (title, sub, icons) ->
            val items = icons.flatMap { ic -> all.filter { it.icon == ic && it !in used } }
            used += items
            if (items.isEmpty()) null else Direction(title, sub, items)
        }.toMutableList()
        val rest = all.filter { it !in used }
        if (rest.isNotEmpty()) out += Direction("Ещё", "", rest)
        return out
    }

    /** Столбики без дыр: направление k — в столбик k % n, как `cols(n)` холста и веба. */
    fun columns(dirs: List<Direction>, n: Int): List<List<Direction>> =
        (0 until n.coerceAtLeast(1)).map { c -> dirs.filterIndexed { k, _ -> k % n == c } }

    /**
     * Сервисы из ответа `/api/me` (`look.nav`). Проверка — та же, что у
     * сервера (`config.parse_nav`): значок — слово латиницей, адрес — «/…» или
     * http(s); кривая запись молча пропускается.
     */
    fun navFrom(me: JSONObject?): List<Nav> {
        val a = me?.optJSONObject("look")?.optJSONArray("nav") ?: return emptyList()
        return navFrom(a)
    }

    fun navFrom(a: JSONArray): List<Nav> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name").trim()
        val icon = o.optString("icon").trim()
        val url = o.optString("url").trim()
        if (name.isEmpty() || name.length > 40 || !ICON.matches(icon) || !URL.matches(url)) null else Nav(name, icon, url)
    }

    /** Сервисы — обратно в JSON: телефон помнит их между запусками. */
    fun navJson(nav: List<Nav>): JSONArray = JSONArray().apply {
        nav.forEach { put(JSONObject().put("name", it.name).put("icon", it.icon).put("url", it.url)) }
    }

    /** Полный адрес: http(s) как есть; «/путь» — от адреса Дел; «/» (сами Дела) и прочее — null. */
    fun absolute(url: String, base: String?): String? = when {
        url.startsWith("https://") || url.startsWith("http://") -> url
        url.startsWith("/") && url.length > 1 && !base.isNullOrBlank() -> base.trimEnd('/') + url
        else -> null
    }

    /** Пакет приложения Claude для Android. */
    const val CLAUDE_PACKAGE = "com.anthropic.claude"
    const val CLAUDE_WEB = "https://claude.ai/"

    private val ICON = Regex("^[a-z][a-zA-Z0-9]{0,23}$")
    private val URL = Regex("^(/|https?://[^\\s\"'<>]+)$")
}
