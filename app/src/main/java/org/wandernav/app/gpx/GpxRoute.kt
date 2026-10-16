package org.wandernav.app.gpx

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMetres: Double? = null,
)

data class Waypoint(
    val point: GeoPoint,
    val name: String? = null,
    val type: String? = null,
)

data class GpxRoute(
    val name: String,
    val creator: String?,
    val category: String?,
    val segments: List<List<GeoPoint>>,
    val waypoints: List<Waypoint>,
    val distanceMetres: Double,
    val ascentMetres: Double?,
    val descentMetres: Double?,
    val maximumPointGapMetres: Double,
    val warnings: List<String>,
) {
    val pointCount: Int = segments.sumOf { it.size }
}

