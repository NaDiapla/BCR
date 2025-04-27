// MusicPlayer.kt
package com.chiller3.bcr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class MusicPlayer(
    private val sampleRate: Int,
    private val stereo: Boolean
) {
    private val channelConfig = if (stereo)
        AudioFormat.CHANNEL_OUT_STEREO
    else
        AudioFormat.CHANNEL_OUT_MONO

    private var audioTrack: AudioTrack? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }

        Log.d("MusicPlayer", "AudioTrack initialized (rate=$sampleRate, stereo=$stereo)")
    }

    /**
     * WebSocket에서 받은 raw PCM 바이트를 이 메서드로 전달하세요.
     */
    fun playPCM(pcmData: ByteArray) {
        audioTrack?.let { track ->
            val written = track.write(pcmData, 0, pcmData.size)
            if (written < 0) {
                Log.e("MusicPlayer", "AudioTrack write error: $written")
            }
        } ?: run {
            initAudioTrack()
            playPCM(pcmData)
        }
    }

    /** 재생 정지 및 리소스 해제 */
    fun stop() {
        audioTrack?.run {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.w("MusicPlayer", "Error stopping AudioTrack", e)
            }
        }
        audioTrack = null
    }
}
