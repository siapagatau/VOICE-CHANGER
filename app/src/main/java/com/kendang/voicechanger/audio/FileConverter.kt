package com.kendang.voicechanger.audio

import android.content.Context
import android.net.Uri
import com.kendang.voicechanger.rvc.RvcEngine
import com.naman14.androidlame.LameBuilder
import java.io.File

/**
 * Ubah berkas audio (vokal lagu) dengan RVC, lalu simpan MP3 mono 192 kbps (LAME).
 *
 * Vokal diproses per segmen [SEG_SEC] detik dengan konteks kiri/kanan [CTX_SEC] supaya RAM tidak jebol
 * dan sambungan antar segmen tidak "patah" (crossfade pendek). Bila ada [instrumental], hasil vokal
 * dicampur ke instrumental (mono) di akhir.
 *
 * BELUM dikompilasi / diuji di HP.
 */
object FileConverter {
    private const val SEG_SEC = 8
    private const val CTX_SEC = 0.5f

    /** Salin berkas pilihan pengguna ke penyimpanan app (filesDir/audio) agar service tidak butuh izin URI. */
    fun copyToCache(ctx: Context, uri: Uri, name: String): File {
        val dir = File(ctx.filesDir, "audio").apply { mkdirs() }
        val f = File(dir, name)
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            ?: throw IllegalStateException("berkas tidak bisa dibuka")
        return f
    }

    fun outputFile(ctx: Context) = File(ctx.cacheDir, "hasil_voice.mp3")

    /**
     * @param progress dipanggil dengan (teks status, 0..1)
     * @param cancelled diperiksa tiap segmen
     * @return File MP3 hasil, atau null bila dibatalkan
     */
    fun convert(
        ctx: Context, eng: RvcEngine, vocal: File, instrumental: File?, gainDb: Float,
        progress: (String, Float) -> Unit, cancelled: () -> Boolean
    ): File? {
        progress("Membaca vokal…", 0f)
        val dec = AudioDecoder.decodeFile(vocal.path) { progress("Membaca vokal…", it * 0.05f) }
        if (dec.samples.isEmpty()) throw IllegalStateException("vokal kosong / format tidak didukung")
        val x = AudioDecoder.resample(dec.samples, dec.sampleRate, RvcEngine.IN_SR)
        if (cancelled()) return null

        val sr = eng.tgtSr
        val seg = SEG_SEC * RvcEngine.IN_SR
        val ctxN = (CTX_SEC * RvcEngine.IN_SR).toInt()
        val xf = sr / 50                                   // crossfade 20 ms (di sampel keluaran)
        val xf16 = xf * RvcEngine.IN_SR / sr + 1           // ekstra sampel 16k agar ekor crossfade ada isinya
        val total = x.size
        val vocalOut = FloatArray((total.toLong() * sr / RvcEngine.IN_SR).toInt())
        var wrote = 0                                      // sampel keluaran yang sudah final
        var tail: FloatArray? = null

        var start = 0
        while (start < total) {
            if (cancelled()) return null
            val end = minOf(total, start + seg)
            val a = maxOf(0, start - ctxN)
            val b = minOf(total, end + ctxN + xf16)
            progress("Mengubah suara ${(start.toLong() * 100 / total)}%…", 0.05f + 0.85f * start / total)
            val y = eng.convert(x.copyOfRange(a, b))
            val from = ((start - a).toLong() * sr / RvcEngine.IN_SR).toInt()
            val coreEnd = ((end - a).toLong() * sr / RvcEngine.IN_SR).toInt()
            val last = end >= total
            val hold = if (last) 0 else xf
            val upto = minOf(y.size, coreEnd + hold)
            val core = if (from < upto) y.copyOfRange(from, upto) else FloatArray(0)
            // crossfade ekor segmen sebelumnya dengan awal segmen ini
            val t = tail
            if (t != null && core.isNotEmpty()) {
                val n = minOf(t.size, core.size)
                for (i in 0 until n) { val w = (i + 1f) / (n + 1f); core[i] = t[i] * (1f - w) + core[i] * w }
            }
            val finalLen = if (last) core.size else maxOf(0, core.size - hold)
            val n = minOf(finalLen, vocalOut.size - wrote)
            if (n > 0) System.arraycopy(core, 0, vocalOut, wrote, n)
            wrote += maxOf(0, n)
            tail = if (!last && core.size > finalLen) core.copyOfRange(finalLen, core.size) else null
            start = end
        }

        val g = Math.pow(10.0, gainDb / 20.0).toFloat()
        var mix = vocalOut
        if (instrumental != null) {
            progress("Mencampur instrumental…", 0.92f)
            val ip = AudioDecoder.decodeFile(instrumental.path)
            val inst = AudioDecoder.resample(ip.samples, ip.sampleRate, sr)
            if (inst.size > mix.size) mix = mix.copyOf(inst.size)   // lagu = yang terpanjang
            for (i in inst.indices) mix[i] += inst[i]
        }
        // volume + softclip; normalisasi bila puncak > 1 (campuran bisa melebihi)
        var peak = 0f
        for (v in mix) { val a = Math.abs(v * g); if (a > peak) peak = a }
        val norm = if (peak > 0.98f) 0.98f / peak else 1f
        progress("Menyimpan MP3…", 0.96f)
        val out = outputFile(ctx)
        writeMp3(out, sr, mix, g * norm)
        progress("Selesai", 1f)
        return out
    }

    /** Enkode MP3 mono dari [data] dikali [scale] (soft-clip). MP3 tidak punya 40 kHz -> LAME mengubah ke 44,1 kHz. */
    private fun writeMp3(f: File, sr: Int, data: FloatArray, scale: Float) {
        val outSr = when (sr) { 32000, 44100, 48000 -> sr; else -> 44100 }
        val lame = LameBuilder()
            .setInSampleRate(sr)
            .setOutSampleRate(outSr)
            .setOutChannels(1)
            .setOutBitrate(192)
            .setQuality(2)
            .build()
        try {
            f.outputStream().buffered(256 * 1024).use { os ->
                val chunk = 8192
                val pcm = ShortArray(chunk)
                val mp3 = ByteArray((1.25 * chunk + 7200).toInt())
                var i = 0
                while (i < data.size) {
                    val m = minOf(chunk, data.size - i)
                    for (k in 0 until m) pcm[k] = (AudioOut.softClip(data[i + k] * scale) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    val n = lame.encode(pcm, pcm, m, mp3)
                    if (n < 0) throw IllegalStateException("enkode MP3 gagal ($n)")
                    if (n > 0) os.write(mp3, 0, n)
                    i += m
                }
                val n = lame.flush(mp3)
                if (n > 0) os.write(mp3, 0, n)
            }
        } finally { lame.close() }
    }
}
