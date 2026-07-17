package it.crono

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Development-only GPS feed used to exercise the exact same timing pipeline
 * indoors. It draws a non-intersecting kart-style closed circuit around the latest real position,
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
        // A compact kart circuit: main straight, hard braking zone, a flowing top complex,
        // hairpin and an infield chicane.  The contour is deliberately asymmetric and
        // non-intersecting so it resembles a real circuit rather than a geometric oval.
        private val RAW_CIRCUIT = listOf(
            LocalPoint(0.0, -150.0), LocalPoint(105.0, -150.0), LocalPoint(155.0, -120.0),
            LocalPoint(170.0, -58.0), LocalPoint(136.0, -12.0), LocalPoint(150.0, 52.0),
            LocalPoint(108.0, 118.0), LocalPoint(50.0, 92.0), LocalPoint(12.0, 142.0),
            LocalPoint(-58.0, 136.0), LocalPoint(-112.0, 100.0), LocalPoint(-88.0, 54.0),
            LocalPoint(-148.0, 25.0), LocalPoint(-166.0, -40.0), LocalPoint(-130.0, -102.0),
            LocalPoint(-76.0, -86.0), LocalPoint(-52.0, -132.0), LocalPoint(4.0, -112.0),
            LocalPoint(46.0, -66.0), LocalPoint(7.0, -32.0), LocalPoint(-36.0, -56.0),
            LocalPoint(-60.0, -110.0), LocalPoint(-26.0, -145.0)
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
