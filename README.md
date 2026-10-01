<p align="center"><img src="docs/icon.svg" width="112" alt="FLACker icon"></p>
<h1 align="center">FLACker for Android</h1>
<p align="center">Your record collection, offline — in your pocket.</p>

FLACker is a native Android music player for personal FLAC libraries, written in Kotlin with Jetpack Compose and Media3 (ExoPlayer). It is the mobile companion of [FLACker for Linux](https://github.com/gap19/flacker-linux): covers come first, albums play in their intended order, and lyrics, booklets and edition details sit next to the music.

**Fully offline. No accounts, telemetry, ads or internet permission. Your folders are opened read-only through the Android folder picker — FLACker never writes to them.**

## Features

- **Library:** pick one or more folders; only FLAC headers, tags and covers are read. Hidden folders and folders starting with `_` are skipped. Albums, artists, tracks and favorites with search, filters (genre, edition or original year, resolution, favorites), sorting and "shuffle a whole album".
- **Cached library:** folders are read once when you add them; after that the app opens straight onto your saved shelf, with albums and covers already in place. **Refresh library** looks for new files only: album folders unchanged since the last scan are reused, and folders are checked in parallel. Optionally, Preferences can refresh every time the app starts (off by default) — never when you just return to the app, for example while music plays. **Re-read all files** checks every file again, for example after editing tags in place.
- **Album order:** follows `playlist.m3u8` / `playlist.m3u` in the album folder; otherwise disc, track number and path. Editions stay separate and `CD1`/`Disc 2` subfolders join their album.
- **Playback:** background playback with the system media notification, lock-screen and headset controls; gapless queues, repeat and shuffle (starting from the chosen track), "play next" that really plays next, ReplayGain by track or album that never amplifies above the peak.
- **Now playing:** lyrics, queue (reorder and remove) and audio details tabs, plus **Focus Mode** — full-screen artwork, timeline and transport, optionally keeping the screen on.
- **Booklets:** images from `Artwork`, `Booklet`, `Covers`, `Scans` or `Images` folders with pinch and double-tap zoom; PDFs open in your PDF reader.
- **Lyrics:** embedded in the FLAC or in a `.txt` / `.lrc` with the same name (UTF-8, UTF-16, Latin-1); timestamps are hidden, nothing is synchronized.
- **Robust:** unplayable files are skipped with a notice, the queue and position survive restarts, state files are written atomically, and landscape, large text and the system dark/light theme are supported.

Android may resample audio to the output device rate; FLACker does not claim bit-perfect output or exclusive USB DAC access.

## Install

Requires Android 8.1 (API 27) or newer. Download [`dist/FLACker-1.0.0.apk`](dist/) and check it against `dist/SHA256SUMS`, then either open it on the phone (allow installs from your file manager) or install from a computer with USB debugging enabled:

```sh
adb install dist/FLACker-1.0.0.apk
```

Open FLACker, tap **Choose music folder** and select the folder that holds your FLAC files.

Release APKs are signed with certificate SHA-256 `cc10d677577f3beff733d3013b643b5e0a9ec4841fca68d11a0a5c7effe5c305`. Android only installs updates signed with the same key.

## Build from source

Requirements: JDK 17 or newer and the Android SDK (platform 37, build-tools 36). Gradle is fetched by the wrapper.

```sh
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:testDebugUnitTest :app:lintRelease   # unit tests and lint
./gradlew :app:assembleDebug                        # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease                      # minified with R8
```

To sign release builds, create `keystore.properties` next to `settings.gradle.kts` (it is git-ignored):

```properties
storeFile=/path/to/your-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, `assembleRelease` produces an unsigned APK.

## Your data

Folders, favorites and preferences are kept in `state.json`; queue, position, modes and shuffle order in `queue.json`, both in the app's private storage and written with `AtomicFile`. Android backup includes only `state.json`. After restoring on a new phone, Android does not restore folder permissions: FLACker warns you and the folders must be picked again in Preferences.

## Disclaimer

FLACker is shared in the hope that it will be useful to others who enjoy listening to their own music collections. It is provided "as is", without warranty of any kind, express or implied, including any warranty of merchantability or fitness for a particular purpose. The author is not liable for any damage or loss arising from its use, including to files, devices or data. Using, building, installing or modifying this software is at your own risk and under your own responsibility. Keep backups of anything that matters to you. See sections 15 and 16 of the [license](LICENSE).

## License

FLACker is free software under the [GNU General Public License v3.0 or later](LICENSE). The bundled League Gothic and Source Sans 3 fonts are under the SIL Open Font License 1.1 (`app/src/main/assets/licenses/`). AndroidX, Jetpack Compose, Media3 and Kotlin coroutines are under the Apache License 2.0. The license grants no rights over the music, lyrics or images in your library.
