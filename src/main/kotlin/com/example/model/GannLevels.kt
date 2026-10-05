package com.example.model

data class GannLevels(
    val previousClose: Double,
    val stopLoss: Double,
    val buyPrice: Double,
    val targets: List<Double>
)
