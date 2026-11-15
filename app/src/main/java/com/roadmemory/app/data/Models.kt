package com.roadmemory.app.data

/** Gear position reduced to what the timing engine cares about. */
enum class GearState { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

enum class TurnSignalState { OFF, LEFT, RIGHT }

enum class TripMode {
    /** Everyday short trip (school run, commute). One question per trip. */
    DAILY,

    /** Travel mode: chapters, more questions, "today's story" at the end. */
    TRAVEL,
}

/** Live vehicle signals collected from the Vehicle SDK. */
data class VehicleSnapshot(
    val gear: GearState = GearState.UNKNOWN,
    val turnSignal: TurnSignalState = TurnSignalState.OFF,
    val wiperOn: Boolean = false,
    val brakePressed: Boolean = false,
    val passengerFront: Boolean = false,
    val passengerRearLeft: Boolean = false,
    val passengerRearRight: Boolean = false,
    val tripDistanceKm: Float? = null,
) {
    val passengerCount: Int
        get() = listOf(passengerFront, passengerRearLeft, passengerRearRight).count { it }

    val hasRearPassenger: Boolean
        get() = passengerRearLeft || passengerRearRight
}

/** Live navigation context collected from NaviHelper. */
data class NaviSnapshot(
    val routeActive: Boolean = false,
    val destinationName: String? = null,
    val nextGuidanceMeters: Double? = null,
    val nextGuidanceText: String? = null,
)

/** Live position + speed derived from FusedLocation. */
data class LocationSnapshot(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speedKmh: Double = 0.0,
    /** Change of speed between the last two samples, km/h per second. */
    val accelKmhPerSec: Double = 0.0,
    val timestampMillis: Long = 0L,
) {
    val isValid: Boolean get() = timestampMillis > 0L
}

/** A place the family visits; learned from trip end points. */
data class Place(
    val id: String,
    var name: String,
    val latitude: Double,
    val longitude: Double,
    var visitCount: Int = 0,
    val firstVisitAt: Long = System.currentTimeMillis(),
    var lastVisitAt: Long = System.currentTimeMillis(),
)

data class Trip(
    val id: String,
    val startedAt: Long,
    var endedAt: Long? = null,
    val mode: TripMode,
    var destinationName: String? = null,
    var destinationPlaceId: String? = null,
    var distanceKm: Float = 0f,
    var passengerCount: Int = 0,
    var rainy: Boolean = false,
    /** Chapter titles for travel mode (stop, scenery, arrival...). */
    val chapters: MutableList<String> = mutableListOf(),
)

/**
 * One answer to a question, pinned to the place where it was spoken.
 * [rawText] is the speech-to-text result and is deleted after [Privacy.RAW_RETENTION_DAYS];
 * only [summary] stays.
 */
data class Moment(
    val id: String,
    val tripId: String,
    val createdAt: Long,
    val latitude: Double,
    val longitude: Double,
    val placeId: String?,
    val question: String,
    var rawText: String?,
    var summary: String,
    val tags: List<String> = emptyList(),
)

data class Milestone(
    val id: String,
    val placeId: String,
    val createdAt: Long,
    val title: String,
)

object Privacy {
    const val RAW_RETENTION_DAYS = 7
}
