# Fitness Tracker

An offline Android app for planning, running and tracking a strength routine, and for planning what to eat. Everything lives on the device in SQLite. There is no account, no sync and no network permission.

What it does:

- Exercises of three kinds: weight, bodyweight and timed.
- Workout groups (a day's exercises with sets) arranged into a repeating cycle with rest days.
- A Today screen that shows the right workout, with progressive overload suggestions you accept or keep.
- A session runner with set logging, rest timers that keep running with the screen off, timed sets with a get-ready countdown, supersets and personal record detection.
- History calendar, per-exercise progress charts and records.
- JSON backup and restore, CSV export.
- A Diet tab: meals with ingredients, quantities and cooking steps; a weekly plan of three meals a day; a time-of-day view of what to eat now; a prep reminder the evening before a meal that needs soaking or other preparation, and an optional reminder when each meal window opens. Reminders are local alarms with a ten minute window; the only extra permission is receiving the boot broadcast so alarms survive a restart.

## Building

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35. The Gradle wrapper fetches everything else.

```
printf 'sdk.dir=/path/to/Android/Sdk\n' > local.properties
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. A release build (`assembleRelease`) is minified and signed with the debug key so it can be sideloaded straight away; it lands in `app/build/outputs/apk/release/app-release.apk`.

Install on a phone with USB debugging enabled:

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Tests

```
JAVA_HOME=... ./gradlew testDebugUnitTest          # domain engines, pure JVM
JAVA_HOME=... ./gradlew connectedDebugAndroidTest  # Room DAO tests, needs an emulator or device
```

## Layout

- `REQUIREMENTS.md` the full requirements the app is built against.
- `design/` the clickable screen designs (Design Component sources plus `workout-tracker-screens.html`, which opens in any browser); `design/diet/` holds the diet planner screens.
- `app/src/main/java/com/animesh/fitnesstracker/`
  - `data/` Room entities, DAOs, database, seed data
  - `domain/` pure Kotlin engines: progression, cycle, records, timer, session planning, and `diet/` (meal clock, window rules, scaling, day menu, prep planner)
  - `repository/` coordination over the DAOs
  - `service/` the foreground timer service and its controller, plus the diet reminder scheduler and receivers
  - `ui/` Compose screens, one package per tab (including `diet/`), plus `theme/` and `components/`
  - `backup/` JSON and CSV export and import

## Fonts

IBM Plex Sans and IBM Plex Mono, bundled under the SIL Open Font License (see `FONT_LICENSE_OFL.txt`).

## Upgrading from Workout Tracker 0.1

Version 0.2 renamed the package, so it installs as a separate app. Export a JSON backup from the old app, install this one, import the backup with "Replace everything", then uninstall the old app. A version 1 backup restores the workout data and leaves the starter meals in place.
