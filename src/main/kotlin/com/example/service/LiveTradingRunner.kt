package com.example.service

import com.example.viewmodel.AuthViewModel
import com.example.strategy.*
import com.example.util.OptionUtils
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class OptionTradeResult(
    val tradeNo: Int,
    val direction: String,
    val entryTime: String,
    val exitTime: String,
    val derivativeSymbol: String,
    val entryOptionPrice: Double,
    val exitOptionPrice: Double,
    val pnl: Double
)

fun parseFyersCandles(json: JSONObject): List<Candle> {
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
        throw RuntimeException("Error parsing Fyers candles: ${e.message}")
    }
    return candles.distinctBy { it.timestamp }.sortedBy { it.timestamp }
}

fun recordTrade(
    tradeNo: Int,
    direction: String,
    entryTime: String,
    exitTime: String,
    derivativeSymbol: String,
    entryOptionPrice: Double,
    exitOptionPrice: Double,
    completedTrades: MutableList<OptionTradeResult>
): Double {
    val pnl = exitOptionPrice - entryOptionPrice
    completedTrades.add(
        OptionTradeResult(
            tradeNo = tradeNo,
            direction = direction,
            entryTime = entryTime,
            exitTime = exitTime,
            derivativeSymbol = derivativeSymbol,
            entryOptionPrice = entryOptionPrice,
            exitOptionPrice = exitOptionPrice,
            pnl = pnl
        )
    )
    return pnl
}

