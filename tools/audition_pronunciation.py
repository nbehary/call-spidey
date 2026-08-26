#!/usr/bin/env python3
"""Audition candidate pronunciations for a word, as WAVs, without a device build.

Why this exists: `TtsPronunciation.kt`'s logged lesson is "judge a respelling
purely by ear" — but the only way to hear one used to be edit -> assemble ->
install -> place a call. This renders each candidate straight to a WAV in a
few seconds so a whole batch can be compared side by side.

Faithfulness: `phonemize()` below is a direct port of the device's
`KokoroPhonemizer` (speech-core/src/models/kokoro/kokoro_phonemizer.cpp) —
same contraction normalisation, same word split, same
special-case/gold/silver/stem resolution order, same char-by-char tokenize
with unknown symbols silently dropped. It reads the *real* dictionaries and
vocab pulled off the device, so the token ids it produces are the ids the
phone produces.

The one approximation is the acoustic model: this runs the public
`onnx-community/Kokoro-82M-ONNX` export (same weights the voice-derivation
tools in this directory already optimise against), not the device's
`kokoro-e2e.onnx` repackaging. Good enough to rank candidates by vowel
length/quality; confirm the winner in the real app before calling it done.

Usage:
    python3 tools/audition_pronunciation.py \
        --models-dir <dir with us_gold.json/us_silver.json/vocab_index.json> \
        --text "Hi, I'm Spidey! What is your name?" \
        --override "name=nˈAm" --override "name=nˈeɪm" --literal naym

Pull the dictionaries/vocab first with:
    adb exec-out run-as dev.nate.spideycall cat files/models/us_gold.json > us_gold.json
    (likewise us_silver.json, vocab_index.json)
"""
import argparse
import json
import re
import string
import struct
import sys
import unicodedata
from pathlib import Path

import numpy as np

PROJECT = Path(__file__).resolve().parent.parent
CANDIDATES = PROJECT / "app/src/main/assets/voices/candidates"

SAMPLE_RATE = 24000
KOKORO_SPEED = 1.0     # speech-android never calls set_voice/set_speed
PLAYBACK_PITCH = 1.12  # SpideyVoice.DEFAULT_PITCH
DEFAULT_VOICE = "spidey_v3"

# kokoro_phonemizer.cpp: normalize_text()
CONTRACTIONS = [
    ("can't", "can not"), ("won't", "will not"), ("don't", "do not"),
    ("doesn't", "does not"), ("didn't", "did not"), ("isn't", "is not"),
    ("aren't", "are not"), ("wasn't", "was not"), ("weren't", "were not"),
    ("couldn't", "could not"), ("wouldn't", "would not"), ("shouldn't", "should not"),
    ("haven't", "have not"), ("hasn't", "has not"), ("hadn't", "had not"),
    ("i'm", "i am"), ("i've", "i have"), ("i'll", "i will"), ("i'd", "i would"),
    ("you're", "you are"), ("you've", "you have"), ("you'll", "you will"),
    ("he's", "he is"), ("she's", "she is"), ("it's", "it is"),
    ("we're", "we are"), ("we've", "we have"), ("we'll", "we will"),
    ("they're", "they are"), ("they've", "they have"), ("they'll", "they will"),
    ("that's", "that is"), ("there's", "there is"), ("let's", "let us"),
]

# kokoro_phonemizer.cpp: special_case()
SPECIAL_CASE = {
    "the": "ðə", "a": "ɐ", "an": "ən", "to": "tʊ", "of": "ʌv", "i": "aɪ",
}

KEPT_PUNCT = {",", ".", "!", "?", ";", ":", "-", "'"}


