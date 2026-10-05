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
//    val rangeStart = "2026-10-05"
    val rangeStart = "2026-09-30"
    val rangeEnd = "2026-10-05"
    val rangeFrom = java.time.LocalDate.parse(rangeStart).minusWeeks(1).toString()

    println("\n[3] Fetching Nifty 50 Historical Data from Fyers API ($rangeFrom to $rangeEnd)...")
    val history = viewModel.fetchHistoricalDataInChunks(
        appId = appId,
        accessToken = token,
        symbol = "NSE:NIFTY50-INDEX",
        resolution = "5",
        rangeFrom = rangeFrom,
        rangeTo = rangeEnd
    )

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

            candles.add(Candle(timestamp = timeStr, open = open, high = high, low = low, close = close, volume = volume))
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

    val config = EmaTrendConfig()
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
                entryTime = ic.timestamp
                entryDate = candleDate
                val optionType = if (isLong) "CE" else "PE"
                
                // Resolve weekly option symbol strictly via live Fyers option chain lookup
                derivativeSymbol = OptionUtils.resolveWeeklyOptionSymbol(
                    viewModel = viewModel,
                    appId = appId,
                    token = token,
                    indexSymbol = "NSE:NIFTY50-INDEX",
                    spotPrice = entrySpot,
                    optionType = optionType,
                    expiryCode = "26O01"
                )

                println("🔔 [PARENT INDEX SIGNAL] NIFTY ${if (isLong) "LONG" else "SHORT"} Triggered at $entryTime")

                derivativeCandles = fetchDerivativeCandles(viewModel, appId, token, derivativeSymbol, candleDate, candleDate)

                val entryDerivativeBar = derivativeCandles.firstOrNull { it.timestamp >= entryTime }
                    ?: throw IllegalStateException("No 1-min derivative candle found at or after entry time $entryTime for $derivativeSymbol")
                entryOptionPrice = entryDerivativeBar.open

                activePosition = EmaTrendPositionContext(
                    direction = if (isLong) "LONG" else "SHORT",
                    entryUnderlying = entrySpot,
                    stopUnderlying = ic.ema11
                )
            }
        } else {
            // Intraday rule: Auto square-off at 15:25 or strategy EXIT
            val isSquareOff = timePart >= "15:25"
            if (decision.action == "EXIT" || isSquareOff) {
                val exitTime = if (isSquareOff && timePart > "15:25") "$candleDate 15:25" else ic.timestamp
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
            }
        }
    }

    // Print summary table without Cumulative P&L
    println("\n==========================================================================================")
    println("WEEKLY EXPIRY OPTION BACKTEST SUMMARY TABLE (SEPT 30 - OCT 5)")
    println("==========================================================================================")
    println("| # | Signal | Entry Time | Exit Time | Derivative Symbol | Entry Opt | Exit Opt | P&L (Pts) |")
    println("|---|--------|------------|-----------|-------------------|-----------|----------|-----------|")
    for (t in completedTrades) {
        val signEmoji = if (t.pnl >= 0) "🟢" else "🔴"
        println("| ${t.tradeNo} | ${t.direction} | ${t.entryTime} | ${t.exitTime} | ${t.derivativeSymbol} | ${String.format("%.2f", t.entryOptionPrice)} | ${String.format("%.2f", t.exitOptionPrice)} | $signEmoji ${String.format("%.2f", t.pnl)} |")
    }
    println("==========================================================================================")
    println("Total Trades Executed: ${completedTrades.size} | Net Derivative Option P&L: ${String.format("%.2f", totalOptionPnl)} points")
    println("==========================================================================================\n")
}
