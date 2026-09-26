package ru.zf.pravka.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import java.util.concurrent.Executor

/**
 * Куда смотрит микрофон диктовки: телефон или Bluetooth-гарнитура.
 *
 * Своей записи (Whisper, `AudioRecord`) устройство указывается напрямую через
 * `setPreferredDevice`, системному распознавателю (Google) — нет: он берёт
 * вход по общему маршруту связи. Поэтому весь разговор здесь — про маршрут:
 * поднять канал к гарнитуре, забрать вход себе на встроенный микрофон,
 * вернуть маршрут системе после тейка. Пока канал поднят, музыка в наушниках
 * молчит (профиль связи вытесняет музыкальный) — ровно поэтому его нельзя
 * держать поднятым постоянно и нельзя поднимать, когда владелец выбрал
 * телефон (`docs/agreements.md`, «Микрофон выбирает владелец, не подключение»).
 *
 * На Android 12+ маршрут просят через `setCommunicationDevice` — штатную
 * замену устаревшего `startBluetoothSco`; заодно система переводит туда же
 * запись, так что распознаватель Google слышит то, что попросили. До 12 —
 * старый путь.
 *
 * ВАЖНО (22.09.2026): всё это работает только с разрешением
 * `MODIFY_AUDIO_SETTINGS` в манифесте. Без него система не бросает
 * исключение, а МОЛЧА отказывает — и «телефон» в машине не забирает вход у
 * хендс-фри. Разрешение обычное, выдаётся при установке; см. манифест.
 */
object MicRouting {

