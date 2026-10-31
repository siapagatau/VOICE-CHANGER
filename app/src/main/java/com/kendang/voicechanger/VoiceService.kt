package com.kendang.voicechanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kendang.voicechanger.audio.AudioDecoder
import com.kendang.voicechanger.audio.AudioOut
import com.kendang.voicechanger.audio.FileConverter
import com.kendang.voicechanger.audio.TtsClient
import com.kendang.voicechanger.data.LocalStore
import com.kendang.voicechanger.rvc.RvcEngine
import com.kendang.voicechanger.rvc.RvcStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pemilik semua audio: mic live (AudioRecord -> RVC -> AudioTrack MEDIA) dan TTS (Google -> dekode -> RVC -> AudioTrack).
 * Keluarannya AudioTrack bergaya MEDIA agar bisa ditangkap perekam layar / app live lewat "audio internal".
 */
class VoiceService : Service() {
    companion object {
        const val ACTION_LIVE_START = "com.kendang.voicechanger.LIVE_START"
        const val ACTION_LIVE_STOP = "com.kendang.voicechanger.LIVE_STOP"
        const val ACTION_MUTE = "com.kendang.voicechanger.MUTE_TOGGLE"
        const val ACTION_SPEAK = "com.kendang.voicechanger.SPEAK"
        const val ACTION_STOP_SPEAK = "com.kendang.voicechanger.STOP_SPEAK"
        const val ACTION_STOP_ALL = "com.kendang.voicechanger.STOP_ALL"
        const val ACTION_FILE_START = "com.kendang.voicechanger.FILE_START"
        const val ACTION_FILE_STOP = "com.kendang.voicechanger.FILE_STOP"
        const val EXTRA_VOCAL = "vocal"
        const val EXTRA_INST = "inst"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LANG = "lang"
        private const val CHANNEL_ID = "voice"
        private const val NOTIF_ID = 7

        fun sendFile(ctx: Context, vocal: String, inst: String?) {
            val i = Intent(ctx, VoiceService::class.java).setAction(ACTION_FILE_START).putExtra(EXTRA_VOCAL, vocal)
            if (inst != null) i.putExtra(EXTRA_INST, inst)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun send(ctx: Context, action: String, text: String? = null, lang: String? = null) {
            val i = Intent(ctx, VoiceService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            if (lang != null) i.putExtra(EXTRA_LANG, lang)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val ttsExec = Executors.newSingleThreadExecutor()
    private val ttsPending = AtomicInteger(0)
    @Volatile private var liveRun = false
    @Volatile private var ttsCancel = false
    @Volatile private var fileCancel = false
    private val fileExec = Executors.newSingleThreadExecutor()
    private val filePending = AtomicInteger(0)
    @Volatile private var ttsTrack: AudioTrack? = null
    private var liveThread: Thread? = null
    private var rvcCache: Pair<String, RvcEngine>? = null

    /** Satu mesin RVC dipakai bersama (live + TTS); dimuat sekali karena modelnya besar. */
    @Synchronized
    private fun rvcEngine(): RvcEngine {
        val v = LocalStore.find(this, VoiceSettings.voiceName)
            ?: throw IllegalStateException("Belum ada model suara. Tambahkan berkas .onnx di layar utama.")
        if (!LocalStore.engineReady(this)) throw IllegalStateException("ContentVec / RMVPE belum dipilih. Lengkapi langkah 1 di layar utama.")
        val key = "${v.file.path}|${v.file.lastModified()}|${v.sr}"
        rvcCache?.let { if (it.first == key) return it.second else { it.second.close(); rvcCache = null } }
        VoiceState.status = "Memuat model…"
        val e = RvcEngine(v.sr, v.file,
            LocalStore.engineFile(this, LocalStore.Kind.CONTENTVEC), LocalStore.engineFile(this, LocalStore.Kind.RMVPE),
            minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors() / 2)))
        rvcCache = key to e
        return e
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // startForegroundService() mewajibkan startForeground() cepat -> selalu panggil dulu.
        if (!enterForeground(action == ACTION_LIVE_START || liveRun)) return START_NOT_STICKY
        when (action) {
            ACTION_LIVE_START -> startLive()
            ACTION_LIVE_STOP -> { stopLive(); checkIdle() }
            ACTION_MUTE -> { VoiceSettings.muted = !VoiceSettings.muted; updateNotif() }
            ACTION_SPEAK -> speak(intent?.getStringExtra(EXTRA_TEXT) ?: "", intent?.getStringExtra(EXTRA_LANG) ?: "id")
            ACTION_STOP_SPEAK -> { ttsCancel = true; ttsTrack?.let { try { it.pause(); it.flush() } catch (_: Exception) {} } }
            ACTION_FILE_START -> startFile(intent?.getStringExtra(EXTRA_VOCAL), intent?.getStringExtra(EXTRA_INST))
            ACTION_FILE_STOP -> { fileCancel = true }
            ACTION_STOP_ALL -> { stopLive(); ttsCancel = true; fileCancel = true; shutdown() }
            else -> checkIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        liveRun = false
        ttsCancel = true
        fileCancel = true
        ttsExec.shutdownNow()
        fileExec.shutdownNow()
        try { rvcCache?.second?.close() } catch (_: Exception) {}
        rvcCache = null
        super.onDestroy()
    }

    // ---------------- foreground ----------------

    private fun enterForeground(mic: Boolean): Boolean {
        val n = buildNotif()
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                ServiceCompat.startForeground(this, NOTIF_ID, n, type)
            } else if (Build.VERSION.SDK_INT == 29) {
                ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            VoiceState.error = "Tidak bisa jalan di latar: ${e.message}"
            VoiceState.live = false
            stopSelf()
            false
        }
    }

    private fun buildNotif(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Voice Changer", NotificationManager.IMPORTANCE_LOW))
        }
        fun pi(action: String, code: Int): PendingIntent = PendingIntent.getService(
            this, code, Intent(this, VoiceService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when {
            liveRun && VoiceSettings.muted -> "Live: BISU"
            liveRun -> "Live: suara diubah"
            ttsPending.get() > 0 -> "Membaca teks…"
            filePending.get() > 0 -> "Mengubah berkas audio…"
            else -> "Siap"
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Voice Changer")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (liveRun) b.addAction(0, if (VoiceSettings.muted) "Aktifkan" else "Bisu", pi(ACTION_MUTE, 1))
        b.addAction(0, "Berhenti", pi(ACTION_STOP_ALL, 2))
        return b.build()
    }

    private fun updateNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif())
    }

    private fun checkIdle() {
        if (!liveRun && ttsPending.get() == 0 && filePending.get() == 0) shutdown()
    }

    private fun shutdown() {
        VoiceState.live = false
        VoiceState.level = 0f
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }

    // ---------------- LIVE ----------------

    private fun startLive() {
        if (liveRun) return
        VoiceState.error = ""
        liveRun = true
        VoiceState.live = true
        VoiceState.status = "Memuat model…"
        val t = Thread { runLive() }
        t.name = "voice-live"
        t.priority = Thread.MAX_PRIORITY
        liveThread = t
        t.start()
        updateNotif()
    }

    private fun stopLive() {
        liveRun = false
        val t = liveThread
        if (t != null && t !== Thread.currentThread()) { try { t.join(2500) } catch (_: InterruptedException) {} }
        liveThread = null
        VoiceState.live = false
        VoiceState.level = 0f
        if (VoiceState.error.isEmpty()) VoiceState.status = "Live berhenti"
    }

    /** Mic 16 kHz -> antrean -> RvcStream (thread pekerja) -> AudioTrack MEDIA. Delay ±1 dtk + waktu hitung. */
    private fun runLive() {
        val sr = RvcEngine.IN_SR
        val chunk = 1024
        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        var worker: Thread? = null
        val q = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
        try {
            val eng = rvcEngine()
            val minIn = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minIn <= 0) throw IllegalStateException("mikrofon tidak mendukung 16 kHz")
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minIn, chunk * 2 * 8))
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mikrofon tidak bisa dibuka (dipakai app lain / izin?)")
            val tr = AudioOut.create(eng.tgtSr, eng.tgtSr * 2)
            track = tr
            val stream = RvcStream(eng)
            VoiceState.status = "Live aktif"
            worker = Thread {
                val shorts = ShortArray(4096)
                while (liveRun) {
                    val b = (try { q.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }) ?: continue
                    // terlalu tertinggal (>~2,5 dtk): buang antrean lama agar delay tidak menumpuk
                    while (q.size > 40) { q.poll(); VoiceState.status = "HP terlalu lambat untuk model ini (suara terpotong)" }
                    eng.pitchSt = VoiceSettings.pitchSt
                    val y = try { stream.push(b, b.size) } catch (e: Throwable) { VoiceState.error = "RVC gagal: ${e.message}"; liveRun = false; break }
                    val g = Math.pow(10.0, VoiceSettings.gainDb / 20.0).toFloat()
                    var i = 0
                    var peak = 0f
                    while (i < y.size) {
                        val n = minOf(shorts.size, y.size - i)
                        for (k in 0 until n) {
                            val v = AudioOut.softClip(y[i + k] * g)
                            if (Math.abs(v) > peak) peak = Math.abs(v)
                            shorts[k] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                        tr.write(shorts, 0, n)
                        i += n
                    }
                    VoiceState.level = VoiceState.level * 0.7f + peak * 0.3f
                }
            }
            worker.name = "voice-rvc"
            worker.priority = Thread.MAX_PRIORITY
            worker.start()

            val inS = ShortArray(chunk)
            var gateG = 0f
            var hang = 0
            rec.startRecording()
            tr.play()
            while (liveRun) {
                val n = rec.read(inS, 0, chunk)
                if (n < 0) throw IllegalStateException("gagal membaca mikrofon ($n)")
                if (n == 0) continue
                val buf = FloatArray(n)
                var sum = 0.0
                for (i in 0 until n) { val v = inS[i] / 32768f; buf[i] = v; sum += v * v }
                val rms = sqrt(sum / n).toFloat()
                val thr = Math.pow(10.0, VoiceSettings.gateDb / 20.0).toFloat()
                if (rms > thr) hang = 16 else if (hang > 0) hang--
                val target = if (hang > 0 && !VoiceSettings.muted) 1f else 0f
                gateG += (target - gateG) * 0.35f
                for (i in 0 until n) buf[i] *= gateG
                q.offer(buf)
            }
        } catch (e: Throwable) {
            VoiceState.error = if (e is OutOfMemoryError) "Memori HP tidak cukup untuk model ini (coba ContentVec/RMVPE versi int8/fp16)" else "Live gagal: ${e.message}"
        } finally {
            liveRun = false
            try { worker?.join(2000) } catch (_: InterruptedException) {}
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            VoiceState.live = false
            VoiceState.level = 0f
            main.post { checkIdle() }
        }
    }

