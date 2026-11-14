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
        val peakInput: Double? = null,
        val peakOutput: Double? = null,
        val peakCachedInput: Double? = null,
        val offPeakInput: Double? = null,
        val offPeakOutput: Double? = null,
        val offPeakCachedInput: Double? = null,
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
        // Reject the previously mis-scaled DeepSeek official snapshot (prices were multiplied
        // by 1,000,000 twice). Until the corrected sync refreshes it, calculator uses safe CNY fallbacks.
        if (row.optString("source").contains("DeepSeek official pricing page", ignoreCase = true)) {
            val peakInput = row.optDouble("peakInput", Double.NaN)
            val peakOutput = row.optDouble("peakOutput", Double.NaN)
            val offPeakInput = row.optDouble("offPeakInput", Double.NaN)
            val offPeakOutput = row.optDouble("offPeakOutput", Double.NaN)
            if (input > 100.0 || output > 100.0 ||
                peakInput > 100.0 || peakOutput > 100.0 ||
                offPeakInput > 100.0 || offPeakOutput > 100.0
            ) return null
        }
        return Price(
            input = input,
            output = output,
            cachedInput = row.optDouble("cachedInput", Double.NaN).takeIf { it.isFinite() && it >= 0 },
            cacheWrite = row.optDouble("cacheWrite", Double.NaN).takeIf { it.isFinite() && it >= 0 },
            source = row.optString("source", ""),
            updatedAtMs = prefs.getLong(UPDATED, 0L),
            peakInput = row.optDouble("peakInput", Double.NaN).takeIf { it.isFinite() && it >= 0 },
            peakOutput = row.optDouble("peakOutput", Double.NaN).takeIf { it.isFinite() && it >= 0 },
            peakCachedInput = row.optDouble("peakCachedInput", Double.NaN).takeIf { it.isFinite() && it >= 0 },
            offPeakInput = row.optDouble("offPeakInput", Double.NaN).takeIf { it.isFinite() && it >= 0 },
            offPeakOutput = row.optDouble("offPeakOutput", Double.NaN).takeIf { it.isFinite() && it >= 0 },
            offPeakCachedInput = row.optDouble("offPeakCachedInput", Double.NaN).takeIf { it.isFinite() && it >= 0 },
        )
    }

    fun getChineseHolidayDates(context: Context): List<String> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("china_public_holidays", null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val date = array.optString(i)
                    if (date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) add(date)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun replaceChineseHolidayDates(context: Context, dates: Set<String>): Boolean {
        if (dates.isEmpty()) return false
        val array = org.json.JSONArray()
        dates.sorted().forEach { array.put(it) }
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("china_public_holidays", array.toString()).commit()
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
