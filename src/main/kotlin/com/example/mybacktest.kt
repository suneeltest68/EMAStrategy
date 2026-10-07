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
import kotlinx.coroutines.delay
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
    val pnl: Double
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
//    val rangeStart = "2026-09-30"
    val rangeStart = "2026-09-17"
//    val rangeEnd = "2026-10-06"
//    val rangeStart = java.time.LocalDate.now().toString()
    val rangeEnd = java.time.LocalDate.now().toString()
    val rangeFrom = java.time.LocalDate.parse(rangeStart).minusWeeks(1).toString()

    println("\n[3] Fetching Nifty 50 Historical Data from Fyers API ($rangeFrom to $rangeEnd)...")
    val history = viewModel.fetchHistoricalDataInChunks(
        appId = appId,
        accessToken = token,
        symbol = "NSE:NIFTY50-INDEX",
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
            println("Successfully parsed ${candles.size} historical candles. Running Weekly Expiry Derivative Backtest...")
            runDerivativeBacktestForRange(viewModel, appId, token, candles, rangeStart, rangeEnd)
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
    val history = viewModel.fetchHistoricalDataInChunks(appId, token, symbol, "1", from, to)
        ?: viewModel.fetchHistoricalData(appId, token, symbol, "1", from, to)
        ?: throw IllegalStateException("Failed to fetch 1-min historical data for derivative symbol $symbol from $from to $to")
    val candles = parseFyersCandles(history)
    if (candles.isEmpty()) {
        throw IllegalStateException("No 1-min candles returned for derivative symbol $symbol from $from to $to")
    }
    return candles
}

/**
 * Helper to record a completed trade and add to P&L.
 */
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

/**
 * Executes the backtest: runs signals on NIFTY index and resolves weekly option symbols strictly via option chain lookup.
 */
