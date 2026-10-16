# Technical Plan

> **Naming:** the app is called **SetBeat** (repo `setbeat`). While this plan was written it was
> "Fitness Timer", and for a day "Cadence", so older sections and dates may still say so. The Android
> package id stays `dev.fitnesstimer` on purpose: changing the application id would stop the app
> upgrading over existing installs.

Grounded in a research pass against primary Android/AOSP/Media3/Play sources
(Sept 2026), each finding adversarially re-checked. Claims below are the
conclusions; anything genuinely unresolved is called out as such rather than
asserted.

## A. Product definition

A gesture-only, full-screen Android stopwatch/countdown whose visual identity
*is* whatever media is playing — local file, another app's session, or a
direct stream — with the timer rendered as a minimal effect layered into that
media. Differentiator: media is the primary surface, the timer is the
accessory (most timer apps are the reverse).

**Direction update, 2026-09-21:** v0.2 adds *companion mode* — the app shows
and controls whatever audio another app (YouTube Music, Spotify, ...) is
already playing, and never plays it itself. See section N.

## B. Feasibility

| Feature | Classification | Notes |
|---|---|---|
| Stopwatch/countdown, accurate across background/lock | Straightforward | `SystemClock.elapsedRealtime()` is monotonic across deep sleep |
| Local file playback (video/audio, artwork) | Straightforward | Media3/ExoPlayer + SAF |
| ~~Online stream playback~~ | **Dropped from v1, 2026-09-20** | Direct-URL/HLS/DASH streaming is technically fine (Media3 supports it), but the user decided it adds distribution/UI complexity not worth it for a local-gym-use v1. Deferred to a future update — see `DECISIONS.md`. |
| Read metadata/art/position of another app's session | Android-dependent | Only via `NotificationListenerService` + `MediaSessionManager.getActiveSessions()`; no lighter permission exists |
| Send play/pause/seek/skip to another app | Android-dependent | Framework forwards commands regardless of advertised capability; the *target app* may silently ignore them |
| **Negative/inverted timer text over full-screen video** | **Challenging — unproven, needs a prototype spike** | See below |
| Same effect over another app's video | **Not possible** | No API exposes another app's decoded frames without per-session, user-consented `MediaProjection`, which also blanks secure/DRM content. Third-party media is control+metadata only, never a drawable video surface. |
| Play a YouTube / YouTube Music link inside this app | **Decided against, 2026-09-21** | Official embedding forbids overlays and background/audio-only playback; unofficial extraction is outside YouTube's rules. Replaced by companion mode — see section N.1 |
| CD/vinyl animation over audio artwork | Straightforward | Pure Compose animation |
| Custom gesture system (tap/double-tap/2-finger/hold) | Straightforward, hand-built | No built-in Compose detector for two-finger tap/swipe; requires a custom `pointerInput` state machine |
| Exact countdown-complete alarm while backgrounded | Straightforward, needs a policy choice | `SCHEDULE_EXACT_ALARM` (user-grant) vs `USE_EXACT_ALARM` (Play auto-grants to genuine alarm/timer apps) |
| Play Store distribution w/ NotificationListenerService | Unconfirmed | No primary Play policy page names `BIND_NOTIFICATION_LISTENER_SERVICE` — verify directly in Play Console before submitting |
| GitHub-APK distribution | Fully local, Android-dependent risk | Media3 core has no proprietary deps (Apache-2.0). Risk: Android 13+ "restricted settings" can block sideloaded installs' notification-listener grant until manually unblocked |

### The rendering question

Media3's recommended video surface is `SurfaceView`, composited by
SurfaceFlinger in its own layer — the app window punches a transparent hole
through it. A Compose `BlendMode.Difference` overlay **cannot** read those
pixels; nothing in the docs supports it, and the model (hole-punch +
alpha-composite) argues against it.

`TextureView` *is* part of the normal view hierarchy and can plausibly
participate in a Compose blend — but this exact case (Compose
`AndroidView(TextureView)` + sibling `Difference` draw) isn't documented
either way. Confirmed costs if this is the path: extra per-frame copy, higher
power draw, HDR tone-mapped down to SDR on API 33+, no DRM support (not
relevant for personal local files).

A heavier fallback exists: a custom Media3 GL shader (`BaseGlShaderProgram`)
sampling a text-mask texture, applied only to the app's own ExoPlayer
instance during decode. Real, Media3-native, but `@UnstableApi` and unproven
for this exact use — more machinery than justified until the simple path is
proven not to work.

**Resolved 2026-09-20:** the TextureView + Difference-blend approach was
prototyped and confirmed working on a real device (Android 16 / API 36) —
see `DECISIONS.md` for the on-device evidence (the
spike screen was later folded into `render/NegativeTimerText.kt`). This is the renderer for local/online video. Fallback for
API < 29 (where `BlendMode.Difference` silently no-ops to `SrcOver`) remains
plain contrast-color/shadow text, not yet re-verified on-device.

## C. Stack

| Dependency | Purpose | License | External dependency |
|---|---|---|---|
| Kotlin + Jetpack Compose | UI, language | Apache-2.0 | None |
| Media3 (ExoPlayer, Session, UI-Compose) 1.11.1 | Local/online playback, MediaSession hosting, `PlayerSurface` | Apache-2.0 | None — no GMS/proprietary deps in core |
| Storage Access Framework | Local file/folder picking, persisted access | Platform API | None |
| `MediaSessionManager` + `NotificationListenerService` | Reading/controlling other apps' sessions | Platform API | None (user grant, not paid) |
| Coroutines/Flow | Async, state streams | Apache-2.0 | None |
| `AlarmManager` (exact alarms) | Countdown-complete while backgrounded | Platform API | None |
| Custom gesture layer (`pointerInput`/`awaitPointerEvent`) | Whole gesture system | Own code | None |
| `androidx.palette` 1.0.0 | One-time dominant-color sampling for ambient letterbox (section H) | Apache-2.0 | None |

