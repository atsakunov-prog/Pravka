package ru.zf.pravka.provider

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.Executors
import ru.zf.pravka.core.ReadyChime

/**
 * Проиграть звук «говори» (`core/ReadyChime.kt`) — туда, где владелец его
 * услышит.
 *
 * В наушники — назначением «связь» и прямо в выход канала гарнитуры: пока
 * он поднят под микрофон, музыкальный канал тех же наушников молчит, и звук с
 * назначением «уведомление» ушёл бы в динамик телефона — то есть на стол, к
 * которому владелец не смотрит. Остальное — назначением «помощник», как голос
 * Засечки в конце (`Speaker.kt`): туда, где играет музыка (динамик или
 * наушники по музыкальному каналу), и беззвучный режим его не глушит —
 * звук интерфейса у владельца в беззвучном молчал бы, и «Послушать» тоже.
 *
 * «Наушники отвалились» ([Kind.LOST]) — всегда «помощником» и с паузой:
 * канал гарнитуры только что упал, и музыкальный канал тех же наушников
 * возвращается не сразу — сыграй сразу, звук ушёл бы в динамик.
 *
 * Свой поток: создание дорожки — вызов в аудиосистему, а главному потоку
 * службы тяжёлое запрещено (складывание Fold). Дорожка на один раз: звук
 * звучит полсекунды, держать её незачем.
 */
object ChimePlayer {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pravka-chime").apply { isDaemon = true } }

    enum class Kind {
        /** «Говори»: всё поднялось и слышит. */
        READY,

        /** «Наушники отвалились»: слушает уже телефон. */
        LOST,
    }

    @Volatile private var readyPcm: ShortArray? = null
    @Volatile private var lostPcm: ShortArray? = null

    /** Музыкальный канал наушников возвращается после канала гарнитуры не сразу. */
    private const val LOST_DELAY_MS = 700L

    fun play(context: Context, toHeadset: Boolean, kind: Kind = Kind.READY, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                if (kind == Kind.LOST) Thread.sleep(LOST_DELAY_MS)
                playNow(app, toHeadset = toHeadset && kind == Kind.READY, kind = kind)
            }.onFailure { log("звук «${if (kind == Kind.READY) "говори" else "отвалились"}» не сыгрался: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun playNow(context: Context, toHeadset: Boolean, kind: Kind) {
        val data = when (kind) {
            Kind.READY -> readyPcm ?: ReadyChime.render().also { readyPcm = it }
            Kind.LOST -> lostPcm ?: ReadyChime.renderLost().also { lostPcm = it }
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(if (toHeadset) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(ReadyChime.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        try {
            track.write(data, 0, data.size)
            if (toHeadset) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                headsetOut(am)?.let { track.setPreferredDevice(it) }
            }
            track.play()
            Thread.sleep(data.size * 1000L / ReadyChime.SAMPLE_RATE + 150L)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /** Выход канала гарнитуры (звонковый), если он есть. */
    private fun headsetOut(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
}
