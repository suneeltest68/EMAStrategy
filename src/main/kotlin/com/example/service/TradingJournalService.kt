package com.example.service

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class TradeJournalEntry(
    val symbol: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Int,
    val pnl: Double,
    val exitReason: String,
    val date: String = LocalDate.now().toString(),
    val timestamp: Long = System.currentTimeMillis()
)

object TradingJournalService {
    private val journalFile = File("trading_journal.json")

    fun logTrade(entry: TradeJournalEntry) {
        try {
            val root = loadJournalRoot()
            val tradesArray = root.optJSONArray("trades") ?: JSONArray()
            
            val tradeObj = JSONObject().apply {
                put("symbol", entry.symbol)
                put("entryPrice", entry.entryPrice)
                put("exitPrice", entry.exitPrice)
                put("quantity", entry.quantity)
                put("pnl", entry.pnl)
                put("exitReason", entry.exitReason)
                put("date", entry.date)
                put("timestamp", entry.timestamp)
            }
            tradesArray.put(tradeObj)
            root.put("trades", tradesArray)

            // Update cumulative stats
            updateStats(root, entry.pnl)

            journalFile.writeText(root.toString(4))
        } catch (e: Exception) {
            println("[Journal Error] Failed to log trade: ${e.message}")
        }
    }

    fun logError(issue: String) {
        try {
            val root = loadJournalRoot()
            val errorsArray = root.optJSONArray("errors") ?: JSONArray()
            val errorObj = JSONObject().apply {
                put("issue", issue)
                put("timestamp", System.currentTimeMillis())
                put("date", LocalDate.now().toString())
            }
            errorsArray.put(errorObj)
            root.put("errors", errorsArray)
            journalFile.writeText(root.toString(4))
        } catch (e: Exception) {
            println("[Journal Error] Failed to log error: ${e.message}")
        }
    }

    private fun loadJournalRoot(): JSONObject {
        return try {
            if (journalFile.exists()) {
                JSONObject(journalFile.readText())
            } else {
                JSONObject().apply {
                    put("trades", JSONArray())
                    put("errors", JSONArray())
                    put("stats", JSONObject().apply {
                        put("cumulativePnl", 0.0)
                        put("totalTrades", 0)
                        put("winningTrades", 0)
                        put("losingTrades", 0)
                        put("maxDrawdown", 0.0)
                        put("peakPnl", 0.0)
                    })
                }
            }
        } catch (_: Exception) {
            JSONObject().apply {
                put("trades", JSONArray())
                put("errors", JSONArray())
                put("stats", JSONObject().apply {
                    put("cumulativePnl", 0.0)
                    put("totalTrades", 0)
                    put("winningTrades", 0)
                    put("losingTrades", 0)
                    put("maxDrawdown", 0.0)
                    put("peakPnl", 0.0)
                })
            }
        }
    }

    private fun updateStats(root: JSONObject, tradePnl: Double) {
        val stats = root.getJSONObject("stats")
        val totalTrades = stats.getInt("totalTrades") + 1
        val winningTrades = stats.getInt("winningTrades") + if (tradePnl > 0) 1 else 0
        val losingTrades = stats.getInt("losingTrades") + if (tradePnl < 0) 1 else 0
        val cumulativePnl = stats.getDouble("cumulativePnl") + tradePnl
        
        var peakPnl = stats.getDouble("peakPnl")
        if (cumulativePnl > peakPnl) {
            peakPnl = cumulativePnl
        }
        
        val currentDrawdown = peakPnl - cumulativePnl
        var maxDrawdown = stats.getDouble("maxDrawdown")
        if (currentDrawdown > maxDrawdown) {
            maxDrawdown = currentDrawdown
        }

        stats.put("totalTrades", totalTrades)
        stats.put("winningTrades", winningTrades)
        stats.put("losingTrades", losingTrades)
        stats.put("cumulativePnl", cumulativePnl)
        stats.put("peakPnl", peakPnl)
        stats.put("maxDrawdown", maxDrawdown)
    }

    fun getJournalReport(): String {
        val root = loadJournalRoot()
        val stats = root.getJSONObject("stats")
        val trades = root.getJSONArray("trades")
        val errors = root.getJSONArray("errors")

        val cumulativePnl = stats.getDouble("cumulativePnl")
        val totalTrades = stats.getInt("totalTrades")
        val winningTrades = stats.getInt("winningTrades")
        val losingTrades = stats.getInt("losingTrades")
        val winRate = if (totalTrades > 0) (winningTrades.toDouble() / totalTrades) * 100 else 0.0
        val maxDrawdown = stats.getDouble("maxDrawdown")

        var grossProfit = 0.0
        var grossLoss = 0.0
        for (i in 0 until trades.length()) {
            val t = trades.getJSONObject(i)
            val pnl = t.getDouble("pnl")
            if (pnl > 0) grossProfit += pnl else grossLoss += kotlin.math.abs(pnl)
        }
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) 999.0 else 0.0

        return buildString {
            append("📈 *TRADING JOURNAL & PERFORMANCE STATS*\n\n")
            append("💰 *Cumulative P&L:* ${String.format("%.2f", cumulativePnl)}\n")
            append("🎯 *Win Rate:* ${String.format("%.1f", winRate)}% ($winningTrades W / $losingTrades L out of $totalTrades Trades)\n")
            append("⚖️ *Profit Factor:* ${String.format("%.2f", profitFactor)}\n")
            append("📉 *Max Drawdown:* ${String.format("%.2f", maxDrawdown)}\n")
            
            if (errors.length() > 0) {
                append("\n⚠️ *Recorded Issues / Errors:* ${errors.length()}\n")
            }
        }
    }
}
