package com.example.service

import com.example.viewmodel.AuthViewModel
import com.example.strategy.*
import com.example.util.OptionUtils
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds


class ForBackTestingLiveTradingRunner(
    private val viewModel: AuthViewModel,
    private val appId: String,
    private val token: String,
    private val expiryIndex: Int = 1 // 1 for next week expiry
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val config = EmaTrendConfig()
    private val engine = EmaTrendSignalEngine(config)

    fun startLiveSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING LIVE FORWARD-TESTING / PAPER TRADING SESSION")
            println("Strategy: NIFTY EMA 4/11/18 Trend-Following | Expiry Index: $expiryIndex")
            println("==============================================================\n")

            TelegramNotifier.sendAlert("🚀 Live Forward-Testing Session Started for NIFTY EMA Strategy.")

            val today = LocalDate.now().toString()
            val warmupFrom = LocalDate.now().minusWeeks(1).toString()

            println("Fetching warmup historical data from $warmupFrom to $today...")
            val history = viewModel.fetchHistoricalDataInChunks(appId, token, "NSE:NIFTY50-INDEX", "5", warmupFrom, today)
            val candles = if (history != null) parseFyersCandles(history) else emptyList()

            if (candles.isEmpty()) {
                println("❌ Failed to fetch warmup candles. Exiting live session.")
                return@launch
            }

            println("Successfully loaded ${candles.size} warmup candles. Synchronizing with 5-min candle boundaries...")

            var activePosition: EmaTrendPositionContext? = null
            var derivativeSymbol = ""
            var entryTime = ""
            var entryDate = ""
            var entryOptionPrice = 0.0
            var totalOptionPnl = 0.0
            var tradeCount = 0
            var derivativeCandles = listOf<Candle>()

            val file = File("src/main/kotlin/com/example/mockjson.json")
            if (!file.exists()) {
                println("❌ mockjson.json not found at ${file.absolutePath}")
                return@launch
            }
            val allMockCandles = parseFyersCandles(JSONObject(file.readText()))
            var replayIndex = allMockCandles.indexOfFirst { it.epoch >= 1791258300L }.let { if (it != -1) it + 1 else minOf(50, allMockCandles.size) }

            val istZone = ZoneId.of("Asia/Kolkata")

            while (isActive) {
                // Synchronize precisely with 5-minute candle closes (e.g. 09:20:06, 09:25:06...)
//                waitForNext6SecBoundary()

                if (replayIndex > allMockCandles.size) {
                    println("🏁 Reached end of mockjson.json candles.")
                    break
                }

                val freshCandles = allMockCandles.subList(0, replayIndex)
                replayIndex++

                val now = LocalDateTime.now(istZone)
                val currentTime = now.toLocalTime()
                val currentDate = now.toLocalDate().toString()

                val marketOpen = LocalTime.of(9, 15)
                val marketClose = LocalTime.of(15, 30)
                val squareOffTime = LocalTime.of(15, 25)

                /*if (currentTime.isBefore(marketOpen)) {
                    println("[Market Closed] Waiting for market open at 09:15 AM...")
                    delay(30_000L)
                    continue
                }

                if (currentTime.isAfter(marketClose)) {
                    println("[Market Closed] Trading hours ended for today.")
                    if (activePosition != null) {
                        println("[EOD] Force square-off at end of day.")
                        activePosition = null
                    }
                    delay(3600_000L)
                    continue
                }*/

                if (freshCandles.isNotEmpty()) {
                        val indicatorCandles = TechnicalIndicators.buildIndicators(freshCandles, config)
                        val lastIc = indicatorCandles.last()
                        val timePart = lastIc.timestamp.substring(11, 16)
                        println("[Live Status] Check at ${lastIc.timestamp} | Nifty Close: ${lastIc.close} | EMA4: ${String.format("%.2f", lastIc.ema4)} | EMA11: ${String.format("%.2f", lastIc.ema11)} | EMA18: ${String.format("%.2f", lastIc.ema18)} | Position: ${activePosition?.direction ?: "FLAT"}")

                       /* if (activePosition != null && (currentTime.isAfter(squareOffTime) || currentTime == squareOffTime || lastIc.timestamp.substring(0, 10) > entryDate)) {
                            val exitTime = "$entryDate 15:25"
                            val exitBar = derivativeCandles.firstOrNull { it.timestamp >= exitTime } ?: derivativeCandles.lastOrNull()
                            if (exitBar != null) {
                                val pnl = exitBar.close - entryOptionPrice
                                totalOptionPnl += pnl
                                val msg = "🔴 [AUTO SQUARE-OFF] Trade #$tradeCount exited at ${exitBar.timestamp} | PnL: ${String.format("%.2f", pnl)} pts"
                                println(msg)
                                TelegramNotifier.sendAlert(msg)
                                TradingJournalService.logTrade(TradeJournalEntry(derivativeSymbol, entryOptionPrice, exitBar.close, 1, pnl, "AUTO_SQUARE_OFF"))
                            }
                            activePosition = null
                            derivativeCandles = emptyList()
                        }*/

                        val slice = indicatorCandles
                        val decision = engine.evaluate(slice, activePosition)

                        if (activePosition == null) {
                            if (decision.action == "ENTER_LONG" || decision.action == "ENTER_SHORT") {
                                tradeCount++
                                val isLong = decision.action == "ENTER_LONG"
                                val entrySpot = icCloseOrLast(lastIc)
                                entryTime = lastIc.timestamp
                                entryDate = lastIc.timestamp.substring(0, 10)
                                val optionType = if (isLong) "CE" else "PE"

                                derivativeSymbol = OptionUtils.resolveWeeklyOptionSymbol(
                                    viewModel, appId, token, "NSE:NIFTY50-INDEX", entrySpot, optionType, expiryIndex
                                )

                                val msg = "🔔 [LIVE SIGNAL] NIFTY ${if (isLong) "LONG" else "SHORT"} at $entryTime Close | Derivative: $derivativeSymbol"
                                println(msg)
                                TelegramNotifier.sendAlert(msg)

                                val dHistory = viewModel.fetchHistoricalData(appId, token, derivativeSymbol, "1", entryDate, entryDate)
                                if (dHistory != null) {
                                    derivativeCandles = parseFyersCandles(dHistory)
                                    val entryBar = derivativeCandles.firstOrNull { it.timestamp >= entryTime } ?: derivativeCandles.firstOrNull()
                                    if (entryBar != null) {
                                        entryOptionPrice = entryBar.open
                                        activePosition = EmaTrendPositionContext(
                                            direction = if (isLong) "LONG" else "SHORT",
                                            entryUnderlying = entrySpot,
                                            stopUnderlying = lastIc.ema11
                                        )
                                        println("✅ Position Entered at Option Price: $entryOptionPrice")
                                    }
                                }
                            }
                        } else {
                            val isSquareOff = timePart >= "15:25"
                            if (decision.action == "EXIT" || isSquareOff) {
                                val currentCandleDate = lastIc.timestamp.substring(0, 10)
                                val exitTime = if (isSquareOff && timePart > "15:25") "$currentCandleDate 15:25" else lastIc.timestamp
                                val exitBar = derivativeCandles.firstOrNull { it.timestamp >= exitTime } ?: derivativeCandles.lastOrNull()
                                if (exitBar != null) {
                                    val pnl = recordTrade(tradeCount, activePosition.direction, entryTime, exitBar.timestamp, derivativeSymbol, entryOptionPrice, exitBar.close, mutableListOf())
                                    totalOptionPnl += pnl
                                    val msg = "🏁 [LIVE EXIT] Trade #$tradeCount exited at ${exitBar.timestamp} | PnL: ${String.format("%.2f", pnl)} pts | Total PnL: ${String.format("%.2f", totalOptionPnl)} pts"
                                    println(msg)
                                    TelegramNotifier.sendAlert(msg)
                                    TradingJournalService.logTrade(TradeJournalEntry(derivativeSymbol, entryOptionPrice, exitBar.close, 1, pnl, decision.exitReason.ifEmpty { "SQUARE_OFF" }))
                                }
                                activePosition = null
                                derivativeCandles = emptyList()
                            }
                        }
                    }
                    delay(2000.milliseconds)
                }
            }
        }

    private suspend fun waitForNext5MinBoundary() {
        val istZone = ZoneId.of("Asia/Kolkata")
        val now = LocalDateTime.now(istZone)
        val minute = now.minute
        val remainder = minute % 5
        val minutesToWait = if (remainder == 0 && now.second < 2) 0 else (5 - (remainder % 5))
        // Target 2 seconds past the next 5-minute mark (e.g., 09:20:02, 09:25:02) to ensure Fyers has closed candle data
        val target = now.plusMinutes(minutesToWait.toLong()).withSecond(2).withNano(0)

        var millisToWait = Duration.between(LocalDateTime.now(istZone), target).toMillis()
        if (millisToWait <= 0) {
            val nextTarget = target.plusMinutes(5)
            millisToWait = Duration.between(LocalDateTime.now(istZone), nextTarget).toMillis()
        }
        if (millisToWait > 0) {
            delay(millisToWait)
        }
    }

    private suspend fun waitForNext6SecBoundary() {
        val istZone = ZoneId.of("Asia/Kolkata")
        val now = LocalDateTime.now(istZone)
        var target = now.withSecond(15).withNano(0)
        if (now.second < 15) {
            target = now.withSecond(15).withNano(0)
        }
        val millisToWait = Duration.between(now, target).toMillis()
        if (millisToWait > 0) {
            delay(millisToWait)
        }
    }

    private fun icCloseOrLast(ic: IndicatorCandle): Double = ic.close
}
