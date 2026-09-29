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
 * которому владелец не смотрит. На телефоне — назначением «звук интерфейса»:
 * динамик или наушники, в которых играет музыка.
 *
 * Свой поток: создание дорожки — вызов в аудиосистему, а главному потоку
 * службы тяжёлое запрещено (складывание Fold). Дорожка на один раз: звук
 * звучит полсекунды, держать её незачем.
 */
object ChimePlayer {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pravka-chime").apply { isDaemon = true } }

    @Volatile private var pcm: ShortArray? = null

    fun play(context: Context, toHeadset: Boolean, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            runCatching { playNow(app, toHeadset) }
                .onFailure { log("звук «говори» не сыгрался: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun playNow(context: Context, toHeadset: Boolean) {
        val data = pcm ?: ReadyChime.render().also { pcm = it }
        val attrs = AudioAttributes.Builder()
            .setUsage(if (toHeadset) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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
