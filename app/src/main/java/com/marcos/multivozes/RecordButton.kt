package com.marcos.multivozes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** Botão de gravar: círculo vermelho; vira quadrado enquanto grava. */
class RecordButton(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var recording = false
        set(v) { field = v; invalidate() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.ink)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.rec)
    }
    private val r = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f
        ring.strokeWidth = radius * 0.09f
        ring.alpha = if (isEnabled) 255 else 90
        fill.alpha = if (isEnabled) 255 else 90
        canvas.drawCircle(cx, cy, radius - ring.strokeWidth, ring)
        if (recording) {
            val s = radius * 0.42f
            r.set(cx - s, cy - s, cx + s, cy + s)
            canvas.drawRoundRect(r, s * 0.25f, s * 0.25f, fill)
        } else {
            canvas.drawCircle(cx, cy, radius * 0.66f, fill)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }
}
