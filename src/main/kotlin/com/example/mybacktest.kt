/**
 * ============================================================================
 * NIFTY EMA 4/11/18 Trend-Following Strategy & Weekly Option Backtest Runner
 * ============================================================================
 *
 * Workflow:
 * 1. Fetch NIFTY 50 Index 5-min historical data for signal generation.
 * 2. When NIFTY triggers an entry between September 30th and October 5th, 2026:
 *    - Resolve weekly option symbol strictly via live Fyers option chain lookup.
 *    - Fetch 1-minute historical candle data for that specific derivative option contract.
 * 3. When NIFTY exits:
 *    - Match the 1-minute derivative candle at exit time to calculate exact option P&L.
 * 4. Present all executed trades in a clean Markdown summary table.
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
    val firstLotExitPrice: Double,
    val secondLotExitPrice: Double,
    val firstLotPnl: Double,
    val secondLotPnl: Double,
    val totalTradePnl: Double,
    val cumulativePnl: Double,
    val cumulativeRupees: Double
)

data class RawTradeCandidate(
    val tradeNo: Int,
    val direction: String,
    val entryTime: String,
    val exitTime: String,
    val derivativeSymbol: String,
    val entryOptionPrice: Double,
    val exitOptionPrice: Double,
    val tradeCandles: List<Candle>
)

data class OptimizationResult(
    val strategyName: String,
    val parameterValue: Double,
    val winRate: Double,
    val grossProfit: Double,
    val grossLoss: Double,
    val profitFactor: Double,
    val netPnlPts: Double,
    val netPnlRupees: Double,
    val maxDrawdownPts: Double,
    val maxDrawdownRupees: Double,
    val completedTrades: List<OptionTradeResult>
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
 * Fetches NIFTY index data, runs strategy signals from September 30th to October 5th, 2026, and resolves weekly options.
 */
