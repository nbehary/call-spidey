#!/usr/bin/env python3
"""Global search for the Kokoro style vector that best matches Spidey_voice.mp3.

Supersedes tools/derive_spidey_voice.py, which had two protocol bugs that made its
reported similarities describe audio the app never produces:

  1. ROW MISMATCH. Kokoro's style tensor is (510, 1, 256) and the row is picked by
     token count; the old scorer synthesised with the *whole* tensor, so a 73-token
     test phrase was scored on row 73 while save_voice_bin() exported row 128 (the
     row speech-android's af_heart.bin actually is). cos(row73, row128) = 0.91 for
     am_puck, 0.79 at row 56 -- the optimiser never scored the shipped vector.
  2. PLAYBACK IGNORED. The app plays through AudioTrack with
     PlaybackParams.pitch = SpideyVoice.DEFAULT_PITCH (1.12), which resample-shifts
     pitch *and formants* up 12%. Scoring un-shifted synthesis optimises a voice
     nobody hears.

This tool always synthesises with a single explicit 256-float row-128 style vector
(exactly the 1024 bytes that get written), emulates the AudioTrack pitch shift before
embedding, averages the objective over several phrases, and reports a held-out score
on phrases the optimiser never saw.

Stages:
  selftest  -- validate the bundled CMA-ES on Rosenbrock (no models loaded)
  baseline  -- true row-128 score for every stock voice and every shipped .bin
  optimize  -- simplex over top-N blends, then long CMA-ES over the 54-voice span
  analyze   -- acoustic sanity check (F0/centroid/rolloff/flatness) vs the reference,
               to catch a candidate that games WavLM with high-frequency noise
  pitch     -- 1-D playback-pitch sweep for a given .bin

Usage:
  python tools/optimize_spidey_voice.py selftest
  python tools/optimize_spidey_voice.py baseline
  python tools/optimize_spidey_voice.py optimize --generations 120 --popsize 16
  python tools/optimize_spidey_voice.py analyze
  python tools/optimize_spidey_voice.py pitch --bin app/src/main/assets/voices/candidates/spidey_v2.bin
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
import time
import warnings
from multiprocessing import Pool
from pathlib import Path

import numpy as np

warnings.filterwarnings("ignore")

# --- deployment facts (keep in sync with the app) ---------------------------------
ROW = 128                 # speech-core hardcodes af_heart -> row 128 of the style tensor
VOICE_BYTES = 256 * 4     # ModelManager.isValidModel rejects anything but 1024 bytes
PLAYBACK_PITCH = 1.12     # SpideyVoice.DEFAULT_PITCH
PLAYBACK_SPEED = 1.08     # SpideyVoice.DEFAULT_SPEED (tempo only; not modelled)
KOKORO_SPEED = 1.0        # speech-android never calls set_speed, so synthesis is 1.0

REF_AUDIO = "/home/nate/projects/ai/general/Spidey_voice.mp3"
PROJECT = Path(__file__).resolve().parent.parent
CANDIDATES = PROJECT / "app/src/main/assets/voices/candidates"
SAMPLE_RATE = 24000

# Phrases the optimiser sees.
TRAIN_PHRASES = [
    "Hi, I'm Spider-Man! Thwip! Friendly neighborhood Spider-Man on duty.",
    "Whoa, that was close! My spider sense is tingling like crazy.",
    "Hey there! Web slinging through the city is the best part of my day.",
    "Okay web heads, let's swing into action and save the day!",
]
# Phrases held out for honest reporting -- includes the app's actual opening line.
HELDOUT_PHRASES = [
    "Hi, I'm Spiderman.",
    "Wow, you are really brave. I bet you would make a great super hero.",
    "Thwip! Gotta go, my spider sense says there's trouble downtown.",
]

# ---------------------------------------------------------------------------------
# Worker pool: ONNX synthesis only. Token ids are computed once in the parent so
# workers never touch espeak, and style vectors are 1 KB so pickling is free.
# ---------------------------------------------------------------------------------
_SESS = None


def _init_worker(model_path: str, intra_threads: int) -> None:
    global _SESS
    import onnxruntime as ort

    so = ort.SessionOptions()
    so.intra_op_num_threads = intra_threads
    so.inter_op_num_threads = 1
    _SESS = ort.InferenceSession(model_path, so, providers=["CPUExecutionProvider"])


def _synth_job(job):
    key, ids, style, speed = job
    inputs = {
        "input_ids": np.asarray([ids], dtype=np.int64),
        "style": np.ascontiguousarray(np.asarray(style, dtype=np.float32).reshape(1, 256)),
        "speed": np.asarray([float(speed)], dtype=np.float32),
    }
    try:
        audio = _SESS.run(None, inputs)[0]
    except Exception as exc:  # a bad style vector must not kill the pool
        return key, None, repr(exc)
    return key, np.squeeze(np.asarray(audio, dtype=np.float32)), None


# ---------------------------------------------------------------------------------
# CMA-ES (Hansen's purecma, rank-mu + rank-one, minimising). Bundled rather than
# `pip install cma` so nothing is added to the shared venv; `selftest` proves it.
# ---------------------------------------------------------------------------------
class CMAES:
    def __init__(self, x0, sigma, popsize=None, seed=0):
        self.N = len(x0)
        self.xmean = np.asarray(x0, dtype=float).copy()
        self.sigma = float(sigma)
        self.lam = int(popsize or 4 + int(3 * np.log(self.N)))
        self.mu = self.lam // 2
        w = np.log(self.mu + 0.5) - np.log(np.arange(1, self.mu + 1))
        self.w = w / w.sum()
        self.mueff = 1.0 / np.sum(self.w ** 2)
        N = self.N
        self.cc = (4 + self.mueff / N) / (N + 4 + 2 * self.mueff / N)
        self.cs = (self.mueff + 2) / (N + self.mueff + 5)
        self.c1 = 2 / ((N + 1.3) ** 2 + self.mueff)
        self.cmu = min(1 - self.c1,
                       2 * (self.mueff - 2 + 1 / self.mueff) / ((N + 2) ** 2 + self.mueff))
        self.damps = 1 + 2 * max(0.0, np.sqrt((self.mueff - 1) / (N + 1)) - 1) + self.cs
        self.pc = np.zeros(N)
        self.ps = np.zeros(N)
        self.B = np.eye(N)
        self.D = np.ones(N)
        self.C = np.eye(N)
        self.invsqrtC = np.eye(N)
        self.counteval = 0
        self.eigeneval = 0
        self.chiN = np.sqrt(N) * (1 - 1 / (4 * N) + 1 / (21 * N ** 2))
        self.rng = np.random.default_rng(seed)

    def ask(self):
        z = self.rng.standard_normal((self.lam, self.N))
        y = (self.B @ (self.D[:, None] * z.T)).T
        return [self.xmean + self.sigma * yi for yi in y]

    def tell(self, xs, fs):
        xs = np.asarray(xs, dtype=float)
        fs = np.asarray(fs, dtype=float)
        self.counteval += len(fs)
        xs = xs[np.argsort(fs)]
        xold = self.xmean.copy()
        self.xmean = self.w @ xs[:self.mu]
        y = (self.xmean - xold) / self.sigma
        self.ps = (1 - self.cs) * self.ps + \
            np.sqrt(self.cs * (2 - self.cs) * self.mueff) * (self.invsqrtC @ y)
        ps_norm = np.linalg.norm(self.ps)
        hsig = ps_norm / np.sqrt(1 - (1 - self.cs) ** (2 * self.counteval / self.lam)) / self.chiN \
            < 1.4 + 2 / (self.N + 1)
        self.pc = (1 - self.cc) * self.pc + \
            hsig * np.sqrt(self.cc * (2 - self.cc) * self.mueff) * y
        artmp = (xs[:self.mu] - xold) / self.sigma
        self.C = ((1 - self.c1 - self.cmu) * self.C
                  + self.c1 * (np.outer(self.pc, self.pc)
                               + (0 if hsig else self.cc * (2 - self.cc)) * self.C)
                  + self.cmu * (artmp.T @ (self.w[:, None] * artmp)))
        self.sigma *= np.exp((self.cs / self.damps) * (ps_norm / self.chiN - 1))
        if self.counteval - self.eigeneval > self.lam / (self.c1 + self.cmu) / self.N / 10:
            self.eigeneval = self.counteval
            self.C = np.triu(self.C) + np.triu(self.C, 1).T
            d, self.B = np.linalg.eigh(self.C)
            self.D = np.sqrt(np.maximum(d, 1e-20))
            self.invsqrtC = self.B @ np.diag(1 / self.D) @ self.B.T


def selftest() -> int:
    def rosenbrock(x):
        return float(np.sum(100 * (x[1:] - x[:-1] ** 2) ** 2 + (1 - x[:-1]) ** 2))

    es = CMAES(np.zeros(10), 0.5, seed=1)
    best = np.inf
    for _ in range(3000):
        xs = es.ask()
        fs = [rosenbrock(x) for x in xs]
        es.tell(xs, fs)
        best = min(best, min(fs))
        if best < 1e-10:
            break
    print(f"CMA-ES selftest: Rosenbrock(10-D) best = {best:.3e} after {es.counteval} evals")
    ok = best < 1e-8
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


# ---------------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------------
class Scorer:
    """Reference centroid + row-128 objective, evaluated over a process pool."""

    def __init__(self, ref_path: str, procs: int, intra: int, pitch: float = PLAYBACK_PITCH):
        import librosa
        import torch
        import torch.nn.functional as F
        from huggingface_hub import hf_hub_download
        from kokoro_onnx import Kokoro
        from transformers import Wav2Vec2FeatureExtractor, WavLMForXVector

        self.librosa, self.torch, self.F = librosa, torch, F
        self.pitch = pitch
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        print(f"Loading WavLM speaker-verification model on {self.device}...")
        self.fe = Wav2Vec2FeatureExtractor.from_pretrained("microsoft/wavlm-base-plus-sv")
        self.wavlm = WavLMForXVector.from_pretrained("microsoft/wavlm-base-plus-sv") \
            .eval().to(self.device)

        model_path = hf_hub_download("onnx-community/Kokoro-82M-ONNX", "onnx/model.onnx")
        kokoro = Kokoro(model_path, "/tmp/voices-v1.0.bin")
        self.voice_names = sorted(kokoro.get_voices())
        self.styles = {n: np.asarray(kokoro.get_voice_style(n), dtype=np.float32)
                       .reshape(510, 256)[ROW].copy() for n in self.voice_names}
        self.train_ids = [list(kokoro.tokenizer.tokenize(kokoro.tokenizer.phonemize(p, "en-us")))
                          for p in TRAIN_PHRASES]
        self.heldout_ids = [list(kokoro.tokenizer.tokenize(kokoro.tokenizer.phonemize(p, "en-us")))
                            for p in HELDOUT_PHRASES]
        del kokoro

        self.pool = Pool(procs, initializer=_init_worker, initargs=(model_path, intra))
        self.ref_emb = self._reference_centroid(ref_path)
        self.evals = 0

    # -- reference ---------------------------------------------------------------
    def _reference_centroid(self, ref_path: str) -> np.ndarray:
        """Mean of L2-normalised x-vectors over sliding windows of the WHOLE reference.

        The old scripts embedded only the first 30 s as one clip.
        """
        y, sr = self.librosa.load(ref_path, sr=SAMPLE_RATE)
        win, hop = int(8 * sr), int(4 * sr)
        embs = []
        for start in range(0, max(len(y) - win, 0) + 1, hop):
            chunk = y[start:start + win]
            if np.max(np.abs(chunk)) < 1e-3:
                continue
            embs.append(self._embed(chunk, sr, shift_pitch=False))
        if not embs:
            embs = [self._embed(y, sr, shift_pitch=False)]
        centroid = np.mean(embs, axis=0)
        centroid /= np.linalg.norm(centroid)
        f0, sc = self.acoustics(y, sr)
        print(f"Reference: {len(y)/sr:.2f}s, {len(embs)} windows, F0 {f0:.1f} Hz, "
              f"centroid {sc:.1f} Hz")
        return centroid

    # -- audio helpers -----------------------------------------------------------
    def apply_playback_pitch(self, audio: np.ndarray, sr: int, pitch: float) -> np.ndarray:
        """Emulate AudioTrack PlaybackParams.setPitch: resample-style shift of pitch
        AND formants by `pitch`. The tempo-only setSpeed(1.08) is not modelled --
        x-vectors are near-invariant to duration."""
        if abs(pitch - 1.0) < 1e-6:
            return audio
        return self.librosa.resample(audio, orig_sr=sr, target_sr=int(round(sr / pitch)))

    def _embed(self, audio: np.ndarray, sr: int, shift_pitch: bool = True,
               pitch: float | None = None) -> np.ndarray:
        a = np.asarray(audio, dtype=np.float32)
        if shift_pitch:
            a = self.apply_playback_pitch(a, sr, self.pitch if pitch is None else pitch)
        if sr != 16000:
            a = self.librosa.resample(a, orig_sr=sr, target_sr=16000)
        if len(a) > 30 * 16000:
            a = a[:30 * 16000]
        x = self.fe(a, sampling_rate=16000, return_tensors="pt", padding=True)
        x = {k: v.to(self.device) for k, v in x.items()}
        with self.torch.no_grad():
            e = self.wavlm(**x).embeddings
        return self.F.normalize(e, dim=-1).squeeze().cpu().numpy()

    def acoustics(self, y: np.ndarray, sr: int):
        f0 = self.librosa.yin(y, fmin=65.0, fmax=800.0, sr=sr)
        f0 = f0[np.isfinite(f0)]
        sc = self.librosa.feature.spectral_centroid(y=y, sr=sr)
        return float(np.median(f0)) if f0.size else float("nan"), float(np.mean(sc))

    # -- objective ---------------------------------------------------------------
    def score_full(self, styles: list[np.ndarray], heldout: bool = False,
                   pitch: float | None = None) -> list[dict]:
        """Per style: mean cosine similarity to the reference centroid, plus the
        as-played spectral flatness and 85% rolloff used by the timbre penalty.

        One pool job per (style, phrase); a failed or silent synthesis scores -1.
        """
        ids = self.heldout_ids if heldout else self.train_ids
        jobs = [((si, pi), [0, *tok, 0], styles[si], KOKORO_SPEED)
                for si in range(len(styles)) for pi, tok in enumerate(ids)]
        acc = {si: {"sim": [], "flat": [], "roll": []} for si in range(len(styles))}
        for key, audio, err in self.pool.imap_unordered(_synth_job, jobs, chunksize=1):
            si = key[0]
            if err is not None or audio is None or not np.all(np.isfinite(audio)) \
                    or np.max(np.abs(audio)) < 0.01:
                acc[si]["sim"].append(-1.0)
                acc[si]["flat"].append(1.0)      # worst case: white noise
                acc[si]["roll"].append(float(SAMPLE_RATE / 2))
                continue
            played = self.apply_playback_pitch(
                audio, SAMPLE_RATE, self.pitch if pitch is None else pitch)
            emb = self._embed(played, SAMPLE_RATE, shift_pitch=False)
            acc[si]["sim"].append(float(np.dot(self.ref_emb, emb)))
            acc[si]["flat"].append(
                float(np.mean(self.librosa.feature.spectral_flatness(y=played))))
            acc[si]["roll"].append(float(np.mean(self.librosa.feature.spectral_rolloff(
                y=played, sr=SAMPLE_RATE, roll_percent=0.85))))
        self.evals += len(styles)
        return [{k: float(np.mean(v)) for k, v in acc[si].items()}
                for si in range(len(styles))]

    def score_batch(self, styles: list[np.ndarray], heldout: bool = False,
                    pitch: float | None = None) -> list[float]:
        """Similarity only -- thin wrapper over score_full."""
        return [r["sim"] for r in self.score_full(styles, heldout, pitch)]

    def synth_one(self, style: np.ndarray, phrase_ids: list[int]) -> np.ndarray:
        job = ((0, 0), [0, *phrase_ids, 0], style, KOKORO_SPEED)
        key, audio, err = self.pool.apply(_synth_job, (job,))
        if err is not None:
            raise RuntimeError(err)
        return audio

    def close(self):
        self.pool.close()
        self.pool.join()


# ---------------------------------------------------------------------------------
def save_bin(style: np.ndarray, path: Path) -> None:
    packed = struct.pack("<256f", *np.asarray(style, dtype=np.float32).reshape(256).tolist())
    assert len(packed) == VOICE_BYTES, len(packed)
    path.write_bytes(packed)
    print(f"  wrote {path} ({len(packed)} bytes)")


def load_bin(path: Path) -> np.ndarray:
    raw = path.read_bytes()
    if len(raw) != VOICE_BYTES:
        raise ValueError(f"{path} is {len(raw)} bytes, expected {VOICE_BYTES}")
    return np.asarray(struct.unpack("<256f", raw), dtype=np.float32)


def stage_baseline(sc: Scorer, args) -> dict:
    names = sc.voice_names
    styles = [sc.styles[n] for n in names]
    t0 = time.time()
    train = sc.score_batch(styles)
    held = sc.score_batch(styles, heldout=True)
    ranked = sorted(zip(names, train, held), key=lambda r: r[1], reverse=True)
    print(f"\n=== Stock voices, row {ROW}, playback pitch {sc.pitch} "
          f"({time.time()-t0:.0f}s) ===")
    for n, tr, hl in ranked[:15]:
        print(f"  {n:14s} train {tr:.4f}   held-out {hl:.4f}")

    shipped = sorted(CANDIDATES.glob("*.bin"))
    if shipped:
        sty = [load_bin(p) for p in shipped]
        tr = sc.score_batch(sty)
        hl = sc.score_batch(sty, heldout=True)
        print(f"\n=== Shipped candidate .bin files (as the app actually plays them) ===")
        for p, a, b in sorted(zip(shipped, tr, hl), key=lambda r: r[1], reverse=True):
            print(f"  {p.name:22s} train {a:.4f}   held-out {b:.4f}")
    return {"stock": ranked, "shipped": list(zip([p.name for p in shipped], tr, hl))
            if shipped else []}


def stage_optimize(sc: Scorer, args) -> None:
    names = sc.voice_names
    V = np.stack([sc.styles[n] for n in names])          # (54, 256) span of real voices
    norms = np.linalg.norm(V, axis=1)
    lo, hi = float(norms.min()) * 0.85, float(norms.max()) * args.norm_cap
    print(f"\nSpan: {V.shape[0]} voices, row-128 norms {norms.min():.2f}-{norms.max():.2f}, "
          f"cap {hi:.2f}")

    # Timbre caps. WavLM rewards high-frequency noise, so an unpenalised search buys
    # similarity with hiss: the first 90-min run reached held-out 0.9368 with spectral
    # flatness 0.1696 against the reference's 0.0283. These caps sit at the level of
    # the clean stock voices; only exceeding them is penalised.
    import librosa as _lb
    _ry, _rsr = _lb.load(args.ref, sr=SAMPLE_RATE)
    ref_flat = float(np.mean(_lb.feature.spectral_flatness(y=_ry)))
    ref_roll = float(np.mean(_lb.feature.spectral_rolloff(y=_ry, sr=_rsr, roll_percent=0.85)))
    flat_cap, roll_cap = ref_flat * args.flat_slack, ref_roll * args.roll_slack
    print(f"Reference flatness {ref_flat:.4f} (cap {flat_cap:.4f}), "
          f"rolloff {ref_roll:.0f} Hz (cap {roll_cap:.0f} Hz)")

    base_scores = sc.score_batch([sc.styles[n] for n in names])
    order = np.argsort(base_scores)[::-1]
    top = [names[i] for i in order[:args.top]]
    print(f"Top {args.top} bases: " + ", ".join(f"{n}({base_scores[names.index(n)]:.3f})"
                                                for n in top))

    def style_from_w(w: np.ndarray) -> np.ndarray:
        return (w @ V).astype(np.float32)

    def norm_penalty(style: np.ndarray) -> float:
        n = float(np.linalg.norm(style))
        if n < lo:
            return (lo - n) ** 2
        if n > hi:
            return (n - hi) ** 2
        return 0.0

    def timbre_penalty(res: dict) -> float:
        """One-sided: being darker/cleaner than the reference costs nothing."""
        f_ex = max(0.0, res["flat"] / flat_cap - 1.0)
        r_ex = max(0.0, res["roll"] / roll_cap - 1.0)
        return args.flat_weight * f_ex ** 2 + args.roll_weight * r_ex ** 2

    # -- stage B: convex blend over the top-N bases, many more iterations than the
    #    old 30-step Nelder-Mead over 4 weights --------------------------------------
    idx = [names.index(n) for n in top]
    rng = np.random.default_rng(args.seed)
    pop = [np.eye(len(idx))[i] for i in range(len(idx))]
    pop += [rng.dirichlet(np.ones(len(idx))) for _ in range(args.blend_pop - len(idx))]
    best_w_top, best_blend = None, -np.inf
    for it in range(args.blend_iters):
        styles = []
        for p in pop:
            w = np.zeros(len(names))
            w[idx] = p
            styles.append(style_from_w(w))
        res = sc.score_full(styles)
        # Same penalised fitness as stage C, so the CMA seed is already timbre-aware.
        fits = [-r["sim"] + timbre_penalty(r) + norm_penalty(s)
                for r, s in zip(res, styles)]
        rank = np.argsort(fits)
        if -fits[rank[0]] > best_blend:
            best_blend, best_w_top = -fits[rank[0]], pop[rank[0]].copy()
        elite = [pop[i] for i in rank[:max(2, len(pop) // 4)]]
        pop = list(elite)
        while len(pop) < args.blend_pop:            # recombine + jitter the elite
            a, b = rng.choice(len(elite), 2, replace=True)
            child = 0.5 * (elite[a] + elite[b]) + rng.normal(0, 0.08, len(idx))
            child = np.maximum(child, 0.0)
            s = child.sum()
            pop.append(child / s if s > 1e-6 else np.ones(len(idx)) / len(idx))
        print(f"  blend iter {it+1}/{args.blend_iters}: best sim-penalty {best_blend:.4f} "
              f"(sim {res[rank[0]]['sim']:.4f}, flat {res[rank[0]]['flat']:.4f}) "
              f"{sc.evals} evals")
    w0 = np.zeros(len(names))
    w0[idx] = best_w_top
    print("\nBest blend: " + ", ".join(f"{n} {v*100:.0f}%"
                                       for n, v in zip(top, best_w_top) if v > 0.01))
    print(f"Blend penalised fitness (sim - penalty) {best_blend:.4f}")

    # -- stage C: CMA-ES over the full 54-D span, seeded from the blend --------------
    es = CMAES(w0, args.sigma, popsize=args.popsize, seed=args.seed)
    blend_res = sc.score_full([style_from_w(w0)])[0]
    best_x = w0.copy()
    best_fit = -blend_res["sim"] + timbre_penalty(blend_res) + norm_penalty(style_from_w(w0))
    best_res = blend_res
    history = []
    t0 = time.time()
    for gen in range(args.generations):
        xs = es.ask()
        styles = [style_from_w(x) for x in xs]
        res = sc.score_full(styles)
        # Selection is on the PENALISED fitness -- a noisy candidate with a high raw
        # similarity must not win, which is exactly what the first run did.
        fs = [-r["sim"] + timbre_penalty(r) + norm_penalty(s) for r, s in zip(res, styles)]
        es.tell(xs, fs)
        g = int(np.argmin(fs))
        if fs[g] < best_fit:
            best_fit, best_x, best_res = fs[g], np.asarray(xs[g]).copy(), res[g]
        history.append(best_fit)
        el = time.time() - t0
        print(f"  gen {gen+1:3d}/{args.generations}: fit {best_fit:+.4f} "
              f"(sim {best_res['sim']:.4f}, flat {best_res['flat']:.4f}, "
              f"roll {best_res['roll']:.0f}) gen sim {res[g]['sim']:.4f}, "
              f"sigma {es.sigma:.3f}, {el/60:.1f}m, {sc.evals} evals", flush=True)
        if args.time_budget and el > args.time_budget * 60:
            print("  time budget reached, stopping early")
            break

    best_style = style_from_w(best_x)
    best_f = best_res["sim"]
    held = sc.score_batch([best_style], heldout=True)[0]
    print(f"\nCMA-ES best: train {best_f:.4f}, HELD-OUT {held:.4f}, "
          f"flatness {best_res['flat']:.4f} (ref {ref_flat:.4f}), "
          f"rolloff {best_res['roll']:.0f} Hz (ref {ref_roll:.0f}), "
          f"||style|| {np.linalg.norm(best_style):.2f}")
    top_w = sorted(zip(names, best_x), key=lambda r: -abs(r[1]))[:8]
    print("Dominant span weights: " + ", ".join(f"{n} {v:+.2f}" for n, v in top_w))

    out_bin = CANDIDATES / f"{args.name}.bin"
    save_bin(best_style, out_bin)
    blend_style = style_from_w(w0)
    save_bin(blend_style, CANDIDATES / f"{args.name}_blend.bin")

    # audition WAVs: written exactly as the device plays them (pitch-shifted) and raw
    import soundfile as sf
    for label, style in (("cma", best_style), ("blend", blend_style)):
        audio = sc.synth_one(style, sc.heldout_ids[0])
        sf.write(str(PROJECT / f"{args.name}_{label}_raw.wav"), audio, SAMPLE_RATE)
        shifted = sc.apply_playback_pitch(audio, SAMPLE_RATE, sc.pitch)
        sf.write(str(PROJECT / f"{args.name}_{label}_asplayed.wav"), shifted, SAMPLE_RATE)
        f0, cent = sc.acoustics(shifted, SAMPLE_RATE)
        print(f"  {label}: as-played F0 {f0:.1f} Hz, centroid {cent:.1f} Hz "
              f"-> {args.name}_{label}_asplayed.wav")

    (PROJECT / f"{args.name}_result.json").write_text(json.dumps({
        "train": best_f, "heldout": held, "blend_fitness": best_blend,
        "flatness": best_res["flat"], "rolloff": best_res["roll"],
        "ref_flatness": ref_flat, "ref_rolloff": ref_roll, "fitness": best_fit,
        "weights": {n: float(v) for n, v in zip(names, best_x)},
        "top_bases": top, "history": history,
        "pitch": sc.pitch, "generations": len(history), "evals": sc.evals,
    }, indent=2))


def stage_analyze(sc: Scorer, args) -> None:
    """Acoustic sanity check: does a high WavLM score correspond to reference-like
    audio, or is the optimiser exploiting the metric? Compares as-played timbre
    against the reference for every candidate .bin plus the top stock voices."""
    import librosa

    def profile(y: np.ndarray, sr: int) -> dict:
        f0, cent = sc.acoustics(y, sr)
        flat = float(np.mean(librosa.feature.spectral_flatness(y=y)))
        roll = float(np.mean(librosa.feature.spectral_rolloff(y=y, sr=sr, roll_percent=0.85)))
        return {"f0": f0, "cent": cent, "flat": flat, "roll": roll}

    ref_y, ref_sr = librosa.load(args.ref, sr=SAMPLE_RATE)
    ref = profile(ref_y, ref_sr)
    print(f"\n{'source':24s} {'sim':>6s} {'F0':>7s} {'centroid':>9s} {'rolloff':>8s} {'flatness':>9s}")
    print(f"{'REFERENCE':24s} {'--':>6s} {ref['f0']:7.1f} {ref['cent']:9.1f} "
          f"{ref['roll']:8.1f} {ref['flat']:9.4f}")

    items = [(p.name, load_bin(p)) for p in sorted(CANDIDATES.glob("*.bin"))]
    items += [(f"{n} (stock)", sc.styles[n]) for n in args.stock.split(",") if n in sc.styles]
    sims = sc.score_batch([s for _, s in items], heldout=True)
    rows = []
    for (label, style), sim in zip(items, sims):
        played = sc.apply_playback_pitch(sc.synth_one(style, sc.heldout_ids[0]),
                                         SAMPLE_RATE, sc.pitch)
        rows.append((sim, label, profile(played, SAMPLE_RATE)))
    for sim, label, pr in sorted(rows, reverse=True, key=lambda r: r[0]):
        flag = " <-- timbre far from reference" if (
            pr["cent"] > ref["cent"] * 1.4 or pr["flat"] > ref["flat"] * 2.5) else ""
        print(f"{label:24s} {sim:6.4f} {pr['f0']:7.1f} {pr['cent']:9.1f} "
              f"{pr['roll']:8.1f} {pr['flat']:9.4f}{flag}")


def stage_pitch(sc: Scorer, args) -> None:
    style = load_bin(Path(args.bin))
    print(f"\nPlayback-pitch sweep for {Path(args.bin).name} (held-out phrases):")
    for p in np.arange(0.90, 1.36, 0.04):
        s = sc.score_batch([style], heldout=True, pitch=float(p))[0]
        print(f"  pitch {p:.2f}: {s:.4f}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", choices=["selftest", "baseline", "optimize", "analyze", "pitch"])
    ap.add_argument("--ref", default=REF_AUDIO)
    ap.add_argument("--procs", type=int, default=8)
    ap.add_argument("--intra", type=int, default=2)
    ap.add_argument("--pitch", type=float, default=PLAYBACK_PITCH)
    ap.add_argument("--top", type=int, default=8, help="bases for the blend stage")
    ap.add_argument("--blend-pop", type=int, default=16)
    ap.add_argument("--blend-iters", type=int, default=8)
    ap.add_argument("--popsize", type=int, default=16)
    ap.add_argument("--generations", type=int, default=120)
    ap.add_argument("--sigma", type=float, default=0.12)
    ap.add_argument("--norm-cap", type=float, default=1.02,
                    help="max ||style|| as a multiple of the largest stock row-128 norm")
    ap.add_argument("--flat-slack", type=float, default=2.5,
                    help="spectral-flatness cap as a multiple of the reference's")
    ap.add_argument("--roll-slack", type=float, default=1.25,
                    help="85%% rolloff cap as a multiple of the reference's")
    ap.add_argument("--flat-weight", type=float, default=0.5)
    ap.add_argument("--roll-weight", type=float, default=0.5)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--time-budget", type=float, default=0, help="minutes, 0 = no limit")
    ap.add_argument("--name", default="spidey_v2")
    ap.add_argument("--bin", default=str(CANDIDATES / "spidey_v2.bin"))
    ap.add_argument("--stock", default="af_sarah,af_kore,af_nicole,am_puck",
                    help="comma-separated stock voices to include in `analyze`")
    args = ap.parse_args()

    if args.stage == "selftest":
        return selftest()

    if not Path(args.ref).exists():
        print(f"reference audio not found: {args.ref}", file=sys.stderr)
        return 2
    sc = Scorer(args.ref, args.procs, args.intra, args.pitch)
    try:
        if args.stage == "baseline":
            stage_baseline(sc, args)
        elif args.stage == "optimize":
            stage_optimize(sc, args)
        elif args.stage == "analyze":
            stage_analyze(sc, args)
        else:
            stage_pitch(sc, args)
    finally:
        sc.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
