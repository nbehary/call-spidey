# Call Spidey — implementation plan

An Android app that simulates placing a phone call to Spider-Man. Tap **Call**,
hear it ring, and Spidey picks up with *"Hi, I'm Spiderman"* — then you talk to
him out loud and he answers, all on-device, no network after first launch.

Personal use only, never distributed (stated constraint). The voice is an
imitation built from a generic TTS voice, not a recording of any performance.

---

## 1. Constraints and decisions

| | |
|---|---|
| Speech stack | `~/src/found_on_reddit/speech-android` (note: hyphen, not underscore) |
| Dialogue | ELIZA-style pattern matcher — no LLM, no network |
| Opening line | displayed as `Hi, I'm Spiderman`, **synthesized** as `Hi, I'm Spider-Man!` so Kokoro's phonemizer stresses it correctly |
| Distribution | none — debug builds installed over adb |
| Target | one phone, arm64, Android 8+ (`minSdk 26`, matching `control-demo`) |

**DECIDED (user, 2026-08-20):** depend on the published Maven artifact, not the
local checkout. The local `:sdk` path would need `./setup.sh` (submodule + ONNX
Runtime download) plus an NDK C++ build.

```kotlin
implementation("audio.soniqo:speech:0.0.17")
```

**Use 0.0.17, not the 0.0.15 in the checkout's README.** Maven Central's
`maven-metadata.xml` lists `0.0.13 … 0.0.17`, release `0.0.17`, last updated
2026-08-14 — the local checkout predates it.

The published `-sources.jar` was diffed against the local checkout to confirm
this plan's API citations survive the version bump:

| File | 0.0.17 vs checkout |
|---|---|
| `SpeechEvent.kt` | identical |
| `SpeechSynthesizer.kt` | identical |
| `ModelManager.kt` | **byte-identical** — the 1024-byte voice rule and every line number cited in §2 and §5.1 still hold |
| `SpeechPipeline.kt` | one change: `invoke()` now calls `requireValidLanguageConfiguration()` |
| `SpeechConfig.kt` | adds `requireValidLanguageConfiguration()` (see below) |

**New constraint in 0.0.17 — `SpeechPipeline(config)` now throws on bad language
settings** rather than ignoring them:

- `PARAKEET` and `PARAKEET_EOU` **reject** a concrete `language`; it must be
  `"auto"` or empty. Both models always auto-detect.
- `languageHints` must be empty for every current backend.

Neither bites us: the plan leaves both at their defaults. Note that the TTS
language is a *separate* argument — `synthesize(text, "en")` — and is
unaffected by this validation.

If Phase 5 turns out to need a JNI change (see §5.1), and only then, fall back
to a composite build:

```kotlin
includeBuild("/home/nate/src/found_on_reddit/speech-android")  // last resort
```

---

## 2. What the SDK actually gives us (verified, with sources)

All references below are under `~/src/found_on_reddit/speech-android/`.

| Need | API | Source |
|---|---|---|
| Mic → transcript | `pipeline.pushAudio(FloatArray)` @16 kHz mono float32 | `sdk/.../SpeechPipeline.kt:56` |
| Transcript event | `SpeechEvent.TranscriptionCompleted(text, confidence, sttMs)` | `sdk/.../SpeechEvent.kt:9` |
| Partial results | `SpeechEvent.PartialTranscription` + `emitPartialTranscriptions = true` | `sdk/.../SpeechConfig.kt` |
| App-composed reply | `pipelineMode = PipelineMode.TRANSCRIBE_ONLY` | `sdk/.../SpeechConfig.kt:38` |
| Text → audio | `pipeline.synthesize(text, "en") : SpeechSynthesisResult(sampleRate, pcm16)` | `SpeechPipeline.kt:81` |
| Streaming audio | `pipeline.synthesizeStreaming(text, "en") { chunk, isFinal -> }` (blocking; off main thread) | `SpeechPipeline.kt:90` |
| Model download | `ModelManager.ensureModels(context, ...)` → model dir path | `sdk/.../ModelManager.kt:381` |
| Name biasing | `setContextPhrases(phrases, maxBonus = 6f)` — **no-op unless `beamSize > 1`** | `SpeechPipeline.kt:70` |
| Barge-in | speaking during playback interrupts; `cancelSynthesis()` / `cancelCurrentTurn()` | README "Pipeline" |

Model bundle we will pull (both are the SDK defaults, ~500 MB total, one-time):

- **STT** `SttModel.PARAKEET_EOU` — streaming + end-of-utterance, 232 MB peak, RTF 0.21
- **TTS** `TtsModel.KOKORO_SHORT_TURN` — 3.0 s unrolled graph, ~527 MB peak, RTF 0.75–0.88

**Design consequence:** the short-turn graph is built for *bounded* replies.
Every ELIZA reassembly must fit roughly one breath — target **≤ 9 words / ≤ 2.5 s**.
That is also exactly how Spidey talks, so the constraint is free.

Two reference files worth copying almost verbatim:

- `control-demo/.../StreamingPcmPlayer.kt` — a warm, low-latency `AudioTrack`
  reused across turns, with `awaitDrained()` and `resetForNextUtterance()`.
- `control-demo/.../ControlAgentActivity.kt:1030-1120` — the `AudioRecord`
  loop (`VOICE_RECOGNITION` source, `ENCODING_PCM_FLOAT`, `FloatArray(512)`,
  `micPaused` gate, dead-read watchdog).

---

## 3. Architecture

```
 ┌── CallActivity (Compose) ────────────────────────────────┐
 │  CallState: Idle → Dialing → Ringing → Connected → Ended │
 └───────────────┬──────────────────────────────────────────┘
                 │
        ┌────────▼─────────┐   transcript    ┌──────────────┐
        │  CallSession     ├────────────────►│ ElizaEngine  │
        │  (mic + pipeline)│◄────────────────┤ + SpideyVoice│
        └────────┬─────────┘   reply text    └──────────────┘
                 │ pcm16
        ┌────────▼──────────────────────────────┐
        │ SpideyVoiceOutput                      │
        │  Kokoro PCM → pitch/rate → phone EQ    │
        │  → StreamingPcmPlayer (AudioTrack)     │
        └────────────────────────────────────────┘
```

