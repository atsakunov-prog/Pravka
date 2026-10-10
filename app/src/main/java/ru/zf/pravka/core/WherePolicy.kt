package ru.zf.pravka.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject

// «Где мы» — точки семьи на одной карте (10.10.2026). Владелец: «мы с
// Марианной так реально… видим, где дети, где мы сами, и всё время это
// используем. У нас нет секретов друг от друга»; «чтобы не было такого, что всё
// время GPS включён… если телефон не двигается, то и местоположение не
// меняется; если Яндекс Карты открыты — GPS и так активен, можно передавать;
// а так раз в час по Wi-Fi».
//
// Отсюда лестница источников по цене, снизу вверх:
//  1. ЧУЖОЙ GPS — пассивный слушатель: точка, которую попросило другое
//     приложение (Яндекс Карты, навигатор, службы Google), достаётся даром;
//  2. ДАТЧИК ЗНАЧИМОГО ДВИЖЕНИЯ — аппаратный, в сенсорном хабе, батарею не ест:
//     лежит телефон — ни одного запроса; пошёл или поехал — будит;
//  3. Wi-Fi и вышки (качество «сбалансированное») — дёшево, 20–100 м в городе:
//     раз в 5 минут, пока едет, и раз в час, пока лежит;
//  4. свой GPS — только когда кто-то из семьи нажал «обновить» на карте, один
//     раз на просьбу.
//
// Здесь — решения этой лестницы и формат точки на сервере Дел; Android — в
// `provider/Locator.kt`, обмен — в `data/WhereSync.kt`. Файл без Android:
// проверяется JVM-тестом `WherePolicyTest`.

/** Одна точка: широта, долгота, точность в метрах, когда и кто её дал. */
data class WhereFix(
    val lat: Double,
    val lon: Double,
    /** Радиус точности, м (68% вероятности, как отдаёт Android). */
    val acc: Float,
    val at: Long,
    /** gps · network · fused — кто посчитал; «чужой» GPS приходит с именем gps. */
    val src: String,
    /** Скорость, м/с; нет — отрицательная. */
    val speed: Float = -1f,
)

/**
 * Точка телефона на сервере Дел (`crm.where_points`, операция `where.set`).
 * Пишет её только сам телефон — свою строку сервер не даст тронуть другому.
 */
data class WhereBeacon(
    val device: String,
    /** Ключ профиля (sasha, marianna): цвет и аватар — по нему. */
    val person: String,
    val name: String,
    val fix: WhereFix,
    /** Заряд, %; не знаем — −1. */
    val battery: Int = -1,
    val charging: Boolean = false,
    /** Едет или идёт: датчик движения сработал недавно. */
    val moving: Boolean = false,
    /** Место по Wi-Fi из автопилота Засечки («Дом», «Летово»); не знаем — пусто. */
    val place: String = "",
    /** Когда телефон последний раз сказал «я здесь» — может быть позже точки. */
    val sentAt: Long,
    /** Версия аватара (момент выбора фото); 0 — фото нет, кружок с буквой. */
    val avatarAt: Long = 0L,
)

object WherePolicy {

    /** После толчка датчика телефон считается едущим столько. */
    const val MOVING_MS = 15 * 60_000L
    /** Пока едет — точка по Wi-Fi и вышкам не реже. */
    const val MOVING_EVERY_MS = 5 * 60_000L
    /** Пока лежит — раз в час, как просил владелец. */
    const val STILL_EVERY_MS = 60 * 60_000L
    /** Между своими попытками — не чаще: неудача (нет сети, подвал) не должна молотить. */
    const val TRY_GAP_MS = 4 * 60_000L
    /** Просьба «обновить» живёт столько; старше — не будит свой GPS. */
    const val ASK_FRESH_MS = 30 * 60_000L
    /** «Я на связи» на сервер — даже если точка не менялась. */
    const val HEARTBEAT_MS = 30 * 60_000L
    /** Между выгрузками — не чаще (навигатор отдаёт точки каждую секунду). */
    const val SEND_GAP_MS = 2 * 60_000L
    /** Сдвиг, после которого точку стоит отправить, м. */
    const val MOVE_SEND_M = 60.0

