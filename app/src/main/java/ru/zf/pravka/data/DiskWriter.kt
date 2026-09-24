package ru.zf.pravka.data

import java.util.concurrent.Executors

// One background thread for every on-device journal (event log, live draft,
// transcription log).
//
// These used to write synchronously from the recognizer's callbacks, which run
// on the main thread. During a dictation that meant ~100 open/write/close
// cycles a minute sitting directly in the path that delivers audio results -
// and the two worst-placed ones landed exactly on the "you can speak now" cue
// and inside the gap between utterances, i.e. precisely where words get lost.
//
// A single thread (rather than a pool) keeps append order intact.
internal object DiskWriter {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pravka-disk").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /** Fire-and-forget; a failed write must never take the dictation down. */
    fun post(block: () -> Unit) {
        runCatching { executor.execute { runCatching { block() } } }
    }

    /**
     * Ждёт, пока очередь допишет всё, что в неё встало до этого вызова, но не
     * дольше [timeoutMs]. Перед перезапуском процесса (переезд базы): иначе
     * последняя запись ленты умерла бы в очереди вместе с процессом.
     */
    fun drain(timeoutMs: Long) {
        val done = java.util.concurrent.CountDownLatch(1)
        runCatching { executor.execute { done.countDown() } }
        runCatching { done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }
}
