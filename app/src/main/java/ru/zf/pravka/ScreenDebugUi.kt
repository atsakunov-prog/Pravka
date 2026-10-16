package ru.zf.pravka

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import kotlin.coroutines.resume
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.zf.pravka.core.DelaDebug
import ru.zf.pravka.core.ScreenDebug

// «Что не так?» на всех экранах (16.10.2026, docs/dela-phone-24.md): строка под шапкой любого экрана, кроме вкладки
// Дел (у неё своя, со снимком Дел). Ставит её шапка (`KovchegHeader`, в «Виде 4.0» — `TabHeader`) из
// `LocalScreenBar`, который `MainActivity` даёт каждому экрану с его маршрутом. Снимок — общий: то, что видит чтение с
// экрана (дерево семантики Compose), и текст страницы сервиса из WebView. Правила снимка — core/ScreenDebug.kt.

/**
 * Строка под шапкой экрана: та же `DebugBar`, что у Дел. [at] — маршрут и режим экрана из навигации, [title] и [sub] —
 * что написано в шапке (экран и его выбор: день, период, страница сервиса).
 */
@Composable
internal fun ScreenDebugBar(app: PravkaApp, at: ScreenDebug.At, title: String, sub: String?) {
    val view = LocalView.current
    // Вид, тема и разворот — в режим снимка: «на развороте в тёмной теме колонка съехала» без них не прочесть.
    val mode = mapOf(
        "look" to if (ru.zf.pravka.ui.Look.v2) "v2" else "4.0",
        "theme" to if (ru.zf.pravka.ui.Look.dark) "тёмная" else "светлая",
        "wide" to ru.zf.pravka.ui.twoPane(),
    )
    DebugBar(app, capture = { screenCapture(app, view, at, title, sub, mode) })
}

/**
 * Снимок общего экрана в миг «взялся за строку»: строки экрана из семантики — сразу (до клавиатуры), текст страницы
 * сервиса — тут же запрошен у WebView (ответ асинхронный, ждём его до записки не больше 2 с). Журнал, очередь Дел и
 * действия — при отправке.
 */
private fun screenCapture(
    app: PravkaApp,
    view: View,
    at: ScreenDebug.At,
    title: String,
    sub: String?,
    mode: Map<String, Any?>,
): DebugCapture {
    val read = runCatching { ScreenRead.rows(view) }
    val web: Deferred<ScreenDebug.Web?>? = ScreenRead.webView(view)?.let { w -> app.appScope.async { ScreenRead.web(w) } }
    return DebugCapture { version, events, now ->
        val page = web?.let { d -> withTimeoutOrNull(2_000L) { d.await() } }
        ScreenDebug.snapshot(
            at, title, sub, read.getOrDefault(emptyList()), page, mode, version, events,
            app.delaStore.queued.value.map { it.op },
            failed = read.exceptionOrNull()?.let { "строки экрана не прочлись: ${it.javaClass.simpleName}" }.orEmpty(),
        ) to DelaDebug.actions(
            DelaDebug.recentOps(), events, now, DelaDebug.recentNotes() + ScreenDebug.recentNav(), app.delaStore.view.value,
        )
    }
}

/**
 * Экран как его видит чтение с экрана: дерево семантики Compose окна, где стоит строка (`ViewRootForTest` — то же
 * дерево, по которому ходят снимки экранов и чтение с экрана; доступно без включённого TalkBack), и WebView страницы
 * сервиса. Только чтение, на главном потоке, по касанию строки — не подписка.
 */
internal object ScreenRead {

    /** Строки экрана по порядку (`ScreenDebug.rows`): узлы сливаются, как их читает TalkBack (строка с кнопкой — одна). */
    fun rows(view: View): List<ScreenDebug.Row> {
        val root = (view as? ViewRootForTest) ?: composeRoot(view.rootView) ?: return emptyList()
        val w = view.rootView.width.toFloat()
        val h = view.rootView.height.toFloat()
        val node = node(root.semanticsOwner.rootSemanticsNode, w, h, 0) ?: return emptyList()
        return ScreenDebug.rows(node)
    }

    private fun composeRoot(v: View): ViewRootForTest? = when (v) {
        is ViewRootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { composeRoot(v.getChildAt(it)) }
        else -> null
    }

