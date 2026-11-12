package com.marcos.multivozes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Marca d'água do vídeo final: o ícone do app com "MultiVozes" embaixo,
 * no canto inferior direito.
 *
 * A imagem tem o tamanho exato do vídeo (transparente fora da marca), assim
 * ela cai no mesmo lugar e sem esticar, pixel a pixel.
 */
object Watermark {

    /** Opacidade da marca (0..255): meio transparente, mas com bordas nítidas. */
    private const val ALPHA = 170

    fun create(context: Context, outW: Int, outH: Int): Bitmap {
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val unit = min(outW, outH).toFloat()          // 1080 nos dois formatos
        val logo = (unit * 0.11f).roundToInt()         // ~119 px
        val margin = unit * 0.035f
        val gap = unit * 0.008f

        val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 0.03f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.02f
            textAlign = Paint.Align.CENTER
            // Sombra leve: o texto continua legível sobre fundos claros.
            setShadowLayer(unit * 0.004f, 0f, unit * 0.0015f, 0xB0000000.toInt())
        }
        val label = "MultiVozes"
        val fm = text.fontMetrics
        val textW = text.measureText(label)
        val blockW = max(logo.toFloat(), textW)
        val cx = outW - margin - blockW / 2f
        val baseline = outH - margin - fm.descent
        val logoBottom = baseline + fm.ascent - gap
        val logoRect = RectF(cx - logo / 2f, logoBottom - logo, cx + logo / 2f, logoBottom)

        c.saveLayerAlpha(0f, 0f, outW.toFloat(), outH.toFloat(), ALPHA)

        // Ícone do app: quadrado arredondado com o desenho das quatro vozes.
        val radius = logo * 0.23f
        val clip = Path().apply { addRoundRect(logoRect, radius, radius, Path.Direction.CW) }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.bg)
            setShadowLayer(unit * 0.006f, 0f, unit * 0.002f, 0x80000000.toInt())
        }
        c.drawRoundRect(logoRect, radius, radius, shadow)
        c.save()
        c.clipPath(clip)
        // O desenho do ícone usa a área central de 72 de 108 unidades (padrão de ícone adaptável).
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_fg)?.let { fg ->
            val full = logo * 108f / 72f
            val l = logoRect.centerX() - full / 2f
            val t = logoRect.centerY() - full / 2f
            fg.setBounds(l.roundToInt(), t.roundToInt(), (l + full).roundToInt(), (t + full).roundToInt())
            fg.draw(c)
        }
        c.restore()

        c.drawText(label, cx, baseline, text)
        c.restore()
        return bmp
    }
}
