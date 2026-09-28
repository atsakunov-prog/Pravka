package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Падение процесса — в файл, до того как система его убьёт.
 *
 * Зачем: владелец (28.09.2026) поставил сборку и «куда-то пропал диск». Если
 * служба доступности падает и поднимается по кругу, снаружи это выглядит ровно
 * так — кнопок нет, — а в «Обновлениях и службе» нет ни строки: журнал
 * событий пишет через `DiskWriter` асинхронно, и процесс умирает раньше, чем
 * строка доезжает до диска. Здесь — синхронная запись в `crash.log` рядом с
 * базой, а при следующем подъёме службы первая строка падения уходит в
 * журнал словами («прошлый раз процесс упал: …»), чтобы причину было видно
 * с телефона, без adb.
 *
 * Прежний обработчик (система: диалог «приложение остановлено», перезапуск
 * службы) вызывается после — мы только смотрим, не лечим.
 */
object CrashLog {

    private const val FILE = "crash.log"
    private const val MAX_BYTES = 256L * 1024
    /** Первые кадры стека — этого хватает, чтобы назвать место; целиком — в файле. */
    private const val FRAMES = 12

    fun install(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(context, thread.name, e) }
            prev?.uncaughtException(thread, e)
        }
    }

    private fun write(context: Context, thread: String, e: Throwable) {
        val f = File(DataRoot.dir(context), FILE)
        if (f.exists() && f.length() > MAX_BYTES) {
            f.renameTo(File(DataRoot.dir(context), "$FILE.1"))
        }
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        f.appendText("=== $stamp · поток $thread\n$sw\n", Charsets.UTF_8)
    }

    /**
     * Последнее падение одной строкой — для журнала при подъёме службы:
     * «NullPointerException: … в ru.zf.pravka.trigger.AutoPilot.start (AutoPilot.kt:412)».
     * null — падений не было или это уже показывали ([markShown]).
     */
    fun lastUnshown(context: Context): String? {
        val f = File(DataRoot.dir(context), FILE)
        if (!f.exists() || f.length() == 0L) return null
        val prefs = context.getSharedPreferences("pravka_internal", Context.MODE_PRIVATE)
        if (prefs.getLong("crash_shown_len", -1L) == f.length()) return null
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val block = text.split("=== ").lastOrNull { it.isNotBlank() } ?: return null
        val lines = block.lines()
        val head = lines.firstOrNull().orEmpty()
        val cause = lines.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
        // Первый кадр из нашего кода — точнее любого «at android.…».
        val frame = lines.drop(1).take(FRAMES * 4)
            .firstOrNull { it.trim().startsWith("at ru.zf.pravka") }
            ?.trim()?.removePrefix("at ")
            ?: lines.drop(2).firstOrNull { it.trim().startsWith("at ") }?.trim()?.removePrefix("at ").orEmpty()
        return "$head — $cause" + (if (frame.isNotBlank()) " в $frame" else "")
    }

    /** Показали в журнале — второй раз при каждом подъёме не повторять. */
    fun markShown(context: Context) {
        val f = File(DataRoot.dir(context), FILE)
        context.getSharedPreferences("pravka_internal", Context.MODE_PRIVATE)
            .edit().putLong("crash_shown_len", if (f.exists()) f.length() else 0L).apply()
    }
}
