package org.wandernav.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.wandernav.app.gpx.GeoPoint
import org.wandernav.app.gpx.GpxParser
import org.wandernav.app.gpx.GpxParseError
import org.wandernav.app.gpx.GpxParseException
import org.wandernav.app.gpx.GpxRoute
import org.wandernav.app.gpx.GpxWarning
import org.wandernav.app.gpx.GpxWarningKind
import org.wandernav.app.map.OnlineRouteMap
import org.wandernav.app.navigation.NavigationEngine
import org.wandernav.app.navigation.NavigationProgress
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val importExecutor = Executors.newSingleThreadExecutor()
    private var routeState by mutableStateOf<RouteScreenState>(RouteScreenState.Empty)
    private var navigationState by mutableStateOf<NavigationUiState>(NavigationUiState.Idle)
    private var pendingNavigationRoute: GpxRoute? = null
    private var navigationEngine: NavigationEngine? = null
    private var lastMovementBearing: Float? = null
    private val gpsHandler = Handler(Looper.getMainLooper())
    private val gpsLossRunnable = Runnable {
        val current = navigationState
        if (current is NavigationUiState.Active) navigationState = current.copy(gpsSignalLost = true)
    }
    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val route = pendingNavigationRoute
        pendingNavigationRoute = null
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true && route != null) beginNavigation(route)
        else navigationState = NavigationUiState.Error(NavigationError.PERMISSION_DENIED)
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasBearing()) lastMovementBearing = location.bearing
            val progress = navigationEngine?.update(
                GeoPoint(location.latitude, location.longitude, location.altitude.takeIf { location.hasAltitude() }),
            ) ?: return
            navigationState = NavigationUiState.Active(
                progress,
                location.accuracy,
                gpsSignalLost = false,
                movementBearing = lastMovementBearing,
            )
            gpsHandler.removeCallbacks(gpsLossRunnable)
            gpsHandler.postDelayed(gpsLossRunnable, GPS_LOSS_TIMEOUT_MILLIS)
        }

        override fun onProviderDisabled(provider: String) {
            navigationState = NavigationUiState.Error(NavigationError.GPS_UNAVAILABLE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        org.maplibre.android.MapLibre.getInstance(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RouteScreen(
                        state = routeState,
                        navigationState = navigationState,
                        onRouteSelected = ::importRoute,
                        onStartNavigation = ::startNavigation,
                        onStopNavigation = ::stopNavigation,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        stopNavigation()
        importExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun importRoute(uri: Uri) {
        stopNavigation()
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
                    onFailure = { error ->
                        val parseError = error as? GpxParseException
                        RouteScreenState.Error(parseError?.reason, parseError?.limit)
                    },
                )
            }
        }
    }

    private fun startNavigation(route: GpxRoute) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingNavigationRoute = route
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            )
            return
        }
        beginNavigation(route)
    }

    private fun beginNavigation(route: GpxRoute) {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            navigationState = NavigationUiState.Error(NavigationError.GPS_UNAVAILABLE)
            return
        }
        navigationEngine = NavigationEngine(route)
        lastMovementBearing = null
        navigationState = NavigationUiState.Waiting
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                1f,
                locationListener,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            navigationState = NavigationUiState.Error(NavigationError.PERMISSION_DENIED)
        }
    }

    private fun stopNavigation() {
        gpsHandler.removeCallbacks(gpsLossRunnable)
        locationManager.removeUpdates(locationListener)
        navigationEngine = null
        lastMovementBearing = null
        navigationState = NavigationUiState.Idle
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor: Cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    companion object {
        private const val GPS_LOSS_TIMEOUT_MILLIS = 15_000L
    }
}

private sealed interface RouteScreenState {
    data object Empty : RouteScreenState
    data object Loading : RouteScreenState
    data class Loaded(val route: GpxRoute) : RouteScreenState
    data class Error(val reason: GpxParseError?, val limit: Int? = null) : RouteScreenState
}

private sealed interface NavigationUiState {
    data object Idle : NavigationUiState
    data object Waiting : NavigationUiState
    data class Active(
        val progress: NavigationProgress,
        val accuracyMetres: Float,
        val gpsSignalLost: Boolean,
        val movementBearing: Float?,
    ) : NavigationUiState
    data class Error(val reason: NavigationError) : NavigationUiState
}

