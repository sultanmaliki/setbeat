# Changelog

All notable changes to SetBeat, newest first. Each release has fuller notes (and the APK) on the
[Releases](https://github.com/sultanmaliki/setbeat/releases) page. Versions before 0.2.3 were published
under the earlier app names *Fitness Timer* (0.1 to 0.2) and *Cadence* (0.2.1 to 0.2.2).

## 0.3.4 - the wave works with spatial audio on, plus security hardening
- **Fixed: the beat wave stayed flat with Xiaomi's "Immersive sound" on.** Owner report, then
  confirmed on the phone: the system spatializer carries the music on a path the app's audio reader
  (session 0, the main mix) never sees (`peak=0.0`); turning it off gave real data. The earlier
  explanation (hardware offload / Bluetooth) was wrong for this phone. Full now notices when the main
  mix is silent while music plays, scans for the playing track's own audio session, and reads that
  instead. Re-checked on-device with Immersive sound **on**: live bass/mid/treble, and it re-attached
  within about 4 seconds after skipping a track. Only loudness levels are read, nothing is stored, it
  runs only while the companion screen is visible and playing, and it needs no new permission.
  Hardware offload is a separate real limit that no app can read; the hint card still covers it and
  now also tells you to try switching spatial audio off.
- **Docs corrected:** README, DECISIONS and the in-app card no longer blame Bluetooth/offload for
  everything; the Vibes edition is described as the always-moving option rather than a fix.
- **The playback service is no longer reachable by other apps.** `PlaybackService` was exported
  with no permission, and Media3's default start handling acts on a media-button or notification-action
  intent without ever running the app's controller allow-list. Another app could in principle have
  started/paused your timer or sent transport commands. It is now `exported="false"`: the lock-screen /
  notification media controls, headset buttons and the in-app player use the platform media session and
  same-app intents, so nothing legitimate depends on the export.
- **Release builds refuse to be signed with the debug key.** Without the private `keystore.properties`,
  packaging a release now fails with a clear message instead of silently producing a debug-signed APK
  that can't update real installs. `-PallowDebugSignedRelease` opts back in for throwaway local builds.
- The two security changes above came from a source-only security audit (no confirmed
  vulnerabilities; one low-impact lead, fixed here and re-tested on the phone: the same `adb` intent
  that used to start the timer is now refused). Nothing else changed.

## 0.3.3 - a third edition: Vibes, for a wave that's always moving
- **New edition: Vibes.** Same real track info as Full (companion mode, needs notification access),
  but the wave is a procedural animation instead of real audio analysis — it always looks alive,
  whatever's actually playing. No Record audio permission at all: it doesn't read anything real, so it
  doesn't ask. For anyone who'd rather have motion than honesty about when the wave can't get real
  data (see 0.3.1/0.3.2) — Full still exists for that.
- **Why a third edition instead of just faking it everywhere:** real audio data was tried harder first.
  `Visualizer` can attach to a specific track instead of the whole-system mix, which can get past the
  hardware-offload wall — *if* the music app announces its session (an optional Android convention).
  Tested against two real apps on the connected test phone by forcing real track changes: neither
  supports it. Rather than silently give everyone a fake wave, Full and Standalone keep the honest
  behavior, and Vibes is there if you'd rather trade accuracy for a wave that never sits still. Full
  details: `DECISIONS.md`.

## 0.3.2 - don't blame Bluetooth when it isn't Bluetooth
- **The silent-wave card now checks whether Bluetooth is actually connected before mentioning it.**
  Owner report: the card suggested a Bluetooth fix while playing through the phone's own speaker,
  which is confusing when you can see you're not on Bluetooth. It now checks the real output route
  (`AudioManager.getDevices()`, no extra permission) and only offers the Bluetooth-specific tip when
  Bluetooth is actually connected; otherwise it says plainly that this isn't a Bluetooth issue and the
  cause is the music app's own hardware decode path, with no in-app fix.

## 0.3.1 - explain a silent beat wave
- **The beat wave now says why it isn't reacting, instead of just sitting flat.** In companion mode,
  if audio access is granted and music is playing but the wave stays idle, a dismissible card explains
  that some music apps play compressed audio through a battery-saving hardware path (over Bluetooth or
  the phone speaker) that skips the system effects the wave reads, with no reliable in-app fix — and
  suggests disabling "Bluetooth A2DP hardware offload" in Developer options for the Bluetooth case.
  This can't make the wave work in every case; it only makes the cause visible instead of looking
  broken.

## 0.3.0 - two editions, private signing key
- **Fixes "App blocked to protect your device" (Google Play Protect) on other phones.** Play Protect
  blocks sideloaded apps that declare notification-listener access in some markets. SetBeat now ships
  two editions: **Standalone** (no notification listener, no audio capture: installs anywhere; local
  media, playlists and the timer) and **Full** (adds companion mode and the beat wave; needs Google
  Play or `adb` on phones that apply the block). On-device tests check that the standalone package
  never declares the blocked permissions.
- **Releases are now signed with SetBeat's own private key** (fingerprint in the README) instead of a
  shared debug key, and each release lists SHA-256 checksums. **Breaking:** Android will not update
  across a signing-key change, so uninstall 0.2.x first (its playlists and timer are removed).
- README install guide rewritten (which edition, what the Play Protect message means, options,
  verification). No other behaviour changes.

## 0.2.3 (SetBeat)
- **Renamed to SetBeat** (a gym "set" plus the "beat" of the music). The earlier candidates were
  rejected: *Cadence* collides with a registered trademark, and *RepBeat* is an existing workout timer
  app. The Android package id is unchanged, so this installs over 0.2.x.
- New banner and README header; `CHANGELOG.md` added.
- No behaviour changes.

## 0.2.2
- Fixed the hold-to-reset ring getting stuck on screen after pausing, starting or resuming.
- Countdown dialog: accepts `mm:ss` and `h:mm:ss` (up to 99:59:59), shows an error on bad input, and
  no longer overflows on a huge number of minutes.
- 30 on-device tests (gestures and the whole timer flow); dead code, a stale heap dump and
  intermediate test logs removed.

## 0.2.1
- Renamed to *Cadence*; new adaptive icon (with a themed-icon layer); README, LICENSE (Apache-2.0),
  CONTRIBUTING and a bug-report template.
- Artwork side bars baked into some covers are trimmed automatically.
- Wave fixes: it now spans the whole bar (it used to start late after about 70% of a song), it no
  longer shrinks or vanishes in quiet passages, and a stale-audio-state bug is fixed.
- Two-finger tap no longer skips the song when one finger lifts first; a quick flick in the centre no
  longer toggles the timer.
- No more "Nothing playing" flicker between tracks.

## 0.2
- **Companion mode:** shows and controls whatever another app is playing (title, artist, artwork,
  progress) in a One UI-style player; gestures drive the source app. Needs notification access.
- **Beat-reactive wave:** the progress wave follows the low, mid and high sounds of the music in real
  time (Android's audio visualizer; needs the Record audio permission, never uses the microphone).
- **Background countdown alarm:** exact alarm plus notification, works with the screen off.
- Non-debuggable release build.

## 0.1.1
- Real player-owned queue (next, previous, auto-advance) and notification controls including a timer
  button; audio focus, pause on headphone unplug, screen kept on.
- Timer state survives the app being killed; performance and security hardening; unit tests.

## 0.1
- First installable build: stopwatch and countdown, gesture system, local video and audio with the
  negative-blend timer, playlists, ambient background, app icon.
