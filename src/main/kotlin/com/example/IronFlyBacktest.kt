/**
 * ============================================================================
 * Weekly-Expiry Iron Fly (Option Selling) Strategy Backtest Runner
 * ============================================================================
 */
package com.example

import com.example.viewmodel.AuthViewModel
import com.example.strategy.*
import com.example.util.AuthUtils
import com.example.util.OptionUtils
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.DayOfWeek
import java.util.Scanner
import kotlin.math.abs

data class IronFlyBacktestResult(
    val tradeNo: Int,
    val date: String,
    val indexSymbol: String,
    val expiryDate: String,
    val soldStrike: Double,
    val entryTime: String,
    val exitTime: String,
    val exitReason: String,
    val netPnlPoints: Double,
    val netPnlRupees: Double,
    val cumulativePnlPoints: Double,
    val cumulativePnlRupees: Double
)

fun main() {
    val dotenv = dotenv { ignoreIfMissing = true }
    val viewModel = AuthViewModel()
    val scanner = Scanner(System.`in`)

    println("=== Weekly-Expiry Iron Fly Strategy Backtest Tool (Strict 5s Data) ===")
    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"] ?: ""
    val secretKey = System.getenv("FYERS_SECRET_KEY") ?: dotenv["FYERS_SECRET_KEY"] ?: ""
    val redirectUri = System.getenv("FYERS_REDIRECT_URI") ?: dotenv["FYERS_REDIRECT_URI"] ?: "https://trade.fyers.in/api-login/redirect-uri/index.html"

    if (appId.isEmpty() || secretKey.isEmpty()) {
        println("⚠️ FYERS_APP_ID or FYERS_SECRET_KEY not set in environment or .env file.")
        return
    }

    val cachedToken = viewModel.getCachedToken()
    val token = if (cachedToken != null) {
        println("\n[Cache] Found valid cached Access Token!")
        cachedToken
    } else {
        viewModel.prepareLoginUrl(appId, redirectUri)
        println("\n[1] Open this URL in your browser to authorize Fyers:")
        println(viewModel.authState.value.loginUrl)

        print("\nPaste either the full redirect URL or the 'auth_code': ")
        val input = scanner.nextLine().trim()
        val authCode = AuthUtils.extractAuthCode(input)

        println("\n[2] Exchanging auth code for access token...")
        runBlocking {
            viewModel.authenticateWithAuthCode(appId, secretKey, authCode)
        }
        viewModel.authState.value.accessToken ?: run {
            println("❌ Failed to obtain access token.")
            return
        }
    }

    runBlocking {
        runIronFlyBacktest(viewModel, appId, token, "2026-10-01", "2026-10-31")
    }
}

