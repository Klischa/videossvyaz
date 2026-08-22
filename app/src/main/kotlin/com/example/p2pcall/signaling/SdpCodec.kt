package com.example.p2pcall.signaling

import android.net.Uri
import android.util.Base64
import com.example.p2pcall.config.AppConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Тип сигнального сообщения, передаваемого в ссылке.
 */
enum class SignalType { OFFER, ANSWER }

/**
 * Распарсенная ссылка-приглашение.
 */
data class CallLink(val type: SignalType, val sdp: String)

/**
 * Кодек для упаковки SDP в ссылку и обратно.
 *
 * Конвейер:  SDP (String)
 *           → UTF-8 байты
 *           → GZIP
 *           → Base64 (URL-safe, без переносов и padding)
 *           → ?sdp=…
 *
 * GZIP даёт многократное сжатие SDP (содержит много повторяющихся строк),
 * а URL-safe Base64 безопасно встраивается в query-параметр ссылки.
 */
object SdpCodec {

    /** GZIP-сжатие строки в массив байт. */
    fun gzipCompress(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    /** Распаковка GZIP-байт обратно в строку. */
    @Throws(IOException::class)
    fun gzipDecompress(bytes: ByteArray): String {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            return input.readBytes().toString(Charsets.UTF_8)
        }
    }

    /** Полный конвейер упаковки: SDP → сжатый Base64. */
    fun encode(sdp: String): String {
        val compressed = gzipCompress(sdp)
        return Base64.encodeToString(
            compressed,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /** Полный конвейер распаковки: Base64 → GZIP → SDP. */
    @Throws(IllegalArgumentException::class, IOException::class)
    fun decode(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING)
        return gzipDecompress(bytes)
    }

    /**
     * Формирует ссылку вида:
     *   https://yourdomain.com/call?type=offer&sdp=<base64>
     */
    fun buildLink(type: SignalType, sdp: String): String {
        val typeParam = if (type == SignalType.OFFER) "offer" else "answer"
        return "${AppConfig.BASE_URL}?type=$typeParam&sdp=${encode(sdp)}"
    }

    /**
     * Разбирает ссылку и извлекает [CallLink].
     * Возвращает null, если ссылка повреждена или не содержит корректных данных.
     */
    fun parseLink(input: String): CallLink? {
        val uri = Uri.parse(input.trim()) ?: return null
        val typeStr = uri.getQueryParameter("type") ?: return null
        val sdpParam = uri.getQueryParameter("sdp") ?: return null
        if (sdpParam.isBlank()) return null

        val type = when (typeStr.lowercase()) {
            "offer" -> SignalType.OFFER
            "answer" -> SignalType.ANSWER
            else -> return null
        }
        return try {
            val sdp = decode(sdpParam)
            if (sdp.isBlank()) null else CallLink(type, sdp)
        } catch (e: Exception) {
            // Повреждённый/нечитаемый payload.
            null
        }
    }
}
