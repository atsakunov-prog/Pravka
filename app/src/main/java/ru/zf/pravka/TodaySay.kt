package ru.zf.pravka

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DelaAsk
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.SayFork
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.Profile
import ru.zf.pravka.provider.sayFork
import ru.zf.pravka.trigger.finishDelaReason
import ru.zf.pravka.trigger.listenForDelaReason

// Строка «сказать» на «Сегодня» вида v2 (Правка 5.0, этап 2; план §4): одна на
// всё. Что сказано — решает Сонет (`SayFork`, промпт «Сегодня» во вкладке
// «Промпты»), дальше фразу разбирает дорога своего режима — те же движки, что
// у кнопок: лента (`ZasechkaEngine.record`), мысль к делу, еда (`FoodEngine`),
// дела (`RaznoskaEngine`), тело (`BodyEngine.hear`), деньги
// (`MoneyEngine.dictate`), вопрос — Claude Дел на сервере (`/api/ask`).
//
// Здесь, а не в слое поверх приложений: развилка «З» и её дороги живут в
// `trigger/`, их не трогаем; у приложения итог — строкой в самой строке
// «сказать», а не запиской на кнопке. Правило то же, что у развилки «З»
// (`docs/agreements.md`, «записал значит записано»): еда, дела и деньги уходят
// сразу, без плашки «ОК» — отменить можно во вкладке режима. Режим выключен в
// профиле — фраза ложится в ленту, а не пропадает.

/** Чем кончилась фраза: строка итога, куда её открыть, ответ Claude на вопрос; [ok] — записано. */
internal class SayOutcome(
    val kind: SayFork.Kind,
    val text: String,
    val ok: Boolean,
    val open: Tab? = null,
    val ask: DelaAsk.Result? = null,
    val askScope: org.json.JSONObject? = null,
    val said: String = "",
)

/**
 * Разобрать фразу [raw] строки «сказать»: развилка, потом дорога. [spoken] —
 * сказано голосом (источник записей — «voice», иначе «text»). [askScope] — что
 * видит Claude Дел, если это вопрос (дела «Сейчас» и «Сегодня» на экране).
 */