**Explicitly not using:** cloud backend, paid media/AI services,
`MANAGE_EXTERNAL_STORAGE`, Media3's optional FFmpeg/AV1 decoder extensions in
v1 (separately-licensed native code, built-in `MediaCodec` formats already
cover MP4/H.264/AAC/MP3/etc.), Media3 custom-shader rendering unless the
TextureView prototype fails.

## D. System architecture

```
UI (Compose, full-screen)
  -> Gesture Engine (custom pointerInput state machine)
    -> Application State (single source of truth: mode, timer state, active media source)
      -> Timer Engine (elapsedRealtime-based, survives background/lock)
      -> Media Engine
          - Local Media (Media3 ExoPlayer + SAF)
          - Android MediaSession (NotificationListenerService + MediaController, read/control only)
          - (Online Media — dropped from v1, see DECISIONS.md)
      -> Visual Renderer
          - Video (ContentFrame, TextureView-backed, native aspect ratio, ambient-color letterbox)
          - Negative Timer Text (Compose Difference-blend overlay)
          - Artwork/CD (rotation animation over MediaMetadata art)
          - Progress (thin bottom bar, subtle)
```

Gesture Engine emits intents into a single `ApplicationState`. Timer Engine
and Media Engine are independent state producers feeding the same state
holder — they never call each other directly. Visual Renderer only reads
state.

## E. Gesture architecture

| Zone/input | Action |
|---|---|
| Tap center | start/pause (fires instantly — no competing double-tap in this zone) |
| Double-tap left third | seek -10s |
| Double-tap right third | seek +10s |
| Two-finger tap | play/pause media |
| Horizontal drag | scrub media |
| Two-finger horizontal swipe | prev/next track |
| Hold timer ~0.8s (paused only) | reset, growing ring, cancel on early release, haptic tick on completion |
| Top-left tap | timer mode picker |
| Top-right tap | media source picker |

Priority order (first match wins):
1. Second finger arrives while 1-finger hold-to-reset is charging → cancel, re-evaluate as 2-finger gesture.
2. Corner taps (mode/source pickers) — fixed hit-zones, checked before body gestures.
3. Two-finger tap vs. swipe — distinguished by movement distance/velocity.
4. One finger: hold-to-reset (paused only) → double-tap zones (left/right thirds) → drag (scrub) → plain tap (center = start/pause).

Left/right double-tap zones inherently add the system double-tap-window
delay to a plain tap there (no way around this while also wanting a
double-tap meaning in the same zone) — acceptable since those zones have no
single-tap meaning per the table above.

Finger-count gestures are distinguished by tracked pointer count (no
dedicated Compose API — custom code), not by timing. Tap vs. drag uses
`ViewConfiguration.touchSlop`. Hold-to-reset uses
`ViewConfiguration.longPressTimeout` (user-configurable, not a hardcoded
value).

System gesture conflicts: `Modifier.systemGestureExclusion()` for
back-swipe-adjacent edges (max 200dp/edge), edge-to-edge/immersive via
`WindowInsetsControllerCompat`. Home/quick-switch gestures cannot be
excluded — nothing essential at the very bottom edge.

**Accessibility gap (flagged, not yet decided):** TalkBack claims one-finger
touch gestures, so this screen is unusable with TalkBack on unless every
gesture is mirrored as a semantic action. Not a Play requirement, but a real
WCAG 2.5.1 gap worth deciding on explicitly rather than ignoring.

## F. Timer architecture

- Basis: `SystemClock.elapsedRealtime()` — monotonic, survives deep sleep, resets only on reboot. Store `startElapsedRealtime` + accumulated-paused-duration; compute displayed time as a pure function, never a mutated running counter.
- Reboot detection: `Settings.Global.BOOT_COUNT` — if changed since persisted, the `elapsedRealtime` anchor is invalid.
- No foreground service needed for stopwatch display while backgrounded.
- Countdown completion while backgrounded needs an exact alarm: `USE_EXACT_ALARM` (Play auto-grants to genuine timer apps) or `SCHEDULE_EXACT_ALARM` (user-grant flow).
- Completion alert: Android 17's background-audio hardening can silently swallow a raw sound-play call from the background. Use a high-importance notification with an alarm-channel sound, not a bare `MediaPlayer.play()`.

## G. Media architecture — one abstraction

```
data class MediaState(
  title: String?, artist: String?, artwork: ImageBitmap?,
  durationMs: Long?,        // unknown allowed on both local and third-party sources
  positionMs: Long, positionAnchorElapsedRealtime: Long, speed: Float,
  isPlaying: Boolean,
  capabilities: Set<Capability>,  // canSeek, canSkipNext, canSkipPrev — advisory only
  source: MediaSourceKind         // Local, ThirdParty (Online dropped from v1 — see DECISIONS.md)
)
```

Live position while playing = `positionMs + (elapsedRealtime() -
positionAnchorElapsedRealtime) * speed`. This extrapolation is our own
convention, not a documented Android formula.

## H. Visual rendering

1. **Prototype spike (first task):** `PlayerSurface(surfaceType =
   SURFACE_TYPE_TEXTURE_VIEW)` + sibling `Text` via
   `drawWithContent`/`drawText(blendMode = BlendMode.Difference)`, tested
   with/without `Modifier.graphicsLayer(compositingStrategy = Offscreen)`
   (want it *without*). Test on a real device, API 29+.
2. If it inverts correctly → that's the renderer, with a plain
   contrast/shadow fallback for API < 29 or non-blending devices.
3. If it doesn't → fall back to the same fallback style as the primary look
   rather than reaching for the GL shader route.
