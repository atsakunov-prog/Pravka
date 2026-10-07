package ru.zf.pravka.trigger

import kotlinx.coroutines.launch
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.ui.Haptics

// «🐞 Баг или предложение» — пункт меню долгого нажатия у каждой кнопки
// (07.10.2026, владелец: «на долгом нажатии на кнопках на диске „записать баг
// или предложение“. И тогда у нас будет копиться и раз в день можно это
// открывать и обновлять»). Говорится там же, где замечено, — не открывая
// приложение. Слушает движок «Д» с заказом текста (`listenForDelaReason`, как
// причина отказа во вкладке Дел) и своей подсказкой в пилюле; сказанное — в
// `FeedbackStore` сразу, сырьём, чистка Правкой догоняет. Список — «Ещё → Баги
// и предложения», разбор раз в день — `docs/feedback.md`.

/**
 * Начать наговор бага или предложения. [origin] — с какой кнопки (П, З, Д,
 * ₽): по нему видно, о каком режиме речь, даже если в словах этого нет.
 */
internal fun PravkaAccessibilityService.startFeedbackTake(origin: String) {
    touched()
    // Без кнопки «Д» (Дела выключены в профиле) слушать некому — пишем в
    // приложении, там же набор текстом.
    if (rButton == null) {
        openFeedbackTab()
        return
    }
    val prompt = ru.zf.pravka.core.PillHint.say(app.profileStore.current?.name, "баг или предложение?")
    val started = listenForDelaReason(prompt) { text -> onFeedbackText(text, origin) }
    if (started) app.eventLog.add("баг/предложение: слушаю (с «$origin»)")
}

/** Сказанное в руках: на диск сразу, записка в пилюле, потом чистка и архив. */
internal fun PravkaAccessibilityService.onFeedbackText(raw: String, origin: String) {
    val text = raw.trim()
    if (text.isEmpty()) return
    scope.launch {
        val item = app.feedbackStore.add(text, origin, ru.zf.pravka.BuildConfig.VERSION_NAME)
        app.eventLog.add("баг/предложение №${item.num} с «$origin»: ${text.take(160)}")
        Haptics.success(this@onFeedbackText)
        rButton?.showNote(
            "🐞 Записал №${item.num}: «${text.take(140)}${if (text.length > 140) "…" else ""}»",
            "Открыть",
            holdMs = 6_000,
        ) { openFeedbackTab() }
        // Чистка — тем же движком Правки, что мысль к делу: словарь и правила
        // уберут ошибки распознавания. Не вышло — остаётся сказанное.
        val target = ru.zf.pravka.target.PlainTextTarget(text)
        runCatching { app.engine.proofread(target, ProofreadMode.CLEAN) }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                app.eventLog.add("баг/предложение №${item.num}: чистка упала — ${e.javaClass.simpleName}: ${e.message}")
            }
        val cleaned = target.result?.trim().orEmpty()
        if (cleaned.isNotBlank() && cleaned != text) app.feedbackStore.setText(item.num, cleaned)
        // На комп — сразу, не дожидаясь тика: ежедневный разбор читает оттуда.
        app.archiveSync.poke(ru.zf.pravka.data.ArchiveSync.Domain.PRAVKA)
    }
}

/** «Ещё → Баги и предложения» поверх «Сегодня». */
internal fun PravkaAccessibilityService.openFeedbackTab() {
    runCatching {
        startActivity(
            android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_FEEDBACK)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
