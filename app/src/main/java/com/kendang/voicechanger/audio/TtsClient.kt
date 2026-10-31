package com.kendang.voicechanger.audio

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TTS lewat endpoint translate.google.com (sama dengan contoh axios: GET arraybuffer + User-Agent).
 * Satu permintaan dibatasi ±200 karakter, jadi teks dipotong per kalimat/kata lalu tiap potongan diunduh sendiri.
 * Catatan: ini endpoint tidak resmi; Google bisa membatasi/mengubahnya kapan saja.
 */
object TtsClient {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private const val MAX_CHARS = 180

    val LANGS = listOf("id" to "Indonesia", "en" to "English", "ja" to "日本語")

    fun splitText(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        // pecah di tanda baca, simpan tandanya
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in clean) {
            sb.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == ':' || ch == ',') {
                parts.add(sb.toString().trim()); sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) parts.add(sb.toString().trim())
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out.add(cur.toString().trim()); cur.setLength(0) }
        for (p in parts) {
            if (p.length > MAX_CHARS) {
                flush()
                // potong per kata
                for (w in p.split(' ')) {
                    var word = w
                    while (word.length > MAX_CHARS) {            // kata super panjang: potong paksa
                        if (cur.isNotEmpty()) flush()
                        out.add(word.substring(0, MAX_CHARS)); word = word.substring(MAX_CHARS)
                    }
                    if (cur.length + word.length + 1 > MAX_CHARS) flush()
                    if (cur.isNotEmpty()) cur.append(' ')
                    cur.append(word)
                }
                flush()
            } else {
                if (cur.length + p.length + 1 > MAX_CHARS) flush()
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(p)
            }
        }
        flush()
        return out
    }

    /** BLOKING: panggil dari thread latar. */
    fun fetch(lang: String, chunk: String): ByteArray {
        val q = URLEncoder.encode(chunk, "UTF-8")
        val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$lang&q=$q"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) throw java.io.IOException("TTS HTTP ${c.responseCode}")
            val bos = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    bos.write(buf, 0, n)
                    if (bos.size() > 4 * 1024 * 1024) throw java.io.IOException("respons TTS terlalu besar")
                }
            }
            val b = bos.toByteArray()
            if (b.isEmpty()) throw java.io.IOException("TTS kosong")
            return b
        } finally {
            c.disconnect()
        }
    }
}
