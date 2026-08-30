package ru.zf.slushalka.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.zf.slushalka.data.BookState
import ru.zf.slushalka.data.PositionStore
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.documentUri

data class PlayState(
    val bookId: String? = null,
    val title: String = "",
    val author: String = "",
    val fileIndex: Int = 0,
    val fileName: String = "",
    val posInFileMs: Long = 0,
    val fileDurationMs: Long = 0,
    val absMs: Long = 0,
    val totalMs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float = 1f,
    val sleepLeftMs: Long = 0,
    val error: String? = null,
) {
    val progress: Float get() = if (totalMs > 0) (absMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
}

/**
 * Единственный плеер приложения. Живёт в Application, а не в экране: экран
 * можно закрыть, книга от этого замолчать не должна. Служба (PlaybackService)
 * берёт этот же экземпляр - отсюда и обложка на локскрине, и кнопки на
 * наушниках.
 *
 * Здесь же тик сохранения позиции. Раз в двадцать секунд, а не «когда-нибудь»:
 * приложение, выгруженное системой из памяти, теряет максимум двадцать секунд
 * книги, а не место в двадцатичасовой записи.
 */
class PlayerHolder(
    private val context: Context,
    private val settings: Settings,
    private val positions: PositionStore,
    /** Дёргается, когда позицию пора отправить в папку библиотеки. */
    private val onSyncDue: (bookId: String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state

    private var book: Book? = null
    private var treeUri: Uri? = null
    private var lastPauseAt = 0L
    private var lastSaveAt = 0L
    private var lastSyncAt = 0L
    private var listenedAcc = 0L
    private var lastTickAt = 0L
    private var sleepDeadline = 0L
    private var sleepFading = false

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
            .also { p ->
                // Экран гаснет, телефон в кармане - книга продолжается.
                p.setWakeMode(C.WAKE_MODE_LOCAL)
                p.addListener(listener)
            }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                lastTickAt = System.currentTimeMillis()
                scheduleTick()
            } else {
                lastPauseAt = System.currentTimeMillis()
                saveNow(markHistory = true)
                book?.id?.let(onSyncDue)
            }
            push()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Граница файла - самое обидное место для потери позиции.
            saveNow()
            push()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finishBook()
            push()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = error.message ?: "Файл не читается")
        }
    }

    // ---------------------------------------------------------------- книга

    fun isOpen(bookId: String) = book?.id == bookId && player.mediaItemCount > 0

    /**
     * Ставит книгу целиком: перемотка между файлами становится обычной
     * перемоткой, а не «открыть следующий файл».
     */
    fun open(tree: Uri, b: Book, startAbsMs: Long? = null, autoPlay: Boolean = false) {
        treeUri = tree
        book = b
        val saved = positions.get(b.id)
        val coverUri = coverUriFor(tree, b)
        val items = b.files.mapIndexed { i, f ->
            MediaItem.Builder()
                .setUri(documentUri(tree, f.docId))
                .setMediaId(f.docId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(b.title.ifBlank { f.name })
                        .setArtist(b.author.ifBlank { "Аудиокнига" })
                        .setAlbumTitle(b.title)
                        .setTrackNumber(i + 1)
                        .setDisplayTitle(f.name.substringBeforeLast('.'))
                        .setArtworkUri(coverUri)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
        player.setMediaItems(items)
        val target = startAbsMs ?: rewound(saved)
        val (index, inFile) = b.locate(target)
        player.seekTo(index, inFile)
        player.setPlaybackSpeed(if (saved.speed > 0f) saved.speed else settings.now().speed)
        player.skipSilenceEnabled = settings.now().skipSilence
        player.prepare()
        player.playWhenReady = autoPlay
        listenedAcc = saved.listenedMs
        lastPauseAt = 0L
        push()
    }

    private fun coverUriFor(tree: Uri, b: Book): Uri? {
        b.coverDocId?.let { return documentUri(tree, it) }
        val extracted = java.io.File(java.io.File(context.filesDir, "covers"), coverKey(b.id))
        return if (extracted.exists()) Uri.fromFile(extracted) else null
    }

    private fun coverKey(bookId: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1").digest(bookId.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(16) + ".img"
    }

    /**
     * Умный откат при возвращении. Через пять минут паузы хватает трёх секунд,
     * через неделю нужно полминуты - иначе включаешься в середину фразы и
     * половину главы вспоминаешь, о чём вообще речь.
     */
    private fun rewound(saved: BookState): Long {
        if (!settings.now().autoRewind || saved.absMs <= 0) return saved.absMs
        val gap = System.currentTimeMillis() - saved.updatedAt
        val back = when {
            gap < 15_000 -> 0L
            gap < 60_000 -> 3_000L
            gap < 30 * 60_000L -> 10_000L
            gap < 6 * 3600_000L -> 20_000L
            else -> 30_000L
        }
        return (saved.absMs - back).coerceAtLeast(0L)
    }

    // -------------------------------------------------------------- команды

    fun playPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            applyRewindAfterPause()
            player.play()
        }
    }

    private fun applyRewindAfterPause() {
        if (!settings.now().autoRewind || lastPauseAt == 0L) return
        val gap = System.currentTimeMillis() - lastPauseAt
        val back = when {
            gap < 15_000 -> 0L
            gap < 60_000 -> 3_000L
            gap < 30 * 60_000L -> 10_000L
            gap < 6 * 3600_000L -> 20_000L
            else -> 30_000L
        }
        if (back > 0) seekTo((absNow() - back).coerceAtLeast(0L))
        lastPauseAt = 0L
    }

    fun skip(seconds: Int) {
        seekTo(absNow() + seconds * 1000L)
    }

    fun seekTo(absMs: Long) {
        val b = book ?: return
        val target = absMs.coerceIn(0L, (b.totalMs - 1000).coerceAtLeast(0L))
        val (index, inFile) = b.locate(target)
        player.seekTo(index, inFile)
        saveNow(markHistory = true)
        push()
    }

    fun jumpToFile(index: Int) {
        val b = book ?: return
        seekTo(b.offsetOf(index.coerceIn(0, b.files.lastIndex)))
    }

    fun setSpeed(v: Float) {
        val speed = v.coerceIn(0.5f, 3.0f)
        player.setPlaybackSpeed(speed)
        book?.let { b -> positions.save(positions.get(b.id).copy(speed = speed)) }
        push()
    }

    fun setSkipSilence(on: Boolean) {
        player.skipSilenceEnabled = on
    }

    fun pauseForAsking(): Boolean {
        if (!player.isPlaying) return false
        player.pause()
        return true
    }

    fun resumeAfterAsking() {
        lastPauseAt = 0L    // пауза ради вопроса - не повод откатываться назад
        player.play()
    }

    // ----------------------------------------------------------- сон и тики

    /** minutes = 0 выключает; [untilChapterEnd] считает остаток текущего файла. */
    fun setSleep(minutes: Int, untilChapterEnd: Boolean = false) {
        sleepFading = false
        player.volume = 1f
        sleepDeadline = when {
            untilChapterEnd -> {
                val left = (player.duration - player.currentPosition).coerceAtLeast(0L)
                System.currentTimeMillis() + (left / player.playbackParameters.speed).toLong()
            }
            minutes > 0 -> System.currentTimeMillis() + minutes * 60_000L
            else -> 0L
        }
        push()
        if (sleepDeadline > 0) scheduleTick()
    }

    fun addSleep(minutes: Int) {
        if (sleepDeadline <= 0) return setSleep(minutes)
        sleepFading = false
        player.volume = 1f
        sleepDeadline += minutes * 60_000L
        push()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (player.isPlaying) {
                listenedAcc += (now - lastTickAt).coerceIn(0, 2000)
                lastTickAt = now
                if (now - lastSaveAt > SAVE_EVERY_MS) saveNow()
                if (now - lastSyncAt > SYNC_EVERY_MS) {
                    lastSyncAt = now
                    book?.id?.let(onSyncDue)
                }
            }
            checkSleep(now)
            push()
            if (player.isPlaying || sleepDeadline > 0) main.postDelayed(this, 500)
        }
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun checkSleep(now: Long) {
        if (sleepDeadline <= 0) return
        val left = sleepDeadline - now
        when {
            left <= 0 -> {
                sleepDeadline = 0
                sleepFading = false
                player.pause()
                player.volume = 1f
            }
            // Последние секунды звук уводится в тишину: резкий обрыв будит.
            left < FADE_MS -> {
                sleepFading = true
                player.volume = (left.toFloat() / FADE_MS).coerceIn(0.05f, 1f)
            }
        }
    }

    // ------------------------------------------------------------- позиция

    private fun absNow(): Long {
        val b = book ?: return 0
        return b.offsetOf(player.currentMediaItemIndex) + player.currentPosition.coerceAtLeast(0L)
    }

    fun saveNow(markHistory: Boolean = false) {
        val b = book ?: return
        if (player.mediaItemCount == 0) return
        lastSaveAt = System.currentTimeMillis()
        val prev = positions.get(b.id)
        positions.save(
            prev.copy(
                fileIndex = player.currentMediaItemIndex,
                posMs = player.currentPosition.coerceAtLeast(0L),
                absMs = absNow(),
                listenedMs = listenedAcc,
            ),
            markHistory = markHistory,
        )
    }

    private fun finishBook() {
        val b = book ?: return
        positions.save(positions.get(b.id).copy(absMs = b.totalMs, finished = true))
        book?.id?.let(onSyncDue)
    }

    private fun push() {
        val b = book
        val abs = absNow()
        _state.value = PlayState(
            bookId = b?.id,
            title = b?.title.orEmpty(),
            author = b?.author.orEmpty(),
            fileIndex = player.currentMediaItemIndex,
            fileName = b?.files?.getOrNull(player.currentMediaItemIndex)?.name.orEmpty(),
            posInFileMs = player.currentPosition.coerceAtLeast(0L),
            fileDurationMs = player.duration.takeIf { it > 0 } ?: 0L,
            absMs = abs,
            totalMs = b?.totalMs ?: 0L,
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            speed = player.playbackParameters.speed,
            sleepLeftMs = if (sleepDeadline > 0) (sleepDeadline - System.currentTimeMillis()) else 0L,
            error = _state.value.error.takeIf { b?.id == _state.value.bookId },
        )
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun release() {
        saveNow()
        main.removeCallbacks(tick)
        player.release()
    }

    companion object {
        /** Тик записи на диск. Владелец просил «раз в минуту-две» - берём чаще. */
        private const val SAVE_EVERY_MS = 20_000L
        /** В папку библиотеки пишем реже: это уже настоящий файловый обмен. */
        private const val SYNC_EVERY_MS = 120_000L
        private const val FADE_MS = 20_000L
    }
}
