package com.example

import com.example.model.GannLevels
import com.example.service.PaperTradingEngine
import org.junit.Test
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PaperTradingEngineTest {

    private val testTime = LocalTime.of(10, 0) // 10:00 AM IST (market open hours)

    @Test
    fun testLimitOrderInitializationAndTrigger() {
        val gann = GannLevels(
            previousClose = 100.0,
            stopLoss = 85.0,
            buyPrice = 100.0,
            targets = listOf(110.0, 120.0, 130.0)
        )
        val engine = PaperTradingEngine(mapOf("NIFTY" to gann), mapOf("NIFTY" to 50))

        // 1. Tick with price above entry (115 > 100) -> should be PENDING_LIMIT
        engine.onTick("NIFTY", 115.0, testTime)
        val trade1 = engine.getTrade("NIFTY")
        assertNotNull(trade1)
        assertEquals("PENDING_LIMIT", trade1.status)

        // 2. Tick with price coming back down to entry (100 <= 100) -> should trigger ACTIVE
        engine.onTick("NIFTY", 100.0, testTime)
        val trade2 = engine.getTrade("NIFTY")
        assertNotNull(trade2)
        assertEquals("ACTIVE", trade2.status)
    }

    @Test
    fun testStopBuyOrderInitializationAndTrigger() {
        val gann = GannLevels(
            previousClose = 100.0,
            stopLoss = 85.0,
            buyPrice = 100.0,
            targets = listOf(110.0, 120.0, 130.0)
        )
        val engine = PaperTradingEngine(mapOf("BANKNIFTY" to gann), mapOf("BANKNIFTY" to 30))

        // 1. Tick with price below entry (90 < 100) -> should be PENDING_STOP_BUY
        engine.onTick("BANKNIFTY", 90.0, testTime)
        val trade1 = engine.getTrade("BANKNIFTY")
        assertNotNull(trade1)
        assertEquals("PENDING_STOP_BUY", trade1.status)

        // 2. Tick with price rising to entry (100 >= 100) -> should trigger ACTIVE
        engine.onTick("BANKNIFTY", 100.0, testTime)
        val trade2 = engine.getTrade("BANKNIFTY")
        assertNotNull(trade2)
        assertEquals("ACTIVE", trade2.status)
    }

    @Test
    fun testActualSlHit() {
        val gann = GannLevels(
            previousClose = 100.0,
            stopLoss = 85.0,
            buyPrice = 100.0,
            targets = listOf(110.0, 120.0, 130.0)
        )
        val engine = PaperTradingEngine(mapOf("SENSEX" to gann), mapOf("SENSEX" to 20))

        // Activate trade at 100
        engine.onTick("SENSEX", 100.0, testTime)
        assertEquals("ACTIVE", engine.getTrade("SENSEX")?.status)

        // Hit Stop Loss at 85
        engine.onTick("SENSEX", 85.0, testTime)
        assertEquals("EXITED", engine.getTrade("SENSEX")?.status)
    }

    @Test
    fun testCostToCostAndTrailingSlProgression() {
        val gann = GannLevels(
            previousClose = 100.0,
            stopLoss = 85.0,
            buyPrice = 100.0,
            targets = listOf(110.0, 120.0, 130.0)
        )
        val engine = PaperTradingEngine(mapOf("NIFTY" to gann), mapOf("NIFTY" to 50))

        // Activate trade
        engine.onTick("NIFTY", 100.0, testTime)

        // Hit TP1 (110) -> TSL moves to entry (100)
        engine.onTick("NIFTY", 110.0, testTime)
        assertEquals(1, engine.getTrade("NIFTY")?.currentTpLevel)
        assertEquals(100.0, engine.getTrade("NIFTY")?.stopLoss)

        // Hit TP2 (120) -> TSL moves to TP1 (110)
        engine.onTick("NIFTY", 120.0, testTime)
        assertEquals(2, engine.getTrade("NIFTY")?.currentTpLevel)
        assertEquals(110.0, engine.getTrade("NIFTY")?.stopLoss)
    }

    @Test
    fun testReEntryPullbackProtection() {
        val gann = GannLevels(
            previousClose = 100.0,
            stopLoss = 85.0,
            buyPrice = 100.0,
            targets = listOf(110.0, 120.0, 130.0)
        )
        val engine = PaperTradingEngine(mapOf("NIFTY" to gann), mapOf("NIFTY" to 50))

        // Activate and hit TP2 (SL = 110)
        engine.onTick("NIFTY", 100.0, testTime)
        engine.onTick("NIFTY", 110.0, testTime)
        engine.onTick("NIFTY", 120.0, testTime)

        // Hit Trailing SL at 105 (Exits while LTP = 105 > entry 100)
        engine.onTick("NIFTY", 105.0, testTime) // Hits SL (110)
        assertEquals("EXITED", engine.getTrade("NIFTY")?.status)

        // Try to tick at 102 (above entry 100) -> should NOT re-enter immediately because price has not dipped below entry yet
        engine.onTick("NIFTY", 102.0, testTime)
        assertEquals("EXITED", engine.getTrade("NIFTY")?.status)

        // Now drop below entry (e.g. 95 < 100) -> should record priceBelowEntrySeen = true
        engine.onTick("NIFTY", 95.0, testTime)

        // Now rise back to entry (100 >= 100) -> should trigger RE-ENTRY
        engine.onTick("NIFTY", 100.0, testTime)
        assertEquals("ACTIVE", engine.getTrade("NIFTY")?.status)
    }
}
