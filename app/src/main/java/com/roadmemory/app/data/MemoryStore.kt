package com.roadmemory.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * On-device store for places, trips, moments and milestones.
 *
 * Everything stays in app-private storage (one JSON file). Raw transcripts are
 * purged after [Privacy.RAW_RETENTION_DAYS]; only short summaries remain.
 * A server sync queue is out of scope for the prototype.
 */
class MemoryStore(context: Context) {

    private val file = File(context.filesDir, "roadmemory.json")
    private val lock = Any()

    val places = mutableListOf<Place>()
    val trips = mutableListOf<Trip>()
    val moments = mutableListOf<Moment>()
    val milestones = mutableListOf<Milestone>()

    private var consent: Boolean = false

    /** Family agreed that answers may be recorded. Nothing is stored without it. */
    var recordingConsent: Boolean
        get() = consent
        set(value) {
            consent = value
            save()
        }

    init {
        load()
        purgeExpiredRawText()
    }

    fun addTrip(trip: Trip) = synchronized(lock) { trips.add(trip); save() }

    fun updateTrip() = save()

    fun addMoment(moment: Moment) = synchronized(lock) { moments.add(moment); save() }

    fun deleteLastMoment(): Moment? = synchronized(lock) {
        val last = moments.maxByOrNull { it.createdAt } ?: return null
        moments.remove(last)
        save()
        last
    }

    fun addMilestone(m: Milestone) = synchronized(lock) { milestones.add(m); save() }

    fun upsertPlace(place: Place) = synchronized(lock) {
        val i = places.indexOfFirst { it.id == place.id }
        if (i >= 0) places[i] = place else places.add(place)
        save()
    }

    fun momentsAt(placeId: String): List<Moment> = moments.filter { it.placeId == placeId }

    fun recentMoments(limit: Int = 20): List<Moment> =
        moments.sortedByDescending { it.createdAt }.take(limit)

    fun clearAll() = synchronized(lock) {
        places.clear(); trips.clear(); moments.clear(); milestones.clear()
        save()
    }

    /** "듣지만 남기지 않는다": raw speech text lives at most 7 days. */
    fun purgeExpiredRawText(now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val limit = TimeUnit.DAYS.toMillis(Privacy.RAW_RETENTION_DAYS.toLong())
        var changed = false
        moments.forEach {
            if (it.rawText != null && now - it.createdAt > limit) {
                it.rawText = null
                changed = true
            }
        }
        if (changed) save()
    }

    // ---- persistence -------------------------------------------------------------------------

    private fun save(): Unit = synchronized(lock) {
        val root = JSONObject()
        root.put("consent", recordingConsent)
        root.put("places", JSONArray(places.map { it.toJson() }))
        root.put("trips", JSONArray(trips.map { it.toJson() }))
        root.put("moments", JSONArray(moments.map { it.toJson() }))
        root.put("milestones", JSONArray(milestones.map { it.toJson() }))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        if (!file.exists()) return
        synchronized(lock) {
            runCatching {
            val root = JSONObject(file.readText())
            consent = root.optBoolean("consent", false)
            root.optJSONArray("places")?.forEachObject { places.add(it.toPlace()) }
            root.optJSONArray("trips")?.forEachObject { trips.add(it.toTrip()) }
            root.optJSONArray("moments")?.forEachObject { moments.add(it.toMoment()) }
            root.optJSONArray("milestones")?.forEachObject { milestones.add(it.toMilestone()) }
            }
        }
    }

}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) block(getJSONObject(i))
}

private fun Place.toJson() = JSONObject()
    .put("id", id).put("name", name).put("lat", latitude).put("lon", longitude)
    .put("visits", visitCount).put("first", firstVisitAt).put("last", lastVisitAt)

private fun JSONObject.toPlace() = Place(
    id = getString("id"), name = getString("name"),
    latitude = getDouble("lat"), longitude = getDouble("lon"),
    visitCount = optInt("visits"), firstVisitAt = optLong("first"), lastVisitAt = optLong("last"),
)

private fun Trip.toJson() = JSONObject()
    .put("id", id).put("start", startedAt).put("end", endedAt ?: JSONObject.NULL)
    .put("mode", mode.name).put("dest", destinationName ?: JSONObject.NULL)
    .put("destPlace", destinationPlaceId ?: JSONObject.NULL)
    .put("km", distanceKm.toDouble()).put("pax", passengerCount).put("rain", rainy)
    .put("chapters", JSONArray(chapters))

private fun JSONObject.toTrip() = Trip(
    id = getString("id"), startedAt = getLong("start"),
    endedAt = if (isNull("end")) null else getLong("end"),
    mode = runCatching { TripMode.valueOf(getString("mode")) }.getOrDefault(TripMode.DAILY),
    destinationName = if (isNull("dest")) null else getString("dest"),
    destinationPlaceId = if (isNull("destPlace")) null else getString("destPlace"),
    distanceKm = optDouble("km", 0.0).toFloat(), passengerCount = optInt("pax"),
    rainy = optBoolean("rain"),
    chapters = optJSONArray("chapters")?.let { a -> MutableList(a.length()) { a.getString(it) } }
        ?: mutableListOf(),
)

private fun Moment.toJson() = JSONObject()
    .put("id", id).put("trip", tripId).put("at", createdAt).put("lat", latitude).put("lon", longitude)
    .put("place", placeId ?: JSONObject.NULL).put("q", question)
    .put("raw", rawText ?: JSONObject.NULL).put("summary", summary).put("tags", JSONArray(tags))

private fun JSONObject.toMoment() = Moment(
    id = getString("id"), tripId = getString("trip"), createdAt = getLong("at"),
    latitude = getDouble("lat"), longitude = getDouble("lon"),
    placeId = if (isNull("place")) null else getString("place"),
    question = getString("q"), rawText = if (isNull("raw")) null else getString("raw"),
    summary = getString("summary"),
    tags = optJSONArray("tags")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
)

private fun Milestone.toJson() = JSONObject()
    .put("id", id).put("place", placeId).put("at", createdAt).put("title", title)

private fun JSONObject.toMilestone() = Milestone(
    id = getString("id"), placeId = getString("place"), createdAt = getLong("at"),
    title = getString("title"),
)
