package com.example.strategy

import java.time.DayOfWeek
import java.time.LocalDate

data class StrangleLeg(
    val symbol: String,
    val type: String, // "CE" or "PE"
    val strike: Int,
    var entryPrice: Double, // Entry price updated on re-entry
    var currentPrice: Double,
    var stopLossPrice: Double, // Entry * 1.40 for short straddle
    var targetPrice: Double,   // Entry * 0.30 for short straddle
    var status: String = "ACTIVE", // ACTIVE, TARGET_HIT, SL_HIT, EXITED
    var targetReentryCount: Int = 0,
    var realizedPnl: Double = 0.0
)

data class IndexStrangleState(
    val indexName: String, // "NIFTY" or "SENSEX"
    var callLeg: StrangleLeg? = null,
    var putLeg: StrangleLeg? = null,
    var isSquareOff: Boolean = false,
    var overallMtmSlBreached: Boolean = false
) {
    fun getTotalMtm(callLtp: Double, putLtp: Double, lotSize: Double): Double {
        var mtmPts = 0.0
        if (callLeg != null && callLeg!!.status == "ACTIVE") {
            callLeg!!.currentPrice = callLtp
            mtmPts += (callLeg!!.entryPrice - callLtp)
        } else if (callLeg != null) {
            mtmPts += callLeg!!.realizedPnl
        }

        if (putLeg != null && putLeg!!.status == "ACTIVE") {
            putLeg!!.currentPrice = putLtp
            mtmPts += (putLeg!!.entryPrice - putLtp)
        } else if (putLeg != null) {
            mtmPts += putLeg!!.realizedPnl
        }
        return mtmPts * lotSize
    }
}

object SensexNiftyStrangleScheduler {
    fun shouldRunNifty(date: LocalDate): Boolean {
        val day = date.dayOfWeek
        return day == DayOfWeek.MONDAY || day == DayOfWeek.TUESDAY || day == DayOfWeek.FRIDAY
    }

    fun shouldRunSensex(date: LocalDate): Boolean {
        val day = date.dayOfWeek
        return day == DayOfWeek.TUESDAY || day == DayOfWeek.WEDNESDAY || day == DayOfWeek.THURSDAY
    }
}
