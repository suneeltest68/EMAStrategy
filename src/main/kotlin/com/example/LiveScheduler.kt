package com.example

import com.example.viewmodel.AuthViewModel
import com.example.util.AuthUtils
import com.example.service.TelegramNotifier
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Scanner

/**
 * Separate scheduler file that authenticates and triggers fetchDataAndRunDerivativeBacktest from mybacktest.kt
 * every 5 minutes at exact marks (e.g. 9:10:02, 9:15:02) during market hours.
 * 
 * Existing files (mybacktest.kt, Main.kt, LiveTradingRunner.kt, AuthUtils.kt) are untouched.
 */
private var lastTriggeredMinute = -1

fun isFiveMinuteBoundaryTime(): Boolean {
    val now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
    val minute = now.minute
    val second = now.second
    if (minute % 5 == 0 && second == 2 && minute != lastTriggeredMinute) {
        lastTriggeredMinute = minute
        return true
    }
    return false
}

fun main() = runBlocking {
    val dotenv = dotenv { ignoreIfMissing = true }
    val viewModel = AuthViewModel()
    val scanner = Scanner(System.`in`)
    val istZone = ZoneId.of("Asia/Kolkata")

    println("=== NIFTY EMA 5-Min Scheduled Runner ===")
    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"] ?: ""
    val secretKey = System.getenv("FYERS_SECRET_KEY") ?: dotenv["FYERS_SECRET_KEY"] ?: ""
    val redirectUri = System.getenv("FYERS_REDIRECT_URI") ?: dotenv["FYERS_REDIRECT_URI"] ?: "https://trade.fyers.in/api-login/redirect-uri/index.html"

    if (appId.isEmpty() || secretKey.isEmpty()) {
        println("⚠️ FYERS_APP_ID or FYERS_SECRET_KEY not set in environment or .env file.")
        return@runBlocking
    }

    var activeToken = viewModel.getCachedToken()
    if (activeToken != null) {
        println("\n[Cache] Found valid cached Access Token! Skipping login flow.")
    } else {
        viewModel.prepareLoginUrl(appId, redirectUri)
        println("\n[1] Open this URL in your browser to authorize:")
        println(viewModel.authState.value.loginUrl)

        print("\nPaste either the full redirect URL or the 'auth_code': ")
        var input = scanner.nextLine().trim()
        while (input.isEmpty()) {
            print("Input cannot be empty. Please paste the redirect URL or auth_code: ")
            input = scanner.nextLine().trim()
        }

        val authCode = AuthUtils.extractAuthCode(input)

        println("\n[2] Exchanging auth code for access token...")
        viewModel.authenticateWithAuthCode(appId, secretKey, authCode)

        val finalState = viewModel.authState.value
        if (finalState.accessToken != null) {
            println("\nSUCCESS! Access Token acquired and cached.")
            activeToken = finalState.accessToken
        } else {
            println("\n❌ FAILED: ${finalState.errorMessage}")
            return@runBlocking
        }
    }

    val token = activeToken ?: return@runBlocking

    println("🚀 [Scheduler] Started 5-minute scheduled runner for mybacktest.kt...")
    TelegramNotifier.sendAlert("🚀 [Fyers Scheduler] 5-minute backtest runner started successfully.")

    val marketOpen = LocalTime.of(9, 15)
    val marketClose = LocalTime.of(15, 30)

    while (true) {
        val nowTime = LocalTime.now(istZone)
        if (nowTime.isAfter(marketClose)) {
            println("[Market Closed] Trading hours ended for today. Waiting...")
            delay(java.time.Duration.ofHours(1).toMillis())
            continue
        }

        if (isFiveMinuteBoundaryTime()) {
            val currentTime = LocalTime.now(istZone)
            if (!currentTime.isBefore(marketOpen) && !currentTime.isAfter(marketClose)) {
                println("🔔 [5-Min Trigger] Calling fetchDataAndRunDerivativeBacktest at $currentTime...")
                try {
                    fetchDataAndRunDerivativeBacktest(viewModel, appId, token)
                } catch (e: Exception) {
                    val errMsg = "❌ [Scheduler Error] Error in 5-min execution: ${e.message}"
                    println(errMsg)
                    TelegramNotifier.sendAlert(errMsg)
                }
            }
        }

        delay(1000L) // Poll every 1 second
    }
}
