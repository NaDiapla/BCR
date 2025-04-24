package com.chiller3.bcr

import org.webrtc.audio.AudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class CustomAudioDeviceModule(
    private val sampleRate: Int = 16000,
    private val channels: Int = 1
) : AudioDeviceModule {
    private var audioTransport: AudioTransport? = null
    private val frameQueue = LinkedBlockingQueue<ByteBuffer>()
    private val running = AtomicBoolean(false)
    private var pumpThread: Thread? = null

    /** RecorderThread에서 PCM을 푸시할 때 호출 */
    fun pushFrame(pcm: ByteArray) {
        frameQueue.offer(ByteBuffer.wrap(pcm))
    }

    override fun registerAudioCallback(transport: AudioTransport) {
        audioTransport = transport
    }

    override fun init() {
        running.set(true)
        pumpThread = Thread {
            while (running.get()) {
                val buf = frameQueue.take()
                val data = buf.array()
                audioTransport?.recordedDataIsAvailable(
                    data,
                    /* bitsPerSample= */ 16,
                    /* sampleRate= */ sampleRate,
                    /* numChannels= */ channels,
                    /* numFrames= */ data.size / (2 * channels),
                    /* totalDelayMs= */ 0,
                    /* clockDrift= */ 0,
                    /* volume= */ 1.0f,
                    /* keyPressed= */ false
                )
            }
        }.apply { start() }
    }

    override fun terminate() {
        running.set(false)
        pumpThread?.interrupt()
    }

    override fun isInitialized() = true

    // 녹음부 no-op (ADM 내부에서 직접 처리하므로)
    override fun startRecording() = true
    override fun stopRecording()  = true
    override fun recordingIsInitialized() = true
    override fun isRecording() = running.get()
    override fun setRecordingSampleRate(rate: Int) {}
    override fun recordingSampleRate() = sampleRate
    override fun initRecording() = true

    // 재생부는 더 이상 사용하지 않으므로 기본값
    override fun startPlayout() = true
    override fun stopPlayout() = true
    override fun playoutIsInitialized() = true
    override fun isPlayout() = true
    override fun setPlayoutSampleRate(rate: Int) {}
    override fun playoutSampleRate() = sampleRate
    override fun initPlayout() = true

    // 기타 no-op
    override fun release() {}
    override fun setStereoPlayout(enable: Boolean) {}
    override fun stereoPlayout() = false
    override fun setStereoRecording(enable: Boolean) {}
    override fun stereoRecording() = false
    override fun setPlayoutDelayBuffer(delay: Int) {}
    override fun playoutDelayBuffer() = 0
}
