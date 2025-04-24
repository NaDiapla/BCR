package com.chiller3.bcr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.net.URL
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resumeWithException

private val JSON_INSTANCE = Json { ignoreUnknownKeys = true }
private const val TAG = "WebRTCClient"

class WebRTCClient(
    private val context: Context,
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val onIncomingEvent: (String) -> Unit
) {
    private val peerConnectionFactory: PeerConnectionFactory
    private val peerConnection: PeerConnection
    private val dataChannel: DatagramChannel
    private val negotiateJob = AtomicReference<Job?>()
    private val customAdm = CustomAudioDeviceModule(16000, 1)

    init {
        // 1) PeerConnectionFactory 초기화 (custom ADM 주입)
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(customAdm)
            .createPeerConnectionFactory()

        // 2) PeerConnection 생성
        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                peerConnection.addIceCandidate(c)
            }
            override fun onAddTrack(rtpReceiver: RtpReceiver, streams: Array<MediaStream>) { }
            override fun onSignalingChange(newState: PeerConnection.SignalingState) { }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { }
            override fun onIceConnectionReceivingChange(p0: Boolean) {

            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { }
            override fun onDataChannel(dc: DataChannel) { }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) { }
            override fun onAddStream(stream: MediaStream) { }
            override fun onRemoveStream(stream: MediaStream) { }
            override fun onRenegotiationNeeded() { }
        })!!

        // 3) DataChannel 생성 (bot event 수신용)
        dataChannel = peerConnection.createDataChannel("oai-events", DataChannel.Init())
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onIncomingEvent(String(bytes, Charsets.UTF_8))
            }
            override fun onStateChange() {}
            override fun onBufferedAmountChange(prev: Long) {}
        })
    }

    /** 1) Offer 생성 + 로컬 SDP 설정 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun createAndSendOffer(): SessionDescription = coroutineScope {
        // create offer
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            val mc = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            peerConnection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(e: String) = cont.resumeWithException(Exception(e))
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String) {}
            }, mc)
        }
        peerConnection.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() { }
            override fun onSetFailure(p0: String?): Unit {
                Log.e(TAG, "setLocalDesc fail: $p0")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, offer)

        // POST to OpenAI Realtime endpoint
        val answerSdp = withContext(Dispatchers.IO) {
            val url = URL("https://api.openai.com/v1/realtime?model=$model")
            (url.openConnection() as HttpsURLConnection).run {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/sdp")
                doOutput = true
                outputStream.write(offer.description.toByteArray())
                if (responseCode !in 200..299) {
                    throw Exception("HTTP ${responseCode}: ${errorStream.bufferedReader().readText()}")
                }
                inputStream.bufferedReader().readText()
            }
        }

        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { Log.i(TAG, "Remote SDP set") }
            override fun onSetFailure(e: String?): Unit {
                Log.e(TAG, "setRemoteDesc fail: $e")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, answer)
        offer
    }

    /** 연결 시작 */
    fun start() {
        if (negotiateJob.get() != null) return
        negotiateJob.set(GlobalScope.launch {
            try {
                createAndSendOffer()
            } catch (e: Exception) {
                Log.e(TAG, "Negotiate failed", e)
            }
        })
        customAdm.init()
    }

    /** raw 오디오 프레임을 푸시 */
    fun pushAudioFrame(pcm: ByteArray) {
        customAdm.pushFrame(pcm)
    }

    /** 종료 */
    fun stop() {
        negotiateJob.getAndSet(null)?.cancel()
        peerConnection.close()
        peerConnectionFactory.dispose()
        dataChannel.dispose()
        customAdm.terminate()
    }
}
