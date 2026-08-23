package com.example.p2pcall.config

import android.content.Context
import org.webrtc.PeerConnection

/**
 * Глобальная конфигурация приложения.
 *
 * Смените [BASE_URL] на свой домен (для App Links) и при необходимости —
 * список ICE-серверов (через настройки можно добавить свой TURN/coturn).
 */
object AppConfig {

    /** Имя файла SharedPreferences для настроек (TURN и т.п.). */
    const val PREFS = "p2pcall_prefs"

    /** Базовый URL, из которого формируются ссылки-приглашения. */
    const val BASE_URL = "https://yourdomain.com/call"

    /** Альтернативная кастомная схема (для тестирования без домена). */
    const val CUSTOM_SCHEME_URL = "myapp://call"

    /** Таймаут ожидания завершения ICE gathering (мс). */
    const val ICE_GATHERING_TIMEOUT_MS = 15_000L

    /** Минимальная версия разрешения для захвата видео (API 23+). */
    const val CAMERA_PERMISSION_REQUEST = 1001

    /**
     * Список ICE-серверов: базовые STUN + пользовательский TURN из настроек.
     *
     * Бесплатный публичный TURN OpenRelay прекратил работу (порты/DNS не отвечают),
     * поэтому он удалён. Для соединения через симметричный NAT (разные мобильные сети)
     * ОБЯЗАТЕЛЬНО впишите свой TURN/coturn в Настройках (см. COTURN.md).
     */
    fun iceServers(context: Context): List<PeerConnection.IceServer> {
        val list = mutableListOf<PeerConnection.IceServer>()

        // STUN
        list += PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        list += PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        list += PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer()

        // Пользовательский TURN из настроек (можно несколько адресов — по строке
        // или через запятую/точку с запятой). Берём только корректные turn:/turns:/stun: —
        // мусор (JSON-скобки, кавычки, поля username/credential из массива Metered)
        // игнорируем, чтобы некорректный ICE-сервер не ломал createPeerConnection.
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("turn_enabled", false)) {
            val user = prefs.getString("turn_user", null)?.takeIf { it.isNotBlank() }
            val pass = prefs.getString("turn_pass", null)?.takeIf { it.isNotBlank() }
            prefs.getString("turn_url", null)
                ?.split("\n", ",", ";")
                ?.map { it.trim().trim('"').trim() }
                ?.filter { url ->
                    url.isNotBlank() &&
                        (url.startsWith("turn:", ignoreCase = true) ||
                            url.startsWith("turns:", ignoreCase = true) ||
                            url.startsWith("stun:", ignoreCase = true))
                }
                ?.forEach { url ->
                    try {
                        val b = PeerConnection.IceServer.builder(url)
                        user?.let { b.setUsername(it) }
                        pass?.let { b.setPassword(it) }
                        list += b.createIceServer()
                    } catch (_: Exception) {
                        // пропускаем некорректный адрес
                    }
                }
        }
        return list
    }
}

