#!/usr/bin/env python3
"""Derive an optimal Kokoro voice embedding for rein_raus from a reference audio file.

Computes speaker embeddings using WavLM (Microsoft) and acoustic features (pitch,
spectral centroid) to find the optimal combination of Kokoro voice style vectors,
then exports the 1024-byte .bin compatible with speech-android / rein_raus.
"""
import argparse
import os
import struct
import warnings
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf
import torch
import torch.nn.functional as F
from huggingface_hub import hf_hub_download
from transformers import Wav2Vec2FeatureExtractor, WavLMForXVector
from scipy.optimize import minimize
from kokoro_onnx import Kokoro
from kokoro_onnx.config import MAX_PHONEME_LENGTH, SAMPLE_RATE

warnings.filterwarnings("ignore")

REPO = "hexgrad/Kokoro-82M"
ROW = 128  # soniqo / speech-android row index
VOICE_BYTES = 256 * 4

def patch_kokoro(kokoro_inst):
    def _create_audio_patched(self, phonemes: str, voice: np.ndarray, speed: float):
        if len(phonemes) > MAX_PHONEME_LENGTH:
            phonemes = phonemes[:MAX_PHONEME_LENGTH]
        tokens = np.array(self.tokenizer.tokenize(phonemes), dtype=np.int64)
        tokens = [[0, *tokens, 0]]
        
        # Select style row if multi-row tensor
        v = np.array(voice, dtype=np.float32)
        if v.ndim == 3:  # (510, 1, 256)
            idx = min(len(tokens[0]) - 2, v.shape[0] - 1)
            style_vec = v[idx].reshape(1, 256)
        elif v.ndim == 2 and v.shape[0] > 1:  # (510, 256)
            idx = min(len(tokens[0]) - 2, v.shape[0] - 1)
            style_vec = v[idx].reshape(1, 256)
        else:
            style_vec = v.reshape(1, 256)

        inputs = {
            "input_ids": np.array(tokens, dtype=np.int64),
            "style": np.ascontiguousarray(style_vec, dtype=np.float32),
            "speed": np.array([float(speed)], dtype=np.float32),
        }
        audio = self.sess.run(None, inputs)[0]
        return audio, SAMPLE_RATE

    kokoro_inst._create_audio = _create_audio_patched.__get__(kokoro_inst, Kokoro)

def get_wavlm_extractor_and_model():
    print("Loading WavLM speaker verification model...")
    feature_extractor = Wav2Vec2FeatureExtractor.from_pretrained("microsoft/wavlm-base-plus-sv")
    model = WavLMForXVector.from_pretrained("microsoft/wavlm-base-plus-sv")
    model.eval()
    return feature_extractor, model

def compute_speaker_embedding(audio, sr, feature_extractor, model):
    if sr != 16000:
        audio = librosa.resample(audio, orig_sr=sr, target_sr=16000)
    if len(audio) > 30 * 16000:
        audio = audio[:30 * 16000]
    inputs = feature_extractor(audio, sampling_rate=16000, return_tensors="pt", padding=True)
    with torch.no_grad():
        embeddings = model(**inputs).embeddings
        embeddings = F.normalize(embeddings, dim=-1)
    return embeddings.squeeze().cpu().numpy()

