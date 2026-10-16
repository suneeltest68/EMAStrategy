package com.example.service

import com.example.strategy.*
import com.example.util.OptionUtils
import com.example.viewmodel.AuthViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class SensexNiftyAlgoRunner(
    private val viewModel: AuthViewModel,
    private val appId: String,
    private val token: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val istZone = ZoneId.of("Asia/Kolkata")
    
    // Store daily states per index: Map<"NIFTY-2026-10-16", IndexStrangleState>
    private val dailyStates = ConcurrentHashMap<String, IndexStrangleState>()

    fun startAlgoSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING NIFTY + SENSEX OTM4 STRANGLE DAY-BASED ROTATION ALGO")
            println("==============================================================\n")

            TelegramNotifier.sendAlert("🚀 NIFTY + SENSEX OTM4 Strangle Day-Based Rotation Algo Started.")

            while (isActive) {
                waitForNext1MinBoundary()

                val now = LocalDateTime.now(istZone)
                val currentDate = now.toLocalDate()
                val currentTime = now.toLocalTime()
                val dateStr = currentDate.toString()

                val marketOpen = LocalTime.of(9, 16)
                val marketClose = LocalTime.of(15, 30)
                val squareOffTime = LocalTime.of(15, 25)

                if (currentTime.isBefore(marketOpen) || currentTime.isAfter(marketClose)) {
                    delay(30_000L)
                    continue
                }

                val runNifty = SensexNiftyStrangleScheduler.shouldRunNifty(currentDate)
                val runSensex = SensexNiftyStrangleScheduler.shouldRunSensex(currentDate)

                // Run NIFTY System if scheduled
                if (runNifty) {
                    executeIndexStrangle(
                        indexName = "NIFTY",
                        indexSymbol = "NSE:NIFTY50-INDEX",
                        strikeInterval = 50,
                        lotSize = 65.0,
                        dateStr = dateStr,
                        currentTime = currentTime,
                        squareOffTime = squareOffTime
                    )
                }

                // Run SENSEX System if scheduled
                if (runSensex) {
                    executeIndexStrangle(
                        indexName = "SENSEX",
                        indexSymbol = "BSE:SENSEX",
                        strikeInterval = 100,
                        lotSize = 10.0,
                        dateStr = dateStr,
                        currentTime = currentTime,
                        squareOffTime = squareOffTime
                    )
                }
            }
        }
    }

    private suspend fun executeIndexStrangle(
        indexName: String,
        indexSymbol: String,
        strikeInterval: Int,
        lotSize: Double,
        dateStr: String,
        currentTime: LocalTime,
        squareOffTime: LocalTime
    ) {
        val stateKey = "$indexName-$dateStr"
        var state = dailyStates[stateKey]

        // 1. Entry at 9:16 AM
        val entryTimeCheck = LocalTime.of(9, 16)
        if (state == null && !currentTime.isBefore(entryTimeCheck)) {
            try {
                println("🔔 [$indexName] Initializing OTM4 Strangle for $dateStr at $currentTime...")
                
                // Fetch spot price
                val quotes = viewModel.fetchStockQuotes(appId, token, indexSymbol)
                val spotPrice = extractSpotLtp(quotes, indexSymbol)
                if (spotPrice <= 0.0) {
                    println("❌ [$indexName] Failed to get spot price for $indexSymbol")
                    return
                }

                val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)
                val otmCallStrike = atmStrike + (4 * strikeInterval)
                val otmPutStrike = atmStrike - (4 * strikeInterval)

                val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 15)
                if (optionChain == null) {
                    println("❌ [$indexName] Failed to fetch option chain")
                    return
                }

                val callSymbol = OptionUtils.findOptionSymbolFromChain(optionChain, otmCallStrike, "CE")
                val putSymbol = OptionUtils.findOptionSymbolFromChain(optionChain, otmPutStrike, "PE")

                if (callSymbol.isNullOrEmpty() || putSymbol.isNullOrEmpty()) {
                    println("❌ [$indexName] Could not resolve OTM4 option symbols (CE: $callSymbol, PE: $putSymbol)")
                    return
                }

                // Fetch entry prices for legs
                val legQuotes = viewModel.fetchStockQuotes(appId, token, "$callSymbol,$putSymbol")
                val callEntry = extractLtp(legQuotes, callSymbol)
                val putEntry = extractLtp(legQuotes, putSymbol)

                if (callEntry <= 0.0 || putEntry <= 0.0) {
                    println("❌ [$indexName] Invalid entry prices (Call: $callEntry, Put: $putEntry)")
                    return
                }

                // Short Strangle rules: SL = Entry * 1.40 (+40%), Target = Entry * 0.30 (-70%)
                val callLeg = StrangleLeg(
                    symbol = callSymbol,
                    type = "CE",
                    strike = otmCallStrike,
                    entryPrice = callEntry,
                    currentPrice = callEntry,
                    stopLossPrice = callEntry * 1.40,
                    targetPrice = callEntry * 0.30
                )

                val putLeg = StrangleLeg(
                    symbol = putSymbol,
                    type = "PE",
                    strike = otmPutStrike,
                    entryPrice = putEntry,
                    currentPrice = putEntry,
                    stopLossPrice = putEntry * 1.40,
                    targetPrice = putEntry * 0.30
                )

                state = IndexStrangleState(indexName, callLeg, putLeg)
                dailyStates[stateKey] = state

                val entryMsg = """
                    🚀 ENTRY · $indexName OTM4 STRANGLE
                    Call: SELL $callSymbol @ ₹${String.format("%.2f", callEntry)} (SL: ₹${String.format("%.2f", callLeg.stopLossPrice)}, Tgt: ₹${String.format("%.2f", callLeg.targetPrice)})
                    Put: SELL $putSymbol @ ₹${String.format("%.2f", putEntry)} (SL: ₹${String.format("%.2f", putLeg.stopLossPrice)}, Tgt: ₹${String.format("%.2f", putLeg.targetPrice)})
                    Qty: ${lotSize.toInt()} (1 lot each)
                    [PAPER] $dateStr $currentTime IST
                """.trimIndent()
                println(entryMsg)
                TelegramNotifier.sendAlert(entryMsg)

            } catch (e: Exception) {
                println("❌ [$indexName] Entry error: ${e.message}")
            }
            return
        }

        if (state == null || state.isSquareOff) return

        // 2. Active Management & Monitoring
        try {
            val callSym = state.callLeg?.symbol ?: ""
            val putSym = state.putLeg?.symbol ?: ""
            if (callSym.isEmpty() || putSym.isEmpty()) return

            val quotes = viewModel.fetchStockQuotes(appId, token, "$callSym,$putSym")
            val callLtp = extractLtp(quotes, callSym)
            val putLtp = extractLtp(quotes, putSym)

            if (callLtp <= 0.0 || putLtp <= 0.0) return

            // Check Overall MTM SL (-₹1,000 per index)
            val totalMtm = state.getTotalMtm(callLtp, putLtp, lotSize)
            if (totalMtm <= -1000.0 && !state.overallMtmSlBreached) {
                state.overallMtmSlBreached = true
                state.isSquareOff = true
                val mtmMsg = "🛑 [$indexName OVERALL MTM SL BREACHED] Combined MTM reached ₹${String.format("%.2f", totalMtm)}. Squaring off all legs for the day."
                println(mtmMsg)
                TelegramNotifier.sendAlert(mtmMsg)
                squareOffAllLegs(state, callLtp, putLtp, lotSize, dateStr, "OVERALL_MTM_SL")
                return
            }

            // Check 3:25 PM EOD Square-Off
            if (!currentTime.isBefore(squareOffTime)) {
                state.isSquareOff = true
                val squareMsg = "🏁 [$indexName EOD SQUARE-OFF] 3:25 PM reached. Squaring off remaining legs."
                println(squareMsg)
                TelegramNotifier.sendAlert(squareMsg)
                squareOffAllLegs(state, callLtp, putLtp, lotSize, dateStr, "AUTO_SQUARE_OFF_3:25")
                return
            }

            // Manage Call Leg Separately
            val callLeg = state.callLeg
            if (callLeg != null && callLeg.status == "ACTIVE") {
                callLeg.currentPrice = callLtp
                if (callLtp >= callLeg.stopLossPrice) {
                    // SL Hit (No Re-entry)
                    callLeg.status = "SL_HIT"
                    val pnlPts = callLeg.entryPrice - callLeg.stopLossPrice
                    callLeg.realizedPnl = pnlPts
                    val rupees = pnlPts * lotSize
                    val msg = "🏁 EXIT · $indexName (CALL SL HIT)\nInstrument: SELL ${callLeg.symbol}\n  Entry: ₹${String.format("%.2f", callLeg.entryPrice)} → Exit: ₹${String.format("%.2f", callLeg.stopLossPrice)}\nP&L: ${if (rupees >= 0) "+₹" else "-₹"}${String.format("%,.2f", abs(rupees))}\n[PAPER] $dateStr $currentTime IST"
                    println(msg)
                    TelegramNotifier.sendAlert(msg)
                    TradingJournalService.logTrade(TradeJournalEntry(callLeg.symbol, callLeg.entryPrice, callLeg.stopLossPrice, lotSize.toInt(), rupees, "CALL_SL_HIT"))
                } else if (callLtp <= callLeg.targetPrice) {
                    // Target Hit (Max 1 re-entry)
                    val pnlPts = callLeg.entryPrice - callLeg.targetPrice
                    callLeg.realizedPnl += pnlPts
                    val rupees = pnlPts * lotSize
                    val msg = "🏁 TARGET HIT · $indexName (CALL)\nInstrument: BOOK ${callLeg.symbol}\n  Entry: ₹${String.format("%.2f", callLeg.entryPrice)} → Target: ₹${String.format("%.2f", callLeg.targetPrice)}\nP&L: +₹${String.format("%,.2f", rupees)}\n[PAPER] $dateStr $currentTime IST"
                    println(msg)
                    TelegramNotifier.sendAlert(msg)
                    TradingJournalService.logTrade(TradeJournalEntry(callLeg.symbol, callLeg.entryPrice, callLeg.targetPrice, lotSize.toInt(), rupees, "CALL_TARGET_HIT"))

                    if (callLeg.targetReentryCount < 1) {
                        callLeg.targetReentryCount++
                        callLeg.entryPrice = callLtp // Re-enter at current LTP
                        callLeg.stopLossPrice = callLtp * 1.40
                        callLeg.targetPrice = callLtp * 0.30
                        callLeg.status = "ACTIVE"
                        val reMsg = "🚀 RE-ENTRY · $indexName (CALL Re-entry #${callLeg.targetReentryCount})\nInstrument: SELL ${callLeg.symbol} @ ₹${String.format("%.2f", callLtp)}"
                        println(reMsg)
                        TelegramNotifier.sendAlert(reMsg)
                    } else {
                        callLeg.status = "TARGET_HIT"
                    }
                }
            }

            // Manage Put Leg Separately
            val putLeg = state.putLeg
            if (putLeg != null && putLeg.status == "ACTIVE") {
                putLeg.currentPrice = putLtp
                if (putLtp >= putLeg.stopLossPrice) {
                    // SL Hit (No Re-entry)
                    putLeg.status = "SL_HIT"
                    val pnlPts = putLeg.entryPrice - putLeg.stopLossPrice
                    putLeg.realizedPnl = pnlPts
                    val rupees = pnlPts * lotSize
                    val msg = "🏁 EXIT · $indexName (PUT SL HIT)\nInstrument: SELL ${putLeg.symbol}\n  Entry: ₹${String.format("%.2f", putLeg.entryPrice)} → Exit: ₹${String.format("%.2f", putLeg.stopLossPrice)}\nP&L: ${if (rupees >= 0) "+₹" else "-₹"}${String.format("%,.2f", abs(rupees))}\n[PAPER] $dateStr $currentTime IST"
                    println(msg)
                    TelegramNotifier.sendAlert(msg)
                    TradingJournalService.logTrade(TradeJournalEntry(putLeg.symbol, putLeg.entryPrice, putLeg.stopLossPrice, lotSize.toInt(), rupees, "PUT_SL_HIT"))
                } else if (putLtp <= putLeg.targetPrice) {
                    // Target Hit (Max 1 re-entry)
                    val pnlPts = putLeg.entryPrice - putLeg.targetPrice
                    putLeg.realizedPnl += pnlPts
                    val rupees = pnlPts * lotSize
                    val msg = "🏁 TARGET HIT · $indexName (PUT)\nInstrument: BOOK ${putLeg.symbol}\n  Entry: ₹${String.format("%.2f", putLeg.entryPrice)} → Target: ₹${String.format("%.2f", putLeg.targetPrice)}\nP&L: +₹${String.format("%,.2f", rupees)}\n[PAPER] $dateStr $currentTime IST"
                    println(msg)
                    TelegramNotifier.sendAlert(msg)
                    TradingJournalService.logTrade(TradeJournalEntry(putLeg.symbol, putLeg.entryPrice, putLeg.targetPrice, lotSize.toInt(), rupees, "PUT_TARGET_HIT"))

                    if (putLeg.targetReentryCount < 1) {
                        putLeg.targetReentryCount++
                        putLeg.entryPrice = putLtp // Re-enter at current LTP
                        putLeg.stopLossPrice = putLtp * 1.40
                        putLeg.targetPrice = putLtp * 0.30
                        putLeg.status = "ACTIVE"
                        val reMsg = "🚀 RE-ENTRY · $indexName (PUT Re-entry #${putLeg.targetReentryCount})\nInstrument: SELL ${putLeg.symbol} @ ₹${String.format("%.2f", putLtp)}"
                        println(reMsg)
                        TelegramNotifier.sendAlert(reMsg)
                    } else {
                        putLeg.status = "TARGET_HIT"
                    }
                }
            }

        } catch (e: Exception) {
            println("❌ [$indexName] Monitoring error: ${e.message}")
        }
    }

    private suspend fun squareOffAllLegs(
        state: IndexStrangleState,
        callLtp: Double,
        putLtp: Double,
        lotSize: Double,
        dateStr: String,
        reason: String
    ) {
        val callLeg = state.callLeg
        var callPnlRupees = 0.0
        if (callLeg != null && callLeg.status == "ACTIVE") {
            callLeg.status = "EXITED"
            val pnlPts = callLeg.entryPrice - callLtp
            callPnlRupees = (callLeg.realizedPnl + pnlPts) * lotSize
            TradingJournalService.logTrade(TradeJournalEntry(callLeg.symbol, callLeg.entryPrice, callLtp, lotSize.toInt(), callPnlRupees, reason))
        } else if (callLeg != null) {
            callPnlRupees = callLeg.realizedPnl * lotSize
        }

        val putLeg = state.putLeg
        var putPnlRupees = 0.0
        if (putLeg != null && putLeg.status == "ACTIVE") {
            putLeg.status = "EXITED"
            val pnlPts = putLeg.entryPrice - putLtp
            putPnlRupees = (putLeg.realizedPnl + pnlPts) * lotSize
            TradingJournalService.logTrade(TradeJournalEntry(putLeg.symbol, putLeg.entryPrice, putLtp, lotSize.toInt(), putPnlRupees, reason))
        } else if (putLeg != null) {
            putPnlRupees = putLeg.realizedPnl * lotSize
        }

        val totalNetRupees = callPnlRupees + putPnlRupees
        val totalStr = if (totalNetRupees >= 0) "+₹${String.format("%,.2f", totalNetRupees)}" else "-₹${String.format("%,.2f", abs(totalNetRupees))}"
        val nowStr = LocalDateTime.now(istZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val eodMsg = """
            📊 End-of-Day P&L · ${state.indexName} [PAPER]
            $nowStr IST
            ${state.indexName} [PAPER]: $totalStr
            ──────────
            Total: $totalStr
        """.trimIndent()
        println(eodMsg)
        TelegramNotifier.sendAlert(eodMsg)
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

    private fun extractLtp(quotes: JSONObject?, symbol: String): Double {
        return extractSpotLtp(quotes, symbol)
    }

    private suspend fun waitForNext1MinBoundary() {
        val now = LocalDateTime.now(istZone)
        val target = if (now.second < 2) {
            now.withSecond(2).withNano(0)
        } else {
            now.plusMinutes(1).withSecond(2).withNano(0)
        }
        val millisToWait = Duration.between(LocalDateTime.now(istZone), target).toMillis()
        if (millisToWait > 0) {
            delay(millisToWait)
        }
    }
}
