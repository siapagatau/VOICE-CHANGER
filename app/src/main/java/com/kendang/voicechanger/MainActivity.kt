package com.kendang.voicechanger

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kendang.voicechanger.audio.AudioOut
import com.kendang.voicechanger.audio.FileConverter
import com.kendang.voicechanger.audio.TtsClient
import com.kendang.voicechanger.data.LocalStore

private val BgColor = Color(0xFF0F1012)
private val PanelColor = Color(0xFF1A1B1E)
private val BtnColor = Color(0xFF34363A)
private val TextDim = Color(0xFF9A9CA1)
private val Green = Color(0xFF3DDC84)
private val GreenText = Color(0xFF08200F)
private val Red = Color(0xFFE5534B)
private val Amber = Color(0xFFF2B84B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceSettings.load(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Green, onPrimary = GreenText, background = BgColor, surface = PanelColor)) {
                Box(Modifier.fillMaxSize().background(BgColor).statusBarsPadding().navigationBarsPadding()) { MainScreen() }
            }
        }
    }

    override fun onPause() {
        VoiceSettings.save(this)
        super.onPause()
    }
}

// ---------------- komponen kecil ----------------

@Composable
private fun Card(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelColor).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (hint != null) Text(hint, color = TextDim, fontSize = 12.sp)
        content()
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) Green else BtnColor, contentColor = if (selected) GreenText else Color.White),
        shape = RoundedCornerShape(10.dp)
    ) { Text(text, fontSize = 13.sp, maxLines = 1) }
}

@Composable
private fun Slide(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 13.sp)
            Text(fmt(value), color = TextDim, fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green)
        )
    }
}

@Composable
private fun FileRow(title: String, detail: String, ready: Boolean, busy: Boolean, button: String, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (ready) "✓" else "○", color = if (ready) Green else TextDim, fontSize = 18.sp)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(detail, color = TextDim, fontSize = 11.sp)
        }
        Chip(if (busy) "…" else button, false) { if (!busy) onPick() }
    }
}