suspend fun fetchDataAndRunDerivativeBacktest(viewModel: AuthViewModel, appId: String, token: String) {
//    val rangeStart = "2026-10-05"
    val rangeStart = "2025-01-01"
    val rangeEnd = "2026-09-30"
//    val rangeStart = java.time.LocalDate.now().toString()
//    val rangeEnd = java.time.LocalDate.now().toString()
    val rangeFrom = java.time.LocalDate.parse(rangeStart).minusWeeks(1).toString()

//    val indexSymbol = "BSE:SENSEX-INDEX" // Change to "NSE:NIFTY50-INDEX" for Nifty or "BSE:SENSEX-INDEX" for Sensex // Set to "NSE:NIFTY50-INDEX" for Nifty or "BSE:SENSEX-INDEX" for Sensex
    val indexSymbol = "NSE:NIFTY50-INDEX" // Change to "NSE:NIFTY50-INDEX" for Nifty or "BSE:SENSEX-INDEX" for Sensex // Set to "NSE:NIFTY50-INDEX" for Nifty or "BSE:SENSEX-INDEX" for Sensex
    println("\n[3] Fetching Historical Data for $indexSymbol from Fyers API ($rangeFrom to $rangeEnd)...")
    val history = viewModel.fetchHistoricalDataInChunks(
        appId = appId,
        accessToken = token,
        symbol = indexSymbol,
        resolution = "1",
        rangeFrom = rangeFrom,
        rangeTo = rangeEnd
    )

    /*val jsonString = ""
    val history = try {
        JSONObject(jsonString)
    } catch (e: org.json.JSONException) {
        // Handle parsing error
        println("Invalid JSON: ${e.message}")
        null
    }*/

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
 * Parses Fyers historical JSON response into a list of [Candle] objects.
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
            val timeStr = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

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
 * Executes the backtest: runs signals on NIFTY index and resolves weekly option symbols strictly via option chain lookup.
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
    println("Parent: $indexSymbol | Derivative: ATM Option Data")
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
    var tradeCount = 0
    var derivativeCandles = listOf<Candle>()
    val rawTrades = mutableListOf<RawTradeCandidate>()

    for (i in indicatorCandles.indices) {
        val ic = indicatorCandles[i]
        val candleDate = ic.timestamp.substring(0, 10)
        if (candleDate < startDate || candleDate > endDate) continue

        val timePart = ic.timestamp.substring(11, 16)

        // Intraday rule: Ensure any active position from a previous day is squared off by 15:20 of entryDate
        if (activePosition != null && candleDate > entryDate) {
            val exitTime = "$entryDate 15:20"
            val exitDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= exitTime }
                ?: derivativeCandles.lastOrNull()

            if (exitDerivativeBar != null) {
                val tradeCandles = derivativeCandles.filter { it.timestamp >= entryTime && it.timestamp <= exitDerivativeBar.timestamp }
                rawTrades.add(
                    RawTradeCandidate(
                        tradeNo = tradeCount,
                        direction = activePosition.direction,
                        entryTime = entryTime,
                        exitTime = exitDerivativeBar.timestamp,
                        derivativeSymbol = derivativeSymbol,
                        entryOptionPrice = entryOptionPrice,
                        exitOptionPrice = exitDerivativeBar.close,
                        tradeCandles = tradeCandles
                    )
                )
            }
            activePosition = null
            derivativeCandles = emptyList()
        }

        val slice = indicatorCandles.subList(0, i + 1)
        val decision = engine.evaluate(slice, activePosition)

        if (activePosition == null) {
            if ((decision.action == "ENTER_LONG" || decision.action == "ENTER_SHORT") && timePart <= "15:20") {
                tradeCount++
                val isLong = decision.action == "ENTER_LONG"
                val entrySpot = ic.close

                /*entryTime = ic.timestamp
                entryDate = candleDate*/
//                println(entryTime +" Original")

                entryTime = LocalDateTime.parse(ic.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .plusMinutes(1)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                entryDate = entryTime.substring(0, 10)
//                println(entryTime +" Plus One")
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
//                println("\u001B[32m🔔 [PARENT INDEX SIGNAL] NIFTY ${if (isLong) "LONG" else "SHORT"} Triggered at $entryTime\u001B[0m  first candle time ${derivativeCandles[0].timestamp}")
                // Here if entry comes at 9:30 candle close , we are fetching data at 9:35:02 second , so order gets executed at 9:35:02 so we are considering open price od 9:35
                val entryDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= entryTime }
                    ?: throw IllegalStateException("No 1-min derivative candle found at or after entry time $entryTime for $derivativeSymbol")
                entryOptionPrice = entryDerivativeBar.open
//                println(entryDerivativeBar.timestamp +" Derivative Candle")

                activePosition = EmaTrendPositionContext(
                    direction = if (isLong) "LONG" else "SHORT",
                    entryUnderlying = entrySpot,
                    stopUnderlying = ic.ema11
                )
            }
        } else {
            // Intraday rule: Auto square-off at 15:20 or strategy EXIT
            val isSquareOff = timePart >= "15:20"
            if (decision.action == "EXIT" || isSquareOff) {

                // Here if exit comes at 10:30 candle close , we are fetching data at 10:35:02 second , so order gets executed at 10:35:02 so we are considering open price of 10:35

                val validExitTime = LocalDateTime.parse(ic.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .plusMinutes(1)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                val exitTime = if (isSquareOff && timePart > "15:20") "$candleDate 15:20" else validExitTime
                val exitDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= exitTime } ?: run {
                    derivativeCandles = fetchDerivativeCandles(viewModel, appId, token, derivativeSymbol, entryDate, candleDate)
                    derivativeCandles.firstOrNull { it.timestamp >= exitTime }
                        ?: derivativeCandles.lastOrNull()
                        ?: throw IllegalStateException("No 1-min derivative candle found at or after exit time $exitTime for $derivativeSymbol")
                }

                val tradeCandles = derivativeCandles.filter { it.timestamp >= entryTime && it.timestamp <= exitDerivativeBar.timestamp }
                rawTrades.add(
                    RawTradeCandidate(
                        tradeNo = tradeCount,
                        direction = activePosition.direction,
                        entryTime = entryTime,
                        exitTime = exitDerivativeBar.timestamp,
                        derivativeSymbol = derivativeSymbol,
                        entryOptionPrice = entryOptionPrice,
                        exitOptionPrice = exitDerivativeBar.open,
                        tradeCandles = tradeCandles
                    )
                )

                activePosition = null
                derivativeCandles = emptyList()
            }
        }
    }

    // Run Optimizer over Trailing Peak and Fixed Target Strategies
    val optimizationResults = mutableListOf<OptimizationResult>()
    val lotSize = 65.0

    // 1. Test Trailing Peak Strategies
    val trailBuffers = listOf(10.0, 15.0, 20.0, 25.0, 30.0)
    for (buffer in trailBuffers) {
        val tradesForBuffer = mutableListOf<OptionTradeResult>()
        var runningCumPnl = 0.0
        var peak = 0.0
        var maxDD = 0.0
        var wins = 0
        var losses = 0
        var gProfit = 0.0
        var gLoss = 0.0

        for ((idx, raw) in rawTrades.withIndex()) {
            var runningPeak = raw.entryOptionPrice
            var firstLotExitPrice = raw.entryOptionPrice
            for (c in raw.tradeCandles) {
                if (c.high > runningPeak) runningPeak = c.high
                val triggerPrice = runningPeak - buffer
                if (c.low <= triggerPrice && triggerPrice > raw.entryOptionPrice) {
                    firstLotExitPrice = triggerPrice
                    break
                }
            }
            if (firstLotExitPrice <= raw.entryOptionPrice) {
                firstLotExitPrice = runningPeak
            }

            val firstLotPnl = firstLotExitPrice - raw.entryOptionPrice
            val secondLotPnl = raw.exitOptionPrice - raw.entryOptionPrice
            val totalTradePnl = firstLotPnl + secondLotPnl
            runningCumPnl += totalTradePnl

            if (runningCumPnl > peak) peak = runningCumPnl
            val dd = peak - runningCumPnl
            if (dd > maxDD) maxDD = dd

            if (totalTradePnl > 0) {
                wins++
                gProfit += totalTradePnl
            } else if (totalTradePnl < 0) {
                losses++
                gLoss += kotlin.math.abs(totalTradePnl)
            }

            tradesForBuffer.add(
                OptionTradeResult(
                    tradeNo = idx + 1,
                    direction = raw.direction,
                    entryTime = raw.entryTime,
                    exitTime = raw.exitTime,
                    derivativeSymbol = raw.derivativeSymbol,
                    entryOptionPrice = raw.entryOptionPrice,
                    firstLotExitPrice = firstLotExitPrice,
                    secondLotExitPrice = raw.exitOptionPrice,
                    firstLotPnl = firstLotPnl,
                    secondLotPnl = secondLotPnl,
                    totalTradePnl = totalTradePnl,
                    cumulativePnl = runningCumPnl,
                    cumulativeRupees = runningCumPnl * lotSize
                )
            )
        }

        val winRate = if (tradesForBuffer.isNotEmpty()) (wins.toDouble() / tradesForBuffer.size) * 100 else 0.0
        val profitFactor = if (gLoss > 0) gProfit / gLoss else if (gProfit > 0) 999.0 else 0.0
        val netPnlPts = runningCumPnl
        val netPnlRupees = netPnlPts * lotSize

        optimizationResults.add(
            OptimizationResult(
                strategyName = "Trailing Peak",
                parameterValue = buffer,
                winRate = winRate,
                grossProfit = gProfit,
                grossLoss = gLoss,
                profitFactor = profitFactor,
                netPnlPts = netPnlPts,
                netPnlRupees = netPnlRupees,
                maxDrawdownPts = maxDD,
                maxDrawdownRupees = maxDD * lotSize,
                completedTrades = tradesForBuffer
            )
        )
    }

    // 2. Test Fixed Target Strategies
    val fixedTargets = listOf(15.0, 25.0, 35.0, 50.0, 75.0)
    for (target in fixedTargets) {
        val tradesForTarget = mutableListOf<OptionTradeResult>()
        var runningCumPnl = 0.0
        var peak = 0.0
        var maxDD = 0.0
        var wins = 0
        var losses = 0
        var gProfit = 0.0
        var gLoss = 0.0

        for ((idx, raw) in rawTrades.withIndex()) {
            val targetPrice = raw.entryOptionPrice + target
            var firstLotExitPrice = raw.entryOptionPrice
            var hitTarget = false
            for (c in raw.tradeCandles) {
                if (c.high >= targetPrice) {
                    firstLotExitPrice = targetPrice
                    hitTarget = true
                    break
                }
            }
            if (!hitTarget) {
                firstLotExitPrice = raw.exitOptionPrice
            }

            val firstLotPnl = firstLotExitPrice - raw.entryOptionPrice
            val secondLotPnl = raw.exitOptionPrice - raw.entryOptionPrice
            val totalTradePnl = firstLotPnl + secondLotPnl
            runningCumPnl += totalTradePnl

            if (runningCumPnl > peak) peak = runningCumPnl
            val dd = peak - runningCumPnl
            if (dd > maxDD) maxDD = dd

            if (totalTradePnl > 0) {
                wins++
                gProfit += totalTradePnl
            } else if (totalTradePnl < 0) {
                losses++
                gLoss += kotlin.math.abs(totalTradePnl)
            }

            tradesForTarget.add(
                OptionTradeResult(
                    tradeNo = idx + 1,
                    direction = raw.direction,
                    entryTime = raw.entryTime,
                    exitTime = raw.exitTime,
                    derivativeSymbol = raw.derivativeSymbol,
                    entryOptionPrice = raw.entryOptionPrice,
                    firstLotExitPrice = firstLotExitPrice,
                    secondLotExitPrice = raw.exitOptionPrice,
                    firstLotPnl = firstLotPnl,
                    secondLotPnl = secondLotPnl,
                    totalTradePnl = totalTradePnl,
                    cumulativePnl = runningCumPnl,
                    cumulativeRupees = runningCumPnl * lotSize
                )
            )
        }

        val winRate = if (tradesForTarget.isNotEmpty()) (wins.toDouble() / tradesForTarget.size) * 100 else 0.0
        val profitFactor = if (gLoss > 0) gProfit / gLoss else if (gProfit > 0) 999.0 else 0.0
        val netPnlPts = runningCumPnl
        val netPnlRupees = netPnlPts * lotSize

        optimizationResults.add(
            OptimizationResult(
                strategyName = "Fixed Target",
                parameterValue = target,
                winRate = winRate,
                grossProfit = gProfit,
                grossLoss = gLoss,
                profitFactor = profitFactor,
                netPnlPts = netPnlPts,
                netPnlRupees = netPnlRupees,
                maxDrawdownPts = maxDD,
                maxDrawdownRupees = maxDD * lotSize,
                completedTrades = tradesForTarget
            )
        )
    }

    // Print Optimizer Comparative Summary Table
    println("\n======================================================================================================================================")
    println("1ST LOT EXIT STRATEGY & PARAMETER OPTIMIZER REPORT (Trailing Peak vs. Fixed Target | 1 Lot = 65 Qty)")
    println("======================================================================================================================================")
    println("| Strategy Type   | Parameter Value | Win Rate | Gross Profit | Gross Loss | Profit Factor | Net P&L (Pts) | Net P&L (INR) | Max Drawdown |")
    println("|-----------------|-----------------|----------|--------------|------------|---------------|---------------+---------------+--------------|")

    for (res in optimizationResults) {
        val pfStr = if (res.profitFactor > 900) "999.0+" else String.format("%.2f", res.profitFactor)
        val paramStr = if (res.strategyName == "Trailing Peak") "Buffer: ${res.parameterValue.toInt()} pts" else "Target: ${res.parameterValue.toInt()} pts"
        println("| ${res.strategyName.padEnd(15)} | ${paramStr.padEnd(15)} | ${String.format("%.1f", res.winRate)}%    | ${String.format("%.2f", res.grossProfit)} pts | ${String.format("%.2f", res.grossLoss)} pts | $pfStr           | ${String.format("%.2f", res.netPnlPts)} pts   | ₹${String.format("%,.2f", res.netPnlRupees)} | ${String.format("%.2f", res.maxDrawdownPts)} pts |")
    }
    println("======================================================================================================================================")

    // Select Best Resulting Strategy & Parameter (highest Net P&L in INR, tie-breaker Profit Factor)
    val bestResult = optimizationResults.maxWithOrNull(compareBy<OptimizationResult> { it.netPnlRupees }.thenBy { it.profitFactor })
        ?: optimizationResults.first()

    val optimalParamDesc = if (bestResult.strategyName == "Trailing Peak") "Buffer: ${bestResult.parameterValue.toInt()} pts" else "Target: ${bestResult.parameterValue.toInt()} pts"

    println("\n🏆 ABSOLUTE BEST 1ST LOT EXIT STRATEGY FOUND:")
    println("• Strategy Type         : ${bestResult.strategyName}")
    println("• Optimal Parameter     : $optimalParamDesc")
    println("• Optimal Win Rate      : ${String.format("%.1f", bestResult.winRate)}%")
    println("• Optimal Profit Factor : ${String.format("%.2f", bestResult.profitFactor)}")
    println("• Optimal Net P&L (INR) : ₹${String.format("%,.2f", bestResult.netPnlRupees)}")
    println("• Optimal Max Drawdown  : ${String.format("%.2f", bestResult.maxDrawdownPts)} pts (₹${String.format("%,.2f", bestResult.maxDrawdownRupees)})")
    println("========================================================================================================================\n")

    val completedTrades = bestResult.completedTrades

    // Print detailed 2-lot trade report with 1st lot exit at optimal strategy, 2nd lot exit at actual exit, and cumulative P&L in points and money
    println("\n========================================================================================================================================================================")
    println("DETAILED 2-LOT OPTION BACKTEST REPORT (${bestResult.strategyName} | $optimalParamDesc | 1 Lot = 65 Qty)")
    println("========================================================================================================================================================================")
    
    if (completedTrades.isEmpty()) {
        println("⚠️ NO TRADES EXECUTED: The strategy did not trigger any entry signals during the specified date range.")
    } else {
        println("| # | Signal | Entry Time | Exit Time | Derivative Symbol | Entry | 1st Lot Exit | 2nd Lot Exit | 1st P&L | 2nd P&L | Trade P&L | Cum. P&L (Pts) | Cum. P&L (₹) |")
        println("|---|--------|------------|-----------|-------------------|-------|--------------|--------------|---------|---------|-----------|----------------|--------------|")
    }

    var peak = 0.0
    var maxDD = 0.0
    var wins = 0
    var losses = 0
    var gProfit = 0.0
    var gLoss = 0.0
    var totalFirstLotPnl = 0.0
    var totalSecondLotPnl = 0.0

    for (t in completedTrades) {
        totalFirstLotPnl += t.firstLotPnl
        totalSecondLotPnl += t.secondLotPnl

        if (t.cumulativePnl > peak) peak = t.cumulativePnl
        val dd = peak - t.cumulativePnl
        if (dd > maxDD) maxDD = dd

        if (t.totalTradePnl > 0) {
            wins++
            gProfit += t.totalTradePnl
        } else if (t.totalTradePnl < 0) {
            losses++
            gLoss += kotlin.math.abs(t.totalTradePnl)
        }

        val sign1 = if (t.firstLotPnl >= 0) "[WIN +]" else "[LOSS -]"
        val sign2 = if (t.secondLotPnl >= 0) "[WIN +]" else "[LOSS -]"
        val signTrade = if (t.totalTradePnl >= 0) "[WIN +]" else "[LOSS -]"
        val signCumPts = if (t.cumulativePnl >= 0) "[+]" else "[-]"
        val signCumRs = if (t.cumulativeRupees >= 0) "[+]" else "[-]"

        println("| ${t.tradeNo} | ${t.direction} | ${t.entryTime} | ${t.exitTime} | ${t.derivativeSymbol} | ${String.format("%.2f", t.entryOptionPrice)} | ${String.format("%.2f", t.firstLotExitPrice)} | ${String.format("%.2f", t.secondLotExitPrice)} | $sign1 ${String.format("%.2f", t.firstLotPnl)} | $sign2 ${String.format("%.2f", t.secondLotPnl)} | $signTrade ${String.format("%.2f", t.totalTradePnl)} | $signCumPts ${String.format("%.2f", t.cumulativePnl)} pts | $signCumRs ₹${String.format("%,.2f", t.cumulativeRupees)} |")
    }

    val combinedStrategyRupees = (totalFirstLotPnl + totalSecondLotPnl) * lotSize
    val peakRupees = peak * lotSize
    val maxDdRupees = maxDD * lotSize
    val winRate = if (completedTrades.isNotEmpty()) (wins.toDouble() / completedTrades.size) * 100 else 0.0
    val profitFactor = if (gLoss > 0) gProfit / gLoss else if (gProfit > 0) 999.0 else 0.0

    println("========================================================================================================================================================================")
    println("2-LOT STRATEGY PERFORMANCE & EXECUTION SUMMARY (1 Lot = 65 Qty | 2 Lots Total = 130 Qty):")
    println("• Total Trades Executed           : ${completedTrades.size}")
    println("• Winning Trades (Net > 0)        : $wins W | Losing Trades (Net < 0): $losses L (Win Rate: ${String.format("%.1f", winRate)}%)")
    println("• Gross Profit                    : ${String.format("%.2f", gProfit)} pts | Gross Loss: ${String.format("%.2f", gLoss)} pts")
    println("• Profit Factor                   : ${String.format("%.2f", profitFactor)}")
    println("• Total 1st Lot P&L (Exited Peak) : ${String.format("%.2f", totalFirstLotPnl)} points")
    println("• Total 2nd Lot P&L (Exited Exit) : ${String.format("%.2f", totalSecondLotPnl)} points")
    println("• Combined Net Strategy P&L (Pts) : ${String.format("%.2f", totalFirstLotPnl + totalSecondLotPnl)} points")
    println("• Combined Net Strategy P&L (INR) : ₹${String.format("%,.2f", combinedStrategyRupees)}")
    println("• Peak Cumulative P&L           : ${String.format("%.2f", peak)} points (₹${String.format("%,.2f", peakRupees)})")
    println("• Max Drawdown                  : [DD] ${String.format("%.2f", maxDD)} points (₹${String.format("%,.2f", maxDdRupees)})")
    println("========================================================================================================================================================================")
    System.out.flush()
    println()
    println()
}
