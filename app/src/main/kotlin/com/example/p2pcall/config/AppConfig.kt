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
     * STUN + TURN. Одних STUN часто не хватает для пробития NAT (особенно на
     * мобильных сетях) — поэтому добавлен публичный TURN OpenRelay.
     *
     * Для надёжности в проде поднимите свой coturn и подставьте сюда
     * (рекомендация из ТЗ: ручное добавление TURN при проблемах с NAT).
     */
    val ICE_SERVERS: List<PeerConnection.IceServer> = listOf(
        // STUN
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:openrelay.metered.ca:80").createIceServer(),

        // TURN (OpenRelay, публичные креды) — для пробития симметричного NAT.
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),

        // Пример своего TURN (раскомментируйте и подставьте свои данные):
        // PeerConnection.IceServer.builder("turn:turn.example.com:3478")
        //     .setUsername("user").setPassword("pass").createIceServer()
    )

    /** Таймаут ожидания завершения ICE gathering (мс). */
    const val ICE_GATHERING_TIMEOUT_MS = 15_000L

    /** Минимальная версия разрешения для захвата видео (API 23+). */
    const val CAMERA_PERMISSION_REQUEST = 1001
}