internal suspend fun PravkaApp.sayToday(raw: String, spoken: Boolean, askScope: org.json.JSONObject): SayOutcome {
    val text = raw.trim()
    val source = if (spoken) "voice" else "text"
    val route = sayRoute(text)
    eventLog.add("сегодня: → ${route.kind.word}: ${route.text}" + if (route.why.isNotBlank()) " (${route.why})" else "")
    val kind = when {
        route.kind == SayFork.Kind.FOOD && !profileStore.has(Profile.Mode.FOOD) -> SayFork.Kind.ENTRY
        route.kind == SayFork.Kind.TASKS && !profileStore.has(Profile.Mode.DELA) -> SayFork.Kind.ENTRY
        route.kind == SayFork.Kind.BODY && !profileStore.has(Profile.Mode.SPORT) -> SayFork.Kind.ENTRY
        route.kind == SayFork.Kind.MONEY && !profileStore.has(Profile.Mode.MONEY) -> SayFork.Kind.ENTRY
        route.kind == SayFork.Kind.ASK && !delaServer.value -> SayFork.Kind.ENTRY
        else -> route.kind
    }
    val said = if (kind == route.kind) route.text.ifBlank { text } else text
    return try {
        when (kind) {
            SayFork.Kind.ENTRY -> sayEntry(text, source)
            SayFork.Kind.COMMENT -> sayComment(said, text, source)
            SayFork.Kind.FOOD -> sayFood(said, source)
            SayFork.Kind.TASKS -> sayTasks(said)
            SayFork.Kind.BODY -> sayBody(said, source)
            SayFork.Kind.MONEY -> sayMoney(said)
            SayFork.Kind.ASK -> sayAsk(said, askScope)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Ошибку не затираем общей фразой: причина целиком — в строке и в журнале.
        eventLog.add("сегодня: ${kind.word} упало: ${e.javaClass.simpleName}: ${e.message}")
        SayOutcome(kind, "Не вышло (${kind.word}): ${e.message ?: e.javaClass.simpleName}", ok = false, said = text)
    }
}

/** Что за фраза: Сонет, а если он молчит или ответил не то — слова. */
private suspend fun PravkaApp.sayRoute(text: String): SayFork.Route {
    val open = runCatching { zasechkaStore.openEntry() }.getOrNull()
    val last = if (open == null) runCatching { zasechkaStore.all().filter { !it.open }.maxByOrNull { it.end } }.getOrNull() else null
    val reply = claudeProvider.sayFork(text, SayFork.context(System.currentTimeMillis(), open, last))
        .onFailure { e -> if (e is CancellationException) throw e }
    reply.getOrNull()?.let { r ->
        runCatching { stats.recordAux(r.costUsd, r.tokensIn, r.tokensOut, route = ModelRoute.ZASECHKA_FORK.key) }
        SayFork.fromModel(r.raw, text)?.let { return it }
        eventLog.add("сегодня: развилка — ответ Сонета не прочитался («${r.raw.take(120)}»), решают слова")
    } ?: eventLog.add("сегодня: развилка — Сонет не ответил (${reply.exceptionOrNull()?.message ?: "без причины"}), решают слова")
    return SayFork.words(text)
}

/** Лента: тот же разбор, что у «З» (Опус со всеми правилами времени). */
private suspend fun PravkaApp.sayEntry(text: String, source: String): SayOutcome {
    val o = zasechkaEngine.record(text, source)
    val e = o.entry
    val tail = listOf(e.category, e.client).filter { it.isNotBlank() }.joinToString(" · ")
    val line = when (o.action) {
        "none" -> "Не записал: ${o.say.ifBlank { "это не про ленту" }}"
        "stop" -> "Закрыто «${e.title}» · ${Fmt.range(e.start, e.end)}"
        "insert" -> "Вставлено «${e.title}» · ${Fmt.range(e.start, e.end)}" + if (tail.isNotBlank()) " · $tail" else ""
        "edit" -> "Поправлено «${e.title}»" + if (tail.isNotBlank()) " · $tail" else ""
        "delete" -> "Удалено «${e.title}»"
        else -> "Засечка: «${e.title}» с ${Fmt.hm(e.start)}" + if (tail.isNotBlank()) " · $tail" else ""
    }
    val error = o.error?.takeIf { it.isNotBlank() }
    return SayOutcome(SayFork.Kind.ENTRY, if (error != null) "$line · $error" else line, ok = o.action != "none" && error == null, open = Tab.ZASECHKA, said = text)
}

/** Мысль к идущему делу (или к последнему); ленты нет — фраза ложится в ленту. */
private suspend fun PravkaApp.sayComment(text: String, original: String, source: String): SayOutcome {
    val target = zasechkaStore.openEntry() ?: zasechkaStore.all().filter { !it.open }.maxByOrNull { it.end }
        ?: return sayEntry(original, source)
    val joined = if (target.comment.isBlank()) text else target.comment + "\n" + text
    if (!zasechkaStore.setComment(target.id, joined)) return SayOutcome(SayFork.Kind.COMMENT, "Не записал мысль: дела уже нет в ленте", ok = false, said = text)
    // Запись начата из дела — мысль ложится и в само дело (как у «З»).
    runCatching { mirrorZasechkaComment(target, target.comment, joined) }
    return SayOutcome(SayFork.Kind.COMMENT, "Мысль к «${target.title.ifBlank { "без названия" }}»", ok = true, open = Tab.ZASECHKA, said = text)
}

/** Еда: разбор «Е» и сразу в дневник. */
private suspend fun PravkaApp.sayFood(text: String, source: String): SayOutcome {
    val meal = foodEngine.parse(text, source = source).getOrElse { e ->
        // Не разобралось — слова ждут во вкладке Спорта, как у «Е».
        runCatching { strengthStore.addRaw(text, "food") }
        return SayOutcome(SayFork.Kind.FOOD, "Еду не разобрал: ${e.message ?: "без причины"} — сказанное сохранено", ok = false, open = Tab.FOOD, said = text)
    }.meal
    if (meal.items.isEmpty()) return SayOutcome(SayFork.Kind.FOOD, "Еды в сказанном не нашёл", ok = false, said = text)
    val done = foodEngine.confirm(meal.id).meal
        ?: return SayOutcome(SayFork.Kind.FOOD, "Разобрал, а в дневник не записал — приём ждёт во вкладке «Еда»", ok = false, open = Tab.FOOD, said = text)
    val what = if (done.supplement) "добавки" else "${Fmt.num(done.kcal)} ккал, белок ${done.protein} г"
    return SayOutcome(SayFork.Kind.FOOD, "Еда: ${done.shortList.take(60)} · $what", ok = true, open = Tab.FOOD, said = text)
}

/** Дела: разбор «Д» и сразу в Дела (или Todoist); дел нет, а заметки есть — в хронологию. */
private suspend fun PravkaApp.sayTasks(text: String): SayOutcome {
    val draft = raznoskaEngine.split(text).getOrElse { e ->
        return SayOutcome(SayFork.Kind.TASKS, "Дела не разобрал: ${e.message ?: "без причины"}", ok = false, open = Tab.TODOIST, said = text)
    }
    val tasks = draft.live
    if (tasks.isEmpty() && draft.noteItems.isNotEmpty() && delaOnServer()) {
        raznoskaEngine.send(draft.id)
        return SayOutcome(SayFork.Kind.TASKS, "Дел нет — заметки ушли в хронологию Дел", ok = true, open = Tab.TODOIST, said = text)
    }
    if (tasks.isEmpty()) return SayOutcome(SayFork.Kind.TASKS, "Дел в сказанном не нашёл", ok = false, said = text)
    val sent = raznoskaEngine.send(draft.id)
    val titles = tasks.take(2).joinToString(", ") { "«${it.content.take(40)}»" } + if (tasks.size > 2) " и ещё ${tasks.size - 2}" else ""
    return when {
        sent.ok -> SayOutcome(
            SayFork.Kind.TASKS,
            (if (sent.queued > 0) "В очереди Дел (нет связи): " else "В ${sent.to}: ") + titles,
            ok = true, open = Tab.TODOIST, said = text,
        )
        else -> SayOutcome(
            SayFork.Kind.TASKS,
            "Записал ${sent.created} из ${sent.created + sent.failed}: ${sent.error}",
            ok = false, open = Tab.TODOIST, said = text,
        )
    }
}

/** Тело: тот же роутер, что у «Т» и вкладки Спорта — подходы, зарядка, самочувствие. */
private suspend fun PravkaApp.sayBody(text: String, source: String): SayOutcome {
    val o = bodyEngine.hear(text, source).getOrElse { e ->
        return SayOutcome(SayFork.Kind.BODY, "Тело не разобрал: ${e.message ?: "без причины"} — сказанное во вкладке Спорта", ok = false, open = Tab.SPORT, said = text)
    }
    return SayOutcome(SayFork.Kind.BODY, o.headline(), ok = true, open = Tab.SPORT, said = text)
}

/** Деньги: разбор «₽» и сразу в журнал — «записал» значит записано; поправить — во вкладке Денег. */
private suspend fun PravkaApp.sayMoney(text: String): SayOutcome {
    val take = moneyEngine.dictate(text).getOrElse { e ->
        return SayOutcome(SayFork.Kind.MONEY, "Деньги не разобрал: ${e.message ?: "без причины"}", ok = false, open = Tab.MONEY, said = text)
    }
    val drafts = moneyEngine.draftsOf(take)
    if (drafts.isEmpty()) return SayOutcome(SayFork.Kind.MONEY, "Денег в сказанном не нашёл", ok = false, said = text)
    moneyEngine.confirm(take, drafts.map { it.id })
    val line = drafts.take(2).joinToString(", ") { d ->
        listOf(d.what.take(30), "${Fmt.rub(d.rubKop)} ₽").filter { it.isNotBlank() }.joinToString(" · ")
    } + if (drafts.size > 2) " и ещё ${drafts.size - 2}" else ""
    return SayOutcome(SayFork.Kind.MONEY, "Деньги: $line", ok = true, open = Tab.MONEY, said = text)
}

/** Вопрос — Claude Дел на сервере (`/api/ask`): ответ и что поменялось — листом разговора. */
private suspend fun PravkaApp.sayAsk(text: String, scope: org.json.JSONObject): SayOutcome {
    val r = delaSync.ask(text, scope).getOrElse { e ->
        return SayOutcome(SayFork.Kind.ASK, "Claude не ответил: ${e.message ?: "без причины"}", ok = false, said = text)
    }
    return SayOutcome(SayFork.Kind.ASK, r.reply.ifBlank { "Claude ответил без слов" }.take(120), ok = true, ask = r, askScope = scope, said = text)
}

// ---------------------------------------------------------------------------
// Строка на экране
// ---------------------------------------------------------------------------

/** Сколько итог стоит в строке, прежде чем она снова приглашает. */
private const val RESULT_MS = 8_000L

/**
 * Строка «сказать» «Сегодня» v2 (`.say` холста): поле, картинка из галереи и
 * снимок (это еда — путь распознавания по фото вкладки Еды), микрофон.
 * Микрофон — тот же движок и пилюля, что у «Д» (`listenForDelaReason`): нажал,
 * говоришь сколько угодно, нажал ещё раз — фраза ушла в развилку. Итог —
 * одной строкой в самой строке (тап — режим, куда легло), сбой — плашкой над
 * ней, текст возвращается в поле. Вопрос — листом разговора Claude Дел.
 */
@androidx.compose.runtime.Composable
internal fun TodaySayBarV2(app: PravkaApp, nav: TodayNav, data: TodayV2Data, modifier: androidx.compose.ui.Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var draft by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var out by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SayOutcome?>(null) }
    var listening by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var talk by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Talk?>(null) }
    val routeBusy = ru.zf.pravka.ui.rememberRouteBusy(app.liveWork, "zasechka", "zasechka_fork")
    androidx.compose.runtime.LaunchedEffect(out) {
        if (out?.ok == true) {
            kotlinx.coroutines.delay(RESULT_MS)
            out = null
        }
    }
    // Что видит Claude Дел, если это вопрос: дела «Сейчас» и «Сегодня» на экране.
    val askScope = {
        DelaAsk.scope("Сегодня", data.nowTasks.map { it.id } + data.todayRows.mapNotNull { it.taskId })
    }
    val run: (String, Boolean) -> Unit = run@{ text, spoken ->
        if (text.isBlank() || busy) return@run
        busy = true
        out = null
        app.appScope.launch {
            val o = runCatching { app.sayToday(text, spoken, askScope()) }
                .getOrElse { e -> SayOutcome(SayFork.Kind.ENTRY, "Не вышло: ${e.message ?: e.javaClass.simpleName}", ok = false, said = text) }
            busy = false
            val ask = o.ask
            if (ask != null && o.askScope != null) {
                talk = Talk(o.askScope, turns = listOf(DelaAsk.Turn(o.said, ask)))
            } else {
                out = o
                // Не записалось — сказанное не теряется: снова в поле.
                if (!o.ok && draft.isBlank()) draft = text
            }
        }
    }
    val wide = ru.zf.pravka.ui.twoPane()
    val o = out
    ru.zf.pravka.ui.SayBar(
        value = draft,
        onValueChange = { draft = it; if (out != null) out = null },
        placeholder = if (wide) "Засечка, еда, дело, тело или вопрос — Сонет сам поймёт, куда"
        else "Засечка, еда, дело или вопрос — разберу сам",
        onSend = {
            val t = draft.trim()
            draft = ""
            run(t, false)
        },
        onMic = {
            val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
            when {
                service == null -> ru.zf.pravka.ui.Feedback.toast(context, context.getString(R.string.toast_no_service))
                listening -> if (!service.finishDelaReason(keep = true)) listening = false
                else -> listening = service.listenForDelaReason("Говори — нажми ещё раз, разберу сам") { said ->
                    listening = false
                    val full = (draft.trim() + " " + said.trim()).trim()
                    draft = ""
                    run(full, true)
                }
            }
        },
        listening = listening,
        busy = busy || routeBusy,
        busyLabel = "Разбираю",
        maxLines = 3,
        result = o?.takeIf { it.ok }?.let { r -> ru.zf.pravka.ui.SayResult(r.text) { out = null; r.open?.let(nav.mode) } },
        error = o?.takeIf { !it.ok }?.text,
        onRetry = o?.takeIf { !it.ok }?.let { r -> { out = null; draft = ""; run(r.said, false) } },
        trailing = {
            ru.zf.pravka.ui.SayIcon(ru.zf.pravka.ui.Glyphs.Image, "картинка из галереи — еда по фото", { nav.foodAction("gallery") })
            ru.zf.pravka.ui.SayIcon(ru.zf.pravka.ui.Glyphs.Camera, "сфотографировать еду", { nav.foodAction("photo") })
        },
        modifier = modifier,
    )
    val tk = talk
    if (tk != null) {
        val snap = data.snap
        TalkSheet(
            talk = tk,
            snap = snap,
            onText = { v -> talk = talk?.copy(text = v) },
            onSend = { q ->
                val cur = talk ?: return@TalkSheet
                talk = cur.copy(busy = q, text = "", error = "")
                app.appScope.launch {
                    val r = app.delaSync.ask(q, DelaAsk.withHistory(cur.scope, cur.turns, snap))
                    val now = talk ?: cur
                    talk = r.fold(
                        { res -> now.copy(turns = now.turns + DelaAsk.Turn(q, res), busy = "", error = "") },
                        { e -> now.copy(busy = "", text = q, error = e.message.orEmpty().ifBlank { "Claude не ответил" }) },
                    )
                }
            },
            onDismiss = { talk = null },
            onOpen = { id -> talk = null; nav.delaTask(id) },
            onUndo = { i ->
                val cur = talk ?: return@TalkSheet
                val turn = cur.turns.getOrNull(i) ?: return@TalkSheet
                talk = cur.copy(turns = cur.turns.mapIndexed { k, t -> if (k == i) t.copy(undone = true) else t })
                app.appScope.launch {
                    // Отмена — обычными операциями очереди с op_id, как во вкладке Дел.
                    app.delaStore.enqueue(DelaAsk.undoOps(turn.result))
                    val sent = app.delaSync.pushNow(4_000L)
                    ru.zf.pravka.ui.Feedback.toast(app, if (sent) "Вернул как было" else "Вернул как было — уйдёт на сервер, когда будет связь")
                }
            },
        )
    }
}
