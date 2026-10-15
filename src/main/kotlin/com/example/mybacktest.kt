/**
 * ============================================================================
 * NIFTY EMA 4/11/18 Trend-Following Strategy & Weekly Option Backtest Runner
 * ============================================================================
 */
package com.example

import com.example.service.TelegramNotifier
import com.example.viewmodel.AuthViewModel
import com.example.util.AuthUtils
import com.example.util.OptionUtils
import com.example.strategy.Candle
import com.example.strategy.TechnicalIndicators
import com.example.strategy.EmaTrendSignalEngine
import com.example.strategy.EmaTrendPositionContext
import com.example.strategy.EmaTrendConfig
import com.example.strategy.IndicatorCandle
import kotlin.math.max
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Scanner

data class OptionTradeResult(
    val tradeNo: Int,
    val direction: String,
    val entryTime: String,
    val exitTime: String,
    val derivativeSymbol: String,
    val entryOptionPrice: Double,
    val exitOptionPrice: Double,
    val pnl: Double,
    val cumulativePnl: Double,
    val cumulativeRupees: Double
)

/**
 * Main application entry point for authentication and executing weekly option backtest.
 */
fun main() {
    val dotenv = dotenv {
        ignoreIfMissing = true
    }
    val viewModel = AuthViewModel()
    val scanner = Scanner(System.`in`)

    println("=== NIFTY EMA Strategy & Weekly Option Backtest Tool ===")
    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"] ?: ""
    val secretKey = System.getenv("FYERS_SECRET_KEY") ?: dotenv["FYERS_SECRET_KEY"] ?: ""
    val redirectUri = System.getenv("FYERS_REDIRECT_URI") ?: dotenv["FYERS_REDIRECT_URI"] ?: "https://trade.fyers.in/api-login/redirect-uri/index.html"

    if (appId.isEmpty() || secretKey.isEmpty()) {
        println("⚠️ FYERS_APP_ID or FYERS_SECRET_KEY not set in environment or .env file.")
        return
    }

    val cachedToken = viewModel.getCachedToken()
    if (cachedToken != null) {
        println("\n[Cache] Found valid cached Access Token! Skipping login flow.")
        runBlocking {
            fetchDataAndRunDerivativeBacktest(viewModel, appId, cachedToken)
        }
        return
    }

    viewModel.prepareLoginUrl(appId, redirectUri)
    println("\n[1] Open this URL in your browser to authorize:")
    println(viewModel.authState.value.loginUrl)

    print("\nPaste either the full redirect URL or the 'auth_code': ")
    var input = scanner.nextLine().trim()
    while (input.isEmpty()) {
        print("Input cannot be empty. Please paste the redirect URL or auth_code: ")
        input = scanner.nextLine().trim()
    }

    val authCode = AuthUtils.extractAuthCode(input)

    println("\n[2] Exchanging auth code for access token...")
    runBlocking {
        viewModel.authenticateWithAuthCode(appId, secretKey, authCode)
    }

    val finalState = viewModel.authState.value
    if (finalState.accessToken != null) {
        println("\nSUCCESS! Access Token acquired and cached.")
        runBlocking {
            fetchDataAndRunDerivativeBacktest(viewModel, appId, finalState.accessToken)
        }
    } else {
        println("\n❌ FAILED: ${finalState.errorMessage}")
    }
}

/**
 * Fetches NIFTY index data, runs strategy signals, and resolves weekly options.
 */
