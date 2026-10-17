package com.example.service

import com.example.strategy.*
import com.example.util.AuthUtils
import com.example.util.OptionUtils
import com.example.viewmodel.AuthViewModel
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Scanner
import kotlin.math.abs

data class StrangleTradeResult(
    val date: String,
    val indexName: String,
    val symbol: String,
    val type: String,
    val entryTime: String,
    val exitTime: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val pnlPts: Double,
    val pnlRupees: Double,
    val exitReason: String
)

class SensexNiftyBacktestRunner(
    private val viewModel: AuthViewModel,
    private val appId: String,
    private val token: String
) {
    suspend fun runBacktest(startDateStr: String, endDateStr: String) {
        println("\n==============================================================")
        println("RUNNING NIFTY + SENSEX OTM4 STRANGLE BACKTEST ($startDateStr to $endDateStr)")
        println("==============================================================\n")

        var start = LocalDate.parse(startDateStr)
        val end = LocalDate.parse(endDateStr)

        val allTrades = mutableListOf<StrangleTradeResult>()
        var totalCombinedNetPnl = 0.0

        while (!start.isAfter(end)) {
            val dateStr = start.toString()
            val runNifty = SensexNiftyStrangleScheduler.shouldRunNifty(start)
            val runSensex = SensexNiftyStrangleScheduler.shouldRunSensex(start)

            if (runNifty) {
                println("\n--------------------------------------------------------------")
                println("📅 [$dateStr] Running NIFTY Strangle System...")
                val trades = simulateIndexDay(dateStr, "NIFTY", "NSE:NIFTY50-INDEX", 50, 65.0)
                allTrades.addAll(trades)
            }

            if (runSensex) {
                println("\n--------------------------------------------------------------")
                println("📅 [$dateStr] Running SENSEX Strangle System...")
                val trades = simulateIndexDay(dateStr, "SENSEX", "BSE:SENSEX-INDEX", 100, 20.0)
                allTrades.addAll(trades)
            }

            start = start.plusDays(1)
        }

        println("\n====================================================================")
        println("BACKTEST PERFORMANCE SUMMARY (${startDateStr} to ${endDateStr}):")
        println("• Total Trades Executed : ${allTrades.size}")
        for (t in allTrades) {
            totalCombinedNetPnl += t.pnlRupees
        }
        val winTrades = allTrades.count { t -> t.pnlRupees > 0 }
        val lossTrades = allTrades.count { t -> t.pnlRupees < 0 }
        val winRate = if (allTrades.isNotEmpty()) (winTrades.toDouble() / allTrades.size) * 100 else 0.0
        val netStr = if (totalCombinedNetPnl >= 0) "+₹${String.format("%,.2f", totalCombinedNetPnl)}" else "-₹${String.format("%,.2f", abs(totalCombinedNetPnl))}"

        println("• Winning Trades        : $winTrades W | Losing Trades: $lossTrades L (Win Rate: ${String.format("%.1f", winRate)}%)")
        println("• Combined Net P&L (INR): $netStr")
        println("====================================================================")
    }

    private suspend fun simulateIndexDay(
        dateStr: String,
        indexName: String,
        indexSymbol: String,
        strikeInterval: Int,
        lotSize: Double
    ): List<StrangleTradeResult> {
        val trades = mutableListOf<StrangleTradeResult>()
        try {
            println("   • [Data] Fetching index 1M candles for $indexSymbol on $dateStr...")
            val history = viewModel.fetchHistoricalDataInChunks(appId, token, indexSymbol, "1", dateStr, dateStr)
                ?: viewModel.fetchHistoricalData(appId, token, indexSymbol, "1", dateStr, dateStr) ?: return trades
            val indexCandles = parseFyersCandlesForStrangle(history)
            if (indexCandles.isEmpty()) {
                println("   ⚠️ [$indexName] No index candles found for $dateStr")
                return trades
            }

            val entryCandle = indexCandles.firstOrNull { it.timestamp.substring(11, 16) >= "09:16" } ?: return trades
            val spotPrice = entryCandle.open

            val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)
            val otmCallStrike = atmStrike + (4 * strikeInterval)
            val otmPutStrike = atmStrike - (4 * strikeInterval)
            println("   • [Spot & Strikes] Spot: $spotPrice | ATM: $atmStrike | OTM4 CE Strike: $otmCallStrike | OTM4 PE Strike: $otmPutStrike")

            val entryTime = entryCandle.timestamp

            val isHistorical = LocalDate.parse(dateStr).isBefore(LocalDate.now().minusDays(1))
            val callSymbol: String
            val putSymbol: String
            val callCandles: List<Candle>
            val putCandles: List<Candle>

            println("   • [Options] Resolving symbols (Historical mode: $isHistorical)...")
            if (isHistorical) {
                val callRes = OptionUtils.resolveAndFetchExpiredDerivativeCandles(viewModel, appId, token, indexSymbol, spotPrice, "CE", dateStr, 0)
                val putRes = OptionUtils.resolveAndFetchExpiredDerivativeCandles(viewModel, appId, token, indexSymbol, spotPrice, "PE", dateStr, 0)
                callSymbol = callRes.symbol
                putSymbol = putRes.symbol
                callCandles = callRes.candles
                putCandles = putRes.candles
            } else {
                callSymbol = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, spotPrice, "CE", 0)
                putSymbol = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, spotPrice, "PE", 0)
                val callHistory = viewModel.fetchHistoricalDataInChunks(appId, token, callSymbol, "1", dateStr, dateStr)
                    ?: viewModel.fetchHistoricalData(appId, token, callSymbol, "1", dateStr, dateStr)
                val putHistory = viewModel.fetchHistoricalDataInChunks(appId, token, putSymbol, "1", dateStr, dateStr)
                    ?: viewModel.fetchHistoricalData(appId, token, putSymbol, "1", dateStr, dateStr)
                callCandles = if (callHistory != null) parseFyersCandlesForStrangle(callHistory) else emptyList()
                putCandles = if (putHistory != null) parseFyersCandlesForStrangle(putHistory) else emptyList()
            }

            println("   • [Symbols Resolved] Call: $callSymbol | Put: $putSymbol")
            if (callCandles.isEmpty() || putCandles.isEmpty()) {
                println("   ⚠️ [$indexName] Option candle data is empty for $dateStr")
                return trades
            }

            val callEntryBar = callCandles.firstOrNull { it.timestamp >= entryTime } ?: callCandles.firstOrNull() ?: return trades
            val putEntryBar = putCandles.firstOrNull { it.timestamp >= entryTime } ?: putCandles.firstOrNull() ?: return trades

            val callEntryPrice = callEntryBar.open
            val putEntryPrice = putEntryBar.open

            val callLeg = StrangleLeg(callSymbol, "CE", otmCallStrike, callEntryPrice, callEntryPrice, callEntryPrice * 1.40, callEntryPrice * 0.30)
            val putLeg = StrangleLeg(putSymbol, "PE", otmPutStrike, putEntryPrice, putEntryPrice, putEntryPrice * 1.40, putEntryPrice * 0.30)

            println("   ✅ [$indexName ENTRY @ $entryTime]")
            println("      - Trade Date   : $dateStr")
            println("      - Chosen CE    : $callSymbol")
            println("      - Chosen PE    : $putSymbol")
            println("      - SELL CE Entry: ₹${String.format("%.2f", callEntryPrice)} | SL: ₹${String.format("%.2f", callLeg.stopLossPrice)} | Tgt: ₹${String.format("%.2f", callLeg.targetPrice)}")
            println("      - SELL PE Entry: ₹${String.format("%.2f", putEntryPrice)} | SL: ₹${String.format("%.2f", putLeg.stopLossPrice)} | Tgt: ₹${String.format("%.2f", putLeg.targetPrice)}")

            val allTimestamps = (callCandles.map { it.timestamp } + putCandles.map { it.timestamp }).distinct().sorted()

            for (ts in allTimestamps) {
                if (ts < entryTime) continue
                val timePart = ts.substring(11, 16)
                if (ts.substring(0, 10) != dateStr) continue

                val isSquareOff = timePart >= "15:25"

                val callBar = callCandles.firstOrNull { it.timestamp == ts }
                val putBar = putCandles.firstOrNull { it.timestamp == ts }

                val callLtp = callBar?.close ?: callLeg.currentPrice
                val putLtp = putBar?.close ?: putLeg.currentPrice

                val currentMtm = calculateMtm(callLeg, putLeg, callLtp, putLtp, lotSize)
                if (currentMtm <= -1000.0) {
                    println("   🛑 [$indexName MTM SL BREACH] Combined MTM reached ₹${String.format("%.2f", currentMtm)} <= -₹1,000 at $ts. Square off all legs.")
                    if (callLeg.status == "ACTIVE") {
                        val pnlPts = callLeg.entryPrice - callLtp
                        val pnlRupees = (callLeg.realizedPnl + pnlPts) * lotSize
                        trades.add(StrangleTradeResult(dateStr, indexName, callLeg.symbol, "CE", entryTime, ts, callLeg.entryPrice, callLtp, pnlPts, pnlRupees, "OVERALL_MTM_SL"))
                        callLeg.status = "EXITED"
                        println("      - Exited CE ($callSymbol) @ $callLtp | PnL: ${String.format("%.2f", pnlPts)} pts (₹${String.format("%,.2f", pnlRupees)})")
                    }
                    if (putLeg.status == "ACTIVE") {
                        val pnlPts = putLeg.entryPrice - putLtp
                        val pnlRupees = (putLeg.realizedPnl + pnlPts) * lotSize
                        trades.add(StrangleTradeResult(dateStr, indexName, putLeg.symbol, "PE", entryTime, ts, putLeg.entryPrice, putLtp, pnlPts, pnlRupees, "OVERALL_MTM_SL"))
                        putLeg.status = "EXITED"
                        println("      - Exited PE ($putSymbol) @ $putLtp | PnL: ${String.format("%.2f", pnlPts)} pts (₹${String.format("%,.2f", pnlRupees)})")
                    }
                    break
                }

                if (isSquareOff) {
                    println("   🏁 [$indexName EOD SQUARE OFF] 3:25 PM reached at $ts. Square off remaining legs.")
                    if (callLeg.status == "ACTIVE") {
                        val pnlPts = callLeg.entryPrice - callLtp
                        val pnlRupees = (callLeg.realizedPnl + pnlPts) * lotSize
                        trades.add(StrangleTradeResult(dateStr, indexName, callLeg.symbol, "CE", entryTime, ts, callLeg.entryPrice, callLtp, pnlPts, pnlRupees, "AUTO_SQUARE_OFF"))
                        callLeg.status = "EXITED"
                        println("      - Square off CE ($callSymbol) @ $callLtp | PnL: ${String.format("%.2f", pnlPts)} pts (₹${String.format("%,.2f", pnlRupees)})")
                    }
                    if (putLeg.status == "ACTIVE") {
                        val pnlPts = putLeg.entryPrice - putLtp
                        val pnlRupees = (putLeg.realizedPnl + pnlPts) * lotSize
                        trades.add(StrangleTradeResult(dateStr, indexName, putLeg.symbol, "PE", entryTime, ts, putLeg.entryPrice, putLtp, pnlPts, pnlRupees, "AUTO_SQUARE_OFF"))
                        putLeg.status = "EXITED"
                        println("      - Square off PE ($putSymbol) @ $putLtp | PnL: ${String.format("%.2f", pnlPts)} pts (₹${String.format("%,.2f", pnlRupees)})")
                    }
                    break
                }

                // Manage Call Leg
                if (callLeg.status == "ACTIVE") {
                    callLeg.currentPrice = callLtp
                    if (callLtp >= callLeg.stopLossPrice) {
                        callLeg.status = "SL_HIT"
                        val pnlPts = callLeg.entryPrice - callLeg.stopLossPrice
                        val pnlRupees = (callLeg.realizedPnl + pnlPts) * lotSize
                        trades.add(StrangleTradeResult(dateStr, indexName, callLeg.symbol, "CE", entryTime, ts, callLeg.entryPrice, callLeg.stopLossPrice, pnlPts, pnlRupees, "SL_HIT"))
                        println("   ❌ [$indexName CALL SL HIT] at $ts | LTP: $callLtp >= SL: ${callLeg.stopLossPrice} | PnL: ${String.format("%.2f", pnlPts)} pts (₹${String.format("%,.2f", pnlRupees)})")
                    } else if (callLtp <= callLeg.targetPrice) {
                        val pnlPts = callLeg.entryPrice - callLeg.targetPrice
                        callLeg.realizedPnl += pnlPts
                        val pnlRupees = pnlPts * lotSize
                        trades.add(StrangleTradeResult(dateStr, indexName, callLeg.symbol, "CE", entryTime, ts, callLeg.entryPrice, callLeg.targetPrice, pnlPts, pnlRupees, "TARGET_HIT"))
                        println("   🎯 [$indexName CALL TARGET HIT] at $ts | LTP: $callLtp <= Tgt: ${callLeg.targetPrice} | PnL: +${String.format("%.2f", pnlPts)} pts (+₹${String.format("%,.2f", pnlRupees)})")

                        if (callLeg.targetReentryCount < 1) {
                            callLeg.targetReentryCount++
                            callLeg.entryPrice = callLtp
                            callLeg.stopLossPrice = callLtp * 1.40
                            callLeg.targetPrice = callLtp * 0.30
                            callLeg.status = "ACTIVE"
                            println("      🚀 [CE RE-ENTRY #${callLeg.targetReentryCount}] Re-entered at ₹${String.format("%.2f", callLtp)} | New SL: ${callLeg.stopLossPrice} | New Tgt: ${callLeg.targetPrice}")
                        } else {
                            callLeg.status = "TARGET_HIT"
                        }
                    }
                }

                // Manage Put Leg
                if (putLeg.status == "ACTIVE") {
                    putLeg.currentPrice = putLtp
                    if (putLtp >= putLeg.stopLossPrice) {
                        putLeg.status = "SL_HIT"
                        val pnlPts = putLeg.entryPrice - putLeg.stopLossPrice
                        val pnlRupees = (putLeg.realizedPnl + pnlPts) * lotSize
                        trades.add(StrangleTradeResult(dateStr, indexName, putLeg.symbol, "PE", entryTime, ts, putLeg.entryPrice, putLeg.stopLossPrice, pnlPts, pnlRupees, "SL_HIT"))
                        println("   ❌ [$indexName PUT SL HIT] at $ts | LTP: $putLtp >= SL: ${putLeg.stopLossPrice} | PnL: ${String.format("%.2f", pnlPts)} pts (₹${String.format("%,.2f", pnlRupees)})")
                    } else if (putLtp <= putLeg.targetPrice) {
                        val pnlPts = putLeg.entryPrice - putLeg.targetPrice
                        putLeg.realizedPnl += pnlPts
                        val pnlRupees = pnlPts * lotSize
                        trades.add(StrangleTradeResult(dateStr, indexName, putLeg.symbol, "PE", entryTime, ts, putLeg.entryPrice, putLeg.targetPrice, pnlPts, pnlRupees, "TARGET_HIT"))
                        println("   🎯 [$indexName PUT TARGET HIT] at $ts | LTP: $putLtp <= Tgt: ${putLeg.targetPrice} | PnL: +${String.format("%.2f", pnlPts)} pts (+₹${String.format("%,.2f", pnlRupees)})")

                        if (putLeg.targetReentryCount < 1) {
                            putLeg.targetReentryCount++
                            putLeg.entryPrice = putLtp
                            putLeg.stopLossPrice = putLtp * 1.40
                            putLeg.targetPrice = putLtp * 0.30
                            putLeg.status = "ACTIVE"
                            println("      🚀 [PE RE-ENTRY #${putLeg.targetReentryCount}] Re-entered at ₹${String.format("%.2f", putLtp)} | New SL: ${putLeg.stopLossPrice} | New Tgt: ${putLeg.targetPrice}")
                        } else {
                            putLeg.status = "TARGET_HIT"
                        }
                    }
                }
            }

        } catch (e: Exception) {
            println("❌ [$indexName Simulation Error on $dateStr]: ${e.message}")
            e.printStackTrace()
        }
        return trades
    }

    private fun calculateMtm(call: StrangleLeg, put: StrangleLeg, callLtp: Double, putLtp: Double, lotSize: Double): Double {
        var mtmPts = 0.0
        if (call.status == "ACTIVE") mtmPts += (call.entryPrice - callLtp) else mtmPts += call.realizedPnl
        if (put.status == "ACTIVE") mtmPts += (put.entryPrice - putLtp) else mtmPts += put.realizedPnl
        return mtmPts * lotSize
    }
}

