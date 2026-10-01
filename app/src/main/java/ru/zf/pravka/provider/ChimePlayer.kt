package ru.zf.pravka.provider

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * «Готово» ([Kind.DONE]) — тоже «помощником»: к концу разбора канал
 * гарнитуры давно закрыт, а музыкальный канал тех же наушников вернулся.
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

    /**
     * Свой поток со сроками: ожидание (пауза «через N секунд», канал
     * наушников) не держит поток — отложенный «говори» не задерживает
     * «принял», вставший за ним.
     */
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pravka-chime").apply { isDaemon = true } }

    enum class Kind(val word: String) {
        /** «Говори»: всё поднялось и слышит. */
        READY("говори"),

        /** «Принял»: стоп нажат, сказанное расшифровывается. */
        STOP("принял"),

        /** «Готово»: разобрано и легло на место — в поле, в ленту, в дневник. */
        DONE("готово"),

        /** «Наушники отвалились»: слушает уже телефон. */
        LOST("отвалились"),
    }

    @Volatile private var readyPcm: ShortArray? = null
    @Volatile private var stopPcm: ShortArray? = null
    @Volatile private var donePcm: ShortArray? = null
    @Volatile private var lostPcm: ShortArray? = null

    /**
     * Поколение «говори»: стоп тейка его сдвигает, и отложенный «говори»
     * ([ReadyChime.readyDelayMs]), не успевший прозвучать, молчит — короткий
     * тейк не должен звенеть «говори» после «принял».
     */
    private val readyEpoch = AtomicInteger()

    /** Тейк кончился: «говори», ещё ждущий своего срока, не звенит. */
    fun cancelReady() {
        readyEpoch.incrementAndGet()
    }

    /** Музыкальный канал наушников возвращается после канала гарнитуры не сразу. */
    const val LOST_DELAY_MS = 700L

    /**
     * Сыграть [kind] через [delayMs]. Возвращает, через сколько миллисекунд
     * от этого мига звук отзвучит: распознавание у стека гарнитуры
     * закрывается после него — закрытый канал оборвал бы щелчок.
     */
    fun play(
        context: Context,
        toHeadset: Boolean,
        kind: Kind = Kind.READY,
        delayMs: Long = if (kind == Kind.LOST) LOST_DELAY_MS else 0L,
        log: (String) -> Unit = {},
    ): Long {
        val app = context.applicationContext
        val intoHeadset = toHeadset && kind != Kind.LOST && kind != Kind.DONE
        val epoch = readyEpoch.get()
        val attempt = object : Runnable {
            private var waited = 0L
            override fun run() {
                runCatching {
                    if (kind == Kind.READY && readyEpoch.get() != epoch) {
                        log("звук «говори» не сыгран: тейк уже кончился")
                        return
                    }
                    // Звук в наушники — когда их канал встал: сыгранный раньше, он
                    // уходит в канал, которого ещё нет, и не слышен (владелец,
                    // 29.09.2026: «звук не слышен теперь»; холодный канал
                    // поднимается до 2,3 с). Ждёт только звук — запись и
                    // распознаватель идут своим ходом. За каналом не следим (стек
                    // не подтвердил распознавание) — играем сразу.
                    if (intoHeadset) {
                        val probe = MicRouting.linkProbe
                        if (probe != null && runCatching { probe() }.getOrNull() == false) {
                            if (waited < HEADSET_CHANNEL_WAIT_MS) {
                                waited += CHANNEL_POLL_MS
                                worker.schedule(this, CHANNEL_POLL_MS, TimeUnit.MILLISECONDS)
                                return
                            }
                            log("звук: канал наушников не встал за $waited мс — играю как есть")
                        }
                    }
                    // Синтез — здесь, на своём потоке: первый раз это тысячи отсчётов с хвостом комнаты.
                    playNow(app, toHeadset = intoHeadset, data = pcm(kind))
                }.onFailure { log("звук «${kind.word}» не сыгрался: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
        worker.schedule(attempt, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        return delayMs + lengthMs(kind)
    }

    /** Сколько звук ждёт канала наушников. */
    private const val HEADSET_CHANNEL_WAIT_MS = 2_500L
    private const val CHANNEL_POLL_MS = 40L

    /** Длина звука, мс — по числам звука, без синтеза. */
    fun lengthMs(kind: Kind): Long = ReadyChime.LEAD_MS + when (kind) {
        Kind.READY -> ReadyChime.READY_BODY_MS
        Kind.STOP -> ReadyChime.STOP_BODY_MS
        Kind.DONE -> ReadyChime.DONE_BODY_MS
        Kind.LOST -> ReadyChime.LOST_BODY_MS
    }.toLong()

    private fun pcm(kind: Kind): ShortArray = when (kind) {
        Kind.READY -> readyPcm ?: ReadyChime.render().also { readyPcm = it }
        Kind.STOP -> stopPcm ?: ReadyChime.renderStop().also { stopPcm = it }
        Kind.DONE -> donePcm ?: ReadyChime.renderDone().also { donePcm = it }
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
