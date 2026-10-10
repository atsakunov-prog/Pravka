package ru.zf.pravka

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.WhereBeacon
import ru.zf.pravka.core.WherePolicy
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.bevel

// Вкладка «Где мы» (Ещё → Где мы, 10.10.2026): семья на карте аватарами,
// под картой — кто где строкой, сверху — «показать всех» и «обновить».
// Делиться — согласие этого телефона (шестерёнка в шапке); смотреть может
// любой телефон семьи с подключённым облаком. Логика — `data/WhereSync.kt`,
// решения — `core/WherePolicy.kt`, карта — `WhereMap.kt`, спецификация —
// `docs/gde.md`.

@Composable
internal fun WhereTab(
    app: PravkaApp,
    serviceEnabled: Boolean,
    settingsRequested: Boolean,
    onSettingsHandled: () -> Unit,
    onOpenCloud: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by app.where.state.collectAsState()
    val profile by app.profileStore.flow.collectAsState()
    val cloud by app.homeServer.saved.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var me by remember { mutableStateOf("") }
    var battery by remember { mutableStateOf(-1 to false) }
    var fitKey by remember { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var focusN by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }

    LaunchedEffect(settingsRequested) {
        if (settingsRequested) {
            sheet = true
            onSettingsHandled()
        }
    }

    // Пока вкладка на экране — чужие точки свежие: обмен раз в 45 секунд,
    // после своей просьбы «обновить» — чаще, чтобы ответы было видно сразу.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        me = withContext(Dispatchers.IO) { app.where.myDevice() }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                battery = app.locator.battery()
                app.where.refresh()
                now = System.currentTimeMillis()
                val fresh = now - app.where.state.value.myAskAt < 6 * 60_000L
                delay(if (fresh) 15_000L else 45_000L)
            }
        }
    }

    // Люди на карте: сам (если делюсь) и все, кто делится.
    val people: List<WhereBeacon> = remember(st, profile, me, battery) {
        val mine = st.own?.takeIf { st.sharing && me.isNotBlank() }?.let { fix ->
            WhereBeacon(
                device = me,
                person = profile?.id ?: "user",
                name = profile?.name ?: "Я",
                fix = fix,
                battery = battery.first,
                charging = battery.second,
                moving = WherePolicy.moving(System.currentTimeMillis(), st.motionAt),
                place = "",
                sentAt = st.sentAt,
            )
        }
        listOfNotNull(mine) + st.others.values.sortedBy { it.name }
    }

    // Аватары с диска — на своём потоке; ключ — время файла.
    val faces = remember { mutableStateMapOf<String, Pair<Long, Bitmap?>>() }
    LaunchedEffect(people.map { it.device }, st.syncedAt, st.avatarAt) {
        withContext(Dispatchers.IO) {
            for (b in people) {
                val f = app.where.avatar(b.device)
                val stamp = f?.lastModified() ?: 0L
                if (faces[b.device]?.first == stamp) continue
                faces[b.device] = stamp to loadAvatar(f)
            }
        }
    }

    val pins = people.map { b ->
        WherePin(
            device = b.device,
            name = b.name,
            color = WherePolicy.color(b.person),
            lat = b.fix.lat,
            lon = b.fix.lon,
            acc = b.fix.acc,
            alpha = WherePolicy.alpha(now, WherePolicy.seenAt(b)),
            avatar = faces[b.device]?.second,
        )
    }

    val askNow: () -> Unit = {
        if (!asking) {
            asking = true
            scope.launch {
                val ok = app.where.ask()
                asking = false
                Feedback.toast(
                    app,
                    if (ok) "Попросил всех обновить точки — телефоны ответят в течение пяти минут"
                    else "Не вышло попросить: " + app.where.state.value.error.ifBlank { "нет связи с облаком" },
                    long = true,
                )
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val shape = RoundedCornerShape(22.dp)
        Box(Modifier.fillMaxWidth().weight(1f).clip(shape).bevel(shape)) {
            WhereMap(
                pins = pins,
                tiles = WhereTiles.of(st.tiles),
                fitKey = fitKey,
                focus = focus,
                onPinTap = { d -> focusN++; focus = d to focusN },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.align(Alignment.TopEnd).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MapKey(Glyphs.Family, "показать всех") { fitKey++ }
                MapKey(Glyphs.Refresh, "обновить точки", busy = asking, enabled = cloud != null, onClick = askNow)
            }
            Text(
                "© OpenStreetMap",
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (pins.isEmpty()) {
                EmptyMapNote(
                    when {
                        cloud == null -> "Карта семьи ездит через облако семьи — подключи его, и здесь появятся все, кто делится."
                        !st.sharing && st.others.isEmpty() -> "Пока никто не делится. Включи у себя — шестерёнка сверху — и попроси того же Марианну."
                        st.sharing && st.own == null -> "Ищу свою точку…"
                        else -> "Пока никто, кроме тебя, не делится."
                    },
                    Modifier.align(Alignment.Center),
                )
            }
        }

        PaperCard(
            label = "кто где",
            trailing = {
                GlyphButton(Glyphs.Gear, "моя точка", onClick = { sheet = true })
            },
        ) {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                if (cloud == null) {
                    PaperHint("Облако семьи не подключено.")
                    Spacer(Modifier.size(8.dp))
                    PaperButton("Подключить облако семьи", onOpenCloud, icon = Glyphs.Cloud, primary = true)
                }
                people.forEachIndexed { i, b ->
                    if (i > 0) RowRule()
                    PersonRow(
                        b = b,
                        mine = b.device == me,
                        now = now,
                        face = faces[b.device]?.second,
                        answered = st.myAskAt > 0L && now - st.myAskAt < 10 * 60_000L && b.device != me,
                        askAt = st.myAskAt,
                        onClick = { focusN++; focus = b.device to focusN },
                        onRoute = { route(context, b) },
                    )
                }
                if (!st.sharing && cloud != null) {
                    if (people.isNotEmpty()) RowRule()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ты не делишься", style = MaterialTheme.typography.bodyMedium)
                            PaperHint("тебя нет на картах семьи")
                        }
                        PaperButton("Делиться", { sheet = true }, icon = Glyphs.Place)
                    }
                }
                val status = when {
                    cloud == null -> ""
                    st.error.isNotBlank() -> st.error
                    st.syncedAt > 0L -> "Облако: обмен " + WherePolicy.ago(now, st.syncedAt)
                    else -> ""
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    PaperHint(status, color = if (st.error.isNotBlank()) ru.zf.pravka.ui.Ink.Warn else null)
                }
            }
        }
    }

    if (sheet) WhereSettingsSheet(app, serviceEnabled, onOpenCloud = { sheet = false; onOpenCloud() }, onDismiss = { sheet = false })
}

