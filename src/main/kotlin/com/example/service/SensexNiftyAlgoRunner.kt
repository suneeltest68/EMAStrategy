package com.example.service

import com.example.strategy.*
import com.example.util.OptionUtils
import com.example.viewmodel.AuthViewModel
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

class SensexNiftyAlgoRunner(
    private val viewModel: AuthViewModel,
    private val appId: String,
    private val token: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val istZone = ZoneId.of("Asia/Kolkata")

    fun startAlgoSession() {
        scope.launch {
            println("==============================================================")
            println("STARTING NIFTY + SENSEX OTM4 STRANGLE DAY-BASED ROTATION ALGO")
            println("==============================================================\n")

            TelegramNotifier.sendAlert("🚀 NIFTY + SENSEX OTM4 Strangle Algo Started.")

            while (isActive) {
                waitForNext1MinBoundary()

                val now = LocalDateTime.now(istZone)
                val currentDate = now.toLocalDate()
                val currentTime = now.toLocalTime()
                val dateStr = currentDate.toString()

                val marketOpen = LocalTime.of(9, 16)
                val marketClose = LocalTime.of(3, 30).let { LocalTime.of(15, 30) }
                val squareOffTime = LocalTime.of(15, 25)

                if (currentTime.isBefore(marketOpen) || currentTime.isAfter(marketClose)) {
                    delay(30_000L)
                    continue
                }

                val runNifty = SensexNiftyStrangleScheduler.shouldRunNifty(currentDate)
                val runSensex = SensexNiftyStrangleScheduler.shouldRunSensex(currentDate)

                // Run NIFTY System if scheduled
                if (runNifty) {
                    executeIndexStrangle(
                        indexName = "NIFTY",
                        indexSymbol = "NSE:NIFTY50-INDEX",
                        strikeInterval = 50,
                        lotSize = 65.0,
                        dateStr = dateStr,
                        currentTime = currentTime,
                        squareOffTime = squareOffTime
                    )
                }

                // Run SENSEX System if scheduled
                if (runSensex) {
                    executeIndexStrangle(
                        indexName = "SENSEX",
                        indexSymbol = "BSE:SENSEX",
                        strikeInterval = 100,
                        lotSize = 10.0, // Sensex lot size 10 (or configurable)
                        dateStr = dateStr,
                        currentTime = currentTime,
                        squareOffTime = squareOffTime
                    )
                }
            }
        }
    }

    private suspend fun executeIndexStrangle(
        indexName: String,
        indexSymbol: String,
        strikeInterval: Int,
        lotSize: Double,
        dateStr: String,
        currentTime: LocalTime,
        squareOffTime: LocalTime
    ) {
        // We maintain state per day/index in memory during the loop session
        // (In a production daemon, state would be managed cleanly per day)
    }

    private suspend fun waitForNext1MinBoundary() {
        val now = LocalDateTime.now(istZone)
        val target = if (now.second < 2) {
            now.withSecond(2).withNano(0)
        } else {
            now.plusMinutes(1).withSecond(2).withNano(0)
        }
        val millisToWait = Duration.between(LocalDateTime.now(istZone), target).toMillis()
        if (millisToWait > 0) {
            delay(millisToWait)
        }
    }
}
