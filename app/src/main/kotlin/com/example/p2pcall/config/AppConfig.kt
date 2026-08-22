package com.example.p2pcall.config

import org.webrtc.PeerConnection

/**
 * Глобальная конфигурация приложения.
 *
 * Смените [BASE_URL] на свой домен (для App Links) и при необходимости —
 * список ICE-серверов (добавьте TURN для пробития симметричных NAT).
 */
object AppConfig {

    /** Базовый URL, из которого формируются ссылки-приглашения. */
    const val BASE_URL = "https://yourdomain.com/call"

    /** Альтернативная кастомная схема (для тестирования без домена). */
    const val CUSTOM_SCHEME_URL = "myapp://call"

    /**
     * Публичные STUN-серверы Google. Для соединения через симметричный NAT
     * добавьте сюда TURN-сервер (например, coturn) с credentials.
     */
    val ICE_SERVERS: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
            .createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
            .createIceServer(),
        // Пример TURN (раскомментируйте и подставьте свои данные):
        // PeerConnection.IceServer.builder("turn:turn.example.com:3478")
        //     .setUsername("user")
        //     .setPassword("pass")
        //     .createIceServer()
    )

    /** Таймаут ожидания завершения ICE gathering (мс). */
    const val ICE_GATHERING_TIMEOUT_MS = 15_000L

    /** Минимальная версия разрешения для захвата видео (API 23+). */
    const val CAMERA_PERMISSION_REQUEST = 1001
}
