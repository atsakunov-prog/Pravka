package ru.zf.pravka.trigger

import android.content.Context
import android.media.AudioManager
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.provider.GoogleSpeechSession
import ru.zf.pravka.provider.MicRouting

/**
 * «Перезагрузить микрофон» — одна кнопка на весь микрофонный тракт.
 *
 * Владелец (22.09.2026): «иногда я подключаюсь к машине, и он не слышит. Как
 * бы я ни переключал между наушниками и машиной, он реально не слышит, но
 * потом каким-то странным образом начинает слышать». Такое залипание бывает
 * трёх видов, и снаружи все три выглядят одинаково — тишина:
 *
 *  1. **Маршрут.** Хендс-фри машины держит канал связи, и системный
 *     распознаватель слушает микрофон у лобового, а не тот, что в руке.
 *  2. **Служба распознавания.** Процесс движка подвис или был убит; клиент
 *     привязывается и молчит, а мы помним старый ответ «распознавание
 *     доступно» (доступность спрашивается один раз за жизнь процесса).
 *  3. **Залипшее удержание.** Пустая служба переднего плана осталась висеть
 *     после оборванного тейка.
 *
 * Перезагрузка бьёт по всем трём и рассказывает словами, что нашла: молчаливая
 * механика читается как поломка (`docs/agreements.md`).
 *
 * Работает и без службы доступности (из вкладки настроек), и из неё (долгое
 * нажатие на кружок микрофона в веере шестерёнки).
 */
fun reloadMicrophone(context: Context): String {
    val app = context.applicationContext as PravkaApp
    val log: (String) -> Unit = { line -> app.eventLog.add("микрофон: $line") }
    val service = PravkaAccessibilityService.instance

    // Идущий тейк не рвём: перезагрузка посреди записи — это потерянная
    // надиктовка, а сырая надиктовка не теряется никогда (правило проекта).
    if (service?.micBusy() == true) {
        return "Идёт запись — сначала останови тейк, потом перезагружай."
    }

    val report = StringBuilder()
    val am = context.getSystemService(AudioManager::class.java)
    if (am == null) {
        log("AudioManager недоступен — перезагружать нечего")
        return "Система не отдала микшер — перезагружать нечего."
    }
    report.append(MicRouting.reload(am, log))

    // Залипшее удержание микрофона: снимаем только когда оно реально висит —
    // будить службу вслепую из фона нельзя.
    if (DictationService.holding) {
        runCatching { context.stopMicHold() }
        report.append("\nСнял залипшее удержание микрофона.")
        log("снял залипшее удержание")
    }

    // Движок: забыть кэш доступности и завести распознаватель заново.
    val network = service?.cachedNetwork ?: false
    GoogleSpeechSession.reloadEngine(context, network, log)
    report.append("\nРаспознаватель перезапущен (путь: ").append(if (network) "сеть" else "офлайн-пакет").append(").")
    return report.toString()
}

/** Состояние микрофона сейчас — строкой; отчёт «стало» после перезагрузки. */
fun micStateNow(context: Context): String {
    val am = context.getSystemService(AudioManager::class.java) ?: return "состояние не прочиталось"
    return MicRouting.describe(am)
}
