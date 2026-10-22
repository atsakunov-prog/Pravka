package ru.zf.pravka.trigger

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.pravka.core.ZasechkaIntent
import ru.zf.pravka.data.PendingTakes
import ru.zf.pravka.provider.zasechkaFork
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Haptics

// Очередь сказанного без связи (22.10.2026, `data/PendingTakes.kt`): кнопки кладут сюда фразу, когда Claude не
// достучаться, а тик службы и вернувшаяся сеть разбирают её заново — той же дорогой и на то же время, когда сказано.

/** Один проход очереди за раз: тик и сеть могут прийти вместе. */
private val draining = AtomicBoolean(false)

/** Слежка за сетью для очереди: одна на службу. */
@Volatile private var netCallback: ConnectivityManager.NetworkCallback? = null

/**
 * Вернулась сеть — через несколько секунд (VPN встаёт следом за сетью) разобрать очередь. Система зовёт onAvailable
 * и сразу при подписке, если сеть есть: так очередь разбирается и на подъёме службы.
 */
internal fun PravkaAccessibilityService.watchPendingNetwork() {
    unwatchPendingNetwork()
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                delay(NET_SETTLE_MS)
                runCatching { drainPending("сеть") }
            }
        }
    }
    if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) netCallback = cb
}

internal fun PravkaAccessibilityService.unwatchPendingNetwork() {
    val cb = netCallback ?: return
    netCallback = null
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    runCatching { cm.unregisterNetworkCallback(cb) }
}

private const val NET_SETTLE_MS = 8_000L

/**
 * Обрыв связи — положить фразу в очередь и сказать об этом. true — фраза в очереди, старую дорогу неудачи
 * вызывающему не идти; false — это не связь (модель ответила, но не то), очередь ни при чём.
 */
internal suspend fun PravkaAccessibilityService.queueOffline(
    kind: PendingTakes.Kind,
    text: String,
    e: Throwable,
    source: String = "voice",
    photo: File? = null,
    entryId: Long = 0L,
    at: Long = System.currentTimeMillis(),
): Boolean {
    if (!PendingTakes.isNetwork(e)) return false
    val take = runCatching { app.pendingTakes.add(kind, text, at, source, photo, entryId, e.message.orEmpty()) }
        .getOrElse { err ->
            app.eventLog.add("очередь: не легло (${err.javaClass.simpleName}: ${err.message}) — «${text.take(60)}»")
            return false
        }
    app.eventLog.add("очередь: ${kind.key} #${take.id} ждёт связи — «${text.take(60)}» (${e.message})")
    Haptics.error(this)
    Feedback.toast(this, "Нет связи с Claude — запомнил, разберу сам, как появится", long = true)
    return true
}

/**
 * Разобрать очередь: по одной фразе, старые первыми. Снова нет связи — стоп до следующего раза (остальные тоже не
 * пройдут). Модель ответила, но не то («еды не нашлось») — фраза уходит из очереди с запиской: повтор дал бы то же.
 */
internal suspend fun PravkaAccessibilityService.drainPending(why: String) {
    if (!draining.compareAndSet(false, true)) return
    try {
        val list = runCatching { app.pendingTakes.all() }.getOrDefault(emptyList()).sortedBy { it.at }
        if (list.isEmpty()) return
        app.eventLog.add("очередь: $why — в очереди ${list.size}")
        for (t in list) {
            val result = runCatching { replay(t) }.getOrElse { e ->
                if (e is CancellationException) throw e
                Result.failure(e)
            }
            val err = result.exceptionOrNull()
            when {
                err == null -> {
                    app.pendingTakes.done(t.id)
                    app.eventLog.add("очередь: #${t.id} разобрано — ${result.getOrNull()}")
                    Feedback.toast(this, "Разобрал отложенное: ${result.getOrNull()}", long = true)
                }
                PendingTakes.isNetwork(err) -> {
                    app.pendingTakes.failed(t.id, err.message.orEmpty())
                    app.eventLog.add("очередь: связи всё ещё нет (${err.message}) — жду")
                    return
                }
                else -> {
                    app.pendingTakes.done(t.id)
                    app.eventLog.add("очередь: #${t.id} «${t.text.take(60)}» не разобралось — ${err.message}")
                    Feedback.toast(this, "Отложенное «${t.text.take(40)}» не разобралось: ${err.message}", long = true)
                }
            }
        }
    } finally {
        draining.set(false)
    }
}

