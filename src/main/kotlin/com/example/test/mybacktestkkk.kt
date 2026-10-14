/**
 * ============================================================================
 * NIFTY EMA 4/11/18 Trend-Following Strategy & Weekly Option Backtest Runner
 * ============================================================================
 */
package com.example.test

import com.example.viewmodel.AuthViewModel
import com.example.util.AuthUtils
import com.example.util.OptionUtils
import com.example.strategy.Candle
import com.example.strategy.TechnicalIndicators
import com.example.strategy.EmaTrendSignalEngine
import com.example.strategy.EmaTrendPositionContext
import com.example.strategy.EmaTrendConfig
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Scanner
import kotlin.math.abs

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
    val rangeFrom = LocalDate.parse(rangeStart).minusWeeks(1).toString()
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
 * Executes the backtest using Pure EMA 11 Strategy Exit (no trailing stops/partial exits),
 * with 03:20 PM intraday cutoffs and Daily Max Loss Limit (₹11,000).
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
    println("EXECUTING WEEKLY EXPIRY OPTION BACKTEST ($startDate to $endDate)")
    println("Parent: $indexSymbol | Strategy: Chandelier Exit Trailing SL (Period: 22, Multiplier: 3.0)")
    println("==============================================================\n")

    val config = EmaTrendConfig(
        emaFastPeriod = 20,
        emaMidPeriod = 55,
        emaSlowPeriod = 90,
        useChandelierExit = true,
        chandelierPeriod = 22,
        chandelierMultiplier = 3.0
    )
    val indicatorCandles = TechnicalIndicators.buildIndicators(candles, config)
    val engine = EmaTrendSignalEngine(config)

    var activePosition: EmaTrendPositionContext? = null
    var entryTime = ""
    var entryDate = ""
    var derivativeSymbol = ""
    var entryOptionPrice = 0.0
    var runningCumulativePnl = 0.0
    var tradeCount = 0
    var derivativeCandles = listOf<Candle>()
    val completedTrades = mutableListOf<OptionTradeResult>()
    val lotSize = 65.0
    val dailyMaxLossLimit = -5500.0

    val dailyPnlMap = mutableMapOf<String, Double>()
    val haltedDays = mutableSetOf<String>()

    for (i in indicatorCandles.indices) {
        val ic = indicatorCandles[i]
        val candleDate = ic.timestamp.substring(0, 10)
        if (candleDate < startDate || candleDate > endDate) continue

        val timePart = ic.timestamp.substring(11, 16)

        // Intraday rule: Ensure any active position from a previous day is squared off by 15:20 of entryDate
        if (activePosition != null && candleDate > entryDate) {
            if (haltedDays.contains(candleDate)) continue

            val exitTime = "$entryDate 15:20:00"
            val exitDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= exitTime }
                ?: derivativeCandles.lastOrNull()

            if (exitDerivativeBar != null) {
                tradeCount++
                val exitOptionPrice = exitDerivativeBar.close
                val pnl = exitOptionPrice - entryOptionPrice
                val tradeRupees = pnl * lotSize

                val dayPnl = (dailyPnlMap[candleDate] ?: 0.0) + tradeRupees
                dailyPnlMap[candleDate] = dayPnl
                if (dayPnl <= dailyMaxLossLimit) {
                    haltedDays.add(candleDate)
                }

                runningCumulativePnl += pnl
                completedTrades.add(
                    OptionTradeResult(
                        tradeNo = tradeCount,
                        direction = activePosition.direction,
                        entryTime = entryTime,
                        exitTime = exitDerivativeBar.timestamp,
                        derivativeSymbol = derivativeSymbol,
                        entryOptionPrice = entryOptionPrice,
                        exitOptionPrice = exitOptionPrice,
                        pnl = pnl,
                        cumulativePnl = runningCumulativePnl,
                        cumulativeRupees = runningCumulativePnl * lotSize
                    )
                )
            }
            activePosition = null
            derivativeCandles = emptyList()
        }

        val slice = indicatorCandles.subList(0, i + 1)
        val decision = engine.evaluate(slice, activePosition)
        var plusMinute = 1

        if (activePosition == null) {
            if ((decision.action == "ENTER_LONG" || decision.action == "ENTER_SHORT") && timePart <= "15:20") {
                if (haltedDays.contains(candleDate)) continue

                val isLong = decision.action == "ENTER_LONG"
                val entrySpot = ic.close

                entryTime = LocalDateTime.parse(ic.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .plusMinutes(plusMinute.toLong())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                entryDate = entryTime.substring(0, 10)
                val optionType = if (isLong) "CE" else "PE"
                val isHistorical = LocalDate.parse(candleDate).isBefore(LocalDate.now().minusDays(7))

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
            }
        } else {
            // Intraday rule: Auto square-off at 15:20 or strategy EXIT (EMA 11 Exit)
            val isSquareOff = timePart >= "15:20"
            if (decision.action == "EXIT" || isSquareOff) {
                if (haltedDays.contains(candleDate)) {
                    activePosition = null
                    derivativeCandles = emptyList()
                    continue
                }

                tradeCount++
                val validExitTime = LocalDateTime.parse(ic.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .plusMinutes(plusMinute.toLong())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val exitTime = if (isSquareOff && timePart > "15:20") "$candleDate 15:20:00" else validExitTime
                val exitDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= exitTime } ?: run {
                    derivativeCandles = fetchDerivativeCandles(viewModel, appId, token, derivativeSymbol, entryDate, candleDate)
                    derivativeCandles.firstOrNull { it.timestamp >= exitTime }
                        ?: derivativeCandles.lastOrNull()
                        ?: throw IllegalStateException("No 1-min derivative candle found at or after exit time $exitTime for $derivativeSymbol")
                }

                val exitOptionPrice = if(plusMinute==1) exitDerivativeBar.open else exitDerivativeBar.close
                val pnl = exitOptionPrice - entryOptionPrice
                val tradeRupees = pnl * lotSize

                val dayPnl = (dailyPnlMap[candleDate] ?: 0.0) + tradeRupees
                dailyPnlMap[candleDate] = dayPnl
                if (dayPnl <= dailyMaxLossLimit) {
                    haltedDays.add(candleDate)
                }

                runningCumulativePnl += pnl
                completedTrades.add(
                    OptionTradeResult(
                        tradeNo = tradeCount,
                        direction = activePosition.direction,
                        entryTime = entryTime,
                        exitTime = exitDerivativeBar.timestamp,
                        derivativeSymbol = derivativeSymbol,
                        entryOptionPrice = entryOptionPrice,
                        exitOptionPrice = exitOptionPrice,
                        pnl = pnl,
                        cumulativePnl = runningCumulativePnl,
                        cumulativeRupees = runningCumulativePnl * lotSize
                    )
                )

                activePosition = null
                derivativeCandles = emptyList()
            }
        }
    }

    // Print detailed trade report
    println("\n========================================================================================================================================================")
    println("DETAILED OPTION BACKTEST REPORT (Chandelier Exit Trailing SL | 1 Lot = 65 Qty)")
    println("========================================================================================================================================================")
    
    if (completedTrades.isEmpty()) {
        println("⚠️ NO TRADES EXECUTED: The strategy did not trigger any entry signals during the specified date range.")
    } else {
        println("| # | Signal | Entry Time | Exit Time | Derivative Symbol | Entry | Exit | P&L (Pts) | P&L (₹) | Cum. P&L (Pts) | Cum. P&L (₹) |")
        println("|---|--------|------------|-----------|-------------------|-------|------|-----------|---------|----------------|--------------|")
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
            gLoss += abs(t.pnl)
        }

        val sign = if (t.pnl >= 0) "[WIN +]" else "[LOSS -]"
        val signCumPts = if (t.cumulativePnl >= 0) "[+]" else "[-]"
        val signCumRs = if (t.cumulativeRupees >= 0) "[+]" else "[-]"

        println("| ${t.tradeNo} | ${t.direction} | ${t.entryTime} | ${t.exitTime} | ${t.derivativeSymbol} | ${String.format("%.2f", t.entryOptionPrice)} | ${String.format("%.2f", t.exitOptionPrice)} | $sign ${String.format("%.2f", t.pnl)} pts | ₹${String.format("%,.2f", t.pnl * lotSize)} | $signCumPts ${String.format("%.2f", t.cumulativePnl)} pts | $signCumRs ₹${String.format("%,.2f", t.cumulativeRupees)} |")
    }

    val totalNetPnlRupees = runningCumulativePnl * lotSize
    val peakRupees = peak * lotSize
    val maxDdRupees = maxDD * lotSize
    val winRate = if (completedTrades.isNotEmpty()) (wins.toDouble() / completedTrades.size) * 100 else 0.0
    val profitFactor = if (gLoss > 0) gProfit / gLoss else if (gProfit > 0) 999.0 else 0.0

    // Print daily-wise profit & loss and cumulative p&l report
    data class DailySummary(
        val date: String,
        var tradeCount: Int = 0,
        var dailyPnlPts: Double = 0.0,
        var dailyPnlRupees: Double = 0.0,
        var wins: Int = 0,
        var losses: Int = 0,
        var isHalted: Boolean = false
    )

    val dailySummariesMap = mutableMapOf<String, DailySummary>()

    for (t in completedTrades) {
        val date = t.exitTime.substring(0, 10)
        val summary = dailySummariesMap.getOrPut(date) { DailySummary(date) }
        summary.tradeCount++
        summary.dailyPnlPts += t.pnl
        summary.dailyPnlRupees += t.pnl * lotSize
        if (t.pnl > 0) summary.wins++ else if (t.pnl < 0) summary.losses++
        if (haltedDays.contains(date)) summary.isHalted = true
    }

    val sortedDailySummaries = dailySummariesMap.values.sortedBy { it.date }

    println("\n========================================================================================================================================================")
    println("DAILY-WISE PROFIT & LOSS REPORT (Cumulative P&L per Day | 1 Lot = 65 Qty)")
    println("========================================================================================================================================================")
    
    if (sortedDailySummaries.isEmpty()) {
        println("⚠️ NO DAILY P&L: No trades executed.")
    } else {
        println("| Date       | Trades | Daily P&L (Pts) | Daily P&L (₹) | Cum. Daily P&L (Pts) | Cum. Daily P&L (₹) | Status |")
        println("|------------|--------|-----------------|---------------|----------------------|--------------------|--------|")
        
        var runningCumDailyPts = 0.0
        var runningCumDailyRupees = 0.0

        for (ds in sortedDailySummaries) {
            runningCumDailyPts += ds.dailyPnlPts
            runningCumDailyRupees += ds.dailyPnlRupees

            val daySign = if (ds.dailyPnlPts >= 0) "[+]" else "[-]"
            val cumSignPts = if (runningCumDailyPts >= 0) "[+]" else "[-]"
            val cumSignRs = if (runningCumDailyRupees >= 0) "[+]" else "[-]"
            val status = if (ds.isHalted) "HALTED (Max Loss)" else "ACTIVE"

            println("| ${ds.date} | ${ds.tradeCount} (${ds.wins}W/${ds.losses}L) | $daySign ${String.format("%.2f", ds.dailyPnlPts)} pts | ₹${String.format("%,.2f", ds.dailyPnlRupees)} | $cumSignPts ${String.format("%.2f", runningCumDailyPts)} pts | $cumSignRs ₹${String.format("%,.2f", runningCumDailyRupees)} | $status |")
        }
    }
    println("========================================================================================================================================================")

    println("========================================================================================================================================================")
    println("STRATEGY PERFORMANCE & EXECUTION SUMMARY (1 Lot = 65 Qty):")
    println("• Total Trades Executed           : ${completedTrades.size}")
    println("• Winning Trades (Net > 0)        : $wins W | Losing Trades (Net < 0): $losses L (Win Rate: ${String.format("%.1f", winRate)}%)")
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
