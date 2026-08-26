#!/usr/bin/env python3
"""Extract a single-speaker Kokoro voice embedding compatible with speech-android.

speech-android's ModelManager ships each Kokoro voice as a 1024-byte file
(256 float32 values) under <modelDir>/voices/*.bin, and rejects/redownloads
anything else (ModelManager.isValidModel, KOKORO_VOICE_BYTES). Upstream
hexgrad/Kokoro-82M ships each voice as a *.pt PyTorch tensor of shape
(510, 1, 256) — one 256-float row per input-token-length bucket (1..510),
used to condition prosody on utterance length.

soniqo's compacted .bin files are byte-identical to exactly ONE row of the
upstream tensor. That row was recovered empirically: three of the compacted
voices speech-android already ships (af_heart, hf_alpha, ff_siwis) were each
pulled off a device and diff'd against every row of their upstream tensor.
All three matched row 128 with max abs diff == 0.0 (next-closest row differed
by ~0.002) — see `--verify` below to re-run that check yourself, e.g. against
a different soniqo model release.

Usage:
    pip install huggingface_hub numpy
    python3 tools/make_voice.py am_puck am_liam am_adam --out app/src/main/assets/voices/candidates
    python3 tools/make_voice.py --verify af_heart hf_alpha ff_siwis --device-dir /path/to/pulled/bins
"""
from __future__ import annotations

import argparse
import struct
import zipfile
from pathlib import Path

import numpy as np
from huggingface_hub import hf_hub_download

REPO = "hexgrad/Kokoro-82M"
ROW = 128  # see module docstring — empirically recovered, not documented upstream.
VOICE_BYTES = 256 * 4


def load_upstream_tensor(voice: str) -> np.ndarray:
    """Downloads (if needed) and returns the upstream (510, 256) float32 tensor."""
    path = hf_hub_download(REPO, f"voices/{voice}.pt")
    with zipfile.ZipFile(path) as z:
        # PyTorch's legacy zip format stores each tensor's raw contiguous
        # storage as a plain file inside the archive — no torch import needed.
        member = f"{Path(path).stem}/data/0"
        raw = z.read(member)
    return np.frombuffer(raw, dtype="<f4").reshape(-1, 256)


def extract_row(voice: str, row: int = ROW) -> bytes:
    tensor = load_upstream_tensor(voice)
    if not (0 <= row < tensor.shape[0]):
        raise ValueError(f"{voice}: row {row} out of range for {tensor.shape[0]} rows")
    return struct.pack("<256f", *tensor[row].tolist())


def verify(voice: str, device_bin: Path) -> int:
    """Returns the best-matching row index for a compacted .bin pulled off a device."""
    tensor = load_upstream_tensor(voice)
    device = np.fromfile(device_bin, dtype="<f4")
    if device.size != 256:
        raise ValueError(f"{device_bin}: expected 256 float32 values, got {device.size}")
    diffs = np.abs(tensor - device[None, :]).max(axis=1)
    best = int(np.argmin(diffs))
    print(f"{voice}: best row {best}, max abs diff {diffs[best]:.6g} "
          f"(row {ROW} diff: {diffs[ROW]:.6g})")
    return best


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("voices", nargs="+", help="Kokoro voice ids, e.g. am_puck am_liam")
    parser.add_argument("--out", type=Path, default=Path("voices_out"), help="output directory")
    parser.add_argument("--verify", action="store_true",
                         help="voices are compacted .bin files already on disk (see --device-dir); "
                              "report which upstream row each matches instead of extracting")
    parser.add_argument("--device-dir", type=Path, default=Path("."),
                         help="directory containing <voice>_device.bin files, used with --verify")
    args = parser.parse_args()

    if args.verify:
        for voice in args.voices:
            verify(voice, args.device_dir / f"{voice}_device.bin")
        return

    args.out.mkdir(parents=True, exist_ok=True)
    for voice in args.voices:
        data = extract_row(voice)
        assert len(data) == VOICE_BYTES
        dest = args.out / f"{voice}.bin"
        dest.write_bytes(data)
        print(f"wrote {dest} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