    /** Свежая точка — маркер яркий; старше — бледнее; совсем старая — серая. */
    const val FRESH_MS = 20 * 60_000L
    const val OLD_MS = 3 * 60 * 60_000L

    enum class Need { NONE, BALANCED, PRECISE }

    /**
     * Нужна ли своя точка сейчас и какой ценой.
     *  - [askAt] — самая свежая просьба «обновить» от другого телефона;
     *    [preciseFor] — на какую просьбу уже был свой GPS (один раз на просьбу);
     *  - [motionAt] — последний толчок датчика движения;
     *  - [triedAt] — последняя своя попытка, удачная или нет.
     */
    fun need(now: Long, fix: WhereFix?, motionAt: Long, askAt: Long, preciseFor: Long, triedAt: Long): Need {
        val asked = askAt > 0L && now - askAt in 0..ASK_FRESH_MS && preciseFor < askAt && (fix == null || fix.at < askAt)
        if (asked) return Need.PRECISE
        if (now - triedAt in 0 until TRY_GAP_MS) return Need.NONE
        if (fix == null) return Need.BALANCED
        val age = now - fix.at
        if (moving(now, motionAt) && age >= MOVING_EVERY_MS) return Need.BALANCED
        if (age >= STILL_EVERY_MS) return Need.BALANCED
        return Need.NONE
    }

    fun moving(now: Long, motionAt: Long): Boolean = motionAt > 0L && now - motionAt in 0..MOVING_MS

    /**
     * Взять ли новую точку вместо прежней. Грубая точка по вышкам (±1500 м)
     * не должна затирать GPS полуминутной давности — если только телефон не
     * уехал дальше обоих кругов или прежняя не устарела.
     */
    fun better(new: WhereFix, old: WhereFix?): Boolean {
        if (old == null) return true
        if (new.at <= old.at) return false
        if (new.acc <= old.acc * 1.5f) return true
        if (new.at - old.at >= 10 * 60_000L) return true
        return distanceM(new, old) > new.acc + old.acc
    }

    /**
     * Отправить ли точку на сервер. [sent] и [sentAt] — что и когда ушло в
     * прошлый раз; [askAt] — просьба «обновить»; [extrasChanged] — место по
     * Wi-Fi или зарядка поменялись (это видно на карте).
     */
    fun shouldSend(now: Long, fix: WhereFix?, sent: WhereFix?, sentAt: Long, askAt: Long, extrasChanged: Boolean): Boolean {
        if (fix == null) return false
        if (sent == null || sentAt <= 0L) return true
        // Просили — ответ уходит сразу, без паузы между выгрузками.
        if (askAt > sentAt && fix.at >= askAt) return true
        if (now - sentAt >= HEARTBEAT_MS) return true
        if (now - sentAt in 0 until SEND_GAP_MS) return false
        if (fix.at <= sent.at) return extrasChanged
        val d = distanceM(fix, sent)
        if (d >= max(MOVE_SEND_M, min(fix.acc, sent.acc).toDouble())) return true
        if (sent.acc > 50f && fix.acc < sent.acc * 0.5f) return true
        return extrasChanged
    }

    /** Расстояние по большому кругу, м. */
    fun distanceM(a: WhereFix, b: WhereFix): Double = distanceM(a.lat, a.lon, b.lat, b.lon)

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    /**
     * С какого момента точка верна: лежащий телефон, сказавший «я здесь» в
     * 15:20, стоит на той же точке и в 15:20, даже если сама точка — с 14:30.
     */
    fun seenAt(b: WhereBeacon): Long = if (b.moving) b.fix.at else max(b.fix.at, b.sentAt)

    /** Насколько бледен маркер: 1 — свежий, 0,7 — пара часов, 0,45 — давно. */
    fun alpha(now: Long, seenAt: Long): Float = when {
        now - seenAt <= FRESH_MS -> 1f
        now - seenAt <= OLD_MS -> 0.7f
        else -> 0.45f
    }

