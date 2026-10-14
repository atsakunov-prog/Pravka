package ru.zf.pravka

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.zf.pravka.core.ServicePage
import ru.zf.pravka.core.ServiceWeb
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.HeaderIcon
import ru.zf.pravka.ui.KovchegHeader
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvGlyphs
import ru.zf.pravka.ui.KvSmallButton
import ru.zf.pravka.ui.LocalKovcheg
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.Look

// Экран «страница сервиса» (14.10.2026, владелец: «две копии одного и того же»
// — одно приложение): Встречи, Шаббат, Дети, Книги, Кино, Дом, VPN открываются
// внутри Правки с шапкой Ковчега, а не вкладкой браузера. Слева значок Ковчега
// (меню всех сервисов), название сервиса и заголовок страницы; справа —
// приложение сервиса (Jellyfin у Кино, Слушалка у Книг), «обновить» и
// «открыть в браузере». Системное «назад» — сначала история страницы, потом
// закрыть экран. Сам WebView и его правила — `ServiceWebView.kt` и
// `core/ServiceWeb.kt`; что внутри — только адреса сервисов из синка Дел.

/** Сохранить открытую страницу сервиса в состоянии экрана: складывание Fold пересоздаёт активити. */
internal val ServicePageSaver: Saver<ServicePage?, ArrayList<String>> = Saver(
    save = { p -> p?.let { arrayListOf(it.name, it.icon, it.url) } },
    restore = { ServicePage.of(it) },
)

/**
 * Открыть сервис: внутри приложения ([inside]) или — настройкой «Сервисы —
 * в браузере» (на случай, если внутри что-то не работает) и в «Виде 4.0» —
 * вкладкой браузера, как было до 14.10.2026. Сервис, который входит паролем
 * браузера (HTTP Basic — панель VPN; устройство это помнит месяц), — тоже
 * браузером сразу: внутри приложения пароль не спросить, не пропустив его через наш код.
 */
internal fun openService(context: Context, page: ServicePage, inBrowser: Boolean, inside: (ServicePage) -> Unit) {
    val basic = ServiceWeb.host(page.url)?.let { it in ServiceWebLinks.basicHosts(context) } == true
    if (inBrowser || Look.v4 || basic) openWeb(context, page.url) else inside(page)
}