Single Gradle module `:app`, namespace `dev.nate.spideycall`:

```
app/src/main/kotlin/dev/nate/spideycall/
  CallActivity.kt          Compose call screen + permission flow
  CallSession.kt           mic loop, pipeline events, turn state machine
  ModelBootstrap.kt        ensureModels() + download progress
  audio/
    StreamingPcmPlayer.kt  (copied from control-demo)
    SpideyVoiceOutput.kt   pitch/rate + telephone EQ + player
  eliza/
    ElizaEngine.kt         keyword rank → decompose → reassemble → memory
    ElizaScript.kt         data classes + JSON loader
    Normalizer.kt          STT cleanup + mishearing map
app/src/main/assets/
  spidey_eliza.json        the whole personality, hot-editable
  voices/am_puck.bin       1024-byte Kokoro voice embedding (see §5)
app/src/test/kotlin/...    JVM unit tests for eliza/ (no device needed)
```

Permissions: `RECORD_AUDIO`, `INTERNET` (first-launch model download only).

---

## 4. The ELIZA engine

Classic Weizenbaum structure, kid-friendly Spidey skin. Everything lives in
`assets/spidey_eliza.json` so the personality is tuned without recompiling.

### 4.1 Script format

```jsonc
{
  "greeting": "Hi, I'm Spiderman",
  "greetingSpoken": "Hi, I'm Spider-Man!",
  "pre":  { "dont": "do not", "cant": "can not", "spidey": "spiderman" },
  "post": { "i": "you", "my": "your", "me": "you", "you": "I", "your": "my" },
  "synonyms": { "scared": ["afraid", "frightened", "spooked"] },
  "keywords": [
    {
      "word": "doc ock",
      "rank": 12,
      "rules": [
        { "decomp": "*",
          "reassemble": [
            "Doc Ock again? Those metal arms never quit!",
            "Whoa, careful — Ock is one slippery scientist.",
            "Team Spidey handled him last time. We can do it again!"
          ] }
      ]
    },
    {
      "word": "scared",
      "rank": 8,
      "rules": [
        { "decomp": "* i am scared of *",
          "reassemble": [
            "Being scared of (2) is okay. Brave means going anyway!",
            "Even I get the jitters about (2). Want a web to hold?"
          ],
          "memory": true }
      ]
    },
    { "word": "$none", "rank": -1,
      "rules": [ { "decomp": "*", "reassemble": [
        "Thwip! Tell me more about that.",
        "Whoa, really? What happened next?",
        "That's a great question, pal!"
      ] } ] }
  ],
  "memoryPrompts": [ "Hey — earlier you said (1). Still on your mind?" ],
  "farewell": [ "Gotta swing! Bye, pal!" ]
}
```

### 4.2 Matching algorithm

1. **Normalize** (`Normalizer.kt`): lowercase, strip punctuation, collapse
   whitespace, apply the mishearing map
   (`spider man|spiderman|spider-man|spidey → spiderman`,
   `doc oc|doctor octopus|dock ock → doc ock`, `miles morales|spin → spin`,
   `gwen|ghost spider → ghost spider`).
2. **Pre-substitute** contractions, then split into sentences; ELIZA processes
   the **first sentence containing a keyword**.
3. **Rank**: collect matching keywords, sort by `rank` descending, so
   `doc ock` (12) wins over the generic `you` (2).
4. **Decompose**: match the sentence against each `decomp` pattern
   (`*` = greedy wildcard group; `(n)` in reassembly = nth group).
5. **Reassemble**: cycle the list round-robin per rule (never random-repeat),
   applying the `post` reflection map to captured groups.
6. **Memory**: rules flagged `memory: true` push the reassembled string onto a
   4-deep stack. On a `$none` fallback, pop a memory ~30 % of the time.
7. **Guards**, checked before step 3:
   - **Farewell** words (`bye`, `goodbye`, `gotta go`, `hang up`) → farewell
     line, then end the call.
   - **Off-limits** words (small blocklist) → gentle deflect
     ("Let's talk about web-slinging instead!"). Kid-safe by construction,
     and cheap since there is no LLM to jailbreak.

### 4.3 Keyword set to author (first pass, ~40 keywords)

`name` · `who are you` · `how are you` · `age/how old` · `web/webs/thwip` ·
`swing` · `spider/bite` · `powers` · `spider-sense` · `costume/suit` ·
`ghost spider/gwen` · `spin/miles` · `trace-e` · `aunt may` · `doc ock` ·
`green goblin` · `rhino` · `electro` · `sandman` · `web-quarters` ·
`school` · `homework` · `friend/friends` · `scared/afraid` · `sad` ·
`hurt` · `mad/angry` · `help` · `favorite` · `food/pizza` · `sleep/bedtime` ·
`play` · `love` · `mom/dad` · `dog/cat` · `yes` · `no` · `why` · `sorry` ·
`thank you` · `$none`

Every reassembly ≤ 9 words. Write them as spoken lines, with `!` and `—` for
Kokoro prosody.

### 4.4 Tests (JVM, no device)

`ElizaEngineTest`: keyword rank ordering · wildcard capture · `post` reflection
(`"I am scared of my dog"` → `"...your dog..."`) · round-robin never repeats
before exhausting · memory push/pop · farewell triggers end · **every**
reassembly in the shipped JSON is ≤ 9 words (guards the TTS budget) ·
`Normalizer` maps each known mishearing.

---

## 5. The Spidey voice — the one genuinely hard part

Spidey in *Spidey and His Amazing Friends* is a bright, young, fast, American
boy voice. Kokoro's default English voice is `af_heart` — adult female. Three
stacked changes get us close:

### 5.1 Swap the voice embedding

The C++ `KokoroTts` picks its voice by **language**, and the mapping is
hardcoded — `{"en", "af_heart"}` at
`speech-core/src/models/kokoro/kokoro_tts.cpp:196`. `set_voice()` exists
(`kokoro_tts.cpp:163`) and `set_speed()` too (`:167`), but **neither is exposed
through the JNI bridge** — `sdk/src/main/cpp/jni_bridge.cpp` only passes the
`voices/` directory.

So: change the *file*, not the code. After `ensureModels()`, copy our chosen
voice embedding over `<modelDir>/voices/af_heart.bin` on every launch
(idempotent, 1 KB, survives cache invalidation).

**Critical size constraint:** `ModelManager.isValidModel` rejects any
`voices/*.bin` that is not exactly **1024 bytes** — 256 float32 — and deletes
and re-downloads it (`ModelManager.kt:957-970`, `KOKORO_VOICE_BYTES`).
Upstream `hexgrad/Kokoro-82M` voices are `[511, 1, 256]` tensors (~523 KB), so
soniqo compacted them to **one row**. We must extract the *same row index*.

Offline prep script (`tools/make_voice.py`, run once on the desktop):

```python
# 1. Recover the row index soniqo used, by matching the shipped af_heart.bin
#    (pulled off the device: adb pull .../models*/voices/af_heart.bin)
#    against every row of the upstream af_heart tensor.
# 2. Extract that same row from the target voice (e.g. am_puck).
# 3. Write exactly 1024 bytes little-endian float32.
```

Candidate voices to audition (verify names against the upstream `voices/`
listing): `am_puck`, `am_liam`, `am_echo`, `am_adam`, `bm_fable`. Pick by ear.

*If the row-index recovery fails* (no exact match — e.g. soniqo averaged rows),
fall back to §5.2 alone, or move to the local `:sdk` build and expose
`set_voice`/`set_speed` through `jni_bridge.cpp` properly. That is the one
scenario that forces the composite build.

### 5.2 Pitch and rate at playback

`AudioTrack.setPlaybackParams(PlaybackParams)` (API 23+) changes pitch and
speed independently — no DSP to write.

```kotlin
track.playbackParams = PlaybackParams()
    .setPitch(1.12f)   // younger; tune 1.06 – 1.20
    .setSpeed(1.08f)   // Spidey talks fast; tune 1.00 – 1.15
```

Expose both as debug sliders during Phase 5, then freeze the chosen numbers.

### 5.3 Telephone character

It is a *phone call*, so the reply should sound like one — and the band-pass
conveniently masks TTS artifacts. A one-pole high-pass at ~300 Hz plus a
one-pole low-pass at ~3.4 kHz over the PCM16 buffer, ~20 lines of Kotlin, plus
a short connect blip. Toggleable, since it also costs intelligibility.

### 5.4 Delivery, via the text

Kokoro follows punctuation. Author reassemblies with interjections and short
clauses — `"Whoa! Nice one, pal."` — rather than trying to bend the model.

---

## 6. Call flow

```
Idle ──Call──► Dialing (dial tone, 0.8 s)
                 └──► Ringing (ring loop, 2–3 s, mask model warm-up)
                        └──► Connected: speak greetingSpoken, then loop:
                                listen → TranscriptionCompleted → Eliza
                                       → synthesizeStreaming → play → listen
                        └──Hang up / farewell keyword──► Ended
```

- Models load and the pipeline starts **during Dialing/Ringing**, so the ring
  hides the ~seconds of warm-up. If loading outruns the ring, keep ringing.
- Phase 4 keeps it half-duplex: mute the mic while Spidey talks (`micPaused`,
  as `control-demo` does) to avoid the app transcribing itself.
- Phase 7 tries barge-in: keep the mic hot and call `cancelSynthesis()` on
  `SpeechStarted`. Only worth it if acoustic echo cancellation holds up on the
  actual device.
- `endOfSpeechSilenceSec = 0.8f` — the 0.5 s default cuts people off, and
  `control-demo` already moved off it for the same reason.

---

## 7. Build phases

Each phase ends with a check that must pass before the next begins.

| # | Phase | Deliverable | DONE-WHEN |
|---|---|---|---|
| 0 | Scaffold | Gradle project, AGP 8.13.2 / Kotlin 2.2.21 / compileSdk 35 / minSdk 26, Compose, `implementation("audio.soniqo:speech:0.0.17")`, empty call screen | `./gradlew :app:assembleDebug` exits 0 — resolving the artifact from `mavenCentral()` is itself the check |
| 1 | ELIZA core | `eliza/` + full `spidey_eliza.json` + unit tests. **No Android APIs — pure Kotlin.** | `./gradlew :app:testDebugUnitTest` green |
| 2 | Text loop | Debug screen: type a line, see Spidey's reply. Proves the personality before any audio | Type 10 lines, replies are in character and ≤ 9 words |
| 3 | Models + STT | `ensureModels()` with progress UI; `AudioRecord` → `pushAudio`; transcripts drive ELIZA (replies still text-only) | Speak 5 lines; each transcript appears and gets an in-character reply |
| 4 | TTS | `synthesizeStreaming` → `StreamingPcmPlayer`; default `af_heart` voice; mic muted while speaking | Full spoken round trip, no dropouts, no self-transcription |
| 5 | Spidey voice | `tools/make_voice.py`, voice swap, pitch/speed sliders, phone EQ; freeze the numbers | Side-by-side A/B: chosen preset sounds like a young, fast, male hero |
| 6 | Call UX | Dial/ring/answer/hang-up, `"Hi, I'm Spiderman"` on connect, call-screen Compose UI | Cold launch → tap Call → greeting plays → conversation → hang up |
| 7 | Polish | Barge-in attempt, latency HUD (`sttMs`/`ttsMs`), script expansion pass, optional `beamSize = 4` + `setContextPhrases(characterNames)` | Latency HUD shows the loop under ~1.5 s; names recognized reliably |

