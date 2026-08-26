# STATE

## Goal
Android app "Call Spidey": simulate phoning Spidey — ELIZA-style conversation,
Spidey-like on-device TTS voice, opening line "Hi, I'm Spidey!". Personal use only.

## Now
All of docs/PLAN.md §7 (8 core phases) and §10 (LLM-backed responses, Phases
A-D) are DONE. As of 2026-08-22 the LLM is Gemma 4 E2B (not Gemma3-1B-IT —
see "LLM swap" entry below), the default voice is the derived `spidey_v3`
(not `am_puck` — see "spidey_v3.bin" entry), `MAX_REPLY_WORDS` is 25 (not
9/15), and every in-character "Spiderman"/"Spider-Man" string was renamed to
"Spidey" (see "Spidey rename" entry). A pre-existing bug where the mic went
permanently silent after any blank/noise transcription was found and fixed
(see "Bug fixed: mic goes deaf" entry) — unrelated to the LLM/voice swaps,
just surfaced the same day.

Current build is installed on the S26 Ultra and the user is live-testing it
by voice (dial -> greeting -> real spoken turns) as of this session's end —
not yet judged by ear across a full conversation.

Connected test devices: Note 10 (SM-N975U1, serial RF8M81B29PZ, Android 12 /
API 31) — original 7-phase target, not used this session; S26 Ultra
(SM-S948U, serial R3GL30G4E4Y, Android 16, SoC SM8850/Adreno, 12 GB RAM) —
the actual target for everything in this "Now" section. Connect via USB
(`adb devices -l` should show serial `R3GL30G4E4Y`, not a
`adb-...-tls-connect` wireless entry) — wireless dropped mid-session when
the screen slept; USB held reliably throughout. `/data` free space as of
2026-08-22: ~18.5 GB of 231 GB (the earlier "~1.6-2.2 GB free" note was
stale/wrong by the time this session checked — re-verify with `adb shell df
/data` rather than trusting either number going forward).

Phase 0 (scaffold) DONE — 2026-08-20.
Verified: `./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL.
Verified: installed + launched on the Note 10, `dumpsys activity activities`
shows `dev.nate.spideycall/.CallActivity` as mResumedActivity.

Phase 1 (ELIZA core) DONE — 2026-08-20.
Verified: `./gradlew :app:testDebugUnitTest` -> BUILD SUCCESSFUL, 23/23 passed
(ElizaEngineTest 10, ElizaScriptTest 6, NormalizerTest 7).
Files: eliza/{Normalizer,ElizaScript,ElizaEngine}.kt, assets/spidey_eliza.json
(41 keyword entries incl. $none), added kotlinx-serialization-json 1.11.0.

Phase 2 (text loop) DONE — 2026-08-20.
Verified: `./gradlew :app:testDebugUnitTest` -> 28/28 JVM tests pass (added
ChatStateTest 5).
Verified: `./gradlew :app:connectedDebugAndroidTest` -> 3/3 pass on the Note 10
(ChatScreenTest: greeting shown, typed line -> Spidey reply round trip,
farewell disables input) — full asset->engine->UI wiring on real hardware.
Files: ChatState.kt, CallActivity.kt rewritten as the chat screen,
androidTest/ChatScreenTest.kt.
Gotcha for later Compose files: `Modifier.weight()` is a MEMBER of
RowScope/ColumnScope, not a top-level extension — do not import it by name
(`import androidx.compose.foundation.layout.weight`); that import path
resolves to an unrelated *internal* top-level property of the same name in
that package and fails with "it is internal in file". control-demo's
ControlScreen.kt never imports it either — same reason.

Phase 3 (models + STT) IN PROGRESS — 2026-08-20.
Implemented: RECORD_AUDIO+INTERNET permissions, VoiceSession.kt (ensureModels
+ SpeechPipeline in TRANSCRIBE_ONLY + AudioRecord mic loop, mirrors
control-demo's AudioRecord pattern), TurnController.kt (routes
SpeechEvent.TranscriptionCompleted through the same ChatState.send() the
typed path uses), "🎤 Voice" button + status row in CallActivity.
Verified: `./gradlew :app:testDebugUnitTest` -> 32/32 JVM tests pass
(added TurnControllerTest 4).
Verified: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.nate.spideycall.ChatScreenTest`
-> 3/3 pass, confirming the Phase 2 typed flow has no regression.
Added androidx.test:rules:1.6.1 for GrantPermissionRule.
Running (background, may take a while — first-run ~500MB model download):
`connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.nate.spideycall.VoiceOptInTest`
— pre-grants RECORD_AUDIO, taps Enable voice, waits up to 15 min for
ensureModels()+SpeechPipeline+AudioRecord to reach "Listening" with no crash.

Verified: `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.nate.spideycall.VoiceOptInTest`
-> BUILD SUCCESSFUL, 1/1 passed, 4m25s (real ~500MB model download +
SpeechPipeline JNI/ONNX Runtime load + AudioRecord mic loop all reached
"Listening" with no crash on the Note 10).

STILL OPEN before Phase 3's plan DONE-WHEN is fully met:
The plan's DONE-WHEN is "speak 5 lines; each transcript appears and gets an
in-character reply" — that needs a live human voice into the Note 10's mic,
which the agent cannot produce itself. Automated coverage above proves the
pipeline mechanism works; it does not prove real speech transcribes
sensibly. Flagged to the user 2026-08-20; awaiting their call on whether to
do that check live now or proceed to Phase 4 on the strength of the
automated coverage + control-demo's proven pattern reuse.

User decision 2026-08-20: defer the live-voice speak-5-lines check; keep
building. Proceeded to Phase 4.

Phase 4 (TTS) DONE — 2026-08-20.
Implemented: audio/StreamingPcmPlayer.kt (copied from control-demo, made
public), ChatState.send() now returns the reply text, TurnController.onEvent
is now suspend and calls a `speak: suspend (String) -> Unit` after chat.send()
before resuming listening, VoiceSession.speak() synthesizes+plays via a
producer/consumer Channel<SpeechSynthesisResult> (generation and playback on
separate coroutines) exactly matching control-demo's turn loop
(ControlAgentActivity.kt:627-650) — a naive synchronous generate-then-write
loop measurably underran on this device. Mic is muted (micPaused) during
playback so Spidey doesn't transcribe himself.
Verified: `./gradlew :app:testDebugUnitTest` -> 32/32 JVM tests pass
(TurnControllerTest rewritten for the async speak, same 4 tests).
Verified (am instrument, app kept installed manually — connectedDebugAndroidTest
uninstalls the app afterward, which wipes the model cache): ChatScreenTest
OK (3), TtsPlaybackTest OK (1) — real synthesizeStreaming + StreamingPcmPlayer
playback via the exact production generator/player pattern.

Gotcha for later coroutine code: `launch { }` inside `withContext(Dispatchers.IO)`
does NOT guarantee the launched child runs concurrently with its
continuation the way `launch(Dispatchers.Default) { }` (control-demo's
explicit choice) does — this mattered here because the whole point of the
producer/consumer split is for TTS generation and AudioTrack playback to
overlap. Pin the dispatcher explicitly on the launch call, don't rely on the
ambient context.

