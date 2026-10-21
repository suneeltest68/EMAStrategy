package com.example.strategy

import org.json.JSONArray
import java.time.DayOfWeek
import java.time.LocalDate

data class TargetPercentageConfig(
    val isExpiryDay: Boolean,
    val regimeName: String,
    val minProfitPct: Double,
    val maxProfitPct: Double,
    val minLossPct: Double,
    val maxLossPct: Double
)

data class MorningStrategyEvaluationResult(
    val signal: MorningStrategySignal,
    val allStrikesData: List<StrikeOiData>
)

object MorningStrategyEvaluator {
    fun getTargetConfig(indexName: String, today: LocalDate): TargetPercentageConfig {
        val isExpiryDay = if (indexName.uppercase().contains("SENSEX")) {
            today.dayOfWeek == DayOfWeek.THURSDAY
        } else {
            today.dayOfWeek == DayOfWeek.TUESDAY
        }

        val regimeName = if (isExpiryDay) "EXPIRY DAY" else "PRE-EXPIRY DAY"
        val minProfit = if (isExpiryDay) 0.8 else 1.8
        val maxProfit = if (isExpiryDay) 1.2 else 2.3
        val minLoss = if (isExpiryDay) 8.0 else 14.0
        val maxLoss = if (isExpiryDay) 12.0 else 17.0

        return TargetPercentageConfig(isExpiryDay, regimeName, minProfit, maxProfit, minLoss, maxLoss)
    }

    fun matchesTargetMatrix(profitPct: Double, lossPct: Double, config: TargetPercentageConfig): Boolean {
        val inProfitRange = profitPct in config.minProfitPct..config.maxProfitPct
        val inLossRange = lossPct in config.minLossPct..config.maxLossPct
        return inProfitRange && inLossRange
    }

    fun calculateDeviation(profitPct: Double, lossPct: Double, config: TargetPercentageConfig): Double {
        val profitDev = when {
            profitPct < config.minProfitPct -> config.minProfitPct - profitPct
            profitPct > config.maxProfitPct -> profitPct - config.maxProfitPct
            else -> 0.0
        }
        val lossDev = when {
            lossPct < config.minLossPct -> config.minLossPct - lossPct
            lossPct > config.maxLossPct -> lossPct - config.maxLossPct
            else -> 0.0
        }
        return profitDev + lossDev
    }

    suspend fun fetchExactStandaloneMargin(
        viewModel: com.example.viewmodel.AuthViewModel,
        appId: String,
        token: String,
        shortSymbol: String,
        longSymbol: String,
        lotSize: Int
    ): Double {
        return try {
            val marginLegs = listOf(
                mapOf("symbol" to shortSymbol, "qty" to lotSize, "side" to -1, "type" to 1, "productType" to "MARGIN"),
                mapOf("symbol" to longSymbol, "qty" to lotSize, "side" to 1, "type" to 1, "productType" to "MARGIN")
            )
            val marginJson = viewModel.fetchMargins(appId, token, marginLegs)
            val dataObj = marginJson?.optJSONObject("data")
            dataObj?.optDouble("total", 0.0) ?: dataObj?.optDouble("net", 0.0) ?: 0.0
        } catch (e: Exception) {
            println("❌ Failed to fetch exact margin from Fyers API: ${e.message}")
            0.0
        }
    }

    fun evaluateSignal(
        optionsArray: JSONArray,
        monitoredStrikes: Set<Int>
    ): MorningStrategySignal {
        return evaluateSignalWithData(optionsArray, monitoredStrikes).signal
    }

