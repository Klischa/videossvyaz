package com.example.p2pcall.webrtc

/**
 * Роль текущего узла в сигнальном обмене.
 */
enum class CallRole {
    /** Инициатор: создаёт offer и ждёт answer. */
    OFFERER,

    /** Принимающий: получил offer, создаёт answer. */
    ANSWERER
}

/**
 * Режим запуска экрана звонка.
 * Передаётся в [com.example.p2pcall.ui.CallActivity] через Intent-экстра.
 */
enum class CallMode {

    /** Инициатор начинает с нуля: нужно создать offer. */
    NEW_OFFER,

    /** Получен offer (по deep link / вставке) — нужно сгенерировать answer. Содержит offerSdp. */
    ANSWERER,

    /** Получен answer (по deep link / вставке) — нужно применить его к существующей сессии. Содержит answerSdp. */
    APPLY_ANSWER,

    /** Звонок через «комнату» на сигнальном сервере: роль (offerer/answerer) определяется автоматически. */
    ROOM
}
