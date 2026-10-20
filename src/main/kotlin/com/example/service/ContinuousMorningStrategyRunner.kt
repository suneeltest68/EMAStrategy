package com.example.service

import com.example.strategy.*
import com.example.util.OiChartGenerator
import com.example.util.OptionUtils
import com.example.viewmodel.AuthViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class ContinuousMorningStrategyRunner(
    private val viewModel: AuthViewModel,
    private val appId: String,
    private val token: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val istZone = ZoneId.of("Asia/Kolkata")
    private val lastAlertWindows = ConcurrentHashMap<String, Int>()

    fun startContinuousSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING MORNING STRATEGY OI ALERT BOT (9:15 AM START)")
            println("==============================================================\n")

            TelegramNotifier.sendAlert("🚀 Morning Strategy OI Alert Bot Started (9:15 AM Onward).")

            while (isActive) {
                val now = LocalDateTime.now(istZone)
                val today = now.toLocalDate()
                val currentTime = now.toLocalTime()
                val dateStr = today.toString()

                val marketOpen = LocalTime.of(9, 15)
                val marketClose = LocalTime.of(15, 25)

                // 1. Check market hours (9:15 AM onwards)
                if (currentTime.isBefore(marketOpen)) {
                    val targetEntry = LocalDateTime.of(today, marketOpen)
                    val millisToWait = Duration.between(LocalDateTime.now(istZone), targetEntry).toMillis()
                    if (millisToWait > 0) {
                        println("[MorningAlertBot] Pre-market. Sleeping ${millisToWait / 1000}s until 9:15 AM...")
                        delay(minOf(millisToWait, 60_000L))
                    } else {
                        delay(1000L)
                    }
                    continue
                }

                if (currentTime.isAfter(marketClose)) {
                    println("[MorningAlertBot] Market closed for today. Waiting for tomorrow.")
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
                    println("[MorningAlertBot] Today is ${today.dayOfWeek}. No trade/alert scheduled.")
                    delay(3600_000L * 4)
                    continue
                }

                // Calculate current 5-minute window slot index (hour * 12 + minute / 5)
                val currentWindowSlot = currentTime.hour * 12 + (currentTime.minute / 5)
                val windowKey = "$selectedIndex-$dateStr"
                val lastAlertSlot = lastAlertWindows[windowKey] ?: -1

                println("🔍 [Continuous Check] Evaluating Morning Strategy for $selectedIndex at $currentTime...")
                try {
                    val evalResult = evaluateSignalWithData(selectedIndex)
                    val signal = evalResult.signal
                    if (signal.action == "BULL_PUT_SPREAD" || signal.action == "BEAR_CALL_SPREAD") {
                        if (currentWindowSlot > lastAlertSlot) {
                            lastAlertWindows[windowKey] = currentWindowSlot

                            println("   📈 [Max OI Detected] Strike: ${signal.maxOiStrike} | OI: ${signal.maxOi}")
                            println("   🎯 [Signal Fulfilled] Strike: ${signal.triggerStrike} (${signal.dominantType}) | OI: ${signal.triggerStrikeOi} | Threshold (80%): ${signal.thresholdOi}")
                            println("   📌 [Strategy Identified] ${signal.action}")

                            val nowStr = LocalDateTime.now(istZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            val indexName = if (selectedIndex.contains("SENSEX")) "SENSEX" else "NIFTY"

                            val alertMsg = """
                                🚀 MORNING STRATEGY IDENTIFIED · $indexName
                                Action: ${signal.action}
                                
                                📈 Max OI & Strike:
                                  Strike: ${signal.maxOiStrike} | OI: ${signal.maxOi}
                                  
                                🎯 Trigger Strike & OI:
                                  Strike: ${signal.triggerStrike} (${signal.dominantType}) | OI: ${signal.triggerStrikeOi}
                                  Threshold (80%): ${signal.thresholdOi}
                                  
                                📌 Reason: ${signal.reason}
                                [ALERT] $nowStr IST
                            """.trimIndent()
                            TelegramNotifier.sendAlert(alertMsg)

                            // Generate OI Chart and Send via Telegram photo
                            try {
                                val spotQuotes = viewModel.fetchStockQuotes(appId, token, selectedIndex)
                                val spotPrice = extractSpotLtp(spotQuotes, selectedIndex)
                                val strikeInterval = if (indexName == "SENSEX") 100 else 50
                                val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)

                                val chartBytes = OiChartGenerator.generateOiChart(
                                    indexName = indexName,
                                    dateStr = dateStr,
                                    atmStrike = atmStrike,
                                    maxOiStrike = signal.maxOiStrike,
                                    strikesData = evalResult.allStrikesData
                                )
                                TelegramNotifier.sendPhoto(chartBytes, alertMsg)
                                println("📊 [Telegram] Morning Strategy OI Chart sent successfully.")
                            } catch (e: Exception) {
                                println("❌ [Telegram Error] Failed to generate/send OI chart photo: ${e.message}")
                            }

                            println("⏳ [MorningAlertBot] Alert sent for window slot $currentWindowSlot. Waiting for next 5-min window to re-continue strategy scanning...")
                        } else {
                            println("ℹ️ [Continuous Check] Strategy found for current 5-min window (already alerted). Waiting for next 5-min window...")
                        }
                    } else {
                        println("ℹ️ [Continuous Check] No strategy trigger met at $currentTime. Retrying in 3s...")
                    }
                } catch (e: Exception) {
                    val errMsg = "❌ [MorningAlertBot CRITICAL ERROR]: ${e.message}"
                    println(errMsg)
                    TelegramNotifier.sendAlert(errMsg)
                }

                // Poll every 3 seconds for fast reaction time
                delay(3_000L)
            }
        }
    }

    private suspend fun evaluateSignalWithData(indexSymbol: String): MorningStrategyEvaluationResult {
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

        // Fetch fresh live option chain
        val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10)
            ?: throw IllegalStateException("Failed to fetch fresh live option chain for $indexSymbol")
        val optionsArray = if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            dataObj.optJSONArray("optionsChain") ?: dataObj.optJSONArray("options") ?: dataObj.optJSONArray("data")
        } else {
            null
        } ?: optionChain.optJSONArray("optionsChain") ?: optionChain.optJSONArray("options") ?: optionChain.optJSONArray("data")
        ?: throw IllegalStateException("Invalid option chain structure")

        return MorningStrategyEvaluator.evaluateSignalWithData(optionsArray, monitoredStrikes)
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
