package ru.zf.pravka.core

// Баги и предложения с кнопок (07.10.2026, владелец: «на долгом нажатии на
// кнопках… записать баг или предложение. И тогда у нас будет копиться и раз в
// день можно это открывать и обновлять»). Обратная дорога — без сервера:
// сессия, которая починила запись №N, кладёт строку «N<TAB>что сделано» в
// `assets/feedback_done.txt` того же коммита. Сборка приезжает на телефон, и
// стор при чтении сам отмечает №N сделанным, с этой строкой и номером
// сборки. Здесь — только разбор файла, под JVM-тестом.

object FeedbackDone {

    /** Что сделано по записи: номер и строка для владельца. */
    data class Line(val num: Int, val note: String, val skip: Boolean = false)

    /**
     * Строки файла: «7<TAB>Подпись плитки ужимается», пустые и «#…» —
     * пропускаются. Пробел вместо табуляции тоже годится. Начало «-» у
     * примечания («7 - не будем: …») — запись отложена, а не сделана.
     * Повтор номера — берётся последняя строка.
     *
     * Номера у каждого телефона свои (08.10.2026: с Марианной их два), и
     * Сашино «7 сделано» не должно закрыть её №7 о другом. Голый номер — запись
     * владельца; «marianna:7» — запись профиля marianna. [profile] — ключ
     * профиля этой установки, [owner] — владелец ли это.
     */
    fun parse(text: String, profile: String? = null, owner: Boolean = true): Map<Int, Line> {
        val out = LinkedHashMap<Int, Line>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val m = LINE.matchEntire(line) ?: continue
            val who = m.groupValues[1]
            if (if (who.isEmpty()) !owner else who != profile) continue
            val num = m.groupValues[2].toIntOrNull() ?: continue
            var note = m.groupValues[3].trim()
            val skip = note.startsWith("-")
            if (skip) note = note.removePrefix("-").trim()
            out[num] = Line(num, note, skip)
        }
        return out
    }

    private val LINE = Regex("""^(?:([a-z][a-z0-9-]*):)?№?(\d+)[\t ]*(.*)$""")
}