/** Круглая клавиша поверх карты: тёмное стекло, белый значок. */
@Composable
private fun MapKey(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(0xCC15171C))
            .bevel(CircleShape)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        else Icon(icon, contentDescription = description, tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.35f), modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun EmptyMapNote(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.9f),
        modifier = modifier
            .padding(28.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun PersonRow(
    b: WhereBeacon,
    mine: Boolean,
    now: Long,
    face: Bitmap?,
    answered: Boolean,
    askAt: Long,
    onClick: () -> Unit,
    onRoute: () -> Unit,
) {
    val color = WherePolicy.color(b.person)
    val img = remember(face, b.name, color) { faceBitmap(face, b.name, color, 120).asImageBitmap() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color(color)),
            contentAlignment = Alignment.Center,
        ) {
            Image(img, contentDescription = b.name, modifier = Modifier.size(38.dp).clip(CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (mine) "${b.name} · это ты" else b.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val fresh = if (answered) (if (b.fix.at >= askAt) "обновил · " else "ждём ответа · ") else ""
            PaperHint(fresh + WherePolicy.line(now, b))
        }
        if (!mine) GlyphButton(Glyphs.Route, "маршрут к ${b.name}", onClick = onRoute)
    }
}

/**
 * Маршрут к человеку — в Яндекс Картах (ими семья и ездит), нет их — любой
 * карте через `geo:`. Свою карту маршрутов не строим.
 */
private fun route(context: Context, b: WhereBeacon) {
    val lat = String.format(Locale.US, "%.6f", b.fix.lat)
    val lon = String.format(Locale.US, "%.6f", b.fix.lon)
    val yandex = Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?rtext=~$lat,$lon&rtt=auto"))
        .setPackage("ru.yandex.yandexmaps")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(b.name)})"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(yandex)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(geo)
        } catch (_: ActivityNotFoundException) {
            Feedback.toast(context, "Нет приложения карт, чтобы построить маршрут")
        }
    }
}

