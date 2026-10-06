/**
 * ============================================================================
 * NIFTY EMA 4/11/18 Trend-Following Strategy & Weekly Option Backtest Runner
 * ============================================================================
 *
 * Workflow:
 * 1. Fetch NIFTY 50 Index 5-min historical data for signal generation.
 * 2. When NIFTY triggers an entry between September 30th and October 5th, 2026:
 *    - Resolve weekly option symbol strictly via live Fyers option chain lookup.
 *    - Fetch 1-minute historical candle data for that specific derivative option contract.
 * 3. When NIFTY exits:
 *    - Match the 1-minute derivative candle at exit time to calculate exact option P&L.
 * 4. Present all executed trades in a clean Markdown summary table.
 * ============================================================================
 */
package com.example

import com.example.viewmodel.AuthViewModel
import com.example.service.LiveTradingRunner
import com.example.service.TelegramNotifier
import com.example.util.AuthUtils
import com.sun.net.httpserver.HttpServer
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Duration
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Scanner

fun main() = runBlocking {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val stackTrace = throwable.stackTraceToString().take(1500)
        val errorMessage = "🚨 [FATAL CRASH] Thread '${thread.name}' crashed:\n${throwable.message}\n\n$stackTrace"
        println(errorMessage)
        try {
            runBlocking {
                TelegramNotifier.sendAlert(errorMessage)
            }
        } catch (_: Exception) {}
    }

    val dotenv = dotenv { ignoreIfMissing = true }
    val viewModel = AuthViewModel()
    val istZone = ZoneId.of("Asia/Kolkata")

    println("==================================================")
    println("🚀 FYERS EMA STRATEGY - RAILWAY DAEMON (24/7 WEB & LIVE TRADE)")
    println("==================================================")

    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"] ?: ""
    val secretKey = System.getenv("FYERS_SECRET_KEY") ?: dotenv["FYERS_SECRET_KEY"] ?: ""
    val redirectUri = System.getenv("FYERS_REDIRECT_URI") ?: dotenv["FYERS_REDIRECT_URI"] ?: "https://trade.fyers.in/api-login/redirect-uri/index.html"
    val portStr = System.getenv("PORT") ?: "8080"
    val port = portStr.toIntOrNull() ?: 8080

    if (appId.isEmpty() || secretKey.isEmpty() || redirectUri.isEmpty()) {
        println("❌ Error: Missing required Fyers environment variables.")
        return@runBlocking
    }

    var authCodeDeferred: CompletableDeferred<String>? = null

    // Start 24/7 Background HTTP Server for OAuth Callback & Journal Dashboard API
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path?.removeSuffix("/") ?: ""
        try {
            if (path == "/api/journal" || path == "/journal") {
                val file = File("trading_journal.json")
                val jsonStr = if (file.exists()) file.readText() else "{\"trades\":[],\"stats\":{}}"
                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val bytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else if (path == "/api/active-trades") {
                val file = File("active_trades.json")
                val jsonStr = if (file.exists()) file.readText() else "{}"
                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val bytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else if (path == "/callback") {
                val query = exchange.requestURI.query ?: ""
                val queryParams = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) {
                        URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to 
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                    } else {
                        "" to ""
                    }
                }
                val code = queryParams["s_code"] ?: queryParams["auth_code"] ?: queryParams["code"]
                val responseHtml = if (!code.isNullOrEmpty()) {
                    "<html><body style='font-family: Arial; text-align: center; margin-top: 50px;'><h1 style='color: green;'>✅ Fyers Authentication Successful!</h1><p>Authorization code received. You can now close this tab.</p></body></html>"
                } else {
                    "<html><body style='font-family: Arial; text-align: center; margin-top: 50px;'><h1 style='color: red;'>❌ Authentication Failed</h1><p>No authorization code found in callback query.</p></body></html>"
                }
                exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                val bytes = responseHtml.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }

                if (!code.isNullOrEmpty()) {
                    authCodeDeferred?.complete(code)
                }
            } else {
                val htmlStr = "<h1>NIFTY EMA Strategy Daemon</h1><p>Status: Running 24/7</p><p><a href='/api/journal'>View Trading Journal</a></p>"
                exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                val bytes = htmlStr.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } catch (e: Exception) {
            val err = "Error: ${e.message}".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(500, err.size.toLong())
            exchange.responseBody.use { it.write(err) }
        }
    }
    server.setExecutor(null)
    server.start()
    println("[HTTP Server] Running 24/7 on port $port (API: /api/journal, OAuth: /callback)")

    var isFirstRun = true

    while (true) {
        if (!isFirstRun) {
            val now = ZonedDateTime.now(istZone)
            val targetTimeToday = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
            val nextRun = if (now.isAfter(targetTimeToday)) targetTimeToday.plusDays(1) else targetTimeToday
            val delayMillis = Duration.between(now, nextRun).toMillis()
            println("\n[Daemon] Next daily startup scheduled for: $nextRun (in ${delayMillis / 1000 / 60} minutes)")
            delay(delayMillis)
        } else {
            println("\n[Daemon] First startup: Running trading session immediately...")
            isFirstRun = false
        }

        println("\n--------------------------------------------------")
        println("🌅 [Daily Routine] Starting Live Trading Session...")
        println("--------------------------------------------------")

        var activeToken: String? = viewModel.getCachedToken()
        if (activeToken != null) {
            println("[Auth] Found valid cached Access Token! Skipping login flow.")
        } else {
            viewModel.prepareLoginUrl(appId, redirectUri)
            val loginUrl = viewModel.authState.value.loginUrl
            println("\n[Auth] Access token required for new trading day.")
            println("[Auth] Open this URL in your browser to authorize:\n$loginUrl")
            TelegramNotifier.sendAlert("⚠️ [Fyers Daemon] New trading day started. Please login & authorize:\n$loginUrl")

            print("\n[Local Run Option] Paste either the full redirect URL or the 'auth_code' (or press Enter to wait for web callback): ")
            val scanner = Scanner(System.`in`)
            val input = try {
                if (System.console() != null || System.`in`.available() > 0) scanner.nextLine().trim() else ""
            } catch (_: Exception) {
                ""
            }

            val authCode = if (input.isNotEmpty()) {
                AuthUtils.extractAuthCode(input)
            } else {
                println("[Auth] Waiting for web callback on port $port...")
                authCodeDeferred = CompletableDeferred()
                val localDeferred = authCodeDeferred
                val code = try {
                    withTimeout(60 * 60 * 1000L) {
                        localDeferred.await()
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    authCodeDeferred = null
                }
                code
            }

            if (!authCode.isNullOrEmpty()) {
                println("[Auth] Exchanging received auth code for access token...")
                viewModel.authenticateWithAuthCode(appId, secretKey, authCode)
                val finalState = viewModel.authState.value
                if (finalState.accessToken != null) {
                    activeToken = finalState.accessToken
                    TelegramNotifier.sendAlert("✅ [Fyers Daemon] Access Token acquired & cached successfully!")
                } else {
                    TelegramNotifier.sendAlert("❌ [Fyers Daemon] Token exchange failed: ${finalState.errorMessage}")
                }
            } else {
                TelegramNotifier.sendAlert("❌ [Fyers Daemon] Authentication timed out or cancelled.")
                delay(10 * 60 * 1000L)
                continue
            }
        }

        if (activeToken != null) {
            try {
                val runner = LiveTradingRunner(viewModel, appId, activeToken, expiryIndex = 1)
                runner.startLiveSession()

                // Keep daemon alive during market hours until 3:35 PM IST today
                val now = ZonedDateTime.now(istZone)
                val marketCloseTimeToday = now.withHour(15).withMinute(35).withSecond(0).withNano(0)
                if (now.isBefore(marketCloseTimeToday)) {
                    val waitDuration = Duration.between(now, marketCloseTimeToday).toMillis()
                    println("[Daemon] Live trading session active. Waiting until market close at 3:35 PM IST (${waitDuration / 1000 / 60} minutes remaining)...")
                    delay(waitDuration)
                }
            } catch (e: Exception) {
                println("[Daemon Error] Live trading session exception: ${e.message}")
                TelegramNotifier.sendAlert("❌ [Fyers Daemon] Error during live execution: ${e.message}")
            }
        }

        // End of day cleanup: Clear token and hard sleep until next morning at 9:00 AM IST
//        viewModel.clearToken()
        println("[Daemon] Trading day ended. Token cleared. Hard sleeping until 9:00 AM IST...")
        TelegramNotifier.sendAlert("💤 [Fyers Daemon] Trading session ended for today. Token cleared. Hard sleeping until 9:00 AM IST.")

        delay(10 * 60 * 1000L)
    }
}