    // ---------------- TTS ----------------

    private fun speak(text: String, lang: String) {
        if (text.isBlank()) { VoiceState.error = "Teks kosong"; checkIdle(); return }
        VoiceState.error = ""
        ttsCancel = false
        ttsPending.incrementAndGet()
        VoiceState.ttsBusy = true
        VoiceState.status = "Memuat model…"
        updateNotif()
        try {
            ttsExec.execute { runTts(text, lang) }
        } catch (e: Exception) {
            ttsPending.decrementAndGet(); VoiceState.ttsBusy = false
        }
    }

    /** Teks -> Google TTS (tiap potongan) -> RVC -> diputar lewat AudioTrack MEDIA. */
    private fun runTts(text: String, lang: String) {
        var track: AudioTrack? = null
        try {
            val chunks = TtsClient.splitText(text)
            if (chunks.isEmpty()) throw IllegalStateException("teks kosong")
            val eng = rvcEngine()
            eng.pitchSt = VoiceSettings.pitchSt
            track = AudioOut.create(eng.tgtSr, eng.tgtSr * 2)
            ttsTrack = track
            track.play()
            val g = Math.pow(10.0, VoiceSettings.gainDb / 20.0).toFloat()
            var written = 0L
            val gap = ShortArray(eng.tgtSr * 12 / 100)
            for ((i, c) in chunks.withIndex()) {
                if (ttsCancel) return
                VoiceState.status = "Unduh suara ${i + 1}/${chunks.size}…"
                val pcm = AudioDecoder.decode(TtsClient.fetch(lang, c))
                val x = AudioDecoder.resample(pcm.samples, pcm.sampleRate, RvcEngine.IN_SR)
                if (ttsCancel) return
                VoiceState.status = "Mengubah suara ${i + 1}/${chunks.size}…"
                val y = eng.convert(x)
                val sh = ShortArray(y.size) { (AudioOut.softClip(y[it] * g) * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                VoiceState.status = "Memutar…"
                track.write(sh, 0, sh.size); written += sh.size
                track.write(gap, 0, gap.size); written += gap.size
            }
            if (!ttsCancel) {
                track.stop() // sisa buffer tetap diputar sampai habis
                val t0 = System.currentTimeMillis()
                while (!ttsCancel && track.playbackHeadPosition < written && System.currentTimeMillis() - t0 < 30000) Thread.sleep(30)
            }
            VoiceState.status = if (ttsCancel) "Dihentikan" else "Selesai"
        } catch (e: Throwable) {
            VoiceState.error = if (e is OutOfMemoryError) "Memori HP tidak cukup untuk model ini" else "TTS gagal: ${e.message}"
        } finally {
            ttsTrack = null
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            val left = ttsPending.decrementAndGet()
            if (left <= 0) VoiceState.ttsBusy = false
            main.post { checkIdle() }
        }
    }

    // ---------------- BERKAS AUDIO ----------------

    private fun startFile(vocal: String?, inst: String?) {
        if (vocal == null) { VoiceState.error = "Pilih berkas vokal dulu"; checkIdle(); return }
        if (filePending.get() > 0) return
        VoiceState.error = ""
        VoiceState.fileResult = ""
        VoiceState.fileProgress = 0f
        VoiceState.fileBusy = true
        fileCancel = false
        filePending.incrementAndGet()
        VoiceState.status = "Memuat model…"
        updateNotif()
        try {
            fileExec.execute { runFile(java.io.File(vocal), inst?.let { java.io.File(it) }) }
        } catch (e: Exception) {
            filePending.decrementAndGet(); VoiceState.fileBusy = false
        }
    }

    /** Berkas vokal (+ instrumental opsional) -> RVC per segmen -> WAV di cache. */
    private fun runFile(vocal: java.io.File, inst: java.io.File?) {
        try {
            val eng = rvcEngine()
            eng.pitchSt = VoiceSettings.pitchSt
            val out = FileConverter.convert(this, eng, vocal, inst, VoiceSettings.gainDb,
                { msg, p -> VoiceState.status = msg; VoiceState.fileProgress = p }, { fileCancel })
            if (out == null) VoiceState.status = "Dihentikan"
            else { VoiceState.fileResult = out.path; VoiceState.status = "Selesai" }
        } catch (e: Throwable) {
            VoiceState.error = if (e is OutOfMemoryError) "Memori HP tidak cukup (coba lagu lebih pendek atau ContentVec/RMVPE int8/fp16)" else "Ubah berkas gagal: ${e.message}"
        } finally {
            VoiceState.fileBusy = false
            filePending.decrementAndGet()
            main.post { checkIdle() }
        }
    }
}
