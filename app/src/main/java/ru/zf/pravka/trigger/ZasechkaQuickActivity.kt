package ru.zf.pravka.trigger

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.ui.Feedback

// Trampoline behind Засечка notification actions: an Activity is the only
// thing a notification can launch, but the actual work lives in the
// accessibility service. Starts the requested action and gets out of the way.
// The home-screen widget (`ZasechkaWidget`, 11.10.2026) comes through here too:
// a take, a thought for the running дело, «ещё раз» for a recent one.
class ZasechkaQuickActivity : Activity() {

    companion object {
        const val EXTRA_WHAT = "what"
        const val W_RECORD = "record"    // default: start a voice take
        /**
         * Якорь времени тейка: пуш автопилота знает, КОГДА кончилась дорога
         * («машина отключилась в 14:02») — сказанное ляжет с этого момента,
         * а не с секунды, когда владелец договорил. [EXTRA_UNTIL] — конец
         * закрытой дыры, тогда сказанное — вставка ровно в неё.
         */
        const val EXTRA_AT = "at"
        const val EXTRA_UNTIL = "until"

        /** Мысль к идущему делу (а если ничего не идёт — к последнему), как в меню «З». */
        const val W_THOUGHT = "thought"

        /**
         * «Ещё раз» с виджета: начать сейчас такое же дело, как запись
         * [EXTRA_ENTRY], — то же название, категория, клиент и дело Дел. Без
         * голоса и без Claude, как касание метки NFC.
         */
        const val W_AGAIN = "again"
        const val EXTRA_ENTRY = "entry"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val what = intent?.getStringExtra(EXTRA_WHAT) ?: W_RECORD
        // «Ещё раз» службе не нужен: лента живёт в приложении.
        if (what == W_AGAIN) {
            again(intent?.getLongExtra(EXTRA_ENTRY, 0L) ?: 0L)
            finish()
            return
        }
        val service = PravkaAccessibilityService.instance
        if (service == null) {
            Feedback.toast(this, getString(R.string.toast_no_service))
        } else if (what == W_THOUGHT) {
            service.scope.launch {
                val app = service.app
                val target = runCatching { app.zasechkaStore.openEntry() ?: app.zasechkaStore.lastEntry() }.getOrNull()
                if (target == null) Feedback.toast(service, "В ленте пока нет дела для мысли")
                else service.startZasechkaComment(target.id)
            }
        } else {
            service.onZasechkaTap(
                anchorStart = intent?.getLongExtra(EXTRA_AT, 0L) ?: 0L,
                anchorEnd = intent?.getLongExtra(EXTRA_UNTIL, 0L) ?: 0L,
            )
        }
        finish()
    }

    /**
     * Начать сейчас такое же дело, как запись [id]: открытое закрывается в
     * этот же миг, лента остаётся непрерывной (`ZasechkaStore.startEntry`).
     * Источник — «voice», как у метки NFC: для ленты это осознанная запись
     * владельца, а не автомат.
     */
    private fun again(id: Long) {
        val app = application as PravkaApp
        app.appScope.launch {
            val e = runCatching { app.zasechkaStore.entryById(id) }.getOrNull()
            if (e == null) {
                Feedback.toast(app, "Это дело в ленте уже не нашлось")
                return@launch
            }
            val now = System.currentTimeMillis()
            runCatching {
                app.zasechkaStore.startEntry(
                    now, "", e.title, e.category, e.client, 0, "voice",
                    task = e.task, project = e.project,
                )
            }.onSuccess {
                Feedback.toast(app, "⏱ ${e.title} — с " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(now)))
                app.eventLog.add("виджет: начато ещё раз «${e.title}» [${e.category}]")
            }.onFailure {
                Feedback.toast(app, "Не начал «${e.title}»: ${it.message ?: it.javaClass.simpleName}", long = true)
            }
        }
    }
}
