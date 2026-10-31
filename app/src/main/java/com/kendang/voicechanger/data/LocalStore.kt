package com.kendang.voicechanger.data

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Penyimpanan 100% lokal. Pengguna memilih berkas dari HP; berkas disalin ke penyimpanan app:
 *   files/rvc/contentvec.onnx, files/rvc/rmvpe.onnx   (mesin bersama)
 *   files/rvc/voices/<nama>.onnx                        (model suara RVC hasil ekspor)
 * Fungsi di sini BLOKING (salin berkas besar): panggil dari thread latar.
 */
object LocalStore {
    enum class Kind(val fileName: String, val label: String) {
        CONTENTVEC("contentvec.onnx", "ContentVec"),
        RMVPE("rmvpe.onnx", "RMVPE"),
        VOICE("", "Model suara")
    }

    class Voice(val name: String, val file: File, val sr: Int)

    private const val PREFS = "voice_store"
    private val SR_CHOICES = intArrayOf(32000, 40000, 48000)

    private fun root(ctx: Context) = File(ctx.applicationContext.filesDir, "rvc")
    private fun voicesDir(ctx: Context) = File(root(ctx), "voices")
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun engineFile(ctx: Context, k: Kind) = File(root(ctx), k.fileName)
    fun hasEngine(ctx: Context, k: Kind) = engineFile(ctx, k).isFile
    fun engineReady(ctx: Context) = hasEngine(ctx, Kind.CONTENTVEC) && hasEngine(ctx, Kind.RMVPE)

    fun voices(ctx: Context): List<Voice> {
        val fs = voicesDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".onnx") } ?: return emptyList()
        return fs.sortedBy { it.name.lowercase() }.map {
            val n = it.name.removeSuffix(".onnx")
            Voice(n, it, srOf(ctx, n))
        }
    }

    fun find(ctx: Context, name: String): Voice? {
        val all = voices(ctx)
        return all.firstOrNull { it.name == name } ?: all.firstOrNull()
    }

    fun srOf(ctx: Context, name: String): Int {
        val saved = prefs(ctx).getInt("sr:$name", 0)
        if (saved in SR_CHOICES) return saved
        return when { name.contains("48") -> 48000; name.contains("32") -> 32000; else -> 40000 }
    }

    fun setSr(ctx: Context, name: String, sr: Int) { prefs(ctx).edit().putInt("sr:$name", sr).apply() }

    fun deleteVoice(ctx: Context, name: String) {
        File(voicesDir(ctx), "$name.onnx").delete()
        prefs(ctx).edit().remove("sr:$name").apply()
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i) ?: "" }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    /**
     * Salin [uri] ke penyimpanan app, periksa isinya, lalu pasang. Mengembalikan null bila sukses, atau pesan galat.
     * [progress] dipanggil dengan jumlah MB tersalin.
     */
    fun import(ctx: Context, uri: Uri, kind: Kind, progress: (Long) -> Unit): String? {
        val shown = displayName(ctx, uri)
        val lower = shown.lowercase()
        if (lower.endsWith(".pth") || lower.endsWith(".pt") || lower.endsWith(".ckpt") || lower.endsWith(".safetensors") || lower.endsWith(".index")) {
            return "Berkas $shown berformat PyTorch/lain. APK hanya membaca .onnx: ubah dulu di komputer (lihat LATIH_RVC.md, langkah 3)."
        }
        val dest: File
        if (kind == Kind.VOICE) {
            val base = shown.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48).ifBlank { "suara" }
            voicesDir(ctx).mkdirs()
            dest = File(voicesDir(ctx), "$base.onnx")
        } else {
            root(ctx).mkdirs()
            dest = engineFile(ctx, kind)
        }
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { os ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    var last = 0L
                    var head: ByteArray? = null
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        if (head == null && n >= 4) head = buf.copyOf(4)
                        os.write(buf, 0, n)
                        total += n
                        if (total - last >= 4L * 1024 * 1024) { last = total; progress(total / (1024 * 1024)) }
                    }
                    val h = head
                    if (h != null && ((h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte()) || h[0] == 0x80.toByte()))
                        return "Isi berkas ini format PyTorch (.pth), bukan ONNX. Ubah dulu di komputer (LATIH_RVC.md, langkah 3)."
                    if (total < 1024) return "Berkas terlalu kecil / kosong."
                }
            } ?: return "Tidak bisa membuka berkas."
            val err = check(kind, tmp)
            if (err != null) return err
            dest.delete()
            if (!tmp.renameTo(dest)) return "Gagal menyimpan berkas."
            if (kind == Kind.VOICE) setSr(ctx, dest.name.removeSuffix(".onnx"), srOf(ctx, dest.name.removeSuffix(".onnx")))
            return null
        } catch (e: Throwable) {
            return if (e is OutOfMemoryError) "Memori HP tidak cukup memeriksa berkas ini." else "Gagal: ${e.message}"
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Pastikan berkas ONNX sesuai slotnya (jumlah & nama input). Null = cocok. */
    private fun check(kind: Kind, f: File): String? {
        val env = OrtEnvironment.getEnvironment()
        val o = OrtSession.SessionOptions()
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        try {
            env.createSession(f.path, o).use { s ->
                val names = s.inputNames.toList()
                val melInput = names.size == 1 && s.inputInfo[names[0]]?.info.let { i ->
                    i is TensorInfo && i.shape.size == 3 && i.shape[1] != 1L } // [1,128,T] = RMVPE mel
                val isRmvpe = melInput || names.any { it.contains("wave", true) || it.contains("thres", true) } ||
                    (names.size == 2 && names.none { it.contains("mask", true) || it.contains("input_values", true) })
                return when (kind) {
                    Kind.VOICE -> if (names.size == 6 || names.size == 5) null else "Bukan model suara RVC: ${names.size} input (harus 6: phone, lengths, pitch, pitchf, ds, rnd; atau 5 tanpa rnd). Ekspor dari .pth dengan pengekspor yang mendukung RVC v2."
                    Kind.CONTENTVEC -> if (!isRmvpe && names.size in 1..3) null
                        else if (names.size >= 5) "Ini MODEL SUARA (input: $names), bukan ContentVec. Pasang di langkah 2 (+ Tambah model). Langkah 1 butuh contentvec.onnx (768, layer 12)."
                        else "Ini bukan ContentVec (input: $names). Pilih ekspor ContentVec 768 layer 12."
                    Kind.RMVPE -> if (isRmvpe && names.size <= 2) null else if (names.size >= 5) "Ini MODEL SUARA (input: $names), bukan RMVPE. Pasang di langkah 2." else "Ini bukan RMVPE (input: $names)."
                }
            }
        } catch (e: Throwable) {
            return if (e is OutOfMemoryError) "Memori HP tidak cukup memeriksa berkas ini." else "Bukan berkas ONNX yang valid: ${e.message}"
        } finally { o.close() }
    }
}