private enum class NavigationError {
    PERMISSION_DENIED,
    GPS_UNAVAILABLE,
}

@Composable
private fun RouteScreen(
    state: RouteScreenState,
    navigationState: NavigationUiState,
    onRouteSelected: (Uri) -> Unit,
    onStartNavigation: (GpxRoute) -> Unit,
    onStopNavigation: () -> Unit,
) {
    val routePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(onRouteSelected) },
    )
    val openPicker = {
        routePicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
    }

    if (state is RouteScreenState.Loaded && navigationState.isRunning()) {
        ActiveNavigationScreen(
            route = state.route,
            state = navigationState,
            onStop = onStopNavigation,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        when (state) {
            RouteScreenState.Empty -> EmptyRoute(onImport = openPicker)
            RouteScreenState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().height(240.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is RouteScreenState.Error -> {
                Text(text = importErrorText(state), color = MaterialTheme.colorScheme.error)
                ImportButton(label = stringResource(R.string.choose_another_gpx), onClick = openPicker)
            }

            is RouteScreenState.Loaded -> RoutePreview(
                route = state.route,
                navigationState = navigationState,
                onImport = openPicker,
                onStartNavigation = { onStartNavigation(state.route) },
                onStopNavigation = onStopNavigation,
            )
        }
    }
}

private fun NavigationUiState.isRunning(): Boolean =
    this is NavigationUiState.Waiting || this is NavigationUiState.Active ||
        (this is NavigationUiState.Error && reason == NavigationError.GPS_UNAVAILABLE)

@Composable
private fun EmptyRoute(onImport: () -> Unit) {
    Spacer(modifier = Modifier.height(80.dp))
    Text(text = stringResource(R.string.choose_gpx_prompt), style = MaterialTheme.typography.bodyLarge)
    ImportButton(label = stringResource(R.string.import_gpx), onClick = onImport)
}

@Composable
private fun RoutePreview(
    route: GpxRoute,
    navigationState: NavigationUiState,
    onImport: () -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
) {
    Text(text = route.name, style = MaterialTheme.typography.headlineSmall)
    RouteCanvas(route = route, progress = (navigationState as? NavigationUiState.Active)?.progress)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Statistic(stringResource(R.string.distance), stringResource(R.string.distance_kilometres, route.distanceMetres / 1_000.0))
        Statistic(stringResource(R.string.ascent), route.ascentMetres?.let { stringResource(R.string.elevation_metres, it.toInt()) } ?: "–")
        Statistic(stringResource(R.string.points), route.pointCount.toString())
        Statistic(stringResource(R.string.waypoints), route.waypoints.size.toString())
    }
    route.category?.let { Text(text = stringResource(R.string.source_category, it), style = MaterialTheme.typography.bodyMedium) }
    route.warnings.forEach { warning ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(text = warningText(warning), modifier = Modifier.padding(12.dp))
        }
    }
    if (navigationState is NavigationUiState.Idle) {
        Text(
            text = stringResource(R.string.preview_only_notice),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    NavigationPanel(
        state = navigationState,
        onStart = onStartNavigation,
        onStop = onStopNavigation,
    )
    ImportButton(label = stringResource(R.string.choose_another_gpx), onClick = onImport)
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
private fun ActiveNavigationScreen(
    route: GpxRoute,
    state: NavigationUiState,
    onStop: () -> Unit,
) {
    val active = state as? NavigationUiState.Active
    Box(modifier = Modifier.fillMaxSize()) {
        OnlineRouteMap(
            route = route,
            progress = active?.progress,
            followLocation = active != null,
            movementBearing = active?.movementBearing,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(route.name, style = MaterialTheme.typography.titleMedium)
                    when (state) {
                        NavigationUiState.Waiting -> Text(stringResource(R.string.waiting_for_gps))
                        is NavigationUiState.Active -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Statistic(
                                stringResource(R.string.progress),
                                stringResource(R.string.percentage, (state.progress.fractionComplete * 100).toInt()),
                            )
                            Statistic(
                                stringResource(R.string.remaining),
                                stringResource(R.string.distance_kilometres, state.progress.distanceRemainingMetres / 1_000.0),
                            )
                            Statistic(
                                stringResource(R.string.off_route_distance),
                                stringResource(R.string.distance_metres, state.progress.distanceFromRouteMetres.toInt()),
                            )
                        }
                        is NavigationUiState.Error -> Text(stringResource(R.string.gps_unavailable))
                        NavigationUiState.Idle -> Unit
                    }
                }
            }
            if (active?.gpsSignalLost == true) SevereWarning(stringResource(R.string.warning_gps_lost))
            if (active != null && active.progress.distanceFromRouteMetres >= SEVERE_DEVIATION_METRES) {
                SevereWarning(
                    stringResource(
                        R.string.warning_route_deviation,
                        active.progress.distanceFromRouteMetres.toInt(),
                    ),
                )
            }
        }
        Button(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .fillMaxWidth()
                .padding(16.dp),
            onClick = onStop,
        ) {
            Text(stringResource(R.string.stop_navigation))
        }
    }
}

