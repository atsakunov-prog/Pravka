package ru.zf.pravka

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.CallRules
import ru.zf.pravka.core.Svod
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.SvodStore

// Свод в приложении (06.10.2026, docs/svod-phone.md): какие сторы смотрят
// в Свод зеркалами, что телефон отдаёт при первом знакомстве и как ответ
// «это он» на вопрос о звонке становится карточкой человека. Вынесено из
// PravkaApp, чтобы сервис-локатор не рос полотном.

/** Сервер Дел знает Свод (`features`), и Дела идут через него, а не Todoist. */
internal fun PravkaApp.svodOn(): Boolean =
    delaServer.value && delaSync.link.value != null && Svod.FEATURE in delaStore.view.value.features

/** Собрать Свод: зеркала сторов и крючки «поменялось». */
internal fun PravkaApp.buildSvod(): SvodStore {
    val store = SvodStore(this, appScope) { eventLog.add(it) }
    store.enabled = { svodOn() }
    store.sender = { ops -> delaDo(ops) }
    promptStore.svod = store
    promptStore.svodOn = { svodOn() }

    // ---- dict.main: словарь Правки, без счётчиков ----
    store.register(object : SvodStore.Mirror {
        override val key = Svod.DICT
        override val quietMs = 30_000L
        override suspend fun export(): String = Svod.dictValue(dictionaryStore.all(), dictionaryStore.seedVersion()).toString()
        override suspend fun replace(value: String) {
            val list = Svod.dictEntries(Svod.parseJson(value))
            if (!dictionaryStore.replaceFromSvod(list)) eventLog.add("свод: пустой словарь с сервера поверх своего — не кладу")
        }
        override suspend fun merge(base: String?, server: String, local: String): String {
            val merged = Svod.mergeDict(
                base?.let { Svod.dictEntries(Svod.parseJson(it)) },
                Svod.dictEntries(Svod.parseJson(server)),
                dictionaryStore.all(),
            )
            return Svod.dictValue(merged, dictionaryStore.seedVersion()).toString()
        }
    })
    dictionaryStore.onEdit = { store.touch(Svod.DICT) }

    // ---- rules.pravka: правила Правки ----
    store.register(object : SvodStore.Mirror {
        override val key = Svod.RULES
        override suspend fun export(): String = rulesStore.exportJson()
        override suspend fun replace(value: String) { rulesStore.replaceFromJson(value) }
        override suspend fun merge(base: String?, server: String, local: String): String =
            mergeObjects(base, server, local) { it.optString("id") }
    })
    rulesStore.onEdit = { store.touch(Svod.RULES) }

    // ---- zasechka.categories: категории Засечки ----
    store.register(object : SvodStore.Mirror {
        override val key = Svod.CATEGORIES
        override suspend fun export(): String = JSONArray().apply {
            for (c in zasechkaStore.categories()) put(
                JSONObject().put("name", c.name).put("hint", c.hint).put("base_min", c.baseMin).put("value", c.value)
            )
        }.toString()
        override suspend fun replace(value: String) {
            val a = Svod.parseJson(value) as? JSONArray ?: return
            val list = (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o ->
                    ru.zf.pravka.data.ZasechkaStore.Category(o.optString("name"), o.optString("hint"), o.optInt("base_min"), o.optInt("value"))
                }
            }.filter { it.name.isNotBlank() }
            // Пустой справочник поверх своего — поломка, не правка.
            if (list.isNotEmpty()) zasechkaStore.setCategories(list, edited = false)
        }
        override suspend fun merge(base: String?, server: String, local: String): String =
            mergeObjects(base, server, local) { it.optString("name").lowercase() }
    })
    zasechkaStore.onCategories = { store.touch(Svod.CATEGORIES) }

    // ---- money.payees: справочник получателей одним списком строк ----
    store.register(object : SvodStore.Mirror {
        override val key = Svod.MONEY_PAYEES
        override suspend fun export(): String = JSONArray().apply { moneyPayeeLines().forEach { put(it) } }.toString()
        // Кэш Свода — сам справочник: заводской слой Денег читает его (moneyFactoryLayer).
        override suspend fun replace(value: String) {}
        override suspend fun merge(base: String?, server: String, local: String): String {
            fun lines(s: String?): List<String>? = (s?.let { Svod.parseJson(it) } as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) } }
            val merged = Svod.merge3(lines(base), lines(server).orEmpty(), lines(local).orEmpty(), { ru.zf.pravka.core.MoneyRules.norm(it) }, { a, b -> a == b })
            return JSONArray().apply { merged.forEach { put(it) } }.toString()
        }
    })
    moneyStore.onRules = { store.touch(Svod.MONEY_PAYEES) }

    // ---- models.routes: выбор владельца в «Моделях» (только уведённое от заводского) ----
    var applyingModels = false
    store.register(object : SvodStore.Mirror {
        override val key = Svod.MODELS
        override suspend fun export(): String = JSONObject().apply {
            for (r in ModelRoute.entries) {
                val c = settings.modelChoice(r)
                if (!c.isDefaultFor(r)) put(r.key, JSONObject().put("model", c.model).put("effort", c.effort))
            }
        }.toString()
        override suspend fun replace(value: String) {
            val o = Svod.parseJson(value) as? JSONObject ?: return
            applyingModels = true
            try {
                for (r in ModelRoute.entries) {
                    val c = o.optJSONObject(r.key)
                    if (c == null) settings.resetModelChoice(r)
                    else {
                        c.optString("model").takeIf { it.isNotBlank() }?.let { settings.setModel(r, it) }
                        c.optString("effort").takeIf { it.isNotBlank() }?.let { settings.setEffort(r, it) }
                    }
                }
            } finally {
                applyingModels = false
            }
        }
        override suspend fun merge(base: String?, server: String, local: String): String {
            fun pairs(s: String?): List<Pair<String, String>>? = (s?.let { Svod.parseJson(it) } as? JSONObject)?.let { o ->
                o.keys().asSequence().map { it to (o.optJSONObject(it)?.toString() ?: "") }.toList()
            }
            val merged = Svod.merge3(pairs(base), pairs(server).orEmpty(), pairs(local).orEmpty(), { it.first }, { a, b -> Svod.sameJson(a.second, b.second) })
            return JSONObject().apply { merged.forEach { (k, v) -> put(k, JSONObject(v)) } }.toString()
        }
    })
    appScope.launch {
        combine(ModelRoute.entries.map { settings.modelChoiceFlow(it) }) { it.toList() }
            .distinctUntilChanged().drop(1)
            .collect { if (!applyingModels) store.touch(Svod.MODELS) }
    }
    return store
}

