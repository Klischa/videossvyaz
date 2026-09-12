package com.example.p2pcall.signaling

import android.content.Context
import com.example.p2pcall.config.AppConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Роль в дозвоне через Cloudflare Worker.
 * См. [WsSignalingListener.onRole].
 */
enum class CCloudRole { CALLER, CALLEE }

/**
 * Слушатель событий WebSocket-канала дозвона.
 * Колбэки вызываются в потоке OkHttp — наружу (в Activity) их надо
 * пускать через Handler.main или lifecycleScope.
 */
interface WsSignalingListener {
    /** Воркер подтвердил роль (первый в комнате — caller, второй — callee). */
    fun onRole(role: CCloudRole)

    /** В комнату зашёл второй участник — можно начинать дозвон. */
    fun onPeerJoined()

    /** Собеседник отключился (или ещё не подключился). */
    fun onPeerLeft()

    /** Дозвон от собеседника (только у callee). */
    fun onRing(from: String)

    /** Получен offer (только у callee). */
    fun onOffer(sdp: String)

    /** Получен answer (только у caller). */
    fun onAnswer(sdp: String)

    /** Собеседник положил трубку. */
    fun onBye()

    /** Канал закрыт (ошибка/обрыв сети). */
    fun onClosed(reason: String?)
}

/**
 * WebSocket-канал дозвона через Cloudflare Worker.
 *
 * Схема:
 *   Телефон A ──wss://worker/ws?room=КОД──► Durable Object
 *   Телефон B ──wss://worker/ws?room=КОД──► Durable Object
 *
 * Воркер ретранслирует JSON-сообщения между двумя сокетами в комнате.
 * Медиа (WebRTC) после согласования идёт напрямую P2P, мимо воркера.
 */
class WsSignaling(
    private val context: Context,
    private val listener: WsSignalingListener
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket живёт долго
        .pingInterval(25, TimeUnit.SECONDS)    // keepalive, чтобы CF не рубил
        .build()

    private var ws: WebSocket? = null

    /** Проверить, что в настройках задан Cloudflare Worker. */
    fun isConfigured(): Boolean = AppConfig.cloudUrl(context).isNotBlank()

    /**
     * Открыть WebSocket к воркеру.
     * @param room код комнаты (одинаковый у обоих телефонов).
     */
    fun connect(room: String) {
        close()

        val url = AppConfig.cloudUrl(context)
        val scheme = if (url.startsWith("https://", ignoreCase = true)) "wss" else "ws"
        val bare = url
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removePrefix("ws://")
        val target = "$scheme://${bare.trimEnd('/')}/ws?room=${URLEncoder.encode(room, "UTF-8")}"

        val request = Request.Builder()
            .url(target)
            // Cloudflare может проверять User-Agent (защита от ботов).
            .header("User-Agent", "P2PCall-Android")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Ничего не шлём до welcome — воркер сам представится.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onClosed(t.message ?: "WebSocket error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(reason)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        })
    }

    /** Разбор входящего JSON от воркера. */
    private fun handleMessage(text: String) {
        val o = try { JSONObject(text) } catch (_: Exception) { return }
        when (o.optString("type")) {
            "welcome" -> {
                val you = if (o.optString("role") == "callee") CCloudRole.CALLEE else CCloudRole.CALLER
                listener.onRole(you)
            }
            "peer-joined" -> listener.onPeerJoined()
            "peer-left" -> listener.onPeerLeft()
            "error" -> listener.onClosed(o.optString("message"))
            "ring" -> listener.onRing(o.optString("from", "Собеседник"))
            "offer" -> listener.onOffer(o.optString("sdp"))
            "answer" -> listener.onAnswer(o.optString("sdp"))
            "bye" -> listener.onBye()
        }
    }

    /** Отправить дозвон. @param from имя/заголовок звонящего. */
    fun sendRing(from: String) {
        ws?.send("""{"type":"ring","from":${jsonStr(from)}}""")
    }

    fun sendOffer(sdp: String) {
        ws?.send("""{"type":"offer","sdp":${jsonStr(sdp)}}""")
    }

    fun sendAnswer(sdp: String) {
        ws?.send("""{"type":"answer","sdp":${jsonStr(sdp)}}""")
    }

    fun sendBye() {
        ws?.send("""{"type":"bye"}""")
    }

    fun close() {
        val w = ws ?: return
        w.close(1000, "by user")
        ws = null
    }

    private fun jsonStr(s: String): String =
        JSONObject().put("v", s).optString("v") // экранирование кавычек/юникода
}