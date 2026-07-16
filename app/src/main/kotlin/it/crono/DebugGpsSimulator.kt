package it.crono

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Development-only GPS feed used to exercise the exact same timing pipeline
 * indoors. It draws a non-intersecting horseshoe-style closed circuit around the latest real position,
 * emitting a point every 400 ms. Its centreline is scaled to 1.25 km;
 * the reference lap lasts about 90 seconds. Each later lap has a different average pace to make the live
 * delta and voice feedback observable.
 */
class DebugGpsSimulator(
    private val origin: TrackPoint,
    private val startTimeMs: Long
) {
    companion object {
        const val RADIUS_M = 100.0
        const val STEP_MS = 400L
        private const val METERS_PER_LAT_DEG = 111_320.0
        private data class LocalPoint(val eastM: Double, val northM: Double)
        private const val TARGET_CIRCUIT_LENGTH_M = 1_250.0
        // A simple horseshoe: outer clockwise arc followed by the inner return.
        // The two branches stay well separated and the centreline never intersects itself.
        private val RAW_CIRCUIT = listOf(
            LocalPoint(72.0, -150.0), LocalPoint(120.0, -125.0), LocalPoint(150.0, -65.0),
            LocalPoint(154.0, 35.0), LocalPoint(125.0, 112.0), LocalPoint(67.0, 162.0),
            LocalPoint(0.0, 180.0), LocalPoint(-67.0, 162.0), LocalPoint(-125.0, 112.0),
            LocalPoint(-154.0, 35.0), LocalPoint(-150.0, -65.0), LocalPoint(-120.0, -125.0),
            LocalPoint(-72.0, -150.0), LocalPoint(-45.0, -112.0), LocalPoint(-55.0, -42.0),
            LocalPoint(-58.0, 30.0), LocalPoint(-39.0, 82.0), LocalPoint(0.0, 108.0),
            LocalPoint(39.0, 82.0), LocalPoint(58.0, 30.0), LocalPoint(55.0, -42.0),
            LocalPoint(45.0, -112.0)
        )
        private fun lengthOf(points: List<LocalPoint>) = points.indices.sumOf { index ->
            val from = points[index]
            val to = points[(index + 1) % points.size]
            hypot(to.eastM - from.eastM, to.northM - from.northM)
        }
        private fun smoothClosed(points: List<LocalPoint>) = points.indices.flatMap { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            listOf(
                LocalPoint(a.eastM * .75 + b.eastM * .25, a.northM * .75 + b.northM * .25),
                LocalPoint(a.eastM * .25 + b.eastM * .75, a.northM * .25 + b.northM * .75)
            )
        }
        private val CIRCUIT = smoothClosed(smoothClosed(RAW_CIRCUIT)).let { smoothed ->
            val scale = TARGET_CIRCUIT_LENGTH_M / lengthOf(smoothed)
            smoothed.map { LocalPoint(it.eastM * scale, it.northM * scale) }
        }
        private val CIRCUIT_LENGTH_M = lengthOf(CIRCUIT)
    }

    private var distanceM = 0.0
    private var timestampMs = startTimeMs
    private var randomState = 0x51A7C0DEL

    private fun nextNoise(): Double {
        // Deterministic pseudo-randomness keeps unit tests repeatable.
        randomState = (randomState * 1_103_515_245L + 12_345L) and 0x7fff_ffffL
        return randomState.toDouble() / 0x7fff_ffffL.toDouble() * 2.0 - 1.0
    }

    private fun circuitPositionAt(distanceM: Double): LocalPoint {
        var remaining = distanceM % CIRCUIT_LENGTH_M
        CIRCUIT.indices.forEach { index ->
            val from = CIRCUIT[index]
            val to = CIRCUIT[(index + 1) % CIRCUIT.size]
            val segment = hypot(to.eastM - from.eastM, to.northM - from.northM)
            if (remaining <= segment) {
                val fraction = remaining / segment
                return LocalPoint(
                    from.eastM + (to.eastM - from.eastM) * fraction,
                    from.northM + (to.northM - from.northM) * fraction
                )
            }
            remaining -= segment
        }
        return CIRCUIT.first()
    }

    fun next(): GpsSample {
        val lapIndex = floor(distanceM / CIRCUIT_LENGTH_M).toInt()
        val phase = distanceM / CIRCUIT_LENGTH_M * 2.0 * PI
        val averagePace = when (lapIndex) {
            // The first two laps deliberately vary more, as with an out-lap and a first push lap.
            0 -> 13.6
            1 -> 14.3
            // Once the driver has settled, keep lap times within roughly 1–2 seconds.
            else -> when (lapIndex % 4) {
                0 -> 13.92
                1 -> 14.10
                2 -> 13.82
                else -> 14.00
            }
        }
        val speed = (averagePace + 2.0 * sin(phase * 3.0) + 1.0 * sin(phase * 7.0)).coerceAtLeast(7.0)
        distanceM += speed * STEP_MS / 1_000.0
        timestampMs += STEP_MS

        val position = circuitPositionAt(distanceM)
        // A low-frequency 3–4 m drift plus ~2 m point jitter resembles phone GPS
        // much more closely than a mathematically perfect circle.
        val driftEastM = 3.8 * sin(phase * 0.47 + 0.8)
        val driftNorthM = 3.2 * cos(phase * 0.39 - 0.5)
        val jitterEastM = nextNoise() * 1.8
        val jitterNorthM = nextNoise() * 1.8
        val lat = origin.lat + (position.northM + driftNorthM + jitterNorthM) / METERS_PER_LAT_DEG
        val lon = origin.lon + (position.eastM + driftEastM + jitterEastM) / (METERS_PER_LAT_DEG * cos(Math.toRadians(origin.lat)))
        val reportedAccuracy = (4.5 + nextNoise().coerceAtLeast(-0.6) * 2.0).toFloat()
        return GpsSample(lat, lon, speed.toFloat(), reportedAccuracy, timestampMs)
    }
}
