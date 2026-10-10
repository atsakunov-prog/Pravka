package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.WhereBeacon
import ru.zf.pravka.core.WhereFix
import ru.zf.pravka.core.WherePolicy
import ru.zf.pravka.provider.Locator

/**
 * «Где мы» — своя точка и точки семьи через сервер Дел (10.10.2026).
 * Решения (когда искать, когда отправлять) — `core/WherePolicy.kt`; точки —
 * `provider/Locator.kt`; здесь порядок шагов, файлы и сервер.
 *
 * Сначала точки ездили через облако семьи (WebDAV), и в тот же вечер
 * владелец: «почему через облако семьи? Вроде мы его не использовали
 * толком» — облако на телефонах не было подключено, а сервер Дел живёт с
 * Правкой каждый день. Теперь — его API (контракт `server/contract/dela-where.json`,
 * сервер — `pravka_dela/where.py`), без новых путей: `/api/ops` (where.set,
 * where.off, where.ask) и `/api/view` (where, where_avatar). Видит и пишет
 * только семья (`crm.users.family`, меняет владелец командой `family` на
 * компе): Наташа карты не видит.
 *
 * Дома, в папке базы: `where.json` (согласие, своя точка, что ушло, чужие
 * точки на момент последнего обмена — карта открывается сразу, не ожидая
 * сервера) и `where/` с аватарами. Это не незаменимые данные: пропали —
 * следующий обмен соберёт заново.
 *
 * Делиться — согласие каждого на своём телефоне: тумблер на вкладке, с
 * завода выключен. Смотреть может любой член семьи, подключённый к Делам.
 */
