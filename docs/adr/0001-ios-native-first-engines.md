---
status: accepted
---

# iOS port uses Apple-native engines first; Kokoro and the Spidey Voice come last

The Android app runs its own models for everything: a third-party speech SDK for speech-to-text and Kokoro text-to-speech with a custom Spidey Voice, and Gemma for the generative Responder. The iOS port (2026-11-13) instead starts on Apple's built-in speech recognition, on-device language model and system speech synthesis, and brings Kokoro and the Spidey Voice back only in the final milestone. We chose this because the port exists to teach its owner iOS development, and the native frameworks are the transferable part; the cost is that the iOS app does not sound like Spidey until that last milestone.

## Considered options

- **Parity-first:** port Kokoro and the Spidey Voice at the start, because the Voice is the point of the app. Rejected: it front-loads third-party ML runtime integration, which teaches little about the platform and is a discouraging first step for a beginner.
- **Native only, no capstone:** rejected, because a system voice is not an acceptable end state for this app.

## Consequences

- Each engine sits behind a protocol (Responder, speaker, listener) so the capstone is a swap and not a rewrite.
- The Android pronunciation fixes and voice-swap mechanism are specific to one Kokoro build and are not ported until the capstone, where they are re-derived by ear for whichever runtime is chosen.
- The speech-to-text and language models differ between the two platforms permanently, so Reply wording and recognition accuracy will not match Android. The Safety net behaviour is the part held identical.
