package com.example.service

import com.example.viewmodel.AuthViewModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

object FyersAuthDaemon {
    suspend fun startDaemon(appId: String, secretKey: String, redirectUri: String, port: Int) {
        val viewModel = AuthViewModel()
        val istZone = ZoneId.of("Asia/Kolkata")
        var authCodeDeferred: CompletableDeferred<String>? = null

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
                } else if (path == "/health" || path == "/ping") {
                    val bytes = "OK".toByteArray(StandardCharsets.UTF_8)
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
                    val htmlStr = "<h1>NIFTY & SENSEX Strangle Algo Daemon</h1><p>Status: Running 24/7</p><p><a href='/api/journal'>View Trading Journal</a></p>"
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
        println("[HTTP Server] Running 24/7 on port $port (API: /api/journal, Health: /health, OAuth: /callback)")

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

            viewModel.clearToken()

            println("[Auth] Access token required for new trading day. Waiting for OAuth callback...")
            viewModel.prepareLoginUrl(appId, redirectUri)
            val loginUrl = viewModel.authState.value.loginUrl
            
            TelegramNotifier.sendAlert("⚠️ [NIFTY + SENSEX OTM4 Strangle] New trading day started. Please login & authorize:\n$loginUrl")

            authCodeDeferred = CompletableDeferred()
            val localDeferred = authCodeDeferred

            val authCode = if (localDeferred != null) {
                try {
                    withTimeout(60 * 60 * 1000L) {
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
                    TelegramNotifier.sendAlert("✅ [Fyers Daemon] Access Token acquired & cached successfully via web callback!")
                } else {
                    TelegramNotifier.sendAlert("❌ [Fyers Daemon] Token exchange failed: ${finalState.errorMessage}")
                }
            } else {
                TelegramNotifier.sendAlert("❌ [Fyers Daemon] Authentication timed out waiting for web callback.")
                delay(10 * 60 * 1000L)
                continue
            }

            if (activeToken != null) {
                try {
                    val runner = SensexNiftyAlgoRunner(viewModel, appId, activeToken)
                    runner.startAlgoSession()

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

            viewModel.clearToken()
            println("[Daemon] Trading day ended. Token cleared. Hard sleeping until 9:00 AM IST...")
            TelegramNotifier.sendAlert("💤 [Fyers Daemon] Trading session ended for today. Token cleared. Hard sleeping until 9:00 AM IST.")

            delay(10 * 60 * 1000L)
        }
    }
}