    fun evaluateSignalWithData(
        optionsArray: JSONArray,
        monitoredStrikes: Set<Int>
    ): MorningStrategyEvaluationResult {
        val allStrikesData = mutableListOf<StrikeOiData>()

        for (i in 0 until optionsArray.length()) {
            val opt = optionsArray.getJSONObject(i)
            val strike = opt.optInt("strike_price", opt.optInt("strikePrice", opt.optInt("strike", 0)))
            val symbol = opt.optString("symbol", opt.optString("fyToken", ""))
            val type = opt.optString("option_type", opt.optString("optionType", ""))
            val ltp = opt.optDouble("ltp", opt.optDouble("lp", 0.0))
            val oi = opt.optLong("open_interest", opt.optLong("openInterest", opt.optLong("oi", 0L)))
            val changeOi = opt.optLong("change_in_oi", opt.optLong("changeInOi", 0L))

            if (strike > 0 && symbol.isNotEmpty()) {
                val optType = if (type.equals("CE", ignoreCase = true) || symbol.endsWith("CE")) "CE" else "PE"
                allStrikesData.add(StrikeOiData(strike, optType, symbol, ltp, oi, changeOi))
            }
        }

        if (allStrikesData.isEmpty()) {
            throw IllegalStateException("No strike data parsed from option chain")
        }

        // 1. Find Max OI across ALL strikes in the option chain
        val maxOiItem = allStrikesData.maxByOrNull { it.openInterest }
            ?: throw IllegalStateException("Could not determine max OI")
        val maxOi = maxOiItem.openInterest
        val thresholdOi = (maxOi * 0.80).toLong()

        // 2. Case B: If Max OI strike itself is within the 5 monitored strikes, decide then and there
        if (monitoredStrikes.contains(maxOiItem.strike)) {
            val isPut = maxOiItem.optionType.equals("PE", ignoreCase = true) || maxOiItem.symbol.endsWith("PE")
            val dominatingType = if (isPut) "PUT" else "CALL"
            val action = if (isPut) "BULL_PUT_SPREAD" else "BEAR_CALL_SPREAD"

            val signal = MorningStrategySignal(
                action = action,
                maxOiStrike = maxOiItem.strike,
                maxOi = maxOi,
                triggerStrike = maxOiItem.strike,
                triggerStrikeOi = maxOiItem.openInterest,
                thresholdOi = thresholdOi,
                dominantType = dominatingType,
                triggerSymbol = maxOiItem.symbol,
                reason = "Max OI strike ${maxOiItem.strike} (${maxOiItem.optionType}) with ${maxOiItem.openInterest} OI is within monitored 5 strikes"
            )
            return MorningStrategyEvaluationResult(signal, allStrikesData)
        }

        // 3. Case A: If Max OI strike is outside, check if any of the 5 monitored strikes reaches >= 80% of Max OI
        val monitoredStrikesData = allStrikesData.filter { monitoredStrikes.contains(it.strike) }
        for (item in monitoredStrikesData) {
            if (item.openInterest >= thresholdOi) {
                val isPut = item.optionType.equals("PE", ignoreCase = true) || item.symbol.endsWith("PE")
                val dominatingType = if (isPut) "PUT" else "CALL"
                val action = if (isPut) "BULL_PUT_SPREAD" else "BEAR_CALL_SPREAD"

                val signal = MorningStrategySignal(
                    action = action,
                    maxOiStrike = maxOiItem.strike,
                    maxOi = maxOi,
                    triggerStrike = item.strike,
                    triggerStrikeOi = item.openInterest,
                    thresholdOi = thresholdOi,
                    dominantType = dominatingType,
                    triggerSymbol = item.symbol,
                    reason = "Monitored strike ${item.strike} reached ${item.openInterest} OI (>= 80% of Max OI $maxOi at strike ${maxOiItem.strike})"
                )
                return MorningStrategyEvaluationResult(signal, allStrikesData)
            }
        }

        // 4. No signal triggered
        val noSignal = MorningStrategySignal(
            action = "NO_TRADE",
            maxOiStrike = maxOiItem.strike,
            maxOi = maxOi,
            triggerStrike = 0,
            triggerStrikeOi = 0L,
            thresholdOi = thresholdOi,
            dominantType = "",
            triggerSymbol = "",
            reason = "Max OI is at ${maxOiItem.strike} (outside 5 strikes) and 80% threshold ($thresholdOi) not met by any monitored strike"
        )
        return MorningStrategyEvaluationResult(noSignal, allStrikesData)
    }
}
