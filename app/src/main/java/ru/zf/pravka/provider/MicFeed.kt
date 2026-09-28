package ru.zf.pravka.provider

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Свой микрофон для системного распознавателя: Правка открывает запись сама
 * и отдаёт распознавателю звук трубой (`RecognizerIntent.EXTRA_AUDIO_SOURCE`,
 * Android 13+).
 *
 * Владелец (28.09.2026): «я специально проверил: отходил далеко в наушниках
 * от телефона, и в момент, когда я отходил, телефон просто переставал это
 * слышать… переключения на наушники — мнимые, телефон просто слушает своими
 * микрофонами». Так и было: распознаватель Google открывает микрофон в своём
 * процессе, вход ему не укажешь, и мы только просили систему перевести
 * «связь» на гарнитуру в надежде, что его запись поедет следом. Она ехала —
 * на секунды: Android (14+) держит маршрут связи только за приложением,
 * которое само пишет или играет звук, а писал распознаватель, и заявку
 * Правки система снимала (журнал 28.09: «слушает гарнитура» через 0,75 с,
 * через полминуты — телефон). Когда пишет сама Правка, маршрут — её.
 *
 * Здесь вход выбираем мы: `setPreferredDevice` у СВОЕЙ записи — гарнитура или
 * телефон — и смена на ходу, не трогая распознаватель вовсе. Заодно
 * пропадают глухие окна: звук копится у нас в очереди, пока распознаватель
 * поднимается (старт тейка, перезапуск после ошибки), и уходит ему целиком,
 * как только он снова читает, — сказанное в эти секунды больше не пропадает.
 *
 * Два потока: запись кладёт куски по 50 мс в очередь, писатель перекладывает
 * их в трубу текущей сессии. Труба неблокирующая и короткая (около полусекунды):
 * распознаватель, переставший читать, не держит писателя, а то, что он не
 * дочитал, — не больше полусекунды. Новая сессия — новая труба
 * ([openSource]); старую закрывает сам писатель — единственный, кто в неё
 * пишет, — чтобы закрытый номер дескриптора не достался чужому файлу посреди
 * записи.
 *
 * Память звука (владелец, 28.09.2026: «иногда в конце может застревать и
 * просто вообще уже ничего не воспринимать… что бы я ни говорил, ничего не
 * получается»): последние полторы минуты записи хранятся, и новая сессия
 * распознавателя может получить звук не с «сейчас», а с последнего слова,
 * которое прежняя успела разобрать ([openSource] с `rewindSinceMs`). Сессия,
 * которая слышала речь и не отдала ни слова, заменяется новой, и та
 * разбирает те же секунды заново.
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO проверяется до любого тейка
class MicFeed private constructor(
    private val record: AudioRecord,
    private val log: (String) -> Unit,
) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS = 1

        /** Кусок записи: 50 мс. Меньше PIPE_BUF (4096) — в трубу уходит целиком или никак. */
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 / 20

        /** Байт звука в миллисекунде: 16 кГц × 2 байта. */
        private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000

        /** Сколько звука держим, пока распознаватель не читает: три минуты. Дальше — старое выкидываем. */
        private const val BACKLOG_MAX_BYTES = SAMPLE_RATE * 2 * 180

        /** Размер трубы: около полусекунды — столько теряется, если сессия умерла, не дочитав. */
        private const val PIPE_BYTES = 16_384

        /** `F_SETPIPE_SZ` из fcntl.h: в OsConstants его нет. */
        private const val F_SETPIPE_SZ = 1031

        /** Память звука для повтора: полторы минуты. */
        private const val HISTORY_MAX_BYTES = SAMPLE_RATE * 2 * 90

        /** Дописать хвост после стопа — не дольше этого: распознаватель мог перестать читать. */
        private const val DRAIN_MAX_MS = 8_000L

        /** Громкость: тишина и полный голос в dBFS — для волны в пилюле. */
        private const val QUIET_DBFS = -55.0
        private const val LOUD_DBFS = -20.0

        /**
         * Открыть запись: 16 кГц, моно, VOICE_RECOGNITION — тот же источник,
         * что берёт сам распознаватель, без телефонной обработки звонка.
         * Не вышло — null, и тейк идёт прежней дорогой: распознаватель слушает сам.
         */
        fun open(log: (String) -> Unit): MicFeed? {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING)
            if (minBuf <= 0) {
                log("свой микрофон: запись 16 кГц не поддерживается ($minBuf)")
                return null
            }
            val rec = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    ENCODING,
                    maxOf(minBuf, CHUNK_BYTES) * 4,
                )
            }.getOrElse {
                log("свой микрофон не открылся: ${it.javaClass.simpleName}: ${it.message}")
                return null
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                log("свой микрофон не открылся: запись не инициализировалась")
                runCatching { rec.release() }
                return null
            }
            return MicFeed(rec, log)
        }
    }

    /** Номер сессии нашей записи — по нему своя запись отличается от чужой в списке системы. */
    val sessionId: Int get() = record.audioSessionId

    private val lock = Object()
    private val backlog = ArrayDeque<ByteArray>()
    private var backlogBytes = 0
    private var droppedBytes = 0L

    /** Кусок записи и когда он кончился (elapsedRealtime) — для повтора. */
    private class Piece(val atMs: Long, val pcm: ByteArray)
    private val history = ArrayDeque<Piece>()
    private var historyBytes = 0

    /**
     * Номер перемотки: писатель, державший кусок до неё, его выбрасывает —
     * этот кусок уже лежит в повторе. Меняется под [lock].
     */
    private var generation = 0

    /** Сколько звука пошло в повтор при последней перемотке, мс. */
    @Volatile var lastRewindMs = 0L
        private set

    @Volatile private var running = true
    @Volatile private var draining = false
    @Volatile private var drainDeadline = 0L
    /** Труба новой сессии, ждущая писателя. Под [lock] — вместе с перемоткой. */
    private var nextTarget: ParcelFileDescriptor? = null

    /** Сколько байт ушло распознавателю всего — растёт, значит он читает. */
    @Volatile var writtenBytes = 0L
        private set

    /** Когда в трубу ушёл последний кусок (elapsedRealtime). */
    @Volatile var lastWriteAtMs = 0L
        private set

    /** Громкость 0..1 из самой записи — зовётся с потока записи. */
    var onLevel: ((Float) -> Unit)? = null

    /** Наши копии концов чтения: труба уходит распознавателю через Binder, его копия — своя. */
    private val readEnds = ArrayList<ParcelFileDescriptor>()

    /** Запустить запись и писателя. false — запись не пошла (микрофон занят, нет прав). */
    fun start(): Boolean {
        runCatching { record.startRecording() }.onFailure {
            log("свой микрофон: запись не пошла — ${it.javaClass.simpleName}")
            runCatching { record.release() }
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            log("свой микрофон: запись не пошла — микрофон занят")
            runCatching { record.release() }
            return false
        }
        thread(name = "pravka-feed-mic") { captureLoop() }
        thread(name = "pravka-feed-pipe") { writeLoop() }
        return true
    }

    /**
     * Труба для новой сессии распознавателя: конец чтения — в интент, конец
     * записи — писателю. Прежнюю трубу писатель закроет сам. null — трубы нет.
     *
     * [rewindSinceMs] > 0 — новая сессия получит звук с этого мига
     * (elapsedRealtime), а не только то, что ещё не отправлено: всё, что
     * прежняя сессия прочла, но не разобрала, будет разобрано заново.
     * Перемотка и новая труба ставятся одним движением под замком, иначе
     * писатель успел бы отдать кусок повтора ещё старой, мёртвой трубе.
     */
    fun openSource(rewindSinceMs: Long = 0L): ParcelFileDescriptor? {
        val pair = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse {
            log("свой микрофон: труба не создалась — ${it.javaClass.simpleName}")
            return null
        }
        val (readEnd, writeEnd) = pair[0] to pair[1]
        runCatching {
            val fd = writeEnd.fileDescriptor
            // У F_GETFL третий аргумент не читается; fcntlVoid в открытом API нет.
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }.onFailure {
            // Блокирующая труба повесила бы писателя на мёртвой сессии — такую не отдаём.
            log("свой микрофон: труба не стала неблокирующей — ${it.javaClass.simpleName}")
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
            return null
        }
        runCatching { Os.fcntlInt(writeEnd.fileDescriptor, F_SETPIPE_SZ, PIPE_BYTES) }
        val stale = synchronized(lock) {
            val prev = nextTarget
            nextTarget = writeEnd
            lastRewindMs = 0L
            if (rewindSinceMs > 0L) {
                generation++
                backlog.clear()
                backlogBytes = 0
                for (p in history) {
                    if (p.atMs > rewindSinceMs) {
                        backlog.addLast(p.pcm)
                        backlogBytes += p.pcm.size
                    }
                }
                lastRewindMs = (backlogBytes / BYTES_PER_MS).toLong()
            }
            lock.notifyAll()
            prev
        }
        // Прежняя ждущая труба писателю не досталась — значит, он в неё не писал: закрыть можно здесь.
        stale?.let { runCatching { it.close() } }
        // Прежние концы чтения к этому мигу у распознавателя (интент ушёл) — наша копия лишняя.
        readEnds.forEach { runCatching { it.close() } }
        readEnds.clear()
        readEnds += readEnd
        return readEnd
    }

    /** Куда смотрит запись: гарнитура, телефон; null — как решит система. */
    fun setDevice(device: AudioDeviceInfo?): Boolean = runCatching { record.setPreferredDevice(device) }.getOrDefault(false)

    /** Что запись слушает на деле, по словам системы. */
    fun routed(): AudioDeviceInfo? = runCatching { record.routedDevice }.getOrNull()

    /** Система отдала микрофон другому, и наша запись получает тишину. */
    fun silenced(): Boolean =
        Build.VERSION.SDK_INT >= 29 &&
            runCatching { record.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)

    /** Сколько звука ждёт распознавателя, мс. */
    fun backlogMs(): Long = synchronized(lock) { (backlogBytes / BYTES_PER_MS).toLong() }

    /**
     * Стоп тейка: запись кончается сейчас, а накопленное дописывается в трубу
     * и труба закрывается — распознаватель дочитывает до конца и сам кончает
     * сессию. Сказанное перед самым «стопом» не теряется.
     */
    fun finish() {
        if (draining) return
        drainDeadline = SystemClock.elapsedRealtime() + DRAIN_MAX_MS
        draining = true
        runCatching { record.stop() }
        synchronized(lock) { lock.notifyAll() }
    }

    /** Бросить всё: тейк кончен, отменён или распознаватель наш звук не берёт. */
    fun abort() {
        running = false
        draining = true
        runCatching { record.stop() }
        synchronized(lock) { lock.notifyAll() }
        readEnds.forEach { runCatching { it.close() } }
        readEnds.clear()
    }

    private fun captureLoop() {
        var lastLevelAt = 0L
        var failures = 0
        try {
            while (running && !draining) {
                val buf = ByteArray(CHUNK_BYTES)
                val n = runCatching { record.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) {
                    if (++failures == 40) log("свой микрофон: запись молчит ($n) — две секунды подряд")
                    Thread.sleep(50)
                    continue
                }
                failures = 0
                val chunk = if (n == buf.size) buf else buf.copyOf(n)
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelAt >= 60) {
                    lastLevelAt = now
                    onLevel?.let { sink -> runCatching { sink(level(chunk, n)) } }
                }
                synchronized(lock) {
                    backlog.addLast(chunk)
                    backlogBytes += chunk.size
                    while (backlogBytes > BACKLOG_MAX_BYTES) {
                        val old = backlog.removeFirst()
                        backlogBytes -= old.size
                        droppedBytes += old.size
                    }
                    history.addLast(Piece(now, chunk))
                    historyBytes += chunk.size
                    while (historyBytes > HISTORY_MAX_BYTES) historyBytes -= history.removeFirst().pcm.size
                    lock.notifyAll()
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            // Отпускает запись тот же поток, что читал: release посреди read роняет процесс.
            runCatching { record.stop() }
            runCatching { record.release() }
            if (droppedBytes > 0) log("свой микрофон: распознаватель не читал — выброшено ${droppedBytes / BYTES_PER_MS / 1000} с старого звука")
        }
    }

    private fun writeLoop() {
        var target: ParcelFileDescriptor? = null
        var chunk: ByteArray? = null
        var chunkGen = 0
        try {
            while (running) {
                // Новая труба и следующий кусок — под одним замком с перемоткой:
                // после неё первый же взятый кусок — уже из повтора и уже в новую трубу.
                var fresh: ParcelFileDescriptor? = null
                var empty = false
                synchronized(lock) {
                    fresh = nextTarget
                    nextTarget = null
                    if (chunk != null && chunkGen != generation) chunk = null
                    if (chunk == null && backlog.isNotEmpty()) {
                        chunk = backlog.removeFirst().also { backlogBytes -= it.size }
                        chunkGen = generation
                    }
                    empty = chunk == null && backlog.isEmpty()
                    if (chunk == null && fresh == null && !draining) lock.wait(50)
                }
                fresh?.let { next ->
                    target?.let { runCatching { it.close() } }
                    target = next
                }
                val c = chunk
                if (c == null) {
                    // Стоп и всё дописано — закрыть трубу: распознаватель увидит конец звука.
                    if (draining && empty) break
                    continue
                }
                if (draining && SystemClock.elapsedRealtime() > drainDeadline) break
                val out = target
                if (out == null) {
                    // Сессии нет (распознаватель поднимается): кусок ждёт следующую трубу.
                    if (draining) break
                    Thread.sleep(10)
                    continue
                }
                try {
                    val n = Os.write(out.fileDescriptor, c, 0, c.size)
                    if (n >= c.size) {
                        writtenBytes += n
                        lastWriteAtMs = SystemClock.elapsedRealtime()
                        chunk = null
                    }
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN) {
                        // Труба полна: распознаватель ещё не читает или читает медленно.
                        Thread.sleep(5)
                    } else {
                        // EPIPE и прочее: распознаватель закрыл свой конец — кусок уйдёт в новую сессию.
                        runCatching { out.close() }
                        target = null
                    }
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            target?.let { runCatching { it.close() } }
            synchronized(lock) { nextTarget.also { nextTarget = null } }?.let { runCatching { it.close() } }
        }
    }

    private fun level(pcm: ByteArray, n: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sum += s.toDouble() * s
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        if (rms < 1.0) return 0f
        val dbfs = 20 * log10(rms / 32768.0)
        return ((dbfs - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS)).coerceIn(0.0, 1.0).toFloat()
    }
}
