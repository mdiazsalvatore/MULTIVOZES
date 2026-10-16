package com.marcos.multivozes

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Toca e grava ao mesmo tempo.
 *
 * O alinhamento não depende de "sorte" no início das duas pontas: enquanto roda,
 * o motor lê os carimbos de tempo do hardware (AudioTrack.getTimestamp e
 * AudioRecord.getTimestamp) e calcula exatamente qual amostra gravada corresponde
 * a cada amostra tocada. A calibração só corrige o pequeno resto que sobra
 * (caminho analógico do fone/microfone e imprecisão do fabricante).
 */
class DuplexEngine(
    private val context: Context,
    val sampleRate: Int,
    private val source: PlaybackSource,
    val contentStartFrame: Int,
    private val record: Boolean,
    private val stopAtPlaybackFrame: Long,
    private val listener: Listener,
    private val maxRecordSeconds: Int = 600
) {
    interface Listener {
        fun onContentStart(t0Nanos: Long) {}
        fun onAutoStop() {}
        fun onError(message: String) {}
    }

    /** Resultado de uma sessão de gravação. */
    class Capture(
        val samples: ShortArray,
        val sampleRate: Int,
        val contentStartFrame: Int,
        val offsetTs: Double?,
        val offsetHead: Double?,
        val t0Nanos: Long?
    ) {
        val method: String get() = if (offsetTs != null) "ts" else "head"
        fun offset(m: String): Double? = if (m == "ts") offsetTs else offsetHead

        /**
         * Amostras gravadas a partir do instante em que o conteúdo começou a tocar.
         * [extraFrames] é o resíduo medido na calibração para o método [m].
         */
        fun aligned(m: String, extraFrames: Double): ShortArray {
            val d = offset(m) ?: 0.0
            val start = (contentStartFrame + d + extraFrames).roundToLong().toInt()
            if (start >= samples.size) return ShortArray(0)
            if (start >= 0) return samples.copyOfRange(start, samples.size)
            val out = ShortArray(samples.size - start)
            System.arraycopy(samples, 0, out, -start, samples.size)
            return out
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var rec: AudioRecord? = null
    @Volatile private var running = false
    private var writer: Thread? = null
    private var reader: Thread? = null

    private val chunkSize get() = sampleRate * 10
    private val chunks = ArrayList<ShortArray>()
    @Volatile var recordedFrames = 0L
        private set
    @Volatile var writtenFrames = 0L
        private set
    /** Pico de entrada recente, 0..1, para o medidor de nível. */
    @Volatile var level = 0f

    private val dTs = ArrayList<Double>()
    private val dHead = ArrayList<Double>()
    private val t0s = ArrayList<Long>()
    private var playStartNanos = 0L
    @Volatile private var contentPosted = false
    @Volatile private var autoStopPosted = false
    @Volatile var t0Estimate: Long? = null
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val enc = AudioFormat.ENCODING_PCM_16BIT
        val outMin = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, enc)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(enc)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // Buffer folgado (~120 ms): a latência é compensada, então priorizamos não engasgar.
            .setBufferSizeInBytes(max(outMin * 2, sampleRate * 2 * 120 / 1000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t

        if (record) {
            val inMin = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, enc)
            val src = if (AudioRoute.unprocessedSupported(context))
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val r = AudioRecord.Builder()
                .setAudioSource(src)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(enc)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(inMin * 4, sampleRate * 2 * 250 / 1000))
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release(); t.release(); track = null
                throw IllegalStateException("Não foi possível abrir o microfone.")
            }
            AudioRoute.builtInMic(context)?.let { r.setPreferredDevice(it) }
            rec = r
        }

        running = true
        rec?.startRecording()
        // Pré-carrega um pouco de som antes do play para não começar com o buffer vazio.
        val pre = ShortArray(1024)
        source.fill(0, pre, pre.size)
        t.write(pre, 0, pre.size)
        writtenFrames = pre.size.toLong()
        playStartNanos = System.nanoTime()
        t.play()

        writer = Thread({ writeLoop() }, "mv-play").also { it.start() }
        reader = Thread({ readLoop() }, "mv-rec").also { it.start() }
    }

    private fun writeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val t = track ?: return
        val buf = ShortArray(512)
        while (running) {
            source.fill(writtenFrames, buf, buf.size)
            val w = t.write(buf, 0, buf.size)
            if (w < 0) {
                postError("Falha ao tocar o áudio ($w).")
                break
            }
            writtenFrames += w
            if (stopAtPlaybackFrame > 0 && writtenFrames >= stopAtPlaybackFrame) postAutoStop()
        }
    }

    private fun readLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val r = rec
        val buf = ShortArray(480)
        var sinceSample = 0
        var levelDecay = 0f
        val maxFrames = sampleRate.toLong() * maxRecordSeconds
        while (running) {
            if (r == null) {
                try { Thread.sleep(15) } catch (_: InterruptedException) { break }
                sampleTimestamps()
                checkContentStart()
                continue
            }
            val n = r.read(buf, 0, buf.size)
            if (n < 0) {
                postError("Falha ao ler o microfone ($n).")
                break
            }
            var peak = 0
            for (i in 0 until n) {
                append(buf[i])
                val a = abs(buf[i].toInt())
                if (a > peak) peak = a
            }
            levelDecay = max(levelDecay * 0.85f, peak / 32768f)
            level = levelDecay
            sinceSample += n
            if (sinceSample >= sampleRate / 20) {
                sinceSample = 0
                sampleTimestamps()
                checkContentStart()
            }
            if (recordedFrames >= maxFrames) postAutoStop()
        }
    }

    private fun append(s: Short) {
        val idx = (recordedFrames % chunkSize).toInt()
        if (idx == 0) chunks.add(ShortArray(chunkSize))
        chunks[chunks.size - 1][idx] = s
        recordedFrames++
    }

    private val tsTrack = AudioTimestamp()
    private val tsRec = AudioTimestamp()

    private fun sampleTimestamps() {
        val t = track ?: return
        val okT = try { t.getTimestamp(tsTrack) } catch (_: Exception) { false }
        // Ignora o primeiro quarto de segundo: os carimbos ainda estão se estabilizando.
        val stable = okT && tsTrack.framePosition > sampleRate / 4
        synchronized(this) {
            if (stable) {
                t0s.add(tsTrack.nanoTime + ((contentStartFrame - tsTrack.framePosition) * 1e9 / sampleRate).toLong())
            }
            val r = rec ?: return
            if (stable) {
                val okR = try {
                    r.getTimestamp(tsRec, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                } catch (_: Exception) { false }
                if (okR && tsRec.framePosition > 0) {
                    // Quadro gravado no mesmo instante em que o quadro tocado P saiu no fone:
                    // r(P) = P + D
                    val d = (tsRec.framePosition - tsTrack.framePosition) +
                        (tsTrack.nanoTime - tsRec.nanoTime) * sampleRate / 1e9
                    dTs.add(d)
                }
            }
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head > sampleRate / 4) dHead.add((recordedFrames - head).toDouble())
        }
    }

    private fun checkContentStart() {
        if (contentPosted) return
        val est: Long? = synchronized(this) {
            if (t0s.size >= 3) median(t0s.map { it.toDouble() })!!.toLong() else null
        }
        val fallbackDue = System.nanoTime() - playStartNanos > 600_000_000L
        val t0 = est ?: if (fallbackDue) playStartNanos + (contentStartFrame * 1e9 / sampleRate).toLong() + 120_000_000L else null
        if (t0 != null) {
            contentPosted = true
            t0Estimate = t0
            main.post { listener.onContentStart(t0) }
        }
    }

    private fun postAutoStop() {
        if (autoStopPosted) return
        autoStopPosted = true
        main.post { listener.onAutoStop() }
    }

    private fun postError(m: String) {
        main.post { listener.onError(m) }
    }

    /** Para tudo e devolve o que foi gravado. */
    fun stop(): Capture {
        running = false
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        writer?.join(1500)
        reader?.join(1500)
        try { rec?.stop() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        rec?.release(); track?.release()
        rec = null; track = null

        val total = recordedFrames.toInt()
        val out = ShortArray(total)
        var pos = 0
        for (c in chunks) {
            val n = minOf(c.size, total - pos)
            if (n <= 0) break
            System.arraycopy(c, 0, out, pos, n)
            pos += n
        }
        chunks.clear()
        val (ts, head, t0) = synchronized(this) {
            Triple(robustMedian(dTs), robustMedian(dHead), median(t0s.map { it.toDouble() })?.toLong())
        }
        return Capture(out, sampleRate, contentStartFrame, ts, head, t0 ?: t0Estimate)
    }

    companion object {
        fun median(v: List<Double>): Double? {
            if (v.isEmpty()) return null
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        /** Mediana descartando as duas primeiras leituras (início ainda instável). */
        fun robustMedian(v: List<Double>): Double? =
            median(if (v.size > 6) v.drop(2) else v)
    }
}
