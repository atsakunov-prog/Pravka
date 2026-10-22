package ru.zf.pravka

import ru.zf.pravka.ui.fadingScroll

import ru.zf.pravka.core.Fmt

import androidx.compose.foundation.layout.imePadding

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.layout.PaddingValues

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.pravka.data.PhoneStore
import ru.zf.pravka.data.PhoneSweeper
import ru.zf.pravka.core.AutoPilotRules
import ru.zf.pravka.core.PlaceDeal
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.phoneDayKey
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.IconAction
import ru.zf.pravka.ui.IconActionRow
import ru.zf.pravka.ui.InfoButton
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperIconButton
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperSlider
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.scrollFade
import ru.zf.pravka.trigger.onZasechkaTap

// Вкладка «Засечка»: the owner's day as a ribbon of entries, the numbers he
// loves, and the knobs. Everything the buttons capture lands here for review
// and fixing - the tab is deliberately editable down to minutes, because the
// voice pipeline is fast but not sacred.

// Радуга владельца живёт в core/CategoryRainbow.kt: по ней сортируются итоги
// здесь и раскладывается донат дня в «Отчёте». Тут — только заливка под тему.
internal fun categoryHue(name: String): Float = ru.zf.pravka.core.CategoryRainbow.hue(name)

/**
 * Цвет категории — светофор цены её часа (`ui.categoryFill`, 10.10.2026:
 * «светофор нужно применить везде к категориям»): цена — из справочника
 * Засечки. Радуга (`CategoryRainbow`) осталась порядком итогов.
 */
@Composable
internal fun categoryColor(name: String): Color = ru.zf.pravka.ui.categoryFill(name)

/** Points a span of [ms] in a category worth [worth] per hour contributes. */
private fun pointsOf(worth: Int, ms: Long): Int =
    kotlin.math.round(worth * ms.toDouble() / 3_600_000.0).toInt()

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
/** Milliseconds -> whole minutes, rounded to nearest (never truncated). */
private fun msToMin(ms: Long): Long = (ms + 30_000L) / 60_000L

@Composable
internal fun ZasechkaSettings(app: PravkaApp) {
    // Настройки открываются и БЕЗ захода в Засечку — стор мог быть не прочитан,
    // и редактор категорий показал бы пустоту. Отредактировать пустоту и
    // сохранить — значит затереть настоящие категории; загрузка обязана
    // случиться раньше.
    LaunchedEffect(Unit) { app.zasechkaStore.all() }
    val categories by app.zasechkaStore.categoriesFlow.collectAsState()
    val clients by app.zasechkaStore.clientsFlow.collectAsState()

    val gapMin by app.settings.zGapMinFlow.collectAsState(initial = 45)
    val dayStartH by app.settings.zDayStartFlow.collectAsState(initial = 9)
    val dayEndH by app.settings.zDayEndFlow.collectAsState(initial = 23)
    val checkins by app.settings.zCheckinsFlow.collectAsState(initial = true)
    val quiet by app.zQuietFlow.collectAsState(initial = false)
    // Ползунки пишут по отпусканию: запись DataStore на каждый шаг
    // перетаскивания — десятки записей файла за секунду.
    var gapSlider by remember(gapMin) { mutableStateOf(gapMin.toFloat()) }
    var startSlider by remember(dayStartH) { mutableStateOf(dayStartH.toFloat()) }
    var endSlider by remember(dayEndH) { mutableStateOf(dayEndH.toFloat()) }

    Column(verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap)) {
        PaperCard(label = "напоминания") {
            // Первым — одним тумблером всё сразу (08.10.2026): Марианне Засечка
            // нужна как журнал сна и тренировок, а не как собеседник.
            PaperToggle(
                title = "Засечка молча",
                checked = quiet,
                onCheckedChange = { app.appScope.launch { app.settings.setZQuiet(it) } },
                hint = if (quiet) "сон, тренировки и экран пишутся сами — без вопросов" else null,
                info = "Лента пишется сама: сон по зарядке и экрану, сон и тренировки с часов через " +
                    "intervals, экран по дням. Ни напоминаний о дырах, ни «всё ещё …?», ни вопросов " +
                    "автопилота, ни дел за тебя — по подъёму («сборы детей»), звонку или календарю. " +
                    "С завода молчит у всех, кроме Саши.",
            )
            val gapShown = (gapSlider / 15f).roundToInt() * 15
            PaperSlider(
                title = "Дыра во времени",
                valueText = if (gapShown > 0) "через $gapShown мин" else "выключено",
                value = gapSlider,
                onValueChange = { gapSlider = it },
                onValueChangeFinished = {
                    app.appScope.launch { app.settings.setZGapMin((gapSlider / 15f).roundToInt() * 15) }
                },
                valueRange = 0f..120f,
                steps = 7,
            )
            PaperSlider(
                title = "День начинается",
                valueText = "${startSlider.roundToInt()}:00",
                value = startSlider,
                onValueChange = { startSlider = it },
                onValueChangeFinished = { app.appScope.launch { app.settings.setZDayStart(startSlider.roundToInt()) } },
                valueRange = 0f..12f,
                steps = 11,
                info = "Активные часы: утром кнопка спрашивает «день начался?», после конца — «закрыть день?». " +
                    "Вне этих часов о дырах не напоминает.",
            )
            PaperSlider(
                title = "День кончается",
                valueText = "${endSlider.roundToInt()}:00",
                value = endSlider,
                onValueChange = { endSlider = it },
                onValueChangeFinished = { app.appScope.launch { app.settings.setZDayEnd(endSlider.roundToInt()) } },
                valueRange = 12f..24f,
                steps = 11,
            )
            PaperToggle(
                title = "Спрашивать «всё ещё …?»",
                checked = checkins,
                onCheckedChange = { app.appScope.launch { app.settings.setZCheckins(it) } },
                info = "Когда дело идёт дольше базового времени своей категории, кнопка моргает и " +
                    "спрашивает. «Да» — считаем дальше, «Нет» — сразу новая запись.",
            )
        }

        // Пояснения разделов — за «i» в подписи плашки (24.09.2026); внутри
        // заголовки-дубли «Категории», «Клиенты и проекты» сняты.
        PaperCard(
            label = "категории",
            info = "Сонет выбирает строго из этого списка; пояснение — подсказка ему. Тап — править: " +
                "там же базовое время («всё ещё …?» после него) и ценность часа от −10 до +10, " +
                "из которой складывается баланс дня.",
        ) {
            CategoriesEditor(
                categories = categories,
                onChange = { app.appScope.launch { app.zasechkaStore.setCategories(it) } },
            )
        }
        PaperCard(label = "клиенты и проекты", info = "Помогают распознаванию и попадают в отчёты.") {
            EditableList(
                values = clients,
                onChange = { app.appScope.launch { app.zasechkaStore.setClients(it) } },
            )
        }

        // Плашку «правила разбора» раздел рисует сам: пустой набор не
        // показывается вовсе.
        ZasechkaRulesSection(app)

        // Своей выгрузки у ленты больше нет (09.09): вся жизнь одним xlsx,
        // лист «Засечка», — за значком выгрузки в Статистике.

        PaperCard(label = "автопилот", info = AUTOPILOT_INFO) { AutoPilotSection(app) }
        PaperCard(label = "метки nfc", info = NFC_INFO) { NfcTagsSection(app) }
    }
}