// ---------------- layar utama ----------------

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }

    var refresh by remember { mutableIntStateOf(0) }            // naik = baca ulang daftar berkas
    var importMsg by remember { mutableStateOf("") }
    var importErr by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingKind by remember { mutableStateOf(LocalStore.Kind.VOICE) }
    var mode by remember { mutableIntStateOf(0) }               // 0 = mikrofon live, 1 = teks -> suara, 2 = berkas audio
    var advanced by remember { mutableStateOf(false) }

    val hasVec = remember(refresh) { LocalStore.hasEngine(ctx, LocalStore.Kind.CONTENTVEC) }
    val hasF0 = remember(refresh) { LocalStore.hasEngine(ctx, LocalStore.Kind.RMVPE) }
    val voices = remember(refresh) { LocalStore.voices(ctx) }
    val current = voices.firstOrNull { it.name == VoiceSettings.voiceName } ?: voices.firstOrNull()
    val ready = hasVec && hasF0 && current != null

    // pilih berkas dari penyimpanan HP -> salin di thread latar
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val kind = pendingKind
            busy = true; importErr = ""; importMsg = "Menyalin ${kind.label}…"
            Thread {
                val err = LocalStore.import(ctx, uri, kind) { mb ->
                    main.post { importMsg = "Menyalin ${kind.label}: $mb MB" }
                }
                main.post {
                    busy = false
                    importMsg = if (err == null) "${kind.label} siap" else ""
                    importErr = err ?: ""
                    if (err == null && kind == LocalStore.Kind.VOICE) {
                        val name = LocalStore.displayName(ctx, uri).substringBeforeLast('.')
                            .replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48)
                        if (LocalStore.voices(ctx).any { it.name == name }) VoiceSettings.voiceName = name
                    }
                    refresh++
                }
            }.start()
        }
    }
    fun pick(kind: LocalStore.Kind) { pendingKind = kind; picker.launch(arrayOf("*/*")) }

    // izin mikrofon + notifikasi
    var wantLive by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val micOk = r[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk) { if (wantLive) VoiceService.send(ctx, VoiceService.ACTION_LIVE_START) }
        else VoiceState.error = "Izin mikrofon ditolak"
        wantLive = false
    }
    fun startLive() {
        VoiceSettings.save(ctx)
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) VoiceService.send(ctx, VoiceService.ACTION_LIVE_START)
        else { wantLive = true; perm.launch(need.toTypedArray()) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text("Voice Changer", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Semua diproses di HP kamu, tanpa server.", color = TextDim, fontSize = 12.sp)
        }

        Card("1 · Mesin suara", "Pilih sekali saja. Dipakai semua model suara.") {
            FileRow("ContentVec", if (hasVec) "Terpasang" else "Berkas contentvec .onnx (768, layer 12)", hasVec, busy, if (hasVec) "Ganti" else "Pilih") { pick(LocalStore.Kind.CONTENTVEC) }
            FileRow("RMVPE (pelacak nada)", if (hasF0) "Terpasang" else "Berkas rmvpe .onnx", hasF0, busy, if (hasF0) "Ganti" else "Pilih") { pick(LocalStore.Kind.RMVPE) }
        }

        Card("2 · Model suara", "Pilih hasil latih RVC berformat .onnx. File .pth harus diubah ke .onnx dulu di komputer (lihat LATIH_RVC.md).") {
            if (voices.isEmpty()) Text("Belum ada model.", color = TextDim, fontSize = 13.sp)
            for (v in voices) {
                val sel = v.name == current?.name
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) Color(0xFF14301F) else BtnColor)
                        .clickable { VoiceSettings.voiceName = v.name; VoiceSettings.save(ctx) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (sel) "●" else "○", color = if (sel) Green else TextDim, fontSize = 16.sp)
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.name, color = Color.White, fontSize = 14.sp)
                        Text("${v.file.length() / (1024 * 1024)} MB · keluaran ${v.sr / 1000} kHz", color = TextDim, fontSize = 11.sp)
                    }
                }
            }
            if (current != null) {
                Text("Sample rate model (biasanya 40 kHz; lihat saat ekspor)", color = TextDim, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sr in intArrayOf(32000, 40000, 48000)) {
                        Chip("${sr / 1000} kHz", current.sr == sr, Modifier.weight(1f)) { LocalStore.setSr(ctx, current.name, sr); refresh++ }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(if (busy) "…" else "+ Tambah model (.onnx)", false, Modifier.weight(1f)) { if (!busy) pick(LocalStore.Kind.VOICE) }
                if (current != null) TextButton(onClick = {
                    LocalStore.deleteVoice(ctx, current.name)
                    if (VoiceSettings.voiceName == current.name) VoiceSettings.voiceName = ""
                    refresh++
                }) { Text("Hapus", color = Red) }
            }
        }

        if (importMsg.isNotEmpty()) Text(importMsg, color = TextDim, fontSize = 12.sp)
        if (importErr.isNotEmpty()) Text(importErr, color = Red, fontSize = 12.sp)

        Card("3 · Pakai") {
            if (!ready) {
                Text("Lengkapi langkah 1 dan 2 dulu.", color = Amber, fontSize = 13.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Mikrofon live", mode == 0, Modifier.weight(1f)) { mode = 0 }
                    Chip("Teks → suara", mode == 1, Modifier.weight(1f)) { mode = 1 }
                    Chip("Berkas audio", mode == 2, Modifier.weight(1f)) { mode = 2 }
                }
                when (mode) {
                    0 -> LivePanel(onStart = { startLive() })
                    1 -> TtsPanel()
                    else -> FilePanel()
                }
            }
            val st = VoiceState.status
            if (st.isNotEmpty() && (VoiceState.live || VoiceState.ttsBusy || VoiceState.fileBusy)) Text(st, color = TextDim, fontSize = 12.sp)
            if (VoiceState.error.isNotEmpty()) Text(VoiceState.error, color = Red, fontSize = 12.sp)
        }

        Card("Pengaturan suara") {
            Slide("Nada (semiton)", VoiceSettings.pitchSt, -12f..12f, { String.format("%+.0f", it) }) { VoiceSettings.pitchSt = Math.round(it).toFloat() }
            Text("Naik = lebih tinggi. Pria ke suara wanita biasanya +12, wanita ke pria −12. Biarkan 0 bila sudah pas.", color = TextDim, fontSize = 11.sp)
            Slide("Volume", VoiceSettings.gainDb, -12f..12f, { String.format("%+.0f dB", it) }) { VoiceSettings.gainDb = it }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Sembunyikan lanjutan" else "Lanjutan", color = Green) }
            if (advanced) {
                Slide("Penahan bising mikrofon", VoiceSettings.gateDb, -70f..-20f, { String.format("%.0f dB", it) }) { VoiceSettings.gateDb = it }
                Text("Makin tinggi (mis. −30) makin rapat: bising latar dibuang, tapi suara pelan ikut hilang.", color = TextDim, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LivePanel(onStart: () -> Unit) {
    val ctx = LocalContext.current
    val hp = remember(VoiceState.live) { AudioOut.headphonesConnected(ctx) }
    Button(
        onClick = { if (VoiceState.live) VoiceService.send(ctx, VoiceService.ACTION_LIVE_STOP) else onStart() },
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (VoiceState.live) Red else Green, contentColor = if (VoiceState.live) Color.White else GreenText),
        shape = RoundedCornerShape(16.dp)
    ) { Text(if (VoiceState.live) "BERHENTI" else "MULAI LIVE", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    if (VoiceState.live) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(BtnColor)) {
            Box(Modifier.fillMaxWidth(VoiceState.level.coerceIn(0f, 1f)).height(6.dp).background(Green))
        }
        Chip(if (VoiceSettings.muted) "Bisu AKTIF (ketuk untuk bicara)" else "Bisukan", VoiceSettings.muted, Modifier.fillMaxWidth()) {
            VoiceService.send(ctx, VoiceService.ACTION_MUTE)
        }
    }
    if (!hp) Text("Pakai earphone/headset, kalau tidak suara speaker masuk lagi ke mikrofon dan berdengung.", color = Amber, fontSize = 12.sp)
    Text("Delay sekitar 1 detik. Di perekam layar / app live pilih sumber audio internal (bukan mikrofon).", color = TextDim, fontSize = 11.sp)
}

@Composable
private fun TtsPanel() {
    val ctx = LocalContext.current
    OutlinedTextField(
        value = VoiceSettings.ttsText, onValueChange = { VoiceSettings.ttsText = it },
        modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Ketik teks") }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((code, name) in TtsClient.LANGS) Chip(name, VoiceSettings.lang == code, Modifier.weight(1f)) { VoiceSettings.lang = code }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { VoiceSettings.save(ctx); VoiceService.send(ctx, VoiceService.ACTION_SPEAK, VoiceSettings.ttsText, VoiceSettings.lang) },
            enabled = !VoiceState.ttsBusy && VoiceSettings.ttsText.isNotBlank(),
            modifier = Modifier.weight(1f).height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = GreenText),
            shape = RoundedCornerShape(14.dp)
        ) { Text("UCAPKAN", fontWeight = FontWeight.Bold) }
        Chip("Stop", false, Modifier.height(52.dp)) { VoiceService.send(ctx, VoiceService.ACTION_STOP_SPEAK) }
    }
    Text("Internet hanya dipakai mengambil suara dasar dari Google TTS; pengubahan suaranya tetap di HP.", color = TextDim, fontSize = 11.sp)
}