class Phonemizer:
    """Port of KokoroPhonemizer's English path. See module docstring."""

    def __init__(self, models_dir: Path):
        self.gold = self._load_dict(models_dir / "us_gold.json")
        self.silver = self._load_dict(models_dir / "us_silver.json")
        vocab = json.loads((models_dir / "vocab_index.json").read_text())
        self.vocab = vocab["vocab"] if "vocab" in vocab else vocab

    @staticmethod
    def _load_dict(path: Path) -> dict:
        raw = json.loads(path.read_text())
        # resolve_entry(): heteronyms are nested objects, DEFAULT wins.
        out = {}
        for word, entry in raw.items():
            if isinstance(entry, dict):
                entry = entry.get("DEFAULT") or next(iter(entry.values()), "")
            if isinstance(entry, str):
                out[word] = entry
        # grow_dictionary(): lowercase aliases for capitalised source keys.
        for word, phonemes in list(out.items()):
            lower = word.lower()
            if word and word != lower:
                out.setdefault(lower, phonemes)
        return out

    def normalize_text(self, text: str) -> str:
        result = text
        for frm, to in CONTRACTIONS:
            result = re.sub(re.escape(frm), to, result, flags=re.IGNORECASE)
        result = result.replace("  ", " ")
        return result.strip(" \t\n\r")

    @staticmethod
    def split_words(text: str) -> list[str]:
        words, current = [], ""
        for c in text:
            if c.isspace():
                if current:
                    words.append(current)
                    current = ""
                words.append(" ")
            elif c in string.punctuation:
                if current:
                    words.append(current)
                    current = ""
                words.append(c)
            else:
                current += c
        if current:
            words.append(current)
        return words

    def lookup(self, word: str) -> str:
        return self.gold.get(word) or self.silver.get(word) or ""

    def stem_and_lookup(self, word: str) -> str:
        # stem_s
        if word.endswith("s") and len(word) > 2:
            if word.endswith("ies"):
                p = self.lookup(word[:-3] + "y")
                if p:
                    return p + "z"
            if word.endswith("es") and len(word) > 3:
                p = self.lookup(word[:-2])
                if p:
                    return p + ("ɪz" if p[-1] in "sz" else "z")
            p = self.lookup(word[:-1])
            if p:
                return p + ("s" if p[-1] in "ptkf" else "z")
        # stem_ed
        if word.endswith("ed") and len(word) > 3:
            if word.endswith("ied"):
                p = self.lookup(word[:-3] + "y")
                if p:
                    return p + "d"
            base = word[:-2]
            if len(base) >= 2 and base[-1] == base[-2]:
                p = self.lookup(base[:-1])
                if p:
                    return p + self._ed_suffix(p)
            p = self.lookup(base)
            if p:
                return p + self._ed_suffix(p)
        # stem_ing
        if word.endswith("ing") and len(word) > 4:
            stem = word[:-3]
            if len(stem) >= 2 and stem[-1] == stem[-2]:
                p = self.lookup(stem[:-1])
                if p:
                    return p + "ɪŋ"
            p = self.lookup(stem) or self.lookup(stem + "e")
            if p:
                return p + "ɪŋ"
        return ""

    @staticmethod
    def _ed_suffix(phonemes: str) -> str:
        if not phonemes:
            return "d"
        last = phonemes[-1]
        if last in "td":
            return "ɪd"
        if last in "pkfs":
            return "t"
        return "d"

    def resolve_word(self, word: str) -> str:
        lower = word.lower()
        return (SPECIAL_CASE.get(lower)
                or self.lookup(lower)
                or self.stem_and_lookup(lower)
                or lower)  # OOV: returned as-is, then mangled by tokenize()

    def phonemize(self, text: str) -> str:
        result = ""
        for word in self.split_words(self.normalize_text(text)):
            if word.isspace():
                result += " "
            elif all(c in string.punctuation for c in word):
                if word in KEPT_PUNCT:
                    result += word
            else:
                result += self.resolve_word(word)
        return result

    def tokenize(self, phonemes: str) -> list[int]:
        # Unknown symbols (spaces included) are silently dropped, exactly as
        # the device does — this is what turns an OOV respelling into noise.
        return [0] + [self.vocab[c] for c in phonemes if c in self.vocab] + [0]

    def dropped(self, phonemes: str) -> list[str]:
        return [c for c in phonemes if c not in self.vocab and not c.isspace()]


def load_style(name: str) -> np.ndarray:
    path = Path(name) if Path(name).is_file() else CANDIDATES / f"{name}.bin"
    data = path.read_bytes()
    if len(data) != 1024:
        sys.exit(f"{path}: expected a 1024-byte voice embedding, got {len(data)}")
    return np.array(struct.unpack("<256f", data), dtype=np.float32)