/**
 * intervals.icu: тренировки двух последних суток встают в ленту, сон Garmin
 * дописывается к «сну», спорт и еда пишут туда своё. Ключ — один на всё
 * приложение, поэтому в «Подключениях», а не в Засечке.
 */
@Composable
internal fun IntervalsSettings(app: PravkaApp) {
    val icuAthlete by app.settings.icuAthleteFlow.collectAsState(initial = "")
    val icuKey by app.settings.icuKeyFlow.collectAsState(initial = "")
    var athleteField by remember(icuAthlete) { mutableStateOf(icuAthlete) }
    var keyField by remember(icuKey) { mutableStateOf(icuKey) }
    val icuProfile by app.sportStore.profileFlow.collectAsState()
    PaperCard(
        label = "ключ",
        info = "Тренировки за последние двое суток сами встают в ленту (бег, вело, силовая, ходьба), " +
            "а Garmin-длительность сна дописывается к записи «сон»; вес, пульс покоя и HRV — во " +
            "вкладке «Спорт». Часы и весы Garmin приходят в intervals.icu сами: там Settings → " +
            "Connections → Garmin Connect. Ключ: intervals.icu → Settings → Developer Settings → " +
            "API Key, Athlete ID — там же (i и цифры). Ключ — свой у каждого: чужой ключ показывает " +
            "чужие тренировки и пишет подходы и еду в чужой intervals.",
    ) {
        // Чей это аккаунт — словами (08.10.2026): на телефоне Марианны стоял
        // Сашин ключ, и вкладка показывала его тренировки как её.
        if (icuProfile.athleteName.isNotBlank()) {
            PaperHint(
                "Аккаунт: ${icuProfile.athleteName}" +
                    (if (icuProfile.weightKg > 0) " · вес в профиле ${"%.1f".format(icuProfile.weightKg)} кг" else "") +
                    " — его тренировки, сон и вес видит приложение",
            )
            Spacer(Modifier.height(6.dp))
        }
        PaperField(value = athleteField, onValueChange = { athleteField = it }, label = "Athlete ID (i…)")
        PaperField(value = keyField, onValueChange = { keyField = it }, label = "API Key")
        Spacer(Modifier.height(6.dp))
        Row {
            Spacer(Modifier.weight(1f))
            PaperButton("Сохранить и проверить", primary = true, onClick = {
                app.appScope.launch {
                    app.settings.setIcuAthlete(athleteField)
                    app.settings.setIcuKey(keyField)
                    Feedback.toast(app, "Сохранено — тренировки подтянутся в ближайший свип")
                    // Глубокая выгрузка сразу: имя аккаунта встаёт строкой выше, а
                    // сменился аккаунт — чужой кэш уходит (IcuSportSync.checkAccount).
                    runCatching { app.icuSportSync.refresh(force = true) }
                    app.icuSweeper.sweep(force = true)
                }
            })
        }
    }
}

/** Пояснение плашки «автопилот» — за её «i» в настройках Засечки (24.09.2026). */
private const val AUTOPILOT_INFO =
    "Wi-Fi-места, Bluetooth машины и датчик движения: приезд закрывает " +
        "передвижение сам, остальное — вопросом-пушем. Имя Wi-Fi система отдаёт " +
        "только с разрешением «Местоположение» (GPS при этом не включается), имя " +
        "Bluetooth-устройства на Android 12+ — с «Устройствами рядом»."

/** Как автопилот узнаёт место и что делает по приезду — за «i» у «Мест». */
private const val PLACES_INFO =
    "У каждого места два способа узнать приезд. «подключился» — " +
        "точнее, так ловится дом. «вижу сеть» — для мест вроде Летово, " +
        "к чьему Wi-Fi ты не подключаешься: приезд засчитывается, как " +
        "только сеть появилась в эфире. У места может быть дело по " +
        "приезду (карандаш): закрыв дорогу, автопилот сам начнёт его; не то — " +
        "«Сказать» в пуше заменит.\n\n" +
        "Новые сети: подключись к домашнему Wi-Fi — он появится здесь (и придёт " +
        "пуш «что это за место?»). Сети, которые просто слышно рядом, " +
        "подтягиваются сами раз в полчаса."

/**
 * Подзаголовок части внутри плашки («Места», «Машина») — со своим «i», если
 * есть что пояснить (24.09.2026): длинный абзац под заголовком раньше стоял
 * прямо в плашке и читался инструкцией, а не настройкой.
 */
@Composable
private fun SubHead(text: String, info: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (info != null) InfoButton(text, info, size = 30.dp)
    }
}

/**
 * Автопилот: телефон сам замечает швы дня. Приезд в известный Wi-Fi закрывает
 * открытое «Передвижение» сам; отъезд из места и подключение машины спрашивают
 * пушем; длинное сидячее дело проверяется, когда телефон значимо задвигался.
 * Имя Wi-Fi система отдаёт только с разрешением «Местоположение» (GPS при этом
 * не включается), имя BT-устройства на Android 12+ — с «Устройствами рядом».
 */