4. Artwork/CD mode: static bitmap + `rememberInfiniteTransition`-driven
   `rotationZ` on a `graphicsLayer` (draw-phase only, cheap).
5. Progress bar: thin `Canvas` draw, low opacity, skipped when duration is
   unknown.
6. No-media state: timer text only, subtle idle motion via the same
   `graphicsLayer` pattern.
7. **Aspect ratio / ambient letterbox — decided 2026-09-20.** When a local
   video's aspect ratio doesn't match the device's, don't crop or stretch —
   use Media3-Compose's `ContentFrame(contentScale = ContentScale.Fit)`
   (documented wrapper around `PlayerSurface` that letterboxes natively)
   instead of raw `PlayerSurface`. The letterbox space is filled with a
   one-time dominant/muted color sampled from the video via
   `MediaMetadataRetriever` + `androidx.palette` (Apache-2.0, stable 1.0.0),
   not a plain black bar — a static color wash, not a live blurred replay of
   the video. True YouTube-style *live blurred* ambient background is
   explicitly **deferred**: it needs either a second simultaneous decode of
   the same file or periodic frame capture, both real cost/complexity for a
   cosmetic effect, and isn't proven cheap the way the TextureView blend
   turned out to be. Revisit only if the static-color version feels flat in
   practice.

## I. Roadmap

1. ~~**Rendering spike**~~ — done, confirmed working on-device 2026-09-20 (see `DECISIONS.md`).
2. ~~Timer engine~~ — done: stopwatch + countdown + mode picker; persisted across process death (v0.1.1). Countdown completion is still foreground-only — the background alarm is pulled into v0.2 (section N.6).
3. ~~Gesture engine~~ — done as one unified state machine (`gesture/TimerGestures.kt`), skipped the "isolated against a dummy timer" staging and went straight to full integration (step 5) since the timer engine was quick to build alongside it.
4. ~~Local media playback~~ — done and hardened by v0.1.1: `MediaSessionService` + `MediaController`, player-owned queue (playlists, next/prev/auto-advance), notification controls incl. a timer button, audio focus/noisy/wake lock, unplayable-file skipping. SAF grants are persisted (safely) and released on playlist delete.
5. ~~Full-screen integration~~ — done: timer + local media + gestures + ambient/aspect-ratio all on one screen (`ui/MainScreen.kt`), 2026-09-20. The gesture priority/timing design in section E was first only hand-tested (MIUI blocks `adb shell input`); it is now covered by on-device Compose tests that dispatch touches straight into the app (30 tests, section N.5g/N.5h).
6. ~~Third-party MediaSession integration~~ — promoted to the v0.2 headline
   feature (section N), including the restricted-settings onboarding flow.
7. ~~Online stream source~~ — dropped from v1, 2026-09-20 (see `DECISIONS.md`).
8. ~~Source-picker~~ — done as a top-right menu (Now playing / choose file / playlists). Still open:
   polish and the accessibility semantics layer (`DECISIONS.md`).
9. Packaging — Play submission groundwork if desired
   (including the Play Console policy check from section B).

## J. V1 scope

**In:** stopwatch + countdown, local media playback (video/audio) and
read/control of other apps' media sessions, negative-text video rendering
with native-aspect-ratio + ambient-color letterboxing, CD/artwork audio
rendering, full gesture system, manual persistent source picker, local
notification-based countdown alert.

**v0.2 addition:** companion mode (section N). Local playback **stays as it is** (decided
2026-09-21, `DECISIONS.md`).

**Out:** workout tracking, reps/sets, calories, social features, AI
features, accounts, cloud sync, stats/dashboards, interval sequencing beyond
stopwatch/countdown, tablet/foldable layouts, any non-Android platform,
**online/direct-URL streaming** (dropped 2026-09-20, deferred to a future
update), live-blurred ambient background (deferred, see section H).

## K. Getting started

1. Install Android Studio (bundles JDK + SDK + emulator).
2. New empty Compose project, minSdk TBD (see `DECISIONS.md`).
3. Add Media3 (`exoplayer`, `session`, `ui-compose`) via version catalog.
4. Do the rendering spike (H/I step 1) as a scratch screen before real app
   structure.

## L. Risks

- Rendering feasibility is unproven — top risk, build the spike first.
- Third-party video is architecturally impossible to visually integrate;
  keep UI copy honest about this.
- Restricted-settings onboarding friction for sideloaded installs — design
  the onboarding screen assuming it might happen.
- Android Developer Verification's 2027 global rollout is a standing,
  unresolved risk to sideload distribution.
- Play's policy stance on `BIND_NOTIFICATION_LISTENER_SERVICE` is
  unconfirmed from docs — check Play Console directly before submitting.
- Android 17 background-audio hardening can silently swallow the
  countdown-complete sound if the alert path isn't built around a valid
  foreground state/notification channel.
- HDR/DRM loss on TextureView if that ends up being the rendering path
  (acceptable for personal local files).
- No official OLED burn-in guidance for a mostly-static full-screen timer —
  a self-chosen design call.
- FFmpeg/AV1 optional codec extensions bring separate copyleft-flavored
  licensing — skip for v1.
- (v0.2) Notification-access is a sensitive permission: it technically
  exposes all notifications. The app must read media sessions only and say so
  in onboarding. Restricted-settings friction on Android 13+ for sideloaded
  installs; HyperOS 3.0 behavior unverified.
- (v0.2) Source apps decide what they publish: artwork may be a bitmap, only
  a URI, or absent; transport commands can be ignored. Observed behavior of
  YouTube Music is still unprobed (no active session at probe time).
- (v0.2) With another app owning playback, the screen is usually off — the
  countdown-complete alert must not depend on the app being visible.

## M. Open-source setup

