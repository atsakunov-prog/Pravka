package ru.zf.pravka.core

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.MoneyStore
import ru.zf.pravka.data.PhoneStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.ZasechkaStore

/**
 * Записи приложения — в события архива на домашнем компе (docs/arkhiv.md).
 *
 * Форма каждого вида — контракт с сервером: `server/contract/batch.json`, по
 * нему построены виды схемы life, и тест `ArchiveEventsTest` сверяет с ним
 * ключи. Разошлись — сервер молча покажет пустые колонки, поэтому любое
 * новое поле здесь — сначала в контракт.
 *
 * Правила формы (их держит и сервер):
 *  * время — ISO 8601 с поясом телефона (`2026-09-07T14:59:00.000+03:00`):
 *    местное «ЧЧ:ММ» сервер берёт из самой строки, а сутки — из поля `day`,
 *    которое решает телефон тем же правилом, что и вкладки;
 *  * деньги — целые копейки, единицы — в именах полей (`protein_g`);
 *  * что идёт в счёт, решает телефон: `live` у денег, `confirmed` у еды,
 *    минуты суток ленты (`budgetMinutes`, сумма за сутки 1440).
 *
 * Файл без Android: всё на вход приходит данными, тесты гоняют его на JVM.
 */
object ArchiveEvents {

    /** Одна порция архива: вид, ключ внутри вида и снимок записи целиком. */
    data class Item(val kind: String, val key: String, val data: JSONObject) {
        val hash: String by lazy { hashOf(data) }
    }

    class Clock(val zone: TimeZone = TimeZone.getDefault()) {
        private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = zone }
        private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        fun iso(ms: Long): String = synchronized(iso) { iso.format(Date(ms)) }
        fun day(ms: Long): String = synchronized(dayFmt) { dayFmt.format(Date(ms)) }

