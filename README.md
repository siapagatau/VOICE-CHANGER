# VOICE-CHANGER — pengubah suara RVC, 100% lokal

Satu APK, tanpa server, tanpa repo model. Mesin: RVC (jaringan saraf) lewat ONNX Runtime di HP.

**Tiga cara pakai**
1. **Mikrofon live** — suara diubah dan diputar sebagai audio media, jadi perekam layar / app live dengan opsi **audio internal** (Android 10+) ikut merekamnya. Delay ± 1 detik. Pakai earphone.
2. **Teks → suara** — teks → Google TTS (butuh internet, hanya untuk suara dasar) → diubah RVC → diputar.

**Pemasangan (semua dari penyimpanan HP, sekali saja)**
1. Pilih `contentvec.onnx` dan `rmvpe.onnx` (mesin bersama, ± 700 MB total; versi int8/fp16 lebih kecil).
2. Tambah model suara `.onnx` hasil ekspor RVC. **APK tidak bisa membaca `.pth`**; ubah ke ONNX dulu dengan `tools/pth_to_onnx.py` di komputer/Colab: lihat `LATIH_RVC.md`.
3. Pilih mode, tekan MULAI LIVE, UCAPKAN, atau UBAH SUARA.

**Berkas audio (baru)** — pilih vokal lagu (mp3/wav/m4a/ogg/flac), opsional instrumental, tekan UBAH SUARA. Diproses per 8 detik di HP, hasil MP3 mono (192 kbps) bisa diputar lalu disimpan. Pakai vokal acapella (pisahkan dulu dari musiknya); lagu penuh langsung diubah akan terdengar rusak.

Build: push ke GitHub → Actions → `build-apk.yml` → artefak `apk-voicechanger-debug` (ID `com.dtxpads.voicechanger`).

## Batas yang harus diketahui
- **Kotlin belum pernah dikompilasi / diuji di HP.** Nama input-output ONNX dicocokkan dari dokumentasi pihak lain; bila beda, pesan galat menyebut nama yang ditemukan. Kirim log Actions bila build gagal.
- Kualitas bergantung data latih (target 5–10 menit ucapan bersih). Tanpa fitur retrieval (index faiss).
- RAM: ContentVec + RMVPE fp32 ± 700 MB + model ± 55–110 MB. HP RAM kecil bisa kehabisan memori. Kecepatan live belum diukur.
- Android tidak punya mikrofon virtual tanpa root: app live yang HANYA bisa memakai mikrofon tidak akan menerima suara hasil.
- Endpoint Google TTS tidak resmi; bisa dibatasi/diubah Google.
- Pakai suara milik sendiri atau yang pemiliknya mengizinkan; jangan menyamar sebagai orang sungguhan.

## Struktur
```
VoiceService.kt     semua audio (live + TTS + berkas) di foreground service
MainActivity.kt     UI Compose (3 langkah)
VoiceSettings.kt    setelan + status
data/LocalStore.kt  impor berkas .onnx dari HP + validasi
rvc/RvcEngine.kt    ContentVec + RMVPE + generator (ONNX Runtime)
rvc/RvcStream.kt    live: jendela bergeser + crossfade
audio/              TtsClient, AudioDecoder, AudioOut, FileConverter (berkas -> RVC -> WAV)
tools/prepare_dataset.py   siapkan audio latih
tools/pth_to_onnx.py       ubah model RVC .pth -> .onnx (komputer/Colab, bukan di HP)
```