suspend fun fetchDataAndRunDerivativeBacktest(viewModel: AuthViewModel, appId: String, token: String) {
    val rangeStart = "2026-01-01"
    val rangeEnd = "2026-09-30"
    val rangeFrom = java.time.LocalDate.parse(rangeStart).minusWeeks(1).toString()
    val indexSymbol = "NSE:NIFTY50-INDEX"

    println("\n[3] Fetching Historical Data for $indexSymbol from Fyers API ($rangeFrom to $rangeEnd)...")
    val history = viewModel.fetchHistoricalDataInChunks(
        appId = appId,
        accessToken = token,
        symbol = indexSymbol,
        resolution = "1",
        rangeFrom = rangeFrom,
        rangeTo = rangeEnd
    )

    if (history != null) {
        println("Successfully fetched historical data from Fyers API. Parsing candles...")
        val candles = parseFyersCandles(history)
        if (candles.isNotEmpty()) {
            println("Successfully parsed ${candles.size} historical candles. Running Weekly Expiry Derivative Backtest for $indexSymbol...")
            runDerivativeBacktestForRange(viewModel, appId, token, candles, rangeStart, rangeEnd, indexSymbol)
        } else {
            throw IllegalStateException("Fetched history JSON from Fyers contained no candles.")
        }
    } else {
        throw IllegalStateException("Failed to fetch historical data from Fyers API for range $rangeFrom to $rangeEnd.")
    }
}

/**
 * Parses Fyers historical JSON response into a list of [Candle] objects with seconds precision.
 */
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
            val timeStr = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            candles.add(Candle(timestamp = timeStr, open = open, high = high, low = low, close = close, volume = volume, epoch = epoch))
        }
    } catch (e: Exception) {
        throw RuntimeException("Error parsing Fyers candles: ${e.message}")
    }
    return candles.distinctBy { it.timestamp }.sortedBy { it.timestamp }
}

/**
 * Helper to fetch derivative 1-min candles for a given date range.
 */
suspend fun fetchDerivativeCandles(
    viewModel: AuthViewModel,
    appId: String,
    token: String,
    symbol: String,
    from: String,
    to: String
): List<Candle> {
    val history = viewModel.fetchHistoricalDataInChunks(appId, token, symbol, "5S", from, to)
        ?: viewModel.fetchHistoricalData(appId, token, symbol, "5S", from, to)
        ?: throw IllegalStateException("Failed to fetch 1-min historical data for derivative symbol $symbol from $from to $to")
    val candles = parseFyersCandles(history)
    if (candles.isEmpty()) {
        throw IllegalStateException("No 1-min candles returned for derivative symbol $symbol from $from to $to")
    }
    return candles
}

/**
 * Executes the 2-Lot Scale-Out Option Backtest:
 * - Lot 1: Books profit at +15 pts option gain (moves Lot 2 stop to breakeven).
 * - Lot 2: Rides trend runner to strategy exit or EOD 15:20 square-off.
 */
