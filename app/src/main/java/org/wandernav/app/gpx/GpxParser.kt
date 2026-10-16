package org.wandernav.app.gpx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GpxParser {
    fun parse(input: InputStream, fallbackName: String): GpxRoute {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }

        var creator: String? = null
        var metadataName: String? = null
        var trackName: String? = null
        var category: String? = null
        var inMetadata = false
        var metadataDepth = -1
        var inTrack = false
        var currentSegment: MutableList<GeoPoint>? = null
        var currentTrackPoint: MutablePoint? = null
        var currentWaypoint: MutableWaypoint? = null
        val segments = mutableListOf<List<GeoPoint>>()
        val waypoints = mutableListOf<Waypoint>()
        val warnings = linkedSetOf<String>()
        var trackPointCount = 0
        var seenGpx = false
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "gpx" -> {
                        seenGpx = true
                        creator = parser.getAttributeValue(null, "creator")
                        val version = parser.getAttributeValue(null, "version")
                        if (version != null && version !in setOf("1.0", "1.1")) {
                            warnings += "This file uses unfamiliar GPX version $version."
                        }
                    }

                    "metadata" -> {
                        inMetadata = true
                        metadataDepth = parser.depth
                    }
                    "trk" -> inTrack = true
                    "trkseg" -> currentSegment = mutableListOf()
                    "trkpt" -> currentTrackPoint = pointFromAttributes(parser, warnings)
                    "wpt" -> currentWaypoint = waypointFromAttributes(parser, warnings)
                    "ele" -> parser.nextText().toDoubleOrNull()?.let { elevation ->
                        currentTrackPoint?.elevationMetres = elevation
                        currentWaypoint?.elevationMetres = elevation
                    }

                    "name" -> {
                        val depth = parser.depth
                        val value = parser.nextText().trim().ifEmpty { null }
                        when {
                            currentWaypoint != null -> currentWaypoint.name = value
                            currentTrackPoint != null -> Unit
                            inTrack && currentSegment == null -> trackName = value
                            inMetadata && depth == metadataDepth + 1 -> metadataName = value
                        }
                    }

                    "type" -> {
                        val value = parser.nextText().trim().ifEmpty { null }
                        if (currentWaypoint != null) currentWaypoint.type = value
                        else if (inTrack && currentSegment == null) category = value
                    }

                    "oaCategory" -> if (category == null) {
                        category = parser.nextText().trim().ifEmpty { null }
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "metadata" -> {
                        inMetadata = false
                        metadataDepth = -1
                    }
                    "trk" -> inTrack = false
                    "trkpt" -> {
                        currentTrackPoint?.toGeoPoint()?.let { point ->
                            currentSegment?.add(point)
                            trackPointCount++
                            if (trackPointCount > MAX_POINTS) {
                                throw GpxParseException("This GPX contains more than $MAX_POINTS track points.")
                            }
                        }
                        currentTrackPoint = null
                    }

                    "trkseg" -> {
                        currentSegment?.takeIf { it.isNotEmpty() }?.let(segments::add)
                        currentSegment = null
                    }

                    "wpt" -> {
                        currentWaypoint?.toWaypoint()?.let(waypoints::add)
                        currentWaypoint = null
                    }
                }
            }
            event = parser.next()
        }

        if (!seenGpx) throw GpxParseException("The selected file is not a GPX document.")
        if (segments.isEmpty() || segments.all { it.size < 2 }) {
            throw GpxParseException("The GPX does not contain a usable track.")
        }

        val statistics = calculateStatistics(segments)
        if (statistics.maximumGapMetres > 250.0) {
            warnings += "Some track points are more than 250 m apart; guidance may be less precise there."
        }
        category?.takeIf { it.contains("ski", ignoreCase = true) }?.let {
            warnings += "The source classifies this route as $it rather than a hiking route."
        }
        if (segments.size > 1) warnings += "This route contains ${segments.size} separate track segments."

        return GpxRoute(
            name = trackName ?: metadataName ?: fallbackName.substringBeforeLast('.'),
            creator = creator,
            category = category,
            segments = segments,
            waypoints = waypoints,
            distanceMetres = statistics.distanceMetres,
            ascentMetres = statistics.ascentMetres,
            descentMetres = statistics.descentMetres,
            maximumPointGapMetres = statistics.maximumGapMetres,
            warnings = warnings.toList(),
        )
    }

    private fun pointFromAttributes(
        parser: XmlPullParser,
        warnings: MutableSet<String>,
    ): MutablePoint? {
        val latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
        if (latitude == null || longitude == null || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            warnings += "One or more track points had invalid coordinates and were skipped."
            return null
        }
        return MutablePoint(latitude, longitude)
    }

    private fun waypointFromAttributes(
        parser: XmlPullParser,
        warnings: MutableSet<String>,
    ): MutableWaypoint? {
        val latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
        if (latitude == null || longitude == null || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            warnings += "One or more waypoints had invalid coordinates and were skipped."
            return null
        }
        return MutableWaypoint(latitude, longitude)
    }

    private fun calculateStatistics(segments: List<List<GeoPoint>>): Statistics {
        var distance = 0.0
        var ascent = 0.0
        var descent = 0.0
        var elevationPairs = 0
        var maximumGap = 0.0

        segments.forEach { segment ->
            segment.zipWithNext().forEach { (from, to) ->
                val gap = distanceBetween(from, to)
                distance += gap
                maximumGap = maxOf(maximumGap, gap)
                if (from.elevationMetres != null && to.elevationMetres != null) {
                    val change = to.elevationMetres - from.elevationMetres
                    if (change >= 0) ascent += change else descent -= change
                    elevationPairs++
                }
            }
        }
        return Statistics(
            distanceMetres = distance,
            ascentMetres = ascent.takeIf { elevationPairs > 0 },
            descentMetres = descent.takeIf { elevationPairs > 0 },
            maximumGapMetres = maximumGap,
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

    private data class MutablePoint(
        val latitude: Double,
        val longitude: Double,
        var elevationMetres: Double? = null,
    ) {
        fun toGeoPoint() = GeoPoint(latitude, longitude, elevationMetres)
    }

    private data class MutableWaypoint(
        val latitude: Double,
        val longitude: Double,
        var elevationMetres: Double? = null,
        var name: String? = null,
        var type: String? = null,
    ) {
        fun toWaypoint() = Waypoint(GeoPoint(latitude, longitude, elevationMetres), name, type)
    }

    private data class Statistics(
        val distanceMetres: Double,
        val ascentMetres: Double?,
        val descentMetres: Double?,
        val maximumGapMetres: Double,
    )

    companion object {
        private const val EARTH_RADIUS_METRES = 6_371_000.0
        private const val MAX_POINTS = 250_000
    }
}

class GpxParseException(message: String) : Exception(message)
