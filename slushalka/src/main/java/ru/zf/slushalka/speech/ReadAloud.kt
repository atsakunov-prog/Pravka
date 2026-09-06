package ru.zf.slushalka.speech

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.BookText

/**
 * Озвучка книги, у которой нет записи: текст читает системный синтез речи.
 *
 * Не аудиокнига и не претендует: голос - тот, что стоит на телефоне (Google
 * или Samsung), лучший из установленных выбирается в настройках. Зато
 * работает сразу, без сети и без копейки - для книги из каталога, которую
 * хочется не читать глазами, а слушать перед сном.
 *
 * Как устроено. Текст режется на куски - абзац или, если абзац длиннее, чем
 * движок принимает, предложения абзаца. В очереди движка всегда лежит
 * читаемый кусок и следующий за ним: иначе между абзацами была бы пауза на
 * подготовку. Движок сообщает, какое слово произносит, - по нему подсвечивается
 * фраза в читалке, и с него же продолжается чтение после паузы: своей паузы у
 * синтеза нет, только остановка.
 *
 * Место чтения пишется тем же порядком, что при листании: слушал перед сном
 * до середины главы - утром читалка откроется там.
 */
class ReadAloud(private val app: SlushalkaApp) {

    data class State(
        val bookId: String? = null,
        val title: String = "",
        /** Озвучка включена: говорит или стоит на паузе. */
        val active: Boolean = false,
        val speaking: Boolean = false,
        /** Начало читаемой фразы - знак в тексте книги. */
        val charOffset: Int = 0,
        /** Фраза, которую произносят прямо сейчас, - для подсветки. */
        val range: IntRange? = null,
        val chapter: String = "",
        val rate: Float = 1f,
        /** Движок поднялся и говорит по-русски. */
        val ready: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State(rate = app.settings.now().ttsRate))
    val state: StateFlow<State> = _state

    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Кусок текста для движка: откуда в книге и к какому абзацу относится. */
    private class Piece(val start: Int, val text: String, val block: Int) {
        val end get() = start + text.length
    }

