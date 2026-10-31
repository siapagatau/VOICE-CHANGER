# Dari audio sampai model .onnx untuk APK

Pelatihan dilakukan **sekali per suara di komputer/Colab ber-GPU**, bukan di HP.

## 1. Data latih
- Target **5–10 menit ucapan bicara bersih**, satu orang, tanpa musik/gema. Tawa/teriak/nyanyi saja = hasil bias; 37 detik = hasil jelek.
- `python3 tools/prepare_dataset.py dataset/ rekaman1.mp3 rekaman2.wav --denoise` → potongan 2–12 dtk, 40 kHz mono. (Butuh ffmpeg, numpy, scipy.)
- Pakai suara milik sendiri atau yang pemiliknya mengizinkan.

## 2. Latih (RVC WebUI, Colab)
github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI (MIT). Tab **Train**: versi **v2**, sample rate **40k**, pitch guidance **ya**, extractor **rmvpe_gpu**, dataset = folder `dataset/`, epoch 200–300 (data sedikit: jangan berlebihan), lalu **Process data → Feature extraction → Train model**. Hasil: `assets/weights/<nama>.pth`. (Langkah persis bisa berubah antarversi RVC.)

## 3. Ubah .pth → .onnx (wajib; APK tidak membaca .pth)
Pakai `tools/pth_to_onnx.py` (di komputer/Colab, butuh torch). Syarat: model **v2** dengan **pitch guidance**; model v1 ditolak karena APK memakai ContentVec 768.

Di Colab (sel baru, sekali jalan). Unggah `NAMA.pth` dan `pth_to_onnx.py` ke panel Files Colab dulu:
```
!git clone --depth 1 https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI rvc
!pip -q install onnx onnxruntime
!python pth_to_onnx.py NAMA.pth NAMA.onnx --rvc-dir rvc
from google.colab import files; files.download("NAMA.onnx")
```
Skrip mencetak versi, sample rate, nama input ONNX, dan menjalankan uji kecil. Bila tertulis `OK`, salin `NAMA.onnx` ke HP. Catat sample rate yang dicetak (biasanya 40000).
**Skrip ini belum diuji dengan model sungguhan.** Bila galat, kirim pesan galatnya apa adanya.

## 4. Mesin bersama (sekali)
`contentvec.onnx` (ContentVec 768, layer 12) dan `rmvpe.onnx`. Ekspor ONNX berlisensi MIT ada mis. di Hugging Face `TigreGotico/voiceclonnx-rvc` (RMVPE fp32 345 MB; int8 `rmvpe_q8.onnx` 94 MB, ganti nama jadi `rmvpe.onnx` untuk HP RAM kecil). ContentVec: `vec-768-layer-12.onnx` (mis. dari MoeSS). **Isinya belum saya verifikasi**; APK memeriksa jumlah input dan menolak berkas yang salah slot.

## 5. Di APK
Salin tiga berkas ke HP (kabel/Drive), buka app: langkah 1 pilih contentvec + rmvpe, langkah 2 **+ Tambah model (.onnx)**, atur sample rate bila bukan 40 kHz.
