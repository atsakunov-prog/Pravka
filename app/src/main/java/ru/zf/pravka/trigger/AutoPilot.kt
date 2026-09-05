package ru.zf.pravka.trigger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.core.AutoPilotRules
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.ui.Feedback

// Автопилот Засечки: телефон сам замечает швы дня и либо чинит ленту, либо
// спрашивает одним пушем. Три сигнала, все дешёвые по батарее и без GPS.
// Что решать по сигналу — в `core/AutoPilotRules.kt`, там же тесты.
//
// ПЕРВАЯ ВЕРСИЯ МОЛЧАЛА, и вот почему — это стоит помнить:
// 1. Имя сети в настройках читалось устаревшим WifiManager.connectionInfo,
//    который на новых Android отдаёт «<unknown ssid>» кому угодно, кроме
//    активного владельца сети. Кнопка «Запомнить» не появлялась, мест не
//    было — и весь автопилот вежливо ничего не делал. Теперь SSID знает
//    СЛУЖБА (живой колбэк с FLAG_INCLUDE_LOCATION_INFO), а настройки берут
//    значение у неё; плюс опрос на тике ловит то, что колбэк пропустил.
// 2. Приезд закрывал только категории «Передвижение…», а «Поездка на
//    велосипеде» лежала в «Спорт: вело» — и оставалась открытой (а потом
//    старая проверка «всё ещё …?» про неё же и спрашивала). Теперь дорога
//    узнаётся и по названию, а спортивную запись автопилот не закрывает
//    молча — предлагает кнопкой.
// 3. Ничего не было видно снаружи. Теперь [statusLine] показывает в
//    настройках, какую сеть служба видит прямо сейчас, сколько мест
//    заведено и когда автопилот срабатывал последний раз.
// 4. И всё равно молчало — потому что разрешение «Местоположение» было
//    выдано «только при использовании приложения». Ловит-то приезд СЛУЖБА,
//    из фона: система отдавала ей «<unknown ssid>», и автопилот честно не
//    видел ни одной сети. Снаружи это выглядит ровно как поломка. Теперь
//    [blockers] называет причину словами и даёт кнопку, которая её чинит.
//
// ВТОРАЯ ВЕРСИЯ ЗАКРЫВАЛА, НО НЕ ГОВОРИЛА (сентябрь 2026). Владелец: «приехал
// домой, он остановил передвижение, но не спросил „что ты делаешь?“; ушёл из
// сети Летово — не спросил „точно ещё встречаешь Серёжу?“; подключился к
// машине — почему-то не переключил на поездку». Причины, по одной на сигнал:
// 5. Приезд с НЕ-дорогой в ленте молчал всегда — «роутер мигнул, не повод».
//    Но так же молча проходил и переезд Летово → дом с открытой «Встречей».
//    Теперь мигание и переезд различаются по зафиксированному ОТЪЕЗДУ:
//    сеть прошлого места пропала раньше, чем началось дело, и место
//    сменилось (или пропадала дольше получаса) — значит, он перемещался, и
//    дело в ленте устарело. Спрашиваем. Нет отъезда — молчим, как раньше.
// 6. Места «по видимости» (Летово) смотрели на wm.scanResults только по
//    своему скану раз в полчаса, а система отдаёт КЭШ: сеть, услышанная два
//    часа назад, «висела в эфире» бесконечно, и отъезд не наступал никогда.
//    Теперь результаты фильтруются по возрасту, и автопилот подписан на
//    SCAN_RESULTS_AVAILABLE — сканы, которые система делает сама, тоже идут
//    в дело. Слабый сигнал — это «ещё здесь», но не «приехал».
// 7. Машина узнавалась по имени, а имя система отдаёт не всегда (нет кэша,
//    отозвано BLUETOOTH_CONNECT) — теперь ещё и по адресу. И вместо вопроса
//    «сел в машину?» поездка НАЧИНАЕТСЯ САМА: владелец — «всегда переключать
//    текущее дело на передвижение на машине». Кнопка «Отменить» есть.
// 8. Пуши шли каналом IMPORTANCE_DEFAULT — на Samsung это тихая строка в
//    шторке, которую никто не видит. Вопрос, требующий ответа, — HIGH. И
//    разрешение на уведомления никто не запрашивал: если его нет, теперь об
//    этом говорит [blockers].
//
// И ещё одно, из жизни: к сети в Летово владелец не подключается — пароля
// нет и не надо. Но она появляется в эфире ровно тогда, когда он приехал.
// Поэтому у места есть два режима: «по подключению» (дом — точнее) и «по
// видимости» (Летово — ловится сканом эфира).
/**
 * Причина, по которой автопилот слеп, и код кнопки, которая её чинит.
 * Отдельный тип верхнего уровня, чтобы настройки могли спросить причину и
 * при выключенной службе.
 */
data class AutoBlocker(val text: String, val fix: String)