/** Что мешает точке уходить — словами и кнопкой. */
private enum class WhereBlock { FINE, ALWAYS, SYSTEM, CLOUD, SERVICE }

/**
 * «Моя точка»: согласие делиться, что мешает, аватар, плитки и как это
 * устроено по батарее. Включение — через окно с тем, что именно увидит семья.
 */
@Composable
private fun WhereSettingsSheet(app: PravkaApp, serviceEnabled: Boolean, onOpenCloud: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by app.where.state.collectAsState()
    val cloud by app.homeServer.saved.collectAsState()
    var permTick by remember { mutableIntStateOf(0) }
    var consent by remember { mutableStateOf(false) }
    var me by remember { mutableStateOf("") }
    var face by remember { mutableStateOf<Bitmap?>(null) }
    val profile by app.profileStore.flow.collectAsState()

    LaunchedEffect(st.avatarAt) {
        withContext(Dispatchers.IO) {
            me = app.where.myDevice()
            face = loadAvatar(app.where.avatar(me))
        }
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permTick++
        app.where.nudge()
    }
    // Точное — только в паре с приблизительным (Android 12+), иначе окна не будет.
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
        app.where.nudge()
    }
    val fineAndCoarse = arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    // Вернулся из настроек («Разрешать всегда», геолокация) — пересчитать, что мешает.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                permTick++
                app.where.nudge()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { runCatching { avatarJpeg(context, uri) }.getOrNull() }
            if (jpeg == null) Feedback.toast(app, "Фото не прочиталось")
            else app.where.setAvatar(jpeg)
        }
    }

    val blockers = remember(permTick, cloud, serviceEnabled, st.sharing) {
        buildList {
            if (!app.locator.fine()) add(WhereBlock.FINE to "Нет доступа к местоположению")
            else if (!app.locator.always()) add(WhereBlock.ALWAYS to "Доступ «только при использовании» — из фона точка не уйдёт. Нужно «Разрешать всегда»")
            if (!app.locator.systemOn()) add(WhereBlock.SYSTEM to "Геолокация выключена в системе")
            if (cloud == null) add(WhereBlock.CLOUD to "Облако семьи не подключено — точке некуда ехать")
            if (!serviceEnabled) add(WhereBlock.SERVICE to "Служба Правки выключена — точка уходит, только пока открыта эта вкладка")
        }
    }

    PaperSheet(onDismiss = onDismiss, title = "Моя точка", icon = Glyphs.Place, subtitle = "что видит семья на карте") {
        PaperToggle(
            title = "Делиться местоположением",
            checked = st.sharing,
            onCheckedChange = { on ->
                if (on) consent = true
                else scope.launch { app.where.setSharing(false) }
            },
            hint = if (st.sharing) "семья видит тебя на карте" else "тебя нет на картах семьи",
            info = HOW_IT_WORKS,
        )
        if (st.sharing || consent) for ((fix, text) in blockers) {
            Text("⚠ $text", style = MaterialTheme.typography.bodySmall, color = ru.zf.pravka.ui.Ink.Warn)
            when (fix) {
                WhereBlock.FINE -> PaperButton("Дать доступ", { askLocation.launch(fineAndCoarse) }, icon = Glyphs.Place)
                WhereBlock.ALWAYS -> PaperButton("Открыть разрешения Правки", {
                    // На Android 11+ системного окна для «всегда» нет — только экран приложения.
                    if (Build.VERSION.SDK_INT == 29) askPermission.launch("android.permission.ACCESS_BACKGROUND_LOCATION")
                    else {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        Feedback.toast(app, "Разрешения → Местоположение → «Разрешать всегда»", long = true)
                    }
                }, icon = Glyphs.Key)
                WhereBlock.SYSTEM -> PaperButton("Включить геолокацию", {
                    context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, icon = Glyphs.Place)
                WhereBlock.CLOUD -> PaperButton("Подключить облако семьи", onOpenCloud, icon = Glyphs.Cloud)
                WhereBlock.SERVICE -> Unit
            }
        }
        if (st.sharing) {
            val own = st.own
            PaperHint(
                if (own == null) "Своей точки ещё нет — ищу."
                else "Точка " + WherePolicy.ago(System.currentTimeMillis(), own.at) + " · " + WherePolicy.accuracy(own.acc) +
                    " · " + sourceName(own.src) +
                    (if (st.sentAt > 0L) " · ушла в облако " + WherePolicy.ago(System.currentTimeMillis(), st.sentAt) else " · в облако ещё не ушла")
            )
            if (!app.locator.hasMotionSensor()) {
                PaperHint("У телефона нет датчика движения: в пути точка обновляется по чужому GPS и раз в час.")
            }
        }

        RowRule()
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = WherePolicy.color(profile?.id ?: "user")
            val img = remember(face, profile, color) { faceBitmap(face, profile?.name ?: "Я", color, 120).asImageBitmap() }
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color(color)), contentAlignment = Alignment.Center) {
                Image(img, contentDescription = "аватар", modifier = Modifier.size(45.dp).clip(CircleShape))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Аватар на карте", style = MaterialTheme.typography.bodyMedium)
                PaperHint(if (face == null) "пока буква — выбери фото" else "видят все, у кого карта")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton("Выбрать фото", {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, icon = Glyphs.Image)
            if (face != null) PaperButton("Убрать", { scope.launch { app.where.clearAvatar() } }, icon = Glyphs.Delete)
        }

        RowRule()
        Text("Карта", style = MaterialTheme.typography.bodyMedium)
        ChipRow {
            val dark = WhereTiles.of(st.tiles) == WhereTiles.DARK
            PaperChip("Тёмная", selected = dark, onClick = { app.where.setTiles(WhereTiles.DARK.name) })
            PaperChip("Светлая", selected = !dark, onClick = { app.where.setTiles(WhereTiles.LIGHT.name) })
        }
        PaperHint("Обе — OpenStreetMap, без ключей; тёмная — наш фильтр поверх.")
    }

    if (consent) {
        PaperAlert(
            onDismiss = { consent = false },
            title = "Делиться местоположением?",
            icon = Glyphs.Family,
            subtitle = "семья увидит тебя на карте",
            confirm = SheetAction("Делиться", icon = Glyphs.Place) {
                consent = false
                if (!app.locator.fine()) askLocation.launch(fineAndCoarse)
                scope.launch { app.where.setSharing(true) }
            },
            dismiss = SheetAction("Не сейчас") { consent = false },
        ) {
            PaperHint(
                "Что увидят: точку на карте с твоим аватаром, насколько она точна, когда обновлялась, " +
                    "заряд телефона и место по Wi-Fi из автопилота («Дом», «Летово»)."
            )
            PaperHint(
                "Кто увидит: все телефоны семьи, подключённые к тому же облаку (домашний сервер). Больше никто — " +
                    "точка лежит только там, история не копится, хранится одна последняя."
            )
            PaperHint(HOW_IT_WORKS)
            PaperHint("Выключить можно здесь же в любой момент — точка сразу исчезнет с карт семьи.")
        }
    }
}