- License: Apache-2.0 (matches Media3/Kotlin/coroutines; avoid GPLv2
  specifically due to Apache-2.0 compatibility issues).
- Repo: standard single-module Compose app (`app/`), `LICENSE`,
  `CONTRIBUTING.md`, `.github/ISSUE_TEMPLATE/`.
- Dependency licensing: stick to Apache-2.0/MIT; document why the optional
  Media3 codec extensions are excluded.
- No telemetry — contradicts the no-cloud/no-accounts requirement, and
  there's no need for it on a personal-use project.
- Release: tagged GitHub Releases (APK) → Play as an
  optional secondary channel pending the policy check above.

## N. v0.2 — Companion mode (decided 2026-09-21)

### N.1 Why not "play a YouTube link" inside the app

Researched 2026-09-21 against Google's published policies. The pages were read
through a summarizing fetch tool — **re-read the originals before relying on
exact wording**.

| Approach | Verdict |
|---|---|
| **A. Official IFrame Player API in a WebView** (e.g. MIT-licensed `android-youtube-player`, v13.0.0) | Compliant, but Required Minimum Functionality says no "overlays, frames, or other visual elements in front of any part of a YouTube embedded player", and Developer Policies forbid background players (III.I.9) and separating audio from video (III.I.7). That kills the negative-timer overlay, audio-only mode, and screen-off playback — the gym use case. Also needs a valid referrer/app identity or players fail with error 153. |
| **B. Unofficial extraction → ExoPlayer** (e.g. NewPipeExtractor, GPLv3, parses YouTube's web interface/internal API) | Keeps every feature, but is outside YouTube's rules (III.I.14, III.E.1.a), breaks when YouTube changes internals (general knowledge, not verified here), forces GPLv3 onto the app, and rules out Play Store. Rejected. |
| **C. Companion mode** — the source app plays; we read its media session and control it | Chosen. We never touch their player or content, so no policy conflict; background playback works because it's their app. |

Consequence: no video in this mode, so the "no overlay on third-party video"
limitation (section B) stops mattering. Because *we* draw the artwork, the
negative-blend timer can still overlay our own artwork tile.

### N.2 What the feature does

- Detects the currently playing media session of any app (YouTube Music,
  Spotify, podcasts, ...) and shows: title, artist, album, artwork, play
  state, position/progress.
- Reuses the existing audio screen: artwork tile (native aspect ratio,
  rounded), equalizer colored from artwork, timer below.
- Gestures drive the *source* app's transport controls (play/pause, next,
  previous, seek) where the app allows it.
- Timer/stopwatch is unchanged and independent of the music.
- No `INTERNET` permission, no network access.

### N.3 Architecture (planned, see `STRUCTURE.md`)

- `NowPlayingListenerService` — a `NotificationListenerService` that does
  nothing with notifications; its enabled state is what authorizes
  `MediaSessionManager.getActiveSessions(componentName)` (the mechanism from
  section B, recorded in earlier research; **not re-confirmed from Android
  docs on 2026-09-21** — the doc fetch returned only navigation).
- `NowPlayingRepository` — `OnActiveSessionsChangedListener` plus a
  `MediaController.Callback` per session (metadata + playback state) exposed
  as a `StateFlow<NowPlaying?>`.
- `SessionSelector` (pure) — choose the session to show: the one in
  `STATE_PLAYING`; ties → most recently active; user override later.
  Unit-tested.
- `PositionExtrapolator` (pure) — `position + (elapsedRealtime -
  lastPositionUpdateTime) * playbackSpeed`; matches the `PlaybackState`
  accessors (`getPosition`, `getLastPositionUpdateTime`, `getPlaybackSpeed`).
  Unknown duration/position (negative) handled explicitly. Unit-tested.
- Artwork fallback chain: metadata bitmap keys → notification large icon
  (only if trivially available) → ambient-colored tile. Artwork given only as
  a remote URI can't be fetched without `INTERNET` and is deliberately not
  fetched. Metadata key names (`METADATA_KEY_ART`, `_ALBUM_ART`,
  `_DISPLAY_ICON` and `_URI` variants) are from general knowledge —
  **unverified today**.
- `NotificationAccessScreen` — onboarding: explain what is read (media
  sessions only), open `ACTION_NOTIFICATION_LISTENER_SETTINGS`, and explain
  the Android 13+ "restricted settings" unblock for sideloaded installs.

### N.4 Device facts (probed 2026-09-21, test phone)

- Android 16, HyperOS 3.0 (`OS3.0.302.0.WOJINXM`).
- Music apps present: Mi Music (`com.miui.player`, the one in real use),
  YouTube Music (`com.google.android.apps.youtube.music`), and the YouTube
  app. **Spotify is not installed.**
- Our app installed by `adb` (installer=null); not a notification listener
  yet. `ACCESS_RESTRICTED_SETTINGS` appop: default (no override).
- Existing listeners (Xiaomi/Google/Microsoft services) are enabled — no
  relevance to our grant.
