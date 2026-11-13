# Call Spidey for iOS — port plan and curriculum

Written 2026-11-13. This plan ports the Android app in this repo to iOS, and
is ordered to teach iOS development to someone who knows Kotlin, coroutines
and Compose but has never used Swift, SwiftUI or Xcode.

Companion files:

- `CONTEXT.md` (repo root): the shared vocabulary. Use its terms.
- `docs/ios/briefs/M<n>.md`: one brief per milestone, written just in time.
- `docs/ios/PROGRESS.md`: what is done, what was hard, decisions made.
- `ios/CLAUDE.md`: tutoring rules for any Claude session working in `ios/`.
- `docs/adr/0001-ios-native-first-engines.md`: why the engines are
  Apple-native first.
- `docs/PLAN.md`, `docs/STATE.md`, `docs/HANDOFF.md`: the Android side.

## 1. Constraints and decisions

Carried over from Android (`docs/STATE.md` `## Constraints`):

- Personal use only; never distributed.
- Fully on-device once any model is downloaded.
- The Greeting is "Hi, I'm Spidey! What is your name?".
- The scripted (Eliza) Responder is never retired. It is the Safety net.

Decided for the port (2026-11-13):

| Decision | Choice | Reason |
|---|---|---|
| Purpose | Learning first, working app second | The owner wants to learn iOS development through this port. |
| Engines | Apple-native first; Kokoro and the Spidey Voice as the capstone | Native frameworks are what transfers. Only TTS loses anything, and it is swapped last behind a protocol. |
| Who writes | The owner writes; Claude tutors and supplies listed mechanical parts | See section 5. |
| Done means | Behavioural parity on the call flow, not line-by-line parity | Much Android code is a workaround for an Android problem. |
| Layout | `ios/` in this repo; Android stays at the root | Kotlin sits beside the Swift it is ported to. |
| Hardware | Intel Mac (macOS 26), iPhone 15 Pro (iOS 26) | ML and audio are tested on the phone only. |
| Deployment target | iOS 26 only | One phone; the native speech and LLM APIs need it. |
| Language mode | Swift 6, strict concurrency on | The Android app's worst bugs were data races. |
| Tests | Swift Testing | Current default; reads like JUnit. |
| UI state | `@Observable` and plain SwiftUI | Closest to Compose state. |
| Dependencies | None until milestone 8 | Learn the platform first. |
| Signing | Free Apple ID | Re-run from Xcode every 7 days. |
| Orientation | Portrait only | Rotation survival was an Android problem. |
| Bundle ID | `dev.nate.spideycall` | Matches Android. |

Swift names follow the Kotlin names where the concept is unchanged. Two
differ, per `CONTEXT.md`: `ChatState` becomes `CallConversation`, and
`VoiceSession` becomes `SpeechPipeline`.

## 2. Scope

| Android feature | iOS |
|---|---|
| Dial, ring, connect, talk, hang up | In (M3, M4) |
| Eliza engine and script | In (M1) |
| Conversation and Intro, turn-taking | In (M2, M5) |
| Typed Utterances (debug path) | In, early (M3) |
| Speech-to-text | In, Apple's on-device recogniser (M5) |
| Hybrid LLM Responder with Safety net | In, Apple's Foundation Models (M6) |
| Text-to-speech | In: system voice (M4), then Kokoro (M8) |
| Hidden voice-test screen | Capstone only (M8) |
| Model download | Capstone only (M8) |
| Pronunciation patches | Capstone only, re-derived by ear (M8) |
| Rotation survival | Out |
| Telephone EQ, barge-in, beam search | Out (never built on Android) |
| CallKit or lock-screen call look | Out |

## 3. Architecture

```
ios/
├── SpideyCore/                  Swift package. No UI, audio or ML imports.
│   ├── Package.swift
│   ├── Sources/SpideyCore/
│   │   ├── Eliza/               Normalizer, ElizaScript, ElizaEngine
│   │   ├── Resources/           spidey_eliza.json (copy of the Android asset)
│   │   └── ...                  SpideyResponder, ElizaResponder,
│   │                            CallConversation, HybridResponder,
│   │                            TurnController
│   └── Tests/SpideyCoreTests/   The Kotlin JVM tests, ported.
└── CallSpidey/                  Xcode app project. Depends on SpideyCore.
                                 SwiftUI views, call phases, tones,
                                 SpeechPipeline, speech and LLM adapters.
```