Finding surfaced to user and resolved 2026-08-20: TtsPlaybackTest initially
asserted `underrunCount == 0`. Three independent implementations (one-shot,
synchronous streaming, concurrent producer/consumer) all showed exactly 1
underrun on the Note 10's second (warm) utterance. control-demo's own
reference code only logs underrunCount as a diagnostic
(ControlAgentActivity.kt:608,698,720) and never asserts on it. User chose:
match that posture — assert frames written/playback completed, log
underrunCount instead of failing on it.

Phase 5 (Spidey voice) mechanism DONE — 2026-08-20; final voice pick is a
listen-and-decide step, deferred like the Phase 3 live check (see below).

tools/make_voice.py recovers the upstream Kokoro row index empirically
rather than assuming it: pulled speech-android's shipped af_heart.bin,
hf_alpha.bin, and ff_siwis.bin off the device, diffed each against every row
of hexgrad/Kokoro-82M's upstream (510,1,256) *.pt tensor (read as a plain
zip — PyTorch's legacy tensor storage is raw contiguous float32, no torch
import needed), and all three matched **row 128 exactly (max abs diff ==
0.0)**, next-closest row off by ~0.002 — confirms one fixed row across the
whole voice pack, not a fluke. `--verify` mode re-runs that check for anyone
re-deriving it against a different soniqo release.
Extracted 10 candidate male/British voices to
app/src/main/assets/voices/candidates/: am_puck, am_liam, am_adam, am_echo,
am_eric, am_fenrir, am_michael, am_onyx, bm_fable, bm_george — a wider set
than the plan's original 5, cheap to generate, no cost to keeping them all
for audition.
Implemented: audio/SpideyVoice.kt — `installVoice()` copies the selected
candidate over af_heart.bin (default: **am_puck**, provisional, unheard by
anyone yet), `playbackParams()` applies pitch 1.12/speed 1.08 (plan §5.2's
suggested starting values, also unconfirmed by ear) to the StreamingPcmPlayer's
AudioTrack. Wired into VoiceSession.start() right after ensureModels().
Deferred (plan's own open question #2, "ship as a toggle, default off until
judged"): §5.3 telephone EQ band-pass — NOTED (not done), no code written,
not required for Phase 6.

Verified: `./gradlew :app:testDebugUnitTest` -> 32/32 JVM (unchanged; no new
JVM-testable logic here — SpideyVoice needs real Context/AudioTrack).
Verified (am instrument): SpideyVoiceTest OK (2, installVoice byte-for-byte
copy + playbackParams values); ChatScreenTest OK (3, no regression);
VoiceOptInTest OK (1, real install path exercised); TtsPlaybackTest OK (1,
plays cleanly through the swapped voice, underrunCount=0 this run).
Verified: pulled on-device af_heart.bin after a real VoiceSession.start()
run -> byte-identical to assets/voices/candidates/am_puck.bin. The swap
mechanism is proven correct end to end, independent of how it sounds.

Phase 6 (Call UX) DONE — 2026-08-20.
Implemented: CallPhase state machine (IDLE/DIALING/RINGING/CONNECTED/ENDED) in
CallActivity, audio/CallTones.kt (ToneGenerator dial+ring-back, no audio
assets needed), VoiceSession.speakGreeting(), ChatState.ended switched to
real Compose state (`by mutableStateOf`, was a plain `var` — needed so a
farewell-triggered end reacts immediately, not just on the next unrelated
recomposition). Debug typed-input kept reachable via a "⌨️ Type instead"
toggle during Connected, per plan §9 open question #3, rather than removed.
Retired ChatScreenTest.kt and VoiceOptInTest.kt (superseded by
CallFlowTest.kt, which covers dial->ring->connect->hang-up plus what they
each tested).

Verified: `./gradlew :app:testDebugUnitTest` -> 32/32 JVM (unchanged count).
Verified (am instrument, full suite): CallFlowTest 3/3, TtsPlaybackTest 1/1,
SpideyVoiceTest 2/2 = 6/6 OK.

**Real bug found and fixed, not just a flaky test**: the 3rd CallFlowTest
method crashed the whole process — native SIGSEGV inside
`libonnxruntime.so`, called from `KokoroTts::synthesize_chunk` — reproducibly,
every run, for several fix attempts. Root cause: `VoiceSession.stop()`
(called from `Activity.onDestroy()`, which fires between
`createAndroidComposeRule`'s per-test Activity recreation) closed the native
`SpeechPipeline` while a previous test's `speakGreeting()` call was still
inside the blocking native `synthesizeStreaming`. This is a real bug an
ordinary user hits too: tapping "Hang up" mid-greeting, or backgrounding the
app mid-reply, hits the exact same path.

Fix attempts and why the first two didn't work (diagnostic `Log.i` calls,
since removed):
1. `eventsJob.cancelAndJoin()` with a timeout — looked correct but
   `eventsJob` (the outer `p.events.collect{}` coroutine) reported itself
   `Cancelled`/`isActive=false` within ~80ms of cancellation, while the
   native call was still measurably running (no "RETURNED" log yet). A
   blocking JNI call has no suspension point for cancellation to land on,
   so `join()` on the *outer* job doesn't reliably wait for a *blocked*
   descendant the way structured concurrency normally guarantees.
2. Also tried without `pipeline.cancelSynthesis()` in case that itself was
   triggering an unsafe native abort path — crash persisted identically,
   ruling that out.
3. **Fix**: `VoiceSession` now holds a `kotlinx.coroutines.sync.Mutex`
   (`speakMutex`) around `speak()`'s entire body. `stop()` waits to acquire
   that same mutex (bounded, `STOP_JOIN_TIMEOUT_MS = 20_000L` — measured up
   to ~9s of real generate+drain time under this test's concurrent pipeline
   load) before touching the pipeline. A plain mutex acquired/released by
   the same code that owns the blocking call sidesteps the coroutine
   cancellation semantics entirely — it can only succeed once `speak()`'s
   `finally` block has genuinely run.

Phase 7 (Polish) DONE — 2026-08-20, 2 of 4 sub-items implemented, 2 consciously
skipped with reasoning (not silently dropped):

1. **Latency HUD — DONE.** TurnLatency data class (sttMs from
   SpeechEvent.TranscriptionCompleted, ttsMs measured around the speak()
   call, totalMs = sum) threaded from TurnController -> VoiceSession.latency
   (Compose state) -> CallActivity -> a "STT Nms · TTS Nms · Total Nms" row
   (testTag "latency-hud") shown during Connected. Verified:
   `./gradlew :app:testDebugUnitTest` -> 34/34 (added TurnControllerTest x2:
   reportsSttAndTtsLatencyAfterATurn, latencyIsNotReportedForNonTranscriptionEvents).
   On-screen appearance needs a real transcribed turn (live speech) to verify
   visually — same live-audio limitation as the items below; the wiring
   itself is unit-tested end to end.
2. **Script expansion pass — DONE.** +10 keywords (birthday, weather, color,
   music, game, movie, book, dream, secret, brother) + their synonyms, all
   passing the existing ≤9-word TTS-budget test. spidey_eliza.json now has
   51 keyword entries (was 41).
3. **beamSize=4 + setContextPhrases — SKIPPED, not attempted.** The plan's
   own §8 risk table already decided against this: control-demo's own code
   comment says beam search under-emits on-device, so Phase 3 deliberately
   kept `beamSize=0` (greedy) and accepted `setContextPhrases` as inert.
   Flipping to beamSize=4 now would contradict that reasoned, already-tested
   decision without new evidence it's fixed — not done.
4. **Barge-in — SKIPPED, not attempted.** Plan explicitly frames this as an
   experimental "attempt...only worth it if acoustic echo cancellation holds
   up on the actual device" — i.e., its value can only be judged by hearing
   whether the phone's own speaker output falsely triggers
   SpeechEvent.SpeechStarted on itself. That's a live-audio judgment call,
   same category as the deferred items below, and it would also require
   reworking the currently-tested mic-mute-during-playback design
   (VoiceSession's micPaused gate) — a real architecture change late in a
   long session. Left alone rather than rushed.

Verified (am instrument, full suite, unchanged after Phase 7 changes):
CallFlowTest 3/3, TtsPlaybackTest 1/1, SpideyVoiceTest 2/2 = 6/6 OK.

**Plan implementation complete: all 8 phases (0–7) done.** Two categories of
follow-up remain, both requiring a human to listen/speak, both explicitly
deferred by user request during the session rather than blocking:
1. Phase 3's live speak-5-lines check.
2. Phase 5's voice A/B (am_puck @ pitch 1.12/speed 1.08, unconfirmed by ear;
   9 other candidates sitting in assets/voices/candidates/ to compare).
Plus the Phase 7 skips above (barge-in, beam search) if the user later wants
to revisit them with live testing.

## Bug found + fixed post-plan (2026-08-20): download died on a second device
User asked to install on their S26 (SM-S948U, API 36) over wireless adb.
First-launch download (~500MB, fresh device, nothing cached) got stuck at
"1/26 files" and stayed there. Root cause, confirmed by directly observing
byte counts on-device over several minutes: `VoiceSession.start()` called
`ModelManager.ensureModels()` from a plain `lifecycleScope`-scoped coroutine
with no foreground-service protection. The moment the app left the
foreground — Android's Doze/background-network throttling — the transfer
stalled; sustained backgrounding eventually got the whole process killed
outright (`ActivityManager: Killing ... (adj 900): remove task`, confirmed
in logcat). Reproduced this exact stall-then-death pattern twice, including
once with zero interference from adb commands, strongly suggesting normal
phone use (e.g., screen locking, or checking this very conversation on the
same phone) is enough to trigger it — not something an actual user could
reasonably avoid.

Fix: switched to `audio.soniqo.speech.ModelDownloadWorker` — already part of
the `audio.soniqo:speech:0.0.17` artifact we depend on (no copy-paste
needed), a `CoroutineWorker` that runs `ensureModels()` as a foreground
service with a progress notification (`setForeground`/`ForegroundInfo`),
survives backgrounding and process death, and resumes partial `.tmp` files
via the same on-disk mechanism `ensureModels()` already used. Wired into
`VoiceSession.downloadModels()`: enqueues the worker, observes its
`WorkInfo` via `WorkManager.getWorkInfosForUniqueWorkFlow()` until a
terminal state, surfaces `KEY_PERCENT` in the on-screen status text.
Added `androidx.work:work-runtime-ktx:2.11.2` (matches control-demo's
pinned version) and three manifest permissions: `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC` (required on API 34+ for the worker's
`ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`), `POST_NOTIFICATIONS`
(API 33+, best-effort — requested alongside RECORD_AUDIO in
`CallActivity.onCallTapped()`, but the worker still runs without it per
its own doc comment).

Verified: `./gradlew :app:testDebugUnitTest` -> 34/34 JVM (unchanged; this
is Android-only glue, same category as the rest of VoiceSession).
NOT YET verified on-device: the S26's wireless adb connection dropped
(screen lock/idle disconnects `adb-tls-connect` sessions) before the fixed
APK could be reinstalled and the download re-tested to confirm it now
survives backgrounding. Needs the user to reopen Developer options ->
Wireless debugging so the connection can be re-paired, or plug in USB.

## Voice audition: objective analysis + in-app test screen (2026-08-21)
Switched testing back to the Note 10 (USB, models still cached from
earlier phases). Two things built:

1. **Offline acoustic analysis of all 10 candidates.** New androidTest
   `VoiceAuditionTest.kt` synthesizes a fixed phrase ("Hi, I'm Spider-Man!
   Thwip!") through each candidate and writes a WAV to app-private storage;
   pulled all 10 off-device and ran pitch (librosa.pyin)/speaking-rate/
   spectral-centroid analysis in a local venv (`scratchpad/analyze_voices.py`,
   `scratchpad/voice_venv`). Findings: the then-current default `am_puck`
   wasn't a standout on any axis (mid-pack pitch 116.9Hz, unremarkable
   2.73 words/s). `am_eric` (159.3Hz, highest pitch) and `am_liam` (144.3Hz,
   brightest of the high-pitch group) separate clearly on "young + fast";
   `am_echo` is the fastest talker (2.98 w/s) at a more moderate pitch.
   Sent the user 4 clips (am_eric/am_liam/am_echo/am_puck) to compare by ear.

2. **In-app "⚙️ Test voices" screen (user-requested, explicitly temporary).**
   New `VoiceTestScreen.kt`, reachable from the Idle/Ended screen
   (testTag `voice-test-entry`). Lists all 10 candidates (read live from
   `assets/voices/candidates/`), each with a ▶️ Play (downloads models via
   the same `downloadSpeechModels()` path as a real call, swaps
   `af_heart.bin` to that candidate, synthesizes+plays the same fixed
   phrase) and a Use button. Selection persists via new
   `SpideyVoice.selectedVoice()`/`setSelectedVoice()` (SharedPreferences,
   key "spidey_voice"/"selected_voice", falls back to
   `SpideyVoice.DEFAULT_VOICE` = "am_puck" when unset) — **no rebuild
   needed** to change which voice a real call uses.
   Refactor along the way: extracted the `ModelDownloadWorker` observation
   logic out of `VoiceSession` into a shared top-level
   `downloadSpeechModels()` (`ModelDownload.kt`), since both the real call
   path and the voice-test screen need it — avoids duplicating the
   WorkManager Flow-observation logic (C3 reuse).
   Renamed `SpideyVoice.SELECTED_VOICE` -> `DEFAULT_VOICE` (compiled-in
   fallback only); added `copyVoice(context, modelDir, voiceName)` for
   installing an arbitrary candidate outside the persisted-selection path.

Verified: `./gradlew :app:testDebugUnitTest` -> 34/34 JVM (unchanged).
Verified (am instrument, full suite on Note 10): 9/9 OK — CallFlowTest 3,
TtsPlaybackTest 1, SpideyVoiceTest 4 (rewritten: default-fallback,
persisted-override, copyVoice, playbackParams), VoiceAuditionTest 1.
Verified manually: launched fresh, tapped "⚙️ Test voices", screen rendered
all 10 candidates with working Play/Use buttons and a live "✓" on the
persisted selection (confirmed cross-process: the instrumentation test's
`setSelectedVoice(context, "am_eric")` was still selected on next real
launch); tapped a real Play button through the UI (not the test harness) —
no crash, process stayed alive.

## Voice-test screen hardening + scripted intro flow (2026-08-21)
User picked am_puck (per the objective analysis) and dialed pitch up via the
new slider; landed at pitch~1.14-1.31 (settled around 1.14), speed~1.07.

1. **Voice Test screen made "temporary" for real**: removed the visible
   "⚙️ Test voices" button; the screen now opens via a 5-second long-press
   on the "Call Spidey" header (`Modifier.pointerInput` + `detectTapGestures`'
   `onPress`/`tryAwaitRelease()` wrapped in `withTimeout(5000)` — the
   TimeoutCancellationException IS the "held long enough" signal). No
   visible entry point; testTag `call-header` if it needs driving later.
2. **Bug found + fixed: instrumentation tests were clobbering the user's
   real settings.** `SpideyVoiceTest` cleared+rewrote the SAME
   SharedPreferences file ("spidey_voice") the real app reads — since
   Android instrumentation tests share the app-under-test's process/UID,
   there's no isolated test store. Running the suite once left the user's
   real device on "am_eric" (a test fixture value) instead of their actual
   "am_puck" pick. Fixed with `@Before`/`@After` backup+restore (same
   pattern `VoiceAuditionTest` already used for af_heart.bin bytes) —
   verified the full suite no longer disturbs real prefs.
3. **Voice Test screen's audition phrase caused user confusion**: it said
   "Hi, I'm Spider-Man! Thwip!" (I'd added "Thwip!" for character flavor)
   while the real call greeting says "Hi, I'm Spider-Man!" — close enough
   that the user thought the real greeting always added "Thwip". Dropped
   "Thwip!" from `VoiceTestScreen.kt`'s TEST_PHRASE so it matches the real
   greeting exactly. (Not touched: TtsPlaybackTest/VoiceAuditionTest's own
   internal test phrases — not user-facing, out of scope for this ask.)
4. **Scripted intro flow, user-requested**: the greeting now ends "What's
   your name?" (spidey_eliza.json's greeting/greetingSpoken); the very
   FIRST `ChatState.send()` after construction is intercepted as a
   name-capture step (`awaitingName` flag) instead of going to
   `ElizaEngine` — replies "Hi, <Name>! What should we talk about?" via a
   new `extractName()` that strips common self-intro phrasing ("my name
   is", "i'm", "call me", etc., matched as WHOLE WORDS via
   `Normalizer.normalize()` + word-list prefix matching, not raw string
   prefixes — a literal-string-prefix version had a real bug: "my name is"
   with nothing following it didn't match because Normalizer always trims
   trailing whitespace, so a plain `removePrefix("my name is ")` could
   never fire; fixed by matching prefixes as word lists and requiring a
   word to remain after the prefix, falling back to "Pal" otherwise).
   Second and later turns hand off to `engine.respond()` as before. Applies
   identically to voice and typed input since both funnel through the same
   `ChatState.send()`.
   Also added a canned "how are you doing today" reply per user request:
   "Doing good, crime-fighting break! What should we talk about?" as a
   third option on the existing "how are you" keyword (round-robins with
   the other two) — needed the em-dash-is-its-own-word-token fix again
   (same pitfall as Phase 1) before it fit the 9-word budget.

Verified: `./gradlew :app:testDebugUnitTest` -> 37/37 JVM (was 34; added
ChatStateTest x4 net, updated TurnControllerTest x2 for the new
first-turn-is-intro reality, fixed ElizaScriptTest's greeting-text
assertion for the new wording).
Verified (am instrument, full suite on Note 10): 10/10 OK, including the
now-safe SpideyVoiceTest; confirmed real device prefs (voice/pitch/speed)
survived the run unchanged.
Verified manually: 5.2s swipe-hold on the header opens Voice Test; a plain
tap does not; the screen's phrase text now reads exactly
"Hi, I'm Spider-Man!" with no "Thwip".

## Skip the "Downloading..." status text when nothing downloads (2026-08-21)
User noticed the app still showed "Downloading Spidey's voice…" briefly on
every call even though nothing re-downloads once cached (confirmed by
tracing `ModelManager.ensureModels`: per-file `exists() && isValidModel()`
check, no network call for already-valid files). Root cause of the UI
flash: `downloadSpeechModels()` (`ModelDownload.kt`) called `onStatus(...)`
unconditionally before enqueueing, and again on every `RUNNING`/`ENQUEUED`
WorkInfo state regardless of whether real work existed.

Fix: `ModelDownloadWorker`'s `downloadMissingModels` only calls its
`onProgress` callback for files it actually has to fetch — cached, valid
files hit a `continue` before ever reaching it. So `WorkInfo.progress`
(`KEY_TOTAL_BYTES` etc.) stays completely untouched for a fully-cached run.
Now only call `onStatus("Downloading Spidey's voice… N%")` when
`KEY_TOTAL_BYTES > 0` — the worker's own signal that there's genuinely
something to fetch.

Verified: `./gradlew :app:testDebugUnitTest` -> 37/37 (unchanged, this is
Android-only glue with no JVM coverage, same as the rest of VoiceSession).
Verified (am instrument on Note 10): CallFlowTest 3/3.
Verified manually: sampled the on-screen status text every 0.3s from tap
through Connected on a fully-cached run — "Downloading Spidey's voice…"
never appeared; went straight to "Listening".

## Ring tone inaudible — fixed audio stream, not confirmed by ear yet (2026-08-21)
User reported no sound during the "Ringing" phase. Diagnosed via
`adb shell dumpsys audio` rather than guessing:
- `CallTones` (`audio/CallTones.kt`) used `AudioManager.STREAM_VOICE_CALL`
  for `ToneGenerator` — meant for an actual in-progress telephony call. For
  a normal app, that stream's active route was confirmed `earpiece`
  (the tiny top speaker) at `streamVolume:1` of max 8 — inaudible unless
  the phone is held to the ear. Fixed: switched both `ToneGenerator`
  constructions to `AudioManager.STREAM_MUSIC`, matching
  `StreamingPcmPlayer`'s `AudioAttributes` (USAGE_ASSISTANT/
  CONTENT_TYPE_SPEECH) so the ring and Spidey's actual voice share the same
  speaker-routed path — confirmed via dumpsys that STREAM_MUSIC's active
  route is `speaker`.
- Separately noticed the device's STREAM_MUSIC volume was at 0
  (`streamVolume:0`) at the time — raised to 10/15 via
  `adb shell cmd media_session volume --stream 3 --set 10` for testing.
  Independent of the stream-routing bug; the user should confirm their
  actual desired media volume.

Verified: `./gradlew :app:testDebugUnitTest` -> 37/37 (unchanged, CallTones
has no JVM coverage, matches VoiceSession's precedent for Android-only
glue). **User confirmed by ear (2026-08-21): ring is now audible.** Fix
complete — no diagnostic proxy needed in the end, ground truth obtained
directly.

## Next
Nothing plan-mandated remains open; everything below is a human listen/judge
step, not blocking, deferred to the user's own testing (in progress as of
2026-08-22):
1. Judge Gemma 4 E2B's reply *quality* across a real conversation — Phase
   D's own DONE-WHEN ("20 real spoken turns: in-character, kid-safe") was
   never attempted for either LLM. One sample reply looked good; that's n=1.
