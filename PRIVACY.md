# Privacy policy — Midtrack

**Last updated: 27 October 2026**

Midtrack is an Android app that seeks to a chosen point in a new song. This
policy explains what the app accesses and what it does with that information.

## The short version

- Midtrack does **not** collect, sell, share or transmit personal data.
- The app has **no internet permission**. Nothing it sees can leave your phone.
- There are no ads, analytics, crash-reporting SDKs or accounts.
- Notification access is used only to reach Android's media-session API, not to
  read your notifications.

## Who is responsible

Midtrack is developed by Asep Saepudin. For privacy questions, open an issue
at <https://github.com/xaepudin/midtrack/issues>. Please do not include
personal information in a public issue.

## What the app accesses

### Notification access

Android only exposes active media sessions to an approved notification listener.
Midtrack therefore asks for notification access. With that access it reads
the **active media session** of the app that is playing music:

- the track title;
- the duration;
- the playback position and state;
- the package name of the player.

This information is used in memory to decide whether and where to seek. The app
does not read, parse, store or transmit the content of your notifications. The
in-app disclosure shown before the system screen states this, and the code only
uses the listener as the gate for `MediaSessionManager.getActiveSessions()`.

### Notification permission

On Android 13+, the app asks for permission to post notifications. It uses this
only for the ongoing playback-controls notification (and the Quick Settings
tile's state). You can deny or revoke it; the seek feature can still work, but
the controls will not be visible.

### Foreground service

While music is playing, Midtrack runs a foreground service with the
`specialUse` type so Android keeps the media-session listener attached. This is
local processing; no data is sent anywhere.

### Battery optimization

Android may stop background listeners when the screen is off. The app shows
whether it is exempt from battery optimization and offers a button that opens
the system battery settings. It does **not** request the restricted
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission.

## What is stored on your device

Midtrack stores a small settings file locally:

- your jump mode, numbers and guardrails;
- per-app rules, the app whitelist, and packages that have held a media session;
- the package and title of the track currently attached, so the notification can
  show it;
- the title and position of the last jump, for the "Last jump" row.

This data never leaves the device. It is excluded from Android cloud backup and
device-to-device transfer by `res/xml/data_extraction_rules.xml` and
`res/xml/backup_rules.xml`. Uninstalling the app deletes all of it.

## What is not collected

Midtrack does not collect or share:

- names, email addresses or account identifiers;
- location, contacts, calendar, photos, files or microphone data;
- advertising identifiers;
- app usage analytics or crash reports;
- notification content;
- listening history beyond the last track title kept locally for display.

There is no server, no database and no third-party analytics.

## Third parties

The app contains no advertising, analytics or tracking SDKs. The Android
libraries it uses (AndroidX and Material Components) run locally and do not send
data. Downloading the app from GitHub is a GitHub service; GitHub's own privacy
policy applies to that download, not to the app's operation.

## Children

Midtrack is a general-purpose music utility and is not directed at children
under 13. It collects no personal data from anyone.

## Security

Because no data is transmitted, there is no data in transit to protect. Local
settings are protected by Android's app sandbox. The release APK is signed; the
certificate fingerprint is published in `docs/releasing.md`.

## Changes

If this policy changes, the new version will be committed to this repository
with a new "last updated" date. Material changes will be noted in the release
notes.

## Contact

- Issues: <https://github.com/xaepudin/midtrack/issues>
