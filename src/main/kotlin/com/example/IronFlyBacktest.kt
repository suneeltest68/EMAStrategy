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
        runIronFlyBacktest(viewModel, appId, token, "2026-10-12", "2026-10-12")
    }
}

fun expandTo5SecondCandles(candles: List<Candle>): List<Candle> {
    if (candles.isEmpty()) return emptyList()
    val expanded = mutableListOf<Candle>()
    for (i in 0 until candles.size - 1) {
        val curr = candles[i]
        val next = candles[i + 1]
        val currEpoch = curr.epoch
        val nextEpoch = next.epoch
        val timeDiff = nextEpoch - currEpoch
        if (timeDiff <= 60L && timeDiff > 0L) {
            val steps = 12 // 60s / 5s = 12 steps
            val priceStep = (next.close - curr.close) / steps.toDouble()
            for (s in 0 until steps) {
                val stepEpoch = currEpoch + (s * 5L)
                val stepPrice = curr.close + (s * priceStep)
                val zdt = java.time.Instant.ofEpochSecond(stepEpoch).atZone(java.time.ZoneId.of("Asia/Kolkata"))
                val timeStr = zdt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                expanded.add(Candle(timestamp = timeStr, open = stepPrice, high = stepPrice, low = stepPrice, close = stepPrice, epoch = stepEpoch))
            }
        } else {
            expanded.add(curr)
        }
    }
    expanded.add(candles.last())
    return expanded
}