---

## 8. Risks, with the fallback already chosen

| Risk | Signal | Fallback |
|---|---|---|
| Voice-row extraction fails | no exact 1024-byte row match against `af_heart.bin` | ship §5.2+§5.3 only, or move to local `:sdk` and expose `set_voice` via JNI |
| Kokoro short-turn truncates replies | clipped audio, retry log lines | hard cap reassemblies at 9 words (already a Phase 1 test) |
| Self-transcription during playback | Spidey answers himself | half-duplex `micPaused` is the Phase 4 default; barge-in stays opt-in |
| `beamSize > 1` under-emits | `control-demo` comment says beam under-emits on-device | keep greedy (`beamSize = 0`); accept that `setContextPhrases` is inert |
| 500 MB first-launch download | slow/failed first run | download on Wi-Fi during Phase 3; progress UI is part of that phase |
| Published 0.0.17 diverges from the checkout the plan was read from | missing API at compile time | already diffed against the `-sources.jar`: only `SpeechConfig`/`SpeechPipeline` differ, and only by the new language validation (§1). If a later bump breaks something, pin 0.0.17 rather than `includeBuild` |
| `SpeechPipeline(config)` throws at construction | `IllegalArgumentException` about `language` / `languageHints` | leave both at defaults (`"auto"`, empty) — new in 0.0.17, see §1 |

---

## 9. Open questions (none block Phase 0–4)

1. **Voice pick** — `am_puck` vs `am_liam` vs `bm_fable`. Decide by ear in Phase 5.
2. **Telephone EQ** — authentic but less intelligible for a kid listener. Ship as a toggle, default off until judged.
3. **Text-in fallback** — keep the Phase 2 typing screen in the shipped app as a quiet-room option? Cheap to keep; recommend keeping it behind a long-press.

---

## 10. LLM-backed responses (proposed, not started)

Goal: replace/augment ELIZA's pattern matching with an on-device LLM for more
natural, less repetitive replies, still fully offline, still kid-safe, still
targeting the ≤9-word / ≤2.5s Kokoro short-turn TTS budget (or a deliberate,
explicit decision to drop that budget — see §10.6). Target device: **Galaxy
S26 Ultra, Android 16, 12 GB RAM** (a second, more capable device than the
Note 10 the original 7 phases were built/verified on).

### 10.1 Runtime choice

| Option | Verdict |
|---|---|
| **Google MediaPipe LLM Inference API** (`com.google.mediapipe:tasks-genai`) | **Superseded — do not build on this.** Confirmed artifact `com.google.mediapipe:tasks-genai:0.10.27` exists and docs explicitly list "Samsung S23 or later" as supported, but [the `LlmInference` class itself is marked `@Deprecated`](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/java/com/google/mediapipe/tasks/genai/llminference/LlmInference.java) — Google's Android LLM Inference API is now **maintenance-only**, with new work directed at LiteRT-LM. Building new integration on a maintenance-mode API would be the wrong call for something meant to last. |
| **Google LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`) | **Recommended instead.** The actively-developed successor, with an idiomatic Kotlin API (`Engine`/`Conversation`, `sendMessageAsync` returns a `Flow<String>`) rather than the older Java-style builder. Verified 2026-08-21 against [the Android guide](https://developers.google.com/edge/litert-lm/android): consumes `.litertlm` files (not `.task`), `Backend.CPU()`/`Backend.GPU()`/`Backend.NPU(nativeLibraryDir)` selectable per-conversation, GPU backend needs two `<uses-native-library>` entries in the manifest (`libvndksupport.so`, `libOpenCL.so`). `litert-community/Gemma3-1B-IT` on Hugging Face ships a plain `gemma3-1b-it-int4.litertlm` (584 MB) alongside several Qualcomm-chipset-specific variants (`sm8550`/`sm8650`/`sm8750`/`sm8850` — likely NPU-accelerated builds tied to specific Snapdragon generations, unconfirmed which one matches the S26 Ultra's chip without checking, not assumed here) — start with the generic one. |
| **Samsung on-device AI (Galaxy AI)** | **Not viable as a third-party API**, as far as I can determine — Samsung's on-device features (Bixby, Note Assist, etc.) are system-level, not exposed through a public SDK a third-party app can prompt with arbitrary text. Worth a 15-minute check of the current Samsung Developer site before fully ruling out, but don't plan around it. |
| **Gemini Nano via AICore / ML Kit GenAI APIs** | **Not viable for this use case** — ML Kit's GenAI APIs are narrow (summarize, rewrite, proofread, describe-image), not open-ended persona chat, and raw AICore prompt access has historically been Pixel-first; unconfirmed whether it's available on a Samsung device at all. |
| **llama.cpp via JNI, self-built** | Fallback only if MediaPipe's model selection turns out too limited for the persona — more control, more integration work (own JNI bridge, own GGUF model management), no official support. |

**Recommendation: MediaPipe LLM Inference API**, same posture as the original
plan's Phase 0 — resolve it from Maven, don't vendor/build anything.

### 10.2 Model choice

Quantized small instruction-tuned model, run fully on-device:

- **Gemma 3 1B-it** (int4/int8 `.task` bundle, roughly 0.5–1.5 GB depending on
  quantization) — first choice: smallest, fastest, and a 1B model is plenty
  for short in-character replies to a kid.
- **Gemma 2 2B-it** as a fallback if 1B's replies feel too shallow/repetitive
  in practice — larger, slower, still comfortably inside 12 GB alongside the
  ~760 MB STT+TTS footprint already resident.

Needs an actual benchmark on the S26 Ultra (tokens/sec, load time, peak RSS
with STT+TTS+LLM all resident) before locking in — this is Phase A below, not
an assumption.

### 10.3 Where it slots into the existing architecture

```
ChatState.send(userText)        // today: synchronous, calls engine.respond()
        │
        ▼ (proposed)
