package com.example.p2pcall.ui

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton

/**
 * Лёгкая тактильная отдача (haptic feedback) на нажатие.
 *
 * Рекурсивно обходит дерево View и вешает OnTouchListener на все кнопки
 * (Button/ImageButton): на ACTION_DOWN срабатывает performHapticFeedback
 * (VIRTUAL_KEY — короткое «постукивание»). Возвращаем false, поэтому ripple,
 * состояние pressed и существующие onClick-слушатели работают как обычно.
 *
 * Разрешение VIBRATE не требуется — используется системный haptic-канал.
 */
fun View.applyHapticToClickables() {
    if (this is Button || this is ImageButton) {
        setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            false
        }
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).applyHapticToClickables()
        }
    }
}
