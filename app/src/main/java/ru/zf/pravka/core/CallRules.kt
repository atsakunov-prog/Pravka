package ru.zf.pravka.core

/**
 * Звонки режут дело (06.10.2026, docs/zasechka.md «Звонки в ленте»).
 *
 * Владелец: «звонки всё же должны перебивать текущее дело, и надо смотреть, с
 * кем я разговариваю — с Марианной, с папой… или с людьми по работе. Надо
 * вставлять это внутрь дел, которые идут: когда я говорю по телефону, я не
 * работаю. Или, допустим, сижу [личное], и пришёл звонок по работе — я прямо
 * вот работаю».
 *
 * В августе врезки звонков уже были и ушли со словами «засоряет ленту, и не
 * всегда это потеря»: звонок ложился безликим «Звонком», и за ним не было
 * видно, работа это или жизнь. Отсюда три отличия этого раза: категорию
 * решает СОБЕСЕДНИК, а не дело, которое шло (семья — «Семья», люди Дел —
 * «Работа: звонки» с клиентом); короткое «перезвоню» в ленту не идёт; и
 * звонок не режет то, где он и так часть дела (свой звонок владельца,
 * встреча из календаря, дорога, спорт).
 *
 * Здесь — то, что не знает ни про журнал звонков, ни про Android.
 */
object CallRules {

    /**
     * Короче двух минут — «перезвоню», «я за рулём», а не разговор: дробить
     * ради него дело — та самая грязь, из-за которой врезки сняли в августе.
     * В суточные счётчики звонок по-прежнему идёт с минуты (`PhoneSweeper`).
     */
    const val MIN_SEC = 120

    const val WORK = "Работа: звонки"
    const val FAMILY = "Семья"
    /** Заводская «если непонятно с кем и о чём» (`ZasechkaStore.DEFAULT_CATEGORIES`). */
    const val UNKNOWN = "Звонки"

    /**
     * Кто — семья, с завода; список правится в автопилоте, раздел «Звонки».
     * Младших нет нарочно: «Рома» и «Боря» ловили бы Романа и Бориса по работе,
     * а звонят они с маминого телефона.
     */
    const val FAMILY_DEFAULT = "Марианна, Папа, Мама, Серёжа"

    /** Строка журнала звонков: начало, конец, имя контакта (может быть пусто), номер. */
    data class Call(val start: Long, val end: Long, val name: String, val number: String, val outgoing: Boolean)

    /**
     * Человек Дел для сверки: имена, телефоны и его клиент (проект Дел), если
     * есть. [id] — карточка Дел (06.10.2026: запись ленты помнит, с кем был
     * звонок), [userId] — человек и есть пользователь Дел (команда, семья).
     */
    data class Person(
        val names: List<String>,
        val phones: List<String>,
        val client: String = "",
        val projectId: String = "",
        val id: String = "",
        val userId: String = "",
    )

    /** Кандидат ответа «кто это». */
    data class Candidate(val id: String, val name: String, val score: Double = 0.0)

    /**
     * Ответ сервера «кто это» (`GET /api/view/who`, контракт svod.json):
     * одно правило на всех — номер, потом имя с уменьшительными, падежами,
     * порядком «Фамилия Имя» и компанией. [sure] — лучший один и с запасом;
     * неуверенное решает владелец.
     */
    data class Who(val best: Candidate?, val sure: Boolean, val candidates: List<Candidate>)

    fun parseWho(o: org.json.JSONObject): Who {
        fun cand(c: org.json.JSONObject?): Candidate? {
            c ?: return null
            val id = c.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return null
            return Candidate(id, c.optString("name"), c.optDouble("score", 0.0))
        }
        val list = o.optJSONArray("candidates")?.let { a -> (0 until a.length()).mapNotNull { cand(a.optJSONObject(it)) } }.orEmpty()
        val best = if (o.isNull("best")) null else cand(o.optJSONObject("best"))
        return Who(best, o.optBoolean("sure", false) && best != null, list)
    }

    /** Что было в ленте раньше с тем же названием звонка — слово владельца (поправил однажды — так и дальше). */
    data class Learned(val category: String, val client: String, val project: String)

    enum class Why { LEARNED, FAMILY, PERSON, UNKNOWN }

    data class Verdict(
        val title: String,
        val category: String,
        val client: String,
        val project: String,
        val why: Why,
        /** Карточка Дел собеседника — уверенно узнанного; "" — не знаем. */
        val personId: String = "",
        /** Не уверены, но есть на кого подумать: пуш спросит кнопками. */
        val candidates: List<Candidate> = emptyList(),
    ) {
        /** Уверены ли мы, с кем говорил: нет — пуш спрашивает громко, да — тихой копией. */
        val sure: Boolean get() = why != Why.UNKNOWN
    }

