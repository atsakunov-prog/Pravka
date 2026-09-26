package ru.zf.pravka.trigger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.zf.pravka.R
import ru.zf.pravka.core.RaznoskaEngine
import ru.zf.pravka.core.ZasechkaIntent
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.Profile
import ru.zf.pravka.provider.zasechkaFork
import ru.zf.pravka.ui.Haptics

// Развилка Засечки (docs/zasechka.md, «Развилка»): сказанное в «З» — с пальца
// или с кнопки гарнитуры — не всегда дело в ленту. «Запиши мысль» ложится
// комментарием к текущему делу, «запиши еду» — в дневник Еды, «запиши дело»,
// «не забыть» — в Todoist. Что это за фраза, решает Сонет (дорога
// «Развилка» в Моделях, промпт — во вкладке «Промпты»), а без сети — слова
// `core/ZasechkaIntent.kt`; дороги —
// те же, что у кнопок «Е» и «Д», только без плашки: владелец сказал
// «записал», значит записано (подтверждения тапом с гарнитуры не бывает),
// а отмена живёт во вкладках. Итог — запиской на «З» и, если тейк позвала
// гарнитура, голосом.

/**
 * Развилка (26.09.2026): сначала Сонет за секунду решает, что это за фраза,
 * потом её разбирает своя дорога. Владелец: «он вообще не понимает, когда
 * писать в дела, когда в мысли… чтобы Сонет очень быстро просматривал фразу
 * и сначала говорил, что это такое… и дальше уже отдавал Опусу». Слова-команды
 * живую речь не ловили: «надо бы завтра позвонить Илье» — дело, хотя «запиши
 * дело» никто не сказал. Сонет не ответил или ответил не то — решают слова
 * (`ZasechkaIntent.route`): сказанное не теряется и не ждёт сети дважды.
 */
internal fun PravkaAccessibilityService.forkZasechka(text: String, source: String, spoken: Boolean) {
    zButton?.setBusy(true)
    scope.launch {
        val route = zasechkaRoute(text)
        if (!applyZasechkaRoute(route, text, spoken)) {
            recordZasechkaEntry(text, source)
        }
    }
}

/** Что это за фраза: Сонет, а если он молчит — слова. */
private suspend fun PravkaAccessibilityService.zasechkaRoute(text: String): ZasechkaIntent.Route {
    val reply = app.claudeProvider.zasechkaFork(text, forkContext())
        .onFailure { e -> if (e is CancellationException) throw e }
    reply.getOrNull()?.let { r ->
        runCatching {
            app.stats.recordAux(r.costUsd, r.tokensIn, r.tokensOut, route = ModelRoute.ZASECHKA_FORK.key)
        }
        ZasechkaIntent.fromModel(r.raw, text)?.let { route ->
            app.eventLog.add(
                "засечка: развилка (Сонет, ${r.latencyMs} мс) — ${word(route.kind)}" +
                    (if (route.why.isNotBlank()) ": ${route.why}" else "")
            )
            return route
        }
        app.eventLog.add("засечка: развилка — ответ Сонета не прочитался («${r.raw.take(120)}»), решают слова")
    } ?: app.eventLog.add(
        "засечка: развилка — Сонет не ответил (${reply.exceptionOrNull()?.message ?: "без причины"}), решают слова"
    )
    return ZasechkaIntent.route(text).also { app.eventLog.add("засечка: развилка по словам — ${word(it.kind)}") }
}

/** «Сейчас: 12:40. Идёт: «Созвон с Ильёй» с 12:05.» — Сонету, чтобы отличить мысль к делу от нового дела. */
private suspend fun PravkaAccessibilityService.forkContext(): String {
    val now = System.currentTimeMillis()
    val store = app.zasechkaStore
    val open = runCatching { store.openEntry() }.getOrNull()
    val going = when {
        open != null -> "Идёт: «${open.title.ifBlank { "без названия" }}» с ${zTime(open.start)}."
        else -> runCatching { store.all().filter { !it.open }.maxByOrNull { it.end } }.getOrNull()
            ?.let { "Сейчас ничего не идёт; последнее — «${it.title.ifBlank { "без названия" }}», закончилось в ${zTime(it.end)}." }
            ?: "Сейчас ничего не идёт."
    }
    return "Сейчас: ${zTime(now)}. $going"
}

private fun word(kind: ZasechkaIntent.Kind): String = when (kind) {
    ZasechkaIntent.Kind.ENTRY -> "лента"
    ZasechkaIntent.Kind.COMMENT -> "мысль"
    ZasechkaIntent.Kind.FOOD -> "еда"
    ZasechkaIntent.Kind.TASKS -> "дела"
}