The split mirrors the Android one: `SpideyCore` is exactly the code the JVM
tests cover today. It runs under `swift test` on the Mac with no simulator
and no signing.

Three protocols are the seams where engines get swapped:

| Seam | M-early implementation | Later implementation |
|---|---|---|
| Responder (`SpideyResponder`) | Eliza (M2) | Hybrid of Foundation Models and Eliza (M6) |
| Speaker (text to audio) | `AVSpeechSynthesizer` (M4) | Kokoro with the Spidey Voice (M8) |
| Listener (audio to Utterances) | Typed text (M3) | Apple speech recogniser (M5) |

`spidey_eliza.json` is duplicated between `app/src/main/assets/` and the
Swift package. If the script is edited, edit both. A sync check is deferred
until drift actually happens.

## 4. Milestones

Each ends with something that runs. Briefs for M0 to M2 exist now. M3 to M8
are outlines here; each gets a full brief when reached, written on the Mac
after checking the real SDK.

| # | Milestone | New concepts | Runs on | Android tests ported |
|---|---|---|---|---|
| 0 | Orientation | Xcode, targets, schemes, signing, Swift packages | iPhone | none |
| 1 | Swift via Eliza | structs, optionals, `Codable`, Regex, Swift Testing | Mac | Normalizer 7, ElizaScript 6, ElizaEngine 10 |
| 2 | Conversation core | protocols, `async`/`await`, `@Observable`, `@MainActor` | Mac | ChatState 8 |
| 3 | Text call | SwiftUI views and state, enums, bundle resources | Simulator | none (UI) |
| 4 | Spidey speaks | `AVAudioSession`, delegates bridged to async | iPhone | none |
| 5 | Spidey listens | permissions, `AsyncSequence`, actors, fakes | iPhone | TurnController 8 |
| 6 | Spidey thinks | Foundation Models, `throws`, cancellation | iPhone | HybridResponder 6, LlmResponder 4 |
| 7 | Polish | Instruments, app icon | iPhone | none |
| 8 | Capstone: the Spidey Voice | packages, ML runtime, downloads, PCM streaming | iPhone | pronunciation tests, re-derived |

### M3. Text call (outline)

- **Goal:** a SwiftUI app that walks the Call phases and holds a full Eliza
  conversation by keyboard, showing the Transcript.
- **Kotlin to read:** `CallActivity.kt` (the `CallPhase` enum and the
  Compose tree only).
- **Do not port:** the Activity lifecycle, `configChanges`, permission
  plumbing, anything touching `VoiceSession`.
- **Accept:** in the Simulator: tap Call Spidey, see dialing then ringing
  then connected, type a name, get "Hi, <name>!", say "bye", see the Call end.

### M4. Spidey speaks (outline)

- **Goal:** the phone rings, then Spidey speaks the Greeting and each Reply
  with a system voice. Define the Speaker protocol here.
- **Kotlin to read:** `audio/CallTones.kt`, `VoiceSession.speak()`.
- **Do not port:** `StreamingPcmPlayer` (raw PCM arrives in M8),
  `speakMutex` (it guarded a native use-after-free that does not exist here).
- **Risk:** audio session category and the silent switch. Test on the phone.
- **Accept:** a typed conversation where every Reply is heard, and hang-up
  stops speech at once.

### M5. Spidey listens (outline)

- **Goal:** spoken Utterances drive Turns. The mic is muted while Spidey
  speaks. `TurnController` ported with its tests against a fake.
- **Kotlin to read:** `TurnController.kt`, `VoiceSession.kt`,
  `TurnControllerTest.kt`.
- **Must port:** the blank-result rule. An empty recognition is not an
  Utterance, and listening must resume (the 2026-08-22 "mic goes deaf" bug).
- **Risk:** the hardest milestone. Strict concurrency meets audio callbacks.
  The brief opens with a concept section on actors and `Sendable`.
