package com.kendang.voicechanger.rvc

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Mesin voice conversion RVC (retrieval-based voice conversion, tanpa fitur retrieval/faiss) memakai ONNX Runtime.
 * Alur: audio 16 kHz -> ContentVec (fitur ucapan, 50 fps) -> RMVPE (nada, 100 fps) -> generator RVC -> audio [tgtSr].
 * Intonasi sumber ikut terbawa lewat kurva nada (dikali pitchSt semiton).
 *
 * BELUM diuji di HP. Nama input/output ONNX dicocokkan lewat daftar nama sesi; bila beda dari dugaan, pesan error
 * menyebut nama yang ditemukan.
 */
class RvcEngine(
    val tgtSr: Int,
    modelFile: File,
    contentVecFile: File,
    rmvpeFile: File,
    threads: Int = 4
) : AutoCloseable {
    companion object {
        const val IN_SR = 16000
        private const val VEC_HOP = 320      // 20 ms @16k
        private const val F0_HOP = 160       // 10 ms @16k
        private const val F0_MIN = 50.0
        private const val F0_MAX = 1100.0
    }

    @Volatile var pitchSt = 0f

    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val vec = env.createSession(contentVecFile.path, opts)
    private val f0net = env.createSession(rmvpeFile.path, opts)
    private val gen = env.createSession(modelFile.path, opts)

    /** Dimensi fitur (256 = RVC v1, 768 = RVC v2) dibaca dari input pertama generator; bila dinamis -> 768. */
    private val featDim: Int = try {
        val sh = (gen.inputInfo[gen.inputNames.first()]!!.info as TensorInfo).shape
        if (sh.size >= 3 && (sh[2] == 256L || sh[2] == 768L)) sh[2].toInt() else 768
    } catch (_: Exception) { 768 }
    /** RMVPE tipe "mel -> salience" (1 input [1,128,T]) butuh log-mel + decode di app; tipe "waveform+threshold" langsung f0. */
    private val rmvpeMel: Boolean = try {
        val nm = f0net.inputNames.first()
        val sh = (f0net.inputInfo[nm]!!.info as TensorInfo).shape
        f0net.inputNames.size == 1 && !nm.contains("wave", true) && sh.size == 3 && sh[1] != 1L
    } catch (_: Exception) { false }
    private val rnd = Random(1234)

    private val melMin = 1127.0 * ln(1.0 + F0_MIN / 700.0)
    private val melMax = 1127.0 * ln(1.0 + F0_MAX / 700.0)

    /** Ubah [x] (mono 16 kHz, -1..1) menjadi audio mono [tgtSr] dengan panjang ≈ x.size * tgtSr / 16000. */
    fun convert(x: FloatArray): FloatArray {
        if (x.isEmpty()) return FloatArray(0)
        // padding: 1 frame di awal & akhir (zero) lalu bulatkan ke kelipatan 320
        val padL = VEC_HOP
        var n = padL + x.size + VEC_HOP
        n = ((n + VEC_HOP - 1) / VEC_HOP) * VEC_HOP
        val a = FloatArray(n)
        System.arraycopy(x, 0, a, padL, x.size)

        val feats = contentVec(a)               // [T50][D]
        val f0 = pitch(a)                       // [T100]
        val t = minOf(feats.size * 2, f0.size)
        if (t < 4) return FloatArray(0)

        val d = featDim
        val phone = FloatArray(t * d)
        for (i in 0 until t) System.arraycopy(feats[i / 2], 0, phone, i * d, d) // upsample x2 (nearest)
        val coarse = LongArray(t)
        val pitchf = FloatArray(t)
        val k = 2.0.pow(pitchSt / 12.0)
        for (i in 0 until t) {
            val f = f0[i] * k
            pitchf[i] = f.toFloat()
            coarse[i] = if (f < 1.0) 1L else {
                val m = 1127.0 * ln(1.0 + f / 700.0)
                (((m - melMin) * 254.0 / (melMax - melMin)) + 1.0).roundToLong().coerceIn(1L, 255L)
            }
        }
        val noise = FloatArray(192 * t) { rnd.nextGaussian().toFloat() }

        val out = runGenerator(phone, coarse, pitchf, noise, t)
        // buang padding awal/akhir, sesuaikan panjang
        val skip = (padL.toLong() * tgtSr / IN_SR).toInt()
        val want = (x.size.toLong() * tgtSr / IN_SR).toInt()
        val res = FloatArray(want)
        val avail = (out.size - skip).coerceAtLeast(0)
        System.arraycopy(out, skip, res, 0, minOf(want, avail))
        return res
    }

    private fun contentVec(a: FloatArray): Array<FloatArray> {
        // Ekspor ContentVec ada yang 1 input (audio) dan ada yang gaya HuggingFace: input_values + attention_mask.
        val n = a.size.toLong()
        val tensors = HashMap<String, OnnxTensor>()
        try {
            for (name in vec.inputNames) {
                val info = vec.inputInfo[name]!!.info as TensorInfo
                tensors[name] = if (name.contains("mask", true)) {
                    val shp = longArrayOf(1, n)
                    when (info.type) {
                        OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(a.size) { 1L }), shp)
                        OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(a.size) { 1 }), shp)
                        OnnxJavaType.BOOL -> OnnxTensor.createTensor(env, arrayOf(BooleanArray(a.size) { true }))
                        else -> OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(a.size) { 1f }), shp)
                    }
                } else {
                    val shape = if (info.shape.size >= 3) longArrayOf(1, 1, n) else longArrayOf(1, n)
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(a), shape)
                }
            }
            vec.run(tensors).use { r ->
                val tens = r[0] as OnnxTensor
                val shp = (tens.info as TensorInfo).shape
                val flat = flatten(tens.value)
                val d = featDim
                if (flat.size % d != 0) throw IllegalStateException("ContentVec keluar ${flat.size} nilai, tidak habis dibagi dimensi $d (model ${featDim}-dim cocok?)")
                val t = flat.size / d
                // beberapa ekspor ContentVec berbentuk [1, D, T] (channel-first), yang lain [1, T, D]
                val channelFirst = shp.size == 3 && shp[1] == d.toLong() && shp[2] != d.toLong()
                return if (channelFirst) Array(t) { i -> FloatArray(d) { c -> flat[c * t + i] } }
                else Array(t) { i -> flat.copyOfRange(i * d, (i + 1) * d) }
            }
        } finally { tensors.values.forEach { it.close() } }
    }

    private fun pitchMel(a: FloatArray): FloatArray {
        val mel = RmvpeMel.logMel(a)                       // [128][n]
        val n = mel[0].size
        val tp = ((n - 1) / 32 + 1) * 32                   // RMVPE minta T kelipatan 32 (sisa diisi 0)
        val buf = FloatArray(128 * tp)
        for (m in 0 until 128) System.arraycopy(mel[m], 0, buf, m * tp, n)
        val name = f0net.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, 128, tp.toLong())).use { inp ->
            f0net.run(mapOf(name to inp)).use { r ->
                val h = flatten(r[0].value)
                if (h.size < n * 360 || h.size % 360 != 0) throw IllegalStateException("keluaran RMVPE ${h.size} nilai, tidak cocok [T,360] (T=$n)")
                val f = RmvpeMel.decode(h, n, 0.03f)
                for (i in f.indices) if (f[i] < F0_MIN.toFloat()) f[i] = 0f
                return f
            }
        }
    }

    private fun pitch(a: FloatArray): FloatArray {
        if (rmvpeMel) return pitchMel(a)
        val inputs = HashMap<String, OnnxTensor>()
        try {
            for (nm in f0net.inputNames) {
                inputs[nm] = if (nm.contains("thres", true)) {
                    val rank = (f0net.inputInfo[nm]!!.info as TensorInfo).shape.size
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.03f)), if (rank == 0) longArrayOf() else longArrayOf(1))
                }
                else OnnxTensor.createTensor(env, FloatBuffer.wrap(a), longArrayOf(1, a.size.toLong()))
            }
            f0net.run(inputs).use { r ->
                val f = flatten(r[0].value)
                for (i in f.indices) if (f[i] < F0_MIN.toFloat()) f[i] = 0f
                return f
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    private fun runGenerator(phone: FloatArray, coarse: LongArray, pitchf: FloatArray, noise: FloatArray, t: Int): FloatArray {
        val d = featDim.toLong()
        val tl = t.toLong()
        val tensors = HashMap<String, OnnxTensor>()
        try {
            tensors["phone"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(phone), longArrayOf(1, tl, d))
            tensors["phone_lengths"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(tl)), longArrayOf(1))
            tensors["pitch"] = OnnxTensor.createTensor(env, LongBuffer.wrap(coarse), longArrayOf(1, tl))
            tensors["pitchf"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(pitchf), longArrayOf(1, tl))
            tensors["ds"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1))
            tensors["rnd"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(noise), longArrayOf(1, 192, tl))
            val names = gen.inputNames.toList()
            val order = listOf("phone", "phone_lengths", "pitch", "pitchf", "ds", "rnd")
            // nama alternatif dari pengekspor lain: nsff0 = pitchf, sid = ds
            val alias = mapOf("nsff0" to "pitchf", "sid" to "ds")
            fun kunci(n: String) = alias[n] ?: n
            val feed: Map<String, OnnxTensor> = if (names.all { kunci(it) in tensors }) names.associateWith { tensors[kunci(it)]!! }
            else if (names.size == 6) names.mapIndexed { i, n -> n to tensors[order[i]]!! }.toMap() // nama lain (feats,p_len,...) -> urutan RVC standar
            else if (names.size == 5) names.mapIndexed { i, n -> n to tensors[order[i]]!! }.toMap() // tanpa rnd: 5 input pertama
            else throw IllegalStateException("input generator tidak dikenal: $names (butuh 5-6 input: phone, lengths, pitch, pitchf/nsff0, ds/sid, [rnd])")
            gen.run(feed).use { r -> return flatten(r[0].value) }
        } finally { tensors.values.forEach { it.close() } }
    }

    /** Ratakan array bersarang hasil ONNX (Array<Array<FloatArray>> dst.) menjadi FloatArray. */
    private fun flatten(v: Any?): FloatArray = when (v) {
        is FloatArray -> v
        is Array<*> -> {
            val parts = v.map { flatten(it) }
            val out = FloatArray(parts.sumOf { it.size })
            var p = 0
            for (x in parts) { System.arraycopy(x, 0, out, p, x.size); p += x.size }
            out
        }
        else -> throw IllegalStateException("tipe keluaran ONNX tidak dikenal: ${v?.javaClass}")
    }

    override fun close() {
        try { vec.close() } catch (_: Exception) {}
        try { f0net.close() } catch (_: Exception) {}
        try { gen.close() } catch (_: Exception) {}
        try { opts.close() } catch (_: Exception) {}
    }
}
