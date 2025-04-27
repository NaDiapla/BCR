package com.chiller3.bcr

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * OpenAI Realtime API를 WebSocket으로 연결해 PCM 스트림을 보내고,
 * AI가 반환한 오디오 바이너리를 콜백으로 전달합니다.
 */
class AIWebSocketClient(
    private val model: String = "gpt-4o-realtime-preview-2024-12-17",
    /** AI가 보낸 오디오 프레임(PCM)을 받는 콜백 */
    //private val onAudioResponse: (pcm: ByteArray) -> Unit
) {
    private val client = OkHttpClient()
    private lateinit var ws: WebSocket

    private val apiKey = ""

    val player = MusicPlayer(sampleRate = 24000, stereo = false)

    /** WebSocket 연결 시작 */
    fun start() {
        val url =
            // 모델과 intent=conversation 으로 대화형 스트리밍 세션 생성
            "wss://api.openai.com/v1/realtime?model=$model"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("AIWS", "WebSocket opened")
                // 세션 시작 메시지 전송 :contentReference[oaicite:0]{index=0}
                val init = buildJsonObject {
                    put("type", "session_start")
                }
                webSocket.send(init.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // AI가 보내는 이벤트(예: VAD, transcription 등)
                Log.d("AIWS", "Event: $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // AI가 보내는 오디오 프레임(PCM)
                //onAudioResponse(bytes.toByteArray())
                Log.d("AIWS", "onMessage byte: $ByteString")
                val pcm = bytes.toByteArray()
                player.playPCM(pcm)   // raw PCM 재생
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("AIWS", "WebSocket failure", t)
            }
        })
    }

    /** RecorderThread에서 캡처한 PCM을 AI로 전송 */
    fun sendAudio(pcm: ByteArray) {
        //Log.d("AIWS", "sendAudio: $pcm")
        ws.send(pcm.toByteString(0, pcm.size))
    }

    /** 세션 종료 */
    fun stop() {
        Log.d("AIWS", "stop")
        ws.send("""{"type":"session_end"}""")
        ws.close(1000, "Normal closure")
        player.stop()
    }
}
