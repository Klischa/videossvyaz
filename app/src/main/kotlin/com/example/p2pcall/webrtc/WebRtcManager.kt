package com.example.p2pcall.webrtc

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.example.p2pcall.config.AppConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Колбэк ключевых событий WebRTC для UI-слоя.
 */
interface WebRtcListener {
    /** Установлено медиа-соединение. */
    fun onConnected()

    /** Соединение разорвано (но не ошибка). */
    fun onDisconnected()

    /** Неустранимый сбой соединения/инициализации. */
    fun onFailed(reason: String)
}

/**
 * Управляет PeerConnection, локальными/удалёнными медиапотоками
 * и генерацией offer/answer в режиме **без trickle ICE**
 * (все ICE-кандидаты включаются в финальный SDP).
 *
 * Все публичные suspend-функции безопасно вызывать из корутины на главном потоке —
 * асинхронные WebRTC-колбэки здесь «упаковываются» в корутины.
 */
class WebRtcManager(
    private val context: Context,
    private val eglBase: EglBase,
    listener: WebRtcListener
) {

    companion object {
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val AUDIO_TRACK_ID = "ARDAMSa0"
        private const val STREAM_ID = "ARDAMS"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30

        /** PeerConnectionFactory.initialize() должен вызываться ОДИН раз за процесс. */
        @Volatile
        private var nativeInitialized = false
    }

    // ----- Фабрика и соединение -------------------------------------------------
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null

    // ----- Локальное медиа ------------------------------------------------------
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null

    // ----- Удалённое медиа ------------------------------------------------------
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    // ----- Рендереры (владеются Activity) ---------------------------------------
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    // ----- ICE gathering (без trickle) ------------------------------------------
    private var iceGatheringDeferred: CompletableDeferred<Unit>? = null

    /**
     * Собранные ICE-кандидаты (страховка: если localDescription по какой-то
     * причине не содержит их, мы вмержим их вручную перед отправкой SDP).
     */
    private val gatheredCandidates = mutableListOf<IceCandidate>()

    // Счётчики кандидатов по типу — для диагностики «ICE failed».
    private var hostCount = 0
    private var srflxCount = 0
    private var relayCount = 0

    // Сколько кандидатов пришло в удалённом SDP и история состояний ICE.
    private var remoteCandidateCount = 0
    private val iceStateLog = mutableListOf<String>()

    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var released = false

    /**
     * Колбэк может быть заменён, если Activity, владеющая менеджером, пересоздаётся
     * (например, после вытеснения из памяти). Менеджер при этом переживает пересоздание,
     * т.к. хранится в [WebRtcController].
     */
    var listener: WebRtcListener = listener
        private set

    fun setListener(newListener: WebRtcListener) {
        listener = newListener
    }

    // --------------------------------------------------------------------------
    //  Инициализация
    // --------------------------------------------------------------------------

    /**
     * Создаёт фабрику, PeerConnection и локальные треки.
     * Должно быть вызвано до [createOffer] / [createAnswer] / [applyAnswer].
     */
    fun initialize() {
        if (factory != null) return

        // 1. Инициализация нативной части WebRTC (ОДИН раз за процесс).
        if (!nativeInitialized) {
            val initOptions = PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)
            nativeInitialized = true
        }

        // 2. Аудио-модуль (Java ADM).
        audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .createAudioDeviceModule()

        // 3. Фабрика с аппаратными кодеками.
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        // 4. PeerConnection с ICE-серверами и Unified Plan.
        val rtcConfig = PeerConnection.RTCConfiguration(AppConfig.iceServers(context.applicationContext)).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // GATHER_ONCE: после начального сбора гарантированно приходит COMPLETE,
            // и localDescription содержит все кандидаты (надёжно для non-trickle).
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            // Предвыделенный пул кандидатов — ускоряет ICE-рестарт при восстановлении.
            iceCandidatePoolSize = 5
        }
        peerConnection = factory?.createPeerConnection(rtcConfig, peerObserver)
            ?: throw IOException("Не удалось создать PeerConnection")

        // 5. Локальные треки.
        createLocalTracks()
        peerConnection!!.addTrack(localAudioTrack!!, listOf(STREAM_ID))
        peerConnection!!.addTrack(localVideoTrack!!, listOf(STREAM_ID))

        // 6. Аудио-маршрутизация (громкая связь для видеозвонка).
        configureAudioManager()
    }

    /** Создаёт источники и треки аудио/видео. */
    private fun createLocalTracks() {
        val f = factory!!

        // Адаптация под качество связи: режим «экономии трафика» — ниже разрешение/FPS.
        val economy = context.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
            .getBoolean("video_economy", false)
        val width = if (economy) 320 else 640
        val height = if (economy) 240 else 480
        val fps = if (economy) 15 else 24

        // Аудио.
        val audioSrc = f.createAudioSource(MediaConstraints())
        audioSource = audioSrc
        val aTrack = f.createAudioTrack(AUDIO_TRACK_ID, audioSrc).apply { setEnabled(true) }
        localAudioTrack = aTrack

        // Видео с камеры (Camera2).
        val capturer = createCameraCapturer()
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceHelper = helper

        val vSource = f.createVideoSource(false)
        videoSource = vSource
        capturer.initialize(helper, context.applicationContext, vSource.capturerObserver)
        capturer.startCapture(width, height, fps)

        val vTrack = f.createVideoTrack(VIDEO_TRACK_ID, vSource).apply { setEnabled(true) }
        localVideoTrack = vTrack
        // Привязываем локальный рендерер, если он уже задан (attachLocalRenderer
        // мог быть вызван до initialize() — тогда трек ещё не существовал).
        localRenderer?.let { vTrack.addSink(it) }
    }

    /** Выбирает фронтальную камеру (с фолбэком на любую доступную). */
    private fun createCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context.applicationContext)
        val devices = enumerator.deviceNames

        for (id in devices) {
            if (enumerator.isFrontFacing(id)) {
                return enumerator.createCapturer(id, null)
                    ?: continue
            }
        }
        for (id in devices) {
            val cap = enumerator.createCapturer(id, null)
            if (cap != null) return cap
        }
        throw IOException("Камера не найдена")
    }

    private fun configureAudioManager() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager = am ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            applySpeakerRouting()
            // Пересчитываем маршрут при подключении/отключении наушников во время звонка.
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
                    applySpeakerRouting()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
                    applySpeakerRouting()
            }
            am.registerAudioDeviceCallback(audioDeviceCallback!!, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) {
            // На некоторых устройствах вызов бросает исключение — игнорируем.
        }
    }

    /** Громкая связь включаем, только если не подключены проводные/USB наушники. */
    private fun applySpeakerRouting() {
        val am = audioManager ?: return
        am.isSpeakerphoneOn = !hasWiredHeadset(am)
    }

    private fun hasWiredHeadset(am: AudioManager): Boolean = try {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    } catch (_: Exception) {
        false
    }

    // --------------------------------------------------------------------------
    //  Привязка рендереров
    // --------------------------------------------------------------------------

    /** Привязывает локальный рендерер (вызывает Activity после SurfaceRenderer.init). */
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        localVideoTrack?.addSink(renderer)
    }

    /** Привязывает удалённый рендерер. */
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        // Трек мог прийти раньше, чем рендерер был готов.
        remoteVideoTrack?.addSink(renderer)
    }

    fun detachRenderers() {
        localVideoTrack?.let { localRenderer?.let { r -> runCatching { it.removeSink(r) } } }
        remoteVideoTrack?.let { remoteRenderer?.let { r -> runCatching { it.removeSink(r) } } }
        localRenderer = null
        remoteRenderer = null
    }

    // --------------------------------------------------------------------------
    //  Сигналинг (suspend API)
    // --------------------------------------------------------------------------

    /**
     * Создаёт offer в режиме без trickle ICE.
     * Возвращает полный SDP (со всеми ICE-кандидатами).
     */
    suspend fun createOffer(): String {
        ensureReady()
        val constraints = receiveConstraints()

        // Готовим ожидание завершения ICE gathering ДО установки local description.
        iceGatheringDeferred = CompletableDeferred()
        gatheredCandidates.clear()
        hostCount = 0; srflxCount = 0; relayCount = 0

        val offer = awaitSdpOp { observer ->
            peerConnection!!.createOffer(observer, constraints)
        } ?: throw IOException("createOffer вернул пустой SDP")

        awaitSdpOp { observer ->
            peerConnection!!.setLocalDescription(observer, offer)
        }

        awaitIceGatheringComplete()
        return finalSdpWithCandidates()
    }

    /**
     * Принимающий: устанавливает offer как remote description и создаёт answer.
     * Возвращает полный SDP ответа (со всеми ICE-кандидатами).
     */
    suspend fun createAnswer(remoteOfferSdp: String): String {
        ensureReady()
        val constraints = receiveConstraints()

        // Сначала remote (offer). Считаем кандидаты в нём — для диагностики.
        iceStateLog.clear()
        remoteCandidateCount = remoteOfferSdp.split("\n").count { it.contains("a=candidate") }
        awaitSdpOp { observer ->
            peerConnection!!.setRemoteDescription(
                observer,
                SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp)
            )
        }

        iceGatheringDeferred = CompletableDeferred()
        gatheredCandidates.clear()
        hostCount = 0; srflxCount = 0; relayCount = 0

        val answer = awaitSdpOp { observer ->
            peerConnection!!.createAnswer(observer, constraints)
        } ?: throw IOException("createAnswer вернул пустой SDP")

        awaitSdpOp { observer ->
            peerConnection!!.setLocalDescription(observer, answer)
        }

        awaitIceGatheringComplete()
        return finalSdpWithCandidates()
    }

    /**
     * Инициатор: применяет полученный answer как remote description.
     * После этого соединение устанавливается автоматически.
     */
    suspend fun applyAnswer(remoteAnswerSdp: String) {
        ensureReady()
        remoteCandidateCount = remoteAnswerSdp.split("\n").count { it.contains("a=candidate") }
        awaitSdpOp { observer ->
            peerConnection!!.setRemoteDescription(
                observer,
                SessionDescription(SessionDescription.Type.ANSWER, remoteAnswerSdp)
            )
        }
    }

    /** Общие ограничения: принимать и аудио, и видео. */
    private fun receiveConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveVideo", "true"))
    }

    /**
     * Ожидает завершения ICE gathering (состояние COMPLETE).
     * Без trickle именно в этот момент localDescription содержит все кандидаты.
     * Если COMPLETE не наступает за таймаут — продолжаем с тем, что собрали
     * (лучше частичный набор, чем вообще не позвонить).
     */
    private suspend fun awaitIceGatheringComplete() {
        withTimeoutOrNull(AppConfig.ICE_GATHERING_TIMEOUT_MS) {
            iceGatheringDeferred?.await()
        }
    }

    /**
     * Берём финальный SDP из localDescription и **объединяем** с собранными
     * кандидатами (на случай, если библиотека поместила в localDescription не все).
     * Дубли пропускаем — так в SDP гарантированно есть все host/srflx/relay кандидаты.
     */
    private fun finalSdpWithCandidates(): String {
        val base = peerConnection!!.localDescription.description
        val missing = gatheredCandidates.filter { c -> !base.contains("a=" + c.sdp) }
        return if (missing.isEmpty()) base else mergeCandidates(base, missing)
    }

    /**
     * Вставляет строки a=candidate в первую m-секцию (при bundle весь транспорт
     * общий — этого достаточно). Фолбэк на случай, если localDescription без кандидатов.
     */
    private fun mergeCandidates(sdp: String, candidates: List<IceCandidate>): String {
        val nl = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val candLines = candidates.map { "a=" + it.sdp }
        val lines = sdp.split(nl).toMutableList()
        // Точка вставки — перед второй строкой "m=" (внутрь первой m-секции);
        // если секция одна — в самый конец.
        var mCount = 0
        var insertAt = lines.size
        for (i in lines.indices) {
            if (lines[i].startsWith("m=")) {
                mCount++
                if (mCount == 2) { insertAt = i; break }
            }
        }
        lines.addAll(insertAt, candLines)
        return lines.joinToString(nl)
    }

    private fun ensureReady() {
        check(!released) { "WebRtcManager уже освобождён" }
        check(peerConnection != null) { "Сначала вызовите initialize()" }
    }

    // --------------------------------------------------------------------------
    //  Управление звонком
    // --------------------------------------------------------------------------

    /** Переключение микрофона (mute/unmute). Возвращает новое состояние (вкл/выкл). */
    fun toggleMic(): Boolean {
        val enabled = !(localAudioTrack?.enabled() ?: true)
        localAudioTrack?.setEnabled(enabled)
        return enabled
    }

    /** Включение/выключение видео. Возвращает новое состояние. */
    fun toggleVideo(): Boolean {
        val enabled = !(localVideoTrack?.enabled() ?: true)
        localVideoTrack?.setEnabled(enabled)
        return enabled
    }

    /** Переключение между фронтальной/задней камерой. */
    fun switchCamera() {
        val capturer = videoCapturer as? CameraVideoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {}
            override fun onCameraSwitchError(errorDescription: String) {}
        })
    }

    // --------------------------------------------------------------------------
    //  Освобождение ресурсов
    // --------------------------------------------------------------------------

    fun release() {
        if (released) return
        released = true

        detachRenderers()

        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoSource?.dispose()
        surfaceHelper?.dispose()
        audioSource?.dispose()

        localVideoTrack?.dispose()
        localAudioTrack?.dispose()

        peerConnection?.dispose()
        factory?.dispose()
        audioDeviceModule?.release()

        audioDeviceCallback?.let { cb -> audioManager?.unregisterAudioDeviceCallback(cb) }
        audioDeviceCallback = null
        audioManager?.apply {
            runCatching {
                isSpeakerphoneOn = false
                mode = AudioManager.MODE_NORMAL
            }
        }

        videoCapturer = null
        videoSource = null
        audioSource = null
        surfaceHelper = null
        localVideoTrack = null
        localAudioTrack = null
        peerConnection = null
        factory = null
        audioDeviceModule = null
    }

    // --------------------------------------------------------------------------
    //  Вспомогательное: оборачивание SdpObserver в корутины
    // --------------------------------------------------------------------------

    /**
     * Универсальный враппер: для createOffer/createAnswer срабатывает [SdpObserver.onCreateSuccess],
     * для setLocal/setRemoteDescription — [SdpObserver.onSetSuccess].
     */
    private suspend fun awaitSdpOp(action: (SdpObserver) -> Unit): SessionDescription? =
        suspendCancellableCoroutine { cont ->
            action(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {
                    if (cont.isActive) cont.resume(p0)
                }

                override fun onSetSuccess() {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IOException("create failed: $error"))
                }

                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IOException("set failed: $error"))
                }
            })
        }

    // --------------------------------------------------------------------------
    //  PeerConnection.Observer
    // --------------------------------------------------------------------------

    private val peerObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            state?.name?.let { iceStateLog.add(it) }
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> listener.onConnected()

                PeerConnection.IceConnectionState.DISCONNECTED -> listener.onDisconnected()

                PeerConnection.IceConnectionState.FAILED -> listener.onFailed(
                    "ICE failed.\n" +
                        "Локально: host=$hostCount, srflx=$srflxCount, relay=$relayCount.\n" +
                        "Удалённых кандидатов: $remoteCandidateCount.\n" +
                        "Состояния ICE: ${iceStateLog.joinToString(" -> ")}.\n" +
                        "Если удалённых=0 — кандидаты теряются при обмене SDP; если >0 — проблема сети/relay."
                )

                else -> Unit
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(p0: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            // Без trickle: ждём, пока соберутся ВСЕ кандидаты.
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                iceGatheringDeferred?.complete(Unit)
            }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            // Без trickle кандидаты уже включены в localDescription.
            // Собираем их — как страховку (finalSdpWithCandidates) и для диагностики типов.
            candidate?.let { c ->
                gatheredCandidates.add(c)
                val s = c.sdp ?: ""
                when {
                    s.contains("typ relay") -> relayCount++
                    s.contains("typ srflx") -> srflxCount++
                    s.contains("typ host") -> hostCount++
                    else -> Unit
                }
            }
        }

        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) = Unit
        override fun onIceCandidateError(p0: IceCandidateErrorEvent?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(p0: org.webrtc.DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            // Unified Plan: треки приходят сюда.
            val track = receiver?.track() ?: return
            when (track) {
                is VideoTrack -> {
                    remoteVideoTrack = track
                    remoteRenderer?.let { runCatching { track.addSink(it) } }
                }
                is AudioTrack -> {
                    remoteAudioTrack = track
                    // Аудио воспроизводится AudioDeviceModule автоматически.
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) = Unit
    }
}