class LiveTradingRunner(
    private val viewModel: AuthViewModel,
    private val appId: String,
    private val token: String,
    private val expiryIndex: Int = 1 // 1 for next week expiry
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val config = EmaTrendConfig(
        emaFastPeriod = 20,
        emaMidPeriod = 55,
        emaSlowPeriod = 90
    )
    private val engine = EmaTrendSignalEngine(config)

    fun startLiveSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING LIVE FORWARD-TESTING / PAPER TRADING SESSION")
            println("Strategy: NIFTY EMA 4/11/18 Trend-Following | Expiry Index: $expiryIndex")
            println("==============================================================\n")

            TelegramNotifier.sendAlert("🚀 Live Forward-Testing Session Started for NIFTY EMA Strategy.")

            val today = java.time.LocalDate.now().toString()
            val warmupFrom = java.time.LocalDate.now().minusWeeks(1).toString()

            println("Fetching warmup historical data from $warmupFrom to $today...")
            val history = viewModel.fetchHistoricalDataInChunks(appId, token, "NSE:NIFTY50-INDEX", "1", warmupFrom, today)
            val candles = if (history != null) parseFyersCandles(history) else emptyList()

            if (candles.isEmpty()) {
                println("❌ Failed to fetch warmup candles. Exiting live session.")
                return@launch
            }


            println("Successfully loaded ${candles.size} warmup candles. Synchronizing with 1-min candle boundaries...")

            var activePosition: EmaTrendPositionContext? = null
            var derivativeSymbol = ""
            var entryTime = ""
            var entryDate = ""
            var entryOptionPrice = 0.0
            var totalOptionPnl = 0.0
            var tradeCount = 0
            var derivativeCandles = listOf<Candle>()

            val istZone = ZoneId.of("Asia/Kolkata")

            while (isActive) {
                // Synchronize precisely with 1-minute candle closes (e.g. 09:15:01, 09:16:01...)
                waitForNext1MinBoundary()

                val now = LocalDateTime.now(istZone)
                val currentTime = now.toLocalTime()
                val currentDate = now.toLocalDate().toString()

                val marketOpen = LocalTime.of(9, 16)
                val marketClose = LocalTime.of(15, 30)
                val squareOffTime = LocalTime.of(15, 15)

                if (currentTime.isBefore(marketOpen)) {
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
                }

                val latestHistory = viewModel.fetchHistoricalData(appId, token, "NSE:NIFTY50-INDEX", "1", warmupFrom, currentDate, isForLiveTrading = true)
                if (latestHistory != null) {
                    val freshCandles = parseFyersCandles(latestHistory)
                    if (freshCandles.isNotEmpty()) {
                        val completedCandles = if (freshCandles.size > 1) freshCandles.dropLast(1) else freshCandles
                        val indicatorCandles = TechnicalIndicators.buildIndicators(completedCandles, config)
                        val lastIc = indicatorCandles.last()
                        print("Last Candle" +lastIc)
                        val timePart = lastIc.timestamp.substring(11, 16)

                        println("[Live Status] Check at ${lastIc.timestamp} | Nifty Close: ${lastIc.close} | EMA4: ${String.format("%.2f", lastIc.ema4)} | EMA11: ${String.format("%.2f", lastIc.ema11)} | EMA18: ${String.format("%.2f", lastIc.ema18)} | Position: ${activePosition?.direction ?: "FLAT"}")

                        // Square off active trades at 3:15 PM (15:15)
                        if (activePosition != null && (timePart >= "15:15" || currentTime.isAfter(squareOffTime) || currentTime == squareOffTime)) {
                            val liveQuotes = viewModel.fetchStockQuotes(appId, token, derivativeSymbol)
                            val liveLtp = liveQuotes?.let { OptionUtils.extractLtpFromQuotes(it, derivativeSymbol) } ?: entryOptionPrice
                            val exitOptionPrice = liveLtp
                            val pnl = recordTrade(tradeCount, activePosition.direction, entryTime, lastIc.timestamp, derivativeSymbol, entryOptionPrice, exitOptionPrice, mutableListOf())
                            totalOptionPnl += pnl

                            val instrument = formatInstrumentName(derivativeSymbol)
                            val rupeesPnl = pnl * 65.0
                            val pnlSignStr = if (rupeesPnl >= 0) "+₹${String.format("%,.2f", rupeesPnl)}" else "-₹${String.format("%,.2f", kotlin.math.abs(rupeesPnl))}"
                            val exitMsg = """
                                🏁 EXIT · EMA (AUTO_SQUARE_OFF_3:15)
                                Direction: ${activePosition.direction}
                                Instrument: SELL $instrument
                                  Entry: ₹${String.format("%.2f", entryOptionPrice)} → Exit: ₹${String.format("%.2f", exitOptionPrice)}
                                Qty: 65 (lot size 65)
                                ⚪ P&L: $pnlSignStr
                                [PAPER] ${lastIc.timestamp} IST
                            """.trimIndent()
                            println(exitMsg)
                            TelegramNotifier.sendAlert(exitMsg)

                            TradingJournalService.logTrade(TradeJournalEntry(derivativeSymbol, entryOptionPrice, exitOptionPrice, 1, pnl, "AUTO_SQUARE_OFF_3:15"))

                            // Send End-of-Day P&L Summary
                            val totalRupees = totalOptionPnl * 65.0
                            val totalRupeesStr = if (totalRupees >= 0) "+₹${String.format("%,.2f", totalRupees)}" else "-₹${String.format("%,.2f", kotlin.math.abs(totalRupees))}"
                            val nowStr = LocalDateTime.now(istZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            val eodMsg = """
                                📊 End-of-Day P&L [PAPER]
                                $nowStr IST
                                EMA [PAPER]: $totalRupeesStr ($tradeCount trade(s))
                                ──────────
                                Total: $totalRupeesStr ($tradeCount trade(s))
                            """.trimIndent()
                            TelegramNotifier.sendAlert(eodMsg)

                            activePosition = null
                            derivativeCandles = emptyList()
                        }

                        val slice = indicatorCandles
                        val decision = engine.evaluate(slice, activePosition)

                        val msg = "🔔 [ Current Signal ${decision.action} | Derivative: $derivativeSymbol"
                        println(msg)

                        if (activePosition == null) {
                            // Do not enter new trades after 15:15
                            if ((decision.action == "ENTER_LONG" || decision.action == "ENTER_SHORT") && timePart < "15:15" && currentTime.isBefore(squareOffTime)) {
                                tradeCount++
                                val isLong = decision.action == "ENTER_LONG"
                                val entrySpot = icCloseOrLast(lastIc)
                                entryTime = lastIc.timestamp
                                entryDate = lastIc.timestamp.substring(0, 10)
                                val optionType = if (isLong) "CE" else "PE"

                                derivativeSymbol = OptionUtils.resolveWeeklyOptionSymbol(
                                    viewModel, appId, token, "NSE:NIFTY50-INDEX", entrySpot, optionType, expiryIndex
                                )

                                val liveQuotes = viewModel.fetchStockQuotes(appId, token, derivativeSymbol)
                                val liveLtp = liveQuotes?.let { OptionUtils.extractLtpFromQuotes(it, derivativeSymbol) } ?: 0.0
                                entryOptionPrice = liveLtp
                                activePosition = EmaTrendPositionContext(
                                    direction = if (isLong) "LONG" else "SHORT",
                                    entryUnderlying = entrySpot,
                                    stopUnderlying = lastIc.ema11
                                )

                                val instrument = formatInstrumentName(derivativeSymbol)
                                val entryMsg = """
                                    🚀 ENTRY · EMA
                                    Direction: ${if (isLong) "LONG" else "SHORT"}
                                    Instrument: BUY $instrument
                                      Entry: ₹${String.format("%.2f", entryOptionPrice)}
                                    Qty: 65 (1 lot(s) x 65)
                                    [PAPER] $entryTime IST
                                """.trimIndent()
                                println(entryMsg)
                                TelegramNotifier.sendAlert(entryMsg)

                                println("✅ Position Entered at Live Option Price: $entryOptionPrice")
                            }
                        } else {
                            val isSquareOff = timePart >= "15:15"
                            if (decision.action == "EXIT" || isSquareOff) {
                                val liveQuotes = viewModel.fetchStockQuotes(appId, token, derivativeSymbol)
                                val liveLtp = liveQuotes?.let { OptionUtils.extractLtpFromQuotes(it, derivativeSymbol) } ?: 0.0
                                val exitOptionPrice = liveLtp
                                val pnl = recordTrade(tradeCount, activePosition.direction, entryTime, lastIc.timestamp, derivativeSymbol, entryOptionPrice, exitOptionPrice, mutableListOf())
                                totalOptionPnl += pnl

                                val instrument = formatInstrumentName(derivativeSymbol)
                                val rupeesPnl = pnl * 65.0
                                val pnlSignStr = if (rupeesPnl >= 0) "+₹${String.format("%,.2f", rupeesPnl)}" else "-₹${String.format("%,.2f", kotlin.math.abs(rupeesPnl))}"
                                val exitReason = if (isSquareOff) "AUTO_SQUARE_OFF_3:15" else (decision.exitReason.ifEmpty { "EMA11_EXIT" })
                                val exitMsg = """
                                    🏁 EXIT · EMA ($exitReason)
                                    Direction: ${activePosition.direction}
                                    Instrument: SELL $instrument
                                      Entry: ₹${String.format("%.2f", entryOptionPrice)} → Exit: ₹${String.format("%.2f", exitOptionPrice)}
                                    Qty: 65 (lot size 65)
                                    ⚪ P&L: $pnlSignStr
                                    [PAPER] ${lastIc.timestamp} IST
                                """.trimIndent()
                                println(exitMsg)
                                TelegramNotifier.sendAlert(exitMsg)

                                TradingJournalService.logTrade(TradeJournalEntry(derivativeSymbol, entryOptionPrice, exitOptionPrice, 1, pnl, exitReason))

                                if (isSquareOff) {
                                    val totalRupees = totalOptionPnl * 65.0
                                    val totalRupeesStr = if (totalRupees >= 0) "+₹${String.format("%,.2f", totalRupees)}" else "-₹${String.format("%,.2f", kotlin.math.abs(totalRupees))}"
                                    val nowStr = LocalDateTime.now(istZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                    val eodMsg = """
                                        📊 End-of-Day P&L [PAPER]
                                        $nowStr IST
                                        EMA [PAPER]: $totalRupeesStr ($tradeCount trade(s))
                                        ──────────
                                        Total: $totalRupeesStr ($tradeCount trade(s))
                                    """.trimIndent()
                                    TelegramNotifier.sendAlert(eodMsg)
                                }

                                activePosition = null
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatInstrumentName(symbol: String): String {
        try {
            val clean = symbol.substringAfter(":")
            val indexName = if (clean.contains("BANKNIFTY")) "BANKNIFTY" else "NIFTY"
            val optionType = if (clean.endsWith("CE")) "CE" else "PE"
            val remaining = clean.removePrefix(indexName).removeSuffix(optionType)
            if (remaining.length >= 5) {
                val strike = remaining.takeLast(5)
                val expiryPart = remaining.dropLast(5)
                val monthChar = expiryPart.getOrNull(2) ?: 'O'
                val monthName = when (monthChar.uppercaseChar()) {
                    'O' -> "Oct"
                    'N' -> "Nov"
                    'D' -> "Dec"
                    'J' -> "Jan"
                    'F' -> "Feb"
                    'M' -> "Mar"
                    'A' -> "Apr"
                    else -> "Oct"
                }
                val year = "20" + expiryPart.take(2)
                return "$indexName-$monthName$year-$strike-$optionType"
            }
        } catch (_: Exception) {}
        return symbol.substringAfter(":")
    }

    private suspend fun waitForNext1MinBoundary() {
        val istZone = ZoneId.of("Asia/Kolkata")
        val now = LocalDateTime.now(istZone)

        // Target 1 second past the next 1-minute mark (e.g., 09:15:01, 09:16:01, 09:17:01)
        val target = if (now.second < 1) {
            now.withSecond(3).withNano(0)
        } else {
            now.plusMinutes(1).withSecond(1).withNano(0)
        }

        var millisToWait = java.time.Duration.between(LocalDateTime.now(istZone), target).toMillis()
        if (millisToWait <= 0) {
            val nextTarget = target.plusMinutes(1)
            millisToWait = java.time.Duration.between(LocalDateTime.now(istZone), nextTarget).toMillis()
        }
        if (millisToWait > 0) {
            delay(millisToWait)
        }
    }

    private fun icCloseOrLast(ic: IndicatorCandle): Double = ic.close
}
