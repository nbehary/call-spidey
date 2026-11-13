# iOS port — progress

Live state of the port. Update at the end of every working session, on
either machine. The plan is `docs/ios/PLAN.md`; briefs are in
`docs/ios/briefs/`.

## Now

Plan written 2026-11-13. No Swift exists yet. Next action: M0, on the Mac.

## Picking this up on the Mac

The plan was made in one session on the Linux box. That session's
conversation does not travel; everything it decided is in the files below.

1. Clone `https://github.com/nbehary/call-spidey.git` and check out the
   `ios-port` branch. All iOS work happens on that branch.
2. Start Claude Code in the repo. Read, in this order: `ios/CLAUDE.md`,
   this file, `docs/ios/PLAN.md`, `CONTEXT.md`, then
   `docs/ios/briefs/M0.md`.
3. Begin M0. The owner does the steps; Claude tutors per `ios/CLAUDE.md`.

What is in the repo and needed later:

- The Kotlin source and tests being ported: `app/src/main/kotlin/` and
  `app/src/test/kotlin/`.
- The Eliza script: `app/src/main/assets/spidey_eliza.json`.
- The Voice candidates for M8, including the current default
  `spidey_v3_blend.bin`: `app/src/main/assets/voices/candidates/`.

What exists only on the Linux box (none of it is needed before M8):

- The Android SDK, devices and emulator.
- The Python environment the `tools/` scripts run in, and the reference
  recording the Voice was derived from.
- Rendered audio (`*.wav`, `auditions/`), which is gitignored.

Not yet in the repo, created during M0: `ios/SpideyCore/`,
`ios/CallSpidey/`, and the Xcode entries in `.gitignore`.

## Milestones

| # | Milestone | Status | Closed on | Evidence |
|---|---|---|---|---|
| 0 | Orientation | not started | | |
| 1 | Swift via Eliza | not started | | |
| 2 | Conversation core | not started | | |
| 3 | Text call | outline only | | |
| 4 | Spidey speaks | outline only | | |
| 5 | Spidey listens | outline only | | |
| 6 | Spidey thinks | outline only | | |
| 7 | Polish | outline only | | |
| 8 | Capstone: the Spidey Voice | outline only | | |

"Evidence" is the command and its result line, or the observed check on the
phone.

## Facts

- Mac: Intel, macOS 26. Xcode version: (fill in at M0)
- Phone: iPhone 15 Pro, iOS 26.
- Signing: free Apple ID, Personal Team.

### M0 capability check

- Foundation Models builds: (fill in)
- Foundation Models available on the phone: (fill in)
- iOS 26 speech recognition API builds: (fill in)
- Apple Intelligence enabled on the phone: (fill in)

## Decisions

- 2026-11-13: all decisions in `docs/ios/PLAN.md` section 1.

## What was hard

Record what took longest in each milestone, in a line or two. Later briefs
are pitched using this.

## Open

- Kokoro runtime for M8.
- Whether the 25-word Reply cap is needed on iOS.
- Whether "Spidey" needs a pronunciation fix in the system voice.
