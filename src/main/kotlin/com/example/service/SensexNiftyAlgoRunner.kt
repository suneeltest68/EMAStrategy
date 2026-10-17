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
    
    // Store daily states per index
    private val dailyStates = ConcurrentHashMap<String, IndexStrangleState>()

    fun startAlgoSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING NIFTY + SENSEX OTM4 STRANGLE STRICT WEBSOCKET ALGO")
            println("==============================================================\n")

            TelegramNotifier.sendAlert("🚀 NIFTY + SENSEX OTM4 Strangle WebSocket Algo Started (Strict Mode).")

            while (isActive) {
                val now = LocalDateTime.now(istZone)
                val currentDate = now.toLocalDate()
                val currentTime = now.toLocalTime()
                val dateStr = currentDate.toString()

                val marketOpen = LocalTime.of(9, 16)
                val marketClose = LocalTime.of(15, 30)
                val squareOffTime = LocalTime.of(15, 25)

                if (currentTime.isBefore(marketOpen)) {
                    val targetEntry = LocalDateTime.of(currentDate, marketOpen)
                    val millisToWait = Duration.between(LocalDateTime.now(istZone), targetEntry).toMillis()
                    if (millisToWait > 0) {
                        println("[Waiting] Pre-market. Sleeping until exact 9:16 AM (${millisToWait / 1000}s)...")
                        delay(minOf(millisToWait, 60_000L))
                    } else {
                        delay(1000L)
                    }
                    continue
                }

                if (currentTime.isAfter(marketClose)) {
                    println("[Market Closed] Trading hours ended for today.")
                    delay(3600_000L)
                    continue
                }

                val runNifty = SensexNiftyStrangleScheduler.shouldRunNifty(currentDate)
                val runSensex = SensexNiftyStrangleScheduler.shouldRunSensex(currentDate)

                // Run NIFTY System if scheduled
                if (runNifty) {
                    try {
                        executeIndexStrangle(
                            indexName = "NIFTY",
                            indexSymbol = "NSE:NIFTY50-INDEX",
                            strikeInterval = 50,
                            lotSize = 65.0,
                            dateStr = dateStr,
                            currentTime = currentTime,
                            squareOffTime = squareOffTime
                        )
                    } catch (e: Exception) {
                        val errMsg = "❌ [NIFTY CRITICAL ERROR]: ${e.message}"
                        println(errMsg)
                        TelegramNotifier.sendAlert(errMsg)
                    }
                }

                // Run SENSEX System if scheduled
                if (runSensex) {
                    try {
                        executeIndexStrangle(
                            indexName = "SENSEX",
                            indexSymbol = "BSE:SENSEX-INDEX",
                            strikeInterval = 100,
                            lotSize = 10.0,
                            dateStr = dateStr,
                            currentTime = currentTime,
                            squareOffTime = squareOffTime
                        )
                    } catch (e: Exception) {
                        val errMsg = "❌ [SENSEX CRITICAL ERROR]: ${e.message}"
                        println(errMsg)
                        TelegramNotifier.sendAlert(errMsg)
                    }
                }

                delay(1000L) // Check every 1 second for millisecond-precise entry and risk checks
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

        // 1. Strict Entry at 9:16 AM
        val entryTimeCheck = LocalTime.of(9, 16)
        if (state == null && !currentTime.isBefore(entryTimeCheck)) {
            println("🔔 [$indexName] Initializing OTM4 Strangle WebSocket Feed for $dateStr at $currentTime...")
            
            val quotes = viewModel.fetchStockQuotes(appId, token, indexSymbol)
                ?: throw IllegalStateException("Failed to fetch spot quotes for $indexSymbol")
            val spotPrice = extractSpotLtp(quotes, indexSymbol)
            if (spotPrice <= 0.0) {
                throw IllegalStateException("Invalid spot price ($spotPrice) for $indexSymbol")
            }

            val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, strikeInterval)
            val otmCallStrike = atmStrike + (4 * strikeInterval)
            val otmPutStrike = atmStrike - (4 * strikeInterval)

            val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 15)
                ?: throw IllegalStateException("Failed to fetch option chain for $indexSymbol")

            val callSymbol = OptionUtils.findOptionSymbolFromChain(optionChain, otmCallStrike, "CE")
                ?: throw IllegalStateException("Could not resolve OTM4 Call symbol for strike $otmCallStrike")
            val putSymbol = OptionUtils.findOptionSymbolFromChain(optionChain, otmPutStrike, "PE")
                ?: throw IllegalStateException("Could not resolve OTM4 Put symbol for strike $otmPutStrike")

            val legQuotes = viewModel.fetchStockQuotes(appId, token, "$callSymbol,$putSymbol")
                ?: throw IllegalStateException("Failed to fetch option entry quotes for $callSymbol and $putSymbol")
            val callEntry = extractLtp(legQuotes, callSymbol)
            val putEntry = extractLtp(legQuotes, putSymbol)

            if (callEntry <= 0.0 || putEntry <= 0.0) {
                throw IllegalStateException("Invalid option entry prices (Call: $callEntry, Put: $putEntry)")
            }

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
                🚀 ENTRY · $indexName OTM4 STRANGLE (STRICT WEBSOCKET LIVE)
                Call: SELL $callSymbol @ ₹${String.format("%.2f", callEntry)}
                Put: SELL $putSymbol @ ₹${String.format("%.2f", putEntry)}
                Qty: ${lotSize.toInt()} (1 lot each)
                [PAPER] $dateStr $currentTime IST
            """.trimIndent()
            println(entryMsg)
            TelegramNotifier.sendAlert(entryMsg)

            // Initialize Fyers WebSocket Real-time Streamer (Strictly WebSocket-driven risk monitoring)
            val streamer = FyersRealtimeStreamer(appId, token) { symbol, ltp ->
                handleRealtimeTick(state, symbol, ltp, lotSize, dateStr)
            }
            streamer.connectAndSubscribe(listOf(callSymbol, putSymbol))
            state.streamer = streamer
            return
        }

        if (state == null || state.isSquareOff) return

        // Check 3:25 PM EOD Square-Off
        if (!currentTime.isBefore(squareOffTime)) {
            state.isSquareOff = true
            state.streamer?.let { try { /* Close socket */ } catch (_: Exception) {} }
            val squareMsg = "🏁 [$indexName EOD SQUARE-OFF] 3:25 PM reached. Squaring off remaining legs."
            println(squareMsg)
            TelegramNotifier.sendAlert(squareMsg)
            scope.launch { squareOffAllLegs(state, lotSize, dateStr, "AUTO_SQUARE_OFF_3:25") }
        }
    }

    private fun handleRealtimeTick(
        state: IndexStrangleState,
        symbol: String,
        ltp: Double,
        lotSize: Double,
        dateStr: String
    ) {
        if (state.isSquareOff || ltp <= 0.0) return

        val nowTime = LocalTime.now(istZone)
        val timeStr = nowTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        // Check Overall MTM SL (-₹1,000)
        val totalMtm = state.getTotalMtm(lotSize)
        if (totalMtm <= -1000.0 && !state.overallMtmSlBreached) {
            state.overallMtmSlBreached = true
            state.isSquareOff = true
            val mtmMsg = "🛑 [${state.indexName} OVERALL MTM SL BREACH] Combined MTM reached ₹${String.format("%.2f", totalMtm)}. Squaring off via WebSocket!"
            println(mtmMsg)
            scope.launch {
                TelegramNotifier.sendAlert(mtmMsg)
                squareOffAllLegs(state, lotSize, dateStr, "OVERALL_MTM_SL")
            }
            return
        }

        // Manage Call Leg
        val callLeg = state.callLeg
        if (callLeg != null && callLeg.symbol.equals(symbol, ignoreCase = true) && callLeg.status == "ACTIVE") {
            callLeg.currentPrice = ltp
            if (ltp >= callLeg.stopLossPrice) {
                callLeg.status = "SL_HIT"
                val pnlPts = callLeg.entryPrice - callLeg.stopLossPrice
                callLeg.realizedPnl = pnlPts
                val rupees = pnlPts * lotSize
                val msg = "🏁 EXIT · ${state.indexName} (CALL SL HIT)\nInstrument: SELL ${callLeg.symbol}\n  Entry: ₹${String.format("%.2f", callLeg.entryPrice)} → Exit: ₹${String.format("%.2f", callLeg.stopLossPrice)}\nP&L: -₹${String.format("%,.2f", abs(rupees))}\n[PAPER] $dateStr $timeStr IST"
                println(msg)
                scope.launch {
                    TelegramNotifier.sendAlert(msg)
                    TradingJournalService.logTrade(TradeJournalEntry(callLeg.symbol, callLeg.entryPrice, callLeg.stopLossPrice, lotSize.toInt(), rupees, "CALL_SL_HIT"))
                }
            } else if (ltp <= callLeg.targetPrice) {
                val pnlPts = callLeg.entryPrice - callLeg.targetPrice
                callLeg.realizedPnl += pnlPts
                val rupees = pnlPts * lotSize
                val msg = "🏁 TARGET HIT · ${state.indexName} (CALL)\nInstrument: BOOK ${callLeg.symbol}\n  Entry: ₹${String.format("%.2f", callLeg.entryPrice)} → Target: ₹${String.format("%.2f", callLeg.targetPrice)}\nP&L: +₹${String.format("%,.2f", rupees)}\n[PAPER] $dateStr $timeStr IST"
                println(msg)
                scope.launch {
                    TelegramNotifier.sendAlert(msg)
                    TradingJournalService.logTrade(TradeJournalEntry(callLeg.symbol, callLeg.entryPrice, callLeg.targetPrice, lotSize.toInt(), rupees, "CALL_TARGET_HIT"))
                }

                if (callLeg.targetReentryCount < 1) {
                    callLeg.targetReentryCount++
                    callLeg.entryPrice = ltp
                    callLeg.stopLossPrice = ltp * 1.40
                    callLeg.targetPrice = ltp * 0.30
                    callLeg.status = "ACTIVE"
                    val reMsg = "🚀 RE-ENTRY · ${state.indexName} (CALL Re-entry #${callLeg.targetReentryCount})\nInstrument: SELL ${callLeg.symbol} @ ₹${String.format("%.2f", ltp)}"
                    println(reMsg)
                    scope.launch { TelegramNotifier.sendAlert(reMsg) }
                } else {
                    callLeg.status = "TARGET_HIT"
                }
            }
        }

        // Manage Put Leg
        val putLeg = state.putLeg
        if (putLeg != null && putLeg.symbol.equals(symbol, ignoreCase = true) && putLeg.status == "ACTIVE") {
            putLeg.currentPrice = ltp
            if (ltp >= putLeg.stopLossPrice) {
                putLeg.status = "SL_HIT"
                val pnlPts = putLeg.entryPrice - putLeg.stopLossPrice
                putLeg.realizedPnl = pnlPts
                val rupees = pnlPts * lotSize
                val msg = "🏁 EXIT · ${state.indexName} (PUT SL HIT)\nInstrument: SELL ${putLeg.symbol}\n  Entry: ₹${String.format("%.2f", putLeg.entryPrice)} → Exit: ₹${String.format("%.2f", putLeg.stopLossPrice)}\nP&L: -₹${String.format("%,.2f", abs(rupees))}\n[PAPER] $dateStr $timeStr IST"
                println(msg)
                scope.launch {
                    TelegramNotifier.sendAlert(msg)
                    TradingJournalService.logTrade(TradeJournalEntry(putLeg.symbol, putLeg.entryPrice, putLeg.stopLossPrice, lotSize.toInt(), rupees, "PUT_SL_HIT"))
                }
            } else if (ltp <= putLeg.targetPrice) {
                val pnlPts = putLeg.entryPrice - putLeg.targetPrice
                putLeg.realizedPnl += pnlPts
                val rupees = pnlPts * lotSize
                val msg = "🏁 TARGET HIT · ${state.indexName} (PUT)\nInstrument: BOOK ${putLeg.symbol}\n  Entry: ₹${String.format("%.2f", putLeg.entryPrice)} → Target: ₹${String.format("%.2f", putLeg.targetPrice)}\nP&L: +₹${String.format("%,.2f", rupees)}\n[PAPER] $dateStr $timeStr IST"
                println(msg)
                scope.launch {
                    TelegramNotifier.sendAlert(msg)
                    TradingJournalService.logTrade(TradeJournalEntry(putLeg.symbol, putLeg.entryPrice, putLeg.targetPrice, lotSize.toInt(), rupees, "PUT_TARGET_HIT"))
                }

                if (putLeg.targetReentryCount < 1) {
                    putLeg.targetReentryCount++
                    putLeg.entryPrice = ltp
                    putLeg.stopLossPrice = ltp * 1.40
                    putLeg.targetPrice = ltp * 0.30
                    putLeg.status = "ACTIVE"
                    val reMsg = "🚀 RE-ENTRY · ${state.indexName} (PUT Re-entry #${putLeg.targetReentryCount})\nInstrument: SELL ${putLeg.symbol} @ ₹${String.format("%.2f", ltp)}"
                    println(reMsg)
                    scope.launch { TelegramNotifier.sendAlert(reMsg) }
                } else {
                    putLeg.status = "TARGET_HIT"
                }
            }
        }
    }

    private suspend fun squareOffAllLegs(
        state: IndexStrangleState,
        lotSize: Double,
        dateStr: String,
        reason: String
    ) {
        val callLeg = state.callLeg
        var callPnlRupees = 0.0
        if (callLeg != null && callLeg.status == "ACTIVE") {
            callLeg.status = "EXITED"
            val pnlPts = callLeg.entryPrice - callLeg.currentPrice
            callPnlRupees = (callLeg.realizedPnl + pnlPts) * lotSize
            TradingJournalService.logTrade(TradeJournalEntry(callLeg.symbol, callLeg.entryPrice, callLeg.currentPrice, lotSize.toInt(), callPnlRupees, reason))
        } else if (callLeg != null) {
            callPnlRupees = callLeg.realizedPnl * lotSize
        }

        val putLeg = state.putLeg
        var putPnlRupees = 0.0
        if (putLeg != null && putLeg.status == "ACTIVE") {
            putLeg.status = "EXITED"
            val pnlPts = putLeg.entryPrice - putLeg.currentPrice
            putPnlRupees = (putLeg.realizedPnl + pnlPts) * lotSize
            TradingJournalService.logTrade(TradeJournalEntry(putLeg.symbol, putLeg.entryPrice, putLeg.currentPrice, lotSize.toInt(), putPnlRupees, reason))
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
}