    private var text: BookText? = null
    private var pieces: List<Piece> = emptyList()
    private var current = -1
    /**
     * Хвост куска, с которого начали или продолжили: список остаётся целым, а
     * «абзац назад» возвращает к настоящему началу абзаца, не к месту паузы.
     */
    private var cutIndex = -1
    private var cutPiece: Piece? = null
    /** До какого куска очередь движка уже заполнена. */
    private var queued = -1
    /**
     * Растёт на каждый пуск и остановку. Обратные вызовы движка приходят с
     * опозданием и из другого потока; вызов с прежним номером - от чтения,
     * которого уже нет, и его надо отбросить, а не принять за текущее.
     */
    private var generation = 0
    private var lastSavedBlock = -1
    private var errorsInRow = 0
    private var pausedByFocus = false
    private var pendingStart: (() -> Unit)? = null

    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        // Движок поднимается заранее, чтобы список голосов был готов к
        // настройкам, а «Озвучить» начинало говорить без задержки на запуск.
        engine()
    }

    private fun engine(): TextToSpeech {
        tts?.let { return it }
        // Обратный вызов инициализации может прийти раньше, чем присвоится
        // ссылка, поэтому внутри него к tts не обращаемся - только по главному
        // потоку, следующим сообщением.
        val t = TextToSpeech(app) { status -> main.post { onInit(status == TextToSpeech.SUCCESS) } }
        tts = t
        t.setOnUtteranceProgressListener(listener)
        return t
    }

    private fun onInit(ok: Boolean) {
        val t = tts ?: return
        ready = ok
        if (!ok) {
            _state.value = _state.value.copy(ready = false, error = "На телефоне нет синтеза речи")
            pendingStart = null
            return
        }
        runCatching {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            t.language = java.util.Locale.forLanguageTag("ru-RU")
        }
        applyVoice(app.settings.now().ttsVoice)
        runCatching { t.setSpeechRate(app.settings.now().ttsRate) }
        _state.value = _state.value.copy(ready = true, error = null)
        pendingStart?.invoke()
        pendingStart = null
    }

    // ---------------------------------------------------------------- голос

    /** Русские голоса движка, лучшие первыми. Пусто - движок ещё не поднялся или русского нет. */
    fun voices(): List<Voice> {
        val t = tts ?: return emptyList()
        if (!ready) return emptyList()
        return runCatching { t.voices }.getOrNull().orEmpty()
            .filter { v ->
                v.locale.language == "ru" &&
                    !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
    }

    fun currentVoice(): String = runCatching { tts?.voice?.name }.getOrNull().orEmpty()

    fun setVoice(name: String) {
        applyVoice(name)
        restartCurrent()
    }

    private fun applyVoice(name: String) {
        val t = tts ?: return
        if (name.isBlank()) return
        val v = runCatching { t.voices }.getOrNull()?.firstOrNull { it.name == name } ?: return
        runCatching { t.voice = v }
    }

    fun setRate(rate: Float) {
        val r = rate.coerceIn(0.5f, 2.5f)
        runCatching { tts?.setSpeechRate(r) }
        _state.value = _state.value.copy(rate = r)
        restartCurrent()
    }

    /** Смена голоса или темпа слышна сразу, а не со следующего абзаца. */
    private fun restartCurrent() {
        val s = _state.value
        if (!s.active || !s.speaking) return
        speakFrom(current, s.charOffset)
    }

    /** Короткая проба голоса - для настроек. */
    fun sample() {
        val t = tts ?: return
        if (!ready) return
        if (_state.value.speaking) return
        runCatching {
            t.speak(
                "Эраст Петрович вышел на Тверскую и вдохнул майский воздух.",
                TextToSpeech.QUEUE_FLUSH, Bundle(), "sample",
            )
        }
    }

    /** Системный экран синтеза речи: там ставят движки и докачивают голоса получше. */
    fun openSystemSettings() {
        runCatching {
            app.startActivity(
                Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // --------------------------------------------------------------- команды

    /** Начать с [fromChar]: с фразы, в которой стоит это место, а не с начала абзаца. */
    fun start(book: Book, bookText: BookText, fromChar: Int) {
        // Аудиокнига в плеере и синтез разом - каша; плеер ставится на паузу.
        app.player.pauseForAsking()
        text = bookText
        pieces = buildPieces(bookText)
        lastSavedBlock = -1
        errorsInRow = 0
        _state.value = _state.value.copy(
            bookId = book.id, title = book.title, active = true, error = null,
        )
        if (pieces.isEmpty()) {
            _state.value = _state.value.copy(active = false, error = "В книге нечего читать вслух")
            return
        }
        val index = pieces.indexOfLast { it.start <= fromChar }.coerceAtLeast(0)
        if (!ready) {
            // Движок ещё поднимается: договорим, как только он ответит.
            pendingStart = { speakFrom(index, fromChar) }
            return
        }
        speakFrom(index, fromChar)
    }

    fun pause() = halt(keepFocus = false)

    /**
     * [keepFocus] - фокус отняли на время (звонок, навигатор): отпускать его
     * нельзя, иначе система не сообщит, что можно продолжать.
     */
    private fun halt(keepFocus: Boolean) {
        val s = _state.value
        if (!s.active || !s.speaking) return
        generation++
        runCatching { tts?.stop() }
        if (!keepFocus) abandonFocus()
        _state.value = s.copy(speaking = false)
    }

    fun resume() {
        val s = _state.value
        if (!s.active || s.speaking) return
        if (pieces.isEmpty()) return
        val index = pieces.indexOfLast { it.start <= s.charOffset }.coerceAtLeast(0)
        speakFrom(index, s.charOffset)
    }

    fun playPause() {
        if (_state.value.speaking) pause() else resume()
    }

    /** На абзац назад или вперёд. */
    fun skip(delta: Int) {
        val s = _state.value
        if (!s.active || pieces.isEmpty()) return
        val here = pieces.getOrNull(current.coerceAtLeast(0)) ?: return
        val target = if (delta > 0) {
            pieces.indexOfFirst { it.block > here.block }
        } else {
            // «Назад» с середины абзаца - к его началу, ещё раз - к предыдущему
            // абзацу, и к его началу тоже, даже если он нарезан на несколько кусков.
            val startOfHere = pieces.indexOfFirst { it.block == here.block }
            if (s.charOffset > here.start + 80 || current > startOfHere) startOfHere
            else pieces.getOrNull(startOfHere - 1)?.let { prev -> pieces.indexOfFirst { it.block == prev.block } } ?: -1
        }
        if (target < 0) return
        speakFrom(target)
    }

    fun stop() {
        generation++
        runCatching { tts?.stop() }
        abandonFocus()
        pausedByFocus = false
        pendingStart = null
        _state.value = State(rate = _state.value.rate, ready = _state.value.ready)
    }

    // ------------------------------------------------------------- механика

    private fun speakFrom(index: Int, fromChar: Int? = null) {
        val t = text ?: return
        val engine = tts ?: return
        if (index >= pieces.size) return finish()
        generation++
        val gen = generation
        runCatching { engine.stop() }

        // С середины абзаца - хвостом от нужной фразы: очередь движка получает
        // ровно его, а список кусков остаётся целым.
        cutIndex = -1
        cutPiece = null
        var piece = pieces[index]
        if (fromChar != null && fromChar > piece.start + 40 && fromChar < piece.end) {
            val cut = t.sentenceAt(fromChar).first.coerceIn(piece.start, piece.end)
            if (cut > piece.start) {
                piece = Piece(cut, piece.text.substring(cut - piece.start), piece.block)
                cutIndex = index
                cutPiece = piece
            }
        }
        current = index
        queued = index - 1
        requestFocus()
        ContextCompat.startForegroundService(app, Intent(app, ReadAloudService::class.java))
        _state.value = _state.value.copy(
            active = true, speaking = true, error = null,
            charOffset = piece.start,
            range = piece.start..(piece.start + piece.text.length),
            chapter = t.chapterAt(piece.start)?.title.orEmpty(),
        )
        enqueueUpTo(index + 1, gen)
    }

    /** Держим в очереди читаемый кусок и следующий: без пауз между абзацами. */
    private fun enqueueUpTo(last: Int, gen: Int) {
        val engine = tts ?: return
        while (queued < last && queued + 1 < pieces.size) {
            queued++
            val p = pieceAt(queued) ?: return
            val rc = runCatching {
                engine.speak(p.text, TextToSpeech.QUEUE_ADD, Bundle(), "$gen:$queued")
            }.getOrDefault(TextToSpeech.ERROR)
            if (rc != TextToSpeech.SUCCESS) {
                queued--
                return
            }
        }
    }

    private fun pieceAt(index: Int): Piece? =
        if (index == cutIndex) cutPiece else pieces.getOrNull(index)

    private fun finish() {
        val t = text
        val s = _state.value
        if (t != null && s.bookId != null) app.state.saveReadChar((t.length - 1).coerceAtLeast(0))
        stop()
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (gen, index) = parse(utteranceId) ?: return
            main.post { started(gen, index) }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val (gen, index) = parse(utteranceId) ?: return
            main.post { progressed(gen, index, start) }
        }

        override fun onDone(utteranceId: String?) {
            val (gen, index) = parse(utteranceId) ?: return
            main.post { done(gen, index) }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            val (gen, index) = parse(utteranceId) ?: return
            main.post { failed(gen, index) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val (gen, index) = parse(utteranceId) ?: return
            main.post { failed(gen, index) }
        }

        private fun parse(id: String?): Pair<Int, Int>? {
            val parts = id?.split(':') ?: return null
            if (parts.size != 2) return null
            val gen = parts[0].toIntOrNull() ?: return null
            val index = parts[1].toIntOrNull() ?: return null
            return gen to index
        }
    }

    private fun started(gen: Int, index: Int) {
        if (gen != generation) return
        val t = text ?: return
        val piece = pieceAt(index) ?: return
        current = index
        errorsInRow = 0
        _state.value = _state.value.copy(
            speaking = true,
            charOffset = piece.start,
            range = piece.start..piece.end,
            chapter = t.chapterAt(piece.start)?.title.orEmpty(),
        )
        // Место чтения - на каждом новом абзаце, не на каждой фразе: писать
        // позиции на диск чаще раза в несколько секунд незачем.
        if (piece.block != lastSavedBlock) {
            lastSavedBlock = piece.block
            app.state.saveReadChar(piece.start)
        }
        enqueueUpTo(index + 1, gen)
    }

    private fun progressed(gen: Int, index: Int, start: Int) {
        if (gen != generation || index != current) return
        val t = text ?: return
        val piece = pieceAt(index) ?: return
        val at = (piece.start + start).coerceIn(piece.start, piece.end)
        // Подсвечивается фраза, а не слово: бегущее по строке слово отвлекает,
        // а фраза спокойно показывает, где чтец.
        val sentence = t.sentenceAt(at)
        _state.value = _state.value.copy(
            charOffset = sentence.first.coerceIn(piece.start, piece.end),
            range = sentence.first.coerceAtLeast(piece.start)..sentence.last.coerceAtMost(piece.end),
        )
    }

    private fun done(gen: Int, index: Int) {
        if (gen != generation) return
        if (index >= pieces.lastIndex) return finish()
        // Следующий кусок обычно уже в очереди и сам сообщит о старте. Если
        // очередь пуста (движок не принял), подталкиваем.
        if (queued <= index) enqueueUpTo(index + 1, gen)
    }

    private fun failed(gen: Int, index: Int) {
        if (gen != generation) return
        errorsInRow++
        if (errorsInRow >= 3) {
            stop()
            _state.value = _state.value.copy(error = "Синтез речи не справился с текстом")
            return
        }
        // Один сбойный кусок пропускается: книга не должна вставать из-за строки.
        if (index + 1 < pieces.size) speakFrom(index + 1)
    }

    // -------------------------------------------------------- фокус звука

    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Звонок или навигатор: замолкаем и возвращаемся, когда отпустят.
                if (_state.value.speaking) {
                    pausedByFocus = true
                    halt(keepFocus = true)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
                pausedByFocus = false
                resume()
            }
        }
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        focusRequest = req
        runCatching { audio.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    // ------------------------------------------------------------ нарезка

    /**
     * Абзацы книги - кусками для движка. Картинки и пустые строки
     * пропускаются; абзац длиннее допустимого режется по концам предложений.
     */
    private fun buildPieces(t: BookText): List<Piece> {
        val max = (runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000) - 500)
            .coerceIn(500, 3500)
        val out = ArrayList<Piece>(t.blocks.size)
        t.blocks.forEachIndexed { i, b ->
            if (b.picture != null || b.text.isBlank()) return@forEachIndexed
            if (b.text.length <= max) {
                out.add(Piece(b.start, b.text, i))
                return@forEachIndexed
            }
            var s = 0
            while (s < b.text.length) {
                var e = minOf(s + max, b.text.length)
                if (e < b.text.length) {
                    val cut = b.text.lastIndexOfAny(SENTENCE_END, e - 1)
                    if (cut > s + 200) e = cut + 1
                }
                out.add(Piece(b.start + s, b.text.substring(s, e), i))
                s = e
            }
        }
        return out
    }

    private companion object {
        val SENTENCE_END = charArrayOf('.', '!', '?', '…', ';')
    }
}
