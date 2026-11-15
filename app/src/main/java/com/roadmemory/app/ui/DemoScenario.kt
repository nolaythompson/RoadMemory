package com.roadmemory.app.ui

import android.os.Handler
import android.os.Looper
import com.roadmemory.app.core.TripSession
import com.roadmemory.app.data.GearState
import com.roadmemory.app.data.TurnSignalState

/**
 * Scripted school-run for the emulator, where real vehicle signals are mostly static.
 * It drives the same adapters the real SDK callbacks use, so every engine runs for real:
 *
 *   0s  D gear, kids in the back, last year's memory seeded on the route
 *   2s  left turn + guidance at 250 m  -> load BUSY, the car stays silent
 *   8s  red light, brake held          -> hard decel (LIGHT), then FREE -> today's question
 *  18s  moving again; ~30s passes the old spot -> same-road recall
 *  34s  P gear at school                -> place learned + milestone
 */
class DemoScenario(
    private val session: TripSession,
    private val currentPhase: () -> TripSession.Phase,
    private val onFinished: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var second = 0
    var running = false
        private set

    private var lat = START_LAT
    private var lon = START_LON
    private var answeredAt = -1

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            tick(second)
            second++
            if (second <= END_SECOND) main.postDelayed(this, 1000) else finish()
        }
    }

    fun start() {
        if (running) return
        running = true
        second = 0
        answeredAt = -1
        lat = START_LAT
        lon = START_LON
        session.setDemoTiming(true)
        if (!session.store.recordingConsent) session.setConsent(true)
        // A memory from last year, a little further along this road.
        session.seedPastMemory(
            daysAgo = 365,
            lat = START_LAT,
            lon = START_LON + STEP_LON * 60,
            text = "여기 지날 때마다 강아지 구름 찾자고 했어요",
        )
        session.navi.inject { it.copy(routeActive = true, destinationName = "초등학교", nextGuidanceMeters = 900.0) }
        main.post(step)
    }

    fun cancel() {
        if (!running) return
        finish()
    }

    private fun finish() {
        running = false
        main.removeCallbacks(step)
        session.setDemoTiming(false)
        onFinished()
    }

    private fun move(stepLon: Double) {
        lon += stepLon
        session.location.accept(lat, lon, System.currentTimeMillis())
    }

    private fun tick(s: Int) {
        when (s) {
            0 -> session.vehicle.inject {
                it.copy(gear = GearState.DRIVE, passengerRearLeft = true, passengerRearRight = true, brakePressed = false)
            }
            2 -> {
                session.vehicle.inject { it.copy(turnSignal = TurnSignalState.LEFT) }
                session.navi.inject { it.copy(nextGuidanceMeters = 250.0, nextGuidanceText = "좌회전") }
            }
            8 -> {
                session.vehicle.inject { it.copy(turnSignal = TurnSignalState.OFF, brakePressed = true) }
                session.navi.inject { it.copy(nextGuidanceMeters = 1200.0, nextGuidanceText = "직진") }
            }
            18 -> session.vehicle.inject { it.copy(brakePressed = false) }
            32 -> session.vehicle.inject { it.copy(brakePressed = true) }
            34 -> {
                session.navi.inject { it.copy(nextGuidanceMeters = null) }
                session.vehicle.inject { it.copy(gear = GearState.PARK) }
            }
        }

        // Movement: drive, stop at the light (8..17), drive, stop at school (32+).
        val stopped = s in 8..17 || s >= 32
        move(
            when {
                stopped -> 0.0
                s >= 18 -> STEP_LON * 2
                else -> STEP_LON
            },
        )

        // If speech recognition is unavailable on the emulator, answer like a child would.
        if (currentPhase() == TripSession.Phase.LISTENING && answeredAt < 0) answeredAt = s
        if (answeredAt >= 0 && s == answeredAt + 4 && currentPhase() == TripSession.Phase.LISTENING) {
            session.submitAnswer(SAMPLE_ANSWER)
        }
    }

    companion object {
        const val START_LAT = 37.5000
        const val START_LON = 127.0000
        /** ~10.6 m per second, about 38 km/h. */
        const val STEP_LON = 0.00012
        const val END_SECOND = 38
        const val SAMPLE_ANSWER = "오늘 체육 시간에 피구했는데 내가 마지막까지 살아남았어!"
    }
}
