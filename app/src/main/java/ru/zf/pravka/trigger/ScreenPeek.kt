package ru.zf.pravka.trigger

import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.zf.pravka.core.PhoneSit

/**
 * Взгляд на экран для «Телефона в ленте» (13.10.2026, `core/PhoneSit.kt`):
 * раз в минуту — какое приложение впереди и что в нём видно, словами. Снимок
 * уходит в память движка (`PhoneSitEngine.addSnap`), на диск — никогда; Сонет
 * видит его, только когда разбирает сидение от порога.
 *
 * Это исключение из правила «за чужими окнами не подглядывать» (складывание
 * Fold: четыре раза чернота от синхронных обращений службы в чужие процессы,
 * опрос адресной строки Chrome был снят именно поэтому), и владелец попросил
 * его сам. Поэтому взгляд устроен так, чтобы складыванию нечего было ждать:
 *  - СВОЙ поток с низким приоритетом, не главный поток службы: чужой процесс
 *    отвечает медленно — ждёт этот поток, а не кнопки и не окна;
 *  - событий окон служба по-прежнему не получает (её типы событий не
 *    менялись) — это опрос раз в минуту, а не подписка;
 *  - не смотрит посреди складывания и двадцать секунд после него, на замке и
 *    при погасшем экране;
 *  - обход ограничен: 160 узлов, глубина 25, четверть секунды — дальше бросает;
 *  - пароли не читаются никогда, банки, Госуслуги и сама Правка — только
 *    названием (`PhoneSit.privateText`).
 * Откат — тумблер «Читать текст экрана» (выключен — взгляда нет вовсе, Сонет
 * судит по приложениям из статистики использования). Взгляд дольше полусекунды
 * — строкой в журнал: если чернота вернётся, будет видно, чей процесс тормозил.
 */
internal class ScreenPeek(private val service: PravkaAccessibilityService) {

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pravka-peek").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /** Тумблер «Телефон в ленте» и «Читать текст экрана» включены, Засечка не молчит. */
    @Volatile private var enabled = false

    /** Первый удачный взгляд в эту жизнь службы — строкой в журнал, чтобы было видно, что смотрит. */
    @Volatile private var told = false

    fun start() {
        val app = service.app
        service.scope.launch {
            combine(app.zPhoneSitFlow, app.settings.zPhoneTextFlow, app.zQuietFlow) { on, text, quiet ->
                on && text && !quiet
            }.collect { enabled = it }
        }
        exec.scheduleWithFixedDelay(
            { runCatching { look() } },
            30_000L, PhoneSit.PEEK_EVERY_MS, TimeUnit.MILLISECONDS,
        )
    }

    fun stop() {
        runCatching { exec.shutdownNow() }
    }

    private fun look() {
        if (!enabled) return
        val app = service.app
        if (!app.profileStore.has(ru.zf.pravka.data.Profile.Mode.ZASECHKA)) return
        if (service.folding || SystemClock.uptimeMillis() - service.foldAtUptime < FOLD_QUIET_MS) return
        val pm = service.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return
        if (runCatching { service.keyguardManager?.isKeyguardLocked == true }.getOrDefault(true)) return
        val started = SystemClock.uptimeMillis()
        val root = service.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == service.packageName || PhoneSit.furniture(pkg)) return
        val text = if (PhoneSit.privateText(pkg)) "" else PhoneSit.clean(collect(root, started))
        val took = SystemClock.uptimeMillis() - started
        app.phoneSit.addSnap(PhoneSit.Snap(System.currentTimeMillis(), pkg, text))
        if (took > SLOW_MS) {
            app.eventLog.add("телефон в ленте: взгляд в «${app.phoneSit.label(pkg)}» занял $took мс")
        } else if (!told) {
            told = true
            app.eventLog.add(
                "телефон в ленте: смотрю на экран раз в минуту — «${app.phoneSit.label(pkg)}», " +
                    "${text.length} знаков за $took мс"
            )
        }
    }

    /**
     * Видимый текст окна обходом в ширину: сверху вниз — заголовок чата,
     * название ролика, адрес страницы приходят первыми. Подписи картинок
     * (contentDescription) тоже: у Телеграма текст сообщений — там.
     */
    private fun collect(root: AccessibilityNodeInfo, started: Long): List<String> {
        val out = ArrayList<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES && SystemClock.uptimeMillis() - started < BUDGET_MS) {
            val (n, depth) = queue.removeFirst()
            visited++
            if (n.isPassword || !n.isVisibleToUser) continue
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            if (depth >= MAX_DEPTH) continue
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.addLast(it to depth + 1) }
            }
        }
        return out
    }

    private companion object {
        const val MAX_NODES = 160
        const val MAX_DEPTH = 25
        const val BUDGET_MS = 250L
        const val SLOW_MS = 500L
        const val FOLD_QUIET_MS = 20_000L
    }
}
