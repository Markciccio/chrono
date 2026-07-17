package it.crono

/**
 * Finds a plausible first-loop closure from raw GPS, without requiring a GPX
 * or a preconfigured finish line. It demands elapsed time, compatible heading
 * and movement to avoid arming while stationary near the start.
 */
class AutoFinishDetector(
    private val minimumLoopMs: Long = 45_000,
    private val proximityM: Double = 12.0,
    private val minimumSpeedMps: Float = 3f,
    private val minimumPathM: Double = 350.0,
    private val minimumExcursionM: Double = 80.0
) {
    private val samples = mutableListOf<GpsSample>()

    data class Discovery(val line: TimingLine, val lap: Lap)

    fun reset() = samples.clear()

    fun process(sample: GpsSample): Discovery? {
        val previous = samples.lastOrNull()
        samples += sample
        if (previous == null || sample.speedMps < minimumSpeedMps) return null
        val heading = Geo.headingDeg(TrackPoint(previous.lat, previous.lon), TrackPoint(sample.lat, sample.lon))
        val candidateIndex = samples.indices
            .asSequence()
            .filter { it < samples.lastIndex }
            .filter { sample.timeMs - samples[it].timeMs >= minimumLoopMs }
            .filter { Geo.distanceM(sample.lat, sample.lon, samples[it].lat, samples[it].lon) <= proximityM }
            .filter { index -> traveledDistanceM(index, samples.lastIndex) >= minimumPathM }
            .filter { index -> maximumExcursionM(index, samples.lastIndex) >= minimumExcursionM }
            .filter { index ->
                val before = samples[(index - 1).coerceAtLeast(0)]
                val point = samples[index]
                val candidateHeading = Geo.headingDeg(TrackPoint(before.lat, before.lon), TrackPoint(point.lat, point.lon))
                Geo.angleDifferenceDeg(heading, candidateHeading) <= 55.0
            }
            .minByOrNull { Geo.distanceM(sample.lat, sample.lon, samples[it].lat, samples[it].lon) }
            ?: return null
        val candidate = samples[candidateIndex]
        val center = TrackPoint((sample.lat + candidate.lat) / 2.0, (sample.lon + candidate.lon) / 2.0)
        val lapSamples = samples.subList(candidateIndex, samples.size).toList()
        return Discovery(Geo.timingLine(center, heading), Lap(1, sample.timeMs - candidate.timeMs, lapSamples))
    }

    fun recordedSamples(): List<GpsSample> = samples.toList()

    private fun traveledDistanceM(startIndex: Int, endIndex: Int): Double {
        var distance = 0.0
        for (index in (startIndex + 1)..endIndex) {
            val previous = samples[index - 1]
            val current = samples[index]
            distance += Geo.distanceM(previous.lat, previous.lon, current.lat, current.lon)
        }
        return distance
    }

    private fun maximumExcursionM(startIndex: Int, endIndex: Int): Double {
        val start = samples[startIndex]
        return (startIndex..endIndex).maxOfOrNull { index ->
            val point = samples[index]
            Geo.distanceM(start.lat, start.lon, point.lat, point.lon)
        } ?: 0.0
    }
}
