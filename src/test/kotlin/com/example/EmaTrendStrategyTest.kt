package com.example

import com.example.strategy.Candle
import com.example.strategy.EmaTrendBacktestEngine
import com.example.strategy.EmaTrendConfig
import com.example.strategy.EmaTrendSignalEngine
import com.example.strategy.TechnicalIndicators
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmaTrendStrategyTest {

    @Test
    fun testIndicatorBuildingAndBacktest() {
        // Generate sample candles simulating a trending market
        val candles = mutableListOf<Candle>()
        var price = 20000.0
        for (i in 0..150) {
            price += 15.0 // steady uptrend
            candles.add(
                Candle(
                    timestamp = "2026-10-01 09:${String.format("%02d", i % 60)}",
                    open = price - 5.0,
                    high = price + 20.0,
                    low = price - 10.0,
                    close = price + 15.0,
                    volume = 100000.0
                )
            )
        }

        val config = EmaTrendConfig()
        val indicatorCandles = TechnicalIndicators.buildIndicators(candles, config)
        assertEquals(candles.size, indicatorCandles.size)

        val backtestEngine = EmaTrendBacktestEngine(config)
        val trades = backtestEngine.runBacktest(candles)
        assertNotNull(trades)
    }

    @Test
    fun testSignalEngineWarmup() {
        val config = EmaTrendConfig()
        val engine = EmaTrendSignalEngine(config)
        assertTrue(engine.minimumHistoryBars() > 0)
    }
}
