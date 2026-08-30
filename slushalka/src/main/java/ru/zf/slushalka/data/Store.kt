package ru.zf.slushalka.data

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

// Дисциплина записи - ровно та же, что в Правке, и по той же причине:
// позиция в книге относится к незаменимым данным. Потерять её - значит
// заново искать место в двадцатичасовой записи.
//
// - запись атомарна (tmp + fsync + переименование): ни убийство процесса, ни
//   внезапная перезагрузка не оставят обрубок на месте настоящего файла;
// - предыдущая версия остаётся рядом как `.prev` до следующей записи;
// - файл, который ЕСТЬ, но не разбирается, уезжает в `.corrupt`, а не
//   затирается пустым.
internal object Store {

    // Одна фоновая нить на все журналы: позиция пишется каждые двадцать
    // секунд прямо из тика плеера, и этому нечего делать на главном потоке.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "slushalka-disk").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /** Пишет в фоне; упавшая запись не должна ронять воспроизведение. */
    fun post(block: () -> Unit) {
        runCatching { executor.execute { runCatching { block() } } }
    }

    /** Дожидается, пока очередь опустеет - для onDestroy и ухода из приложения. */
    fun flush(timeoutMs: Long = 1500) {
        val done = java.util.concurrent.CountDownLatch(1)
        runCatching { executor.execute { done.countDown() } }
        runCatching { done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            // Без fsync переименование бывает долговечнее самих данных: файл
            // на месте, а внутри - хвост нулей после внезапной перезагрузки.
            runCatching { out.fd.sync() }
        }
        if (file.exists()) {
            runCatching { file.copyTo(File(file.parentFile, file.name + ".prev"), overwrite = true) }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun <T> readOrQuarantine(file: File, parse: (String) -> T): T? {
        if (!file.exists()) return readPrev(file, parse)
        val text = runCatching { file.readText() }.getOrNull() ?: return readPrev(file, parse)
        return runCatching { parse(text) }.getOrElse {
            val bad = File(file.parentFile, file.name + ".corrupt")
            bad.delete()
            file.renameTo(bad)
            readPrev(file, parse)
        }
    }

    private fun <T> readPrev(file: File, parse: (String) -> T): T? {
        val prev = File(file.parentFile, file.name + ".prev")
        if (!prev.exists()) return null
        val text = runCatching { prev.readText() }.getOrNull() ?: return null
        return runCatching { parse(text) }.getOrNull()
    }
}
