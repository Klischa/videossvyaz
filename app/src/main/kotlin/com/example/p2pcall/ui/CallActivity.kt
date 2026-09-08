package com.example.p2pcall.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.p2pcall.config.AppConfig
import com.example.p2pcall.R
import com.example.p2pcall.databinding.ActivityCallBinding
import com.example.p2pcall.signaling.LinkDelivery
import com.example.p2pcall.signaling.Messenger
import com.example.p2pcall.signaling.SignalType
import com.example.p2pcall.signaling.SdpCodec
import com.example.p2pcall.signaling.FirebaseSignaling
import com.example.p2pcall.signaling.RoomHistory
import com.example.p2pcall.webrtc.CallMode
import com.example.p2pcall.webrtc.WebRtcController
import com.example.p2pcall.webrtc.WebRtcListener
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.RendererCommon
import java.io.IOException

/**
 * Экран звонка. Обрабатывает три сценария (см. [CallMode]):
 *   • NEW_OFFER   — инициатор создаёт приглашение и ждёт ответную ссылку;
 *   • ANSWERER    — получатель offer'а генерирует и отправляет ответ;
 *   • APPLY_ANSWER — инициатор применяет ответную ссылку и соединяется.
 *
 * Activity объявлена singleTask: если ответная ссылка приходит, когда экран уже
 * открыт (offerer), срабатывает [onNewIntent] и переиспользуется тот же PeerConnection.
 */
class CallActivity : AppCompatActivity(), WebRtcListener {

    private lateinit var binding: ActivityCallBinding

    private var renderersInitialized = false

    // Сгенерированные ссылки (для кнопок «копировать/поделиться»).
    private var offerLink: String? = null
    private var answerLink: String? = null

    // Флаги предотвращают повторную обработку одного и того же режима.
    private var offerDone = false
    private var answerDone = false

    // Состояние медиа-кнопок.
    private var micEnabled = true
    private var videoEnabled = true

    // Авто-восстановление связи в режиме комнаты.
    private var isRoomCall = false
    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelayMs = 3000L

    // Перетаскивание превью.
    private var previewMoved = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startLeft = 0
    private var startTop = 0
    private var curLeft = 0
    private var curTop = 0

