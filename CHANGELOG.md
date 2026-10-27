# Changelog

## 1.0.0 — 2026-10-27

First public release.

- New identity: **Midtrack**, a player-neutral Android app.
- First-run setup checklist and an on-demand health card.
- Focused main screen; the app list and advanced settings are separate screens.
- App picker lists real players by default, with search, pinning, select-all and clear-all.
- App list loads off the main thread and uses a RecyclerView with loading/empty/error states.
- All user-facing copy moved to string resources.
- English-only UI, light and dark themes, Material You dynamic colors.
- Jump modes: fixed time, percentage, and random percentage window.
- Optional guardrails: minimum track length and maximum jump point, off by
  default and enabled from Advanced settings; unknown-length tracks are never
  seeked.
- Per-app rules and an app whitelist.
- Notification controls with turn on/off, mode cycling, and **Jump now**.
- **Always show notification** is off by default, so the notification is
  dismissible; enable it in Advanced settings to make it ongoing and re-pin it
  after a swipe.
- Quick Settings tile.
- Signed release APK and Play Store AAB, built by GitHub Actions.
- No internet permission, no ads, no analytics; settings excluded from backups.
