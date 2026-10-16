package com.marcos.multivozes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Uma voz na tela: vídeo gravado ou câmera, com o rótulo por cima. */
class TileView(context: Context, val index: Int) : FrameLayout(context) {
    val texture = TextureView(context)
    val cameraSlot = FrameLayout(context)
    private val label = TextView(context)
    private val hint = TextView(context)
    private val border = GradientDrawable()
    var selectedTile = false
        set(v) { field = v; updateBorder() }
    var recordingNow = false
        set(v) { field = v; updateBorder() }

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cameraSlot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        hint.setTextColor(ContextCompat.getColor(context, R.color.ink_dim))
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        hint.gravity = Gravity.CENTER
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        label.setTextColor(Color.WHITE)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        label.setPadding(dp(8), dp(3), dp(8), dp(3))
        label.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x99000000.toInt())
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        lp.setMargins(dp(6), dp(6), 0, 0)
        addView(label, lp)
        border.setColor(Color.TRANSPARENT)
        foreground = border
        setLabel("Voz ${index + 1}")
    }

    fun setLabel(text: String) { label.text = text }
    fun setHint(text: String?) {
        hint.text = text ?: ""
        hint.visibility = if (text == null) View.GONE else View.VISIBLE
    }
    fun showVideo(show: Boolean) { texture.visibility = if (show) View.VISIBLE else View.INVISIBLE }

    private fun updateBorder() {
        val c = when {
            recordingNow -> ContextCompat.getColor(context, R.color.rec)
            selectedTile -> ContextCompat.getColor(context, R.color.accent)
            else -> Color.TRANSPARENT
        }
        border.setStroke(dp(3), c)
        invalidate()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** Área que mostra as vozes exatamente na proporção do vídeo final. */
class StageView(context: Context) : ViewGroup(context) {
    var vertical = true
        set(v) { field = v; requestLayout() }
    var rects: List<FloatArray> = emptyList()
        set(v) { field = v; requestLayout() }
    private val frame = RectF()
    private val bg = Paint().apply { color = Color.BLACK }

    init { setWillNotDraw(false) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        computeFrame(w, h)
        for (i in 0 until childCount) {
            val r = rects.getOrNull(i) ?: continue
            val cw = ((r[2] - r[0]) * frame.width() - gapPx()).toInt().coerceAtLeast(1)
            val ch = ((r[3] - r[1]) * frame.height() - gapPx()).toInt().coerceAtLeast(1)
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            )
        }
    }

    private fun gapPx() = 3 * resources.displayMetrics.density

    private fun computeFrame(w: Int, h: Int) {
        val (ow, oh) = Grid.outSize(vertical)
        val scale = minOf(w / ow.toFloat(), h / oh.toFloat())
        val fw = ow * scale
        val fh = oh * scale
        frame.set((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val half = gapPx() / 2
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val rr = rects.getOrNull(i)
            if (rr == null) { c.layout(0, 0, 0, 0); continue }
            val left = frame.left + rr[0] * frame.width() + if (rr[0] > 0f) half else 0f
            val top = frame.top + rr[1] * frame.height() + if (rr[1] > 0f) half else 0f
            c.layout(left.toInt(), top.toInt(), left.toInt() + c.measuredWidth, top.toInt() + c.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(frame, bg)
    }
}
