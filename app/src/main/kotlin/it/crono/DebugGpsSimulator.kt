package it.crono

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Development-only GPS feed used to exercise the exact same timing pipeline
 * indoors. It draws a simple, non-intersecting oval around the latest real position,
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
        private fun lengthOf(points: List<LocalPoint>) = points.indices.sumOf { index ->
            val from = points[index]
            val to = points[(index + 1) % points.size]
            hypot(to.eastM - from.eastM, to.northM - from.northM)
        }
        // A clean ellipse is deliberately used for the indoor test: it cannot cross itself,
        // has no chicanes that confuse the automatic finish-line detector, and remains easy to
        // recognise on the map. The many points make the curve look smooth at GPS sample scale.
        private val CIRCUIT = List(96) { index ->
            val angle = 2.0 * PI * index / 96.0
            LocalPoint(205.0 * cos(angle), 112.0 * sin(angle))
        }.let { ellipse ->
            val scale = TARGET_CIRCUIT_LENGTH_M / lengthOf(ellipse)
            ellipse.map { LocalPoint(it.eastM * scale, it.northM * scale) }
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
        // Keep the simulated GPS imperfect, but small enough that the oval remains legible.
        val driftEastM = 1.4 * sin(phase * 0.47 + 0.8)
        val driftNorthM = 1.2 * cos(phase * 0.39 - 0.5)
        val jitterEastM = nextNoise() * 0.9
        val jitterNorthM = nextNoise() * 0.9
        val lat = origin.lat + (position.northM + driftNorthM + jitterNorthM) / METERS_PER_LAT_DEG
        val lon = origin.lon + (position.eastM + driftEastM + jitterEastM) / (METERS_PER_LAT_DEG * cos(Math.toRadians(origin.lat)))
        val reportedAccuracy = (4.5 + nextNoise().coerceAtLeast(-0.6) * 2.0).toFloat()
        return GpsSample(lat, lon, speed.toFloat(), reportedAccuracy, timestampMs)
    }
}
