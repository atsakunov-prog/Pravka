package ru.zf.slushalka.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.zf.slushalka.data.Cloud

/**
 * Короткая запись с сервера мимо книги - «книга за 15 минут» из разбора.
 *
 * Свой маленький плеер, а не плеер книги: подменить ему очередь значило бы
 * тронуть место в книге, журнал подходов и шторку. Звук идёт тем же путём,
 * что главы (HTTP с входом, кэш записи, шлагбаум мобильной сети), а фокус
 * звука у обоих плееров общий: пустил книгу - замолчал этот, и наоборот.
 *
 * Живёт, пока открыт лист разбора: закрыл лист - замолчало. Экран гаснет -
 * играет дальше (лист с экрана при этом не уходит).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ClipPlayer(
    private val context: Context,
    private val streaming: Streaming,
    private val cloud: Cloud,
    private val scope: CoroutineScope,
) {

    data class State(
        /** Что играет: путь от корня облака; пусто - ничего. */
        val path: String = "",
        val playing: Boolean = false,
        val loading: Boolean = false,
        val posMs: Long = 0L,
        val durMs: Long = 0L,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var player: ExoPlayer? = null
    private var tick: Job? = null

    private fun ensure(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(streaming.dataSourceFactory)
                .setLoadErrorHandlingPolicy(streaming.errorPolicy),
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { p ->
            // Сеть и процессор держатся, пока играет: экран погас - запись идёт.
            p.setWakeMode(C.WAKE_MODE_NETWORK)
            p.addListener(listener)
            player = p
        }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            update()
            tick?.cancel()
            if (isPlaying) {
                tick = scope.launch {
                    while (isActive) {
                        update()
                        delay(500)
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) = update()

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(
                playing = false,
                loading = false,
                error = streaming.explain(error) ?: "Запись не играет: ${error.errorCodeName}",
            )
        }
    }

    private fun update() {
        val p = player ?: return
        _state.value = _state.value.copy(
            playing = p.isPlaying,
            loading = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady,
            posMs = p.currentPosition.coerceAtLeast(0L),
            durMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: _state.value.durMs,
        )
    }

    /** Пуск и пауза. [beforePlay] - перед пуском: книгу на паузу. */
    fun toggle(path: String, beforePlay: () -> Unit) {
        val p = ensure()
        if (_state.value.path != path) {
            p.setMediaItem(MediaItem.fromUri(cloud.urlOf(path)))
            p.prepare()
            _state.value = State(path = path, loading = true)
            beforePlay()
            p.play()
            return
        }
        // После ошибки (сеть, шлагбаум) плеер стоит, а «играть» в нём так и
        // осталось включённым: это повтор с того же места, а не пауза.
        if (p.playbackState == Player.STATE_IDLE) {
            _state.value = _state.value.copy(error = null, loading = true)
            p.prepare()
            beforePlay()
            p.play()
            return
        }
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
            p.pause()
            return
        }
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        beforePlay()
        p.play()
    }

    fun seekBy(ms: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceIn(0L, dur))
        update()
    }

    fun seekTo(share: Float) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        p.seekTo((dur * share.coerceIn(0f, 1f)).toLong())
        update()
    }

    /** Лист закрыли: замолчать и отпустить плеер - он держит декодер и сеть. */
    fun stop() {
        tick?.cancel()
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        _state.value = State()
    }
}
