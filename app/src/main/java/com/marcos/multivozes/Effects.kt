package com.marcos.multivozes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Efeitos de uma voz. Campos voláteis: a tela muda e o som acompanha na hora. */
class VoiceFx {
    @Volatile var reverb = 0f        // 0..1, quanto da voz vai para a sala
    @Volatile var bassDb = 0f        // -12..+12
    @Volatile var trebleDb = 0f      // -12..+12
    @Volatile var echo = 0f          // 0..1
    @Volatile var echoMs = 320       // 60..900
    @Volatile var echoFeedback = 0.35f

    fun copyFrom(o: VoiceFx) {
        reverb = o.reverb; bassDb = o.bassDb; trebleDb = o.trebleDb
        echo = o.echo; echoMs = o.echoMs; echoFeedback = o.echoFeedback
    }
}

/** A "sala" do reverb, comum a todas as vozes, para soarem no mesmo ambiente. */
class RoomFx {
    @Volatile var size = 0.6f        // 0..1
    @Volatile var brightness = 0.5f  // 0..1 (o contrário do amortecimento)
    @Volatile var preDelayMs = 20    // 0..120

    fun preset(name: String) {
        when (name) {
            "sala" -> { size = 0.45f; brightness = 0.55f; preDelayMs = 10 }
            "igreja" -> { size = 0.75f; brightness = 0.45f; preDelayMs = 30 }
            "catedral" -> { size = 0.92f; brightness = 0.35f; preDelayMs = 60 }
        }
    }
}

/** Filtro biquad (fórmulas de shelving de R. Bristow-Johnson). */
private class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    var flat = true
        private set

    fun setShelf(low: Boolean, sr: Int, f0: Double, db: Double) {
        if (kotlin.math.abs(db) < 0.05) { flat = true; return }
        flat = false
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f0 / sr
        val cw = cos(w); val sw = sin(w)
        val alpha = sw / 2 * sqrt(2.0)
        val sa = 2 * sqrt(a) * alpha
        val n0: Double; val n1: Double; val n2: Double; val d0: Double; val d1: Double; val d2: Double
        if (low) {
            n0 = a * ((a + 1) - (a - 1) * cw + sa)
            n1 = 2 * a * ((a - 1) - (a + 1) * cw)
            n2 = a * ((a + 1) - (a - 1) * cw - sa)
            d0 = (a + 1) + (a - 1) * cw + sa
            d1 = -2 * ((a - 1) + (a + 1) * cw)
            d2 = (a + 1) + (a - 1) * cw - sa
        } else {
            n0 = a * ((a + 1) + (a - 1) * cw + sa)
            n1 = -2 * a * ((a - 1) + (a + 1) * cw)
            n2 = a * ((a + 1) + (a - 1) * cw - sa)
            d0 = (a + 1) - (a - 1) * cw + sa
            d1 = 2 * ((a - 1) - (a + 1) * cw)
            d2 = (a + 1) - (a - 1) * cw - sa
        }
        b0 = n0 / d0; b1 = n1 / d0; b2 = n2 / d0; a1 = d1 / d0; a2 = d2 / d0
    }

    fun process(x: Double): Double {
        if (flat) return x
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
}

/** Reverb do tipo Freeverb (8 filtros pente + 4 passa-tudo), mono. */
private class Reverb(sr: Int) {
    private val scale = sr / 44100.0
    private val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { DoubleArray((it * scale).toInt()) }
    private val combIdx = IntArray(8)
    private val combStore = DoubleArray(8)
    private val aps = intArrayOf(556, 441, 341, 225).map { DoubleArray((it * scale).toInt()) }
    private val apIdx = IntArray(4)
    private val pre = DoubleArray((sr * 0.13).toInt())
    private var preW = 0
    private val sampleRate = sr

    var feedback = 0.84
    var damp = 0.2
    var preDelay = 0

    fun configure(room: RoomFx) {
        feedback = 0.7 + room.size.coerceIn(0f, 1f) * 0.28
        damp = (1 - room.brightness.coerceIn(0f, 1f)) * 0.4 + 0.05
        preDelay = (room.preDelayMs.coerceIn(0, 120) * sampleRate / 1000).coerceAtMost(pre.size - 1)
    }

