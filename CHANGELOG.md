# Changelog

All notable changes to FLACker for Android are documented here. Versions follow [Semantic Versioning](https://semver.org/).

## 1.0.0 — 2026-10-01

First public release.

- Read-only library from folders chosen with the Android folder picker, read once on setup; the saved shelf then opens instantly, with no empty screen. Refresh library reads only new files, reusing unchanged album folders and checking folders in parallel. Optional refresh on app start (off by default, never when returning to the app) and a full re-read in Preferences.
- Albums, artists, tracks and favorites with search, filters, sorting and whole-album shuffle.
- Album order from `playlist.m3u8`, separate editions, multi-disc folders.
- Background playback with Media3: media notification, lock screen and headset controls, gapless queue, repeat, shuffle from the chosen track, play next, ReplayGain with peak protection.
- Now playing with lyrics, queue and audio tabs; Focus Mode; booklet viewer with zoom.
- Reads ID3-prefixed FLAC files and covers stored as a non-front embedded picture.
- Unplayable files are skipped with a notice; queue and position restored after restarts; atomic state files.
- Landscape navigation rail, large text support, dark, light and system themes.
- Requires Android 8.1 or newer.
