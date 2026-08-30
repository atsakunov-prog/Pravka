package ru.zf.slushalka.ask

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Ответ вслух. Тумблер в настройках: за рулём, на кухне и на прогулке в экран
 * не смотрят, а вопрос там задают чаще всего.
 */
class Speaker(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ready = false
    private var pending: Pair<String, () -> Unit>? = null
    private var onDone: (() -> Unit)? = null

    // Обратный вызов инициализации может прийти раньше, чем присвоится сама
    // ссылка, поэтому внутри него к tts не обращаемся - только помечаем
    // готовность и договариваем остальное следующим сообщением главного потока.
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        main.post {
            ready = ok
            if (ok) {
                runCatching { tts.language = Locale.forLanguageTag("ru-RU") }
                pending?.let { (text, done) ->
                    pending = null
                    speak(text, done)
                }
            } else {
                pending?.second?.invoke()
                pending = null
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) = fireDone()

            // Абстрактный метод слушателя: реализовать обязаны, хотя он и
            // помечен устаревшим в пользу onError(String, Int).
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) = fireDone()
        })
    }

    private fun fireDone() {
        main.post {
            onDone?.invoke()
            onDone = null
        }
    }

    fun speak(text: String, done: () -> Unit = {}) {
        // Разметку модель и так не ставит, но если проскочит - вслух её не
        // читаем: «звёздочка Эраст звёздочка» звучит дико.
        val clean = text.replace(Regex("[*#`_]+"), "").trim()
        if (clean.isBlank()) return done()
        if (!ready) {
            pending = clean to done
            return
        }
        onDone = done
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE)
    }

    val isSpeaking: Boolean get() = runCatching { tts.isSpeaking }.getOrDefault(false)

    fun stop() {
        onDone = null
        pending = null
        runCatching { tts.stop() }
    }

    fun release() {
        stop()
        runCatching { tts.shutdown() }
    }

    private companion object {
        const val UTTERANCE = "slushalka-answer"
    }
}
