package com.example.strategy

data class StrikeOiData(
    val strike: Int,
    val optionType: String, // "CE" or "PE"
    val symbol: String,
    val ltp: Double,
    val openInterest: Long,
    val changeInOi: Long
)

data class MorningStrategySignal(
    val action: String, // "BULL_PUT_SPREAD", "BEAR_CALL_SPREAD", "NO_TRADE"
    val maxOiStrike: Int,
    val maxOi: Long,
    val triggerStrike: Int,
    val triggerStrikeOi: Long,
    val thresholdOi: Long,
    val dominantType: String, // "PUT" or "CALL"
    val triggerSymbol: String,
    val reason: String
)
