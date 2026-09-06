package ru.zf.slushalka.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import ru.zf.slushalka.ask.GuideState

/**
 * Справочники по книгам - файлом на книгу в `guides/`. Та же дисциплина
 * записи, что у позиций: справочник стоит денег, и терять его из-за убитого
 * процесса обидно.
 */
class GuideStore(context: Context) {

    private val dir = File(context.filesDir, "guides").apply { mkdirs() }

    fun load(bookId: String): GuideState? =
        Store.readOrQuarantine(file(bookId)) { GuideState.fromJson(JSONObject(it)) }

    fun save(bookId: String, state: GuideState) {
        val text = state.toJson().toString()
        Store.post { Store.writeAtomic(file(bookId), text) }
    }

    fun delete(bookId: String) {
        Store.post {
            val f = file(bookId)
            f.delete()
            File(dir, f.name + ".prev").delete()
        }
    }

    private fun file(bookId: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest(bookId.toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) }.take(16) + ".json")
    }
}