    /** Запрос разрешений камеры и микрофона. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val camera = result[android.Manifest.permission.CAMERA] ?: false
        val mic = result[android.Manifest.permission.RECORD_AUDIO] ?: false
        if (camera && mic) {
            onReadyToProceed()
        } else {
            Toast.makeText(this, R.string.msg_permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Запрос разрешения на чтение контактов (для отправки ссылки контакту). */
    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchContactPicker()
        else Toast.makeText(this, R.string.msg_contacts_permission_required, Toast.LENGTH_LONG).show()
    }

    /** Выбор контакта из адресной книги. */
    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri -> uri?.let { onContactPicked(it) } }

    /** Сканирование QR-кода камерой (результат — текст ссылки). */
    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val res = IntentIntegrator.parseActivityResult(
            IntentIntegrator.REQUEST_CODE, result.resultCode, result.data
        )
        val text = res?.contents
        if (!text.isNullOrEmpty()) handleScannedLink(text)
    }

    /** Выбор изображения из галереи для распознавания QR. */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onImagePicked(it) } }

    /** Выбор файла для отправки через DataChannel. */
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { sendFileViaDataChannel(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wireControls()
        // Лёгкая вибрация при нажатии всех кнопок.
        binding.root.applyHapticToClickables()

        if (hasPermissions()) {
            onReadyToProceed()
        } else {
            val perms = mutableListOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Например, ответная ссылка пришла, пока offerer ждёт на этом же экране.
        handleIntent(intent ?: return)
    }

    // ------------------------------------------------------------------------
    //  Подготовка
    // ------------------------------------------------------------------------

    /** Вызывается, когда разрешения получены (или уже были). */
    private fun onReadyToProceed() {
        initRenderers()
        WebRtcController.bindContext(this)

        // Берём (или создаём) менеджер и назначаем себя слушателем событий.
        val manager = WebRtcController.getOrCreate(this)
        manager.setListener(this)

        // Перепривязываем рендереры (на случай пересоздания Activity).
        manager.detachRenderers()
        manager.attachLocalRenderer(binding.localRenderer)
        manager.attachRemoteRenderer(binding.remoteRenderer)

        // Держим процесс живым, пока активен экран звонка (фикс потери сессии на Android 13+).
        CallService.start(this)

        // Превью: скругление + перетаскивание; индикатор связи — «подключение».
        setupLocalPreview()
        binding.btnQuality.isActivated = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
            .getBoolean("video_economy", false)
        setQuality(0xFFFFB300.toInt())

        handleIntent(intent)
    }

    /** Извлекает режим и SDP из Intent и запускает соответствующий сценарий. */
    private fun handleIntent(intent: Intent) {
        val modeName = intent.getStringExtra(EXTRA_MODE) ?: return
        val mode = runCatching { CallMode.valueOf(modeName) }.getOrNull() ?: return
        val sdp = intent.getStringExtra(EXTRA_SDP)

        when (mode) {
            CallMode.NEW_OFFER -> startOfferFlow()
            CallMode.ANSWERER -> {
                if (sdp.isNullOrEmpty()) {
                    fail(getString(R.string.msg_invalid_link))
                } else {
                    startAnswererFlow(sdp)
                }
            }
            CallMode.APPLY_ANSWER -> {
                if (sdp.isNullOrEmpty()) {
                    fail(getString(R.string.msg_invalid_link))
                } else {
                    startApplyAnswerFlow(sdp)
                }
            }
            CallMode.ROOM -> startRoomCall()
        }
    }

    // ------------------------------------------------------------------------
    //  Инициализация рендереров
    // ------------------------------------------------------------------------

    private fun initRenderers() {
        if (renderersInitialized) return
        val egl = WebRtcController.eglBase.eglBaseContext
        binding.localRenderer.init(egl, null)
        binding.localRenderer.setMirror(true)
        binding.localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        binding.remoteRenderer.init(egl, null)
        binding.remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        renderersInitialized = true
    }

    // ------------------------------------------------------------------------
    //  Сценарии
    // ------------------------------------------------------------------------

    /** Инициатор: создать offer. */
    private fun startOfferFlow() {
        if (offerDone) return // экран уже показан (например, после onNewIntent)
        val manager = WebRtcController.manager ?: return

        lifecycleScope.launch {
            try {
                setStatus(R.string.status_generating_offer)
                showProgress(true)
                manager.initialize()
                val offer = manager.createOffer()
                WebRtcController.markOfferer(offer)

                offerLink = SdpCodec.buildLink(SignalType.OFFER, offer)
                offerDone = true
                showProgress(false)
                setStatus(R.string.status_waiting_answer)
                showOfferPanel()
            } catch (e: Exception) {
                fail(e.message ?: "createOffer")
            }
        }
    }

    /** Принимающий: на основе offer сгенерировать answer. */
    private fun startAnswererFlow(offerSdp: String) {
        if (answerDone) return
        val manager = WebRtcController.manager ?: return

        lifecycleScope.launch {
            try {
                setStatus(R.string.status_generating_answer)
                showProgress(true)
                manager.initialize()
                val answer = manager.createAnswer(offerSdp)
                WebRtcController.markAnswerer()

                answerLink = SdpCodec.buildLink(SignalType.ANSWER, answer)
                answerDone = true
                showProgress(false)
                setStatus(R.string.status_connecting)
                showAnswerPanel()

                // Автоматически копируем ответ в буфер и подсказываем отправить.
                answerLink?.let { copyToClipboard(it) }
                Toast.makeText(this@CallActivity, R.string.msg_autocopy_answer, Toast.LENGTH_LONG).show()

                // Авто-открываем «поделиться», чтобы отправить ответ в один тап
                // (Android не позволяет отправлять в чужой мессенджер совсем без тапа).
                answerLink?.let { shareLink(it) }
            } catch (e: Exception) {
                fail(e.message ?: "createAnswer")
            }
        }
    }

    /** Инициатор: применить полученный answer. */
    private fun startApplyAnswerFlow(answerSdp: String) {
        val manager = WebRtcController.manager
        val offer = WebRtcController.localOfferSdp
        if (manager == null || offer == null) {
            // Процесс был убит — PeerConnection с offer'ом потерян.
            Toast.makeText(this, R.string.msg_session_lost, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        manager.setListener(this)

        lifecycleScope.launch {
            try {
                setStatus(R.string.status_connecting)
                showProgress(true)
                manager.applyAnswer(answerSdp)
                hideSignalingPanels()
            } catch (e: Exception) {
                fail(e.message ?: "applyAnswer")
            }
        }
    }

    // ------------------------------------------------------------------------
    //  Звонок через «комнату» Firebase (постоянный ключ)
    // ------------------------------------------------------------------------

    /**
     * Авто-сигналинг через Firebase-комнату. Роль определяется по наличию offer:
     * offer ещё нет — мы offerer; offer уже есть — мы answerer.
     */
    private fun startRoomCall() {
        isRoomCall = true
        retryCount = 0
        val code = FirebaseSignaling.roomCode(this)
        RoomHistory.add(this, code)
        RoomHistory.setActiveRoom(this, code)
        runRoomNegotiation()
    }

    /** Авто-восстановление: повторить согласование через комнату. */
    private fun retryRoomCall() {
        retryCount++
        binding.statusText.text = "Соединение потеряно, переподключение… (попытка $retryCount)"
        runRoomNegotiation()
    }

    private fun runRoomNegotiation() {
        if (!FirebaseSignaling.isConfigured(this)) {
            Toast.makeText(this, R.string.msg_room_not_configured, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Свежий менеджер для (пере)согласования.
        WebRtcController.reset()
        val manager = WebRtcController.getOrCreate(this).also { it.setListener(this) }
        manager.detachRenderers()
        manager.attachLocalRenderer(binding.localRenderer)
        manager.attachRemoteRenderer(binding.remoteRenderer)

        lifecycleScope.launch {
            try {
                setStatus(R.string.status_connecting)
                showProgress(true)
                manager.initialize()

                val existingOffer = FirebaseSignaling.readOffer(this@CallActivity)
                if (existingOffer == null) {
                    // Мы — инициатор: чистим комнату и кладём свой offer.
                    FirebaseSignaling.clearRoom(this@CallActivity)
                    val offer = manager.createOffer()
                    WebRtcController.markOfferer(offer)
                    if (!FirebaseSignaling.writeOffer(this@CallActivity, SdpCodec.encode(offer))) {
                        throw IOException("Не удалось записать offer в Firebase")
                    }
                    hideSignalingPanels()
                    setStatus(R.string.status_waiting_answer)

                    // Ждём ответ (~до 90 сек).
                    var answerEnc: String? = null
                    var tries = 0
                    while (answerEnc == null && tries < 60 && WebRtcController.manager != null) {
                        delay(1500); tries++
                        answerEnc = FirebaseSignaling.readAnswer(this@CallActivity)
                    }
                    val a = answerEnc ?: throw IOException("Ответ не получен (таймаут)")
                    setStatus(R.string.status_connecting)
                    manager.applyAnswer(SdpCodec.decode(a))
                } else {
                    // Мы — принимающий: отвечаем на чужой offer.
                    val offerSdp = SdpCodec.decode(existingOffer)
                    val answer = manager.createAnswer(offerSdp)
                    WebRtcController.markAnswerer()
                    if (!FirebaseSignaling.writeAnswer(this@CallActivity, SdpCodec.encode(answer))) {
                        throw IOException("Не удалось записать answer на сигнальный сервер")
                    }
                    hideSignalingPanels()
                    setStatus(R.string.status_connecting)
                }
                // Соединение установится/упадёт через onConnected/onFailed.
            } catch (e: Exception) {
                onFailed(e.message ?: "room call")
            }
        }
    }

    // ------------------------------------------------------------------------
    //  UI: панели
    // ------------------------------------------------------------------------

    private fun showOfferPanel() {
        hideSignalingPanels()
        binding.panelOffer.visibility = android.view.View.VISIBLE
        binding.panelPasteAnswer.visibility = android.view.View.VISIBLE
        binding.offerLinkText.text = offerLink
    }

    private fun showAnswerPanel() {
        hideSignalingPanels()
        binding.panelAnswer.visibility = android.view.View.VISIBLE
        binding.answerLinkText.text = answerLink
    }

    private fun hideSignalingPanels() {
        binding.panelOffer.visibility = android.view.View.GONE
        binding.panelPasteAnswer.visibility = android.view.View.GONE
        binding.panelAnswer.visibility = android.view.View.GONE
    }

    private fun setStatus(resId: Int) {
        binding.statusText.text = getString(resId)
    }

    private fun showProgress(show: Boolean) {
        binding.progressBar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ------------------------------------------------------------------------
    //  Кнопки управления
    // ------------------------------------------------------------------------

    private fun wireControls() {
        binding.btnCopyOffer.setOnClickListener { offerLink?.let { copyToClipboard(it) } }
        binding.btnShareOffer.setOnClickListener { offerLink?.let { shareLink(it) } }

        binding.btnCopyAnswer.setOnClickListener { answerLink?.let { copyToClipboard(it) } }
        binding.btnShareAnswer.setOnClickListener { answerLink?.let { shareLink(it) } }

        binding.btnApplyAnswer.setOnClickListener {
            val text = binding.answerInput.text?.toString().orEmpty()
            val parsed = SdpCodec.parseLink(text)
            if (parsed == null || parsed.type != SignalType.ANSWER) {
                Toast.makeText(this, R.string.msg_invalid_link, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // Применяем ответную ссылку в рамках того же экрана.
            startApplyAnswerFlow(parsed.sdp)
        }

        binding.btnMic.setOnClickListener {
            micEnabled = WebRtcController.manager?.toggleMic() ?: micEnabled
            // Красный круг = микрофон выключен (isActivated подсвечивает фон кнопки).
            binding.btnMic.isActivated = !micEnabled
        }

        binding.btnVideo.setOnClickListener {
            videoEnabled = WebRtcController.manager?.toggleVideo() ?: videoEnabled
            // Красный круг = видео выключено.
            binding.btnVideo.isActivated = !videoEnabled
            // Визуально гасим локальный рендер при выключенной камере.
            binding.localRenderer.alpha = if (videoEnabled) 1f else 0.3f
        }

        binding.btnSwitch.setOnClickListener {
            WebRtcController.manager?.switchCamera()
        }

        binding.btnHangup.setOnClickListener { endCall() }

        binding.btnFile.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        // Доставка ссылки: выбор контакта + мессенджер, QR-код, сканирование QR.
        binding.btnContactOffer.setOnClickListener { startSendViaContact() }
        binding.btnQrOffer.setOnClickListener { currentLink()?.let { showQrDialog(it) } }
        binding.btnContactAnswer.setOnClickListener { startSendViaContact() }
        binding.btnQrAnswer.setOnClickListener { currentLink()?.let { showQrDialog(it) } }
        binding.btnScanAnswerQr.setOnClickListener { startQrScan() }

        // Качество видео: живое переключение режима экономии трафика во время звонка.
        binding.btnQuality.setOnClickListener {
            val mgr = WebRtcController.manager
            val newEco = !(mgr?.isEconomyMode() ?: false)
            mgr?.setVideoMode(newEco)
            getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit()
                .putBoolean("video_economy", newEco).apply()
            binding.btnQuality.isActivated = newEco
            Toast.makeText(
                this,
                if (newEco) "Экономия трафика ВКЛ (320×240@15)" else "Обычное качество (640×480@24)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Раскрыть/свернуть предпросмотр своей камеры. */
    private var previewExpanded = false
    private fun togglePreview() {
        previewExpanded = !previewExpanded
        val lp = binding.previewContainer.layoutParams as FrameLayout.LayoutParams
        val dp = resources.displayMetrics.density
        if (previewExpanded) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.CENTER
            lp.leftMargin = 0
            lp.topMargin = 0
            binding.previewMask.cornerRadius = 24f * dp
        } else {
            lp.width = (110 * dp).toInt()
            lp.height = (150 * dp).toInt()
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = curLeft
            lp.topMargin = curTop
            binding.previewMask.cornerRadius = 16f * dp
        }
        binding.previewContainer.layoutParams = lp
        binding.previewMask.invalidate()
    }

    /** Скруглённые углы (маска поверх видео) + перетаскивание (тап — раскрыть). */
    private fun setupLocalPreview() {
        val dp = resources.displayMetrics.density
        binding.previewMask.maskColor = 0xFF101418.toInt() // цвет фона экрана
        binding.previewMask.cornerRadius = 16f * dp

        // Начальное положение — верхний-правый угол (через TOP|START + отступы).
        binding.previewContainer.post {
            val parent = binding.previewContainer.parent as android.view.View
            val lp = binding.previewContainer.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or Gravity.START
            val pad = (16 * dp).toInt()
            curLeft = (parent.width - binding.previewContainer.width - pad).coerceAtLeast(pad)
            curTop = pad
            lp.leftMargin = curLeft
            lp.topMargin = curTop
            binding.previewContainer.layoutParams = lp
        }

        binding.localRenderer.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    previewMoved = false
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startLeft = curLeft
                    startTop = curTop
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) previewMoved = true
                    if (previewMoved && !previewExpanded) {
                        val parent = binding.previewContainer.parent as android.view.View
                        val lp = binding.previewContainer.layoutParams as FrameLayout.LayoutParams
                        val maxX = (parent.width - binding.previewContainer.width).coerceAtLeast(0)
                        val maxY = (parent.height - binding.previewContainer.height).coerceAtLeast(0)
                        curLeft = (startLeft + dx).coerceIn(0, maxX)
                        curTop = (startTop + dy).coerceIn(0, maxY)
                        lp.leftMargin = curLeft
                        lp.topMargin = curTop
                        binding.previewContainer.layoutParams = lp
                    }
                }
                MotionEvent.ACTION_UP -> {
                    binding.localRenderer.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    if (!previewMoved) togglePreview()
                }
            }
            true
        }
    }

    /** Цвет индикатора качества связи: зелёный/жёлтый/красный. */
    private fun setQuality(color: Int) {
        binding.qualityDot.setTextColor(color)
    }

    private fun sendFileViaDataChannel(uri: Uri) {
        val mgr = WebRtcController.manager ?: return
        if (!mgr.isDataChannelOpen()) {
            Toast.makeText(this, R.string.msg_dc_not_open, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                mgr.sendFile(uri)
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@CallActivity,
                        "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    //  Утилиты
    // ------------------------------------------------------------------------

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("call_link", text))
        Toast.makeText(this, R.string.msg_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLink(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.chooser_share)))
    }

    // ------------------------------------------------------------------------
    //  Доставка ссылки: контакт + мессенджер, QR-код
    // ------------------------------------------------------------------------

    /** Текущая ссылка, которую показываем/отправляем (offer или answer). */
    private fun currentLink(): String? = offerLink ?: answerLink

    /** Запуск выбора контакта (с запросом READ_CONTACTS при необходимости). */
    private fun startSendViaContact() {
        if (currentLink() == null) return
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchContactPicker()
        else contactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
    }

    private fun launchContactPicker() {
        try {
            pickContactLauncher.launch(null)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.msg_contacts_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private fun onContactPicked(uri: Uri) {
        val numbers = LinkDelivery.loadPhoneNumbers(this, uri)
        if (numbers.isEmpty()) {
            Toast.makeText(this, R.string.contact_no_number, Toast.LENGTH_LONG).show()
            return
        }
        // Сразу открываем системный список приложений для отправки приглашения
        // (SMS, мессенджеры — все сразу); ссылка подставлена в поле ввода.
        currentLink()?.let { shareLink(it) }
    }

    /** Показывает QR-код текущей ссылки в диалоге. */
    private fun showQrDialog(text: String) {
        val bmp: Bitmap = LinkDelivery.generateQr(text, 800) ?: run {
            Toast.makeText(this, "Не удалось сгенерировать QR", Toast.LENGTH_SHORT).show()
            return
        }
        val imageView = ImageView(this).apply {
            setImageBitmap(bmp)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_dialog_title)
            .setMessage(R.string.qr_dialog_hint)
            .setView(imageView)
            .setNegativeButton(R.string.qr_share_image) { _, _ ->
                // Отправить QR как файл-изображение (PNG), а не как ссылку.
                if (!LinkDelivery.shareImage(this, bmp)) {
                    Toast.makeText(this, R.string.qr_share_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Запуск сканера QR: выбор способа — камерой или из изображения. */
    private fun startQrScan() {
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_method_title)
            .setItems(
                arrayOf(
                    getString(R.string.scan_method_camera),
                    getString(R.string.scan_method_image)
                )
            ) { _, which ->
                if (which == 0) startCameraScan() else pickImageLauncher.launch("image/*")
            }
            .show()
    }

    /** Сканер QR камерой (ZXing). */
    private fun startCameraScan() {
        val integrator = IntentIntegrator(this).apply {
            setPrompt(getString(R.string.scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
            setDesiredBarcodeFormats(listOf(IntentIntegrator.QR_CODE))
        }
        try {
            qrScanLauncher.launch(integrator.createScanIntent())
        } catch (_: Exception) {
            Toast.makeText(this, R.string.msg_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    /** Распознавание QR из выбранного изображения. */
    private fun onImagePicked(uri: Uri) {
        val bitmap = LinkDelivery.loadBitmap(this, uri)
        val text = if (bitmap != null) LinkDelivery.decodeQrFromBitmap(bitmap) else null
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.qr_not_found, Toast.LENGTH_LONG).show()
        } else {
            handleScannedLink(text)
        }
    }

    /** Обработка отсканированной ссылки (как вставка/переход по deep link). */
    private fun handleScannedLink(text: String) {
        val parsed = SdpCodec.parseLink(text)
        if (parsed == null) {
            Toast.makeText(this, R.string.msg_invalid_link, Toast.LENGTH_LONG).show()
            return
        }
        when (parsed.type) {
            SignalType.OFFER -> {
                // Получен offer — открываем режим принимающего.
                startActivity(CallActivity.intent(this, CallMode.ANSWERER, parsed.sdp))
            }
            SignalType.ANSWER -> {
                // Инициатор применяет ответную ссылку.
                startApplyAnswerFlow(parsed.sdp)
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
        val mic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
        return camera == PackageManager.PERMISSION_GRANTED && mic == PackageManager.PERMISSION_GRANTED
    }

    /** Завершение звонка пользователем: освобождаем ресурсы и закрываем экран. */
    private fun endCall() {
        RoomHistory.setActiveRoom(this, null)
        WebRtcController.reset()
        finish()
    }

    /** Неустранимая ошибка: показать сообщение и закрыть экран. */
    private fun fail(reason: String) {
        showProgress(false)
        setStatus(R.string.status_disconnected)
        Toast.makeText(
            this,
            getString(R.string.msg_failed, reason),
            Toast.LENGTH_LONG
        ).show()
        WebRtcController.reset()
        finish()
    }

    // ------------------------------------------------------------------------
    //  WebRtcListener
    // ------------------------------------------------------------------------

    override fun onConnected() {
        runOnUiThread {
            showProgress(false)
            hideSignalingPanels()
            setStatus(R.string.status_connected)
            setQuality(0xFF43A047.toInt()) // зелёный — на связи
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            // Кратковременный обрыв: не убиваем звонок, даём WebRTC восстановиться.
            setQuality(0xFFFFB300.toInt()) // жёлтый — потеря, восстановление
            binding.statusText.text = "Соединение потеряно, восстановление…"
        }
    }

    override fun onFileSendProgress(sent: Long, total: Long) {
        runOnUiThread {
            val pct = if (total > 0) (sent * 100 / total).toInt() else 0
            binding.statusText.text = "Отправка файла… $pct%"
        }
    }

    override fun onFileReceived(fileName: String, filePath: String) {
        runOnUiThread {
            binding.statusText.text = getString(R.string.status_connected)
            AlertDialog.Builder(this)
                .setTitle(R.string.file_received_title)
                .setMessage(fileName)
                .setPositiveButton(R.string.file_open) { _, _ ->
                    try {
                        val file = java.io.File(filePath)
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this, "${packageName}.fileprovider", file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(this, R.string.msg_cannot_open, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.file_share) { _, _ ->
                    try {
                        val file = java.io.File(filePath)
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this, "${packageName}.fileprovider", file
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = contentResolver.getType(uri) ?: "*/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, fileName))
                    } catch (_: Exception) {
                        Toast.makeText(this, R.string.msg_cannot_open, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onFailed(reason: String) {
        runOnUiThread {
            showProgress(false)
            // В режиме комнаты пробуем автоматически переподключиться.
            if (isRoomCall && retryCount < maxRetries) {
                binding.statusText.text =
                    "Соединение потеряно, переподключение… (попытка ${retryCount + 1}/$maxRetries)"
                lifecycleScope.launch {
                    delay(retryDelayMs)
                    retryRoomCall()
                }
            } else {
                // Попытки исчерпаны или режим без комнаты — показать детали и закрыть.
                setStatus(R.string.status_disconnected)
                setQuality(0xFFE53935.toInt()) // красный — связь потеряна
                AlertDialog.Builder(this)
                    .setTitle("Соединение не удалось")
                    .setMessage(reason)
                    .setCancelable(false)
                    .setPositiveButton("Закрыть") { _, _ ->
                        RoomHistory.setActiveRoom(this, null)
                        WebRtcController.reset()
                        finish()
                    }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------------
    //  Жизненный цикл
    // ------------------------------------------------------------------------

    private var lastAppliedClipboard: String? = null

    override fun onResume() {
        super.onResume()
        maybeAutoApplyAnswerFromClipboard()
        // Перепривязываем рендереры — на Android 14/15 поверхность SurfaceView
        // может пересоздаваться при сворачивании/возврате, и видео "чернеет".
        if (renderersInitialized) {
            val mgr = WebRtcController.manager
            mgr?.detachRenderers()
            mgr?.attachLocalRenderer(binding.localRenderer)
            mgr?.attachRemoteRenderer(binding.remoteRenderer)
        }
    }

    /**
     * Авто-применение ответной ссылки для инициатора: если в буфере обмена
     * лежит ссылка-ответ (её обычно копируют из мессенджера), применяем её
     * автоматически — без ручной вставки. Защита от повторов по [lastAppliedClipboard].
     */
    private fun maybeAutoApplyAnswerFromClipboard() {
        // Имеет смысл только для инициатора, ожидающего ответ.
        if (WebRtcController.localOfferSdp == null || WebRtcController.manager == null) return
        val text = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        if (text == lastAppliedClipboard) return
        val parsed = SdpCodec.parseLink(text) ?: return
        if (parsed.type != SignalType.ANSWER) return
        lastAppliedClipboard = text
        Toast.makeText(this, R.string.msg_autocopy_answer, Toast.LENGTH_SHORT).show()
        startApplyAnswerFlow(parsed.sdp)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Снимаем удержание процесса.
        CallService.stop(this)
        // Отвязываем рендереры от менеджера.
        WebRtcController.manager?.detachRenderers()
        runCatching {
            if (renderersInitialized) {
                binding.localRenderer.release()
                binding.remoteRenderer.release()
                renderersInitialized = false
            }
        }
        // Если экран закрывается окончательно — освобождаем и сам менеджер.
        if (isFinishing) {
            RoomHistory.setActiveRoom(this, null)
            WebRtcController.reset()
        }
    }

    companion object {
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_SDP = "extra_sdp"

        /** Фабрика Intent для запуска экрана звонка в нужном режиме. */
        fun intent(context: Context, mode: CallMode, sdp: String?): Intent =
            Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode.name)
                sdp?.let { putExtra(EXTRA_SDP, it) }
            }
    }
}