SpideyResponder.respond(userText, history): Reply   // new interface, suspend
        │
   ┌────┴─────────────┐
   │                  │
ElizaResponder    LlmResponder      // ElizaEngine wrapped unchanged;
(existing engine,  (new: MediaPipe   new LLM path added
 unchanged)         LlmInference)
```

- `ElizaEngine`'s farewell/off-limits **guards stay first and stay
  deterministic**, ahead of any LLM call — the original plan's safety
  argument ("kid-safe by construction, cheap since there's no LLM to
  jailbreak") no longer holds once an LLM is in the loop, so that guarantee
  has to be re-earned with a system prompt + output validation, not assumed.
- `ChatState.send()` becomes `suspend` (it currently is not — `TurnController`
  already awaits it in a coroutine, so callers don't change shape, but the
  typed-input debug path in `CallActivity` will need a `coroutineScope`/
  `launch` wrapper it doesn't need today).
- Recommend **hybrid, not full replace**: if the LLM fails to load, times
  out, or produces output that fails validation (empty, over the word/time
  budget, trips a banned-word check), fall back to `ElizaEngine.respond()`
  for that turn rather than surfacing an error to a kid mid-call. This also
  keeps the fully-tested ELIZA path as a real safety net, not dead code.

### 10.4 New model bootstrap

The Gemma `.task` bundle is a large file (hundreds of MB to ~1.5 GB) that
needs the same treatment as the STT/TTS models: download-once,
foreground-service-protected (reuse the existing `ModelDownloadWorker` /
`downloadSpeechModels()` pattern from the 2026-08-20 background-download
bugfix — same failure mode applies here, arguably worse given the larger
file), cached under app-private storage, validated before use. **Confirmed,
not optional**: Google's own guide states the model "is too large to be
bundled in an APK" and must be hosted and downloaded at runtime — bundling it
as an asset (the way `am_puck.bin` etc. are shipped) is not an available
shortcut here.

### 10.5 Prompting harness

System prompt carries the persona + constraints ELIZA currently gets for
free from hand-authored `spidey_eliza.json` lines: stay in-character as
Spider-Man talking to a kid, keep replies short (aim for one sentence),
never discuss off-limits topics, no advice-giving beyond the character's
voice. Needs real iteration against actual model output, not a one-shot
guess — this is the part most likely to need several rounds of on-device
listening, same spirit as the original Phase 5 "voice A/B, decide by ear."

### 10.6 Open decision: keep the 9-word TTS budget, or drop it?

`TtsModel.KOKORO_SHORT_TURN` (the model already in use) is built for ≤~2.5s
utterances; ELIZA's reassemblies are hand-capped at 9 words to fit it. An LLM
won't naturally respect that cap. Two options, **user decision needed before
Phase D**:

1. Keep the cap: constrain the LLM's `maxTokens` and prompt it to be brief,
   truncate/regenerate on overrun. Simpler, no TTS-side change, but caps how
   natural the replies can get — somewhat fights the reason for adding an
   LLM at all.
2. Switch to the SDK's standard (non-short-turn) Kokoro graph: removes the
   length ceiling, but changes TTS latency/RTF characteristics that were
   tuned against short-turn — would need its own re-verification pass on the
   S26 Ultra, not just assumed to still work.

### 10.7 Testing strategy change

`ElizaEngineTest`'s exact-string assertions don't transfer to an LLM path —
non-deterministic output can't be tested that way. Plan: keep `ElizaEngine`'s
existing tests untouched (still real, still the fallback path), and test
`LlmResponder` structurally instead — non-blank, within the chosen word/time
budget, doesn't contain banned-topic words, via a handful of real on-device
prompts rather than exact-match JVM tests (the model can't run in a plain JVM
unit test the way `ElizaEngine` can).

### 10.8 Phases

| # | Phase | Deliverable | DONE-WHEN |
|---|---|---|---|
| A | Bench | Standalone LiteRT-LM + Gemma 3 1B `.litertlm`, no app wiring yet | **DONE 2026-08-21, see §10.11** — measured tokens/sec, load time, memory on the S26 Ultra; go: proceed to Phase B |
| B | Model bootstrap | `.task` download via the `ModelDownloadWorker` pattern, foreground-protected | Fresh install survives backgrounding mid-download (same check as the 2026-08-20 STT/TTS fix) |
| C | `SpideyResponder` abstraction | **DONE 2026-08-21** — Interface + `ElizaResponder` (wraps existing engine, no behavior change) + `LlmResponder` skeleton; `ChatState.send()` becomes suspend | `./gradlew :app:testDebugUnitTest` green (37/37), `ElizaEngineTest` unchanged and untouched (tests `ElizaEngine` directly, never went through `ChatState`) |
| D | Prompting harness + validation + fallback | **Mechanism DONE 2026-08-21, see §10.13** — system prompt, maxTokens/truncation per §10.6's decision, banned-word check, fallback-to-Eliza on failure | 20 real spoken turns on the S26 Ultra: in-character, kid-safe, no crash-to-Eliza-fallback needed for a well-formed input — **not done, needs live human speech, see §10.13** |
| E | Latency | Sentence-chunked streaming from `LlmInference`'s token callback into TTS (mirrors the existing STT/TTS producer-consumer split) | Latency HUD (already exists) shows LLM-path total turn time; judged acceptable by ear, not just the number |
| F | Sign-off | Full call flow on the S26 Ultra, hybrid fallback exercised at least once deliberately (e.g. airplane mode mid-call) | Cold launch → call → several natural-feeling turns → hang up, no regression in existing `CallFlowTest`/`TtsPlaybackTest`/`SpideyVoiceTest` |

### 10.9 Risks

| Risk | Signal | Mitigation |
|---|---|---|
| STT+TTS+LLM concurrent RAM exceeds comfortable headroom | OOM kill, or measured RSS close to 12 GB in Phase A | Drop to Gemma 1B int4; consider unloading STT/TTS pipeline between turns (bigger architecture change, only if needed) |
| LLM decode latency ruins the call's back-and-forth feel | Phase A tokens/sec too low | Sentence-chunked streaming (§10.8 Phase E); smaller model; shorter maxTokens |
| LLM breaks character / says something not kid-appropriate | Phase D real-turn testing | System prompt iteration + banned-word output check + hybrid fallback to ELIZA (§10.3) |
| Output length blows the Kokoro short-turn budget | Clipped/truncated audio | §10.6 decision, made explicitly before Phase D, not discovered by accident |
| MediaPipe's Maven artifact/version diverges from what this plan assumes | Compile error, missing API | Verify against Maven Central at Phase A start, same diffing discipline as §1 |

### 10.10 Decisions (user, 2026-08-21)

1. **Model size**: start with **Gemma 3 1B**.
2. **Hybrid vs. full replace**: **hybrid** — `ElizaEngine` stays as the
   deterministic fallback/safety net (§10.3), not retired.
3. **§10.6's TTS budget**: **keep the ≤9-word `KOKORO_SHORT_TURN` cap** —
   constrain the LLM via `maxTokens`/prompt brevity + truncate-on-overrun,
   no TTS-side change.

All three unblock Phase A.

### 10.11 Phase A results (measured on the S26 Ultra, 2026-08-21) — GO

`app/src/androidTest/kotlin/dev/nate/spideycall/llm/LlmBenchTest.kt`, using
LiteRT-LM's own `benchmark()` helper against
`litert-community/Gemma3-1B-IT`'s generic `gemma3-1b-it-int4.litertlm`
(584 MB, pushed to `/data/local/tmp/llm/` — too large to bundle in the APK,
confirmed by Google's own docs, see §10.4). Three separate CPU runs, all
close enough to call the numbers real rather than noise:

| Run | Engine init | Time to first token | Prefill | Decode | Process PSS delta |
|---|---|---|---|---|---|
| 1st (cold) | 30.95 s | 2.01 s | 33.6 tok/s | 9.08 tok/s | +166.9 MB |
| 2nd (same process, model file OS-cache-warm) | 28.30 s | 1.93 s | 35.1 tok/s | 9.30 tok/s | +166.4 MB |
| 3rd (separate test run) | 27.19–27.26 s | 1.85–1.93 s | 35.1–36.8 tok/s | 9.18–9.36 tok/s | +164.0 MB |

**GPU backend (`Backend.GPU()`) failed outright** — `LiteRtLmJniException:
Failed to create engine`, deep in
`llm_litert_compiled_model_executor_factory.cc`. The generic int4 file is
most likely CPU-only; GPU/NPU probably needs one of the SoC-specific
`.litertlm` builds the same HF repo ships (`sm8550`/`sm8650`/`sm8750`/
`sm8850` — chip-codename-tagged, unconfirmed which matches the S26 Ultra's
Snapdragon). **Not pursued further** — CPU already gives a usable number
(see below); revisit only if decode speed ever becomes the bottleneck.

**Key finding, changes the architecture (§10.3) from an assumption to a
confirmed requirement:** the ~27–31 s cost is the native engine's own
init — re-running in the *same process* right after (OS page cache warm)
barely moved the number (30.95 s → 28.30 s), ruling out "one-time disk
read" as the explanation. **The `Engine` must be created once and stay
resident — never recreated per call, let alone per turn.** This is far
too long to hide behind the existing ~2–3 s Dialing/Ringing window the way
STT/TTS warm-up currently is (§6) — Phase C/D needs a real answer for
*when* that one-time cost gets paid (candidates: at app process start,
before the user ever taps Call; or on first Call tap with an extended
loading state; not decided here, needs its own design pass in Phase C).

**Everything else is a clean go:**
- **9.1–9.4 tok/s decode** is plenty for a ~9-word reply (≈12–16 tokens,
  so ~1.3–1.8 s decode) — comparable to the app's existing STT/TTS turn
  latencies, not a new bottleneck once the engine is already warm.
- **~164–167 MB memory delta** (total process PSS ~254 MB with the model
  and a short conversation loaded) is a non-issue against the S26 Ultra's
  12 GB, even stacked with the STT+TTS bundle's own ~760 MB — no combined
  STT+TTS+LLM residency test was needed to reach that conclusion given how
  small this number is.
- Manifest changes made permanent for this: two `<uses-native-library
  .../>` entries (`libvndksupport.so`, `libOpenCL.so`, both
  `required="false"`) in `AndroidManifest.xml`, needed for the GPU
  backend attempt above and harmless to leave in for a future retry.

### 10.12 Engine-warm-timing wiring (2026-08-21) — done for the timing mechanism, response wiring still separate

Follow-up finding that **refines §10.11's ~27-31s number**: a staged-timing
test (`LlmBenchTest.engineInit_vs_firstConversationTurn_vs_secondConversationTurn`)
isolated what `benchmark()`'s single number was bundling together. One
`Engine`, two `Conversation`s, timed separately:

| Stage | Time |
|---|---|
| `engine.initialize()` | 6.4-13.8s (varies by run) |
| `createConversation()` | ~0-9ms — free |
| **First-ever** `sendMessage()` on a fresh conversation | **~4.8s extra** on top of init |
| Second `sendMessage()`, same conversation | ~732ms (steady state) |
| First `sendMessage()` on a **second, brand-new conversation**, same already-used engine | **~876ms** — fast, not ~4.8s |

**The ~4-5s "first turn" cost (likely one-time delegate/graph compilation)
is paid once per `Engine`, not once per `Conversation`/call.** This matters:
warming only `Engine.initialize()` would have left every real phone call's
*first message* eating a surprise ~5s delay even with the engine
"ready" — defeating the point of warming at all.

**Wired up** (docs/PLAN.md §10 decision from the engine-warm-timing
discussion: eager warm-up at Idle, staggered before STT/TTS, in a
process-scoped singleton — option 3):
- `llm/LlmEngine.kt` — a plain Kotlin `object` (not an `Application`
  subclass — nothing else in this codebase uses one, and a singleton gets
  process-scoping for free). `ensureReady()` builds the engine **and**
  pre-pays the first-turn cost with one throwaway conversation+message
  before returning, so every *real* conversation created afterward starts
  fast. Idempotent via a `Mutex` + nullable-engine double-check (same shape
  as `VoiceSession.speakMutex`). Runs on its own `CoroutineScope`
  (`SupervisorJob` + `Dispatchers.Default`), deliberately not a caller's
  `lifecycleScope` — the whole point is surviving Activity recreation
  (rotation, dark/light toggle, DeX/split-screen on this device), which
  would otherwise cancel and waste an in-progress warm-up.
- `CallActivity.onCreate()` calls `LlmEngine.warmUpAsync()` (fire-and-forget)
  immediately, before `VoiceSession`'s STT/TTS load (which only starts once
  Call is tapped) — gives the LLM a head start rather than contending with
  STT/TTS for CPU during Dialing/Ringing.
- **Not yet wired**: nothing calls `LlmEngine.ensureReady()` to actually
  *use* the warm engine for a reply — `ChatState`/`TurnController` still
  only use `ElizaEngine`. That's Phase C/D (the `SpideyResponder`
  abstraction, §10.3), a separate, larger piece of work not started here.

Verified: `./gradlew :app:assembleDebug :app:testDebugUnitTest` -> BUILD
SUCCESSFUL (37/37 JVM, unchanged). Verified (S26 Ultra, fresh install):
Idle screen (`call-button`) renders and `topResumedActivity` reports
resumed immediately after launch — warm-up does not block the UI thread
(dispatched on `Dispatchers.Default`, not main). Full logcat for the
launch confirms the staged sequence with no errors: `cold-starting LiteRT-LM
engine…` fires essentially at launch, `engine.initialize() done in 6419ms`,
`throwaway warm-up turn done in 2860ms`, `engine fully ready in 9279ms
total`. Existing `CallFlowTest`/`TtsPlaybackTest`/`SpideyVoiceTest` not
re-run this pass (no code path they exercise changed — `ChatState`/
`TurnController`/`VoiceSession` are untouched); worth a full pass before
Phase C ships anything user-visible.

### 10.13 Phase D: prompting harness, validation, fallback (2026-08-21)

**Mechanism built and verified; the actual "is it good, is it kid-safe in
practice" judgment still needs a human on a live call — see the open item
at the end of this section.**

Upgraded `llm/LlmResponder.kt` from Phase C's skeleton into the real Phase D
harness:
- `SYSTEM_PROMPT` (constant, plain string, passed as `ConversationConfig`'s
  `systemInstruction`): the persona + kid-safety + brevity instructions
  ELIZA got for free from hand-authored script lines — Spidey persona,
  "one short sentence, ideally under 9 words," steer away from anything
  scary/violent/inappropriate rather than refuse outright, no real-world
  advice-giving.
- `MAX_OUTPUT_TOKEN = 32` on `ConversationConfig` bounds decode length
  technically (secondary defense + keeps latency down); the real guarantee
  is `validateAndTruncate()`'s hard word-count cut to `MAX_REPLY_WORDS = 9`
  (§10.6's decision: keep the existing Kokoro short-turn TTS budget, don't
  touch the TTS graph).
- `validateAndTruncate()` also rejects blank output and anything tripping
  the same `ElizaEngine.OFF_LIMITS_TRIGGERS` word list user input is
  checked against, by throwing `UnsafeLlmOutputException`. Pulled out as an
  `internal` companion function specifically so it's unit-testable (JVM,
  no device) independent of the real engine — `LlmResponderTest` covers
  blank/off-limits/pass-through/truncation directly.

New `HybridResponder.kt` (top-level `dev.nate.spideycall` package, not
`llm/`, since it composes both worlds): farewell/off-limits detection on
the **user's input** runs first, using the exact same word lists and
word-boundary matching `ElizaEngine.respond()`'s own guards use, so those
two categories never reach the LLM at all — re-earning the original plan's
"kid-safe by construction, cheap since there's no LLM to jailbreak"
argument for at least these cases, per §10.3. Any other turn tries the LLM;
`CancellationException` is explicitly rethrown (never swallowed — would
break structured concurrency for the caller), any other exception
(technical failure *or* `UnsafeLlmOutputException`) falls back to Eliza for
that turn. `SpideyResponder` itself grew a default no-op `close()` so
`HybridResponder`/`LlmResponder`/`ElizaResponder` are uniformly closeable
without narrowing constructor parameter types to concrete classes (an
early version of this file typed `HybridResponder`'s constructor to the
concrete `LlmResponder`, which made it impossible to unit-test with a
fake — caught and fixed before it shipped, see `HybridResponderTest`, which
exercises guard-bypass-of-LLM, LLM-success-passthrough,
LLM-failure-fallback, and cancellation-is-not-swallowed all against fakes,
no device needed).

**Wired into the live app** (unlike Phase C's `LlmResponder`, which was
built but deliberately left unused): `CallActivity.beginCall()` now
constructs `HybridResponder(LlmResponder(), ElizaResponder(ElizaEngine(script)))`
for every real call. Held in a new `currentResponder: SpideyResponder?`
field (separate from `ChatState`, which doesn't need to know about closing
it) so `hangUp()`/`onDestroy()` can call `.close()` — releases the
`LlmResponder`'s per-call `Conversation` (a real native resource) so it
doesn't leak across calls, since a fresh `HybridResponder`/`LlmResponder`
is built on every `beginCall()`.

Verified:
- `./gradlew :app:testDebugUnitTest` -> BUILD SUCCESSFUL, 47/47 (was 37;
  +4 `LlmResponderTest`, +6 `HybridResponderTest`).
- `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` -> BUILD
  SUCCESSFUL.
- New `LlmResponderDeviceTest` (S26 Ultra, real model): asked
  "describe every one of your superpowers in as much detail as you possibly
  can" specifically to provoke a long answer and confirm truncation
  actually engages against real output, not just the pure-logic test's
  synthetic strings. Real reply, truncated: **`"Okay! Let's talk about me!
  You're asking about my"`** (exactly 9 words) — confirms the harness holds
  up end-to-end against the real model.