def apply_playback_pitch(audio, sr, pitch, librosa):
    """AudioTrack PlaybackParams.setPitch is a resample-style shift of pitch and
    formants; setSpeed (tempo only) is not modelled. Same as
    optimize_spidey_voice.py's apply_playback_pitch."""
    if abs(pitch - 1.0) < 1e-6:
        return audio
    return librosa.resample(audio, orig_sr=sr, target_sr=int(round(sr / pitch)))


def describe(phonemes: str) -> str:
    return " ".join(f"{c}({unicodedata.name(c, '?')})" for c in phonemes)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models-dir", required=True, type=Path,
                    help="directory holding us_gold.json/us_silver.json/vocab_index.json "
                         "pulled off the device")
    ap.add_argument("--text", default="Hi, I'm Spidey! What is your name?")
    ap.add_argument("--word", default="name",
                    help="the word each --override replaces the phonemes of")
    ap.add_argument("--override", action="append", default=[], metavar="[LABEL=]PHONEMES",
                    help="a candidate phoneme string for --word (repeatable). IPA does not "
                         "survive a filename, so prefix an ASCII LABEL= to name the WAV.")
    ap.add_argument("--literal", action="append", default=[], metavar="[LABEL=]RESPELLING",
                    help="a TtsPronunciation-style text respelling to try, i.e. what "
                         "the device does today (repeatable)")
    ap.add_argument("--voice", default=DEFAULT_VOICE)
    ap.add_argument("--pitch", type=float, default=PLAYBACK_PITCH)
    ap.add_argument("--out-dir", type=Path, default=PROJECT / "auditions")
    args = ap.parse_args()

    import librosa
    import onnxruntime as ort
    import soundfile as sf
    from huggingface_hub import hf_hub_download

    ph = Phonemizer(args.models_dir)
    style = load_style(args.voice).reshape(1, 256)

    model_path = hf_hub_download("onnx-community/Kokoro-82M-ONNX", "onnx/model.onnx")
    sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])

    def split_label(spec: str, fallback: str) -> tuple[str, str]:
        label, sep, value = spec.partition("=")
        return (label, value) if sep else (fallback, spec)

    # (label, text, dictionary override) per candidate.
    jobs = [("baseline-dictionary", args.text, None)]
    for i, spec in enumerate(args.override, 1):
        label, phonemes = split_label(spec, f"dict-{i}")
        jobs.append((label, args.text, phonemes))
    for i, spec in enumerate(args.literal, 1):
        label, respelling = split_label(spec, f"text-{i}")
        text = re.sub(rf"\b{re.escape(args.word)}\b", respelling, args.text,
                      flags=re.IGNORECASE)
        jobs.append((label, text, None))

    args.out_dir.mkdir(parents=True, exist_ok=True)
    original = ph.gold.get(args.word)
    for label, text, override in jobs:
        if override is None:
            if original is not None:
                ph.gold[args.word] = original
            else:
                ph.gold.pop(args.word, None)
        else:
            ph.gold[args.word] = override

        phonemes = ph.phonemize(text)
        ids = ph.tokenize(phonemes)
        audio = np.squeeze(sess.run(None, {
            "input_ids": np.asarray([ids], dtype=np.int64),
            "style": np.ascontiguousarray(style),
            "speed": np.asarray([KOKORO_SPEED], dtype=np.float32),
        })[0]).astype(np.float32)
        played = apply_playback_pitch(audio, SAMPLE_RATE, args.pitch, librosa)

        safe = re.sub(r"[^A-Za-z0-9._-]", "_", label)
        out = args.out_dir / f"{safe}.wav"
        sf.write(str(out), played, SAMPLE_RATE)
        lost = ph.dropped(phonemes)
        print(f"{label}\n  phonemes: {phonemes}\n  tokens:   {len(ids)}  "
              f"duration: {len(played) / SAMPLE_RATE:.2f}s"
              + (f"\n  DROPPED (not in vocab): {describe(''.join(lost))}" if lost else "")
              + f"\n  -> {out}")

    if original is not None:
        ph.gold[args.word] = original


if __name__ == "__main__":
    main()
