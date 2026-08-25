package ru.zf.pravka.core

enum class DictMode { HARD, HINT, PROTECT }

data class DictEntry(
    val id: Long = 0,
    // Устойчивый идентификатор записи: локальный id у телефона и воркстанции
    // свой, а сводить их надо по одному и тому же ключу. Заполняется при
    // создании; у старых записей появляется при первой загрузке.
    val uid: String = "",
    val from: String,          // what speech recognition produces
    val to: String = "",       // what it should be; empty for PROTECT
    val mode: DictMode,
    val note: String = "",     // explanation, goes into the prompt for HINT
    val hits: Int = 0,         // how many times the entry fired
    val enabled: Boolean = true,
    val createdAt: Long,
    /** Когда запись последний раз меняли: по нему решается спор при слиянии. */
    val updatedAt: Long = createdAt,
    /**
     * Надгробие. Удалённая запись не исчезает, а помечается: иначе второе
     * устройство при следующей синхронизации вернуло бы её обратно.
     */
    val deleted: Boolean = false,
)
