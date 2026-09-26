package ru.zf.pravka.data

import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сырьё выписок: каждый загруженный файл — как есть, в `imports/` базы
 * (25.09.2026). Строки выписки в журнале хранятся уже разобранными; поправили
 * разбор — старые строки без файла заново не вывести, пришлось бы грузить
 * выписку снова. Владелец: «да, складывай в imports» — теперь шаг переразбора
 * истории (`core/HistoryFixes.kt`) сможет пройти по этим файлам сам.
 *
 * Файл кладётся ДО разбора: и тот, что не узнался, тоже — по нему учат новый
 * формат. Тот же файл второй раз (недельный пакет внахлёст, повторная
 * загрузка) копию не плодит: имя несёт отпечаток содержимого. Едет вместе с
 * базой — в переезде и в суточной копии.
 */
internal class ImportArchive(private val root: () -> File) {

    private val dir: File get() = File(root(), DIR)

    /** Сохранить файл; уже лежит (то же содержимое) — вернуть прежний. */
    fun keep(bytes: ByteArray, now: Long = System.currentTimeMillis()): File {
        val print = fingerprint(bytes)
        dir.listFiles()?.firstOrNull { it.name.contains("-$print.") }?.let { return it }
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date(now))
        val file = File(dir, "$stamp-$print.${extension(bytes)}")
        val tmp = File(dir, file.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return file
    }

    /** Все сохранённые выписки, старые сверху — в порядке загрузки. */
    fun files(): List<File> =
        dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.sortedBy { it.name }.orEmpty()

    companion object {
        const val DIR = "imports"

        /** Первые 12 знаков SHA-256: для имён файлов с запасом, совпадений в семейных выписках не будет. */
        fun fingerprint(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }

        /** .xlsx — это zip (МКБ); остальное — текст (CSV Тинькова, Альфы, Т-Бизнеса, чат). */
        fun extension(bytes: ByteArray): String =
            if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) "xlsx" else "csv"
    }
}
