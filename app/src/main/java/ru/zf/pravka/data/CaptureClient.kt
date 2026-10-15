package ru.zf.pravka.data

import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Capture

/**
 * Захват на телефоне — одна дверь в Дела (шаг 0 петли, 15.10.2026; задание — docs/dela-phone-22c.md, п. 1). Фраза
 * уходит на сервер операцией `capture.say`; раскладывает сервер, строку «куда легло и когда вернётся» и кнопки поправки
 * отдаёт он же. Телефон ждёт итог и показывает его — пилюлей «Д» и «З», строкой «сказать».
 *
 * Две дороги:
 * - [rule] — только правило ($0, миллисекунды): мимо очереди, с коротким ожиданием. Его спрашивают параллельно своей
 *   развилке (Сонет «сказать» и «З»): узнало — строка сервера, ответ Сонета выбрасывается; нет — дорога Сонета. Без
 *   связи ответа нет — решает развилка, фраза не теряется.
 * - [say] — правило, а не узнало — модель на сервере (`lane: auto`): через очередь Дел (на диске, op_id): без связи
 *   фраза ждёт и уйдёт сама, итог придёт тихим уведомлением. Тот же `ref`, что у [rule], — сервер не запишет дважды:
 *   повтор с тем же ref отдаёт прежний итог.
 *
 * Время до строки — в журнал событий («захват: … — строка через N мс»).
 */
