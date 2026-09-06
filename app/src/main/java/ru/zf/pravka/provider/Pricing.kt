package ru.zf.pravka.provider

import ru.zf.pravka.data.Settings

// ONE price table for every API caller (ClaudeProvider and DictMiner used to
// keep their own copies - a price change would silently desync the miner's
// spend from everything else). USD per million tokens; cache pricing derives
// from the input price: 1h-TTL writes cost 2x, reads 0.1x - except where the
// model has its own read price.
object Pricing {

    private class Price(val input: Double, val output: Double, cacheRead: Double? = null) {
        val cacheRead: Double = cacheRead ?: (input * 0.1)
    }

    private val prices = mapOf(
        // Сонет 5 подешевел против 4.6 ($3/$15): считать по старой цене
        // значило бы завышать расход в статистике в полтора раза.
        Settings.MODEL_SONNET to Price(2.0, 10.0),
        Settings.MODEL_OPUS to Price(5.0, 25.0),
        // Fable 5.1: вдвое дороже Опуса, а чтение кэша — $0.25, не десятая
        // часть входа. Без строки здесь его вызовы считались бесплатными.
        Settings.MODEL_FABLE to Price(10.0, 50.0, cacheRead = 0.25),
    )

    fun costUsd(
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        cacheWriteTokens: Int = 0,
        cacheReadTokens: Int = 0,
    ): Double {
        val p = prices[model] ?: return 0.0
        val inputCost =
            (inputTokens + 2.0 * cacheWriteTokens) / 1_000_000.0 * p.input +
                cacheReadTokens / 1_000_000.0 * p.cacheRead
        return inputCost + outputTokens / 1_000_000.0 * p.output
    }
}
