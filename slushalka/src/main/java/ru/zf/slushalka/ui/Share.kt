package ru.zf.slushalka.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import ru.zf.slushalka.data.Docx
import ru.zf.slushalka.data.Note
import ru.zf.slushalka.text.BookText

/**
 * Отдать файл другому приложению: мессенджеру, почте, Диску. Файлы - во
 * временной папке `shared/`, путь к ней открыт FileProvider'ом.
 */
object Share {

    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun file(context: Context, name: String): File =
        File(File(context.cacheDir, "shared").apply { mkdirs() }, safe(name))

    fun send(context: Context, file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(context, "ru.zf.slushalka.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun safe(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(90)

    /**
     * Пометки книги в Word. Без Claude - как есть: главы заголовками, цитаты
     * курсивом с отступом, мысль под цитатой. С конспектом ([digest]) - его
     * текст: «# » - заголовок, «> » - цитата, остальное - абзацы.
     */
    fun notesDocx(text: BookText, title: String, notes: List<Note>, digest: String?): List<Docx.Para> = buildList {
        add(Docx.Para(title, Docx.Style.TITLE))
        add(
            Docx.Para(
                listOfNotNull(
                    text.author.takeIf { it.isNotBlank() },
                    if (digest != null) "конспект чтения" else "пометки на полях: ${notes.size}",
                    formatDate(System.currentTimeMillis()),
                ).joinToString(" · "),
                Docx.Style.SUBTITLE,
            )
        )
        if (digest != null) {
            digest.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { line ->
                when {
                    line.startsWith("# ") -> add(Docx.Para(line.removePrefix("# ").trim(), Docx.Style.HEADING))
                    line.startsWith("> ") -> add(Docx.Para(line.removePrefix("> ").trim(), Docx.Style.QUOTE))
                    else -> add(Docx.Para(line.trim()))
                }
            }
            return@buildList
        }
        var chapter = -1
        notes.sortedBy { it.start }.forEach { n ->
            val c = text.chapterIndexAt(n.start)
            if (c != chapter && text.chapters.isNotEmpty()) {
                chapter = c
                add(Docx.Para(text.chapters.getOrNull(c)?.title?.ifBlank { "Глава ${c + 1}" } ?: "Глава ${c + 1}", Docx.Style.HEADING))
            }
            add(Docx.Para("«${n.quote.trim()}»", Docx.Style.QUOTE))
            if (n.text.isNotBlank()) add(Docx.Para(n.text.trim()))
        }
    }
}
