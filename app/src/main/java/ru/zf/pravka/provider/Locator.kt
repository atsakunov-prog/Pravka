package ru.zf.pravka.provider

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.zf.pravka.core.WhereFix

/**
 * Откуда «Где мы» берёт свою точку — платформенный LocationManager, без Play
 * Services: на телефонах с Google провайдеры fused и network и так отдаёт
 * служба Google, а лишней библиотеки и лишнего разрешения не нужно. Какой
 * ступенью лестницы пользоваться — решает `core/WherePolicy.kt`.
 *
 * Разрешения те же, что у автопилота Засечки (`ACCESS_FINE_LOCATION` +
 * «Разрешать всегда»): точку просит служба доступности из фона, и с «только
 * при использовании» система ей молча ничего не отдаёт.
 *
 * Колбэки идут на свой поток, не на главный: главный поток службы держит окна
 * кнопок, и складывание Fold не любит на нём ничего лишнего (правило 2).
 */
class Locator(private val context: Context) {

    private val lm: LocationManager? get() = context.getSystemService(LocationManager::class.java)
    private val sensors: SensorManager? get() = context.getSystemService(SensorManager::class.java)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pravka-where").apply { isDaemon = true } }

    private var passive: LocationListenerCompat? = null
    private var motion: TriggerEventListener? = null

    fun fine(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** «Разрешать всегда»: до Android 10 его нет — там «точно» и есть «всегда». */
    fun always(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Геолокация включена в системе (шторка «Местоположение»). */
    fun systemOn(): Boolean = lm?.let { runCatching { LocationManagerCompat.isLocationEnabled(it) }.getOrDefault(true) } ?: false

    /** Есть ли у телефона датчик значимого движения: без него едущего не видно, только раз в час. */
    fun hasMotionSensor(): Boolean = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null

    /**
     * Пассивный слушатель: точки, которые попросили другие приложения
     * (Яндекс Карты, навигатор, службы Google). Своего запроса нет — батарея
     * не тратится. Не чаще минуты и 30 метров: навигатор отдаёт точку в секунду.
     */
    @Synchronized
    @SuppressLint("MissingPermission")
    fun startPassive(onFix: (WhereFix) -> Unit): Boolean {
        if (passive != null) return true
        val m = lm ?: return false
        if (!fine()) return false
        val l = LocationListenerCompat { loc -> runCatching { onFix(loc.toFix()) } }
        val req = LocationRequestCompat.Builder(60_000L)
            .setMinUpdateIntervalMillis(60_000L)
            .setMinUpdateDistanceMeters(30f)
            .build()
        val ok = runCatching {
            LocationManagerCompat.requestLocationUpdates(m, LocationManager.PASSIVE_PROVIDER, req, executor, l)
        }.isSuccess
        if (ok) passive = l
        return ok
    }

    @Synchronized
    fun stopPassive() {
        val l = passive ?: return
        passive = null
        lm?.let { m -> runCatching { LocationManagerCompat.removeUpdates(m, l) } }
    }

    /**
     * Датчик значимого движения: срабатывает один раз и снимается сам —
     * поэтому взводится заново после каждого толчка и на каждом тике.
     * Колбэк короткий: только отметка времени, работа — на корутине.
     */
    @Synchronized
    fun armMotion(onMove: () -> Unit): Boolean {
        val sm = sensors ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return false
        val l = motion ?: object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                runCatching { onMove() }
            }
        }.also { motion = it }
        return runCatching { sm.requestTriggerSensor(l, sensor) }.getOrDefault(false)
    }

    @Synchronized
    fun disarmMotion() {
        val l = motion ?: return
        motion = null
        val sm = sensors ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        runCatching { sm.cancelTriggerSensor(l, sensor) }
    }

    /** Последняя точка любого приложения — даром, без единого запроса. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): WhereFix? {
        val m = lm ?: return null
        if (!fine()) return null
        return runCatching { m.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()?.toFix()
    }

    /**
     * Одна своя точка. [precise] — с GPS (только по просьбе «обновить»), иначе
     * Wi-Fi и вышки. Система сама обрывает поиск через 30 секунд; null — не
     * нашлось (подвал, всё выключено), тогда вызывающий берёт [lastKnown].
     */
    @SuppressLint("MissingPermission")
    suspend fun current(precise: Boolean): WhereFix? {
        val m = lm ?: return null
        if (!fine()) return null
        val provider = provider(m, precise) ?: return null
        return withTimeoutOrNull(40_000L) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { runCatching { signal.cancel() } }
                val done: (Location?) -> Unit = { loc -> if (cont.isActive) cont.resume(loc?.toFix()) }
                val started = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && provider == LocationManager.FUSED_PROVIDER) {
                        val req = android.location.LocationRequest.Builder(0L)
                            .setQuality(
                                if (precise) android.location.LocationRequest.QUALITY_HIGH_ACCURACY
                                else android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                            )
                            .setDurationMillis(30_000L)
                            .build()
                        m.getCurrentLocation(provider, req, signal, executor) { done(it) }
                    } else {
                        LocationManagerCompat.getCurrentLocation(m, provider, signal, executor) { done(it) }
                    }
                }
                if (started.isFailure && cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Кто посчитает: fused (Android 12+, у телефонов с Google — служба Google,
     * сама смешивает Wi-Fi, вышки и GPS по качеству), иначе network для
     * дешёвой точки и gps для точной.
     */
    private fun provider(m: LocationManager, precise: Boolean): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { m.hasProvider(LocationManager.FUSED_PROVIDER) && m.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false)
        ) return LocationManager.FUSED_PROVIDER
        val on = { p: String -> runCatching { m.isProviderEnabled(p) }.getOrDefault(false) }
        return when {
            precise && on(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            on(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            on(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
    }

    /** Заряд в процентах и идёт ли зарядка; не знаем — −1. */
    fun battery(): Pair<Int, Boolean> {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return -1 to false
        val pct = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)
        val charging = runCatching { bm.isCharging }.getOrDefault(false)
        return (if (pct in 0..100) pct else -1) to charging
    }

    private fun Location.toFix(): WhereFix = WhereFix(
        lat = latitude,
        lon = longitude,
        acc = if (hasAccuracy()) accuracy else 500f,
        // Время точки — по часам телефона: у GPS свои часы, и бывает, что они
        // уходят на секунды; карта считает «сколько назад» по системным.
        at = time.takeIf { it > 0L && it <= System.currentTimeMillis() + 60_000L } ?: System.currentTimeMillis(),
        src = provider.orEmpty(),
        speed = if (hasSpeed()) speed else -1f,
    )
}
