# Open Questions & Decisions

Things that research couldn't settle and need a real answer (from the user,
or from an on-device test) before or during implementation. Update this file
as each is resolved — move resolved items into `PLAN.md` instead of leaving
them here.

## Resolved

- **Google Play Protect blocks sideloaded installs — RESOLVED 2026-09-21 with two editions.** Owner
  report: on phones other than the test phone, installing the APK shows "App blocked to protect your
  device / This app can request access to sensitive data" with only OK. Cause (Google's developer
  guidance, `developers.google.com/android/play-protect/warning-dev-guidance`, read through a
  summarizing fetch): in select markets (Google has piloted India, Singapore, Thailand, Brazil), Play
  Protect automatically blocks apps installed from "Internet-sideloading sources" (browser, messaging
  app, file manager) if they use RECEIVE_SMS, READ_SMS, NOTIFICATION_LISTENER or ACCESSIBILITY; the
  dialog has no override. SetBeat declares a NotificationListenerService (the only way to read other
  apps' media sessions), so it was blocked. The test phone never showed it because `adb install` is not
  an internet-sideloading source. Decision: ship **two editions** (same package id): **full** (companion
  mode: notification listener + Record audio; for Google Play or `adb`) and **standalone** (no
  notification listener, no audio capture, no SMS/accessibility; installs anywhere; local media and
  timer only), with on-device tests that fail if the blocked declarations reach the standalone edition.
  Deliberately NOT done: hiding or delaying the service declaration to slip past the scan (that would
  evade a fraud-protection control and risks the app being flagged as harmful). Not verified: the
  standalone APK on a phone that actually applies the block (only the test phone was available; the
  manifest was inspected and tested instead). Other routes for the full edition: publish on Google Play
  (Play installs are not blocked; needs a developer account, closed testing, and a policy review of
  notification-listener, Record audio and exact-alarm use) or file a Play Protect appeal (outcome
  unknown; may not apply to a permission-based block).
- **Signing key — DECIDED 2026-09-21.** Releases are signed with a private RSA-4096 key
  (CN=SetBeat, valid to 2054) kept outside the repo; `keystore.properties` and `*.jks` are gitignored.
  Its SHA-256 fingerprint is published in the README. Consequence: the earlier debug-signed releases
  (0.1 to 0.2.3) cannot be updated in place; the first installs of this key need an uninstall. The key
  must be backed up by the owner: losing it means no future update can install over existing copies.
- **R8/minify not enabled — 2026-09-21.** Considered for a smaller APK (~29 MB now). Not enabled because
  the release-only code paths (Media3 service, receivers, alarm, listener) cannot be fully exercised
  on-device in a non-debuggable build, so a keep-rule mistake would ship untested; revisit with a
  minified debuggable "QA" build type that the on-device tests can run against.
- **Local playback — DECIDED 2026-09-21: keep it as it is** (owner). Video and audio files,
  playlists and the ExoPlayer service stay alongside companion mode; companion mode is the default and
  a menu entry switches sources. It is built, tested, idle unless used, the only source for offline
  files, and the home of the original negative-blend video look.
- **Cleanup pass — done 2026-09-21** (`PLAN.md` N.5h): unused code, stale heap dump and intermediate
  test logs removed; nothing user-visible changed.
- **Beat wave silently not reacting — RESOLVED 2026-09-22 with an honest in-app hint; root cause
  confirmed on-device, and it is not Bluetooth-specific. (CORRECTED 2026-10-13: on the owner's phone
  the cause was the system spatializer, not hardware offload — see the 2026-10-13 entry below. The
  offload explanation here is real Android behavior but was not what was happening on this phone.)**
  Owner report: at the gym, in companion mode
  over Bluetooth headphones, the wave stayed a flat idle ripple. `AudioLevelSource` already
  distinguished this (`Status.SILENT`: the visualizer is running, RECORD_AUDIO is granted, but capture
  keeps returning zeros) from "nothing playing", but the UI never surfaced the difference — both looked
  identical. Reproduced immediately after on the connected test phone with **no Bluetooth involved**:
  Mi Music playing over the phone's own speaker at full volume, `[viz] peak=0.0` continuously, despite
  the same device having captured real data (`peak≈100`) in an earlier session (`test-logs/device-viz-
  probe.log`) — so this is track/player-dependent, not a fixed device limitation. Root cause, confirmed
  via a detailed technical writeup of the AOSP audio stack (`nift4.org`, cross-checked against a
  Chromium bug on audio-offload power savings; Android's own reference docs don't document this):
  compressed formats (MP3, FLAC, …) are commonly decoded on a low-power DSP via "hardware offload",
  which runs on a `DirectOutputThread` instead of AudioFlinger's mixer — global-mix effects like
  `Visualizer`(session 0) can only attach to a mixer thread, so an offloaded track is invisible to them
  regardless of output device. Offload is a per-track decision made by the player/OS, not something a
  Bluetooth connection uniquely causes (Bluetooth is only the most common trigger a listener notices,
  because Bluetooth streaming engages it especially often). Decision: don't try to detect Bluetooth
  output programmatically (would need `AudioManager.getDevices()`, whose exact permission/behavior
  wasn't confirmed, and it would misdiagnose cases like this one anyway) — instead show a dismissible
  card when `Status.SILENT` persists while companion audio is playing, stating plainly that this is a
  hardware power-saving path some players use (over Bluetooth or the speaker) with no reliable in-app
  fix, and that disabling "Bluetooth A2DP hardware offload" in Developer options is worth trying
  specifically for the Bluetooth case (`CompanionScreen.kt`'s `BeatSilentHintCard`). There is no known
  general fix for local-playback offload short of the source app itself offering a "disable offload" or
  "software decode" setting.
- **Silent-wave card wrongly implied Bluetooth when it wasn't — RESOLVED 2026-09-22, supersedes the
  "don't try to detect Bluetooth" call above.** Owner report: saw the card mention Bluetooth while not
  using Bluetooth audio, which reads as the app misdiagnosing its own state. The earlier decision
  against `AudioManager.getDevices()` was because its permission requirement wasn't confirmed — now
  checked directly against the AOSP source (`frameworks/base/media/java/android/media/AudioManager.java`):
  `getDevices(int)` carries no `@RequiresPermission` annotation, and has existed since API 23 (below
  this app's minSdk 29), so it needs no manifest change. Added
  `isBluetoothAudioOutputActive()` (`AudioLevelSource.kt`), checking `GET_DEVICES_OUTPUTS` for
  `TYPE_BLUETOOTH_A2DP`/`TYPE_BLE_HEADSET`/`TYPE_BLE_SPEAKER`/`TYPE_BLE_BROADCAST`. `BeatSilentHintCard`
  now picks accurate wording: the Bluetooth-offload tip only when Bluetooth is actually connected,
  otherwise a message that explicitly rules Bluetooth out and names the player's own decode path
  instead. Caveat: `getDevices()` reports currently-connected devices, which is a fine proxy for the
  active route in the common case (only one output connected) but could be wrong if a phone has
  multiple simultaneous outputs and routes elsewhere — not verified against such a setup.
- **Real fix for the silent wave attempted and found not to help — 2026-09-22, third edition added
  instead.** Owner asked for the wave to actually work, not just explain itself. Researched and
  implemented the one legitimate mechanism that can get real data past hardware offload: `Visualizer`
  can attach to a specific track's session instead of the session-0 global mix, and Android excludes
  a session with a non-offloadable effect attached from offload — but only if the player announces its
  session via the standard (optional, long-standing) `android.media.action.OPEN_AUDIO_EFFECT_CONTROL_
  SESSION` broadcast. Implemented (`EffectSessionReceiver`, `AudioLevelSource.setTargetSession`) and
  tested on the connected test phone by forcing real track changes (via a temporary debug hook, since
  this ROM also blocks scripted input and `am start`'s `onNewIntent` delivery to an already-foregrounded
  activity) on **both real apps available**: YouTube Music and Mi Music. Neither sent the broadcast on a
  fresh track. Conclusion: this app has no real second data point to test against and the mechanism
  provides no verified benefit for this owner's actual use — rolled back (never committed) rather than
  ship unverified complexity. It could still help with a player that does support the convention (VLC,
  installed on the test phone but not tested — no track was queued); worth revisiting if that becomes
  relevant.
  Decision: add a third product flavor, **`vibes`** — same real companion-mode track info as `full`
  (needs notification access), but the wave is a procedural animation (`WaveProgress.kt`'s
  `fakeBeatTarget`, driven by playback position so it's deterministic, not audio data) instead of real
  analysis, and it declares no Record audio / Modify audio settings at all (`fake_wave` resource,
  gates `AudioLevelSource` out entirely in `MainActivity.kt`/`MainScreen.kt`). This is a genuine
  reversal of the "idle ripple, not fake beats" principle the honest editions keep — deliberate, and
  scoped to this one edition only, so the choice between honesty and always-looks-alive is the
  installer's, not silently made for them. Verified on-device: the wave visibly animates with distinct
  rhythmic peaks across several screenshots, and none of the real-capture permission/hint UI
  (`BeatAccessCard`, `BeatSilentHintCard`) appears, confirmed by code (guarded on `!fakeWave`) and by
  screenshot. Regression check after both changes: 369 unit tests (up from 246 across the two prior
  editions) all pass, lint clean on all three editions, `aapt2 dump permissions` re-confirmed
  `standalone` has none of the Play-Protect-blocked declarations and `vibes` has no audio permissions,
  and `full`'s real wave/hint behaviour re-verified working on-device, unaffected by the shared-code
  changes needed to thread the `fake`/`fakeWave` flag through.
- **Name — DECIDED 2026-09-21: "SetBeat"** (repo `setbeat`), after two rejected candidates.
  *Cadence* (used briefly) collides with Cadence Design Systems' registered CADENCE mark (US Reg. No.
  3474136, Class 9, IC-design software). *RepBeat* (proposed by the owner) already exists as an App
  Store workout interval timer ("Interval Timer: RepBeat") and as the workout-music app "Repbeats",
  i.e. the same product space. Web searches for SetBeat, RepWave and BeatSet found no app with that
  exact name (only differently named workout-music/timer apps, e.g. FitBeat). **That is a search, not a
  trademark clearance, and not legal advice:** before any store listing, search USPTO/EUIPO/WIPO and
  the stores for the exact name (Google Play's process on a complaint is that the owner contacts the
  developer or files a form, which can end in a takedown). For GitHub/sideload use the practical
  risk looks low; it rises with a store listing, money or popularity. The package id stays
  `dev.fitnesstimer` so installs keep upgrading.
- **Icon — DECIDED 2026-09-21:** a timer progress ring (dim track, gradient arc up to a ring thumb)
  around the layered wave hills, on a dark indigo gradient; adaptive with a monochrome layer. It fits
  the SetBeat name (a set's progress ring plus the beat).
- **Banner — 2026-09-21:** `docs/banner.png` (1280x640) is used at the top of the README and is meant to
  be the repo's GitHub "social preview". GitHub offers no API for that setting, so it is uploaded by hand
  (repo Settings > General > Social preview).
- **Licence — Apache-2.0 added 2026-09-21**, as `PLAN.md` section M had planned from the start.
- **Beat-reactive wave needs `RECORD_AUDIO` — DECIDED 2026-09-21** (user asked
  for a real-time, One UI-style reactive wave). The only unprivileged way to
  see another app's audio is `Visualizer` on the output mix, which Android
  gates behind `RECORD_AUDIO`; MediaProjection playback capture was not
  needed. Mitigation: explanation card first, capture only while visible and
  playing, no microphone use, nothing stored or sent. Works on the test phone
  (PLAN.md N.5e); other devices/ROMs unverified, with an honest idle
  fallback. Play Store review of a `RECORD_AUDIO` declaration for a non-
  recording use is an open question if Play distribution is ever chosen.
- **Exact-alarm permission — DECIDED 2026-09-21: `USE_EXACT_ALARM`** (plus
  `SCHEDULE_EXACT_ALARM` for Android 12 only, maxSdk 32). Per the Android
  alarm docs it is auto-granted on Android 13+ with no user flow, but is
  subject to Google Play's limited-use-cases policy. A countdown timer
  should qualify as a genuine timer/alarm use, but that is a **Play Console
  question to confirm before any Play submission**; GitHub/F-Droid
  distribution is unaffected. `SCHEDULE_EXACT_ALARM` alone would need a
  per-user grant flow on Android 13+.
- **YouTube / YouTube Music "play from link" — DECIDED AGAINST, 2026-09-21.**
  Researched three approaches (details and sources in `PLAN.md` section
  N.1). Official IFrame embed: forbids overlays on the player and
  background/audio-only playback — incompatible with the negative timer
  overlay and screen-off gym use. Unofficial extraction (NewPipeExtractor,
  GPLv3): outside YouTube's rules, fragile, forces GPLv3, no Play Store.
  Chosen instead: **companion mode** — read and control another app's media
  session; the source app does the playback. Policy quotes came via a
  summarizing fetch; re-read the original pages before quoting them
  externally.
- **v0.2 order — decided 2026-09-21:** build companion mode first, decide the
  fate of local playback afterwards. Nothing is removed until then.

- **Code-review hardening pass — RESOLVED 2026-09-21, verified on-device.**
  The player is now the single source of truth for the queue
  (`setMediaItems` once; UI mirrors it via a listener), which gave real
  next/previous/auto-advance, the notification's Next button, and survival
  of Activity recreation. Also: missing/unreadable files skip to the next
  item with a message; audio focus + becoming-noisy + wake lock on the
  player; keep-screen-on; bounded artwork decode and scaled ambient-color
  frame (no OOM on huge art); timer ticks once per displayed second and is
  read in the draw phase; equalizer animates only while playing; scrub
  seeks coalesced (`ScrubAccumulator`); gesture handler no longer restarts
  on state change; timer persisted across process death
  (`TimerSnapshot`, wall-clock gap credited up to 12h); picker grant
  failures no longer crash and grants are released on playlist delete;
  corrupt playlist file kept as `.corrupt` instead of overwritten;
  PlaybackService only accepts own/system/notification controllers; debug
  launch hooks only work in debuggable builds. Not testable here (MIUI
  blocks injected input): the gesture state machine itself, the SAF picker
  UI, HyperOS Island. Known remaining limits: Android caps persisted URI
  grants per app (not surfaced to the user); countdown completion is still
  foreground-only (no background alarm).
- **Media wouldn't play — RESOLVED 2026-09-21, confirmed on-device.** Root
  cause was `PlaybackService.onConnect` granting *empty* command sets: the
  `super.onConnect()` result's `Player$Commands` / `SessionCommands` both
  hashed as empty-set values in logcat, so inheriting them denied every
  `setMediaItem`/`play` from the controller silently (no player state
  change on either side, no error). Fixed by granting explicitly
  (`Player.Commands.Builder().addAllCommands()` + the public
  `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` plus the custom timer command).
  Two other real bugs were fixed on the way: the notification button's
  missing icon resource (crashed every launch) and a Compose
  `DisposableEffect` closure that released the controller right after
  connecting. Diagnosis method worth reusing: a debug launch hook
  (`--es debug_media_uri file:///sdcard/Android/data/dev.fitnesstimer/files/x.mp4`
  on `MainActivity`) plus `FitnessTimer`-tagged logs, since MIUI blocks
  scripted taps and the SAF picker can't be driven from adb.

- **Rendering approach — RESOLVED 2026-09-20, confirmed on-device.**
  `TextureView` (`PlayerSurface(surfaceType = SURFACE_TYPE_TEXTURE_VIEW)`)
  + a sibling Compose `drawText(blendMode = BlendMode.Difference)`, with
  `CompositingStrategy.Auto` (not `Offscreen`), genuinely inverts pixels
  under the timer text against live video. Tested on a real phone (Xiaomi/
  POCO, Android 16 / API 36): the same string rendered purple/blue over
  yellow-green grass and near-white over a dark shadow in the same frame —
  conclusive evidence the blend reads actual video content, not a static
  color. This is the renderer for local/online video. Third-party video
  remains out of scope regardless (see PLAN.md section B) since this
  technique only ever applies to the app's own `ExoPlayer`/`PlayerSurface`.
  (The spike screen was later folded into `render/NegativeTimerText.kt`.)
  Not yet re-verified: the `Offscreen`-breaks-it and API<29-fallback control
  cases (MIUI blocked ADB's synthetic taps on the test device); low priority
  since the positive result already answers the load-bearing question.

- **Online media source — DROPPED from v1, 2026-09-20.** User decided
  direct-URL/HLS/DASH streaming isn't worth the added complexity for a
  local-gym-use app right now. Media3 supports it technically (unchanged
  finding), so re-adding it later is a scoped addition, not a redesign —
  deferred rather than ruled out. `PLAN.md` sections B/D/G/I/J updated.
- **Aspect-ratio / letterbox ambient background — decided 2026-09-20.**
  Native aspect ratio via Media3-Compose's `ContentFrame(contentScale =
  ContentScale.Fit)`, letterbox filled with a one-time dominant color
  (`MediaMetadataRetriever` + `androidx.palette` 1.0.0) rather than a plain
  black bar or a live blurred replay of the video. True YouTube-style live
  blur is deferred — it needs a second simultaneous decode or periodic frame
  capture, real added cost for a cosmetic effect, unlike the TextureView
  blend which turned out to be cheap. Revisit if the static-color version
  looks flat in practice.

- **On-device automated gesture testing isn't available on the current test
  phone.** MIUI/HyperOS blocks ADB's synthetic `input tap`/`swipe`
  (`SecurityException: Injecting input events requires INJECT_EVENTS
  permission`) — confirmed on this device, not a project bug. The gesture
  state machine (`gesture/TimerGestures.kt`) has been compiled and smoke-run
  (a hold-to-reset ring was observed rendering during a real manual touch)
  but not systematically exercised. Real verification of the priority rules
  in PLAN.md section E — double-tap timing, drag-vs-tap thresholds,
  two-finger detection, corner zones — has to happen by hand on-device, or
  via Android Studio's instrumented UI tests (`MotionEvent` injection in a
  test APK isn't subject to the same restriction) if that's worth setting
  up later.
- **Top-right corner now does double duty.** Per the gesture table it's the
  "media source picker"; since there's only one source kind (local files) in
  v1, it directly opens the SAF file picker rather than a picker-of-pickers.
  Revisit this if/when MediaSession sources are added (roadmap step 6) —
  then it needs to actually pick between sources, not just open one.

- **Silent wave root cause corrected, and a real fix found — 2026-10-13 (v0.3.4).** The owner said
  the earlier "hardware offload" diagnosis was wrong for their phone, and tested by hand. On the POCO X7
  Pro (HyperOS, Mi Music playing, Redmi Buds connected), with **Immersive sound** on (Settings →
  Sound effects → Dolby effects; Dolby Atmos itself can stay selected), `dumpsys audio` showed the
  playing track flagged `isSpatialized=true`, running on a dedicated SPATIALIZER output thread
  (`Current effect: dolby, spatializer enabled: true`), and the app logged `[viz] peak=0.0` constantly.
  With Immersive sound off the same app and track gave `peak≈90–130` and live bands. So the
  global-mix `Visualizer(0)` doesn't carry spatialized tracks; that, not offload, was the cause here.
  **Fix:** when session 0 stays silent, `AudioLevelSource` scans recent audio session ids
  (`AudioManager.generateAudioSessionId()` gives the newest; constructing a `Visualizer` on an id
  that doesn't exist fails fast, so only live sessions answer) and attaches to the one carrying real
  audio. Verified on-device with Immersive sound on: it found the track's session within ~4 s
  (`best=98769 peak≈118`) and the wave followed the music; skipping a track made it re-scan and switch
  to the new session (98841 → 98857) in ~4 s; pausing stops the visualizer as before. It reads
  magnitudes only, keeps nothing, runs only while the companion screen is visible and playing, needs
  no permission beyond the existing RECORD_AUDIO/MODIFY_AUDIO_SETTINGS, and is rate-limited to one scan
  per 10 s. It picks the loudest live session, so another app's concurrent audio could win; accepted
  for a visualizer. Hardware offload (a real, separate Android limitation) is still unreadable, and
  `BeatSilentHintCard` now says "already tried the music app's own audio" and names spatial audio as
  something to switch off. Earlier decisions stand where still valid; the "real fix not possible"
  conclusion of 2026-09-22 only covered the announce-broadcast route.
  **Vibes edition:** it was built on the offload premise. It stays published as an always-moving
  option (and its "not distributed" comments were corrected), but Full is now the edition that follows
  real audio in more cases.
- **Security audit, 2026-10-13 (v0.3.4).** A source-only audit (no code run) found no confirmed
  vulnerability and one low-impact lead: `PlaybackService` was `exported="true"` with no permission,
  and Media3 1.11.1's default `onStartCommand` handles media-button and custom-action start intents
  without calling `onConnect`, so another app could in principle toggle the timer or send transport
  commands as the notification controller. Reproduced from `adb shell` on the owner's phone with
  v0.3.3 (one intent started the stopwatch). Fix: `android:exported="false"` — nothing legitimate
  needs the export (system UI and Bluetooth use the platform media session; the app's own controller
  and notification intents are same-app). Verified on-device: the same intents now fail with
  `Requires permission not exported from uid …`, the timer was unaffected, and companion mode and the
  media session still work. Also: release packaging now fails without `keystore.properties` instead of
  silently using the debug key (`-PallowDebugSignedRelease` opts in for throwaway local builds).
  Merged release manifests were checked: exported components are exactly MainActivity, BootReceiver,
  the permission-gated listener (Full/Vibes), and permission-gated library components.

- **F-Droid preparation — 2026-10-13 (v0.3.5), not yet submitted.** F-Droid builds from source and
  signs with its own key, one app per application id. Decisions: (1) list only the **`full`**
  edition (the superset); Standalone and Vibes stay on GitHub Releases. (2) F-Droid-signed installs and
  GitHub-release installs can't update each other (different signing key), so a user picks one source.
  Reproducible-build verification against the GitHub APKs (which would let F-Droid publish the
  maintainer's signature) was not attempted. (3) Repo changes: `dependenciesInfo` off (F-Droid rejects
  the Google-signed blob; confirmed present in 0.3.4's signing block, absent in 0.3.5's); wrapper
  checksum added; `gradle-daemon-jvm.properties` removed (unverified JDK 25 download; the build works
  on a plain JDK 17+, re-checked: tests, lint and all three release builds pass). (4) The release-signing
  fail-closed check needs `-PallowDebugSignedRelease` on F-Droid's builder, set via `gradleprops` in the
  draft recipe (`docs/fdroid/dev.fitnesstimer.yml`). Unverified: F-Droid's build server having Android
  platform 37 (`compileSdk`) and a JDK that runs Gradle 9.7.1 / AGP 9.4.0; reviewers will find out.

## Needs a decision, not urgent

- **Artwork bars — RESOLVED 2026-09-21:** auto-trim implemented (`ArtTrim.kt`, `PLAN.md`
  N.5f). Still to see on a real barred cover.
- **minSdk.** Confirmed constraints: Media3 needs ≥23; `BlendMode.Difference`
  needs ≥29 (silent no-op fallback below that); some haptic constants need
  ≥34. No device-reach data available locally — pick a floor once that's
  known, likely 29 or 30.
- **Distribution channel(s).** F-Droid/GitHub APK vs. Google Play vs. both.
  Affects the restricted-settings/notification-listener onboarding design
  and whether the Play policy check below is needed at all.
- **Accessibility fallback.** TalkBack intercepts one-finger gestures, so
  the app is unusable with it on unless gestures are mirrored as semantic
  actions. Worth doing given how cheap it is relative to the gesture engine
  already being built, but not decided.

## Needs external verification (not answerable from docs)

- **Countdown-finished notification + sound** — needs a hand test: pick a
  countdown, tap Allow on the notification permission dialog, go home, wait.
  Cannot be granted from the shell on this MIUI phone. Also untested:
  reboot rescheduling and long Doze.
- **What the source apps actually publish.** Observed 2026-09-21 with Mi
  Music (`PLAN.md` N.4): keys `ALBUM, ALBUM_ARTIST, ART, ARTIST, DURATION,
  MEDIA_ID, NUM_TRACKS, TITLE, TRACK_NUMBER`; a 256x144 artwork bitmap under
  `ART` and no artwork URI; full transport actions incl. skip and seek. Still
  unobserved: YouTube Music (not playing at probe time) and any other app.
- **Stale sessions.** The YouTube app leaves a `STOPPED` session with
  metadata in the stack; selection must require `PLAYING` and exclude our own
  session. Handle in `SessionSelector` with unit tests.
- **Restricted settings for `NotificationListenerService` on HyperOS 3.0 /
  Android 16** for our adb-installed and GitHub-APK installs. Test phone
  state before granting: listener not enabled, `ACCESS_RESTRICTED_SETTINGS`
  appop at default, installer=null.
- **Listener rebind after HyperOS kills the process** — still untested (the
  shell can't kill the app process). Also: `onListenerConnected` did not
  fire after a `cmd notification allow_listener` grant on this phone, though
  session access worked; the app refreshes on resume instead. Verify with a
  real Settings-toggle grant.
- **Android docs for `MediaSessionManager.getActiveSessions` / `MediaMetadata`
  keys** could not be re-read on 2026-09-21 (the fetch returned navigation
  only); the plan currently relies on earlier research and general
  knowledge for these.
- **Play policy on `BIND_NOTIFICATION_LISTENER_SERVICE`.** No primary Play
  policy page names it. Check directly in Play Console's "App content" flow
  before submitting, if Play distribution is chosen.
- **Restricted-settings trigger specifics.** Whether an F-Droid-client
  install vs. a raw sideloaded APK behaves differently for the Android 13+
  notification-listener toggle block isn't stated in any primary source
  found. Test on a real device with each install method.
- **Android Developer Verification 2027 rollout.** Global sideload
  enforcement is expected but unspecified in detail. Revisit before
  finalizing a distribution plan.
