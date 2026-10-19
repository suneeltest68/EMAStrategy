package com.example.service

import com.example.strategy.*
import com.example.util.OptionUtils
import com.example.viewmodel.AuthViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

class MorningStrategyRunner(
    private val viewModel: AuthViewModel,
    private val appId: String,
    private val token: String,
    private val expiryIndex: Int = 0
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val istZone = ZoneId.of("Asia/Kolkata")

    fun startMorningSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING MORNING STRATEGY (9:45 AM - 10:10 AM WINDOW)")
            println("==============================================================\n")

            TelegramNotifier.sendAlert("🚀 Morning Strategy (9:45 AM Window) Started.")

            while (isActive) {
                val now = LocalDateTime.now(istZone)
                val today = now.toLocalDate()
                val currentTime = now.toLocalTime()
                val dateStr = today.toString()

                val sessionStart = LocalTime.of(9, 45)
                val sessionDeadline = LocalTime.of(10, 10)
                val marketClose = LocalTime.of(15, 30)

                // Wait until 9:45 AM
                if (currentTime.isBefore(sessionStart)) {
                    val targetEntry = LocalDateTime.of(today, sessionStart)
                    val millisToWait = Duration.between(LocalDateTime.now(istZone), targetEntry).toMillis()
                    if (millisToWait > 0) {
                        println("[MorningStrategy] Waiting for 9:45 AM trigger window. Sleeping ${millisToWait / 1000}s...")
                        delay(minOf(millisToWait, 60_000L))
                    } else {
                        delay(1000L)
                    }
                    continue
                }

                // If past 10:10 AM or market closed, wait for next day
                if (currentTime.isAfter(sessionDeadline) || currentTime.isAfter(marketClose)) {
                    println("[MorningStrategy] Morning window (9:45 - 10:10) passed for today. Waiting for tomorrow.")
                    delay(3600_000L * 4)
                    continue
                }

                // Select Index (Nifty for Mon/Tue, Sensex for Wed/Thu, No trade for Fri)
                val selectedIndex = when (today.dayOfWeek) {
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY -> "NSE:NIFTY50-INDEX"
                    DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY -> "BSE:SENSEX-INDEX"
                    else -> null
                }

                if (selectedIndex == null) {
                    println("[MorningStrategy] Today is ${today.dayOfWeek}. No trade scheduled.")
                    delay(3600_000L * 4)
                    continue
                }

                println("[MorningStrategy] Running for index: $selectedIndex on $dateStr")

                try {
                    val signal = evaluateMorningSignal(selectedIndex)
                    if (signal.action == "BULL_PUT_SPREAD" || signal.action == "BEAR_CALL_SPREAD") {
                        executeSpread(selectedIndex, signal)
                        // Stop running morning strategy for the rest of the day once trade is taken
                        delay(3600_000L * 6)
                    } else {
                        println("ℹ️ [MorningStrategy] No trade triggered by 10:10 AM. Standing down for today.")
                        TelegramNotifier.sendAlert("ℹ️ [MorningStrategy] No trade triggered (80% OI threshold not met by 10:10 AM). No trade for the day.")
                        delay(3600_000L * 4)
                    }
                } catch (e: Exception) {
                    val errMsg = "❌ [MorningStrategy CRITICAL ERROR]: ${e.message}"
                    println(errMsg)
                    TelegramNotifier.sendAlert(errMsg)
                }

                delay(10_000L)
            }
        }
    }

    private suspend fun evaluateMorningSignal(indexSymbol: String): MorningStrategySignal {
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

        val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10)
            ?: throw IllegalStateException("Failed to fetch option chain for $indexSymbol")
        val optionsArray = optionChain.optJSONArray("optionsChain") ?: optionChain.optJSONArray("options")
            ?: optionChain.optJSONArray("data") ?: throw IllegalStateException("Invalid option chain structure")

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

        val sessionDeadline = LocalTime.of(10, 10)

        while (LocalTime.now(istZone).isBefore(sessionDeadline)) {
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
            delay(3_000L)
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

    private suspend fun executeSpread(indexSymbol: String, signal: MorningStrategySignal) {
        val indexName = if (indexSymbol.contains("SENSEX")) "SENSEX" else "NIFTY"
        val strikeInterval = if (indexName == "SENSEX") 100 else 50
        val lotSize = if (indexName == "SENSEX") 20.0 else 65.0

        val spotQuotes = viewModel.fetchStockQuotes(appId, token, indexSymbol)
        val spotPrice = extractSpotLtp(spotQuotes, indexSymbol)
        val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)

        val hedgeOffset = if (indexName == "SENSEX") 300 else 200

        val (shortSymbol, longSymbol) = if (signal.action == "BULL_PUT_SPREAD") {
            val shortSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, atmStrike.toDouble(), "PE", expiryIndex)
            val longSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, (atmStrike - hedgeOffset).toDouble(), "PE", expiryIndex)
            Pair(shortSym, longSym)
        } else {
            val shortSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, atmStrike.toDouble(), "CE", expiryIndex)
            val longSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, (atmStrike + hedgeOffset).toDouble(), "CE", expiryIndex)
            Pair(shortSym, longSym)
        }

        val legQuotes = viewModel.fetchStockQuotes(appId, token, "$shortSymbol,$longSymbol")
        val shortLtp = extractSpotLtp(legQuotes, shortSymbol)
        val longLtp = extractSpotLtp(legQuotes, longSymbol)

        val nowStr = LocalDateTime.now(istZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val entryMsg = """
            🚀 ENTRY · MORNING STRATEGY (${signal.action})
            Index: $indexName | Trigger Strike: ${signal.triggerStrike}
              Short Leg: SELL $shortSymbol @ ₹${String.format("%.2f", shortLtp)}
              Long Hedge: BUY $longSymbol @ ₹${String.format("%.2f", longLtp)}
            Qty: ${lotSize.toInt()} (1 lot)
            [PAPER] $nowStr IST
        """.trimIndent()
        println(entryMsg)
        TelegramNotifier.sendAlert(entryMsg)

        TradingJournalService.logTrade(TradeJournalEntry(shortSymbol, shortLtp, 0.0, lotSize.toInt(), 0.0, signal.action))
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
