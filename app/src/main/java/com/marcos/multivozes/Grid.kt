package com.marcos.multivozes

/** Posição de cada voz na tela dividida (mesma no app e no vídeo final). */
object Grid {
    const val GAP = 8

    fun outSize(vertical: Boolean) = if (vertical) 1080 to 1920 else 1920 to 1080

    /** Retângulos normalizados (esquerda, topo, direita, base). */
    fun rects(vertical: Boolean, n: Int): List<FloatArray> {
        if (!vertical) return List(n) { i -> floatArrayOf(i / n.toFloat(), 0f, (i + 1) / n.toFloat(), 1f) }
        return when (n) {
            2 -> listOf(floatArrayOf(0f, 0f, 1f, .5f), floatArrayOf(0f, .5f, 1f, 1f))
            3 -> listOf(floatArrayOf(0f, 0f, .5f, .5f), floatArrayOf(.5f, 0f, 1f, .5f), floatArrayOf(0f, .5f, 1f, 1f))
            else -> listOf(
                floatArrayOf(0f, 0f, .5f, .5f), floatArrayOf(.5f, 0f, 1f, .5f),
                floatArrayOf(0f, .5f, .5f, 1f), floatArrayOf(.5f, .5f, 1f, 1f)
            )
        }
    }

    /** Retângulos em pixels do vídeo final, com espaço entre as vozes e medidas pares. */
    fun pixelRects(vertical: Boolean, n: Int): List<IntArray> {
        val (w, h) = outSize(vertical)
        val half = GAP / 2
        return rects(vertical, n).map { r ->
            val l = (r[0] * w).toInt() + if (r[0] > 0f) half else 0
            val t = (r[1] * h).toInt() + if (r[1] > 0f) half else 0
            val rr = (r[2] * w).toInt() - if (r[2] < 1f) half else 0
            val b = (r[3] * h).toInt() - if (r[3] < 1f) half else 0
            val ww = (rr - l) and 1.inv()
            val hh = (b - t) and 1.inv()
            intArrayOf(l, t, ww, hh)
        }
    }
}