class CaptureClient(
    private val store: DelaStore,
    private val sync: DelaSync,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    /** Итог, пришедший позже (без связи, долгая модель), — тихим уведомлением. */
    private val notice: (String) -> Unit,
) {

    /**
     * Чем кончилось: [result] — итог сервера (done, pending, error, none); [offline] — фраза в очереди, ответа нет;
     * [slow] — при этом связь не падала (медленная сеть, первый запрос после простоя): не «нет связи», а «строка придёт
     * уведомлением»; [refused] — сервер отказал (его словами); [ms] — сколько ждали строку.
     */
    data class Outcome(
        val result: Capture.Result? = null,
        val offline: Boolean = false,
        val refused: String = "",
        val ms: Long = 0,
        val ref: String = "",
        val slow: Boolean = false,
    ) {
        /** Строка для глаз — что бы ни случилось. */
        val line: String
            get() = when {
                refused.isNotBlank() -> "Сервер не принял: $refused"
                offline && slow -> Capture.SLOW_LINE
                offline -> Capture.OFFLINE_LINE
                result != null -> Capture.line(result)
                else -> "Нет ответа Дел"
            }
    }

    private fun saidAt(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

    /**
     * Только правило — сразу на сервер, мимо очереди (`POST /api/ops`, тот же путь, что «Где мы»). Ждёт не дольше
     * [waitMs]: правило отвечает за доли секунды, а дальше его ждёт развилка. Нет связи или ответа — null (решает
     * развилка; если она скажет «дела» — [say] с тем же [ref]).
     */
    suspend fun rule(text: String, ref: String, from: String, hint: String = "", waitMs: Long = RULE_WAIT_MS): Capture.Result? {
        if (sync.link.value == null) return null
        val started = System.currentTimeMillis()
        val op = Capture.sayOp(text, ref, Capture.LANE_RULE, from, saidAt(), hint).apply { remove("op_id") }
        val job = scope.async(Dispatchers.IO) { runCatching { sync.rawOps(JSONArray().put(op)) } }
        val got = withTimeoutOrNull(waitMs) { job.await() }
        val ms = System.currentTimeMillis() - started
        val results = got?.getOrNull()
        if (results == null) {
            log("захват: правило не ответило за ${ms} мс" + (got?.exceptionOrNull()?.message?.let { " — $it" } ?: ""))
            return null
        }
        return when (val r = Capture.reply(results.optJSONObject(0))) {
            is Capture.Reply.Ok -> r.result.also {
                if (it.done) {
                    log("захват ($from): правило ${it.form} — «${Capture.line(it).take(80)}» за $ms мс")
                    pullSoon()
                }
            }
            is Capture.Reply.Refused -> {
                log("захват: правило — отказ: ${r.error}")
                null
            }
            null -> null
        }
    }

    /**
     * Фраза в Дела очередью (`lane: auto`): правило, а не узнало — модель на сервере. [onPending] — строка «Записал —
     * раскладываю» сразу, пока модель думает; итог — возвратом (до полутора минут). Без связи — [Outcome.offline], а
     * когда очередь уйдёт, итог придёт тихим уведомлением.
     */
    suspend fun say(
        text: String,
        ref: String,
        from: String,
        hint: String = "",
        lane: String = Capture.LANE_AUTO,
        onPending: (Capture.Result) -> Unit = {},
    ): Outcome {
        val started = System.currentTimeMillis()
        val op = Capture.sayOp(text, ref, lane, from, saidAt(), hint)
        val opId = op.optString("op_id")
        store.enqueue(listOf(op))
        // Медленно (больше 2,5 с) — «Отправляю…» на месте строки, ждём до 6 с (проверка 16.10.2026: LTE с VPN и первый
        // запрос после простоя давали «Нет связи с Делами», хотя запрос был в пути и ложился через секунду).
        val reply = push(opId) { onPending(Capture.Result(ref, Capture.Status.PENDING, head = Capture.sendingLine(text), text = text)) }
        if (reply == null) {
            if (store.isQueued(opId)) {
                val down = netFailedSince(started)
                log("захват ($from): " + (if (down) "нет связи с Делами" else "Дела не ответили за ${PUSH_MAX_MS / 1000} с") +
                    " — фраза в очереди («${text.take(60)}»)")
                follow(opId, ref, from)
                return Outcome(offline = true, slow = !down, ms = System.currentTimeMillis() - started, ref = ref)
            }
            // Ушла, а ответа в памяти нет (снята из очереди или ответ потерялся) — спросим вид.
            return finish(poll(ref, from, onPending), from, started, ref, pull = true)
        }
        return when (val r = Capture.reply(reply)) {
            is Capture.Reply.Refused -> Outcome(refused = r.error, ms = System.currentTimeMillis() - started, ref = ref)
            is Capture.Reply.Ok -> {
                val waited = r.result.status == Capture.Status.PENDING
                val res = if (waited) {
                    onPending(r.result)
                    poll(ref, from, onPending) ?: r.result
                } else r.result
                // Правило записало в том же проходе очереди, что и синк, — копия уже знает новое; модель — позже.
                finish(res, from, started, ref, pull = waited)
            }
            null -> finish(poll(ref, from, onPending), from, started, ref, pull = true)
        }
    }

    /**
     * Отправить очередь сейчас и ждать ответа именно этой операции — не всего прохода: синк после очереди строку не
     * задерживает. Не дождались за [PUSH_WAIT_MS] — [onSlow] (строка «Отправляю…») и ждём дальше, до [PUSH_MAX_MS], пока
     * проход не кончится; null — ответа нет (связь упала или сервер медлит; проход идёт дальше сам).
     */
    private suspend fun push(opId: String, onSlow: () -> Unit = {}): JSONObject? {
        val job = scope.async(Dispatchers.IO) { runCatching { sync.pushNow(PUSH_MAX_MS) }.getOrDefault(false) }
        withTimeoutOrNull(PUSH_WAIT_MS) { store.captured.first { it.containsKey(opId) }[opId] }?.let { return it }
        if (job.isCompleted) return store.captureReply(opId)
        onSlow()
        return withTimeoutOrNull(PUSH_MAX_MS - PUSH_WAIT_MS) {
            var got: JSONObject? = store.captureReply(opId)
            while (got == null && !job.isCompleted) {
                delay(150)
                got = store.captureReply(opId)
            }
            got ?: store.captureReply(opId)
        }
    }

    /** Проход синка упал с ошибкой после [since] — связи с Делами правда нет (а не просто медленно). */
    private fun netFailedSince(since: Long): Boolean = sync.status.value.lastErrorAt >= since

    private fun finish(res: Capture.Result?, from: String, started: Long, ref: String, pull: Boolean): Outcome {
        val ms = System.currentTimeMillis() - started
        if (res != null) {
            log("захват ($from): ${res.status.key}${if (res.by.isNotBlank()) ", " + res.by else ""} — «${Capture.line(res).take(80)}», строка через $ms мс")
            if (pull && res.status != Capture.Status.PENDING) pullSoon()
        }
        return Outcome(result = res, ms = ms, ref = ref)
    }

    /**
     * Кнопка поправки (`capture.move`): ответ — новая строка («Переложил: …»). Очередью, как любая правка Дел: без связи
     * она уйдёт сама, а строка скажет это словами.
     */
    suspend fun move(ref: String, item: Int, chip: Capture.Chip, option: Capture.Option? = null): Outcome {
        val started = System.currentTimeMillis()
        val op = Capture.moveFor(ref, item, chip, option)
        val opId = op.optString("op_id")
        store.enqueue(listOf(op))
        val reply = push(opId)
            ?: return if (store.isQueued(opId)) Outcome(offline = true, ref = ref) else Outcome(result = view(ref), ref = ref)
        return when (val r = Capture.reply(reply)) {
            is Capture.Reply.Refused -> Outcome(refused = r.error, ref = ref)
            is Capture.Reply.Ok -> {
                log("захват: поправка «${chip.label}${option?.let { " · " + it.label } ?: ""}» — «${Capture.line(r.result).take(80)}»")
                pullSoon()
                // «Повторить» сбоя — снова модель: строка придёт, когда она разложит.
                val res = if (r.result.status == Capture.Status.PENDING) poll(ref, "move") ?: r.result else r.result
                Outcome(result = res, ms = System.currentTimeMillis() - started, ref = ref)
            }
            null -> Outcome(result = view(ref), ref = ref)
        }
    }

    /** «Отменить последнее» (меню «Д», 10 минут): «Отменить» каждой записи захвата — тем же `capture.move`. */
    suspend fun cancelAll(r: Capture.Result): Outcome {
        val ops = Capture.cancelAll(r)
        if (ops.isEmpty()) return Outcome(result = r, ref = r.ref)
        store.enqueue(ops)
        val last = ops.lastOrNull()?.optString("op_id").orEmpty()
        val reply = push(last)
        pullSoon()
        return when (val x = Capture.reply(reply)) {
            is Capture.Reply.Ok -> Outcome(result = x.result, ref = r.ref)
            is Capture.Reply.Refused -> Outcome(refused = x.error, ref = r.ref)
            null -> Outcome(offline = store.isQueued(last), result = r, ref = r.ref)
        }
    }

    /** Итог по ref — вид `capture?ref=`; нет связи или вида — null. */
    suspend fun view(ref: String): Capture.Result? = withContext(Dispatchers.IO) {
        runCatching { Capture.parse(sync.rawView("capture?ref=" + URLEncoder.encode(ref, "UTF-8")).optJSONObject("capture")) }.getOrNull()
    }

    /** Ждать, пока модель разложит: вид раз в [Capture.POLL_MS], не дольше [Capture.POLL_MAX_MS]. */
    private suspend fun poll(ref: String, from: String, onPending: (Capture.Result) -> Unit = {}): Capture.Result? {
        val until = System.currentTimeMillis() + Capture.POLL_MAX_MS
        var last: Capture.Result? = null
        var misses = 0
        while (System.currentTimeMillis() < until) {
            delay(Capture.POLL_MS)
            val r = view(ref)
            if (r == null) {
                if (++misses > POLL_MISSES) break
                continue
            }
            misses = 0
            last = r
            if (r.status != Capture.Status.PENDING) return r
        }
        if (last?.status == Capture.Status.PENDING) {
            log("захват ($from): модель не разложила за ${Capture.POLL_MAX_MS / 1000} с — итог придёт уведомлением")
            scope.launch(Dispatchers.IO) { lateNotice(ref) }
        }
        return last
    }

    /**
     * Фраза ушла без связи: дождаться, пока очередь её отдаст, потом итога — и сказать тихим уведомлением (пилюля к
     * тому времени давно ушла, а «записал» должно стать «легло туда-то»). Живёт, пока жив процесс; сервер в любом
     * случае разложит сам.
     */
    private fun follow(opId: String, ref: String, from: String) {
        scope.launch(Dispatchers.IO) {
            withTimeoutOrNull(FOLLOW_MAX_MS) { store.queued.first { q -> q.none { it.opId == opId } } } ?: return@launch
            val reply = Capture.reply(store.captureReply(opId))
            val res = when (reply) {
                is Capture.Reply.Refused -> {
                    notice("Не разложил: ${reply.error}")
                    return@launch
                }
                is Capture.Reply.Ok -> if (reply.result.status == Capture.Status.PENDING) poll(ref, from) ?: reply.result else reply.result
                null -> poll(ref, from)
            } ?: return@launch
            if (res.status != Capture.Status.PENDING) {
                pullSoon()
                notice(Capture.line(res))
                log("захват ($from): связь появилась — «${Capture.line(res).take(80)}»")
            }
        }
    }

    /** Модель думает дольше полутора минут: спросить ещё раз через пару минут и сказать уведомлением. */
    private suspend fun lateNotice(ref: String) {
        repeat(4) {
            delay(LATE_STEP_MS)
            val r = view(ref) ?: return@repeat
            if (r.status != Capture.Status.PENDING) {
                pullSoon()
                notice(Capture.line(r))
                return
            }
        }
    }

    /** Новое дело или напоминание — в копию сразу (синк), чтобы «Сегодня» и будильник увидели его без пятиминутного тика. */
    private fun pullSoon() {
        scope.launch(Dispatchers.IO) { runCatching { sync.sync("захват", wait = true) } }
    }

    companion object {
        /** Сколько ждать правила мимо очереди: сам ответ — доли секунды, остальное — сеть через посредника. */
        const val RULE_WAIT_MS = 2_500L
        /** Сколько ждать ответа очереди молча; дольше — строка «Отправляю…». */
        const val PUSH_WAIT_MS = 2_500L
        /** Дольше этого ответа не ждём: «нет связи» (проход упал) или «строка придёт уведомлением» (медленно). */
        const val PUSH_MAX_MS = 6_000L
        private const val POLL_MISSES = 5
        private const val FOLLOW_MAX_MS = 12 * 3_600_000L
        private const val LATE_STEP_MS = 30_000L
    }
}
