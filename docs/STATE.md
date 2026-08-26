# STATE

Full pre-2026-08-22 narrative history (detailed bug diagnoses, exact quotes,
phase-by-phase play-by-play) lives in `docs/STATE-archive-2026-08-22.md` —
this file is the compact live-state template; consult the archive when a
one-line summary here isn't enough context to act.

## Goal
Android app "Call Spidey": simulate phoning Spidey — ELIZA-style conversation,
Spidey-like on-device TTS voice, opening line "Hi, I'm Spidey!". Personal use only.

## Now
All of docs/PLAN.md §7 (8 core phases) and §10 (LLM Phases A-D) are DONE. As
of 2026-08-22: LLM is Gemma 4 E2B (not Gemma3-1B-IT), default voice is
`spidey_v3` (not `am_puck`), `MAX_REPLY_WORDS` is 25 (not 9/15), every
in-character "Spiderman"/"Spider-Man" string is now "Spidey". A pre-existing
bug where the mic went permanently deaf after any blank/noise transcription
was found and fixed the same day (unrelated to the LLM/voice swaps).

Current build is installed on the S26 Ultra; user is live-testing it by
voice (dial -> greeting -> real spoken turns) as of this session's end — not
yet judged by ear across a full conversation.

`CallActivity` now survives rotation without losing the live call (2026-08-22
hardening); a `spidey_test` AVD exists for emulator-based dev/testing, and
the full LLM path (`HybridResponder` -> `LlmResponder` -> `LiteRT-LM`) is
confirmed working there after a real GPU/CPU-fallback bug found via that
testing was fixed — see `## Decisions`/`## Done` below.

Pronunciation pass (2026-08-24): "Spidey" fixed for real (dictionary patch,
confirmed by ear). "name" took a wrong turn mid-investigation — reverted a
pre-existing "naym" respelling to plain "name" on the reasoning that "name"
was already dictionary-correct (`nˈAm`), but the user confirmed by ear that
the dictionary-correct rendering sounds *worse* than "naym"'s phonemically-
wrong-but-better-sounding OOV rendering. Restored "naym". Lesson logged in
`TtsPronunciation.kt`: judge these by ear, not by whether the phoneme string
looks "correct". See `## Decisions`.

"name" root-caused and fixed properly (2026-08-26): the shortness was misaki's
single-token `A` shorthand for /eɪ/ — Kokoro's duration predictor allots time
per token, so `nˈAm` gives the diphthong one phone's worth. Dictionary entry
replaced with `nˈeɪm` (identical phonemes, spelled as two real IPA symbols);
the "naym" text respelling is retired, since a respelling would shadow the
dictionary entry. Awaiting the user's by-ear verdict — see `## Next`.

## Next
Nothing plan-mandated remains; everything below is a human listen/judge step,
deferred to the user's own testing (in progress as of 2026-08-22):
0. Judge "name" by ear on the installed build (2026-08-26 fix, `nˈeɪm`). If
   it's still not right, `auditions/` holds the alternatives already rendered
   — regenerate/extend with `tools/audition_pronunciation.py` rather than
   guessing, and change only `CustomPronunciationDict.NAME_PHONEMES`.
1. Judge Gemma 4 E2B's reply *quality* across a real conversation — Phase D's
   own DONE-WHEN ("20 real spoken turns: in-character, kid-safe") never
   attempted for either LLM. One sample reply looked good; n=1 so far.
2. Confirm the TurnController blank-transcription fix holds across a full
   real conversation, not just the regression test — say a name, pause/
   mumble, keep talking, confirm the mic never goes silently deaf.
