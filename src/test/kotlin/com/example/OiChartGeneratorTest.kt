package com.example

import com.example.strategy.StrikeOiData
import com.example.util.OiChartGenerator
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.random.Random

class OiChartGeneratorTest {

    @Test
    fun testGenerateOiChartWithRandomValues() {
        val atmStrike = 22850
        val maxOiStrike = 22900
        val strikesData = mutableListOf<StrikeOiData>()

        val strikeInterval = 50
        for (i in -5..5) {
            val strike = atmStrike + (i * strikeInterval)
            val putOi = Random.nextLong(10_000_000L, 40_000_000L)
            val callOi = Random.nextLong(10_000_000L, 45_000_000L)

            strikesData.add(StrikeOiData(strike, "PE", "NSE:NIFTY-PE-$strike", 100.0, putOi, 1000L))
            strikesData.add(StrikeOiData(strike, "CE", "NSE:NIFTY-CE-$strike", 100.0, callOi, 1000L))
        }

        val chartBytes = OiChartGenerator.generateOiChart(
            indexName = "NIFTY",
            dateStr = "2023-10-19",
            atmStrike = atmStrike,
            maxOiStrike = maxOiStrike,
            strikesData = strikesData
        )

        assertNotNull(chartBytes)
        assertTrue(chartBytes.isNotEmpty(), "Generated chart image bytes should not be empty")
        println("✅ Successfully generated OI Chart with random values (${chartBytes.size} bytes).")
    }

    // Uncomment and run to test sending random chart image to Telegram directly
    /*
    @Test
    fun testSendRandomChartToTelegram() = kotlinx.coroutines.runBlocking {
        val atmStrike = 22850
        val maxOiStrike = 22900
        val strikesData = mutableListOf<StrikeOiData>()

        val strikeInterval = 50
        for (i in -5..5) {
            val strike = atmStrike + (i * strikeInterval)
            val putOi = Random.nextLong(10_000_000L, 40_000_000L)
            val callOi = Random.nextLong(10_000_000L, 45_000_000L)

            strikesData.add(StrikeOiData(strike, "PE", "NSE:NIFTY-PE-$strike", 100.0, putOi, 1000L))
            strikesData.add(StrikeOiData(strike, class: String::class.java, ...)) // simplified
        }

        val chartBytes = OiChartGenerator.generateOiChart(
            indexName = "NIFTY",
            dateStr = "2023-10-19",
            atmStrike = atmStrike,
            maxOiStrike = maxOiStrike,
            strikesData = strikesData
        )

        com.example.service.TelegramNotifier.sendPhoto(chartBytes, "🧪 Test OI Chart generated with random values!")
        println("🚀 Test OI Chart sent to Telegram.")
    }
    */
}