suspend fun runDerivativeBacktestForRange(
    viewModel: AuthViewModel,
    appId: String,
    token: String,
    candles: List<Candle>,
    startDate: String,
    endDate: String,
    indexSymbol: String = "NSE:NIFTY50-INDEX"
) {
    println("\n==============================================================")
    println("EXECUTING 2-LOT SCALE-OUT OPTION BACKTEST ($startDate to $endDate)")
    println("Parent: $indexSymbol | Lot 1 Target: +15 pts (Move SL to BE) | Lot 2 Runner")
    println("==============================================================\n")

    val config = EmaTrendConfig(
        emaFastPeriod = 20,
        emaMidPeriod = 55,
        emaSlowPeriod = 90
    )
    val indicatorCandles = TechnicalIndicators.buildIndicators(candles, config)
    val engine = EmaTrendSignalEngine(config)

    var activePosition: EmaTrendPositionContext? = null
    var entryTime = ""
    var entryDate = ""
    var derivativeSymbol = ""
    var entryOptionPrice = 0.0
    var lot1Active = false
    var lot2Active = false
    var currentTradeNo = 0
    var runningCumulativePnl = 0.0
    var tradeCount = 0
    var derivativeCandles = listOf<Candle>()
    val completedTrades = mutableListOf<OptionTradeResult>()
    val lotSize = 65.0 // 1 lot = 65 qty (2 lots = 130 qty)

    val haltedDays = mutableSetOf<String>()

    for (i in indicatorCandles.indices) {
        val ic = indicatorCandles[i]
        val candleDate = ic.timestamp.substring(0, 10)
        if (candleDate < startDate || candleDate > endDate) continue

        val timePart = ic.timestamp.substring(11, 16)

        // Intraday EOD square-off by 15:20
        if (activePosition != null && (candleDate > entryDate || timePart >= "15:20")) {
            if (haltedDays.contains(candleDate)) continue

            val exitTime = if (candleDate > entryDate) "$entryDate 15:20:00" else ic.timestamp
            val exitDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= exitTime }
                ?: derivativeCandles.lastOrNull()

            if (exitDerivativeBar != null) {
                val currentOptionPrice = exitDerivativeBar.close

                if (lot1Active) {
                    val pnl1 = currentOptionPrice - entryOptionPrice
                    runningCumulativePnl += pnl1
                    completedTrades.add(
                        OptionTradeResult(
                            tradeNo = currentTradeNo,
                            direction = "${activePosition.direction} [Lot 1 EOD]",
                            entryTime = entryTime,
                            exitTime = exitDerivativeBar.timestamp,
                            derivativeSymbol = derivativeSymbol,
                            entryOptionPrice = entryOptionPrice,
                            exitOptionPrice = currentOptionPrice,
                            pnl = pnl1,
                            cumulativePnl = runningCumulativePnl,
                            cumulativeRupees = runningCumulativePnl * lotSize
                        )
                    )
                    lot1Active = false
                }

                if (lot2Active) {
                    val pnl2 = currentOptionPrice - entryOptionPrice
                    runningCumulativePnl += pnl2
                    completedTrades.add(
                        OptionTradeResult(
                            tradeNo = currentTradeNo,
                            direction = "${activePosition.direction} [Lot 2 EOD]",
                            entryTime = entryTime,
                            exitTime = exitDerivativeBar.timestamp,
                            derivativeSymbol = derivativeSymbol,
                            entryOptionPrice = entryOptionPrice,
                            exitOptionPrice = currentOptionPrice,
                            pnl = pnl2,
                            cumulativePnl = runningCumulativePnl,
                            cumulativeRupees = runningCumulativePnl * lotSize
                        )
                    )
                    lot2Active = false
                }
            }
            activePosition = null
            derivativeCandles = emptyList()
            continue
        }

        val slice = indicatorCandles.subList(0, i + 1)
        val decision = engine.evaluate(slice, activePosition)
        var plusMinute = 1

        if (activePosition == null) {
            if ((decision.action == "ENTER_LONG" || decision.action == "ENTER_SHORT") && timePart <= "15:15") {
                if (haltedDays.contains(candleDate)) continue

                val isLong = decision.action == "ENTER_LONG"
                val entrySpot = ic.close

                entryTime = LocalDateTime.parse(ic.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .plusMinutes(plusMinute.toLong())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                entryDate = entryTime.substring(0, 10)
                val optionType = if (isLong) "CE" else "PE"
                val isHistorical = java.time.LocalDate.parse(candleDate).isBefore(java.time.LocalDate.now().minusDays(7))

                if (isHistorical) {
                    val expiredRes = OptionUtils.resolveAndFetchExpiredDerivativeCandles(
                        viewModel = viewModel,
                        appId = appId,
                        token = token,
                        indexSymbol = indexSymbol,
                        spotPrice = entrySpot,
                        optionType = optionType,
                        tradeDate = candleDate
                    )
                    derivativeSymbol = expiredRes.symbol
                    derivativeCandles = expiredRes.candles
                } else {
                    derivativeSymbol = OptionUtils.resolveWeeklyOptionSymbol(
                        viewModel = viewModel,
                        appId = appId,
                        token = token,
                        indexSymbol = indexSymbol,
                        spotPrice = entrySpot,
                        optionType = optionType,
                        expiryIndex = 0
                    )
                    derivativeCandles = fetchDerivativeCandles(viewModel, appId, token, derivativeSymbol, candleDate, candleDate)
                }
                val entryDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= entryTime }
                    ?: throw IllegalStateException("No 1-min derivative candle found at or after entry time $entryTime for $derivativeSymbol")
                entryOptionPrice = if(plusMinute==1) entryDerivativeBar.open else entryDerivativeBar.close

                activePosition = EmaTrendPositionContext(
                    direction = if (isLong) "LONG" else "SHORT",
                    entryUnderlying = entrySpot,
                    stopUnderlying = ic.ema11
                )
                tradeCount++
                currentTradeNo = tradeCount
                lot1Active = true
                lot2Active = true
            }
        } else {
            // Check derivative price for Lot 1 target (+15 pts option gain)
            val currentDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= ic.timestamp } ?: derivativeCandles.lastOrNull()
            val currentOptionPrice = currentDerivativeBar?.close ?: entryOptionPrice
            val optionProfitPts = currentOptionPrice - entryOptionPrice

            // Check Lot 1 target (+15 pts)
            if (lot1Active && optionProfitPts >= 15.0) {
                runningCumulativePnl += optionProfitPts
                completedTrades.add(
                    OptionTradeResult(
                        tradeNo = currentTradeNo,
                        direction = "${activePosition.direction} [Lot 1 Target +15pts]",
                        entryTime = entryTime,
                        exitTime = ic.timestamp,
                        derivativeSymbol = derivativeSymbol,
                        entryOptionPrice = entryOptionPrice,
                        exitOptionPrice = currentOptionPrice,
                        pnl = optionProfitPts,
                        cumulativePnl = runningCumulativePnl,
                        cumulativeRupees = runningCumulativePnl * lotSize
                    )
                )
                lot1Active = false
                println("🎯 [Lot 1 Booked] +15 pts locked in for $derivativeSymbol at ${ic.timestamp}")
            }

            // Check Stop Loss on Lot 2 (-25 pts underlying drawdown or breakeven)
            val spotDiff = if (activePosition.direction == "LONG") ic.close - activePosition.entryUnderlying else activePosition.entryUnderlying - ic.close
            val isStopLossHit = spotDiff <= -25.0
            val isBreakevenHit = !lot1Active && optionProfitPts <= 0.0 // Breakeven stop after Lot 1 booked

            val isSquareOff = timePart >= "15:20"
            if (decision.action == "EXIT" || isSquareOff || isStopLossHit || isBreakevenHit) {
                if (lot1Active) {
                    val pnl1 = optionProfitPts
                    runningCumulativePnl += pnl1
                    val reason = when {
                        isStopLossHit -> "STOP_LOSS_HIT"
                        isBreakevenHit -> "BREAKEVEN_STOP"
                        isSquareOff -> "EOD_SQUARE_OFF"
                        else -> "STRATEGY_EXIT"
                    }
                    completedTrades.add(
                        OptionTradeResult(
                            tradeNo = currentTradeNo,
                            direction = "${activePosition.direction} [Lot 1 ($reason)]",
                            entryTime = entryTime,
                            exitTime = ic.timestamp,
                            derivativeSymbol = derivativeSymbol,
                            entryOptionPrice = entryOptionPrice,
                            exitOptionPrice = currentOptionPrice,
                            pnl = pnl1,
                            cumulativePnl = runningCumulativePnl,
                            cumulativeRupees = runningCumulativePnl * lotSize
                        )
                    )
                    lot1Active = false
                }
                if (lot2Active) {
                    val pnl2 = optionProfitPts
                    runningCumulativePnl += pnl2
                    val reason = when {
                        isStopLossHit -> "STOP_LOSS_HIT"
                        isBreakevenHit -> "BREAKEVEN_STOP"
                        isSquareOff -> "EOD_SQUARE_OFF"
                        else -> "STRATEGY_EXIT"
                    }
                    completedTrades.add(
                        OptionTradeResult(
                            tradeNo = currentTradeNo,
                            direction = "${activePosition.direction} [Lot 2 Runner ($reason)]",
                            entryTime = entryTime,
                            exitTime = ic.timestamp,
                            derivativeSymbol = derivativeSymbol,
                            entryOptionPrice = entryOptionPrice,
                            exitOptionPrice = currentOptionPrice,
                            pnl = pnl2,
                            cumulativePnl = runningCumulativePnl,
                            cumulativeRupees = runningCumulativePnl * lotSize
                        )
                    )
                    lot2Active = false
                }
                activePosition = null
                derivativeCandles = emptyList()
            }
        }
    }

    // Run Post-Entry Price Movement Analysis and print in logs
    analyzePostEntryMovementsForCandles(indicatorCandles)

    // Print detailed trade report using clean box-drawing table
    println("\n========================================================================================================================================================")
    println("DETAILED 2-LOT SCALE-OUT OPTION BACKTEST REPORT (Lot 1 Target +15pts | Lot 2 Runner)")
    println("========================================================================================================================================================")

    if (completedTrades.isEmpty()) {
        println("⚠️ NO TRADES EXECUTED: The strategy did not trigger any entry signals during the specified date range.")
    } else {
        ConsoleTablePrinter.printTradeTable(completedTrades, lotSize)
    }

    var peak = 0.0
    var maxDD = 0.0
    var wins = 0
    var losses = 0
    var gProfit = 0.0
    var gLoss = 0.0

    for (t in completedTrades) {
        if (t.cumulativePnl > peak) peak = t.cumulativePnl
        val dd = peak - t.cumulativePnl
        if (dd > maxDD) maxDD = dd

        if (t.pnl > 0) {
            wins++
            gProfit += t.pnl
        } else if (t.pnl < 0) {
            losses++
            gLoss += kotlin.math.abs(t.pnl)
        }
    }

    val totalNetPnlRupees = runningCumulativePnl * lotSize
    val peakRupees = peak * lotSize
    val maxDdRupees = maxDD * lotSize
    val winRate = if (completedTrades.isNotEmpty()) (wins.toDouble() / completedTrades.size) * 100 else 0.0
    val profitFactor = if (gLoss > 0) gProfit / gLoss else if (gProfit > 0) 999.0 else 0.0

    println("========================================================================================================================================================")
    println("STRATEGY PERFORMANCE & EXECUTION SUMMARY (2 Lots = 130 Qty):")
    println("• Total Legs Executed             : ${completedTrades.size}")
    println("• Winning Legs (Net > 0)          : $wins W | Losing Legs (Net < 0): $losses L (Win Rate: ${String.format("%.1f", winRate)}%)")
    println("• Gross Profit                    : ${String.format("%.2f", gProfit)} pts | Gross Loss: ${String.format("%.2f", gLoss)} pts")
    println("• Profit Factor                   : ${String.format("%.2f", profitFactor)}")
    println("• Net Strategy P&L (Pts)          : ${String.format("%.2f", runningCumulativePnl)} points")
    println("• Net Strategy P&L (INR)          : ₹${String.format("%,.2f", totalNetPnlRupees)}")
    println("• Peak Cumulative P&L           : ${String.format("%.2f", peak)} points (₹${String.format("%,.2f", peakRupees)})")
    println("• Max Drawdown                  : [DD] ${String.format("%.2f", maxDD)} points (₹${String.format("%,.2f", maxDdRupees)})")
    println("========================================================================================================================================================")
    System.out.flush()
    println()
}

