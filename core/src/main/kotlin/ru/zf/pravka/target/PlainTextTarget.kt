package ru.zf.pravka.target

// A target with no field behind it: proofreads a string that arrived from a
// no-field dictation (folded phone, focused node died mid-take). The engine
// still does its full job - dictionary, history, stats - and the result is
// captured for the caller to put on the clipboard / into a notification.
class PlainTextTarget(private val text: String) : TextTarget {

    var result: String? = null
        private set

    override suspend fun read(): String = text

    override suspend fun write(text: String): Boolean {
        result = text
        return true
    }
}
