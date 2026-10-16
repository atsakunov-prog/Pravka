package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * «Что не так?» на всех экранах телефона (16.10.2026, docs/dela-phone-24.md, раздел «На всех экранах»).
 *
 * Владелец: «Слушай, а можешь сделать на телефоне тоже дебаг строку такую же как на вебе? Чтобы отправлялось вместе с
 * тем же, что и на вебе». До этого строка была только во вкладке Дел и в карточке дела: снимок Дел данными собирает
 * вкладка (`DelaDebug.snapshot`). У «Сегодня», Засечки, Тела, Денег, «Правки», «Телефона» и страниц сервисов своих
 * данных для снимка нет — их экран читается так, как его видит чтение с экрана: дерево семантики Compose (тексты,
 * роль, состояние, где лежит), а у страницы сервиса — её текст из WebView. Записка та же (`debug.add`, правило 28 —
 * только владельцу), строка та же (`DebugBar`).
 *
 * Здесь — то, что не знает ни про Android, ни про сеть (под тестом `ScreenDebugTest`): где строка и какой у экрана
 * маршрут, строки экрана по порядку сверху вниз из узлов семантики, граница размера, снимок и кольцо переходов.
 */
object ScreenDebug {

    /** Строк экрана в снимке — как строк списка у Дел (`DelaDebug.ROWS`, контракт «до ~200»). */
    const val ROWS = DelaDebug.ROWS
    /** Знаков одной строки: длинная плашка текста не съест снимок. */
    const val ROW_MAX = 300
    /**
     * Знаков текста строк на весь снимок. Снимок Дел несёт 35–60 КБ; 30 тыс. знаков кириллицы — те же ~60 КБ JSON
     * вместе с ключами, сервер режет только за 400 тыс. (`debug.SNAPSHOT_MAX`).
     */
    const val TEXT_MAX = 30_000
    /** Переходов в кольце: своё кольцо, не кольцо Дел — частые взмахи карусели не вытеснят «сказал Claude». */
    const val NAV = 30

    /** Тег строки отладки в дереве семантики: снимок экрана не пишет саму строку. */
    const val BAR_TAG = "debug-bar"

    /** Ключ вкладки Дел: там своя строка со снимком Дел. */
    const val DELA = "dela"

    // ------------------------------------------------------------ где строка

    /**
     * Строка на экране: [route] — маршрут снимка (`app:<экран>`, `app:page:<что>`, `web:<сервис>`), [mode] — что ещё
     * знает навигация (экран под страницей, стопка страниц, часть «Тела», адрес сервиса).
     */
    data class At(val route: String, val title: String = "", val mode: Map<String, Any?> = emptyMap())

    /**
     * Видна ли строка: тем же условием, что строка Дел (`DelaDebugSync.on`) — режим включён (поле `debug` синка) и сервер
     * знает флаг `debug`. Выключили — её нет нигде.
     */
    fun show(info: DelaDebug.Info?, features: Set<String>): Boolean = info?.on == true && DelaDebug.FEATURE in features

    /**
     * Где строка и с каким маршрутом: [tab] — экран режима («today», «zasechka», «dela», «sport», «food», «money»,
     * «pravka»), [pages] — стопка страниц поверх него (верхняя — последней: «phone», «settings:dela», «cost»…),
     * [web] — открытая страница сервиса. Вкладка Дел на своём сервере ([delaOwn]) рисует свою строку со снимком Дел —
     * общей там нет (не дублировать); страница поверх Дел — уже не Дела, строка общая.
     */
    fun at(
        tab: String,
        pages: List<String> = emptyList(),
        web: String? = null,
        delaOwn: Boolean = false,
        mode: Map<String, Any?> = emptyMap(),
    ): At? = when {
        web != null -> At("web:$web", web, mode + ("under" to tab))
        pages.isNotEmpty() -> At("app:page:${pages.last()}", pages.last(),
            mode + ("under" to tab) + (if (pages.size > 1) mapOf("pages" to pages.joinToString(" › ")) else emptyMap()))
        tab == DELA && delaOwn -> null
        else -> At("app:$tab", tab, mode)
    }

    // ------------------------------------------------------------ строки экрана

    /**
     * Узел дерева семантики, как его видит снимок: тексты (уже словами: «поле: «…»», подписи значков), роль и состояние,
     * рамка в окне без обрезки прокруткой ([top]…[right]) и видна ли хоть часть в окне ([visible] — с обрезкой).
     */
    data class Node(
        val texts: List<String> = emptyList(),
        val role: String = "",
        val state: List<String> = emptyList(),
        val top: Float = 0f,
        val left: Float = 0f,
        val bottom: Float = 0f,
        val right: Float = 0f,
        val visible: Boolean = true,
        val children: List<Node> = emptyList(),
    )

