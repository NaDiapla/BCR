package com.chiller3.bcr

import android.content.Context
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback

/**
 * JavaAudioDeviceModule을 래핑하여 AudioDeviceModule 인터페이스를 구현합니다.
 * 내부적으로는 WebRTC의 AudioDeviceModule 포인터를 그대로 사용하며,
 * 샘플 콜백을 통해 원본 PCM 데이터를 onFrameCaptured에 전달합니다.
 */
class CustomAudioDeviceModule(
    context: Context,
    sampleRate: Int = 16_000,
    channels: Int = 1,
    /** WebRTC가 캡처한 PCM 바이트를 받을 콜백 */
    private val onFrameCaptured: (pcm: ByteArray) -> Unit
) : AudioDeviceModule {

    // 실제 AudioDeviceModule 구현체를 JavaAudioDeviceModule로 생성
    private val delegate: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
        .setSampleRate(sampleRate)
        .setSamplesReadyCallback(SamplesReadyCallback { audioSamples ->
            // audioSamples.buffer는 ByteBuffer, 안에 PCM16 데이터
            val pcm: ByteArray = audioSamples.data
            onFrameCaptured(pcm)
        })
        .createAudioDeviceModule()

    override fun getNativeAudioDeviceModulePointer(): Long {
        // 네이티브 포인터는 내부 delegate가 관리
        return delegate.nativeAudioDeviceModulePointer
    }

    override fun release() {
        delegate.release()
    }

    override fun setSpeakerMute(mute: Boolean) {
        delegate.setSpeakerMute(mute)
    }

    override fun setMicrophoneMute(mute: Boolean) {
        delegate.setMicrophoneMute(mute)
    }

    override fun setNoiseSuppressorEnabled(enabled: Boolean): Boolean {
        return delegate.setNoiseSuppressorEnabled(enabled)
    }

    override fun setPreferredMicrophoneFieldDimension(dimension: Float): Boolean {
        return delegate.setPreferredMicrophoneFieldDimension(dimension)
    }
}