package com.example.service

import com.example.strategy.*
import com.example.viewmodel.AuthViewModel
import com.example.util.OptionUtils
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

class IronFlyTradingRunner(
    private val viewModel: AuthViewModel,
    private val appId: String,
    private val token: String,
    private val expiryIndex: Int = 0
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val istZone = ZoneId.of("Asia/Kolkata")

    fun startLiveSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING LIVE WEEKLY-EXPIRY IRON FLY (STRICT WEBSOCKET ALGO)")
            println("==============================================================\n")

            TelegramNotifier.sendAlert("🚀 Live Weekly-Expiry Iron Fly Strategy Session Started (Strict Mode).")

            while (isActive) {
                try {
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

                    val entryWindowStart = LocalTime.of(11, 0)
                    val entryWindowEnd = LocalTime.of(11, 30)
                    val squareOffTime = LocalTime.of(14, 50)
                    val marketClose = LocalTime.of(15, 30)

                    if (currentTime.isBefore(entryWindowStart)) {
                        val targetEntry = LocalDateTime.of(today, entryWindowStart)
                        val millisToWait = Duration.between(LocalDateTime.now(istZone), targetEntry).toMillis()
                        if (millisToWait > 0) {
                            println("[IronFly] Waiting for entry window (13:30). Sleeping ${millisToWait / 1000}s...")
                            delay(min(millisToWait, 60_000L))
                        } else {
                            delay(1000L)
                        }
                        continue
                    }

                    if (currentTime.isAfter(squareOffTime) || currentTime.isAfter(marketClose)) {
                        println("[IronFly] Past square-off time (14:50). Waiting for next day.")
                        delay(3600_000L)
                        continue
                    }

                    // Determine eligible index dynamically based on fetched expiry weekdays (Live: no cache)
                    val selectedIndex = IronFlyStrategyConfig.getEligibleIndexForDate(viewModel, appId, token, today, useCache = false)
                    if (selectedIndex == null) {
                        println("[IronFly] No eligible index to trade today ($today).")
                        delay(3600_000L)
                        continue
                    }

                    println("[IronFly] Selected Index for today: $selectedIndex")

                    // Entry Search Window (WebSocket powered)
                    var position: IronFlyPosition? = null
                    val spotSymbol = if (selectedIndex.contains("SENSEX")) "BSE:SENSEX-INDEX" else "NSE:NIFTY50-INDEX"

                    val tickMap = ConcurrentHashMap<String, Double>()
                    val liveStreamer = FyersRealtimeStreamer(appId, token) { symbol, ltp ->
                        if (ltp > 0.0) {
                            tickMap[symbol] = ltp
                        }
                    }

                    // 1. Connect WebSocket first
                    liveStreamer.connectAndSubscribe(listOf(spotSymbol))

                    // Wait for WebSocket connection confirmation (OnOpen)
                    println("⏳ Waiting for Fyers WebSocket connection...")
                    var connAttempts = 0
                    while (!liveStreamer.isConnected && connAttempts < 30 && isActive) {
                        delay(500L)
                        connAttempts++
                    }

                    if (!liveStreamer.isConnected) {
                        throw IllegalStateException("Failed to establish Fyers WebSocket connection for streaming data.")
                    }
                    println("🟢 WebSocket confirmed connected. Proceeding to retrieve data...")

                    // 2. Only retrieve option chain & spot price once WebSocket is connected
                    val optionChain = viewModel.fetchOptionChain(appId, token, selectedIndex, 15, useCache = false)
                        ?: throw IllegalStateException("Failed to fetch option chain for $selectedIndex")
                    val expiryDateStr = OptionUtils.extractExpiryDateString(optionChain, expiryIndex)
                    println("[IronFly] Weekly Expiry Date resolved: $expiryDateStr")

                    // Wait for initial spot price tick via WebSocket or REST API fallback
                    var spotPrice = 0.0
                    var spotAttempts = 0
                    while (spotPrice <= 0.0 && LocalTime.now(istZone).isBefore(entryWindowEnd) && isActive) {
                        spotPrice = tickMap[spotSymbol] ?: 0.0
                        if (spotPrice <= 0.0) {
                            if (spotAttempts % 4 == 0) {
                                try {
                                    val spotQuotes = viewModel.fetchStockQuotes(appId, token, spotSymbol)
                                    spotPrice = extractSpotLtp(spotQuotes, spotSymbol)
                                    if (spotPrice > 0.0) {
                                        println("[IronFly] Got spot price via REST API fallback: $spotPrice")
                                        break
                                    }
                                } catch (_: Exception) {}
                            }
                            delay(500L)
                            spotAttempts++
                        }
                    }

                    if (spotPrice <= 0.0) {
                        throw IllegalStateException("Failed to get spot price for $spotSymbol")
                    }
                    println("[IronFly] Initial Spot Price of $spotSymbol: $spotPrice")

                    val strikeInterval = if (selectedIndex.contains("SENSEX")) 100 else 50
                    var currentAtmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)
                    var ceSymbol = OptionUtils.buildWeeklyOptionSymbol(selectedIndex, expiryDateStr, currentAtmStrike.toDouble(), "CE")
                    var peSymbol = OptionUtils.buildWeeklyOptionSymbol(selectedIndex, expiryDateStr, currentAtmStrike.toDouble(), "PE")

                    liveStreamer.updateSubscription(listOf(spotSymbol, ceSymbol, peSymbol))

                    while (LocalTime.now(istZone).isBefore(entryWindowEnd) && position == null && isActive) {
                        val currentSpot = tickMap[spotSymbol] ?: spotPrice
                        val newAtm = OptionUtils.calculateAtmStrike(currentSpot, strikeInterval)

                        if (newAtm != currentAtmStrike) {
                            currentAtmStrike = newAtm
                            ceSymbol = OptionUtils.buildWeeklyOptionSymbol(selectedIndex, expiryDateStr, currentAtmStrike.toDouble(), "CE")
                            peSymbol = OptionUtils.buildWeeklyOptionSymbol(selectedIndex, expiryDateStr, currentAtmStrike.toDouble(), "PE")
                            liveStreamer.updateSubscription(listOf(spotSymbol, ceSymbol, peSymbol))
                            println("[IronFly] ATM Strike shifted to $currentAtmStrike | CE: $ceSymbol | PE: $peSymbol")
                        }

                        val ceLtp = tickMap[ceSymbol] ?: 0.0
                        val peLtp = tickMap[peSymbol] ?: 0.0

                        println("[IronFly] [WebSocket] Checking ATM Strike $currentAtmStrike | CE: $ceSymbol (@ $ceLtp) | PE: $peSymbol (@ $peLtp)")

                        if (ceLtp > 0.0 && peLtp > 0.0) {
                            val gap = IronFlyStrategyConfig.calculatePremiumGap(ceLtp, peLtp)
                            println("[IronFly] ATM Premium Gap: ${String.format("%.2f", gap)}%")

                            if (gap <= 15.0) {
                                val soldStrike = currentAtmStrike
                                val hedgeOffset = if (selectedIndex.contains("SENSEX")) 400 else 200

                                val soldCeSym = ceSymbol
                                val soldPeSym = peSymbol
                                val buyCeSym = OptionUtils.buildWeeklyOptionSymbol(selectedIndex, expiryDateStr, (soldStrike + hedgeOffset).toDouble(), "CE")
                                val buyPeSym = OptionUtils.buildWeeklyOptionSymbol(selectedIndex, expiryDateStr, (soldStrike - hedgeOffset).toDouble(), "PE")

                                val legQuotes = viewModel.fetchStockQuotes(appId, token, "$soldCeSym,$buyCeSym,$soldPeSym,$buyPeSym")
                                    ?: throw IllegalStateException("Failed to fetch entry quotes for Iron Fly legs")

                                val soldCeLtp = OptionUtils.extractLtpFromQuotes(legQuotes, soldCeSym) ?: ceLtp
                                val buyCeLtp = OptionUtils.extractLtpFromQuotes(legQuotes, buyCeSym) ?: 10.0
                                val soldPeLtp = OptionUtils.extractLtpFromQuotes(legQuotes, soldPeSym) ?: peLtp
                                val buyPeLtp = OptionUtils.extractLtpFromQuotes(legQuotes, buyPeSym) ?: 10.0

                                // Dynamic Lot Sizing: 1 lot on expiry day, 2 lots one expiry before day
                                val expiryDay = IronFlyStrategyConfig.getDynamicExpiryDay(viewModel, appId, token, selectedIndex, today, useCache = false)
                                val isExpiryDay = (today.dayOfWeek == expiryDay) || expiryDateStr.startsWith(today.format(DateTimeFormatter.ofPattern("dd-MM")))
                                val lots = if (isExpiryDay) 1 else 2
                                val baseLotSize = if (selectedIndex.contains("SENSEX", ignoreCase = true)) 20.0 else 65.0
                                val lotSize = baseLotSize * lots
                                val deployedCapital = (soldCeLtp + soldPeLtp + buyCeLtp + buyPeLtp) * lotSize

                                val soldCeLeg = IronFlyLeg(soldCeSym, "SELL_CE", soldStrike.toDouble(), soldCeLtp, soldCeLtp * 1.4)
                                val buyCeLeg = IronFlyLeg(buyCeSym, "BUY_CE", (soldStrike + hedgeOffset).toDouble(), buyCeLtp, 0.0)
                                val soldPeLeg = IronFlyLeg(soldPeSym, "SELL_PE", soldStrike.toDouble(), soldPeLtp, soldPeLtp * 1.4)
                                val buyPeLeg = IronFlyLeg(buyPeSym, "BUY_PE", (soldStrike - hedgeOffset).toDouble(), buyPeLtp, 0.0)

                                position = IronFlyPosition(
                                    indexSymbol = selectedIndex,
                                    tradeDate = today.toString(),
                                    soldCe = soldCeLeg,
                                    buyCeHedge = buyCeLeg,
                                    soldPe = soldPeLeg,
                                    buyPeHedge = buyPeLeg,
                                    deployedCapital = deployedCapital
                                )

                                val instrumentName = if (selectedIndex.contains("SENSEX")) "SENSEX" else "NIFTY"
                                val entryMsg = """
                                    🚀 ENTRY · IRON FLY ($instrumentName)
                                    Direction: NEUTRAL (Iron Butterfly)
                                    Instrument: SELL ATM & BUY OTM Wings
                                      Sold CE: $soldCeSym @ ₹${String.format("%.2f", soldCeLtp)} (SL: ₹${String.format("%.2f", soldCeLeg.currentSl)})
                                      Sold PE: $soldPeSym @ ₹${String.format("%.2f", soldPeLtp)} (SL: ₹${String.format("%.2f", soldPeLeg.currentSl)})
                                      Hedges: $buyCeSym, $buyPeSym
                                    Qty: ${lotSize.toInt()} (${lots} lot(s))
                                    [PAPER] ${LocalTime.now(istZone)} IST
                                """.trimIndent()
                                println(entryMsg)
                                TelegramNotifier.sendAlert(entryMsg)
                                break
                            }
                        }
                        delay(1_000L)
                    }

                    if (position == null) {
                        val noEntryMsg = "ℹ️ [IronFly] No valid entry pair (gap <= 15%) found by $entryWindowEnd. Skipping day."
                        println(noEntryMsg)
                        TelegramNotifier.sendAlert(noEntryMsg)
                        delay(3600_000L * 4)
                        continue
                    }

                    // 4. Real-Time WebSocket Risk Monitoring & Management
                    val activePos = position!!
                    val ltpMap = tickMap
                    
                    ltpMap[activePos.soldCe.symbol] = activePos.soldCe.entryPrice
                    ltpMap[activePos.buyCeHedge.symbol] = activePos.buyCeHedge.entryPrice
                    ltpMap[activePos.soldPe.symbol] = activePos.soldPe.entryPrice
                    ltpMap[activePos.buyPeHedge.symbol] = activePos.buyPeHedge.entryPrice

                    val symbolsToStream = listOf(
                        activePos.soldCe.symbol, activePos.buyCeHedge.symbol,
                        activePos.soldPe.symbol, activePos.buyPeHedge.symbol
                    )

                    liveStreamer.updateSubscription(symbolsToStream)

                    val effectiveLotSize = activePos.deployedCapital / (activePos.soldCe.entryPrice + activePos.soldPe.entryPrice + activePos.buyCeHedge.entryPrice + activePos.buyPeHedge.entryPrice)

                    // Monitoring loop until 2:50 PM or all closed
                    while (isActive) {
                        val currentT = LocalTime.now(istZone)
                        if (currentT.isAfter(squareOffTime) || currentT == squareOffTime) {
                            val exitMsg = "🏁 [IronFly TIME EXIT] Squaring off all open legs at 14:50."
                            println(exitMsg)
                            TelegramNotifier.sendAlert(exitMsg)
                            squareOffIronFly(activePos, ltpMap, effectiveLotSize, "AUTO_SQUARE_OFF_14:50")
                            break
                        }

                        val soldCeLtp = ltpMap[activePos.soldCe.symbol] ?: activePos.soldCe.entryPrice
                        val buyCeLtp = ltpMap[activePos.buyCeHedge.symbol] ?: activePos.buyCeHedge.entryPrice
                        val soldPeLtp = ltpMap[activePos.soldPe.symbol] ?: activePos.soldPe.entryPrice
                        val buyPeLtp = ltpMap[activePos.buyPeHedge.symbol] ?: activePos.buyPeHedge.entryPrice

                        // Check Stop-Loss on sold legs (SL = entry * 1.4)
                        if (!activePos.soldPe.isClosed && soldPeLtp >= activePos.soldPe.currentSl) {
                            activePos.soldPe.isClosed = true
                            activePos.buyPeHedge.isClosed = true
                            activePos.soldCe.currentSl = activePos.soldCe.entryPrice // Move CE SL to cost
                            val slMsg = "🛑 [IronFly SL HIT] Sold PE hit SL ($soldPeLtp >= ${activePos.soldPe.currentSl}). Closed PE legs & moved CE SL to cost."
                            println(slMsg)
                            scope.launch { TelegramNotifier.sendAlert(slMsg) }
                        }

                        if (!activePos.soldCe.isClosed && soldCeLtp >= activePos.soldCe.currentSl) {
                            activePos.soldCe.isClosed = true
                            activePos.buyCeHedge.isClosed = true
                            activePos.soldPe.currentSl = activePos.soldPe.entryPrice // Move PE SL to cost
                            val slMsg = "🛑 [IronFly SL HIT] Sold CE hit SL ($soldCeLtp >= ${activePos.soldCe.currentSl}). Closed CE legs & moved PE SL to cost."
                            println(slMsg)
                            scope.launch { TelegramNotifier.sendAlert(slMsg) }
                        }

                        // Check Profit Target: ₹1,200 fixed profit target
                        val cePnl = if (activePos.soldCe.isClosed) 0.0 else (activePos.soldCe.entryPrice - soldCeLtp)
                        val buyCePnl = if (activePos.buyCeHedge.isClosed) 0.0 else (buyCeLtp - activePos.buyCeHedge.entryPrice)
                        val pePnl = if (activePos.soldPe.isClosed) 0.0 else (activePos.soldPe.entryPrice - soldPeLtp)
                        val buyPePnl = if (activePos.buyPeHedge.isClosed) 0.0 else (buyPeLtp - activePos.buyPeHedge.entryPrice)

                        val totalMtm = (cePnl + buyCePnl + pePnl + buyPePnl) * effectiveLotSize
                        val targetProfit = 1200.0

                        if (totalMtm >= targetProfit) {
                            val tpMsg = "💰 [IronFly PROFIT TARGET HIT] MTM Profit: ₹${String.format("%.2f", totalMtm)} >= ₹1,200 target. Exiting all legs!"
                            println(tpMsg)
                            scope.launch {
                                TelegramNotifier.sendAlert(tpMsg)
                                squareOffIronFly(activePos, ltpMap, effectiveLotSize, "PROFIT_TARGET_1200")
                            }
                            activePos.soldCe.isClosed = true
                            activePos.soldPe.isClosed = true
                        }

                        if (activePos.soldCe.isClosed && activePos.soldPe.isClosed) {
                            break
                        }

                        delay(1_000L)
                    }

                    liveStreamer.let { try { /* Disconnect socket */ } catch (_: Exception) {} }
                    delay(3600_000L)

                } catch (e: Exception) {
                    val criticalErr = "❌ [IronFly CRITICAL ERROR]: ${e.message}"
                    println(criticalErr)
                    TelegramNotifier.sendAlert(criticalErr)
                    throw e
                }
            }
        }
    }

    private suspend fun squareOffIronFly(
        activePos: IronFlyPosition,
        ltpMap: Map<String, Double>,
        lotSize: Double,
        reason: String
    ) {
        val soldCeLtp = ltpMap[activePos.soldCe.symbol] ?: activePos.soldCe.entryPrice
        val buyCeLtp = ltpMap[activePos.buyCeHedge.symbol] ?: activePos.buyCeHedge.entryPrice
        val soldPeLtp = ltpMap[activePos.soldPe.symbol] ?: activePos.soldPe.entryPrice
        val buyPeLtp = ltpMap[activePos.buyPeHedge.symbol] ?: activePos.buyPeHedge.entryPrice

        val cePnlPts = if (activePos.soldCe.isClosed) 0.0 else (activePos.soldCe.entryPrice - soldCeLtp)
        val buyCePnlPts = if (activePos.buyCeHedge.isClosed) 0.0 else (buyCeLtp - activePos.buyCeHedge.entryPrice)
        val pePnlPts = if (activePos.soldPe.isClosed) 0.0 else (activePos.soldPe.entryPrice - soldPeLtp)
        val buyPePnlPts = if (activePos.buyPeHedge.isClosed) 0.0 else (buyPeLtp - activePos.buyPeHedge.entryPrice)

        val totalPnlPts = cePnlPts + buyCePnlPts + pePnlPts + buyPePnlPts
        val totalRupees = totalPnlPts * lotSize
        val totalStr = if (totalRupees >= 0) "+₹${String.format("%,.2f", totalRupees)}" else "-₹${String.format("%,.2f", abs(totalRupees))}"
        val nowStr = LocalDateTime.now(istZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val indexName = if (activePos.indexSymbol.contains("SENSEX")) "SENSEX" else "NIFTY"

        val exitMsg = """
            🏁 EXIT · IRON FLY ($indexName) ($reason)
            Total P&L: $totalStr
            [PAPER] $nowStr IST
        """.trimIndent()
        println(exitMsg)
        TelegramNotifier.sendAlert(exitMsg)

        val eodMsg = """
            📊 End-of-Day P&L · $indexName [PAPER]
            $nowStr IST
            IRON FLY [PAPER]: $totalStr (1 trade session)
            ──────────
            Total: $totalStr (1 trade session)
        """.trimIndent()
        println(eodMsg)
        TelegramNotifier.sendAlert(eodMsg)

        TradingJournalService.logTrade(TradeJournalEntry(activePos.indexSymbol, 0.0, 0.0, lotSize.toInt(), totalRupees, reason))
    }

    private fun extractSpotLtp(quotes: JSONObject?, symbol: String): Double {
        try {
            val dataArray = quotes?.optJSONArray("d") ?: return 0.0
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val name = item.optString("n", "")
                if (name.equals(symbol, ignoreCase = true)) {
                    val vObj = item.optJSONObject("v") ?: continue
                    return vObj.optDouble("lp", 0.0)
                }
            }
        } catch (_: Exception) {}
        return 0.0
    }
}
