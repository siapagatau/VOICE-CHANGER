package com.kendang.voicechanger.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

object AudioOut {
    /**
     * AudioTrack bergaya MEDIA. Dengan android:allowAudioPlaybackCapture="true" di manifest, perekam layar /
     * app live yang punya "audio internal" (Android 10+) ikut menangkap suara ini (kebijakan capture bawaan = boleh).
     */
    fun create(sr: Int, minBytes: Int): AudioTrack {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(sr)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, minBytes))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Headset/earphone tersambung? (tanpa itu, suara speaker masuk lagi ke mikrofon -> berdengung). */
    fun headphonesConnected(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET -> return true
            }
        }
        return false
    }

    fun softClip(x: Float): Float {
        val a = Math.abs(x)
        if (a < 0.7f) return x
        val y = 0.7f + 0.3f * Math.tanh(((a - 0.7f) / 0.3f).toDouble()).toFloat()
        return if (x < 0f) -y else y
    }
}
