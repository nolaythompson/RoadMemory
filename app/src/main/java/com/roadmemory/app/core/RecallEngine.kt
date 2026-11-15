package com.roadmemory.app.core

import com.roadmemory.app.data.Moment
import java.util.concurrent.TimeUnit

/**
 * "Same road recall": when the car passes a spot where the family said something
 * a while ago, it offers that memory back once.
 */
class RecallEngine(
    private val minAgeMillis: Long = TimeUnit.DAYS.toMillis(7),
    private val radiusMeters: Double = 300.0,
) {
    private val recalledThisTrip = mutableSetOf<String>()

    fun onTripStarted() = recalledThisTrip.clear()

    fun findRecall(
        lat: Double,
        lon: Double,
        moments: List<Moment>,
        now: Long = System.currentTimeMillis(),
    ): Moment? = moments
        .asSequence()
        .filter { it.id !in recalledThisTrip }
        .filter { now - it.createdAt >= minAgeMillis }
        .filter { PlaceLearner.distanceMeters(lat, lon, it.latitude, it.longitude) <= radiusMeters }
        .minByOrNull { it.createdAt }

    fun markRecalled(moment: Moment) {
        recalledThisTrip += moment.id
    }

    fun phrase(moment: Moment, now: Long = System.currentTimeMillis()): String {
        val days = TimeUnit.MILLISECONDS.toDays(now - moment.createdAt)
        val whenText = when {
            days >= 330 -> "작년 이맘때"
            days >= 60 -> "${days / 30}달 전"
            days >= 14 -> "${days / 7}주 전"
            else -> "${days}일 전"
        }
        return "$whenText 여기서 이런 이야기를 했었어요. \"${moment.summary}\""
    }
}
