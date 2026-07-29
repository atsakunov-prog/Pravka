package ru.zf.pravka.provider

import java.io.ByteArrayOutputStream

// Tiny hand-rolled protobuf codec - just enough to talk to Yandex SpeechKit v3
// over gRPC without pulling in protoc/grpc codegen. We only ever build a
// handful of request messages and read a couple of response fields, so a full
// generated stub would be far more weight than it's worth.
internal object Proto {

    fun message(build: (ByteArrayOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also(build).toByteArray()

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) out.write(b or 0x80) else { out.write(b); break }
        }
    }

    private fun tag(out: ByteArrayOutputStream, field: Int, wire: Int) =
        varint(out, ((field shl 3) or wire).toLong())

    fun varintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        tag(out, field, 0); varint(out, value)
    }

    fun lenField(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
        tag(out, field, 2); varint(out, bytes.size.toLong()); out.write(bytes)
    }

    fun stringField(out: ByteArrayOutputStream, field: Int, s: String) =
        lenField(out, field, s.toByteArray(Charsets.UTF_8))

    fun messageField(out: ByteArrayOutputStream, field: Int, msg: ByteArray) =
        lenField(out, field, msg)

    // Streaming field reader over a message buffer.
    class Reader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
        fun hasMore(): Boolean = pos < end

        /** Returns (fieldNumber, wireType) or null at end. */
        fun readTag(): Pair<Int, Int>? {
            if (pos >= end) return null
            val t = readVarint().toInt()
            return (t ushr 3) to (t and 0x7)
        }

        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b < 0x80) break
                shift += 7
            }
            return result
        }

        fun readLenBytes(): ByteArray {
            val len = readVarint().toInt()
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun readString(): String = String(readLenBytes(), Charsets.UTF_8)

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> pos += readVarint().toInt()
                5 -> pos += 4
            }
        }
    }
}
