package dev.zz.laptracker

import kotlin.math.abs
import kotlin.math.max

data class RideAnalysis(
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val maxAcceleration: Double,
    val maxBraking: Double,
    val hardAccelerations: Int,
    val hardBrakings: Int,
    val accelerationEvents: Int,
    val estimatedKmPerLitre: Double,
    val estimatedFuelLitres: Double,
    val confidence: String
)

object FuelModel {
    private const val BASE_CD70_KM_L = 55.0

    fun analyse(points: List<TrackPoint>, distanceM: Double, durationMs: Long): RideAnalysis {
        val valid = points.filter { it.speed != null && it.accuracy != null && (it.accuracy ?: 999f) <= 35f }
        var maxA = 0.0; var maxB = 0.0; var hardA = 0; var hardB = 0; var events = 0
        var sumSpeed = 0.0; var speedCount = 0
        for (p in valid) { sumSpeed += (p.speed ?: 0f) * 3.6; speedCount++ }
        for (i in 1 until valid.size) {
            val a = valid[i - 1]; val b = valid[i]
            val dt = (b.time - a.time) / 1000.0
            if (dt <= 0.25 || dt > 5.0) continue
            val dv = ((b.speed ?: 0f) - (a.speed ?: 0f)).toDouble() / dt
            if (abs(dv) >= 0.8) events++
            if (dv > maxA) maxA = dv
            if (-dv > maxB) maxB = -dv
            if (dv >= 2.0) hardA++
            if (dv <= -2.5) hardB++
        }
        val avg = if (speedCount > 0) sumSpeed / speedCount else if (durationMs > 0) distanceM / (durationMs / 1000.0) * 3.6 else 0.0
        val maxSpeed = valid.maxOfOrNull { (it.speed ?: 0f) * 3.6 } ?: 0.0
        var kmL = BASE_CD70_KM_L
        kmL *= when { avg < 15 -> 0.84; avg < 25 -> 0.91; avg < 40 -> 1.00; avg < 55 -> 0.96; avg < 70 -> 0.90; else -> 0.80 }
        val km = distanceM / 1000.0
        if (km > 0) kmL *= max(0.78, 1.0 - (hardA / km) * 0.006 - (hardB / km) * 0.002)
        val confidence = when { valid.size >= 30 && km >= 3 -> "Medium"; valid.size >= 10 -> "Low"; else -> "Very low" }
        return RideAnalysis(avg, maxSpeed, maxA, maxB, hardA, hardB, events, kmL, if (kmL > 0) km / kmL else 0.0, confidence)
    }
}
