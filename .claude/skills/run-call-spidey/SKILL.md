---
name: run-call-spidey
description: Build, install, launch, and drive the Call Spidey Android app (dev.nate.spideycall) on a connected device/emulator via adb. Use when asked to run, build, install, launch, screenshot, or interact with Call Spidey / this app.
---

Call Spidey is a single-Activity Android (Compose) app that simulates a
voice phone call with "Spidey" (STT -> LLM/ELIZA -> TTS). It has no
resource-ids and no `chromium-cli`-style harness available - it's
driven over `adb`: `uiautomator dump` to read on-screen text, `input
tap` to press buttons. `.claude/skills/run-call-spidey/driver.sh`
wraps that into commands. All paths below are relative to the repo
root (`rein_raus/`).

## Prerequisites

- `adb` on `PATH`, and exactly one device/emulator visible:
  ```bash
  adb devices -l
  ```
  (A real device was used to write this skill - `SM_S948U` over USB.
  An emulator works the same way once it shows up in this list.)
- JDK 17 (the Gradle build's `sourceCompatibility`/`jvmTarget`).

## Build

```bash
./gradlew installDebug
```

Builds `app-debug.apk` and installs it on whatever device `adb` sees
(`applicationId = dev.nate.spideycall`). First run downloads the Gradle
8.13 distribution and takes ~2 minutes; incremental rebuilds are much
faster. Ends with `BUILD SUCCESSFUL` and `Installed on 1 device.`

## Run (agent path)

Use the driver for everything after install:

```bash
D=.claude/skills/run-call-spidey/driver.sh
$D grant-perms   # one-time per install, see Gotchas
$D launch        # starts the (only) activity, CallActivity
$D screen-text   # dumps visible text labels - cheap state check
$D call          # taps "Call Spidey" to start a call
$D wait-text "Listening" 15   # poll until connected & mic is live
$D screenshot /tmp/call.png   # visual check
$D hangup        # taps "Hang up"
$D stop          # am force-stop when done
```

| command | what it does |
|---|---|
| `build` | `./gradlew installDebug` |
| `grant-perms` | `pm grant` RECORD_AUDIO + POST_NOTIFICATIONS (skips the runtime permission dialog) |
| `launch` | `am start` the app's one activity |
| `stop` | `am force-stop` the app |
| `screenshot <path>` | PNG of current screen via `screencap` |
| `dump` | raw `uiautomator` XML (for debugging selectors) |
| `screen-text` | every visible text label, one per line - fast way to check app state without a screenshot |
| `tap-text "<text>"` | tap the element with that text (last match wins - see Gotchas) |
| `wait-text "<text>" [timeout-s]` | poll `screen-text` until it appears (default 30s) |
| `call` | `tap-text "Call Spidey"` |
| `hangup` | `tap-text "Hang up"` |
| `logs [n]` | last `n` (default 200) logcat lines for the app's current pid |

Call flow and the text you'll see in `screen-text` at each step:
`Call Spidey` (idle) -> tap `call` -> `Dialing…` -> `Ringing…` (both
brief and easy to miss if models are already warm - poll for
`Listening` instead of trying to catch them) -> `Listening` +
`STT ##ms · TTS ##ms · Total ##ms` (connected, mic live) -> tap
`hangup` -> `Call ended`.

## Run (human path)

```bash
adb shell am start -n dev.nate.spideycall/.CallActivity
```

Then use the phone/emulator screen directly. `Ctrl-C` doesn't apply -
just tap "Hang up" or press the device's home/back button.

## Gotchas

- **RECORD_AUDIO blocks the whole call.** `onCallTapped()` in
  `CallActivity.kt` requests `RECORD_AUDIO` (and `POST_NOTIFICATIONS`
  on API 33+) the first time you tap "Call Spidey," and a fresh
  install has neither granted. The system permission dialog isn't
  reachable via `uiautomator`/`input tap` reliably headless, so
  `driver.sh grant-perms` pre-grants both with `pm grant` before you
  ever tap the button. Skip it and `call` will just sit showing the
  OS permission dialog forever.
- **The launcher activity is `CallActivity`, not `MainActivity`.**
  There's only one `<activity>` in `AndroidManifest.xml` (namespace
  `dev.nate.spideycall`); `am start -n dev.nate.spideycall/.MainActivity`
  fails with "Activity class ... does not exist."
- **"Call Spidey" appears twice in the UI tree** - once as a static
  header (`Text`, not clickable) above the screen content, once as the
  button label (inside a clickable `View`) lower down. Both show up as
  `text="Call Spidey"` nodes in the `uiautomator` dump with no
  resource-id to disambiguate (Compose `Modifier.testTag` isn't
  exposed as `resource-id` unless the app opts into
  `testTagsAsResourceId`, which this one doesn't). `driver.sh`'s
  `find_center` takes the **last** matching node in document order,
  which is always the button since the header renders first - don't
  "fix" this to take the first match.
- **`Dialing…`/`Ringing…` are easy to miss by polling.** If STT/TTS
  models are already warm from a previous call this session, the
  ring's minimum duration (`MIN_RING_MS = 2000`) can already have
  elapsed by the time your first poll runs, so you'll jump straight to
  `Listening`. Wait for `Listening` (or `Hang up`, which appears at
  the same time) rather than the transient dialing/ringing text if you
  need a reliable "call is now connected" signal.
- **The chat transcript and typed-input toggle are hidden by default**
  (`AppSettings.chatVisible` is off in a fresh install) - `screen-text`
  during a connected call normally shows only the header, `Listening`,
  the latency HUD, and `Hang up`. Don't expect `message`/`input-field`
  text unless that setting's been turned on first.
- **A farewell keyword can end the call on its own**
  (`CallScreen`'s `LaunchedEffect` reacts to `chat.ended`), so
  `Call ended` can appear without you ever calling `hangup`.

## Troubleshooting

- **`am start` says `Activity class {dev.nate.spideycall/dev.nate.spideycall.MainActivity} does not exist`**:
  wrong component name - use `.CallActivity` (see Gotchas).
- **`tap-text` exits with "no element with text ... found"**: it
  prints the current `screen-text` on failure - check you're not
  targeting the header copy of "Call Spidey" text that doesn't exist
  standalone, or that the app is even in the foreground (`adb shell
  dumpsys activity activities | grep mResumedActivity`).
- **Call never leaves "Ringing…"/permission dialog is on screen**:
  you skipped `grant-perms` on a fresh install.