fun analyzePostEntryMovementsForCandles(indicatorCandles: List<IndicatorCandle>) {
    val horizons = listOf(1, 3, 5, 10, 20)
    val favorableSums = mutableMapOf<Int, Double>().withDefault { 0.0 }
    val adverseSums = mutableMapOf<Int, Double>().withDefault { 0.0 }
    val minFavorable = mutableMapOf<Int, Double>().withDefault { Double.MAX_VALUE }
    var entryCount = 0

    val config = EmaTrendConfig(
        emaFastPeriod = 20,
        emaMidPeriod = 55,
        emaSlowPeriod = 90
    )
    val engine = EmaTrendSignalEngine(config)
    var activePos: EmaTrendPositionContext? = null

    for (i in 95 until indicatorCandles.size) {
        val slice = indicatorCandles.subList(0, i + 1)
        val decision = engine.evaluate(slice, activePos)

        if (activePos == null && (decision.action == "ENTER_LONG" || decision.action == "ENTER_SHORT")) {
            val isLong = decision.action == "ENTER_LONG"
            val entryPrice = indicatorCandles[i].close
            entryCount++

            for (h in horizons) {
                val targetIdx = i + h
                if (targetIdx < indicatorCandles.size) {
                    var maxHigh = -Double.MAX_VALUE
                    var minLow = Double.MAX_VALUE
                    for (k in (i + 1)..targetIdx) {
                        if (indicatorCandles[k].high > maxHigh) maxHigh = indicatorCandles[k].high
                        if (indicatorCandles[k].low < minLow) minLow = indicatorCandles[k].low
                    }

                    val fav = if (isLong) maxHigh - entryPrice else entryPrice - minLow
                    val adv = if (isLong) entryPrice - minLow else maxHigh - entryPrice

                    favorableSums[h] = favorableSums.getValue(h) + max(0.0, fav)
                    adverseSums[h] = adverseSums.getValue(h) + max(0.0, adv)
                    if (fav > 0.0) {
                        minFavorable[h] = minOf(minFavorable.getValue(h), fav)
                    }
                }
            }

            activePos = EmaTrendPositionContext(
                direction = if (isLong) "LONG" else "SHORT",
                entryUnderlying = entryPrice
            )
        } else if (activePos != null && decision.action == "EXIT") {
            activePos = null
        }
    }

    println("\n==============================================================")
    println("POST-ENTRY PRICE MOVEMENT ANALYSIS (Average & Minimum Points)")
    println("==============================================================")
    println("Total Signals Analyzed: $entryCount")
    if (entryCount > 0) {
        for (h in horizons) {
            val avgFav = favorableSums.getValue(h) / entryCount
            val avgAdv = adverseSums.getValue(h) / entryCount
            val minFav = if (minFavorable.getValue(h) == Double.MAX_VALUE) 0.0 else minFavorable.getValue(h)
            println("  +${h} Candles -> Avg Fav: +${String.format("%.2f", avgFav)} pts | Min Fav: +${String.format("%.2f", minFav)} pts | Avg Adv: -${String.format("%.2f", avgAdv)} pts")
        }
    } else {
        println("  No entry signals recorded for analysis.")
    }
    println("==============================================================\n")
}

