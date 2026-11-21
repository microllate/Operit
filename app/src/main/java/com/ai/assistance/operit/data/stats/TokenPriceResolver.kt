package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode

data class ModelPriceSettings(
    val billingMode: BillingMode? = null,
    val currency: PricingCurrency? = null,
    val inputPricePerMillion: Double? = null,
    val cachedInputPricePerMillion: Double? = null,
    val cacheWritePricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val pricePerRequest: Double? = null,
) {
    fun hasAnyUserSetting(): Boolean =
        billingMode != null ||
            currency != null ||
            inputPricePerMillion != null ||
            cachedInputPricePerMillion != null ||
            cacheWritePricePerMillion != null ||
            outputPricePerMillion != null ||
            pricePerRequest != null
}

data class TokenPriceSettingsSnapshot(
    val providerModels: Map<String, ModelPriceSettings?>,
    val configs: Map<String, ModelPriceSettings>,
) {
    fun settingFor(providerModel: String, configId: String?): ModelPriceSettings? {
        val model = providerModels[providerModel]
        val config = configId?.let { configs[tokenPriceConfigKey(providerModel, it)] }
        if (config == null) return model
        return ModelPriceSettings(
            billingMode = config.billingMode ?: model?.billingMode,
            currency = config.currency ?: model?.currency,
            inputPricePerMillion = config.inputPricePerMillion ?: model?.inputPricePerMillion,
            cachedInputPricePerMillion =
                config.cachedInputPricePerMillion ?: model?.cachedInputPricePerMillion,
            cacheWritePricePerMillion =
                config.cacheWritePricePerMillion ?: model?.cacheWritePricePerMillion,
            outputPricePerMillion = config.outputPricePerMillion ?: model?.outputPricePerMillion,
            pricePerRequest = config.pricePerRequest ?: model?.pricePerRequest,
        )
    }
}

internal fun tokenPriceConfigKey(providerModel: String, configId: String): String =
    "$providerModel\u001f$configId"

data class ResolvedTokenPricing(
    val billingMode: BillingMode,
    val currency: PricingCurrency,
    val inputPricePerMillion: Double,
    val cachedInputPricePerMillion: Double,
    val cacheWritePricePerMillion: Double,
    val outputPricePerMillion: Double,
    val pricePerRequest: Double,
    val source: PricingSource,
)

/** Resolves only the current price: user setting first, then the built-in model table. */
object TokenPriceResolver {
    fun resolve(
        providerModel: String,
        user: ModelPriceSettings?,
    ): ResolvedTokenPricing {
        val defaults = DefaultModelPricingCollect.getDefaultPricing(providerModel)
        val official = OfficialModelPricingCache.get(providerModel)
        val hasUserPrice = user?.hasAnyUserSetting() == true
        val useOfficial = !hasUserPrice && official != null
        return ResolvedTokenPricing(
            billingMode = user?.billingMode ?: defaults.billingMode,
            currency = user?.currency ?: if (useOfficial) PricingCurrency.USD else defaults.currency,
            inputPricePerMillion = user?.inputPricePerMillion
                ?: if (useOfficial) official!!.input else defaults.inputPricePerMillion,
            cachedInputPricePerMillion = user?.cachedInputPricePerMillion
                ?: user?.inputPricePerMillion
                ?: if (useOfficial) (official!!.cachedInput ?: official.input) else defaults.cachedInputPricePerMillion,
            cacheWritePricePerMillion = user?.cacheWritePricePerMillion
                ?: user?.inputPricePerMillion
                ?: if (useOfficial) (official!!.cacheWrite ?: official.input) else defaults.inputPricePerMillion,
            outputPricePerMillion = user?.outputPricePerMillion
                ?: if (useOfficial) official!!.output else defaults.outputPricePerMillion,
            pricePerRequest = user?.pricePerRequest ?: defaults.pricePerRequest,
            source = if (hasUserPrice) PricingSource.USER else PricingSource.BUILT_IN,
        )
    }
}
