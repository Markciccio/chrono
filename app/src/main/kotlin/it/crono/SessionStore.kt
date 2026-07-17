package it.crono

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecordedLap(
    val number: Int,
    val durationMs: Long,
    val sectorElapsedMs: List<Long>,
    val samples: List<GpsSample> = emptyList(),
    /** Manual review can exclude false loop closures without deleting their GPS evidence. */
    val valid: Boolean = true
)

data class SavedSession(
    val id: String,
    val displayName: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val simulated: Boolean,
    val gpxName: String?,
    val laps: List<RecordedLap>,
    val maxSpeedMps: Float? = null,
    val minSpeedMps: Float? = null,
    val timingLine: TimingLine? = null,
    val sectorLines: List<TimingLine> = emptyList(),
    /** Complete raw path, including warm-up/return-to-box portions and incomplete laps. */
    val samples: List<GpsSample> = emptyList()
)

class SessionStore(context: Context) {
    private val directory = File(context.filesDir, "sessions").apply { mkdirs() }

    fun save(session: SavedSession): SavedSession {
        val json = JSONObject().apply {
            put("id", session.id)
            put("displayName", session.displayName)
            put("startedAtMs", session.startedAtMs)
            put("durationMs", session.durationMs)
            put("simulated", session.simulated)
            put("gpxName", session.gpxName ?: JSONObject.NULL)
            put("maxSpeedMps", session.maxSpeedMps ?: JSONObject.NULL)
            put("minSpeedMps", session.minSpeedMps ?: JSONObject.NULL)
            put("timingLine", session.timingLine?.let(::lineJson) ?: JSONObject.NULL)
            put("sectorLines", JSONArray().apply { session.sectorLines.forEach { put(lineJson(it)) } })
            put("samples", JSONArray().apply { session.samples.forEach { sample ->
                put(JSONObject().apply {
                    put("lat", sample.lat); put("lon", sample.lon); put("speed", sample.speedMps)
                    put("accuracy", sample.accuracyM); put("time", sample.timeMs)
                })
            } })
            put("laps", JSONArray().apply {
                session.laps.forEach { lap ->
                    put(JSONObject().apply {
                        put("number", lap.number)
                        put("durationMs", lap.durationMs)
                        put("valid", lap.valid)
                        put("sectors", JSONArray(lap.sectorElapsedMs))
                        put("samples", JSONArray().apply {
                            lap.samples.forEach { sample ->
                                put(JSONObject().apply {
                                    put("lat", sample.lat)
                                    put("lon", sample.lon)
                                    put("speed", sample.speedMps)
                                    put("accuracy", sample.accuracyM)
                                    put("time", sample.timeMs)
                                })
                            }
                        })
                    })
                }
            })
        }
        File(directory, "${session.id}.json").writeText(json.toString())
        return session
    }

    fun list(): List<SavedSession> = directory.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { runCatching { parse(JSONObject(it.readText())) }.getOrNull() }
        ?.sortedByDescending { it.startedAtMs }
        ?: emptyList()

    fun delete(session: SavedSession): Boolean = File(directory, "${session.id}.json").delete()

    private fun parse(json: JSONObject): SavedSession {
        val lapsJson = json.getJSONArray("laps")
        val laps = (0 until lapsJson.length()).map { index ->
            val lap = lapsJson.getJSONObject(index)
            val sectors = lap.getJSONArray("sectors")
            val samples = lap.optJSONArray("samples")?.let { samplesJson ->
                (0 until samplesJson.length()).map { sampleIndex ->
                    val sample = samplesJson.getJSONObject(sampleIndex)
                    GpsSample(
                        sample.getDouble("lat"), sample.getDouble("lon"),
                        sample.optDouble("speed").toFloat(), sample.optDouble("accuracy", 8.0).toFloat(), sample.getLong("time")
                    )
                }
            } ?: emptyList()
            RecordedLap(
                lap.getInt("number"),
                lap.getLong("durationMs"),
                (0 until sectors.length()).map { sectors.getLong(it) },
                samples,
                lap.optBoolean("valid", true)
            )
        }
        val sessionSamples = json.optJSONArray("samples")?.let { samplesJson ->
            (0 until samplesJson.length()).map { sampleIndex ->
                val sample = samplesJson.getJSONObject(sampleIndex)
                GpsSample(sample.getDouble("lat"), sample.getDouble("lon"), sample.optDouble("speed").toFloat(), sample.optDouble("accuracy", 8.0).toFloat(), sample.getLong("time"))
            }
        } ?: emptyList()
        return SavedSession(
            json.getString("id"),
            json.optString("displayName", "Sessione"),
            json.getLong("startedAtMs"),
            json.getLong("durationMs"),
            json.getBoolean("simulated"),
            if (json.isNull("gpxName")) null else json.getString("gpxName"),
            laps,
            if (json.isNull("maxSpeedMps")) null else json.optDouble("maxSpeedMps").toFloat(),
            if (json.isNull("minSpeedMps")) null else json.optDouble("minSpeedMps").toFloat(),
            json.optJSONObject("timingLine")?.let(::parseLine),
            json.optJSONArray("sectorLines")?.let { lines -> (0 until lines.length()).map { parseLine(lines.getJSONObject(it)) } } ?: emptyList(),
            sessionSamples
        )
    }

    private fun lineJson(line: TimingLine) = JSONObject().apply {
        put("aLat", line.pointA.lat); put("aLon", line.pointA.lon)
        put("bLat", line.pointB.lat); put("bLon", line.pointB.lon)
        put("heading", line.allowedHeadingDeg); put("minimumSpeed", line.minimumSpeedMps)
    }

    private fun parseLine(json: JSONObject) = TimingLine(
        TrackPoint(json.getDouble("aLat"), json.getDouble("aLon")),
        TrackPoint(json.getDouble("bLat"), json.getDouble("bLon")),
        json.getDouble("heading"), json.optDouble("minimumSpeed", 2.0)
    )
}
