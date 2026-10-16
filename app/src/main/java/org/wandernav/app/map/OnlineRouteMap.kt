package org.wandernav.app.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.wandernav.app.gpx.GpxRoute
import org.wandernav.app.navigation.NavigationProgress
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

@Composable
fun OnlineRouteMap(
    route: GpxRoute,
    progress: NavigationProgress?,
    followLocation: Boolean,
    movementBearing: Float?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val renderer = remember { MapRenderer() }
    val mapView = remember {
        MapView(context).also(renderer::attach)
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { renderer.render(route, progress, followLocation) },
        )
        if (followLocation && progress != null) {
            LocationArrow(movementBearing ?: 0f)
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> renderer.mapView?.onStart()
                Lifecycle.Event.ON_RESUME -> renderer.mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> renderer.mapView?.onPause()
                Lifecycle.Event.ON_STOP -> renderer.mapView?.onStop()
                Lifecycle.Event.ON_DESTROY -> renderer.mapView?.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            renderer.mapView?.onStop()
            renderer.mapView?.onDestroy()
            renderer.mapView = null
        }
    }
}

@Composable
private fun LocationArrow(bearing: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color.White, radius = 17f, center = centre)
        rotate(degrees = bearing, pivot = centre) {
            val arrow = Path().apply {
                moveTo(centre.x, centre.y - 14f)
                lineTo(centre.x + 10f, centre.y + 12f)
                lineTo(centre.x, centre.y + 7f)
                lineTo(centre.x - 10f, centre.y + 12f)
                close()
            }
            drawPath(arrow, Color(0xFF1D4ED8))
        }
    }
}

private class MapRenderer {
    var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var styleReady = false
    private var latestRoute: GpxRoute? = null
    private var latestProgress: NavigationProgress? = null
    private var latestFollow = false

    fun attach(view: MapView) {
        mapView = view
        view.onCreate(null)
        view.getMapAsync { readyMap ->
            map = readyMap
            readyMap.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                styleReady = true
                addRouteLayers(style, latestRoute ?: return@setStyle)
                updatePosition(style, latestProgress)
                updateCamera()
            }
        }
    }

    fun render(route: GpxRoute, progress: NavigationProgress?, followLocation: Boolean) {
        latestRoute = route
        latestProgress = progress
        latestFollow = followLocation
        val style = map?.style ?: return
        if (!styleReady) return
        if (style.getSource(ROUTE_SOURCE) == null) addRouteLayers(style, route)
        updatePosition(style, progress)
        updateCamera()
    }

    private fun addRouteLayers(style: Style, route: GpxRoute) {
        val routeFeatures = route.segments.map { segment ->
            Feature.fromGeometry(LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }
        style.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(routeFeatures)))
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                lineColor("#245C3A"),
                lineWidth(5f),
                lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        style.addSource(GeoJsonSource(POSITION_SOURCE))
        style.addLayer(
            CircleLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(
                circleColor("#1D4ED8"),
                circleRadius(8f),
            ),
        )
    }

    private fun updatePosition(style: Style, progress: NavigationProgress?) {
        val source = style.getSourceAs<GeoJsonSource>(POSITION_SOURCE) ?: return
        if (progress != null) {
            source.setGeoJson(Point.fromLngLat(progress.location.longitude, progress.location.latitude))
        }
    }

    private fun updateCamera() {
        val route = latestRoute ?: return
        val progress = latestProgress
        val target = if (latestFollow && progress != null) {
            LatLng(progress.location.latitude, progress.location.longitude)
        } else {
            val points = route.segments.flatten()
            LatLng(points.map { it.latitude }.average(), points.map { it.longitude }.average())
        }
        val zoom = if (latestFollow && progress != null) 16.0 else previewZoom(route)
        val position = CameraPosition.Builder().target(target).zoom(zoom).build()
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(position), 500)
    }

    private fun previewZoom(route: GpxRoute): Double {
        val points = route.segments.flatten()
        val middleLatitude = points.map { it.latitude }.average()
        val latitudeSpan = (points.maxOf { it.latitude } - points.minOf { it.latitude }).coerceAtLeast(0.0001)
        val longitudeSpan = ((points.maxOf { it.longitude } - points.minOf { it.longitude }) *
            cos(Math.toRadians(middleLatitude))).coerceAtLeast(0.0001)
        val span = max(latitudeSpan, longitudeSpan)
        return (ln(360.0 / span) / ln(2.0) - 2.0).coerceIn(5.0, 16.0)
    }

    companion object {
        private const val ROUTE_SOURCE = "wandernav-route-source"
        private const val ROUTE_LAYER = "wandernav-route-layer"
        private const val POSITION_SOURCE = "wandernav-position-source"
        private const val POSITION_LAYER = "wandernav-position-layer"
    }
}
