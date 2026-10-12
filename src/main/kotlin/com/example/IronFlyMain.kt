/**
 * ============================================================================
 * Weekly-Expiry Iron Fly (Option Selling) Strategy & Fyers Authentication Runner
 * ============================================================================
 */
package com.example

import com.example.viewmodel.AuthViewModel
import com.example.service.IronFlyTradingRunner
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
    println("🚀 WEEKLY-EXPIRY IRON FLY STRATEGY - FYERS AUTH & DAEMON")
    println("==================================================")

    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"] ?: ""
    val secretKey = System.getenv("FYERS_SECRET_KEY") ?: dotenv["FYERS_SECRET_KEY"] ?: ""
    val redirectUri = System.getenv("FYERS_REDIRECT_URI") ?: dotenv["FYERS_REDIRECT_URI"] ?: "https://trade.fyers.in/api-login/redirect-uri/index.html"
    val portStr = System.getenv("PORT") ?: "8080"
    val port = portStr.toIntOrNull() ?: 8080

    if (appId.isEmpty() || secretKey.isEmpty() || redirectUri.isEmpty()) {
        println("❌ Error: Missing required Fyers environment variables (FYERS_APP_ID, FYERS_SECRET_KEY, FYERS_REDIRECT_URI).")
        return@runBlocking
    }

    var authCodeDeferred: CompletableDeferred<String>? = null

    // Start HTTP Server for OAuth Callback
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path?.removeSuffix("/") ?: ""
        try {
            if (path == "/callback") {
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
                val htmlStr = "<h1>Iron Fly Strategy Daemon</h1><p>Status: Running 24/7</p>"
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
    println("[HTTP Server] Running on port $port (OAuth callback: /callback)")

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
        println("🌅 [Daily Routine] Starting Fyers Authentication...")
        println("--------------------------------------------------")

        viewModel.clearToken()
        viewModel.prepareLoginUrl(appId, redirectUri)
        val loginUrl = viewModel.authState.value.loginUrl

        println("[Auth] Please open this URL in your browser to authorize Fyers login:")
        println(loginUrl)
        TelegramNotifier.sendAlert("⚠️ [IronFly Daemon] Fyers login required. Please authorize:\n$loginUrl")

        authCodeDeferred = CompletableDeferred()
        val localDeferred = authCodeDeferred

        val authCode = if (localDeferred != null) {
            try {
                withTimeout(15 * 60 * 1000L) {
                    localDeferred.await()
                }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        authCodeDeferred = null

        var activeToken: String? = null
        if (authCode != null) {
            println("[Auth] Exchanging received auth code for access token...")
            viewModel.authenticateWithAuthCode(appId, secretKey, authCode)
            val finalState = viewModel.authState.value
            if (finalState.accessToken != null) {
                activeToken = finalState.accessToken
                println("✅ [Auth] Access Token acquired successfully!")
                TelegramNotifier.sendAlert("✅ [IronFly Daemon] Access Token acquired successfully!")
            } else {
                println("❌ [Auth] Token exchange failed: ${finalState.errorMessage}")
                TelegramNotifier.sendAlert("❌ [IronFly Daemon] Token exchange failed: ${finalState.errorMessage}")
            }
        } else {
            println("❌ [Auth] Authentication timed out waiting for web callback.")
            TelegramNotifier.sendAlert("❌ [IronFly Daemon] Authentication timed out.")
            delay(10 * 60 * 1000L)
            continue
        }

        if (activeToken != null) {
            try {
                val runner = IronFlyTradingRunner(viewModel, appId, activeToken, expiryIndex = 1)
                runner.startLiveSession()

                val now = ZonedDateTime.now(istZone)
                val marketCloseTimeToday = now.withHour(15).withMinute(35).withSecond(0).withNano(0)
                if (now.isBefore(marketCloseTimeToday)) {
                    val waitDuration = Duration.between(now, marketCloseTimeToday).toMillis()
                    println("[Daemon] Iron Fly session active. Waiting until market close (${waitDuration / 1000 / 60} mins)...")
                    delay(waitDuration)
                }
            } catch (e: Exception) {
                println("[Daemon Error] Iron Fly session exception: ${e.message}")
                TelegramNotifier.sendAlert("❌ [IronFly Daemon] Error during execution: ${e.message}")
            }
        }

        viewModel.clearToken()
        println("[Daemon] Trading session ended. Token cleared. Sleeping until next morning...")
        TelegramNotifier.sendAlert("💤 [IronFly Daemon] Trading session ended for today.")
        delay(10 * 60 * 1000L)
    }
}