**Real finding worth acting on before calling the persona "good," not a
bug**: that truncated reply cuts off mid-sentence. Hard word-count
truncation was always going to do this sometimes — §10.6 chose it anyway
over touching the TTS graph, but hearing it land mid-clause is a genuine
data point for tuning, not just a hypothetical risk anymore. Options for a
future pass, not decided now: tighten the system prompt's brevity
instruction further, lower `MAX_OUTPUT_TOKEN` so the model's own sentence
end lands before the word cap more often, or accept it as a rare,
tolerable rough edge.

**Open item — Phase D's own DONE-WHEN, not done**: "20 real spoken turns
on the S26 Ultra: in-character, kid-safe" needs an actual human voice
conversation through the real call flow (dial -> ring -> connect -> talk),
the same category of live-audio judgment already deferred at Phase 3's
speak-5-lines check and Phase 5's voice A/B. The mechanism is proven; a
human still needs to place a real call and listen.

### 10.14 GPU backend actually works — root-caused, not just retried (2026-08-21)

§10.11's plain `Backend.GPU()` attempt failed outright and wasn't chased
further at the time (hypothesized as "the generic int4 file is probably
CPU-only, GPU probably needs a chip-specific `.litertlm` variant" —
reasonable at the time, **wrong**, confirmed by actually reading the full
logcat instead of stopping at the top-level exception).