private const val HOW_IT_WORKS =
    "Как бережём батарею. Лежит телефон — точка раз в час по Wi-Fi и вышкам, GPS не включается. " +
        "Пошёл или поехал — это замечает датчик движения (он в железе и батарею не ест), и пока едешь, точка — раз в пять минут, тоже без GPS. " +
        "Открыты Яндекс Карты или навигатор — их GPS-точки достаются даром. " +
        "Свой GPS включается, только когда кто-то из семьи нажал «обновить», и один раз."

private fun sourceName(src: String): String = when (src) {
    "gps" -> "по GPS"
    "network" -> "по Wi-Fi и вышкам"
    "fused" -> "по Wi-Fi, вышкам и GPS"
    else -> src.ifBlank { "источник неизвестен" }
}

/**
 * Фото из галереи — в квадрат 256×256 JPEG. ImageDecoder сам поворачивает
 * снимок по EXIF (до Android 9 — как лежит в файле) и сразу уменьшает при
 * чтении: двенадцать мегапикселей в память целиком не нужны.
 */
private fun avatarJpeg(context: Context, uri: Uri): ByteArray {
    val target = 256
    val src: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val side = minOf(info.size.width, info.size.height)
            decoder.setTargetSampleSize((side / (target * 2)).coerceAtLeast(1))
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        val side = minOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
        val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = (side / (target * 2)).coerceAtLeast(1) }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o2) }
            ?: error("не читается")
    }
    val side = minOf(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(square, target, target, true)
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
    return out.toByteArray()
}
