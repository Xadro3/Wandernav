package org.wandernav.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wandernav.app.gpx.GeoPoint
import org.wandernav.app.gpx.GpxRoute

class NavigationEngineTest {
    private val route = GpxRoute(
        name = "Test route",
        creator = null,
        category = null,
        segments = listOf(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01))),
        waypoints = emptyList(),
        distanceMetres = 1_111.95,
        ascentMetres = null,
        descentMetres = null,
        maximumPointGapMetres = 1_111.95,
        warnings = emptyList(),
    )

    @Test
    fun locationHalfwayAlongRouteReportsHalfProgress() {
        val progress = NavigationEngine(route).update(GeoPoint(0.0, 0.005))

        assertEquals(0.5, progress.fractionComplete, 0.01)
        assertEquals(0.0, progress.distanceFromRouteMetres, 0.5)
        assertEquals(route.distanceMetres / 2, progress.distanceRemainingMetres, 2.0)
    }

    @Test
    fun offRouteLocationProjectsOntoRoute() {
        val progress = NavigationEngine(route).update(GeoPoint(0.001, 0.005))

        assertEquals(0.5, progress.fractionComplete, 0.01)
        assertTrue(progress.distanceFromRouteMetres in 110.0..112.5)
        assertEquals(0.0, progress.nearestRoutePoint.latitude, 0.00001)
    }
}

