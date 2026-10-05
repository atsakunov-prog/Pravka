package ru.zf.pravka

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DelaWebScript
import ru.zf.pravka.data.DelaSync
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.finishDelaReason
import ru.zf.pravka.trigger.listenForDelaReason
import ru.zf.pravka.trigger.onRaznoskaTap
import ru.zf.pravka.trigger.onRaznoskaText
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.VoiceInput

// Вкладка «Дела» — сам веб Дел внутри Правки (05.10.2026, владелец: «давай
// сделаем как веб версию, чтобы не мучаться»). Экраны, проекты, микрофоны,
// CRM — ровно те, что на ПК, и любая правка веба на сервере сразу здесь:
// второй копии экранов, которую надо догонять, больше нет.
//
// Что добавляет телефон поверх веба:
// - вход токеном устройства из QR (скрипт до начала страницы кладёт его в
//   запросы к своему адресу — `core/DelaWebScript.kt`), без бота;
// - голос Правки вместо распознавания браузера (мост `PravkaDela`): во
//   встроенном браузере Android `SpeechRecognition` нет;
// - пилюля «говори дела» сверху — та же Разноска, что у «Д»; после синка
//   телефона веб подтягивает новое сам;
// - «копия телефона» — прежние родные экраны (`DelaTab.kt`): они работают без
//   сети и открываются сами, если веб не загрузился, с причиной словами.

/** Что видно про веб: грузится, чем кончилась загрузка, есть ли куда «назад». */
internal class DelaWebState {
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
}

/**
 * Один веб на процесс: вкладку переключают часто, а перезагрузка — это полный
 * синк Дел в странице заново. Новый — если сменилась активность (пересоздание
 * окна) или адрес и токен (QR отсканирован заново).
 */
private object DelaWebHolder {
    var key: String = ""
    var activity: Activity? = null
    var view: WebView? = null
    val state = DelaWebState()
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Мост голоса: страница зовёт `PravkaDela.listen()` вместо распознавания
 * браузера; сказанное возвращается в неё `__pravkaSaid(text)`. Методы моста
 * идут не на главном потоке — служба и её окна живут на нём, поэтому всё — через
 * главный поток; `listen` ждёт ответа (стартовал ли тейк) не дольше 3 секунд.
 */
private class DelaWebBridge(private val app: PravkaApp, private val web: WebView) {
    private val main = Handler(Looper.getMainLooper())

    /**
     * ▶ — запись в Засечке из строки дела, как в копии телефона. Веб этой
     * кнопки пока не рисует: она для него, когда он увидит «PravkaPhone» в
     * user-agent (просьба сессии на ПК — `docs/status.md`). Дело — из копии
     * телефона: её Засечка и знает.
     */
    @JavascriptInterface
    fun play(taskId: String) {
        main.post {
            val t = app.delaStore.view.value.task(taskId)
            if (t == null) {
                Feedback.toast(app, "Дела ещё нет в копии телефона — обнови и нажми снова")
                return@post
            }
            app.appScope.launch {
                val entry = runCatching { app.zasechkaEngine.startTask(t) }.getOrNull()
                Feedback.toast(app, if (entry != null) "⏱ ${entry.title}" else "Не смог записать дело")
            }
        }
    }

