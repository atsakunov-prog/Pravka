package ru.zf.pravka.core

import org.json.JSONObject
import ru.zf.pravka.data.FoodStore

/**
 * Еда — правда на сервере, телефон — одно из окон (13.10.2026, docs/eda-server.md).
 *
 * Владелец: «Я хочу, чтобы правда была на сервере. А добавляю я спокойно из
 * телефона или из компьютера». Приём, записанный службой «Еда» на домашнем
 * компе, лежит в архиве записью `food.meal`; телефон забирает его `POST /sync`
 * (`ArchiveSync.Merge`) и кладёт в дневник тем же путём, что свой.
 *
 * Здесь — то, что не знает ни про сеть, ни про диск: приём из формата архива
 * (обратное [ArchiveEvents.meal]) и решение, что из пришедшего класть.
 * Главное правило — **своя неотправленная правка сильнее**: если снимок приёма
 * на телефоне не совпадает с квитанцией (правили, ещё не ушло), чужой снимок не
 * кладётся — телефон отправит своё следом, и у сервера победит более позднее.
 * Иначе — кладётся, и в квитанцию идёт отпечаток положенного: телефон не
 * перешлёт полученное как своё.
 */
object FoodSync {

    const val KIND = "food.meal"

    /** Что делать с забранным: положить, убрать, отпечатки в квитанции и почему что-то не легло. */
    data class Plan(
        val put: List<FoodStore.Meal> = emptyList(),
        val gone: List<Long> = emptyList(),
        /** Ключ записи → отпечаток своего снимка после забора ([ArchiveEvents.GONE] — записи нет). */
        val sent: Map<String, String> = emptyMap(),
        /** Чужое не легло: своя правка ещё не ушла. */
        val kept: List<String> = emptyList(),
        /** Запись не читается — словами, для журнала. */
        val broken: List<String> = emptyList(),
    ) {
        /** Сколько поменялось в дневнике. */
        val changed: Int get() = put.size + gone.size
    }

    /**
     * Что из [records] (`{kind, key, op, data?}` ответа `/sync`) класть в дневник.
     * [local] — приёмы телефона по id, [sent] — квитанция записи по ключу
     * (отпечаток отправленного, [ArchiveEvents.GONE] — отправлено удаление, null —
     * не отправлялось: новый приём или квитанции стёрты «Отправить всё заново»;
     * тогда правда — сервер).
     */
    fun plan(
        records: List<JSONObject>,
        local: Map<Long, FoodStore.Meal>,
        sent: (String) -> String?,
        clock: ArchiveEvents.Clock,
    ): Plan {
        val put = LinkedHashMap<Long, FoodStore.Meal>()
        val gone = LinkedHashSet<Long>()
        val ledger = LinkedHashMap<String, String>()
        val kept = mutableListOf<String>()
        val broken = mutableListOf<String>()
        for (r in records) {
            if (r.optString("kind") != KIND) continue
            val key = r.optString("key")
            val id = key.removePrefix("f").toLongOrNull()
            if (!key.startsWith("f") || id == null) {
                broken += "ключ «$key» — не f<id>"
                continue
            }
            val mine = put[id] ?: local[id]?.takeIf { id !in gone }
            val mineHash = mine?.let { ArchiveEvents.meal(it, clock).hash } ?: ArchiveEvents.GONE
            val was = sent(key)
            val dirty = was != null && was != mineHash
            when (r.optString("op")) {
                "put" -> {
                    val got = r.optJSONObject("data")?.let(::meal)
                    if (got == null || got.id != id) {
                        broken += "$key — приём не читается"
                        continue
                    }
                    if (dirty) {
                        kept += key
                        continue
                    }
                    // Снимок остаётся на том устройстве, где снят: у приёма с компа фото
                    // пустое, а поправленный на компе свой приём не теряет своего файла.
                    val merged = got.copy(photo = got.photo.ifBlank { mine?.photo.orEmpty() })
                    val hash = ArchiveEvents.meal(merged, clock).hash
                    // Тот же снимок (повтор окна перекрытия курсора) — дневник не трогаем:
                    // отметки «уехало в ленту и intervals» у него верные.
                    if (hash != mineHash) {
                        put[id] = merged.copy(icuSynced = false, ribbonSynced = false)
                        gone -= id
                    }
                    ledger[key] = hash
                }
                "del" -> {
                    if (mine == null) {
                        ledger[key] = ArchiveEvents.GONE
                        continue
                    }
                    if (dirty) {
                        kept += key
                        continue
                    }
                    put.remove(id)
                    if (local.containsKey(id)) gone += id
                    ledger[key] = ArchiveEvents.GONE
                }
                else -> broken += "$key — op «${r.optString("op")}»"
            }
        }
        return Plan(put.values.toList(), gone.toList(), ledger, kept, broken)
    }

    /**
     * Приём из формата архива — обратное [ArchiveEvents.meal]: `ts` — из `at`,
     * `createdAt` — из `created`, позиции — целыми (так их держит дневник),
     * вещества — до сотой, как на диске: иначе приём в памяти и он же после
     * перезапуска дали бы разные отпечатки, и телефон переслал бы чужое как своё.
     * Отметки «уехало» снимает тот, кто кладёт: сумма дня поменялась.
     */
    fun meal(o: JSONObject): FoodStore.Meal? = runCatching {
        if (!o.has("id")) return null
        val id = o.getLong("id")
        val ts = isoMs(str(o, "at")) ?: return null
        val items = o.optJSONArray("items") ?: return null
        val list = (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            MealItem(
                name = str(it, "name"),
                grams = int(it, "grams"),
                kcal = int(it, "kcal"),
                protein = int(it, "protein_g"),
                fat = int(it, "fat_g"),
                carbs = int(it, "carbs_g"),
                fiber = int(it, "fiber_g"),
                sureness = str(it, "sureness"),
                micro = micro(it.optJSONObject("micro")),
                pill = it.optBoolean("pill", false),
            )
        }.filter { it.name.isNotBlank() }
        if (list.isEmpty()) return null
        FoodStore.Meal(
            id = id,
            ts = ts,
            createdAt = isoMs(str(o, "created")) ?: ts,
            kind = str(o, "kind"),
            raw = str(o, "raw"),
            items = list,
            note = str(o, "note"),
            source = str(o, "source").ifBlank { "voice" },
            photo = str(o, "photo"),
            confirmed = o.optBoolean("confirmed", false),
            costUsd = if (o.isNull("cost_usd")) 0.0 else o.optDouble("cost_usd", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            model = str(o, "model"),
            origin = str(o, "origin"),
        )
    }.getOrNull()

    /** ISO 8601 с поясом («+03:00», «Z», с миллисекундами и без) → мс эпохи. */
    fun isoMs(s: String): Long? = runCatching {
        if (s.isBlank()) null else java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    }.getOrNull()

    private fun micro(o: JSONObject?): Map<String, Double> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (k in o.keys()) {
            val v = o.optDouble(k, 0.0)
            if (v > 0) out[k] = Math.round(v * 100.0) / 100.0
        }
        return out
    }

    private fun int(o: JSONObject, key: String): Int =
        o.optDouble(key, 0.0).takeIf { !it.isNaN() }?.let { Math.round(it).toInt() } ?: 0

    /** Строка поля; JSON null — пусто (у Android `optString` отдаёт строку «null»). */
    private fun str(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "" else o.optString(key)
}
