package com.example.p2pcall.signaling

import android.content.Context
import com.example.p2pcall.config.AppConfig

/**
 * Хранилище комнат: история недавних кодов комнат и флаг активного звонка
 * (для авто-восстановления комнаты после убийства процесса).
 */
object RoomHistory {
    private const val HISTORY_KEY = "room_history"
    private const val ACTIVE_KEY = "active_room"
    private const val MAX = 5

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)

    /** Добавить код комнаты в историю (наверх, без дубликатов). */
    fun add(ctx: Context, code: String) {
        if (code.isBlank()) return
        val cur = list(ctx).toMutableList()
        cur.remove(code)
        cur.add(0, code)
        prefs(ctx).edit().putString(HISTORY_KEY, cur.take(MAX).joinToString(",")).apply()
    }

    /** Список недавних кодов комнат. */
    fun list(ctx: Context): List<String> =
        prefs(ctx).getString(HISTORY_KEY, "").orEmpty()
            .split(",").filter { it.isNotBlank() }

    /** Запомнить активный звонок по комнате (для авто-восстановления). null — сброс. */
    fun setActiveRoom(ctx: Context, code: String?) {
        prefs(ctx).edit().putString(ACTIVE_KEY, code).apply()
    }

    fun getActiveRoom(ctx: Context): String? =
        prefs(ctx).getString(ACTIVE_KEY, null)?.takeIf { it.isNotBlank() }
}