private fun parseFyersCandlesForStrangle(json: JSONObject): List<Candle> {
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

fun main() {
    val dotenv = dotenv { ignoreIfMissing = true }
    val viewModel = AuthViewModel()
    val scanner = Scanner(System.`in`)

    println("=== NIFTY + SENSEX OTM4 Strangle Backtest Tool ===")
    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"] ?: ""
    val secretKey = System.getenv("FYERS_SECRET_KEY") ?: dotenv["FYERS_SECRET_KEY"] ?: ""
    val redirectUri = System.getenv("FYERS_REDIRECT_URI") ?: dotenv["FYERS_REDIRECT_URI"] ?: "https://trade.fyers.in/api-login/redirect-uri/index.html"

    if (appId.isEmpty() || secretKey.isEmpty()) {
        println("⚠️ FYERS_APP_ID or FYERS_SECRET_KEY not set in environment or .env file.")
        return
    }

    val startDate = "2026-10-01"
    val endDate = LocalDate.now().toString()

    val cachedToken = viewModel.getCachedToken()
    if (cachedToken != null) {
        println("\n[Cache] Found valid cached Access Token! Skipping login flow.")
        runBlocking {
            SensexNiftyBacktestRunner(viewModel, appId, cachedToken).runBacktest(startDate, endDate)
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
            SensexNiftyBacktestRunner(viewModel, appId, finalState.accessToken).runBacktest(startDate, endDate)
        }
    } else {
        println("\n❌ FAILED: ${finalState.errorMessage}")
    }
}