    fun process(input: Double): Double {
        pre[preW] = input
        var r = preW - preDelay
        if (r < 0) r += pre.size
        val x = pre[r] * 0.015
        preW = (preW + 1) % pre.size

        var out = 0.0
        for (c in 0 until 8) {
            val buf = combs[c]
            val i = combIdx[c]
            val o = buf[i]
            combStore[c] = o * (1 - damp) + combStore[c] * damp
            buf[i] = x + combStore[c] * feedback
            combIdx[c] = if (i + 1 >= buf.size) 0 else i + 1
            out += o
        }
        for (a in 0 until 4) {
            val buf = aps[a]
            val i = apIdx[a]
            val bo = buf[i]
            val y = -out + bo
            buf[i] = out + bo * 0.5
            apIdx[a] = if (i + 1 >= buf.size) 0 else i + 1
            out = y
        }
        return out * 2.0
    }

    fun reset() {
        combs.forEach { it.fill(0.0) }; aps.forEach { it.fill(0.0) }; pre.fill(0.0)
        combStore.fill(0.0); combIdx.fill(0); apIdx.fill(0); preW = 0
    }
}

/**
 * Mixa as vozes com volume, ajuste fino e efeitos. Processa em sequência,
 * bloco a bloco: serve tanto para tocar ao vivo quanto para gerar o arquivo final.
 */
class MixEngine(
    private val sampleRate: Int,
    private val parts: List<Part>,
    private val room: RoomFx,
    val length: Int
) {
    class Part(val samples: ShortArray, val volume: () -> Float, val nudgeFrames: () -> Int, val fx: VoiceFx)

    private inner class Chain(val part: Part) {
        val low = Biquad(); val high = Biquad()
        val echoBuf = DoubleArray(sampleRate + 16)
        var echoW = 0
        var lastBass = Float.NaN; var lastTreble = Float.NaN

        fun update() {
            val fx = part.fx
            if (fx.bassDb != lastBass) { lastBass = fx.bassDb; low.setShelf(true, sampleRate, 200.0, fx.bassDb.toDouble()) }
            if (fx.trebleDb != lastTreble) { lastTreble = fx.trebleDb; high.setShelf(false, sampleRate, 4000.0, fx.trebleDb.toDouble()) }
        }

        fun reset() { low.reset(); high.reset(); echoBuf.fill(0.0); echoW = 0 }
    }

    private val chains = parts.map { Chain(it) }
    private val reverb = Reverb(sampleRate)
    var position = 0
        private set

    fun seek(frame: Int) {
        position = frame
        chains.forEach { it.reset() }
        reverb.reset()
    }

    /** Gera [n] quadros a partir da posição atual, em -1..1 (pode passar de 1; o limitador cuida). */
    fun render(out: FloatArray, n: Int, offset: Int = 0) {
        chains.forEach { it.update() }
        reverb.configure(room)
        val vols = DoubleArray(chains.size) { chains[it].part.volume().toDouble() / 32768.0 }
        val nudges = IntArray(chains.size) { chains[it].part.nudgeFrames() }
        val sends = DoubleArray(chains.size) { chains[it].part.fx.reverb.toDouble() }
        val echoMix = DoubleArray(chains.size) { chains[it].part.fx.echo.toDouble() }
        val echoFb = DoubleArray(chains.size) { chains[it].part.fx.echoFeedback.toDouble().coerceIn(0.0, 0.85) }
        val echoLen = IntArray(chains.size) { (chains[it].part.fx.echoMs.coerceIn(20, 950) * sampleRate / 1000) }
        val anyReverb = sends.any { it > 0.0 }

        for (i in 0 until n) {
            val f = position + i
            var dry = 0.0
            var send = 0.0
            for (k in chains.indices) {
                val ch = chains[k]
                val s = ch.part.samples
                val j = f - nudges[k]
                var x = if (j >= 0 && j < s.size) s[j] * vols[k] else 0.0
                x = ch.low.process(x)
                x = ch.high.process(x)
                if (echoMix[k] > 0.0) {
                    val buf = ch.echoBuf
                    var r = ch.echoW - echoLen[k]
                    if (r < 0) r += buf.size
                    val d = buf[r]
                    buf[ch.echoW] = x + d * echoFb[k]
                    ch.echoW = if (ch.echoW + 1 >= buf.size) 0 else ch.echoW + 1
                    x += d * echoMix[k]
                }
                dry += x
                send += x * sends[k]
            }
            val wet = if (anyReverb) reverb.process(send) else reverb.process(0.0)
            out[offset + i] = (dry + wet).toFloat()
        }
        position += n
    }

    /** Gera a mixagem inteira de uma vez (para gravar a próxima voz e para exportar). */
    fun renderAll(): ShortArray {
        seek(0)
        val out = ShortArray(length)
        val buf = FloatArray(4096)
        var p = 0
        while (p < length) {
            val n = minOf(buf.size, length - p)
            render(buf, n)
            for (i in 0 until n) out[p + i] = Mixer.toShort(buf[i])
            p += n
        }
        return out
    }
}
