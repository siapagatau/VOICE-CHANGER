#!/usr/bin/env python3
"""Siapkan audio latih untuk RVC: mono, 40 kHz, high-pass, (opsional) peredam bising, dipotong di jeda, dinormalkan.

  python3 tools/prepare_dataset.py out_dataset/ rekaman1.mp3 rekaman2.wav ... [--denoise] [--sr 40000]

Hasil: out_dataset/NNN.wav (2-12 dtk). Zip folder itu / unggah ke Colab sebagai dataset RVC.
Butuh: ffmpeg, numpy, scipy.
"""
import argparse, os, subprocess, sys, tempfile
import numpy as np
from scipy import signal
from scipy.io import wavfile


def load(path, sr):
    with tempfile.TemporaryDirectory() as td:
        w = os.path.join(td, 'x.wav')
        r = subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', path, '-vn', '-ac', '1', '-ar', str(sr), '-c:a', 'pcm_f32le', w])
        if r.returncode: raise SystemExit(f'ffmpeg gagal membaca {path}')
        _, x = wavfile.read(w)
    return x.astype(np.float32)


def denoise(x, sr, strength=1.0):
    """Gerbang spektral stasioner: profil bising = 10% frame terlemah."""
    n = 1024
    f, t, Z = signal.stft(x, sr, nperseg=n, noverlap=n * 3 // 4)
    mag = np.abs(Z)
    e = mag.mean(axis=0)
    prof = mag[:, e <= np.quantile(e, 0.10)].mean(axis=1, keepdims=True)
    g = np.clip(1.0 - strength * prof / (mag + 1e-9), 0.1, 1.0)
    g = signal.medfilt2d(g.astype(np.float32), (1, 5))
    _, y = signal.istft(Z * g, sr, nperseg=n, noverlap=n * 3 // 4)
    return y[:len(x)].astype(np.float32)


def split(x, sr, lo=2.0, hi=12.0):
    fr = int(sr * 0.02)
    m = len(x) // fr
    rms = np.sqrt((x[:m * fr].reshape(m, fr) ** 2).mean(axis=1) + 1e-12)
    db = 20 * np.log10(rms + 1e-9)
    thr = max(np.percentile(db, 15) + 8.0, db.max() - 45.0)
    quiet = db < thr
    segs, start, qrun = [], 0, 0
    for i in range(m):
        qrun = qrun + 1 if quiet[i] else 0
        dur = (i - start) * 0.02
        if (qrun >= 12 and dur >= lo) or dur >= hi:     # jeda >= 240 ms atau terlalu panjang
            segs.append((start * fr, (i + 1) * fr)); start = i + 1; qrun = 0
    if m - start > lo / 0.02: segs.append((start * fr, m * fr))
    return segs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('out'); ap.add_argument('inputs', nargs='+')
    ap.add_argument('--sr', type=int, default=40000)
    ap.add_argument('--denoise', action='store_true')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    k, total = 0, 0.0
    for p in a.inputs:
        x = load(p, a.sr)
        dur = len(x) / a.sr
        sos = signal.butter(4, 70, 'hp', fs=a.sr, output='sos')
        x = signal.sosfilt(sos, x).astype(np.float32)
        if a.denoise: x = denoise(x, a.sr)
        peak = float(np.abs(x).max()) or 1.0
        x = x / peak * 0.89                                   # puncak -1 dBFS
        n0 = k
        for s, e in split(x, a.sr):
            seg = x[s:e]
            fade = int(0.01 * a.sr); w = np.ones(len(seg), np.float32)
            w[:fade] = np.linspace(0, 1, fade); w[-fade:] = np.linspace(1, 0, fade)
            wavfile.write(os.path.join(a.out, f'{k:03d}.wav'), a.sr, (seg * w * 32767).astype(np.int16))
            total += len(seg) / a.sr; k += 1
        print(f'{os.path.basename(p)}: {dur:.1f} dtk -> {k - n0} potongan')
    print(f'\nTotal {k} potongan, {total:.0f} dtk ({total / 60:.1f} menit) di {a.out}/')
    if total < 120: print('PERINGATAN: < 2 menit. RVC hampir pasti hasilnya jelek/mirip-seadanya. Target 5-10 menit ucapan bicara bersih.')
    elif total < 300: print('Catatan: 2-5 menit cukup untuk uji; 5-10 menit jauh lebih baik.')
    print('Pastikan isinya satu orang saja, tanpa musik/gema/suara lain. Tawa/teriak/nyanyi saja = hasil bias.')


if __name__ == '__main__':
    main()