suspend fun runDerivativeBacktestForRange(
    viewModel: AuthViewModel,
    appId: String,
    token: String,
    candles: List<Candle>,
    startDate: String,
    endDate: String
) {
    println("\n==============================================================")
    println("EXECUTING WEEKLY EXPIRY OPTION BACKTEST (SEPT 30 - OCT 5)")
    println("Parent: NIFTY 50 Index | Derivative: ATM Option 1-Min Data")
    println("==============================================================\n")

    val config = EmaTrendConfig(
        emaFastPeriod = 20,
        emaMidPeriod = 55,
        emaSlowPeriod = 90,
        adxThreshold = 30.0
    )
    val indicatorCandles = TechnicalIndicators.buildIndicators(candles, config)
    val engine = EmaTrendSignalEngine(config)

    var activePosition: EmaTrendPositionContext? = null
    var entryTime = ""
    var entryDate = ""
    var derivativeSymbol = ""
    var entryOptionPrice = 0.0
    var totalOptionPnl = 0.0
    var tradeCount = 0
    var derivativeCandles = listOf<Candle>()
    val completedTrades = mutableListOf<OptionTradeResult>()

    for (i in indicatorCandles.indices) {
        val ic = indicatorCandles[i]
        val candleDate = ic.timestamp.substring(0, 10)
        if (candleDate < startDate || candleDate > endDate) continue

        val timePart = ic.timestamp.substring(11, 16)

        // Intraday rule: Ensure any active position from a previous day is squared off by 15:25 of entryDate
        if (activePosition != null && candleDate > entryDate) {
            val exitTime = "$entryDate 15:25"
            val exitDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= exitTime }
                ?: derivativeCandles.lastOrNull()

            if (exitDerivativeBar != null) {
                totalOptionPnl += recordTrade(
                    tradeNo = tradeCount,
                    direction = activePosition.direction,
                    entryTime = entryTime,
                    exitTime = exitDerivativeBar.timestamp,
                    derivativeSymbol = derivativeSymbol,
                    entryOptionPrice = entryOptionPrice,
                    exitOptionPrice = exitDerivativeBar.close,
                    completedTrades = completedTrades
                )
            }
            activePosition = null
            derivativeCandles = emptyList()
        }

        val slice = indicatorCandles.subList(0, i + 1)
        val decision = engine.evaluate(slice, activePosition)

        if (activePosition == null) {
            if (decision.action == "ENTER_LONG" || decision.action == "ENTER_SHORT") {
                tradeCount++
                val isLong = decision.action == "ENTER_LONG"
                val entrySpot = ic.close

                /*entryTime = ic.timestamp
                entryDate = candleDate*/

                entryTime = LocalDateTime.parse(ic.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .plusMinutes(1)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                entryDate = entryTime.substring(0, 10)
                val optionType = if (isLong) "CE" else "PE"
                // Resolve weekly option symbol strictly via live Fyers option chain lookup (expiryIndex = 1 for next week)
                derivativeSymbol = OptionUtils.resolveWeeklyOptionSymbol(
                    viewModel = viewModel,
                    appId = appId,
                    token = token,
                    indexSymbol = "NSE:NIFTY50-INDEX",
                    spotPrice = entrySpot,
                    optionType = optionType,
                    expiryIndex = 1
                )

                println("\u001B[32m🔔 [PARENT INDEX SIGNAL] NIFTY ${if (isLong) "LONG" else "SHORT"} Triggered at $entryTime\u001B[0m")
//                if (entryTime != "2026-10-06 09:30")
//                    TelegramNotifier.sendAlert("🚀 We got a trade signal at $entryTime")

                derivativeCandles = fetchDerivativeCandles(viewModel, appId, token, derivativeSymbol, candleDate, candleDate)

                // Here if entry comes at 9:30 candle close , we are fetching data at 9:35:02 second , so order gets executed at 9:35:02 so we are considering open price od 9:35
                val entryDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= entryTime }
                    ?: throw IllegalStateException("No 1-min derivative candle found at or after entry time $entryTime for $derivativeSymbol")
                entryOptionPrice = entryDerivativeBar.open

                activePosition = decision.updatedPosition ?: EmaTrendPositionContext(
                    direction = if (isLong) "LONG" else "SHORT",
                    entryUnderlying = entrySpot,
                    stopUnderlying = ic.ema11
                )
            }
        } else {
            // Intraday rule: Auto square-off at 15:25 or strategy EXIT
            val isSquareOff = timePart >= "15:25"
            if (decision.action == "EXIT" || isSquareOff) {

                // Here if exit comes at 10:30 candle close , we are fetching data at 10:35:02 second , so order gets executed at 10:35:02 so we are considering open price of 10:35

                val validExitTime = LocalDateTime.parse(ic.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .plusMinutes(1)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

                val exitTime = if (isSquareOff && timePart > "15:25") "$candleDate 15:25" else validExitTime
                val exitDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= exitTime } ?: run {
                    derivativeCandles = fetchDerivativeCandles(viewModel, appId, token, derivativeSymbol, entryDate, candleDate)
                    derivativeCandles.firstOrNull { it.timestamp >= exitTime }
                        ?: derivativeCandles.lastOrNull()
                        ?: throw IllegalStateException("No 1-min derivative candle found at or after exit time $exitTime for $derivativeSymbol")
                }

                totalOptionPnl += recordTrade(
                    tradeNo = tradeCount,
                    direction = activePosition.direction,
                    entryTime = entryTime,
                    exitTime = exitDerivativeBar.timestamp,
                    derivativeSymbol = derivativeSymbol,
                    entryOptionPrice = entryOptionPrice,
                    exitOptionPrice = exitDerivativeBar.close,
                    completedTrades = completedTrades
                )

                activePosition = null
                derivativeCandles = emptyList()
            } else {
                activePosition = decision.updatedPosition ?: activePosition
            }
        }
    }

    // Print summary table with Cumulative P&L and Max Drawdown details
    println("\n==========================================================================================================")
    println("WEEKLY EXPIRY OPTION BACKTEST SUMMARY TABLE & MAX DRAWDOWN REPORT (SEPT 30 - OCT 5)")
    println("==========================================================================================================")
    println("| # | Signal | Entry Time | Exit Time | Derivative Symbol | Entry Opt | Exit Opt | P&L (Pts) | Cum. P&L |")
    println("|---|--------|------------|-----------|-------------------|-----------|----------|-----------|----------|")

    var runningPnl = 0.0
    var peak = 0.0
    var maxDD = 0.0
    var wins = 0
    var losses = 0
    var gProfit = 0.0
    var gLoss = 0.0

    for (t in completedTrades) {
        runningPnl += t.pnl
        if (runningPnl > peak) peak = runningPnl
        val dd = peak - runningPnl
        if (dd > maxDD) maxDD = dd

        if (t.pnl > 0) {
            wins++
            gProfit += t.pnl
        } else if (t.pnl < 0) {
            losses++
            gLoss += kotlin.math.abs(t.pnl)
        }

        val signEmoji = if (t.pnl >= 0) "🟢" else "🔴"
        val cumSignEmoji = if (runningPnl >= 0) "🟢" else "🔴"
        println("| ${t.tradeNo} | ${t.direction} | ${t.entryTime} | ${t.exitTime} | ${t.derivativeSymbol} | ${String.format("%.2f", t.entryOptionPrice)} | ${String.format("%.2f", t.exitOptionPrice)} | $signEmoji ${String.format("%.2f", t.pnl)} | $cumSignEmoji ${String.format("%.2f", runningPnl)} |")
    }

    val winRate = if (completedTrades.isNotEmpty()) (wins.toDouble() / completedTrades.size) * 100 else 0.0
    val profitFactor = if (gLoss > 0) gProfit / gLoss else if (gProfit > 0) 999.0 else 0.0

    println("==========================================================================================================")
    println("PERFORMANCE & MAX DRAWDOWN STATISTICS:")
    println("• Total Trades Executed : ${completedTrades.size}")
    println("• Winning Trades        : $wins W | Losing Trades: $losses L (Win Rate: ${String.format("%.1f", winRate)}%)")
    println("• Gross Profit          : ${String.format("%.2f", gProfit)} pts | Gross Loss: ${String.format("%.2f", gLoss)} pts")
    println("• Profit Factor         : ${String.format("%.2f", profitFactor)}")
    println("• Net Derivative P&L    : ${String.format("%.2f", runningPnl)} points")
    println("• Peak P&L (High Water) : ${String.format("%.2f", peak)} points")
    println("• Max Drawdown          : 📉 ${String.format("%.2f", maxDD)} points")
    println("==========================================================================================================\n")
}
