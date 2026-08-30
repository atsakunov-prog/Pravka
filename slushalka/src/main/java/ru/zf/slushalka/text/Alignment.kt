package ru.zf.slushalka.text

import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.library.Book

/** Точка привязки: в этот миг записи читают вот это место текста. */
data class Anchor(val audioMs: Long, val charOffset: Int, val manual: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("ms", audioMs).put("c", charOffset).put("m", manual)

    companion object {
        fun fromJson(o: JSONObject) =
            Anchor(o.optLong("ms"), o.optInt("c"), o.optBoolean("m", true))
    }
}

/**
 * Перевод «сколько наслушал» в «где мы в тексте» и обратно.
 *
 * Между соседними точками привязки - линейная пропорция. Пустая книга (без
 * ручных отметок и без совпадения числа файлов с числом глав) сводится к одной
 * пропорции на всю книгу: грубо, но достаточно, чтобы попасть в те же
 * несколько страниц. Каждая ручная отметка «я тут» делает карту точнее -
 * дальше по книге ошибка уже не накапливается.
 */
class Alignment(val anchors: List<Anchor>) {

    val manualCount = anchors.count { it.manual }

    /**
     * Далеко ли до ближайшей выверенной точки. Если рядом - карта здесь уже
     * точная, и распознавать при переходе нечего.
     */
    fun distanceToAnchor(audioMs: Long): Long =
        anchors.filter { it.manual }.minOfOrNull { abs(it.audioMs - audioMs) } ?: Long.MAX_VALUE

    fun charAt(audioMs: Long): Int {
        if (anchors.isEmpty()) return 0
        if (audioMs <= anchors.first().audioMs) return anchors.first().charOffset
        if (audioMs >= anchors.last().audioMs) return anchors.last().charOffset
        for (i in 0 until anchors.lastIndex) {
            val a = anchors[i]
            val b = anchors[i + 1]
            if (audioMs in a.audioMs..b.audioMs) {
                val span = (b.audioMs - a.audioMs).coerceAtLeast(1)
                val k = (audioMs - a.audioMs).toDouble() / span
                return (a.charOffset + k * (b.charOffset - a.charOffset)).toInt()
            }
        }
        return anchors.last().charOffset
    }

    fun audioAt(charOffset: Int): Long {
        if (anchors.isEmpty()) return 0
        if (charOffset <= anchors.first().charOffset) return anchors.first().audioMs
        if (charOffset >= anchors.last().charOffset) return anchors.last().audioMs
        for (i in 0 until anchors.lastIndex) {
            val a = anchors[i]
            val b = anchors[i + 1]
            if (charOffset in a.charOffset..b.charOffset) {
                val span = (b.charOffset - a.charOffset).coerceAtLeast(1)
                val k = (charOffset - a.charOffset).toDouble() / span
                return (a.audioMs + k * (b.audioMs - a.audioMs)).toLong()
            }
        }
        return anchors.last().audioMs
    }

    companion object {

        /** Ручная отметка вытесняет засеянные точки в получасе вокруг себя. */
        private const val MANUAL_RADIUS_MS = 30 * 60_000L

        fun build(book: Book, text: BookText, manual: List<Anchor>): Alignment {
            val total = book.totalMs
            if (total <= 0 || text.length <= 0) return Alignment(listOf(Anchor(0, 0)))

            val seeds = ArrayList<Anchor>()
            seeds.add(Anchor(0, 0))
            // Один файл - одна глава: самая частая раскладка у аудиокниг, и
            // самая полезная. Тогда ошибка привязки живёт внутри главы и не
            // копится к концу книги.
            if (book.files.size > 1 && book.files.size == text.chapters.size) {
                for (i in 1 until book.files.size) {
                    seeds.add(Anchor(book.offsetOf(i), text.chapters[i].start))
                }
            }
            seeds.add(Anchor(total, text.length))

            val kept = seeds.filter { s ->
                manual.none { abs(it.audioMs - s.audioMs) < MANUAL_RADIUS_MS }
            }
            val all = (kept + manual.map { it.copy(manual = true) }).sortedBy { it.audioMs }

            // Карта обязана быть монотонной: время идёт вперёд, текст тоже.
            // Ручная отметка всегда права, засеянная точка ей уступает.
            val out = ArrayList<Anchor>()
            for (a in all) {
                var skip = false
                while (out.isNotEmpty()) {
                    val last = out.last()
                    if (last.audioMs < a.audioMs && last.charOffset <= a.charOffset) break
                    if (last.manual && !a.manual) {
                        skip = true
                        break
                    }
                    out.removeAt(out.lastIndex)
                }
                if (!skip) out.add(a)
            }
            return Alignment(if (out.size >= 2) out else listOf(Anchor(0, 0), Anchor(total, text.length)))
        }

        fun listToJson(anchors: List<Anchor>): JSONArray =
            JSONArray().apply { anchors.forEach { put(it.toJson()) } }

        fun listFromJson(arr: JSONArray?): List<Anchor> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { Anchor.fromJson(arr.getJSONObject(it)) }
        }
    }
}
