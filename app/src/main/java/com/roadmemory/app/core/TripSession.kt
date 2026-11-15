package com.roadmemory.app.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.roadmemory.app.data.GearState
import com.roadmemory.app.data.LocationSnapshot
import com.roadmemory.app.data.MemoryStore
import com.roadmemory.app.data.Milestone
import com.roadmemory.app.data.Moment
import com.roadmemory.app.data.NaviSnapshot
import com.roadmemory.app.data.Trip
import com.roadmemory.app.data.TripMode
import com.roadmemory.app.data.VehicleSnapshot
import com.roadmemory.app.sdk.LocationTracker
import com.roadmemory.app.sdk.NaviTracker
import com.roadmemory.app.sdk.VehicleSignals
import com.roadmemory.app.sdk.VoiceIO
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Orchestrates one family trip:
 *
 *   gear P -> D           : trip starts (daily mode unless travel mode is on)
 *   load FREE, stable     : "today's question" via TTS, then STT listens once
 *   answer                : masked, summarized, pinned to the current place
 *   same road, old memory : recall is spoken once
 *   arrival / gear P      : place learned, milestone (first / Nth visit)
 */
class TripSession(context: Context, private val ui: (UiState) -> Unit) :
    VoiceIO.Callback, NaviTracker.Listener {

    enum class Phase { IDLE, DRIVING, ASKING, LISTENING, SAVING }

    data class UiState(
        val phase: Phase,
        val mode: TripMode,
        val tripActive: Boolean,
        val consent: Boolean,
        val paused: Boolean,
        val petOnBoard: Boolean,
        val load: DrivingLoadEngine.Result,
        val vehicle: VehicleSnapshot,
        val location: LocationSnapshot,
        val navi: NaviSnapshot,
        val question: String?,
        val transcript: String?,
        val status: String,
        val recent: List<Moment>,
        val weekCount: Int,
        val placeCount: Int,
        val milestones: List<Milestone>,
    )

    val store = MemoryStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val places = PlaceLearner(store.places)
    private val prompts = PromptEngine()
    private val recall = RecallEngine()
    private var timing = TimingEngine()

    val vehicle = VehicleSignals(context) { onSignals() }
    val location = LocationTracker(context) { onSignals() }
    val navi = NaviTracker(context, this)
    private val voice = VoiceIO(context, this)

    var mode = TripMode.DAILY
        private set
    private var trip: Trip? = null
    private var phase = Phase.IDLE
    private var paused = false
    private var petOnBoard = false
    private var question: String? = null
    private var transcript: String? = null
    private var status = "대기 중 · 기어를 D로 바꾸면 기록을 시작해요"
    private var load = DrivingLoadEngine.Result(0, DrivingLoadEngine.Tier.FREE, emptyList())
    private var lastGear = GearState.UNKNOWN
    private val askedThisWeek = mutableListOf<String>()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 1000)
        }
    }

    fun start() {
        vehicle.start()
        location.start()
        navi.start()
        voice.start()
        main.post(ticker)
        publish()
    }

    fun stop() {
        main.removeCallbacks(ticker)
        voice.release()
        navi.stop()
        location.stop()
        vehicle.stop()
    }

    /** Demo mode shortens all timing windows by 10x so a scenario runs in about a minute. */
    fun setDemoTiming(enabled: Boolean) {
        timing = TimingEngine(timeScale = if (enabled) 0.1 else 1.0)
        if (trip != null) timing.onTripStarted()
    }

    // ---- user actions --------------------------------------------------------------------------

    fun setConsent(value: Boolean) {
        store.recordingConsent = value
        status = if (value) "기록 동의됨 · 답변만 장소와 함께 남겨요" else "기록 꺼짐 · 아무것도 저장하지 않아요"
        publish()
    }

    fun toggleTravelMode() {
        mode = if (mode == TripMode.DAILY) TripMode.TRAVEL else TripMode.DAILY
        val msg = if (mode == TripMode.TRAVEL) {
            "여행 모드를 켰어요. 오늘 이야기를 챕터로 남길게요."
        } else {
            "일상 모드로 돌아왔어요. 하루에 한 질문만 할게요."
        }
        status = msg
        voice.speak(msg) { timing.onSpoke() }
        trip?.chapters?.add(if (mode == TripMode.TRAVEL) "여행 시작" else "여행 끝")
        publish()
    }

    /** Pet on board: switches to pet questions and tags memories with #반려견. */
    fun setPetOnBoard(value: Boolean) {
        if (petOnBoard == value) return
        petOnBoard = value
        status = if (value) "반려견 동승 · 반려견과의 순간도 함께 남길게요" else "반려견 동승 해제"
        if (value && trip != null) voice.speak("반려견도 함께 탔네요. 오늘 이야기에 같이 남길게요.") { timing.onSpoke() }
        publish()
    }

    /** Ask now (driver pressed the steering-wheel shortcut or the screen button while stopped). */
    fun askNow() {
        if (trip == null) startTrip()
        if (load.tier == DrivingLoadEngine.Tier.BUSY) {
            status = "지금은 운전에 집중할 때예요 (부담 ${load.score})"
            publish()
            return
        }
        askQuestion()
    }

    /** Typed or demo answer, same path as speech. */
    fun submitAnswer(text: String) = onFinal(text)

    fun endTripManually() = endTrip(navi.snapshot.destinationName, null)

    fun seedPastMemory(daysAgo: Int, lat: Double, lon: Double, text: String) {
        val createdAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysAgo.toLong())
        store.addMoment(
            Moment(
                id = UUID.randomUUID().toString(),
                tripId = "seed",
                createdAt = createdAt,
                latitude = lat,
                longitude = lon,
                placeId = places.nearest(lat, lon)?.id,
                question = "이 길에서 생각나는 거 있어요?",
                rawText = null,
                summary = text,
                tags = listOf("seed"),
            ),
        )
        publish()
    }

    fun clearAll() {
        store.clearAll()
        status = "모든 기록을 지웠어요"
        publish()
    }

    // ---- signal handling -----------------------------------------------------------------------

    private fun onSignals() {
        val gear = vehicle.snapshot.gear
        if (gear != lastGear) {
            if (gear == GearState.DRIVE && trip == null) startTrip()
            if (gear == GearState.PARK && lastGear == GearState.DRIVE && trip != null) {
                val t = trip!!
                if (System.currentTimeMillis() - t.startedAt > MIN_TRIP_MS) {
                    endTrip(navi.snapshot.destinationName, null)
                }
            }
            lastGear = gear
        }
    }

    private fun tick() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        load = DrivingLoadEngine.evaluate(vehicle.snapshot, location.snapshot, navi.snapshot, hour)
        timing.onLoad(load)

        val t = trip
        if (t != null) {
            if (vehicle.snapshot.wiperOn) t.rainy = true
            t.passengerCount = maxOf(t.passengerCount, vehicle.snapshot.passengerCount)
            vehicle.snapshot.tripDistanceKm?.let { t.distanceKm = it }

            if (phase == Phase.DRIVING && !paused && store.recordingConsent) {
                val loc = location.snapshot
                val memory = if (loc.isValid && timing.canRecall()) {
                    recall.findRecall(loc.latitude, loc.longitude, store.moments)
                } else {
                    null
                }
                when {
                    memory != null -> speakRecall(memory)
                    timing.canAskQuestion(mode) -> askQuestion()
                }
            }
        }
        publish()
    }

    // ---- trip lifecycle ------------------------------------------------------------------------

    private fun startTrip() {
        val t = Trip(
            id = UUID.randomUUID().toString(),
            startedAt = System.currentTimeMillis(),
            mode = mode,
            destinationName = navi.snapshot.destinationName,
        )
        trip = t
        store.addTrip(t)
        timing.onTripStarted()
        recall.onTripStarted()
        phase = Phase.DRIVING
        status = if (store.recordingConsent) "주행 시작 · 여유 있을 때 한 가지만 물어볼게요" else "주행 시작 · 기록 동의 전이라 듣지 않아요"
        publish()
    }

    private fun endTrip(destName: String?, address: String?) {
        val t = trip ?: return
        voice.stopAll()
        t.endedAt = System.currentTimeMillis()
        t.destinationName = destName ?: t.destinationName ?: address

        val loc = location.snapshot
        var announce: String? = null
        if (loc.isValid) {
            val visit = places.recordArrival(loc.latitude, loc.longitude, t.destinationName)
            t.destinationPlaceId = visit.place.id
            store.upsertPlace(visit.place)
            visit.milestone?.let { title ->
                store.addMilestone(
                    Milestone(UUID.randomUUID().toString(), visit.place.id, System.currentTimeMillis(), title),
                )
                announce = "오늘은 $title 이에요. 기록해 둘게요."
            }
        }
        val count = store.moments.count { it.tripId == t.id }
        if (t.mode == TripMode.TRAVEL || mode == TripMode.TRAVEL) {
            t.chapters.add("도착")
            announce = (announce?.plus(" ") ?: "") + "오늘의 이야기에 순간 ${count}개를 담았어요."
        }
        store.updateTrip()
        trip = null
        phase = Phase.IDLE
        question = null
        status = "도착 · 이번 주행에서 ${count}개의 순간을 남겼어요"
        announce?.let { voice.speak(it) }
        publish()
    }

    // ---- conversation --------------------------------------------------------------------------

    private fun askQuestion() {
        if (!store.recordingConsent) {
            status = "기록 동의를 먼저 해 주세요"
            publish()
            return
        }
        val destVisits = navi.snapshot.destinationName?.let { name ->
            store.places.firstOrNull { it.name == name }?.visitCount
        } ?: 0
        val q = prompts.pick(
            PromptEngine.contextNow(
                mode = mode,
                rainy = vehicle.snapshot.wiperOn,
                hasRearPassenger = vehicle.snapshot.hasRearPassenger,
                destinationVisitCount = destVisits,
                destinationName = navi.snapshot.destinationName,
                petOnBoard = petOnBoard,
            ),
            askedThisWeek,
        )
        askedThisWeek += q
        if (askedThisWeek.size > 20) askedThisWeek.removeAt(0)

        question = q
        transcript = null
        phase = Phase.ASKING
        timing.onQuestionAsked()
        status = "오늘의 한 질문"
        publish()
        voice.speak(q) {
            phase = Phase.LISTENING
            status = "듣고 있어요 · \"기록하지 마\"라고 하면 멈춰요"
            publish()
            voice.listen()
        }
    }

    private fun speakRecall(m: Moment) {
        recall.markRecalled(m)
        timing.onSpoke()
        val text = recall.phrase(m)
        status = "같은 길 회상"
        question = text
        publish()
        voice.speak(text)
    }

    override fun onSpeakDone() = Unit

    override fun onPartial(text: String) {
        transcript = text
        publish()
    }

    override fun onFinal(text: String) {
        if (text.isBlank()) return
        when (PrivacyGuard.detectCommand(text)) {
            PrivacyGuard.Command.PAUSE -> {
                paused = true
                voice.stopAll()
                status = "기록을 멈췄어요"
                voice.speak("알겠어요. 지금부터는 기록하지 않을게요.")
                phase = if (trip != null) Phase.DRIVING else Phase.IDLE
                publish()
                return
            }
            PrivacyGuard.Command.RESUME -> {
                paused = false
                status = "기록을 다시 시작했어요"
                voice.speak("다시 기록할게요.")
                phase = if (trip != null) Phase.DRIVING else Phase.IDLE
                publish()
                return
            }
            PrivacyGuard.Command.DELETE_LAST -> {
                store.deleteLastMoment()
                status = "방금 기록을 지웠어요"
                voice.speak("방금 기록은 지웠어요.")
                phase = if (trip != null) Phase.DRIVING else Phase.IDLE
                publish()
                return
            }
            PrivacyGuard.Command.NONE -> Unit
        }

        if (paused || !store.recordingConsent) {
            phase = if (trip != null) Phase.DRIVING else Phase.IDLE
            publish()
            return
        }

        phase = Phase.SAVING
        val loc = location.snapshot
        val masked = PrivacyGuard.mask(text)
        val moment = Moment(
            id = UUID.randomUUID().toString(),
            tripId = trip?.id ?: "manual",
            createdAt = System.currentTimeMillis(),
            latitude = loc.latitude,
            longitude = loc.longitude,
            placeId = if (loc.isValid) places.nearest(loc.latitude, loc.longitude)?.id else null,
            question = question ?: PromptEngine.FALLBACK,
            rawText = masked,
            summary = PrivacyGuard.summarize(masked),
            tags = buildList {
                if (vehicle.snapshot.wiperOn) add("비")
                if (mode == TripMode.TRAVEL) add("여행")
                if (vehicle.snapshot.hasRearPassenger) add("뒷좌석")
                if (petOnBoard) add("반려견")
            },
        )
        store.addMoment(moment)
        if (mode == TripMode.TRAVEL) trip?.chapters?.add(moment.summary)
        transcript = masked
        status = "이 길 위에 남겨 두었어요"
        voice.speak("고마워요. 이 길에 남겨 둘게요.")
        phase = if (trip != null) Phase.DRIVING else Phase.IDLE
        publish()
    }

    override fun onListenEnded() {
        if (phase == Phase.LISTENING) {
            phase = if (trip != null) Phase.DRIVING else Phase.IDLE
            publish()
        }
    }

    override fun onVoiceError(message: String) {
        if (phase == Phase.LISTENING || phase == Phase.ASKING) {
            phase = if (trip != null) Phase.DRIVING else Phase.IDLE
        }
        status = "음성 오류 · $message"
        publish()
    }

    // ---- NaviTracker.Listener ------------------------------------------------------------------

    override fun onNaviChanged(snapshot: NaviSnapshot) = Unit

    override fun onRouteStarted(destinationName: String?) {
        if (trip == null) startTrip() else trip?.destinationName = destinationName
    }

    override fun onArrived(destinationName: String?, address: String?) = endTrip(destinationName, address)

    // ---- UI ------------------------------------------------------------------------------------

    private fun publish() {
        val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val state = UiState(
            phase = phase,
            mode = mode,
            tripActive = trip != null,
            consent = store.recordingConsent,
            paused = paused,
            petOnBoard = petOnBoard,
            load = load,
            vehicle = vehicle.snapshot,
            location = location.snapshot,
            navi = navi.snapshot,
            question = question,
            transcript = transcript,
            status = status,
            recent = store.recentMoments(8),
            weekCount = store.moments.count { it.createdAt >= weekAgo && "seed" !in it.tags },
            placeCount = store.places.size,
            milestones = store.milestones.sortedByDescending { it.createdAt }.take(3),
        )
        if (Looper.myLooper() == Looper.getMainLooper()) ui(state) else main.post { ui(state) }
    }

    companion object {
        private const val MIN_TRIP_MS = 20_000L
    }
}
