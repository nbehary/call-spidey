# Handoff — Call Spidey (`rein_raus`)

**Date:** 2026-08-26 (refreshed against current state. Earlier revisions:
2026-08-22, itself moved here from `../breed/HANDOFF.md` where it had been
saved by mistake; the 2026-08-21 original described Gemma 3 1B, a 9-word
reply budget, and a visible "Test voices" button, all superseded)

**Project Root:** `/home/nate/projects/code/android/rein_raus/`
**Namespace / Application ID:** `dev.nate.spideycall`
**Reference Docs:** `docs/PLAN.md` (architecture, 8 core phases + §10 LLM
plan), `docs/STATE.md` (live state and changelog — the authoritative source;
this file is a snapshot, not a replacement for it), and
`docs/STATE-archive-2026-08-22.md` for the pre-2026-08-22 narrative history.

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
tested on physical hardware (Samsung Galaxy Note 10 and Samsung Galaxy S26
Ultra, the current target). Nothing plan-mandated remains open — everything
in `## 7` below is a human listen-and-judge step.

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
  0.9335), **not yet confirmed by ear** (see `## 7`). Fallbacks if it sounds
  off: `spidey_v3_blend.bin` (real-voice convex blend, held-out 0.8747),
  then `am_puck` @ pitch 1.26 (stays male).
* **Derivation tooling:** `tools/optimize_spidey_voice.py` (supersedes the
  older `tools/derive_spidey_voice.py`, which had two protocol bugs) —
  stages `selftest`/`baseline`/`optimize`/`analyze`/`pitch`. Candidates live
  in `app/src/main/assets/voices/candidates/`.
* **Reference audio:** `/home/nate/projects/ai/general/Spidey_voice.mp3`
  (F0 301.1 Hz, spectral centroid 2,708.6 Hz).
* Note `set_voice`/`set_speed` exist in the C++ but are NOT exposed through
  the JNI bridge — synthesis is always speed 1.0; pitch/speed are applied at
  playback time only, via `SpideyVoice.playbackParams()`.

---

## 4. Pronunciation (2026-08-24 → 2026-08-26)

Kokoro's phonemizer gives a word real phonemes **only** via a dictionary hit
in the downloaded `us_gold.json`/`us_silver.json` (or a `-s`/`-ed`/`-ing`
suffix-stripped hit). Everything else falls to `resolve_word`'s
`return lower;`, after which `tokenize()` reads each raw Latin *character* as
if it were already a misaki phoneme symbol. So a **text respelling cannot
choose phonemes** — it can only make the phonemizer look up a different word,
or fall into that garbage path.

* **`CustomPronunciationDict.kt` is the real lever.** It patches phoneme
  entries into the downloaded `us_gold.json` after model download (mirroring
  `SpideyVoice.installVoice`'s "patch a downloaded asset after fetch"
  pattern). Idempotent, never throws. Current overrides:
  `"spidey" -> "spˈIdi"` (built by analogy from real entries "spider"
  `spˈIdəɹ` and "tidy" `tˈIdi`) — **confirmed by ear 2026-08-24** — and
  `"name" -> "nˈeɪm"`.
* **`TtsPronunciation.kt` is now deliberately empty** and documented as a
  last resort, for something a dictionary entry can't express (an
  abbreviation, a mis-split token). A respelling left in it would *shadow* a
  dictionary entry for the same word.
* **The "name" root cause (2026-08-26):** misaki's uppercase `A` is a
  *single* token standing for the whole /eɪ/ diphthong, and Kokoro's duration
  predictor allots time per token — so the stock `nˈAm` gave the diphthong one
  phone's worth of time. `e` and `ɪ` are separately in Kokoro's vocab, so
  `nˈeɪm` is the identical sound spelled as two tokens. Measured on
  `spidey_v3`, word alone: `nˈAm` 0.71 s, the old `"name" -> "naym"`
  respelling 0.98 s, **`nˈeɪm` 1.18 s**. That also explains why "naym" ever
  helped — the OOV path tokenized it as /n/+/a/+/y/+/m/, two vowel tokens
  instead of one, i.e. the right effect by accident.
