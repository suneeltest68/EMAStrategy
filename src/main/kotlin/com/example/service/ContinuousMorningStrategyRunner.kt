package com.example.service

import com.example.strategy.*
import com.example.util.OptionUtils
import com.example.viewmodel.AuthViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class ContinuousMorningStrategyRunner(
    private val viewModel: AuthViewModel,
    private val appId: String,
    private val token: String,
    private val expiryIndex: Int = 0
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val istZone = ZoneId.of("Asia/Kolkata")
    private val triggeredDays = ConcurrentHashMap<String, Boolean>()
    private val resolvedExpiryCache = ConcurrentHashMap<String, String>()

    fun startContinuousSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING CONTINUOUS 3-SECOND MORNING STRATEGY ALGO (BATCH OPTIMIZED)")
            println("==============================================================\n")

            TelegramNotifier.sendAlert("🚀 Continuous 3-Second Morning Strategy Algo Started (9:15 AM - 3:25 PM).")

            while (isActive) {
                val now = LocalDateTime.now(istZone)
                val today = now.toLocalDate()
                val currentTime = now.toLocalTime()
                val dateStr = today.toString()

                val marketOpen = LocalTime.of(9, 15)
                val squareOffTime = LocalTime.of(15, 25)
                val marketClose = LocalTime.of(15, 30)

                // 1. Check market hours (9:15 AM to 3:25 PM)
                if (currentTime.isBefore(marketOpen)) {
                    val targetEntry = LocalDateTime.of(today, marketOpen)
                    val millisToWait = Duration.between(LocalDateTime.now(istZone), targetEntry).toMillis()
                    if (millisToWait > 0) {
                        println("[ContinuousMorning] Pre-market. Sleeping ${millisToWait / 1000}s until 9:15 AM...")
                        delay(minOf(millisToWait, 60_000L))
                    } else {
                        delay(1000L)
                    }
                    continue
                }

                if (currentTime.isAfter(squareOffTime) || currentTime.isAfter(marketClose)) {
                    println("[ContinuousMorning] Past square-off time (15:25). Waiting for tomorrow.")
                    delay(3600_000L * 4)
                    continue
                }

                // 2. Select Index (Nifty for Mon/Tue, Sensex for Wed/Thu, No trade for Fri)
                val selectedIndex = when (today.dayOfWeek) {
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY -> "NSE:NIFTY50-INDEX"
                    DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY -> "BSE:SENSEX-INDEX"
                    else -> null
                }

                if (selectedIndex == null) {
                    println("[ContinuousMorning] Today is ${today.dayOfWeek}. No trade scheduled.")
                    delay(3600_000L * 4)
                    continue
                }

                val stateKey = "$selectedIndex-$dateStr"
                val alreadyTriggered = triggeredDays[stateKey] ?: false

                if (alreadyTriggered) {
                    println("[ContinuousMorning] Trade already taken for today ($stateKey). Pausing option chain polling. Sleeping until EOD/tomorrow...")
                    delay(3600_000L)
                    continue
                }

                println("🔍 [Continuous Check] Evaluating Morning Strategy for $selectedIndex at $currentTime (Bypassing cache for fresh live data)...")
                try {
                    val signal = evaluateSignal(selectedIndex)
                    if (signal.action == "BULL_PUT_SPREAD" || signal.action == "BEAR_CALL_SPREAD") {
                        println("   📈 [Max OI Detected] Strike: ${signal.maxOiStrike} | OI: ${signal.maxOi}")
                        println("   🎯 [Signal Fulfilled] Strike: ${signal.triggerStrike} (${signal.dominantType}) | OI: ${signal.triggerStrikeOi} | Threshold (80%): ${signal.thresholdOi}")
                        println("   📌 [Strategy Identified] ${signal.action}")
                        println("   🎯 Checking Target Percentage Matrix until end of 5-min window...")
                        
                        // Check Target Percentage Matrix until the end of this 5-minute window
                        val matched = evaluatePercentageMatrixInWindow(selectedIndex, signal, today, currentTime, dateStr)
                        if (matched) {
                            triggeredDays[stateKey] = true
                            executeOptimizedSpread(selectedIndex, signal, today, dateStr)
                            println("🛑 [ContinuousMorning] Trade executed successfully. Pausing for today.")
                        } else {
                            println("ℹ️ [Percentage Matrix] Target % matrix not met by end of 5-min window. Resuming strategy scan for next window...")
                        }
                    } else {
                        println("ℹ️ [Continuous Check] No 80% OI trigger met at $currentTime.")
                    }
                } catch (e: Exception) {
                    val errMsg = "❌ [ContinuousMorning CRITICAL ERROR]: ${e.message}"
                    println(errMsg)
                    TelegramNotifier.sendAlert(errMsg)
                }

                // Continuously poll every 3 seconds for fast reaction time
                delay(3_000L)
            }
        }
    }

    private suspend fun evaluateSignal(indexSymbol: String): MorningStrategySignal {
        val strikeInterval = if (indexSymbol.contains("SENSEX")) 100 else 50

        val spotQuotes = viewModel.fetchStockQuotes(appId, token, indexSymbol)
            ?: throw IllegalStateException("Failed to fetch spot quotes for $indexSymbol")
        val spotPrice = extractSpotLtp(spotQuotes, indexSymbol)
        if (spotPrice <= 0.0) throw IllegalStateException("Invalid spot price for $indexSymbol")

        val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)
        val monitoredStrikes = setOf(
            atmStrike,
            atmStrike + strikeInterval,
            atmStrike + (2 * strikeInterval),
            atmStrike - strikeInterval,
            atmStrike - (2 * strikeInterval)
        )

        // Fetch fresh live option chain (isForLiveTrading = true bypasses cache)
        val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10, isForLiveTrading = true)
            ?: throw IllegalStateException("Failed to fetch fresh live option chain for $indexSymbol")
        val optionsArray = if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            dataObj.optJSONArray("optionsChain") ?: dataObj.optJSONArray("options") ?: dataObj.optJSONArray("data")
        } else {
            null
        } ?: optionChain.optJSONArray("optionsChain") ?: optionChain.optJSONArray("options") ?: optionChain.optJSONArray("data")
        ?: throw IllegalStateException("Invalid option chain structure")

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

        if (allStrikesData.isEmpty()) throw IllegalStateException("No strike data parsed from option chain")

        val maxOiItem = allStrikesData.maxByOrNull { it.openInterest }
            ?: throw IllegalStateException("Could not determine max OI")
        val maxOi = maxOiItem.openInterest
        val thresholdOi = (maxOi * 0.80).toLong()

        for (item in allStrikesData) {
            if (monitoredStrikes.contains(item.strike) && item.openInterest >= thresholdOi) {
                val dominatingType = if (item.optionType == "PE") "PUT" else "CALL"
                val action = if (dominatingType == "PUT") "BULL_PUT_SPREAD" else "BEAR_CALL_SPREAD"

                return MorningStrategySignal(
                    action = action,
                    maxOiStrike = maxOiItem.strike,
                    maxOi = maxOi,
                    triggerStrike = item.strike,
                    triggerStrikeOi = item.openInterest,
                    thresholdOi = thresholdOi,
                    dominantType = dominatingType,
                    triggerSymbol = item.symbol,
                    reason = "Strike ${item.strike} reached ${item.openInterest} OI (>= 80% of Max OI $maxOi)"
                )
            }
        }

        return MorningStrategySignal(
            action = "NO_TRADE",
            maxOiStrike = maxOiItem.strike,
            maxOi = maxOi,
            triggerStrike = 0,
            triggerStrikeOi = 0L,
            thresholdOi = thresholdOi,
            dominantType = "",
            triggerSymbol = "",
            reason = "80% OI threshold not met"
        )
    }

    private suspend fun evaluatePercentageMatrixInWindow(
        indexSymbol: String,
        signal: MorningStrategySignal,
        today: LocalDate,
        triggerTime: LocalTime,
        dateStr: String
    ): Boolean {
        val indexName = if (indexSymbol.contains("SENSEX")) "SENSEX" else "NIFTY"
        val strikeInterval = if (indexName == "SENSEX") 100 else 50

        val isExpiryDay = if (indexName == "SENSEX") {
            today.dayOfWeek == DayOfWeek.THURSDAY
        } else {
            today.dayOfWeek == DayOfWeek.TUESDAY
        }

        val regimeName = if (isExpiryDay) "EXPIRY DAY" else "PRE-EXPIRY DAY"

        // Resolve Expiry Date string for logging
        val expiryCacheKey = "$indexSymbol-$dateStr"
        val resolvedExpiryDate = resolvedExpiryCache.getOrPut(expiryCacheKey) {
            var expiry = "Current Weekly Expiry"
            try {
                val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10, isForLiveTrading = true)
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

        val targetMinProfitPct = if (isExpiryDay) 0.8 else 1.8
        val targetMaxProfitPct = if (isExpiryDay) 1.2 else 2.3
        val targetMinLossPct = if (isExpiryDay) 8.0 else 14.0
        val targetMaxLossPct = if (isExpiryDay) 12.0 else 17.0

        val spotQuotes = viewModel.fetchStockQuotes(appId, token, indexSymbol)
        val spotPrice = extractSpotLtp(spotQuotes, indexSymbol)
        val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)

        val candidateOffsets = listOf(100, 150, 200, 250, 300, 350, 400, 450, 500, 600, 700, 800, 900, 1000)

        // Calculate the end of the current 5-minute window
        val currentMinute = triggerTime.minute
        val windowEndMinute = ((currentMinute / 5) + 1) * 5
        val windowEndTime = if (windowEndMinute >= 60) {
            LocalTime.of(triggerTime.hour + 1, 0)
        } else {
            LocalTime.of(triggerTime.hour, windowEndMinute)
        }

        val optionType = if (signal.action == "BULL_PUT_SPREAD") "PE" else "CE"

        // Resolve Short Symbol (ATM) ONCE outside the loop to extract base expiry prefix locally
        val shortSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, atmStrike.toDouble(), optionType, expiryIndex, isForLiveTrading = true)
        val strikeStr = atmStrike.toString()
        val baseExpiryPrefix = if (shortSym.contains(strikeStr)) {
            shortSym.substringBefore(strikeStr)
        } else {
            val prefix = if (indexName == "SENSEX") "BSE:SENSEX" else "NSE:NIFTY"
            prefix
        }

        val allSymbolsList = mutableListOf(shortSym)
        for (offset in candidateOffsets) {
            val longStrike = if (signal.action == "BULL_PUT_SPREAD") atmStrike - offset else atmStrike + offset
            allSymbolsList.add("$baseExpiryPrefix$longStrike$optionType")
        }

        // Check every 10 seconds until the end of this 5-minute window using ONE batch quote call
        while (LocalTime.now(istZone).isBefore(windowEndTime)) {
            println("   🔄 [Matrix Check] Strategy: ${signal.action} | Scanning offsets (up to 1000 pts) | Targets -> Profit: [$targetMinProfitPct% - $targetMaxProfitPct%], Loss: [$targetMinLossPct% - $targetMaxLossPct%] at ${LocalTime.now(istZone)}...")

            val symbolsStr = allSymbolsList.joinToString(",")
            val legQuotes = viewModel.fetchStockQuotes(appId, token, symbolsStr)
            val shortLtp = extractSpotLtp(legQuotes, shortSym)

            for (offset in candidateOffsets) {
                val longStrike = if (signal.action == "BULL_PUT_SPREAD") atmStrike - offset else atmStrike + offset
                val longSym = "$baseExpiryPrefix$longStrike$optionType"
                val longLtp = extractSpotLtp(legQuotes, longSym)

                if (shortLtp > 0 && longLtp > 0 && shortLtp > longLtp) {
                    val lotSize = if (indexName == "SENSEX") 20.0 else 65.0
                    val netCredit = (shortLtp - longLtp) * lotSize
                    val maxLoss = ((offset - (shortLtp - longLtp)) * lotSize)
                    val approxMargin = shortLtp * lotSize * 5.0

                    if (approxMargin > 0) {
                        val profitPct = (netCredit / approxMargin) * 100.0
                        val lossPct = (maxLoss / approxMargin) * 100.0

                        val inProfitRange = profitPct in targetMinProfitPct..targetMaxProfitPct
                        val inLossRange = lossPct in targetMinLossPct..targetMaxLossPct

                        println("      • Offset ${offset}pts (Short Strike: $atmStrike @ ₹${String.format("%.2f", shortLtp)} / Long Strike: $longStrike @ ₹${String.format("%.2f", longLtp)}) -> Profit: ${String.format("%.2f", profitPct)}% (Target: $targetMinProfitPct-$targetMaxProfitPct%) | Loss: ${String.format("%.2f", lossPct)}% (Target: $targetMinLossPct-$targetMaxLossPct%) -> Match: ${inProfitRange && inLossRange}")

                        if (inProfitRange && inLossRange) {
                            println("   ✅ [Target Matrix Met!] Found matching strikes within window (Profit: $profitPct%, Loss: $lossPct%).")
                            return true
                        }
                    }
                }
            }
            delay(10_000L) // Check every 10 seconds within the window
        }

        return false
    }

    private suspend fun executeOptimizedSpread(indexSymbol: String, signal: MorningStrategySignal, today: LocalDate, dateStr: String) {
        val indexName = if (indexSymbol.contains("SENSEX")) "SENSEX" else "NIFTY"
        val strikeInterval = if (indexName == "SENSEX") 100 else 50
        val lotSize = if (indexName == "SENSEX") 20.0 else 65.0

        val isExpiryDay = if (indexName == "SENSEX") {
            today.dayOfWeek == DayOfWeek.THURSDAY
        } else {
            today.dayOfWeek == DayOfWeek.TUESDAY
        }

        val targetMinProfitPct = if (isExpiryDay) 0.8 else 1.8
        val targetMaxProfitPct = if (isExpiryDay) 1.2 else 2.3
        val targetMinLossPct = if (isExpiryDay) 8.0 else 14.0
        val targetMaxLossPct = if (isExpiryDay) 12.0 else 17.0

        val expiryCacheKey = "$indexSymbol-$dateStr"
        val resolvedExpiryDate = resolvedExpiryCache.getOrPut(expiryCacheKey) {
            var expiry = "Current Weekly Expiry"
            try {
                val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10, isForLiveTrading = true)
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

        val spotQuotes = viewModel.fetchStockQuotes(appId, token, indexSymbol)
        val spotPrice = extractSpotLtp(spotQuotes, indexSymbol)
        val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)

        val candidateOffsets = listOf(100, 150, 200, 250, 300, 350, 400, 450, 500, 600, 700, 800, 900, 1000)

        var bestOffset = 200
        var bestShortSym = ""
        var bestLongSym = ""
        var bestShortLtp = 0.0
        var bestLongLtp = 0.0
        var bestNetCredit = 0.0
        var bestMaxLoss = 0.0
        var bestProfitPct = 0.0
        var bestLossPct = 0.0
        var bestScore = 999.0

        val optionType = if (signal.action == "BULL_PUT_SPREAD") "PE" else "CE"

        val shortSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, atmStrike.toDouble(), optionType, expiryIndex, isForLiveTrading = true)
        val strikeStr = atmStrike.toString()
        val baseExpiryPrefix = if (shortSym.contains(strikeStr)) {
            shortSym.substringBefore(strikeStr)
        } else {
            val prefix = if (indexName == "SENSEX") "BSE:SENSEX" else "NSE:NIFTY"
            prefix
        }

        val allSymbolsList = mutableListOf(shortSym)
        for (offset in candidateOffsets) {
            val longStrike = if (signal.action == "BULL_PUT_SPREAD") atmStrike - offset else atmStrike + offset
            allSymbolsList.add("$baseExpiryPrefix$longStrike$optionType")
        }

        val symbolsStr = allSymbolsList.joinToString(",")
        val legQuotes = viewModel.fetchStockQuotes(appId, token, symbolsStr)
        val shortLtp = extractSpotLtp(legQuotes, shortSym)

        for (offset in candidateOffsets) {
            val longStrike = if (signal.action == "BULL_PUT_SPREAD") atmStrike - offset else atmStrike + offset
            val longSym = "$baseExpiryPrefix$longStrike$optionType"
            val longLtp = extractSpotLtp(legQuotes, longSym)

            if (shortLtp > 0 && longLtp > 0 && shortLtp > longLtp) {
                val lotSize = if (indexName == "SENSEX") 20.0 else 65.0
                val netCredit = (shortLtp - longLtp) * lotSize
                val maxLoss = ((offset - (shortLtp - longLtp)) * lotSize)
                val approxMargin = shortLtp * lotSize * 5.0

                if (approxMargin > 0) {
                    val profitPct = (netCredit / approxMargin) * 100.0
                    val lossPct = (maxLoss / approxMargin) * 100.0

                    val profitDev = when {
                        profitPct < targetMinProfitPct -> targetMinProfitPct - profitPct
                        profitPct > targetMaxProfitPct -> profitPct - targetMaxProfitPct
                        else -> 0.0
                    }
                    val lossDev = when {
                        lossPct < targetMinLossPct -> targetMinLossPct - lossPct
                        lossPct > targetMaxLossPct -> lossPct - targetMaxLossPct
                        else -> 0.0
                    }
                    val totalDev = profitDev + lossDev

                    if (totalDev < bestScore) {
                        bestScore = totalDev
                        bestOffset = offset
                        bestShortSym = shortSym
                        bestLongSym = longSym
                        bestShortLtp = shortLtp
                        bestLongLtp = longLtp
                        bestNetCredit = netCredit
                        bestMaxLoss = maxLoss
                        bestProfitPct = profitPct
                        bestLossPct = lossPct
                    }
                }
            }
        }

        val nowStr = LocalDateTime.now(istZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val regimeName = if (isExpiryDay) "EXPIRY DAY" else "PRE-EXPIRY DAY"

        val alertMsg = """
            🚀 5-MIN SIGNAL · MORNING STRATEGY
            Index: $indexName | Expiry: $expiryDisplay
            
            📈 Max OI & Strike:
              Strike: ${signal.maxOiStrike} | OI: ${signal.maxOi}
              
            🎯 Strategy Fulfilled Strike & OI:
              Strike: ${signal.triggerStrike} (${signal.dominantType}) | OI: ${signal.triggerStrikeOi}
              Threshold (80%): ${signal.thresholdOi}
              
            📌 Strategy Taken: ${signal.action}
            [PAPER] $nowStr IST
        """.trimIndent()
        TelegramNotifier.sendAlert(alertMsg)

        println("🎯 [Executing Strategy] ${signal.action} | Expiry: $expiryDisplay with optimized offset ${bestOffset}pts")

        val entryMsg = """
            🚀 ENTRY · MORNING STRATEGY (${signal.action}) [$regimeName]
            Index: $indexName | Expiry: $expiryDisplay | ATM Strike: $atmStrike | Offset: ${bestOffset} pts
              Short Leg: SELL $bestShortSym @ ₹${String.format("%.2f", bestShortLtp)}
              Long Hedge: BUY $bestLongSym @ ₹${String.format("%.2f", bestLongLtp)}
              Max Profit: ₹${String.format("%.2f", bestNetCredit)} (${String.format("%.2f", bestProfitPct)}%)
              Max Loss: ₹${String.format("%.2f", bestMaxLoss)} (${String.format("%.2f", bestLossPct)}%)
            Qty: ${lotSize.toInt()} (1 lot)
            [PAPER] $nowStr IST
        """.trimIndent()
        println(entryMsg)
        TelegramNotifier.sendAlert(entryMsg)

        TradingJournalService.logTrade(TradeJournalEntry(bestShortSym, bestShortLtp, 0.0, lotSize.toInt(), bestNetCredit, signal.action))
    }

    private fun extractSpotLtp(quotes: JSONObject?, symbol: String): Double {
        try {
            val dataArray = quotes?.optJSONArray("d") ?: return 0.0
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val name = item.optString("n", "")
                if (name.equals(symbol, ignoreCase = true)) {
                    val vObj = item.optJSONObject("v") ?: continue
                    return vObj.optDouble("lp", 0.0)
                }
            }
        } catch (_: Exception) {}
        return 0.0
    }
}
