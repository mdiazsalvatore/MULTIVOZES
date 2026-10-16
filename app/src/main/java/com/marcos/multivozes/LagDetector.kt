package com.marcos.multivozes

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Localiza rajadas conhecidas dentro da gravação, por correlação cruzada.
 * Não depende do Android, para poder ser testado no computador.
 */
object LagDetector {

    class Result(
        val lags: List<Int>,       // atraso de cada rajada encontrada, em amostras
        val medianLag: Double?,    // atraso típico
        val spreadFrames: Int      // diferença entre o maior e o menor atraso
    )

    /**
     * @param y gravação completa
     * @param contentIndex índice em [y] onde o conteúdo começa, pela estimativa do motor
     * @param positions posições das rajadas no conteúdo, em amostras
     * @param searchFrames janela de busca para cada lado
     */
    fun detect(
        y: ShortArray,
        contentIndex: Int,
        positions: IntArray,
        template: FloatArray,
        searchFrames: Int
    ): Result {
        val m = template.size
        var tEnergy = 0.0
        for (v in template) tEnergy += v * v
        val tNorm = sqrt(tEnergy)
        val lags = ArrayList<Int>()

        for (p in positions) {
            val center = contentIndex + p
            val lo = center - searchFrames
            val hi = center + searchFrames
            if (lo < 0 || hi + m >= y.size) continue

            // Energia da janela deslizante, para normalizar a correlação.
            val n = hi - lo + 1
            val scores = DoubleArray(n)
            var winE = 0.0
            for (j in 0 until m) { val s = y[lo + j].toDouble(); winE += s * s }
            for (k in 0 until n) {
                val start = lo + k
                if (k > 0) {
                    val out = y[start - 1].toDouble(); val inn = y[start + m - 1].toDouble()
                    winE += inn * inn - out * out
                }
                var c = 0.0
                for (j in 0 until m) c += template[j] * y[start + j]
                val norm = sqrt(maxOf(winE, 1e-9)) * tNorm
                scores[k] = abs(c) / norm
            }

            var best = 0
            for (k in 1 until n) if (scores[k] > scores[best]) best = k
            val peak = scores[best]

            // Exige um pico nítido: bem acima do fundo e acima de um limiar absoluto.
            val sorted = scores.sortedArray()
            val floor = sorted[sorted.size / 2]
            if (peak < 0.35 || peak < floor * 4) continue
            lags.add(lo + best - center)
        }

        if (lags.isEmpty()) return Result(lags, null, 0)
        val s = lags.sorted()
        val med = if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        // Descarta valores muito fora (eco, batida na mesa) antes de medir a variação.
        val tol = maxOf(1, (template.size / 10))
        val kept = s.filter { abs(it - med) <= tol * 4 }
        val spread = if (kept.isEmpty()) 0 else kept.last() - kept.first()
        return Result(kept, med, spread)
    }
}