    private fun node(n: SemanticsNode, w: Float, h: Float, depth: Int): ScreenDebug.Node? {
        val c = n.config
        // Сама строка отладки и спрятанное от чтения с экрана — не экран.
        if (c.getOrNull(SemanticsProperties.TestTag) == ScreenDebug.BAR_TAG) return null
        if (c.contains(SemanticsProperties.HideFromAccessibility)) return null
        if (!n.layoutInfo.isPlaced || depth > 80) return null
        val secret = c.contains(SemanticsProperties.Password) || c.getOrNull(SemanticsProperties.IsSensitiveData) == true
        val texts = buildList {
            c.getOrNull(SemanticsProperties.ContentDescription)?.let(::addAll)
            if (!secret) c.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
            if (secret) add("поле скрыто")
            else c.getOrNull(SemanticsProperties.EditableText)?.text?.let { add(if (it.isBlank()) "поле пустое" else "поле: «$it»") }
            c.getOrNull(SemanticsProperties.Error)?.let { add("ошибка: $it") }
        }
        val state = buildList {
            c.getOrNull(SemanticsProperties.ToggleableState)?.let {
                add(when (it) { ToggleableState.On -> "вкл"; ToggleableState.Off -> "выкл"; else -> "частично" })
            }
            if (c.getOrNull(SemanticsProperties.Selected) == true) add("выбрано")
            c.getOrNull(SemanticsProperties.StateDescription)?.let(::add)
            c.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let { p ->
                val span = p.range.endInclusive - p.range.start
                if (span > 0f) add("${((p.current - p.range.start) / span * 100).toInt()} %")
            }
            if (c.contains(SemanticsProperties.Heading)) add("заголовок")
            if (c.contains(SemanticsProperties.Disabled)) add("недоступно")
            if (c.getOrNull(SemanticsProperties.Focused) == true) add("в фокусе")
        }
        val pos = n.positionInWindow
        val size = n.size
        val clip = n.boundsInWindow
        val visible = clip.width > 0f && clip.height > 0f && clip.bottom > 0f && clip.top < h && clip.right > 0f && clip.left < w
        return ScreenDebug.Node(
            texts = texts,
            role = c.getOrNull(SemanticsProperties.Role)?.let(::roleWord).orEmpty(),
            state = state,
            top = pos.y,
            left = pos.x,
            bottom = pos.y + size.height,
            right = pos.x + size.width,
            visible = visible,
            children = n.children.mapNotNull { node(it, w, h, depth + 1) },
        )
    }

    private fun roleWord(r: Role): String = when (r) {
        Role.Button -> "кнопка"
        Role.Checkbox -> "флажок"
        Role.Switch -> "переключатель"
        Role.RadioButton -> "выбор"
        Role.Tab -> "вкладка"
        Role.Image -> "картинка"
        Role.DropdownList -> "список"
        Role.ValuePicker -> "выбор значения"
        Role.Carousel -> "карусель"
        else -> ""
    }

    /** WebView страницы сервиса на экране (видимый; пул держит и спрятанные), или null. */
    fun webView(view: View): WebView? {
        fun find(v: View): WebView? = when {
            v is WebView -> v.takeIf { it.isShown && it.width > 0 && it.height > 0 }
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(view.rootView)
    }

    /**
     * Текст страницы сервиса по блокам (`ScreenDebug.web`): адрес, заголовок, где прокручено, строки — текст блока и
     * был ли блок в окне; не больше 400 блоков и 60 тыс. знаков (граница снимка режет дальше). Скрипт только читает.
     */
    suspend fun web(w: WebView): ScreenDebug.Web? {
        val raw = withTimeoutOrNull(1_500L) {
            suspendCancellableCoroutine { c ->
                runCatching { w.evaluateJavascript(READ_JS) { r -> if (c.isActive) c.resume(r) } }
                    .onFailure { if (c.isActive) c.resume(null) }
            }
        }
        return ScreenDebug.web(raw) ?: ScreenDebug.Web(ScreenDebug.url(w.url.orEmpty()), w.title.orEmpty(), error = "текст страницы не прочёлся")
    }

    private val READ_JS = """
        (function(){try{
          var H=innerHeight,W=innerWidth,out=[],chars=0,cur=null,buf='',vis=false;
          function blk(e){while(e&&e!==document.body){var d=getComputedStyle(e).display;
            if(d!=='inline'&&d!=='inline-block'&&d!=='contents')return e;e=e.parentElement}return document.body}
          function flush(){var s=buf.replace(/\s+/g,' ').trim();if(s){out.push([s.slice(0,300),vis?1:0]);chars+=s.length}buf='';vis=false}
          var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
          while(w.nextNode()&&out.length<400&&chars<60000){
            var t=w.currentNode,s=t.nodeValue;if(!s||!s.trim())continue;
            var p=t.parentElement;if(!p)continue;var tag=p.tagName;
            if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT'||tag==='TEMPLATE')continue;
            var r=p.getBoundingClientRect();if(r.width===0&&r.height===0)continue;
            if(getComputedStyle(p).visibility==='hidden')continue;
            var b=blk(p);if(b!==cur){flush();cur=b}buf+=' '+s;
            if(r.bottom>0&&r.top<H&&r.right>0&&r.left<W)vis=true}
          flush();
          return JSON.stringify({url:location.href,title:document.title,y:Math.round(scrollY),
            h:document.documentElement.scrollHeight,vh:H,rows:out})
        }catch(e){return JSON.stringify({url:location.href,error:String(e)})}})()
    """.trimIndent()
}
