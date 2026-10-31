package com.kendang.voicechanger.rvc

import kotlin.math.sqrt

/**
 * Mengubah aliran mikrofon 16 kHz menjadi aliran keluaran [RvcEngine.tgtSr] dengan jendela bergeser:
 * tiap blok [CHUNK] sampel diberi konteks kiri [CTX_L] dan kanan [CTX_R] supaya batas blok tidak "patah",
 * lalu disambung dengan crossfade pendek. Delay kira-kira (CHUNK + CTX_R) / 16000 detik + waktu hitung.
 * Bukan thread-safe: pakai dari satu thread pekerja.
 */
class RvcStream(private val engine: RvcEngine, private val silenceRms: Float = 0.003f) {
    companion object {
        const val CHUNK = 8000   // 0,5 dtk
        const val CTX_L = 4000   // 0,25 dtk
        const val CTX_R = 1600   // 0,1 dtk
    }

    private val sr = engine.tgtSr
    private val fade = sr / 100                      // 10 ms
    private var buf = FloatArray(0)                  // sampel 16k yang belum "diputuskan"
    private var bufStart = 0L                        // indeks absolut sampel pertama di buf
    private var next = 0L                            // awal blok berikutnya (absolut)
    private var tail: FloatArray? = null             // ekor blok sebelumnya (untuk crossfade)

    /** Masukkan sampel 16k; kembalikan keluaran siap putar (bisa kosong). */
    fun push(x: FloatArray, n: Int): FloatArray {
        val nb = FloatArray(buf.size + n)
        System.arraycopy(buf, 0, nb, 0, buf.size)
        System.arraycopy(x, 0, nb, buf.size, n)
        buf = nb
        val out = ArrayList<FloatArray>()
        while (bufStart + buf.size >= next + CHUNK + CTX_R) out.add(step())
        var total = 0
        for (o in out) total += o.size
        val r = FloatArray(total)
        var p = 0
        for (o in out) { System.arraycopy(o, 0, r, p, o.size); p += o.size }
        return r
    }

    private fun step(): FloatArray {
        val from = maxOf(bufStart, next - CTX_L)
        val to = next + CHUNK + CTX_R
        val win = buf.copyOfRange((from - bufStart).toInt(), (to - bufStart).toInt())
        var s = 0.0
        for (v in win) s += v * v
        val silent = sqrt(s / win.size) < silenceRms
        val lead = (next - from).toInt()                       // konteks kiri sebenarnya
        val o0 = (lead.toLong() * sr / RvcEngine.IN_SR).toInt()
        val len = (CHUNK.toLong() * sr / RvcEngine.IN_SR).toInt()
        val piece = FloatArray(len + fade)
        if (!silent) {
            val y = engine.convert(win)
            val e = minOf(y.size, o0 + len + fade)
            if (e > o0) System.arraycopy(y, o0, piece, 0, e - o0)
        }
        // crossfade dengan ekor blok sebelumnya
        val t = tail
        if (t != null) {
            for (i in 0 until fade) {
                val w = (i + 1f) / (fade + 1f)
                piece[i] = t[i] * (1f - w) + piece[i] * w
            }
        }
        tail = piece.copyOfRange(len, len + fade)
        next += CHUNK
        // buang sampel lama yang tidak lagi jadi konteks
        val keepFrom = next - CTX_L
        if (keepFrom > bufStart) {
            val cut = (keepFrom - bufStart).toInt().coerceAtMost(buf.size)
            buf = buf.copyOfRange(cut, buf.size)
            bufStart += cut
        }
        return piece.copyOfRange(0, len)
    }
}
