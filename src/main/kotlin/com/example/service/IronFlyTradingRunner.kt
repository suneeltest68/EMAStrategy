package com.example.service

import com.example.strategy.*
import com.example.viewmodel.AuthViewModel
import com.example.util.OptionUtils
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.min

class IronFlyTradingRunner(
    private val viewModel: AuthViewModel,
    private val appId: String,
    private val token: String,
    private val expiryIndex: Int = 1
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startLiveSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING LIVE WEEKLY-EXPIRY IRON FLY (OPTION SELLING) SESSION")
            println("==============================================================\n")

            TelegramNotifier.sendAlert("🚀 Live Weekly-Expiry Iron Fly Strategy Session Started.")

            val istZone = ZoneId.of("Asia/Kolkata")

            while (isActive) {
                val now = ZonedDateTime.now(istZone)
                val today = now.toLocalDate()
                val currentTime = now.toLocalTime()

                // 1. Pre-trade filter: High impact days
                if (IronFlyStrategyConfig.isHighImpactDay(today)) {
                    println("[IronFly] Today ($today) is a high-impact day. No trade.")
                    TelegramNotifier.sendAlert("⚠️ [IronFly] Today ($today) is a high-impact event day. Skipping trades.")
                    delay(3600_000L * 4)
                    continue
                }

                // Friday check
                if (today.dayOfWeek == DayOfWeek.FRIDAY) {
                    println("[IronFly] Friday: No trade.")
                    delay(3600_000L * 4)
                    continue
                }

                val marketOpen = LocalTime.of(9, 15)
                val entryWindowStart = LocalTime.of(11, 0)
                val entryWindowEnd = LocalTime.of(11, 30)
                val squareOffTime = LocalTime.of(14, 50)
                val marketClose = LocalTime.of(15, 30)

                if (currentTime.isBefore(entryWindowStart)) {
                    val waitMillis = Duration.between(LocalTime.now(istZone), entryWindowStart).toMillis()
                    println("[IronFly] Waiting for entry window (11:00 AM)...")
                    if (waitMillis > 0) delay(min(waitMillis, 60_000L))
                    continue
                }

                if (currentTime.isAfter(squareOffTime)) {
                    println("[IronFly] Past square-off time (14:50). Waiting for next day.")
                    delay(3600_000L)
                    continue
                }

                // Determine eligible index dynamically based on fetched expiry weekdays
                val selectedIndex = IronFlyStrategyConfig.getEligibleIndexForDate(viewModel, appId, token, today)
                if (selectedIndex == null) {
                    println("[IronFly] No eligible index to trade today ($today).")
                    delay(3600_000L)
                    continue
                }

                println("[IronFly] Selected Index for today: $selectedIndex")

                // Fetch spot price
                val spotSymbol = if (selectedIndex.contains("SENSEX")) "BSE:SENSEX" else "NSE:NIFTY50-INDEX"
                val spotQuotes = viewModel.fetchStockQuotes(appId, token, spotSymbol)
                val spotPrice = spotQuotes?.let { OptionUtils.extractLtpFromQuotes(it, spotSymbol) } ?: 0.0

                if (spotPrice <= 0.0) {
                    println("[IronFly] Failed to fetch valid spot price for $spotSymbol. Retrying in 10s...")
                    delay(10_000L)
                    continue
                }

                println("[IronFly] Spot Price of $spotSymbol: $spotPrice")

                // Entry Search Window (11:00 to 11:30)
                var position: IronFlyPosition? = null
                val startTime = LocalTime.now(istZone)

                while (LocalTime.now(istZone).isBefore(entryWindowEnd) && position == null && isActive) {
                    try {
                        val strikeInterval = if (selectedIndex.contains("SENSEX")) 100 else 50
                        val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)

                        // Fetch option chain to find matching CE and PE premium
                        val optionChain = viewModel.fetchOptionChain(appId, token, selectedIndex, 15)
                        if (optionChain != null) {
                            // Find strikes around ATM to find nearly equal premium CE and PE
                            val offsetRange = -3..3
                            var bestPair: Triple<Int, Double, Double>? = null
                            var bestGap = 999.0

                            for (offset in offsetRange) {
                                val strike = atmStrike + (offset * strikeInterval)
                                val ceSymbol = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, selectedIndex, strike.toDouble(), "CE", expiryIndex)
                                val peSymbol = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, selectedIndex, strike.toDouble(), "PE", expiryIndex)

                                val quotes = viewModel.fetchStockQuotes(appId, token, "$ceSymbol,$peSymbol")
                                val ceLtp = quotes?.let { OptionUtils.extractLtpFromQuotes(it, ceSymbol) } ?: 0.0
                                val peLtp = quotes?.let { OptionUtils.extractLtpFromQuotes(it, peSymbol) } ?: 0.0

                                if (ceLtp > 0 && peLtp > 0) {
                                    val gap = IronFlyStrategyConfig.calculatePremiumGap(ceLtp, peLtp)
                                    if (gap < bestGap) {
                                        bestGap = gap
                                        bestPair = Triple(strike, ceLtp, peLtp)
                                    }
                                }
                            }

                            if (bestPair != null && bestGap <= 15.0) {
                                val (soldStrike, cePrice, pePrice) = bestPair
                                val hedgeOffset = if (selectedIndex.contains("SENSEX")) 400 else 200

                                val soldCeSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, selectedIndex, soldStrike.toDouble(), "CE", expiryIndex)
                                val soldPeSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, selectedIndex, soldStrike.toDouble(), "PE", expiryIndex)
                                val buyCeSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, selectedIndex, (soldStrike + hedgeOffset).toDouble(), "CE", expiryIndex)
                                val buyPeSym = OptionUtils.resolveWeeklyOptionSymbol(viewModel, appId, token, selectedIndex, (soldStrike - hedgeOffset).toDouble(), "PE", expiryIndex)

                                val todayStr = today.toString()
                                val dHistory = viewModel.fetchHistoricalData(appId, token, soldCeSym, "5S", todayStr, todayStr)
                                val soldCeLtp = if (dHistory != null) {
                                    parseFyersCandles(dHistory).lastOrNull()?.close ?: cePrice
                                } else {
                                    val quotes = viewModel.fetchStockQuotes(appId, token, soldCeSym)
                                    quotes?.let { OptionUtils.extractLtpFromQuotes(it, soldCeSym) } ?: cePrice
                                }

                                val buyCeHistory = viewModel.fetchHistoricalData(appId, token, buyCeSym, "5S", todayStr, todayStr)
                                val buyCeLtp = if (buyCeHistory != null) {
                                    parseFyersCandles(buyCeHistory).lastOrNull()?.close ?: 10.0
                                } else {
                                    val quotes = viewModel.fetchStockQuotes(appId, token, buyCeSym)
                                    quotes?.let { OptionUtils.extractLtpFromQuotes(it, buyCeSym) } ?: 10.0
                                }

                                val soldPeHistory = viewModel.fetchHistoricalData(appId, token, soldPeSym, "5S", todayStr, todayStr)
                                val soldPeLtp = if (soldPeHistory != null) {
                                    parseFyersCandles(soldPeHistory).lastOrNull()?.close ?: pePrice
                                } else {
                                    val quotes = viewModel.fetchStockQuotes(appId, token, soldPeSym)
                                    quotes?.let { OptionUtils.extractLtpFromQuotes(it, soldPeSym) } ?: pePrice
                                }

                                val buyPeHistory = viewModel.fetchHistoricalData(appId, token, buyPeSym, "5S", todayStr, todayStr)
                                val buyPeLtp = if (buyPeHistory != null) {
                                    parseFyersCandles(buyPeHistory).lastOrNull()?.close ?: 10.0
                                } else {
                                    val quotes = viewModel.fetchStockQuotes(appId, token, buyPeSym)
                                    quotes?.let { OptionUtils.extractLtpFromQuotes(it, buyPeSym) } ?: 10.0
                                }

                                val lotSize = if (selectedIndex.contains("SENSEX", ignoreCase = true)) 20.0 else 65.0
                                val deployedCapital = (soldCeLtp + soldPeLtp + buyCeLtp + buyPeLtp) * lotSize // Approximate margin/capital

                                position = IronFlyPosition(
                                    indexSymbol = selectedIndex,
                                    tradeDate = today.toString(),
                                    soldCe = IronFlyLeg(soldCeSym, "SELL_CE", soldStrike.toDouble(), soldCeLtp, soldCeLtp * 1.4),
                                    buyCeHedge = IronFlyLeg(buyCeSym, "BUY_CE", (soldStrike + hedgeOffset).toDouble(), buyCeLtp, 0.0),
                                    soldPe = IronFlyLeg(soldPeSym, "SELL_PE", soldStrike.toDouble(), soldPeLtp, soldPeLtp * 1.4),
                                    buyPeHedge = IronFlyLeg(buyPeSym, "BUY_PE", (soldStrike - hedgeOffset).toDouble(), buyPeLtp, 0.0),
                                    deployedCapital = deployedCapital
                                )

                                val entryMsg = "🎯 [IronFly ENTRY] Sold CE: $soldCeSym @ $soldCeLtp | Sold PE: $soldPeSym @ $soldPeLtp | Premium Gap: ${String.format("%.2f", bestGap)}% | Hedges: $buyCeSym, $buyPeSym"
                                println(entryMsg)
                                TelegramNotifier.sendAlert(entryMsg)
                                break
                            }
                        }
                    } catch (e: Exception) {
                        println("[IronFly Entry Error]: ${e.message}")
                    }
                    delay(10_000L)
                }

                if (position == null) {
                    println("[IronFly] No valid entry pair found by 11:30. Skipping today's trade.")
                    TelegramNotifier.sendAlert("ℹ️ [IronFly] No valid entry pair (gap <= 15%) found by 11:30. Skipping day.")
                    delay(3600_000L * 4)
                    continue
                }

                // 4. Management & Exits Loop
                val activePos = position!!
                while (isActive) {
                    val currentT = LocalTime.now(istZone)
                    if (currentT.isAfter(squareOffTime) || currentT == squareOffTime) {
                        val exitMsg = "🏁 [IronFly TIME EXIT] Squaring off all open legs at 14:50."
                        println(exitMsg)
                        TelegramNotifier.sendAlert(exitMsg)
                        break
                    }

                    // Fetch live quotes for all legs
                    val symbolsToFetch = listOf(
                        activePos.soldCe.symbol, activePos.buyCeHedge.symbol,
                        activePos.soldPe.symbol, activePos.buyPeHedge.symbol
                    ).joinToString(",")

                    val quotes = viewModel.fetchStockQuotes(appId, token, symbolsToFetch)
                    if (quotes != null) {
                        val soldCeLtp = OptionUtils.extractLtpFromQuotes(quotes, activePos.soldCe.symbol) ?: activePos.soldCe.entryPrice
                        val buyCeLtp = OptionUtils.extractLtpFromQuotes(quotes, activePos.buyCeHedge.symbol) ?: activePos.buyCeHedge.entryPrice
                        val soldPeLtp = OptionUtils.extractLtpFromQuotes(quotes, activePos.soldPe.symbol) ?: activePos.soldPe.entryPrice
                        val buyPeLtp = OptionUtils.extractLtpFromQuotes(quotes, activePos.buyPeHedge.symbol) ?: activePos.buyPeHedge.entryPrice

                        // Check Stop-Loss on sold legs (SL = entry * 1.4)
                        if (!activePos.soldPe.isClosed && soldPeLtp >= activePos.soldPe.currentSl) {
                            activePos.soldPe.isClosed = true
                            activePos.buyPeHedge.isClosed = true
                            // Move sold CE's SL to entry price (cost)
                            activePos.soldCe.currentSl = activePos.soldCe.entryPrice
                            val slMsg = "🛑 [IronFly SL HIT] Sold PE hit SL ($soldPeLtp >= ${activePos.soldPe.currentSl}). Closed PE legs & moved CE SL to cost."
                            println(slMsg)
                            TelegramNotifier.sendAlert(slMsg)
                        }

                        if (!activePos.soldCe.isClosed && soldCeLtp >= activePos.soldCe.currentSl) {
                            activePos.soldCe.isClosed = true
                            activePos.buyCeHedge.isClosed = true
                            // Move sold PE's SL to entry price (cost)
                            activePos.soldPe.currentSl = activePos.soldPe.entryPrice
                            val slMsg = "🛑 [IronFly SL HIT] Sold CE hit SL ($soldCeLtp >= ${activePos.soldCe.currentSl}). Closed CE legs & moved PE SL to cost."
                            println(slMsg)
                            TelegramNotifier.sendAlert(slMsg)
                        }

                        // Check Profit Target: Combined MTM profit >= 1% of deployed capital
                        val cePnl = if (activePos.soldCe.isClosed) 0.0 else (activePos.soldCe.entryPrice - soldCeLtp)
                        val buyCePnl = if (activePos.buyCeHedge.isClosed) 0.0 else (buyCeLtp - activePos.buyCeHedge.entryPrice)
                        val pePnl = if (activePos.soldPe.isClosed) 0.0 else (activePos.soldPe.entryPrice - soldPeLtp)
                        val buyPePnl = if (activePos.buyPeHedge.isClosed) 0.0 else (buyPeLtp - activePos.buyPeHedge.entryPrice)

                        val lotSize = if (activePos.indexSymbol.contains("SENSEX", ignoreCase = true)) 20.0 else 65.0
                        val totalMtm = (cePnl + buyCePnl + pePnl + buyPePnl) * lotSize // Lot multiplier
                        val targetProfit = activePos.deployedCapital * 0.01

                        if (totalMtm >= targetProfit) {
                            val tpMsg = "💰 [IronFly PROFIT TARGET HIT] MTM Profit: ${String.format("%.2f", totalMtm)} >= Target (${String.format("%.2f", targetProfit)}). Exiting all legs!"
                            println(tpMsg)
                            TelegramNotifier.sendAlert(tpMsg)
                            break
                        }

                        // If all sold legs are closed, exit
                        if (activePos.soldCe.isClosed && activePos.soldPe.isClosed) {
                            val allClosedMsg = "🏁 [IronFly EXIT] Both sold legs closed. Exiting strategy session for today."
                            println(allClosedMsg)
                            TelegramNotifier.sendAlert(allClosedMsg)
                            break
                        }
                    }

                    delay(5_000L)
                }

                delay(3600_000L)
            }
        }
    }
}
