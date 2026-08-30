package ru.zf.slushalka.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Кусок звука из середины файла в чистый PCM.
 *
 * Нужен ровно для одного: отдать распознавателю последние секунд десять
 * записи, чтобы понять, какое место книги сейчас читают. Играющего плеера это
 * не касается - декодер тут свой, отдельный.
 */
object AudioChunk {

    /** Моно, 16 бит, порядок байтов машинный - как любит распознаватель. */
    class Pcm(val bytes: ByteArray, val sampleRate: Int)

    fun decode(context: Context, uri: Uri, startMs: Long, durationMs: Long): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            val input = format ?: return null
            if (track < 0) return null
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = input.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(input, null, null, 0)
            codec.start()

            var rate = input.runCatching { getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
            var channels = input.runCatching { getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            var floatPcm = false

            val fromUs = startMs * 1000
            val toUs = (startMs + durationMs) * 1000
            val out = ByteArrayOutputStream(durationMs.toInt() * 32)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0

            while (!outputDone && guard++ < MAX_LOOPS) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val ts = extractor.sampleTime
                            codec.queueInputBuffer(index, 0, size, ts, 0)
                            extractor.advance()
                            // Дальше конца куска читать нечего - закрываем вход,
                            // иначе декодер честно домотает файл до конца.
                            if (ts > toUs) {
                                inputDone = true
                            }
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index >= 0 -> {
                        if (info.size > 0 && info.presentationTimeUs >= fromUs) {
                            codec.getOutputBuffer(index)?.let { buf ->
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                appendMono(out, buf, channels, floatPcm)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                            info.presentationTimeUs > toUs
                        ) outputDone = true
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        rate = of.runCatching { getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(rate)
                        channels = of.runCatching { getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                            .getOrDefault(channels)
                        floatPcm = of.runCatching { getInteger(MediaFormat.KEY_PCM_ENCODING) }
                            .getOrDefault(2) == 4   // AudioFormat.ENCODING_PCM_FLOAT
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) {
                        // Декодер молчит и входа больше нет - выходим, а не ждём вечно.
                        if (guard > MAX_LOOPS / 2) outputDone = true
                    }
                }
            }
            val bytes = out.toByteArray()
            return if (bytes.size < 4000) null else Pcm(bytes, rate)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Сводит любое число каналов в моно 16 бит: распознавателю больше не нужно. */
    private fun appendMono(out: ByteArrayOutputStream, buf: ByteBuffer, channels: Int, floatPcm: Boolean) {
        buf.order(ByteOrder.nativeOrder())
        val ch = channels.coerceAtLeast(1)
        if (floatPcm) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / ch
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) sum += fb.get(i * ch + c)
                val v = ((sum / ch) * 32767f).toInt().coerceIn(-32768, 32767)
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
        } else {
            val sb = buf.asShortBuffer()
            val frames = sb.remaining() / ch
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until ch) sum += sb.get(i * ch + c).toInt()
                val v = (sum / ch).coerceIn(-32768, 32767)
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
        }
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_LOOPS = 20_000
}
