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
 * Проиграть звук диктовки (`core/ReadyChime.kt`) — туда, где владелец его
 * услышит.
 *
 * В наушники, пока их канал поднят под микрофон, — назначением «связь» и
 * прямо в выход канала гарнитуры: музыкальный канал тех же наушников в это
 * время молчит, и звук с назначением «уведомление» ушёл бы в динамик
 * телефона — то есть на стол, к которому владелец не смотрит. Остальное —
 * назначением «помощник», как голос в конце (`Speaker.kt`): туда, где играет
 * музыка (динамик или наушники по музыкальному каналу), и беззвучный режим
 * его не глушит — звук интерфейса у владельца в беззвучном молчал бы, и
 * «Послушать» тоже.
 *
 * «Наушники отвалились» ([Kind.LOST]) — всегда «помощником» и с паузой:
 * канал гарнитуры только что упал, и музыкальный канал тех же наушников
 * возвращается не сразу — сыграй сразу, звук ушёл бы в динамик. Так же —
 * «принял» после стопа кнопкой гарнитуры: её канал закрыла она сама.
 *
 * Свой поток: создание дорожки — вызов в аудиосистему, а главному потоку
 * службы тяжёлое запрещено (складывание Fold). Дорожка на один раз: звук
 * звучит секунду, держать её незачем.
 */
object ChimePlayer {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pravka-chime").apply { isDaemon = true } }

    enum class Kind(val word: String) {
        /** «Говори»: всё поднялось и слышит. */
        READY("говори"),

        /** «Принял»: стоп нажат, сказанное расшифровывается. */
        STOP("принял"),

        /** «Наушники отвалились»: слушает уже телефон. */
        LOST("отвалились"),
    }

    @Volatile private var readyPcm: ShortArray? = null
    @Volatile private var stopPcm: ShortArray? = null
    @Volatile private var lostPcm: ShortArray? = null

    /** Музыкальный канал наушников возвращается после канала гарнитуры не сразу. */
    const val LOST_DELAY_MS = 700L

    /**
     * Сыграть [kind] через [delayMs]. Возвращает, через сколько миллисекунд
     * от этого мига звук отзвучит: голос в конце («Расшифровал») ждёт его и
     * не говорит поверх.
     */
    fun play(
        context: Context,
        toHeadset: Boolean,
        kind: Kind = Kind.READY,
        delayMs: Long = if (kind == Kind.LOST) LOST_DELAY_MS else 0L,
        log: (String) -> Unit = {},
    ): Long {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                if (delayMs > 0) Thread.sleep(delayMs)
                // Синтез — здесь, на своём потоке: первый раз это десятки тысяч отсчётов с эхом.
                playNow(app, toHeadset = toHeadset && kind != Kind.LOST, data = pcm(kind))
            }.onFailure { log("звук «${kind.word}» не сыгрался: ${it.javaClass.simpleName}: ${it.message}") }
        }
        return delayMs + lengthMs(kind)
    }

    /** Длина звука, мс — по числам звука, без синтеза. */
    fun lengthMs(kind: Kind): Long = ReadyChime.LEAD_MS + when (kind) {
        Kind.READY -> ReadyChime.READY_BODY_MS
        Kind.STOP -> ReadyChime.STOP_BODY_MS
        Kind.LOST -> ReadyChime.LOST_BODY_MS
    }.toLong()

    private fun pcm(kind: Kind): ShortArray = when (kind) {
        Kind.READY -> readyPcm ?: ReadyChime.render().also { readyPcm = it }
        Kind.STOP -> stopPcm ?: ReadyChime.renderStop().also { stopPcm = it }
        Kind.LOST -> lostPcm ?: ReadyChime.renderLost().also { lostPcm = it }
    }

    private fun playNow(context: Context, toHeadset: Boolean, data: ShortArray) {
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