internal class WhereSync(
    private val context: Context,
    private val locator: Locator,
    /** Связь с сервером Дел; не подключены — null. */
    private val dela: () -> DelaSync?,
    private val profile: () -> Profile?,
    /** Имя телефона на сервере — то же, что у журналов Денег (`sasha-3f9a2c`). */
    private val device: () -> String,
    /** Место по Wi-Fi из автопилота Засечки; служба выключена — пусто. */
    private val place: () -> String,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {

    companion object {
        private const val FILE = "where.json"
        private const val DIR = "where"
        const val NO_DELA = "Правка не подключена к Делам — Настройки → Подключения → Дела, QR с компа"
        const val OLD_SERVER = "Сервер Дел на компе ещё без карты — обнови его (install-dela.ps1 от администратора)"
    }

    data class State(
        /** Согласие этого телефона делиться точкой. */
        val sharing: Boolean = false,
        /**
         * Выключил «делиться», а убрать свою точку с сервера ещё не вышло (не
         * было сети): тик доделает, пока не получится.
         */
        val retract: Boolean = false,
        /** Лучшая своя точка. */
        val own: WhereFix? = null,
        /** Что и когда ушло на сервер последний раз. */
        val sent: WhereFix? = null,
        val sentAt: Long = 0L,
        /** Место и зарядка в отправленной точке: поменялись — повод отправить. */
        val sentExtras: String = "",
        val motionAt: Long = 0L,
        val triedAt: Long = 0L,
        /** На какую просьбу «обновить» уже был свой GPS. */
        val preciseFor: Long = 0L,
        /** Своя версия аватара; 0 — фото нет. */
        val avatarAt: Long = 0L,
        /** Какая версия аватара уже лежит на сервере: другая — выложить заново. */
        val avatarSent: Long = 0L,
        /** Чужие точки: устройство → точка. */
        val others: Map<String, WhereBeacon> = emptyMap(),
        /** Чужие просьбы «обновить»: устройство → когда. */
        val asks: Map<String, Long> = emptyMap(),
        /** Своя последняя просьба. */
        val myAskAt: Long = 0L,
        /** Плитки карты: DARK или LIGHT (`WhereTiles`); выбор этого телефона. */
        val tiles: String = "DARK",
        /** Версии чужих аватаров, что уже скачаны: устройство → avatar_at. */
        val seen: Map<String, String> = emptyMap(),
        /** Считает ли сервер Дел этот телефон семьёй; null — ещё не спрашивали. */
        val family: Boolean? = null,
        /** Последний удачный обмен; 0 — не было. */
        val syncedAt: Long = 0L,
        val error: String = "",
        val running: Boolean = false,
    ) {
        /** Самая свежая чужая просьба. */
        val askAt: Long get() = asks.values.maxOrNull() ?: 0L
    }

    private val file: File get() = File(DataRoot.dir(context), FILE)
    private val dir: File get() = File(DataRoot.dir(context), DIR).apply { mkdirs() }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state

    private val mutex = Mutex()
    private var attached = false
    private var lastLoggedError = ""

    /** Файл аватара телефона (свой — [device]); нет — null. */
    fun avatar(device: String): File? = File(dir, device + WherePolicy.AVATAR).takeIf { it.isFile && it.length() > 0 }

    fun myDevice(): String = device()

    // ---- Жизненный цикл: служба поднялась или ушла ----

    /**
     * Служба на месте: делится — слушаем чужой GPS и датчик движения.
     * Повторный вызов ничего не ломает.
     */
    fun attach() {
        attached = true
        if (_state.value.sharing) listen()
    }

    fun detach() {
        attached = false
        locator.stopPassive()
        locator.disarmMotion()
    }

    private fun listen() {
        locator.startPassive { fix -> offer(fix) }
        locator.armMotion(::onMotion)
    }

    /** Толчок датчика: телефон пошёл или поехал. Колбэк датчика — короткий. */
    private fun onMotion() {
        val now = System.currentTimeMillis()
        _state.update { it.copy(motionAt = now) }
        if (!_state.value.sharing) return
        locator.armMotion(::onMotion)
        scope.launch(Dispatchers.IO) { runCatching { locate("движение", eager = false) } }
    }

    /** Новая точка от кого угодно: лучше прежней — своя, и если стоит — на сервер. */
    private fun offer(fix: WhereFix) {
        var took = false
        _state.update { s ->
            if (WherePolicy.better(fix, s.own)) {
                took = true
                s.copy(own = fix)
            } else s
        }
        if (!took) return
        persist()
        val s = _state.value
        if (s.sharing && WherePolicy.shouldSend(System.currentTimeMillis(), s.own, s.sent, s.sentAt, s.askAt, extras() != s.sentExtras)) {
            scope.launch(Dispatchers.IO) { runCatching { sync("точка") } }
        }
    }

    // ---- Согласие ----

    /**
     * Включить или выключить «делиться». Выключение удаляет свою точку и
     * аватар с сервера сразу: карта у семьи не должна показывать телефон,
     * который ничего больше не шлёт, как будто он там.
     */
    suspend fun setSharing(on: Boolean) {
        _state.update { it.copy(sharing = on, retract = !on, sent = if (on) it.sent else null, sentAt = if (on) it.sentAt else 0L) }
        persist()
        log("где мы: делиться точкой — " + if (on) "включено" else "выключено")
        if (on) {
            if (attached) listen()
            runCatching { locate("включил", eager = true) }
        } else {
            locator.stopPassive()
            locator.disarmMotion()
        }
        sync(if (on) "включил" else "выключил")
    }

    // ---- Обмен ----

    /**
     * Тик службы (раз в 5 минут): делится — своя точка по лестнице и обмен;
     * не делится — молчит (смотреть карту ходит сама вкладка).
     */
    suspend fun tick(reason: String) {
        if (!_state.value.sharing) {
            if (_state.value.retract) sync(reason)
            return
        }
        if (attached) {
            // Датчик мог сняться, а слушатель — отвалиться после смены разрешения.
            listen()
        }
        locator.lastKnown()?.let { offer(it) }
        sync(reason)
    }

    /**
     * Найти свою точку, если [WherePolicy.need] велит. [eager] — вкладка
     * открыта: точка старше пяти минут обновляется, раз уж смотрят.
     */
    private suspend fun locate(reason: String, eager: Boolean) {
        val s = _state.value
        if (!s.sharing) return
        val now = System.currentTimeMillis()
        var need = WherePolicy.need(now, s.own, s.motionAt, s.askAt, s.preciseFor, s.triedAt)
        if (need == WherePolicy.Need.NONE && eager && (s.own == null || now - s.own.at >= WherePolicy.MOVING_EVERY_MS) &&
            now - s.triedAt >= 60_000L
        ) need = WherePolicy.Need.BALANCED
        if (need == WherePolicy.Need.NONE) return
        val precise = need == WherePolicy.Need.PRECISE
        _state.update { it.copy(triedAt = now, preciseFor = if (precise) it.askAt else it.preciseFor) }
        val fix = locator.current(precise) ?: locator.lastKnown()
        if (fix != null) offer(fix)
        else if (precise) log("где мы: просили обновить ($reason), а своей точки не нашлось — нет сети и спутников?")
        persist()
    }

    /**
     * Один обмен: точки семьи к себе → своя точка на сервер (или убрать, если
     * больше не делимся). Второй поверх идущего не встаёт.
     */
    suspend fun sync(reason: String): Boolean {
        val d = dela()
        if (d == null) {
            _state.update { it.copy(error = NO_DELA) }
            return false
        }
        if (!mutex.tryLock()) return false
        _state.update { it.copy(running = true) }
        try {
            withContext(Dispatchers.IO) {
                // Сначала чужое — в нём бывает свежая просьба «обновить»; потом
                // своя точка (на просьбу — своим GPS) и тем же обменом на сервер.
                val present = readOthers(d)
                if (_state.value.sharing && _state.value.family != false) runCatching { locate(reason, eager = reason == "вкладка") }
                sendOwn(d, present)
            }
            val notFamily = if (_state.value.family == false)
                "Сервер Дел не считает тебя семьёй — на компе: python -m pravka_dela family ${d.link.value?.user ?: "<кто>"}" else ""
            _state.update { it.copy(syncedAt = System.currentTimeMillis(), error = notFamily, running = false) }
            persist()
            lastLoggedError = ""
            return true
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val why = why(e)
            _state.update { it.copy(error = why, running = false) }
            if (why != lastLoggedError) {
                log("где мы ($reason): обмен не вышел — $why")
                lastLoggedError = why
            }
            return false
        } finally {
            if (_state.value.running) _state.update { it.copy(running = false) }
            mutex.unlock()
        }
    }

    /** Ошибка словами: старый сервер без карты — что сделать на компе, остальное — как сказали Дела. */
    private fun why(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "нет такого вида" in m || "нет такой операции" in m -> OLD_SERVER
            m.isNotBlank() -> m
            else -> "${e.javaClass.simpleName}: где мы — обмен не вышел"
        }
    }

    /**
     * Точки семьи и просьбы — к себе; аватары — только сменившиеся. Возвращает
     * телефоны, чьи точки сейчас на сервере (свой среди них — значит, лежит).
     */
    private suspend fun readOthers(d: DelaSync): Set<String> {
        val me = device()
        val o = d.rawView("where")
        val present = HashSet<String>()
        if (!o.optBoolean("family")) {
            _state.update { it.copy(family = false, others = emptyMap(), asks = emptyMap(), seen = emptyMap()) }
            dir.listFiles()?.forEach { f -> if (f.name.endsWith(WherePolicy.AVATAR) && f.name != me + WherePolicy.AVATAR) f.delete() }
            return present
        }
        val seen = HashMap(_state.value.seen)
        val others = HashMap<String, WhereBeacon>()
        val points = o.optJSONArray("points") ?: JSONArray()
        for (i in 0 until points.length()) {
            val p = points.optJSONObject(i) ?: continue
            val dev = p.optString("device")
            if (!WherePolicy.isDevice(dev)) continue
            present.add(dev)
            if (dev == me) continue
            val raw = p.optJSONObject("point") ?: continue
            // Устройство — по строке сервера (её пишет только хозяин), имя — его же, если телефон не прислал.
            raw.put("device", dev)
            if (raw.optString("name").isBlank()) raw.put("name", p.optString("name"))
            if (raw.optString("person").isBlank()) raw.put("person", p.optString("user_id"))
            val b = WherePolicy.fromJson(raw.toString()) ?: continue
            val hasAvatar = p.optBoolean("has_avatar")
            val avatarAt = p.optLong("avatar_at")
            others[dev] = b.copy(avatarAt = if (hasAvatar) avatarAt else 0L)
            val f = File(dir, dev + WherePolicy.AVATAR)
            if (!hasAvatar) {
                f.delete()
                seen.remove(dev)
            } else if (seen[dev] != avatarAt.toString() || !f.isFile) {
                val a = d.rawView("where_avatar?device=$dev").optString("avatar")
                if (a.isNotBlank() && a != "null") {
                    StoreFilesBytes.write(f, android.util.Base64.decode(a, android.util.Base64.DEFAULT))
                    seen[dev] = avatarAt.toString()
                }
            }
        }
        val asks = HashMap<String, Long>()
        val arr = o.optJSONArray("asks") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val dev = a.optString("device")
            if (dev != me && WherePolicy.isDevice(dev)) asks[dev] = a.optLong("at")
        }
        // Пропал с сервера — перестал делиться: с карты его тоже убрать.
        for (dev in seen.keys.toList()) if (dev !in present) seen.remove(dev)
        dir.listFiles()?.forEach { f ->
            val dev = f.name.removeSuffix(WherePolicy.AVATAR)
            if (f.name.endsWith(WherePolicy.AVATAR) && dev != me && dev !in present) f.delete()
        }
        _state.update { it.copy(family = true, others = others, asks = asks, seen = seen) }
        return present
    }

    /** Своё: точка (и сменившийся аватар) на сервер, пока делимся; перестали — убрать. */
    private suspend fun sendOwn(d: DelaSync, present: Set<String>) {
        val me = device()
        val s = _state.value
        if (s.sharing) {
            if (s.family == false) return
            val fix = s.own ?: return
            val now = System.currentTimeMillis()
            val extras = extras()
            val missing = me !in present
            val avatarChanged = s.avatarSent != s.avatarAt
            if (!missing && !avatarChanged && !WherePolicy.shouldSend(now, fix, s.sent, s.sentAt, s.askAt, extras != s.sentExtras)) return
            val op = JSONObject().put("op", "where.set").put("device", me).put("point", JSONObject(WherePolicy.toJson(beacon(fix, now))))
            if (missing || avatarChanged) {
                val f = avatar(me)
                if (f != null) op.put("avatar", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)).put("avatar_at", s.avatarAt)
                else op.put("avatar", JSONObject.NULL).put("avatar_at", 0)
            }
            ok(d.rawOps(JSONArray().put(op)), "точку")
            _state.update { it.copy(sent = fix, sentAt = now, sentExtras = extras, avatarSent = s.avatarAt) }
        } else if (me in present || s.retract) {
            ok(d.rawOps(JSONArray().put(JSONObject().put("op", "where.off").put("device", me))), "«больше не делюсь»")
            if (me in present) log("где мы: своя точка убрана с сервера Дел — больше не делюсь")
            _state.update { it.copy(retract = false) }
        }
    }

    /** Ответ на одну операцию: не принята — ошибка словами сервера, а не молчание. */
    private fun ok(results: JSONArray, what: String) {
        val r = results.optJSONObject(0)
        if (r?.optBoolean("ok") != true) {
            throw DelaSync.DelaException("Дела не приняли $what: " + (r?.optString("error")?.ifBlank { null } ?: "пустой ответ"))
        }
    }

    private fun beacon(fix: WhereFix, now: Long): WhereBeacon {
        val p = profile()
        val (battery, charging) = locator.battery()
        val s = _state.value
        return WhereBeacon(
            device = device(),
            person = p?.id ?: "user",
            name = p?.name ?: "Без имени",
            fix = fix,
            battery = battery,
            charging = charging,
            moving = WherePolicy.moving(now, s.motionAt),
            place = place(),
            sentAt = now,
            avatarAt = if (avatar(device()) != null) s.avatarAt else 0L,
        )
    }

    /** То, что на карте видно, кроме самой точки: место и зарядка. */
    private fun extras(): String = place() + "|" + locator.battery().second

    // ---- Вкладка ----

    /** Вкладка открыта: свои — по мере нужды, чужие — свежие с сервера. */
    suspend fun refresh(): Boolean = sync("вкладка")

    /**
     * «Обновить точки»: своя просьба на сервер — делящиеся телефоны на
     * ближайшем тике (до пяти минут) отвечают своим GPS. Свою точку этот
     * телефон обновляет сразу, если делится.
     */
    suspend fun ask(): Boolean {
        val d = dela()
        if (d == null) {
            _state.update { it.copy(error = NO_DELA) }
            return false
        }
        val now = System.currentTimeMillis()
        return runCatching {
            ok(d.rawOps(JSONArray().put(JSONObject().put("op", "where.ask").put("device", device()))), "просьбу «обновить»")
            _state.update { it.copy(myAskAt = now) }
            persist()
            log("где мы: попросил всех обновить точки")
            if (_state.value.sharing) runCatching {
                // Свой GPS — один раз на свою просьбу, как у всех.
                _state.update { it.copy(triedAt = now, preciseFor = now) }
                locator.current(precise = true)?.let { offer(it) }
            }
            sync("обновить")
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(error = why(e)) }
            false
        }
    }

    /**
     * Разрешение только что выдали: слушатель чужого GPS не мог встать без
     * него — встаёт сейчас, и своя точка ищется, не дожидаясь тика.
     */
    fun nudge() {
        if (!_state.value.sharing) return
        if (attached) listen()
        scope.launch(Dispatchers.IO) { runCatching { sync("разрешение") } }
    }

    /** Свой аватар: готовый JPEG (квадрат 256). Уезжает со следующим обменом. */
    suspend fun setAvatar(jpeg: ByteArray) {
        val me = device()
        withContext(Dispatchers.IO) {
            val f = File(dir, me + WherePolicy.AVATAR)
            StoreFilesBytes.write(f, jpeg)
        }
        _state.update { it.copy(avatarAt = System.currentTimeMillis(), sentAt = 0L) }
        persist()
        sync("аватар")
    }

    suspend fun clearAvatar() {
        withContext(Dispatchers.IO) { File(dir, device() + WherePolicy.AVATAR).delete() }
        _state.update { it.copy(avatarAt = 0L, sentAt = 0L) }
        persist()
        sync("аватар")
    }

    fun setTiles(name: String) {
        _state.update { it.copy(tiles = name) }
        persist()
    }

    // ---- Файл ----

    private fun persist() {
        val snap = _state.value
        DiskWriter.post { StoreFiles.writeAtomic(file, toJson(snap)) }
    }

    private fun load(): State = StoreFiles.readOrQuarantine(file) { fromJson(it) } ?: State()

    private fun fixJson(f: WhereFix?): JSONObject? = f?.let {
        JSONObject().put("lat", it.lat).put("lon", it.lon).put("acc", it.acc.toDouble()).put("at", it.at)
            .put("src", it.src).put("speed", it.speed.toDouble())
    }

    private fun fixOf(o: JSONObject?): WhereFix? = o?.let {
        WhereFix(it.getDouble("lat"), it.getDouble("lon"), it.optDouble("acc", 100.0).toFloat(), it.optLong("at"),
            it.optString("src"), it.optDouble("speed", -1.0).toFloat())
    }

    private fun toJson(s: State): String = JSONObject()
        .put("sharing", s.sharing)
        .put("retract", s.retract)
        .put("own", fixJson(s.own))
        .put("sent", fixJson(s.sent))
        .put("sentAt", s.sentAt)
        .put("sentExtras", s.sentExtras)
        .put("triedAt", s.triedAt)
        .put("preciseFor", s.preciseFor)
        .put("avatarAt", s.avatarAt)
        .put("avatarSent", s.avatarSent)
        .put("myAskAt", s.myAskAt)
        .put("tiles", s.tiles)
        .put("syncedAt", s.syncedAt)
        .put("others", JSONArray(s.others.values.map { JSONObject(WherePolicy.toJson(it)) }))
        .put("asks", JSONObject(s.asks))
        .put("seen", JSONObject(s.seen))
        .toString()

    private fun fromJson(text: String): State {
        val o = JSONObject(text)
        val others = o.optJSONArray("others")?.let { a ->
            (0 until a.length()).mapNotNull { WherePolicy.fromJson(a.getJSONObject(it).toString()) }.associateBy { it.device }
        }.orEmpty()
        val asks = o.optJSONObject("asks")?.let { a -> a.keys().asSequence().associateWith { a.optLong(it) } }.orEmpty()
        val seen = o.optJSONObject("seen")?.let { a -> a.keys().asSequence().associateWith { a.optString(it) } }.orEmpty()
        return State(
            sharing = o.optBoolean("sharing"),
            retract = o.optBoolean("retract"),
            own = fixOf(o.optJSONObject("own")),
            sent = fixOf(o.optJSONObject("sent")),
            sentAt = o.optLong("sentAt"),
            sentExtras = o.optString("sentExtras"),
            triedAt = o.optLong("triedAt"),
            preciseFor = o.optLong("preciseFor"),
            avatarAt = o.optLong("avatarAt"),
            avatarSent = o.optLong("avatarSent"),
            myAskAt = o.optLong("myAskAt"),
            tiles = o.optString("tiles").ifBlank { "DARK" },
            syncedAt = o.optLong("syncedAt"),
            others = others,
            asks = asks,
            seen = seen,
        )
    }
}

/** Байты файла атомарно: временное имя и перенос — аватар не бывает недописанным. */
internal object StoreFilesBytes {
    fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