    /** Строка экрана: текст, было ли в окне, роль и состояние словами, откуда (у страницы сервиса — «страница»). */
    data class Row(val text: String, val inView: Boolean, val status: String = "", val group: String = "")

    /**
     * Строки экрана по порядку чтения: дерево сверху вниз, соседи — строками экрана. Соседи на одной высоте
     * (значок, название и кнопки шапки; две колонки разворота) — слева направо: на развороте левая колонка целиком,
     * потом правая, а не вперемешку по высоте.
     */
    fun rows(root: Node): List<Row> {
        val out = ArrayList<Row>()
        fun walk(n: Node) {
            val text = rowText(n.texts)
            if (text.isNotEmpty()) out += Row(text, n.visible, (listOf(n.role) + n.state).filter { it.isNotBlank() }.distinct().joinToString(" · "))
            ordered(n.children).forEach(::walk)
        }
        walk(root)
        return out
    }

    /**
     * Соседи по порядку чтения: по верхнему краю, и кто начинается выше середины строки (своей серединой не ниже её
     * низа) — в той же строке; строка — слева направо. Не сравнение «с допуском» внутри сортировки: оно не
     * транзитивно и роняет сортировку.
     */
    internal fun ordered(nodes: List<Node>): List<Node> {
        if (nodes.size < 2) return nodes
        val lines = ArrayList<MutableList<Node>>()
        var bottom = Float.NEGATIVE_INFINITY
        for (n in nodes.sortedWith(compareBy({ it.top }, { it.left }))) {
            val mid = (n.top + n.bottom) / 2f
            if (lines.isNotEmpty() && mid < bottom) {
                lines.last() += n
                bottom = maxOf(bottom, n.bottom)
            } else {
                lines += mutableListOf(n)
                bottom = n.bottom
            }
        }
        return lines.flatMap { line -> line.sortedBy { it.left } }
    }

    private fun rowText(texts: List<String>): String {
        val parts = texts.map { mask(it.split(Regex("\\s+")).filter { w -> w.isNotEmpty() }.joinToString(" ")) }
            .filter { it.isNotEmpty() }.distinct()
        val s = parts.joinToString(" · ")
        return if (s.length <= ROW_MAX) s else s.take(ROW_MAX - 1) + "…"
    }

    /**
     * Ключи и токены — не в снимок: записка ложится в базу Дел, а на экранах настроек бывают ключи API и пароли
     * (поля пароля строка и так пишет без содержимого). «sk-…» и длинные слитные строки из букв и цифр — «•••».
     */
    private val SECRET = Regex("""sk-[A-Za-z0-9_\-]{8,}|(?<![A-Za-z0-9_\-])[A-Za-z0-9_\-]{32,}(?![A-Za-z0-9_\-])""")

    fun mask(s: String): String = SECRET.replace(s, "•••")

    /** Строки после границы размера: какие легли, сколько было всего и резали ли. */
    data class Fit(val rows: List<Row>, val total: Int, val cut: Boolean)

    /**
     * Граница размера: не больше [maxRows] строк и [maxChars] знаков текста. Первыми ложатся строки, бывшие в окне (их
     * человек и видел, когда писал), потом остальные сверху вниз; порядок в снимке — как на экране.
     */
    fun fit(rows: List<Row>, maxRows: Int = ROWS, maxChars: Int = TEXT_MAX): Fit {
        val keep = BooleanArray(rows.size)
        var n = 0
        var chars = 0
        for (pass in 0..1) {
            for ((i, r) in rows.withIndex()) {
                if (keep[i] || r.inView != (pass == 0)) continue
                if (n >= maxRows || chars + r.text.length > maxChars) continue
                keep[i] = true
                n++
                chars += r.text.length
            }
        }
        val out = rows.filterIndexed { i, _ -> keep[i] }
        return Fit(out, rows.size, out.size < rows.size)
    }

    // ------------------------------------------------------------ страница сервиса

    /** Страница сервиса (WebView): адрес, заголовок, где прокручено, строки текста по блокам; [error] — не прочлось. */
    data class Web(
        val url: String,
        val title: String = "",
        val scroll: String = "",
        val rows: List<Row> = emptyList(),
        val error: String = "",
    )