/** Страница сервиса целиком: шапка Ковчега, тонкая полоска загрузки акцентом, сама страница. */
@Composable
internal fun ServiceWebScreen(app: PravkaApp, page: ServicePage, onClose: () -> Unit) {
    val context = LocalContext.current
    val nav by app.delaSync.services.collectAsState()
    val link by app.delaSync.link.collectAsState()
    // Внутри — только сервисы из синка и их домен; адрес открытой страницы — тоже (Шаббат с плитки «Сегодня»).
    // Кроме тех, что входят паролем браузера (HTTP Basic): ссылка на них со страницы («Дом» → VPN) — браузером.
    val rules = remember(nav, link, page.url) {
        ServiceWeb.rules(nav, link?.url, page.url, outside = ServiceWebLinks.basicHosts(context))
    }
    val appBtn = ServiceWeb.appLink(page.icon)
    // Где была страница — переживает и смерть процесса: вернёмся туда же, а не на главную сервиса.
    var lastUrl by rememberSaveable(page.url) { mutableStateOf<String?>(null) }
    // Процесс отрисовки WebView умер — новая страница (номер поколения в ключе).
    var gen by remember(page.url) { mutableIntStateOf(0) }
    val preview = LocalInspectionMode.current
    val bg = Kv.bg.toArgb()
    val web = remember(page.url, gen) {
        if (preview) null else ServiceWebPool.obtain(context, page, rules, Look.dark, bg, lastUrl)
    }
    if (web != null) {
        LaunchedEffect(web.gone) {
            if (web.gone) {
                ServiceWebPool.drop(page.url)
                gen++
            }
        }
        // Сам сервис входит паролем браузера (панель VPN): страница уже ушла в браузер — экран закрыть, а пустую
        // страницу выбросить из пула, когда экран её отпустит (сразу — WebView ещё на экране, гаснет растворением).
        LaunchedEffect(web.handedOff) {
            if (web.handedOff) {
                Feedback.toast(context, "«${page.name}» спрашивает пароль браузера — открыл в браузере; дальше он сразу там", long = true)
                onClose()
                web.view.postDelayed({ ServiceWebPool.drop(page.url, web) }, 1_000)
            }
        }
        // Новые правила из синка — с хостами «пароль браузера», выясненными этой страницей по ходу.
        LaunchedEffect(rules) { web.rules = web.rules.outside.fold(rules) { r, h -> r.withOutside(h) } }
        LaunchedEffect(web.current) { web.current?.let { lastUrl = it } }
        // Тема приложения → кука темы и сама страница (общий слой Ковчега переключается без перезагрузки).
        val dark = Look.dark
        LaunchedEffect(web, dark) { web.theme(dark) }
        FilePicker(web)
        WebLifecycle(web)
    }
    // «Назад»: открытое окно страницы (карточка книги), потом история страницы, потом закрыть. Пока открыто меню
    // Ковчега — его «назад», не наше.
    val menuOpen = LocalKovcheg.current?.isOpen == true
    BackHandler(enabled = !menuOpen) {
        val w = web
        if (w != null) w.back(onClose) else onClose()
    }

    val title = web?.title?.trim().orEmpty()
    Column(Modifier.fillMaxSize().background(Kv.bg)) {
        KovchegHeader(
            title = page.name,
            // Заголовок страницы — вторым тоном, если он говорит больше названия сервиса («Встреча с …»).
            subtitle = title.takeIf { it.isNotEmpty() && !it.equals(page.name, ignoreCase = true) && !it.startsWith("http") },
            modifier = Modifier.statusBarsPadding(),
            actions = {
                if (appBtn != null) {
                    KvSmallButton(
                        appBtn.label,
                        onClick = { ServiceWebLinks.openApp(context, appBtn, web) },
                        soft = true,
                        icon = KvGlyphs.byKey(appBtn.glyph),
                        modifier = Modifier.padding(end = 2.dp),
                    )
                }
                HeaderIcon(KvGlyphs.byKey("refresh")!!, "обновить страницу", onClick = { web?.retry() })
                HeaderIcon(KvGlyphs.byKey("globe")!!, "открыть в браузере", onClick = { openWeb(context, web?.current ?: page.url) })
            },
        )
        // Полоска загрузки — тонкой линией акцента под шапкой; место под неё есть всегда, страница не прыгает.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            val p = web?.progress ?: 0
            if (web != null && p in 1..99) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p / 100f).background(Kv.accent))
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Ключ — сама страница: умер процесс отрисовки — новая страница встаёт новым видом, а не в старый.
            if (web != null) key(web) {
                AndroidView(
                    factory = { ctx -> web.show(ctx) },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { web.hide(context) },
                )
            }
            val err = web?.error
            when {
                err != null -> LoadError(page, err, onRetry = { web?.retry() }, onBrowser = { openWeb(context, web?.current ?: page.url) })
                web == null || !web.committed -> Opening(page)
            }
        }
    }
}

/** Системный выбор файла для `<input type=file>`: результат — странице, даже если Fold сложили посреди выбора. */
@Composable
private fun FilePicker(web: ServiceWebPage) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        web.filesPicked(r.resultCode, r.data)
    }
    DisposableEffect(web, launcher) {
        web.pickFiles = { intent -> runCatching { launcher.launch(intent) }.isSuccess }
        onDispose { if (web.pickFiles != null) web.pickFiles = null }
    }
}

/**
 * Страница засыпает и просыпается вместе с приложением; уходя в фон — дописывает куки входа на диск;
 * вернулись через долгое время — перечитывается (правка сервера видна сразу).
 */
@Composable
private fun WebLifecycle(web: ServiceWebPage) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, web) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> web.wake()
                Lifecycle.Event.ON_PAUSE -> web.sleep()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

/** Пока первый кадр страницы не показан — значок сервиса и «Открываю…», а не пустое поле. */
@Composable
private fun Opening(page: ServicePage) {
    val t = LocalPravkaType.current
    Column(
        Modifier.fillMaxSize().background(Kv.bg).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(KvGlyphs.byKey(page.icon) ?: KvGlyphs.byKey("globe")!!, null, tint = Kv.check, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text("Открываю «${page.name}»…", style = t.body, color = Kv.faint, textAlign = TextAlign.Center)
    }
}

/** Главная загрузка не удалась: причина целиком, «Повторить» и «В браузере». */
@Composable
private fun LoadError(page: ServicePage, error: String, onRetry: () -> Unit, onBrowser: () -> Unit) {
    val t = LocalPravkaType.current
    Box(Modifier.fillMaxSize().background(Kv.bg).padding(20.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(KvGlyphs.byKey("alert")!!, null, tint = Kv.warn, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text("«${page.name}» не открылся", style = t.valueS, color = Kv.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(error, style = t.meta, color = Kv.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KvSmallButton("Повторить", onClick = onRetry, soft = true, icon = KvGlyphs.byKey("refresh"))
                KvSmallButton("В браузере", onClick = onBrowser, icon = KvGlyphs.byKey("globe"))
            }
        }
    }
}
