# Project Structure

The app is **SetBeat** (repo `setbeat`; formerly *Fitness Timer*, then *Cadence*); the Android package is still
`dev.fitnesstimer`. Living map, not a spec — update it when the structure moves.

```
setbeat/
├── README.md  PLAN.md  STRUCTURE.md  DECISIONS.md  CHANGELOG.md  CONTRIBUTING.md  LICENSE
├── docs/                            # logo, banner (also the GitHub social preview) and README screenshots
├── .github/ISSUE_TEMPLATE/          # bug report template
├── keystore.properties (gitignored, local only)  # points at the private release key
├── test-logs/                       # saved unit/lint/device test run logs
└── app/
    ├── build.gradle.kts
    └── src/
        ├── full/                        # FULL edition extras: NotificationListenerService + Record audio manifest, companion_enabled=true
        ├── standalone/                  # STANDALONE edition: companion_enabled=false, declares none of the blocked permissions
        ├── vibes/                       # VIBES edition: like full minus Record audio; fake_wave=true (procedural wave, DECISIONS.md)
        ├── main/
        │   ├── AndroidManifest.xml
        │   └── kotlin/dev/fitnesstimer/
        │       ├── MainActivity.kt          # entry; keep-screen-on; debug launch hooks (debuggable builds only)
        │       ├── gesture/
        │       │   ├── TimerGestures.kt     # unified pointerInput state machine (PLAN section E)
        │       │   ├── ScrubAccumulator.kt  # coalesces drag-scrub seeks (~10/s) + trailing flush
        │       │   └── TwoFingerTracker.kt  # pure: per-pointer-id tap vs swipe decision (fixes lift-order false swipes)
        │       ├── timer/
        │       │   ├── CountdownInput.kt    # pure: parse "mm:ss" / "h:mm:ss" for the countdown dialog (capped 99:59:59)
        │       │   ├── CountdownAlarm.kt    # pure alarmDelayMs + AlarmManager scheduling + finished notification
        │       │   ├── CountdownAlarmReceivers.kt  # alarm + BOOT_COMPLETED receivers
        │       │   ├── TimerEngine.kt       # elapsedRealtime basis, stopwatch + countdown, snapshot/restore, tick scheduling
        │       │   └── AppTimer.kt          # process-wide singleton + SharedPreferences persistence
        │       ├── media/
        │       │   ├── PlaybackService.kt   # MediaSessionService: ExoPlayer, notification, timer button, controller allow-list
        │       │   ├── ControllerConnection.kt  # suspend MediaController connect
        │       │   └── UriGrants.kt         # safe persist/release of SAF read grants
        │       ├── playlist/
        │       │   ├── Playlist.kt
        │       │   └── PlaylistRepository.kt    # atomic JSON file; corrupt file kept as .corrupt
        │       ├── render/
        │       │   ├── NegativeTimerText.kt # Difference-blend timer + hold ring (lambda inputs: draw-phase reads)
        │       │   ├── AudioVisual.kt       # artwork tile (native aspect, rounded), equalizer, timer slot
        │       │   ├── ArtTrim.kt           # pure: find/crop flat-colour bars baked into artwork
        │       │   ├── WaveProgress.kt      # One UI-style waveform progress (companion mode); fake=true drives it procedurally (vibes edition)
        │       │   └── AmbientColor.kt      # scaled-frame Palette sample; bounded artwork decode; ArtColors (HSL, pure)
        │       └── ui/
        │           ├── MainScreen.kt        # mirrors player state from a MediaController; wires everything
        │           ├── MediaSourceMenu.kt  PlaylistScreen.kt  TimerModeMenu.kt
        ├── androidTestFull/  androidTestStandalone/   # per-edition on-device checks of the installed package
        ├── test/kotlin/dev/fitnesstimer/    # JVM unit tests (timer, alarm, gestures, audio bands, selection, colours, playlists, art trim)
        └── androidTest/kotlin/dev/fitnesstimer/   # on-device Compose tests: gesture engine (22) + full timer flow via MainScreen (8)
```

## Companion mode (v0.2, see `PLAN.md` section N) - all phases built

```
        ├── nowplaying/                       # BUILT (P1)
        │   ├── NowPlayingListenerService.kt  # NotificationListenerService, used only to authorize session access
        │   ├── NowPlayingRepository.kt       # MediaSessionManager + per-session MediaController callbacks -> StateFlow
        │   ├── SessionSelector.kt            # pure: which session to show (playing, most recent, override)
        │   ├── PositionExtrapolator.kt       # pure: position + elapsed * speed
        │   ├── NullGrace.kt                  # pure: hold the last value through ~150 ms between-track gaps
        │   ├── AudioBands.kt                 # pure: FFT -> low/mid/high, adaptive gain + beat emphasis, smoothing
        │   ├── AudioLevelSource.kt           # Visualizer capture: session 0, then the playing track's own session when the mix is silent (needs RECORD_AUDIO), status ACTIVE/SILENT/FAILED
        │   └── NowPlaying.kt                 # data class (title, artist, album, artwork, position, state, actions)
        └── ui/
            └── CompanionScreen.kt            # BUILT: CompanionBody (reuses render/AudioVisual + title/artist + timer),
                                              # progress line, NotificationAccessCard (onboarding overlay)
```

Notes:
- `ui/MainScreen.kt` holds the source mode (companion vs local) and routes gestures per
  mode; `ui/MediaSourceMenu.kt` has the "Now playing (other apps)" entry.
- The player (via `MediaController`) remains the single source of truth for
  local playback. Companion mode adds a second, independent source of the
  same shape; the fate of local playback is an open decision
  (`DECISIONS.md`), so nothing under `media/`/`playlist/` is removed yet.
- No `state/` holder or DI layer: `AppTimer` (object) + state mirrored from
  the controller is enough at this size. Revisit if `MainScreen` grows
  another source kind and gets unwieldy — companion mode is the likely
  trigger for extracting a `MediaState` (PLAN section G).
- `media/online/` — dropped from v1 (2026-09-20). YouTube playback from a
  link — decided against in-app playback 2026-09-21 (PLAN section N).
- Package name: `dev.fitnesstimer` (revisit before any wider release).
