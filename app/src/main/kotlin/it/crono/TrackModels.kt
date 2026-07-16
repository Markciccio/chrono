package it.crono

data class TrackPoint(val lat: Double, val lon: Double, val timeMs: Long? = null)

data class GpsSample(
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val accuracyM: Float,
    val timeMs: Long
)

data class Lap(val number: Int, val durationMs: Long, val samples: List<GpsSample>)

data class TimingLine(
    val pointA: TrackPoint,
    val pointB: TrackPoint,
    val allowedHeadingDeg: Double,
    val minimumSpeedMps: Double = 2.0
)

data class SectorReference(val number: Int, val line: TimingLine, val referenceElapsedMs: Long)

sealed interface TimingEvent {
    data class Armed(val timestampMs: Long) : TimingEvent
    data class SectorCompleted(val number: Int, val elapsedMs: Long) : TimingEvent
    data class LapCompleted(val lap: Lap) : TimingEvent
}
