package it.crono

import kotlin.math.roundToLong

/**
 * Pure timing core. It deliberately has no Android dependency so it can be
 * exercised with recorded Race Chrono data and synthetic unit tests.
 */
class TimingEngine(
    private val minimumLapMs: Long = 20_000,
    private val crossingCooldownMs: Long = 8_000
) {
    var line: TimingLine? = null
    var sectors: List<TimingLine> = emptyList()
    private var previous: GpsSample? = null
    private var lapStartMs: Long? = null
    private var lastCrossingMs = Long.MIN_VALUE
    private var activeSamples = mutableListOf<GpsSample>()
    private var completedLaps = 0
    private val passedSectors = mutableSetOf<Int>()
    private var pausedAtMs: Long? = null

    val isArmed: Boolean get() = lapStartMs != null
    val currentLapStartMs: Long? get() = lapStartMs
    val currentLapNumber: Int get() = completedLaps + 1

    fun reset() {
        previous = null
        lapStartMs = null
        lastCrossingMs = Long.MIN_VALUE
        activeSamples.clear()
        completedLaps = 0
        passedSectors.clear()
        pausedAtMs = null
    }

    /** Arms timing at a known crossing, for example when auto-discovery closes its learning lap. */
    fun armAt(sample: GpsSample, completedLapCount: Int = 0) {
        lapStartMs = sample.timeMs
        lastCrossingMs = sample.timeMs
        activeSamples = mutableListOf(sample)
        completedLaps = completedLapCount
        passedSectors.clear()
        pausedAtMs = null
    }

    /** Excludes a box/pause interval from the current lap without discarding session history. */
    fun pauseAt(timeMs: Long) {
        if (lapStartMs != null && pausedAtMs == null) pausedAtMs = timeMs
    }

    fun resumeAt(timeMs: Long) {
        val pausedAt = pausedAtMs ?: return
        val pausedDuration = (timeMs - pausedAt).coerceAtLeast(0L)
        lapStartMs = lapStartMs?.plus(pausedDuration)
        lastCrossingMs = if (lastCrossingMs == Long.MIN_VALUE) Long.MIN_VALUE else lastCrossingMs + pausedDuration
        previous = null // Do not treat the journey from the box as a crossing segment.
        pausedAtMs = null
    }

    fun process(sample: GpsSample): TimingEvent? {
        val old = previous
        previous = sample
        val timingLine = line ?: run {
            if (lapStartMs != null) activeSamples += sample
            return null
        }
        if (old == null || sample.speedMps < timingLine.minimumSpeedMps) {
            if (lapStartMs != null) activeSamples += sample
            return null
        }

        if (lapStartMs != null) activeSamples += sample
        val activeStart = lapStartMs
        if (activeStart != null) {
            sectors.forEachIndexed { index, sector ->
                if (index in passedSectors) return@forEachIndexed
                val fraction = Geo.segmentIntersectionFraction(
                    TrackPoint(old.lat, old.lon), TrackPoint(sample.lat, sample.lon), sector.pointA, sector.pointB
                ) ?: return@forEachIndexed
                val heading = Geo.headingDeg(TrackPoint(old.lat, old.lon), TrackPoint(sample.lat, sample.lon))
                if (Geo.angleDifferenceDeg(heading, sector.allowedHeadingDeg) > 75.0) return@forEachIndexed
                passedSectors += index
                val crossingMs = old.timeMs + ((sample.timeMs - old.timeMs) * fraction).roundToLong()
                return TimingEvent.SectorCompleted(index + 1, crossingMs - activeStart)
            }
        }
        val fraction = Geo.segmentIntersectionFraction(
            TrackPoint(old.lat, old.lon), TrackPoint(sample.lat, sample.lon), timingLine.pointA, timingLine.pointB
        ) ?: return null
        val heading = Geo.headingDeg(TrackPoint(old.lat, old.lon), TrackPoint(sample.lat, sample.lon))
        if (Geo.angleDifferenceDeg(heading, timingLine.allowedHeadingDeg) > 75.0) return null

        val crossingMs = old.timeMs + ((sample.timeMs - old.timeMs) * fraction).roundToLong()
        if (lastCrossingMs != Long.MIN_VALUE && crossingMs - lastCrossingMs < crossingCooldownMs) return null
        lastCrossingMs = crossingMs

        val started = lapStartMs
        if (started == null) {
            lapStartMs = crossingMs
            activeSamples = mutableListOf(sample)
            passedSectors.clear()
            return TimingEvent.Armed(crossingMs)
        }
        val duration = crossingMs - started
        if (duration < minimumLapMs) return null
        completedLaps++
        val lap = Lap(completedLaps, duration, activeSamples.toList())
        lapStartMs = crossingMs
        activeSamples = mutableListOf(sample)
        passedSectors.clear()
        return TimingEvent.LapCompleted(lap)
    }
}

/**
 * Matches a live lap against a reference lap in temporal order. Unlike a
 * global nearest-point lookup, it cannot jump backwards to a nearby parallel
 * section of the circuit.
 */
class PredictiveDelta(private val reference: List<GpsSample>) {
    private var lastIndex = 0

    fun reset() { lastIndex = 0 }

    fun calculate(sample: GpsSample, currentLapStartMs: Long): Long? {
        if (reference.size < 3) return null
        val start = (lastIndex - 8).coerceAtLeast(0)
        val end = (lastIndex + 70).coerceAtMost(reference.lastIndex)
        val index = (start..end).minByOrNull { i ->
            Geo.distanceM(sample.lat, sample.lon, reference[i].lat, reference[i].lon)
        } ?: return null
        lastIndex = index
        val currentElapsed = sample.timeMs - currentLapStartMs
        val referenceElapsed = reference[index].timeMs - reference.first().timeMs
        return currentElapsed - referenceElapsed
    }
}
