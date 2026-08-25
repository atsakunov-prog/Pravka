package ru.zf.pravka.desktop.audio

import java.io.File
import java.io.RandomAccessFile

// Пишет 16 кГц моно PCM16 WAV - ровно тот формат, что пишет телефон и ждёт
// распознаватель. Размеры в заголовке проставляются при закрытии, поэтому
// оборванная запись остаётся читаемой ровно до места обрыва.
class WavWriter(file: File, private val sampleRate: Int = 16_000) : AutoCloseable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))  // место под заголовок
    }

    fun write(bytes: ByteArray, length: Int) {
        raf.write(bytes, 0, length)
        dataBytes += length
    }

    override fun close() {
        val byteRate = sampleRate * 2
        raf.seek(0)
        raf.write("RIFF".toByteArray())
        raf.writeIntLe(36 + dataBytes)
        raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray())
        raf.writeIntLe(16)          // размер fmt-блока
        raf.writeShortLe(1)         // PCM
        raf.writeShortLe(1)         // моно
        raf.writeIntLe(sampleRate)
        raf.writeIntLe(byteRate)
        raf.writeShortLe(2)         // выравнивание блока
        raf.writeShortLe(16)        // бит на отсчёт
        raf.write("data".toByteArray())
        raf.writeIntLe(dataBytes)
        raf.close()
    }

    val durationMs: Long get() = dataBytes * 1000L / (sampleRate * 2)

    private fun RandomAccessFile.writeIntLe(value: Int) {
        write(byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte(),
        ))
    }

    private fun RandomAccessFile.writeShortLe(value: Int) {
        write(byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte()))
    }
}