def analyze_audio(y, sr):
    f0, voiced_flag, _ = librosa.pyin(y, fmin=librosa.note_to_hz('C2'), fmax=librosa.note_to_hz('C7'), sr=sr)
    f0_clean = f0[voiced_flag & ~np.isnan(f0)] if np.any(voiced_flag) else np.array([150.0])
    mean_f0 = float(np.mean(f0_clean))
    sc = librosa.feature.spectral_centroid(y=y, sr=sr)
    mean_sc = float(np.mean(sc))
    return {"mean_f0": mean_f0, "mean_sc": mean_sc}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference_audio", help="Path to reference audio file (.mp3 / .wav)")
    parser.add_argument("--out-dir", default="app/src/main/assets/voices/candidates", help="Output directory for .bin")
    parser.add_argument("--voice-name", default="spidey_custom", help="Base name of custom voice")
    args = parser.parse_args()

    ref_path = Path(args.reference_audio)
    if not ref_path.exists():
        raise FileNotFoundError(f"Reference file not found: {ref_path}")

    feature_extractor, wavlm = get_wavlm_extractor_and_model()

    print(f"\nAnalyzing reference audio: {ref_path.name}...")
    ref_y, ref_sr = librosa.load(str(ref_path), sr=24000)
    ref_emb = compute_speaker_embedding(ref_y, ref_sr, feature_extractor, wavlm)
    ref_acoustics = analyze_audio(ref_y, ref_sr)
    print(f"  Reference F0 (Pitch): {ref_acoustics['mean_f0']:.1f} Hz")
    print(f"  Reference Spectral Centroid: {ref_acoustics['mean_sc']:.1f} Hz")

    onnx_model_path = hf_hub_download("onnx-community/Kokoro-82M-ONNX", "onnx/model.onnx")
    voices_path = "/tmp/voices-v1.0.bin"
    kokoro = Kokoro(onnx_model_path, voices_path)
    patch_kokoro(kokoro)
    print(f"Kokoro ONNX engine initialized with {len(kokoro.get_voices())} voices.")

    # Evaluate all male voices + top youth/high-energy voices
    all_voices = kokoro.get_voices()
    candidate_voices = [v for v in all_voices if v.startswith("am_") or v.startswith("bm_") or v.startswith("em_") or v.startswith("pm_") or v.startswith("af_")]
    print(f"\nEvaluating {len(candidate_voices)} candidate voices against Spidey reference...")

    test_phrase = "Hi, I'm Spider-Man! Thwip! Let's go save the city."
    voice_scores = {}

    for name in candidate_voices:
        try:
            v_tensor = kokoro.get_voice_style(name)
            audio, rate = kokoro.create(test_phrase, voice=v_tensor, speed=1.0)
            emb = compute_speaker_embedding(audio, rate, feature_extractor, wavlm)
            cos_sim = float(np.dot(ref_emb, emb))
            ac = analyze_audio(audio, rate)
            voice_scores[name] = {
                "cos_sim": cos_sim,
                "f0": ac["mean_f0"],
                "sc": ac["mean_sc"],
                "tensor": v_tensor
            }
            print(f"  {name:12s} -> Sim: {cos_sim:.4f}, Pitch: {ac['mean_f0']:.1f} Hz, Centroid: {ac['mean_sc']:.1f} Hz")
        except Exception as ex:
            print(f"  {name:12s} -> Error: {ex}")

    ranked = sorted(voice_scores.items(), key=lambda x: x[1]["cos_sim"], reverse=True)
    print("\nTop 5 Closest Pre-trained Voices to Spidey Reference:")
    for r_name, r_data in ranked[:5]:
        print(f"  {r_name:12s}: Cosine Similarity = {r_data['cos_sim']:.4f}, F0 = {r_data['f0']:.1f} Hz, Centroid = {r_data['sc']:.1f} Hz")

    # Take top 4 distinct male candidates for blending
    top_candidates = [r[0] for r in ranked if r[0].startswith("am_") or r[0].startswith("bm_") or r[0].startswith("em_") or r[0].startswith("pm_")][:4]
    print(f"\nRunning style optimization over top male bases: {top_candidates}...")

    # Optimize linear weights over the top candidates to maximize cosine similarity and match pitch
    def loss_fn(w):
        w = np.array(w)
        w = np.maximum(w, 0)
        s = np.sum(w)
        if s > 0:
            w = w / s
        blended = np.zeros_like(voice_scores[top_candidates[0]]["tensor"])
        for i, n in enumerate(top_candidates):
            blended += w[i] * voice_scores[n]["tensor"]
        
        try:
            audio, rate = kokoro.create(test_phrase, voice=blended, speed=1.05)
            emb = compute_speaker_embedding(audio, rate, feature_extractor, wavlm)
            sim = float(np.dot(ref_emb, emb))
            return -sim
        except Exception:
            return 0.0

    init_w = np.ones(len(top_candidates)) / len(top_candidates)
    res = minimize(loss_fn, init_w, method="Nelder-Mead", options={"maxiter": 30, "disp": False})
    opt_w = np.maximum(res.x, 0)
    opt_w = opt_w / np.sum(opt_w)

    print("\nOptimal Voice Blend Formulation:")
    for name, w_val in zip(top_candidates, opt_w):
        print(f"  {name:12s}: {w_val * 100:.1f}%")

    best_tensor = np.zeros_like(voice_scores[top_candidates[0]]["tensor"])
    for i, name in enumerate(top_candidates):
        best_tensor += opt_w[i] * voice_scores[name]["tensor"]

    # Synthesize final test audio with the optimized voice
    final_audio, final_rate = kokoro.create(
        "Hi, I'm Spider-Man! Thwip! Friendly neighborhood Spider-Man on duty.",
        voice=best_tensor,
        speed=1.06
    )
    final_emb = compute_speaker_embedding(final_audio, final_rate, feature_extractor, wavlm)
    final_sim = float(np.dot(ref_emb, final_emb))
    final_ac = analyze_audio(final_audio, final_rate)
    print(f"\nFinal Optimized Voice Stats:")
    print(f"  Cosine Similarity to Reference: {final_sim:.4f}")
    print(f"  Pitch (F0): {final_ac['mean_f0']:.1f} Hz")
    print(f"  Spectral Centroid: {final_ac['mean_sc']:.1f} Hz")

    # Extract row 128 (Kokoro row used by soniqo speech-android runtime)
    custom_row = best_tensor[ROW].reshape(256)
    packed_bytes = struct.pack("<256f", *custom_row.tolist())
    assert len(packed_bytes) == VOICE_BYTES

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_bin = out_dir / f"{args.voice_name}.bin"
    out_bin.write_bytes(packed_bytes)
    print(f"\nExported binary voice embedding to {out_bin} ({len(packed_bytes)} bytes)")

    # Save audition WAV
    audition_wav = Path(f"/home/nate/projects/code/android/rein_raus/{args.voice_name}_audition.wav")
    sf.write(str(audition_wav), final_audio, final_rate)
    print(f"Saved audition audio to {audition_wav}")

if __name__ == "__main__":
    main()