- **P0 probe, 2026-09-21 (Mi Music playing; YouTube Music not yet observed).**
  `dumpsys media_session` listed 3 sessions:
  - `com.miui.player` (Mi Music) — `PLAYING`, position reported with a
    last-update timestamp and speed 1.0 (so extrapolation is needed), metadata
    with 9 entries (title, artist list, album visible in the description),
    supported actions include stop/pause/play/play-pause, **skip previous,
    skip next, seek-to**, fast-forward/rewind, repeat/shuffle, plus two
    custom actions. Its media notification is `MediaStyle`, carries the
    session token, and has a **large-icon bitmap of only 156x87** — a
    fallback, clearly lower resolution than a proper artwork bitmap.
  - `com.google.android.youtube` — a **stale `STOPPED` session with metadata**
    left behind by the YouTube app (not playing).
  - `dev.fitnesstimer` — our own `PlaybackService` session (`NONE`).
  **P1 follow-up (metadata read through a real `MediaController`, same day):**
  Mi Music's metadata keys are `ALBUM, ALBUM_ARTIST, ART, ARTIST, DURATION,
  MEDIA_ID, NUM_TRACKS, TITLE, TRACK_NUMBER`. **Artwork is a real bitmap under
  `ART`, 256x144 (16:9, not square), and no artwork URI is set** — so a
  bitmap is available with no network, at a modest resolution the artwork
  tile will upscale (blur/soften rather than crop, keep native aspect ratio).
  Duration and position come through; position advances between callbacks so
  extrapolation is needed.
  Consequences for the design: `SessionSelector` must (1) require
  `STATE_PLAYING` and ignore stopped/none sessions, and (2) **exclude our own
  package**, or the app would show itself. Actions are only advertised
  capabilities — whether Mi Music honors them is untested. `dumpsys` prints
  only the *count* of metadata entries, not their keys, so whether Mi Music
  sets a full-size artwork bitmap (vs. only the small notification icon) can
  only be answered by reading the metadata from a `MediaController` — that is
  the first job of P1.

### N.5 Known constraints

- Source app may ignore transport commands; capabilities are advisory
  (section B/G).
- A video playing in the YouTube app also publishes a session; we can't
  reliably tell audio from video — accept, offer override.
- Equalizer stays a decorative loop (real audio reactivity needs
  `RECORD_AUDIO`), but should follow the source app's play/pause state.
- Process death: `AppTimer` already persists; whether Android rebinds the
  listener service after HyperOS kills the process is **assumed, unverified**.

### N.5b Implementation status (P1 done 2026-09-21)

Built and unit-tested: `nowplaying/NowPlaying.kt`, `SessionSelector.kt`,
`PositionExtrapolator.kt`, `NowPlayingRepository.kt`,
`NowPlayingListenerService.kt` (manifest entry with
`BIND_NOTIFICATION_LISTENER_SERVICE`). Verified on the test phone:

- Without access: repository reports `accessGranted=false`, no crash.
- With access (granted via `adb shell cmd notification allow_listener ...`,
  which **bypasses the Settings toggle and any restricted-settings screen** —
  the real onboarding is still untested): correct session chosen (Mi Music),
  the stale YouTube session and our own session ignored, metadata keys,
  artwork bitmap, position, actions all read.
- User flow "leave app, toggle access, return": picked up on resume.
- **Observed:** `NowPlayingListenerService.onListenerConnected` never fired
  in this test (grant via `cmd`), although `getActiveSessions()` worked. The
  app therefore refreshes on `onResume` and must not depend on that
  callback. Whether a real Settings-toggle grant behaves differently is
  untested.
- Could not test: listener rebind after the OS kills the process (`kill` is
  not permitted from the shell, and force-stop is not equivalent).

### N.5c Implementation status (P2 done 2026-09-21)

Built: source mode (companion is the default; picking a file/playlist
switches to local; a new "Now playing (other apps)" entry in the top-right
menu switches back and pauses local playback; the mode survives Activity
recreation), `ui/CompanionScreen.kt` (`CompanionBody`, progress line,
`NotificationAccessCard`), gestures routed by mode (seek +/-10s, drag-scrub
with the same throttle, two-finger play/pause, two-finger swipe prev/next,
all forwarded to the source app's transport controls).

Verified on the test phone with Mi Music:
- Screen shows the real artwork (rounded, native aspect), title, "artist ·
  album", equalizer, timer and a progress line; ambient color sampled once
  per track from the artwork.
- Controls, driven through the same repository calls the gestures use (a
  debuggable-only launch hook, since MIUI blocks injected touches):
  play/pause toggled the source state 3 -> 2 -> 3; seek +10s / -10s moved
  Mi Music's position by ~10s each way; next changed the track and prev
  returned to it.
- Onboarding card shown when access is off, over a working timer screen.
- **Performance found by measurement and fixed:** the first version
  rendered ~105 fps with a 20 ms median frame time. `Modifier.blur` on the
  equalizer glow (RenderEffect, re-rendered every frame while the bars
  animate) was the cost (20 ms -> 8 ms without it). Replaced by a static
  radial gradient, and the five 120 Hz animations by one ~30 Hz ticker that
  only runs while playing. Result: ~60 fps while playing, p50 8 ms,
  p99 17 ms, and **0 frames while paused**.
- Equalizer bars were invisible (same color as the background); now a
  lightened variant of the ambient hue.
- Layout: artwork tile is sized to the image itself (no dead space).

**Hands-on check by the user, 2026-09-21:** the gestures work in companion
mode — song change, play/pause and the other gestures all act on the source
app. (Detail of which were tried was not itemized.)

Not verified (needs hands): the real Settings toggle and any restricted-settings screen, the
"Now playing" menu entry and file/playlist switching, other source apps
(YouTube Music not observed), a source app that gives no artwork or no
position/duration. Mi Music's artwork has dark side bars baked into the 16:9
bitmap; trimming uniform borders is a possible polish item.

### N.5d Implementation status (P3 + One UI theme, 2026-09-21)

**P3 — countdown alarm.** `timer/CountdownAlarm.kt` (pure `alarmDelayMs`,
scheduling, notification), `CountdownAlarmReceivers.kt` (alarm + boot),
`AppTimer` re-syncs the alarm on every timer state change and after restore.
Exact `setExactAndAllowWhileIdle` on `ELAPSED_REALTIME_WAKEUP`; falls back to
the inexact variant if exact alarms aren't allowed. Permissions:
`USE_EXACT_ALARM` (auto-granted on Android 13+, Play-restricted to genuine
alarm/timer apps), `SCHEDULE_EXACT_ALARM` (maxSdk 32), `RECEIVE_BOOT_COMPLETED`;
`POST_NOTIFICATIONS` is requested when the user picks a countdown.
Documented (Android alarm docs, fetched 2026-09-21): alarms are cancelled on
shutdown (boot receiver reschedules) and on force-stop.
The receiver settles the countdown from the real clock (no UI loop needed),
posts a high-importance alarm-sound notification unless the screen is up
(`uiVisible`), and a countdown that finished while the process was dead is
detected on restore (`restore()` returns it; boot receiver notifies).
Verified on the test phone: alarm scheduled as exact (`exactAllowReason=
policy_permission`), fired on time with the app in the background, cold/warm
paths log correctly, foreground skips the notification, denied notification
permission is handled without a crash. **Not verified:** the notification and
its sound actually appearing — MIUI blocks granting POST_NOTIFICATIONS from
the shell, so it needs a hand tap on the runtime dialog. Also untested:
reboot rescheduling, Doze delivery with the screen off for a long time,
the sound over headphones.

**One UI-style theme (companion mode).** Design taken from press descriptions
of One UI 9's media player (the reference video itself could not be viewed):
colors driven by the album art, colorful waveform progress, title/artist over
the artwork, blur, smooth transitions. Implemented: `render/WaveProgress.kt`
(animated sine wave up to the position, dim flat remainder, round thumb, time
labels; wave flattens smoothly when paused), title/artist overlaid on the
artwork with a scrim, tinted gradient background + accent derived from the
artwork (`deriveArtColors`, HSL-based, pure and unit-tested; greys stay
neutral). Not done, deliberately: visible circular transport buttons (this
app is gesture-driven and a button would also trigger tap gestures — could be
added as an overlay if wanted), real blur (RenderEffect measured too costly,
see N.5c), a "glass" visualizer. The equalizer is not used in this screen
(still used by local audio mode). Performance: the wave canvas has its own
graphics layer; without it every tick re-recorded the whole screen (16 ms
median); now 13 ms median while playing, **0 frames while paused**.

### N.5e Beat-reactive wave (2026-09-21)

Request: the One UI player's waveform reacts to the music in real time (lows,
mids, highs). Reference image shows a filled, colour-graded wave along the
progress bar with a ring thumb.

**Mechanism (verified on the test phone, Android 16 / HyperOS 3.0):**
`android.media.audiofx.Visualizer` on audio session 0 (the output mix). The
API documentation (mirrored copies; the live reference page could not be
fetched) says the output mix needs `MODIFY_AUDIO_SETTINGS` and that using the
Visualizer requires `RECORD_AUDIO`; it also calls the data partial and
low quality — enough for visualization, not recording. Measured here:
capture size 1024, 48 kHz, 20 Hz updates, real non-zero data from **another
app's** playback (Mi Music) with low/mid/high levels swinging live. So
"react to any playing app" works on this device without MediaProjection.
Not verified on other devices/ROMs: session-0 capture is known to be
device-dependent, so `AudioLevelSource.status` reports ACTIVE / SILENT /
FAILED and the wave falls back to a faint idle ripple (no fake beats).

**Cost to the user:** the `RECORD_AUDIO` runtime permission (plus
`MODIFY_AUDIO_SETTINGS`, normal). The app never uses the microphone and
records/stores/sends nothing; it only measures three band levels. A
one-time in-app card (`BeatAccessCard`) explains this before the system
dialog. The card itself was not seen on-device (the permission was granted
by the user through system settings before it was shown).

**Design:** `nowplaying/AudioBands.kt` (pure, unit-tested): FFT -> low
(20-250 Hz) / mid (250-2500) / high (2500-12000) RMS; `BandNormalizer`
(adaptive gain + beat emphasis = rise above the recent average, so steady
loudness reads calm and hits read as peaks — the first version, per-band peak
scaling only, gave flat-topped plateaus); `BandSmoother`. `AudioLevelSource`
runs only while companion mode is playing AND the Activity is visible AND the
permission is granted (verified: stops on Home, restarts on return).
`render/WaveProgress.kt`: three translucent filled bands (bass tallest) from
art-derived analogous hues, scrolling left from the thumb so the newest audio
is at the current position, smooth curves, tapered at both ends, ring thumb,
flattens when paused. Frames: ~58/s while playing (median 13 ms), 0 when paused.

### N.5f Branding and artwork polish (2026-09-21)

- **Name/icon:** app label "Cadence" (later renamed SetBeat, see `DECISIONS.md`); new adaptive icon generated in code (Pillow): a timer
  progress ring (dim track, gradient arc up to a ring thumb) enclosing three translucent wave hills
  in the same hues as the in-app wave, on a dark indigo gradient. Ships as adaptive layers
  (background/foreground) plus a **monochrome layer** for Android 13+ themed icons, in every
  density; the old artwork-derived launcher PNGs were removed. Not verified: how each launcher
  (other than the test phone's system UI, where it rendered correctly) masks or themes it.
- **Artwork bars:** `render/ArtTrim.kt` crops flat-colour bars that some apps (Mi Music) bake into
  the sides of a portrait cover. Deliberately conservative (bars must exist on both opposite
  sides, be one flat colour, agree in colour, and leave >= 40% of the image), unit-tested with
  synthetic images including noisy bars and full-bleed art that must NOT be cropped. Applied once
  per track in `NowPlayingRepository`, so the colour extraction samples the real cover too. The
  tile also caps upscaling at ~6 screen px per artwork px so a small cropped cover stays sharp
  enough. Not verified on a real barred cover on-device (the track playing during testing had
  full-bleed art, where the trim correctly did nothing).

### N.5g Bugs found by the owner and by soak testing, fixed (2026-09-21)

Reported by the owner after using v0.2, each reproduced or explained from the code and covered by a
regression test where the logic is pure:

1. **Wave started "forward from the beginning" after ~2:30.** The scrolling history held only 60
   columns (~240 dp, about 2 s of audio); once the thumb was further along the bar than that, the left
   part of the bar had no wave. History is now 160 columns (wider than any portrait phone). Verified:
   over 31 consecutive shots the thumb moved x=432 to x=1152 while the wave's left edge stayed at the
   start of the bar (x~100, the end taper).
2. **Wave shrank or vanished mid-song.** Two causes. (a) The level was almost entirely "rise above
   the recent average", so a quiet steady passage after a loud one collapsed to ~0, and the mid/high
   bands were further scaled to 70%/50%: levels now have a 0.16 floor, the gain recovers ~2x faster
   (peak decay 0.985) and band scales are 1.0/0.8/0.62 (regression tests: a quiet passage after a loud
   one stays >= the floor). (b) **A stale-capture bug:** the wave's update loop decided once, at
   start, whether live audio existed; the capture restarts at each track change and comes up a moment
   later, leaving the loop stuck in the faint "no audio" ripple until an unrelated playback update.
   The status is now read every tick. Reproduced on-device (identical faint shape across shots while
   levels were healthy) and re-verified after forced track changes.
3. **A tiny finger movement changed the song.** The two-finger swipe read "the first pointer currently
   down"; if one finger lifted slightly before the other, the survivor became "first", its x jumped a
   whole finger-gap, and a tap read as a swipe. Now `TwoFingerTracker` follows each pointer by id,
   freezes a finger's displacement when it lifts, averages them, and requires the swipe to be mostly
   horizontal. Unit-tested including the lift-order scenarios. Not verifiable by script on the test phone
   (touch injection is blocked): needs the owner's hands.

**On-device gesture tests (new).** Shell touch injection is blocked on this phone (MIUI), but
Compose's own test framework (`createComposeRule` + `performTouchInput`) dispatches pointer events
straight into the app and works. `app/src/androidTest/.../TimerGesturesInstrumentedTest.kt` runs the
REAL gesture state machine on the phone: 18 tests covering two-finger tap/swipe (both lift orders,
jitter, three fingers), single/double taps in every zone, corner menus, drag-scrub, a quick flick,
and hold-to-reset in real time (early release and a second finger cancelling it). Proof they detect
the bug: with the pre-fix gesture code, 4 of the first 10 fail (lift-order, jitter, plain two-finger
tap, quick flick); with the fix all pass. Running them: build with
`assembleDebug assembleDebugAndroidTest`, `adb install -r -t` both APKs and
`adb shell am instrument -w dev.fitnesstimer.test/androidx.test.runner.AndroidJUnitRunner`.
Do NOT use `connectedDebugAndroidTest` on a phone with real data: Gradle uninstalls the app
afterwards. Test-harness gotchas learned: a `performTouchInput` block delivers its events together
when it ends (put real pauses BETWEEN blocks), and the engine measures holds in real time but wakes
on virtual-clock timeouts (also call `mainClock.advanceTimeBy`).

A second gesture bug found this way: a very fast single-finger flick (finished inside the 100 ms
grace before a drag begins) fell through as a "tap" and toggled the timer. A finger that travelled
past touch slop is now never a tap (`movedPastSlop`).

Found by soak testing: at every track boundary Mi Music reports "no session" for ~150 ms, which
flashed "Nothing playing" and restarted the audio capture. `NullGrace` now holds the last value for
1.5 s (unit-tested) and the capture is stopped only after 2.5 s of not-playing. Result: 0 flashes
across repeated forced track changes. The bar-trim was also seen working on a real barred cover
(256x144 -> 146x144).

### N.5h Timer and stopwatch pass, stuck-ring fix, cleanup (2026-09-21)

- **Stuck hold-to-reset ring (owner report).** The gesture engine reported hold progress while the
  finger was held but never reported it back to 0 on release, so an early release left a partial ring
  and a completed reset left a full circle on screen. Every gesture now ends by clearing the ring, and
  a completed reset keeps the full ring visible until the finger lifts. Covered by on-device tests
  (early release, completed reset, ordinary tap while paused, drag away from a hold).
- **Countdown input.** `parseCountdown` (pure, unit-tested) replaces a private parser that gave no
  feedback on bad input and could overflow to a garbage target with a huge number of minutes. Accepts
  `mm:ss` and `h:mm:ss`, digits only, seconds/minutes < 60, capped at 99:59:59; the dialog now shows
  an error message.
- **End-to-end timer tests on a device** (`TimerFlowInstrumentedTest`, 8 tests through the real
  `MainScreen`): stopwatch start/pause/resume totals, hold-to-reset only while paused, holding while
  running does not reset, a countdown running to zero and stopping, a finished countdown restarting only
  after a reset, 21 rapid taps ending in the right state, switching mode while running, and state saved
  on every transition. The first version was flaky because it slept in real time and then jumped the
  virtual clock; time now passes on both together. 3 of 3 full runs of all 30 on-device tests pass.
- **Cleanup:** removed code nothing used (`sampleColorFromBitmap`, `TwoFingerTracker.pointerCount`,
  the never-wired session override in `NowPlayingRepository`/`SessionSelector`, three `NowPlaying`
  capability flags only their own tests read, unused imports), the 850 MB stale heap dump, and the
  intermediate test logs (kept: the probe, alarm, stress and gesture logs).
- **Name.** "Cadence" collided with Cadence Design Systems' registered CADENCE mark; the owner then
  proposed "RepBeat", which turned out to be an existing workout interval-timer app, so the app became
  **SetBeat** (details and the checks made are in `DECISIONS.md`).

### N.5i Rename to SetBeat, banner, changelog (2026-09-21)

- App label and gradle project renamed to **SetBeat**, repo to `setbeat`; the package id stays
  `dev.fitnesstimer` so installs upgrade. Naming history and the checks behind it: `DECISIONS.md`.
- `docs/banner.png` (1280x640, generated in code) is the README header and the intended GitHub social
  preview (uploaded by hand: GitHub has no API for it).
- `CHANGELOG.md` summarises every release; release notes on GitHub stay the detailed record.
- Verification for the rename release: 123 unit tests, lint, clean release build, the 30 on-device
  tests, and a launch/capture smoke test of the release APK on the test phone.

### N.5j Play Protect block, two editions, release signing (2026-09-21)

Owner report: on other phones the APK shows Google Play Protect's "App blocked to protect your device".
Cause and decision are recorded in `DECISIONS.md` (sideloaded apps declaring notification-listener,
SMS or accessibility access are blocked in select markets; no user override). Result: `full` and
`standalone` product flavors (same package id) selected by `companion_enabled`, per-edition manifests,
per-edition on-device tests (34 standalone, 33 full, all passing), a private release key
(`keystore.properties`, gitignored) and published fingerprint. Not verified: the standalone APK on a phone
that actually applies the block; installing the release-signed APK (needs an uninstall of the
debug-signed one, so it was not done on the only test phone).

### N.5k Silent beat wave: honest hint, real fix attempt, third edition (2026-09-22)

Owner report: at the gym, in companion mode, the wave stayed a flat idle ripple. Full investigation
and decisions recorded in `DECISIONS.md`; summary here:

1. **Root cause, confirmed on-device**: hardware audio offload (a battery-saving decode path some
   players use for compressed formats) runs outside AudioFlinger's mixer, so the wave's audio reader
   (`Visualizer`, session 0) never sees it — over Bluetooth or the phone speaker, depending on the
   player and even the track. Not Bluetooth-specific, despite that being the most common trigger.
2. **`full`/`standalone` fixed to be honest about it**: `AudioLevelSource.Status.SILENT` is now
   surfaced in the UI instead of looking identical to "nothing playing" (`BeatSilentHintCard`), and
   the hint only blames Bluetooth when `AudioManager.getDevices()` confirms it's actually connected.
3. **Real fix attempted and found not to help**: `Visualizer` can attach to a specific track's
   session instead of the global mix, which forces that track out of offload — *if* the player
   broadcasts its session id (a long-standing but optional Android convention). Implemented and
   tested against both real apps available (YouTube Music, Mi Music) by forcing real track changes on
   the connected test phone: neither sends the broadcast. Rolled back (never committed).
4. **Third edition added**: `vibes` — same companion-mode track info as `full`, but the wave is a
   procedural animation (`WaveProgress.kt`'s `fakeBeatTarget`) instead of real audio analysis, and it
   declares no Record audio permission at all. Verified on-device: animates convincingly, correctly
   skips all the real-capture permission/hint UI (`fake_wave` resource, `MainScreen.kt`).
5. **Regression check**: full check suite re-run after each change (369 unit tests across all three
   editions, lint clean, permissions re-verified with `aapt2`); `full`'s real wave and hint UI
   re-confirmed working on-device, unaffected by the shared-code changes for `vibes`.

### N.5l Silent wave correction and fix, security hardening (2026-10-13, v0.3.4)

1. **Root cause corrected** (see `DECISIONS.md`): on the owner's POCO X7 Pro, Xiaomi's *Immersive
   sound* routed the music through the spatializer, which `Visualizer(0)` doesn't see. Not hardware
   offload (still a real, separate limit). Confirmed by toggling it: `peak=0.0` → `peak≈100`.
2. **Fix**: `AudioLevelSource` falls back from session 0 to the playing track's own session, found by
   scanning recent session ids (only live sessions can be opened). Re-scans on track change, at most
   once per 10 s, only while the companion screen is visible and playing. Verified on-device with
   Immersive sound on, including a track skip.
3. **Security audit** (source-only): no confirmed vulnerability; `PlaybackService` un-exported;
   release signing fails closed without the private key.
4. **Docs corrected**: the offload/Bluetooth-only story in README, DECISIONS, the hint card and the
   stale "Vibes is a local experiment, not distributed" comments.
5. **Not covered**: scripted track skipping and pause/resume could not be automated on this ROM
   (injected input is blocked), so the track-change check was a manual skip by the owner.

### N.6 Phases

- **P0 — Probe (device) — DONE 2026-09-21, see N.4:** with music playing, dump `dumpsys media_session`
  for YouTube Music: which metadata keys/artwork it sets, playback actions,
  position updates. Record results in `DECISIONS.md`.
- **P1 — Core — DONE 2026-09-21, see N.5b:** listener service + repository + `SessionSelector` +
  `PositionExtrapolator`; unit tests for both pure pieces. Test sources: our
  own `PlaybackService` session and YouTube Music on the phone.
- **P2 — UI & control — DONE 2026-09-21, see N.5c:** `NowPlayingScreen` reusing `AudioVisual`;
  gestures → transport controls; `NotificationAccessScreen`.
- **P3 — Background countdown alarm — DONE 2026-09-21 (notification/sound still to be verified by hand), see N.5d:** exact alarm + high-importance
  notification (section F), since the screen will usually be off.
- **P4 — Polish — MOSTLY DONE:** empty/idle state ("nothing playing") done; the fate of local
  playback is decided (keep). Still open: accessibility semantics. A per-app session override was
  dropped as unused code (the selector and repository no longer carry it; re-add only if wanted).

Testing: `adb shell input` and permission grants are blocked on the test phone (MIUI), but gestures
and the whole timer flow are tested on-device with Compose's own test framework (N.5g/N.5h);
permission dialogs, the Settings toggle and the countdown notification still need hands.
