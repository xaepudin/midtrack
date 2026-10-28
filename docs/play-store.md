# Google Play listing

Midtrack is **not published on Google Play yet**. This file is the working
copy for the store listing and the Play Console declarations, so a future
submission does not have to rediscover them.

## Store listing

| Field | Value |
| --- | --- |
| App name | Midtrack |
| Category | Music & Audio |
| Tags | music, player, utility, playback |
| Contact | Set a support email in Play Console; it is not stored in this repository. |
| Privacy policy | https://github.com/xaepudin/midtrack/blob/main/PRIVACY.md |
| Source | https://github.com/xaepudin/midtrack |
| License | GPL-3.0 |
| Ads | No |
| In-app purchases | No |
| Account / login | No |
| Internet permission | No |

### Short description (80 characters)

```
Auto-seek every new song in any music app. Pick a time, percent, or random spot.
```

### Full description

```
Midtrack jumps to the spot you choose every time a new song starts — in the music player you already use.

A first-run checklist gets notification access, notifications and battery reliability right, and a health card on the main screen points out anything that needs fixing later. Pick a fixed time (1:00), a percentage of the song (20%), or a random spot between two percentages (15–60%). Optional guardrails keep a jump from ruining a track: skip songs shorter than a minimum length, and never jump past a maximum point. They are off by default and can be enabled in Advanced settings. A track with no known length is never seeked.

Per-app rules let YouTube Music jump 20% in while YouTube jumps to 0:15 and a podcast app is left alone. Choose whether Midtrack works in every player or only in the apps you whitelist. The picker lists real players by default, with search, pinning, select-all and clear-all; generic audio handlers stay behind Show all apps. The main screen stays focused on the switch, jump mode and app scope; the app list and advanced settings each have their own screen. The ongoing notification and the Quick Settings tile let you turn it on or off, cycle the mode, or apply the rule to the song already playing.

No ads. No account. No internet permission. No tracking. Midtrack has no server and does not collect any data. The app needs notification access only to read the active media session — the title, duration and playback position — and never reads your notifications.

Midtrack is not affiliated with Google, YouTube, YouTube Music, Spotify, or any other music service. Product names are used only to describe compatibility.
```

### What's new (1.0.0)

```
First public release. Midtrack is a player-neutral rewrite with a new identity, English-only UI, a first-run setup checklist, a focused main screen with health warnings, a clean app picker with search/pins/bulk selection, separate Apps and Advanced settings screens, optional guardrails (off by default), per-app rules, notification controls and a Quick Settings tile.
```

## Graphic assets

| Asset | File | Requirement |
| --- | --- | --- |
| App icon | `docs/play-store/icon-512.png` | 512 × 512 PNG, 32-bit |
| Feature graphic | `docs/play-store/feature-graphic.png` | 1024 × 500 PNG/JPEG |
| Phone screenshot 1 | `docs/play-store/screenshots/phone-setup.png` | 1080 × 1920 (9:16) |
| Phone screenshot 2 | `docs/play-store/screenshots/phone-main.png` | 1080 × 1920 (9:16) |
| Phone screenshot 3 | `docs/play-store/screenshots/phone-selected-apps.png` | 1080 × 1920 (9:16) |
| Phone screenshot 4 | `docs/play-store/screenshots/phone-apps.png` | 1080 × 1920 (9:16) |
| Phone screenshot 5 | `docs/play-store/screenshots/phone-advanced.png` | 1080 × 1920 (9:16) |

Captured from the Midtrack build on an Android 14 device via adb. The Apps screen
is included because the default picker only lists real players, so no unrelated
installed apps appear.

## Play Console declarations

### Foreground service

The app declares `android:foregroundServiceType="specialUse"` with subtype
`media_session_control` and holds `FOREGROUND_SERVICE_SPECIAL_USE`.

Declaration text:

> Midtrack keeps a media-session listener attached while music plays so it
> can seek the next track. It does not play audio itself. The foreground service
> exists only to keep that listener alive and to show the playback controls; it
> stops being useful when the user turns Midtrack off.

### Notification access

`NotificationListenerService` is the system gate for
`MediaSessionManager.getActiveSessions()`. The app must declare that this is
core functionality, initiated by the user, with an in-app disclosure.

Declaration text:

> Midtrack's core feature is seeking to a chosen point in a new song. Android
> only exposes active media sessions to an approved notification listener, so
> the app requires notification access. It reads only the active media session's
> title, duration and playback position. It does not read, store or transmit
> notification content. The app shows a disclosure before opening the system
> notification-access screen.

The in-app disclosure is `notification_access_message` in
`app/src/main/res/values/strings.xml`, shown by
`MainActivity.showNotificationAccessDisclosure()`.

### Data safety

| Question | Answer |
| --- | --- |
| Does the app collect or share any required user data? | No |
| Is data encrypted in transit? | Not applicable — no data leaves the device |
| Can users request data deletion? | No data is collected; uninstalling removes local settings |
| Data processed ephemerally? | Yes — media-session metadata is used in memory only |
| Ads or analytics SDKs? | None |
| Account creation? | None |

The app accesses media-session metadata locally, but it is not "collected" under
Play's definition because it is not sent off the device. The privacy policy
states this explicitly.

### Content and audience

| Question | Answer |
| --- | --- |
| Content rating | Everyone / PEGI 3 (no objectionable content) |
| Target audience | 13+ (general music utility; not designed for children) |
| Ads | No |
| Government app | No |
| News app | No |
| COVID-19 app | No |
| Financial features | No |
| Health features | No |

### App access

No login or account is required. The app's main screen is usable immediately;
notification access is an Android special access, explained in-app.

## Submission checklist

- [ ] Bump `versionCode`/`versionName`, tag, publish a GitHub release
- [ ] Upload `midtrack-<version>.aab` to Internal testing
- [ ] Copy the listing text above
- [ ] Upload icon and feature graphic
- [ ] Upload the five phone screenshots
- [ ] Set privacy policy URL
- [ ] Complete Data safety with "No data collected"
- [ ] Complete content rating questionnaire
- [ ] Declare the foreground service and notification access
- [ ] Verify the certificate SHA-256 matches
      `FF:A3:23:4E:9F:36:72:A0:3E:D6:F6:61:F8:ED:AB:43:CA:91:FC:02:7E:09:A9:55:B6:28:BE:D9:A2:54:3E:F8`
- [ ] Test the internal-track build on a clean device
