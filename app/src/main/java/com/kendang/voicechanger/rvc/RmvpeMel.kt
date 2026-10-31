package com.kendang.voicechanger.rvc

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pra/pasca-proses RMVPE untuk ekspor ONNX bertipe "mel -> salience" (1 input [1,128,T], keluaran [1,T,360]),
 * sama seperti yang dipakai RVC asli: mel 128 pita, FFT 1024, hop 160 @16 kHz, lalu decode rata-rata lokal sen.
 */
object RmvpeMel {
    private const val SR = 16000.0
    private const val N_FFT = 1024
    private const val HOP = 160
    private const val N_MELS = 128
    private const val FMIN = 30.0
    private const val FMAX = 8000.0
    private const val BINS = 360
    private const val CENTS0 = 1997.3794084376191

    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2.0 * PI * it / N_FFT) }
    private val cosT = DoubleArray(N_FFT / 2) { cos(2.0 * PI * it / N_FFT) }
    private val sinT = DoubleArray(N_FFT / 2) { -sin(2.0 * PI * it / N_FFT) }
    private val rev = IntArray(N_FFT).also { r ->
        val bits = Integer.numberOfTrailingZeros(N_FFT)
        for (i in 0 until N_FFT) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    private fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)       // HTK
    private fun melToHz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)

    private class Basis(val w: Array<FloatArray>, val lo: IntArray, val hi: IntArray)

    /** Sama dengan librosa.filters.mel(sr=16000, n_fft=1024, n_mels=128, fmin=30, fmax=8000, htk=True) (norm slaney). */
    private val basis: Basis by lazy {
        val nb = N_FFT / 2 + 1
        val fft = DoubleArray(nb) { it * SR / N_FFT }
        val mMin = hzToMel(FMIN)
        val mMax = hzToMel(FMAX)
        val melF = DoubleArray(N_MELS + 2) { melToHz(mMin + (mMax - mMin) * it / (N_MELS + 1)) }
        val w = Array(N_MELS) { FloatArray(nb) }
        val lo = IntArray(N_MELS)
        val hi = IntArray(N_MELS)
        for (i in 0 until N_MELS) {
            val enorm = 2.0 / (melF[i + 2] - melF[i])
            var first = -1
            var last = -1
            for (k in 0 until nb) {
                val lower = (fft[k] - melF[i]) / (melF[i + 1] - melF[i])
                val upper = (melF[i + 2] - fft[k]) / (melF[i + 2] - melF[i + 1])
                val v = max(0.0, min(lower, upper)) * enorm
                if (v > 0.0) { w[i][k] = v.toFloat(); if (first < 0) first = k; last = k }
            }
            lo[i] = if (first < 0) 0 else first
            hi[i] = if (last < 0) -1 else last
        }
        Basis(w, lo, hi)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = N_FFT
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val c = cosT[k * step]
                    val s = sinT[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }

    private fun sample(a: FloatArray, idx: Int): Double {
        var j = idx
        val n = a.size
        if (j < 0) j = -j                  // pantulan (reflect) seperti torch.stft(center=True)
        if (j >= n) j = 2 * (n - 1) - j
        if (j < 0) j = 0
        if (j >= n) j = n - 1
        return a[j].toDouble()
    }

    /** log-mel [128][frames], frames = 1 + a.size / 160 (100 frame/detik). */
    fun logMel(a: FloatArray): Array<FloatArray> {
        val b = basis
        val frames = 1 + a.size / HOP
        val out = Array(N_MELS) { FloatArray(frames) }
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val mag = FloatArray(N_FFT / 2 + 1)
        for (f in 0 until frames) {
            val start = f * HOP - N_FFT / 2
            for (k in 0 until N_FFT) { re[k] = sample(a, start + k) * window[k]; im[k] = 0.0 }
            fft(re, im)
            for (k in mag.indices) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
            for (m in 0 until N_MELS) {
                var s = 0f
                val w = b.w[m]
                for (k in b.lo[m]..b.hi[m]) s += w[k] * mag[k]
                out[m][f] = ln(max(s, 1e-5f))
            }
        }
        return out
    }

    /** salience [t][360] (diratakan) -> f0 Hz per frame; 0 bila tidak bersuara. */
    fun decode(h: FloatArray, t: Int, thr: Float): FloatArray {
        val out = FloatArray(t)
        for (i in 0 until t) {
            val base = i * BINS
            var c = 0
            var mx = h[base]
            for (k in 1 until BINS) if (h[base + k] > mx) { mx = h[base + k]; c = k }
            if (mx <= thr) continue
            var ps = 0.0
            var ws = 0.0
            for (k in (c - 4)..(c + 4)) {
                if (k < 0 || k >= BINS) continue          // bin di luar rentang = bobot 0 (padding)
                val w = h[base + k].toDouble()
                ps += w * (20.0 * k + CENTS0)
                ws += w
            }
            if (ws <= 0.0) continue
            val cents = ps / ws
            out[i] = (10.0 * 2.0.pow(cents / 1200.0)).toFloat()
        }
        return out
    }
}
