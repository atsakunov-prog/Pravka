package ru.zf.pravka.desktop.data

import java.io.File

// Где Правка держит свои данные на воркстанции: %APPDATA%\Pravka (на других
// системах - ~/.pravka). Тот же набор файлов, что в filesDir на телефоне,
// поэтому словарь можно просто скопировать туда-сюда руками, а синхронизация
// работает с одинаковым форматом.
object Paths {

    val dir: File by lazy {
        val appData = System.getenv("APPDATA")
        val base = if (!appData.isNullOrBlank()) File(appData, "Pravka")
        else File(System.getProperty("user.home"), ".pravka")
        base.apply { mkdirs() }
    }

    /** Записи диктовок: WAV остаётся на диске, чтобы неудачную можно было переразобрать. */
    val recordings: File by lazy { File(dir, "recordings").apply { mkdirs() } }
}