/** Трёхстороннее слияние JSON-массивов объектов по ключу элемента (правила, категории). */
private fun mergeObjects(base: String?, server: String, local: String, keyOf: (JSONObject) -> String): String {
    fun list(s: String?): List<JSONObject>? = (s?.let { Svod.parseJson(it) } as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    val merged = Svod.merge3(list(base), list(server).orEmpty(), list(local).orEmpty(), keyOf) { a, b -> Svod.sameJson(a.toString(), b.toString()) }
    return JSONArray().apply { merged.forEach { put(it) } }.toString()
}

/**
 * Справочник получателей одним списком: правила телефона впереди (они
 * сильнее), за ними — заводской слой (запись Свода, без неё — файл APK).
 */
internal suspend fun PravkaApp.moneyPayeeLines(): List<String> {
    val phone = ru.zf.pravka.core.MoneyRules.toText(moneyStore.load().rules).lines()
    val base = svodPayeeLines() ?: if (profileStore.owner) {
        runCatching { assets.open("money_payees.txt").bufferedReader().use { it.readText() } }.getOrDefault("").lines()
    } else emptyList()
    return (phone + base).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .distinctBy { ru.zf.pravka.core.MoneyRules.norm(it) }
}

/** Строки `money.payees` из Свода; записи нет — null. */
internal fun svodPayeeLines(): List<String>? =
    (Svod.value(Svod.MONEY_PAYEES) as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) } }

/**
 * Первое знакомство (docs/svod-phone.md, 1.2): всё, что телефон знает сам, —
 * ключ → (текст, JSON). Свод кладёт только то, чего на сервере ещё нет.
 */
internal suspend fun PravkaApp.svodSeedTexts(): Map<String, Pair<String?, String?>> {
    val out = LinkedHashMap<String, Pair<String?, String?>>()
    for (id in PromptStore.PromptId.entries) {
        val text = if (id == PromptStore.PromptId.TASKS_DELA) {
            ru.zf.pravka.core.prompts.PromptsRaznoska.stripTail(promptStore.local(id))
        } else promptStore.local(id)
        out[PromptStore.svodKey(id)] = text to null
    }
    for ((k, v) in ru.zf.pravka.core.prompts.PromptsReview.SVOD) out[k] = v to null
    out[ru.zf.pravka.core.prompts.PromptsZasechka.SVOD_KEY] = ru.zf.pravka.core.prompts.PromptsZasechka.RULES to null
    out[ru.zf.pravka.core.prompts.PromptsPravka.DICT_MINER_KEY] = ru.zf.pravka.core.prompts.PromptsPravka.DICT_MINER to null
    out[ru.zf.pravka.core.prompts.PromptsPravka.LEARN_KEY] = ru.zf.pravka.core.prompts.PromptsPravka.LEARN to null
    out[ru.zf.pravka.core.prompts.PromptsPravka.RULES_SUMMARY_KEY] = ru.zf.pravka.core.prompts.PromptsPravka.RULES_SUMMARY to null
    out[Svod.DICT] = null to Svod.dictValue(dictionaryStore.all(), dictionaryStore.seedVersion()).toString()
    out[Svod.RULES] = null to rulesStore.exportJson()
    out[Svod.CATEGORIES] = null to JSONArray().apply {
        for (c in zasechkaStore.categories()) put(JSONObject().put("name", c.name).put("hint", c.hint).put("base_min", c.baseMin).put("value", c.value))
    }.toString()
    out[Svod.MODELS] = null to JSONObject().apply {
        for (r in ModelRoute.entries) {
            val c = settings.modelChoice(r)
            if (!c.isDefaultFor(r)) put(r.key, JSONObject().put("model", c.model).put("effort", c.effort))
        }
    }.toString()
    out[Svod.MONEY_PAYEES] = null to JSONArray().apply { moneyPayeeLines().forEach { put(it) } }.toString()
    // Заводские якоря и ручные записи Денег — про семью владельца: только у него.
    if (profileStore.owner) {
        fun asset(name: String) = runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
        asset("money_balances.txt")?.takeIf { it.isNotBlank() }?.let { out[Svod.MONEY_ANCHORS] = it to null }
        asset("money_manual.txt")?.takeIf { it.isNotBlank() }?.let { out[Svod.MONEY_MANUAL] = it to null }
    }
    return out
}

