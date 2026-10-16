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
    val warnings: List<GpxWarning>,
) {
    val pointCount: Int = segments.sumOf { it.size }
}

data class GpxWarning(
    val kind: GpxWarningKind,
    val detail: String? = null,
    val count: Int? = null,
)

enum class GpxWarningKind {
    UNFAMILIAR_VERSION,
    INVALID_TRACK_POINTS,
    INVALID_WAYPOINTS,
    SPARSE_TRACK,
    NON_HIKING_CATEGORY,
    MULTIPLE_SEGMENTS,
}
