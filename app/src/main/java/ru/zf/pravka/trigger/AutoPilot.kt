package ru.zf.pravka.trigger

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
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
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
import ru.zf.pravka.ui.Feedback

// Автопилот Засечки: телефон сам замечает швы дня и либо чинит ленту, либо
// спрашивает одним пушем. Три сигнала, все дешёвые по батарее:
//
// 1. Wi-Fi как места. Поймал известную сеть при открытом «Передвижении» —
//    приехал: передвижение закрывается САМО (ложных срабатываний почти не
//    бывает: к домашнему Wi-Fi в дороге не подключишься). Потерял сеть
//    известного места и три минуты не вернулся — «уехал?» ВОПРОСОМ, не сам:
//    роутер перезагрузился или дальний угол двора — не отъезд.
// 2. Bluetooth машины: магнитола подцепилась — «сел в машину?» вопросом.
// 3. Significant motion — аппаратный одноразовый датчик «телефон значимо
//    переместился», спит бесплатно: сидячее дело идёт ≥30 минут и телефон
//    задвигался — «всё ещё …?». Свайп уведомления = «да, продолжаю».
//
// Владелец: «я выхожу из домашнего Wi-Fi — явно начинается передвижение;
// поймал дачный — значит приехал; телефон задвигался — спроси, точно ли ещё
// работа». Никакого GPS: только события системы.
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

        const val WHAT_MOVE_CAR = "move_car"
        const val WHAT_MOVE_WALK = "move_walk"
        const val WHAT_STILL_DONE = "still_done"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val jobs = mutableListOf<Job>()

    @Volatile private var places: Map<String, String> = emptyMap()
    @Volatile private var carBt: String = ""
    @Volatile private var autoArrive = true
    @Volatile private var askLeave = true
    @Volatile private var askCar = true
    @Volatile private var askStill = true

    private var currentSsid = ""
    private var lastPlace = ""
    private var lastArriveAt = 0L
    private var pendingLeave: Runnable? = null
    private var leftAtMs = 0L
    private var lastStillAsk = 0L
    private var motionArmed = false
    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var btReceiver: BroadcastReceiver? = null

    fun start() {
        jobs += scope.launch { app.settings.autoPlacesFlow.collect { places = it } }
        jobs += scope.launch { app.settings.autoCarBtFlow.collect { carBt = it } }
        jobs += scope.launch { app.settings.autoArriveFlow.collect { autoArrive = it } }
        jobs += scope.launch { app.settings.autoLeaveAskFlow.collect { askLeave = it } }
        jobs += scope.launch { app.settings.autoCarAskFlow.collect { askCar = it } }
        jobs += scope.launch { app.settings.autoStillAskFlow.collect { askStill = it } }
        startWifiWatch()
        startBtWatch()
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
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val info = caps.transportInfo as? WifiInfo ?: return
                    onSsid(cleanSsid(info.ssid))
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
        return if (s.isBlank() || s.equals("<unknown ssid>", ignoreCase = true)) "" else s
    }

    private fun onSsid(ssid: String) {
        if (ssid.isBlank() || ssid == currentSsid) return
        currentSsid = ssid
        val place = places[ssid] ?: return
        // Вернулись в известное место — «уехал?» отменяется.
        pendingLeave?.let { handler.removeCallbacks(it) }
        pendingLeave = null
        val now = System.currentTimeMillis()
        if (place == lastPlace && now - lastArriveAt < ARRIVE_DEBOUNCE_MS) return
        lastPlace = place
        lastArriveAt = now
        app.eventLog.add("автопилот: Wi-Fi «$ssid» — место «$place»")
        if (!autoArrive) return
        scope.launch {
            val open = app.zasechkaStore.all().lastOrNull { it.open } ?: return@launch
            if (!open.category.startsWith("Передвижение", ignoreCase = true)) return@launch
            val closed = app.zasechkaEngine.closeOpen() ?: return@launch
            notify(
                "✓ Приехал: $place",
                "«${closed.title}» закрыто, ${closed.durationMin()} мин.",
                emptyList(),
            )
            app.eventLog.add("автопилот: приехал «$place» — закрыто «${closed.title}»")
        }
    }

    private fun onWifiLost() {
        val fromPlace = places[currentSsid]
        currentSsid = ""
        if (fromPlace == null || !askLeave) return
        leftAtMs = System.currentTimeMillis()
        pendingLeave?.let { handler.removeCallbacks(it) }
        val ask = Runnable {
            pendingLeave = null
            scope.launch {
                val open = app.zasechkaStore.all().lastOrNull { it.open }
                if (open != null && open.category.startsWith("Передвижение", ignoreCase = true)) {
                    return@launch
                }
                notify(
                    "Уехал из «$fromPlace»?",
                    "Начну с момента потери сети, ${timeHm(leftAtMs)}. Нет — просто смахни.",
                    listOf(
                        action("Транспорт", WHAT_MOVE_CAR, leftAtMs, fromPlace),
                        action("Пешком", WHAT_MOVE_WALK, leftAtMs, fromPlace),
                    ),
                )
                app.eventLog.add("автопилот: потерял «$fromPlace» — спросил про передвижение")
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
            if (open != null && open.category.startsWith("Передвижение", ignoreCase = true)) return@launch
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

    /** Тик службы (раз в ~5 минут): взводим датчик, только когда есть что охранять. */
    fun tick() {
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
                WHAT_STILL_DONE -> {
                    val closed = app.zasechkaStore.closeOpen(atMs)
                    Feedback.toast(
                        app,
                        if (closed == null) "Открытого дела уже нет"
                        else "⏹ «${closed.title}» закрыто на ${timeHm(atMs)}",
                    )
                    if (closed != null) {
                        app.eventLog.add("автопилот: «${closed.title}» закрыто моментом движения")
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

    private fun notify(title: String, text: String, actions: List<Notification.Action>) {
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
                .setSmallIcon(R.drawable.ic_tile)
                .setAutoCancel(true)
            actions.forEach { b.addAction(it) }
            nm.notify((title + text).hashCode(), b.build())
        }
    }

    private fun timeHm(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
}
