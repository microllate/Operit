package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Refreshes the public models.dev catalog (aggregated model metadata with provider pricing).
 * It is a direct HTTPS request and does not call any inference/model API.
 */
class OfficialPricingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            OfficialModelPricingCache.initialize(applicationContext)
            val request = Request.Builder()
                .url("https://models.dev/api.json")
                .header("Accept", "application/json")
                .header("User-Agent", "Operit-OfficialPricingSync/1.0")
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return if (runAttemptCount < 3) Result.retry() else Result.failure()
                response.body?.string() ?: return Result.retry()
            }
            // Refresh the current-year Chinese holiday calendar without inference calls.
            runCatching {
                val year = java.time.LocalDate.now().year
                val holidayDates = linkedSetOf<String>()
                for (holidayYear in (year - 2)..(year + 2)) {
                    val holidayJson = client.newCall(
                        Request.Builder()
                            .url("https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/${holidayYear}.json")
                            .header("Accept", "application/json")
                            .build()
                    ).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    } ?: continue
                    val days = JSONObject(holidayJson).optJSONArray("days") ?: continue
                    for (i in 0 until days.length()) {
                        val day = days.optJSONObject(i) ?: continue
                        if (day.optBoolean("isOffDay", false)) {
                            val date = day.optString("date")
                            if (date.length == 10 && date[4] == '-' && date[7] == '-') holidayDates.add(date)
                        }
                    }
                }
                OfficialModelPricingCache.replaceChineseHolidayDates(applicationContext, holidayDates)
            }
            val catalog = JSONObject(body)
            val prices = JSONObject()
            // DeepSeek's official rate card is authoritative for its peak/off-peak tiers.
            // The first 12 USD amounts are ordered by the official pricing table:
            // cache hit off-peak, cache hit peak, cache miss off-peak, cache miss peak,
            // output off-peak, output peak; each tier lists Flash then Pro.
            runCatching {
                val officialHtml = client.newCall(
                    Request.Builder()
                        .url("https://api-docs.deepseek.com/quick_start/pricing/")
                        .header("Accept", "text/html")
                        .build()
                ).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                } ?: return@runCatching
                if (!officialHtml.contains("deepseek-flash") || !officialHtml.contains("OFF-PEAK") ||
                    !officialHtml.contains("PEAK")) return@runCatching
                val amounts = Regex("\\$([0-9]+(?:\\.[0-9]+)?)")
                    .findAll(officialHtml.substringBefore("Search & Fetch Pricing"))
                    .mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }
                    .toList()
                if (amounts.size < 12) return@runCatching
                // DeepSeek's official page already quotes USD per 1M tokens.
                // Keep the values in that unit; TokenCostCalculator divides token count by 1M.
                fun officialRow(index: Int, prefix: String): JSONObject = JSONObject()
                    .put("input", amounts[index])
                    .put("cachedInput", amounts[if (prefix == "flash") 0 else 1])
                    .put("output", amounts[if (prefix == "flash") 8 else 9])
                    .put("peakInput", amounts[if (prefix == "flash") 6 else 7])
                    .put("peakCachedInput", amounts[if (prefix == "flash") 2 else 3])
                    .put("peakOutput", amounts[if (prefix == "flash") 10 else 11])
                    .put("offPeakInput", amounts[if (prefix == "flash") 4 else 5])
                    .put("offPeakCachedInput", amounts[if (prefix == "flash") 0 else 1])
                    .put("offPeakOutput", amounts[if (prefix == "flash") 8 else 9])
                    .put("source", "DeepSeek official pricing page (USD per 1M tokens)")
                prices.put("deepseek:deepseek-flash", officialRow(4, "flash"))
                prices.put("deepseek-flash", JSONObject(prices.getJSONObject("deepseek:deepseek-flash").toString()))
                prices.put("deepseek:deepseek-v4-pro", officialRow(5, "pro"))
                prices.put("deepseek-v4-pro", JSONObject(prices.getJSONObject("deepseek:deepseek-v4-pro").toString()))
            }

            val providers = catalog.keys()
            while (providers.hasNext()) {
                val providerId = providers.next()
                val provider = catalog.optJSONObject(providerId) ?: continue
                val models = provider.optJSONArray("models") ?: continue
                for (i in 0 until models.length()) {
                    val model = models.optJSONObject(i) ?: continue
                    val id = model.optString("id").trim().lowercase()
                    val cost = model.optJSONObject("cost") ?: continue
                    val input = cost.optDouble("input", Double.NaN)
                    val output = cost.optDouble("output", Double.NaN)
                    if (id.isBlank() || !input.isFinite() || !output.isFinite() ||
                        input < 0 || output < 0 || (input == 0.0 && output == 0.0)) continue
                    // models.dev prices are USD per token; app calculations use USD per million tokens.
                    val row = JSONObject()
                        .put("input", input * 1_000_000.0)
                        .put("output", output * 1_000_000.0)
                        .put("source", "models.dev:$providerId")
                    cost.optDouble("cache_read", Double.NaN)
                        .takeIf { it.isFinite() && it >= 0 }
                        ?.let { row.put("cachedInput", it * 1_000_000.0) }
                    cost.optDouble("cache_write", Double.NaN)
                        .takeIf { it.isFinite() && it >= 0 }
                        ?.let { row.put("cacheWrite", it * 1_000_000.0) }
                    val providerModelKey = "$providerId:$id"
                    // Preserve successfully parsed official DeepSeek prices over catalog fallbacks.
                    if (!prices.has(providerModelKey)) prices.put(providerModelKey, row)
                    // Also keep a model-only alias for provider names normalized by Operit.
                    if (!prices.has(id)) prices.put(id, JSONObject(row.toString()))
                }
            }
            if (OfficialModelPricingCache.replaceValidated(applicationContext, prices, System.currentTimeMillis())) {
                Result.success()
            } else {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
