package org.wandernav.app

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.wandernav.app.gpx.GeoPoint
import org.wandernav.app.gpx.GpxParser
import org.wandernav.app.gpx.GpxRoute
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos

class MainActivity : ComponentActivity() {
    private val importExecutor = Executors.newSingleThreadExecutor()
    private var routeState by mutableStateOf<RouteScreenState>(RouteScreenState.Empty)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RouteScreen(
                        state = routeState,
                        onRouteSelected = ::importRoute,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        importExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun importRoute(uri: Uri) {
        routeState = RouteScreenState.Loading
        importExecutor.execute {
            val result = runCatching {
                val fileName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Imported route"
                contentResolver.openInputStream(uri)?.use { input ->
                    GpxParser().parse(input, fileName)
                } ?: error("The selected file could not be opened.")
            }
            runOnUiThread {
                routeState = result.fold(
                    onSuccess = RouteScreenState::Loaded,
                    onFailure = { RouteScreenState.Error(it.message ?: "The GPX could not be imported.") },
                )
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor: Cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}

private sealed interface RouteScreenState {
    data object Empty : RouteScreenState
    data object Loading : RouteScreenState
    data class Loaded(val route: GpxRoute) : RouteScreenState
    data class Error(val message: String) : RouteScreenState
}

@Composable
private fun RouteScreen(
    state: RouteScreenState,
    onRouteSelected: (Uri) -> Unit,
) {
    val routePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(onRouteSelected) },
    )
    val openPicker = {
        routePicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Wandernav", style = MaterialTheme.typography.headlineLarge)
        when (state) {
            RouteScreenState.Empty -> EmptyRoute(onImport = openPicker)
            RouteScreenState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().height(240.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is RouteScreenState.Error -> {
                Text(text = state.message, color = MaterialTheme.colorScheme.error)
                ImportButton(label = "Choose another GPX", onClick = openPicker)
            }

            is RouteScreenState.Loaded -> RoutePreview(route = state.route, onImport = openPicker)
        }
    }
}

@Composable
private fun EmptyRoute(onImport: () -> Unit) {
    Spacer(modifier = Modifier.height(80.dp))
    Text(text = "Choose a GPX route to begin.", style = MaterialTheme.typography.bodyLarge)
    ImportButton(label = "Import GPX", onClick = onImport)
}

@Composable
private fun RoutePreview(route: GpxRoute, onImport: () -> Unit) {
    Text(text = route.name, style = MaterialTheme.typography.headlineSmall)
    RouteCanvas(route = route)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Statistic("Distance", formatDistance(route.distanceMetres))
        Statistic("Ascent", route.ascentMetres?.let { "${it.toInt()} m" } ?: "–")
        Statistic("Points", route.pointCount.toString())
        Statistic("Waypoints", route.waypoints.size.toString())
    }
    route.category?.let { Text(text = "Source category: $it", style = MaterialTheme.typography.bodyMedium) }
    route.warnings.forEach { warning ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(text = warning, modifier = Modifier.padding(12.dp))
        }
    }
    Text(
        text = "Route preview only — no map or navigation is active.",
        style = MaterialTheme.typography.bodySmall,
    )
    ImportButton(label = "Choose another GPX", onClick = onImport)
}

@Composable
private fun Statistic(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ImportButton(label: String, onClick: () -> Unit) {
    Button(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Text(text = label)
    }
}

@Composable
private fun RouteCanvas(route: GpxRoute) {
    val allPoints = route.segments.flatten()
    val centreLatitude = allPoints.map { it.latitude }.average()
    val longitudeScale = cos(Math.toRadians(centreLatitude))
    val projected = allPoints.map { it.longitude * longitudeScale to it.latitude }
    val minX = projected.minOf { it.first }
    val maxX = projected.maxOf { it.first }
    val minY = projected.minOf { it.second }
    val maxY = projected.maxOf { it.second }

    Card(shape = RoundedCornerShape(16.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.15f)
                .padding(18.dp),
        ) {
            val widthRange = (maxX - minX).takeIf { it > 0.0 } ?: 1.0
            val heightRange = (maxY - minY).takeIf { it > 0.0 } ?: 1.0
            val scale = minOf(size.width / widthRange.toFloat(), size.height / heightRange.toFloat())
            val drawnWidth = widthRange.toFloat() * scale
            val drawnHeight = heightRange.toFloat() * scale
            val left = (size.width - drawnWidth) / 2f
            val top = (size.height - drawnHeight) / 2f

            fun project(point: GeoPoint): Offset = Offset(
                x = left + ((point.longitude * longitudeScale - minX).toFloat() * scale),
                y = top + ((maxY - point.latitude).toFloat() * scale),
            )

            route.segments.forEach { segment ->
                val path = Path()
                segment.forEachIndexed { index, point ->
                    val offset = project(point)
                    if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF245C3A),
                    style = Stroke(width = 7f, cap = StrokeCap.Round),
                )
            }
            route.waypoints.forEach { drawCircle(Color(0xFFF4A261), radius = 6f, center = project(it.point)) }
            drawCircle(Color(0xFF2A9D5B), radius = 10f, center = project(route.segments.first().first()))
            drawCircle(Color(0xFFD1495B), radius = 7f, center = project(route.segments.last().last()))
        }
    }
}

private fun formatDistance(distanceMetres: Double): String =
    String.format(Locale.getDefault(), "%.1f km", distanceMetres / 1_000.0)