* **New tool: `tools/audition_pronunciation.py`** renders pronunciation
  candidates to WAV in seconds, no device build. It is a faithful port of
  `KokoroPhonemizer`'s English path reading the real device-pulled
  dictionaries plus `vocab_index.json`, then the public Kokoro ONNX export
  and the `PlaybackParams` pitch shift. Run it with
  `/home/nate/local-xtts/venv/bin/python` (has onnxruntime + librosa +
  soundfile); output lands in the gitignored `auditions/`. Pull the
  dictionaries first with
  `adb exec-out run-as dev.nate.spideycall cat files/models/us_gold.json`.
* **Standing lesson (logged in `TtsPronunciation.kt`'s class doc so it isn't
  relearned):** judge these by ear, not by whether a phoneme string *looks*
  linguistically correct. A 2026-08-24 revert of the "naym" respelling — on
  the reasoning that `nˈAm` was already "correct" — made it audibly worse and
  had to be restored; only the duration-predictor finding above explained why.

---

## 5. UI & In-App Testing

* **Voice Test screen** (`VoiceTestScreen.kt`) is deliberately hidden, not a
  visible button: a 5-second long-press on the "Call Spidey" header
  (testTag `call-header`) opens it. Reads `.bin` candidates live from
  `assets/voices/candidates/`, has Play/Use controls and live pitch/speed
  sliders, persists the pick to `SharedPreferences` without a rebuild.
* **Rotation:** `CallActivity` declares
  `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"`
  so the live call (mic pipeline, TTS, LLM `Conversation`, transcript — all
  plain Activity fields) survives a rotate. Process-death data loss is
  explicitly out of scope for this personal, short-lived-call app.

---

## 6. Verification Commands

* **Unit Tests (JVM):** `./gradlew :app:testDebugUnitTest`
  (59/59 passing as of 2026-08-26: Eliza, ChatState, TurnController,
  LlmResponder, HybridResponder, Normalizer, TtsPronunciation,
  CustomPronunciationDict.) Gradle caches this task — add
  `:app:cleanTestDebugUnitTest` first if you need the tests to actually run.
* **Build Verification:** `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
* **On-Device Instrumentation Tests:** `./gradlew :app:connectedDebugAndroidTest`
  (device: S26 Ultra, serial `R3GL30G4E4Y`, connect over USB — wireless adb
  drops when the screen sleeps.)
* **Emulator:** AVD `spidey_test` (android-36 google_apis x86_64) — boot with
  `ANDROID_HOME=/home/nate/Android/Sdk ~/Android/Sdk/emulator/emulator -avd spidey_test`
  (that SDK, not `/home/nate/my-android-sdk` from `local.properties`, has the
  emulator). No OpenCL there, so `LlmEngine` always falls back to CPU — by
  design since 2026-08-22.

---

## 7. Open / Next Steps

(See `docs/STATE.md` `## Next` for the live, authoritative list — summary:)
0. Judge "name" by ear on the installed build (the 2026-08-26 `nˈeɪm` fix).
   If it's still not right, `auditions/` already holds rendered
   alternatives — regenerate or extend them with
   `tools/audition_pronunciation.py` rather than guessing, and change only
   `CustomPronunciationDict.NAME_PHONEMES`.
1. Judge Gemma 4 E2B's reply *quality* across a real conversation — Phase D's
   own DONE-WHEN ("20 real spoken turns: in-character, kid-safe") has never
   been attempted for either LLM. One sample reply looked good; n=1 so far.
2. Confirm the `TurnController` blank-transcription fix holds across a full
   real conversation, not just the regression test — say a name, pause or
   mumble, keep talking, confirm the mic never goes silently deaf.
3. Judge `spidey_v3.bin` by ear against the reference; fall back to
   `spidey_v3_blend.bin` or `am_puck` @ pitch 1.26 if it sounds off.

Lower-priority leftovers also tracked in `docs/STATE.md` `## Open items`:
the never-built LLM download bootstrap, the unused
`gemma3-1b-it-int4.litertlm` still on-device, the §5.3 telephone-EQ effect,
and the deliberately-skipped `beamSize=4` / barge-in Phase 7 items.
