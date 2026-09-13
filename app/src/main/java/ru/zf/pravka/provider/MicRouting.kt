package ru.zf.pravka.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler

/**
 * Куда смотрит микрофон диктовки: телефон или Bluetooth-гарнитура.
 *
 * Своей записи (Whisper, `AudioRecord`) устройство указывается напрямую через
 * `setPreferredDevice`, системному распознавателю (Google) — нет: он берёт
 * Bluetooth-микрофон только при поднятом канале SCO, а без него слушает
 * телефон. Поэтому «гарнитура» для обоих движков значит одно и то же: поднять
 * SCO перед тейком и опустить после. Пока канал поднят, музыка в наушниках
 * молчит (профиль связи вытесняет музыкальный) — ровно поэтому его нельзя
 * держать поднятым постоянно и нельзя поднимать, когда владелец выбрал
 * телефон (`docs/agreements.md`, «Микрофон выбирает владелец, не подключение»).
 *
 * На Android 12+ канал поднимает `setCommunicationDevice` — штатная замена
 * устаревшего `startBluetoothSco`; заодно система переводит запись на SCO
 * (`FOR_RECORD → BT_SCO`), так что распознаватель Google слышит гарнитуру.
 * До 12 — старый путь.
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

    /** Канал гарнитуры уже поднят (кем угодно — нами, звонком, машиной). */
    fun isScoUp(am: AudioManager): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            am.communicationDevice?.let { isHeadset(it) } == true || @Suppress("DEPRECATION") am.isBluetoothScoOn
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn
        }
    }.getOrDefault(false)

    /**
     * Поднять канал гарнитуры. Возвращает true, если канал запросили МЫ — тогда
     * на стопе его надо опустить ([drop]); чужой поднятый канал не трогаем.
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

    /** Опустить канал, поднятый нами: иначе наушники так и останутся в режиме связи. */
    fun drop(am: AudioManager, log: (String) -> Unit) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
            log("BT SCO: опустили канал")
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
}
