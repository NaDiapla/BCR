package com.chiller3.bcr

import android.content.Context
import android.util.Log
import ai.pipecat.client.RTVIClient
import ai.pipecat.client.RTVIClientOptions
import ai.pipecat.client.RTVIClientParams
import ai.pipecat.client.RTVIEventCallbacks
import ai.pipecat.client.openai_realtime_webrtc.OpenAIRealtimeSessionConfig
import ai.pipecat.client.openai_realtime_webrtc.OpenAIRealtimeWebRTCTransport
import ai.pipecat.client.result.Future
import ai.pipecat.client.result.RTVIError
import ai.pipecat.client.transport.MsgServerToClient
import ai.pipecat.client.types.*

class CallAIManager(
    private val context: Context,
    //private val apiKey: String
) {
    companion object {
        private const val TAG = "CallAIManager"
    }

    private var client: RTVIClient? = null

    private val apiKey = ""

    // 내부 에러 로깅용 extension
    private fun <E> Future<E, RTVIError>.displayErrors() = withErrorCallback { err ->
        Log.e(TAG, "RTVI error: ${err.description}", err.exception)
    }
/** WebRTC 세션 시작 */

    fun start() {
        if (client != null) return

        // 1) 옵션 구성
        val options = RTVIClientOptions(
            params = RTVIClientParams(
                baseUrl = null,
                config = OpenAIRealtimeWebRTCTransport.buildConfig(
                    apiKey = apiKey,
                    initialConfig = OpenAIRealtimeSessionConfig(
                        turnDetection          = Value.Object("type" to Value.Str("semantic_vad")),
                        inputAudioNoiseReduction = Value.Object("type" to Value.Str("near_field")),
                        inputAudioTranscription  = Value.Object("model" to Value.Str("gpt-4o-transcribe"))
                    )
                )
            )
        )

        // 2) 이벤트 콜백 정의
        val callbacks = object : RTVIEventCallbacks() {
            override fun onTransportStateChanged(state: TransportState) {
                Log.i(TAG, "Transport state: $state")
            }
            override fun onBackendError(message: String) {
                Log.e(TAG, "Backend error: $message")
            }
            override fun onBotReady(version: String, config: List<ServiceConfig>) {
                Log.i(TAG, "Bot ready: version=$version, config=$config")
            }
            override fun onPipecatMetrics(data: PipecatMetrics) {
                Log.i(TAG, "Metrics: $data")
            }
            override fun onBotTTSText(data: MsgServerToClient.Data.BotTTSTextData) {
                Log.i(TAG, "Bot TTS text: ${data.text}")
            }
            override fun onUserTranscript(data: Transcript) {
                Log.i(TAG, "User transcript: ${data.text}")
            }
            override fun onBotStartedSpeaking() {
                Log.i(TAG, "Bot started speaking")
            }
            override fun onBotStoppedSpeaking() {
                Log.i(TAG, "Bot stopped speaking")
            }
            override fun onUserStartedSpeaking() {
                Log.i(TAG, "User started speaking")
            }
            override fun onUserStoppedSpeaking() {
                Log.i(TAG, "User stopped speaking")
            }
            override fun onTracksUpdated(tracks: Tracks) {
                Log.i(TAG, "Tracks updated: $tracks")
            }
            override fun onInputsUpdated(camera: Boolean, mic: Boolean) {
                Log.i(TAG, "Inputs updated – camera: $camera, mic: $mic")
            }
            override fun onConnected() {
                Log.i(TAG, "Connected to OpenAI Realtime")
            }
            override fun onDisconnected() {
                Log.i(TAG, "Disconnected from OpenAI Realtime")
                client = null
            }
            override fun onUserAudioLevel(level: Float) {
                Log.i(TAG, "User level: $level")
            }
            override fun onRemoteAudioLevel(level: Float, participant: Participant) {
                Log.i(TAG, "Remote level: $level from $participant")
            }
        }

        // 3) 클라이언트 생성 및 연결
        client = RTVIClient(
            OpenAIRealtimeWebRTCTransport.Factory(context),
            callbacks,
            options
        ).also { rtcClient ->
            rtcClient.connect()
                .displayErrors()
                .withErrorCallback {
                    callbacks.onDisconnected()
                }
        }
    }

/** 마이크 온/오프 토글 */

    fun enableMic(enabled: Boolean) {
        client?.enableMic(enabled)
            ?.displayErrors()
    }


/** 세션 종료 */

    fun stop() {
        client?.disconnect()
            ?.displayErrors()
    }

    fun sendAudioData(pcm: ByteArray) {

    }
}