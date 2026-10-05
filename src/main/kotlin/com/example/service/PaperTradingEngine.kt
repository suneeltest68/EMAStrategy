package com.example.service

import com.example.model.GannLevels
import com.example.model.TradeState
import org.json.JSONObject
import java.io.File
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PaperTradingEngine(
    private val symbolsGannMap: Map<String, GannLevels>,
    private val quantitiesMap: Map<String, Int>
) {
    private val stateFile = File("active_trades.json")
    private val tradesMap = ConcurrentHashMap<String, TradeState>()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun getTrade(symbol: String): TradeState? = tradesMap[symbol]

    init {
        // Start each trading session fresh by clearing any previous day's state file
        if (stateFile.exists()) {
            stateFile.delete()
        }
        loadState()
        for ((symbol, gann) in symbolsGannMap) {
            val existing = tradesMap[symbol]
            val qty = quantitiesMap[symbol] ?: 1
            if (existing == null) {
                tradesMap[symbol] = TradeState(
                    symbol = symbol,
                    status = "IDLE",
                    stopLoss = gann.stopLoss,
                    entryPrice = gann.buyPrice,
                    targets = gann.targets,
                    quantity = qty
                )
            } else {
                existing.entryPrice = gann.buyPrice
                if (existing.status == "IDLE") {
                    existing.stopLoss = gann.stopLoss
                }
                existing.targets = gann.targets
            }
        }
    }

    fun onTick(symbol: String, ltp: Double, testTime: LocalTime? = null) {
        val trade = tradesMap[symbol] ?: return
        val gann = symbolsGannMap[symbol] ?: return

        // Check EOD Auto Square-Off at 3:25 PM IST
        val istZone = ZoneId.of("Asia/Kolkata")
        val now = testTime ?: LocalTime.now(istZone)
        val squareOffTime = LocalTime.of(15, 25)
        if (now.isAfter(squareOffTime) || now == squareOffTime) {
            try {
                if (trade.status == "ACTIVE") {
                    val pnl = (ltp - trade.entryPrice) * trade.quantity
                    val msg = "[AUTO SQUARE-OFF] 3:25 PM EOD reached! Force exiting $symbol at LTP ${String.format("%.2f", ltp)} | P&L: ${String.format("%.2f", pnl)}"
                    println(msg)
                    scope.launch { TelegramNotifier.sendAlert(msg) }
                    
                    TradingJournalService.logTrade(TradeJournalEntry(symbol, trade.entryPrice, ltp, trade.quantity, pnl, "AUTO_SQUARE_OFF"))

                    trade.status = "EXITED"
                    saveState()
                    checkAndSendEodSummary()
                }
            } catch (e: Exception) {
                TradingJournalService.logError("Auto Square-off error for $symbol: ${e.message}")
            }
            return
        }

        when (trade.status) {
            "IDLE" -> {
                if (ltp > trade.entryPrice) {
                    trade.status = "PENDING_LIMIT"
                    println("[PENDING LIMIT ORDER] $symbol placed at Entry Price: ${String.format("%.2f", trade.entryPrice)} (Current LTP: ${String.format("%.2f", ltp)}). Waiting for price to drop.")
                    saveState()
                } else if (ltp < trade.entryPrice) {
                    trade.status = "PENDING_STOP_BUY"
                    println("[PENDING STOP BUY] $symbol placed at Entry Price: ${String.format("%.2f", trade.entryPrice)} (Current LTP: ${String.format("%.2f", ltp)}). Waiting for price to rise.")
                    saveState()
                } else {
                    val msg = "[TRADE PLACED] $symbol at Entry: ${String.format("%.2f", trade.entryPrice)} | Qty: ${trade.quantity} | LTP: ${String.format("%.2f", ltp)}"
                    println(msg)
                    scope.launch { TelegramNotifier.sendAlert(msg) }

                    trade.status = "ACTIVE"
                    trade.currentTpLevel = 0
                    trade.entryTime = System.currentTimeMillis()
                    saveState()
                }
            }
            "PENDING_LIMIT" -> {
                if (ltp <= trade.entryPrice) {
                    val msg = "[TRADE PLACED (LIMIT TRIGGERED)] $symbol at Entry: ${String.format("%.2f", trade.entryPrice)} | Qty: ${trade.quantity} | LTP: ${String.format("%.2f", ltp)}"
                    println(msg)
                    scope.launch { TelegramNotifier.sendAlert(msg) }

                    trade.status = "ACTIVE"
                    trade.currentTpLevel = 0
                    trade.entryTime = System.currentTimeMillis()
                    saveState()
                }
            }
            "PENDING_STOP_BUY" -> {
                if (ltp >= trade.entryPrice) {
                    val msg = "[TRADE PLACED (STOP BUY TRIGGERED)] $symbol at Entry: ${String.format("%.2f", trade.entryPrice)} | Qty: ${trade.quantity} | LTP: ${String.format("%.2f", ltp)}"
                    println(msg)
                    scope.launch { TelegramNotifier.sendAlert(msg) }

                    trade.status = "ACTIVE"
                    trade.currentTpLevel = 0
                    trade.entryTime = System.currentTimeMillis()
                    saveState()
                }
            }
            "ACTIVE" -> {
                // 1. Check Stop Loss
                if (ltp <= trade.stopLoss) {
                    try {
                        val pnl = (trade.stopLoss - trade.entryPrice) * trade.quantity
                        
                        val exitType = when {
                            trade.currentTpLevel == 0 -> "ACTUAL_SL"
                            trade.currentTpLevel == 1 -> "COST_TO_COST"
                            else -> "TRAILING_SL"
                        }

                        val tag = when (exitType) {
                            "ACTUAL_SL" -> "ACTUAL SL HIT"
                            "COST_TO_COST" -> "COST TO COST (C2C) SL HIT"
                            else -> "TRAILING SL HIT"
                        }

                        val msg = "[$tag] $symbol | Entry: ${String.format("%.2f", trade.entryPrice)} | SL: ${String.format("%.2f", trade.stopLoss)} | TP Level: ${trade.currentTpLevel} | LTP: ${String.format("%.2f", ltp)} | Est P&L: ${String.format("%.2f", pnl)}"
                        println(msg)
                        scope.launch { TelegramNotifier.sendAlert(msg) }

                        TradingJournalService.logTrade(TradeJournalEntry(symbol, trade.entryPrice, trade.stopLoss, trade.quantity, pnl, exitType))

                        trade.status = "EXITED"
                        trade.priceBelowEntrySeen = (ltp < trade.entryPrice)
                        saveState()
                    } catch (e: Exception) {
                        TradingJournalService.logError("Stop loss error for $symbol: ${e.message}")
                    }
                    return
                }

                // 2. Check Targets & Trailing SL
                val targets = trade.targets
                if (trade.currentTpLevel < targets.size) {
                    val nextTarget = targets[trade.currentTpLevel]
                    if (ltp >= nextTarget) {
                        trade.currentTpLevel++
                        if (trade.currentTpLevel == 1) {
                            trade.stopLoss = trade.entryPrice
                        } else {
                            trade.stopLoss = targets[trade.currentTpLevel - 2]
                        }
                        saveState()
                    }
                }
            }
            "EXITED" -> {
                if (!trade.priceBelowEntrySeen && ltp < trade.entryPrice) {
                    trade.priceBelowEntrySeen = true
                    saveState()
                }
                if (trade.priceBelowEntrySeen && ltp >= trade.entryPrice) {
                    val msg = "[RE-ENTRY TRIGGERED] $symbol re-opened at Buy Price: ${String.format("%.2f", trade.entryPrice)} | LTP: ${String.format("%.2f", ltp)}"
                    println(msg)
                    scope.launch { TelegramNotifier.sendAlert(msg) }

                    trade.status = "ACTIVE"
                    trade.stopLoss = gann.stopLoss
                    trade.currentTpLevel = 0
                    trade.entryTime = System.currentTimeMillis()
                    trade.priceBelowEntrySeen = false
                    saveState()
                }
            }
        }
    }

    private fun checkAndSendEodSummary() {
        val allExited = tradesMap.values.all { it.status == "EXITED" }
        if (allExited) {
            val journalReport = TradingJournalService.getJournalReport()
            val summary = buildString {
                append("📊 *END OF DAY P&L SUMMARY REPORT*\n\n")
                var totalPnl = 0.0
                for ((sym, tr) in tradesMap) {
                    val pnl = (tr.stopLoss - tr.entryPrice) * tr.quantity
                    totalPnl += pnl
                    append("• $sym: Entry ${String.format("%.2f", tr.entryPrice)} | Qty ${tr.quantity} | Final SL/Exit ${String.format("%.2f", tr.stopLoss)} | P&L: ${String.format("%.2f", pnl)}\n")
                }
                append("\n💰 *TOTAL P&L: ${String.format("%.2f", totalPnl)}*\n\n")
                append(journalReport)
            }
            println(summary)
            scope.launch { TelegramNotifier.sendAlert(summary) }
        }
    }

    private fun loadState() {
        try {
            if (stateFile.exists()) {
                val jsonStr = stateFile.readText()
                val json = JSONObject(jsonStr)
                for (key in json.keys()) {
                    val obj = json.getJSONObject(key)
                    val trade = TradeState(
                        symbol = obj.getString("symbol"),
                        status = obj.getString("status"),
                        entryPrice = obj.getDouble("entryPrice"),
                        stopLoss = obj.getDouble("stopLoss"),
                        currentTpLevel = obj.getInt("currentTpLevel"),
                        quantity = obj.getInt("quantity"),
                        entryTime = obj.getLong("entryTime"),
                        priceBelowEntrySeen = obj.optBoolean("priceBelowEntrySeen", false)
                    )
                    tradesMap[trade.symbol] = trade
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveState() {
        try {
            val json = JSONObject()
            for ((symbol, trade) in tradesMap) {
                val obj = JSONObject().apply {
                    put("symbol", trade.symbol)
                    put("status", trade.status)
                    put("entryPrice", trade.entryPrice)
                    put("stopLoss", trade.stopLoss)
                    put("currentTpLevel", trade.currentTpLevel)
                    put("quantity", trade.quantity)
                    put("entryTime", trade.entryTime)
                    put("priceBelowEntrySeen", trade.priceBelowEntrySeen)
                }
                json.put(symbol, obj)
            }
            stateFile.writeText(json.toString(4))
        } catch (_: Exception) {}
    }

    fun printStatus() {
        println("\n=== PAPER TRADING ENGINE STATUS ===")
        for ((symbol, trade) in tradesMap) {
            println("Symbol: $symbol | Status: ${trade.status} | Entry: ${String.format("%.2f", trade.entryPrice)} | SL: ${String.format("%.2f", trade.stopLoss)} | Active TP Level: ${trade.currentTpLevel}/10 | Qty: ${trade.quantity}")
        }
        println("=====================================")
    }
}