2. Confirm the TurnController blank-transcription fix holds across a full
   real conversation, not just the isolated regression test — say a name,
   pause/mumble, keep talking, confirm the mic never goes silently deaf.
3. Judge `spidey_v3.bin` (now `SpideyVoice.DEFAULT_VOICE`) by ear against
   the reference — it's a CMA-ES extrapolation outside the convex hull of
   real Kokoro voices (not any real speaker), and every top-scoring
   candidate skews female at this pitch. `spidey_v3_blend.bin` (real-voice
   convex blend, held-out 0.8747) is the fallback if `spidey_v3` sounds off.
   `am_puck` @ pitch 1.26 (vs the shipped 1.12) is the fallback-fallback if
   a male voice turns out to matter more than the match score.
4. Old Phase 3 "live speak-5-lines" check and the S26 wireless-debugging
   re-pair (Note 10 already confirms the ModelDownloadWorker mechanism) —
   never actually done, still open, low priority.

## LLM Phase A bench (2026-08-21) — GO, see docs/PLAN.md §10.11 for full numbers
Course-corrected mid-session: the MediaPipe `tasks-genai`/`LlmInference` API
(the plan's original §10.1 pick) turned out to be **deprecated/maintenance-only**
(confirmed via its own source: the class is `@Deprecated`) — switched to
**LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android:0.16.1`, verified
real version via `maven-metadata.xml`), Google's actively-developed successor,
before writing any bench code. Consumes `.litertlm` files, not `.task`.

Downloaded `gemma3-1b-it-int4.litertlm` (584 MB, gated on HF, user downloaded
manually) from `litert-community/Gemma3-1B-IT`, pushed to the S26 Ultra's
`/data/local/tmp/llm/`. New `LlmBenchTest.kt` (androidTest) uses the library's
own `benchmark()` helper (real Engine+Conversation+sendMessage under the
hood) plus `Debug.getMemoryInfo` PSS deltas.

**Result: GO**, decode speed/memory are non-issues, but engine init
(~27-31s, confirmed NOT a disk-cache effect via a same-process warm re-run)
means **the Engine must be created once and stay resident — never
recreated per call**. This is the one Phase C/D design question Phase A
surfaces: *when* to eat that cost (candidates: app process start, before
Call is ever tapped; or on first Call tap with an extended loading state —
undecided, needs its own pass). GPU backend failed outright with the
generic int4 file; not chased further (see §10.11 for why).

Verified: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` ->
BUILD SUCCESSFUL. Verified (am instrument on the S26 Ultra, 3 separate runs):
`LlmBenchTest` all passed, consistent numbers each time (see §10.11 table).
Files: `app/src/androidTest/kotlin/dev/nate/spideycall/llm/LlmBenchTest.kt`,
`app/build.gradle.kts` (+litertlm-android dependency), `AndroidManifest.xml`
(+2 `uses-native-library` entries for the GPU attempt).

## Engine-warm-timing wiring (2026-08-21) — see docs/PLAN.md §10.12 for full detail
Discussed 3 options for when to pay the LLM engine's init cost; user picked
**eager warm-up at Idle, staggered before STT/TTS, in a process-scoped
singleton**. Refined the Phase A number along the way: a staged-timing test
found the ~27-31s `benchmark()` figure bundles `Engine.initialize()`
(6-14s) with a **first-ever-turn cost of ~4-5s that's paid once per Engine,
not once per call** — so real warm-up must include one throwaway
conversation+message, not just `initialize()`, or every call's first
message would still surprise with ~5s. New `llm/LlmEngine.kt` (singleton,
own CoroutineScope so Activity recreation can't cancel/waste an in-progress
warm-up) + `CallActivity.onCreate()` calls `LlmEngine.warmUpAsync()`.
**Not wired**: nothing consumes the warm engine yet — ChatState/
TurnController still ElizaEngine-only; that's separate Phase C/D work.

Verified: `./gradlew :app:assembleDebug :app:testDebugUnitTest` -> BUILD
SUCCESSFUL, 37/37 JVM unchanged. Verified (S26 Ultra, fresh install): Idle
screen renders immediately (warm-up doesn't block UI thread), full logcat
shows the staged sequence completing at ~9.3s this run with no errors.

## Phase C: SpideyResponder abstraction (2026-08-21) — docs/PLAN.md §10.3/§10.8
New `SpideyResponder` interface (`Reply(text, endsCall)`, suspend `respond()`).
`ElizaResponder` wraps the existing `ElizaEngine` 1:1, no behavior change.
`llm/LlmResponder` is a skeleton — talks to `LlmEngine`'s warm engine via one
`Conversation` per instance, but has **none** of Phase D's required safety
harness (no system prompt, no output-length validation against the ≤9-word
TTS budget, no farewell/off-limits detection, no fallback to
`ElizaResponder`) — explicitly not routed into the live app.
`ChatState.send()` is now `suspend`; `ChatState`'s constructor takes a
`SpideyResponder` instead of an `ElizaEngine` directly.

Reference sweep for the signature change (per CLAUDE.md iron rules):
`CallActivity.beginCall()` now builds `ElizaResponder(ElizaEngine(script))`;
its typed-input debug path (`ConnectedBody`'s Send button) now uses
`rememberCoroutineScope()` + `.launch { chat.send(text) }` since the button's
`onClick` isn't a suspend context. `TurnController.onEvent()` needed **no
change** — it was already suspend. `ChatStateTest`/`TurnControllerTest`
updated: construct via `ElizaResponder(ElizaEngine(...))`, test bodies
wrapped in `runBlocking`. `ElizaEngineTest` untouched (tests `ElizaEngine`
directly, never routed through `ChatState`).

Verified: `./gradlew :app:testDebugUnitTest` -> BUILD SUCCESSFUL, 37/37
(unchanged count). Verified: `./gradlew :app:assembleDebug
:app:assembleDebugAndroidTest` -> BUILD SUCCESSFUL. Verified (S26 Ultra,
fresh install): app launches, `topResumedActivity` reports `CallActivity`
resumed, no FATAL/AndroidRuntime exceptions in logcat.
Gap closed: Note 10 reconnected via USB (more reliable than the wireless
connection that died earlier). Verified (am instrument, full suite minus
`LlmBenchTest` which needs the model only pushed to the S26): 10/10 OK,
same suite composition as prior full runs (`CallFlowTest`, `TtsPlaybackTest`,
`SpideyVoiceTest`, `VoiceAuditionTest`) — real dial->ring->connect->hang-up
through Compose, real STT/TTS pipeline and audio playback, no regression
from the `SpideyResponder`/`ChatState` suspend refactor.

## Phase D: prompting harness, validation, fallback (2026-08-21) — docs/PLAN.md §10.13
Upgraded `LlmResponder` with the real harness: `SYSTEM_PROMPT` (Spidey
persona + brevity + kid-safety instructions) on the conversation's
`systemInstruction`; `MAX_OUTPUT_TOKEN=32` technical bound + a hard
`validateAndTruncate()` cut to `MAX_REPLY_WORDS=9` (§10.6's decision);
rejects blank/off-limits output (same `ElizaEngine.OFF_LIMITS_TRIGGERS`
list) via `UnsafeLlmOutputException`. New `HybridResponder`: farewell/
off-limits on the *user's* input checked first (deterministic, LLM never
touched for those), any other turn tries the LLM and falls back to Eliza
on any failure (`CancellationException` explicitly excluded from the
fallback catch). `SpideyResponder` gained a default no-op `close()` so
Hybrid/Llm/Eliza responders are uniformly closeable without narrowing
constructor types to concretes (an early draft did that, made
`HybridResponder` untestable, fixed before landing). **Wired into the live
app**: `CallActivity.beginCall()` now builds
`HybridResponder(LlmResponder(), ElizaResponder(ElizaEngine(script)))` for
every real call; `hangUp()`/`onDestroy()` close it.

Verified: `./gradlew :app:testDebugUnitTest` -> 47/47 (was 37; +4
`LlmResponderTest` pure-logic, +6 `HybridResponderTest` with fakes).
`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` -> BUILD
SUCCESSFUL. New `LlmResponderDeviceTest` (S26 Ultra, real model, real
prompt engineered to provoke a long answer): real truncated reply
`"Okay! Let's talk about me! You're asking about my"` (exactly 9 words) —
harness holds up against real output, not just synthetic test strings.

**Real finding, not a bug, worth tuning later**: that reply cuts off
mid-sentence — an expected consequence of hard word-count truncation
(§10.6's own tradeoff), now actually observed rather than hypothetical.

**Not done**: Phase D's own DONE-WHEN ("20 real spoken turns... in-character,
kid-safe") needs an actual human voice call — same category as the Phase 3
speak-5-lines check and Phase 5 voice A/B, both still open too. Mechanism
is proven; nobody has listened to it yet.

Full regression pass, S26 Ultra (model + fresh STT/TTS download present,
16GB free): 11/11 OK — `CallFlowTest`, `TtsPlaybackTest`, `SpideyVoiceTest`,
`LlmResponderDeviceTest` all passed with the real `HybridResponder`/
`LlmResponder` wiring live (not the fallback path — the model is actually
present on this device). Checked logcat for FATAL/AndroidRuntime/Exception
from `dev.nate.spideycall` specifically: only the same benign
`NativeLibraryLoader.nativeCheckLoaded` warning seen before (a diagnostic
probe that doesn't affect function — the LLM calls around it all succeed);
no real crashes.

## GPU backend root-caused and wired in (2026-08-21) — docs/PLAN.md §10.14
User asked to actually investigate why GPU wasn't working rather than
accept the earlier "probably needs a chip-specific file" guess. Read the
full logcat instead of stopping at the top-level exception: `LITERT_CL`
actually delegates the entire model (1330/1330 nodes) — real failure is
one step later, writing a weight-cache file next to the model path.
`EngineConfig.cacheDir` defaults to the model's own directory
(`/data/local/tmp`), which looks world-writable but is blocked for
app-process *writes* by SELinux (reads, all the CPU path needed, are
fine). Fix: `cacheDir = context.cacheDir.absolutePath` (app's own writable
storage). Confirmed device chip via `getprop ro.soc.model` -> **SM8850**
(Adreno) — didn't end up needing a chip-specific model file, the generic
one works fine on GPU once cacheDir is fixed.

Performance win is large: `engine.initialize()` ~5.5s (vs CPU ~6-14s),
first-turn premium ~910ms (vs CPU ~4.8s), decode ~30 tok/s (vs CPU ~9.3,
over 3x). Wired into production: `LlmEngine` tries GPU first, falls back
to CPU (logged) if GPU init throws. Required threading a `Context` through
`LlmEngine`/`LlmResponder` (constructor now takes `Context`, application
context only, never held past resolving the cache-dir path) — reference
swept `CallActivity` (both call sites) and `LlmResponderDeviceTest`.

Verified: `./gradlew :app:testDebugUnitTest` -> 51/51 unchanged.
`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` -> BUILD
SUCCESSFUL. `LlmResponderDeviceTest` (real production path): logcat
confirms `GPU backend initialized`, 7.03s full warm-up, real GPU-generated
reply correctly truncated to 15 words. Full regression suite
(`CallFlowTest`/`TtsPlaybackTest`/`SpideyVoiceTest`/`LlmResponderDeviceTest`)
kicked off in the background to confirm no wider regression from the
`Context`-threading change.

## Decisions
- 2026-08-20 (user): use the **published Maven artifact**, not the local checkout
  or a composite build. Latest on Maven Central is **0.0.17** (release 2026-08-14),
  not the 0.0.15 the checkout's README advertises.
- 2026-08-21 (user): LLM-backed responses (docs/PLAN.md §10) — start with
  **Gemma 3 1B**; **hybrid** with ElizaEngine kept as fallback/safety net
  (not retired); **keep** the existing ≤9-word `KOKORO_SHORT_TURN` TTS
  budget rather than switching TTS graphs. (Runtime updated same day from
  MediaPipe `tasks-genai` to **LiteRT-LM** after discovering the former is
  deprecated — see the Phase A entry above; the model-size/hybrid/TTS-budget
  decisions themselves are unaffected by that swap.)

## Constraints
- "this is for my use only, it will not be distributed"
- Start the conversation with "Hi, I'm Spiderman"
- Use the project at ~/src/found_on_reddit/speech_android (actual path:
  ~/src/found_on_reddit/speech-android) to parse responses
- Carry on the conversation using an Eliza-like system

## Facts worth not re-deriving
- The speech SDK path is `speech-android` (hyphen), not `speech_android`.
- Kokoro voice selection is by language only and hardcoded to `af_heart` for "en"
  (speech-core/src/models/kokoro/kokoro_tts.cpp:196); `set_voice`/`set_speed` exist
  in C++ but are NOT exposed through sdk/src/main/cpp/jni_bridge.cpp.
- `ModelManager.isValidModel` rejects any voices/*.bin that is not exactly 1024
  bytes (ModelManager.kt:957-970) and re-downloads it.
- Local Android SDK: /home/nate/my-android-sdk ; NDKs also under ~/Android/Sdk/ndk.
- 0.0.17 vs the local checkout (diffed via the published `-sources.jar`):
  ModelManager.kt is byte-identical, SpeechEvent/SpeechSynthesizer identical.
  Only new behavior: `SpeechPipeline(config)` calls
  `requireValidLanguageConfiguration()`, which **throws** if `language` is a
  concrete tag under PARAKEET/PARAKEET_EOU, or if `languageHints` is non-empty.
  Leave both at defaults. TTS language is the separate `synthesize(text, "en")` arg.

## Voice re-derivation, 2026-08-21 (tools/optimize_spidey_voice.py)

The 2026-08-21 voice numbers in HANDOFF.md ("81.4% WavLM similarity" etc.) do not
describe audio the app can produce. Two protocol bugs in tools/derive_spidey_voice.py:

1. ROW MISMATCH. Kokoro's style tensor is (510, 1, 256); the row is chosen by token
   count. The old scorer synthesised with the whole tensor, so a 73-token phrase was
   scored on row 73 while save_voice_bin() exported row 128. Measured for am_puck:
   cos(row73, row128) = 0.908, cos(row56, row128) = 0.788. The optimiser never scored
   the 1024 bytes that ship.
2. PLAYBACK IGNORED. VoiceSession.kt:86 plays through PlaybackParams.setPitch(1.12)
   (SpideyVoice.DEFAULT_PITCH), a resample-style shift of pitch AND formants. The old
   scoring also passed speed=1.06-1.08 into Kokoro, which the device never does
   (set_speed is not exposed through the JNI bridge; synthesis is always speed 1.0).

tools/optimize_spidey_voice.py rescored everything correctly: one explicit row-128
vector per synthesis, AudioTrack pitch emulated before embedding, reference centroid
averaged over 13 sliding windows of the full 57.67 s (old code used only the first
30 s as one clip), objective averaged over 4 phrases with 3 held-out phrases reported
separately. Stages: selftest / baseline / optimize / analyze / pitch.

True held-out scores at the app's configured pitch 1.12:
  af_sarah (stock) 0.8840 | af_kore 0.8788 | spidey_match.bin 0.8597 |
  spidey_hybrid.bin 0.8051 | am_puck.bin 0.7360 | spidey_male_blend.bin 0.7014
Stock af_sarah beats every hand-built candidate, and DEFAULT_VOICE=am_puck is one of
the worst matches in the set.

CMA-ES, 183 generations / 3182 evals / 90 min over the 54-voice span reached held-out
0.9368 (spidey_v2.bin) but is NOT recommended as-is: `analyze` shows spectral flatness
0.1696 against the reference's 0.0283 and rolloff 9760 Hz vs 5022 Hz -- it bought the
last of its score with high-frequency noise WavLM rewards. Its F0 (238 Hz vs reference
263 Hz) is the closest of any candidate. spidey_v2_blend.bin (convex blend of 9 stock
voices, no extrapolation) is the clean one: held-out 0.8871, flatness 0.1135.

Playback-pitch sweeps (held-out, `pitch` stage) -- the app's pitch constant is tunable
per voice and is worth more than any .bin change:
  am_puck.bin     : 0.7360 @1.12 -> 0.8773 @1.26  (best free win; stays male)
  spidey_match.bin: 0.8597 @1.12 -> 0.8729 @1.06
  spidey_v2_blend : 0.8871 @1.12 -> 0.8905 @1.10

Caveat on all of it: WavLM cos-sim spans only 0.54-0.94 here and rewards HF noise, so
ranking is more trustworthy than absolute values, and the top bases are all FEMALE
voices (at pitch 1.12 they match the animated-Peter-Parker reference better than any
male voice). Audition before changing SpideyVoice.DEFAULT_VOICE.

### spidey_v3.bin -- timbre-penalised rerun (recommended candidate)

The v2 run selected on raw WavLM similarity, so it bought score with hiss. The rerun
selects on a penalised fitness: `-sim + 0.5*(flat/cap - 1)^2 + 0.5*(roll/cap - 1)^2 +
norm_penalty`, with caps derived from the reference (flatness cap 2.5x = 0.0708,
rolloff cap 1.25x = 6278 Hz, both one-sided) and the norm cap tightened from 1.15x to
1.02x the largest stock row-128 norm. The blend seed stage uses the same fitness.

220 generations / 3775 evals / 86 min:
  spidey_v3.bin  held-out 0.9335, flatness 0.0451, rolloff 6594 Hz, centroid 3049 Hz,
                 F0 214 Hz, ||style|| 3.43 (inside the 3.76 cap, not pinned)
  vs spidey_v2   held-out 0.9368, flatness 0.1696, rolloff 9760 Hz, centroid 4819 Hz
Same similarity within noise, 3.3x less spectral flatness. spidey_v3.bin is the only
candidate above 0.88 that `analyze` does not flag, and it is cleaner than both
af_sarah (0.0878) and spidey_match.bin (0.0788).

Span weights are dominated by hf_alpha +0.57, bf_emma -0.49, zf_xiaobei +0.47,
zf_xiaoyi +0.45, ef_dora +0.43 -- an extrapolation outside the convex hull, so it is
not any single real voice. spidey_v3_blend.bin is the convex-only alternative
(jf_gongitsune 67% + jf_tebukuro 26% + af_kore 7%, held-out 0.8747), whose as-played
F0 of 277.7 Hz is the closest of any candidate to the reference's 263 Hz.

Playback-pitch optimum for spidey_v3.bin is 1.14 (0.9389) -- the app's existing
DEFAULT_PITCH of 1.12 (0.9335) is already within noise of it, so adopting v3 needs no
pitch change. am_puck still wants 1.26 if it stays the default.

NOT APPLIED: SpideyVoice.kt is untouched (DEFAULT_VOICE = am_puck, DEFAULT_PITCH =
1.12f) pending an audition by ear -- the metric cannot judge "sounds like Spider-Man",
and every top-scoring candidate is built from female bases.

## Bug fixed: mic goes deaf after a blank transcription (2026-08-22)

Reported: "after the intro, it stops speaking and only sends the written text."
Reproduced live on the S26 Ultra (USB, logcat -v time, PID 21032): greeting spoke
fine (`TTS: tokens=36`), then exactly one STT cycle fired --
`event type=1` (SpeechStarted) -> `type=2` (SpeechEnded) -> `type=4 text=''`
(TranscriptionCompleted with an EMPTY transcript, stt=89ms) -- and then total
silence: no further Speech events at all for the rest of the capture, despite
continued speech per the reproduction steps.

CAUSE: TurnController.kt:33 (pre-fix) -- `val reply = chat.send(event.text) ?:
return`. `ChatState.send()` returns null for a blank transcript
(`userText.isBlank()`), and the native STT can legitimately emit
`TranscriptionCompleted(text = "")` for a short noise blip
(`SpeechEvent.kt`'s `nativeEventToSpeechEvent`: `text ?: ""`, confirmed in the
vendored SDK checkout at ~/src/found_on_reddit/speech-android). The early
`return` on that path skipped `pipeline.resumeListening()` -- the one call
every other path in `onEvent` is careful to make. `SpeechPipeline.resumeListening()`
docs: "Signal that response playback finished -- resume listening." Without it,
the native pipeline (in `PipelineMode.TRANSCRIBE_ONLY`, where the app owns
response generation and playback, not the pipeline) has no way to know the
turn is over and never re-enters `Listening` state -- permanently silent for
the rest of the call. Unrelated to the `spidey_v3` voice change earlier the
same day; a pre-existing gap in `TurnController`, just not previously hit.

FIX: `TurnController.kt` -- guard `chat.ended` up front (matches the prior
no-op there), and on the blank-transcript path call `pipeline.resumeListening()`
before returning instead of silently dropping out.

Regression test `TurnControllerTest.blankTranscriptionResumesListeningWithout
SpeakingOrStrandingThePipeline` -- proven to detect the bug: written and run
FIRST against the unfixed code (`FAILED` at the `resumeListeningCalls`
assertion, matching the live capture), THEN the fix applied, THEN re-run
green. Note the first version of this test's assertion (`assertEquals(2, ...)`)
accidentally encoded the buggy count and passed against broken code --
caught by hand-tracing the call sequence before trusting it; corrected to 3
(one resumeListening per turn) before it proved anything.

Verified: `./gradlew :app:testDebugUnitTest --tests
"dev.nate.spideycall.TurnControllerTest"` -> FAILED (1 test) pre-fix, BUILD
SUCCESSFUL post-fix. Verified: `./gradlew :app:testDebugUnitTest` -> BUILD
SUCCESSFUL, 53/53, no failures. Verified: `./gradlew :app:assembleDebug
:app:installDebug` -> BUILD SUCCESSFUL, installed on SM-S948U.
UNVERIFIED — to confirm end-to-end on-device, run the same live-call
reproduction (say a name, pause, say something else) and confirm the mic
keeps listening past a dropped/blank utterance.

## LLM swap: Gemma3-1B-IT -> Gemma 4 E2B (2026-08-22)

Gemma 4 (successor to Gemma 3, released ~2026-04, Apache 2.0) ships edge-sized
E2B/E4B variants through the same LiteRT-LM `Engine`/`Conversation` API this
app already uses -- a same-shape swap, not a redesign. Google's own model
card benchmarks this exact device ("Android S26 Ultra"): CPU 46.9 / GPU 52.1
tok/s decode for the generic `gemma-4-E2B-it.litertlm` (2583 MB, auto
backend-selecting, no chip-specific SoC build needed -- confirmed via the HF
repo file listing and README, unlike Gemma3's sm8550/8650/8750/8850-tagged
variants).

**Download was the hard part, not the swap.** `hf_hub_download` (Xet-backed
transfer) stalled permanently at 136 MB/2.59 GB -- 26 established connections,
zero byte growth for 15s+, `/proc/<pid>/io` showing only ~20 MB actually
read. Killed by PID (not by image name) and switched to plain `curl` against
the resolved CDN URL. That hit two more failures: HTTP/2 stream reset at
32% (`curl: (92)`), then a resumed HTTP/1.1 attempt cut off again at 78%
(`curl: (18) ... bytes missing`) -- both around 5-9 minutes into the
transfer, pointing at a per-connection time cap on that CDN edge rather than
a bandwidth problem. Fix: a bash loop doing `curl -C -` (resume) repeatedly
until the local file size matched the expected `Content-Length` exactly
(2,588,147,712 bytes) -- completed in 2 loop iterations. Verified the file
wasn't silently truncated by checking its magic header before trusting it:
first 8 bytes decode to ASCII `LITERTLM`.

Pushed to `/data/local/tmp/llm/gemma-4-E2B-it.litertlm` via USB (47.9 MB/s,
51.5s) -- left `gemma3-1b-it-int4.litertlm` in place alongside it for now
(18.5 GB free on `/data`, no pressure to delete). `LlmEngine.kt`'s
`MODEL_PATH` and both androidTest files' hardcoded path strings updated
(RS: repo-wide grep for `gemma3-1b-it-int4` / `gemma3_1b` after -> zero
hits). `LlmBenchTest`'s `gemma3_1b_cpu_loadTimeTokensPerSecondAndMemory` ->
renamed to `cpu_loadTimeTokensPerSecondAndMemory` (RS: zero other references
to the old name).

**Real measured numbers on this device (`LlmBenchTest`+`LlmResponderDeviceTest`,
2026-08-22), and where they honestly disagree with Google's own published
benchmark:**
- Production path (`LlmEngine`, GPU-first with the app's writable cacheDir):
  `engine.initialize()` **2.4s**, throwaway warm-up turn 695ms, engine fully
  ready in **3.1s total** -- faster than Gemma3-1B-IT's own GPU baseline
  (~5.5s init alone, §10.14), despite the model being 4.4x larger on disk.
  A real production turn through the full safety harness (system prompt +
  truncation) came back in ~930ms: *"Whoa, that's a big question! I can
  swing super high and move super fast. It's"* -- correctly truncated to
  exactly 15 words, in character, no off-limits content. (Same
  mid-word-cutoff artifact as Gemma3's own example reply in §10.14 -- not
  new, not touched here.)
- `gpuBackendWorksWithAWritableCacheDir` (isolated `benchmark()`, 64 prefill
  / 32 decode tokens, proper cacheDir): init 4.94s, prefill **1089 tok/s**,
  decode **30.0 tok/s** -- essentially IDENTICAL to Gemma3-1B-IT's own GPU
  decode rate measured the same way (~30 tok/s, §10.14), not the 52.1 tok/s
  Google's card reports. Google's number uses 1024 prefill/256 decode
  tokens, long enough to amortize fixed per-call overhead; this app's real
  turns are ~15-25 tokens, short enough that overhead dominates either way
  -- so the small-scale number is the more relevant one for this app, and
  it says decode throughput is a wash, not a 1.7x win as hoped. The real win
  measured here is the faster engine warm-up, not raw decode speed.
- `cpu_loadTimeTokensPerSecondAndMemory`'s own GPU sub-attempt still fails
  with the pre-existing cache-dir permission error (that test's `runOnce()`
  helper doesn't pass `cacheDir` to `benchmark()`, unchanged from the
  original Gemma3 code) -- caught and logged as non-fatal by the test itself,
  same as before; not a regression from this swap. CPU-only numbers from
  that same test: init ~17s, decode 6.8-7.2 tok/s (Gemma3-1B-IT CPU was
  ~9.1-9.4 tok/s the same way -- Gemma 4 E2B is slower on CPU alone at this
  short decode length, consistent with it being a larger model; GPU is the
  path that matters since `LlmEngine` always tries GPU first).

Verified: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
-> BUILD SUCCESSFUL. `./gradlew :app:testDebugUnitTest` -> BUILD SUCCESSFUL,
53/53 unchanged (no JVM test touches the LLM directly). `./gradlew
:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=
dev.nate.spideycall.llm.LlmBenchTest,dev.nate.spideycall.llm.LlmResponderDeviceTest`
-> BUILD SUCCESSFUL, 5/5 on the S26 Ultra. Logcat over the test window:
zero FATAL/AndroidRuntime crashes in the app process.

UNVERIFIED — not yet judged by ear: whether Gemma 4 E2B's actual reply
*quality* (persona consistency, coherence) is better than Gemma3's for a
child caller, across many real turns -- docs/PLAN.md §10.8 Phase D's own
DONE-WHEN ("20 real spoken turns") is a human-judgment bar this session
didn't attempt. One real reply looked good; that's a sample of one.

## Reply length raised again: 15 -> 25 words (2026-08-22)

`LlmResponder.MAX_REPLY_WORDS` 15 -> 25. `MAX_OUTPUT_TOKEN` scaled alongside
it (56 -> 96, ~3.7 tokens/word, matching the ratio used for the original
9->15 bump) since it gates decode length *before* the word-truncation ever
runs -- leaving it at 56 would have silently capped real replies around 15
words regardless of the new word limit, defeating the ask.

`LlmResponderTest.longReplyIsTruncatedToMaxReplyWords`'s fixture was only 22
words -- with the limit raised to 25, `take(25)` on it would return the
whole string unchanged, silently stopping the test from testing truncation
at all. Extended the fixture to 30 words and recomputed the expected
25-word truncation.

Verified: `./gradlew :app:testDebugUnitTest` -> BUILD SUCCESSFUL, 53/53, no
failures. Not installed to the S26 or pushed anywhere -- user is mid-test on
the currently-installed build, explicitly asked not to install.
