package com.example

import com.example.service.IronFlyTradingRunner
import com.example.service.TelegramNotifier
import com.example.util.AuthUtils
import com.example.viewmodel.AuthViewModel
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import java.io.File
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

    val dotenv = dotenv {
        directory = if (File(".env").exists()) "." else ".."
        ignoreIfMissing = true
    }
    val viewModel = AuthViewModel()
    val scanner = Scanner(System.`in`)

    println("==================================================")
    println("🚀 MORNING STRATEGY - LOCAL LIVE RUNNER")
    println("==================================================")

    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"]
    val secretKey = System.getenv("FYERS_SECRET_KEY") ?: dotenv["FYERS_SECRET_KEY"]
    val redirectUri = System.getenv("FYERS_REDIRECT_URI") ?: dotenv["FYERS_REDIRECT_URI"] ?: "https://trade.fyers.in/api-login/redirect-uri/index.html"

    if (appId.isNullOrEmpty() || secretKey.isNullOrEmpty()) {
        println("❌ Error: Missing FYERS_APP_ID or FYERS_SECRET_KEY in .env file.")
        return@runBlocking
    }

    var token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"]

    if (token.isNullOrEmpty()) {
        println("\n[Auth] No valid Access Token found. Initiating login flow...")
        viewModel.prepareLoginUrl(appId, redirectUri)
        val loginUrl = viewModel.authState.value.loginUrl
        println("1. Open this URL in your browser to authorize:\n$loginUrl")
        print("2. Paste either the full redirect URL or the 'auth_code': ")

        var input = scanner.nextLine().trim()
        while (input.isEmpty()) {
            print("Input cannot be empty. Please paste the redirect URL or auth_code: ")
            input = scanner.nextLine().trim()
        }

        val authCode = AuthUtils.extractAuthCode(input)
        println("\n[Auth] Exchanging auth code for access token...")
        viewModel.authenticateWithAuthCode(appId, secretKey, authCode)

        token = viewModel.authState.value.accessToken
        if (token.isNullOrEmpty()) {
            println("❌ Authentication failed: ${viewModel.authState.value.errorMessage}")
            return@runBlocking
        }
        println("✅ Successfully acquired Access Token!")
    } else {
        println("[Auth] Using valid cached or environment Access Token.")
    }

    val runner = IronFlyTradingRunner(viewModel, appId, token)
    runner.startLiveSession()

    while (true) {
        kotlinx.coroutines.delay(60_000L)
    }
}
