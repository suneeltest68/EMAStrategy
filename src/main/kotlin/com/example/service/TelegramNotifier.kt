package com.example.service

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TelegramNotifier {
    private val dotenv = dotenv {
        ignoreIfMissing = true
    }

    private val botToken: String
        get() = System.getenv("TELEGRAM_BOT_TOKEN") ?: dotenv["TELEGRAM_BOT_TOKEN"] ?: "YOUR_BOT_TOKEN_HERE"

    private val chatId: String
        get() = System.getenv("TELEGRAM_CHAT_ID") ?: dotenv["TELEGRAM_CHAT_ID"] ?: "YOUR_CHAT_ID_HERE"

    private val isEnabled: Boolean
        get() = (System.getenv("TELEGRAM_ENABLED") ?: dotenv["TELEGRAM_ENABLED"])?.toBoolean() ?: true

    suspend fun sendAlert(message: String) {
        if (!isEnabled || botToken == "YOUR_BOT_TOKEN_HERE" || chatId == "YOUR_CHAT_ID_HERE") {
            println("[Telegram Alert - Mock] $message")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val urlString = "https://api.telegram.org/bot$botToken/sendMessage"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.doOutput = true

                val payload = "chat_id=$chatId&text=" + URLEncoder.encode(message, StandardCharsets.UTF_8.toString())

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    println("Failed to send Telegram alert. Response code: $responseCode")
                }
            } catch (e: Exception) {
                println("Telegram alert exception: ${e.message}")
            }
        }
    }

    suspend fun sendPhoto(imageBytes: ByteArray, caption: String) {
        if (!isEnabled || botToken == "YOUR_BOT_TOKEN_HERE" || chatId == "YOUR_CHAT_ID_HERE") {
            println("[Telegram Photo - Mock] Caption: $caption")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val boundary = "Boundary_" + System.currentTimeMillis()
                val urlString = "https://api.telegram.org/bot$botToken/sendPhoto"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                conn.outputStream.use { outputStream ->
                    val writer = OutputStreamWriter(outputStream, StandardCharsets.UTF_8)
                    
                    // chatId field
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                    writer.write("$chatId\r\n")

                    // caption field
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
                    writer.write("$caption\r\n")

                    // photo file part
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"photo\"; filename=\"oi_chart.png\"\r\n")
                    writer.write("Content-Type: image/png\r\n\r\n")
                    writer.flush()

                    outputStream.write(imageBytes)
                    outputStream.flush()

                    writer.write("\r\n--$boundary--\r\n")
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    println("Failed to send Telegram photo. Response code: $responseCode")
                }
            } catch (e: Exception) {
                println("Telegram photo exception: ${e.message}")
            }
        }
    }
}
