package com.example.strategy

import com.example.util.OptionUtils
import com.example.viewmodel.AuthViewModel
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TargetMatrixEvaluator {

    /**
     * Evaluates the Target Percentage Matrix within a 5-minute window.
     * @param enableMatrix If false, bypasses the matrix check and executes immediately with default offset.
     */
    suspend fun evaluate(
        enableMatrix: Boolean,
        viewModel: AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        signal: MorningStrategySignal,
        expiryIndex: Int,
        today: LocalDate,
        triggerTime: LocalTime,
        dateStr: String,
        resolvedExpiryCache: MutableMap<String, String>,
        istZone: ZoneId,
        extractSpotLtp: (JSONObject?, String) -> Double,
        onExecute: suspend (bestOffset: Int, shortSym: String, longSym: String, shortLtp: Double, longLtp: Double, netCredit: Double, maxLoss: Double, profitPct: Double, lossPct: Double, exactMargin: Double) -> Unit
    ): Boolean {
        val indexName = if (indexSymbol.contains("SENSEX")) "SENSEX" else "NIFTY"
        val strikeInterval = if (indexName == "SENSEX") 100 else 50
        val lotSize = if (indexName == "SENSEX") 20.0 else 65.0

        val targetConfig = MorningStrategyEvaluator.getTargetConfig(indexName, today)
        val regimeName = targetConfig.regimeName

        val spotQuotes = viewModel.fetchStockQuotes(appId, token, indexSymbol)
        val spotPrice = extractSpotLtp(spotQuotes, indexSymbol)
        val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)
        val optionType = if (signal.action == "BULL_PUT_SPREAD") "PE" else "CE"

        val shortSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, atmStrike.toDouble(), optionType, expiryIndex)
        val strikeStr = atmStrike.toString()
        val baseExpiryPrefix = if (shortSym.contains(strikeStr)) {
            shortSym.substringBefore(strikeStr)
        } else {
            if (indexName == "SENSEX") "BSE:SENSEX" else "NSE:NIFTY"
        }

        // If matrix logic is disabled via flag, execute immediately with default offset
        if (!enableMatrix) {
            println("ℹ️ [Target Matrix] Matrix logic is disabled (enableMatrix = false). Executing immediately.")
            val defaultOffset = if (indexName == "SENSEX") 300 else 200
            val longStrike = if (signal.action == "BULL_PUT_SPREAD") atmStrike - defaultOffset else atmStrike + defaultOffset
            val longSym = "$baseExpiryPrefix$longStrike$optionType"

            val legQuotes = viewModel.fetchStockQuotes(appId, token, "$shortSym,$longSym")
            val shortLtp = extractSpotLtp(legQuotes, shortSym)
            val longLtp = extractSpotLtp(legQuotes, longSym)

            if (shortLtp > 0 && longLtp > 0) {
                val netCredit = (shortLtp - longLtp) * lotSize
                val maxLoss = ((defaultOffset - (shortLtp - longLtp)) * lotSize)
                val approxMargin = MorningStrategyEvaluator.fetchExactStandaloneMargin(viewModel, appId, token, shortSym, longSym, lotSize.toInt())
                val profitPct = if (approxMargin > 0) (netCredit / approxMargin) * 100.0 else 1.0
                val lossPct = if (approxMargin > 0) (maxLoss / approxMargin) * 100.0 else 10.0

                onExecute(defaultOffset, shortSym, longSym, shortLtp, longLtp, netCredit, maxLoss, profitPct, lossPct, approxMargin)
                return true
            }
            return false
        }

        // Otherwise, run the full Target Percentage Matrix evaluation window
        val expiryCacheKey = "$indexSymbol-$dateStr"
        val resolvedExpiryDate = resolvedExpiryCache.getOrPut(expiryCacheKey) {
            var expiry = "Current Weekly Expiry"
            try {
                val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10)
                if (optionChain != null && optionChain.has("data")) {
                    val dataObj = optionChain.getJSONObject("data")
                    val expiryArray = dataObj.optJSONArray("expiryData") ?: dataObj.optJSONArray("expiry_dates")
                    if (expiryArray != null && expiryIndex in 0 until expiryArray.length()) {
                        val expiryObj = expiryArray.optJSONObject(expiryIndex)
                        expiry = expiryObj?.optString("date", expiryObj.optString("expiry", ""))
                            ?: expiryArray.optString(expiryIndex, "")
                    }
                }
            } catch (_: Exception) {}
            expiry
        }

        val expiryDayOfWeek = try {
            val parsedDate = LocalDate.parse(resolvedExpiryDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            parsedDate.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            try {
                val parsedDate = LocalDate.parse(resolvedExpiryDate, DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                parsedDate.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            } catch (_: Exception) {
                "Weekly Expiry"
            }
        }
        val expiryDisplay = "$resolvedExpiryDate ($expiryDayOfWeek)"

        println("📅 [Matrix Evaluation] Index: $indexName | Expiry: $expiryDisplay | Regime: $regimeName")

        val candidateOffsets = listOf(100, 150, 200, 250, 300, 350, 400, 450, 500, 600, 700, 800, 900, 1000)

        val currentMinute = triggerTime.minute
        val windowEndMinute = ((currentMinute / 5) + 1) * 5
        val windowEndTime = if (windowEndMinute >= 60) {
            LocalTime.of(triggerTime.hour + 1, 0)
        } else {
            LocalTime.of(triggerTime.hour, windowEndMinute)
        }

        val allSymbolsList = mutableListOf(shortSym)
        for (offset in candidateOffsets) {
            val longStrike = if (signal.action == "BULL_PUT_SPREAD") atmStrike - offset else atmStrike + offset
            allSymbolsList.add("$baseExpiryPrefix$longStrike$optionType")
        }

        while (LocalTime.now(istZone).isBefore(windowEndTime)) {
            println("   🔄 [Matrix Check] Strategy: ${signal.action} | Scanning offsets (up to 1000 pts) | Targets -> Profit: [${targetConfig.minProfitPct}% - ${targetConfig.maxProfitPct}%], Loss: [${targetConfig.minLossPct}% - ${targetConfig.maxLossPct}%] at ${LocalTime.now(istZone)}...")

            val symbolsStr = allSymbolsList.joinToString(",")
            val legQuotes = viewModel.fetchStockQuotes(appId, token, symbolsStr)
            val shortLtp = extractSpotLtp(legQuotes, shortSym)

            for (offset in candidateOffsets) {
                val longStrike = if (signal.action == "BULL_PUT_SPREAD") atmStrike - offset else atmStrike + offset
                val longSym = "$baseExpiryPrefix$longStrike$optionType"
                val longLtp = extractSpotLtp(legQuotes, longSym)

                if (shortLtp > 0 && longLtp > 0 && shortLtp > longLtp) {
                    val netCredit = (shortLtp - longLtp) * lotSize
                    val maxLoss = ((offset - (shortLtp - longLtp)) * lotSize)
                    
                    val approxMargin = MorningStrategyEvaluator.fetchExactStandaloneMargin(viewModel, appId, token, shortSym, longSym, lotSize.toInt())

                    if (approxMargin > 0) {
                        val profitPct = (netCredit / approxMargin) * 100.0
                        val lossPct = (maxLoss / approxMargin) * 100.0

                        val isMatched = MorningStrategyEvaluator.matchesTargetMatrix(profitPct, lossPct, targetConfig)

                        if (isMatched) {
                            println("   ✅ [Target Matrix Met!] Found matching strikes within window (Profit: $profitPct%, Loss: $lossPct%).")
                            onExecute(offset, shortSym, longSym, shortLtp, longLtp, netCredit, maxLoss, profitPct, lossPct, approxMargin)
                            return true
                        }
                    }
                }
            }
            delay(10_000L) // Wait 10 seconds before next check
        }

        return false
    }
}
