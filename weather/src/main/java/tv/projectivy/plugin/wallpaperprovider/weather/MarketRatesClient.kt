package tv.projectivy.plugin.wallpaperprovider.weather

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Small, cached market-data reader for the Projectivy home-screen panel.
 *
 * Gold: UAE Dahab public UAE gold benchmark API (spot-derived, before making
 * charges and VAT). FX: open.er-api.com mid-market AED/INR reference.
 */
data class MarketRates(
    val gold24k: Double,
    val gold22k: Double,
    val aedToInr: Double,
    val goldUpdatedAt: Long,
    val fxUpdatedAt: Long
)

object MarketRatesClient {
    private const val TAG = "MarketRates"
    private const val PREFS = "market_rates_cache"
    private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L

    private const val GOLD_URL = "https://uae-dahab.com/api/gold-prices"
    private const val FX_URL = "https://open.er-api.com/v6/latest/AED"

    @Volatile private var lastAttemptAt = 0L
    @Volatile private var memoryCache: MarketRates? = null

    @Volatile private var refreshInFlight = false

    fun cached(context: Context): MarketRates? {
        return memoryCache ?: readCache(context.applicationContext).also { memoryCache = it }
    }

    fun refreshInBackground(context: Context, onUpdated: () -> Unit) {
        synchronized(this) {
            if (refreshInFlight || System.currentTimeMillis() - lastAttemptAt < REFRESH_INTERVAL_MS) return
            refreshInFlight = true
            lastAttemptAt = System.currentTimeMillis()
        }
        Thread({
            try {
                val before = cached(context)
                val after = fetchNow(context.applicationContext, before)
                if (after != null && after != before) onUpdated()
            } finally {
                synchronized(this) { refreshInFlight = false }
            }
        }, "MarketRatesRefresh").apply { isDaemon = true }.start()
    }

    private fun fetchNow(context: Context, cached: MarketRates?): MarketRates? {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()

        var gold24 = cached?.gold24k
        var gold22 = cached?.gold22k
        var goldAt = cached?.goldUpdatedAt ?: 0L
        var fx = cached?.aedToInr
        var fxAt = cached?.fxUpdatedAt ?: 0L

        try {
            val gold = fetchJson(GOLD_URL)
            val karats = gold.optJSONObject("karats")
            val candidate24 = karats?.optDouble("24K", Double.NaN) ?: Double.NaN
            val candidate22 = karats?.optDouble("22K", Double.NaN) ?: Double.NaN
            val stale = gold.optBoolean("isStale", false)
            if (!stale && candidate24.isFinite() && candidate24 > 0.0 &&
                candidate22.isFinite() && candidate22 > 0.0) {
                gold24 = candidate24
                gold22 = candidate22
                goldAt = parseIsoTime(gold.optString("ts"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Gold feed unavailable; retaining last good value", t)
        }

        try {
            val rates = fetchJson(FX_URL)
            val candidate = rates.optJSONObject("rates")?.optDouble("INR", Double.NaN)
                ?: Double.NaN
            if (rates.optString("result") == "success" && candidate.isFinite() && candidate > 0.0) {
                fx = candidate
                fxAt = rates.optLong("time_last_update_unix", now / 1000L) * 1000L
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AED/INR feed unavailable; retaining last good value", t)
        }

        val result = if (gold24 != null && gold22 != null && fx != null) {
            MarketRates(gold24, gold22, fx, goldAt, fxAt)
        } else {
            cached
        }

        if (result != null && result != cached) {
            memoryCache = result
            saveCache(appContext, result)
        }
        return result
    }

    private fun fetchJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Projectivy-Market-Panel/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code from $address")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseIsoTime(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun readCache(context: Context): MarketRates? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = p.getFloat("gold24", Float.NaN).toDouble()
        val b = p.getFloat("gold22", Float.NaN).toDouble()
        val c = p.getFloat("aedInr", Float.NaN).toDouble()
        if (!a.isFinite() || !b.isFinite() || !c.isFinite()) return null
        return MarketRates(
            a, b, c,
            p.getLong("goldAt", 0L),
            p.getLong("fxAt", 0L)
        )
    }

    private fun saveCache(context: Context, rates: MarketRates) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("gold24", rates.gold24k.toFloat())
            .putFloat("gold22", rates.gold22k.toFloat())
            .putFloat("aedInr", rates.aedToInr.toFloat())
            .putLong("goldAt", rates.goldUpdatedAt)
            .putLong("fxAt", rates.fxUpdatedAt)
            .apply()
    }

    fun timeLabel(timestamp: Long): String {
        if (timestamp <= 0L) return "—"
        val format = SimpleDateFormat("HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Dubai")
        }
        return format.format(Date(timestamp)) + " GST"
    }
}