@Composable
private fun FilePanel() {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val vocalOk = VoiceSettings.vocalPath.isNotEmpty() && java.io.File(VoiceSettings.vocalPath).isFile
    val instOk = VoiceSettings.instPath.isNotEmpty() && java.io.File(VoiceSettings.instPath).isFile
    var copying by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }

    fun stopPlay() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null; playing = false
    }
    DisposableEffect(Unit) { onDispose { stopPlay() } }
    // Cadangan: selama "playing", cek pemutar tiap 300 ms; bila sudah berhenti sendiri, kembalikan tombol ke "Putar".
    LaunchedEffect(playing) {
        while (playing) {
            kotlinx.coroutines.delay(300)
            val alive = try { player?.isPlaying == true } catch (_: Exception) { false }
            if (!alive) { stopPlay(); break }
        }
    }

    fun importAudio(uri: Uri, fileName: String, onDone: (java.io.File, String) -> Unit) {
        copying = true; note = "Menyalin berkas…"
        Thread {
            try {
                val f = FileConverter.copyToCache(ctx, uri, fileName)
                val name = LocalStore.displayName(ctx, uri)
                main.post { copying = false; note = ""; onDone(f, name) }
            } catch (e: Throwable) {
                main.post { copying = false; note = ""; VoiceState.error = "Gagal menyalin berkas: ${e.message}" }
            }
        }.start()
    }

    val pickVocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importAudio(uri, "vokal_input") { f, n -> stopPlay(); VoiceSettings.vocalPath = f.path; VoiceSettings.vocalName = n; VoiceSettings.save(ctx); VoiceState.fileResult = ""; VoiceState.error = "" }
    }
    val pickInst = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importAudio(uri, "instrumental_input") { f, n -> VoiceSettings.instPath = f.path; VoiceSettings.instName = n; VoiceSettings.save(ctx) }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri: Uri? ->
        val src = java.io.File(VoiceState.fileResult)
        if (uri != null && src.isFile) {
            Thread {
                val err = try {
                    ctx.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
                    null
                } catch (e: Throwable) { e.message ?: "gagal" }
                main.post { note = if (err == null) "Tersimpan ✓" else ""; if (err != null) VoiceState.error = "Gagal menyimpan: $err" }
            }.start()
        }
    }

    Text("Pilih vokal (acapella) lagu. Hasilnya diubah ke model suara terpilih, lalu bisa diputar dan disimpan sebagai MP3.", color = TextDim, fontSize = 12.sp)
    FileRow("Vokal (wajib)", if (vocalOk) VoiceSettings.vocalName else "mp3 / wav / m4a / ogg / flac", vocalOk, copying || VoiceState.fileBusy, if (vocalOk) "Ganti" else "Pilih") {
        pickVocal.launch(arrayOf("audio/*"))
    }
    FileRow("Instrumental (opsional)", if (instOk) VoiceSettings.instName else "Dicampur ke hasil di akhir", instOk, copying || VoiceState.fileBusy, if (instOk) "Ganti" else "Pilih") {
        pickInst.launch(arrayOf("audio/*"))
    }
    if (instOk) TextButton(onClick = { VoiceSettings.instPath = ""; VoiceSettings.instName = ""; VoiceSettings.save(ctx) }) { Text("Lepas instrumental", color = Red) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                stopPlay()
                VoiceSettings.save(ctx)
                VoiceService.sendFile(ctx, VoiceSettings.vocalPath, if (instOk) VoiceSettings.instPath else null)
            },
            enabled = vocalOk && !VoiceState.fileBusy && !copying,
            modifier = Modifier.weight(1f).height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = GreenText),
            shape = RoundedCornerShape(14.dp)
        ) { Text("UBAH SUARA", fontWeight = FontWeight.Bold) }
        if (VoiceState.fileBusy) Chip("Batal", false, Modifier.height(52.dp)) { VoiceService.send(ctx, VoiceService.ACTION_FILE_STOP) }
    }
    if (VoiceState.fileBusy) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(BtnColor)) {
            Box(Modifier.fillMaxWidth(VoiceState.fileProgress.coerceIn(0.02f, 1f)).height(6.dp).background(Green))
        }
    }
    if (note.isNotEmpty()) Text(note, color = TextDim, fontSize = 12.sp)

    val res = VoiceState.fileResult
    if (res.isNotEmpty() && java.io.File(res).isFile && !VoiceState.fileBusy) {
        Text("Hasil siap", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (playing) "■ Stop" else "▶ Putar", playing, Modifier.weight(1f)) {
                if (playing) stopPlay() else try {
                    val mp = MediaPlayer()
                    mp.setDataSource(res)
                    mp.setOnCompletionListener { main.post { stopPlay() } }
                    mp.setOnErrorListener { _, _, _ -> main.post { stopPlay() }; true }
                    mp.prepare(); mp.start()
                    player = mp; playing = true
                } catch (e: Exception) { VoiceState.error = "Tidak bisa memutar: ${e.message}" }
            }
            Chip("Simpan MP3", false, Modifier.weight(1f)) { saveAs.launch("voice_hasil.mp3") }
        }
    }
    Text("Terbaik dengan vokal bersih tanpa musik (pisahkan dulu pakai aplikasi/situs pemisah vokal). Lagu penuh dengan musik di dalamnya akan terdengar rusak. Proses lagu 3–4 menit bisa makan waktu lama di HP; biarkan layar menyala.", color = TextDim, fontSize = 11.sp)
    Text("Hasil mono. Pakai suara milik sendiri atau yang pemiliknya mengizinkan.", color = TextDim, fontSize = 11.sp)
}
