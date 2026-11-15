package com.roadmemory.app.core

import com.roadmemory.app.data.GearState
import com.roadmemory.app.data.LocationSnapshot
import com.roadmemory.app.data.NaviSnapshot
import com.roadmemory.app.data.TripMode
import com.roadmemory.app.data.TurnSignalState
import com.roadmemory.app.data.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginesTest {

    private val moving = LocationSnapshot(37.5, 127.0, speedKmh = 40.0, timestampMillis = 1L)

    @Test
    fun parkedIsAlwaysFree() {
        val r = DrivingLoadEngine.evaluate(
            VehicleSnapshot(gear = GearState.PARK, turnSignal = TurnSignalState.LEFT, wiperOn = true),
            moving, NaviSnapshot(routeActive = true, nextGuidanceMeters = 100.0), hourOfDay = 23,
        )
        assertEquals(0, r.score)
        assertEquals(DrivingLoadEngine.Tier.FREE, r.tier)
    }

    @Test
    fun turnSignalNearGuidanceIsBusy() {
        val r = DrivingLoadEngine.evaluate(
            VehicleSnapshot(gear = GearState.DRIVE, turnSignal = TurnSignalState.RIGHT),
            moving, NaviSnapshot(routeActive = true, nextGuidanceMeters = 200.0), hourOfDay = 9,
        )
        assertEquals(85, r.score)
        assertEquals(DrivingLoadEngine.Tier.BUSY, r.tier)
    }

    @Test
    fun timingNeedsStableFreeWarmupAndBudget() {
        var now = 0L
        val t = TimingEngine(clock = { now })
        val free = DrivingLoadEngine.Result(0, DrivingLoadEngine.Tier.FREE, emptyList())
        t.onTripStarted()
        t.onLoad(free)
        assertFalse(t.canAskQuestion(TripMode.DAILY)) // warm-up
        now = TimingEngine.WARMUP_MS + TimingEngine.STABLE_FREE_MS
        assertTrue(t.canAskQuestion(TripMode.DAILY))
        t.onQuestionAsked()
        now += TimingEngine.COOLDOWN_MS * 2
        assertFalse(t.canAskQuestion(TripMode.DAILY)) // one question per daily trip
        assertTrue(t.canAskQuestion(TripMode.TRAVEL))
    }

    @Test
    fun placeLearnerMergesNearbyArrivalsAndGivesMilestones() {
        val learner = PlaceLearner(mutableListOf())
        val first = learner.recordArrival(37.5, 127.0, "초등학교")
        assertTrue(first.isNew)
        assertEquals("초등학교에 처음 온 날", first.milestone)
        repeat(8) { learner.recordArrival(37.5003, 127.0002, null) }
        val tenth = learner.recordArrival(37.5001, 127.0001, null)
        assertFalse(tenth.isNew)
        assertEquals("초등학교 10번째 방문", tenth.milestone)
    }

    @Test
    fun privacyGuardMasksAndDetectsCommands() {
        assertEquals("[전화번호]로 전화해", PrivacyGuard.mask("010-1234-5678로 전화해"))
        assertEquals(PrivacyGuard.Command.DELETE_LAST, PrivacyGuard.detectCommand("방금 거 지워 줘"))
        assertEquals(PrivacyGuard.Command.PAUSE, PrivacyGuard.detectCommand("지금은 기록하지 마"))
        assertEquals(PrivacyGuard.Command.NONE, PrivacyGuard.detectCommand("오늘 피구했어"))
    }

    @Test
    fun promptAlwaysReturnsQuestion() {
        val q = PromptEngine().pick(
            PromptEngine.Context(TripMode.DAILY, 8, 2, rainy = true, hasRearPassenger = true, 0, "수영장"),
        )
        assertNotNull(q)
        assertTrue(q.isNotBlank())
    }

    @Test
    fun petOnBoardPrefersPetQuestions() {
        val ctx = PromptEngine.Context(TripMode.DAILY, 17, 3, rainy = false, hasRearPassenger = false, 3, null, petOnBoard = true)
        val engine = PromptEngine(kotlin.random.Random(1))
        val picks = (1..30).map { engine.pick(ctx) }
        assertTrue(picks.count { it in PromptEngine.PET } > 15)
    }
}
