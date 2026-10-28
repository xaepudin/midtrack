# Development

Setup, build and debugging notes for Midtrack. For the design, see
[architecture.md](architecture.md); for releases and signing, see
[releasing.md](releasing.md).

## App identity

| Item | Value |
| --- | --- |
| Application ID | `com.xaepudin.midtrack` |
| Namespace | `com.xaepudin.midtrack` |
| Launcher label | `Midtrack` |
| Minimum SDK | 26 (Android 8.0) |
| Target / compile SDK | 36 (Android 16) |
| Version | `1.0.0` (`versionCode` 1) |
| License | GPL-3.0 |

If the application ID changes, update it in `app/build.gradle.kts` and move the
Kotlin package directories under `app/src/main/java` and `app/src/test/java`.
Also update the notification-access command in this document and the download
URLs in `README.md`, `PRIVACY.md` and `docs/play-store.md`.

## Project layout

```
app/src/main/java/com/xaepudin/midtrack/
  MainActivity.kt             master switch, jump mode, app scope, health card
  SetupActivity.kt            first-run checklist and test jump
  AppsActivity.kt             app list, whitelist and per-app rules
  AppListAdapter.kt           RecyclerView rows and inline rule editor
  AdvancedActivity.kt         guardrails, notification, battery
  MidtrackService.kt          notification listener + media-session watcher
  SeekLogic.kt                target maths and guardrails (pure Kotlin)
  AppRules.kt, AppScope.kt    per-app rules and whitelist (pure Kotlin)
  Permissions.kt              notification access + runtime permission
  DeviceSettings.kt           battery-optimization state and settings
  Prefs.kt, Notifications.kt  storage, notification, tile
  SeekTileService.kt          Quick Settings tile
app/src/test/                 unit tests for the pure logic
docs/                         development, architecture, release, Play listing
```

## Requirements

- JDK 17 or newer. JDK 21 is what CI and the release builds use.
- Android SDK Platform 36 and Build-Tools 35+.
- A device or emulator running Android 8.0 (API 26) or newer.
- The repository includes the Gradle wrapper, so no system Gradle is needed.

On macOS, point Java at a JDK 21 installation (Android Studio's bundled JBR also
works if it is 17+):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

`local.properties` is machine-specific and gitignored. Android Studio creates it
automatically; otherwise write:

```properties
sdk.dir=/path/to/Android/sdk
```

## Build and test

```bash
./gradlew testDebugUnitTest      # pure Kotlin logic tests
./gradlew lintDebug              # Android lint; errors fail the build
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # minified release APK (signed if configured)
./gradlew bundleRelease          # Play Store AAB
```

Build outputs:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
app/build/outputs/bundle/release/app-release.aab
app/build/reports/lint-results-debug.html
```

## Install and grant access

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Android 13+: notification permission
adb shell pm grant com.xaepudin.midtrack android.permission.POST_NOTIFICATIONS

# Notification access (or Settings → Apps → Special access → Notification access)
adb shell cmd notification allow_listener \
  com.xaepudin.midtrack/com.xaepudin.midtrack.MidtrackService

adb shell am start -n com.xaepudin.midtrack/.MainActivity
```

The app only receives media sessions after notification access is granted. On
first launch it shows a disclosure dialog and opens the system screen; the adb
command above is just a shortcut for development.

## Debugging

The log tag is `Midtrack`:

```bash
adb logcat -s Midtrack
```

Useful lines:

```
listener connected — watching for media sessions
attached to media session (<pkg>) — current: <title> (baseline, no seek)
track change: <title> (dur <s>s, autoNext=true|false)
SEEK to <s>s (dur <s>s, pos <s>s)
SEEK (manual) to <s>s
no jump: track is 1:12 — shorter than the 1:30 minimum
no jump: track length unknown (live stream, ad, or no duration metadata)
<app> is playing but not selected in Apps — ignoring
foreground start failed: <reason>
```

Confirm the media session state independently:

```bash
adb shell dumpsys media_session | grep -E "state=PlaybackState|description="
adb shell dumpsys activity services com.xaepudin.midtrack
```

The second command should show `isForeground=true` and
`types=40000000` (`specialUse`) once playback has been attached.

## Reproducing a jump

1. Grant notification access and open the app; the status should say
   "Listening for media sessions".
2. Play a song, then skip to the next track. The first track is a baseline and
   is deliberately not seeked.
3. Watch `adb logcat -s Midtrack` for "track change" followed by "SEEK".

If nothing happens, check the log for a guardrail message. A track with no
reported duration (live streams, ads, some players) is never seeked by design.

## Common issues

- **`Resource mipmap/ic_launcher not found`** — adaptive icons must stay in
  `res/mipmap-anydpi-v26/`; moving them to an unqualified folder breaks resource
  linking.
- **Foreground service crash on Android 14+** — the service must declare
  `android:foregroundServiceType="specialUse"` and the app must hold
  `FOREGROUND_SERVICE_SPECIAL_USE`. The Play Console declaration is described in
  [play-store.md](play-store.md).
- **No jumps after the screen is off** — OEM battery optimization. Use the
  in-app **Open battery settings** action and choose **Unrestricted**.
- **Debug build cannot post the notification on Android 13+** — grant
  `POST_NOTIFICATIONS` as shown above, or accept the in-app prompt.

## Code conventions

- The app is small and English-only. Do not add localized resources; the build
  filters packaged locales to `en`.
- Keep `SeekLogic`, `AppRules` and `AppScope` free of Android imports so they
  stay unit-testable.
- Every user-visible behaviour that can fail should leave a log line explaining
  why. "Nothing happened" is a bug.
- Release builds run R8. Keep manifest components and anything reached only by
  reflection documented in `app/proguard-rules.pro`.

## Contributing

Bug reports and pull requests are welcome at
[github.com/xaepudin/midtrack](https://github.com/xaepudin/midtrack).

Before opening a pull request:

```bash
./gradlew testDebugUnitTest lintDebug
```

The app is deliberately small and English-only. Features that do not serve the
one job — seeking to a spot in a song — are out of scope.
