package com.marcos.multivozes

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToLong

/** Resíduo de latência medido, guardado no aparelho. */
class Calibration(context: Context) {
    private val prefs = context.getSharedPreferences("calibracao", Context.MODE_PRIVATE)

    val tsMs: Double? get() = prefs.getString("ts_ms", null)?.toDoubleOrNull()
    val headMs: Double? get() = prefs.getString("head_ms", null)?.toDoubleOrNull()
    val route: String? get() = prefs.getString("route", null)
    val spreadMs: Double? get() = prefs.getString("spread_ms", null)?.toDoubleOrNull()
    val isSet: Boolean get() = tsMs != null || headMs != null

    fun extraFrames(method: String, sampleRate: Int): Double {
        val ms = (if (method == "ts") tsMs else headMs) ?: 0.0
        return ms * sampleRate / 1000.0
    }

    fun hasMethod(method: String) = (if (method == "ts") tsMs else headMs) != null

    fun save(ts: Double?, head: Double?, route: Route, spread: Double) {
        prefs.edit()
            .putString("ts_ms", ts?.toString())
            .putString("head_ms", head?.toString())
            .putString("route", route.name)
            .putString("spread_ms", spread.toString())
            .apply()
    }
}

/**
 * Calibração: toca 8 rajadas curtas e as encontra na gravação.
 * O usuário encosta o fone (com fio) no microfone do celular.
 */
class CalibrationRun(
    private val context: Context,
    private val sampleRate: Int,
    private val onDone: (Outcome) -> Unit
) {
    class Outcome(
        val ok: Boolean,
        val tsMs: Double?,
        val headMs: Double?,
        val spreadMs: Double,
        val found: Int,
        val message: String
    )

    private val contentStart = sampleRate
    private val positions = IntArray(8) { (it * 0.8 * sampleRate).toInt() }
    private val source = BurstSource(sampleRate, contentStart, positions)
    private var engine: DuplexEngine? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var finished = false

    fun start() {
        val e = DuplexEngine(
            context, sampleRate, source, contentStart, record = true,
            stopAtPlaybackFrame = (contentStart + positions.last() + sampleRate).toLong(),
            listener = object : DuplexEngine.Listener {
                override fun onAutoStop() = finish()
                override fun onError(message: String) {
                    cancel()
                    onDone(Outcome(false, null, null, 0.0, 0, message))
                }
            }
        )
        engine = e
        e.start()
    }

    val level: Float get() = engine?.level ?: 0f

    fun cancel() {
        finished = true
        val e = engine ?: return
        engine = null
        Thread { e.stop() }.start()
    }

    private fun finish() {
        if (finished) return
        finished = true
        val e = engine ?: return
        engine = null
        Thread {
            val cap = e.stop()
            val search = (sampleRate * 0.35).toInt()
            fun run(m: String): LagDetector.Result? {
                val off = cap.offset(m) ?: return null
                val idx = (contentStart + off).roundToLong().toInt()
                return LagDetector.detect(cap.samples, idx, positions, source.template, search)
            }
            val rTs = run("ts")
            val rHead = run("head")
            val best = if ((rTs?.lags?.size ?: 0) >= (rHead?.lags?.size ?: 0)) rTs else rHead
            val found = best?.lags?.size ?: 0
            val spreadMs = (best?.spreadFrames ?: 0) * 1000.0 / sampleRate
            val tsMs = rTs?.takeIf { it.lags.size >= 5 }?.medianLag?.let { it * 1000.0 / sampleRate }
            val headMs = rHead?.takeIf { it.lags.size >= 5 }?.medianLag?.let { it * 1000.0 / sampleRate }
            val outcome = when {
                tsMs == null && headMs == null -> Outcome(
                    false, null, null, spreadMs, found,
                    "Não ouvi os cliques ($found de 8). Aumente o volume de mídia e encoste bem o fone no microfone do celular (geralmente na borda de baixo)."
                )
                spreadMs > 3.0 -> Outcome(
                    false, tsMs, headMs, spreadMs, found,
                    "As medições variaram demais (%.1f ms). Repita em um lugar silencioso, sem mexer no celular.".format(spreadMs)
                )
                else -> Outcome(true, tsMs, headMs, spreadMs, found, "")
            }
            main.post { onDone(outcome) }
        }.start()
    }
}