- **To verify on the Mac first:** the exact iOS 26 speech API surface.
- **Accept:** a five-Turn spoken Call with one deliberate mumble in the
  middle; the mic keeps working.

### M6. Spidey thinks (outline)

- **Goal:** `LlmResponder` on Apple's on-device model, wrapped by
  `HybridResponder`. Safety net behaviour identical to Android.
- **Kotlin to read:** `llm/LlmResponder.kt`, `HybridResponder.kt`, and both
  test files.
- **Must port:** the pre-check of farewell and off-limits Utterances before
  the model is touched; fallback to Eliza on any failure or unsuitable
  output; the system prompt's persona and brevity rules.
- **Do not port:** `LlmEngine` (warm singleton, GPU to CPU fallback, model
  path). Decide by ear whether the 25-word cap is still needed.
- **Fallback if the framework is unavailable** (M0 checks this): stay
  Eliza-only until M8, or open the parity route as its own decision.
- **Accept:** the ported tests pass; a spoken Call gets non-scripted
  Replies; "bye" still ends the Call with the model never consulted.

### M7. Polish (outline)

- **Goal:** the Android Phase D check finally done on a device: 20 real
  spoken Turns, all in character and kid-safe. Latency measured per Turn.
- **Accept:** the 20-Turn log is recorded in `PROGRESS.md`.

### M8. Capstone: the Spidey Voice (outline, needs its own planning session)

- **Goal:** Spidey speaks in `spidey_v3_blend` through Kokoro on the phone,
  behind the Speaker protocol from M4.
- **Open decision:** which Kokoro runtime. Choose when M8 starts; the
  options change quickly. Candidates to evaluate then: a Core ML or MLX
  port, an ONNX Runtime build, or the Swift sibling of the Android speech
  SDK if one exists.
- **To verify first:** that the 256-float style vector used on Android is
  accepted by the chosen runtime, and how that runtime phonemises. The
  Android pronunciation fixes patch one specific phonemiser's dictionary and
  may not apply.
- **Also here:** model download, the hidden voice-test screen, pitch and
  speed at playback.

## 5. How we work

Mixed mode, weighted towards the owner typing.

**The owner writes:** all of `SpideyCore`, all SwiftUI, the concurrency in
`SpeechPipeline`, audio session and permission handling.

**Claude supplies (and labels as supplied):** `Package.swift` and Xcode
project fixes, the second and later `Codable` structs once the first is
written, signing troubleshooting, and runtime integration scaffolding in M8.

**Rules for every milestone:**

1. Briefs contain no solution code.
2. Port the tests first, watch them fail, then write Swift until they pass.
3. Hint ladder when stuck: a question about the concept; then the name of
   the API or language feature; then a small example on an unrelated
   problem. Code for the actual problem only on explicit request.
4. When the tests pass, Claude reviews for Swift idiom.
5. A milestone is closed only with fresh command output: `swift test` for
   `SpideyCore`, an `xcodebuild` build or test for the app, or an observed
   check on the phone recorded in `PROGRESS.md`.

**Two machines.** Swift is written on the Mac, with Claude Code running
there. The Linux box holds the Android build and the Python voice tooling.
Both work from this repo.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Xcode on the Intel Mac lacks an iOS 26 feature | M0 checks the SDK and Foundation Models availability before anything depends on them. |
| Intel Simulator misleads on audio and ML | Simulator is used for layout and typed Calls only. |
| Strict concurrency stalls M5 | `async`/`await` introduced in M2 in pure code; M5 opens with a concept section. |
| Free signing expires weekly | Re-run from Xcode; upgrade only if it becomes a nuisance. |
| System voice is not Spidey | Accepted until M8. |
| Eliza script drifts between platforms | Edit both copies; add a sync check if it happens. |
| Details of iOS 26 APIs in this plan are from memory | M3+ briefs are written on the Mac against the installed SDK. |

## 7. Open questions

None block M0 to M2.

1. Kokoro runtime for M8.
2. Whether the 25-word Reply cap survives on iOS (M6, by ear).
3. Whether "Spidey" needs a pronunciation fix in the system voice (M4, by ear).
