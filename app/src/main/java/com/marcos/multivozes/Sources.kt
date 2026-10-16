package com.marcos.multivozes

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Gera o áudio que vai para o fone, quadro a quadro. */
fun interface PlaybackSource {
    fun fill(startFrame: Long, out: ShortArray, n: Int)
}

/** Som do metrônomo: tique curto, mais agudo no primeiro tempo do compasso. */
class Clicks(sampleRate: Int) {
    private val len = sampleRate * 30 / 1000
    val accent = FloatArray(len) { i -> click(i, sampleRate, 1760.0) * 0.55f }
    val normal = FloatArray(len) { i -> click(i, sampleRate, 1320.0) * 0.40f }
    val length get() = len

    private fun click(i: Int, sr: Int, f: Double): Float {
        val t = i.toDouble() / sr
        return (sin(2 * PI * f * t) * exp(-t * 180.0)).toFloat()
    }
}

/**
 * O que toca no fone durante uma gravação:
 * contagem antes de começar, as vozes já gravadas e (opcional) o metrônomo.
 */
/** Conteúdo musical (as vozes) tocado a partir do início da música. */
interface ContentSource {
    val length: Int
    /** Escreve [n] quadros a partir do quadro [from] da música em dst[offset...]. */
    fun read(from: Int, dst: FloatArray, offset: Int, n: Int)
}

/** Mixagem já pronta, em memória. */
class ShortContent(private val data: ShortArray) : ContentSource {
    override val length get() = data.size
    override fun read(from: Int, dst: FloatArray, offset: Int, n: Int) {
        for (i in 0 until n) dst[offset + i] = data[from + i] / 32768f
    }
}

/** Mixagem calculada na hora: mexer nos efeitos muda o som imediatamente. */
class LiveMixContent(private val engine: MixEngine) : ContentSource {
    override val length get() = engine.length
    override fun read(from: Int, dst: FloatArray, offset: Int, n: Int) {
        if (engine.position != from) engine.seek(from)
        engine.render(dst, n, offset)
    }
}

class SessionSource(
    private val sampleRate: Int,
    private val content: ContentSource?,
    private val contentStart: Int,
    bpm: Int,
    private val countInBeats: Int,
    private val clickDuring: Boolean,
    private val beatsPerBar: Int = 4
) : PlaybackSource {
    private val clicks = Clicks(sampleRate)
    private val spb = sampleRate * 60.0 / bpm
    private val firstClick = contentStart - countInBeats * spb

    private var tmp = FloatArray(0)

    override fun fill(startFrame: Long, out: ShortArray, n: Int) {
        if (tmp.size < n) tmp = FloatArray(n)
        java.util.Arrays.fill(tmp, 0, n, 0f)
        val c = content
        if (c != null) {
            val a = max(startFrame, contentStart.toLong())
            val b = minOf(startFrame + n, contentStart.toLong() + c.length)
            if (b > a) c.read((a - contentStart).toInt(), tmp, (a - startFrame).toInt(), (b - a).toInt())
        }
        for (i in 0 until n) {
            val f = startFrame + i
            var v = tmp[i]
            val rel = f - firstClick
            if (rel >= 0) {
                val beat = floor(rel / spb).toLong()
                val within = (rel - beat * spb).toInt()
                if (within < clicks.length && (beat < countInBeats || clickDuring)) {
                    val inBar = ((beat - countInBeats) % beatsPerBar + beatsPerBar) % beatsPerBar
                    v += if (inBar == 0L) clicks.accent[within] else clicks.normal[within]
                }
            }
            out[i] = Mixer.toShort(v)
        }
    }

    companion object {
        /** Quadro em que o conteúdo começa: depois da contagem, nunca antes de 1 s. */
        fun contentStartFor(sampleRate: Int, bpm: Int, countInBeats: Int): Int {
            val spb = sampleRate * 60.0 / bpm
            return max(sampleRate.toDouble(), sampleRate * 0.4 + countInBeats * spb).toInt()
        }
    }
}

/** Rajadas de ruído usadas na calibração: fáceis de localizar com precisão de 1 amostra. */
class BurstSource(sampleRate: Int, val contentStart: Int, val positions: IntArray) : PlaybackSource {
    val template: FloatArray = run {
        val n = sampleRate / 100 // 10 ms
        val rnd = Random(20261016)
        FloatArray(n) { i ->
            val w = 0.5f - 0.5f * kotlin.math.cos(2 * PI * i / (n - 1)).toFloat()
            (rnd.nextFloat() * 2f - 1f) * w * 0.8f
        }
    }

    override fun fill(startFrame: Long, out: ShortArray, n: Int) {
        for (i in 0 until n) {
            val f = startFrame + i - contentStart
            var v = 0f
            if (f >= 0) {
                for (p in positions) {
                    val k = f - p
                    if (k >= 0 && k < template.size) v += template[k.toInt()]
                }
            }
            out[i] = Mixer.toShort(v)
        }
    }
}

object Mixer {
    /** Converte para 16 bits com limitador suave (evita estalos se a soma passar do máximo). */
    fun toShort(v: Float): Short {
        val a = kotlin.math.abs(v)
        val y = if (a <= 0.8f) v else {
            val s = if (v < 0) -1f else 1f
            s * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
        }
        return (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }

    class Part(val samples: ShortArray, val volume: Float, val nudgeFrames: Int)

    /** Soma as vozes com volume e ajuste fino. Ajuste positivo = voz soa mais tarde. */
    fun mix(parts: List<Part>, length: Int): ShortArray {
        val acc = FloatArray(length)
        for (p in parts) {
            val g = p.volume / 32768f
            for (i in 0 until length) {
                val j = i - p.nudgeFrames
                if (j >= 0 && j < p.samples.size) acc[i] += p.samples[j] * g
            }
        }
        return ShortArray(length) { toShort(acc[it]) }
    }
}