**Real root cause**, read straight from logcat: `LITERT_CL` delegates the
*entire* model (`Replacing 1330 out of 1330 node(s) with delegate
(LITERT_CL)`) — the generic file is fully GPU-compatible. It then fails one
step later:

```
Failed to create litert::ml_drift::DelegateKernelLiteRt: INTERNAL: Could not
open file ('/data/local/tmp/llm/..._mldrift_weight_cache.bin'): Permission denied
```

`EngineConfig.cacheDir` defaults to the model's own directory when unset.
`/data/local/tmp` looks world-writable via plain Unix permissions
(`drwxrwxrwx`) but Android's SELinux app-sandbox policy blocks app-process
*writes* there regardless — reads (all the CPU path ever needed) are fine,
which is exactly why this went unnoticed until GPU tried to write a cache
file next to the model.

**Fix**: pass `cacheDir = context.cacheDir.absolutePath` (the app's own
writable storage) in `EngineConfig`. Confirmed directly
(`LlmBenchTest.gpuBackendWorksWithAWritableCacheDir`) — this doesn't need
Phase B's real download bootstrap to be moot in practice, either: once
models live in app-private storage instead of the dev-only
`/data/local/tmp` stand-in, this whole class of problem disappears on its
own, since app-private storage is always writable by the app that owns it.