@Composable
private fun AutoPilotSection(app: PravkaApp) {
    val context = LocalContext.current
    val scope = app.appScope
    val settings = app.settings

    // Пояснение раздела — за «i» у плашки (AUTOPILOT_INFO), здесь сразу дело.
    var permTick by remember { mutableStateOf(0) }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permTick++ }
    // Точное — только в паре с приблизительным: с Android 12 одно точное
    // система молча игнорирует, окна нет, и кнопка выглядит сломанной
    // (на телефоне владельца доступ был выдан давно, поэтому не замечали).
    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permTick++ }

    val places by settings.autoPlacesFlow.collectAsState(initial = emptyMap<String, String>())
    val visibleSsids by settings.autoVisibleFlow.collectAsState(initial = emptySet<String>())
    val carBt by settings.autoCarBtFlow.collectAsState(initial = "")
    val calOn by settings.autoCalOnFlow.collectAsState(initial = true)

    // Что автопилот видит ПРЯМО СЕЙЧАС. Первая версия молчала, и понять это
    // было нельзя ниоткуда — теперь состояние на виду.
    val pilot = ru.zf.pravka.trigger.PravkaAccessibilityService.instance?.autoPilot
    Text(
        pilot?.statusLine() ?: "Служба выключена — автопилот спит",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Почему молчит — словами и с кнопкой. Раньше на это место приходила
    // одна кнопка «дать доступ», и она врала: доступ был выдан «только при
    // использовании», а служба всё равно не видела ни одной сети.
    val blockers = remember(permTick, carBt, calOn) {
        ru.zf.pravka.trigger.AutoPilot.blockers(context, carBt, calOn)
    }
    // Разрешение на уведомления просим один раз; если система его уже не
    // покажет (отказано дважды) — вторая кнопка ведёт в настройки Правки.
    var notifAsked by remember { mutableStateOf(false) }
    for (b in blockers) {
        Spacer(Modifier.height(8.dp))
        Text(
            "⚠ " + b.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        PaperButton(
            when (b.fix) {
                ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION -> "Дать доступ к имени Wi-Fi"
                ru.zf.pravka.trigger.AutoPilot.FIX_BACKGROUND -> "Открыть разрешения Правки"
                ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION_SYS -> "Включить геолокацию"
                ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF -> "Разрешить уведомления"
                ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF_CHANNEL -> "Открыть канал уведомлений"
                ru.zf.pravka.trigger.AutoPilot.FIX_BT -> "Дать доступ к Bluetooth-устройствам"
                ru.zf.pravka.trigger.AutoPilot.FIX_CALENDAR -> "Дать доступ к календарю"
                else -> "Включить Wi-Fi"
            },
            icon = when (b.fix) {
                ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION,
                ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION_SYS -> Glyphs.Place
                ru.zf.pravka.trigger.AutoPilot.FIX_BACKGROUND -> Glyphs.Key
                ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF,
                ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF_CHANNEL -> Glyphs.Bell
                ru.zf.pravka.trigger.AutoPilot.FIX_BT -> Glyphs.Car
                ru.zf.pravka.trigger.AutoPilot.FIX_CALENDAR -> Glyphs.Calendar
                else -> Glyphs.Wifi
            },
            onClick = {
                when (b.fix) {
                    ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION ->
                        askLocation.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    ru.zf.pravka.trigger.AutoPilot.FIX_BACKGROUND -> {
                        // На Android 11+ системного диалога для фонового
                        // местоположения нет вовсе: только экран приложения,
                        // руками. Поэтому ведём прямо туда.
                        if (android.os.Build.VERSION.SDK_INT == 29) {
                            askPermission.launch("android.permission.ACCESS_BACKGROUND_LOCATION")
                        } else {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            Feedback.toast(app, "Разрешения → Местоположение → «Разрешать всегда»")
                        }
                    }
                    ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION_SYS ->
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF -> {
                        if (android.os.Build.VERSION.SDK_INT >= 33 && !notifAsked) {
                            notifAsked = true
                            askPermission.launch("android.permission.POST_NOTIFICATIONS")
                        } else {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                )
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF_CHANNEL ->
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                            )
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .putExtra(
                                    android.provider.Settings.EXTRA_CHANNEL_ID,
                                    ru.zf.pravka.trigger.AutoPilot.CHANNEL,
                                )
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    ru.zf.pravka.trigger.AutoPilot.FIX_BT ->
                        askPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                    ru.zf.pravka.trigger.AutoPilot.FIX_CALENDAR ->
                        askPermission.launch(android.Manifest.permission.READ_CALENDAR)
                    else -> context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
    }
    if (blockers.isNotEmpty()) {
        PaperTextButton("Проверить ещё раз", onClick = { permTick++ }, icon = Glyphs.Refresh)
    }

    // Вопросы — плашкой сверху (28.09.2026): пуш тогда тихий, в шторке.
    val platesOn by settings.autoPlatesFlow.collectAsState(initial = true)
    PaperToggle(
        title = "Вопросы автопилота — плашкой сверху",
        checked = platesOn,
        onCheckedChange = { on -> scope.launch { settings.setAutoPlates(on) } },
        info = "Экран включён и открыт, пилюля свободна — вопрос встаёт строкой пилюли «З» " +
            "с теми же кнопками, а пуш ложится в шторку тихо, без баннера: копия на потом. " +
            "Ответил на плашке — пуш снимается, нажал в пуше — плашка уходит. Экран заперт " +
            "или пилюля занята — громкий пуш, как раньше. Плашку можно смахнуть вбок.",
    )

    // ---- Места по Wi-Fi ----
    // Сети, которые служба уже видела: владелец называет каждую местом
    // («это дача») — ровно то, что он просил, вместо угадывания SSID.
    val seen by settings.autoSeenFlow.collectAsState(initial = emptyMap<String, Long>())
    var namingSsid by remember { mutableStateOf<String?>(null) }
    var placeName by remember { mutableStateOf("") }
    val unnamed = seen.keys.filter { !places.containsKey(it) }
        .sortedByDescending { seen[it] ?: 0L }

    // Дело места по приезду: закрыв дорогу, автопилот сам начинает его
    // («Летово» → «Забираю Серёжу»). Ключ — имя места, см. Settings.
    val deals by settings.autoPlaceDealsFlow.collectAsState(initial = emptyMap<String, PlaceDeal>())
    val dealCategories by app.zasechkaStore.categoriesFlow.collectAsState()
    var dealPlace by remember { mutableStateOf<String?>(null) }
    SubHead("Места", info = PLACES_INFO)
    for ((ssid, name) in places) {
        val byAir = visibleSsids.contains(ssid)
        val deal = AutoPilotRules.dealFor(name, deals)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$name — $ssid", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (deal != null) {
                        "по приезду: «${deal.title}»" +
                            (if (deal.category.isNotBlank()) " [${deal.category}]" else "")
                    } else "по приезду — спросить, что делаешь",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlyphButton(
                Glyphs.Edit,
                if (deal != null) "изменить дело по приезду" else "задать дело по приезду",
                onClick = { dealPlace = name },
                size = 36.dp,
            )
            GlyphButton(
                Glyphs.Delete,
                "убрать место",
                onClick = { scope.launch { settings.removeAutoPlace(ssid) } },
                size = 36.dp,
            )
        }
        // Что считать приездом — выбором из двух, а не кнопкой-переключателем
        // со словом текущего режима: так видно и что выбрано, и что ещё есть.
        ChipRow(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            PaperChip("подключился", selected = !byAir, onClick = {
                if (byAir) scope.launch { settings.setAutoVisible(ssid, false) }
            })
            PaperChip("вижу сеть", selected = byAir, onClick = {
                if (!byAir) scope.launch { settings.setAutoVisible(ssid, true) }
            })
        }
    }
    dealPlace?.let { place ->
        PlaceDealDialog(
            place = place,
            current = AutoPilotRules.dealFor(place, deals),
            categories = dealCategories.map { it.name },
            onDismiss = { dealPlace = null },
            onSave = { title, category ->
                dealPlace = null
                scope.launch { settings.setAutoPlaceDeal(place, title, category) }
            },
        )
    }

    if (unnamed.isEmpty()) {
        PaperHint(if (places.isEmpty()) "Новых сетей пока не видел." else "Все увиденные сети названы.")
    } else {
        Text(
            "Что это за сети?",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        for (ssid in unnamed) {
            if (namingSsid == ssid) {
                val done: () -> Unit = {
                    val name = placeName.trim()
                    if (name.isNotBlank()) {
                        scope.launch {
                            settings.addAutoPlace(ssid, name)
                            settings.removeAutoSeen(ssid)
                        }
                    }
                    namingSsid = null
                    placeName = ""
                }
                PaperField(
                    value = placeName,
                    onValueChange = { placeName = it },
                    label = "«$ssid» — это…",
                    trailing = {
                        GlyphButton(Glyphs.Check, "готово", onClick = done, tint = MaterialTheme.colorScheme.primary)
                    },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ssid, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "видел " + SimpleDateFormat("dd.MM HH:mm", Locale.US)
                                .format(Date(seen[ssid] ?: 0L)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PaperTextButton("Это место…", onClick = { namingSsid = ssid; placeName = "" })
                    PaperTextButton("Не место", onClick = { scope.launch { settings.removeAutoSeen(ssid) } })
                }
            }
        }
    }
    val autoArrive by settings.autoArriveFlow.collectAsState(initial = true)
    val leaveAsk by settings.autoLeaveAskFlow.collectAsState(initial = true)
    val carAsk by settings.autoCarAskFlow.collectAsState(initial = true)
    val carStart by settings.autoCarStartFlow.collectAsState(initial = true)
    val stillAsk by settings.autoStillAskFlow.collectAsState(initial = true)
    // Тумблеры — рядом с тем, чем они правят (24.09.2026): приезд и отъезд —
    // у мест, поездка — у машины, «точно ещё» — отдельно.
    Spacer(Modifier.height(4.dp))
    PaperToggle(
        title = "Приезд в место закрывает передвижение",
        checked = autoArrive,
        onCheckedChange = { on -> scope.launch { settings.setAutoArrive(on) } },
    )
    val walkStart by settings.autoWalkStartFlow.collectAsState(initial = true)
    PaperToggle(
        title = "Спрашивать при отъезде из места",
        checked = leaveAsk,
        onCheckedChange = { on -> scope.launch { settings.setAutoLeaveAsk(on) } },
        info = "Вопрос приходит через десять минут после потери сети: вышел вынести мусор и " +
            "вернулся раньше — ни вопроса, ни разрыва в деле. Машина и вело с часов в эти " +
            "двадцать минут склеиваются с выходом: дорога начинается у двери. Сказал на " +
            "выходе, что делаешь («пошёл развозить детей», «иду к другу»), — не спрашивает " +
            "и поездку поверх не кладёт: за десять минут до потери сети, а со словами " +
            "выхода — за полчаса.",
    )
    PaperToggle(
        title = "Двадцать минут в движении после отъезда — пешком",
        checked = walkStart,
        onCheckedChange = { on -> scope.launch { settings.setAutoWalkStart(on) } },
        info = "Сеть места пропала, машина не подключилась, нового места не видно, а телефон " +
            "двадцать минут толкается — «Дорога пешком» начинается с момента отъезда сама. " +
            "Такси и метро толкают так же: в пуше кнопка «Транспорт».",
    )

    // ---- Машина по Bluetooth ----
    SubHead("Машина")
    val needBtPerm = android.os.Build.VERSION.SDK_INT >= 31 &&
        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    // permTick пересчитывает и это условие тоже.
    val btPermTick = remember(permTick) { needBtPerm }
    val carBtAddr by settings.autoCarBtAddrFlow.collectAsState(initial = "")
    when {
        carBt.isNotBlank() -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Машина: $carBt", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (carBtAddr.isBlank()) {
                            // Машина заведена старой сборкой — только по имени. Имя
                            // система отдаёт не всегда; переустановить один раз
                            // стоит, чтобы узнавать и по адресу.
                            "узнаю только по имени — убери и выбери заново, будет и адрес"
                        } else "узнаю по имени и адресу $carBtAddr",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PaperTextButton("Убрать", onClick = { scope.launch { settings.setAutoCarBt("") } })
            }
        }
        btPermTick -> {
            PaperButton(
                "Дать доступ к Bluetooth-устройствам",
                icon = Glyphs.Car,
                onClick = { askPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT) },
            )
        }
        else -> {
            // Имя → адрес: адрес едет в настройки вместе с именем, по нему
            // машина узнаётся и тогда, когда система имени не отдаёт.
            val bonded = remember(permTick) {
                runCatching {
                    (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                        as android.bluetooth.BluetoothManager)
                        .adapter?.bondedDevices
                        ?.mapNotNull { d -> d.name?.let { it to d.address.orEmpty() } }
                        ?.sortedBy { it.first }
                }.getOrNull().orEmpty()
            }
            if (bonded.isEmpty()) {
                PaperHint("Спаренных Bluetooth-устройств не видно.")
            } else {
                Text("Какое устройство — машина?", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                ChipRow {
                    for ((name, addr) in bonded) {
                        PaperChip(
                            name,
                            selected = false,
                            onClick = { scope.launch { settings.setAutoCarBt(name, addr) } },
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    PaperToggle(
        title = "Машина подключилась — сразу начать «Поездку на машине»",
        checked = carStart,
        onCheckedChange = { on -> scope.launch { settings.setAutoCarStart(on) } },
        info = "Поездка стартует с момента подключения и закрывает текущее дело; в пуше " +
            "есть «Отменить». Отключилась машина — через две минуты вопрос «приехал?». " +
            "Приезд в место с открытой дорогой закрывает её и спрашивает, что теперь.",
    )
    if (!carStart) {
        PaperToggle(
            title = "…или хотя бы спросить «сел в машину?»",
            checked = carAsk,
            onCheckedChange = { on -> scope.launch { settings.setAutoCarAsk(on) } },
        )
    }

    // ---- Движение ----
    SubHead("Движение")
    PaperToggle(
        title = "«Всё ещё …?», когда телефон задвигался",
        checked = stillAsk,
        onCheckedChange = { on -> scope.launch { settings.setAutoStillAsk(on) } },
        info = "Взял телефон и пошёл — значит, началось другое дело. Датчик значимого " +
            "движения после двадцати минут любого дела, кроме дороги, спорта, быта и сна. " +
            "Первый толчок — не вопрос: десять минут считаются остальные, и спрашивает, " +
            "только если ты так и не сел; отъезд после толчка спросит вопрос «уехал?». Не " +
            "чаще раза в двадцать минут. Выключено — не спрашивает вовсе.",
    )

    // ---- Календарь ----
    SubHead("Календарь", info = CALENDAR_INFO)
    PaperToggle(
        title = "Встречи из календаря — в ленту сами",
        checked = calOn,
        onCheckedChange = { on -> scope.launch { settings.setAutoCalOn(on) } },
    )
    if (calOn) {
        val calPerm = remember(permTick) { ru.zf.pravka.trigger.CalendarPilot.hasPermission(context) }
        if (!calPerm) {
            PaperButton(
                "Дать доступ к календарю",
                icon = Glyphs.Calendar,
                onClick = { askPermission.launch(android.Manifest.permission.READ_CALENDAR) },
            )
        } else {
            val calCategory by settings.autoCalCategoryFlow
                .collectAsState(initial = ru.zf.pravka.core.CalendarRules.DEFAULT_CATEGORY)
            Text(
                "Категория встреч (БЖЖ — всегда спорт)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            ChipRow(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                val work = dealCategories.filter { it.name.startsWith("Работа", ignoreCase = true) }
                    .ifEmpty { dealCategories }
                for (c in work) {
                    PaperChip(
                        c.name,
                        selected = c.name == calCategory,
                        onClick = { scope.launch { settings.setAutoCalCategory(c.name) } },
                    )
                }
            }
            // null — не выбирали: только основной календарь аккаунта.
            val chosen by settings.autoCalendarsFlow.collectAsState(initial = null)
            val cals = remember(permTick) { ru.zf.pravka.trigger.CalendarPilot.calendars(context) }
            if (cals.isEmpty()) {
                PaperHint("Календарей на телефоне не видно.")
            } else {
                Text(
                    "Какие календари",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                val effective = chosen ?: cals.filter { it.primary }.map { it.name }.toSet()
                for (c in cals) {
                    PaperToggle(
                        title = c.name + if (c.primary) " · основной" else "",
                        checked = c.name in effective,
                        onCheckedChange = { on ->
                            scope.launch { settings.setAutoCalendar(c.name, on, effective) }
                        },
                    )
                }
            }
            // Что автопилот видит сегодня: молчащий календарь читается как поломка.
            var calToday by remember { mutableStateOf("") }
            LaunchedEffect(permTick, chosen) {
                calToday = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ru.zf.pravka.trigger.CalendarPilot.todayLine(context, chosen) }
                        .getOrElse { "календарь не прочитался: ${it.message}" }
                }
            }
            if (calToday.isNotBlank()) PaperHint(calToday.replaceFirstChar { it.uppercase() })
            val calLine = ru.zf.pravka.trigger.PravkaAccessibilityService.instance?.calendarPilot?.lastFire.orEmpty()
            if (calLine.isNotBlank()) PaperHint("Последнее: $calLine")
        }
    }

    // ---- Звонки (06.10.2026) ----
    SubHead("Звонки", info = CALLS_INFO)
    val callsCut by settings.zCallsCutFlow.collectAsState(initial = true)
    PaperToggle(
        title = "Звонок режет дело — с категорией по собеседнику",
        checked = callsCut,
        onCheckedChange = { on -> scope.launch { settings.setZCallsCut(on) } },
    )
    if (callsCut) {
        val callPerm = remember(permTick) { ru.zf.pravka.data.PhoneSweeper.hasCallLogAccess(context) }
        if (!callPerm) {
            PaperButton(
                "Дать доступ к журналу звонков",
                icon = Glyphs.Phone,
                onClick = { askPermission.launch(android.Manifest.permission.READ_CALL_LOG) },
            )
        }
        val family by settings.zCallFamilyFlow.collectAsState(initial = ru.zf.pravka.core.CallRules.FAMILY_DEFAULT)
        var familyText by remember(family) { mutableStateOf(family) }
        PaperField(
            value = familyText,
            onValueChange = { v ->
                familyText = v
                scope.launch { settings.setZCallFamily(v) }
            },
            label = "Семья — имена контактов через запятую",
            singleLine = false,
        )
        PaperHint(
            "Семья — [Семья]; люди Дел (по номеру или имени) — [Работа: звонки] с их клиентом; " +
                "остальные — [Звонки] и вопрос пушем. Ответил однажды — этот собеседник дальше так и приезжает.",
        )
    }

    // ---- Телефон в ленте (13.10.2026) ----

    // ---- Подъём и отбой ----
    SubHead("Подъём и отбой", info = WAKE_INFO)
    val wakeDeal by settings.autoWakeDealFlow.collectAsState(initial = null)
    var wakeEdit by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("По подъёму, каждый день", style = MaterialTheme.typography.bodyMedium)
            Text(
                wakeDeal?.let { d ->
                    "«${d.title}»" + (if (d.category.isNotBlank()) " [${d.category}]" else "") +
                        " — с момента подъёма, между пятью утра и полуднем"
                } ?: "дела нет — только сон в ленту",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GlyphButton(
            Glyphs.Edit,
            if (wakeDeal != null) "изменить дело по подъёму" else "задать дело по подъёму",
            onClick = { wakeEdit = true },
            size = 36.dp,
        )
    }
    if (wakeEdit) {
        PlaceDealDialog(
            place = "утро",
            current = wakeDeal,
            categories = dealCategories.map { it.name },
            onDismiss = { wakeEdit = false },
            onSave = { title, category ->
                wakeEdit = false
                scope.launch { settings.setAutoWakeDeal(title, category) }
            },
            heading = "Проснулся — что начать?",
            hint = "Сон кончился утром (взял телефон, ночь по экрану) между пятью и полуднем — " +
                "это дело начнётся с момента подъёма. Уже сказал, что делаешь, — не начнётся. " +
                "Не то — «Сказать» в пуше заменит, «Ещё сплю» вернёт сон, «Отменить» уберёт.",
            placeholder = "Просыпаюсь",
            removeLabel = "убрать дело по подъёму",
            icon = Glyphs.Moon,
        )
    }
    val bedtimeOn by settings.autoBedtimeFlow.collectAsState(initial = true)
    PaperToggle(
        title = "Сон сам: зарядка и полчаса без движения",
        checked = bedtimeOn,
        onCheckedChange = { on -> scope.launch { settings.setAutoBedtime(on) } },
        info = "Телефон на зарядке, экран погас, полчаса без единого толчка и слова, положил " +
            "его после 22:00, и уже после 23:00 — в ленте начинается «Сон» с того мига, когда " +
            "телефон положили, а вечернее дело закрывается там же. Когда подключил зарядку — " +
            "неважно. Пуш тихий — лежит в шторке, экран не зажигает; не спишь — «Отменить», " +
            "вернётся прежнее дело. Ночью взял, глянул, положил — сон идёт; сидишь в телефоне " +
            "дольше десяти минут — значит, не спишь: сон короче трёх часов убирается, длиннее — " +
            "закрывается, и, когда положишь телефон снова, начнётся заново. Кончается сон, " +
            "когда утром (с пяти) взял телефон: разблокировал, он задвигался или ты долго " +
            "смотришь в экран; зарядку снимать не нужно. Так кончается и сон, который ты " +
            "включил сам (метка NFC, «сплю» голосом). Ошибся — «Ещё сплю» в утреннем пуше.",
    )
}

/** Подъём и отбой — за «i» у заголовка. */
private const val WAKE_INFO =
    "Вечер: телефон на зарядке, экран погас, полчаса тишины, положил после 22:00 (решается " +
        "не раньше 23:00) — «Сон» начинается сам, вечернее дело закрыто, тихий пуш с " +
        "«Отменить». Утро: взял телефон после пяти — сон кончился (и твой, по метке или " +
        "голосом); с этого момента сам начинается дело по подъёму — «Просыпаюсь». Без " +
        "автосна утро узнаётся, как раньше, по ночи на экране."

private const val CALLS_INFO =
    "Владелец, 06.10.2026: «звонки должны перебивать текущее дело… когда я говорю по " +
        "телефону, я не работаю; а если сижу и пришёл звонок по работе — я прямо работаю». " +
        "Разговор от двух минут встаёт в ленту врезкой: дело режется на начале звонка и " +
        "продолжается после. Категорию решает собеседник, а не дело, которое шло. Не режет " +
        "свой записанный звонок, встречу из календаря, дорогу и тренировку. Пуш — «Работа», " +
        "«Семья», «Убрать» (дело сшивается обратно). Выключено — звонки только в счётчиках дня."

private const val CALENDAR_INFO =
    "Встреча из календаря телефона начинается в ленте сама с начала события и " +
        "закрывает текущее дело; по концу события закрывается и возвращает то, что " +
        "шло до неё, — как перерыв по метке NFC. Не встреча — «Отменить» в пуше; " +
        "затянулась — «Ещё идёт». Смотрятся только выбранные календари (с завода — " +
        "основной), без событий на весь день, отклонённых и «свободен». Встреча узнаёт " +
        "себя в твоих словах — по названию и клиенту: сказал «встречи в Птиц» — второй " +
        "записи нет, а если забыл переключиться, по концу события она закроется сама. " +
        "Сказал то же самое уже после начала — твоя запись заберёт её начало. Сказал " +
        "другое за десять минут до начала или позже — встреча не пишется. БЖЖ — не " +
        "созвон: «BJJ: борьба» спортом, и тренировка с часов дописывается к ней, а не " +
        "режет. Клиент — проект Дел по имени или алиасу в названии встречи; своя " +
        "сторона («ЗФ», «Знакомый Финансист») из названия уходит."

/**
 * Дело места по приезду: название и категория; пустое название — дела нет.
 * Лист набора вместо AlertDialog (24.09.2026): «Убрать» — корзиной слева,
 * «Отмену» заменили крестик и свайп.
 */
@Composable
private fun PlaceDealDialog(
    place: String,
    current: PlaceDeal?,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (title: String, category: String) -> Unit,
    // Тот же лист служит делу по подъёму — слова свои, механика одна.
    heading: String = "Приехал в «$place» — что начать?",
    hint: String = "Дорога закроется приездом, и это дело начнётся с того же момента. " +
        "Ошибся автопилот — «Сказать» в пуше заменит его.",
    placeholder: String = "Забираю Серёжу",
    removeLabel: String = "убрать дело по приезду",
    icon: androidx.compose.ui.graphics.vector.ImageVector = Glyphs.Place,
) {
    var title by remember { mutableStateOf(current?.title.orEmpty()) }
    var category by remember { mutableStateOf(current?.category.orEmpty()) }
    PaperAlert(
        onDismiss = onDismiss,
        title = heading,
        icon = icon,
        confirm = SheetAction("Готово") { onSave(title.trim(), category) },
        destructive = if (current != null) SheetAction(removeLabel) { onSave("", "") } else null,
    ) {
        PaperHint(hint)
        PaperField(
            value = title,
            onValueChange = { title = it },
            label = "Дело",
            placeholder = placeholder,
        )
        CategoryPicker(selected = category, options = categories, onSelect = { category = it })
    }
}

/**
 * Выбор категории в окне: кнопка с текущей и выпадающий список. Своей детали
 * в наборе нет (24.09.2026): чипами два десятка категорий в окно не лезут, а
 * выбранная уезжала бы за край ряда.
 */
@Composable
private fun CategoryPicker(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    noneLabel: String? = "без категории",
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PaperButton(
            "Категория: " + selected.ifBlank { "нет" },
            onClick = { open = true },
            icon = Glyphs.Tag,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (noneLabel != null) {
                DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(""); open = false })
            }
            for (c in options) {
                DropdownMenuItem(text = { Text(c) }, onClick = { onSelect(c); open = false })
            }
        }
    }
}

/**
 * Backups, out in the open: a copy of the ribbon per day, the state before any
 * sharp shrink, and the quarantined file if one ever appears. Restore puts a
 * copy back (undoable like any other operation), «Файлом» hands the raw JSON
 * out so nothing important is ever trapped in private storage.
 *
 * Лежит в плашке «резервные копии ленты» (настройки «Приложения»), поэтому
 * своего заголовка нет, а пояснение — за «i» в строке состояния (24.09.2026).
 */
@Composable
internal fun BackupsSection(app: PravkaApp) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var list by remember { mutableStateOf<List<ZasechkaStore.BackupInfo>>(emptyList()) }
    LaunchedEffect(tick) {
        list = runCatching { app.zasechkaStore.backups() }.getOrDefault(emptyList())
    }
    // Импорт возвращает ленту из любой выгрузки CSV - последняя линия обороны,
    // если и файл, и копии на диске подвели (копии живут в приватной памяти
    // приложения, а выгрузка уже уехала в мессенджер).
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            app.appScope.launch {
                val n = runCatching { app.zasechkaStore.importCsv(uri) }.getOrDefault(0)
                tick++
                Feedback.toast(
                    app,
                    when {
                        n > 0 -> "Вернулось записей: $n"
                        n < 0 -> "Файл не прочитался"
                        else -> "Новых записей в файле нет"
                    },
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (list.isEmpty()) "Копий пока нет — первая появится в течение часа."
            else "Копий: ${list.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Кнопки ↩︎ во вкладке больше нет (15.09) — отмена живёт в меню
        // долгого нажатия «З», так и сказано.
        InfoButton(
            "Резервные копии",
            "Копия всего нажитого (лента, словарь, правила, телефон) снимается раз в час, " +
                "плюс копия ленты на каждый день и перед любым резким сокращением записей. " +
                "«Восстановить» возвращает копию целиком (отменяется из меню долгого нажатия «З»), " +
                "значок «поделиться» у копии и «Текущий файл» отдают сырой JSON. " +
                "«Импорт CSV» поднимает ленту из любой выгрузки: " +
                "строки, которые уже есть, не удваиваются.",
            size = 30.dp,
        )
    }
    IconActionRow {
        IconAction(Glyphs.Refresh, "Обновить", onClick = { tick++ })
        IconAction(Glyphs.Share, "Текущий файл", onClick = {
            app.appScope.launch {
                runCatching { context.startActivity(app.zasechkaStore.shareStoreIntent()) }
            }
        })
        IconAction(Glyphs.Download, "Импорт CSV", onClick = {
            runCatching { importer.launch(arrayOf("*/*")) }
        })
    }
    for (b in list) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    b.name.removePrefix("lenta-").removePrefix("zasechka-").removeSuffix(".json"),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${b.entries} записей · ${b.bytes / 1024} КБ · " +
                        SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(b.at)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlyphButton(
                Glyphs.Share,
                "отдать файлом",
                onClick = {
                    app.appScope.launch {
                        runCatching { context.startActivity(app.zasechkaStore.shareStoreIntent(b.name)) }
                    }
                },
                size = 36.dp,
            )
            PaperTextButton(
                "Восстановить",
                onClick = {
                    app.appScope.launch {
                        val n = runCatching { app.zasechkaStore.restoreFrom(b.name) }.getOrDefault(0)
                        Feedback.toast(
                            app,
                            if (n > 0) "Восстановлено записей: $n" else "В копии нечего восстанавливать",
                        )
                    }
                },
                enabled = b.entries > 0,
            )
        }
    }
}

/**
 * Список категорий: имя цветом радуги, справа базовое время и ценность часа,
 * под ним подсказка разбору. Заголовок и пояснение — у плашки снаружи.
 */
@Composable
private fun CategoriesEditor(
    categories: List<ZasechkaStore.Category>,
    onChange: (List<ZasechkaStore.Category>) -> Unit,
) {
    var editing by remember { mutableStateOf<ZasechkaStore.Category?>(null) }
    // Same order as the day's progress bars: along the rainbow, red first.
    for (category in categories.sortedBy { categoryHue(it.name) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { editing = category },
        ) {
            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = categoryColor(category.name),
                        modifier = Modifier.weight(1f),
                    )
                    // The two knobs at a glance: typical length and what an
                    // hour of it is worth (+ lifts the day, - sinks it).
                    // Часики — значком набора, а не эмодзи ⏱.
                    if (category.baseMin > 0) {
                        Icon(
                            Glyphs.Timer,
                            contentDescription = "базовое время",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "${category.baseMin} м",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (category.value > 0) "+${category.value}" else "${category.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (category.hint.isNotBlank()) {
                    Text(
                        category.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GlyphButton(
                Glyphs.Delete,
                "удалить категорию",
                onClick = { onChange(categories.filter { it.name != category.name }) },
                size = 36.dp,
            )
        }
    }
    var newName by remember { mutableStateOf("") }
    Spacer(Modifier.height(4.dp))
    PaperField(
        value = newName,
        onValueChange = { newName = it },
        label = "Добавить категорию",
        trailing = {
            GlyphButton(
                Glyphs.Plus,
                "добавить",
                onClick = {
                    val v = newName.trim()
                    if (v.isNotEmpty()) {
                        onChange(categories + ZasechkaStore.Category(v, ""))
                        newName = ""
                    }
                },
                enabled = newName.isNotBlank(),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    )
    editing?.let { original ->
        var name by remember(original) { mutableStateOf(original.name) }
        var hint by remember(original) { mutableStateOf(original.hint) }
        var baseMin by remember(original) { mutableStateOf(original.baseMin.toString()) }
        var worth by remember(original) { mutableStateOf(original.value) }
        PaperAlert(
            onDismiss = { editing = null },
            title = "Категория",
            icon = Glyphs.Tag,
            subtitle = original.name,
            confirm = SheetAction("Сохранить") {
                editing = null
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) {
                    onChange(
                        categories.map {
                            if (it.name == original.name) {
                                ZasechkaStore.Category(
                                    name = trimmed,
                                    hint = hint.trim(),
                                    baseMin = baseMin.toIntOrNull() ?: 0,
                                    value = worth,
                                )
                            } else it
                        }
                    )
                }
            },
        ) {
            PaperField(value = name, onValueChange = { name = it }, label = "Название")
            PaperField(
                value = hint,
                onValueChange = { hint = it },
                label = "Что сюда относится (подсказка Сонету)",
                singleLine = false,
                maxLines = 4,
            )
            PaperField(
                value = baseMin,
                onValueChange = { baseMin = it.filter { c -> c.isDigit() }.take(4) },
                label = "Базовое время, мин (0 — не спрашивать)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            // Ползунок меняет только поле окна — пишет «Сохранить», поэтому
            // по отпусканию делать нечего.
            PaperSlider(
                title = "Ценность часа",
                valueText = if (worth > 0) "+$worth" else "$worth",
                value = worth.toFloat(),
                onValueChange = { worth = it.roundToInt() },
                onValueChangeFinished = {},
                valueRange = -10f..10f,
                steps = 19,
            )
            PaperHint(
                when {
                    worth >= 7 -> "Тянет день вверх"
                    worth > 0 -> "Плюс"
                    worth == 0 -> "Ватерлиния, сервисное время"
                    worth > -7 -> "Минус"
                    else -> "Тянет день вниз"
                }
            )
        }
    }
}

/** Список строк с «удалить» и полем «добавить»; подпись и пояснение — у плашки. */
@Composable
private fun EditableList(
    values: List<String>,
    onChange: (List<String>) -> Unit,
) {
    for (value in values) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            GlyphButton(Glyphs.Delete, "удалить", onClick = { onChange(values - value) }, size = 36.dp)
        }
    }
    var newValue by remember { mutableStateOf("") }
    Spacer(Modifier.height(4.dp))
    PaperField(
        value = newValue,
        onValueChange = { newValue = it },
        label = "Добавить",
        trailing = {
            GlyphButton(
                Glyphs.Plus,
                "добавить",
                onClick = {
                    val v = newValue.trim()
                    if (v.isNotEmpty()) {
                        onChange(values + v)
                        newValue = ""
                    }
                },
                enabled = newValue.isNotBlank(),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

// ---------------------------------------------------------------------------
// The phone layer: screen time, pickups, отвлечения and per-app minutes.
// A separate ledger from the ribbon (owner's design): most app time is
// tooling inside a bigger activity. Tapping an app row promotes it to an
// "attention eater" - its sessions then auto-claim ribbon time.
// ---------------------------------------------------------------------------

/**
 * Правила разбора Засечки: набор, одобренный владельцем, едет в каждый разбор
 * фразы. Самообучение, которое их предлагало каждую ночь (батч Опусом, ⭐ над
 * «З», «Обучить», «Прогнать всю историю»), снято 08.09.2026 — владелец: «все
 * паттерны и так уже найдены, звёздочка очень сильно раздражает, я на них
 * вообще не смотрю». Осталось то, что работает: список с тумблерами.
 * Предложение, застрявшее с прежних ночей, показывается ещё раз — судить или
 * снять руками, само оно никуда не уедет и в промпт не попадёт.
 *
 * Плашку раздел рисует сам (24.09.2026): раньше он стоял голым текстом между
 * плашками настроек, а пустой не показывается вовсе — снаружи этого не знают.
 */
@Composable
private fun ZasechkaRulesSection(app: PravkaApp) {
    val scope = app.appScope
    var rules by remember { mutableStateOf(emptyList<ru.zf.pravka.data.RulesStore.Rule>()) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        rules = runCatching { app.zasechkaRules.all() }.getOrDefault(emptyList())
    }

    val proposed = rules.filter { it.pending }
    val active = rules.filter { !it.pending }
    // Пустой раздел не показываем: добавить правило руками отсюда нельзя,
    // а робот новых не предлагает — говорить было бы нечего.
    if (proposed.isEmpty() && active.isEmpty()) return

    PaperCard(
        label = "правила разбора",
        info = "Что у тебя значат слова про время и дела — уходит в каждый разбор " +
            "фразы. Новых правил робот не предлагает, набор правится здесь.",
    ) {
        if (proposed.isNotEmpty()) {
            Text("Предложено раньше — суди или сними", style = MaterialTheme.typography.bodyMedium)
            for (r in proposed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Text(r.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    PaperTextButton("Да", onClick = {
                        scope.launch { app.zasechkaRules.approve(r.id); reload++ }
                    })
                    PaperTextButton("Нет", color = MaterialTheme.colorScheme.error, onClick = {
                        scope.launch { app.zasechkaRules.delete(r.id); reload++ }
                    })
                }
            }
        }

        if (active.isNotEmpty()) {
            // Подзаголовок нужен, только когда рядом есть предложенные:
            // иначе он повторял бы подпись плашки.
            if (proposed.isNotEmpty()) {
                Text(
                    "Действующие правила",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            for (r in active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        PaperToggle(
                            title = r.text,
                            checked = r.enabled,
                            onCheckedChange = { v ->
                                scope.launch { app.zasechkaRules.setEnabled(r.id, v); reload++ }
                            },
                        )
                    }
                    GlyphButton(
                        Glyphs.Delete,
                        "удалить правило",
                        onClick = { scope.launch { app.zasechkaRules.delete(r.id); reload++ } },
                        size = 36.dp,
                    )
                }
            }
        }
    }
}

/**
 * Откуда запись, значком после длительности: из дела — галочка дел, из
 * календаря — календарь (05.10.2026: «понимать, что этим делом я занимаюсь»).
 * Остальные — без значка: лента дышит.
 */
@Composable
private fun SourceMark(e: ZasechkaStore.Entry) {
    val glyph = when {
        e.task.isNotBlank() -> Glyphs.Delo
        e.source == ru.zf.pravka.core.CalendarRules.SOURCE -> Glyphs.Calendar
        else -> return
    }
    Spacer(Modifier.width(5.dp))
    Icon(
        glyph,
        contentDescription = if (e.task.isNotBlank()) "из дела" else "из календаря",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(12.dp),
    )
}
