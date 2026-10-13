package ru.zf.pravka.provider

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.zf.pravka.core.Playback
import ru.zf.pravka.data.WavFile

/**
 * «Слушать» в экране «Правка» (Правка 5.0, доводка 14.10.2026): проиграть звук
 * тейка, пока он лежит на телефоне (`TakeAudio`, последние 300 МБ). Простой
 * плеер — пуск, пауза, перемотка по полосе; один тейк за раз.
 *
 * Почему `MediaPlayer`, а не своя `AudioTrack`: файл — обычный WAV 16 кГц моно
 * (`WavFile`), система читает его сама и сама держит поток; своя дорога ради
 * пуска и паузы — лишний код рядом с движком диктовки, который трогать нельзя.
 * Готовится `prepareAsync`: чтение файла не на главном потоке.
 *
 * Фокус звука — «на время» (`AUDIOFOCUS_GAIN_TRANSIENT`, назначение «медиа»,
 * речь): музыка встаёт на паузу, пока слушаешь, и сама играет дальше после.
 * Отняли фокус (звонок, новый тейк «П» — тот берёт фокус целиком, `TakeFocus`)
 * — пауза, сама не продолжает: продолжение посреди чужого звука хуже тишины.
 * Ушёл с экрана — [stop] (экран зовёт его сам), фокус отпускается сразу.
 *
 * Состояние — [state]; позицию экран спрашивает [tick] раз в 200 мс, пока
 * играет: у `MediaPlayer` нет подписки на позицию.
 */
class TakePlayer(context: Context) {

    /** Что играет: имя тейка (`audio` в журнале), идёт ли звук, где и сколько всего, причина сбоя. */
    data class State(
        val name: String? = null,
        val playing: Boolean = false,
        val posMs: Long = 0L,
        val durMs: Long = 0L,
        val error: String? = null,
    )

    private val app = context.applicationContext
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var mp: MediaPlayer? = null
    private var prepared = false
    private var focus: AudioFocusRequest? = null

    /** Пауза, нажатая, пока файл готовится, — не стартовать, когда он готов. */
    private var want = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Пуск [name] из [file]. Тот же тейк на паузе — дальше с того же места;
     * другой — прежний останавливается, новый с начала (или с [startMs]).
     */
    fun play(name: String, file: File, startMs: Long = 0L) {
        val cur = _state.value
        val p = mp
        want = true
        if (cur.name == name && p != null && prepared) {
            if (!holdFocus()) return
            runCatching { p.start() }.onFailure { fail(name, it) }.onSuccess { _state.value = cur.copy(playing = true, error = null) }
            return
        }
        release()
        val dur = WavFile.durationMs(file)
        _state.value = State(name = name, playing = false, posMs = startMs, durMs = dur)
        if (!holdFocus()) {
            _state.value = _state.value.copy(error = "звук занят — фокус не дали")
            return
        }
        val player = MediaPlayer()
        mp = player
        prepared = false
        runCatching {
            player.setAudioAttributes(attrs)
            player.setDataSource(file.path)
            player.setOnPreparedListener { pl ->
                if (mp !== pl) return@setOnPreparedListener
                prepared = true
                val total = pl.duration.toLong().takeIf { it > 0 } ?: dur
                val from = Playback.clampMs(startMs, total)
                if (from > 0) pl.seekTo(from.toInt())
                if (want) pl.start()
                _state.value = State(name = name, playing = want, posMs = from, durMs = total)
            }
            player.setOnCompletionListener { pl ->
                if (mp !== pl) return@setOnCompletionListener
                // Дослушал — к началу, фокус отпустить: музыка вернётся сразу.
                _state.value = _state.value.copy(playing = false, posMs = 0L)
                runCatching { pl.seekTo(0) }
                letGo()
            }
            player.setOnErrorListener { pl, what, extra ->
                if (mp === pl) fail(name, IllegalStateException("MediaPlayer $what/$extra"))
                true
            }
            player.prepareAsync()
        }.onFailure { fail(name, it) }
    }

    /** Пауза: звук стоит, фокус отпущен — музыка играет дальше. */
    fun pause() {
        want = false
        val p = mp ?: return
        if (prepared) runCatching { if (p.isPlaying) p.pause() }
        _state.value = _state.value.copy(playing = false, posMs = position(p))
        letGo()
    }

    /** Перемотка на долю [fraction] длины; на паузе — только запомнить место. */
    fun seek(fraction: Float) {
        val s = _state.value
        val to = Playback.seekMs(fraction, s.durMs)
        val p = mp
        if (p != null && prepared) runCatching { p.seekTo(to.toInt()) }
        _state.value = s.copy(posMs = to)
    }

    /** Позиция для полосы — экран зовёт, пока играет. */
    fun tick() {
        val p = mp ?: return
        if (!prepared) return
        val s = _state.value
        if (s.playing) _state.value = s.copy(posMs = position(p))
    }

    /** Совсем: ушёл с экрана, закрыл лист, другой тейк. */
    fun stop() {
        want = false
        release()
        _state.value = State()
    }

    private fun position(p: MediaPlayer): Long = runCatching { p.currentPosition.toLong() }.getOrDefault(_state.value.posMs)

    private fun release() {
        val p = mp
        mp = null
        prepared = false
        if (p != null) runCatching { p.reset(); p.release() }
        letGo()
    }

    private fun fail(name: String, e: Throwable) {
        release()
        _state.value = State(name = name, error = e.message?.take(120) ?: e.javaClass.simpleName)
    }

    private fun holdFocus(): Boolean {
        if (focus != null) return true
        val m = am ?: return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                // Звонок или новый тейк «П» — пауза; сами не продолжаем.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) pause()
            }
            .build()
        val granted = runCatching { m.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = req
        return true
    }

    private fun letGo() {
        val req = focus ?: return
        focus = null
        runCatching { am?.abandonAudioFocusRequest(req) }
    }
}
