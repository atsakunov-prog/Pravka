package ru.zf.pravka.core

import org.json.JSONObject

/**
 * Идеи у клиента, человека и сделки на телефоне (захват, 15.10.2026; ответ владельца 5а: «свой список „Идеи“ у
 * клиента, человека и сделки; всплывают перед встречей»). Сущность — `crm.ideas` сервера (`ideas.py`), телефон
 * получает их полем `ideas` синка (`Dela.Snapshot.ideas`). Здесь — кнопки карточки, как в вебе (`ideaToTask`,
 * `ideaSet`): «В дело» — дело «потом» с той же привязкой (идея — использована и помнит дело), «Использована»,
 * «Убрать»; у каждой — «Вернуть».
 */
object Ideas {

    const val USED = "used"
    const val DROPPED = "dropped"

    fun setOp(id: String, set: JSONObject, opId: String = Dela.newId()): JSONObject =
        JSONObject().put("op", "idea.set").put("op_id", opId).put("id", id).put("set", set)

    /** «Использована» / «Убрать» и «Вернуть» — живой снова. */
    fun mark(x: Dela.Idea, status: String): DelaCrm.Undoable = DelaCrm.Undoable(
        listOf(setOp(x.id, JSONObject().put("status", status))),
        listOf(setOp(x.id, JSONObject().put("status", Dela.IDEA_OPEN).put("task_id", JSONObject.NULL))),
        if (status == USED) "Идея использована" else "Идея убрана",
    )

    /**
     * «В дело»: дело «потом» (вернётся в следующий рабочий день строкой «Вернулось») с клиентом, человеком и сделкой
     * идеи; идея — использована, помнит дело. «Вернуть» — дело отменено, идея снова живая.
     */
    fun toTask(x: Dela.Idea, today: String, taskId: String = Dela.newId()): DelaCrm.Undoable {
        val title = x.text.trim().replaceFirstChar { it.uppercase() }
        val t = Dela.Task(
            id = taskId, title = title, projectId = x.projectId, personId = x.personId, dealId = x.dealId,
            backOn = Dela.nextWorkday(today), source = "manual",
        )
        return DelaCrm.Undoable(
            listOf(Dela.createOp(t), setOp(x.id, JSONObject().put("status", USED).put("task_id", taskId))),
            listOf(Dela.statusOp("task.cancel", taskId), setOp(x.id, JSONObject().put("status", Dela.IDEA_OPEN).put("task_id", JSONObject.NULL))),
            "Дело «потом»: ${title.take(40)} · верну ${DelaViews.backChip(t, today)?.removePrefix("↩ ") ?: "завтра"}",
        )
    }
}
