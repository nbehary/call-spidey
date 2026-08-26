#!/usr/bin/env python3
"""Generate optimized Spidey voice candidates for rein_raus based on Spidey_voice.mp3.

Generates:
1. spidey_match.bin: Top acoustic match to Spidey_voice.mp3 (high-energy/youth profile matching 300Hz reference)
2. spidey_male_blend.bin: Highest-matching male profile blend
3. spidey_hybrid.bin: Balanced youthful male Spider-Man blend
"""
import struct
import warnings
from pathlib import Path

import librosa
import numpy as np
from scipy.io import wavfile
import torch
import torch.nn.functional as F
from huggingface_hub import hf_hub_download
from transformers import Wav2Vec2FeatureExtractor, WavLMForXVector
from kokoro_onnx import Kokoro
from kokoro_onnx.config import MAX_PHONEME_LENGTH, SAMPLE_RATE

warnings.filterwarnings("ignore")

ROW = 128  # soniqo / speech-android row index
VOICE_BYTES = 256 * 4

def patch_kokoro(kokoro_inst):
    def _create_audio_patched(self, phonemes: str, voice: np.ndarray, speed: float):
        if len(phonemes) > MAX_PHONEME_LENGTH:
            phonemes = phonemes[:MAX_PHONEME_LENGTH]
        tokens = np.array(self.tokenizer.tokenize(phonemes), dtype=np.int64)
        tokens = [[0, *tokens, 0]]
        
        v = np.array(voice, dtype=np.float32)
        if v.ndim == 3:
            idx = min(len(tokens[0]) - 2, v.shape[0] - 1)
            style_vec = v[idx].reshape(1, 256)
        elif v.ndim == 2 and v.shape[0] > 1:
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

def save_voice_bin(tensor, path: Path):
    row = tensor[ROW].reshape(256)
    packed = struct.pack("<256f", *row.tolist())
    path.write_bytes(packed)
    print(f"  Wrote {path} ({len(packed)} bytes)")

def main():
    ref_path = Path("/home/nate/projects/ai/general/Spidey_voice.mp3")
    out_dir = Path("app/src/main/assets/voices/candidates")
    out_dir.mkdir(parents=True, exist_ok=True)

    print("Loading WavLM & Kokoro ONNX...")
    feature_extractor = Wav2Vec2FeatureExtractor.from_pretrained("microsoft/wavlm-base-plus-sv")
    wavlm = WavLMForXVector.from_pretrained("microsoft/wavlm-base-plus-sv").eval()

    ref_y, ref_sr = librosa.load(str(ref_path), sr=24000)
    ref_emb = compute_speaker_embedding(ref_y, ref_sr, feature_extractor, wavlm)

    onnx_model_path = hf_hub_download("onnx-community/Kokoro-82M-ONNX", "onnx/model.onnx")
    kokoro = Kokoro(onnx_model_path, "/tmp/voices-v1.0.bin")
    patch_kokoro(kokoro)

    test_phrase = "Hi, I'm Spider-Man! Thwip! Friendly neighborhood Spider-Man on duty."

    t_bella = kokoro.get_voice_style("af_bella")
    t_jessica = kokoro.get_voice_style("af_jessica")
    t_eric = kokoro.get_voice_style("am_eric")
    t_puck = kokoro.get_voice_style("am_puck")
    t_echo = kokoro.get_voice_style("am_echo")
    t_santa = kokoro.get_voice_style("pm_santa")

    # 1. Exact acoustic match (Youth/bright blend matching ~300Hz reference)
    # 50% Bella + 35% Jessica + 15% Eric
    t_match = 0.50 * t_bella + 0.35 * t_jessica + 0.15 * t_eric
    save_voice_bin(t_match, out_dir / "spidey_match.bin")
    audio_match, sr = kokoro.create(test_phrase, voice=t_match, speed=1.06)
    audio_match = np.squeeze(audio_match).astype(np.float32)
    sim_match = float(np.dot(ref_emb, compute_speaker_embedding(audio_match, sr, feature_extractor, wavlm)))
    wavfile.write("spidey_match_audition.wav", 24000, audio_match)
    print(f"  spidey_match: Similarity = {sim_match:.4f} -> spidey_match_audition.wav")

    # 2. Young male blend (Eric + Puck + Echo + Bella accent)
    # 40% Eric (highest pitch male), 30% Puck (playful), 20% Bella (brightness), 10% Echo
    t_hybrid = 0.40 * t_eric + 0.30 * t_puck + 0.20 * t_bella + 0.10 * t_echo
    save_voice_bin(t_hybrid, out_dir / "spidey_hybrid.bin")
    audio_hybrid, sr = kokoro.create(test_phrase, voice=t_hybrid, speed=1.08)
    audio_hybrid = np.squeeze(audio_hybrid).astype(np.float32)
    sim_hybrid = float(np.dot(ref_emb, compute_speaker_embedding(audio_hybrid, sr, feature_extractor, wavlm)))
    wavfile.write("spidey_hybrid_audition.wav", 24000, audio_hybrid)
    print(f"  spidey_hybrid: Similarity = {sim_hybrid:.4f} -> spidey_hybrid_audition.wav")

    # 3. Pure male blend (Puck + Eric + Echo + Santa)
    t_male = 0.35 * t_puck + 0.35 * t_eric + 0.15 * t_echo + 0.15 * t_santa
    save_voice_bin(t_male, out_dir / "spidey_male_blend.bin")
    audio_male, sr = kokoro.create(test_phrase, voice=t_male, speed=1.08)
    audio_male = np.squeeze(audio_male).astype(np.float32)
    sim_male = float(np.dot(ref_emb, compute_speaker_embedding(audio_male, sr, feature_extractor, wavlm)))
    wavfile.write("spidey_male_audition.wav", 24000, audio_male)
    print(f"  spidey_male: Similarity = {sim_male:.4f} -> spidey_male_audition.wav")

    print("\nAll candidate voice .bin files installed to app/src/main/assets/voices/candidates/")

if __name__ == "__main__":
    main()
