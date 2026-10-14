package ru.zf.pravka

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.zf.pravka.core.ServicePage
import ru.zf.pravka.core.ServiceWeb
import ru.zf.pravka.ui.Feedback

// Сервисы Ковчега внутри Правки (14.10.2026): WebView страницы сервиса и всё,
// что вокруг него, — без Compose. Экран с шапкой — `ServiceWebScreen.kt`,
// правила «что внутри, что наружу» — `core/ServiceWeb.kt` (под тестом).
//
// Почему WebView живёт дольше экрана. Активити Правки пересоздаётся при
// складывании Fold и повороте (`configChanges` у неё нет — так у всего
// приложения); WebView, созданный заново, перечитал бы страницу и потерял
// прокрутку, открытую встречу, набранное. Поэтому страница держится в пуле на
// процесс ([ServiceWebPool]) поверх `MutableContextWrapper`: при показе её
// контекст — текущая активити (диалоги, выбор в `<select>`, автозаполнение),
// спрятанная — контекст приложения, чтобы не держать мёртвую активити.

/** Одна живая страница сервиса: WebView и то, что о нём видит экран (состояние Compose). */
internal class ServiceWebPage private constructor(
    val page: ServicePage,
    private val wrapper: MutableContextWrapper,
    @Volatile var rules: ServiceWeb.Rules,
) {
    lateinit var view: WebView
        private set

    /** Загрузка 0…100; 100 — полоска не видна. */
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var title by mutableStateOf("")

    /** Адрес, что сейчас на экране: из него — «открыть в браузере» и восстановление после смерти процесса. */
    var current by mutableStateOf<String?>(null)

    /** Главная загрузка не удалась — причина целиком (правило 6: не затирать общей фразой). */
    var error by mutableStateOf<String?>(null)

    /** Первый кадр страницы показан: до него — заглушка «Открываю…», а не белое поле. */
    var committed by mutableStateOf(false)

    /** Процесс отрисовки WebView умер: страницу выбросить и создать заново. */
    var gone by mutableStateOf(false)

    /**
     * Сам сервис входит паролем браузера (HTTP Basic — панель VPN): страница
     * ушла в браузер, экран закрывается сам ([ServiceWeb.basicAuthLeave]).
     */
    var handedOff by mutableStateOf(false)

    /** Адрес, который главная рамка сейчас грузит (ещё не начат): для него — запрос пароля браузера. */
    private var loading: String? = null

    /** Хосты, которые эта страница уже отдала браузеру из-за пароля браузера. */
    private val leftHosts = HashSet<String>()

    /** Тема, с которой страница сейчас (кука `kv-theme`); null — ещё не ставили. */
    private var dark: Boolean? = null

    /** Когда спрятали (0 — на экране): вернулись через [ServiceWeb.STALE_MS] — перечитать. */
    private var hiddenAt = 0L

    /** Кому показана: отпускать может только тот же экран (при складывании новая активити успевает раньше). */
    private var shownIn: Context? = null

    /** Выбор файла (`<input type=file>`) запускает экран — у него есть активити и её результат. */
    var pickFiles: ((Intent) -> Boolean)? = null

    /** Ждущий ответа `<input type=file>`: живёт здесь, а не в экране, — выбор переживает складывание. */
    var pendingFiles: ValueCallback<Array<Uri>>? = null

    /** Показать страницу в [host] (активити экрана): снять с прежнего места, ожить, при надобности перечитать. */
    fun show(host: Context): WebView {
        (view.parent as? ViewGroup)?.removeView(view)
        wrapper.baseContext = host
        shownIn = host
        wake()
        return view
    }

    /** Экран ушёл: заснуть, отдать активити, дописать куки входа на диск. */
    fun hide(from: Context) {
        if (shownIn !== from) return
        shownIn = null
        sleep()
        wrapper.baseContext = from.applicationContext
    }

    /**
     * Заснуть (экран ушёл или приложение в фоне): таймеры страницы стоят, куки
     * входа — на диск сразу. Пока открыт системный выбор файла — не считать
     * спрятанной: вернёмся с файлом, и перечитывание его потеряло бы.
     */
    fun sleep() {
        view.onPause()
        CookieManager.getInstance().flush()
        if (hiddenAt == 0L && pendingFiles == null) hiddenAt = SystemClock.elapsedRealtime()
    }

    /** Проснуться: спрятанная дольше [ServiceWeb.STALE_MS] — перечитать, чтобы правка сервера была видна сразу. */
    fun wake() {
        view.onResume()
        if (ServiceWeb.needsReload(hiddenAt, SystemClock.elapsedRealtime()) && committed && error == null) view.reload()
        hiddenAt = 0L
    }

    /** Тема приложения поменялась (или страница открыта впервые): кука и сама страница. */
    fun theme(dark: Boolean) {
        if (this.dark == dark) return
        this.dark = dark
        val at = current ?: page.url
        setThemeCookies(dark, listOf(at, page.url)) {
            view.evaluateJavascript(ServiceWeb.themeScript(dark)) { r -> if (r?.trim() != "1") view.reload() }
        }
    }

    /** Повторить: страница не открылась ни разу — загрузить заново адрес, иначе перечитать. */
    fun retry() {
        error = null
        if (committed) view.reload() else load(current ?: page.url)
    }

    /** Открыть адрес по правилам: свой — здесь (с кукой темы), чужой — браузером. */
    fun go(url: String) {
        when (val r = ServiceWeb.route(url, rules)) {
            ServiceWeb.Route.Inside -> load(url)
            else -> handle(r)
        }
    }

    private fun load(url: String) {
        loading = url
        setThemeCookies(dark ?: false, listOf(url)) { view.loadUrl(url) }
    }

    /**
     * Системное «назад»: сначала открытое окно страницы (карточка книги —
     * `<dialog>`) — закрыть, как в Chrome; нет окна — история страницы, потом
     * закрыть экран ([close]). Ответ страницы ждём недолго: зависла — «назад» всё
     * равно срабатывает, а не пропадает.
     */
    fun back(close: () -> Unit) {
        fun fallback() = if (canBack && error == null) view.goBack() else close()
        if (error != null || !committed) return fallback()
        var done = false
        val late = Runnable { if (!done) { done = true; fallback() } }
        view.evaluateJavascript(ServiceWeb.CLOSE_DIALOG_SCRIPT) { r ->
            if (done) return@evaluateJavascript
            done = true
            view.removeCallbacks(late)
            if (r?.trim() != "1") fallback()
        }
        view.postDelayed(late, 400)
    }

    /**
     * Кука темы на каждый адрес, потом [then] на главном потоке. `setCookie`
     * асинхронный: грузить страницу раньше, чем кука легла, — первая отрисовка
     * в чужой теме. Ответ не пришёл за полсекунды — грузим всё равно.
     */
    private fun setThemeCookies(dark: Boolean, urls: List<String>, then: () -> Unit) {
        val cm = CookieManager.getInstance()
        val list = urls.distinct()
        var left = list.size
        var done = false
        val finish = Runnable { if (!done) { done = true; then() } }
        for (u in list) {
            runCatching {
                cm.setCookie(u, ServiceWeb.themeCookie(dark, u, rules.domain)) { _ -> view.post { if (--left <= 0) finish.run() } }
            }.onFailure { view.post { if (--left <= 0) finish.run() } }
        }
        view.postDelayed(finish, 500)
    }

    /** Переход со страницы: куда — решает [ServiceWeb.route]; true — WebView его не грузит. */
    private fun handle(r: ServiceWeb.Route): Boolean {
        val ctx = view.context
        return when (r) {
            ServiceWeb.Route.Inside, ServiceWeb.Route.Stay -> false
            ServiceWeb.Route.Block -> true
            is ServiceWeb.Route.Browser -> { openWeb(ctx, r.url); true }
            is ServiceWeb.Route.Intent -> { ServiceWebLinks.openIntent(ctx, r.url) { go(it) }; true }
            is ServiceWeb.Route.App -> { ServiceWebLinks.openOther(ctx, r.url); true }
        }
    }

    fun destroy() {
        pendingFiles?.onReceiveValue(null)
        pendingFiles = null
        pickFiles = null
        runCatching {
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build(dark: Boolean, bg: Int, start: String) {
        val v = WebView(wrapper)
        view = v
        this.dark = dark
        // Размер — «во всё место», заранее и явно. Без этого Compose (`AndroidView` → `addView` без параметров) даёт
        // WebView WRAP_CONTENT, а WebView по высоте WRAP_CONTENT меняет раскладку: высота окна страницы для неё — ноль
        // («force zero layout height»). Тогда 100vh = 0, и всё, что меряется окном, схлопывается: карточка книги в
        // Книгах (`<dialog>` с max-height: calc(100vh − 32px)) не видна вовсе (владелец 14.10.2026: «не показывается её
        // карточка»), меню и шестерёнка общего слоя — тоже; `position: fixed` — от верха страницы, а не экрана.
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        with(v.settings) {
            // Страницы сервисов — приложения на JS (тема, меню Ковчега, вход); без него не живут.
            javaScriptEnabled = true
            // localStorage: запас темы у `kovcheg.js`, выбор разделов и черновики у сервисов.
            domStorageEnabled = true
            // Файлов и провайдеров устройства страницам не видно: загрузка — системным выбором файла.
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Одно окно: ссылки target=_blank открываются здесь же — и проходят те же правила «свой — чужой».
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString ${ServiceWeb.UA_MARK}"
        }
        // Вход в каждый сервис — куками: принимать и помнить; чужих (третьих) — нет.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, false)
        // Пока страница не нарисовалась — фон экрана, а не белая вспышка в тёмной теме.
        v.setBackgroundColor(bg)
        v.webViewClient = Client()
        v.webChromeClient = Chrome()
        v.setDownloadListener { url, ua, disposition, mime, _ -> ServiceWebLinks.download(v.context, url, ua, disposition, mime) }
        load(start)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString() ?: return true
            val r = ServiceWeb.route(url, rules)
            // Рамки внутри страницы (видео, карта) грузятся своим чередом; наружу — только чужие схемы.
            if (!request.isForMainFrame && (r is ServiceWeb.Route.Browser || r == ServiceWeb.Route.Inside)) return false
            // Сервис сразу увёл на чужой адрес (вход у чужого сайта): ушли в браузер — на экране так и сказать,
            // а не «Открываю…» без конца.
            if (r is ServiceWeb.Route.Browser && !committed) {
                error = "Страница ушла на чужой адрес (${ServiceWeb.host(url) ?: url.take(60)}) — он открыт в браузере"
            }
            if (r == ServiceWeb.Route.Inside) loading = url
            return handle(r)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            error = null
            current = url
            if (url != null) loading = url
        }

        /**
         * Вход паролем браузера (HTTP Basic, панель VPN). Пароль приложение не
         * берёт ни при каком раскладе — отказ всегда. Просит хост страницы, которую
         * грузим, — она уходит в браузер (Chrome спросит пароль и запомнит), хост
         * запоминается: дальше — браузером сразу. Это сам сервис экрана или его
         * первая страница — экран закрывается; ссылка с другого сервиса («Дом» →
         * VPN) — остаёмся, где были.
         */
        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
            handler.cancel()
            val leave = ServiceWeb.basicAuthLeave(host, loading) ?: ServiceWeb.basicAuthLeave(host, current) ?: return
            val h = ServiceWeb.host(leave) ?: return
            // Второй запрос того же хоста (значок вкладки, повтор) — уже ушли в браузер, второй вкладки не надо.
            if (!leftHosts.add(h)) return
            view.stopLoading()
            rules = rules.withOutside(h)
            ServiceWebLinks.rememberBasic(view.context, h)
            openWeb(view.context, leave)
            if (!committed || h == ServiceWeb.host(page.url)) handedOff = true
            else Feedback.toast(view.context, "$h спрашивает пароль браузера — открыл в браузере", long = true)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            committed = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            canBack = view.canGoBack()
            if (url != null && !url.startsWith("data:")) current = url
            // Вход живёт куками: на диск сразу, а не когда WebView соберётся сам.
            CookieManager.getInstance().flush()
            // Кино: ссылка «Открыть Jellyfin» на странице несёт адрес веба Jellyfin — запомнить на случай без приложения.
            val link = ServiceWeb.appLink(page.icon)
            if (link != null && link.webFallback) {
                view.evaluateJavascript(ServiceWeb.findIntentScript(link.pkg)) { r ->
                    ServiceWeb.jsString(r)?.let(ServiceWeb::parseIntent)?.fallback?.let { ServiceWebLinks.remember(view.context, link.pkg, it) }
                }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            canBack = view.canGoBack()
            if (url != null) current = url
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
            if (request.isForMainFrame) error = "${err.description} · код ${err.errorCode}"
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, err: SslError) {
            // Никогда не «продолжить»: страница с чужим сертификатом получила бы куки входа.
            handler.cancel()
            error = "Сертификат не прошёл проверку (${sslWhy(err.primaryError)}) — страница не открыта"
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Без «true» система убила бы всё приложение вместе с диском и службой.
            gone = true
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, t: String?) {
            title = t.orEmpty()
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            // Прежний выбор (не дождались) — закрыть пустым, иначе поле на странице зависнет.
            pendingFiles?.onReceiveValue(null)
            pendingFiles = callback
            val intent = runCatching { params.createIntent() }.getOrNull()?.apply {
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            val launched = intent != null && pickFiles?.invoke(intent) == true
            if (!launched) {
                pendingFiles = null
                callback.onReceiveValue(null)
                Feedback.toast(view.context, "Выбор файла не открылся")
            }
            return true
        }
    }

    /** Ответ системного выбора файла — странице; отмена — пусто. */
    fun filesPicked(resultCode: Int, data: Intent?) {
        val cb = pendingFiles ?: return
        pendingFiles = null
        val uris: Array<Uri>? = if (resultCode != Activity.RESULT_OK || data == null) null else {
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) Array(clip.itemCount) { clip.getItemAt(it).uri }
            else data.data?.let { arrayOf(it) }
        }
        cb.onReceiveValue(uris)
    }

    companion object {
        fun create(host: Context, page: ServicePage, rules: ServiceWeb.Rules, dark: Boolean, bg: Int, start: String): ServiceWebPage {
            val p = ServiceWebPage(page, MutableContextWrapper(host), rules)
            p.build(dark, bg, start)
            return p
        }

        private fun sslWhy(code: Int): String = when (code) {
            SslError.SSL_EXPIRED -> "истёк"
            SslError.SSL_NOTYETVALID -> "ещё не действует"
            SslError.SSL_IDMISMATCH -> "чужое имя"
            SslError.SSL_UNTRUSTED -> "не доверен"
            SslError.SSL_DATE_INVALID -> "дата"
            else -> "код $code"
        }
    }
}

/**
 * Живые страницы сервисов на процесс: последние [KEEP] — вернуться во
 * «Встречи» из «Сегодня» мгновенно и на том же месте. Больше не держим:
 * каждая страница — память процесса отрисовки.
 */
internal object ServiceWebPool {
    private const val KEEP = 3
    private val pages = LinkedHashMap<String, ServiceWebPage>()

    /** Страница сервиса [page]: живая — она же (с новыми правилами), нет — новая с адреса [start] или главной. */
    fun obtain(host: Context, page: ServicePage, rules: ServiceWeb.Rules, dark: Boolean, bg: Int, start: String?): ServiceWebPage {
        val key = page.url
        pages.remove(key)?.let { old ->
            if (!old.gone) {
                old.rules = rules
                pages[key] = old
                return old
            }
            old.destroy()
        }
        val from = start?.takeIf { rules.inside(it) } ?: page.url
        val p = ServiceWebPage.create(host, page, rules, dark, bg, from)
        pages[key] = p
        while (pages.size > KEEP) pages.remove(pages.keys.first())?.destroy()
        return p
    }

    /**
     * Выбросить страницу (умер процесс отрисовки, сервис ушёл в браузер) — следующий [obtain] создаст новую.
     * [only] — только эту: за время отложенного выброса под тем же ключом могла встать новая.
     */
    fun drop(key: String, only: ServiceWebPage? = null) {
        if (only != null && pages[key] !== only) return
        pages.remove(key)?.destroy()
    }
}

/** Ссылки со страницы, которые WebView не открывает сам: приложения, загрузки, вход паролем браузера. */
internal object ServiceWebLinks {

    private const val PREFS = "service-web"

    /**
     * `intent://…#Intent;…;end` — как Chrome: приложение по пакету, только
     * открываемые из браузера экраны (без явного компонента и селектора);
     * нет приложения — `browser_fallback_url` по правилам ([inside] — свой
     * адрес грузится на месте, чужой — браузером).
     */
    fun openIntent(ctx: Context, url: String, inside: (String) -> Unit) {
        val link = ServiceWeb.parseIntent(url)
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
            if (ctx.activity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent != null && start(ctx, intent)) return
        val fb = link?.fallback
        when {
            fb != null -> inside(fb)
            link?.pkg != null -> Feedback.toast(ctx, "Нет приложения ${appName(link.pkg)} на телефоне")
            else -> Feedback.toast(ctx, "Ссылка не открылась: ${url.take(80)}")
        }
    }

    /** Своя схема (`slushalka://`), `tel:`, `mailto:`, `geo:` — системе; нечем открыть — словами. */
    fun openOther(ctx: Context, url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme.orEmpty().lowercase()
        val intent = (if (scheme == "mailto") Intent(Intent.ACTION_SENDTO, uri) else Intent(Intent.ACTION_VIEW, uri)).apply {
            if (ctx.activity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (start(ctx, intent)) return
        Feedback.toast(ctx, if (scheme == "slushalka") "Слушалки на телефоне нет" else "Нечем открыть «$scheme:» — нет приложения")
    }

    /**
     * Приложение из шапки сервиса (Jellyfin, Слушалка). Нет его — у Jellyfin
     * веб: ссылка «Открыть Jellyfin» со страницы Кино (`browser_fallback_url`),
     * найденная сейчас или запомненная раньше; адреса в коде нет.
     */
    fun openApp(ctx: Context, link: ServiceWeb.AppLink, web: ServiceWebPage?) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(link.pkg)
        if (launch != null && start(ctx, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
        if (!link.webFallback) {
            Feedback.toast(ctx, "${link.label}: приложения на телефоне нет")
            return
        }
        val fallback = { found: String? ->
            val u = found ?: remembered(ctx, link.pkg)
            if (u == null) Feedback.toast(ctx, "Приложения ${link.label} нет, а ссылки на его веб на странице не нашлось", long = true)
            else if (web != null) web.go(u) else openWeb(ctx, u)
        }
        if (web == null) return fallback(null)
        web.view.evaluateJavascript(ServiceWeb.findIntentScript(link.pkg)) { r ->
            val found = ServiceWeb.jsString(r)?.let(ServiceWeb::parseIntent)?.fallback
            if (found != null) remember(ctx, link.pkg, found)
            fallback(found)
        }
    }

    /**
     * Загрузка со страницы — системным загрузчиком в «Загрузки», с куками
     * входа сервиса (иначе файл за входом придёт страницей входа).
     * `blob:` и `data:` загрузчик не умеет — словами, с выходом в браузер.
     */
    fun download(ctx: Context, url: String, ua: String?, disposition: String?, mime: String?) {
        val scheme = url.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") {
            Feedback.toast(ctx, "Этот файл страница собирает сама ($scheme:) — открой страницу в браузере и скачай там", long = true)
            return
        }
        runCatching {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                if (!ua.isNullOrBlank()) addRequestHeader("User-Agent", ua)
                if (!mime.isNullOrBlank()) setMimeType(mime)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            name
        }.onSuccess { name -> Feedback.toast(ctx, "Скачиваю «$name» — в «Загрузки»") }
            .onFailure { e -> Feedback.toast(ctx, "Загрузка не началась: ${e.javaClass.simpleName}: ${e.message}", long = true) }
    }

    /** Запомнить веб приложения (адрес со страницы сервиса) — в закрытых настройках устройства, не в коде. */
    fun remember(ctx: Context, pkg: String, url: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString("fallback.$pkg", null) != url) p.edit().putString("fallback.$pkg", url).apply()
    }

    private fun remembered(ctx: Context, pkg: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("fallback.$pkg", null)

    /**
     * Хост входит паролем браузера (HTTP Basic): запомнить на устройстве, когда это выяснилось
     * ([ServiceWeb.basicAuthLeave]). Только хост и время — ни пароля, ни имени: их знает один Chrome.
     */
    fun rememberBasic(ctx: Context, host: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(BASIC + host.lowercase(), System.currentTimeMillis()).apply()
    }

    /** Хосты, которые открываются браузером сразу: вход паролем браузера, выяснено не дольше месяца назад. */
    fun basicHosts(ctx: Context): Set<String> {
        val all = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        val saved = all.entries.filter { it.key.startsWith(BASIC) }
            .mapNotNull { e -> (e.value as? Long)?.let { e.key.removePrefix(BASIC) to it } }.toMap()
        return ServiceWeb.freshHosts(saved, System.currentTimeMillis())
    }

    private const val BASIC = "basic."

    private fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun appName(pkg: String): String = when (pkg) {
        ServiceWeb.JELLYFIN_PACKAGE -> "Jellyfin"
        ServiceWeb.SLUSHALKA_PACKAGE -> "Слушалка"
        else -> pkg
    }
}

/** Активити под контекстом (в том числе под `MutableContextWrapper` страницы); нет — null. */
internal fun Context.activity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}
