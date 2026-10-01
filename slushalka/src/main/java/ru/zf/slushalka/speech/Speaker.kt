package ru.zf.slushalka.speech

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
 *
 * Синтезатор заводится на первую фразу и отпускается через две минуты тишины
 * (01.10.2026, проверка батареи: «Распознавание и синтез речи» 57 % за день).
 * Раньше он поднимался на старте приложения и держался, пока жив процесс, —
 * а процесс с играющей книгой живёт часами, и всё это время служба речи
 * Google была привязана и жива ради ответа, которого могло и не быть. Снова
 * завести — доля секунды перед первой фразой.
 */
class Speaker(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pending: Pair<String, () -> Unit>? = null
    private var onDone: (() -> Unit)? = null
    private val idleRelease = Runnable { releaseIfQuiet() }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) = fireDone()

        // Абстрактный метод слушателя: реализовать обязаны, хотя он и
        // помечен устаревшим в пользу onError(String, Int).
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = fireDone()
    }

    /** Завести синтезатор, если его нет. Только главный поток. */
    private fun engine() {
        if (tts != null) return
        ready = false
        // Обратный вызов инициализации может прийти раньше, чем присвоится
        // ссылка, поэтому внутри него к tts не обращаемся - только помечаем
        // готовность и договариваем остальное следующим сообщением главного потока.
        val t = runCatching {
            TextToSpeech(context.applicationContext) { status ->
                main.post { onInit(status == TextToSpeech.SUCCESS) }
            }
        }.getOrNull()
        if (t == null) {
            pending?.second?.invoke()
            pending = null
            return
        }
        t.setOnUtteranceProgressListener(listener)
        tts = t
        // Не ответил вовсе - отпустить через ту же паузу, а не держать вечно.
        scheduleRelease()
    }

    private fun onInit(ok: Boolean) {
        val t = tts ?: return
        ready = ok
        if (!ok) {
            pending?.second?.invoke()
            pending = null
            release()
            return
        }
        runCatching { t.language = Locale.forLanguageTag("ru-RU") }
        pending?.let { (text, done) ->
            pending = null
            speak(text, done)
        }
    }

    private fun fireDone() {
        main.post {
            onDone?.invoke()
            onDone = null
            scheduleRelease()
        }
    }

    fun speak(text: String, done: () -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { speak(text, done) }
            return
        }
        // Разметку модель и так не ставит, но если проскочит - вслух её не
        // читаем: «звёздочка Эраст звёздочка» звучит дико.
        val clean = text.replace(Regex("[*#`_]+"), "").trim()
        if (clean.isBlank()) return done()
        main.removeCallbacks(idleRelease)
        val t = tts
        if (t == null || !ready) {
            pending = clean to done
            engine()
            return
        }
        onDone = done
        t.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE)
        // Отсчёт и отсюда: если «договорил» не придёт (синтезатор умер), держать вечно нельзя.
        scheduleRelease()
    }

    val isSpeaking: Boolean get() = runCatching { tts?.isSpeaking == true }.getOrDefault(false)

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stop() }
            return
        }
        onDone = null
        pending = null
        runCatching { tts?.stop() }
        if (tts != null) scheduleRelease()
    }

    /** Отпустить синтезатор сейчас: служба речи Google больше не держится. */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { release() }
            return
        }
        main.removeCallbacks(idleRelease)
        onDone = null
        ready = false
        val t = tts ?: return
        tts = null
        runCatching { t.stop() }
        runCatching { t.shutdown() }
    }

    private fun scheduleRelease() {
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_MS)
    }

    /** Тишина - отпустить; ещё говорит - подождать. Кто ждал конца фразы, получает «готово». */
    private fun releaseIfQuiet() {
        if (tts == null) return
        if (isSpeaking) {
            main.postDelayed(idleRelease, RECHECK_MS)
            return
        }
        pending?.second?.invoke()
        pending = null
        // «Договорил» так и не пришло - книга, ждущая конца ответа, не должна стоять вечно.
        onDone?.invoke()
        onDone = null
        release()
    }

    private companion object {
        const val UTTERANCE = "slushalka-answer"

        /** Сколько держать синтезатор после последней фразы: в разговоре с вопросами фразы идут чаще. */
        const val IDLE_MS = 2 * 60_000L
        const val RECHECK_MS = 5_000L
    }
}
