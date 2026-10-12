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
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.DayOfWeek
import java.util.Scanner
import kotlin.math.abs

data class IronFlyBacktestResult(
    val tradeNo: Int,
    val date: String,
    val indexSymbol: String,
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

    println("=== Weekly-Expiry Iron Fly Strategy Backtest Tool ===")
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
        val todayStr = LocalDate.now().toString()
        runIronFlyBacktest(viewModel, appId, token, todayStr, todayStr)
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
    println("STARTING WEEKLY EXPIRY IRON FLY BACKTEST FOR TODAY ($startDateStr) [5-SEC RESOLUTION]")
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

        val indexSymbol = IronFlyStrategyConfig.getEligibleIndexForDate(viewModel, appId, token, currDate)
        if (indexSymbol == null) {
            println("[$currDate] No eligible index to trade today.")
            currDate = currDate.plusDays(1)
            continue
        }

        val lotSize = if (indexSymbol.contains("SENSEX", ignoreCase = true)) 20.0 else 65.0

        println("--------------------------------------------------")
        println("Evaluating date: $currDate for index: $indexSymbol")

        try {
            val dateStr = currDate.toString()
            val spotHistory = viewModel.fetchHistoricalData(appId, token, indexSymbol, "5S", dateStr, dateStr)
            if (spotHistory == null || !spotHistory.has("candles")) {
                println("  • Failed to fetch index 5S candles for $dateStr (Market might be closed or not started yet)")
                currDate = currDate.plusDays(1)
                continue
            }

            val spotCandles = parseFyersCandles(spotHistory)
            val searchSpotCandles = spotCandles.filter { it.timestamp.substring(11, 16) >= "11:00" && it.timestamp.substring(11, 16) <= "11:30" }

            if (searchSpotCandles.isEmpty()) {
                println("  • No spot candles found in 11:00 - 11:30 window for $dateStr")
                currDate = currDate.plusDays(1)
                continue
            }

            val isToday = currDate == LocalDate.now()
            val initialSpot = searchSpotCandles.first().close
            val strikeInterval = if (indexSymbol.contains("SENSEX")) 100 else 50
            val atmStrike = OptionUtils.calculateAtmStrike(initialSpot, strikeInterval)
            val hedgeOffset = if (indexSymbol.contains("SENSEX")) 400 else 200

            // Fetch 5S candles for ATM CE, PE and Hedges across the day
            val (ceSymbol, ceCandles) = if (isToday) {
                val sym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, initialSpot.toDouble(), "CE", expiryIndex = 0)
                val hist = viewModel.fetchHistoricalData(appId, token, sym, "5S", dateStr, dateStr)
                val candles = if (hist != null) parseFyersCandles(hist) else emptyList()
                Pair(sym, candles)
            } else {
                val res = OptionUtils.resolveAndFetchExpiredDerivativeCandles(viewModel, appId, token, indexSymbol, initialSpot, "CE", dateStr, expiryIndex = 0)
                Pair(res.symbol, res.candles)
            }

            val (peSymbol, peCandles) = if (isToday) {
                val sym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, initialSpot.toDouble(), "PE", expiryIndex = 0)
                val hist = viewModel.fetchHistoricalData(appId, token, sym, "5S", dateStr, dateStr)
                val candles = if (hist != null) parseFyersCandles(hist) else emptyList()
                Pair(sym, candles)
            } else {
                val res = OptionUtils.resolveAndFetchExpiredDerivativeCandles(viewModel, appId, token, indexSymbol, initialSpot, "PE", dateStr, expiryIndex = 0)
                Pair(res.symbol, res.candles)
            }

            val (buyCeSymbol, buyCeCandles) = if (isToday) {
                val sym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, (initialSpot + hedgeOffset).toDouble(), "CE", expiryIndex = 0)
                val hist = viewModel.fetchHistoricalData(appId, token, sym, "5S", dateStr, dateStr)
                val candles = if (hist != null) parseFyersCandles(hist) else emptyList()
                Pair(sym, candles)
            } else {
                val res = OptionUtils.resolveAndFetchExpiredDerivativeCandles(viewModel, appId, token, indexSymbol, initialSpot + hedgeOffset, "CE", dateStr, expiryIndex = 0)
                Pair(res.symbol, res.candles)
            }

            val (buyPeSymbol, buyPeCandles) = if (isToday) {
                val sym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, indexSymbol, (initialSpot - hedgeOffset).toDouble(), "PE", expiryIndex = 0)
                val hist = viewModel.fetchHistoricalData(appId, token, sym, "5S", dateStr, dateStr)
                val candles = if (hist != null) parseFyersCandles(hist) else emptyList()
                Pair(sym, candles)
            } else {
                val res = OptionUtils.resolveAndFetchExpiredDerivativeCandles(viewModel, appId, token, indexSymbol, initialSpot - hedgeOffset, "PE", dateStr, expiryIndex = 0)
                Pair(res.symbol, res.candles)
            }

            // Search through 11:00 AM to 11:30 AM for valid entry (premium gap <= 15%)
            val entrySearchCandles = ceCandles.filter { it.timestamp.substring(11, 16) >= "11:00" && it.timestamp.substring(11, 16) <= "11:30" }
            
            var soldCe: IronFlyLeg? = null
            var buyCe: IronFlyLeg? = null
            var soldPe: IronFlyLeg? = null
            var buyPe: IronFlyLeg? = null
            var entryTimestamp = ""

            println("\n--------------------------------------------------------------------------------------------------------")
            println("ENTRY WINDOW SEARCH (11:00 AM - 11:30 AM, checking every 5s):")
            println("--------------------------------------------------------------------------------------------------------")

            for (sc in entrySearchCandles) {
                val t = sc.timestamp
                val ceP = ceCandles.firstOrNull { it.timestamp == t }?.open ?: continue
                val peP = peCandles.firstOrNull { it.timestamp == t }?.open ?: continue
                val gap = IronFlyStrategyConfig.calculatePremiumGap(ceP, peP)

                println("⏱️ [$t] ATM: $atmStrike | CE: $ceP | PE: $peP | Premium Gap: ${String.format("%.2f", gap)}%")

                if (gap <= 15.0) {
                    val buyCeP = buyCeCandles.firstOrNull { it.timestamp == t }?.open ?: 10.0
                    val buyPeP = buyPeCandles.firstOrNull { it.timestamp == t }?.open ?: 10.0

                    soldCe = IronFlyLeg(ceSymbol, "SELL_CE", atmStrike.toDouble(), ceP, ceP * 1.4)
                    buyCe = IronFlyLeg(buyCeSymbol, "BUY_CE", (atmStrike + hedgeOffset).toDouble(), buyCeP, 0.0)
                    soldPe = IronFlyLeg(peSymbol, "SELL_PE", atmStrike.toDouble(), peP, peP * 1.4)
                    buyPe = IronFlyLeg(buyPeSymbol, "BUY_PE", (atmStrike - hedgeOffset).toDouble(), buyPeP, 0.0)
                    entryTimestamp = t

                    println("  🎯 [IronFly ENTERED at $t] Sold CE: ${soldCe.symbol} @ $ceP | Sold PE: ${soldPe.symbol} @ $peP | Gap: ${String.format("%.2f", gap)}%")
                    break
                }
            }

            if (soldCe == null || soldPe == null || buyCe == null || buyPe == null) {
                println("  • No valid entry pair (gap <= 15%) found by 11:30. Skipping day.")
                currDate = currDate.plusDays(1)
                continue
            }

            val deployedCapital = (soldCe.entryPrice + soldPe.entryPrice + buyCe.entryPrice + buyPe.entryPrice) * lotSize
            val targetProfit = deployedCapital * 0.01 / lotSize

            val allTimes = ceCandles.map { it.timestamp }.filter { it >= entryTimestamp && it.substring(11, 16) <= "14:50" }
            var exitTime = "$dateStr 14:50:00"
            var exitReason = "TIME_EXIT"
            var finalNetPts = 0.0

            println("\n--------------------------------------------------------------------------------------------------------")
            println("5-SECOND INTERVAL TIMESTAMP LOG (From Entry $entryTimestamp onwards):")
            println("--------------------------------------------------------------------------------------------------------")

            for (t in allTimes) {
                val spotC = spotCandles.firstOrNull { it.timestamp == t }?.close ?: initialSpot
                val ceLtp = ceCandles.firstOrNull { it.timestamp == t }?.close ?: soldCe.entryPrice
                val buyCeLtp = buyCeCandles.firstOrNull { it.timestamp == t }?.close ?: buyCe.entryPrice
                val peLtp = peCandles.firstOrNull { it.timestamp == t }?.close ?: soldPe.entryPrice
                val buyPeLtp = buyPeCandles.firstOrNull { it.timestamp == t }?.close ?: buyPe.entryPrice

                if (!soldPe.isClosed && peLtp >= soldPe.currentSl) {
                    soldPe.isClosed = true
                    buyPe.isClosed = true
                    soldPe.exitPrice = peLtp
                    buyPe.exitPrice = buyPeLtp
                    soldCe.currentSl = soldCe.entryPrice
                    println("  🛑 [SL HIT] Sold PE hit SL at $t ($peLtp >= ${soldPe.currentSl}). CE SL moved to cost.")
                }

                if (!soldCe.isClosed && ceLtp >= soldCe.currentSl) {
                    soldCe.isClosed = true
                    buyCe.isClosed = true
                    soldCe.exitPrice = ceLtp
                    buyCe.exitPrice = buyCeLtp
                    soldPe.currentSl = soldPe.entryPrice
                    println("  🛑 [SL HIT] Sold CE hit SL at $t ($ceLtp >= ${soldCe.currentSl}). PE SL moved to cost.")
                }

                val cePnl = if (soldCe.isClosed) (soldCe.entryPrice - soldCe.exitPrice) else (soldCe.entryPrice - ceLtp)
                val buyCePnl = if (buyCe.isClosed) (buyCe.exitPrice - buyCe.entryPrice) else (buyCeLtp - buyCe.entryPrice)
                val pePnl = if (soldPe.isClosed) (soldPe.entryPrice - soldPe.exitPrice) else (soldPe.entryPrice - peLtp)
                val buyPePnl = if (buyPe.isClosed) (buyPe.exitPrice - buyPe.entryPrice) else (buyPeLtp - buyPe.entryPrice)

                val mtmPts = cePnl + buyCePnl + pePnl + buyPePnl
                println("⏱️ [$t] Spot: $spotC | SoldCE: $ceLtp (SL:${soldCe.currentSl}) | SoldPE: $peLtp (SL:${soldPe.currentSl}) | MTM P&L: ${String.format("%.2f", mtmPts)} pts")

                if (mtmPts >= targetProfit) {
                    exitTime = t
                    exitReason = "PROFIT_TARGET_1%"
                    soldCe.exitPrice = ceLtp
                    buyCe.exitPrice = buyCeLtp
                    soldPe.exitPrice = peLtp
                    buyPe.exitPrice = buyPeLtp
                    println("  💰 [TARGET HIT] Profit target reached at $t (MTM: ${String.format("%.2f", mtmPts)} pts)")
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

            val finalCePnl = soldCe.entryPrice - (if (soldCe.isClosed) soldCe.exitPrice else ceCandles.last().close)
            val finalBuyCePnl = (if (buyCe.isClosed) buyCe.exitPrice else buyCeCandles.last().close) - buyCe.entryPrice
            val finalPePnl = soldPe.entryPrice - (if (soldPe.isClosed) soldPe.exitPrice else peCandles.last().close)
            val finalBuyPePnl = (if (buyPe.isClosed) buyPe.exitPrice else buyPeCandles.last().close) - buyPe.entryPrice

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
                    soldStrike = atmStrike.toDouble(),
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

        } catch (e: Exception) {
            println("  • Error processing date $currDate: ${e.message}")
        }

        currDate = currDate.plusDays(1)
    }

    println("\n========================================================================================================================")
    println("WEEKLY EXPIRY IRON FLY STRATEGY BACKTEST REPORT (Today | 1 Lot = 25 Qty)")
    println("========================================================================================================================")
    if (completedTrades.isEmpty()) {
        println("⚠️ No trades executed for today (Market might be closed, before 11:30 AM, or not a valid trade day).")
    } else {
        println("| # | Date | Index | Strike | Entry | Exit Time | Exit Reason | P&L (Pts) | P&L (₹) | Cum. P&L (Pts) | Cum. P&L (₹) |")
        println("|---|------|-------|--------|-------|-----------|-------------|-----------|---------|----------------|--------------|")
        for (t in completedTrades) {
            val sign = if (t.netPnlPoints >= 0) "[WIN +]" else "[LOSS -]"
            println("| ${t.tradeNo} | ${t.date} | ${t.indexSymbol} | ${t.soldStrike} | ${t.entryTime.substring(11, 16)} | ${t.exitTime.substring(11, 16)} | ${t.exitReason} | $sign ${String.format("%.2f", t.netPnlPoints)} pts | ₹${String.format("%,.2f", t.netPnlRupees)} | ${String.format("%.2f", t.cumulativePnlPoints)} pts | ₹${String.format("%,.2f", t.cumulativePnlRupees)} |")
        }
    }
    println("========================================================================================================================")
}
