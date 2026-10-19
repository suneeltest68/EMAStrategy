package com.example.util

import com.example.strategy.Candle
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

data class ExpiredDerivativeResult(
    val symbol: String,
    val candles: List<Candle>
)

object OptionUtils {

    private val optionChainCache = mutableMapOf<String, JSONObject>()
    private val expiryCache = mutableMapOf<String, JSONObject>()
    private val contractsCache = mutableMapOf<String, JSONObject>()
    private val fnoHistoryCache = mutableMapOf<String, JSONObject>()

    /**
     * Identifies the At-The-Money (ATM) strike price given the current underlying spot price and strike interval.
     * - NIFTY strike interval: 50
     * - BANKNIFTY strike interval: 100
     * - SENSEX strike interval: 100
     *
     * @param spotPrice Current underlying spot price
     * @param strikeInterval Strike interval
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
     * @param expiryIndex 0 for current week (1st), 1 for bi-weekly (2nd), 2 for 3rd week, etc.
     */
    suspend fun resolveWeeklyOptionSymbol(
        viewModel: com.example.viewmodel.AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        spotPrice: Double,
        optionType: String,
        expiryIndex: Int = 0,
        useCache: Boolean = true
    ): String {
        val strikeInterval = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true) || indexSymbol.contains("SENSEX", ignoreCase = true)) 100 else 50
        val atmStrike = calculateAtmStrike(spotPrice, strikeInterval)

        val cacheKey = indexSymbol
        var optionChain = if (useCache) optionChainCache[cacheKey] else null
        if (optionChain == null) {
            var attempts = 0
            while (attempts < 3 && optionChain == null) {
                if (attempts > 0) kotlinx.coroutines.delay(500L * attempts)
                optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10, useCache = useCache)
                attempts++
            }
            if (optionChain == null) {
                throw IllegalStateException("Failed to fetch option chain from Fyers API for symbol $indexSymbol after retries (Rate limit 429)")
            }
            if (useCache) {
                optionChainCache[cacheKey] = optionChain
            }
        }

        var expiryDateStr: String? = null
        if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            val expiryArray = dataObj.optJSONArray("expiryData") ?: dataObj.optJSONArray("expiry_dates")
            if (expiryArray != null && expiryIndex in 0 until expiryArray.length()) {
                val expiryObj = expiryArray.optJSONObject(expiryIndex)
                expiryDateStr = expiryObj?.optString("date", expiryObj.optString("expiry", ""))
                    ?: expiryArray.optString(expiryIndex, "")
            }
        }

        if (expiryDateStr.isNullOrEmpty() || expiryDateStr.length < 10) {
            throw IllegalStateException("Could not find expiry date for expiryIndex $expiryIndex in option chain response")
        }

        val day = expiryDateStr.substring(0, 2)
        val monthNum = expiryDateStr.substring(3, 5)
        val fullYear = if (expiryDateStr.length >= 10) expiryDateStr.substring(6, 10) else "20" + expiryDateStr.substring(8, 10)
        val shortYear = fullYear.substring(2, 4)
        val isoDate = "$fullYear-$monthNum-$day"

        val monthCode = when (monthNum) {
            "01" -> "1"
            "02" -> "2"
            "03" -> "3"
            "04" -> "4"
            "05" -> "5"
            "06" -> "6"
            "07" -> "7"
            "08" -> "8"
            "09" -> "9"
            "10" -> "O"
            "11" -> "N"
            "12" -> "D"
            else -> ""
        }

        if (monthCode.isEmpty()) {
            throw IllegalStateException("Invalid month number $monthNum in expiry date $expiryDateStr")
        }

        val targetExpiryCode = "$shortYear$monthCode$day"
        val underlyingPrefix = when {
            indexSymbol.contains("BANKNIFTY", ignoreCase = true) -> "NSE:BANKNIFTY"
            indexSymbol.contains("SENSEX", ignoreCase = true) -> "BSE:SENSEX"
            else -> "NSE:NIFTY"
        }

        // First attempt: Search optionsChain array for exact matching contract with targetExpiryCode or isoDate
        if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            val optionsArray = dataObj.optJSONArray("optionsChain") ?: dataObj.optJSONArray("options")
            if (optionsArray != null) {
                for (i in 0 until optionsArray.length()) {
                    val optObj = optionsArray.getJSONObject(i)
                    val strike = optObj.optDouble("strike_price", optObj.optDouble("strikePrice", optObj.optDouble("strike", 0.0)))
                    val symbol = optObj.optString("symbol", "")
                    val type = optObj.optString("option_type", optObj.optString("optionType", ""))
                    val optExpiry = optObj.optString("expiry", optObj.optString("expiry_date", ""))

                    val isStrikeMatch = abs(strike - atmStrike.toDouble()) < 1.0
                    val isTypeMatch = type.equals(optionType, ignoreCase = true) || symbol.endsWith(optionType)
                    val isExpiryMatch = symbol.contains(targetExpiryCode) ||
                            (optExpiry.isNotEmpty() && (optExpiry == isoDate || optExpiry == expiryDateStr || optExpiry.contains(isoDate)))

                    if (isStrikeMatch && isTypeMatch && isExpiryMatch && symbol.isNotEmpty()) {
                        return symbol
                    }
                }
            }
        }

        println("   • [DEBUG] Fetching Underlying Expired Contracts for Expiry: $expiryDateStr...")

        // Second attempt: Return constructed symbol
        return "$underlyingPrefix$targetExpiryCode$atmStrike$optionType"
    }

    /**
     * Extracts the expiry date string (e.g. "27-10-2026") from an option chain response once per day.
     */
    fun extractExpiryDateString(optionChain: JSONObject, expiryIndex: Int = 0): String {
        var expiryDateStr: String? = null
        if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            val expiryArray = dataObj.optJSONArray("expiryData") ?: dataObj.optJSONArray("expiry_dates")
            if (expiryArray != null && expiryIndex in 0 until expiryArray.length()) {
                val expiryObj = expiryArray.optJSONObject(expiryIndex)
                expiryDateStr = expiryObj?.optString("date", expiryObj.optString("expiry", ""))
                    ?: expiryArray.optString(expiryIndex, "")
            }
        }
        if (expiryDateStr.isNullOrEmpty() || expiryDateStr.length < 10) {
            throw IllegalStateException("Could not find expiry date for expiryIndex $expiryIndex in option chain response")
        }
        return expiryDateStr
    }

    /**
     * Builds weekly option symbol locally from index symbol, expiry date string, strike, and option type.
     */
    fun buildWeeklyOptionSymbol(indexSymbol: String, expiryDateStr: String, strike: Double, optionType: String): String {
        val day = expiryDateStr.substring(0, 2)
        val monthNum = expiryDateStr.substring(3, 5)
        val fullYear = if (expiryDateStr.length >= 10) expiryDateStr.substring(6, 10) else "20" + expiryDateStr.substring(8, 10)
        val shortYear = fullYear.substring(2, 4)

        val monthCode = when (monthNum) {
            "01" -> "1"
            "02" -> "2"
            "03" -> "3"
            "04" -> "4"
            "05" -> "5"
            "06" -> "6"
            "07" -> "7"
            "08" -> "8"
            "09" -> "9"
            "10" -> "O"
            "11" -> "N"
            "12" -> "D"
            else -> ""
        }

        if (monthCode.isEmpty()) {
            throw IllegalStateException("Invalid month number $monthNum in expiry date $expiryDateStr")
        }

        val targetExpiryCode = "$shortYear$monthCode$day"
        val underlyingPrefix = when {
            indexSymbol.contains("BANKNIFTY", ignoreCase = true) -> "NSE:BANKNIFTY"
            indexSymbol.contains("SENSEX", ignoreCase = true) -> "BSE:SENSEX"
            else -> "NSE:NIFTY"
        }

        val strikeInt = strike.toInt()
        return "$underlyingPrefix$targetExpiryCode$strikeInt$optionType"
    }

    /**
     * Resolves the official Fyers option symbol directly from the fetched option chain JSON response.
     */
    fun resolveSymbolFromOptionChain(
        optionChain: JSONObject,
        indexSymbol: String,
        expiryDateStr: String,
        strike: Double,
        optionType: String
    ): String {
        val day = expiryDateStr.substring(0, 2)
        val monthNum = expiryDateStr.substring(3, 5)
        val fullYear = if (expiryDateStr.length >= 10) expiryDateStr.substring(6, 10) else "20" + expiryDateStr.substring(8, 10)
        val shortYear = fullYear.substring(2, 4)
        val isoDate = "$fullYear-$monthNum-$day"

        val monthCode = when (monthNum) {
            "01" -> "1"
            "02" -> "2"
            "03" -> "3"
            "04" -> "4"
            "05" -> "5"
            "06" -> "6"
            "07" -> "7"
            "08" -> "8"
            "09" -> "9"
            "10" -> "O"
            "11" -> "N"
            "12" -> "D"
            else -> ""
        }

        val targetExpiryCode = "$shortYear$monthCode$day"

        if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            val optionsArray = dataObj.optJSONArray("optionsChain") ?: dataObj.optJSONArray("options")
            if (optionsArray != null) {
                for (i in 0 until optionsArray.length()) {
                    val optObj = optionsArray.getJSONObject(i)
                    val optStrike = optObj.optDouble("strike_price", optObj.optDouble("strikePrice", optObj.optDouble("strike", 0.0)))
                    val symbol = optObj.optString("symbol", "")
                    val type = optObj.optString("option_type", optObj.optString("optionType", ""))
                    val optExpiry = optObj.optString("expiry", optObj.optString("expiry_date", ""))

                    val isStrikeMatch = abs(optStrike - strike) < 1.0
                    val isTypeMatch = type.equals(optionType, ignoreCase = true) || symbol.endsWith(optionType)
                    val isExpiryMatch = symbol.contains(targetExpiryCode) ||
                            (optExpiry.isNotEmpty() && (optExpiry == isoDate || optExpiry == expiryDateStr || optExpiry.contains(isoDate)))

                    if (isStrikeMatch && isTypeMatch && isExpiryMatch && symbol.isNotEmpty()) {
                        return symbol
                    }
                }
            }
        }

        // Fallback constructed symbol
        val underlyingPrefix = when {
            indexSymbol.contains("BANKNIFTY", ignoreCase = true) -> "NSE:BANKNIFTY"
            indexSymbol.contains("SENSEX", ignoreCase = true) -> "BSE:SENSEX"
            else -> "NSE:NIFTY"
        }
        return "$underlyingPrefix$targetExpiryCode${strike.toInt()}$optionType"
    }
    suspend fun resolveMonthlyOptionSymbol(
        viewModel: com.example.viewmodel.AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        spotPrice: Double,
        optionType: String,
        monthIndex: Int = 0
    ): String {
        val strikeInterval = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true) || indexSymbol.contains("SENSEX", ignoreCase = true)) 100 else 50
        val atmStrike = calculateAtmStrike(spotPrice, strikeInterval)

        val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10)
            ?: throw IllegalStateException("Failed to fetch option chain from Fyers API for symbol $indexSymbol")

        if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            val optionsArray = dataObj.optJSONArray("optionsChain") ?: dataObj.optJSONArray("options")
            if (optionsArray != null) {
                for (i in 0 until optionsArray.length()) {
                    val optObj = optionsArray.getJSONObject(i)
                    val strike = optObj.optDouble("strike_price", optObj.optDouble("strikePrice", optObj.optDouble("strike", 0.0)))
                    val symbol = optObj.optString("symbol", "")
                    val type = optObj.optString("option_type", optObj.optString("optionType", ""))

                    if (abs(strike - atmStrike.toDouble()) < 1.0 &&
                        (type.equals(optionType, ignoreCase = true) || symbol.endsWith(optionType)) &&
                        symbol.matches(Regex(".*\\d{2}[A-Z]{3}\\d+${optionType}"))
                    ) {
                        return symbol
                    }
                }
            }
        }

        val targetDate = java.time.LocalDate.now().plusMonths(monthIndex.toLong())
        val year = targetDate.format(java.time.format.DateTimeFormatter.ofPattern("yy"))
        val month3Letter = targetDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM")).uppercase()
        val underlyingPrefix = when {
            indexSymbol.contains("BANKNIFTY", ignoreCase = true) -> "NSE:BANKNIFTY"
            indexSymbol.contains("SENSEX", ignoreCase = true) -> "BSE:SENSEX"
            else -> "NSE:NIFTY"
        }

        return "$underlyingPrefix$year$month3Letter$atmStrike$optionType"
    }

    /**
     * Resolves and fetches historical 1-min candle data for an EXPIRED weekly option contract
     * using Fyers' dedicated expired F&O APIs with in-memory caching.
     */
    suspend fun resolveAndFetchExpiredDerivativeCandles(
        viewModel: com.example.viewmodel.AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        spotPrice: Double,
        optionType: String,
        tradeDate: String,
        expiryIndex: Int = 0
    ): ExpiredDerivativeResult {
        val strikeInterval = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true) || indexSymbol.contains("SENSEX", ignoreCase = true)) 100 else 50
        val atmStrike = calculateAtmStrike(spotPrice, strikeInterval)

        val rangeFrom = LocalDate.parse(tradeDate).minusDays(15).toString()
        val rangeTo = LocalDate.parse(tradeDate).plusDays(15).toString()
        val expiryCacheKey = "$indexSymbol-$rangeFrom-$rangeTo"

        var expiryJson = expiryCache[expiryCacheKey]
        if (expiryJson == null) {
//            println("   • [DEBUG] Resolving expiry dates for tradeDate=$tradeDate (range: $rangeFrom to $rangeTo)...")
            expiryJson = viewModel.fetchHistoryExpiryDates(appId, token, indexSymbol, rangeFrom, rangeTo)
            if (expiryJson != null) expiryCache[expiryCacheKey] = expiryJson
        }

        var targetExpiry = tradeDate
        if (expiryJson != null && expiryJson.has("data")) {
            val dataObj = expiryJson.getJSONObject("data")
            if (dataObj.has("expiry_dates")) {
                val optionsExpiries = dataObj.getJSONObject("expiry_dates").optJSONArray("options")
                if (optionsExpiries != null) {
                    val validExpiries = mutableListOf<String>()
                    for (i in 0 until optionsExpiries.length()) {
                        val expDate = optionsExpiries.getString(i)
                        if (expDate >= tradeDate) {
                            validExpiries.add(expDate)
                        }
                    }
                    validExpiries.sort()
                    targetExpiry = if (expiryIndex in validExpiries.indices) {
                        validExpiries[expiryIndex]
                    } else {
                        validExpiries.firstOrNull() ?: tradeDate
                    }
                }
            }
        }

        val contractsCacheKey = "$indexSymbol-$targetExpiry"
        var contractsJson = contractsCache[contractsCacheKey]
        if (contractsJson == null) {
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
            val underlyingPrefix = when {
                indexSymbol.contains("BANKNIFTY", ignoreCase = true) -> "NSE:BANKNIFTY"
                indexSymbol.contains("SENSEX", ignoreCase = true) -> "BSE:SENSEX"
                else -> "NSE:NIFTY"
            }
            optionSymbol = "$underlyingPrefix$targetExpiry$atmStrike$optionType"
        }
        println("Trade date $tradeDate Expiry $targetExpiry ")


        val fnoCacheKey = "$optionSymbol-$tradeDate"
        var fnoHistory = fnoHistoryCache[fnoCacheKey]
        if (fnoHistory == null) {
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

        val parsed = parseExpiredCandles(fnoHistory)
        return ExpiredDerivativeResult(optionSymbol, parsed)
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
                val timeStr = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                candles.add(Candle(timestamp = timeStr, open = open, high = high, low = low, close = close, volume = volume, epoch = epoch))
            }
        } catch (e: Exception) {
            throw RuntimeException("Error parsing expired F&O candles: ${e.message}")
        }
        return candles.distinctBy { if (it.epoch > 0) it.epoch else it.timestamp }
            .sortedBy { if (it.epoch > 0) it.epoch else 0L }
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

    /**
     * Extracts Last Traded Price (LTP) for a given symbol from Fyers GetStockQuotes response.
     */
    fun extractLtpFromQuotes(quotesJson: org.json.JSONObject, symbol: String): Double? {
        try {
            val dataArray = quotesJson.optJSONArray("d") ?: return null
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val name = item.optString("n", "")
                if (name.equals(symbol, ignoreCase = true)) {
                    val vObj = item.optJSONObject("v") ?: continue
                    return vObj.optDouble("lp", 0.0)
                }
            }
        } catch (e: Exception) {
            println("Error extracting LTP for $symbol from quotes: ${e.message}")
        }
        return null
    }
}
