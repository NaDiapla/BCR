package com.chiller3.bcr

import android.content.Context
import android.util.Base64
import android.util.Log
import com.shhphone.autoanswerlib.OemHelper
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * OpenAI Realtime API를 WebSocket으로 연결해 PCM 스트림을 보내고,
 * AI가 반환한 오디오 바이너리를 콜백으로 전달합니다.
 */
class AIWebSocketClient(
    private val context: Context,
    private val model: String = "gpt-4o-realtime-preview-2024-12-17",
    /** AI가 보낸 오디오 프레임(PCM)을 받는 콜백 */
    //private val onAudioResponse: (pcm: ByteArray) -> Unit
) {
    private val client = OkHttpClient()
    private lateinit var ws: WebSocket
    private var sessionCreated = false
    private var isResponseDone = false

    private val apiKey = "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"

    val player = MusicPlayer(context, sampleRate = 24000, stereo = false)

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
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("AIWS", "WebSocket opened")
                Log.d("AIWS", "Sent session.create")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d("AIWS", "Event: $text")
                try {
                    val obj = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "session.created" -> {
                            // 한 번만 session.update 전송
                            if (!sessionCreated) {
                                sessionCreated = true
                                val upd = buildJsonObject {
                                    put("type", "session.update")
                                    put("session", buildJsonObject {
                                        // 포맷 지정 (pcm16 == 16-bit LE mono @ 24 kHz)
                                        put("input_audio_format", "pcm16")
                                        put("output_audio_format", "pcm16")
                                        put("voice", "alloy")
                                        put("instructions", "너는 한국말로 응답하는 AI야. 친절하고 존중을 담아 답변하고, 자주 웃어. 존댓말만 사용해야해. 너의 이름은 아큐온이야.")
                                        // 서버 VAD 모드로 자동 커밋
                                        put("turn_detection", buildJsonObject {
                                            put("type", "server_vad")
                                        })
                                    })
                                }
                                ws.send(upd.toString())
                                Log.d("AIWS", "Sent session.update")
                            }
                        }
                        "session.updated" -> {
                            Log.d("AIWS", "Session updated, ready to send audio")
                        }
                        "response.audio.delta" -> {
                            OemHelper.setOnServiceState(context, true)
                            val deltaB64 = obj["delta"]!!.jsonPrimitive.content
                            val pcm = Base64.decode(deltaB64, Base64.NO_WRAP)
                            /*player.setOnCompletionListener {
                                if (isResponseDone) {
                                    OemHelper.setOnServiceState(context, false)
                                    isResponseDone = false
                                }
                            }*/
                            player.playPCM(pcm)
                        }
                        "response.done" -> {
                            isResponseDone = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AIWS", "Failed to parse event", e)
                }
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
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        //val decodedPcm = Base64.decode(b64, Base64.NO_WRAP)
        //player.playPCM(decodedPcm)
        val msg = buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", b64)
        }
        ws.send(msg.toString())
    }

    /** 세션 종료 */
    fun stop() {
        Log.d("AIWS", "stop")
        ws.close(1000, "Normal closure")
        OemHelper.setOnServiceState(context, false)
        player.stop()
    }
}
