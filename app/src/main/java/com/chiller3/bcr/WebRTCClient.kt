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
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resumeWithException

private val JSON_INSTANCE = Json { ignoreUnknownKeys = true }
private const val TAG = "WebRTCClient"

class WebRTCClient(
    private val context: Context,
    private val model: String = "gpt-4o",
    //private val onIncomingEvent: (String) -> Unit
) {
    private var peerConnectionFactory: PeerConnectionFactory
    private lateinit var peerConnection: PeerConnection
    private var dataChannel: DataChannel

    private val negotiateJob = AtomicReference<Job?>(null)

    private val apiKey = ""

    private val customAdm = CustomAudioDeviceModule(
        context = context,
        sampleRate = 16_000,
        channels = 1
    ) { pcm ->
        Log.d(TAG, "Captured PCM frame: ${pcm.size} bytes")
    }

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
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) {
                    peerConnection.addIceCandidate(c)
                }
                override fun onAddTrack(rtpReceiver: RtpReceiver, streams: Array<MediaStream>) {}
                override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onDataChannel(dc: DataChannel) {}
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onRenegotiationNeeded() {}
            }
        )!!

        // 3) DataChannel 생성 (봇 이벤트 수신용)
        dataChannel = peerConnection.createDataChannel("oai-events", DataChannel.Init())
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val msg = String(bytes, Charsets.UTF_8)
                Log.d(TAG, "Received event: $msg")
            }
            override fun onStateChange() {}
            override fun onBufferedAmountChange(prev: Long) {}
        })
    }

    fun pushAudioFrame(pcm: ByteArray) {
        //customAdm.pushFrame(pcm)
    }

    /** Offer 생성 → OpenAI Realtime에 POST → Answer 설정 */
    private suspend fun negotiate(): SessionDescription = coroutineScope {
        // Offer 생성
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            val mc = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            peerConnection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) =
                    cont.resume(sdp) { }
                override fun onCreateFailure(e: String) =
                    cont.resumeWithException(Exception(e))
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String) {}
            }, mc)
        }

        // Local SDP 설정
        peerConnection.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "setLocalDescription failed: $err")
            }
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(err: String?) {}
        }, offer)

        // OpenAI Realtime endpoint에 POST
        val answerSdp = withContext(Dispatchers.IO) {
            val url = URL("https://api.openai.com/v1/realtime?model=$model")
            (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/sdp")
                doOutput = true
                outputStream.write(offer.description.toByteArray())
                if (responseCode !in 200..299) {
                    throw Exception("HTTP $responseCode: ${errorStream.bufferedReader().readText()}")
                }
            }.inputStream.bufferedReader().readText()
        }

        // Remote SDP 설정
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote SDP set")
            }
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "setRemoteDescription failed: $err")
            }
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(err: String?) {}
        }, answer)

        offer
    }

    /** WebRTC 연결 시작 */
    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        if (negotiateJob.get() != null) return
        negotiateJob.set(
            GlobalScope.launch {
                try {
                    negotiate()
                } catch (e: Exception) {
                    Log.e(TAG, "Negotiation failed", e)
                }
            }
        )
    }

    /** WebRTC 세션 종료 */
    fun stop() {
        negotiateJob.getAndSet(null)?.cancel()
        peerConnection.close()
        peerConnectionFactory.dispose()
        dataChannel.dispose()
        customAdm.release()
    }
}