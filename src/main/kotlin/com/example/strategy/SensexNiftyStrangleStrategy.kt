package com.example.strategy

import com.example.service.FyersRealtimeStreamer
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
    var overallMtmSlBreached: Boolean = false,
    var streamer: FyersRealtimeStreamer? = null
) {
    fun getTotalMtm(lotSize: Double): Double {
        var mtmPts = 0.0
        if (callLeg != null) {
            if (callLeg!!.status == "ACTIVE") {
                mtmPts += (callLeg!!.entryPrice - callLeg!!.currentPrice)
            } else {
                mtmPts += callLeg!!.realizedPnl
            }
        }
        if (putLeg != null) {
            if (putLeg!!.status == "ACTIVE") {
                mtmPts += (putLeg!!.entryPrice - putLeg!!.currentPrice)
            } else {
                mtmPts += putLeg!!.realizedPnl
            }
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
