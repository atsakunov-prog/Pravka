package ru.zf.pravka.core

// Last 10 before/after pairs, in process memory only (spec section 8).
// ACTION_SET_TEXT overwrites the whole field, so undo is mandatory.
object UndoStack {

    const val CAPACITY = 10

    data class Entry(val before: String, val after: String)

    private val entries = ArrayDeque<Entry>(CAPACITY)

    @Synchronized
    fun push(before: String, after: String) {
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(Entry(before, after))
    }

    /** The most recent entry whose result matches the field's current text. */
    @Synchronized
    fun matchByCurrentText(current: String?): Entry? {
        val trimmed = current?.trim() ?: return null
        return entries.lastOrNull { it.after.trim() == trimmed }
    }

    @Synchronized
    fun remove(entry: Entry) {
        entries.remove(entry)
    }
}
