package ru.zf.pravka.provider

import ru.zf.pravka.data.Settings

// ONE price table for every API caller (ClaudeProvider and DictMiner used to
// keep their own copies - a price change would silently desync the miner's
// spend from everything else). USD per million tokens; cache pricing derives
// from the input price: 1h-TTL writes cost 2x, reads 0.1x.
object Pricing {

    private val prices = mapOf(
        Settings.MODEL_SONNET to (3.0 to 15.0),
        Settings.MODEL_OPUS to (5.0 to 25.0),
    )

    fun costUsd(
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        cacheWriteTokens: Int = 0,
        cacheReadTokens: Int = 0,
    ): Double {
        val (pIn, pOut) = prices[model] ?: return 0.0
        val inputCost =
            (inputTokens + 2.0 * cacheWriteTokens + 0.1 * cacheReadTokens) / 1_000_000.0 * pIn
        return inputCost + outputTokens / 1_000_000.0 * pOut
    }
}
