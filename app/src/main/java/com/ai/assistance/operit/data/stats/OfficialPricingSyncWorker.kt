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
                    prices.put("$providerId:$id", row)
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