3. Judge `spidey_v3_blend.bin` @ pitch 0.95 / speed 0.98 by ear — set as
   `DEFAULT_VOICE` 2026-08-26 (user's call), replacing `spidey_v3` @ 1.12/1.08.
   `spidey_v3` was a CMA-ES extrapolation outside the convex hull of real
   Kokoro voices and every top-scoring candidate skewed female at that pitch;
   the blend is the convex-only alternative (held-out 0.8747, but measured at
   pitch 1.12, so that number does not describe the new 0.95 playback).
   Fallbacks if it sounds off: back to `spidey_v3`, or `am_puck` @ pitch 1.26
   (stays male). NOTE when testing on a build that already ran: the in-app
   voice-test screen persists its picks to `SharedPreferences`, which override
   these compiled-in defaults — clear app data (or re-pick in that screen) or
   the new defaults will not be what you hear.

## Constraints
- "this is for my use only, it will not be distributed"
- Start the conversation with "Hi, I'm Spidey!" (was "Hi, I'm Spiderman" —
  superseded 2026-08-22 by the Spidey rename; the constraint's *intent*
  — start with a self-introducing greeting — is what's binding, not the
  literal old string)
- Use the project at ~/src/found_on_reddit/speech-android to parse responses
- Carry on the conversation using an Eliza-like system

## Decisions
- 2026-08-20: Published Maven artifact `audio.soniqo:speech:0.0.17`, not the
  local checkout or a composite build.
- 2026-08-20: `TtsPlaybackTest` logs `underrunCount` as a diagnostic, never
  asserts `==0` — matches control-demo's own reference posture (1 underrun
  on second/warm utterance is normal on this hardware across 3 independent
  implementations).
- 2026-08-21: LLM runtime is **LiteRT-LM**, not MediaPipe `tasks-genai`
  (the latter is `@Deprecated` — confirmed via its own source before
  committing to it).
- 2026-08-21 (user): Hybrid responder — LLM for natural replies, ElizaEngine
  kept as fallback/safety net (never retired); keep the existing
  `KOKORO_SHORT_TURN` TTS budget rather than switching TTS graphs.
- 2026-08-21/22: `MAX_REPLY_WORDS` 9 -> 15 -> 25 (each raise driven by a real
  cutoff landing mid-sentence on a live call); `MAX_OUTPUT_TOKEN` scaled
  alongside each time (32 -> 56 -> 96, ~3.7 tokens/word) since it gates
  decode *before* the word-truncation ever runs.
- 2026-08-21: Voice Test screen stays a hidden dev-only entry point (5s
  long-press on the header), never a visible shipped button.
- 2026-08-22: LLM swapped Gemma3-1B-IT -> Gemma 4 E2B, generic (not
  chip-specific) `.litertlm` file, same LiteRT-LM `Engine`/`Conversation`
  API — a same-shape swap, not a redesign.
- 2026-08-22: Voice default swapped `am_puck` -> `spidey_v3` (timbre-
  penalised CMA-ES result) — not yet confirmed by ear, see `## Next`.
- 2026-08-22: All in-character "Spiderman"/"Spider-Man" text renamed to
  "Spidey"; `Normalizer.kt` (interprets what a *kid* says, "spidey" already
  folds into its canonical bucket) and historical doc prose left alone —
  deliberately, not an oversight.
- 2026-08-22 (user, hardening): rotation handled via
  `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"`
  on `CallActivity`, not `onSaveInstanceState`/a ViewModel — the live call
  (mic pipeline, TTS, LLM `Conversation`) lives in plain Activity fields with
  no re-attachment logic, so the only viable fix is stopping the system from
  destroying/recreating the Activity at all; Compose already re-lays-out via
  `LocalConfiguration` with zero extra code. Process-death data loss stays
  explicitly out of scope (user: acceptable for a personal, short-lived-call
  app) — no `onSaveInstanceState` plumbing was added for that.
- 2026-08-22: `spidey_test` AVD (android-36 google_apis x86_64, pixel_6
  profile) built for dev/testing, matching the S26 Ultra's Android 16 rather
  than reusing another project's older-API AVD — see `## Facts`.
- 2026-08-22: `LlmEngine`'s GPU->CPU fallback restructured to wrap the whole
  build-and-warm-up attempt (`initialize()` + first real `sendMessage()`),
  not just `initialize()` — found via the emulator's GPU backend, which
  passes `initialize()` but throws "Can not find OpenCL library" on first
  inference, a failure shape the old code never caught. Real device (S26
  Ultra, has working OpenCL) is unaffected either way; this only matters on
  GPU-less/driver-less targets like the emulator.
