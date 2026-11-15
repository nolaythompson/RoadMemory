package com.roadmemory.app.core

import com.roadmemory.app.data.Place
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Learns the family's places from trip end points (school, academy, grandma's house...)
 * and produces "first time" / "Nth time" milestones.
 */
class PlaceLearner(private val places: MutableList<Place>) {

    data class Visit(val place: Place, val isNew: Boolean, val milestone: String?)

    fun nearest(lat: Double, lon: Double, withinMeters: Double = MATCH_RADIUS_M): Place? =
        places.map { it to distanceMeters(lat, lon, it.latitude, it.longitude) }
            .filter { it.second <= withinMeters }
            .minByOrNull { it.second }
            ?.first

    /** Register an arrival. Returns the place plus a milestone title when one is reached. */
    fun recordArrival(lat: Double, lon: Double, name: String?, now: Long = System.currentTimeMillis()): Visit {
        val existing = nearest(lat, lon)
        val place = existing ?: Place(
            id = UUID.randomUUID().toString(),
            name = name?.takeIf { it.isNotBlank() } ?: "새 장소 ${places.size + 1}",
            latitude = lat,
            longitude = lon,
            visitCount = 0,
            firstVisitAt = now,
        ).also { places.add(it) }

        if (existing != null && !name.isNullOrBlank() && existing.name.startsWith("새 장소")) {
            existing.name = name
        }
        place.visitCount += 1
        place.lastVisitAt = now

        return Visit(place, existing == null, milestoneFor(place))
    }

    private fun milestoneFor(place: Place): String? = when (place.visitCount) {
        1 -> "${place.name}에 처음 온 날"
        in MILESTONES -> "${place.name} ${place.visitCount}번째 방문"
        else -> null
    }

    companion object {
        const val MATCH_RADIUS_M = 150.0
        val MILESTONES = setOf(10, 30, 50, 100, 200)

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