/**
 * Отдать фразу её дороге. true — ушло не в ленту и разбор ленты не нужен;
 * false — обычная засечка (или режим, куда просится фраза, выключен в
 * профиле: тогда она честно ложится в ленту, а не пропадает).
 */
private fun PravkaAccessibilityService.applyZasechkaRoute(
    route: ZasechkaIntent.Route,
    original: String,
    spoken: Boolean,
): Boolean {
    when (route.kind) {
        ZasechkaIntent.Kind.ENTRY -> return false
        ZasechkaIntent.Kind.COMMENT -> Unit
        ZasechkaIntent.Kind.FOOD -> if (!app.profileStore.has(Profile.Mode.FOOD)) {
            app.eventLog.add("засечка: фраза про еду, а Еда выключена в профиле — в ленту")
            return false
        }
        ZasechkaIntent.Kind.TASKS -> if (!app.profileStore.has(Profile.Mode.DELA)) {
            app.eventLog.add("засечка: фраза про дела, а Дела выключены в профиле — в ленту")
            return false
        }
    }
    app.eventLog.add("засечка: → ${word(route.kind)}: ${route.text}")
    if (route.text.isBlank()) {
        // «Запиши мысль» — и тишина: записывать нечего, но и молчать нельзя.
        zButton?.setBusy(false)
        Haptics.error(this)
        zButton?.showNote("🤷 Команду услышал, а что записать — нет", ok = false, holdMs = 3_000)
        if (spoken) say("не расслышал, что записать")
        return true
    }
    when (route.kind) {
        ZasechkaIntent.Kind.COMMENT -> zasechkaThought(route.text, original, spoken)
        ZasechkaIntent.Kind.FOOD -> zasechkaFood(route.text, spoken)
        ZasechkaIntent.Kind.TASKS -> zasechkaTasks(route.text, spoken)
        ZasechkaIntent.Kind.ENTRY -> Unit
    }
    return true
}

/** Голос Правки: одна короткая фраза в гарнитуру. */
internal fun PravkaAccessibilityService.say(text: String) {
    runCatching { speakerLazy.value.say(text) }
}

/**
 * Мысль к текущему делу — той же дорогой, что «Записать мысль» из меню «З»
 * (чистка движком Правки, дописывается с новой строки). Текущее — открытое;
 * ничего не идёт — последнее закончившееся. Лента пуста совсем — мысли лечь
 * некуда, и сказанное ложится в ленту целиком: сырая надиктовка не
 * теряется никогда.
 */
private fun PravkaAccessibilityService.zasechkaThought(text: String, original: String, spoken: Boolean) {
    scope.launch {
        val store = app.zasechkaStore
        val target = runCatching {
            store.openEntry() ?: store.all().filter { !it.open }.maxByOrNull { it.end }
        }.getOrNull()
        if (target == null) {
            app.eventLog.add("засечка: мысли некуда лечь — лента пуста, пишу в ленту")
            onZasechkaText(original, route = false)
            return@launch
        }
        zCommentFor = target.id
        onZasechkaCommentText(text, spoken = spoken)
    }
}

/**
 * Еда: тот же разбор, что у «Е», и сразу в дневник — «записал еду» должно
 * быть правдой. Не разобралось — слова ждут во вкладке Спорта, как у «Е»;
 * разобралось, но не записалось — приём ждёт на вкладке «Еда». Отменить —
 * там же.
 */
