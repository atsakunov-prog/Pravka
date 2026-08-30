package ru.zf.slushalka.library

// «Глава 2» должна идти перед «Глава 10», а не после неё - обычное
// лексикографическое сравнение перемешивает главы книги и рушит и порядок
// слушания, и привязку аудио к тексту.
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var i2 = i
                while (i2 < a.length && a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < b.length && b[j2].isDigit()) j2++
                // Ведущие нули не должны делать «007» больше «7».
                val na = a.substring(i, i2).trimStart('0')
                val nb = b.substring(j, j2).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                i = i2
                j = j2
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
