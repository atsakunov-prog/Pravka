package ru.zf.pravka.core

import org.json.JSONObject

/**
 * Место в дереве жизни при разборе (13.10.2026, docs/dela-phone-15.md, сервер — `server/pravka_dela/place.py`).
 *
 * Владелец: «нужно брать текущее Дерево жизни и смотреть, куда дела попадают… если я надиктовываю в телефоне».
 * Сервер Дел сам собирает блок «дерево жизни» (правила раскладки, направления, цели с номерами, правки владельца)
 * и кладёт его в Свод — `krug.place`; телефон получает его синком и вставляет в промпт Разноски вместо `{TREE}`.
 * Модель называет у дела `goal` — номер цели; телефон отдаёт его серверу полем `why_auto` в `task.create`, а
 * проверяет и ставит «Зачем» сервер (клиентское дело, сделку, ориентир не поставит). Телефон номер не угадывает и
 * в id не превращает: блок и проверка живут в одном месте.
 *
 * Чистые функции — под JVM-тестами; Свод — общий кэш [Svod.current].
 */
object DelaPlace {
    /** Свод: блок «дерево жизни» для промптов разбора (пишет сервер Дел). */
    const val SVOD_KEY = "krug.place"

    /** Блока нет (Свод пуст, говорит не хозяин) — так и сказать модели: место потом найдёт разборщик Круга. */
    const val NO_TREE = "Дерева жизни нет — goal у всех дел 0."

    private const val PLACEHOLDER = "{TREE}"
    private const val TODAY = "{TODAY}"

    /** Блок из Свода; нет записи — пусто. */
    fun block(): String = Svod.current[SVOD_KEY]?.body.orEmpty().trim()

    /**
     * Шаблон с деревом: `{TREE}` — блоком. Правила без `{TREE}` (тюнер переписал их раньше 13.10.2026) — блок перед
     * строкой с `{TODAY}`: там кончается голова промпта под часовым кэшем. Как `parse.with_tree` на сервере.
     */
    fun withTree(template: String, tree: String): String {
        val block = tree.trim().ifBlank { NO_TREE }
        if (template.contains(PLACEHOLDER)) return template.replace(PLACEHOLDER, block)
        val cut = template.indexOf(TODAY)
        if (cut < 0) return template.trimEnd() + "\n\n" + block
        val line = template.lastIndexOf('\n', cut) + 1
        return template.substring(0, line) + block + "\n\n" + template.substring(line)
    }

    /**
     * Номер цели — в операцию `task.create` (поле `why_auto`), только серверу, который его знает ([on] —
     * `Snapshot.placeOn`): старый сервер отверг бы незнакомое поле вместе с делом.
     */
    fun withPlace(op: JSONObject, whyAuto: Int, on: Boolean): JSONObject {
        if (on && whyAuto > 0) op.optJSONObject("task")?.put("why_auto", whyAuto)
        return op
    }
}
