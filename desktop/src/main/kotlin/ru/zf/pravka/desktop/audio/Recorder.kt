package ru.zf.pravka.desktop.audio

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Запись с микрофона в WAV. Живёт на своём потоке: пропуск буфера здесь -
// это проглоченное слово, поэтому в цикле чтения нет ничего, кроме чтения,
// подсчёта громкости и записи на диск.
//
// Формат подбирается под железо. 16 кГц моно - идеал (столько же пишет
// телефон, и распознавателю не придётся ничего пересчитывать), но далеко не
// каждый микрофон на Windows отдаёт его напрямую, а падать из-за этого при
// первой же диктовке нельзя. Что записалось - то и уедет на сервер: он
// принимает любую частоту и приводит её сам.
class Recorder {

    class AudioException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val preferred = listOf(
        AudioFormat(16_000f, 16, 1, true, false),
        AudioFormat(48_000f, 16, 1, true, false),
        AudioFormat(44_100f, 16, 1, true, false),
        AudioFormat(48_000f, 16, 2, true, false),
        AudioFormat(44_100f, 16, 2, true, false),
    )

    @Volatile private var line: TargetDataLine? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var writer: WavWriter? = null

    /** Формат, на котором в итоге удалось открыть микрофон, - для журнала. */
    @Volatile var format: AudioFormat? = null
        private set

    /** Громкость 0..1 для полоски в плашке. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    val recording: Boolean get() = thread != null

    /** Начинает запись в [file]. Бросает AudioException, если микрофона нет. */
    fun start(file: File) {
        if (recording) return

        var opened: TargetDataLine? = null
        var chosen: AudioFormat? = null
        var lastError: Exception? = null
        for (candidate in preferred) {
            val info = DataLine.Info(TargetDataLine::class.java, candidate)
            if (!AudioSystem.isLineSupported(info)) continue
            try {
                opened = (AudioSystem.getLine(info) as TargetDataLine).apply {
                    // Буфер примерно на полсекунды звука.
                    open(candidate, (candidate.frameSize * candidate.sampleRate / 2).toInt())
                    start()
                }
                chosen = candidate
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (opened == null || chosen == null) {
            throw AudioException(
                "Не удалось открыть микрофон" +
                    (lastError?.message?.let { ": $it" } ?: ". Проверь, что он есть и разрешён в Windows."),
                lastError,
            )
        }

        line = opened
        format = chosen
        val out = WavWriter(file, chosen.sampleRate.toInt(), chosen.channels)
        writer = out

        thread = Thread({
            // Примерно 100 мс звука за чтение.
            val buffer = ByteArray(chosen.frameSize * chosen.sampleRate.toInt() / 10)
            try {
                while (line === opened) {
                    val read = opened.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    out.write(buffer, read)
                    _level.value = rms(buffer, read)
                }
            } catch (_: Exception) {
                // Обрыв линии - не повод падать: что записалось, уже на диске.
            }
        }, "pravka-recorder").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Останавливает запись и возвращает длительность записанного, мс. */
    fun stop(): Long {
        val opened = line ?: return 0
        line = null
        runCatching { opened.stop() }
        runCatching { opened.close() }
        thread?.join(1_000)
        thread = null
        val out = writer
        writer = null
        val ms = out?.durationMs ?: 0
        runCatching { out?.close() }
        _level.value = 0f
        return ms
    }

    private fun rms(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort()
            val normalized = sample.toDouble() / Short.MAX_VALUE
            sum += normalized * normalized
            i += 2
        }
        val count = length / 2
        if (count == 0) return 0f
        // Корень из среднего квадрата, слегка растянутый: тихая речь тоже
        // должна двигать полоску.
        return (Math.sqrt(sum / count) * 3).coerceIn(0.0, 1.0).toFloat()
    }
}
