# Dependency policy

Wandernav does not include analytics, advertising, remote logging, user profiling, or crash-reporting SDKs.

Before adding a production dependency, record:

- the user-facing capability it enables;
- why a platform API or existing dependency is insufficient;
- release APK/AAB size impact;
- whether it opens network connections or collects data;
- transitive dependencies;
- license and maintenance status;
- how it can be replaced or removed.

Dependencies must not silently initialize network services. Optional features should remain separable from the core GPX and navigation domain.

## Initial dependency inventory

| Dependency | Purpose | Network or telemetry behavior |
| --- | --- | --- |
| AndroidX Activity Compose | Hosts the Compose UI and system activity-result contracts | None |
| Jetpack Compose Foundation | Layout, scrolling, and route canvas primitives | None |
| Jetpack Compose Material 3 | UI toolkit | None |
| AndroidX Lifecycle Compose | Connects the native map view to the activity lifecycle | None |
| MapLibre Native 13.6.1 | Vector-map rendering and future offline-region storage | Fetches only the configured map style and tile resources; contains no analytics SDK |
| JUnit 4 | Local unit tests only; excluded from the app | None |

## Map service

The beta uses the keyless OpenFreeMap Liberty vector style at `https://tiles.openfreemap.org/styles/liberty`. MapLibre attribution controls remain enabled. The style URL is isolated in the map renderer so the provider can be replaced or self-hosted later.
