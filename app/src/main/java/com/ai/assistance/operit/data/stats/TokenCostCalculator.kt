package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
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