**Also worth knowing, in case it matters to the S26 Ultra specifically**:
`adb shell getprop ro.soc.model` reports **SM8850** (Adreno GPU,
`ro.hardware.egl`/`ro.hardware.vulkan` both confirm `adreno`) — matches one
of the chip-tagged `.litertlm` variants noted in §10.1 as unconfirmed at
the time. Didn't end up needing that variant — the generic file works fine
on GPU once the cache-dir bug is out of the way — but the SoC identity is
now a confirmed fact rather than a guess, worth having if a chip-specific
build is ever worth revisiting for further gains.

**Performance, GPU (with the cache-dir fix) vs CPU, same device, same
model** — `LlmBenchTest.gpuEngineInit_vs_firstConversationTurn_vs_secondConversationTurn`,
same isolation methodology as §10.12's CPU staging:

| Stage | CPU | GPU |
|---|---|---|
| `engine.initialize()` | 6.4-13.8s | ~5.5s |
| First-ever turn (one-time compile premium) | +4.8s | +910ms |
| Steady-state turn (same conversation) | ~730ms | ~620ms |
| First turn, new conversation, warm engine | ~880ms | ~520ms |
| `benchmark()`'s prefill rate | ~35 tok/s | **~986 tok/s** |
| `benchmark()`'s decode rate | ~9.3 tok/s | **~30 tok/s** |

The one-time first-turn premium is still there on GPU (same story as CPU:
paid once per `Engine`, not per `Conversation`) — just much smaller (910ms
vs 4.8s), so `LlmEngine`'s existing pre-pay-with-a-throwaway-turn design
still applies unchanged, it's just faster now.

**Wired into production**: `LlmEngine.buildEngine()` tries `Backend.GPU()`
with the app's cache dir first, falls back to `Backend.CPU()` (logged, not
fatal) if GPU init throws for any reason — covers a future device without
this one's GPU/driver support. Required threading a `Context` through:
`LlmEngine.warmUpAsync(context)`/`ensureReady(context)` (application
context only — never held past resolving the cache-dir path, never a live
`Context` reference sitting in the singleton) and `LlmResponder(context)`
(constructor, was no-arg). Reference sweep: `CallActivity.beginCall()`
(`LlmResponder(applicationContext)`), `CallActivity.onCreate()`
(`LlmEngine.warmUpAsync(applicationContext)`), `LlmResponderDeviceTest`
(instrumentation target context). JVM tests (`LlmResponderTest`,
`HybridResponderTest`) untouched — neither constructs a real `LlmResponder`.

Verified: `./gradlew :app:testDebugUnitTest` -> 51/51 unchanged.
`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` -> BUILD
SUCCESSFUL. `LlmResponderDeviceTest` (real production path, not just the
isolated bench): logcat confirms `GPU backend initialized`, full warm-up
7.03s, real GPU-generated reply correctly truncated to the 15-word cap:
`"Okay! Let's talk! You're asking about my powers? Well... I can stretch my
webbing! It's"`.