        /** Полночь суток, в которые попало [ms], — в поясе телефона. */
        fun dayStart(ms: Long): Long = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = ms
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Отпечаток снимка: тот же снимок не отправляется второй раз. JSONObject
     * из Android не обещает порядка ключей, поэтому отпечаток — от
     * упорядоченной записи, а не от toString().
     */
    fun hashOf(o: Any?): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(canonical(o).toByteArray(Charsets.UTF_8))
        return md.digest().take(10).joinToString("") { "%02x".format(it) }
    }

    private fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        is Double, is Float -> JSONObject.numberToString(v as Number)
        else -> v.toString()
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        for ((k, v) in pairs) put(k, v ?: JSONObject.NULL)
    }

    private fun arr(items: Iterable<Any?>): JSONArray = JSONArray().apply { items.forEach { put(it ?: JSONObject.NULL) } }

    private fun round1(x: Double): Double = Math.round(x * 10.0) / 10.0

    // ------------------------------------------------------------------ Засечка

    /**
     * Лента — СУТКАМИ целиком, а не записью: номера записей ленты не вечные
     * (разрез по полуночи и вокруг сна рождает новые куски, заполнители
     * пересоздаются на каждом проходе). Сервер заменяет сутки целиком — ему
     * всё равно, какой номер у куска.
     *
     * `minutes` — сколько минут суток кусок занимает СВЕРХ более ранних:
     * куски идут по началу, нахлёст с уже посчитанным второй раз не
     * считается. Минута — та же разность минут суток, что у
     * `ZasechkaStore.budgetMinutes`, но та считает кусок в одиночку, и
     * первая заливка (01.10.2026) показала, что сумма 1440 держится только у
     * кусков встык: секундные нахлёсты на стыках давали +1…+4 минуты, дубль
     * тренировки с часов 22.09 — 1497 минут в сутках. Начало и конец уходят
     * как есть — нахлёст в них виден.
     */
    fun zasechkaDays(entries: List<ZasechkaStore.Entry>, clock: Clock, now: Long): List<Item> =
        entries.groupBy { clock.day(it.start) }.toSortedMap().map { (day, list) ->
            val base = clock.dayStart(list.first().start)
            fun minuteOf(ms: Long): Long = (ms - base + 30_000L) / 60_000L
            var covered = Long.MIN_VALUE
            // Одно начало — длинный кусок первым: дубль покороче уходит в ноль
            // целиком; у одинаковых — минуты у заведённого раньше.
            val ordered = list.sortedWith(
                compareBy<ZasechkaStore.Entry> { it.start }.thenByDescending { if (it.open) now else it.end }
                    .thenBy { it.id }
            )
            Item("zasechka.day", day, obj(
                "day" to day,
                "entries" to arr(ordered.map { e ->
                    val from = maxOf(e.start, covered)
                    val to = maxOf(if (e.open) now else e.end, covered)
                    covered = to
                    val minutes = minuteOf(to) - minuteOf(from)
                    obj(
                        "id" to e.id,
                        "start" to clock.iso(e.start),
                        "end" to if (e.open) null else clock.iso(e.end),
                        "minutes" to if (e.open) null else minutes,
                        "title" to e.title,
                        "category" to e.category,
                        "client" to e.client,
                        "source" to e.source,
                        "raw" to e.raw,
                        "comment" to e.comment,
                        "pomodoros" to e.pomodoros,
                        "rating" to e.useful,
                        "created" to clock.iso(e.createdAt),
                    ).apply {
                        // Связь с Делами (контракт архива, 03.10.2026): только у
                        // записей из дела — у остальных отпечаток суток прежний,
                        // и архиву не приходится принимать всю историю заново.
                        if (e.task.isNotBlank()) put("task", e.task)
                        if (e.project.isNotBlank()) put("project", e.project)
                    }
                }),
            ))
        }

    fun zasechkaReference(categories: List<ZasechkaStore.Category>, clients: List<String>): Item =
        Item("zasechka.reference", "all", obj(
            "categories" to arr(categories.mapIndexed { i, c ->
                obj("name" to c.name, "hint" to c.hint, "base_min" to c.baseMin, "value" to c.value, "ord" to i + 1)
            }),
            "clients" to arr(clients),
        ))

    // ------------------------------------------------------------------ Телефон

    fun phoneDay(date: String, d: PhoneStore.Day, labels: Map<String, String>): Item =
        Item("phone.day", date, obj(
            "day" to date,
            "screen_min" to (d.screenMs + 30_000L) / 60_000L,
            "pickups" to d.pickups,
            "glances" to d.glances,
            "calls_min" to (d.callsMs + 30_000L) / 60_000L,
            "calls" to d.calls,
            "backfilled" to d.backfilled,
            "apps" to arr(d.apps.entries.sortedByDescending { it.value }.map { (pkg, ms) ->
                obj(
                    "pkg" to pkg,
                    "label" to (labels[pkg] ?: pkg),
                    "min" to round1(ms / 60_000.0),
                    "sessions" to (d.appSessions[pkg] ?: 0),
                    "glances" to (d.glanceApps[pkg] ?: 0),
                )
            }),
            "sites" to arr(d.sites.entries.sortedByDescending { it.value }.map { (domain, ms) ->
                obj("domain" to domain, "min" to round1(ms / 60_000.0))
            }),
            "callers" to arr(d.callers.entries.sortedByDescending { it.value }.map { (who, ms) ->
                obj("who" to who, "min" to round1(ms / 60_000.0))
            }),
        ))

    // ------------------------------------------------------------------ Еда

    fun meal(m: FoodStore.Meal, clock: Clock): Item =
        Item("food.meal", "f${m.id}", obj(
            "id" to m.id,
            "day" to clock.day(m.ts),
            "at" to clock.iso(m.ts),
            "created" to clock.iso(m.createdAt),
            "kind" to m.kind,
            "source" to m.source,
            "confirmed" to m.confirmed,
            "raw" to m.raw,
            "note" to m.note,
            "photo" to m.photo,
            "model" to m.model,
            "cost_usd" to m.costUsd,
            "items" to arr(m.items.map { i ->
                obj(
                    "name" to i.name,
                    "grams" to i.grams,
                    "kcal" to i.kcal,
                    "protein_g" to i.protein,
                    "fat_g" to i.fat,
                    "carbs_g" to i.carbs,
                    "fiber_g" to i.fiber,
                    "sureness" to i.sureness,
                    "pill" to i.pill,
                    "micro" to JSONObject().apply { for ((k, v) in i.micro.toSortedMap()) put(k, v) },
                )
            }),
            "totals" to obj(
                "kcal" to m.kcal, "protein_g" to m.protein, "fat_g" to m.fat,
                "carbs_g" to m.carbs, "fiber_g" to m.fiber, "grams" to m.grams,
            ),
        ))

    fun norms(all: List<Micronutrients.Nutrient>): Item =
        Item("food.norms", "all", obj(
            "substances" to arr(all.mapIndexed { i, n ->
                obj(
                    "id" to n.id, "name" to n.name, "group" to n.group, "unit" to n.unit,
                    "norm" to n.norm, "upper" to n.ceiling, "why" to n.why, "where" to n.source, "ord" to i + 1,
                )
            }),
        ))

    // ------------------------------------------------------------------ Силовые и зарядка

    fun session(s: StrengthStore.Session): Item =
        Item("strength.session", "s${s.id}", obj(
            "id" to s.id,
            "day" to s.date,
            "block" to s.block,
            "title" to s.title,
            "minutes" to s.minutes,
            "feel" to s.feel,
            "rpe" to s.rpe,
            "note" to s.note,
            "done" to s.done,
            "checked" to arr(s.checkedIds),
            "icu_activity_id" to s.icuActivityId,
            "raw_ids" to arr(s.rawIds),
            "exercises" to arr(s.exercises.map { e ->
                obj(
                    "exercise_id" to e.exerciseId,
                    "name" to e.name,
                    "unit" to e.unit,
                    "note" to e.note,
                    "sets" to arr(e.rows.map { r -> obj("amount" to r.amount, "weight_kg" to r.weightKg, "note" to r.note) }),
                )
            }),
        ))

    fun gtg(g: StrengthStore.GtgDay): Item =
        Item("strength.gtg", g.date, obj(
            "day" to g.date,
            "status" to g.status(),
            "charged" to g.charged,
            "hang_s" to g.hangSec,
            "negatives" to g.negatives,
            "scapular" to g.scapular,
            "pullups" to g.pullups,
            "knee" to g.knee,
            "feel" to g.feel,
            "note" to g.note,
            "items" to arr(g.items.map { i ->
                obj("id" to i.id, "name" to i.name, "plan" to i.plan, "status" to i.status, "fact" to i.fact, "note" to i.note)
            }),
        ))

    fun strengthTake(t: StrengthStore.RawTake, clock: Clock): Item =
        Item("strength.take", t.id.toString(), obj(
            "id" to t.id,
            "day" to clock.day(t.ts),
            "at" to clock.iso(t.ts),
            "text" to t.text,
            "kind" to t.kind,
            "source" to t.source,
            // Во что фраза превратилась: еда — приём, остальное — тренировка.
            "consumed_by" to when {
                t.consumedBy == 0L -> ""
                t.kind == "food" -> "f${t.consumedBy}"
                else -> "s${t.consumedBy}"
            },
            "error" to t.error,
        ))

    // ------------------------------------------------------------------ Деньги

    /**
     * Все записи журнала: черновик до «ОК» — ещё не факт, уйдёт, когда станет
     * записью. Счёт каждой (`MoneyCashflow.places`) считается один раз на проход.
     */
    fun moneyEntries(entries: List<MoneyEntry>, clock: Clock): List<Item> {
        val places = MoneyCashflow.places(entries)
        return entries.filter { !it.draft }.map { moneyEntry(it, clock, places[it.id]) }
    }

    /**
     * Операция со всеми полями. [place] — где она стоит в балансе телефона
     * (05.10.2026): `balance_account` — счёт, `roundup_from` — у округления в
     * копилку счёт покупки, отдавший деньги. Сервер «Деньги» считает остатки
     * этим счётом, а не угадывает его по карте.
     */
    fun moneyEntry(e: MoneyEntry, clock: Clock, place: MoneyCashflow.Place? = null): Item =
        Item("money.entry", e.id, obj(
            "id" to e.id,
            "owner" to e.owner,
            "source" to e.source.key,
            "day" to clock.day(e.ts),
            "at" to clock.iso(e.ts),
            "time_known" to e.timeKnown,
            "amount_kop" to e.rubKop,
            "orig_minor" to e.origMinor,
            "currency" to e.currency,
            "rub_basis" to e.rubBasis.key,
            "what" to e.what,
            "note" to e.note,
            "mcc" to e.mcc,
            "bank_category" to e.bankCategory,
            "account" to e.account,
            "category" to e.category,
            "for_whom" to e.who,
            "category_by" to e.categoryBy.key,
            "draft" to e.draft,
            "dropped" to e.dropped,
            "live" to e.live(),
            "match_id" to e.matchId,
            "replaced_by" to e.replacedBy,
            "take_id" to if (e.takeId == 0L) "" else e.takeId.toString(),
            "balance_account" to place?.account,
            "roundup_from" to place?.roundupFrom,
        ))

    /**
     * Справочники Денег одной записью. Кроме категорий и правил (05.10.2026,
     * чтобы сервер «Деньги» видел то же, что телефон):
     *  * `anchors` — якоря остатков, которые знает баланс телефона ([anchors] —
     *    `MoneyEngine.anchors`): заводской снимок и вписанные. «Доступно» из
     *    пушей едет в записи самого пуша (`moneyPushes`): запись «all» шлётся
     *    целиком на каждую правку, и якорь каждого пуша здесь переотправлял бы
     *    её по пятнадцать раз в день. Вид `life.money_balances` собирает оба;
     *  * `accounts` — реестр счетов баланса (`MoneyCashflow.registry`);
     *  * `statements` — что покрывает каждая загруженная выписка: банк, счёт,
     *    дни, строки, когда загружена. Сервер по нему отличает «операций не
     *    было» от «выписки за эти дни нет».
     */
    fun moneyReference(
        s: MoneyStore.State,
        clock: Clock,
        anchors: List<MoneyCashflow.Anchor> = s.balances,
        zfAccounts: Set<String> = s.zfAccounts,
        owner: String = "",
    ): Item =
        Item("money.reference", "all", obj(
            "categories" to arr(MoneyCategories.ALL.map { c ->
                obj("key" to c.key, "title" to c.title, "group" to c.group, "shelf" to c.shelf.name.lowercase(), "income" to c.income)
            }),
            "rules" to arr(s.rules.map { r ->
                obj(
                    "pattern" to r.pattern, "category" to r.category, "for_whom" to r.who, "sign" to r.sign,
                    "owner" to r.owner, "comment" to r.comment, "source" to r.source, "mcc" to r.mcc, "amount_kop" to r.amountKop,
                )
            }),
            "anchors" to arr(
                anchors.filter { it.origin != MoneyCashflow.Origin.PUSH }
                    .sortedWith(compareBy({ it.account }, { it.ts }, { it.origin.ordinal }, { it.kop }))
                    .map { anchorJson(it, clock, emptyMap()) }
            ),
            "accounts" to arr(MoneyCashflow.registry(s.entries, anchors, zfAccounts, owner).map { a ->
                obj(
                    "name" to a.name, "side" to a.side, "kind" to a.kind, "currency" to a.currency,
                    "cards" to arr(a.cards), "owner" to a.owner,
                )
            }),
            "statements" to arr(statements(s.imports, s.entries, clock)),
            "zf_accounts" to arr(s.zfAccounts.sorted()),
            "not_zf_accounts" to arr(s.notZfAccounts.sorted()),
        ))

    /**
     * Якорь одной формы для справочника и для пуша. `covers` дополняется
     * строкой выписки, которая заменила пуш ([replacedBy]): в «Доступно» уже
     * вошла именно эта операция, и сервер не прибавит её второй раз.
     */
    private fun anchorJson(a: MoneyCashflow.Anchor, clock: Clock, replacedBy: Map<String, String>): JSONObject = obj(
        "account" to a.account, "at" to clock.iso(a.ts), "kop" to a.kop, "source" to a.origin.key,
        "covers" to arr((a.covers + a.covers.mapNotNull { replacedBy[it] }).sorted()), "note" to a.source,
    )

    /** Банк загрузки по её виду (`MoneyStore.Import.kind`) и чьи строки она приносит. */
    private val STATEMENT_BANKS = mapOf(
        "Тиньков" to ("Т-Банк" to MoneyEntry.Source.TINKOFF),
        "Альфа" to ("Альфа" to MoneyEntry.Source.ALFA),
        "МКБ" to ("МКБ" to MoneyEntry.Source.MKB),
        "ЗФ" to ("Т-Бизнес" to MoneyEntry.Source.TBIZ),
    )

    /**
     * Что покрывает каждая загруженная выписка — по журналу загрузок стора
     * (`MoneyStore.imports`): он ведётся с первой выписки, а сырьё в
     * `imports/` — только с 25.09.2026, и без журнала пропали бы самые
     * большие выгрузки. Выгрузка Т-Банка «все карты» несёт несколько счетов —
     * строка на каждый: счёт по строкам журнала в днях выписки
     * (`accountOf`), его дни — от первой до последней его строки там, строк —
     * сколько их у счёта в эти дни.
     */
    private fun statements(imports: List<MoneyStore.Import>, entries: List<MoneyEntry>, clock: Clock): List<JSONObject> {
        val cards = MoneyCashflow.cardMap(entries)
        val bySource = entries.filter { it.fromBank }.groupBy { it.source }
        val out = ArrayList<JSONObject>()
        for (i in imports.sortedBy { it.ts }) {
            if (i.fromTs <= 0L || i.toTs < i.fromTs) continue
            val (bank, source) = STATEMENT_BANKS[i.kind] ?: continue
            val rows = bySource[source].orEmpty().filter { it.ts in i.fromTs..i.toTs }
            val byAccount = rows.groupBy { MoneyCashflow.accountOf(it, cards) ?: bank }
            if (byAccount.isEmpty()) {
                out += obj(
                    "bank" to bank, "account" to null, "from" to clock.day(i.fromTs), "to" to clock.day(i.toTs),
                    "rows" to i.rows, "loaded_at" to clock.iso(i.ts),
                )
                continue
            }
            for ((account, list) in byAccount.toSortedMap()) {
                out += obj(
                    "bank" to bank, "account" to account,
                    "from" to clock.day(list.minOf { it.ts }), "to" to clock.day(list.maxOf { it.ts }),
                    "rows" to list.size, "loaded_at" to clock.iso(i.ts),
                )
            }
        }
        return out
    }

    /** У надиктовки денег номер — время в миллисекундах; другого времени у неё нет. */
    fun moneyTake(t: MoneyStore.Take, clock: Clock): Item {
        val at = if (t.id > 1_000_000_000_000L) t.id else 0L
        return Item("money.take", t.id.toString(), obj(
            "id" to t.id,
            "day" to if (at > 0) clock.day(at) else null,
            "at" to if (at > 0) clock.iso(at) else null,
            "text" to t.text,
            "model" to t.model,
            "cost_usd" to t.costUsd,
        ))
    }

    /**
     * Пойманные пуши. У пуша Т-Банка с «Доступно» и узнанной картой — его
     * якорь остатка ([anchors] — `MoneyEngine.anchors`, пушевые находят свой
     * пуш по `covers`). Якорь живёт в записи пуша, а не в справочнике: пуш
     * пишется один раз и меняется, только когда его заменила строка выписки.
     */
    fun moneyPushes(pushes: List<MoneyStore.Push>, anchors: List<MoneyCashflow.Anchor>, entries: List<MoneyEntry>, clock: Clock): List<Item> {
        val byPush = anchors.filter { it.origin == MoneyCashflow.Origin.PUSH }
            .flatMap { a -> a.covers.map { it to a } }.toMap()
        val replacedBy = entries.filter { it.replacedBy.isNotEmpty() }.associate { it.id to it.replacedBy }
        return pushes.map { p -> moneyPush(p, clock, byPush["push-" + p.key], replacedBy) }
    }

    fun moneyPush(
        p: MoneyStore.Push,
        clock: Clock,
        anchor: MoneyCashflow.Anchor? = null,
        replacedBy: Map<String, String> = emptyMap(),
    ): Item =
        Item("money.push", p.key, obj(
            "day" to clock.day(p.ts),
            "at" to clock.iso(p.ts),
            "pkg" to p.pkg,
            "title" to p.title,
            "text" to p.text,
            "result" to p.result,
            "anchor" to anchor?.let { anchorJson(it, clock, replacedBy) },
        ))

    // ------------------------------------------------------------------ Тренер

    fun talk(t: SportStore.Talk, clock: Clock): Item =
        Item("sport.talk", t.id.toString(), obj(
            "id" to t.id,
            "day" to clock.day(t.ts),
            "at" to clock.iso(t.ts),
            "question" to t.question,
            "answer" to t.answer,
            "cost_usd" to t.costUsd,
            "error" to t.error,
        ))

    // ------------------------------------------------------------------ Правка

    private val logTs = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US) }

    /** Время строки журнала Правки («2026-09-07T15:46:40+03:00») в мс; не разобралось — 0. */
    fun logMillis(ts: String): Long = runCatching { logTs.get()!!.parse(ts)?.time ?: 0L }.getOrDefault(0L)

    /**
     * Ключ строки журнала: время с точностью до секунды — не ключ (чистка и
     * обучение пишутся в одну секунду), поэтому к нему — отпечаток строки.
     */
    private fun logKey(ms: Long, line: JSONObject): String = "$ms-${hashOf(line).take(8)}"

    /** Одна диктовка из `transcriptions.jsonl`: распознанный текст до чистки. */
    fun pravkaTake(line: JSONObject, clock: Clock): Item? {
        val ms = logMillis(line.optString("ts"))
        if (ms == 0L) return null
        return Item("pravka.take", logKey(ms, line), obj(
            "id" to ms,
            "day" to clock.day(ms),
            "at" to clock.iso(ms),
            "engine" to line.optString("engine"),
            "audio_ms" to line.optLong("audio_ms"),
            "text" to line.optString("text"),
            "ok" to line.optBoolean("ok", true),
            "error" to line.optString("error"),
            "audio" to line.optString("audio"),
            "mic" to line.optString("mic"),
        ))
    }

    /** Одна чистка из `history.jsonl`: что ушло модели и что она вернула. */
    fun pravkaClean(line: JSONObject, clock: Clock): Item? {
        val ms = logMillis(line.optString("ts"))
        if (ms == 0L) return null
        return Item("pravka.clean", logKey(ms, line), obj(
            "id" to ms,
            "day" to clock.day(ms),
            "at" to clock.iso(ms),
            "mode" to line.optString("mode"),
            "model" to line.optString("model"),
            "input" to line.optString("input"),
            "output" to line.optString("output"),
            "latency_ms" to line.optLong("latency_ms"),
            "cost_usd" to line.optDouble("cost_usd", 0.0),
            "error" to line.optString("error"),
        ))
    }

    fun correction(id: Long, ts: Long, pkg: String, dictated: String, cleaned: String, edited: String, result: String, clock: Clock): Item =
        Item("pravka.correction", id.toString(), obj(
            "id" to id,
            "day" to clock.day(ts),
            "at" to clock.iso(ts),
            "said" to dictated,
            "model" to cleaned,
            "final" to edited,
            "app" to pkg,
            "result" to result,
        ))
}
