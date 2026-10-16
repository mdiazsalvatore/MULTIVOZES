package com.marcos.multivozes

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Monta o vídeo final: cada voz recortada no seu quadro, todas começando no mesmo
 * instante da música, com o áudio mixado (já alinhado) por baixo.
 */
@OptIn(UnstableApi::class)
class Exporter(private val context: Context, private val project: Project) {

    interface Listener {
        fun onProgress(percent: Int)
        fun onDone(uri: Uri)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var cancelled = false

    /** Chamar na thread principal. A mixagem roda em segundo plano. */
    fun start(listener: Listener) {
        val voices = (0 until project.voiceCount).mapNotNull { i -> project.tracks[i]?.let { i to it } }
            .filter { it.second.videoFile != null && File(project.dir, it.second.videoFile!!).exists() }
        if (voices.isEmpty()) { listener.onError("Nenhuma voz com vídeo para exportar."); return }
        listener.onProgress(0)

        Thread {
            try {
                val sr = project.sampleRate
                val mix = project.mixEngine(project.visibleRecorded()).renderAll()
                val mixFile = File(context.cacheDir, "mix.wav")
                Wav.write(mixFile, mix, mix.size, sr)
                main.post { if (!cancelled) build(voices, mixFile, listener) }
            } catch (e: Exception) {
                main.post { listener.onError("Erro ao mixar o áudio: ${e.message}") }
            }
        }.start()
    }

    private fun build(voices: List<Pair<Int, Track>>, mixFile: File, listener: Listener) {
        val (outW, outH) = Grid.outSize(project.vertical)
        val pix = Grid.pixelRects(project.vertical, project.voiceCount)
        val lengthMs = project.masterLengthMs

        val sequences = ArrayList<EditedMediaItemSequence>()
        val placement = ArrayList<IntArray>()
        for ((i, t) in voices) {
            val r = pix[i]
            val start = project.clipStartMs(t)
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(File(project.dir, t.videoFile!!)))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start)
                        .setEndPositionMs(start + lengthMs)
                        .build()
                )
                .build()
            val edited = EditedMediaItem.Builder(item)
                .setRemoveAudio(true)
                .setEffects(
                    Effects(
                        listOf(),
                        listOf(Presentation.createForWidthAndHeight(r[2], r[3], Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
                    )
                )
                .build()
            sequences.add(EditedMediaItemSequence(listOf(edited)))
            placement.add(r)
        }
        // Áudio mixado como uma sequência só de som.
        val audio = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(mixFile))).setRemoveVideo(true).build()
        sequences.add(EditedMediaItemSequence(listOf(audio)))

        val compositor = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = Size(outW, outH)

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                val r = placement.getOrNull(inputId) ?: return OverlaySettings.Builder().build()
                // Âncoras em coordenadas -1..1: canto superior esquerdo do quadro vai para
                // o canto superior esquerdo do retângulo da voz.
                val x = -1f + 2f * r[0] / outW
                val y = 1f - 2f * r[1] / outH
                return OverlaySettings.Builder()
                    .setOverlayFrameAnchor(-1f, 1f)
                    .setBackgroundFrameAnchor(x, y)
                    .build()
            }
        }

        val composition = Composition.Builder(sequences)
            .setVideoCompositorSettings(compositor)
            .build()

        val out = File(context.cacheDir, "multivozes.mp4")
        out.delete()
        val tr = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    Thread {
                        try {
                            val uri = saveToGallery(out)
                            main.post { listener.onDone(uri) }
                        } catch (e: Exception) {
                            main.post { listener.onError("Vídeo gerado, mas não consegui salvar na galeria: ${e.message}") }
                        }
                    }.start()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    listener.onError("Falha ao gerar o vídeo: ${exportException.errorCodeName} ${exportException.message ?: ""} ${exportException.cause?.message ?: ""}")
                }
            })
            .build()
        transformer = tr
        tr.start(composition, out.absolutePath)

        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) listener.onProgress(holder.progress)
                main.postDelayed(this, 400)
            }
        }
        main.postDelayed(poll, 400)
    }

    fun cancel() {
        cancelled = true
        transformer?.cancel()
        transformer = null
    }

    private fun saveToGallery(file: File): Uri {
        val name = "MultiVozes_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MultiVozes")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("galeria indisponível")
        resolver.openOutputStream(uri)!!.use { os -> file.inputStream().use { it.copyTo(os, 1 shl 16) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