/**
 * Люди семьи по карточкам (docs/svod-phone.md, 3.5): список `people.family`
 * Свода (id людей), а пока его нет — люди, связанные с пользователем Дел.
 */
internal fun PravkaApp.callFamilyIds(): Set<String> {
    val listed = (Svod.value(Svod.FAMILY) as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
    if (listed != null) return listed.toSet()
    return delaStore.view.value.people.values.filter { it.live && it.userId.isNotBlank() }.map { it.id }.toSet()
}

/**
 * «Это он» на вопрос о звонке: запись ленты несёт человека и клиента от его
 * организации, сервер учит номер и имя контакта (`person.add`), и дальше
 * этого собеседника узнают все. Возвращает фразу для тоста.
 */
internal suspend fun PravkaApp.callIsWho(entryId: Long, personId: String): String {
    val call = zasechkaStore.entryById(entryId) ?: return "Звонка в ленте уже нет"
    val s = delaStore.view.value
    val p = s.person(personId) ?: return "Человека «$personId» в Делах нет — обнови Дела"
    val client = if (p.orgId.isBlank()) null else s.projects.values
        .filter { it.live && it.orgId == p.orgId }
        .let { same -> same.firstOrNull { it.kind == "client" } ?: same.firstOrNull() }
    val family = p.id in callFamilyIds()
    zasechkaStore.update(
        call.copy(
            person = p.id,
            category = if (family) CallRules.FAMILY else CallRules.WORK,
            client = if (family) "" else client?.name.orEmpty(),
            project = if (family) "" else client?.id.orEmpty(),
        )
    )
    // Имя контакта или номер — из названия звонка («Звонок: Женя С.»): его и учим.
    val said = call.title.removePrefix("Звонок: ").trim()
    val isNumber = said.count { it.isDigit() } >= 7
    val person = CallRules.Person(
        names = (listOf(p.name, p.short) + p.aliases).filter { it.isNotBlank() }, phones = p.phones, id = p.id,
    )
    val c = CallRules.Call(call.start, call.end, if (isNumber) "" else said, if (isNumber) said else "", false)
    val iso = java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(call.start), java.time.ZoneId.systemDefault()).withNano(0).toString()
    delaDo(listOfNotNull(
        CallRules.learnOp(person, c),
        if (family) null else CallRules.interactionOp(c, call.title, p.id, client?.id.orEmpty(), iso),
    ))
    eventLog.add("звонок: «${call.title}» — это ${p.name} (кнопкой)")
    return "📞 ${call.title} — ${p.name}; запомнил для всех"
}

// ---- Деньги из Свода (docs/svod-phone.md, часть 2) ----

private var payeeMemo: Pair<Any?, List<ru.zf.pravka.core.MoneyRules.Rule>>? = null
private var partnerMemo: Pair<Any?, List<ru.zf.pravka.core.MoneyRules.Rule>>? = null

/** Заводской слой справочника из Свода (`money.payees`); записи нет — null, и действует файл APK. */
internal fun PravkaApp.moneySvodRules(): List<ru.zf.pravka.core.MoneyRules.Rule>? {
    val e = Svod.current[Svod.MONEY_PAYEES] ?: return null
    payeeMemo?.takeIf { it.first === e }?.let { return it.second }
    val lines = svodPayeeLines() ?: return null
    val r = ru.zf.pravka.core.MoneyRules.parseText(lines.joinToString("\n"))
    if (r.errors.isNotEmpty()) eventLog.add("деньги: справочник из Свода — ${r.errors.take(3)}")
    payeeMemo = e to r.rules
    return r.rules
}

/** Правила получателей «Денег» (`money.partner.payee_rules`): сильнее заводского слоя. */
internal fun moneyPartnerPayeeRules(): List<ru.zf.pravka.core.MoneyRules.Rule> {
    val e = Svod.current[Svod.MONEY_PARTNER] ?: return emptyList()
    partnerMemo?.takeIf { it.first === e }?.let { return it.second }
    val r = ru.zf.pravka.core.MoneyRules.fromPartner(Svod.obj(Svod.MONEY_PARTNER)?.optJSONArray("payee_rules"))
    partnerMemo = e to r
    return r
}
