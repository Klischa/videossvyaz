package com.example.p2pcall.signaling

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.net.URLEncoder

/**
 * Мессенджеры для прямой отправки ссылки по номеру контакта.
 *
 * WhatsApp и SMS умеют открывать чат с конкретным номером и подставлять ссылку.
 * Telegram не поддерживает открытие чата по номеру через URI — для него
 * используется обычный «поделиться» (пользователь сам выбирает чат).
 */
enum class Messenger(val title: String) {
    WHATSAPP("WhatsApp"),
    SMS("SMS"),
    TELEGRAM("Telegram"),
    SYSTEM("Системный выбор")
}

/**
 * Доставка ссылки собеседнику:
 *   • чтение номера выбранного контакта;
 *   • открытие нужного мессенджера по номеру со ссылкой;
 *   • генерация QR-кода из ссылки.
 */
object LinkDelivery {

    /** Читает все номера телефонов выбранного контакта. */
    fun loadPhoneNumbers(context: Context, contactUri: Uri): List<String> {
        val result = mutableListOf<String>()
        var contactId: String? = null

        context.contentResolver.query(
            contactUri,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
            ),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) contactId = c.getString(0) }

        val id = contactId ?: return emptyList()

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id),
            null
        )?.use { c ->
            val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { result.add(it) }
            }
        }
        return result
    }

    /** Очищает номер до цифр (wa.me ждёт international без '+'). */
    fun toDigits(raw: String): String = raw.filter { it.isDigit() }

    /** Генерация QR-кода в Bitmap (null при ошибке). */
    fun generateQr(text: String, size: Int): Bitmap? = try {
        BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, size, size)
    } catch (e: Exception) {
        null
    }

    /**
     * Открывает мессенджер и пытается доставить ссылку указанному номеру.
     * Если конкретное приложение не установлено — открывает системный выбор.
     */
    fun openMessenger(context: Context, messenger: Messenger, rawNumber: String, link: String) {
        val digits = toDigits(rawNumber)
        val intent = when (messenger) {
            Messenger.WHATSAPP -> {
                val url = "https://api.whatsapp.com/send?phone=$digits" +
                    "&text=${URLEncoder.encode(link, "UTF-8")}"
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp")
            }
            Messenger.SMS -> {
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$rawNumber"))
                    .putExtra("sms_body", link)
            }
            Messenger.TELEGRAM -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link)
                setPackage("org.telegram.messenger")
            }
            Messenger.SYSTEM -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link)
            }
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Приложение не установлено — пробуем без привязки к пакету.
            try {
                intent.setPackage(null)
                context.startActivity(Intent.createChooser(intent, "Отправить ссылку"))
            } catch (_: Exception) {
                Toast.makeText(context, "Не удалось открыть мессенджер", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
