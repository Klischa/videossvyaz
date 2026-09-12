package com.example.p2pcall.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.p2pcall.R
import com.example.p2pcall.config.AppConfig
import com.example.p2pcall.databinding.ActivityMainBinding
import com.example.p2pcall.signaling.FirebaseSignaling
import com.example.p2pcall.signaling.LinkDelivery
import com.example.p2pcall.signaling.RoomHistory
import com.example.p2pcall.signaling.SignalType
import com.example.p2pcall.signaling.SdpCodec
import com.example.p2pcall.webrtc.CallMode
import com.google.zxing.integration.android.IntentIntegrator

/**
 * Главный экран: выбор режима и маршрутизация deep link.
 *
 * Этот Activity (singleTop) перехватывает ссылки вида
 *   https://yourdomain.com/call?type=offer&sdp=...
 *   myapp://call?type=answer&sdp=...
 * и направляет их в [CallActivity].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Сканирование QR-кода со ссылкой-приглашением. */
    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val res = IntentIntegrator.parseActivityResult(
            IntentIntegrator.REQUEST_CODE, result.resultCode, result.data
        )
        val text = res?.contents
        if (!text.isNullOrEmpty() && !routeLink(text)) {
            Toast.makeText(this, R.string.msg_invalid_link, Toast.LENGTH_LONG).show()
        }
    }

    /** Выбор изображения из галереи для распознавания QR-приглашения. */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onImagePicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCreateCall.setOnClickListener {
            startActivity(
                CallActivity.intent(this, CallMode.NEW_OFFER, sdp = null)
            )
        }

        binding.btnJoinCall.setOnClickListener { showJoinDialog() }
        binding.btnScanQr.setOnClickListener { startQrScan() }
        binding.btnRoomCall.setOnClickListener {
            startActivity(CallActivity.intent(this, CallMode.ROOM, sdp = null))
        }
        binding.btnCloudCall.setOnClickListener {
            startActivity(CallActivity.intent(this, CallMode.CLOUD, sdp = null))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Лёгкая вибрация при нажатии всех кнопок.
        binding.root.applyHapticToClickables()

        // Если приложение открылось по deep link — обработаем его.
        handleViewIntent(intent)

        populateHistory()
        // Авто-восстановление комнаты, если процесс был убит во время звонка.
        RoomHistory.getActiveRoom(this)?.let {
            RoomHistory.setActiveRoom(this, null)
            if (FirebaseSignaling.isConfigured(this)) {
                startActivity(CallActivity.intent(this, CallMode.ROOM, sdp = null))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        populateHistory()
    }

    /** Кнопки недавних комнат для быстрого повторного вызова. */
    private fun populateHistory() {
        val container = binding.historyContainer
        container.removeAllViews()
        for (code in RoomHistory.list(this)) {
            val btn = android.widget.Button(this).apply {
                text = "📞 $code"
                setOnClickListener {
                    getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
                        .edit().putString("room_code", code).apply()
                    startActivity(CallActivity.intent(this@MainActivity, CallMode.ROOM, sdp = null))
                }
            }
            container.addView(btn)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /** Диалог ручной вставки ссылки (если deep link не сработал). */
    private fun showJoinDialog() {
        val edit = android.widget.EditText(this).apply {
            hint = getString(R.string.join_dialog_hint)
            setSingleLine(false)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.join_dialog_title)
            .setView(edit)
            .setPositiveButton(R.string.join_dialog_ok) { _, _ ->
                val text = edit.text?.toString().orEmpty()
                if (!routeLink(text)) {
                    Toast.makeText(this, R.string.msg_invalid_link, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.join_dialog_cancel, null)
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

    /** Распознавание QR из выбранного изображения и обработка ссылки. */
    private fun onImagePicked(uri: Uri) {
        val bitmap = LinkDelivery.loadBitmap(this, uri)
        val text = if (bitmap != null) LinkDelivery.decodeQrFromBitmap(bitmap) else null
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.qr_not_found, Toast.LENGTH_LONG).show()
        } else if (!routeLink(text)) {
            Toast.makeText(this, R.string.msg_invalid_link, Toast.LENGTH_LONG).show()
        }
    }

    /** Обрабатывает ACTION_VIEW: парсит ссылку и запускает нужный режим звонка. */
    private fun handleViewIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        routeLink(data.toString())
    }

    /**
     * Разбирает ссылку и направляет в CallActivity.
     * @return true, если ссылка корректна и маршрут выбран.
     */
    private fun routeLink(link: String): Boolean {
        val callLink = SdpCodec.parseLink(link) ?: return false
        return when (callLink.type) {
            SignalType.OFFER -> {
                // Получен offer → режим принимающего.
                startActivity(CallActivity.intent(this, CallMode.ANSWERER, callLink.sdp))
                true
            }
            SignalType.ANSWER -> {
                // Получен answer → режим инициатора (применить ответ).
                startActivity(CallActivity.intent(this, CallMode.APPLY_ANSWER, callLink.sdp))
                true
            }
        }
    }
}