class AutoPilot(
    private val service: PravkaAccessibilityService,
    private val app: PravkaApp,
    private val scope: CoroutineScope,
) {

    companion object {
        /**
         * Канал с IMPORTANCE_HIGH. Прежний «pravka-auto» был DEFAULT, а
         * важность канала после создания менять нельзя — только завести новый
         * и снести старый.
         */
        const val CHANNEL = "pravka-auto-hi"
        private const val OLD_CHANNEL = "pravka-auto"
        // Три минуты после потери сети: роутер мигнул — не отъезд.
        private const val LEAVE_DELAY_MS = 3 * 60_000L
        // Место «по видимости» считается покинутым, когда его нет в СВЕЖИХ
        // сканах пять минут. Свой дребезг у эфира больше, чем у подключения.
        private const val VISIBLE_LEAVE_MS = 5 * 60_000L
        // Скан старше десяти минут — кэш, а не эфир: по нему ничего не решаем.
        private const val SCAN_FRESH_MS = 10 * 60_000L
        // Машина отключилась: две минуты на «заглушил у магазина и поехал».
        private const val CAR_OFF_DELAY_MS = 2 * 60_000L
        // «Точно ещё …?» — только по делам длиннее получаса.
        private const val SEDENTARY_MIN_MS = 30 * 60_000L
        // И не чаще раза в двадцать минут: взял телефон — не допрос же.
        private const val STILL_THROTTLE_MS = 20 * 60_000L
        // Повторная поимка того же места — не событие.
        private const val ARRIVE_DEBOUNCE_MS = 10 * 60_000L
        // Про одну и ту же незнакомую сеть спрашиваем раз в сутки.
        private const val UNKNOWN_ASK_MS = 24 * 3_600_000L
        // Сеть слышно, но еле-еле — это «школа где-то за забором», а не
        // приезд. Приездом считаем только уверенный сигнал.
        private const val NEAR_DBM = -75
        // Фоновому приложению система разрешает один скан в полчаса. Ровно
        // столько и просим: чаще всё равно откажут, реже — приезд заметим
        // с опозданием. Системные сканы приходят сверх этого — через
        // SCAN_RESULTS_AVAILABLE.
        private const val SCAN_EVERY_MS = 30 * 60_000L

        const val FIX_WIFI = "wifi"
        const val FIX_LOCATION = "loc_perm"
        const val FIX_BACKGROUND = "loc_bg"
        const val FIX_LOCATION_SYS = "loc_sys"
        const val FIX_NOTIF = "notif"
        const val FIX_NOTIF_CHANNEL = "notif_channel"
        const val FIX_BT = "bt_perm"

        /**
         * ПОЧЕМУ НЕ РАБОТАЕТ — прямым текстом. Автопилот замолкает по
         * полудюжине системных причин, и снаружи все они выглядят одинаково:
         * «ничего не происходит». Владелец так и сказал — «вайфаи что-то
         * совсем не работают», — и узнать, что именно мешает, было неоткуда.
         *
         * Пустой список — с разрешениями всё в порядке, дело не в них.
         * Функция статическая: настройки должны уметь показать причину и
         * тогда, когда служба вовсе не запущена. [carBt] — имя машины из
         * настроек: без него проверка Bluetooth не нужна.
         */
        fun blockers(ctx: Context, carBt: String = ""): List<AutoBlocker> = buildList {
            val wifiOn = runCatching {
                (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .isWifiEnabled
            }.getOrDefault(true)
            if (!wifiOn) {
                add(AutoBlocker("Wi-Fi выключен — сетей не видно вообще.", FIX_WIFI))
            }
            val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!fine) {
                add(
                    AutoBlocker(
                        "Нет разрешения «Местоположение» — Android прячет имя сети " +
                            "и отдаёт «<unknown ssid>».",
                        FIX_LOCATION,
                    )
                )
            } else if (Build.VERSION.SDK_INT >= 29 &&
                ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                // Самая частая причина молчания. «Разрешить при использовании
                // приложения» даёт имя сети, только пока Правка открыта. А
                // приезд ловит служба, из фона — и получает пустоту.
                add(
                    AutoBlocker(
                        "Местоположение разрешено только при открытой Правке. Приезд " +
                            "ловит служба из фона — ей имя сети не отдают. Нужно " +
                            "«Разрешать всегда».",
                        FIX_BACKGROUND,
                    )
                )
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val on = runCatching { lm?.isLocationEnabled ?: true }.getOrDefault(true)
                if (!on) {
                    add(
                        AutoBlocker(
                            "В системе выключена геолокация — имя сети закрыто даже " +
                                "с разрешением.",
                            FIX_LOCATION_SYS,
                        )
                    )
                }
            }
            // Автопилот разговаривает пушами. Без разрешения на уведомления
            // (Android 13+ его надо запросить, а никто не запрашивал) он всё
            // делает — закрывает дорогу, спрашивает — и всё это в пустоту.
            // Владелец так и описал: «останавливает передвижение, но не говорит».
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm != null) {
                if (!nm.areNotificationsEnabled()) {
                    add(
                        AutoBlocker(
                            "Уведомления Правки выключены — автопилот спрашивает " +
                                "пушем, и его никто не увидит.",
                            FIX_NOTIF,
                        )
                    )
                } else {
                    val ch = nm.getNotificationChannel(CHANNEL)
                    if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) {
                        add(
                            AutoBlocker(
                                "Канал «Автопилот Засечки» заглушен в системе — " +
                                    "вопросы приходят молча.",
                                FIX_NOTIF_CHANNEL,
                            )
                        )
                    }
                }
            }
            if (carBt.isNotBlank() && Build.VERSION.SDK_INT >= 31 &&
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(
                    AutoBlocker(
                        "Нет доступа к Bluetooth-устройствам — подключение машины " +
                            "служба не увидит.",
                        FIX_BT,
                    )
                )
            }
        }

        const val WHAT_MOVE_CAR = "move_car"
        const val WHAT_MOVE_WALK = "move_walk"
        const val WHAT_STILL_DONE = "still_done"
        const val WHAT_CLOSE_OPEN = "close_open"
        /** Дорога задним числом: с момента отъезда до сейчас, закрытая. */
        const val WHAT_TRIP_BETWEEN = "trip_between"
        /** Поездка началась сама по Bluetooth, а владелец не в машине. */
        const val WHAT_CAR_UNDO = "car_undo"

        const val CAR_TITLE = "Поездка на машине"
        const val CAR_CATEGORY = "Передвижение: транспорт"

        /** Дорога узнаётся по категории ИЛИ по названию: «Поездка домой». */
        fun travelish(e: ZasechkaStore.Entry): Boolean = AutoPilotRules.travelish(e.title, e.category)

        /** Тренировка: приехал домой — скорее всего закончил, но спросим. */
        fun sporty(e: ZasechkaStore.Entry): Boolean = AutoPilotRules.sporty(e.category)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val jobs = mutableListOf<Job>()

    @Volatile private var places: Map<String, String> = emptyMap()
    /** Подмножество [places], которое ловится сканом эфира, а не подключением. */
    @Volatile private var visible: Set<String> = emptySet()
    @Volatile private var carBt: String = ""
    @Volatile private var carBtAddr: String = ""
    @Volatile private var autoArrive = true
    @Volatile private var askLeave = true
    @Volatile private var askCar = true
    @Volatile private var autoCarStart = true
    @Volatile private var askStill = true

    /** Что служба видит прямо сейчас — для строки состояния в настройках. */
    @Volatile var seenSsid: String = ""
        private set
    @Volatile private var lastFire: String = ""

    private var lastPlace = ""
    private var lastArriveAt = 0L
    private var pendingLeave: Runnable? = null
    /**
     * Последний зафиксированный отъезд: откуда и когда пропала сеть. Это
     * доказательство перемещения для правил приезда — пишется всегда, даже
     * когда вопрос про отъезд выключен.
     */
    private var leftPlace = ""
    private var leftAtMs = 0L
    private var lastStillAsk = 0L
    private val unknownAsked = HashMap<String, Long>()
    private var motionArmed = false
    /** Сети «по видимости», которые слышно прямо сейчас. */
    private val around = HashSet<String>()
    /** Когда каждую сеть «по видимости» слышали последний раз (свежим сканом). */
    private val lastHeard = HashMap<String, Long>()
    private val pushedSeen = HashSet<String>()
    private var lastScanAt = 0L
    private var pendingCarOff: Runnable? = null
    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var btReceiver: BroadcastReceiver? = null
    private var scanReceiver: BroadcastReceiver? = null

    /** Одной строкой для настроек: видно, живой автопилот или спит впустую. */
    fun statusLine(): String = buildString {
        append(if (seenSsid.isBlank()) "Сеть не вижу" else "Вижу сеть «$seenSsid»")
        append(" · мест: ${places.size}")
        if (visible.isNotEmpty()) append(" (по видимости: ${visible.size})")
        if (around.isNotEmpty()) {
            append(" · в эфире: ")
            append(around.mapNotNull { places[it] }.joinToString(", "))
        }
        if (carBt.isNotBlank()) append(" · машина: $carBt")
        if (lastFire.isNotBlank()) append(" · последнее: $lastFire")
    }

    fun start() {
        jobs += scope.launch { app.settings.autoPlacesFlow.collect { places = it } }
        jobs += scope.launch { app.settings.autoVisibleFlow.collect { visible = it } }
        jobs += scope.launch { app.settings.autoCarBtFlow.collect { carBt = it } }
        jobs += scope.launch { app.settings.autoCarBtAddrFlow.collect { carBtAddr = it } }
        jobs += scope.launch { app.settings.autoArriveFlow.collect { autoArrive = it } }
        jobs += scope.launch { app.settings.autoLeaveAskFlow.collect { askLeave = it } }
        jobs += scope.launch { app.settings.autoCarAskFlow.collect { askCar = it } }
        jobs += scope.launch { app.settings.autoCarStartFlow.collect { autoCarStart = it } }
        jobs += scope.launch { app.settings.autoStillAskFlow.collect { askStill = it } }
        startWifiWatch()
        startScanWatch()
        startBtWatch()
        // Сеть могла подключиться до старта службы — колбэка по ней не будет.
        handler.postDelayed({ pollWifi() }, 3_000)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        pendingLeave?.let { handler.removeCallbacks(it) }
        pendingLeave = null
        pendingCarOff?.let { handler.removeCallbacks(it) }
        pendingCarOff = null
        runCatching { netCallback?.let { connectivity?.unregisterNetworkCallback(it) } }
        runCatching { btReceiver?.let { service.unregisterReceiver(it) } }
        runCatching { scanReceiver?.let { service.unregisterReceiver(it) } }
        netCallback = null
        btReceiver = null
        scanReceiver = null
    }

    // ---- Wi-Fi: приезд и отъезд ----

    @SuppressLint("NewApi", "MissingPermission")
    private fun startWifiWatch() {
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity = cm
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            // Только колбэк, зарегистрированный с этим флагом, получает SSID
            // нередактированным — на этом и держится всё определение мест.
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    onSsid(cleanSsid((caps.transportInfo as? WifiInfo)?.ssid))
                }

                override fun onLost(network: Network) = onWifiLost()
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onSsid(cleanSsid(legacySsid()))
                }

                override fun onLost(network: Network) = onWifiLost()
            }
        }
        netCallback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacySsid(): String = runCatching {
        (service.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .connectionInfo?.ssid.orEmpty()
    }.getOrDefault("")

    /** Система отдаёт SSID в кавычках, а без доступа — «<unknown ssid>». */
    private fun cleanSsid(raw: String?): String {
        val s = raw.orEmpty().trim().trim('"')
        return if (s.isBlank() || s.equals("<unknown ssid>", ignoreCase = true) ||
            s.equals("<unknown>", ignoreCase = true)
        ) "" else s
    }

    /**
     * Опрос на всякий случай: колбэк мог не прийти (служба поднялась позже
     * подключения, Doze проглотил событие). Отъезд отсюда НЕ объявляем —
     * пустой ответ опроса бывает и просто редактированием данных.
     */
    @SuppressLint("MissingPermission")
    private fun pollWifi() {
        val cm = connectivity ?: return
        val ssid = runCatching {
            val net = cm.activeNetwork ?: return
            val caps = cm.getNetworkCapabilities(net) ?: return
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
            cleanSsid((caps.transportInfo as? WifiInfo)?.ssid).ifBlank { cleanSsid(legacySsid()) }
        }.getOrDefault("")
        if (ssid.isNotBlank()) onSsid(ssid)
    }

    private fun onSsid(ssid: String) {
        if (ssid.isBlank() || ssid == seenSsid) return
        seenSsid = ssid
        // Любая увиденная сеть попадает в список для настроек — там владелец
        // и называет её местом («это дача»), как он и просил.
        scope.launch { runCatching { app.settings.addAutoSeen(ssid, System.currentTimeMillis()) } }
        val place = places[ssid]
        if (place == null) {
            askAboutUnknown(ssid)
            return
        }
        reachedPlace(place, "Wi-Fi «$ssid»")
    }

    /**
     * Приезд, общий вход: и по подключению, и по видимости в эфире. Дальше
     * они неразличимы — место есть место.
     */
    private fun reachedPlace(place: String, how: String) {
        // Вернулись в известное место — «уехал?» отменяется.
        pendingLeave?.let { handler.removeCallbacks(it) }
        pendingLeave = null
        val now = System.currentTimeMillis()
        if (place == lastPlace && now - lastArriveAt < ARRIVE_DEBOUNCE_MS) return
        lastPlace = place
        lastArriveAt = now
        app.eventLog.add("автопилот: $how — место «$place»")
        if (!autoArrive) return
        scope.launch { onArrived(place, now) }
    }

    // ---- Места «по видимости»: эфир ----

    /**
     * Система сканирует эфир сама — ища сети, по своим часам, чаще при
     * включённом экране. Раньше мы смотрели только результаты своего скана
     * раз в полчаса, и приезд в Летово замечался с получасовым опозданием,
     * а отъезд — никогда (см. пункт 6 в шапке). Теперь каждый скан — наш.
     */
    private fun startScanWatch() {
        val rec = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) readScan()
            }
        }
        scanReceiver = rec
        runCatching {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                service.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED)
            } else {
                service.registerReceiver(rec, filter)
            }
        }
    }

    private fun mayScan(): Boolean {
        val ctx = service.applicationContext
        // На Android 13+ вместо местоположения годится «Устройства
        // поблизости» — сканировать разрешает любое из двух.
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED)
    }

    /**
     * Свой скан — не чаще раза в полчаса, столько фоновому приложению и
     * положено. Сканируем и когда мест «по видимости» ещё нет: иначе сеть,
     * к которой не подключаешься, неоткуда взять в списке — и назвать её
     * местом нечем. Результаты читаем и без своего скана: кэш мог обновить
     * системный.
     */
    @SuppressLint("MissingPermission")
    private fun pollVisible() {
        if (!mayScan()) return
        val wm = runCatching {
            service.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        if (now - lastScanAt >= SCAN_EVERY_MS) {
            lastScanAt = now
            @Suppress("DEPRECATION")
            runCatching { wm.startScan() }
        }
        readScan()
    }

    /**
     * Разбор результатов скана. Система отдаёт КЭШ — всё, что слышала когда
     * бы то ни было с последней очистки, — поэтому каждая запись проверяется
     * на возраст: старше десяти минут — не эфир, а воспоминание, и по нему
     * ничего не решаем. Ни приезда, ни отъезда: отъезд — это когда СВЕЖИЙ скан
     * сети не содержит, а не когда скана не было.
     */
    @SuppressLint("MissingPermission")
    private fun readScan() {
        if (!mayScan()) return
        val wm = runCatching {
            service.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull() ?: return
        val results = runCatching { wm.scanResults }.getOrNull().orEmpty()
        if (results.isEmpty()) return
        val nowEl = SystemClock.elapsedRealtime()
        // timestamp у ScanResult — микросекунды с загрузки.
        val fresh = results.filter { nowEl - it.timestamp / 1000 <= SCAN_FRESH_MS }
        if (fresh.isEmpty()) return
        val now = System.currentTimeMillis()
        // SSID → лучший уровень среди свежих точек этой сети.
        val heard = HashMap<String, Int>()
        for (r in fresh) {
            @Suppress("DEPRECATION")
            val ssid = cleanSsid(r.SSID)
            if (ssid.isBlank()) continue
            val prev = heard[ssid]
            if (prev == null || r.level > prev) heard[ssid] = r.level
        }
        val strong = heard.filterValues { it >= NEAR_DBM }.keys
        // Всё, что слышно уверенно, попадает в список настроек — иначе сеть,
        // к которой не подключаешься, назвать местом просто негде.
        val newcomers = strong.filter { !places.containsKey(it) && pushedSeen.add(it) }
        if (newcomers.isNotEmpty()) {
            scope.launch { runCatching { app.settings.addAutoSeenAll(newcomers.take(6), now) } }
        }
        for (ssid in visible) {
            val place = places[ssid] ?: continue
            if (heard.containsKey(ssid)) {
                // Слышно — значит, ещё здесь, даже если слабо. Но ПРИЕЗД —
                // только на уверенное появление: услышать школьный Wi-Fi можно
                // и с соседней улицы.
                lastHeard[ssid] = now
                if (strong.contains(ssid) && around.add(ssid)) {
                    reachedPlace(place, "вижу сеть «$ssid»")
                }
            } else if (around.contains(ssid)) {
                val since = lastHeard[ssid] ?: now
                if (now - since >= VISIBLE_LEAVE_MS) {
                    around.remove(ssid)
                    lastHeard.remove(ssid)
                    // Пять минут без сети в свежих сканах — дребезг уже отсеян,
                    // ждать ещё три минуты незачем.
                    onLeftPlace(place, since, delayMs = 0L)
                }
            }
        }
    }

    // ---- Приезд ----

    /**
     * Приехал в место. Решает [AutoPilotRules.arrival]; здесь — только
     * действия и слова. Смотрим ТОЛЬКО основной трек: открытая параллель
     * («слушаю Акунина») — не дело, и закрывать её приездом нельзя.
     */
    private suspend fun onArrived(place: String, now: Long) {
        val open = app.zasechkaStore.openEntry()
        val verdict = AutoPilotRules.arrival(
            openTitle = open?.title,
            openCategory = open?.category,
            openStart = open?.start ?: 0L,
            place = place,
            leftPlace = leftPlace,
            leftAtMs = leftAtMs,
            now = now,
        )
        when (verdict) {
            AutoPilotRules.Arrival.CLOSE_TRAVEL -> {
                val closed = app.zasechkaEngine.closeOpen() ?: return
                lastFire = "приехал «$place» ${timeHm(now)}"
                notify(
                    "✓ Приехал: $place",
                    "«${closed.title}» закрыта, ${closed.durationMin()} мин. Что теперь? " +
                        "Открытого дела нет.",
                    listOf(sayAction()),
                )
                app.eventLog.add("автопилот: приехал «$place» — закрыл «${closed.title}»")
            }
            AutoPilotRules.Arrival.ASK_SPORT -> {
                val o = open ?: return
                // Молча закрывать тренировку нельзя: он мог заехать домой за
                // водой и поехать дальше. Но и висеть она не должна.
                lastFire = "спросил про «${o.title}»"
                notify(
                    "Приехал: $place",
                    "«${o.title}» ещё идёт, ${o.durationMin(now)} мин. Закончил?",
                    listOf(
                        action("Закрыть «${o.title.take(18)}»", WHAT_CLOSE_OPEN, now, ""),
                        sayAction(),
                    ),
                )
                app.eventLog.add("автопилот: приехал «$place» — спросил про «${o.title}»")
            }
            AutoPilotRules.Arrival.ASK_STILL -> {
                val o = open ?: return
                lastFire = "приехал «$place», спросил про «${o.title}»"
                val moved = leftPlace != place
                notify(
                    "Приехал: $place",
                    "В ленте всё ещё «${o.title}» с ${timeHm(o.start)} " +
                        "(${o.durationMin(now)} мин). " +
                        if (moved) {
                            "Сеть «$leftPlace» пропала в ${timeHm(leftAtMs)} — " +
                                "«Ехал» закроет дело там и запишет дорогу до сейчас."
                        } else {
                            "Сети не было с ${timeHm(leftAtMs)}. Всё ещё оно?"
                        },
                    listOf(
                        if (moved) action("Ехал с ${timeHm(leftAtMs)}", WHAT_TRIP_BETWEEN, leftAtMs, leftPlace)
                        else action("Закончил в ${timeHm(leftAtMs)}", WHAT_CLOSE_OPEN, leftAtMs, ""),
                        sayAction(),
                    ),
                )
                app.eventLog.add(
                    "автопилот: приехал «$place» из «$leftPlace», открыто «${o.title}» — спросил"
                )
            }
            AutoPilotRules.Arrival.ASK_WHAT -> {
                lastFire = "приехал «$place», спросил, что делает"
                notify(
                    "Приехал: $place",
                    "Открытого дела нет. Сеть «$leftPlace» пропала в ${timeHm(leftAtMs)} — " +
                        "дорога не записана. Что делаешь?",
                    listOf(
                        action("Ехал с ${timeHm(leftAtMs)}", WHAT_TRIP_BETWEEN, leftAtMs, leftPlace),
                        sayAction(),
                    ),
                )
                app.eventLog.add("автопилот: приехал «$place» из «$leftPlace», открытых дел нет — спросил")
            }
            AutoPilotRules.Arrival.SILENT -> {
                app.eventLog.add(
                    if (open == null) "автопилот: приехал «$place», открытых дел нет, отъезда не было — промолчал"
                    else "автопилот: приехал «$place», открыто «${open.title}» [${open.category}] — " +
                        "не дорога, перемещения не видно, промолчал"
                )
            }
        }
    }

    /** Незнакомая сеть — спрашиваем, что это за место (раз в сутки на сеть). */
    private fun askAboutUnknown(ssid: String) {
        val now = System.currentTimeMillis()
        val asked = unknownAsked[ssid] ?: 0L
        if (now - asked < UNKNOWN_ASK_MS) return
        unknownAsked[ssid] = now
        app.eventLog.add("автопилот: новая сеть «$ssid» — спросил, что это за место")
        notify(
            "Новая сеть: «$ssid»",
            "Что это за место? Открой Настройки → Засечка → Автопилот и назови " +
                "её — дом, дача, офис. Не место — просто смахни.",
            emptyList(),
            openSettings = true,
        )
    }

    private fun onWifiLost() {
        val fromPlace = places[seenSsid]
        seenSsid = ""
        if (fromPlace == null) return
        onLeftPlace(fromPlace, System.currentTimeMillis(), LEAVE_DELAY_MS)
    }

    /**
     * Уехал: сеть пропала — из-под ног (отключились) или из эфира. Факт
     * отъезда запоминается СРАЗУ и всегда — им потом доказывается
     * перемещение при приезде. Вопрос — через [delayMs], если не вернёмся.
     */
    private fun onLeftPlace(fromPlace: String, atMs: Long, delayMs: Long) {
        leftPlace = fromPlace
        leftAtMs = atMs
        app.eventLog.add("автопилот: потерял «$fromPlace» в ${timeHm(atMs)}")
        if (!askLeave) return
        pendingLeave?.let { handler.removeCallbacks(it) }
        val ask = Runnable {
            pendingLeave = null
            scope.launch {
                val open = app.zasechkaStore.openEntry()
                if (open != null && travelish(open)) {
                    app.eventLog.add("автопилот: потерял «$fromPlace», дорога уже идёт — молчу")
                    return@launch
                }
                lastFire = "спросил про отъезд из «$fromPlace»"
                notify(
                    "Уехал из «$fromPlace»?",
                    (if (open != null) {
                        "В ленте всё ещё «${open.title}», ${open.durationMin()} мин. " +
                            "Дорога закроет его в ${timeHm(atMs)} и пойдёт с этого момента. "
                    } else "Начну с момента потери сети, ${timeHm(atMs)}. ") +
                        "Нет — просто смахни.",
                    listOf(
                        action("Транспорт", WHAT_MOVE_CAR, atMs, fromPlace),
                        action("Пешком", WHAT_MOVE_WALK, atMs, fromPlace),
                    ),
                )
                app.eventLog.add(
                    "автопилот: потерял «$fromPlace» — спросил про передвижение" +
                        (open?.let { ", открыто «${it.title}»" } ?: "")
                )
            }
        }
        pendingLeave = ask
        if (delayMs <= 0L) ask.run() else handler.postDelayed(ask, delayMs)
    }

    // ---- Bluetooth машины ----

    private fun startBtWatch() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val rec = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (carBt.isBlank() && carBtAddr.isBlank()) return
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                // Без BLUETOOTH_CONNECT имя не отдаётся — тогда выручает адрес.
                val name = runCatching { device?.name }.getOrNull().orEmpty()
                val addr = runCatching { device?.address }.getOrNull().orEmpty()
                if (!AutoPilotRules.isCar(name, addr, carBt, carBtAddr)) {
                    if (name.isNotBlank() || addr.isNotBlank()) {
                        app.eventLog.add("автопилот: BT «${name.ifBlank { addr }}» — не машина")
                    }
                    return
                }
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> onCarConnected()
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onCarDisconnected()
                }
            }
        }
        btReceiver = rec
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                service.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED)
            } else {
                service.registerReceiver(rec, filter)
            }
        }
    }

    /**
     * Машина подключилась. Владелец: «просто всегда переключать текущее дело
     * на передвижение на машине». Так и делаем — с кнопкой «Отменить» в пуше:
     * магнитола ловит телефон и с балкона, если машина под окном. Если
     * дорога уже идёт (сказал голосом, вышел из Летово кнопкой) — не трогаем.
     */
    private fun onCarConnected() {
        pendingCarOff?.let { handler.removeCallbacks(it) }
        pendingCarOff = null
        val at = System.currentTimeMillis()
        scope.launch {
            val open = app.zasechkaStore.openEntry()
            if (open != null && travelish(open)) {
                app.eventLog.add("автопилот: BT «$carBt» подключился, «${open.title}» уже идёт")
                return@launch
            }
            if (autoCarStart) {
                val entry = app.zasechkaStore.startEntry(
                    start = at,
                    raw = "",
                    title = CAR_TITLE,
                    category = CAR_CATEGORY,
                    client = "",
                    useful = 0,
                    // Источник — как у кнопки «Поехали», не "auto". Иначе книга
                    // за рулём из Слушалки (insertAutoFact) не найдёт «занятого
                    // владельцем» времени и лягет в ОСНОВНОЙ трек поверх
                    // поездки — два авто-факта внахлёст, чего лента не терпит.
                    // Ложное срабатывание чинит кнопка «Отменить» в пуше.
                    source = "voice",
                )
                app.zasechkaSync.kickSoon(scope)
                lastFire = "машина в ${timeHm(at)}, поездка начата"
                notify(
                    "🚗 Поехали: $CAR_TITLE",
                    "С ${timeHm(at)}, по Bluetooth «$carBt»." +
                        (open?.let { " «${it.title}» закрыто, ${it.durationMin(at)} мин." } ?: "") +
                        " Не в машине — отмени.",
                    listOf(action("Отменить", WHAT_CAR_UNDO, at, "", id = entry.id, prevId = open?.id ?: 0L)),
                )
                app.eventLog.add(
                    "автопилот: BT «$carBt» подключился — начата «$CAR_TITLE»" +
                        (open?.let { ", закрыто «${it.title}»" } ?: "")
                )
            } else if (askCar) {
                lastFire = "машина в ${timeHm(at)}"
                notify(
                    "Сел в машину?",
                    "«$carBt» подключилась в ${timeHm(at)}. Нет — просто смахни.",
                    listOf(action("Поехали", WHAT_MOVE_CAR, at, "")),
                )
                app.eventLog.add("автопилот: BT «$carBt» подключился — спросил про поездку")
            }
        }
    }

    /**
     * Машина отключилась — двигатель заглушен. Дорогу НЕ закрываем сами:
     * приезд домой и в Летово закроет её Wi-Fi, а «заглушил у магазина» —
     * не приезд. Через две минуты без переподключения — вопрос с кнопкой.
     */
    private fun onCarDisconnected() {
        val at = System.currentTimeMillis()
        pendingCarOff?.let { handler.removeCallbacks(it) }
        val ask = Runnable {
            pendingCarOff = null
            scope.launch {
                val open = app.zasechkaStore.openEntry() ?: return@launch
                if (!travelish(open)) return@launch
                lastFire = "машина отключилась в ${timeHm(at)}"
                notify(
                    "Машина отключилась",
                    "«${open.title}» идёт ${open.durationMin()} мин, «$carBt» отвалилась в " +
                        "${timeHm(at)}. Приехал?",
                    listOf(
                        action("Приехал в ${timeHm(at)}", WHAT_CLOSE_OPEN, at, ""),
                        sayAction(),
                    ),
                )
                app.eventLog.add("автопилот: BT «$carBt» отключился при «${open.title}» — спросил")
            }
        }
        pendingCarOff = ask
        handler.postDelayed(ask, CAR_OFF_DELAY_MS)
    }

    // ---- «Точно ещё …?» по датчику значимого движения ----

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            motionArmed = false
            onSignificantMotion()
        }
    }

    /** Тик службы (раз в ~5 минут): опрос сети и взвод датчика движения. */
    fun tick() {
        pollWifi()
        pollVisible()
        if (!askStill) return
        scope.launch {
            val open = app.zasechkaStore.openEntry() ?: return@launch
            if (!AutoPilotRules.sedentary(open.category)) return@launch
            if (System.currentTimeMillis() - open.start < SEDENTARY_MIN_MS) return@launch
            armMotion()
        }
    }

    private fun armMotion() {
        if (motionArmed) return
        val sm = service.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        motionArmed = runCatching { sm.requestTriggerSensor(motionListener, sensor) }.getOrDefault(false)
    }

    private fun onSignificantMotion() {
        val now = System.currentTimeMillis()
        if (!askStill || now - lastStillAsk < STILL_THROTTLE_MS) return
        scope.launch {
            val open = app.zasechkaStore.openEntry() ?: return@launch
            if (!AutoPilotRules.sedentary(open.category)) return@launch
            if (now - open.start < SEDENTARY_MIN_MS) return@launch
            lastStillAsk = now
            lastFire = "движение при «${open.title}»"
            notify(
                "Всё ещё «${open.title}»?",
                "Телефон задвигался в ${timeHm(now)}. Продолжаешь — просто смахни.",
                listOf(action("Закончил тогда", WHAT_STILL_DONE, now, "")),
            )
            app.eventLog.add("автопилот: движение при «${open.title}» — спросил")
        }
    }

    // ---- Кнопки уведомлений (через AutoPilotActivity) ----

    fun onAction(what: String, atMs: Long, fromPlace: String, id: Long = 0L, prevId: Long = 0L) {
        scope.launch {
            val now = System.currentTimeMillis()
            when (what) {
                WHAT_MOVE_CAR, WHAT_MOVE_WALK -> {
                    val walk = what == WHAT_MOVE_WALK
                    val entry = app.zasechkaStore.startEntry(
                        start = atMs.coerceIn(1L, now),
                        raw = "",
                        title = when {
                            fromPlace.isBlank() && walk -> "Дорога пешком"
                            fromPlace.isBlank() -> CAR_TITLE
                            walk -> "Дорога из «$fromPlace» пешком"
                            else -> "Поездка из «$fromPlace»"
                        },
                        category = if (walk) "Передвижение: пешком" else CAR_CATEGORY,
                        client = "",
                        useful = 0,
                        // Кнопку нажал владелец — это его клейм, не робота:
                        // не гибнет в clear-and-refill и не режется сплайсом.
                        source = "voice",
                    )
                    app.zasechkaSync.kickSoon(scope)
                    Feedback.toast(app, "⏱ ${entry.title} — с ${timeHm(entry.start)}")
                    app.eventLog.add("автопилот: начато «${entry.title}»")
                }
                WHAT_TRIP_BETWEEN -> {
                    // Дорога, которую не записали: от потери сети прошлого
                    // места до приезда сюда. Закрытая — мы уже здесь; открытое
                    // дело закрывается её началом, дальше лента пуста и ждёт
                    // слова владельца.
                    val start = atMs.coerceIn(1L, now - 60_000L)
                    val entry = app.zasechkaStore.startEntry(
                        start = start,
                        raw = "",
                        title = if (fromPlace.isBlank()) CAR_TITLE else "Поездка из «$fromPlace»",
                        category = CAR_CATEGORY,
                        client = "",
                        useful = 0,
                        source = "voice",
                    )
                    val closed = app.zasechkaStore.closeOpen(now)
                    app.zasechkaSync.kickSoon(scope)
                    Feedback.toast(
                        app,
                        "⏱ ${entry.title}: ${timeHm(start)}–${timeHm(now)}. Что теперь — скажи «З»",
                        long = true,
                    )
                    app.eventLog.add(
                        "автопилот: дорога задним числом «${entry.title}» " +
                            "${timeHm(start)}–${timeHm(now)}" +
                            (closed?.let { "" } ?: ", закрыть не вышло")
                    )
                }
                WHAT_STILL_DONE, WHAT_CLOSE_OPEN -> {
                    val at = atMs.coerceIn(1L, now)
                    val closed = app.zasechkaStore.closeOpen(at)
                    if (closed != null) app.zasechkaSync.kickSoon(scope)
                    Feedback.toast(
                        app,
                        if (closed == null) "Открытого дела уже нет"
                        else "⏹ «${closed.title}» закрыто на ${timeHm(at)}",
                    )
                    if (closed != null) {
                        app.eventLog.add("автопилот: «${closed.title}» закрыто кнопкой уведомления")
                    }
                }
                WHAT_CAR_UNDO -> {
                    val back = app.zasechkaStore.revertAutoStart(id, prevId)
                    app.zasechkaSync.kickSoon(scope)
                    Feedback.toast(
                        app,
                        if (back != null) "↩︎ Поездка убрана, снова «${back.title}»"
                        else "↩︎ Поездка убрана",
                    )
                    app.eventLog.add(
                        "автопилот: «$CAR_TITLE» отменена кнопкой" +
                            (back?.let { ", вернулся к «${it.title}»" } ?: "")
                    )
                }
            }
        }
    }

    // ---- Обвязка ----

    private fun action(
        label: String,
        what: String,
        at: Long,
        place: String,
        id: Long = 0L,
        prevId: Long = 0L,
    ): Notification.Action {
        val intent = Intent(service, AutoPilotActivity::class.java)
            .putExtra(AutoPilotActivity.EXTRA_WHAT, what)
            .putExtra(AutoPilotActivity.EXTRA_AT, at)
            .putExtra(AutoPilotActivity.EXTRA_PLACE, place)
            .putExtra(AutoPilotActivity.EXTRA_ID, id)
            .putExtra(AutoPilotActivity.EXTRA_PREV, prevId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            service, (what + at).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            null as android.graphics.drawable.Icon?, label, pending,
        ).build()
    }

    /**
     * «Сказать»: тот же путь, что тап по «З» — микрофон и разбор Сонетом.
     * Ответ на «что теперь делаешь?» — это всегда диктовка, не кнопка.
     */
    private fun sayAction(): Notification.Action {
        val intent = Intent(service, ZasechkaQuickActivity::class.java)
            .putExtra(ZasechkaQuickActivity.EXTRA_WHAT, ZasechkaQuickActivity.W_RECORD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            service, 72, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            null as android.graphics.drawable.Icon?, "Сказать", pending,
        ).build()
    }

    private fun notify(
        title: String,
        text: String,
        actions: List<Notification.Action>,
        openSettings: Boolean = false,
    ) {
        runCatching {
            val nm = service.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                // Вопрос, на который ждут ответа, — HIGH: всплывает поверх
                // экрана. DEFAULT на Samsung — тихая строка в шторке, и
                // владелец её не видел («останавливает, но не говорит»).
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, "Автопилот Засечки",
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
                runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }
            }
            val b = Notification.Builder(service, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_tile)
                .setAutoCancel(true)
            if (openSettings) {
                b.setContentIntent(
                    PendingIntent.getActivity(
                        service, 71,
                        Intent(service, ru.zf.pravka.MainActivity::class.java)
                            .putExtra(
                                ru.zf.pravka.MainActivity.EXTRA_TAB,
                                ru.zf.pravka.MainActivity.TAB_SETTINGS,
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
            }
            actions.forEach { b.addAction(it) }
            nm.notify((title + text).hashCode(), b.build())
        }.onFailure { app.eventLog.add("автопилот: уведомление не показалось — ${it.message}") }
    }

    private fun timeHm(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
}
