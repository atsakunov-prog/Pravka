package ru.zf.pravka.trigger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import ru.zf.pravka.R
import ru.zf.pravka.ui.Feedback

/**
 * Кнопка гарнитуры. Прозрачный трамплин, как у метки NFC: команду «голосовой
 * помощник» Android отдаёт только Activity (`ACTION_VOICE_COMMAND`), — но
 * живёт она доли секунды и ничего не рисует.
 *
 * Откуда команда. Гарнитура по долгому нажатию (у Shokz OpenComm2 2025 —
 * Mute две секунды вне звонка; у машины — кнопка голоса на руле) шлёт по
 * Bluetooth «включи распознавание», стек телефона открывает на неё
 * приложение голосовых команд. Правка объявила себя таким приложением; в
 * первый раз система спросит «чем открыть» — «Правка, всегда». Спрашивает она
 * только на разблокированном экране, поэтому первый раз — не с локскрина.
 *
 * Решение — в службе и сразу, в onCreate: пока окно трамплина не встало,
 * фокус ещё у поля, где стоял курсор, и «с полем или без» решается
 * по-настоящему (`ServiceHeadset.kt`).
 */
class HeadsetButtonActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle()
        // Экран не нужен ни на кадр: тейк живёт в службе.
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle()
        finish()
    }

    private fun handle() {
        val service = PravkaAccessibilityService.instance
        if (service == null) {
            Feedback.toast(this, getString(R.string.toast_no_service))
            return
        }
        service.onHeadsetButton()
    }

    /** Кто сейчас отвечает на кнопку гарнитуры — для строки в настройках. */
    sealed class Answer {
        data object Pravka : Answer()
        /** Несколько приложений и ни одно не выбрано — система спросит при нажатии. */
        data object Ask : Answer()
        data class Other(val packageName: String, val label: String) : Answer()
        data object Nobody : Answer()
    }

    companion object {

        /**
         * Молчаливая механика читается как поломка (правило 6): если «всегда»
         * когда-то выбрали за Google, Правка кнопку гарнитуры не получит
         * никогда, и снаружи это ровно «не работает».
         */
        fun whoAnswers(context: Context): Answer = runCatching {
            val pm = context.packageManager
            val ri = pm.resolveActivity(Intent(Intent.ACTION_VOICE_COMMAND), PackageManager.MATCH_DEFAULT_ONLY)
                ?: return@runCatching Answer.Nobody
            val pkg = ri.activityInfo?.packageName.orEmpty()
            when {
                pkg == context.packageName -> Answer.Pravka
                // Выбор «чем открыть» — системный: android или отдельный модуль.
                pkg == "android" || pkg.contains("intentresolver") || pkg.contains("resolver") -> Answer.Ask
                else -> Answer.Other(pkg, ri.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg })
            }
        }.getOrDefault(Answer.Nobody)
    }
}
