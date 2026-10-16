package org.wandernav.app.model

/** Stable identifiers prevent UI, storage, and future watch clients from sharing implementation details. */
@JvmInline
value class RouteId(val value: String)

@JvmInline
value class NavigationSessionId(val value: String)

enum class NavigationStatus {
    IDLE,
    ACTIVE,
    PAUSED,
    FINISHED,
}

data class NavigationSnapshot(
    val sessionId: NavigationSessionId,
    val routeId: RouteId,
    val routeName: String,
    val status: NavigationStatus,
    val elapsedSeconds: Long,
    val distanceTravelledMetres: Double,
    val distanceRemainingMetres: Double?,
    val distanceFromRouteMetres: Double?,
)

