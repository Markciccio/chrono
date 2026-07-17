package it.crono

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A verified circuit extracted from a completed session, ready for the next visit. */
data class SavedTrack(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val lap: RecordedLap,
    val finishLine: TimingLine,
    val sectors: List<TimingLine>
) {
    val center: TrackPoint
        get() {
            val samples = lap.samples
            return TrackPoint(samples.map { it.lat }.average(), samples.map { it.lon }.average())
        }
}

class TrackStore(context: Context) {
    private val directory = File(context.filesDir, "tracks").apply { mkdirs() }

    fun save(track: SavedTrack) {
        val json = JSONObject().apply {
            put("id", track.id); put("name", track.name); put("createdAtMs", track.createdAtMs)
            put("finishLine", lineJson(track.finishLine))
            put("sectors", JSONArray().apply { track.sectors.forEach { put(lineJson(it)) } })
            put("lap", lapJson(track.lap))
        }
        File(directory, "${track.id}.json").writeText(json.toString())
    }

    fun list(): List<SavedTrack> = directory.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { runCatching { parse(JSONObject(it.readText())) }.getOrNull() }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun nearby(point: TrackPoint, radiusM: Double = 1_500.0): List<SavedTrack> =
        list().filter { Geo.distanceM(point.lat, point.lon, it.center.lat, it.center.lon) <= radiusM }

    private fun parse(json: JSONObject): SavedTrack {
        val lap = parseLap(json.getJSONObject("lap"))
        return SavedTrack(
            json.getString("id"), json.getString("name"), json.getLong("createdAtMs"), lap,
            parseLine(json.getJSONObject("finishLine")),
            json.getJSONArray("sectors").let { array -> (0 until array.length()).map { parseLine(array.getJSONObject(it)) } }
        )
    }

    private fun lapJson(lap: RecordedLap) = JSONObject().apply {
        put("number", lap.number); put("durationMs", lap.durationMs); put("sectors", JSONArray(lap.sectorElapsedMs))
        put("samples", JSONArray().apply { lap.samples.forEach { sample ->
            put(JSONObject().apply {
                put("lat", sample.lat); put("lon", sample.lon); put("speed", sample.speedMps)
                put("accuracy", sample.accuracyM); put("time", sample.timeMs)
            })
        } })
    }

    private fun parseLap(json: JSONObject): RecordedLap {
        val samples = json.getJSONArray("samples").let { array -> (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            GpsSample(item.getDouble("lat"), item.getDouble("lon"), item.getDouble("speed").toFloat(), item.optDouble("accuracy", 8.0).toFloat(), item.getLong("time"))
        } }
        val sectors = json.getJSONArray("sectors").let { array -> (0 until array.length()).map { array.getLong(it) } }
        return RecordedLap(json.getInt("number"), json.getLong("durationMs"), sectors, samples)
    }

    private fun lineJson(line: TimingLine) = JSONObject().apply {
        put("aLat", line.pointA.lat); put("aLon", line.pointA.lon); put("bLat", line.pointB.lat); put("bLon", line.pointB.lon)
        put("heading", line.allowedHeadingDeg); put("minimumSpeed", line.minimumSpeedMps)
    }

    private fun parseLine(json: JSONObject) = TimingLine(
        TrackPoint(json.getDouble("aLat"), json.getDouble("aLon")), TrackPoint(json.getDouble("bLat"), json.getDouble("bLon")),
        json.getDouble("heading"), json.optDouble("minimumSpeed", 2.0)
    )
}
