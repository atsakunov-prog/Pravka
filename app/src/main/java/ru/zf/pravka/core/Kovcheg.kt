package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Меню Ковчега на телефоне (Правка 5.0, план §3, решение владельца 13.10.2026):
 * значок Ковчега в шапке каждого экрана открывает все сервисы семьи — три
 * большие кнопки (Сегодня, Засечка, Правка) и восемь направлений Круга жизни
 * столбиками, «Творчество» с Claude строкой ниже.
 *
 * С 14.10.2026 (этап 7) что в меню и в каком порядке — Свод `ui.menu`, одна
 * правда с вебом Дел и всеми страницами Ковчега: владелец правит его в вебе
 * Дел («Настройки → Вид и меню»), телефон получает обычным синком Свода.
 * Здесь — разбор и проверка записи ([menuFrom], те же правила, что у сервера:
 * кривая запись — заводское целиком), заводское ([FACTORY] — ровно
 * `ui.menu_factory` контракта `server/contract/svod.json`, сверяет
 * `KovchegTest`) и раскладка пунктов по дорогам ([build]). В меню — КЛЮЧИ
 * сервисов: что есть в приложении — открывает свой экран (Спорт, Еда, Дела,
 * Деньги, CRM — раздел Дел; большие кнопки — Сегодня, Засечка, Правка), чего
 * нет (Встречи, Дети, Шаббат, Книги, Кино, Дом, VPN) — веб-страница по адресу
 * из `/api/me` → `look.nav` (настройки сервера на компе; репозиторий публичный).
 * Ключ без дороги не показывается, знакомый, но не поставленный — в «Ещё».
 * Вид (`ui.look`, токены тем поверх заводских) разбирает [tokens].
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

    /** Пункт меню: название, значок набора Ковчега (имя веба), куда ведёт, подпись и ключ сервиса в Своде. */
    data class Item(val name: String, val icon: String, val target: Target, val sub: String = "", val key: String = "")

    /** Направление Круга жизни: название, подпись из формулы и сервисы. */
    data class Direction(val title: String, val sub: String, val items: List<Item>)

    /** Сервис из настроек сервера (`look.nav`): [key] — ключ меню Свода (сервер, а у старого — по значку). */
    data class Nav(val name: String, val icon: String, val url: String, val key: String = KEY_BY_ICON[icon].orEmpty())

    // ------------------------------------------------------------ меню Свода

    /** Пункт записи `ui.menu`: ключ сервиса, его подпись, значок и мелкая подпись — пустое значит «своё у сервиса». */
    data class Spec(val key: String, val name: String = "", val icon: String = "", val sub: String = "")

    /** Направление записи `ui.menu`. */
    data class Group(val name: String, val sub: String, val items: List<Spec>)

    /** Меню целиком: большие кнопки, направления по порядку, строка «Творчество» (null — её нет). */
    data class Menu(val big: List<Spec>, val directions: List<Group>, val create: Group?)

    const val MENU_KEY = "ui.menu"
    const val LOOK_KEY = "ui.look"

    const val TODAY = "today"
    const val ZASECHKA = "zasechka"
    const val PRAVKA = "pravka"
    const val SPORT = "sport"
    const val FOOD = "food"
    const val DELA = "dela"
    const val MONEY = "money"
    const val CRM = "crm"

    /**
     * Каталог знакомых сервисов (как `kovcheg_ui.CATALOG` сервера): ключ Свода →
     * название и значок. По нему пункт без подписи берёт свою, а сервис
     * настроек без ключа узнаётся по значку.
     */
    val CATALOG: Map<String, Pair<String, String>> = linkedMapOf(
        "today" to ("Сегодня" to "sun"), "zasechka" to ("Засечка" to "clock"), "pravka" to ("Правка" to "edit"),
        "sport" to ("Спорт" to "activity"), "eda" to ("Еда" to "food"), "knigi" to ("Книги" to "book"),
        "dela" to ("Дела" to "dela"), "meetings" to ("Встречи" to "mic"), "dengi" to ("Деньги" to "wallet"),
        "crm" to ("CRM" to "briefcase"), "deti" to ("Дети" to "kids"), "shabbat" to ("Шаббат" to "candles"),
        "kino" to ("Кино" to "film"), "dom" to ("Дом" to "home"), "vpn" to ("VPN" to "shield"),
        "claude" to ("Claude" to "claude"),
    )
    val KEY_BY_ICON: Map<String, String> = CATALOG.entries.associate { (k, v) -> v.second to k }

    /**
     * Ключ Свода → экран приложения. Сервис, у которого в приложении свой экран,
     * в меню телефона только экраном: его веб на компе («Спорт», «Еда») сюда не
     * идёт — экран приложения и есть этот сервис.
     */
    private val SCREEN_OF = mapOf(
        "today" to TODAY, "zasechka" to ZASECHKA, "pravka" to PRAVKA, "sport" to SPORT, "eda" to FOOD,
        "dela" to DELA, "dengi" to MONEY, "crm" to CRM,
    )
    private val OWN_KEYS = SCREEN_OF.keys + "claude"

    /** Большие кнопки — экраны, без которых приложение не обойдётся: из меню их не убрать. */
    private val MUST = listOf("today", "zasechka", "pravka")

    /** Большие кнопки страницами веба Дел (веб-двойники 14.10.2026), когда своих экранов у телефона нет. */
    private val WEB_OF = mapOf("today" to "/#/day", "zasechka" to "/#/zasechka", "pravka" to "/#/pravka")

    private fun g(name: String, sub: String, vararg keys: String) = Group(name, sub, keys.map { Spec(it) })

    /** Заводское меню — `ui.menu_factory` контракта: то, что было в коде до Свода (план §3). */
    val FACTORY = Menu(
        big = listOf(Spec("today", sub = "день целиком"), Spec("zasechka", sub = "что делаю сейчас"), Spec("pravka", sub = "все диктовки")),
        directions = listOf(
            g("Здоровье", "сильное тело, ясная голова", "sport", "eda"),
            g("Ум", "много знаю и узнаю", "knigi"),
            g("Дело", "растёт вглубь и вширь", "dela", "meetings"),
            g("Деньги", "деньги — не ограничение", "dengi"),
            g("Круг", "знаю многих", "crm"),
            g("Родительство", "сыновья растут сильными", "deti", "shabbat"),
            g("Пара", "близкая живая пара", "kino"),
            g("Быт", "дома хорошо всем пятерым", "dom", "vpn"),
        ),
        create = g("Творчество", "создаю новое", "claude"),
    )

    private val KEY = Regex("^[a-z][a-z0-9_-]{0,23}$")
    private val BAD = Regex("[\\u0000-\\u001f\\u007f<>]")

    private class Bad(why: String) : Exception(why)

    private fun text(v: Any?, most: Int, need: Boolean = true): String {
        if (v != null && v != JSONObject.NULL && v !is String) throw Bad("не текст")
        val s = (v as? String).orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if ((need && s.isEmpty()) || s.length > most || BAD.containsMatchIn(s)) throw Bad("текст «${s.take(30)}»")
        return s
    }

    private fun spec(x: Any?, seen: MutableSet<String>): Spec {
        val o = when (x) {
            is String -> JSONObject().put("key", x)
            is JSONObject -> x
            else -> throw Bad("пункт")
        }
        val key = o.optString("key")
        if (!KEY.matches(key) || !seen.add(key)) throw Bad("ключ «$key»")
        val icon = o.optString("icon").takeIf { ICON.matches(it) }.orEmpty()
        val name = text(o.opt("name"), 24, need = false).ifEmpty { CATALOG[key]?.first.orEmpty() }
        if (name.isEmpty()) throw Bad("у «$key» нет названия")
        return Spec(key, name, icon, text(o.opt("sub"), 40, need = false))
    }

    private fun group(x: Any?, seen: MutableSet<String>): Group {
        val o = x as? JSONObject ?: throw Bad("направление")
        val items = o.opt("items").let { if (it == null || it == JSONObject.NULL) JSONArray() else it as? JSONArray ?: throw Bad("items") }
        if (items.length() > 10) throw Bad("больше 10 сервисов")
        return Group(text(o.opt("name"), 24), text(o.opt("sub"), 60, need = false), (0 until items.length()).map { spec(items.get(it), seen) })
    }

    /**
     * Запись Свода `ui.menu` → меню. Правила — как у сервера
     * (`kovcheg_ui.check_menu`): 1–12 направлений, до 10 сервисов, ключ — один
     * раз на всё меню, подписи без угловых скобок; нет `big` или `create` —
     * заводские, `create: null` — строки нет. Кривое — null: тогда меню
     * заводское целиком (лучше прежнее, чем половина нового).
     */
    fun menuFrom(value: Any?): Menu? {
        val o = value as? JSONObject ?: return null
        return runCatching {
            val seen = HashSet<String>()
            val big = if (!o.has("big")) FACTORY.big.map { spec(JSONObject().put("key", it.key).put("sub", it.sub), seen) }
            else (o.get("big") as? JSONArray ?: throw Bad("big")).let { a ->
                if (a.length() > 4) throw Bad("больше 4 больших кнопок")
                (0 until a.length()).map { spec(a.get(it), seen) }
            }
            val dirs = o.optJSONArray("directions") ?: throw Bad("directions")
            if (dirs.length() !in 1..12) throw Bad("направлений ${dirs.length()}")
            val groups = (0 until dirs.length()).map { group(dirs.get(it), seen) }
            if (groups.map { it.name.lowercase() }.toSet().size != groups.size) throw Bad("два направления с одним названием")
            val create = when {
                !o.has("create") -> FACTORY.create?.let { c -> Group(c.name, c.sub, c.items.map { spec(it.key, seen) }) }
                o.isNull("create") -> null
                else -> group(o.get("create"), seen)
            }
            if (seen.size > 40) throw Bad("больше 40 пунктов")
            Menu(big, groups, create)
        }.getOrNull()
    }

    /** Что нарисовать: большие кнопки, направления (с «Ещё» в конце), строка «Творчество» (null — нет). */
    data class Built(val big: List<Item>, val directions: List<Direction>, val create: Direction?)

    /**
     * Пункты меню по дорогам. [own] — свои экраны приложения (по включённым
     * режимам, [own]), [nav] — сервисы сервера (адрес «/» — сами Дела,
     * относительный — от адреса Дел [base]), [zasechka] — Засечка включена.
     * Большая кнопка — экран приложения; Сегодня, Засечка и Правка, которых в
     * записи нет, — добавляются (из меню их не убрать: это экраны телефона).
     * Направление без единого пункта не показывается; знакомое, но не
     * поставленное в меню — в «Ещё» последним, как в вебе.
     */
    fun build(
        menu: Menu,
        own: List<Item>,
        nav: List<Nav>,
        base: String?,
        zasechka: Boolean = true,
        /**
         * Правка на диете (22.10.2026): своих экранов у телефона нет — Спорт, Еда, Деньги, CRM и сами Дела
         * (страница `/t`) идут страницами сервера, а большие кнопки — страницами веба Дел ([WEB_OF]).
         */
        webScreens: Boolean = false,
    ): Built {
        val road = LinkedHashMap<String, Item>()
        for (x in own) road[x.key] = x
        road["claude"] = Item("Claude", "claude", Target.Claude, key = "claude")
        val loose = ArrayList<Item>()
        for (n in nav) {
            if (!webScreens && (n.key in OWN_KEYS || n.url == "/")) continue
            val url = (if (n.url == "/") base?.let { DelaWeb.pageUrl(it) } else absolute(n.url, base)) ?: continue
            val item = Item(n.name, n.icon, Target.Web(url), key = n.key)
            if (n.key.isNotEmpty() && n.key !in road) road[n.key] = item else if (n.key.isEmpty()) loose += item
        }
        val used = HashSet<String>()
        fun pick(specs: List<Spec>): List<Item> = specs.mapNotNull { s ->
            val r = road[s.key] ?: return@mapNotNull null
            if (!used.add(s.key)) return@mapNotNull null
            r.copy(name = s.name.ifEmpty { r.name }, icon = s.icon.ifEmpty { r.icon }, sub = s.sub.ifEmpty { r.sub })
        }
        // Большие кнопки — экраны приложения: Засечка — только когда включена.
        val screens = MUST.filter { it != "zasechka" || zasechka }
        for (k in screens) {
            val (name, icon) = CATALOG.getValue(k)
            // Сервер сам назвал страницу этой кнопки (look.nav) — она сильнее заводской.
            if (webScreens && road[k]?.target is Target.Web) continue
            val target = if (webScreens) WEB_OF[k]?.let { absolute(it, base) }?.let { Target.Web(it) } ?: continue
            else Target.Screen(SCREEN_OF.getValue(k))
            road[k] = Item(name, icon, target, key = k)
        }
        val big = pick(menu.big) + pick(screens.filter { k -> menu.big.none { it.key == k } && menu.directions.none { d -> d.items.any { it.key == k } } }
            .map { k -> FACTORY.big.first { it.key == k } })
        val dirs = menu.directions.mapNotNull { d -> pick(d.items).takeIf { it.isNotEmpty() }?.let { Direction(d.name, d.sub, it) } }.toMutableList()
        val create = menu.create?.let { c -> pick(c.items).takeIf { it.isNotEmpty() }?.let { Direction(c.name, c.sub, it) } }
        val rest = road.values.filter { it.key !in used && it.key !in MUST } + loose
        if (rest.isNotEmpty()) dirs += Direction("Ещё", "", rest)
        return Built(big, dirs, create)
    }

    /** Три большие кнопки заводского меню: «день целиком», «что делаю сейчас», «все диктовки». */
    val BIG: List<Item> get() = build(FACTORY, emptyList(), emptyList(), null).big

    /** «Творчество» заводского меню — строкой ниже направлений, Claude рядом. */
    val CREATE: Direction get() = build(FACTORY, emptyList(), emptyList(), null).create!!

    /**
     * Свои пункты приложения: режимы, включённые в профиле ([modes] —
     * ключи `SPORT`, `FOOD`, `DELA`, `MONEY`), и CRM, если она открыта ([crm]).
     */
    fun own(modes: Set<String>, crm: Boolean): List<Item> = buildList {
        if (SPORT in modes) add(Item("Спорт", "activity", Target.Screen(SPORT), key = "sport"))
        if (FOOD in modes) add(Item("Еда", "food", Target.Screen(FOOD), key = "eda"))
        if (DELA in modes) add(Item("Дела", "dela", Target.Screen(DELA), key = "dela"))
        if (MONEY in modes) add(Item("Деньги", "wallet", Target.Screen(MONEY), key = "dengi"))
        if (crm && DELA in modes) add(Item("CRM", "briefcase", Target.Screen(CRM), key = "crm"))
    }

    /** Направления заводского меню (как до Свода) — для тех, кому запись не нужна. */
    fun directions(own: List<Item>, nav: List<Nav>, base: String?): List<Direction> = build(FACTORY, own, nav, base).directions

    /** Столбики без дыр: направление k — в столбик k % n, как `cols(n)` холста и веба. */
    fun columns(dirs: List<Direction>, n: Int): List<List<Direction>> =
        (0 until n.coerceAtLeast(1)).map { c -> dirs.filterIndexed { k, _ -> k % n == c } }

    /**
     * Сервисы из ответа `/api/me` (`look.nav`). Проверка — та же, что у
     * сервера (`config.parse_nav`): значок — слово латиницей, адрес — «/…» или
     * http(s); кривая запись молча пропускается. Ключ — от сервера, у старого
     * (и в файле, записанном до этапа 7) — по значку.
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
        val key = o.optString("key").trim().takeIf { KEY.matches(it) } ?: KEY_BY_ICON[icon].orEmpty()
        if (name.isEmpty() || name.length > 40 || !ICON.matches(icon) || !URL.matches(url)) null else Nav(name, icon, url, key)
    }

    /** Сервисы — обратно в JSON: телефон помнит их между запусками. */
    fun navJson(nav: List<Nav>): JSONArray = JSONArray().apply {
        nav.forEach { put(JSONObject().put("name", it.name).put("icon", it.icon).put("url", it.url).put("key", it.key)) }
    }

    /**
     * Страница сервиса по ключу (22.10.2026, своих экранов у телефона нет): «Открыть Засечку» из меню кнопки,
     * уведомление, виджет — ведут на страницу сервера. Большие кнопки — веб Дел ([WEB_OF]), «dela» — `/t`,
     * остальное — адрес из `look.nav`. null — адреса нет (сервер не подключён или сервиса нет в списке).
     */
    fun pageFor(key: String, nav: List<Nav>, base: String?): ServicePage? {
        val url = when {
            key in WEB_OF -> absolute(WEB_OF.getValue(key), base)
            key == "dela" -> base?.let { DelaWeb.pageUrl(it) }
            else -> nav.firstOrNull { it.key == key }?.let { n ->
                if (n.url == "/") base?.let { DelaWeb.pageUrl(it) } else absolute(n.url, base)
            }
        } ?: return null
        val (name, icon) = CATALOG[key] ?: (key to "globe")
        return ServicePage(name, icon, url)
    }

    /** Полный адрес: http(s) как есть; «/путь» — от адреса Дел; «/» (сами Дела) и прочее — null. */
    fun absolute(url: String, base: String?): String? = when {
        url.startsWith("https://") || url.startsWith("http://") -> url
        url.startsWith("/") && url.length > 1 && !base.isNullOrBlank() -> base.trimEnd('/') + url
        else -> null
    }

    // ------------------------------------------------------------ вид Свода

    /** Токены вида, которые можно поменять Сводом (`ui.look`): переменные общего слоя без «--». */
    val TOKENS: Set<String> = setOf(
        "bg", "side", "panel", "panel-2", "line", "line-soft", "text", "muted", "faint", "icon", "check", "ok", "warn",
        "bad", "info", "claude", "accent", "accent-ink", "accent-soft", "bar-fill",
        "sp0", "sp1", "sp2", "sp3", "sp4", "sp5", "sp6", "sp7",
    )

    /** Токены обеих тем: имя → цвет ARGB. Пусто — заводской вид. */
    data class Tokens(val light: Map<String, Long> = emptyMap(), val dark: Map<String, Long> = emptyMap())

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    /**
     * Запись Свода `ui.look` → токены. Чужое имя или цвет не вида `#rrggbb` —
     * мимо (сервер такое и не запишет, но кэш мог лечь до проверки): лучше
     * заводской цвет, чем падение экрана.
     */
    fun tokens(value: Any?): Tokens {
        val o = value as? JSONObject ?: return Tokens()
        fun theme(name: String): Map<String, Long> {
            val t = o.optJSONObject(name) ?: return emptyMap()
            return t.keys().asSequence().filter { it in TOKENS }.mapNotNull { k ->
                t.optString(k).takeIf { HEX.matches(it) }?.let { k to (0xFF000000L or it.substring(1).toLong(16)) }
            }.toMap()
        }
        return Tokens(theme("light"), theme("dark"))
    }

    /** Смесь цветов ARGB: доля [wa] первого (как `kovcheg_ui.mix` сервера). */
    fun mix(a: Long, b: Long, wa: Double): Long {
        fun ch(c: Long, s: Int) = ((c shr s) and 0xFF).toDouble()
        val r = (0..2).map { i -> val s = 16 - 8 * i; Math.round(ch(a, s) * wa + ch(b, s) * (1 - wa)) }
        return 0xFF000000L or (r[0] shl 16) or (r[1] shl 8) or r[2]
    }

    /**
     * Шкала графиков от акцента: [светлее, акцент, темнее, бледный, бледнее] —
     * как заводские `ramp` обеих тем, только от нового акцента. Нужна, когда
     * акцент поменяли Сводом: иначе столбики остались бы старым синим.
     */
    fun ramp(accent: Long, dark: Boolean): List<Long> {
        val black = 0xFF000000L
        val white = 0xFFFFFFFFL
        val bg = 0xFF161513L
        return if (dark) listOf(mix(accent, white, 0.55), accent, mix(accent, black, 0.75), mix(accent, bg, 0.4), mix(accent, bg, 0.25))
        else listOf(mix(accent, white, 0.7), accent, mix(accent, black, 0.75), mix(accent, white, 0.3), mix(accent, white, 0.15))
    }

    /** Пакет приложения Claude для Android. */
    const val CLAUDE_PACKAGE = "com.anthropic.claude"
    const val CLAUDE_WEB = "https://claude.ai/"

    private val ICON = Regex("^[a-z][a-zA-Z0-9]{0,23}$")
    private val URL = Regex("^(/|https?://[^\\s\"'<>]+)$")
}