    /** «только что», «7 мин назад», «в 14:05», «вчера в 18:40», «08.10 в 09:12». */
    fun ago(now: Long, at: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = now - at
        if (d < 90_000L) return "только что"
        if (d < 60 * 60_000L) return "${(d / 60_000L).coerceAtLeast(2)} мин назад"
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(at))
        return when (day) {
            today -> "в $hm"
            today.minusDays(1) -> "вчера в $hm"
            else -> DateTimeFormatter.ofPattern("dd.MM").withZone(zone).format(Instant.ofEpochMilli(at)) + " в $hm"
        }
    }

    /** «±20 м», «±1,5 км». */
    fun accuracy(acc: Float): String = if (acc < 1000f) "±${acc.roundToInt()} м"
    else "±" + String.format(java.util.Locale.US, "%.1f", acc / 1000f).replace('.', ',').removeSuffix(",0") + " км"

    /** Строка под именем: «Дом · 7 мин назад · ±20 м · 64%». */
    fun line(now: Long, b: WhereBeacon, zone: ZoneId = ZoneId.systemDefault()): String = buildList {
        if (b.place.isNotBlank()) add(b.place)
        if (b.moving) add("в пути")
        add(ago(now, seenAt(b), zone))
        add(accuracy(b.fix.acc))
        if (b.battery >= 0) add("${b.battery}%" + if (b.charging) " ⚡" else "")
    }.joinToString(" · ")

    // ---- Цвета людей: обводка аватара и круг точности ----

    private val KNOWN = mapOf(
        "sasha" to 0xFFFF8A3D,
        "marianna" to 0xFFFF5C8A,
        "seryozha" to 0xFF4DA3FF,
    )
    private val PALETTE = longArrayOf(0xFF2EC4B6, 0xFFA78BFA, 0xFFB5E550, 0xFFFFC145, 0xFF5EEAD4, 0xFFF472B6)

    /** ARGB человека: знакомые — свои, остальные — из палитры по ключу (на любом телефоне тот же). */
    fun color(person: String): Long = KNOWN[person]
        ?: PALETTE[(person.fold(7) { h, c -> h * 31 + c.code } and Int.MAX_VALUE) % PALETTE.size]

    // ---- Формат файла ----

    const val VERSION = 1

    fun toJson(b: WhereBeacon): String = JSONObject()
        .put("v", VERSION)
        .put("device", b.device)
        .put("person", b.person)
        .put("name", b.name)
        .put("lat", round6(b.fix.lat))
        .put("lon", round6(b.fix.lon))
        .put("acc", (b.fix.acc * 10).roundToInt() / 10.0)
        .put("at", b.fix.at)
        .put("src", b.fix.src)
        .put("speed", (b.fix.speed * 10).roundToInt() / 10.0)
        .put("battery", b.battery)
        .put("charging", b.charging)
        .put("moving", b.moving)
        .put("place", b.place)
        .put("sent", b.sentAt)
        .put("avatar", b.avatarAt)
        .toString()

    /** null — не наш файл или без точки. Лишние поля нового формата не мешают. */
    fun fromJson(text: String): WhereBeacon? = runCatching {
        val o = JSONObject(text)
        val device = o.optString("device").ifBlank { return null }
        if (!o.has("lat") || !o.has("lon")) return null
        val lat = o.getDouble("lat")
        val lon = o.getDouble("lon")
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        WhereBeacon(
            device = device,
            person = o.optString("person").ifBlank { device.substringBefore('-') },
            name = o.optString("name").ifBlank { device },
            fix = WhereFix(lat, lon, o.optDouble("acc", 100.0).toFloat(), o.optLong("at"), o.optString("src"), o.optDouble("speed", -1.0).toFloat()),
            battery = o.optInt("battery", -1),
            charging = o.optBoolean("charging"),
            moving = o.optBoolean("moving"),
            place = o.optString("place"),
            sentAt = o.optLong("sent"),
            avatarAt = o.optLong("avatar"),
        )
    }.getOrNull()

    private fun round6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0

    // ---- Имена ----

    /** Свой аватар и чужие лежат дома файлами `<устройство>.jpg`. */
    const val AVATAR = ".jpg"

    /** Имя телефона, как его примет сервер Дел (`sql/dela_0008.sql`): латиница, цифры, дефис. */
    fun isDevice(s: String): Boolean = DEVICE.matches(s)

    private val DEVICE = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
}
