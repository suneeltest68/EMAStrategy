package com.example.service

import com.example.strategy.*
import com.example.util.OiChartGenerator
import com.example.util.OptionUtils
import com.example.viewmodel.AuthViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
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
    private var lastStartedDate: LocalDate? = null

    fun startContinuousSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING MORNING STRATEGY OI ALERT BOT (9:45 AM START)")
            println("==============================================================\n")

            while (isActive) {
                val now = LocalDateTime.now(istZone)
                val today = now.toLocalDate()
                val currentTime = now.toLocalTime()
                val dateStr = today.toString()

                val marketOpen = LocalTime.of(9, 45)
                val marketClose = LocalTime.of(10, 10)

                // 1. Check market hours
                if (currentTime.isBefore(marketOpen)) {
                    val targetEntry = LocalDateTime.of(today, marketOpen)
                    val millisToWait = Duration.between(LocalDateTime.now(istZone), targetEntry).toMillis()
                    if (millisToWait > 0) {
                        println("[MorningAlertBot] Pre-market. Sleeping ${millisToWait / 1000}s until 9:45 AM...")
                        delay(minOf(millisToWait, 60_000L))
                    } else {
                        delay(1000L)
                    }
                    continue
                }

                // Send 9:45 AM market open start alert once per day exactly when 9:45 AM is reached
                if (lastStartedDate != today) {
                    lastStartedDate = today
                    val openAlert = "🚀 [MorningAlertBot] Market open (9:45 AM IST) reached. Starting live OI analysis (9:45 - 10:10 AM)..."
                    println(openAlert)
                    TelegramNotifier.sendAlert(openAlert)
                }

                if (currentTime.isAfter(marketClose)) {
                    println("[MorningAlertBot] Market close (10:10 AM) reached for today. Waiting for tomorrow.")
                    val nowZoned = ZonedDateTime.now(istZone)
                    val tomorrow9Am = nowZoned.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
                    val waitMillis = Duration.between(nowZoned, tomorrow9Am).toMillis()
                    delay(waitMillis)
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
                    val nowZoned = ZonedDateTime.now(istZone)
                    val tomorrow9Am = nowZoned.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
                    val waitMillis = Duration.between(nowZoned, tomorrow9Am).toMillis()
                    delay(waitMillis)
                    continue
                }

                // Calculate current 5-minute window slot index
                val currentWindowSlot = currentTime.hour * 12 + (currentTime.minute / 5)
                val windowKey = "$selectedIndex-$dateStr"
                val lastAlertSlot = lastAlertWindows[windowKey] ?: -1

                if (currentWindowSlot == lastAlertSlot) {
                    delay(15_000L)
                    continue
                }

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

                            // Delete all caches and stop execution for today
                            deleteAllCaches()
                            val stopMsg = "🛑 [MorningAlertBot] Morning Strategy identified! Stopping execution for today, deleting all caches, and hard sleeping until tomorrow 9:00 AM IST."
                            println(stopMsg)
                            TelegramNotifier.sendAlert(stopMsg)

                            val nowZoned = ZonedDateTime.now(istZone)
                            val tomorrow9Am = nowZoned.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
                            val waitMillis = Duration.between(nowZoned, tomorrow9Am).toMillis()
                            println("💤 [MorningAlertBot] Hard sleeping for ${waitMillis / 1000 / 60} minutes until tomorrow 9:00 AM IST...")
                            delay(waitMillis)
                            continue
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

    private fun deleteAllCaches() {
        try {
            val cacheDir = File("cache")
            if (cacheDir.exists() && cacheDir.isDirectory) {
                cacheDir.listFiles()?.forEach { it.delete() }
            }
            File("derivatives_cache.json").delete()
            File("index_cache.json").delete()
            File("token.json").delete()
            println("🗑️ [MorningAlertBot] All cache files deleted successfully.")
        } catch (e: Exception) {
            println("❌ [MorningAlertBot] Error deleting cache: ${e.message}")
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

        // Fetch fresh live option chain (no cache)
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
