package org.wandernav.app.navigation

import org.wandernav.app.gpx.GeoPoint
import org.wandernav.app.gpx.GpxRoute
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NavigationProgress(
    val location: GeoPoint,
    val nearestRoutePoint: GeoPoint,
    val distanceFromRouteMetres: Double,
    val distanceAlongRouteMetres: Double,
    val distanceRemainingMetres: Double,
    val fractionComplete: Double,
)

class NavigationEngine(private val route: GpxRoute) {
    fun update(location: GeoPoint): NavigationProgress {
        var best: Candidate? = null
        var completedDistance = 0.0

        route.segments.forEach { segment ->
            segment.zipWithNext().forEach { (from, to) ->
                val segmentLength = distanceBetween(from, to)
                val candidate = project(location, from, to, completedDistance, segmentLength)
                if (best == null || candidate.distanceMetres < best!!.distanceMetres) best = candidate
                completedDistance += segmentLength
            }
        }

        val match = best ?: error("A navigable route requires at least two points.")
        val remaining = (route.distanceMetres - match.distanceAlongRouteMetres).coerceAtLeast(0.0)
        return NavigationProgress(
            location = location,
            nearestRoutePoint = match.point,
            distanceFromRouteMetres = match.distanceMetres,
            distanceAlongRouteMetres = match.distanceAlongRouteMetres,
            distanceRemainingMetres = remaining,
            fractionComplete = if (route.distanceMetres > 0) {
                (match.distanceAlongRouteMetres / route.distanceMetres).coerceIn(0.0, 1.0)
            } else 0.0,
        )
    }

    private fun project(
        location: GeoPoint,
        from: GeoPoint,
        to: GeoPoint,
        completedDistance: Double,
        segmentLength: Double,
    ): Candidate {
        val referenceLatitude = Math.toRadians(location.latitude)
        val metresPerDegreeLatitude = Math.PI * EARTH_RADIUS_METRES / 180.0
        val metresPerDegreeLongitude = metresPerDegreeLatitude * cos(referenceLatitude)
        val fromX = (from.longitude - location.longitude) * metresPerDegreeLongitude
        val fromY = (from.latitude - location.latitude) * metresPerDegreeLatitude
        val toX = (to.longitude - location.longitude) * metresPerDegreeLongitude
        val toY = (to.latitude - location.latitude) * metresPerDegreeLatitude
        val deltaX = toX - fromX
        val deltaY = toY - fromY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        val fraction = if (lengthSquared == 0.0) 0.0 else {
            (-(fromX * deltaX + fromY * deltaY) / lengthSquared).coerceIn(0.0, 1.0)
        }
        val projectedX = fromX + fraction * deltaX
        val projectedY = fromY + fraction * deltaY
        return Candidate(
            point = GeoPoint(
                latitude = from.latitude + fraction * (to.latitude - from.latitude),
                longitude = from.longitude + fraction * (to.longitude - from.longitude),
            ),
            distanceMetres = sqrt(projectedX * projectedX + projectedY * projectedY),
            distanceAlongRouteMetres = completedDistance + segmentLength * fraction,
        )
    }

    private fun distanceBetween(from: GeoPoint, to: GeoPoint): Double {
        val latitude1 = Math.toRadians(from.latitude)
        val latitude2 = Math.toRadians(to.latitude)
        val latitudeDelta = latitude2 - latitude1
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(latitude1) * cos(latitude2) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return EARTH_RADIUS_METRES * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private data class Candidate(
        val point: GeoPoint,
        val distanceMetres: Double,
        val distanceAlongRouteMetres: Double,
    )

    companion object {
        private const val EARTH_RADIUS_METRES = 6_371_000.0
    }
}

