package com.example.util

import com.example.strategy.Candle
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

object OptionUtils {

    private val optionChainCache = mutableMapOf<String, JSONObject>()
    private val expiryCache = mutableMapOf<String, JSONObject>()
    private val contractsCache = mutableMapOf<String, JSONObject>()
    private val fnoHistoryCache = mutableMapOf<String, JSONObject>()

    /**
     * Identifies the At-The-Money (ATM) strike price given the current underlying spot price and strike interval.
     * - NIFTY strike interval: 50
     * - BANKNIFTY strike interval: 100
     *
     * @param spotPrice Current underlying spot price
     * @param strikeInterval Strike interval (default 50 for NIFTY)
     * @return Nearest ATM strike price
     */
    fun calculateAtmStrike(spotPrice: Double, strikeInterval: Int = 50): Int {
        return (round(spotPrice / strikeInterval) * strikeInterval).toInt()
    }

    /**
     * Resolves option contract details around ATM (ATM, ITM, OTM).
     */
    fun getStrikeWithOffset(spotPrice: Double, offset: Int, strikeInterval: Int = 50): Int {
        val atm = calculateAtmStrike(spotPrice, strikeInterval)
        return atm + (offset * strikeInterval)
    }

    /**
     * Resolves the exact weekly option symbol strictly via live Fyers option chain lookup.
     */
    suspend fun resolveWeeklyOptionSymbol(
        viewModel: com.example.viewmodel.AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        spotPrice: Double,
        optionType: String,
        expiryIndex: Int = 1
    ): String {
        val strikeInterval = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true)) 100 else 50
        val atmStrike = calculateAtmStrike(spotPrice, strikeInterval)

        val cacheKey = indexSymbol
        var optionChain = optionChainCache[cacheKey]
        if (optionChain == null) {
            var attempts = 0
            while (attempts < 3 && optionChain == null) {
                kotlinx.coroutines.delay(500L * (attempts + 1))
                optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10)
                attempts++
            }
            if (optionChain == null) {
                throw IllegalStateException("Failed to fetch option chain from Fyers API for symbol $indexSymbol after retries (Rate limit 429)")
            }
            optionChainCache[cacheKey] = optionChain
        }

        var expiryDateStr: String? = null
        if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            if (dataObj.has("expiryData")) {
                val expiryArray = dataObj.getJSONArray("expiryData")
                if (expiryIndex in 0 until expiryArray.length()) {
                    val expiryObj = expiryArray.getJSONObject(expiryIndex)
                    expiryDateStr = expiryObj.optString("date", "") // e.g., "13-10-2026"
                }
            }
        }

        if (expiryDateStr.isNullOrEmpty() || expiryDateStr.length < 10) {
            // Fallback to searching optionsChain if expiryData not present
            if (optionChain.has("data")) {
                val dataObj = optionChain.getJSONObject("data")
                if (dataObj.has("optionsChain")) {
                    val optionsArray = dataObj.getJSONArray("optionsChain")
                    for (i in 0 until optionsArray.length()) {
                        val optObj = optionsArray.getJSONObject(i)
                        val strike = optObj.optDouble("strike_price", 0.0)
                        val symbol = optObj.optString("symbol", "")
                        val type = optObj.optString("option_type", "")

                        if (abs(strike - atmStrike.toDouble()) < 1.0 && type.equals(optionType, ignoreCase = true)) {
                            if (symbol.isNotEmpty()) return symbol
                        }
                    }
                }
            }
            throw IllegalStateException("Could not find expiry date for expiryIndex $expiryIndex or matching option symbol in option chain response")
        }

        val day = expiryDateStr.substring(0, 2)
        val monthNum = expiryDateStr.substring(3, 5)
        val year = expiryDateStr.substring(8, 10)
        val monthLetter = when (monthNum) {
            "01" -> "F"
            "02" -> "G"
            "03" -> "H"
            "04" -> "J"
            "05" -> "K"
            "06" -> "L"
            "07" -> "M"
            "08" -> "N"
            "09" -> "P"
            "10" -> "O"
            "11" -> "Q"
            "12" -> "R"
            else -> ""
        }

        if (monthLetter.isEmpty()) {
            throw IllegalStateException("Invalid month number $monthNum in expiry date $expiryDateStr")
        }

        val expiryCode = "$year$monthLetter$day" // e.g. "26O13"
        val underlyingPrefix = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true)) "NSE:BANKNIFTY" else "NSE:NIFTY"

        return "$underlyingPrefix$expiryCode$atmStrike$optionType"
    }

    /**
     * Resolves and fetches historical 1-min candle data for an EXPIRED weekly option contract
     * using Fyers' dedicated expired F&O APIs with in-memory caching to eliminate redundant network calls.
     */
    suspend fun resolveAndFetchExpiredDerivativeCandles(
        viewModel: com.example.viewmodel.AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        spotPrice: Double,
        optionType: String,
        tradeDate: String
    ): List<Candle> {
        val strikeInterval = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true)) 100 else 50
        val atmStrike = calculateAtmStrike(spotPrice, strikeInterval)

        val rangeFrom = LocalDate.parse(tradeDate).minusMonths(1).toString()
        val rangeTo = LocalDate.parse(tradeDate).plusDays(7).toString()
        val expiryCacheKey = "$indexSymbol-$rangeFrom-$rangeTo"

        var expiryJson = expiryCache[expiryCacheKey]
        if (expiryJson == null) {
            println("   • [DEBUG] Resolving expiry dates for tradeDate=$tradeDate (range: $rangeFrom to $rangeTo)...")
            expiryJson = viewModel.fetchHistoryExpiryDates(appId, token, indexSymbol, rangeFrom, rangeTo)
            if (expiryJson != null) expiryCache[expiryCacheKey] = expiryJson
        }

        var targetExpiry = tradeDate
        if (expiryJson != null && expiryJson.has("data")) {
            val dataObj = expiryJson.getJSONObject("data")
            if (dataObj.has("expiry_dates")) {
                val optionsExpiries = dataObj.getJSONObject("expiry_dates").optJSONArray("options")
                if (optionsExpiries != null) {
                    var found = false
                    for (i in 0 until optionsExpiries.length()) {
                        val expDate = optionsExpiries.getString(i)
                        if (expDate >= tradeDate) {
                            targetExpiry = expDate
                            found = true
                            break
                        }
                    }
                    if (!found && optionsExpiries.length() > 0) {
                        targetExpiry = optionsExpiries.getString(optionsExpiries.length() - 1)
                    }
                }
            }
        }

        val contractsCacheKey = "$indexSymbol-$targetExpiry"
        var contractsJson = contractsCache[contractsCacheKey]
        if (contractsJson == null) {
            println("   • [DEBUG] Fetching Underlying Expired Contracts for Expiry: $targetExpiry...")
            contractsJson = viewModel.fetchHistoryUnderlyingSymbols(appId, token, indexSymbol, targetExpiry)
            if (contractsJson != null) contractsCache[contractsCacheKey] = contractsJson
        }

        var optionSymbol = ""
        if (contractsJson != null && contractsJson.has("data")) {
            val dataObj = contractsJson.getJSONObject("data")
            if (dataObj.has("contracts")) {
                val optionsArray = dataObj.getJSONObject("contracts").optJSONArray("options")
                if (optionsArray != null) {
                    val strikeStr = atmStrike.toString()
                    for (i in 0 until optionsArray.length()) {
                        val sym = optionsArray.getString(i)
                        if (sym.contains(strikeStr) && sym.endsWith(optionType)) {
                            optionSymbol = sym
                            break
                        }
                    }
                }
            }
        }

        if (optionSymbol.isEmpty()) {
            throw IllegalStateException("Could not resolve expired contract symbol for strike $atmStrike $optionType on expiry $targetExpiry for date $tradeDate.")
        }

        val fnoCacheKey = "$optionSymbol-$tradeDate"
        var fnoHistory = fnoHistoryCache[fnoCacheKey]
        if (fnoHistory == null) {
            println("   • [DEBUG] Fetching F&O Expired Data for $optionSymbol on $tradeDate...")
            fnoHistory = viewModel.fetchHistoryFNOExpired(
                appId = appId,
                accessToken = token,
                fnoSymbol = optionSymbol,
                rangeFrom = tradeDate,
                rangeTo = tradeDate,
                resolution = "1"
            )
            if (fnoHistory != null) fnoHistoryCache[fnoCacheKey] = fnoHistory
        }

        if (fnoHistory == null || !fnoHistory.has("candles") || fnoHistory.optJSONArray("candles")?.length() == 0) {
            throw IllegalStateException("Failed to fetch FNO expired history for $optionSymbol on $tradeDate.")
        }

        return parseExpiredCandles(fnoHistory)
    }

    private fun parseExpiredCandles(json: JSONObject): List<Candle> {
        val candles = mutableListOf<Candle>()
        try {
            val candlesArray = json.optJSONArray("candles") ?: return candles
            for (i in 0 until candlesArray.length()) {
                val item = candlesArray.getJSONArray(i)
                val epoch = item.optLong(0, 0L)
                val open = item.optDouble(1, 0.0)
                val high = item.optDouble(2, 0.0)
                val low = item.optDouble(3, 0.0)
                val close = item.optDouble(4, 0.0)
                val volume = item.optDouble(5, 0.0)

                val zdt = Instant.ofEpochSecond(epoch).atZone(ZoneId.of("Asia/Kolkata"))
                val timeStr = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

                candles.add(Candle(timestamp = timeStr, open = open, high = high, low = low, close = close, volume = volume))
            }
        } catch (e: Exception) {
            throw RuntimeException("Error parsing expired F&O candles: ${e.message}")
        }
        return candles
    }

    /**
     * Parses an option chain JSONObject to find the exact option contract symbol matching strike and type (CE/PE).
     */
    fun findOptionSymbolFromChain(optionChainJson: JSONObject, targetStrike: Int, optionType: String): String? {
        try {
            val optionsArray = optionChainJson.optJSONArray("options") ?: optionChainJson.optJSONArray("data") ?: return null
            for (i in 0 until optionsArray.length()) {
                val opt = optionsArray.getJSONObject(i)
                val strike = opt.optInt("strikePrice", opt.optInt("strike", 0))
                val symbol = opt.optString("symbol", opt.optString("fyToken", ""))
                val type = opt.optString("optionType", opt.optString("instrumentType", ""))

                if (strike == targetStrike && symbol.isNotEmpty() && (type.equals(optionType, ignoreCase = true) || symbol.endsWith(optionType))) {
                    return symbol
                }
            }
        } catch (e: Exception) {
            println("Error parsing option chain for strike $targetStrike $optionType: ${e.message}")
        }
        return null
    }
}
