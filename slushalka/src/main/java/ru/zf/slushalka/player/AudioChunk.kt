package ru.zf.slushalka.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Кусок звука из середины файла в чистый PCM 16 кГц моно.
 *
 * Нужен ровно для одного: отдать распознавателю несколько секунд записи, чтобы
 * понять, какое место книги сейчас читают. Играющего плеера это не касается -
 * декодер тут свой, отдельный.
 *
 * Шестнадцать килогерц не прихоть: локальные распознаватели речи работают
 * именно на них, и звук в исходных 44,1 кГц они просто не принимают.
 */
object AudioChunk {

    class Pcm(val bytes: ByteArray, val sampleRate: Int) {
        val durationMs: Long get() = bytes.size * 1000L / (2 * sampleRate)
    }

    /** Частота, на которой говорят все распознаватели. */
    const val TARGET_RATE = 16_000

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
            val mono = Mono((durationMs * 48).toInt().coerceAtLeast(16_000))
            val info = MediaCodec.BufferInfo()
            var reachedEnd = false     // нужный кусок скормлен целиком
            var inputDone = false      // декодеру сказано «конец»
            var outputDone = false
            val deadline = SystemClock.elapsedRealtime() + HARD_LIMIT_MS

            while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)
                        val size = if (buffer == null || reachedEnd) -1
                        else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            // Конец входа объявляется ЯВНО. Без этого декодер
                            // молчит, конца потока не присылает, и ожидание
                            // упирается в таймаут вместо доли секунды.
                            codec.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val ts = extractor.sampleTime
                            codec.queueInputBuffer(index, 0, size, ts, 0)
                            extractor.advance()
                            if (ts > toUs) reachedEnd = true
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    in 0..Int.MAX_VALUE -> {
                        if (info.size > 0 && info.presentationTimeUs >= fromUs) {
                            codec.getOutputBuffer(index)?.let { buf ->
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                mono.append(buf, channels, floatPcm)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        rate = of.runCatching { getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(rate)
                        channels = of.runCatching { getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                            .getOrDefault(channels)
                        floatPcm = of.runCatching { getInteger(MediaFormat.KEY_PCM_ENCODING) }
                            .getOrDefault(2) == 4   // AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }

            if (mono.count < rate / 4) return null      // меньше четверти секунды - не о чем говорить
            val resampled = resample(mono.data, mono.count, rate, TARGET_RATE)
            return Pcm(toBytes(resampled), TARGET_RATE)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Растущий буфер моно-отсчётов: любое число каналов сводится в один. */
    private class Mono(capacity: Int) {
        var data = ShortArray(capacity)
        var count = 0

        fun append(buf: ByteBuffer, channels: Int, floatPcm: Boolean) {
            buf.order(ByteOrder.nativeOrder())
            val ch = channels.coerceAtLeast(1)
            if (floatPcm) {
                val fb = buf.asFloatBuffer()
                val frames = fb.remaining() / ch
                ensure(frames)
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += fb.get(i * ch + c)
                    data[count++] = ((sum / ch) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
            } else {
                val sb = buf.asShortBuffer()
                val frames = sb.remaining() / ch
                ensure(frames)
                for (i in 0 until frames) {
                    var sum = 0
                    for (c in 0 until ch) sum += sb.get(i * ch + c).toInt()
                    data[count++] = (sum / ch).coerceIn(-32768, 32767).toShort()
                }
            }
        }

        private fun ensure(more: Int) {
            if (count + more <= data.size) return
            data = data.copyOf(maxOf(data.size * 2, count + more))
        }
    }

    /**
     * Понижение частоты усреднением по окну. Простое прореживание дало бы
     * зеркальные призвуки на согласных, а распознаватель их не любит.
     */
    private fun resample(src: ShortArray, count: Int, from: Int, to: Int): ShortArray {
        if (from == to || from <= 0) return src.copyOf(count)
        val outLen = (count.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val step = count.toDouble() / outLen
        for (i in 0 until outLen) {
            val a = (i * step).toInt().coerceIn(0, count - 1)
            val b = ((i + 1) * step).toInt().coerceIn(a + 1, count)
            var sum = 0L
            for (j in a until b) sum += src[j]
            out[i] = (sum / (b - a)).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private const val TIMEOUT_US = 5_000L
    /** Потолок на всю расшифровку куска: что бы ни делал декодер, дольше не ждём. */
    private const val HARD_LIMIT_MS = 6_000L
}
