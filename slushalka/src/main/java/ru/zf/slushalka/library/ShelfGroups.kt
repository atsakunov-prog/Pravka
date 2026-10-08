package ru.zf.slushalka.library

import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.text.BookMeta

/**
 * Кусок полки: серия или автор с заголовком, сколько в ней и что пройдено;
 * [title] null - книги вне групп, подряд, без заголовка.
 */
class ShelfGroup(val key: String, val title: String?, val note: String, val books: List<Book>)

/**
 * Группы поверх порядка (08.10.2026, владелец: «порядок по дате добавления,
 * и уже в нём группировка по сериям»): книги уже стоят по порядку, группа
 * встаёт туда, где её первая книга, - у «добавленных» серия поднимается, когда
 * в неё пришла новая книга. Внутри серии - по номерам, у автора - его серии по
 * номерам, потом остальное тем же порядком.
 *
 * Серия с одной книгой на полке - не группа: заголовок над одной плиткой -
 * шум, серия и так написана под названием. Книги вне групп стоят как стояли,
 * кусками без заголовка; [skipLoose] (книга из «Продолжить») среди них не
 * повторяется. У автора группа - каждый, и с одной книгой тоже: это каталог.
 */
fun groupShelf(
    shown: List<Book>,
    by: String,
    series: (Book) -> BookMeta.Series?,
    surname: (Book) -> String,
    skipLoose: String? = null,
    note: (List<Book>) -> String = { "" },
): List<ShelfGroup> {
    val keyOf: (Book) -> String? = when (by) {
        Settings.GROUP_SERIES -> { b -> series(b)?.name?.let(BookMeta::key)?.takeIf { it.isNotEmpty() } }
        Settings.GROUP_AUTHOR -> { b -> surname(b).trim().lowercase() }
        else -> return listOf(ShelfGroup("loose", null, "", shown.filter { it.id != skipLoose }))
    }
    val members = shown.groupBy(keyOf)
    val out = ArrayList<ShelfGroup>()
    val placed = HashSet<String>()
    var loose = ArrayList<Book>()
    fun flush() {
        if (loose.isEmpty()) return
        out += ShelfGroup("loose:${out.size}", null, "", loose)
        loose = ArrayList()
    }
    for (b in shown) {
        val k = keyOf(b)
        val list = k?.let { members[it] }
        if (k == null || list == null || (by == Settings.GROUP_SERIES && list.size < 2)) {
            if (b.id != skipLoose) loose += b
            continue
        }
        if (!placed.add(k)) continue
        flush()
        // Сортировка устойчивая: при равных номерах книги остаются в порядке полки.
        out += if (by == Settings.GROUP_SERIES) {
            ShelfGroup(
                "s:$k", series(list.first())!!.name, note(list),
                list.sortedBy { BookMeta.order(series(it)?.number) },
            )
        } else {
            // Имя в заголовке - как оно чаще написано у его книг.
            val name = list.groupingBy { it.author.trim() }.eachCount().maxBy { it.value }.key
            ShelfGroup(
                "a:$k", name.ifBlank { "Автор не указан" }, note(list),
                list.sortedWith(
                    compareBy(
                        { series(it) == null },
                        { series(it)?.name?.let(BookMeta::key).orEmpty() },
                        { BookMeta.order(series(it)?.number) },
                    )
                ),
            )
        }
    }
    flush()
    return out
}

/** Строка для поиска по полке: строчные, «ё» как «е», всё, кроме букв и цифр, - пробел. */
fun searchKey(s: String): String =
    s.lowercase().replace('ё', 'е').map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        .replace(Regex(" +"), " ").trim()

/** Книга подходит под запрос: каждое его слово есть в названии, авторе, серии или имени папки. */
fun matchesShelfQuery(book: Book, series: String?, query: String): Boolean {
    val words = searchKey(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return false
    val hay = searchKey(listOf(book.title, book.author, series.orEmpty(), book.folderName).joinToString(" "))
    return words.all { it in hay }
}
