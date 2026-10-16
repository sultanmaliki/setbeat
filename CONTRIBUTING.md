# Contributing to SetBeat

Thanks for looking. This is a small personal-use project, so the bar is simple: keep it small,
keep it private, and say what you actually tested.

## Ground rules

- **No network, accounts, analytics or paid services.** The app has no internet permission and
  that is a feature. A change that needs a new permission must justify it in the README's
  permissions table and in `DECISIONS.md`.
- **Don't invent Android behaviour.** If a change relies on an API's behaviour, check the primary
  documentation or source, and in `PLAN.md` / `DECISIONS.md` separate what you confirmed from what
  you assumed. "It compiled" and "it worked on my phone" are different claims; say which one you
  mean.
- **Keep it simple.** Prefer the smallest change that works; don't add layers "for later", and delete
  code that nothing uses.
- **Don't change the package id (`dev.fitnesstimer`).** The app has been renamed twice; the id stays
  so installs keep upgrading.

## Setup

You need Android Studio (its bundled JDK is fine), an Android SDK with API 37, and a phone or
emulator on Android 10 (API 29) or newer. The Gradle wrapper does the rest. See
[`STRUCTURE.md`](STRUCTURE.md) for where things live.

> **Windows + Git Bash:** point `JAVA_HOME` at Android Studio's `jbr` folder, and do **not** set
> `MSYS_NO_PATHCONV=1` in the same shell as `gradlew.bat`: it stops the path being converted and
> Gradle then fails without a useful message. Use it only for the `adb` commands.

## Before you open a pull request

```bash
./gradlew testFullDebugUnitTest testStandaloneDebugUnitTest testVibesDebugUnitTest \
  lintFullDebug lintStandaloneDebug lintVibesDebug \
  assembleFullRelease assembleStandaloneRelease assembleVibesRelease
```

All of it must pass, with lint clean. Then, depending on what you touched:

### Unit tests (JVM, `app/src/test`)
Pure logic lives in small functions so it can be tested without a phone: the timer engine, alarm
scheduling, countdown input, session selection, position maths, audio-band analysis, colour
derivation, artwork trimming, the two-finger tracker, playlist storage. New logic gets tests, and a
bug fix gets a regression test that **fails without the fix** (revert the fix once and confirm).

### On-device tests (`app/src/androidTest`)
About 34 tests per edition drive the real gesture engine and the whole timer flow through the real `MainScreen`, using
Compose's test framework. It dispatches touches straight into the app, so it works even on phones that
block `adb shell input` (e.g. MIUI). Run them by hand: Gradle's `connectedDebugAndroidTest` uninstalls
the app afterwards and wipes its data.

Run them for `full` and `standalone` (shown for `full`; `vibes` has no instrumented tests of its own —
see below):

```bash
./gradlew assembleFullDebug assembleFullDebugAndroidTest
adb install -r -t app/build/outputs/apk/full/debug/app-full-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk
adb shell am instrument -w dev.fitnesstimer.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall dev.fitnesstimer.test
```

`full` and `standalone` also each have their own tests (`src/androidTestFull`,
`src/androidTestStandalone`) that check the installed package: the standalone edition must never
declare a notification listener, SMS/accessibility binding, Record audio or Internet, because Google
Play Protect blocks sideloaded installs of apps that do (see `DECISIONS.md`). Adding any of those to
the standalone edition breaks its purpose. `vibes` isn't checked the same way because it isn't trying
to avoid that block (it already declares the notification listener, like `full`) — its whole point is
the fake wave, checked by hand (DECISIONS.md).

Two harness gotchas that cost time:
- A `performTouchInput { ... }` block delivers its events together when it ends, so a real pause
  between "finger down" and "move" has to happen **between** blocks.
- The gesture engine measures holds in real time but wakes on coroutine timeouts that run on
  Compose's virtual clock. Let both pass together in small steps (see `waitReal` in the tests), or
  hold-related tests will be flaky.

### Things a script can't check
Permission dialogs, the Settings toggle for notification access, the countdown notification's
sound and the launcher icon need hands and a real phone. Say plainly what you checked by hand, and on
which device and Android version.

### Performance
The screen is on for a whole workout, so frames and battery matter. If you touch anything that
animates, check frame times (`adb shell dumpsys gfxinfo dev.fitnesstimer`), keep animations off the
composition path (read state in the draw phase), and keep the paused screen fully idle (zero frames).

## Docs and changelog

- User-visible change: add a line to [`CHANGELOG.md`](CHANGELOG.md).
- Moved or added files: update [`STRUCTURE.md`](STRUCTURE.md).
- Settled a question or made a trade-off: record it in [`DECISIONS.md`](DECISIONS.md), with why.
- Changed how something works or what was verified: update the relevant section of
  [`PLAN.md`](PLAN.md), keeping "confirmed" and "assumed" separate.

## Pull requests

Keep them focused, and describe **what changed, why, how you tested it, and what you did not
verify**. Contributions are licensed under the project's [Apache-2.0](LICENSE) licence.

## Releases (maintainer checklist)

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`; update `CHANGELOG.md`.
2. Clean build with unit tests, lint and `assembleFullRelease assembleStandaloneRelease
   assembleVibesRelease`; run the on-device tests for `full` and `standalone`.
3. Check each release APK: `aapt2 dump permissions` (the standalone edition must have none of the
   blocked declarations; `vibes` must have no Record audio / Modify audio settings) and
   `apksigner verify --print-certs` (must show SetBeat's release key; the fingerprint is in the
   README).
4. Install a release APK on a real phone and smoke-test it (launch, no crash, developer test hooks are
   ignored). Note that changing the signing key needs an uninstall first.
5. Push, then publish a GitHub release from `main` with all three APKs (`setbeat-full-vX.Y.Z.apk`,
   `setbeat-standalone-vX.Y.Z.apk`, `setbeat-vibes-vX.Y.Z.apk`), their SHA-256 checksums and detailed
   notes, including a "Not verified" section.

**Signing key.** Releases are signed with a private key kept outside the repo
(`keystore.properties`, gitignored, points at the `.jks`). **Back both files up somewhere safe:** if
the key is lost, no update can ever install over existing copies. Never commit them. The same key can
serve as the upload key if the app is ever published on Google Play.

## Reporting a bug

Please include your phone model, Android version and ROM, which music app was playing, and what you
expected. A `logcat` excerpt filtered on `FitnessTimer` helps a lot. For anything security-sensitive,
use GitHub's private vulnerability reporting (the repo's Security tab) if it is available, and
otherwise open an issue without exploit details.
