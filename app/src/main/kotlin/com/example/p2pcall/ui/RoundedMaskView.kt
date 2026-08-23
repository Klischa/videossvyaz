package com.example.p2pcall.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Маска со скруглёнными углами: рисует [maskColor] (фон экрана) везде, КРОМЕ
 * скруглённого прямоугольника в центре. Кладётся поверх SurfaceViewRenderer —
 * так углы видео становятся скруглёнными (SurfaceView сам по себе не клипится
 * через outline, поэтому нужен этот оверлей).
 *
 * Заполнение углов делается через Path с FillType EVEN_ODD (внешний квадрат +
 * внутренний скруглённый прямоугольник) — это работает и на hardware-accel.
 */
class RoundedMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cornerRadius: Float = 16f * resources.displayMetrics.density
    var maskColor: Int = Color.BLACK

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        paint.color = maskColor
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        path.reset()
        path.addRect(rect, Path.Direction.CW)
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, paint)
    }
}
