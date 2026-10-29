package com.ai.assistance.operit.data.stats

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** Cached public catalog prices; user-entered prices are stored separately and always win. */
object OfficialModelPricingCache {
    data class Price(
        val input: Double,
        val output: Double,
        val cachedInput: Double?,
        val cacheWrite: Double?,
        val source: String,
        val updatedAtMs: Long,
    )

    private const val PREFS = "official_model_pricing_cache"
    private const val DATA = "prices_json"
    private const val UPDATED = "updated_at_ms"

    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun get(providerModel: String): Price? {
        val context = appContext ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = runCatching { JSONObject(prefs.getString(DATA, "{}") ?: "{}") }.getOrNull() ?: return null
        val normalized = providerModel.trim().lowercase(Locale.ROOT)
        val model = normalized.substringAfter(':', normalized)
        val row = json.optJSONObject(normalized) ?: json.optJSONObject(model) ?: return null
        val input = row.optDouble("input", Double.NaN)
        val output = row.optDouble("output", Double.NaN)
        if (!input.isFinite() || !output.isFinite() || input < 0 || output < 0) return null
        return Price(
            input = input,
            output = output,
            cachedInput = row.optDouble("cachedInput", Double.NaN).takeIf { it.isFinite() && it >= 0 },
            cacheWrite = row.optDouble("cacheWrite", Double.NaN).takeIf { it.isFinite() && it >= 0 },
            source = row.optString("source", ""),
            updatedAtMs = prefs.getLong(UPDATED, 0L),
        )
    }

    fun replaceValidated(context: Context, prices: JSONObject, updatedAtMs: Long): Boolean {
        if (prices.length() == 0) return false
        // Validate every row before replacing the previous known-good snapshot.
        val keys = prices.keys()
        while (keys.hasNext()) {
            val row = prices.optJSONObject(keys.next()) ?: return false
            val input = row.optDouble("input", Double.NaN)
            val output = row.optDouble("output", Double.NaN)
            if (!input.isFinite() || !output.isFinite() || input < 0 || output < 0) return false
        }
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(DATA, prices.toString()).putLong(UPDATED, updatedAtMs).commit()
    }
}
