package com.example.strategy

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Candle(
    val timestamp: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
    val epoch: Long = 0L
)

data class IndicatorCandle(
    val timestamp: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val ema4: Double,
    val ema11: Double,
    val ema18: Double,
    val atr: Double,
    val adx: Double,
    val candleBody: Double,
    val candleRange: Double,
    val candleBodyRatio: Double,
    val fullBodyCandle: Boolean,
    val emaDistance: Double,
    val ema11Slope: Double,
    val ema18Slope: Double,
    val ema11SlopeStrength: Double,
    val ema18SlopeStrength: Double,
    val ema11DeltaCurrent: Double,
    val ema11DeltaPrevious: Double,
    val longSetup: Boolean,
    val shortSetup: Boolean,
    val longExit: Boolean,
    val shortExit: Boolean
)

data class EmaTrendConfig(
    val emaFastPeriod: Int = 4,
    val emaMidPeriod: Int = 11,
    val emaSlowPeriod: Int = 18,
    val atrPeriod: Int = 14,
    val adxPeriod: Int = 14,
    val slopeLookback: Int = 3,
    val adxThreshold: Double = 20.0,
    val distanceAtrMultiplier: Double = 0.5,
    val ema11SlopeAtrMultiplier: Double = 0.3,
    val ema18SlopeAtrMultiplier: Double = 0.2,
    val fullBodyMinRatio: Double = 0.5,
    val useAtrTrailingStop: Boolean = true,
    val atrStopMultiplier: Double = 2.0
) {
    init {
        require(emaFastPeriod < emaMidPeriod && emaMidPeriod < emaSlowPeriod) {
            "Require emaFastPeriod < emaMidPeriod < emaSlowPeriod."
        }
        require(fullBodyMinRatio in 0.0..1.0) {
            "fullBodyMinRatio must be between 0.0 and 1.0."
        }
    }
}

data class EmaTrendPositionContext(
    val direction: String, // "LONG" or "SHORT"
    val entryUnderlying: Double,
    val stopUnderlying: Double = 0.0,
    val extremePrice: Double = 0.0,
    val trailingStop: Double = 0.0
)

data class EmaTrendDecision(
    val action: String = "HOLD", // HOLD, ENTER_LONG, ENTER_SHORT, EXIT
    val entryUnderlying: Double = 0.0,
    val stopUnderlying: Double = 0.0,
    val exitReason: String = "",
    val signalTriggered: Boolean = false,
    val updatedPosition: EmaTrendPositionContext? = null
)

data class TradeRecord(
    val direction: String,
    val entryIndex: Int,
    val entryTime: String,
    val entryPrice: Double,
    var exitIndex: Int = -1,
    var exitTime: String = "",
    var exitPrice: Double = 0.0,
    var exitReason: String = "",
    var pnl: Double = 0.0
)

class EmaTrendSignalEngine(private val config: EmaTrendConfig = EmaTrendConfig()) {

    fun minimumHistoryBars(): Int {
        return max(
            max(config.emaFastPeriod, config.emaMidPeriod),
            max(config.emaSlowPeriod, max(config.atrPeriod, config.adxPeriod))
        ) + config.slopeLookback + 2
    }

    fun evaluate(candles: List<IndicatorCandle>, position: EmaTrendPositionContext? = null): EmaTrendDecision {
        if (candles.size < minimumHistoryBars()) {
            return EmaTrendDecision(action = "HOLD")
        }

        val current = candles.last()

        // 1. If position is open, check exit conditions first
        if (position != null) {
            val direction = position.direction.trim().uppercase()
            if (config.useAtrTrailingStop) {
                if (direction == "LONG") {
                    val newExtreme = max(position.extremePrice, current.high)
                    val potentialStop = newExtreme - (config.atrStopMultiplier * current.atr)
                    val newStop = max(position.trailingStop, potentialStop)
                    if (current.low < newStop) {
                        return EmaTrendDecision(action = "EXIT", exitReason = "ATR_TRAILING_STOP")
                    }
                    return EmaTrendDecision(action = "HOLD", updatedPosition = position.copy(extremePrice = newExtreme, trailingStop = newStop))
                } else if (direction == "SHORT") {
                    val newExtreme = min(if (position.extremePrice == 0.0) current.low else position.extremePrice, current.low)
                    val potentialStop = newExtreme + (config.atrStopMultiplier * current.atr)
                    val newStop = if (position.trailingStop == 0.0) potentialStop else min(position.trailingStop, potentialStop)
                    if (current.high > newStop) {
                        return EmaTrendDecision(action = "EXIT", exitReason = "ATR_TRAILING_STOP")
                    }
                    return EmaTrendDecision(action = "HOLD", updatedPosition = position.copy(extremePrice = newExtreme, trailingStop = newStop))
                }
            } else {
                if (direction == "LONG" && current.low < current.ema11) {
                    return EmaTrendDecision(action = "EXIT", exitReason = "EMA11_EXIT")
                }
                if (direction == "SHORT" && current.high > current.ema11) {
                    return EmaTrendDecision(action = "EXIT", exitReason = "EMA11_EXIT")
                }
                return EmaTrendDecision(action = "HOLD", updatedPosition = position)
            }
            return EmaTrendDecision(action = "HOLD", updatedPosition = position)
        }

        // 2. If flat, check entry setups
        if (current.longSetup) {
            val extreme = current.high
            val tStop = current.close - (config.atrStopMultiplier * current.atr)
            return EmaTrendDecision(
                action = "ENTER_LONG",
                entryUnderlying = current.close,
                stopUnderlying = current.ema11,
                signalTriggered = true,
                updatedPosition = EmaTrendPositionContext(
                    direction = "LONG",
                    entryUnderlying = current.close,
                    stopUnderlying = current.ema11,
                    extremePrice = extreme,
                    trailingStop = tStop
                )
            )
        }

        if (current.shortSetup) {
            val extreme = current.low
            val tStop = current.close + (config.atrStopMultiplier * current.atr)
            return EmaTrendDecision(
                action = "ENTER_SHORT",
                entryUnderlying = current.close,
                stopUnderlying = current.ema11,
                signalTriggered = true,
                updatedPosition = EmaTrendPositionContext(
                    direction = "SHORT",
                    entryUnderlying = current.close,
                    stopUnderlying = current.ema11,
                    extremePrice = extreme,
                    trailingStop = tStop
                )
            )
        }

        return EmaTrendDecision(action = "HOLD")
    }
}

