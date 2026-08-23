package com.example.p2pcall.signaling

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.File
import java.io.FileOutputStream
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

    /** Пакеты конкретных приложений (для проверки установки). */
    private const val WHATSAPP_PKG = "com.whatsapp"
    private const val TELEGRAM_PKG = "org.telegram.messenger"

    /** Установлено ли приложение-мессенджер на устройстве. */
    fun isAvailable(context: Context, messenger: Messenger): Boolean = when (messenger) {
        Messenger.WHATSAPP -> isPackageInstalled(context, WHATSAPP_PKG)
        Messenger.TELEGRAM -> isPackageInstalled(context, TELEGRAM_PKG)
        // SMS и системный выбор доступны всегда.
        Messenger.SMS, Messenger.SYSTEM -> true
    }

    /** Список мессенджеров, доступных на устройстве (всегда включает SMS и системный выбор). */
    fun availableMessengers(context: Context): List<Messenger> =
        Messenger.values().filter { isAvailable(context, it) }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0) != null
        }
    } catch (_: Exception) {
        false
    }

    /** Генерация QR-кода в Bitmap (null при ошибке). */
    fun generateQr(text: String, size: Int): Bitmap? = try {
        BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, size, size)
    } catch (e: Exception) {
        null
    }

    /**
     * Загружает Bitmap из [uri] с понижающей дискретизацией до [maxDim] по большей
     * стороне — чтобы избежать OOM на больших фотографиях и ускорить декодирование.
     */
    fun loadBitmap(context: Context, uri: Uri, maxDim: Int = 2000): Bitmap? = try {
        val resolver = context.contentResolver
        // 1-й проход: только размеры.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val max = maxOf(bounds.outWidth, bounds.outHeight)
        while (max / sample > maxDim) sample *= 2
        // 2-й проход: с inSampleSize.
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }

    /**
     * Сохраняет Bitmap QR-кода во временный PNG и открывает системный «поделиться»,
     * чтобы отправить его как файл-изображение (а не как ссылку).
     */
    fun shareImage(context: Context, bitmap: Bitmap): Boolean = try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "qr_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться QR-кодом"))
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Декодирует QR-код из растрового изображения.
     * Возвращает текст ссылки или null, если QR не найден.
     */
    fun decodeQrFromBitmap(bitmap: Bitmap): String? = try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))

        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
        try {
            reader.decodeWithState(binary).text
        } finally {
            reader.reset()
        }
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
