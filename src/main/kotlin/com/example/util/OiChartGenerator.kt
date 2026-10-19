package com.example.util

import com.example.strategy.StrikeOiData
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max

object OiChartGenerator {
    fun generateOiChart(
        indexName: String,
        dateStr: String,
        atmStrike: Int,
        maxOiStrike: Int,
        strikesData: List<StrikeOiData>
    ): ByteArray {
        val width = 1200
        val height = 700
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2 = image.createGraphics()

        // Enable anti-aliasing
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Background (Dark trading theme)
        g2.color = Color(30, 34, 45) // #1e222d
        g2.fillRect(0, 0, width, height)

        // Title
        g2.color = Color.WHITE
        g2.font = Font("SansSerif", Font.BOLD, 22)
        g2.drawString("Open Interest (OI) Analysis - $indexName ($dateStr)", 40, 50)

        g2.font = Font("SansSerif", Font.PLAIN, 14)
        g2.color = Color(150, 160, 180)
        g2.drawString("ATM Strike: $atmStrike | Max OI Strike: $maxOiStrike", 40, 75)

        // Legend
        g2.color = Color(38, 166, 154) // Green (Put OI)
        g2.fillRect(40, 95, 15, 15)
        g2.color = Color.WHITE
        g2.drawString("Put OI (PE)", 65, 108)

        g2.color = Color(239, 83, 80) // Red (Call OI)
        g2.fillRect(170, 95, 15, 15)
        g2.color = Color.WHITE
        g2.drawString("Call OI (CE)", 195, 108)

        // Group strikes
        val sortedStrikes = strikesData.groupBy { it.strike }.toSortedMap()
        if (sortedStrikes.isEmpty()) {
            g2.dispose()
            val baos = ByteArrayOutputStream()
            ImageIO.write(image, "png", baos)
            return baos.toByteArray()
        }

        val maxOiVal = max(1L, sortedStrikes.values.flatten().maxOfOrNull { it.openInterest } ?: 1L)

        // Chart area
        val chartLeft = 80
        val chartTop = 140
        val chartWidth = width - 120
        val chartHeight = height - 200

        // Draw axes
        g2.color = Color(60, 70, 90)
        g2.drawLine(chartLeft, chartTop + chartHeight, chartLeft + chartWidth, chartTop + chartHeight)
        g2.drawLine(chartLeft, chartTop, chartLeft, chartTop + chartHeight)

        // Draw horizontal grid lines & Y-axis labels
        val gridSteps = 5
        for (i in 0..gridSteps) {
            val y = chartTop + (chartHeight * i / gridSteps)
            val valAtY = maxOiVal * (gridSteps - i) / gridSteps
            g2.color = Color(50, 60, 75)
            g2.drawLine(chartLeft, y, chartLeft + chartWidth, y)

            g2.color = Color(160, 170, 190)
            g2.font = Font("SansSerif", Font.PLAIN, 11)
            val label = formatOi(valAtY)
            g2.drawString(label, chartLeft - 65, y + 4)
        }

        // Draw bars for each strike
        val strikeCount = sortedStrikes.size
        val groupWidth = chartWidth.toDouble() / max(1, strikeCount)
        var index = 0

        for ((strike, entries) in sortedStrikes) {
            val xGroupCenter = chartLeft + (index * groupWidth) + (groupWidth / 2)
            val barWidth = max(4.0, (groupWidth * 0.35))

            val putEntry = entries.find { it.optionType == "PE" }
            val callEntry = entries.find { it.optionType == "CE" }

            val putOi = putEntry?.openInterest ?: 0L
            val callOi = callEntry?.openInterest ?: 0L

            val putBarHeight = ((putOi.toDouble() / maxOiVal) * chartHeight).toInt()
            val callBarHeight = ((callOi.toDouble() / maxOiVal) * chartHeight).toInt()

            val baselineY = chartTop + chartHeight

            // Highlight ATM or Max OI strike background
            if (strike == atmStrike || strike == maxOiStrike) {
                g2.color = Color(45, 55, 75, 120)
                g2.fillRect((xGroupCenter - groupWidth / 2).toInt(), chartTop, groupWidth.toInt(), chartHeight)
            }

            // Put Bar (Green)
            g2.color = Color(38, 166, 154)
            g2.fillRect((xGroupCenter - barWidth - 2).toInt(), baselineY - putBarHeight, barWidth.toInt(), putBarHeight)

            // Call Bar (Red)
            g2.color = Color(239, 83, 80)
            g2.fillRect((xGroupCenter + 2).toInt(), baselineY - callBarHeight, barWidth.toInt(), callBarHeight)

            // Strike label
            g2.color = if (strike == atmStrike) Color(100, 220, 255) else if (strike == maxOiStrike) Color(255, 200, 100) else Color(180, 190, 210)
            g2.font = Font("SansSerif", if (strike == atmStrike || strike == maxOiStrike) Font.BOLD else Font.PLAIN, 11)
            
            val strikeStr = strike.toString()
            g2.drawString(strikeStr, (xGroupCenter - 15).toInt(), baselineY + 20)

            index++
        }

        g2.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return baos.toByteArray()
    }

    private fun formatOi(oi: Long): String {
        return when {
            oi >= 10_000_000 -> String.format("%.1fCr", oi / 10_000_000.0)
            oi >= 100_000 -> String.format("%.1fL", oi / 100_000.0)
            oi >= 1_000 -> String.format("%.1fk", oi / 1_000.0)
            else -> oi.toString()
        }
    }
}
