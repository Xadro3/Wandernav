# Wandernav

Wandernav is a small, offline-first Android hiking navigator. Its first goal is a fast flow from a GPX file to route preview and navigation without accounts, analytics, advertising, or unnecessary data collection.

## Current state

The app opens a GPX document using Android's system file picker, parses its track and optional waypoints, and renders a map-free route preview with basic statistics and sanity warnings. It deliberately has no internet or location permissions yet.

## Product principles

- Fast from install to navigation.
- Offline behavior is a first-class requirement.
- Collect no data that is not needed for a user-requested feature.
- Prefer Android platform APIs and small, auditable components.
- Keep original GPX input intact; derived data must remain exportable.
- Keep navigation state independent of the phone UI for future Wear OS clients.
- New dependencies must document purpose, size impact, network behavior, telemetry, and license.

## Build requirements

- Android Studio with JDK 17 or newer
- Android SDK 37

Open the repository in Android Studio and allow it to sync. A Gradle wrapper will be added once a compatible Gradle installation or Android Studio environment is available locally.

## Current import slice

1. Read the selected document through `ContentResolver`.
2. Parse GPX metadata, track segments, elevation, and waypoints with the platform XML pull parser.
3. Validate coordinates and calculate distance, ascent, descent, and point spacing.
4. Draw the route and markers on an aspect-ratio-preserving Compose canvas.

Map rendering and location permissions will be added only after this import pipeline is reliable across files from multiple sources.

## License

Wandernav is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE). You may use, study, modify, fork, and redistribute it for permitted noncommercial purposes. Selling the app, including it in a paid product, or monetizing a derivative through advertising is not permitted.

The Wandernav name and logo are not licensed for uses that imply an unofficial derivative is the original project or is endorsed by it. See [NOTICE](NOTICE) for the required attribution notice.