    /** Встроенный микрофон телефона среди входов. */
    fun builtinMic(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    /** Микрофон Bluetooth-гарнитуры среди входов; null — гарнитура не подключена. */
    fun headsetMic(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isHeadset(it) }

    private fun isHeadset(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isBuiltin(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    /** Канал гарнитуры уже поднят (кем угодно — нами, звонком, машиной). */
    fun isScoUp(am: AudioManager): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            am.communicationDevice?.let { isHeadset(it) } == true || @Suppress("DEPRECATION") am.isBluetoothScoOn
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn
        }
    }.getOrDefault(false)

    /** Идёт разговор: в маршрут не лезем — оборвём звук собеседнику. */
    fun callInProgress(am: AudioManager): Boolean = runCatching {
        am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
    }.getOrDefault(false)

    /**
     * Поднять канал гарнитуры. Возвращает true, если маршрут заказали МЫ —
     * тогда на стопе его надо вернуть ([drop]); чужой поднятый канал не трогаем.
     */
    fun raise(am: AudioManager, log: (String) -> Unit): Boolean {
        if (isScoUp(am)) {
            log("BT SCO уже поднят — слушаем гарнитуру")
            return false
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull { isHeadset(it) }
                if (dev == null) {
                    log("гарнитуры среди устройств связи нет — SCO не поднимаем")
                    false
                } else {
                    val ok = am.setCommunicationDevice(dev)
                    log(if (ok) "BT SCO: подняли канал к «${dev.productName}»" else "BT SCO: система отказала")
                    ok
                }
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                log("BT SCO: подняли канал (startBluetoothSco)")
                true
            }
        }.getOrElse {
            log("BT SCO не поднялся: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /**
     * Забрать вход себе на встроенный микрофон. Случай владельца: «подключаюсь
     * к машине, и он не слышит» — хендс-фри держит канал, и распознаватель
     * слушает микрофон у лобового. Чужой канал опустить нельзя (не наш), а вот
     * заказать СВОЁ устройство связи можно: последняя заявка и решает, куда
     * идёт вход. Возвращает true, если заявку приняли, — тогда на стопе [drop].
     */
    fun forceBuiltin(am: AudioManager, log: (String) -> Unit): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            val current = am.communicationDevice
            if (current != null && isBuiltin(current)) {
                log("вход уже встроенный («${current.productName}») — маршрут не трогаем")
                return@runCatching false
            }
            // Наушник телефона, а не динамик: это классический разговорный
            // маршрут, вход у него — основной голосовой микрофон. Динамик —
            // запас на телефоны без наушника.
            val dev = am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?: am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (dev == null) {
                log("встроенного устройства связи нет — слушаем как есть")
                false
            } else {
                val ok = am.setCommunicationDevice(dev)
                log(
                    if (ok) "маршрут: связь смотрела на гарнитуру — забрали вход телефону («${dev.productName}»)"
                    else "маршрут: система отказала — проверь разрешение MODIFY_AUDIO_SETTINGS"
                )
                ok
            }
        } else {
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
            log("маршрут: уронили канал гарнитуры (stopBluetoothSco)")
            true
        }
    }.getOrElse {
        log("маршрут не переложился: ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /** Вернуть маршрут системе: иначе наушники так и останутся в режиме связи. */
    fun drop(am: AudioManager, log: (String) -> Unit) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
            log("маршрут: вернули системе")
        }
    }

    /**
     * Дождаться, пока канал реально поднимется: гарнитура отвечает за
     * полсекунды-секунду, и стартовать распознаватель раньше значит первые
     * слова услышать телефоном из кармана. [onReady] зовётся ровно один раз —
     * сразу, если канал уже есть, по событию системы или по таймауту.
     */
    fun awaitSco(
        context: Context,
        am: AudioManager,
        main: Handler,
        timeoutMs: Long,
        log: (String) -> Unit,
        onReady: () -> Unit,
    ) {
        if (isScoUp(am)) {
            onReady()
            return
        }
        var fired = false
        var receiver: BroadcastReceiver? = null
        val fire = { why: String ->
            if (!fired) {
                fired = true
                receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
                log("BT SCO: $why")
                onReady()
            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) main.post { fire("канал поднялся") }
            }
        }
        val registered = runCatching {
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (!registered) receiver = null
        main.postDelayed({ fire("канал не поднялся за $timeoutMs мс — стартуем как есть") }, timeoutMs)
    }

    /**
     * Дождаться, пока вход реально переедет на встроенный микрофон. Та же
     * причина, что и у [awaitSco]: маршрут переезжает не мгновенно, а
     * распознаватель, стартовавший раньше, успевает услышать первые слова
     * прежним входом — то есть салоном. Система сообщает о переезде сама
     * (Android 12+); ниже — только по таймауту, там всё равно ждать нечего
     * длиннее. [onReady] зовётся ровно один раз.
     */
    fun awaitBuiltin(
        am: AudioManager,
        main: Handler,
        timeoutMs: Long,
        log: (String) -> Unit,
        onReady: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < 31) {
            main.postDelayed(onReady, timeoutMs.coerceAtMost(SHORT_ROUTE_WAIT_MS))
            return
        }
        val done = runCatching { am.communicationDevice?.let { isBuiltin(it) } == true }.getOrDefault(false)
        if (done) {
            onReady()
            return
        }
        var fired = false
        var listener: AudioManager.OnCommunicationDeviceChangedListener? = null
        val fire = { why: String ->
            if (!fired) {
                fired = true
                listener?.let { l -> runCatching { am.removeOnCommunicationDeviceChangedListener(l) } }
                log("маршрут: $why")
                onReady()
            }
        }
        listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device != null && isBuiltin(device)) main.post { fire("вход переехал на телефон") }
        }
        val executor = Executor { command -> main.post(command) }
        val added = runCatching {
            am.addOnCommunicationDeviceChangedListener(executor, listener)
        }.isSuccess
        if (!added) listener = null
        main.postDelayed({ fire("вход не переехал за $timeoutMs мс — стартуем как есть") }, timeoutMs)
    }

    /** Состояние микрофона словами — для журнала и для отчёта владельцу. */
    fun describe(am: AudioManager): String = runCatching {
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC || isHeadset(it) || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            .joinToString(", ") {
                if (it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) "телефон" else it.productName.toString()
            }
            .ifBlank { "не видно ни одного" }
        val route = if (Build.VERSION.SDK_INT >= 31) {
            am.communicationDevice?.let { d ->
                when {
                    isHeadset(d) -> "гарнитура «${d.productName}»"
                    isBuiltin(d) -> "телефон"
                    else -> d.productName.toString()
                }
            } ?: "системный"
        } else if (isScoUp(am)) "гарнитура" else "системный"
        val mute = if (am.isMicrophoneMute) ", МИКРОФОН ЗАГЛУШЕН" else ""
        val mode = when (am.mode) {
            AudioManager.MODE_IN_CALL -> ", идёт звонок"
            AudioManager.MODE_IN_COMMUNICATION -> ", режим связи"
            else -> ""
        }
        "входы: $inputs; канал связи: $route$mute$mode"
    }.getOrElse { "состояние микрофона не прочиталось: ${it.javaClass.simpleName}" }

    /**
     * «Перезагрузить микрофон» (владелец, 22.09.2026: «подключаюсь к машине, и
     * он не слышит… а потом каким-то странным образом начинает»). Возвращает
     * строку для владельца: что было и что сделали.
     *
     * Держится в рамках: во время разговора не трогает ничего — оборвать
     * звонок ради диктовки хуже, чем не расслышать фразу.
     */
    fun reload(am: AudioManager, log: (String) -> Unit): String {
        val before = describe(am)
        log("перезагрузка микрофона, было — $before")
        if (callInProgress(am)) {
            log("перезагрузка микрофона: идёт разговор — маршрут не трогаю")
            return "Идёт разговор — маршрут не трогал.\n$before"
        }
        val wasMuted = runCatching { am.isMicrophoneMute }.getOrDefault(false)
        if (wasMuted) runCatching { am.isMicrophoneMute = false }
        val wasUp = isScoUp(am)
        drop(am, log)
        // Старый путь на всякий случай и на 12+: канал мог быть поднят до нас
        // устаревшим вызовом, и тогда `clearCommunicationDevice` о нём не знает.
        runCatching {
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoOn) am.stopBluetoothSco()
        }
        return buildString {
            append(
                if (wasUp) "Связь смотрела на гарнитуру — сбросил маршрут и опустил канал."
                else "Маршрут был системный — сбросил на всякий случай."
            )
            if (wasMuted) append(" Микрофон был заглушен — снял заглушку.")
            append('\n')
            append("Было: ").append(before)
        }
    }

    /**
     * Сколько ждать переезда входа на телефон. Полсекунды с запасом: обычно
     * система успевает за сто-двести миллисекунд, а ждать дольше — самому
     * съесть те первые слова, ради которых всё и затевалось.
     */
    const val BUILTIN_WAIT_MS = 500L

    /** До Android 12 о переезде никто не сообщает — просто короткая пауза. */
    private const val SHORT_ROUTE_WAIT_MS = 250L
}
