package com.marcos.multivozes

import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

/** Câmera: pré-visualização + gravação de vídeo sem som (o som vem do motor de áudio). */
class CameraRig(private val activity: AppCompatActivity, val previewView: PreviewView) {

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    var front = true
        private set

    /** Melhor estimativa do instante (System.nanoTime) do primeiro quadro gravado. */
    var videoStartNanos: Long = 0
        private set

    fun bind(useFront: Boolean, onError: (String) -> Unit) {
        front = useFront
        val f = ProcessCameraProvider.getInstance(activity)
        f.addListener({
            try {
                provider = f.get()
                rebind()
            } catch (e: Exception) {
                onError("Não foi possível abrir a câmera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun switchCamera(useFront: Boolean) {
        if (useFront == front) return
        front = useFront
        rebind()
    }

    private fun rebind() {
        val p = provider ?: return
        p.unbindAll()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        val vc = VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val chosen = if (p.hasCamera(selector)) selector else CameraSelector.DEFAULT_BACK_CAMERA
        p.bindToLifecycle(activity, chosen, preview, vc)
        videoCapture = vc
    }

    val isRecording get() = recording != null

    @SuppressLint("MissingPermission")
    fun start(file: File, onStarted: () -> Unit, onFinalized: (ok: Boolean, error: String?) -> Unit): Boolean {
        val vc = videoCapture ?: return false
        file.delete()
        var startEvent = 0L
        var bestStart = Long.MAX_VALUE
        recording = vc.output
            .prepareRecording(activity, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(activity)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Start -> {
                        startEvent = System.nanoTime()
                        videoStartNanos = startEvent
                        onStarted()
                    }
                    is VideoRecordEvent.Status -> {
                        // Cada evento informa quanto já foi gravado; "agora - duração" aponta
                        // para o primeiro quadro. O menor valor é o mais próximo do real.
                        val d = ev.recordingStats.recordedDurationNanos
                        if (d > 0) {
                            val est = System.nanoTime() - d
                            if (est < bestStart) bestStart = est
                            videoStartNanos = if (bestStart in 1 until startEvent + 2_000_000_000L) bestStart else startEvent
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        val ok = !ev.hasError() ||
                            ev.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                        onFinalized(ok && file.exists() && file.length() > 0, if (ev.hasError()) "erro ${ev.error}: ${ev.cause?.message}" else null)
                    }
                }
            }
        return true
    }

    fun stop() {
        recording?.stop()
    }
}
