package com.example.ioscamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val p = Paint().apply { color = Color.argb(150, 255, 255, 255); strokeWidth = 2f }
    override fun onDraw(canvas: Canvas) {
        for (i in 1..2) {
            canvas.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), p)
            canvas.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, p)
        }
    }
}
