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
| JUnit 4 | Local unit tests only; excluded from the app | None |
