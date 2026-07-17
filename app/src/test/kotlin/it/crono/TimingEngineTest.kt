package it.crono

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingEngineTest {
    private val line = TimingLine(
        pointA = TrackPoint(45.0, 8.9998),
        pointB = TrackPoint(45.0, 9.0002),
        allowedHeadingDeg = 0.0
    )

    @Test fun `crossing in valid direction arms then closes a lap with interpolated time`() {
        val engine = TimingEngine(minimumLapMs = 20_000, crossingCooldownMs = 5_000).apply { this.line = this@TimingEngineTest.line }
        engine.process(sample(44.9999, 9.0, 0))
        val armed = engine.process(sample(45.0001, 9.0, 1_000))
        assertNotNull(armed)
        engine.process(sample(44.9999, 9.0, 30_000))
        val completed = engine.process(sample(45.0001, 9.0, 31_000)) as TimingEvent.LapCompleted
        assertEquals(1, completed.lap.number)
        assertEquals(30_000, completed.lap.durationMs)
    }

    @Test fun `opposite direction does not arm the line`() {
        val engine = TimingEngine().apply { this.line = this@TimingEngineTest.line }
        engine.process(sample(45.0001, 9.0, 0))
        assertNull(engine.process(sample(44.9999, 9.0, 1_000)))
    }

    @Test fun `predictive delta follows reference order rather than jumping backwards`() {
        val reference = listOf(
            sample(45.0000, 9.0000, 0), sample(45.0001, 9.0000, 1_000),
            sample(45.0002, 9.0000, 2_000), sample(45.0003, 9.0000, 3_000)
        )
        val delta = PredictiveDelta(reference)
        assertEquals(200L, delta.calculate(sample(45.0001, 9.0000, 1_200), 0))
        assertEquals(300L, delta.calculate(sample(45.0002, 9.0000, 2_300), 0))
    }

    @Test fun `auto detector creates a line only after a plausible loop closure`() {
        val detector = AutoFinishDetector(minimumLoopMs = 40_000, proximityM = 18.0, minimumPathM = 30.0, minimumExcursionM = 25.0)
        detector.process(sample(44.9998, 9.0, 0))
        detector.process(sample(45.0000, 9.0, 1_000))
        detector.process(sample(45.0003, 9.0, 15_000))
        detector.process(sample(44.9999, 9.0, 49_000))
        assertNotNull(detector.process(sample(45.0001, 9.0, 50_000)))
    }

    @Test fun `debug simulator closes a realistic loop for the automatic detector`() {
        val simulator = DebugGpsSimulator(TrackPoint(45.0, 9.0), 0)
        val detector = AutoFinishDetector(minimumLoopMs = 20_000, proximityM = 12.0)
        var discovery: AutoFinishDetector.Discovery? = null
        repeat(360) {
            discovery = detector.process(simulator.next()) ?: discovery
            if (discovery != null) return@repeat
        }
        assertNotNull(discovery)
        assertTrue(discovery!!.lap.durationMs in 75_000..110_000)
    }

    @Test fun `auto detector ignores a small GPS orbit around one point`() {
        val detector = AutoFinishDetector(minimumLoopMs = 20_000, proximityM = 18.0, minimumPathM = 350.0, minimumExcursionM = 80.0)
        val points = listOf(
            45.00000 to 9.00000, 45.00003 to 9.00000,
            45.00000 to 9.00004, 44.99997 to 9.00000,
            45.00000 to 8.99996, 45.00000 to 9.00000
        )
        repeat(8) { loop ->
            points.forEachIndexed { index, (lat, lon) ->
                assertNull(detector.process(sample(lat, lon, (loop * points.size + index) * 10_000L)))
            }
        }
    }

    @Test fun `simulator reports both automatic sectors after loop discovery`() {
        val simulator = DebugGpsSimulator(TrackPoint(45.0, 9.0), 0)
        val detector = AutoFinishDetector(minimumLoopMs = 20_000, proximityM = 12.0)
        var discovery: AutoFinishDetector.Discovery? = null
        var last: GpsSample? = null
        repeat(400) {
            val sample = simulator.next()
            last = sample
            discovery = detector.process(sample) ?: discovery
            if (discovery != null) return@repeat
        }
        val found = discovery ?: throw AssertionError("Loop not discovered")
        val samples = found.lap.samples
        fun sectorAt(fraction: Double): TimingLine {
            val index = (samples.lastIndex * fraction).toInt().coerceIn(1, samples.lastIndex - 1)
            val point = samples[index]
            return Geo.timingLine(
                TrackPoint(point.lat, point.lon),
                Geo.headingDeg(TrackPoint(samples[index - 1].lat, samples[index - 1].lon), TrackPoint(samples[index + 1].lat, samples[index + 1].lon))
            )
        }
        val engine = TimingEngine().apply {
            line = found.line
            sectors = listOf(sectorAt(1.0 / 3.0), sectorAt(2.0 / 3.0))
            armAt(last!!, completedLapCount = 1)
        }
        val detectedSectors = mutableSetOf<Int>()
        repeat(300) {
            when (val event = engine.process(simulator.next())) {
                is TimingEvent.SectorCompleted -> detectedSectors += event.number
                else -> Unit
            }
        }
        assertEquals(setOf(1, 2), detectedSectors)
    }

    private fun sample(lat: Double, lon: Double, time: Long) = GpsSample(lat, lon, 10f, 3f, time)
}
