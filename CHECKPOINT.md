# CHECKPOINT — VOICE-CHANGER (v0.4.0, RVC saja, 100% lokal)
- [x] Mesin lama (DSP/phase vocoder), repo model GitHub, alat Python lama: DIHAPUS
- [x] UI 3 langkah (mesin → model → pakai), impor .onnx dari HP, validasi slot, sample rate per model
- [x] tools/pth_to_onnx.py (konversi .pth→.onnx, uji onnxruntime bawaan)
- [x] Generator 5 input (phone, phone_lengths, pitch, nsff0, sid; tanpa rnd) dari konverter web kini diterima selain yang 6 input (belum dikompilasi/diuji)
- [x] ContentVec gaya HuggingFace (input_values + attention_mask) kini diterima (belum dikompilasi/diuji)
- [x] RMVPE tipe mel→salience (1 input "input" [1,128,T]; rmvpe.onnx dari RVC asli): rvc/RmvpeMel.kt (log-mel + decode); logika dicek dengan cermin Python, Kotlin belum dikompilasi/diuji
- [x] rvc/RvcEngine.kt + RvcStream.kt, VoiceService (live + TTS), tools/prepare_dataset.py (diuji pada klip 37 dtk)
- [x] Mode "Berkas audio": upload vokal (+instrumental opsional) -> RVC per segmen (audio/FileConverter.kt) -> MP3 (LAME via JitPack, build GitHub Actions: Kotlin lolos kompilasi, manifest merger diperbaiki); putar & simpan. Logika sambung segmen dicek dengan simulasi Python; Kotlin belum dikompilasi/diuji
- [ ] BELUM: kompilasi Kotlin; uji dengan ONNX asli (nama/bentuk I/O, layout ContentVec); latih model dari data cukup; ukur kecepatan & RAM di HP; tools/pth_to_onnx.py sudah ditulis (lulus cek sintaks) tetapi BELUM dijalankan dengan model asli; faiss index
