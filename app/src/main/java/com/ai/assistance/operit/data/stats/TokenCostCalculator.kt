package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
import java.util.Locale
import com.ai.assistance.operit.data.dao.TokenUsageModelAggregateRow
import com.ai.assistance.operit.data.model.BillingMode

object TokenCostCalculator {
    fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    fun currentCost(
        row: TokenUsageModelAggregateRow,
        pricing: ResolvedTokenPricing,
        targetCurrency: PricingCurrency,
        usdToCnyRate: Double,
    ): TokenStatsCostSummary {
        val nativeAmount: Double
        val unknown: Long
        val nativeBreakdown = linkedMapOf<String, Double>()
        val normalizedModel = row.providerModel.lowercase(Locale.ROOT)
        val isDeepSeek = normalizedModel.contains("deepseek")
        val isDeepSeekPro = isDeepSeek && normalizedModel.contains("pro")
        val isDeepSeekFlash = isDeepSeek && !isDeepSeekPro &&
            (normalizedModel.contains("flash") || normalizedModel.endsWith(":deepseek-chat"))
        if ((isDeepSeekFlash || isDeepSeekPro) && pricing.billingMode != BillingMode.COUNT) {
            fun amount(tokens: Long, price: Double): Double =
                if (price > 0.0) tokens.toDouble() * price / 1_000_000.0 else 0.0
            val official = OfficialModelPricingCache.get(row.providerModel)
            val useOfficialUsd = official?.peakInput != null &&
                official.peakOutput != null && official.peakCachedInput != null &&
                official.offPeakInput != null && official.offPeakOutput != null &&
                official.offPeakCachedInput != null
            // Prefer live official rates; use documented CNY fallback only until first sync succeeds.
            val peakInputPrice = official?.peakInput ?: if (isDeepSeekPro) 9.0 else 2.0
            val peakCachedPrice = official?.peakCachedInput ?: if (isDeepSeekPro) 0.30 else 0.04
            val peakOutputPrice = official?.peakOutput ?: if (isDeepSeekPro) 27.0 else 8.0
            val offpeakInputPrice = official?.offPeakInput ?: if (isDeepSeekPro) 4.5 else 1.0
            val offpeakCachedPrice = official?.offPeakCachedInput ?: if (isDeepSeekPro) 0.15 else 0.02
            val offpeakOutputPrice = official?.offPeakOutput ?: if (isDeepSeekPro) 13.5 else 4.0
            val peakInput = amount(row.peakUncachedInputTokens, peakInputPrice)
            val peakCached = amount(row.peakCachedInputTokens, peakCachedPrice)
            val peakWrite = amount(row.peakCacheWriteTokens, peakInputPrice)
            val peakOutput = amount(row.peakOutputTokens, peakOutputPrice)
            val offpeakInput = amount((row.uncachedInputTokens - row.peakUncachedInputTokens).coerceAtLeast(0L), offpeakInputPrice)
            val offpeakCached = amount((row.cachedInputTokens - row.peakCachedInputTokens).coerceAtLeast(0L), offpeakCachedPrice)
            val offpeakWrite = amount((row.cacheWriteTokens - row.peakCacheWriteTokens).coerceAtLeast(0L), offpeakInputPrice)
            val offpeakOutput = amount((row.outputTokens - row.peakOutputTokens).coerceAtLeast(0L), offpeakOutputPrice)
            listOf(
                "peak_input" to peakInput, "peak_cache_hit" to peakCached,
                "peak_cache_write" to peakWrite, "peak_output" to peakOutput,
                "offpeak_input" to offpeakInput, "offpeak_cache_hit" to offpeakCached,
                "offpeak_cache_write" to offpeakWrite, "offpeak_output" to offpeakOutput,
            ).forEach { (key, value) -> if (value > 0.0) nativeBreakdown[key] = value }
            nativeAmount = nativeBreakdown.values.sum()
            unknown = if (row.uncachedInputKnown == 0L && row.cachedInputKnown == 0L &&
                row.cacheWriteKnown == 0L && row.outputKnown == 0L) row.requests else 0L
            val sourceCurrency = if (useOfficialUsd) PricingCurrency.USD else PricingCurrency.CNY
            val converted = TokenCostCurrency.convertTo(nativeAmount, sourceCurrency, targetCurrency, usdToCnyRate)
            return TokenStatsCostSummary(
                currency = targetCurrency,
                knownAmount = converted,
                unknownContributionCount = unknown,
                totalContributionCount = row.requests,
                rateUsed = usdToCnyRate,
                originalCurrencyAmounts = if (nativeAmount > 0.0) mapOf(sourceCurrency to nativeAmount) else emptyMap(),
                breakdown = nativeBreakdown.mapValues { (_, value) ->
                    TokenCostCurrency.convertTo(value, sourceCurrency, targetCurrency, usdToCnyRate)
                },
            )
        }
        if (pricing.billingMode == BillingMode.COUNT) {
            nativeAmount = pricing.pricePerRequest * row.requests
            if (nativeAmount > 0.0) nativeBreakdown["request"] = nativeAmount
            unknown = if (pricing.pricePerRequest > 0.0) 0L else row.requests
        } else {
            fun amount(tokens: Long, price: Double): Double =
                if (price > 0.0) tokens.toDouble() * price / 1_000_000.0 else 0.0

            val uncached = amount(row.uncachedInputTokens, pricing.inputPricePerMillion)
            val cached = amount(row.cachedInputTokens, pricing.cachedInputPricePerMillion)
            val cacheWrite = amount(row.cacheWriteTokens, pricing.cacheWritePricePerMillion)
            val output = amount(row.outputTokens, pricing.outputPricePerMillion)
            if (uncached > 0.0) nativeBreakdown["input"] = uncached
            if (cached > 0.0) nativeBreakdown["cache_hit"] = cached
            if (cacheWrite > 0.0) nativeBreakdown["cache_write"] = cacheWrite
            if (output > 0.0) nativeBreakdown["output"] = output
            nativeAmount = uncached + cached + cacheWrite + output

            var unknownRequests = 0L
            fun markUnknown(known: Long, price: Double) {
                if (price > 0.0) unknownRequests = maxOf(unknownRequests, row.requests - known)
            }
            markUnknown(row.uncachedInputKnown, pricing.inputPricePerMillion)
            markUnknown(row.cachedInputKnown, pricing.cachedInputPricePerMillion)
            markUnknown(row.cacheWriteKnown, pricing.cacheWritePricePerMillion)
            markUnknown(row.outputKnown, pricing.outputPricePerMillion)
            unknown = if (
                pricing.inputPricePerMillion <= 0.0 &&
                pricing.cachedInputPricePerMillion <= 0.0 &&
                pricing.cacheWritePricePerMillion <= 0.0 &&
                pricing.outputPricePerMillion <= 0.0
            ) row.requests else unknownRequests
        }
        val converted = TokenCostCurrency.convertTo(
            nativeAmount,
            pricing.currency,
            targetCurrency,
            usdToCnyRate,
        )
        return TokenStatsCostSummary(
            currency = targetCurrency,
            knownAmount = converted,
            unknownContributionCount = unknown,
            totalContributionCount = row.requests,
            rateUsed = usdToCnyRate,
            originalCurrencyAmounts =
                if (nativeAmount > 0.0) mapOf(pricing.currency to nativeAmount) else emptyMap(),
            breakdown = nativeBreakdown.mapValues { (_, amount) ->
                TokenCostCurrency.convertTo(amount, pricing.currency, targetCurrency, usdToCnyRate)
            },
        )
    }
}

object TokenCostCurrency {
    const val DEFAULT_USD_TO_CNY_RATE = 7.0

    fun convertTo(
        amount: Double,
        source: PricingCurrency,
        target: PricingCurrency,
        usdToCnyRate: Double,
    ): Double = when {
        source == target -> amount
        source == PricingCurrency.USD -> amount * usdToCnyRate
        else -> amount / usdToCnyRate
    }
}
