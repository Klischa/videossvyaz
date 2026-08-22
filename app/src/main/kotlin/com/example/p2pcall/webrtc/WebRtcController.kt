package com.example.p2pcall.webrtc

import org.webrtc.EglBase

/**
 * Процесс-синглтон, хранящий активный WebRTC-контекст.
 *
 * Зачем он нужен: глубокая ссылка с answer может прийти в [com.example.p2pcall.ui.MainActivity]
 * уже ПОСЛЕ того, как инициатор создал offer в отдельной [com.example.p2pcall.ui.CallActivity].
 * Чтобы «применить» ответ, нужен тот же PeerConnection с уже созданным offer —
 * поэтому менеджер переживает переключение Activity (пока жив процесс).
 */
object WebRtcController {

    /** Общий EGL-контекст для всех рендереров и кодеков. */
    val eglBase: EglBase by lazy { EglBase.create() }

    /** Активный менеджер (null, если звонка нет). */
    var manager: WebRtcManager? = null
        private set

    /** Роль текущего узла. */
    var role: CallRole? = null
        private set

    /** Локально созданный offer (для инициатора) — используется при APPLY_ANSWER. */
    var localOfferSdp: String? = null
        private set

    /** Создаёт/возвращает менеджер с заданным слушателем. */
    fun getOrCreate(listener: WebRtcListener): WebRtcManager {
        manager?.let { return it }
        val ctx = currentContext ?: throw IllegalStateException("Сначала вызовите bindContext()")
        return WebRtcManager(ctx, eglBase, listener).also { manager = it }
    }

    @Volatile
    private var currentContext: android.content.Context? = null

    /** Установить ApplicationContext. Вызывается из CallActivity. */
    fun bindContext(context: android.content.Context) {
        currentContext = context.applicationContext
    }

    /** Зафиксировать роль и (для инициатора) созданный offer. */
    fun markOfferer(offerSdp: String) {
        role = CallRole.OFFERER
        localOfferSdp = offerSdp
    }

    fun markAnswerer() {
        role = CallRole.ANSWERER
    }

    /** Полный сброс и освобождение ресурсов. */
    fun reset() {
        manager?.release()
        manager = null
        role = null
        localOfferSdp = null
    }
}
