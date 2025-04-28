package com.chiller3.bcr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log

class MusicPlayer(
    private val context: Context,
    private val sampleRate: Int,
    private val stereo: Boolean
) {
    private val channelConfig = if (stereo)
        AudioFormat.CHANNEL_OUT_STEREO
    else
        AudioFormat.CHANNEL_OUT_MONO

    private var audioTrack: AudioTrack? = null
    private var totalFramesWritten = 0
    private var onCompletionListener: (() -> Unit)? = null

    private var audioManager: AudioManager? = null

    /** Register a callback to be invoked when playback of the current buffer completes */
    fun setOnCompletionListener(listener: () -> Unit) {
        onCompletionListener = listener
    }

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (audioManager == null) audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager!!.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager!!.setParameters("phone_memo=phone_hold")

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
            .apply {
                // Listen for marker to signal completion
                setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) {
                        Log.d("MusicPlayer", "Playback completed at marker")
                        onCompletionListener?.invoke()
                    }
                    override fun onPeriodicNotification(track: AudioTrack?) {
                        // no-op
                    }
                }, Handler(Looper.getMainLooper()))
                play()
            }
        totalFramesWritten = 0
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
                return
            }
            // Calculate frames written and update marker for completion callback
            val bytesPerFrame = 2 * if (stereo) 2 else 1
            val frames = written / bytesPerFrame
            totalFramesWritten += frames
            try {
                track.setNotificationMarkerPosition(totalFramesWritten)
            } catch (e: Exception) {
                Log.w("MusicPlayer", "Failed to set marker position", e)
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
        audioManager = null
    }
}