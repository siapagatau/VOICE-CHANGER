package com.kendang.voicechanger.audio

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

class Pcm(val samples: FloatArray, val sampleRate: Int)

/** Dekode mp3 (hasil Google TTS) ke PCM mono float lewat MediaCodec bawaan Android. */
object AudioDecoder {
    private class BytesSource(val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size, (data.size - position).toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    fun decode(bytes: ByteArray): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(BytesSource(bytes))
        return decodeFrom(ex, null)
    }

    /** Dekode berkas audio (mp3/wav/m4a/ogg/flac…) dari path lokal; [onProgress] 0..1 (perkiraan dari waktu sampel). */
    fun decodeFile(path: String, onProgress: ((Float) -> Unit)? = null): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        return decodeFrom(ex, onProgress)
    }

    private fun decodeFrom(ex: MediaExtractor, onProgress: ((Float) -> Unit)?): Pcm {
        var codec: MediaCodec? = null
        try {
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("tidak ada trek audio")
            ex.selectTrack(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 24000
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            var out = FloatArray(rate * 10)
            var outLen = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var guard = 0
            while (!outDone && guard < 5000000) {
                guard++
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            val st = ex.sampleTime
                            c.queueInputBuffer(ii, 0, sz, st, 0)
                            if (onProgress != null && durUs > 0) onProgress((st.toFloat() / durUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10000)
                if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val total = sb.remaining()
                        val frames = total / ch
                        if (outLen + frames > out.size) out = out.copyOf(maxOf(out.size * 2, outLen + frames))
                        for (f in 0 until frames) {
                            var s = 0f
                            for (k in 0 until ch) s += sb.get(f * ch + k) / 32768f
                            out[outLen++] = s / ch
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            return Pcm(out.copyOf(outLen), rate)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    /** Resample linear (cukup untuk ucapan); dipakai bila laju hasil dekode berbeda antar potongan. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val n = (x.size.toLong() * to / from).toInt()
        val out = FloatArray(n)
        val step = from.toDouble() / to
        if (step > 1.0) {
            // turun laju (mis. 44,1k -> 16k): rata-ratakan jendela agar tidak terjadi aliasing
            val half = step / 2.0
            for (i in 0 until n) {
                val c = (i + 0.5) * step
                val a = maxOf(0, (c - half).toInt())
                val b = minOf(x.size, maxOf(a + 1, (c + half).toInt()))
                var sum = 0f
                for (k in a until b) sum += x[k]
                out[i] = sum / (b - a)
            }
            return out
        }
        for (i in 0 until n) {
            val p = i * step
            val i0 = p.toInt()
            val t = (p - i0).toFloat()
            val a = x[minOf(i0, x.size - 1)]
            val b = x[minOf(i0 + 1, x.size - 1)]
            out[i] = a + t * (b - a)
        }
        return out
    }
}