suspend fun runIronFlyBacktest(
    viewModel: AuthViewModel,
    appId: String,
    token: String,
    startDateStr: String,
    endDateStr: String
) {
    println("\n==============================================================")
    println("STARTING WEEKLY EXPIRY IRON FLY BACKTEST ($startDateStr to $endDateStr) [STRICT 5s DATA]")
    println("==============================================================\n")

    val startDate = LocalDate.parse(startDateStr)
    val endDate = LocalDate.parse(endDateStr)
    var currDate = startDate

    val completedTrades = mutableListOf<IronFlyBacktestResult>()
    var cumulativePts = 0.0
    var cumulativeRs = 0.0
    var tradeCounter = 0

    while (!currDate.isAfter(endDate)) {
        if (currDate.dayOfWeek == DayOfWeek.SATURDAY || currDate.dayOfWeek == DayOfWeek.SUNDAY || currDate.dayOfWeek == DayOfWeek.FRIDAY) {
            println("[$currDate] Weekend or Friday. No trade.")
            currDate = currDate.plusDays(1)
            continue
        }

        if (IronFlyStrategyConfig.isHighImpactDay(currDate)) {
            println("[$currDate] High impact day. Skipping.")
            currDate = currDate.plusDays(1)
            continue
        }

        val indexSymbol = IronFlyStrategyConfig.getEligibleIndexForDate(viewModel, appId, token, currDate, useCache = false)
        if (indexSymbol == null) {
            println("[$currDate] No eligible index to trade today.")
            currDate = currDate.plusDays(1)
            continue
        }

        val lotSize = if (indexSymbol.contains("SENSEX", ignoreCase = true)) 20.0 else 65.0

        println("--------------------------------------------------")
        println("Evaluating date: $currDate for index: $indexSymbol (Lot Size: $lotSize)")

        try {
            val dateStr = currDate.toString()
            val spotHistory = viewModel.fetchHistoricalData(appId, token, indexSymbol, "5S", dateStr, dateStr)
            if (spotHistory == null || !spotHistory.has("candles") || spotHistory.optJSONArray("candles")?.length() == 0) {
                println("  • Failed to fetch strict 5s index candles for $dateStr")
                currDate = currDate.plusDays(1)
                continue
            }

            val spotCandles = parseFyersCandles(spotHistory)
            val searchSpotCandles = spotCandles.filter { it.timestamp.substring(11, 16) >= "11:00" && it.timestamp.substring(11, 16) <= "11:30" }

            if (searchSpotCandles.isEmpty()) {
                println("  • No 5s spot candles found in 11:00 - 11:30 window for $dateStr")
                currDate = currDate.plusDays(1)
                continue
            }

            val isToday = currDate == LocalDate.now()
            val strikeInterval = if (indexSymbol.contains("SENSEX")) 100 else 50
            val hedgeOffset = if (indexSymbol.contains("SENSEX")) 400 else 200

            val firstSpot = searchSpotCandles.first().close
            val atmStrike = OptionUtils.calculateAtmStrike(firstSpot, strikeInterval)

            val expiryDateStr = dateStr
            println("📅 [Expiry Info] Index: $indexSymbol | Trade Date: $dateStr | ATM Strike: $atmStrike")

            val cSym = if (isToday) {
                OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, atmStrike.toDouble(), "CE", expiryIndex = 0)
            } else {
                OptionUtils.resolveAndFetchExpiredDerivativeCandles(viewModel, appId, token, indexSymbol, atmStrike.toDouble(), "CE", dateStr, expiryIndex = 0).symbol
            }
            val pSym = if (isToday) {
                OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, atmStrike.toDouble(), "PE", expiryIndex = 0)
            } else {
                OptionUtils.resolveAndFetchExpiredDerivativeCandles(viewModel, appId, token, indexSymbol, atmStrike.toDouble(), "PE", dateStr, expiryIndex = 0).symbol
            }
            val bCeSym = if (isToday) {
                OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, (atmStrike + hedgeOffset).toDouble(), "CE", expiryIndex = 0)
            } else {
                OptionUtils.resolveAndFetchExpiredDerivativeCandles(viewModel, appId, token, indexSymbol, (atmStrike + hedgeOffset).toDouble(), "CE", dateStr, expiryIndex = 0).symbol
            }
            val bPeSym = if (isToday) {
                OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, (atmStrike - hedgeOffset).toDouble(), "PE", expiryIndex = 0)
            } else {
                OptionUtils.resolveAndFetchExpiredDerivativeCandles(viewModel, appId, token, indexSymbol, (atmStrike - hedgeOffset).toDouble(), "PE", dateStr, expiryIndex = 0).symbol
            }

            println("  • [INFO] Fetching 5S historical candles for 4 legs...")
            val results = withContext(Dispatchers.IO) {
                val c = async {
                    if (isToday) viewModel.fetchHistoricalData(appId, token, cSym, "5S", dateStr, dateStr)
                    else viewModel.fetchHistoryFNOExpired(appId, token, cSym, dateStr, dateStr, "5S")
                }
                val p = async {
                    if (isToday) viewModel.fetchHistoricalData(appId, token, pSym, "5S", dateStr, dateStr)
                    else viewModel.fetchHistoryFNOExpired(appId, token, pSym, dateStr, dateStr, "5S")
                }
                val bc = async {
                    if (isToday) viewModel.fetchHistoricalData(appId, token, bCeSym, "5S", dateStr, dateStr)
                    else viewModel.fetchHistoryFNOExpired(appId, token, bCeSym, dateStr, dateStr, "5S")
                }
                val bp = async {
                    if (isToday) viewModel.fetchHistoricalData(appId, token, bPeSym, "5S", dateStr, dateStr)
                    else viewModel.fetchHistoryFNOExpired(appId, token, bPeSym, dateStr, dateStr, "5S")
                }
                listOf(c.await(), p.await(), bc.await(), bp.await())
            }

            val ceCandlesFull = results[0]?.let { parseFyersCandles(it) } ?: emptyList()
            val peCandlesFull = results[1]?.let { parseFyersCandles(it) } ?: emptyList()
            val bCeCandlesFull = results[2]?.let { parseFyersCandles(it) } ?: emptyList()
            val bPeCandlesFull = results[3]?.let { parseFyersCandles(it) } ?: emptyList()

            var soldCe: IronFlyLeg? = null
            var buyCe: IronFlyLeg? = null
            var soldPe: IronFlyLeg? = null
            var buyPe: IronFlyLeg? = null
            var entryTimestamp = ""

            val entrySearchTimes = searchSpotCandles.map { it.timestamp }
            for (t in entrySearchTimes) {
                val ceP = ceCandlesFull.firstOrNull { it.timestamp == t }?.close ?: continue
                val peP = peCandlesFull.firstOrNull { it.timestamp == t }?.close ?: continue
                val gap = IronFlyStrategyConfig.calculatePremiumGap(ceP, peP)

                if (gap <= 15.0) {
                    val buyCeP = bCeCandlesFull.firstOrNull { it.timestamp == t }?.close ?: 10.0
                    val buyPeP = bPeCandlesFull.firstOrNull { it.timestamp == t }?.close ?: 10.0

                    soldCe = IronFlyLeg(cSym, "SELL_CE", atmStrike.toDouble(), ceP, ceP * 1.4)
                    buyCe = IronFlyLeg(bCeSym, "BUY_CE", (atmStrike + hedgeOffset).toDouble(), buyCeP, 0.0)
                    soldPe = IronFlyLeg(pSym, "SELL_PE", atmStrike.toDouble(), peP, peP * 1.4)
                    buyPe = IronFlyLeg(bPeSym, "BUY_PE", (atmStrike - hedgeOffset).toDouble(), buyPeP, 0.0)
                    entryTimestamp = t

                    println("\n--------------------------------------------------------------------------------------------------------")
                    println("🚀 ORDER PLACED SUMMARY (Entry Time: $entryTimestamp)")
                    println("--------------------------------------------------------------------------------------------------------")
                    println("  1. [ORDER - SELL]     ${soldCe.symbol} @ ${String.format("%.2f", soldCe.entryPrice)} | Initial SL: ${String.format("%.2f", soldCe.currentSl)}")
                    println("  2. [ORDER - BUY HEDGE] ${buyCe.symbol} @ ${String.format("%.2f", buyCe.entryPrice)}")
                    println("  3. [ORDER - SELL]     ${soldPe.symbol} @ ${String.format("%.2f", soldPe.entryPrice)} | Initial SL: ${String.format("%.2f", soldPe.currentSl)}")
                    println("  4. [ORDER - BUY HEDGE] ${buyPe.symbol} @ ${String.format("%.2f", buyPe.entryPrice)}")
                    println("--------------------------------------------------------------------------------------------------------")
                    break
                }
            }

            if (soldCe == null || soldPe == null || buyCe == null || buyPe == null) {
                println("  • No valid entry pair (gap <= 15%) found at ATM by 11:30. Skipping day.")
                currDate = currDate.plusDays(1)
                continue
            }

            val allTimes = ceCandlesFull.map { it.timestamp }.filter { it >= entryTimestamp && it.substring(11, 16) <= "14:50" }
            var exitTime = "$dateStr 14:50:00"
            var exitReason = "TIME_EXIT"
            var finalNetPts = 0.0

            println("\n--------------------------------------------------------------------------------------------------------")
            println("MONITORING (From Entry $entryTimestamp to 14:50):")
            println("--------------------------------------------------------------------------------------------------------")

            for (t in allTimes) {
                val ceLtp = ceCandlesFull.firstOrNull { it.timestamp == t }?.close ?: soldCe.entryPrice
                val buyCeLtp = bCeCandlesFull.firstOrNull { it.timestamp == t }?.close ?: buyCe.entryPrice
                val peLtp = peCandlesFull.firstOrNull { it.timestamp == t }?.close ?: soldPe.entryPrice
                val buyPeLtp = bPeCandlesFull.firstOrNull { it.timestamp == t }?.close ?: buyPe.entryPrice

                if (!soldPe.isClosed && peLtp >= soldPe.currentSl) {
                    soldPe.isClosed = true
                    buyPe.isClosed = true
                    soldPe.exitPrice = peLtp
                    buyPe.exitPrice = buyPeLtp
                    soldCe.currentSl = soldCe.entryPrice
                    println("  🛑 [SL HIT] Sold PE hit SL at $t (${String.format("%.2f", peLtp)} >= ${String.format("%.2f", soldPe.currentSl)}). CE SL moved to cost.")
                }

                if (!soldCe.isClosed && ceLtp >= soldCe.currentSl) {
                    soldCe.isClosed = true
                    buyCe.isClosed = true
                    soldCe.exitPrice = ceLtp
                    buyCe.exitPrice = buyCeLtp
                    soldPe.currentSl = soldPe.entryPrice
                    println("  🛑 [SL HIT] Sold CE hit SL at $t (${String.format("%.2f", ceLtp)} >= ${String.format("%.2f", soldCe.currentSl)}). PE SL moved to cost.")
                }

                val cePnl = if (soldCe.isClosed) (soldCe.entryPrice - soldCe.exitPrice) else (soldCe.entryPrice - ceLtp)
                val buyCePnl = if (buyCe.isClosed) (buyCe.exitPrice - buyCe.entryPrice) else (buyCeLtp - buyCe.entryPrice)
                val pePnl = if (soldPe.isClosed) (soldPe.entryPrice - soldPe.exitPrice) else (soldPe.entryPrice - peLtp)
                val buyPePnl = if (buyPe.isClosed) (buyPe.exitPrice - buyPe.entryPrice) else (buyPeLtp - buyPe.entryPrice)

                val mtmRupees = (cePnl + buyCePnl + pePnl + buyPePnl) * lotSize
                val targetProfit = 1200.0

                if (mtmRupees >= targetProfit) {
                    exitTime = t
                    exitReason = "PROFIT_TARGET_1200"
                    soldCe.exitPrice = ceLtp
                    buyCe.exitPrice = buyCeLtp
                    soldPe.exitPrice = peLtp
                    buyPe.exitPrice = buyPeLtp
                    soldCe.isClosed = true
                    soldPe.isClosed = true
                    println("  💰 [PROFIT TARGET HIT] MTM Profit: ₹${String.format("%.2f", mtmRupees)} >= ₹1,200 target at $t. Exiting trade!")
                    break
                }

                if (soldCe.isClosed && soldPe.isClosed) {
                    exitTime = t
                    exitReason = "BOTH_SL_HIT"
                    break
                }

                if (t.substring(11, 16) >= "14:50") {
                    exitTime = t
                    exitReason = "TIME_EXIT_14:50"
                    soldCe.exitPrice = ceLtp
                    buyCe.exitPrice = buyCeLtp
                    soldPe.exitPrice = peLtp
                    buyPe.exitPrice = buyPeLtp
                    break
                }
            }

            val finalCeLtp = ceCandlesFull.firstOrNull { it.timestamp == exitTime }?.close ?: soldCe.entryPrice
            val finalBuyCeLtp = bCeCandlesFull.firstOrNull { it.timestamp == exitTime }?.close ?: buyCe.entryPrice
            val finalPeLtp = peCandlesFull.firstOrNull { it.timestamp == exitTime }?.close ?: soldPe.entryPrice
            val finalBuyPeLtp = bPeCandlesFull.firstOrNull { it.timestamp == exitTime }?.close ?: buyPe.entryPrice

            if (!soldCe.isClosed) soldCe.exitPrice = finalCeLtp
            if (!buyCe.isClosed) buyCe.exitPrice = finalBuyCeLtp
            if (!soldPe.isClosed) soldPe.exitPrice = finalPeLtp
            if (!buyPe.isClosed) buyPe.exitPrice = finalBuyPeLtp

            val finalCePnl = soldCe.entryPrice - soldCe.exitPrice
            val finalBuyCePnl = buyCe.exitPrice - buyCe.entryPrice
            val finalPePnl = soldPe.entryPrice - soldPe.exitPrice
            val finalBuyPePnl = buyPe.exitPrice - buyPe.entryPrice

            finalNetPts = finalCePnl + finalBuyCePnl + finalPePnl + finalBuyPePnl
            val finalNetRs = finalNetPts * lotSize

            tradeCounter++
            cumulativePts += finalNetPts
            cumulativeRs += finalNetRs

            completedTrades.add(
                IronFlyBacktestResult(
                    tradeNo = tradeCounter,
                    date = dateStr,
                    indexSymbol = indexSymbol,
                    expiryDate = expiryDateStr,
                    soldStrike = soldCe.strike,
                    entryTime = entryTimestamp,
                    exitTime = exitTime,
                    exitReason = exitReason,
                    netPnlPoints = finalNetPts,
                    netPnlRupees = finalNetRs,
                    cumulativePnlPoints = cumulativePts,
                    cumulativePnlRupees = cumulativeRs
                )
            )

            println("  • Trade Result: PnL = ${String.format("%.2f", finalNetPts)} pts (₹${String.format("%,.2f", finalNetRs)}) | Reason: $exitReason")
            println("✅ [COMPLETED] Backtest finished for date $dateStr. Total trades recorded: ${completedTrades.size}\n")

        } catch (e: Exception) {
            println("  • Error processing date $currDate: ${e.message}")
        }

        currDate = currDate.plusDays(1)
    }

    println("\n========================================================================================================================")
    println("WEEKLY EXPIRY IRON FLY STRATEGY BACKTEST REPORT (Strict 5s Data)")
    println("========================================================================================================================")
    if (completedTrades.isEmpty()) {
        println("⚠️ No trades executed.")
    } else {
        println("| # | Date | Expiry | Index | Strike | Entry | Exit Time | Exit Reason | P&L (Pts) | P&L (₹) | Cum. P&L (Pts) | Cum. P&L (₹) |")
        for (r in completedTrades) {
            println("| ${r.tradeNo} | ${r.date} | ${r.expiryDate} | ${r.indexSymbol} | ${r.soldStrike} | ${r.entryTime} | ${r.exitTime} | ${r.exitReason} | ${String.format("%.2f", r.netPnlPoints)} | ${String.format("%,.2f", r.netPnlRupees)} | ${String.format("%.2f", r.cumulativePnlPoints)} | ${String.format("%,.2f", r.cumulativePnlRupees)} |")
        }
    }
    println("========================================================================================================================")
}