- 2026-08-24: Pronunciation investigation, full trail (so it isn't
  re-litigated) — reported symptom: "Spidey" and "name" both come through
  with a soft/short vowel. Read the vendored `speech-android` checkout's
  `kokoro_phonemizer.cpp`/`.h` directly rather than guessing: a word only
  gets real phonemes via dictionary lookup (`us_gold.json`/`us_silver.json`,
  downloaded on-device, not bundled) or `-s`/`-ed`/`-ing` suffix-stripped
  lookup — anything else falls to `KokoroPhonemizer::resolve_word`'s
  `return lower;`, and `tokenize()` then looks up each raw Latin *character*
  of that as if it already were a misaki phoneme symbol. Pulled the real
  on-device dictionaries and checked directly (`python3 -c "import json..."`
  against the pulled `us_gold.json`/`us_silver.json`):
  - "name" was **already correctly dictionaried**: `nˈAm` (misaki's
    uppercase `A` = the /eɪ/ diphthong — genuinely long-a). The pre-existing
    `"name" -> "naym"` respelling is phonemically *wrong* (OOV, hits the
    broken raw-character path above, not the dictionary's `nˈAm`) — this
    looked like proof it was a pointless respelling, so it got reverted to
    plain "name" for the "correct" dictionary phonemes. **That was a
    mistake, caught by the user's ear, not by re-reading this reasoning**:
    plain "name" (`nˈAm`) sounds *worse* — shorter — than "naym"'s broken
    OOV rendering. Tried `"naim"` too (a real dictionary word that
    coincidentally also maps to `nˈAm`) — sounded identical to plain
    "name", confirming the *dictionary-correct* rendering is what's
    actually short, not something OOV-specific. Tried `am_puck` (a real
    stock voice, not the custom `spidey_v3`/`spidey_custom` embedding) —
    same shortness there too, ruling out a voice-embedding-specific cause.
    **Restored `"name" -> "naym"`** (2026-08-24) — whatever phonemes that
    OOV path actually produces apparently render with better vowel length
    on this specific voice than the textbook-correct diphthong does.
    **Lesson, written into `TtsPronunciation.kt`'s class doc so it isn't
    relearned**: judge a respelling purely by ear; "this phoneme string
    looks linguistically correct" is not evidence it sounds better, and
    isn't grounds to revert something the user already confirmed worked
    better, even partially ("still a little off" was never "broken").
  - "spidey" was **never dictionaried at all** (confirmed absent from both
    `us_gold.json`/`us_silver.json`), so it always hit the same broken OOV
    path regardless of respelling ("spidey" vs "spydey" — no perceptible
    difference, consistent with both being equally garbage). Fixed for
    real via new `CustomPronunciationDict.kt`: patches
    `"spidey": "spˈIdi"` directly into the downloaded `us_gold.json` after
    model download (mirrors `SpideyVoice.installVoice`'s "patch a
    downloaded model asset after fetch" pattern) — the phoneme string built
    by direct analogy from two already-correct real entries: "spider" ->
    `spˈIdəɹ` (same `I` = /aɪ/) and "tidy" -> `tˈIdi` (same "-dy" -> "di"
    ending). **Confirmed fixed by ear, 2026-08-24.**

- 2026-08-26 (user): voice defaults changed to `spidey_v3_blend` @ pitch 0.95
  / speed 0.98, reversing direction from `spidey_v3` @ 1.12/1.08 (which raised
  both above neutral). Chosen by the user, to be judged by ear — consistent
  with the standing lesson that the WavLM similarity score is not the arbiter
  here. Measurement context for whoever revisits this: the reference-audio
  centroid the optimiser targets is already converged (57.7 s reference, 13
  windows; two disjoint halves agree at cos 0.9876, and 20 s alone reproduces
  the full centroid at 0.9931), so a longer or gap-free reference would move
  the target by ~0.001-0.01 while `spidey_v3`'s own held-out gap to 1.0 is
  0.066. The binding limits are the objective/perception mismatch and the
  256-float row-128 style vector itself, not the reference recording.

## Facts
- Devices: Note 10 (SM-N975U1, serial RF8M81B29PZ, Android 12/API 31) —
  original 7-phase target, unused since 2026-08-21. S26 Ultra (SM-S948U,
  serial R3GL30G4E4Y, Android 16, SoC SM8850/Adreno, 12 GB RAM) — the
  current target for everything. Connect via USB (`adb devices -l` should
  show serial `R3GL30G4E4Y` directly, not an `adb-...-tls-connect` wireless
  entry) — wireless drops when the screen sleeps; USB has held reliably.
  `/data` free space as of 2026-08-22: ~18.5 GB of 231 GB — re-verify with
  `adb shell df /data` before trusting any older number in this file.
- `speech-android` SDK checkout path has a hyphen, not `speech_android`.
  Local Android SDK: `/home/nate/my-android-sdk`; NDKs under
  `~/Android/Sdk/ndk`.
- Kokoro voice is hardcoded to `af_heart` for "en" at the native layer
  (`speech-core/src/models/kokoro/kokoro_tts.cpp:196`); swapped by
  overwriting `voices/af_heart.bin` on-device (`SpideyVoice.installVoice`).
  `ModelManager.isValidModel` rejects anything not exactly 1024 bytes.
  `set_voice`/`set_speed` exist in C++ but are NOT exposed through
  `sdk/src/main/cpp/jni_bridge.cpp` — synthesis is always speed 1.0;
  pitch/speed at playback time only, via `SpideyVoice.playbackParams()`.
- `SpideyVoice.kt`: `DEFAULT_VOICE = "spidey_v3_blend"`, `DEFAULT_PITCH = 0.95f`,
  `DEFAULT_SPEED = 0.98f` (2026-08-26; was `spidey_v3` @ 1.12/1.08 — note the
  candidates' recorded held-out scores were all measured at pitch 1.12, so
  they no longer describe what actually gets played). `tools/` still hardcodes
  the old 1.12/1.08 as its playback model — see `## Open items`.
  `LlmResponder.kt`: `MAX_REPLY_WORDS = 25`,
  `MAX_OUTPUT_TOKEN = 96`, `SYSTEM_PROMPT` = Spidey persona + brevity +
  kid-safety instructions.
- LLM model file: `/data/local/tmp/llm/gemma-4-E2B-it.litertlm`
  (2,588,147,712 bytes, generic auto-backend-selecting build — magic header
  `LITERTLM`). `gemma3-1b-it-int4.litertlm` (584 MB) still present alongside
  it on-device, unused, no urgency to delete (18.5 GB free). Both paths are
  dev-only stand-ins (`adb push` by hand) — Phase B's real download
  bootstrap for the LLM was never built. LiteRT-LM dependency:
  `com.google.ai.edge.litertlm:litertlm-android:0.16.1`.
- `tools/optimize_spidey_voice.py` (supersedes `tools/derive_spidey_voice.py`,
  which had two protocol bugs — see archive) — stages: `selftest` /
  `baseline` / `optimize` / `analyze` / `pitch`. Candidate voices live in
  `app/src/main/assets/voices/candidates/`.
- Emulator: AVD `spidey_test` (`~/.android/avd/spidey_test.avd`, android-36
  google_apis x86_64, pixel_6 profile, 4G RAM, 8G data partition) — boot with
  `ANDROID_HOME=/home/nate/Android/Sdk ANDROID_SDK_ROOT=/home/nate/Android/Sdk ~/Android/Sdk/emulator/emulator -avd spidey_test`
  (that SDK, not `/home/nate/my-android-sdk` from `local.properties`, is the
  one with `emulator`/`system-images` on this machine). GPU backend has no
  OpenCL there, so `LlmEngine` always falls back to CPU (~13s cold engine
  build+warm-up observed) — by design now, see `## Decisions`. LLM model
  pushed to it at the same `MODEL_PATH` as the real device
  (`/data/local/tmp/llm/gemma-4-E2B-it.litertlm`, source copy at
  `/tmp/claude-1000/-home-nate-projects-code-android-breed/.../scratchpad/gemma-4-E2B-it.litertlm`
  as of 2026-08-22, not committed anywhere permanent). `~/.zshrc`'s
  `ANDROID_HOME` auto-detect had a real precedence bug (unguarded
  `[[ -d ... ]] &&` chain let the last matching path silently win over the
  first) — fixed 2026-08-22, unrelated repo but worth knowing before trusting
  a bare `$ANDROID_HOME` in a fresh shell on this machine.

## Done
- Phases 0-7 (scaffold, ELIZA core, text loop, voice/STT, TTS, Spidey voice
  mechanism, Call UX, Polish) — 2026-08-20. RESULT: full dial -> ring ->
  connect -> talk -> hang-up flow on the Note 10, JVM + instrumentation
  suites green throughout. Real SIGSEGV crash found + fixed (native
  use-after-free when `stop()` closed the pipeline mid-`speak()`) — see
  `## Failed attempts`.
- `ModelDownloadWorker` fix — 2026-08-20. RESULT: model download now runs as
  a foreground service and survives backgrounding/process death (was dying
  to Doze throttling).
- Voice audition tooling + hidden Voice Test screen, scripted name-capture
  intro, ring-tone audibility fix — 2026-08-21. RESULT: 10 candidate voices
  auditioned, `am_puck` picked by ear (later superseded 2026-08-22); ring
  confirmed audible by ear.
- LLM Phases A-D (LiteRT-LM integration, warm-engine singleton, `SpideyResponder`/
  `HybridResponder` abstraction, safety harness, GPU backend) — 2026-08-21.
  RESULT: Gemma-backed replies live with Eliza fallback; GPU decode ~30
  tok/s once the `cacheDir`-permission bug was found and fixed (SELinux
  blocks app-process writes to `/data/local/tmp`, reads were always fine —
  see `## Facts`-adjacent archive entry for the full root-cause trace).
- Voice re-derivation via `tools/optimize_spidey_voice.py` — 2026-08-21/22.
  RESULT: `spidey_v3.bin` (held-out similarity 0.9335, clean spectrum vs a
  noisier 0.9368 first attempt) set as `DEFAULT_VOICE`.
- Bug fix: mic goes deaf after a blank transcription — 2026-08-22. RESULT:
  `TurnController` now calls `pipeline.resumeListening()` on the blank-
  transcript path; regression test proven to catch the bug (failed pre-fix,
  passed post-fix).
- LLM swap Gemma3-1B-IT -> Gemma 4 E2B — 2026-08-22. RESULT: engine warm-up
  faster (3.1s vs ~5.5s+ despite a 4.4x larger model); decode throughput a
  wash at this app's short reply length (~30 tok/s either way, not the 52
  tok/s Google's longer-decode benchmark implied); reply quality unjudged.
- Spidey rename (Spiderman/Spider-Man -> Spidey everywhere in-character) —
  2026-08-22. RESULT: all live content + coupled tests updated, 53/53 JVM
  green.
- `MAX_REPLY_WORDS` 15 -> 25, `MAX_OUTPUT_TOKEN` 56 -> 96 — 2026-08-22.
  RESULT: 53/53 JVM green; not yet installed/heard at the time of that change
  (installed and smoke-tested in a later step the same session).
- Rotation hardening — 2026-08-22. RESULT: `android:configChanges` added to
  `CallActivity`; new `CallFlowTest.rotatingDuringACallKeepsTheSameActivityAndTranscript`
  flips `requestedOrientation` mid-call and asserts the same Activity
  instance + unchanged transcript. Verified on the S26 Ultra: 4/4
  `CallFlowTest` passed, no FATAL/AndroidRuntime in logcat.
- Stray `HANDOFF.md` recovered — 2026-08-22. A 2026-08-21 handoff doc for
  *this* project had been saved into the unrelated `../breed` repo by
  mistake. Moved to `docs/HANDOFF.md` here and refreshed against current
  state (it still described Gemma 3 1B, a 9-word budget, and a visible
  "Test voices" button, all superseded); deleted from `../breed`.
- `spidey_test` AVD created + LLM model pushed + full LLM path verified —
  2026-08-22. RESULT: found and fixed a real `LlmEngine` GPU/CPU-fallback
  bug (see `## Decisions`); `LlmResponderDeviceTest` then passed on the
  emulator (real generated reply, within word budget). Also drove the actual
  app UI end-to-end (dial -> connect -> two typed turns -> hang up); the
  second turn's reply ("Saving the day is the best part! Helping friends and
  making the neighborhood smile is super fun.") isn't any canned
  `spidey_eliza.json` line, confirming the live `HybridResponder` ->
  `LlmResponder` path, not the Eliza fallback, produced it. Clean hang-up,
  no crashes.
- "Spidey" pronunciation fixed — 2026-08-24. RESULT: new
  `CustomPronunciationDict.kt` patches a correct `"spidey"` dictionary
  entry into the downloaded `us_gold.json`; **confirmed fixed by ear** on
  the Note 10. Full trail in `## Decisions`. Verified:
  `./gradlew :app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest` -> BUILD SUCCESSFUL; installed on the
  Note 10, dialed a real call, confirmed the patched dictionary on-device
  via `adb shell run-as ... cat us_gold.json`, no crashes.
- "name" pronunciation — investigated, wrong turn caught and corrected,
  same day — 2026-08-24. RESULT: `TtsPronunciation.kt`'s pre-existing
  `"name" -> "naym"` respelling was reverted (reasoning: "name" is already
  dictionary-correct, so "naym" looked pointless) then **restored** after
  the user confirmed by ear that the "correct" dictionary rendering
  actually sounds worse than "naym"'s phonemically-wrong one. Ruled out
  along the way: playback pitch/speed (neutral made no difference),
  voice-embedding-specific cause (`am_puck`, a real stock voice, sounds
  equally short). Full trail in `## Decisions`. Verified:
  `./gradlew :app:testDebugUnitTest :app:assembleDebug` -> BUILD
  SUCCESSFUL; installed on the S26 Ultra — not yet re-confirmed by ear
  after the restore (do that before considering this fully closed).
- "name" pronunciation root-caused and fixed at the dictionary — 2026-08-26.
  CAUSE (not previously understood): the shortness is misaki's *single-token*
  uppercase `A` shorthand for the /eɪ/ diphthong. Kokoro's duration predictor
  allots time per token, so `nˈAm` gives the whole diphthong one phone's
  worth of time. `e` and `ɪ` are separately in Kokoro's vocab
  (`vocab_index.json`, 176 symbols — checked, and nothing in any candidate
  was silently dropped by `tokenize()`), so `nˈeɪm` is phonemically the same
  sound spelled as two tokens. Measured on `spidey_v3` with the new
  `tools/audition_pronunciation.py`, word alone: `nˈAm` 0.71 s, "naym" (the
  old OOV respelling) 0.98 s, **`nˈeɪm` 1.18 s**, `nˈeɪːm` 1.25 s,
  `nˈAɪm` 1.29 s. That also explains why "naym" ever helped — the OOV path
  tokenizes it as /n/+/a/+/y/+/m/, two vowel tokens where the dictionary had
  one, i.e. the right effect by accident.
  FIX: `CustomPronunciationDict` now also overrides `"name" -> "nˈeɪm"`, and
  `TtsPronunciation.RESPELLINGS` is empty — a respelling makes the phonemizer
  look up a *different word*, so leaving "naym" in would have shadowed the new
  entry. `TtsPronunciation` kept (with an `internal respell()` the tests drive)
  as the place for fixes a dictionary entry can't express.
  NEW TOOL: `tools/audition_pronunciation.py` renders candidates to WAV in
  seconds — a faithful port of `KokoroPhonemizer`'s English path reading the
  real device-pulled `us_gold.json`/`us_silver.json`/`vocab_index.json`, then
  the public Kokoro ONNX export plus the `PlaybackParams` pitch shift. Run it
  with `/home/nate/local-xtts/venv/bin/python` (has onnxruntime + librosa +
  soundfile); output lands in the gitignored `auditions/`.
  Verified: `./gradlew :app:testDebugUnitTest` -> BUILD SUCCESSFUL, 59/59, 0
  failures. Verified: `./gradlew :app:assembleDebug :app:installDebug` ->
  BUILD SUCCESSFUL on SM-S948U; dialed a real call and re-pulled the
  dictionary — `name -> nˈeɪm`, `spidey -> spˈIdi`, 90202 entries.
  NOT yet judged by ear — auditions handed to the user 2026-08-26.

## Open items
- Old Phase 3 "live speak-5-lines" check, never actually done (low priority
  now — superseded in spirit by the `## Next` items above, which cover the
  same ground plus the LLM/voice work since).
- S26 wireless-debugging re-pair, to confirm `ModelDownloadWorker` survives
  backgrounding on wireless too (Note 10 over USB already confirms the
  mechanism itself) — low priority, USB works fine.
- Phase 7 skips, deliberately not attempted, no new evidence to revisit
  either: `beamSize=4` + `setContextPhrases` (plan's own risk table already
  decided against it — control-demo found beam search under-emits
  on-device); barge-in (experimental, needs live acoustic-echo-cancellation
  judgment, would also require reworking the tested `micPaused` mic-mute
  design).
- §5.3 telephone EQ band-pass effect — never built, deferred per the plan's
  own "ship as toggle, default off until judged" framing.
- `gemma3-1b-it-int4.litertlm` still on-device, unused — fine to delete once
  Gemma 4 E2B is confirmed good by ear (no urgency, 18.5 GB free).
- `tools/` still models playback at the OLD 1.12/1.08:
  `optimize_spidey_voice.py:55-56` (`PLAYBACK_PITCH`/`PLAYBACK_SPEED`, whose
  own comment says "keep in sync with the app") and
  `audition_pronunciation.py:49-50` (`PLAYBACK_PITCH`, `DEFAULT_VOICE`).
  Deliberately NOT updated alongside the 2026-08-26 default change: every
  score recorded in this file and the archive was measured at 1.12, so
  flipping the constants would silently invalidate them. Update them together
  with a re-run of `baseline`/`optimize` if the new defaults stick by ear.

## Failed attempts
- ATTEMPT 1 [L1] (Phase 6 SIGSEGV crash): `eventsJob.cancelAndJoin()` with a
  timeout -> still crashed; `eventsJob` reported `Cancelled` ~80ms before the
  blocking native call had actually returned (no suspension point inside a
  JNI call for cancellation to land on).
- ATTEMPT 2 [L1]: also dropped `pipeline.cancelSynthesis()` in case that
  itself triggered an unsafe native abort -> crash persisted identically,
  ruled out.
- ATTEMPT 3 [L2, fix]: `speakMutex` held for `speak()`'s whole body; `stop()`
  waits to acquire it before touching the pipeline -> fixed (bounded by
  `STOP_JOIN_TIMEOUT_MS = 20_000L`).
- ATTEMPT 1 [L1] (2026-08-22 Gemma 4 download): `hf_hub_download`'s Xet
  transfer -> stalled permanently at 136 MB/2.59 GB (confirmed via
  `/proc/<pid>/io`, not just a slow progress meter).
- ATTEMPT 2 [L1]: plain `curl` over HTTP/2 against the resolved CDN URL ->
  failed at 32%, `curl: (92) HTTP/2 stream reset`.
- ATTEMPT 3 [L2]: `curl -C -` resume over HTTP/1.1 -> failed again at 78%,
  `curl: (18) ... bytes missing` — both failures ~5-9 min in, pointing at a
  per-connection time cap on that CDN edge, not a bandwidth problem.
- ATTEMPT 4 [L2, fix]: bash loop re-issuing `curl -C -` until local size
  matched the expected `Content-Length` exactly -> succeeded in 2 iterations.
  **Takeaway for next time: skip `hf_hub_download`/Xet for a large file on
  this network, go straight to the curl resume-loop.**
- (2026-08-22, TurnController regression test) First version of
  `assertEquals(2, resumeListeningCalls)` accidentally encoded the *buggy*
  call count and passed against unfixed code, proving nothing — caught by
  hand-tracing the call sequence before trusting it; corrected to 3.
