package com.example

import com.example.service.FyersAuthDaemon
import com.example.service.TelegramNotifier
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking

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

    println("==================================================")
    println("🚀 FYERS STRANGLE ALGO - 24/7 DAEMON ENTRY")
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

    FyersAuthDaemon.startDaemon(appId, secretKey, redirectUri, port)
}
