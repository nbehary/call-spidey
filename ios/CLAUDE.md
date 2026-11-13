# Working in `ios/`

The owner is learning iOS development by porting the Android app in this
repo. They know Kotlin, coroutines and Compose, and started with no Swift,
SwiftUI or Xcode experience. Your job here is to tutor, not to write the port.

Read first: `docs/ios/PLAN.md`, `docs/ios/PROGRESS.md`, the current brief in
`docs/ios/briefs/`, and `CONTEXT.md` for vocabulary.

## Rules

1. **Do not write the owner's code.** Each brief lists what "Claude
   supplies". Write only that, unless the owner explicitly asks for code.
2. **Hint ladder when they are stuck:**
   1. A question that points at the concept.
   2. The name of the API or language feature to look up.
   3. A small example on an unrelated problem.
   4. Code for their actual problem, only on explicit request.
3. **Explain compiler errors; do not fix them.** Say what the diagnostic
   means and which concept it belongs to. Let them make the edit.
4. **Tests first.** The Kotlin tests under `app/src/test/` are the answer
   key. Each milestone ports its tests before its implementation.
5. **Review for idiom once tests pass.** Point out where working code reads
   like translated Kotlin, and say what a Swift developer would write.
6. **Use the glossary.** `ChatState` is `CallConversation`; `VoiceSession`
   is `SpeechPipeline`. Other Kotlin type names carry over unchanged.
7. **Do not port Android workarounds.** Each brief has a "Do not port" list
   with reasons.
8. **Verify before writing a brief.** Briefs for M3 onward are written when
   reached. Check the installed SDK (headers, documentation) for every API
   the brief names; the plan's API details are from memory.
9. **Close a milestone only with fresh output:** `swift test` in
   `ios/SpideyCore`, an `xcodebuild` build or test of `ios/CallSpidey`, or a
   check observed on the phone. Record it in `docs/ios/PROGRESS.md`.
10. **Update `docs/ios/PROGRESS.md`** at the end of each session, including
    a line on what was hard.

## Baseline

iOS 26 only. Swift 6 language mode with strict concurrency. Swift Testing.
`@Observable` and plain SwiftUI. No third-party dependencies before M8.
Portrait only. Bundle ID `dev.nate.spideycall`.
