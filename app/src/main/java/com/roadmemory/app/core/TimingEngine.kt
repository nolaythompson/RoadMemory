package com.roadmemory.app.core

import com.roadmemory.app.data.TripMode

/**
 * Decides *when* the car may talk. A question needs the FREE tier to hold for a few
 * seconds (not a single quiet sample), a cooldown since the last utterance, and a
 * per-trip budget so daily trips never feel chatty.
 */
class TimingEngine(
    private val clock: () -> Long = System::currentTimeMillis,
    /** 1.0 in the car; 0.1 for the demo scenario. */
    timeScale: Double = 1.0,
) {
    private val stableFreeMs = (STABLE_FREE_MS * timeScale).toLong()
    private val cooldownMs = (COOLDOWN_MS * timeScale).toLong()
    private val warmupMs = (WARMUP_MS * timeScale).toLong()

    private var freeSince: Long? = null
    private var lastSpokeAt: Long = Long.MIN_VALUE / 2
    private var questionsThisTrip = 0
    private var tripStartedAt: Long = 0L

    fun onTripStarted() {
        questionsThisTrip = 0
        tripStartedAt = clock()
        lastSpokeAt = Long.MIN_VALUE / 2
        freeSince = null
    }

    /** Feed every new load evaluation. */
    fun onLoad(result: DrivingLoadEngine.Result) {
        val now = clock()
        freeSince = if (result.tier == DrivingLoadEngine.Tier.FREE) freeSince ?: now else null
    }

    fun canAskQuestion(mode: TripMode): Boolean {
        val now = clock()
        val since = freeSince ?: return false
        val budget = if (mode == TripMode.DAILY) DAILY_QUESTIONS else TRAVEL_QUESTIONS
        return questionsThisTrip < budget &&
            now - since >= stableFreeMs &&
            now - lastSpokeAt >= cooldownMs &&
            now - tripStartedAt >= warmupMs
    }

    /** Recall ("last year, here...") is shorter, so it only needs FREE + cooldown. */
    fun canRecall(): Boolean {
        val now = clock()
        val since = freeSince ?: return false
        return now - since >= stableFreeMs && now - lastSpokeAt >= cooldownMs
    }

    fun onQuestionAsked() {
        questionsThisTrip++
        lastSpokeAt = clock()
    }

    fun onSpoke() {
        lastSpokeAt = clock()
    }

    companion object {
        const val STABLE_FREE_MS = 5_000L
        const val COOLDOWN_MS = 90_000L
        const val WARMUP_MS = 60_000L
        const val DAILY_QUESTIONS = 1
        const val TRAVEL_QUESTIONS = 6
    }
}