object TechnicalIndicators {
    fun calculateEma(values: List<Double>, period: Int): List<Double> {
        val result = MutableList(values.size) { 0.0 }
        if (values.isEmpty()) return result
        val multiplier = 2.0 / (period + 1.0)
        var sum = 0.0
        for (i in values.indices) {
            if (i < period) {
                sum += values[i]
                result[i] = sum / (i + 1)
            } else {
                if (i == period) {
                    var sma = 0.0
                    for (j in 0 until period) sma += values[j]
                    result[period - 1] = sma / period
                }
                result[i] = (values[i] - result[i - 1]) * multiplier + result[i - 1]
            }
        }
        return result
    }

    fun calculateAtr(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int): List<Double> {
        val trs = MutableList(highs.size) { 0.0 }
        for (i in highs.indices) {
            val h = highs[i]
            val l = lows[i]
            val tr = if (i == 0) h - l else max(h - l, max(abs(h - closes[i - 1]), abs(l - closes[i - 1])))
            trs[i] = tr
        }
        return calculateSma(trs, period)
    }

    private fun calculateSma(values: List<Double>, period: Int): List<Double> {
        val result = MutableList(values.size) { 0.0 }
        var windowSum = 0.0
        for (i in values.indices) {
            windowSum += values[i]
            if (i >= period) {
                windowSum -= values[i - period]
            }
            result[i] = if (i < period - 1) windowSum / (i + 1) else windowSum / period
        }
        return result
    }

    fun calculateAdx(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int): List<Double> {
        val atrList = calculateAtr(highs, lows, closes, period)
        val result = MutableList(highs.size) { 25.0 }
        for (i in highs.indices) {
            if (i > 0 && atrList[i] > 0) {
                val move = abs(closes[i] - closes[i - 1])
                val ratio = (move / atrList[i]) * 50.0
                result[i] = min(100.0, max(5.0, ratio + 15.0))
            }
        }
        return result
    }

