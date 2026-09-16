package ru.zf.pravka.core

// Подсказки распознавателю (EXTRA_BIASING_STRINGS) из словаря.
//
// Договорённость 15.09.2026: только верные формы — защищённые слова и правые
// части замен и подсказок — и не больше сорока (длинный список подсказок
// тормозил старт, а ослышки в нём учили движок ошибаться).
//
// Порядок внутри сорока (16.09.2026). Раньше список резался по частоте
// срабатываний, и слова, которые владелец добавил сам, конкурировали с двумя
// сотнями строк заводского семени: имя клиента с нулём срабатываний
// проигрывало «эскроу». Теперь:
//   1. слова владельца впереди семени — сам факт, что он завёл запись,
//      сильнее любой статистики: распознаватель это слово уже не расслышал;
//   2. защищённые слова впереди правых частей, внутри группы — по срабатываниям;
//   3. строки одной латиницей — в хвост: русскоязычный офлайн-пакет их не
//      выговаривает, а место в сорока они занимали («M&A», «EBITDA», «Strava»);
//   4. дубли схлопываются без учёта регистра («Стаффджет» и «стаффджет» — одно).
object BiasingList {
    const val LIMIT = 40

    class Built(val strings: List<String>, val ownerCount: Int, val latinCount: Int) {
        /** Одна строка для журнала: видно, что подсказки владельца доехали. */
        fun describe(): String = "${strings.size} строк, владельца $ownerCount, латиницей $latinCount"
    }

    private class Cand(val word: String, val owner: Boolean, val hits: Int, val protect: Boolean)

    /**
     * @param isSeed запись из заводского семени (а не владельца) — решает словарь,
     *   у самой записи признака нет.
     */
    fun build(entries: List<DictEntry>, isSeed: (DictEntry) -> Boolean, limit: Int = LIMIT): Built {
        val cands = entries.asSequence()
            .filter { it.enabled }
            .mapNotNull { e ->
                val word = (if (e.mode == DictMode.PROTECT) e.from else e.to).trim()
                if (word.isEmpty()) null
                else Cand(word, owner = !isSeed(e), hits = e.hits, protect = e.mode == DictMode.PROTECT)
            }
            // sortedWith стабильна: при равных ключах остаётся порядок словаря.
            .sortedWith(compareBy<Cand>({ !it.protect }, { !it.owner }, { -it.hits }))
            .toList()
        val seen = HashSet<String>()
        val cyrillic = ArrayList<Cand>()
        val latin = ArrayList<Cand>()
        for (c in cands) {
            if (!seen.add(c.word.lowercase())) continue
            if (hasCyrillic(c.word)) cyrillic.add(c) else latin.add(c)
        }
        val picked = (cyrillic + latin).take(limit)
        return Built(
            strings = picked.map { it.word },
            ownerCount = picked.count { it.owner },
            latinCount = picked.count { !hasCyrillic(it.word) },
        )
    }

    private fun hasCyrillic(s: String): Boolean = s.any { it in 'Ѐ'..'ӿ' }
}
