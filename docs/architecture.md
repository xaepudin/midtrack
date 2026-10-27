# Architecture

Midtrack is a single-module Android app with no backend and no network
permission. Its one job is to notice a track change in another app and call
`seekTo()` on that app's media session.

## Components

| Screen / component | Responsibility |
| --- | --- |
| `MainActivity` | Master switch, jump mode, app scope, selected-app summary, live status and the health card. It deliberately does not host the full app list or the advanced settings. |
| `SetupActivity` | First-run checklist: notification access, notifications, battery reliability and a test jump. Reopenable from Advanced settings. |
| `AppsActivity` | The app list, whitelist switches and per-app rule editors. Loads package data on a background executor and renders it with `AppListAdapter`. |
| `AdvancedActivity` | Guardrails, "Always show notification", "Also seek on natural end", the battery shortcut and a link back to the setup checklist. |
| `Permissions` / `DeviceSettings` | The notification disclosure, runtime permission, and the standard battery-optimization screen. |
| `MidtrackService` | Notification listener and media-session watcher; computes and applies seeks. |
| `Notifications` / `MidtrackActionReceiver` | Ongoing controls and the broadcast actions behind them. |
| `SeekTileService` | Quick Settings on/off. |
| `Prefs` | One `SharedPreferences` file for the global rule, per-app rules, scope and small UI state. |

```
MainActivity ──startForegroundService──► MidtrackService
AppsActivity ──startForegroundService──►   │  getActiveSessions / seekTo
AdvancedActivity                          │
      │                                   ▼
      └────────── Prefs ◄────── Notifications
```

`SeekLogic`, `AppRules` and `AppScope` are pure Kotlin with no Android imports,
so the decision layer is tested on the JVM.

## Setup and health

`SetupActivity` is offered once on first launch (`setupOffered`), and its
`setupCompleted` flag is set by **Done** or **Skip for now**. The checklist
shows live status for notification access, app notifications and battery
optimization, and sends a **Jump now** broadcast to test the current track.

After setup, `MainActivity.renderHealth()` shows a single card for the first
problem it finds: unfinished setup, missing notification access, notifications
turned off, or battery optimization. The card links to the relevant system
screen or to the setup checklist. Health is recomputed on resume and after the
runtime permission result.

## Why notification access is required

`MediaSessionManager.getActiveSessions()` only returns sessions to a component
that the user has approved as a notification listener. The service therefore
extends `NotificationListenerService`, and the app explains the disclosure
before sending the user to the system screen.

The app does not read notification content. The listener is used only as the
gate that unlocks the media-session API. This is stated in the in-app disclosure,
the README and the privacy policy.

## App picker

`AppsActivity` runs its PackageManager/media-session queries on a single-thread
executor and posts the result back to the main thread. `AppListAdapter` renders
rows in a RecyclerView and owns the inline `AppRuleEditor`, so scrolling does
not rebuild the whole list.

The default set is live sessions, selected apps, pinned apps, declared music
apps and apps seen before. Media-browser apps, audio handlers and the rest of
the launcher only join when **Show all apps** is on. Rows are ranked playing,
pinned, player, other; the search field is always available; **Select all**
picks the player rows and **Clear all** empties the whitelist. The adapter keeps
a map of state labels so a per-app rule edit updates the row without
re-inflating the editor and losing focus.

`AppsActivity` saves the search query, expanded rule and list scroll state, and
restores them after the asynchronous reload.

## Session selection

`pickBestSession()` scores every active controller:

1. `STATE_PLAYING` adds `1_000_000`.
2. Having metadata adds `100_000`.
3. `lastPositionUpdateTime` breaks ties.

Controllers belonging to Midtrack itself are skipped. If app scope is
`selected`, packages outside the whitelist are skipped and logged. Every package
seen holding a session is remembered so the picker can offer it later.

## Track-change detection

Media sessions are noisy: apps re-post metadata, recreate sessions at a track
boundary, and sometimes report duration only on a later update. The service
handles this in three layers:

- **Metadata callback** — snapshot the previous position, ignore metadata
  without a duration, deduplicate by media ID, and absorb a same-title re-post
  within 3 seconds of attaching.
- **Session recreation** — when a session is replaced and the new position is
  near the start (or clearly stale), evaluate a jump, but only after the first
  attach. Deduplication prevents a double seek.
- **Natural end** — `isAutoNext(previousPosition, previousDuration)` is true
  within the last 3 seconds, which lets the "Also seek on natural end" option
  distinguish an automatic next track from a manual skip.

On the first attach, the current track is adopted as a baseline. This is what
prevents the app from yanking a song that was already halfway through when the
service started.

