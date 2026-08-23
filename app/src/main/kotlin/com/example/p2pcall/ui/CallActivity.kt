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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.p2pcall.R
import com.example.p2pcall.databinding.ActivityCallBinding
import com.example.p2pcall.signaling.LinkDelivery
import com.example.p2pcall.signaling.Messenger
import com.example.p2pcall.signaling.SignalType
import com.example.p2pcall.signaling.SdpCodec
import com.example.p2pcall.webrtc.CallMode
import com.example.p2pcall.webrtc.WebRtcController
import com.example.p2pcall.webrtc.WebRtcListener
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon

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
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO
                )
            )
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

        // Доставка ссылки: выбор контакта + мессенджер, QR-код, сканирование QR.
        binding.btnContactOffer.setOnClickListener { startSendViaContact() }
        binding.btnQrOffer.setOnClickListener { currentLink()?.let { showQrDialog(it) } }
        binding.btnContactAnswer.setOnClickListener { startSendViaContact() }
        binding.btnQrAnswer.setOnClickListener { currentLink()?.let { showQrDialog(it) } }
        binding.btnScanAnswerQr.setOnClickListener { startQrScan() }

        // Локальное превью: тап — раскрыть на весь экран (контроль кадра),
        // тап ещё раз — вернуть в маленькое PiP-окно.
        binding.localRenderer.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            togglePreview()
        }
    }

    /** Раскрыть/свернуть предпросмотр своей камеры. */
    private var previewExpanded = false
    private fun togglePreview() {
        previewExpanded = !previewExpanded
        val lp = binding.localRenderer.layoutParams as FrameLayout.LayoutParams
        val dp = resources.displayMetrics.density
        if (previewExpanded) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.CENTER
        } else {
            lp.width = (110 * dp).toInt()
            lp.height = (150 * dp).toInt()
            lp.gravity = Gravity.TOP or Gravity.END
        }
        binding.localRenderer.layoutParams = lp
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
        if (numbers.size == 1) {
            showMessengerChooser(numbers.first())
        } else {
            val items = numbers.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.contact_pick_number)
                .setItems(items) { _, which -> showMessengerChooser(items[which]) }
                .show()
        }
    }

    private fun showMessengerChooser(number: String) {
        val link = currentLink() ?: return
        // Показываем только установленные мессенджеры (SMS и системный выбор — всегда).
        val messengers = LinkDelivery.availableMessengers(this)
        val titles = messengers.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.messenger_dialog_title)
            .setItems(titles) { _, which ->
                LinkDelivery.openMessenger(this, messengers[which], number, link)
            }
            .show()
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
        }
    }

    override fun onDisconnected() {
        runOnUiThread { setStatus(R.string.status_disconnected) }
    }

    override fun onFailed(reason: String) {
        runOnUiThread { fail(reason) }
    }

    // ------------------------------------------------------------------------
    //  Жизненный цикл
    // ------------------------------------------------------------------------

    override fun onDestroy() {
        super.onDestroy()
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
