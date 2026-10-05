package com.example.service

import com.example.model.GannLevels
import kotlin.math.sqrt
import kotlin.math.round

object GannCalculator {
    fun calculate(pdc: Double): GannLevels {
        val sqCal = sqrt(pdc)
        val bsl = round(((sqCal - 0.0625) * (sqCal - 0.0625)) * 100.0) / 100.0
        val bt2 = round(((sqCal + 0.5) * (sqCal + 0.5)) * 100.0) / 100.0
        
        val targetsList = mutableListOf<Double>()
        for (i in 1..50) {
            val t = (sqCal + (i * 0.5 + 0.5)) * (sqCal + (i * 0.5 + 0.5))
            targetsList.add(round(t * 100.0) / 100.0)
        }

        return GannLevels(
            previousClose = pdc,
            stopLoss = bsl,
            buyPrice = bt2,
            targets = targetsList
        )
    }

    fun printTable(title: String, gann: GannLevels) {
        println("\n--- GANN BUY LEVELS up to TP50 [ $title ] ---")
        println("Previous Day Close (PDC) : ${gann.previousClose}")
        println("Stop Loss (SL)           : ${round(gann.stopLoss * 100) / 100}")
        println("Buy At or Above (Entry)  : ${round(gann.buyPrice * 100) / 100}")
        gann.targets.forEachIndexed { index, target ->
            println("Target ${index + 1} (TP${index + 1})".padEnd(25) + " : ${round(target * 100) / 100}")
        }
        println("--------------------------------------------------")
    }
}
