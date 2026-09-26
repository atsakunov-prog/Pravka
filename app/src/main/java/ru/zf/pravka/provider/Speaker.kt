package ru.zf.pravka.provider

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Голос Правки — короткое «записал коммент» в ухо. Владелец (25.09.2026):
 * «говорит в конце: записал коммент / записал еду / записал дела». Тейк с
 * кнопки гарнитуры идёт без взгляда на экран — часто на заблокированном
 * телефоне, — и записка на стекле там не видна никому.
 *
 * Синтезатор системный (`TextToSpeech`), голос — русский, какой стоит в
 * телефоне. Заводится при первой фразе, а не на подъёме службы: большинство
 * дней он не нужен вовсе. Пока заводится — фразы ждут в очереди. Звук идёт как
 * у помощника (`USAGE_ASSISTANT`), то есть туда же, куда музыка: в гарнитуру,
 * если она подключена.
 */
class Speaker(private val context: Context, private val log: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var broken = false
    private val pending = ArrayList<String>()
    private var seq = 0

    fun say(text: String) {
        if (text.isBlank() || broken) return
        if (ready) {
            speakNow(text)
            return
        }
        pending += text
        if (tts != null) return
        tts = runCatching {
            TextToSpeech(context.applicationContext) { status -> main.post { onInit(status) } }
        }.getOrElse {
            log("синтезатор речи не завёлся: ${it.javaClass.simpleName}: ${it.message}")
            broken = true
            pending.clear()
            null
        }
    }

    fun shutdown() {
        pending.clear()
        ready = false
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            // Молча не глохнем: запись сделана, а «почему не сказал» — в журнал.
            log("синтезатор речи ответил ошибкой ($status) — говорю только запиской")
            broken = true
            pending.clear()
            return
        }
        val lang = runCatching { engine.setLanguage(Locale("ru", "RU")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            log("русского голоса в синтезаторе нет ($lang) — говорю как умеет")
        }
        runCatching {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        ready = true
        val queued = ArrayList(pending)
        pending.clear()
        queued.forEach(::speakNow)
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return
        val result = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "pravka-${++seq}")
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) log("не сказал «$text»")
    }
}
