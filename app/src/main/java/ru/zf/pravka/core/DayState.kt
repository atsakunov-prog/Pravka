package ru.zf.pravka.core

// Тихая строка состояния под днём недели «Сегодня» (баг №13, 07.10.2026,
// владелец: «рядом со средой написать текущее моё состояние… сколько я спал,
// сколько времени на восстановление, какая тренированность… не будет ли
// нагружать? Подумай»). Решение: одна строка вторым тоном, без плашек и
// цифр-заголовков — светофор Спорта точками и его же три числа (сон, HRV,
// форма) в том порядке, как владелец их назвал. Источник — `TrafficLight`:
// одно правило на Спорт и «Сегодня», числа не расходятся. Нет свежего
// (сегодняшнего) — строки нет совсем; тумблер — в настройках «Сегодня».

data class DayState(
    /** Сколько точек светофора горит: 3 — по плану, 2 — осторожно, 1 — восстановление. */
    val lit: Int,
    val parts: List<Part>,
) {
    /** «сон 7,2 ч» — и хуже ли нормы (красится тёплым). */
    data class Part(val text: String, val worse: Boolean)

    companion object {
        private val ORDER = listOf("Сон", "HRV", "Форма")

        fun of(v: TrafficLight.Verdict): DayState? {
            val parts = v.numbers
                .filter { it.label in ORDER }
                .sortedBy { ORDER.indexOf(it.label) }
                .map { n ->
                    val word = if (n.label == "HRV") "HRV" else n.label.lowercase()
                    Part("$word ${n.value}", n.tone < 0)
                }
            if (parts.isEmpty()) return null
            val lit = when {
                v.tone >= 1 -> 3
                v.tone == 0 -> 2
                else -> 1
            }
            return DayState(lit, parts)
        }
    }
}
