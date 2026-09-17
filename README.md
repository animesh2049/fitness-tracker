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
- A Health tab fed by a Garmin watch over Bluetooth LE, with no Garmin account, no Garmin Connect and no internet: the app speaks Garmin's GFDI protocol itself, downloads the watch's FIT files (monitoring, sleep, HRV, metrics, activities), decodes them with its own FIT reader and shows steps, heart rate, Body Battery, stress, sleep with a hypnogram, HRV, intensity minutes, trends over 7 days to a year, and recorded activities with heart rate zones and routes. Watch activities that overlap a logged session are linked to it. Raw FIT files stay on the device and can be exported and imported as a zip, so USB copies from the watch load without Bluetooth. Built and tested against a Forerunner 570.

The bottom bar has three tabs: Workout (with Today, History, Progress and Plan as sections), Diet and Health.

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
- `design/` the clickable screen designs (Design Component sources plus `workout-tracker-screens.html`, which opens in any browser); `design/diet/` holds the diet planner screens; `design/health/` the Health tab, the Watch screen and the three-tab navigation.
- `app/src/main/java/com/animesh/fitnesstracker/`
  - `data/` Room entities, DAOs, database, seed data (version 3 adds the health and activity tables)
  - `domain/` pure Kotlin engines: progression, cycle, records, timer, session planning, `diet/` (meal clock, window rules, scaling, day menu, prep planner) and `health/` (day summary, sleep nights, trends, heart rate zones, session matching)
  - `repository/` coordination over the DAOs
  - `service/` the foreground timer service and its controller, the diet reminder scheduler and receivers, and the watch sync foreground service
  - `garmin/` the watch stack: `ble/` (GATT client, Multi-Link handles, reliable channel, COBS, CRC, GFDI framing), `gfdi/` (message catalogue, handshake, minimal protobuf), `sync/` (file download, raw file store, watch controller, scanner, auto sync), `fit/` (FIT decoder and profile), `fitimport/` (FIT to Room importer, zip archive)
  - `ui/` Compose screens, one package per tab (`today`, `history`, `progress`, `plan` under Workout; `diet/`; `health/` and `watch/`), plus `theme/` and `components/`
  - `backup/` JSON and CSV export and import
- `app/src/test/resources/fit/` FIT files copied from a Forerunner 570 over USB; the decoder and importer tests run against them.

## Fonts

IBM Plex Sans and IBM Plex Mono, bundled under the SIL Open Font License (see `FONT_LICENSE_OFL.txt`).

## Pairing a Garmin watch (version 0.3)

Set the watch up on the watch itself and never pair it with Garmin Connect. In the app open Health, tap Pair (or Settings, Garmin watch), put the watch in Settings, Phone, Pair phone, and tap Scan for watches. Pairing asks for the Bluetooth permissions only; there is no location permission and still no internet permission. The first sync downloads every new file on the watch; later syncs happen when you open the app after an hour, on demand, or every few hours in the background if you turn that on. Export watch data writes a zip of the raw FIT files; Import watch data reads such a zip or FIT files copied from the watch's `GARMIN` folder over USB.

Upgrading from 0.2 is an in-place install (same package); the database migrates itself. Health data is not part of the JSON backup because of its size; move it between phones with the watch zip.

## Upgrading from Workout Tracker 0.1

Version 0.2 renamed the package, so it installs as a separate app. Export a JSON backup from the old app, install this one, import the backup with "Replace everything", then uninstall the old app. A version 1 backup restores the workout data and leaves the starter meals in place.
