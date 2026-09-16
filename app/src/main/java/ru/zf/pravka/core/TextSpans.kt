package ru.zf.pravka.core

// Где в поле лёг только что вставленный кусок. Нужно «Чистке буфера»: служба
// доступности буфер сама не читает (с Android 10 его отдают только окну в
// фокусе), вставляет чужое приложение по ACTION_PASTE, а мы узнаём границы
// вставки по разнице текста до и после — чтобы выделить ровно её и не
// переписать чистке всё поле.
object TextSpans {

    /**
     * Границы вставки в [after] относительно [before] (полуинтервал: `first`
     * включительно, `last + 1` — исключительно). Сначала верим курсору: вставка
     * ложится на место выделения [selStart, selEnd) или в точку курсора
     * (selStart == selEnd); если текст вокруг сошёлся — это она. Иначе ищем
     * общий префикс и суффикс и принимаем только чистую вставку. `null` — когда
     * ничего не добавилось или поле поменялось не так, как ожидали вставка.
     */
    fun insertedSpan(before: String, after: String, selStart: Int, selEnd: Int): IntRange? {
        if (selStart in 0..selEnd && selEnd <= before.length) {
            val head = before.substring(0, selStart)
            val tail = before.substring(selEnd)
            if (after.length >= head.length + tail.length && after.startsWith(head) && after.endsWith(tail)) {
                val end = after.length - tail.length
                if (end > selStart) return selStart until end
                // Пусто на месте курсора: вставка не случилась (или вставили
                // пустоту) — курсор не помог, пробуем по разнице.
            }
        }
        if (after.length <= before.length) return null
        var prefix = 0
        while (prefix < before.length && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (
            suffix < before.length - prefix &&
            before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
        ) suffix++
        if (prefix + suffix != before.length) return null
        return prefix until after.length - suffix
    }
}
