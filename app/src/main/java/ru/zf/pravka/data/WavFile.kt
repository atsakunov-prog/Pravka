package ru.zf.pravka.data

import java.io.File
import java.io.RandomAccessFile

// Minimal 16 kHz mono 16-bit PCM WAV writer/reader. 16k mono PCM is the
// lingua franca of on-device ASR - both Gemini Nano's speech recognizer and
// a home Whisper accept it without transcoding. The header is written with a
// placeholder size and patched on close(), so a recording killed mid-write
// is still a valid (if truncated) WAV.
object WavFile {

    const val SAMPLE_RATE = 16_000
    private const val CHANNELS = 1
    private const val BITS = 16
    private const val HEADER = 44

    class Writer(file: File) {
        private val raf = RandomAccessFile(file, "rw")
        private var dataBytes = 0

        init {
            raf.setLength(0)
            raf.write(ByteArray(HEADER))  // placeholder, patched on close()
        }

        fun write(buffer: ByteArray, length: Int) {
            raf.write(buffer, 0, length)
            dataBytes += length
        }

        fun close() {
            runCatching {
                raf.seek(0)
                raf.write(header(dataBytes))
                raf.fd.sync()
            }
            runCatching { raf.close() }
        }
    }

    private fun header(dataBytes: Int): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        val blockAlign = CHANNELS * BITS / 8
        val totalMinus8 = HEADER - 8 + dataBytes
        fun le32(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), (v shr 8 and 0xff).toByte(),
            (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte(),
        )
        fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
        return "RIFF".toByteArray() + le32(totalMinus8) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(16) + le16(1) + le16(CHANNELS) +
            le32(SAMPLE_RATE) + le32(byteRate) + le16(blockAlign) + le16(BITS) +
            "data".toByteArray() + le32(dataBytes)
    }

    fun durationMs(file: File): Long {
        val dataBytes = (file.length() - HEADER).coerceAtLeast(0)
        val bytesPerMs = SAMPLE_RATE * CHANNELS * BITS / 8 / 1000.0
        return if (bytesPerMs > 0) (dataBytes / bytesPerMs).toLong() else 0
    }
}
