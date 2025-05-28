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
    private val model: String = "gpt-4o-realtime-preview",
    /** AI가 보낸 오디오 프레임(PCM)을 받는 콜백 */
    //private val onAudioResponse: (pcm: ByteArray) -> Unit
) {
    private val client = OkHttpClient()
    private lateinit var ws: WebSocket
    private var sessionCreated = false
    private var isResponseDone = true

    val player = MusicPlayer(context, sampleRate = 24000, stereo = false)

    /** WebSocket 연결 시작 */
    fun start() {
        player.setOnCompletionListener {
            //if (isResponseDone) {
            ///OemHelper.setOnServiceState(context, false)
            isResponseDone = true
            //}
        }

        val url =
            // 모델과 intent=conversation 으로 대화형 스트리밍 세션 생성
            "wss://api.openai.com/v1/realtime?model=$model"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
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
                                        put("instructions", """너는 한국말로 아이큐브온 이라는 회사를 소개하는 AI야.
                                            | 너의 이름은 아큐온이야.
                                            | 친절하고 존중을 담아 답변하고, 자주 웃어. 
                                            | 존댓말만 사용해야해. 
                                            | 세션이 시작되면, 다음과 같이 인사해.
                                            | "안녕하세요. 아큐온입니다. 무엇을 도와드릴까요?"
                                            | 다른 말은 붙이지 말고, 대답할 때는 세 문장 이하로 대답해. 너의 말이 너무 길어지게 하지마.
                                            | 너는 아이큐브온에 대한 정보 외에도 묻는 질문에 아는대로 최대한 답변하도록 노력해야해.
                                            | 너가 말하는 도중이라도 상대방이 말을 하면, 일단 말을 멈추고 들어.
                                            | 아이큐브온에 대한 정보는 다음과 같아.
                                            | “ 독창적인 아이디어로 세상을 편리하게 ” 스마트폰 기반의 콜페이지 솔루션 특화 전문기업
                                            | Mobile 자동응답(ARS) 솔루션을 개발하여 통신사(KT) 부가서비스를 제공 중에 있으며, 현재 국내 위험전화(보이스피싱, 스팸) 알림 서비스 1위인 브이피와 사업을 제휴하여 솔루션을 공급 중입니다.
                                            | 콜리BIZ는 사용자가 전화를 못 받을 때, 자동으로 전화를 받아 대신 응대해주는 모바일 솔루션입니다.
                                            | 콜리BIZ를 기반으로한 보이는 ARS 서비스를 현대해상 등 다양한 기업에 제공하고 있습니다.
                                            | 회사의 대표는 최승진 회장과 이황균 대표, 김석기 대표로 이루어져 있습니다.
                                            | 아이큐브온은 2018년 2월 13일에 설립되었습니다.
                                            | 회사의 주소는 서울 특별시 성동구 뚝섬로 1길 31, 서울숲 M타워 305호이며, 전화번호는 025148802입니다.
                                            | 이메일로 문의 시 info@icubeon.com으로 문의 주실 수 있습니다.
                                            | 아이큐브온 회사 페이지의 url은  https://www.icubeon.com 입니다.
                                            | """.trimMargin())
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
                            isResponseDone = false
                            OemHelper.setOnServiceState(context, true)
                            val deltaB64 = obj["delta"]!!.jsonPrimitive.content
                            val pcm = Base64.decode(deltaB64, Base64.NO_WRAP)
                            player.playPCM(pcm)
                        }
                        "response.audio_transcript.done" -> {
                            player.stop()
                        }
                        "response.content_part.done" -> {
                            player.stop()
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
        if (isResponseDone) {
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
    }

    /** 세션 종료 */
    fun stop() {
        Log.d("AIWS", "stop")
        ws.close(1000, "Normal closure")
        OemHelper.setOnServiceState(context, false)
        player.stop()
    }
}
