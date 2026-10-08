package ru.zf.pravka.core

// Переразбор пойманных пушей текущим разбором (шаг переразбора истории,
// `HistoryFixes`, 25.09.2026). Сырьё пуша хранится целиком (`MoneyStore.Push`),
// запись журнала — его вывод; поправили разбор — старые записи выводятся из
// того же сырья заново, как если бы пуш пришёл сегодня.
//
// Что меняется, а что нет:
//  - поля разбора (что, заметка, счёт/карта, сумма) — берутся из нового разбора;
//  - решение владельца (категория, которую поставил он) — не трогается никогда;
//    категория справочника или догадка модели, выведенная из СТАРОГО «что»,
//    снимается — её поставит заново сверка по новому «что»;
//  - связи, вычеркнутость, «заменён выпиской» — не трогаются;
//  - пуш, который раньше не стал записью, а теперь стал, — добавляется;
//  - запись никогда не удаляется: пуш, который новый разбор не узнал,
//    остаётся как был.
//
// Сырьё Альфы и МКБ (с 08.10.2026) — тем же проходом, разбором своего банка
// (`BankPush.parseFrom`): напишется точный разбор — шаг переразбора истории
// выведет их записи заново. Повтор «пуш приложения + SMS» записью не становится.
//
// Файл без Android: проверяется JVM-тестом на настоящем пуше владельца.
object MoneyReparse {

    /** Сырьё пуша — то же, что хранит `MoneyStore.Push`. */
    data class Raw(val ts: Long, val pkg: String, val title: String, val text: String)

    data class Out(
        val entries: List<MoneyEntry>,
        /** Сколько сырых пушей банков (Т-Банк, Альфа, МКБ) просмотрено. */
        val looked: Int,
        /** Сколько записей поправлено. */
        val changed: Int,
        /** Сколько записей добавлено (раньше пуш не разбирался). */
        val added: Int,
        /** Номера сырых пушей, которые теперь стали записью, — им обновить «что вышло». */
        val nowMoney: Set<String>,
    )

    fun pushes(entries: List<MoneyEntry>, raw: List<Raw>, owner: String): Out {
        val out = entries.toMutableList()
        val index = entries.withIndex().associate { it.value.id to it.index }.toMutableMap()
        var looked = 0
        var changed = 0
        var added = 0
        val nowMoney = HashSet<String>()
        // Приложение каждой записи пуша — для повторов «пуш + SMS» Альфы и МКБ (`BankPush.twin`).
        val pkgOf = raw.associate { "push-" + BankPush.key(it.title, it.text) to it.pkg }
        for (r in raw) {
            val from = BankPush.from(r.pkg, r.title)
            if (from != BankPush.From.TBANK && from != BankPush.From.ALFA && from != BankPush.From.MKB) continue
            looked++
            val parsed = (BankPush.parseFrom(from, r.title, r.text) as? BankPush.Outcome.Money)?.p ?: continue
            val key = BankPush.key(r.title, r.text)
            val id = "push-$key"
            val at = index[id]
            if (at == null) {
                val fresh = BankPush.entry(parsed, r.ts, owner, r.title, r.text, from)
                // Та же операция уже есть из другого приложения банка — повтор, не запись.
                if (from != BankPush.From.TBANK) {
                    val near = out.filter {
                        it.source == MoneyEntry.Source.PUSH && kotlin.math.abs(it.ts - r.ts) <= BankPush.TWIN_MS
                    }.mapNotNull { e -> pkgOf[e.id]?.let { it to e } }
                    if (BankPush.twin(fresh, r.pkg, near) != null) continue
                }
                out.add(fresh)
                index[id] = out.lastIndex
                added++
                nowMoney.add(key)
                continue
            }
            val old = out[at]
            val fresh = BankPush.entry(parsed, r.ts, old.owner, r.title, r.text, from)
            val same = old.what == fresh.what && old.note == fresh.note &&
                old.account == fresh.account && old.rubKop == fresh.rubKop
            if (same) continue
            changed++
            val owners = old.categoryBy == MoneyEntry.CategoryBy.OWNER
            out[at] = old.copy(
                what = fresh.what,
                note = fresh.note,
                account = fresh.account,
                rubKop = fresh.rubKop,
                origMinor = fresh.origMinor,
                category = if (owners) old.category else "",
                categoryBy = if (owners) old.categoryBy else MoneyEntry.CategoryBy.NONE,
                question = if (owners) old.question else "",
            )
        }
        return Out(out, looked, changed, added, nowMoney)
    }
}
