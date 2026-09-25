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

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. A release build (`assembleRelease`) is minified; it lands in `app/build/outputs/apk/release/app-release.apk`.

Install on a phone with USB debugging enabled:

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Signing

There are no prebuilt APKs: build and sign your own. Android only lets an app be updated by an APK signed with the same key, so the key decides who can ship updates to an installed copy. Without any setup, release builds are signed with the local debug key, which is enough for building and sideloading your own copy on your own phone. To sign with a key of your own that survives reinstalling the toolchain, create one once and describe it in a `keystore.properties` file in the project root (gitignored):

```
keytool -genkeypair -v -keystore release.jks -alias fitness-tracker -keyalg RSA -keysize 4096 -validity 10000
printf 'storeFile=release.jks\nstorePassword=...\nkeyAlias=fitness-tracker\nkeyPassword=...\n' > keystore.properties
```

Keep the keystore and its passwords out of git and backed up somewhere safe: losing them means future releases cannot update existing installs. Switching an installed copy from one key to another requires uninstalling first; export a JSON backup and the watch data zip before you do, and import them into the new install.

### Versions

Versions are git tags (`v0.4.0` and so on); check one out and build it. Nothing is downloaded at runtime and the app has no update mechanism, so moving to a newer version is a matter of building it and installing over the old one with the same key.

## Tests

```
JAVA_HOME=... ./gradlew testDebugUnitTest          # domain engines, pure JVM
JAVA_HOME=... ./gradlew connectedDebugAndroidTest  # Room DAO tests, needs an emulator or device
```

## Layout

- `REQUIREMENTS.md` the full requirements the app is built against.
- `design/` the clickable screen designs (Design Component sources plus `workout-tracker-screens.html`, which opens in any browser); `design/diet/` holds the diet planner screens; `design/health/` the Health tab, the Watch screen and the three-tab navigation; `design/health2/health-0-5.html` the version 0.5 screens as one self-contained page.
- `app/src/main/java/com/animesh/fitnesstracker/`
  - `data/` Room entities, DAOs, database, seed data (version 3 adds the health and activity tables)
  - `domain/` pure Kotlin engines: progression, cycle, records, timer, session planning, `diet/` (meal clock, window rules, scaling, day menu, prep planner) and `health/` (day summary, sleep nights, trends, heart rate zones, session matching)
  - `repository/` coordination over the DAOs
  - `service/` the foreground timer service and its controller, the diet reminder scheduler and receivers, and the watch sync foreground service
  - `garmin/` the watch stack: `ble/` (GATT client, Multi-Link handles, reliable channel, COBS, CRC, GFDI framing), `gfdi/` (message catalogue, handshake, minimal protobuf), `sync/` (file download, raw file store, watch controller, scanner, auto sync), `fit/` (FIT decoder and profile), `fitimport/` (FIT to Room importer, zip archive)
  - `ui/` Compose screens, one package per tab (`today`, `history`, `progress`, `plan` under Workout; `diet/`; `health/` and `watch/`), plus `theme/` and `components/`
  - `backup/` JSON and CSV export and import
- `app/src/test/resources/fit/` synthetic Forerunner 570 style FIT files generated by `SyntheticFixtures`; the decoder and importer tests run against them.

## Privacy

The app declares no internet permission and no location permission, so nothing it stores can leave the phone through the app. Bluetooth is used only to talk to the paired watch. There are no accounts, no analytics and no crash reporting. Everything lives in the app's SQLite database and, for the watch, in raw FIT files under the app's private storage; the only ways data leaves are the export buttons, which write files where you choose.

## How the Garmin part works, and its license

The watch is spoken to directly over Bluetooth LE using Garmin's own device protocol (GFDI over the Multi-Link service), the same one the Garmin Connect app uses, with no Garmin account and no server involved. The app downloads the watch's FIT files (monitoring, sleep, HRV, metrics, activities), decodes them with its own FIT reader, and can upload a workout FIT file the other way. The protocol knowledge and the meaning of Garmin's undocumented FIT messages come from studying [Gadgetbridge](https://gadgetbridge.org), which is licensed under the AGPL-3.0. This project is therefore released under the **GNU Affero General Public License v3.0** as well; see `LICENSE` and `NOTICE`. Garmin, Forerunner and Body Battery are trademarks of Garmin Ltd.; this project is not affiliated with or endorsed by Garmin. It has only been used with a Forerunner 570.

## Fonts

IBM Plex Sans and IBM Plex Mono, bundled under the SIL Open Font License (see `FONT_LICENSE_OFL.txt`).

## Pairing a Garmin watch (version 0.3)

Set the watch up on the watch itself and never pair it with Garmin Connect. In the app open Health, tap Pair (or Settings, Garmin watch), put the watch in Settings, Phone, Pair phone, and tap Scan for watches. Pairing asks for the Bluetooth permissions only; there is no location permission and still no internet permission. The first sync downloads every new file on the watch; later syncs happen when you open the app after an hour, on demand, or every few hours in the background if you turn that on. Export watch data writes a zip of the raw FIT files; Import watch data reads such a zip or FIT files copied from the watch's `GARMIN` folder over USB.

Upgrading from 0.2 is an in-place install (same package); the database migrates itself. Health data is not part of the JSON backup because of its size; move it between phones with the watch zip.

## Version 0.4: session navigation and workouts on the watch

In a session the previous and next arrows, the progress segments and the Today's list move between exercises in any state; Skip only marks an exercise and can be undone with Resume. The rest timer shows the current exercise with its set progress and lets you peek at the others while it keeps running. On the Today screen, Send to watch turns the day's plan into a Garmin workout file (rep sets, timed sets, warm-ups, rests, supersets, exercise names from Garmin's catalogue or a custom title) and uploads it over Bluetooth; the watch lists it under Training, Workouts, and the next send replaces it. Save file instead writes the same file for copying into the watch's `GARMIN/NewFiles` folder over USB.

## Version 0.5: more from the watch

The Health tab now shows more of what the Forerunner already writes. Health today has a Floors tile (3 m of ascent per floor, the watch's own rule) and a Calories tile with the day's total (the watch's resting metabolic rate, prorated through today, plus active calories); the Body Battery card lists what charged and drained it and opens a Body Battery day screen with the watch's own event list and the overnight gain. Sleep night gains the watch's score breakdown (duration, quality, deep, light, REM, restlessness, interruptions, awake time, awakenings, recovery), the Sleep Coach need for the night compared with the time asleep, and Body Battery at sleep start and end. Activity detail shows performance condition and the watch's training benefit label when the file carries them. Trends adds Floors, Calories (resting and active stacked) and Sleep vs need.

The database moves to version 4 and the upgrade rebuilds the health rows from the raw files already on the phone, so no watch is needed for the new columns to fill; the Watch screen shows the rebuild while it runs. The sync also starts downloading the watch's skin temperature and Health Snapshot files; they are stored and decoded as far as the watch has been seen to write them (the skin temperature value itself waits for a watch with a baseline). Two labels are provisional until checked against the watch's own screens: the Body Battery event kinds beyond sleep and activity, and the training benefit codes.

## Upgrading from Workout Tracker 0.1

Version 0.2 renamed the package, so it installs as a separate app. Export a JSON backup from the old app, install this one, import the backup with "Replace everything", then uninstall the old app. A version 1 backup restores the workout data and leaves the starter meals in place.