object ConsoleTablePrinter {
    fun printTradeTable(trades: List<OptionTradeResult>, lotSize: Double) {
        if (trades.isEmpty()) {
            println("⚠️ No trades executed.")
            return
        }

        println("\n┌─────┬─────────────────────────────────────┬─────────────────────┬─────────────────────┬──────────────────────────┬────────┬────────┬─────────────┬──────────────┐")
        println("│ #   │ Trade Leg / Description             │ Entry Time          │ Exit Time           │ Option Symbol            │ Entry  │ Exit   │ P&L (Pts)   │ P&L (INR)    │")
        println("├─────┼─────────────────────────────────────┼─────────────────────┼─────────────────────┼──────────────────────────┼────────┼────────┼─────────────┼──────────────┤")
        for (t in trades) {
            val pnlStr = if (t.pnl >= 0) "+${String.format("%.2f", t.pnl)}" else String.format("%.2f", t.pnl)
            val inrStr = if (t.pnl >= 0) "+₹${String.format("%,.2f", t.pnl * lotSize)}" else "-₹${String.format("%,.2f", kotlin.math.abs(t.pnl * lotSize))}"
            println(String.format("│ %-3d │ %-35s │ %-19s │ %-19s │ %-24s │ %6.2f │ %6.2f │ %11s │ %12s │",
                t.tradeNo,
                t.direction.take(35),
                t.entryTime,
                t.exitTime,
                t.derivativeSymbol.substringAfter("NSE:").take(24),
                t.entryOptionPrice,
                t.exitOptionPrice,
                pnlStr,
                inrStr
            ))
        }
        println("└─────┴─────────────────────────────────────┴─────────────────────┴─────────────────────┴──────────────────────────┴────────┴────────┴─────────────┴──────────────┘")
    }
}