    /**
     * Ответ `evaluateJavascript` (строка JSON в кавычках): `{url, title, y, h, vh, rows: [[текст, 1|0]…]}` — строки по
     * блокам страницы, 1 — блок был в окне. Кривое — null, записка уходит без текста страницы.
     */
    fun web(raw: String?): Web? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            val v = JSONTokener(raw).nextValue()
            val o = when (v) {
                is String -> JSONObject(v)
                is JSONObject -> v
                else -> return null
            }
            val rows = ArrayList<Row>()
            o.optJSONArray("rows")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.optJSONArray(i) ?: continue
                    val t = rowText(listOf(r.optString(0)))
                    if (t.isNotEmpty()) rows += Row(t, r.optInt(1) == 1, group = "страница")
                }
            }
            val y = o.optInt("y")
            val h = o.optInt("h")
            Web(
                url = url(o.optString("url")),
                title = o.optString("title").trim(),
                scroll = if (h > 0) "$y из $h (окно ${o.optInt("vh")})" else "",
                rows = rows,
                error = o.optString("error"),
            )
        }.getOrNull()
    }

    /** Адрес без значений ключей в запросе (token, key, pass, secret, auth, sig): адрес идёт в базу Дел. */
    fun url(u: String): String {
        val q = u.indexOf('?')
        if (q < 0) return u
        val hash = u.indexOf('#', q).takeIf { it >= 0 } ?: u.length
        val params = u.substring(q + 1, hash).split('&').joinToString("&") { p ->
            val k = p.substringBefore('=')
            if (p.contains('=') && Regex("token|key|pass|secret|auth|sig", RegexOption.IGNORE_CASE).containsMatchIn(k)) "$k=•••" else p
        }
        return u.substring(0, q + 1) + params + u.substring(hash)
    }

    // ------------------------------------------------------------ снимок

    /**
     * Снимок экрана данными — состав контракта `dela-debug.json` (`snapshot`), насколько его знает чужой экран:
     * маршрут, заголовок и подпись шапки, режим (навигация, вид, тема, разворот), строки экрана по порядку (`rows`:
     * `title` — текст, `status` — роль и состояние, `in_view` — было в окне, `group` — «страница» у текста страницы
     * сервиса), страница сервиса (`web`), сборка, строки журнала приложения и очередь Дел, ещё не ушедшая на сервер.
     * Сервер снимок не проверяет (кроме размера) — старый сервер примет его как есть.
     */
    fun snapshot(
        at: At,
        title: String,
        sub: String?,
        rows: List<Row>,
        web: Web? = null,
        mode: Map<String, Any?> = emptyMap(),
        build: String = "",
        events: List<String> = emptyList(),
        queue: List<JSONObject> = emptyList(),
        failed: String = "",
    ): JSONObject {
        val o = JSONObject().put("v", 1).put("route", at.route).put("kind", "phone").put("title", title.ifBlank { at.title })
        if (!sub.isNullOrBlank()) o.put("sub", sub)
        val m = JSONObject()
        for ((k, v) in at.mode + mode) if (v != null && v != "" && v != false) m.put(k, v)
        if (m.length() > 0) o.put("mode", m)
        val fit = fit(rows + web?.rows.orEmpty())
        if (fit.rows.isNotEmpty()) {
            o.put("rows", JSONArray().apply {
                fit.rows.forEach { r ->
                    put(JSONObject().put("title", r.text).apply {
                        if (r.group.isNotBlank()) put("group", r.group)
                        if (r.status.isNotBlank()) put("status", r.status)
                    }.put("in_view", r.inView))
                }
            })
            o.put("rows_total", fit.total)
            if (fit.cut) o.put("rows_cut", true)
        }
        if (web != null) o.put("web", JSONObject().put("url", web.url).apply {
            if (web.title.isNotBlank()) put("title", web.title)
            if (web.scroll.isNotBlank()) put("scroll", web.scroll)
            if (web.error.isNotBlank()) put("error", web.error)
        })
        if (failed.isNotBlank()) o.put("failed", failed)
        if (build.isNotBlank()) o.put("build", build)
        if (events.isNotEmpty()) o.put("events", JSONArray(events.takeLast(DelaDebug.EVENTS)))
        val q = queue.filterNot { it.optString("op").startsWith("debug.") }
        if (q.isNotEmpty()) o.put("queue", JSONArray().apply { q.take(30).forEach { put(DelaDebug.shrink(it, 0)) } })
        return o
    }

    // ------------------------------------------------------------ переходы

    private val nav = ArrayDeque<Pair<Long, JSONObject>>()

    /**
     * Переход на другой экран (вид `nav` контракта): «Засечка [app:zasechka]». В памяти процесса, своё кольцо; тот же
     * экран второй раз подряд (взмах туда и обратно не дошёл) — не пишется.
     */
    fun noteNav(route: String, title: String, now: Long = System.currentTimeMillis()) {
        val text = (if (title.isNotBlank()) "$title " else "") + "[$route]"
        synchronized(nav) {
            if (nav.lastOrNull()?.second?.optString("text") == text) return
            nav.addLast(now to JSONObject().put("kind", "nav").put("text", text))
            while (nav.size > NAV) nav.removeFirst()
        }
    }

    fun recentNav(): List<Pair<Long, JSONObject>> = synchronized(nav) { nav.toList() }

    internal fun clearNav() = synchronized(nav) { nav.clear() }
}