    fun buildIndicators(candles: List<Candle>, config: EmaTrendConfig): List<IndicatorCandle> {
        val closes = candles.map { it.close }
        val highs = candles.map { it.high }
        val lows = candles.map { it.low }

        val ema4s = calculateEma(closes, config.emaFastPeriod)
        val ema11s = calculateEma(closes, config.emaMidPeriod)
        val ema18s = calculateEma(closes, config.emaSlowPeriod)
        val atrs = calculateAtr(highs, lows, closes, config.atrPeriod)
        val adxs = calculateAdx(highs, lows, closes, config.adxPeriod)

        val result = mutableListOf<IndicatorCandle>()

        for (i in candles.indices) {
            val c = candles[i]
            val body = abs(c.close - c.open)
            val range = abs(c.high - c.low)
            val ratio = if (range > 0) body / range else 0.0
            val fullBody = range > 0 && body >= config.fullBodyMinRatio * range

            val ema4 = ema4s[i]
            val ema11 = ema11s[i]
            val ema18 = ema18s[i]
            val atr = atrs[i]
            val adx = adxs[i]

            val emaDist = ema4 - ema18

            val slopeLookback = config.slopeLookback
            val ema11Slope = if (i >= slopeLookback) ema11 - ema11s[i - slopeLookback] else 0.0
            val ema18Slope = if (i >= slopeLookback) ema18 - ema18s[i - slopeLookback] else 0.0

            val safeAtr = if (atr == 0.0) 1e-8 else atr
            val ema11Strength = ema11Slope / safeAtr
            val ema18Strength = ema18Slope / safeAtr

            val ema11DeltaCurr = if (i >= 1) ema11 - ema11s[i - 1] else 0.0
            val ema11DeltaPrev = if (i >= 2) ema11s[i - 1] - ema11s[i - 2] else 0.0

            val longSetup = (c.close > ema4 && c.close > ema11 && c.close > ema18 &&
                    ema4 > ema11 && ema11 > ema18 &&
                    emaDist > config.distanceAtrMultiplier * atr &&
                    ema18Slope > 0.0 && ema11Slope > 0.0 &&
                    ema18Slope >= config.ema18SlopeAtrMultiplier * atr &&
                    ema11Slope >= config.ema11SlopeAtrMultiplier * atr &&
                    ema11Strength > ema18Strength &&
                    ema11DeltaCurr > ema11DeltaPrev &&
                    adx > config.adxThreshold &&
                    fullBody)

            val shortSetup = (c.close < ema4 && c.close < ema11 && c.close < ema18 &&
                    ema4 < ema11 && ema11 < ema18 &&
                    emaDist < -config.distanceAtrMultiplier * atr &&
                    ema18Slope < 0.0 && ema11Slope < 0.0 &&
                    ema18Slope <= -config.ema18SlopeAtrMultiplier * atr &&
                    ema11Slope <= -config.ema11SlopeAtrMultiplier * atr &&
                    ema11Strength < ema18Strength &&
                    ema11DeltaCurr < ema11DeltaPrev &&
                    adx > config.adxThreshold &&
                    fullBody)

            val longExit = c.low < ema11
            val shortExit = c.high > ema11

            result.add(
                IndicatorCandle(
                    timestamp = c.timestamp,
                    open = c.open,
                    high = c.high,
                    low = c.low,
                    close = c.close,
                    volume = c.volume,
                    ema4 = ema4,
                    ema11 = ema11,
                    ema18 = ema18,
                    atr = atr,
                    adx = adx,
                    candleBody = body,
                    candleRange = range,
                    candleBodyRatio = ratio,
                    fullBodyCandle = fullBody,
                    emaDistance = emaDist,
                    ema11Slope = ema11Slope,
                    ema18Slope = ema18Slope,
                    ema11SlopeStrength = ema11Strength,
                    ema18SlopeStrength = ema18Strength,
                    ema11DeltaCurrent = ema11DeltaCurr,
                    ema11DeltaPrevious = ema11DeltaPrev,
                    longSetup = longSetup,
                    shortSetup = shortSetup,
                    longExit = longExit,
                    shortExit = shortExit
                )
            )
        }
        return result
    }
}

class EmaTrendBacktestEngine(private val config: EmaTrendConfig = EmaTrendConfig()) {
    fun runBacktest(candles: List<Candle>): List<TradeRecord> {
        val indicatorCandles = TechnicalIndicators.buildIndicators(candles, config)
        val engine = EmaTrendSignalEngine(config)
        val trades = mutableListOf<TradeRecord>()
        var activePosition: EmaTrendPositionContext? = null
        var currentTrade: TradeRecord? = null

        for (i in indicatorCandles.indices) {
            val slice = indicatorCandles.subList(0, i + 1)
            val decision = engine.evaluate(slice, activePosition)

            if (activePosition == null) {
                if (decision.action == "ENTER_LONG") {
                    activePosition = EmaTrendPositionContext(
                        direction = "LONG",
                        entryUnderlying = decision.entryUnderlying,
                        stopUnderlying = decision.stopUnderlying
                    )
                    currentTrade = TradeRecord(
                        direction = "LONG",
                        entryIndex = i,
                        entryTime = indicatorCandles[i].timestamp,
                        entryPrice = decision.entryUnderlying
                    )
                } else if (decision.action == "ENTER_SHORT") {
                    activePosition = EmaTrendPositionContext(
                        direction = "SHORT",
                        entryUnderlying = decision.entryUnderlying,
                        stopUnderlying = decision.stopUnderlying
                    )
                    currentTrade = TradeRecord(
                        direction = "SHORT",
                        entryIndex = i,
                        entryTime = indicatorCandles[i].timestamp,
                        entryPrice = decision.entryUnderlying
                    )
                }
            } else {
                if (decision.action == "EXIT") {
                    val exitPrice = indicatorCandles[i].ema11
                    currentTrade?.let { trade ->
                        trade.exitIndex = i
                        trade.exitTime = indicatorCandles[i].timestamp
                        trade.exitPrice = exitPrice
                        trade.exitReason = decision.exitReason
                        trade.pnl = if (trade.direction == "LONG") exitPrice - trade.entryPrice else trade.entryPrice - exitPrice
                        trades.add(trade)
                    }
                    activePosition = null
                    currentTrade = null
                }
            }
        }
        return trades
    }
}
