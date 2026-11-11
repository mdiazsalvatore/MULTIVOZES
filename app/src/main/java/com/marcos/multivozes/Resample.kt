package com.marcos.multivozes

/** Acumula áudio mono em blocos, sem saber o tamanho final. */
class ShortCollector {
    private val chunks = ArrayList<ShortArray>()
    private val chunkSize = 1 shl 18
    var size = 0
        private set

    fun add(v: Short) {
        val idx = size % chunkSize
        if (idx == 0) chunks.add(ShortArray(chunkSize))
        chunks[chunks.size - 1][idx] = v
        size++
    }

    operator fun get(i: Int): Short = chunks[i / chunkSize][i % chunkSize]

    fun toArray(): ShortArray {
        val out = ShortArray(size)
        var pos = 0
        for (c in chunks) {
            val n = minOf(c.size, size - pos)
            if (n <= 0) break
            System.arraycopy(c, 0, out, pos, n)
            pos += n
        }
        return out
    }
}

object Resample {
    /**
     * Converte a taxa de amostragem. Quando reduz a taxa, passa antes um filtro
     * passa-baixa simples para não criar chiado (aliasing).
     */
    fun convert(src: ShortCollector, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return src.toArray()
        val n = src.size
        if (n == 0) return ShortArray(0)
        val input: FloatArray = FloatArray(n) { src[it].toFloat() }
        if (toRate < fromRate) lowPass(input, fromRate, toRate * 0.45)
        val outLen = ((n.toLong() * toRate) / fromRate).toInt()
        val ratio = fromRate.toDouble() / toRate
        return ShortArray(outLen) { i ->
            val pos = i * ratio
            val k = pos.toInt()
            val frac = (pos - k).toFloat()
            val a = input[k]
            val b = if (k + 1 < n) input[k + 1] else a
            (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Dois polos em série (12 dB/oitava), ida e volta para não deslocar no tempo. */
    private fun lowPass(x: FloatArray, rate: Int, cutoff: Double) {
        val a = Math.exp(-2.0 * Math.PI * cutoff / rate).toFloat()
        val b = 1f - a
        repeat(2) {
            var y = 0f
            for (i in x.indices) { y = b * x[i] + a * y; x[i] = y }
            y = 0f
            for (i in x.indices.reversed()) { y = b * x[i] + a * y; x[i] = y }
        }
    }
}
