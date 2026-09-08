package com.example.p2pcall.signaling

import android.content.Context
import android.net.Uri
import com.example.p2pcall.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Сигналинг «комнат» через REST API.
 *
 * Два варианта сервера (выбирается тем, что вписано в настройках):
 *  1. Firebase Realtime Database — https://ваш-проект...firebasedatabase.app
 *     (работает БЕЗ google-services.json и Firebase SDK — обычные HTTPS-запросы);
 *  2. Свой сервер на Windows 7 — http://IP-компьютера:8080
 *     (server/signaling_server.py, см. WIN7_SERVER.md — тот же REST-протокол).
 *
 * «Комната» = узел /rooms/{roomCode}. В нём хранятся offer и answer
 * (в нашем gzip+Base64-кодированном виде, см. [SdpCodec] — строка без спецсимволов,
 * поэтому JSON-кодирование сводится к обёртке в кавычки).
 *
 * Этот код комнаты и есть «постоянный ключ» для связи с одним человеком:
 * оба вводят его один раз, дальше звонок согласуется автоматически.
 */
object FirebaseSignaling {

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)

    /**
     * URL сигнального сервера, например:
     *  - https://myproj-default-rtdb.firebaseio.com (Firebase)
     *  - http://192.168.1.5:8080 (свой сервер на Windows 7)
     */
    fun dbUrl(ctx: Context): String = prefs(ctx).getString("firebase_url", "") ?: ""

    /** Код комнаты — общий «постоянный ключ» двух абонентов. */
    fun roomCode(ctx: Context): String = prefs(ctx).getString("room_code", "") ?: ""

    /** Настроено ли (есть и URL, и код комнаты). */
    fun isConfigured(ctx: Context): Boolean =
        dbUrl(ctx).isNotBlank() && roomCode(ctx).isNotBlank()

    private fun baseUrl(ctx: Context): String? {
        val db = dbUrl(ctx).trim().trimEnd('/')
        val room = roomCode(ctx).trim()
        // Кодируем код комнаты для пути URL (пробелы/кириллица).
        // Оба сервера (Firebase и свой) понимают percent-encoding.
        return if (db.isBlank() || room.isBlank()) null else "$db/rooms/${Uri.encode(room)}"
    }

    private suspend fun http(method: String, url: String, body: String? = null): String? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doInput = true
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                }
                if (body != null) {
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                }
                if (conn.responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use(BufferedReader::readText)
                } else null
            } catch (_: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    /** Достаёт строку из JSON-ответа сервера (строка приходит как "value", отсутствие — "null"). */
    private fun decodeValue(resp: String?): String? {
        if (resp.isNullOrBlank()) return null
        val t = resp.trim()
        if (t == "null") return null
        return if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t.substring(1, t.length - 1) else t
    }

    suspend fun writeOffer(ctx: Context, encoded: String): Boolean =
        baseUrl(ctx)?.let { http("PUT", "$it/offer.json", "\"$encoded\"") != null } ?: false

    suspend fun writeAnswer(ctx: Context, encoded: String): Boolean =
        baseUrl(ctx)?.let { http("PUT", "$it/answer.json", "\"$encoded\"") != null } ?: false

    suspend fun readOffer(ctx: Context): String? =
        baseUrl(ctx)?.let { decodeValue(http("GET", "$it/offer.json")) }

    suspend fun readAnswer(ctx: Context): String? =
        baseUrl(ctx)?.let { decodeValue(http("GET", "$it/answer.json")) }

    /** Удалить комнату (чтобы следующий звонок был «чистым»). */
    suspend fun clearRoom(ctx: Context): Boolean =
        baseUrl(ctx)?.let { http("DELETE", "$it.json") != null } ?: false
}
