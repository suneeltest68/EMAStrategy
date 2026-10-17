package com.example.strategy

import io.github.cdimascio.dotenv.dotenv
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min

data class IronFlyLeg(
    val symbol: String,
    val type: String, // "SELL_CE", "BUY_CE", "SELL_PE", "BUY_PE"
    val strike: Double,
    val entryPrice: Double,
    var currentSl: Double,
    var isClosed: Boolean = false,
    var exitPrice: Double = 0.0
)

data class IronFlyPosition(
    val indexSymbol: String,
    val tradeDate: String,
    val soldCe: IronFlyLeg,
    val buyCeHedge: IronFlyLeg,
    val soldPe: IronFlyLeg,
    val buyPeHedge: IronFlyLeg,
    val deployedCapital: Double
) {
    fun allLegs(): List<IronFlyLeg> = listOf(soldCe, buyCeHedge, soldPe, buyPeHedge)
}

object IronFlyStrategyConfig {
    private val dotenv = dotenv { ignoreIfMissing = true }

    // High-impact event dates (Supports both dd-MM-yyyy and yyyy-MM-dd formats)
    // If HIGH_IMPACT_DATES is empty or not set, zero days are skipped (trades every eligible day).
    val highImpactDates: Set<LocalDate> by lazy {
        val envRaw = System.getenv("HIGH_IMPACT_DATES") ?: dotenv["HIGH_IMPACT_DATES"] ?: ""
        val dateSet = mutableSetOf<LocalDate>()
        
        if (envRaw.isNotEmpty()) {
            val rawList = envRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            for (dStr in rawList) {
                val parsed = parseFlexibleDate(dStr)
                if (parsed != null) {
                    dateSet.add(parsed)
                }
            }
        }
        dateSet
    }

    private fun parseFlexibleDate(dateStr: String): LocalDate? {
        return try {
            if (dateStr.contains("-")) {
                val parts = dateStr.split("-")
                if (parts[0].length == 4) {
                    LocalDate.parse(dateStr) // yyyy-MM-dd
                } else {
                    LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt()) // dd-MM-yyyy
                }
            } else if (dateStr.contains("/")) {
                val parts = dateStr.split("/")
                if (parts[0].length == 4) {
                    LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) // yyyy/MM/dd
                } else {
                    LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt()) // dd/MM/yyyy
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isHighImpactDay(date: LocalDate): Boolean {
        return highImpactDates.contains(date)
    }

    /**
     * Dynamically finds the weekly expiry DayOfWeek for an index (NIFTY or SENSEX) using Fyers API.
     */
    suspend fun getDynamicExpiryDay(
        viewModel: com.example.viewmodel.AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        targetDate: LocalDate
    ): DayOfWeek {
        val rangeFrom = targetDate.minusDays(15).toString()
        val yesterday = LocalDate.now().minusDays(1)
        val rawRangeTo = targetDate.plusDays(15)
        val rangeTo = if (rawRangeTo.isAfter(yesterday)) yesterday.toString() else rawRangeTo.toString()
        
        try {
            val expiryJson = viewModel.fetchHistoryExpiryDates(appId, token, indexSymbol, rangeFrom, rangeTo)
            if (expiryJson != null && expiryJson.has("data")) {
                val dataObj = expiryJson.getJSONObject("data")
                val expiries = dataObj.optJSONObject("expiry_dates")?.optJSONArray("options")
                    ?: dataObj.optJSONArray("expiry_dates")
                    ?: dataObj.optJSONArray("expiryData")
                
                if (expiries != null) {
                    val parsedDates = mutableListOf<LocalDate>()
                    for (i in 0 until expiries.length()) {
                        val expStr = expiries.getString(i)
                        val ld = parseExpiryDateString(expStr)
                        if (ld != null) parsedDates.add(ld)
                    }
                    // Find upcoming or matching expiry date for the week
                    val upcoming = parsedDates.filter { !it.isBefore(targetDate.minusDays(6)) }.sorted().firstOrNull()
                    if (upcoming != null) {
                        return upcoming.dayOfWeek
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback default: Thursday
        return DayOfWeek.THURSDAY
    }

    private fun parseExpiryDateString(dateStr: String): LocalDate? {
        return try {
            if (dateStr.contains("-")) {
                val parts = dateStr.split("-")
                if (parts[0].length == 4) {
                    LocalDate.parse(dateStr) // yyyy-MM-dd
                } else {
                    LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt()) // dd-MM-yyyy
                }
            } else if (dateStr.length >= 8) {
                val day = dateStr.substring(0, 2).toInt()
                val month = dateStr.substring(2, 4).toInt()
                val year = if (dateStr.length == 8) "20" + dateStr.substring(6, 8) else dateStr.substring(4, 8)
                LocalDate.of(year.toInt(), month, day)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Determines whether to trade NIFTY or SENSEX on a given date based on dynamically fetched expiry weekdays:
     * - Eligible: NIFTY weekly and SENSEX weekly options only.
     * - Trade index whose expiry falls on that day or the day before.
     * - Friday: no trade.
     * - Holiday shifts: trade on actual shifted expiry day and day before, no trade on Friday before.
     */
    suspend fun getEligibleIndexForDate(
        viewModel: com.example.viewmodel.AuthViewModel,
        appId: String,
        token: String,
        date: LocalDate
    ): String? {
        if (date.dayOfWeek == DayOfWeek.FRIDAY) return null

        val niftyExpiryDay = getDynamicExpiryDay(viewModel, appId, token, "NSE:NIFTY50-INDEX", date)
        val sensexExpiryDay = getDynamicExpiryDay(viewModel, appId, token, "BSE:SENSEX-INDEX", date)

        val niftyTradeDays = setOf(niftyExpiryDay, niftyExpiryDay.minus(1))
        if (niftyTradeDays.contains(date.dayOfWeek)) {
            return "NSE:NIFTY50-INDEX"
        }

        val sensexTradeDays = setOf(sensexExpiryDay, sensexExpiryDay.minus(1))
        if (sensexTradeDays.contains(date.dayOfWeek)) {
            return "BSE:SENSEX-INDEX"
        }

        return null
    }

    /**
     * Calculates premium gap % = ABS(CE - PE) / MIN(CE, PE) * 100
     */
    fun calculatePremiumGap(cePrice: Double, pePrice: Double): Double {
        if (min(cePrice, pePrice) <= 0.0) return 999.0
        return abs(cePrice - pePrice) / min(cePrice, pePrice) * 100.0
    }
}