    /** Последние десять цифр: «+7 916 …», «8 916 …» и «916…» — один номер. */
    fun digits(number: String): String = number.filter { it.isDigit() }.takeLast(10)

    /** «Звонок: Марианна»; без имени — номером. Начало «Звонок» держит дедуп ленты (`isCallTitle`). */
    fun title(c: Call): String {
        val who = c.name.trim().ifBlank { prettyNumber(c.number) }.ifBlank { "номер скрыт" }
        return "Звонок: $who"
    }

    private fun prettyNumber(number: String): String {
        val d = number.filter { it.isDigit() }
        if (d.length == 11 && (d[0] == '7' || d[0] == '8')) {
            return "+7 ${d.substring(1, 4)} ${d.substring(4, 7)}-${d.substring(7, 9)}-${d.substring(9)}"
        }
        return number.trim()
    }

    /** Список семьи из настройки: «Марианна, Папа» — по запятым и строкам. */
    fun familyList(raw: String): List<String> =
        raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * С кем говорил — и, значит, что это было. По старшинству: слово владельца
     * в ленте (тот же собеседник, поправленная категория), семья по имени
     * контакта, человек Дел (работа, с его клиентом; человек из семьи —
     * «Семья»), иначе «Звонки» — и вопрос пушем.
     *
     * Человека Дел с 06.10.2026 узнаёт сервер ([who], одно правило с
     * встречами и CRM: «Женя Соколов» из контакта — тот же «Евгений
     * Соколов» из карточки). Ответа нет (нет сети, старый сервер) — прежнее
     * точное сопоставление по синку. [familyIds] — люди семьи по карточкам
     * (`people.family` в Своде или пользователи Дел).
     */
    fun verdict(
        c: Call,
        family: List<String>,
        people: List<Person>,
        learned: Learned?,
        who: Who? = null,
        familyIds: Set<String> = emptySet(),
    ): Verdict {
        val title = title(c)
        val p = if (who != null) {
            if (who.sure) people.firstOrNull { it.id == who.best?.id } else null
        } else {
            person(c, people)
        }
        val pid = p?.id.orEmpty()
        if (learned != null && learned.category.isNotBlank()) {
            return Verdict(title, learned.category, learned.client, learned.project, Why.LEARNED, personId = pid)
        }
        if (c.name.isNotBlank() && family.any { sameName(it, c.name) }) {
            return Verdict(title, FAMILY, "", "", Why.FAMILY, personId = pid)
        }
        if (p != null) {
            if (p.id.isNotBlank() && p.id in familyIds) return Verdict(title, FAMILY, "", "", Why.FAMILY, personId = pid)
            return Verdict(title, WORK, p.client, p.projectId, Why.PERSON, personId = pid)
        }
        val ask = who?.candidates.orEmpty().filter { cand -> people.any { it.id == cand.id } }
        return Verdict(title, UNKNOWN, "", "", Why.UNKNOWN, candidates = ask)
    }

    /** Человек Дел: по номеру (последние десять цифр), иначе по имени контакта целиком. */
    fun person(c: Call, people: List<Person>): Person? {
        val d = digits(c.number)
        if (d.length >= 7) people.firstOrNull { p -> p.phones.any { digits(it) == d } }?.let { return it }
        val n = norm(c.name)
        if (n.isEmpty()) return null
        return people.filter { p -> p.names.any { norm(it) == n } }.singleOrNull()
    }

    /**
     * Телефон учит сервер: у карточки нет этого номера или имени контакта —
     * `person.add` (дописывает, не затирая). Дальше узнают все: встречи, CRM,
     * следующий звонок. Нечего дописать — null. op_id постоянный от того, что
     * дописываем: второй звонок с того же номера дубля не ставит.
     */
    fun learnOp(p: Person, c: Call): org.json.JSONObject? {
        if (p.id.isBlank()) return null
        val d = digits(c.number)
        val phone = c.number.trim().takeIf { d.length >= 7 && p.phones.none { digits(it) == d } }
        val alias = c.name.trim().takeIf { it.isNotEmpty() && p.names.none { n -> norm(n) == norm(it) } }
        if (phone == null && alias == null) return null
        val add = org.json.JSONObject()
        if (alias != null) add.put("aliases", org.json.JSONArray().put(alias))
        if (phone != null) add.put("phones", org.json.JSONArray().put(phone))
        return org.json.JSONObject()
            .put("op", "person.add")
            .put("op_id", Dela.stableId("person.add:${p.id}:${d}:${alias.orEmpty().lowercase()}"))
            .put("id", p.id)
            .put("add", add)
    }

