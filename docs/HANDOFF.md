# Handoff — Call Spidey (`rein_raus`)

**Date:** 2026-08-22 (moved here from `../breed/HANDOFF.md`, where it had been
saved by mistake, and refreshed against current state — the 2026-08-21
original described Gemma 3 1B, a 9-word reply budget, and a visible
"Test voices" button, all superseded since)

**Project Root:** `/home/nate/projects/code/android/rein_raus/`
**Namespace / Application ID:** `dev.nate.spideycall`
**Reference Docs:** `docs/PLAN.md` (architecture, 8 core phases + §10 LLM
plan), `docs/STATE.md` (live state and changelog — the authoritative source;
this file is a snapshot, not a replacement for it)

---

## 1. What This Project Is

A private, offline Android app that simulates an interactive voice phone
call to Spidey for a child.
* **Flow:** Tap **Call Spidey** → phone rings → Spidey answers with *"Hi,
  I'm Spidey! What is your name?"* → child speaks → on-device STT captures
  input → dialogue engine generates an in-character response → on-device TTS
  synthesizes Spidey's speech with low latency.
* **Constraints:** Personal use only (not for distribution). Fully on-device
  (no network required after first model download). High speech
  responsiveness.

---

## 2. Current State & Architecture

All core phases (0–7) and LLM enhancement phases (A–D) are complete and
tested on physical hardware (Samsung Galaxy Note 10 — unused since
2026-08-21 — and Samsung Galaxy S26 Ultra, the current target).

### Core Stack
* **Speech Stack:** `audio.soniqo:speech:0.0.17` (Kokoro-82M TTS + STT
  running on-device via ONNX / LiteRT).
* **Dialogue Engine:** `HybridResponder`
  (`app/src/main/kotlin/dev/nate/spideycall/HybridResponder.kt` — top-level
  package, not a `data/` subpackage):
  * **Deterministic Fast Path / Safety Net:** Farewell/off-limits detection
    on the user's own input runs first via `ElizaEngine` +
    `assets/spidey_eliza.json`, before the LLM is ever touched.
  * **Generative Engine:** `LlmResponder.kt` queries **Gemma 4 E2B** via
    LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android:0.16.1`),
    replies hard-truncated to `MAX_REPLY_WORDS = 25`
    (`MAX_OUTPUT_TOKEN = 96`) — raised twice from an original 9-word budget
    after real cutoffs landed mid-sentence on live calls.
  * **Fallback:** Falls back to `ElizaResponder` if LLM inference fails,
    times out, or produces blank/off-limits output
    (`UnsafeLlmOutputException`).
* **Audio & Turn Management:**
  * `VoiceSession.kt`: manages the speech pipeline, mic streaming, and
    playback.
  * `TurnController.kt`: state machine coordinating transcription
    completion → responder turn → TTS playback. (Bug fixed 2026-08-22: mic
    used to go permanently deaf after a blank transcript — now calls
    `pipeline.resumeListening()` on that path.)
* **LLM model file (dev-only, hand-pushed):**
  `/data/local/tmp/llm/gemma-4-E2B-it.litertlm` (~2.59 GB). Phase B's real
  download-bootstrap for the LLM (mirroring `ModelDownloadWorker` for
  STT/TTS) was never built — still `adb push` by hand.

---

## 3. Voice Architecture & Custom Spidey Voice Clones

Kokoro-82M on Android hardcodes English voice playback to `af_heart.bin`
(1,024 bytes = 256 `float32` values, row 128 of Kokoro's style tensor).
`SpideyVoice.kt` (`app/src/main/kotlin/dev/nate/spideycall/audio/`) swaps
that file on-device.

* **Current default:** `DEFAULT_VOICE = "spidey_v3"`, `DEFAULT_PITCH = 1.12f`,
  `DEFAULT_SPEED = 1.08f` — a CMA-ES-derived candidate (held-out similarity
  0.9335), **not yet confirmed by ear** as of 2026-08-22 (see `docs/STATE.md`
  `## Next`). Fallbacks if it sounds off: `spidey_v3_blend.bin` (real-voice
  convex blend, held-out 0.8747), then `am_puck` @ pitch 1.26 (stays male).
* **Derivation tooling:** `tools/optimize_spidey_voice.py` (supersedes the
  older `tools/derive_spidey_voice.py`, which had two protocol bugs) —
  stages `selftest`/`baseline`/`optimize`/`analyze`/`pitch`. Candidates live
  in `app/src/main/assets/voices/candidates/`.
* **Reference audio:** `/home/nate/projects/ai/general/Spidey_voice.mp3`
  (F0 301.1 Hz, spectral centroid 2,708.6 Hz).

---

## 4. UI & In-App Testing

* **Voice Test screen** (`VoiceTestScreen.kt`) is deliberately hidden, not a
  visible button: a 5-second long-press on the "Call Spidey" header
  (testTag `call-header`) opens it. Reads `.bin` candidates live from
  `assets/voices/candidates/`, has Play/Use controls and live pitch/speed
  sliders, persists the pick to `SharedPreferences` without a rebuild.

---

## 5. Verification Commands

* **Unit Tests (JVM):** `./gradlew :app:testDebugUnitTest`
  (53/53 passing as of 2026-08-22: Eliza, ChatState, TurnController,
  LlmResponder, HybridResponder, Normalizer.)
* **Build Verification:** `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
* **On-Device Instrumentation Tests:** `./gradlew :app:connectedDebugAndroidTest`
  (device: S26 Ultra, serial `R3GL30G4E4Y`, connect over USB — wireless adb
  drops when the screen sleeps.)

---

## 6. Recent Hardening (2026-08-22, this session)

`CallActivity` held the live call (mic pipeline, TTS, LLM conversation,
transcript) in plain Activity instance fields with no `configChanges`
declared — a rotation destroyed and recreated the Activity mid-call,
tearing down `voiceSession`/`currentResponder` in `onDestroy()` and
resetting call phase/transcript to blank in `onCreate()`. Fixed by adding
`android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"`
to `CallActivity` in `AndroidManifest.xml` — Compose already re-lays-out via
`LocalConfiguration` with no extra code, so the Activity instance, and
everything it holds, now survives rotation. Backgrounding (Home/app-switch)
was already fine since the Activity instance isn't destroyed on a plain
pause/stop. Process-death data loss remains accepted/out of scope (personal
short-lived-call app; not worth `onSaveInstanceState`/ViewModel plumbing for
this use case).

New regression test: `rotatingDuringACallKeepsTheSameActivityAndTranscript`
in `CallFlowTest.kt` — dials a real call, flips `requestedOrientation`
landscape→portrait, asserts the same Activity instance and an unchanged
transcript. Verified 2026-08-22: `./gradlew :app:connectedDebugAndroidTest`
→ 4/4 `CallFlowTest` passed on the S26 Ultra, no FATAL/AndroidRuntime
exceptions in logcat.

---

## 7. Open / Next Steps

(See `docs/STATE.md` `## Next` for the live, authoritative list — summary:)
1. Judge Gemma 4 E2B's reply *quality* across a real conversation (n=1 so
   far, looked good).
2. Confirm the `TurnController` blank-transcription fix holds across a full
   real conversation, not just the regression test.
3. Judge `spidey_v3.bin` by ear against the reference; fall back to
   `spidey_v3_blend.bin` or `am_puck` @ pitch 1.26 if it sounds off.
