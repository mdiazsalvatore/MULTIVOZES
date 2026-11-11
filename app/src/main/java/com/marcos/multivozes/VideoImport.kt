package com.marcos.multivozes

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

/**
 * Traz um vídeo do celular para dentro do projeto:
 * copia o arquivo e extrai o som em mono, na taxa do projeto.
 */
class VideoImport(private val context: Context) {

    class Result(
        val audio: ShortArray,
        /** Onde o som começa na linha do tempo do vídeo (normalmente perto de 0). */
        val audioStartMs: Double
    )

    class ImportException(message: String) : Exception(message)

    /** Copia o vídeo escolhido para [dest]. */
    fun copy(uri: Uri, dest: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Não consegui abrir o vídeo escolhido.")
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        if (dest.length() == 0L) throw ImportException("O vídeo escolhido está vazio.")
    }

    /**
     * Decodifica o som do vídeo. [progress] recebe 0..100.
     * Limite de 10 minutos, o mesmo da gravação.
     */
    fun decodeAudio(file: File, targetRate: Int, progress: (Int) -> Unit): Result {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; break }
            }
            if (track < 0) throw ImportException("Este vídeo não tem som. Escolha um vídeo com áudio.")
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > 10L * 60 * 1_000_000) throw ImportException("O vídeo tem mais de 10 minutos.")
            ex.selectTrack(track)
            ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val firstUs = ex.sampleTime.coerceAtLeast(0)

            val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                throw ImportException("O celular não consegue ler o som deste vídeo ($mime).")
            }
            val out = ShortCollector()
            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastPct = -1
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val t = ex.sampleTime
                                codec.queueInputBuffer(inIdx, 0, n, t, 0)
                                ex.advance()
                                if (durationUs > 0) {
                                    val pct = (t * 100 / durationUs).toInt().coerceIn(0, 99)
                                    if (pct != lastPct) { lastPct = pct; progress(pct) }
                                }
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val b = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                                b.position(info.offset)
                                b.limit(info.offset + info.size)
                                downmix(b, channels, pcmFloat, out)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }
            if (out.size < srcRate / 2) throw ImportException("O som do vídeo é curto demais.")
            val audio = Resample.convert(out, srcRate, targetRate)
            progress(100)
            return Result(audio, (firstUs / 1000.0).coerceIn(0.0, 1000.0))
        } finally {
            ex.release()
        }
    }

    /** Junta os canais em um só (mono). */
    private fun downmix(b: java.nio.ByteBuffer, channels: Int, pcmFloat: Boolean, out: ShortCollector) {
        val ch = channels.coerceAtLeast(1)
        if (pcmFloat) {
            val fb = b.asFloatBuffer()
            val frames = fb.remaining() / ch
            for (f in 0 until frames) {
                var s = 0f
                for (c in 0 until ch) s += fb.get()
                out.add((s / ch * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        } else {
            val sb = b.asShortBuffer()
            val frames = sb.remaining() / ch
            for (f in 0 until frames) {
                var s = 0
                for (c in 0 until ch) s += sb.get()
                out.add((s / ch).toShort())
            }
        }
    }
}
