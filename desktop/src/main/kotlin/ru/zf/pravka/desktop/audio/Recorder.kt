package ru.zf.pravka.desktop.audio

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Запись с микрофона в WAV. Живёт на своём потоке: пропуск буфера здесь -
// это проглоченное слово, поэтому в цикле чтения не должно быть ничего,
// кроме чтения, подсчёта громкости и записи на диск.
class Recorder {

    class AudioException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val format = AudioFormat(16_000f, 16, 1, true, false)

    @Volatile private var line: TargetDataLine? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var writer: WavWriter? = null

    /** Громкость 0..1 для полоски в плашке. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    val recording: Boolean get() = thread != null

    /** Начинает запись в [file]. Бросает AudioException, если микрофона нет. */
    fun start(file: File) {
        if (recording) return
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            throw AudioException("Микрофон не поддерживает 16 кГц моно.")
        }
        val opened = try {
            (AudioSystem.getLine(info) as TargetDataLine).apply {
                open(format, 16_000)  // буфер примерно на полсекунды
                start()
            }
        } catch (e: Exception) {
            throw AudioException("Не удалось открыть микрофон: ${e.message}", e)
        }
        line = opened
        val out = WavWriter(file)
        writer = out

        thread = Thread({
            val buffer = ByteArray(3_200)  // 100 мс
            try {
                while (line === opened) {
                    val read = opened.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    out.write(buffer, read)
                    _level.value = rms(buffer, read)
                }
            } catch (_: Exception) {
                // Обрыв линии - не повод падать: то, что записалось, уже на диске.
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
            sum += (sample.toDouble() / Short.MAX_VALUE) * (sample.toDouble() / Short.MAX_VALUE)
            i += 2
        }
        val count = length / 2
        if (count == 0) return 0f
        // Корень из среднего квадрата, слегка растянутый: тихая речь тоже
        // должна двигать полоску.
        return (Math.sqrt(sum / count) * 3).coerceIn(0.0, 1.0).toFloat()
    }
}
