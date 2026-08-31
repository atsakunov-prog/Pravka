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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.ui.Feedback

// Автопилот Засечки: телефон сам замечает швы дня и либо чинит ленту, либо
// спрашивает одним пушем. Три сигнала, все дешёвые по батарее и без GPS.
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
// И ещё одно, из жизни: к сети в Летово владелец не подключается — пароля
// нет и не надо. Но она появляется в эфире ровно тогда, когда он приехал.
// Поэтому у места есть два режима: «по подключению» (дом — точнее) и «по
// видимости» (Летово — ловится сканом эфира на тике).
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
        private const val CHANNEL = "pravka-auto"
        // Три минуты после потери сети: роутер мигнул — не отъезд.
        private const val LEAVE_DELAY_MS = 3 * 60_000L
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
        // с опозданием.
        private const val SCAN_EVERY_MS = 30 * 60_000L

        const val FIX_WIFI = "wifi"
        const val FIX_LOCATION = "loc_perm"
        const val FIX_BACKGROUND = "loc_bg"
        const val FIX_LOCATION_SYS = "loc_sys"

        /**
         * ПОЧЕМУ НЕ РАБОТАЕТ — прямым текстом. Автопилот замолкает по
         * полудюжине системных причин, и снаружи все они выглядят одинаково:
         * «ничего не происходит». Владелец так и сказал — «вайфаи что-то
         * совсем не работают», — и узнать, что именно мешает, было неоткуда.
         *
         * Пустой список — с разрешениями всё в порядке, дело не в них.
         * Функция статическая: настройки должны уметь показать причину и
         * тогда, когда служба вовсе не запущена.
         */
        fun blockers(ctx: Context): List<AutoBlocker> = buildList {
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
        }

        const val WHAT_MOVE_CAR = "move_car"
        const val WHAT_MOVE_WALK = "move_walk"
        const val WHAT_STILL_DONE = "still_done"
        const val WHAT_CLOSE_OPEN = "close_open"

        /** Дорога узнаётся по категории ИЛИ по названию: «Поездка домой». */
        fun travelish(e: ZasechkaStore.Entry): Boolean {
            if (e.category.startsWith("Передвижение", ignoreCase = true)) return true
            val t = e.title.lowercase()
            return listOf("поездка", "поехал", "дорога", "едем", "еду ", "в пути", "такси")
                .any { t.contains(it) }
        }

        /** Тренировка: приехал домой — скорее всего закончил, но спросим. */
        fun sporty(e: ZasechkaStore.Entry): Boolean =
            e.category.startsWith("Спорт", ignoreCase = true)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val jobs = mutableListOf<Job>()

    @Volatile private var places: Map<String, String> = emptyMap()
    /** Подмножество [places], которое ловится сканом эфира, а не подключением. */
    @Volatile private var visible: Set<String> = emptySet()
    @Volatile private var carBt: String = ""
    @Volatile private var autoArrive = true
    @Volatile private var askLeave = true
    @Volatile private var askCar = true
    @Volatile private var askStill = true

    /** Что служба видит прямо сейчас — для строки состояния в настройках. */
    @Volatile var seenSsid: String = ""
        private set
    @Volatile private var lastFire: String = ""

    private var lastPlace = ""
    private var lastArriveAt = 0L
    private var pendingLeave: Runnable? = null
    private var leftPlace = ""
    private var leftAtMs = 0L
    private var lastStillAsk = 0L
    private val unknownAsked = HashMap<String, Long>()
    private var motionArmed = false
    /** Сети «по видимости», которые слышно прямо сейчас. */
    private val around = HashSet<String>()
    private val goneSince = HashMap<String, Long>()
    private val pushedSeen = HashSet<String>()
    private var lastScanAt = 0L
    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var btReceiver: BroadcastReceiver? = null

    /** Одной строкой для настроек: видно, живой автопилот или спит впустую. */
    fun statusLine(): String = buildString {
        append(if (seenSsid.isBlank()) "Сеть не вижу" else "Вижу сеть «$seenSsid»")
        append(" · мест: ${places.size}")
        if (visible.isNotEmpty()) append(" (по видимости: ${visible.size})")
        if (carBt.isNotBlank()) append(" · машина: $carBt")
        if (lastFire.isNotBlank()) append(" · последнее: $lastFire")
    }

    fun start() {
        jobs += scope.launch { app.settings.autoPlacesFlow.collect { places = it } }
        jobs += scope.launch { app.settings.autoVisibleFlow.collect { visible = it } }
        jobs += scope.launch { app.settings.autoCarBtFlow.collect { carBt = it } }
        jobs += scope.launch { app.settings.autoArriveFlow.collect { autoArrive = it } }
        jobs += scope.launch { app.settings.autoLeaveAskFlow.collect { askLeave = it } }
        jobs += scope.launch { app.settings.autoCarAskFlow.collect { askCar = it } }
        jobs += scope.launch { app.settings.autoStillAskFlow.collect { askStill = it } }
        startWifiWatch()
        startBtWatch()
        // Сеть могла подключиться до старта службы — колбэка по ней не будет.
        handler.postDelayed({ pollWifi() }, 3_000)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        pendingLeave?.let { handler.removeCallbacks(it) }
        pendingLeave = null
        runCatching { netCallback?.let { connectivity?.unregisterNetworkCallback(it) } }
        runCatching { btReceiver?.let { service.unregisterReceiver(it) } }
        netCallback = null
        btReceiver = null
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

    /**
     * Места «по видимости»: сеть в эфире = приехал. Скан отдаёт система —
     * своих просим не чаще раза в полчаса, столько фоновому приложению и
     * положено. Слабый сигнал не считаем: услышать школьный Wi-Fi можно и
     * с соседней улицы.
     */
    @SuppressLint("MissingPermission")
    private fun pollVisible() {
        // Сканируем и когда мест «по видимости» ещё нет: иначе сеть, к которой
        // не подключаешься, неоткуда взять в списке — и назвать её местом
        // нечем. Один скан в полчаса того стоит.
        val ctx = service.applicationContext
        val mayScan = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED)
        if (!mayScan) return
        // На Android 13+ вместо местоположения годится «Устройства
        // поблизости» — сканировать разрешает любое из двух.
        val wm = runCatching { ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager }
            .getOrNull() ?: return
        val now = System.currentTimeMillis()
        if (now - lastScanAt >= SCAN_EVERY_MS) {
            lastScanAt = now
            @Suppress("DEPRECATION")
            runCatching { wm.startScan() }
        }
        val results = runCatching { wm.scanResults }.getOrNull().orEmpty()
        if (results.isEmpty()) return
        @Suppress("DEPRECATION")
        val strong = results.filter { it.level >= NEAR_DBM }
            .map { cleanSsid(it.SSID) }
            .filter { it.isNotBlank() }
            .toSet()
        // Всё, что слышно уверенно, попадает в список настроек — иначе сеть,
        // к которой не подключаешься, назвать местом просто негде.
        val fresh = strong.filter { !places.containsKey(it) && pushedSeen.add(it) }
        if (fresh.isNotEmpty()) {
            scope.launch { runCatching { app.settings.addAutoSeenAll(fresh.take(6), now) } }
        }
        for (ssid in visible) {
            val place = places[ssid] ?: continue
            if (strong.contains(ssid)) {
                goneSince.remove(ssid)
                // Приезд — только на ПОЯВЛЕНИЕ сети: висит она в эфире часами.
                if (around.add(ssid)) reachedPlace(place, "вижу сеть «$ssid»")
            } else if (around.contains(ssid)) {
                val since = goneSince.getOrPut(ssid) { now }
                if (now - since >= LEAVE_DELAY_MS) {
                    around.remove(ssid)
                    goneSince.remove(ssid)
                    onLeftPlace(place, since)
                }
            }
        }
    }

    /** Приехал в место: дорогу закрываем сами, тренировку предлагаем закрыть. */
    private suspend fun onArrived(place: String, now: Long) {
        val open = app.zasechkaStore.all().lastOrNull { it.open }
        if (open == null) {
            lastFire = "приехал «$place», нечего закрывать"
            app.eventLog.add("автопилот: приехал «$place», открытых дел нет")
            return
        }
        when {
            travelish(open) -> {
                val closed = app.zasechkaEngine.closeOpen() ?: return
                lastFire = "приехал «$place» ${timeHm(now)}"
                notify(
                    "✓ Приехал: $place",
                    "«${closed.title}» закрыто, ${closed.durationMin()} мин.",
                    emptyList(),
                )
                app.eventLog.add("автопилот: приехал «$place» — закрыл «${closed.title}»")
            }
            sporty(open) -> {
                // Молча закрывать тренировку нельзя: он мог заехать домой за
                // водой и поехать дальше. Но и висеть она не должна.
                lastFire = "спросил про «${open.title}»"
                notify(
                    "Приехал: $place",
                    "«${open.title}» ещё идёт, ${open.durationMin(now)} мин. Закончил?",
                    listOf(action("Закрыть «${open.title.take(18)}»", WHAT_CLOSE_OPEN, now, "")),
                )
                app.eventLog.add("автопилот: приехал «$place» — спросил про «${open.title}»")
            }
            else -> {
                // Работал дома и роутер мигнул — не повод дёргать владельца.
                app.eventLog.add(
                    "автопилот: приехал «$place», но открыто «${open.title}» " +
                        "[${open.category}] — не дорога, промолчал"
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
        onLeftPlace(fromPlace, System.currentTimeMillis())
    }

    /** Уехал: сеть пропала — из-под ног (отключились) или из эфира. */
    private fun onLeftPlace(fromPlace: String, atMs: Long) {
        if (!askLeave) return
        leftPlace = fromPlace
        leftAtMs = atMs
        pendingLeave?.let { handler.removeCallbacks(it) }
        val ask = Runnable {
            pendingLeave = null
            scope.launch {
                val open = app.zasechkaStore.all().lastOrNull { it.open }
                if (open != null && travelish(open)) return@launch
                lastFire = "спросил про отъезд из «$leftPlace»"
                notify(
                    "Уехал из «$leftPlace»?",
                    "Начну с момента потери сети, ${timeHm(leftAtMs)}. Нет — просто смахни.",
                    listOf(
                        action("Транспорт", WHAT_MOVE_CAR, leftAtMs, leftPlace),
                        action("Пешком", WHAT_MOVE_WALK, leftAtMs, leftPlace),
                    ),
                )
                app.eventLog.add("автопилот: потерял «$leftPlace» — спросил про передвижение")
            }
        }
        pendingLeave = ask
        handler.postDelayed(ask, LEAVE_DELAY_MS)
    }

    // ---- Bluetooth машины ----

    private fun startBtWatch() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val rec = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                // Без BLUETOOTH_CONNECT имя не отдаётся — просто молчим.
                val name = runCatching { device?.name }.getOrNull().orEmpty()
                if (name.isBlank() || carBt.isBlank() || !name.equals(carBt, ignoreCase = true)) return
                if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) onCarConnected()
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

    private fun onCarConnected() {
        if (!askCar) return
        val at = System.currentTimeMillis()
        scope.launch {
            val open = app.zasechkaStore.all().lastOrNull { it.open }
            if (open != null && travelish(open)) return@launch
            lastFire = "машина в ${timeHm(at)}"
            notify(
                "Сел в машину?",
                "«$carBt» подключилась в ${timeHm(at)}. Нет — просто смахни.",
                listOf(action("Поехали", WHAT_MOVE_CAR, at, "")),
            )
            app.eventLog.add("автопилот: BT «$carBt» подключился — спросил про поездку")
        }
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
            val open = app.zasechkaStore.all().lastOrNull { it.open } ?: return@launch
            if (!sedentary(open.category)) return@launch
            if (System.currentTimeMillis() - open.start < SEDENTARY_MIN_MS) return@launch
            armMotion()
        }
    }

    private fun sedentary(category: String): Boolean = category.lowercase().let {
        it.startsWith("работа") || it == "систематизация" || it == "чтение"
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
            val open = app.zasechkaStore.all().lastOrNull { it.open } ?: return@launch
            if (!sedentary(open.category)) return@launch
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

    fun onAction(what: String, atMs: Long, fromPlace: String) {
        scope.launch {
            when (what) {
                WHAT_MOVE_CAR, WHAT_MOVE_WALK -> {
                    val walk = what == WHAT_MOVE_WALK
                    val entry = app.zasechkaStore.startEntry(
                        start = atMs.coerceIn(1L, System.currentTimeMillis()),
                        raw = "",
                        title = when {
                            fromPlace.isBlank() && walk -> "Дорога пешком"
                            fromPlace.isBlank() -> "Поездка на машине"
                            walk -> "Дорога из «$fromPlace» пешком"
                            else -> "Поездка из «$fromPlace»"
                        },
                        category = if (walk) "Передвижение: пешком" else "Передвижение: транспорт",
                        client = "",
                        useful = 0,
                        // Кнопку нажал владелец — это его клейм, не робота:
                        // не гибнет в clear-and-refill и не режется сплайсом.
                        source = "voice",
                    )
                    Feedback.toast(app, "⏱ ${entry.title} — с ${timeHm(entry.start)}")
                    app.eventLog.add("автопилот: начато «${entry.title}»")
                }
                WHAT_STILL_DONE, WHAT_CLOSE_OPEN -> {
                    val closed = app.zasechkaStore.closeOpen(atMs)
                    Feedback.toast(
                        app,
                        if (closed == null) "Открытого дела уже нет"
                        else "⏹ «${closed.title}» закрыто на ${timeHm(atMs)}",
                    )
                    if (closed != null) {
                        app.eventLog.add("автопилот: «${closed.title}» закрыто кнопкой уведомления")
                    }
                }
            }
        }
    }

    // ---- Обвязка ----

    private fun action(label: String, what: String, at: Long, place: String): Notification.Action {
        val intent = Intent(service, AutoPilotActivity::class.java)
            .putExtra(AutoPilotActivity.EXTRA_WHAT, what)
            .putExtra(AutoPilotActivity.EXTRA_AT, at)
            .putExtra(AutoPilotActivity.EXTRA_PLACE, place)
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

    private fun notify(
        title: String,
        text: String,
        actions: List<Notification.Action>,
        openSettings: Boolean = false,
    ) {
        runCatching {
            val nm = service.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, "Автопилот Засечки",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
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
        }
    }

    private fun timeHm(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
}