suspend fun runIronFlyBacktest(
    viewModel: AuthViewModel,
    appId: String,
    token: String,
    startDateStr: String,
    endDateStr: String
) {
    println("\n==============================================================")
    println("STARTING WEEKLY EXPIRY IRON FLY BACKTEST FOR TODAY ($startDateStr) [ATM STRIKE ONLY]")
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
        println("Evaluating date: $currDate for index: $indexSymbol (Lot Size: $lotSize)")

        try {
            val dateStr = currDate.toString()
            val spotHistory = viewModel.fetchHistoricalData(appId, token, indexSymbol, "1", dateStr, dateStr)
            if (spotHistory == null || !spotHistory.has("candles")) {
                println("  • Failed to fetch index candles for $dateStr")
                currDate = currDate.plusDays(1)
                continue
            }

            val spotCandles = expandTo5SecondCandles(parseFyersCandles(spotHistory))
            val searchSpotCandles = spotCandles.filter { it.timestamp.substring(11, 16) >= "11:00" && it.timestamp.substring(11, 16) <= "11:30" }

            if (searchSpotCandles.isEmpty()) {
                println("  • No spot candles found in 11:00 - 11:30 window for $dateStr")
                currDate = currDate.plusDays(1)
                continue
            }

            val isToday = currDate == LocalDate.now()
            val strikeInterval = if (indexSymbol.contains("SENSEX")) 100 else 50
            val hedgeOffset = if (indexSymbol.contains("SENSEX")) 400 else 200

            val expiryDateStr = dateStr
            println("📅 [Expiry Info] Index: $indexSymbol | Trade Date: $dateStr | Weekly Expiry Date: $expiryDateStr")

            val entrySearchTimes = searchSpotCandles.map { it.timestamp }
            var soldCe: IronFlyLeg? = null
            var buyCe: IronFlyLeg? = null
            var soldPe: IronFlyLeg? = null
            var buyPe: IronFlyLeg? = null
            var entryTimestamp = ""
            var ceSymbol = ""
            var peSymbol = ""
            var buyCeSymbol = ""
            var buyPeSymbol = ""

            println("\n--------------------------------------------------------------------------------------------------------")
            println("ENTRY WINDOW SEARCH (11:00 AM - 11:30 AM, checking ATM strike only every 5s):")
            println("--------------------------------------------------------------------------------------------------------")

            outer@ for (t in entrySearchTimes) {
                val spotC = spotCandles.firstOrNull { it.timestamp == t }?.close ?: continue
                val atmStrike = OptionUtils.calculateAtmStrike(spotC, strikeInterval)

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

                val (cHist, pHist) = withContext(Dispatchers.IO) {
                    val c = async { viewModel.fetchHistoricalData(appId, token, cSym, "1", dateStr, dateStr) }
                    val p = async { viewModel.fetchHistoricalData(appId, token, pSym, "1", dateStr, dateStr) }
                    Pair(c.await(), p.await())
                }

                val cCandles = if (cHist != null) expandTo5SecondCandles(parseFyersCandles(cHist)) else emptyList()
                val pCandles = if (pHist != null) expandTo5SecondCandles(parseFyersCandles(pHist)) else emptyList()

                val ceP = cCandles.firstOrNull { it.timestamp == t }?.close ?: continue
                val peP = pCandles.firstOrNull { it.timestamp == t }?.close ?: continue
                val gap = IronFlyStrategyConfig.calculatePremiumGap(ceP, peP)

                println("⏱️ [$t] ATM Strike: $atmStrike | CE: $cSym (@ ${String.format("%.2f", ceP)}) | PE: $pSym (@ ${String.format("%.2f", peP)}) | Premium Gap: ${String.format("%.2f", gap)}%")

                if (gap <= 15.0) {
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

                    val (bCeHist, bPeHist) = withContext(Dispatchers.IO) {
                        val bc = async { viewModel.fetchHistoricalData(appId, token, bCeSym, "1", dateStr, dateStr) }
                        val bp = async { viewModel.fetchHistoricalData(appId, token, bPeSym, "1", dateStr, dateStr) }
                        Pair(bc.await(), bp.await())
                    }

                    val bCeCandles = if (bCeHist != null) expandTo5SecondCandles(parseFyersCandles(bCeHist)) else emptyList()
                    val bPeCandles = if (bPeHist != null) expandTo5SecondCandles(parseFyersCandles(bPeHist)) else emptyList()

                    val buyCeP = bCeCandles.firstOrNull { it.timestamp == t }?.close ?: 10.0
                    val buyPeP = bPeCandles.firstOrNull { it.timestamp == t }?.close ?: 10.0

                    ceSymbol = cSym
                    peSymbol = pSym
                    buyCeSymbol = bCeSym
                    buyPeSymbol = bPeSym

                    soldCe = IronFlyLeg(ceSymbol, "SELL_CE", atmStrike.toDouble(), ceP, ceP * 1.4)
                    buyCe = IronFlyLeg(buyCeSymbol, "BUY_CE", (atmStrike + hedgeOffset).toDouble(), buyCeP, 0.0)
                    soldPe = IronFlyLeg(peSymbol, "SELL_PE", atmStrike.toDouble(), peP, peP * 1.4)
                    buyPe = IronFlyLeg(buyPeSymbol, "BUY_PE", (atmStrike - hedgeOffset).toDouble(), buyPeP, 0.0)
                    entryTimestamp = t

                    println("\n--------------------------------------------------------------------------------------------------------")
                    println("🚀 ORDER PLACED SUMMARY (Entry Time: $entryTimestamp)")
                    println("--------------------------------------------------------------------------------------------------------")
                    println("  1. [ORDER - SELL]     ${soldCe.symbol} @ ${String.format("%.2f", soldCe.entryPrice)} | Initial SL: ${String.format("%.2f", soldCe.currentSl)}")
                    println("  2. [ORDER - BUY HEDGE] ${buyCe.symbol} @ ${String.format("%.2f", buyCe.entryPrice)}")
                    println("  3. [ORDER - SELL]     ${soldPe.symbol} @ ${String.format("%.2f", soldPe.entryPrice)} | Initial SL: ${String.format("%.2f", soldPe.currentSl)}")
                    println("  4. [ORDER - BUY HEDGE] ${buyPe.symbol} @ ${String.format("%.2f", buyPe.entryPrice)}")
                    println("--------------------------------------------------------------------------------------------------------")
                    break@outer
                }
            }

            if (soldCe == null || soldPe == null || buyCe == null || buyPe == null) {
                println("  • No valid entry pair (gap <= 15%) found at ATM by 11:30. Skipping day.")
                currDate = currDate.plusDays(1)
                continue
            }

            // Fetch full day candles in parallel
            val results = withContext(Dispatchers.IO) {
                val c = async { viewModel.fetchHistoricalData(appId, token, ceSymbol, "1", dateStr, dateStr) }
                val p = async { viewModel.fetchHistoricalData(appId, token, peSymbol, "1", dateStr, dateStr) }
                val bc = async { viewModel.fetchHistoricalData(appId, token, buyCeSymbol, "1", dateStr, dateStr) }
                val bp = async { viewModel.fetchHistoricalData(appId, token, buyPeSymbol, "1", dateStr, dateStr) }
                listOf(c.await(), p.await(), bc.await(), bp.await())
            }

            val ceCandlesFull = results[0]?.let { expandTo5SecondCandles(parseFyersCandles(it)) } ?: emptyList()
            val peCandlesFull = results[1]?.let { expandTo5SecondCandles(parseFyersCandles(it)) } ?: emptyList()
            val bCeCandlesFull = results[2]?.let { expandTo5SecondCandles(parseFyersCandles(it)) } ?: emptyList()
            val bPeCandlesFull = results[3]?.let { expandTo5SecondCandles(parseFyersCandles(it)) } ?: emptyList()

            val deployedCapital = (soldCe.entryPrice + soldPe.entryPrice + buyCe.entryPrice + buyPe.entryPrice) * lotSize
            val targetProfit = deployedCapital * 0.01 / lotSize

            val allTimes = ceCandlesFull.map { it.timestamp }.filter { it >= entryTimestamp && it.substring(11, 16) <= "14:50" }
            var exitTime = "$dateStr 14:50:00"
            var exitReason = "TIME_EXIT"
            var finalNetPts = 0.0

            println("\n--------------------------------------------------------------------------------------------------------")
            println("5-SECOND INTERVAL TIMESTAMP LOG (From Entry $entryTimestamp onwards):")
            println("--------------------------------------------------------------------------------------------------------")

            for (t in allTimes) {
                val spotC = spotCandles.firstOrNull { it.timestamp == t }?.close ?: 0.0
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

                val mtmPts = cePnl + buyCePnl + pePnl + buyPePnl
                println("⏱️ [$t] Spot: ${String.format("%.2f", spotC)} | SoldCE: ${String.format("%.2f", ceLtp)} (SL:${String.format("%.2f", soldCe.currentSl)}) | SoldPE: ${String.format("%.2f", peLtp)} (SL:${String.format("%.2f", soldPe.currentSl)}) | MTM P&L: ${String.format("%.2f", mtmPts)} pts")

                /*if (mtmPts >= targetProfit) {
                    exitTime = t
                    exitReason = "PROFIT_TARGET_1%"
                    soldCe.exitPrice = ceLtp
                    buyCe.exitPrice = buyCeLtp
                    soldPe.exitPrice = peLtp
                    buyPe.exitPrice = buyPeLtp
                    println("  💰 [TARGET HIT] Profit target reached at $t (MTM: ${String.format("%.2f", mtmPts)} pts >= Target ${String.format("%.2f", targetProfit)} pts)")
                    break
                }*/

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

        } catch (e: Exception) {
            println("  • Error processing date $currDate: ${e.message}")
        }

        currDate = currDate.plusDays(1)
    }

    println("\n========================================================================================================================")
    println("WEEKLY EXPIRY IRON FLY STRATEGY BACKTEST REPORT (8 Oct 2026)")
    println("========================================================================================================================")
    if (completedTrades.isEmpty()) {
        println("⚠️ No trades executed.")
    } else {
        println("| # | Date | Expiry | Index | Strike | Entry | Exit Time | Exit Reason | P&L (Pts) | P&L (₹) | Cum. P&L (Pts) | Cum. P&L (₹) |")
        println("|---|------|--------|-------|--------|-------|-----------|-------------|-----------|---------|----------------|--------------|")
        for (t in completedTrades) {
            val sign = if (t.netPnlPoints >= 0) "[WIN +]" else "[LOSS -]"
            println("| ${t.tradeNo} | ${t.date} | ${t.expiryDate} | ${t.indexSymbol} | ${t.soldStrike} | ${t.entryTime.substring(11, 16)} | ${t.exitTime.substring(11, 16)} | ${t.exitReason} | $sign ${String.format("%.2f", t.netPnlPoints)} pts | ₹${String.format("%,.2f", t.netPnlRupees)} | ${String.format("%.2f", t.cumulativePnlPoints)} pts | ₹${String.format("%,.2f", t.cumulativePnlRupees)} |")
        }
    }
    println("========================================================================================================================")
}
