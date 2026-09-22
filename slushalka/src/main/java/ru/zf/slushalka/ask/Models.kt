package ru.zf.slushalka.ask

/**
 * Модели Claude, которые можно выбрать, - одним списком: идентификатор для
 * API, имя для экрана, цена и что значит «усилие по умолчанию». Раньше это
 * лежало в трёх местах (настройки, транспорт, подписи), и смена модели
 * задевала все три.
 */
object Models {

    const val SONNET_ID = "claude-sonnet-5"
    const val OPUS_ID = "claude-opus-5-5"
    const val FABLE_ID = "claude-fable-5-1"

    /**
     * [input], [output], [cacheRead] - доллары за миллион токенов; запись в
     * кэш на час стоит двойной вход (см. ClaudeClient.costUsd).
     * [defaultEffort] - что уходит в output_config.effort, когда в настройках
     * «по умолчанию»; пусто - параметр не передаётся.
     */
    data class Model(
        val id: String,
        val label: String,
        val input: Double,
        val output: Double,
        val cacheRead: Double,
        val defaultEffort: String,
    )

    // Сонет 5 дешевле 4.6 ($3/$15): старая цена завышала расход в полтора раза.
    val SONNET = Model(SONNET_ID, "Сонет 5", input = 2.0, output = 10.0, cacheRead = 0.20, defaultEffort = "")

    // Опус 5.5: у API для него по умолчанию medium, на ступень ниже прежнего
    // high, - молча отдать его значило бы сделать ответы мельче, чем были.
    val OPUS = Model(OPUS_ID, "Опус 5.5", input = 4.0, output = 20.0, cacheRead = 0.20, defaultEffort = "high")

    // Fable 5.1: в два с половиной раза дороже Опуса 5.5.
    val FABLE = Model(FABLE_ID, "Fable 5.1", input = 10.0, output = 50.0, cacheRead = 0.25, defaultEffort = "")

    /** Что можно выбрать; порядок - от дешёвой к дорогой. */
    val ALL = listOf(SONNET, OPUS, FABLE)

    fun of(id: String): Model? = ALL.firstOrNull { it.id == id }
}
