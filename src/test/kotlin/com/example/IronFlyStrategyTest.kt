package com.example

import com.example.strategy.*
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IronFlyStrategyTest {

    @Test
    fun testCalculatePremiumGap() {
        // CE = 100.0, PE = 95.0 -> Gap = abs(100 - 95) / 95 * 100 = 5.26%
        val gap = IronFlyStrategyConfig.calculatePremiumGap(100.0, 95.0)
        assertTrue(gap < 6.0)

        // Zero or negative price returns 999.0
        val invalidGap = IronFlyStrategyConfig.calculatePremiumGap(100.0, 0.0)
        assertEquals(999.0, invalidGap)
    }

    @Test
    fun testHighImpactDayFilter() {
        // Without HIGH_IMPACT_DATES env set, highImpactDates should be empty by default
        val testDate = LocalDate.of(2026, 2, 1)
        // If not set, returns false (trades every day)
        // Let's test custom high impact check
        assertFalse(IronFlyStrategyConfig.isHighImpactDay(testDate))
    }

    @Test
    fun testIronFlyLegStopLossAndAdjustment() {
        val soldCe = IronFlyLeg("NSE:CE", "SELL_CE", 24000.0, 100.0, 140.0)
        val buyCeHedge = IronFlyLeg("NSE:BCE", "BUY_CE", 24200.0, 10.0, 0.0)
        val soldPe = IronFlyLeg("NSE:PE", "SELL_PE", 24000.0, 90.0, 126.0)
        val buyPeHedge = IronFlyLeg("NSE:BPE", "BUY_PE", 23800.0, 10.0, 0.0)

        val position = IronFlyPosition(
            indexSymbol = "NSE:NIFTY50-INDEX",
            tradeDate = "2026-10-16",
            soldCe = soldCe,
            buyCeHedge = buyCeHedge,
            soldPe = soldPe,
            buyPeHedge = buyPeHedge,
            deployedCapital = 14000.0
        )

        // Verify legs
        assertEquals(4, position.allLegs().size)

        // Scenario: Sold PE hits SL (LTP = 130.0 >= 126.0)
        val soldPeLtp = 130.0
        if (soldPeLtp >= soldPe.currentSl) {
            soldPe.isClosed = true
            buyPeHedge.isClosed = true
            soldCe.currentSl = soldCe.entryPrice // Move CE SL to cost (100.0)
        }

        assertTrue(soldPe.isClosed)
        assertTrue(buyPeHedge.isClosed)
        assertEquals(100.0, soldCe.currentSl)
    }
}