/** Повторить одну фразу её дорогой. Успех — строка для журнала и тоста. */
private suspend fun PravkaAccessibilityService.replay(t: PendingTakes.Take): Result<String> = when (t.kind) {
    PendingTakes.Kind.FOOD -> replayFood(t.text, t.at, t.source, app.pendingTakes.photoFile(t))
    PendingTakes.Kind.MONEY -> replayMoney(t)
    PendingTakes.Kind.ZASECHKA -> replayZasechka(t)
}

/** Еда: разбор на время, когда сказано, и сразу в дневник — как у кнопки. */
private suspend fun PravkaAccessibilityService.replayFood(text: String, at: Long, source: String, photo: File?): Result<String> {
    val parsed = app.foodEngine.parse(text, photo = photo, source = source, at = at).getOrElse { return Result.failure(it) }
    val meal = app.foodEngine.confirm(parsed.meal.id).meal
        ?: return Result.success("«${text.take(40)}» разобрано, ждёт подтверждения в «Еде»")
    return Result.success(
        (if (text.isNotBlank()) "«${text.take(40)}» → " else "снимок → ") +
            if (meal.supplement) "добавки в дневнике" else "${meal.kcal} ккал в дневнике"
    )
}

/** Траты: черновики и сразу в журнал — плашки «ОК» уже не будет, а её молчание и есть «да». */
private suspend fun PravkaAccessibilityService.replayMoney(t: PendingTakes.Take): Result<String> {
    val takeId = app.moneyEngine.dictate(t.text, at = t.at).getOrElse { return Result.failure(it) }
    val drafts = app.moneyEngine.draftsOf(takeId)
    if (drafts.isEmpty()) return Result.failure(IllegalStateException("трат не нашлось"))
    app.moneyEngine.confirm(takeId, drafts.map { it.id })
    return Result.success("«${t.text.take(40)}» → трат ${drafts.size} в журнале")
}

/**
 * Засечка: сырая запись уже лежит в ленте на своём месте ([PendingTakes.Take.entryId]). Сначала развилка — что это
 * было: еда становится ещё и приёмом на то же время (запись остаётся делом ленты, как «Завтрак»). Потом сама запись
 * получает название, категорию и клиента (`ZasechkaEngine.label`) — время и соседи не трогаются.
 */
private suspend fun PravkaAccessibilityService.replayZasechka(t: PendingTakes.Take): Result<String> {
    val fork = app.claudeProvider.zasechkaFork(t.text, "Сказано в ${zTime(t.at)}.", false)
    val forkErr = fork.exceptionOrNull()
    if (forkErr != null && PendingTakes.isNetwork(forkErr)) return Result.failure(forkErr)
    val kind = fork.getOrNull()?.let { ZasechkaIntent.fromModel(it.raw, t.text)?.kind } ?: ZasechkaIntent.route(t.text).kind
    val parts = mutableListOf<String>()
    val outcome = app.zasechkaEngine.label(t.entryId)
    if (!outcome.categorized && outcome.offline) return Result.failure(java.io.IOException(outcome.error ?: "нет связи"))
    if (outcome.action == "edit") parts += "лента: «${outcome.entry.title}» [${outcome.entry.category.ifBlank { "без категории" }}]"
    if (kind == ZasechkaIntent.Kind.FOOD) {
        replayFood(t.text, t.at, "voice", null)
            .onSuccess { parts += it }
            .onFailure { e -> parts += "еда не разобралась: ${e.message}" }
    }
    return Result.success(parts.joinToString(" · ").ifBlank { "«${t.text.take(40)}» — запись ленты уже разобрана" })
}