    @JavascriptInterface
    fun listen(): Boolean {
        val ok = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        main.post {
            val service = PravkaAccessibilityService.instance
            if (service == null) {
                Feedback.toast(web.context, web.context.getString(R.string.toast_no_service))
            } else {
                ok.set(service.listenForDelaReason { said -> web.post { web.evaluateJavascript(DelaWebScript.said(said), null) } })
            }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return ok.get()
    }

    @JavascriptInterface
    fun finish(keep: Boolean) {
        main.post {
            val stopped = PravkaAccessibilityService.instance?.finishDelaReason(keep) == true
            // Отмена или стоп, которого уже не было, — странице всё равно нужно
            // «тейк кончился»: иначе её микрофон так и останется гореть.
            if (!keep || !stopped) web.evaluateJavascript(DelaWebScript.said(""), null)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun obtainWeb(app: PravkaApp, activity: Activity, link: DelaSync.Link, origin: String): WebView {
    val key = link.url + "\n" + link.token
    DelaWebHolder.view?.let { v ->
        if (DelaWebHolder.activity === activity && DelaWebHolder.key == key) {
            // Прошлый раз не открылся (метро, сеть) — новый заход во вкладку пробует снова.
            val st = DelaWebHolder.state
            if (st.error.isNotBlank()) {
                st.error = ""
                st.loading = true
                v.reload()
            }
            return v
        }
        (v.parent as? ViewGroup)?.removeView(v)
        v.destroy()
    }
    val st = DelaWebHolder.state
    st.loading = true
    st.error = ""
    st.canGoBack = false
    val web = WebView(activity)
    web.setBackgroundColor(Color.TRANSPARENT)
    with(web.settings) {
        javaScriptEnabled = true
        // Веб помнит вид, группировки и избранное в localStorage.
        domStorageEnabled = true
        setSupportZoom(false)
        // Веб узнаёт, что он внутри Правки (на будущее: ▶ в Засечку из строки дела).
        userAgentString = "$userAgentString PravkaPhone/1"
    }
    web.addJavascriptInterface(DelaWebBridge(app, web), DelaWebScript.BRIDGE)
    // Скрипт до начала страницы — только на адрес Дел: токен чужим не уходит.
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        st.loading = false
        st.error = "встроенный браузер старый — нет скрипта до начала страницы; обнови «Android System WebView» в Play"
    } else {
        WebViewCompat.addDocumentStartJavaScript(web, DelaWebScript.bootstrap(link.token), setOf(origin))
    }
    // Без этого alert/confirm/prompt веба молчат: «Почему проиграли?», «Убрать запись?».
    web.webChromeClient = WebChromeClient()
    web.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = DelaWebScript.origin(request.url.toString())
            if (target == origin) return false
            // Ссылка встречи, Telegram, чужой сайт — наружу, в свой браузер.
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            st.loading = false
            st.canGoBack = view.canGoBack()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            st.canGoBack = view.canGoBack()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            st.loading = false
            st.error = "веб Дел не открылся: ${error.description} (${error.errorCode})"
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!request.isForMainFrame) return
            st.loading = false
            st.error = "веб Дел ответил ${response.statusCode}" + (response.reasonPhrase?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") +
                if (response.statusCode == 404) " — страницы нет по этому адресу (посредник или старая служба)" else ""
        }
    }
    DelaWebHolder.activity = activity
    DelaWebHolder.key = key
    DelaWebHolder.view = web
    if (st.error.isBlank()) web.loadUrl(link.url)
    return web
}

/**
 * Веб Дел во вкладке. Сверху — пилюля «говори дела» (железное правило 3:
 * первой строкой каждой вкладки) и тонкая строка: что с вебом, «обновить» и
 * «копия телефона». «Назад» листает историю веба, пока есть куда.
 */
@Composable
internal fun DelaWebTab(app: PravkaApp, link: DelaSync.Link, onNative: () -> Unit, onFail: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context.activity()
    val origin = DelaWebScript.origin(link.url)
    if (activity == null || origin == null) {
        LaunchedEffect(Unit) { onFail(if (origin == null) "адрес Дел не похож на адрес: ${link.url}" else "нет окна для веба") }
        return
    }
    val web = remember(activity, link.url, link.token) { obtainWeb(app, activity, link, origin) }
    val st = DelaWebHolder.state
    // Веб не открылся (сеть, адрес, старый браузер) — во вкладку копия телефона с причиной.
    LaunchedEffect(st.error) { if (st.error.isNotBlank()) onFail(st.error) }
    val queued by app.delaStore.queued.collectAsState()
    val sync by app.delaSync.status.collectAsState()
    val ownerName = app.profileStore.flow.collectAsState().value?.name
    val talking = ru.zf.pravka.ui.rememberRouteBusy(app.liveWork, "raznoska")
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(link) {
        app.delaStore.load()
        // Телефону своя копия нужна и с вебом: ▶ в Засечке, каталог Разноски, очередь.
        runCatching { app.delaSync.tick() }
    }
    // Телефон отправил очередь (Разноска, ▶) и взял синк — веб подтягивает новое сам.
    LaunchedEffect(sync.lastOk) {
        if (sync.lastOk > 0) web.evaluateJavascript(DelaWebScript.REFRESH, null)
    }
    BackHandler(enabled = st.canGoBack) { web.goBack() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VoiceInput(
                value = draft,
                onValueChange = { draft = it },
                placeholder = if (talking) "Разбираю…" else ru.zf.pravka.core.PillHint.say(ownerName, "говори дела"),
                onSend = {
                    val text = draft.trim()
                    val service = PravkaAccessibilityService.instance
                    if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
                    else if (text.isNotEmpty()) {
                        draft = ""
                        service.onRaznoskaText(text)
                    }
                },
                onMic = {
                    val service = PravkaAccessibilityService.instance
                    if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
                    else service.onRaznoskaTap()
                },
                sendEnabled = draft.isNotBlank(),
                maxLines = 4,
                busy = talking,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val c = MaterialTheme.colorScheme
                val line = listOfNotNull(
                    when {
                        st.error.isNotBlank() -> st.error
                        st.loading -> "открываю веб Дел…"
                        else -> "веб Дел · " + origin.substringAfter("://")
                    },
                    queued.size.takeIf { it > 0 }?.let { "в очереди телефона $it — уйдут сами" },
                ).joinToString(" · ")
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (st.error.isNotBlank()) c.error else c.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                GlyphButton(Glyphs.Refresh, "обновить веб", onClick = { st.error = ""; st.loading = true; web.reload() }, size = 34.dp)
                GlyphButton(Glyphs.Phone, "копия телефона — работает без сети", onClick = onNative, size = 34.dp)
            }
        }
        AndroidView(
            factory = { (web.parent as? ViewGroup)?.removeView(web); web },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * «Дела»: веб, если он включён и телефон подключён к серверу; иначе — копия
 * телефона (родные экраны). Веб не открылся — копия с причиной и «открыть веб».
 */
@Composable
fun DelaTab(app: PravkaApp) {
    val webOn by app.settings.delaWebFlow.collectAsState(initial = true)
    val link by app.delaSync.link.collectAsState()
    var failed by remember { mutableStateOf("") }
    val scope = app.appScope
    val l = link
    if (webOn && l != null && failed.isBlank()) {
        DelaWebTab(
            app = app,
            link = l,
            onNative = { scope.launch { app.settings.setDelaWeb(false) } },
            onFail = { failed = it },
        )
    } else {
        DelaNativeTab(
            app = app,
            webNote = failed,
            onWeb = if (l != null) {
                {
                    failed = ""
                    DelaWebHolder.state.error = ""
                    // Не открылся — пробуем заново с нуля, а не тот же сломанный экран.
                    DelaWebHolder.view?.let { v -> (v.parent as? ViewGroup)?.removeView(v); v.destroy() }
                    DelaWebHolder.view = null
                    DelaWebHolder.key = ""
                    scope.launch { app.settings.setDelaWeb(true) }
                }
            } else null,
        )
    }
}
