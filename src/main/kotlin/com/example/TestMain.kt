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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Duration
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
            println("[Auth] Open this URL in your browser to authorize:")
            println(loginUrl)
            TelegramNotifier.sendAlert("⚠️ [Fyers Daemon] New trading day started. Please login & authorize:\n$loginUrl")

            val scanner = Scanner(System.`in`)
            print("\nPaste either the full redirect URL or the 'auth_code': ")
            var input = scanner.nextLine().trim()
            while (input.isEmpty()) {
                print("Input cannot be empty. Please paste the redirect URL or auth_code: ")
                input = scanner.nextLine().trim()
            }

            val authCode = AuthUtils.extractAuthCode(input)

            println("[Auth] Exchanging received auth code for access token...")
            viewModel.authenticateWithAuthCode(appId, secretKey, authCode)
            val finalState = viewModel.authState.value
            if (finalState.accessToken != null) {
                activeToken = finalState.accessToken
                println("\nSUCCESS! Access Token acquired and cached.")
                TelegramNotifier.sendAlert("✅ [Fyers Daemon] Access Token acquired & cached successfully!")
            } else {
                println("\n❌ FAILED: ${finalState.errorMessage}")
                TelegramNotifier.sendAlert("❌ [Fyers Daemon] Token exchange failed: ${finalState.errorMessage}")
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
