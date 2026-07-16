package it.crono

import kotlin.math.*

object Geo {
    private const val EarthRadiusM = 6_371_000.0

    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = EarthRadiusM
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dp = Math.toRadians(bLat - aLat)
        val dl = Math.toRadians(bLon - aLon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun nearestDistanceM(p: TrackPoint, route: List<TrackPoint>): Double =
        route.minOfOrNull { distanceM(p.lat, p.lon, it.lat, it.lon) } ?: Double.MAX_VALUE

    fun nearestRouteIndex(p: TrackPoint, route: List<TrackPoint>): Int =
        route.indices.minByOrNull { distanceM(p.lat, p.lon, route[it].lat, route[it].lon) } ?: 0

    fun headingDeg(from: TrackPoint, to: TrackPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angleDifferenceDeg(a: Double, b: Double): Double {
        val raw = (a - b + 540.0) % 360.0 - 180.0
        return abs(raw)
    }

    fun offset(center: TrackPoint, eastM: Double, northM: Double): TrackPoint {
        val lat = center.lat + Math.toDegrees(northM / EarthRadiusM)
        val lon = center.lon + Math.toDegrees(eastM / (EarthRadiusM * cos(Math.toRadians(center.lat))))
        return TrackPoint(lat, lon)
    }

    fun timingLine(center: TrackPoint, travelHeadingDeg: Double, widthM: Double = 24.0): TimingLine {
        val lineHeading = Math.toRadians(travelHeadingDeg + 90.0)
        val half = widthM / 2.0
        val east = sin(lineHeading) * half
        val north = cos(lineHeading) * half
        return TimingLine(offset(center, -east, -north), offset(center, east, north), travelHeadingDeg)
    }

    /** Returns the fraction along AB where AB intersects CD, in a local metric plane. */
    fun segmentIntersectionFraction(a: TrackPoint, b: TrackPoint, c: TrackPoint, d: TrackPoint): Double? {
        val bx = localEastM(a, b); val by = localNorthM(a, b)
        val cx = localEastM(a, c); val cy = localNorthM(a, c)
        val dx = localEastM(a, d); val dy = localNorthM(a, d)
        val rx = bx; val ry = by
        val sx = dx - cx; val sy = dy - cy
        val denominator = rx * sy - ry * sx
        if (abs(denominator) < 0.00001) return null
        val t = (cx * sy - cy * sx) / denominator
        val u = (cx * ry - cy * rx) / denominator
        return if (t in 0.0..1.0 && u in 0.0..1.0) t else null
    }

    private fun localEastM(origin: TrackPoint, point: TrackPoint): Double =
        Math.toRadians(point.lon - origin.lon) * EarthRadiusM * cos(Math.toRadians(origin.lat))

    private fun localNorthM(origin: TrackPoint, point: TrackPoint): Double =
        Math.toRadians(point.lat - origin.lat) * EarthRadiusM
}
