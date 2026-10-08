package ru.zf.pravka.core

/**
 * Клиент у записи ленты с личной категорией (09.10.2026, docs/dela-phone-9.md):
 * «Время с семьёй» 4 часа легло на клиента — модель Засечки перенесла клиента из
 * прошлой записи. Часы клиента на сервере личное время уже не берут
 * (`life.work_time`, тот же список категорий), а телефон не должен такого
 * писать: у личной записи клиент остаётся, только если он назван в самих словах.
 */
object ZasechkaClient {

    /** Личные категории — как `life_dela.sql` сервера: Семья, Еда, Быт, Отдых, Сон, Спорт…, Секс…, Чтение, Потери, «Не размечено». */
    private val PERSONAL = Regex("^(Семья|Еда|Быт|Отдых|Сон|Спорт|Секс|Чтение|Потери|Не размечено)", RegexOption.IGNORE_CASE)

    fun personal(category: String): Boolean = PERSONAL.containsMatchIn(category.trim())

    /**
     * Клиент для новой записи: у рабочей — как сказала модель; у личной — только
     * если клиент назван в надиктовке [said] (имя или начало его первого слова).
     */
    fun keep(category: String, client: String, said: String): String {
        if (client.isBlank() || !personal(category)) return client
        val text = said.lowercase().replace('ё', 'е')
        val name = client.lowercase().replace('ё', 'е').trim()
        val stem = name.split(Regex("[\\s\\-«»\"]+")).firstOrNull { it.length >= 3 }?.let { it.take(maxOf(3, it.length - 1)) }.orEmpty()
        return if (text.contains(name) || (stem.isNotBlank() && text.contains(stem))) client else ""
    }
}