## Target computation and guardrails

`SeekLogic.computeTarget(durationMs, config)`:

1. Refuse a track whose duration is unknown (`<= 1s`). There is no safe target
   without a length; live streams, ads and players without
   `METADATA_KEY_DURATION` are left alone. This is not the guardrail toggle and
   always applies.
2. If `guardrailsEnabled` is true, apply the minimum-length guardrail
   (`minDurationSec`). The master switch is stored globally and is off by
   default; per-app rules cannot turn it on.
3. Compute the raw target:
   - `absolute` — the configured number of seconds;
   - `relative` — `duration * percent`;
   - `random` — a uniform value in `[randomMin, randomMax]` percent.
4. Clamp to at least 0.5 s and at most `duration - 2 s`, then apply the maximum
   jump percentage when guardrails are enabled. The result never lands on the
   very end.

`Rejected` tracks are logged with `skipReason()`, so a missing jump is always
explainable.

Additional seek-time guards:

- The "already past the target" check is skipped when the position looks stale
  (still the previous track's end, or unchanged from before the change).
- The same media ID is not seeked twice within 30 seconds.
- **Jump now** deliberately ignores the minimum length and the past-target
  guard, because the user explicitly asked for it. It still refuses unknown
  durations.

## Per-app rules and scope

Settings have two independent layers:

- **Where** — `SeekLogic.Config` (mode, numbers, guardrails, auto-next). The
  global default applies to every app; an app can store its own encoded copy
  (`v1;mode=…;pct=…`) and override the default.
- **Whether** — the master switch and `AppScope` (`all` or `selected` plus the
  whitelist). A rule never turns an app on; it only changes where a jump lands.

The first edit in an app's rule panel seeds the app rule from the current
default. "Use default settings" removes the override. Changes are written on
every keystroke and are read by the service on the next track, with no restart.

The guardrails master switch is global, not per app. A per-app rule can store
its own minimum and maximum, but it cannot turn guardrails on; while the global
switch is off, those fields are disabled in the per-app editor too.

## Notification, actions and tile

`Notifications.build()` is the single source of truth for the ongoing
notification. It is rebuilt from `Prefs` by the service, the action receiver,
the tile and the activities, so all surfaces stay consistent. The channel is
`IMPORTANCE_LOW`, `setOnlyAlertOnce(true)`, and `setShowWhen(false)`; actions are
`Turn on/off`, `Mode: …`, and `Jump now`.

The action receiver is not exported and receives explicit broadcasts from the
notification's `PendingIntent`. `Jump now` is forwarded to the running service
as an in-process broadcast rather than starting a new service, because starting
a foreground service from the background is restricted on Android 12+.

The Quick Settings tile reads and writes the same `enabled` pref. It does not
hold a `MediaController`; it only flips the switch and reposts the notification.

## Lifecycle and foreground service

`MidtrackService` is started with `startForegroundService()` when an activity
resumes with notification access granted, and is also bound by the system as a
notification listener. It runs as a foreground service so the process is less
likely to be killed while music plays.

The manifest type is `specialUse`, subtype `media_session_control` — the
Play-compliant type for a media-session controller that does not play audio
itself. `startForeground()` uses the two-argument form so the declared type is
applied on every API level.

The service deliberately survives task removal (`stopWithTask="false"`) and
re-posts a swiped-away ongoing notification when "Always show notification" is
on (off by default). The service never stops itself on task removal; users stop it with the
notification's **Turn off** action, which leaves the listener bound but makes
the app inert.

## Storage and privacy

`Prefs` is a small `SharedPreferences` file named `settings`. It holds:

- the global rule and per-app overrides;
- the app whitelist, seen packages and picker state;
- the last attached package and track title, used by the notification and the
  "Last jump" row.

No track history or notification content is stored. The file is excluded from
cloud backup and device transfer in `res/xml/data_extraction_rules.xml` and
`res/xml/backup_rules.xml`. The app has no `INTERNET` permission.

## Package visibility

Android 11+ requires package queries to be declared. The manifest declares four
targeted queries: music apps (`CATEGORY_APP_MUSIC`), launcher apps, audio
handlers, and `MediaBrowserService` apps. The picker also learns packages from
live media sessions. There is no `QUERY_ALL_PACKAGES` permission; "Show all
apps" uses the declared launcher query.

## Testing

JVM tests cover target maths, guardrails, natural-end detection, mode cycling,
label formatting, rule encoding/decoding and whitelist behaviour. The Android
layers (notification listener, notification, tile, UI) are exercised manually
on a device; `docs/development.md` contains the adb commands.