@Composable
private fun SevereWarning(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(message, modifier = Modifier.fillMaxWidth().padding(12.dp))
    }
}

@Composable
private fun NavigationPanel(
    state: NavigationUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    when (state) {
        NavigationUiState.Idle -> ImportButton(stringResource(R.string.start_navigation), onStart)
        NavigationUiState.Waiting -> {
            Text(stringResource(R.string.waiting_for_gps), style = MaterialTheme.typography.bodyLarge)
            ImportButton(stringResource(R.string.stop_navigation), onStop)
        }
        is NavigationUiState.Active -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Statistic(
                    stringResource(R.string.progress),
                    stringResource(R.string.percentage, (state.progress.fractionComplete * 100).toInt()),
                )
                Statistic(
                    stringResource(R.string.remaining),
                    stringResource(R.string.distance_kilometres, state.progress.distanceRemainingMetres / 1_000.0),
                )
                Statistic(
                    stringResource(R.string.off_route_distance),
                    stringResource(R.string.distance_metres, state.progress.distanceFromRouteMetres.toInt()),
                )
                Statistic(
                    stringResource(R.string.gps_accuracy),
                    stringResource(R.string.distance_metres, state.accuracyMetres.toInt()),
                )
            }
            ImportButton(stringResource(R.string.stop_navigation), onStop)
        }
        is NavigationUiState.Error -> {
            Text(
                text = when (state.reason) {
                    NavigationError.PERMISSION_DENIED -> stringResource(R.string.location_permission_denied)
                    NavigationError.GPS_UNAVAILABLE -> stringResource(R.string.gps_unavailable)
                },
                color = MaterialTheme.colorScheme.error,
            )
            ImportButton(stringResource(R.string.start_navigation), onStart)
        }
    }
}

@Composable
private fun RouteCanvas(route: GpxRoute, progress: NavigationProgress?) {
    Card(shape = RoundedCornerShape(16.dp)) {
        OnlineRouteMap(
            route = route,
            progress = progress,
            followLocation = false,
            movementBearing = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.15f)
        )
    }
}

private const val SEVERE_DEVIATION_METRES = 50.0

@Composable
private fun importErrorText(error: RouteScreenState.Error): String = when (error.reason) {
    GpxParseError.NOT_GPX -> stringResource(R.string.error_not_gpx)
    GpxParseError.NO_USABLE_TRACK -> stringResource(R.string.error_no_usable_track)
    GpxParseError.TOO_MANY_POINTS -> stringResource(R.string.error_too_many_points, error.limit ?: 250_000)
    null -> stringResource(R.string.error_import_failed)
}

@Composable
private fun warningText(warning: GpxWarning): String = when (warning.kind) {
    GpxWarningKind.UNFAMILIAR_VERSION -> stringResource(R.string.warning_gpx_version, warning.detail.orEmpty())
    GpxWarningKind.INVALID_TRACK_POINTS -> stringResource(R.string.warning_invalid_track_points)
    GpxWarningKind.INVALID_WAYPOINTS -> stringResource(R.string.warning_invalid_waypoints)
    GpxWarningKind.SPARSE_TRACK -> stringResource(R.string.warning_sparse_track)
    GpxWarningKind.NON_HIKING_CATEGORY -> stringResource(R.string.warning_non_hiking, warning.detail.orEmpty())
    GpxWarningKind.MULTIPLE_SEGMENTS -> {
        val count = warning.count ?: 0
        LocalContext.current.resources.getQuantityString(R.plurals.warning_multiple_segments, count, count)
    }
}