    /**
     * Звонок от двух минут с человеком клиента — в хронологию CRM
     * (`interaction.add`, kind call, source phone). op_id и id — от звонка в
     * журнале: повтор свипа не плодит дубль.
     */
    fun interactionOp(c: Call, title: String, personId: String, projectId: String, startIso: String): org.json.JSONObject? {
        if (personId.isBlank() || projectId.isBlank()) return null
        val sec = (c.end - c.start) / 1000
        if (sec < MIN_SEC) return null
        val seed = "call:${c.start}:${digits(c.number)}"
        return org.json.JSONObject()
            .put("op", "interaction.add")
            .put("op_id", Dela.stableId(seed))
            .put("data", org.json.JSONObject()
                .put("id", Dela.stableId("$seed:row"))
                .put("at", startIso)
                .put("kind", "call")
                .put("summary", title + (if (c.outgoing) " (исходящий)" else " (входящий)"))
                .put("project_id", projectId)
                .put("person_ids", org.json.JSONArray().put(personId))
                .put("duration_min", ((sec + 30) / 60).coerceAtLeast(1))
                .put("source", "phone"))
    }

    /**
     * Имя из списка семьи в имени контакта: каждое его слово есть среди слов
     * контакта — целиком или с другим окончанием («Марианна» — «Марианна Ц.»,
     * «Серёжа» — «Серёжа сын», «Папа» — «Папуля»). Не любым началом, как у
     * встреч (`CalendarRules.stemEq`): там «Мама» нашлась бы в «Маматове».
     */
    fun sameName(listed: String, contact: String): Boolean {
        val need = words(listed)
        val have = words(contact)
        if (need.isEmpty() || have.isEmpty()) return false
        return need.all { w -> have.any { h -> nameEq(w, h) } }
    }

    /** Одно имя: целиком, или от четырёх букв — без последней буквы в начале и хвост не длиннее двух. */
    private fun nameEq(w: String, h: String): Boolean {
        if (w == h) return true
        if (w.length < 4) return false
        return h.startsWith(w.dropLast(1)) && h.length - w.length in -1..2
    }

    private fun words(s: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase().replace('ё', 'е')).map { it.value }.filter { it.length >= 2 }.toList()

    private fun norm(s: String): String = s.trim().lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ")

    // ------------------------------------------------------------ резать ли

    /** Запись ленты, которую звонок задевает: что это за дело и чьё. */
    data class Seen(val title: String, val category: String, val source: String)

    enum class Skip(val words: String) {
        NONE(""),
        OWN_CALL("ты сам записал этот разговор"),
        MEETING("идёт встреча из календаря — звонок и есть она"),
        TRAVEL("идёт дорога — звонок из машины поездку не режет"),
        SPORT("идёт тренировка"),
    }

    /**
     * Где звонок НЕ режет дело — потому что и так его часть. Свой звонок
     * владельца («созвон с Ильёй», «Работа: звонки») — человек сильнее
     * робота, второй записи нет. Встреча из календаря — это обычно и есть
     * созвон. Дорога — время занимает то, чем заняты руки (правило ленты, как
     * у встреч: «созвон из машины — поездка»). Тренировка — тоже.
     */
    fun skip(overlaps: List<Seen>): Skip {
        for (e in overlaps) {
            if (e.source == "gap") continue
            if (e.source != "auto" && callish(e.title, e.category)) return Skip.OWN_CALL
        }
        for (e in overlaps) {
            if (e.source == CalendarRules.SOURCE) return Skip.MEETING
            if (AutoPilotRules.travelish(e.title, e.category)) return Skip.TRAVEL
            if (AutoPilotRules.sporty(e.category)) return Skip.SPORT
        }
        return Skip.NONE
    }

    /** Запись — сам разговор по телефону: категория звонков или слово в названии. */
    fun callish(title: String, category: String): Boolean {
        val c = category.lowercase()
        if (c == UNKNOWN.lowercase() || c == WORK.lowercase()) return true
        val t = title.lowercase().replace('ё', 'е')
        return listOf("звонок", "звонил", "созвон", "по телефону", "разговор с", "говорил с", "перезвон")
            .any { t.contains(it) }
    }
}
