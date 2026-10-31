#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ubah model suara RVC (.pth) menjadi .onnx generator yang dibaca APK VOICE-CHANGER.

Dijalankan di KOMPUTER / COLAB (butuh torch), bukan di HP.

Pakai:
    python3 pth_to_onnx.py NAMA.pth NAMA.onnx --rvc-dir <folder repo RVC>

--rvc-dir = folder hasil `git clone` Retrieval-based-Voice-Conversion-WebUI
(skrip memakai kelas SynthesizerTrnMsNSFsidM milik RVC itu sendiri).
Bila skrip ini dijalankan dari dalam folder RVC, --rvc-dir boleh dikosongkan.

Hasil: satu .onnx dengan 6 input (phone, phone_lengths, pitch, pitchf, ds, rnd)
dan 1 output (audio). Sample rate dicetak di akhir -> isikan di app bila bukan 40 kHz.

Catatan: BELUM diuji dengan model sungguhan. Bila galat, kirim pesan galat apa adanya.
"""
import argparse
import importlib
import inspect
import os
import sys

KANDIDAT_MODUL = (
    "infer.lib.infer_pack.models_onnx",   # RVC WebUI versi terbaru
    "lib.infer_pack.models_onnx",         # sebagian fork
    "infer_pack.models_onnx",             # RVC lama
)
NAMA_INPUT = ["phone", "phone_lengths", "pitch", "pitchf", "ds", "rnd"]


def keluar(pesan):
    print("\nGAGAL: " + pesan, file=sys.stderr)
    sys.exit(1)


def muat_kelas(rvc_dir):
    sys.path.insert(0, os.getcwd())
    if rvc_dir:
        rvc_dir = os.path.abspath(rvc_dir)
        if not os.path.isdir(rvc_dir):
            keluar("folder --rvc-dir tidak ada: " + rvc_dir)
        sys.path.insert(0, rvc_dir)
    galat = []
    for nama in KANDIDAT_MODUL:
        try:
            modul = importlib.import_module(nama)
            return getattr(modul, "SynthesizerTrnMsNSFsidM")
        except Exception as e:  # noqa: BLE001
            galat.append("  %s -> %s: %s" % (nama, type(e).__name__, e))
    keluar(
        "kelas SynthesizerTrnMsNSFsidM tidak ditemukan.\n"
        "Pastikan --rvc-dir menunjuk ke folder repo RVC (git clone "
        "https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI).\n"
        "Percobaan:\n" + "\n".join(galat)
    )


def muat_ckpt(torch, path):
    try:
        return torch.load(path, map_location="cpu", weights_only=False)
    except TypeError:  # torch lama tanpa weights_only
        return torch.load(path, map_location="cpu")


def sr_ke_hz(nilai):
    if isinstance(nilai, str):
        s = nilai.strip().lower().replace("k", "")
        return int(s) * 1000 if int(s) < 1000 else int(s)
    return int(nilai)


def bangun_model(kelas, cfg, versi, vec):
    """Bangun generator; tanda tangan kelas beda antar versi RVC, jadi dicoba beberapa cara."""
    param = inspect.signature(kelas.__init__).parameters
    kw = {"is_half": False}
    if "version" in param:
        kw["version"] = versi
    if "encoder_dim" in param:
        kw["encoder_dim"] = vec
    try:
        return kelas(*cfg, **kw)
    except TypeError as e:
        # coba tanpa 'version' (RVC yang tidak punya argumen itu)
        kw.pop("version", None)
        try:
            return kelas(*cfg, **kw)
        except TypeError:
            keluar("gagal membuat model: %s\n(versi RVC-mu mungkin berbeda; kirim pesan ini)" % e)


def main():
    ap = argparse.ArgumentParser(description="RVC .pth -> .onnx untuk APK VOICE-CHANGER")
    ap.add_argument("pth", help="model RVC kecil, mis. assets/weights/NAMA.pth")
    ap.add_argument("onnx", help="berkas hasil, mis. NAMA.onnx")
    ap.add_argument("--rvc-dir", default="", help="folder repo RVC (git clone)")
    ap.add_argument("--opset", type=int, default=17)
    ap.add_argument("--izinkan-v1", action="store_true",
                    help="tetap ekspor model v1 (256-dim). APK memakai ContentVec 768, jadi v1 TIDAK cocok.")
    a = ap.parse_args()

    if not os.path.isfile(a.pth):
        keluar("berkas tidak ada: " + a.pth)
    try:
        import torch
    except ImportError:
        keluar("torch belum terpasang. Di Colab sudah ada; di komputer: pip install torch")

    kelas = muat_kelas(a.rvc_dir)
    cpt = muat_ckpt(torch, a.pth)

    if not isinstance(cpt, dict) or "weight" not in cpt or "config" not in cpt:
        if isinstance(cpt, dict) and "model" in cpt:
            keluar("ini checkpoint LATIHAN (G_xxxx.pth di folder logs), bukan model kecil.\n"
                   "Pakai file hasil di assets/weights/NAMA.pth.")
        keluar("bukan model RVC kecil (tidak ada 'weight' dan 'config').")

    versi = cpt.get("version", "v1")
    f0 = cpt.get("f0", 1)
    vec = 256 if versi == "v1" else 768
    cfg = list(cpt["config"])
    cfg[-3] = cpt["weight"]["emb_g.weight"].shape[0]  # jumlah pembicara
    sr = sr_ke_hz(cfg[-1])
    print("Model: versi=%s  f0=%s  sample rate=%d Hz  pembicara=%d" % (versi, f0, sr, cfg[-3]))

    if int(f0) != 1:
        keluar("model ini dilatih TANPA pitch guidance; APK butuh model dengan pitch guidance (f0=1). Latih ulang.")
    if versi == "v1" and not a.izinkan_v1:
        keluar("model v1 (fitur 256-dim) tidak cocok dengan ContentVec 768 di APK.\n"
               "Latih ulang sebagai v2, atau pakai model v2 lain.")

    net_g = bangun_model(kelas, cfg, versi, vec)
    hasil = net_g.load_state_dict(cpt["weight"], strict=False)
    hilang = [k for k in getattr(hasil, "missing_keys", [])]
    if hilang:
        print("PERINGATAN: %d bobot hilang (contoh: %s). Hasil bisa rusak." % (len(hilang), hilang[:3]))
    net_g.eval()

    t = 200
    contoh = (
        torch.rand(1, t, vec),
        torch.tensor([t]).long(),
        torch.randint(size=(1, t), low=5, high=255),
        torch.rand(1, t),
        torch.LongTensor([0]),
        torch.rand(1, 192, t),
    )
    kw = dict(
        input_names=NAMA_INPUT,
        output_names=["audio"],
        dynamic_axes={"phone": [1], "pitch": [1], "pitchf": [1], "rnd": [2]},
        do_constant_folding=False,
        opset_version=a.opset,
        verbose=False,
    )
    if "dynamo" in inspect.signature(torch.onnx.export).parameters:
        kw["dynamo"] = False  # torch baru: pakai pengekspor klasik
    print("Mengekspor ke %s ..." % a.onnx)
    with torch.no_grad():
        torch.onnx.export(net_g, contoh, a.onnx, **kw)
    print("Selesai ekspor: %.1f MB" % (os.path.getsize(a.onnx) / 1e6))

    # ---- uji cepat dengan onnxruntime ----
    try:
        import numpy as np
        import onnxruntime as ort
    except ImportError:
        print("onnxruntime belum terpasang -> uji dilewati (pip install onnxruntime numpy).")
        print_akhir(sr)
        return
    sess = ort.InferenceSession(a.onnx, providers=["CPUExecutionProvider"])
    nama = [i.name for i in sess.get_inputs()]
    print("Input ONNX :", nama)
    print("Output ONNX:", [o.name for o in sess.get_outputs()])
    if nama != NAMA_INPUT:
        keluar("nama/jumlah input tidak sama dengan yang diminta APK: %s" % nama)
    for tt in (120, 200):  # dua panjang -> memastikan sumbu dinamis jalan
        feed = {
            "phone": np.random.rand(1, tt, vec).astype(np.float32),
            "phone_lengths": np.array([tt], dtype=np.int64),
            "pitch": np.random.randint(5, 255, (1, tt)).astype(np.int64),
            "pitchf": (np.random.rand(1, tt) * 300).astype(np.float32),
            "ds": np.array([0], dtype=np.int64),
            "rnd": np.random.rand(1, 192, tt).astype(np.float32),
        }
        audio = sess.run(None, feed)[0]
        print("Uji T=%d -> %d sampel (%.0f sampel/frame)" % (tt, audio.size, audio.size / tt))
    print_akhir(sr)


def print_akhir(sr):
    print("\nOK. Salin .onnx ini ke HP, lalu di app: + Tambah model (.onnx).")
    print("Sample rate model = %d Hz%s" % (sr, "" if sr == 40000 else "  <- ISI di app (bukan 40 kHz)"))


if __name__ == "__main__":
    main()
