package com.roadmemory.app.core

import com.roadmemory.app.data.GearState
import com.roadmemory.app.data.LocationSnapshot
import com.roadmemory.app.data.NaviSnapshot
import com.roadmemory.app.data.TurnSignalState
import com.roadmemory.app.data.VehicleSnapshot
import kotlin.math.abs

/**
 * Driving load score (0..100). The app only speaks when the driver has spare attention.
 *
 *  - turn signal on               +45
 *  - navigation guidance < 300 m  +40  (< 800 m +20)
 *  - hard accel / decel           +35
 *  - wiper on                     +20
 *  - stop-and-go traffic          +15
 *  - night time                   +10
 *  - gear P (parked)              -> 0 (always safe)
 *
 * Tiers: 0..30 FREE (questions, recall), 31..60 LIGHT (short notices only), 61..100 BUSY (silent).
 */
object DrivingLoadEngine {

    enum class Tier { FREE, LIGHT, BUSY }

    data class Result(val score: Int, val tier: Tier, val reasons: List<String>)

    private const val HARD_ACCEL_KMH_PER_SEC = 8.0
    private const val SLOW_TRAFFIC_KMH = 15.0

    fun evaluate(
        vehicle: VehicleSnapshot,
        location: LocationSnapshot,
        navi: NaviSnapshot,
        hourOfDay: Int,
    ): Result {
        if (vehicle.gear == GearState.PARK) {
            return Result(0, Tier.FREE, listOf("주차(P)"))
        }

        var score = 0
        val reasons = mutableListOf<String>()

        if (vehicle.turnSignal != TurnSignalState.OFF) {
            score += 45; reasons += "방향지시등"
        }

        navi.nextGuidanceMeters?.let { d ->
            if (navi.routeActive) {
                when {
                    d < 300 -> { score += 40; reasons += "안내지점 300m" }
                    d < 800 -> { score += 20; reasons += "안내지점 800m" }
                }
            }
        }

        if (abs(location.accelKmhPerSec) >= HARD_ACCEL_KMH_PER_SEC) {
            score += 35; reasons += "급가감속"
        }

        if (vehicle.wiperOn) {
            score += 20; reasons += "와이퍼"
        }

        val moving = location.speedKmh > 1.0
        if (moving && location.speedKmh < SLOW_TRAFFIC_KMH && vehicle.brakePressed) {
            score += 15; reasons += "정체"
        }

        if (hourOfDay >= 20 || hourOfDay < 6) {
            score += 10; reasons += "야간"
        }

        // Fully stopped with brake held (red light): attention is free for a short answer.
        if (!moving && vehicle.brakePressed && vehicle.turnSignal == TurnSignalState.OFF) {
            score = minOf(score, 20)
            reasons += "정차"
        }

        score = score.coerceIn(0, 100)
        val tier = when {
            score <= 30 -> Tier.FREE
            score <= 60 -> Tier.LIGHT
            else -> Tier.BUSY
        }
        return Result(score, tier, reasons)
    }
}