private fun PravkaAccessibilityService.zasechkaFood(text: String, spoken: Boolean) {
    scope.launch {
        val parsed = runCatching { app.foodEngine.parse(text, source = "voice") }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        val meal = parsed.getOrElse { e ->
            runCatching { app.strengthStore.addRaw(text, "food") }
            zButton?.setBusy(false)
            Haptics.error(this@zasechkaFood)
            zButton?.showNote(
                "🍽 Еду не разобрал: ${e.message ?: "без причины"}\nсказанное сохранено",
                ok = false,
                holdMs = 5_000,
            )
            if (spoken) say("еду не записал")
            return@launch
        }.meal
        if (meal.items.isEmpty()) {
            zButton?.setBusy(false)
            Haptics.error(this@zasechkaFood)
            zButton?.showNote("🍽 Еды в сказанном не нашёл", ok = false, holdMs = 3_000)
            if (spoken) say("еду не нашёл")
            return@launch
        }
        val outcome = runCatching { app.foodEngine.confirm(meal.id) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                app.eventLog.add("засечка → еда: confirm бросил ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        zButton?.setBusy(false)
        val done = outcome?.meal
        if (done == null) {
            Haptics.error(this@zasechkaFood)
            zButton?.showNote(
                "🍽 Разобрал, а в дневник не записал — приём ждёт во вкладке «Еда»",
                ok = false,
                holdMs = 5_000,
            )
            if (spoken) say("еду не записал")
            return@launch
        }
        Haptics.success(this@zasechkaFood)
        // Итог — в пилюле «З», как у «Е»: что записано, по тапу — позиции с
        // карандашом (приём в редакторе «Еды») и «Отменить».
        zButton?.showResult(
            summary = done.shortList + "\n" +
                (if (done.supplement) "добавки в дневнике" else "${done.kcal} ккал — в дневнике") +
                (if (outcome.ribbon.isNotBlank()) " · к «${outcome.ribbon}»" else ""),
            rows = done.items.map { item ->
                DictationPill.ResultRow(
                    title = item.name,
                    meta = foodItemMeta(item),
                    onEdit = { openFoodTab("edit:${done.id}") },
                )
            },
            footer = foodDayLine(done),
            action = DictationPill.ResultAction("Отменить") { forgetFood(done.id) },
            onOpen = { openFoodTab() },
        )
        if (spoken) say(ZasechkaIntent.said(ZasechkaIntent.Kind.FOOD))
    }
}

/**
 * Дела: тот же разбор, что у «Д», и сразу в Todoist — без плашки «ОК»:
 * «записал дела» должно быть правдой, а тапнуть с гарнитуры нечем. Отмена —
 * «Отменить отправку» в меню «Д» и вкладка «Дела». Не ушло — дела ждут там
 * же, причина целиком на записке.
 */
private fun PravkaAccessibilityService.zasechkaTasks(text: String, spoken: Boolean) {
    scope.launch {
        val split = runCatching { app.raznoskaEngine.split(text) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        val draft = split.getOrElse { e ->
            zButton?.setBusy(false)
            Haptics.error(this@zasechkaTasks)
            zButton?.showNote(
                "Дела не разобрал: ${e.message ?: getString(R.string.r_split_failed)}",
                ok = false,
                holdMs = 5_000,
            )
            if (spoken) say("дела не записал")
            return@launch
        }
        val tasks = draft.live
        if (tasks.isEmpty()) {
            zButton?.setBusy(false)
            Haptics.error(this@zasechkaTasks)
            zButton?.showNote(
                if (draft.notes.isBlank()) "Дел в сказанном не нашёл"
                else "Дел нет — записал в заметки «Дел»",
                ok = false,
                holdMs = 3_000,
            )
            if (spoken) say("дел не нашёл")
            return@launch
        }
        val sent = runCatching { app.raznoskaEngine.send(draft.id) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                RaznoskaEngine.SendOutcome(0, tasks.size, e.message ?: "не отправилось")
            }
        zButton?.setBusy(false)
        val titles = tasks.take(2).joinToString("\n") { "· " + it.content }
        when {
            sent.ok -> {
                Haptics.success(this@zasechkaTasks)
                zButton?.showResult(
                    summary = "В Todoist: ${raznCount(sent.created)}\n$titles",
                    rows = tasks.map { task ->
                        DictationPill.ResultRow(
                            title = task.content,
                            meta = raznMeta(task),
                            onEdit = { openTodoistTab() },
                        )
                    },
                    action = DictationPill.ResultAction("Отменить") { undoRaznoska() },
                    onOpen = { openTodoistTab() },
                )
                if (spoken) say(ZasechkaIntent.said(ZasechkaIntent.Kind.TASKS, count = sent.created))
            }
            sent.created > 0 -> {
                Haptics.error(this@zasechkaTasks)
                zButton?.showNote(
                    "Записал ${sent.created} из ${sent.created + sent.failed}: ${sent.error}\n" +
                        "остальные ждут во вкладке «Дела»",
                    ok = false,
                    holdMs = 5_000,
                )
                if (spoken) say("записал не все дела")
            }
            else -> {
                Haptics.error(this@zasechkaTasks)
                zButton?.showNote(
                    "Дела не ушли (${sent.error.ifBlank { "без причины" }})\nждут во вкладке «Дела»",
                    ok = false,
                    holdMs = 5_000,
                )
                if (spoken) say("дела не ушли")
            }
        }
    }
}
