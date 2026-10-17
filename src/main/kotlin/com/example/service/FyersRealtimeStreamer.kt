package com.example.service

import com.tts.`in`.model.FyersClass
import com.tts.`in`.websocket.FyersSocket
import com.tts.`in`.websocket.FyersSocketDelegate
import `in`.tts.hsjavalib.ChannelModes
import org.json.JSONObject

class FyersRealtimeStreamer(
    private val clientId: String,
    private val accessToken: String,
    private val onTickCallback: (symbol: String, ltp: Double) -> Unit
) : FyersSocketDelegate {

    private val fyersSocket = FyersSocket(3)

    init {
        val fyersClass = FyersClass.getInstance()
        fyersClass.clientId = clientId
        fyersClass.accessToken = accessToken
        fyersSocket.webSocketDelegate = this
    }

    fun connectAndSubscribe(symbols: List<String>) {
        try {
            println("🔌 Connecting to Fyers WebSocket (Lite Mode)...")
            fyersSocket.ConnectHSM(ChannelModes.LITE)
            fyersSocket.SubscribeData(symbols)
            println("📡 Subscribed to symbols via WebSocket: $symbols")
        } catch (e: Exception) {
            println("❌ WebSocket connection/subscription error: ${e.message}")
        }
    }

    fun updateSubscription(symbols: List<String>) {
        try {
            fyersSocket.SubscribeData(symbols)
            println("📡 Updated WebSocket subscription: $symbols")
        } catch (e: Exception) {
            println("❌ WebSocket update subscription error: ${e.message}")
        }
    }

    override fun OnIndex(index: JSONObject?) {
        try {
            if (index != null) {
                val symbol = index.optString("symbol", index.optString("n", ""))
                val ltp = index.optDouble("ltp", index.optDouble("lp", 0.0))
                if (symbol.isNotEmpty() && ltp > 0.0) {
                    onTickCallback(symbol, ltp)
                }
            }
        } catch (e: Exception) {
            println("WebSocket OnIndex Error: ${e.message}")
        }
    }

    override fun OnScrips(scrips: JSONObject?) {
        try {
            if (scrips != null) {
                val symbol = scrips.optString("symbol", scrips.optString("n", ""))
                val ltp = scrips.optDouble("ltp", scrips.optDouble("lp", 0.0))
                if (symbol.isNotEmpty() && ltp > 0.0) {
                    onTickCallback(symbol, ltp)
                }
            }
        } catch (e: Exception) {
            println("WebSocket OnScrips Error: ${e.message}")
        }
    }

    override fun OnDepth(depths: JSONObject?) {}
    override fun OnOrder(orders: JSONObject?) {}
    override fun OnTrade(trades: JSONObject?) {}
    override fun OnPosition(positions: JSONObject?) {}
    
    override fun OnOpen(status: String?) {
        println("🟢 Fyers WebSocket Connected: $status")
    }

    override fun OnClose(status: String?) {
        println("🔴 Fyers WebSocket Closed: $status")
    }

    override fun OnError(error: JSONObject?) {
        println("⚠️ Fyers WebSocket Error: $error")
    }

    override fun OnMessage(message: JSONObject?) {
        try {
            if (message != null) {
                val symbol = message.optString("symbol", message.optString("n", ""))
                val ltp = message.optDouble("ltp", message.optDouble("lp", 0.0))
                if (symbol.isNotEmpty() && ltp > 0.0) {
                    onTickCallback(symbol, ltp)
                }
            }
        } catch (_: Exception) {}
    }
}
