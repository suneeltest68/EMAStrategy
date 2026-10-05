package com.example.util

import kotlin.math.abs
import kotlin.math.round

object OptionUtils {

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
     * Throws an exception if the option chain fails or the strike symbol is not found.
     */
    suspend fun resolveWeeklyOptionSymbol(
        viewModel: com.example.viewmodel.AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        spotPrice: Double,
        optionType: String,
        expiryCode: String = "26O01"
    ): String {
        val strikeInterval = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true)) 100 else 50
        val atmStrike = calculateAtmStrike(spotPrice, strikeInterval)

        val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10)
            ?: throw IllegalStateException("Failed to fetch option chain from Fyers API for symbol $indexSymbol")

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
        throw IllegalStateException("Could not find matching option symbol for strike $atmStrike $optionType in option chain response")
    }

    /**
     * Parses an option chain JSONObject to find the exact option contract symbol matching strike and type (CE/PE).
     */
    fun findOptionSymbolFromChain(optionChainJson: org.json.JSONObject, targetStrike: Int, optionType: String): String? {
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
