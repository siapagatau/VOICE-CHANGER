package com.kendang.voicechanger

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Setelan pengguna (tersimpan). Dibaca langsung oleh thread audio, jadi cukup state sederhana. */
object VoiceSettings {
    private const val PREFS = "voice_settings"

    var voiceName by mutableStateOf("")
    var pitchSt by mutableStateOf(0f)       // semiton, -12..12
    var gateDb by mutableStateOf(-48f)      // gerbang bising mikrofon
    var gainDb by mutableStateOf(0f)        // volume keluaran
    var lang by mutableStateOf("id")
    var muted by mutableStateOf(false)
    var ttsText by mutableStateOf("")
    // mode berkas audio: berkas yang dipilih tetap ada walau pindah mode / app dibuka ulang
    var vocalPath by mutableStateOf("")
    var vocalName by mutableStateOf("")
    var instPath by mutableStateOf("")
    var instName by mutableStateOf("")

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        voiceName = p.getString("voiceName", "") ?: ""
        pitchSt = p.getFloat("pitchSt", 0f)
        gateDb = p.getFloat("gateDb", gateDb)
        gainDb = p.getFloat("gainDb", 0f)
        lang = p.getString("lang", lang) ?: lang
        ttsText = p.getString("ttsText", "") ?: ""
        vocalPath = p.getString("vocalPath", "") ?: ""
        vocalName = p.getString("vocalName", "") ?: ""
        instPath = p.getString("instPath", "") ?: ""
        instName = p.getString("instName", "") ?: ""
        if (vocalPath.isNotEmpty() && !java.io.File(vocalPath).isFile) { vocalPath = ""; vocalName = "" }
        if (instPath.isNotEmpty() && !java.io.File(instPath).isFile) { instPath = ""; instName = "" }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("voiceName", voiceName).putFloat("pitchSt", pitchSt)
            .putFloat("gateDb", gateDb).putFloat("gainDb", gainDb)
            .putString("lang", lang).putString("ttsText", ttsText)
            .putString("vocalPath", vocalPath).putString("vocalName", vocalName)
            .putString("instPath", instPath).putString("instName", instName)
            .apply()
    }
}

/** Status yang ditampilkan di layar (diisi service). */
object VoiceState {
    var live by mutableStateOf(false)
    var ttsBusy by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf("")
    var level by mutableStateOf(0f)
    // mode berkas audio
    var fileBusy by mutableStateOf(false)
    var fileProgress by mutableStateOf(0f)
    var fileResult by mutableStateOf("")   // path WAV hasil ("" = belum ada)
}
