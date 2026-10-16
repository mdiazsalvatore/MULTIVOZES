package com.marcos.multivozes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private enum class Mode { IDLE, RECORDING, SAVING, PLAYING, CALIBRATING, EXPORTING }

    private lateinit var project: Project
    private lateinit var calib: Calibration
    private lateinit var camera: CameraRig
    private lateinit var stage: StageView
    private lateinit var txtStatus: TextView
    private lateinit var txtLatency: TextView
    private lateinit var meter: ProgressBar
    private lateinit var btnRec: RecordButton
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnExport: MaterialButton
    private lateinit var btnNew: MaterialButton
    private lateinit var btnCalib: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var btnFx: MaterialButton
    private var fxPlayButton: MaterialButton? = null
    private lateinit var cameraParking: FrameLayout

    private val main = Handler(Looper.getMainLooper())
    private val tiles = ArrayList<TileView>()
    private val players = arrayOfNulls<ExoPlayer>(4)
    private val videoSizes = arrayOfNulls<VideoSize>(4)

    private var mode = Mode.IDLE
    private var selected = 0
    /** Voz já gravada que o usuário escolheu regravar (mostra a câmera nela). */
    private var armed = -1
    private var cameraReady = false

    // Sessão de gravação em andamento
    private var engine: DuplexEngine? = null
    private var recTarget = -1
    private var recT0: Long? = null
    private var recSpbNanos = 0L
    private var recCountIn = 0
    private var recUsingCamera = false
    private var audioDone = false
    private var videoDone = false
    private var videoOk = false
    private var savedLength = 0
    private var savedT0: Long? = null
    private var savedMethodNote = ""
    private var calibration: CalibrationRun? = null
    private var exporter: Exporter? = null

    private val tmpVideo get() = File(project.dir, "gravando.mp4")
    private val tmpAudio get() = File(project.dir, "gravando.wav")

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) setupCamera()
        else status("O app precisa da câmera e do microfone. Libere nas configurações do Android.")
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        txtStatus = findViewById(R.id.txtStatus)
        txtLatency = findViewById(R.id.txtLatency)
        meter = findViewById(R.id.meter)
        btnRec = findViewById(R.id.btnRec)
        btnPlay = findViewById(R.id.btnPlay)
        btnExport = findViewById(R.id.btnExport)
        btnNew = findViewById(R.id.btnNew)
        btnCalib = findViewById(R.id.btnCalib)
        btnSettings = findViewById(R.id.btnSettings)
        btnFx = findViewById(R.id.btnFx)
        cameraParking = findViewById(R.id.cameraParking)

        project = Project(File(filesDir, "projeto"))
        project.load()
        if (project.recordedCount() == 0) project.sampleRate = AudioRoute.nativeSampleRate(this)
        calib = Calibration(this)

        stage = StageView(this)
        findViewById<FrameLayout>(R.id.stageHolder).addView(
            stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val pv = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        cameraParking.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        camera = CameraRig(this, pv)

        btnRec.setOnClickListener { onRecPressed() }
        btnPlay.setOnClickListener { if (mode == Mode.PLAYING) stopPlaying() else startPlaying() }
        btnExport.setOnClickListener { if (mode == Mode.EXPORTING) cancelExport() else startExport() }
        btnNew.setOnClickListener { confirmNewProject() }
        btnCalib.setOnClickListener { showCalibrationIntro() }
        btnSettings.setOnClickListener { showSettings() }
        btnFx.setOnClickListener { showEffects() }

        selected = firstEmptyOr(0)
        buildStage()
        updateLatencyText()
        updateButtons()
        status(welcomeText())

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) setupCamera()
        else permLauncher.launch(needed)

        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        when (mode) {
            Mode.RECORDING -> stopRecording()
            Mode.PLAYING -> stopPlaying()
            Mode.CALIBRATING -> { calibration?.cancel(); calibration = null; mode = Mode.IDLE; updateButtons() }
            else -> {}
        }
        players.forEach { it?.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        releasePlayers()
        exporter?.cancel()
    }

    private fun setupCamera() {
        camera.bind(project.useFrontCamera) { msg -> status(msg) }
        cameraReady = true
        placeCamera()
    }

    // ---------------------------------------------------------------- palco

    private fun buildStage() {
        releasePlayers()
        stage.removeAllViews()
        tiles.clear()
        stage.vertical = project.vertical
        stage.rects = Grid.rects(project.vertical, project.voiceCount)
        for (i in 0 until project.voiceCount) {
            val t = TileView(this, i)
            t.setOnClickListener { onTileTap(i) }
            t.setOnLongClickListener { if (mode == Mode.IDLE) showTileMenu(i); true }
            t.texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCrop(i) }
            stage.addView(t)
            tiles.add(t)
        }
        if (selected >= project.voiceCount) selected = 0
        rebuildPlayers()
        refreshTiles()
    }

    private fun refreshTiles() {
        for (t in tiles) {
            val i = t.index
            val tr = project.tracks[i]
            t.selectedTile = i == selected && mode != Mode.RECORDING
            t.recordingNow = mode == Mode.RECORDING && i == recTarget
            val showsCamera = i == cameraTile()
            t.showVideo(tr?.videoFile != null && !showsCamera)
            val label = buildString {
                append("Voz ${i + 1}")
                if (tr != null) {
                    if (tr.volume != 1f) append(" · ${(tr.volume * 100).roundToInt()}%")
                    if (tr.nudgeMs != 0) append(" · ${if (tr.nudgeMs > 0) "+" else ""}${tr.nudgeMs} ms")
                }
            }
            t.setLabel(label)
            t.setHint(
                when {
                    showsCamera -> null
                    tr == null && i == selected -> "Toque no botão vermelho\npara gravar esta voz"
                    tr == null -> "vazia"
                    tr.videoFile == null -> "só áudio"
                    else -> null
                }
            )
        }
        placeCamera()
    }

    /** Em qual quadro a câmera aparece agora. */
    private fun cameraTile(): Int = when {
        mode == Mode.RECORDING || mode == Mode.SAVING -> recTarget
        mode != Mode.IDLE -> -1
        armed >= 0 -> armed
        selected < project.voiceCount && project.tracks[selected] == null -> selected
        else -> -1
    }

    private fun placeCamera() {
        val pv = camera.previewView
        val target = cameraTile()
        val parent = if (target in tiles.indices) tiles[target].cameraSlot else cameraParking
        if (pv.parent === parent) return
        (pv.parent as? ViewGroup)?.removeView(pv)
        parent.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun onTileTap(i: Int) {
        if (mode != Mode.IDLE) return
        if (i == selected && project.tracks[i] != null) { showTileMenu(i); return }
        selected = i
        if (armed != i) armed = -1
        refreshTiles()
        status(if (project.tracks[i] == null) "Voz ${i + 1} selecionada. Toque no botão vermelho para gravar." else "Voz ${i + 1}: toque de novo para ajustes ou regravar.")
    }

    private fun firstEmptyOr(def: Int): Int =
        (0 until project.voiceCount).firstOrNull { project.tracks[it] == null } ?: def

    // ---------------------------------------------------------------- vídeos das vozes

    private fun releasePlayers() {
        for (i in players.indices) { players[i]?.release(); players[i] = null; videoSizes[i] = null }
    }

    private fun rebuildPlayers() {
        releasePlayers()
        for (i in 0 until minOf(project.voiceCount, tiles.size)) {
            val tr = project.tracks[i] ?: continue
            val vf = tr.videoFile ?: continue
            val f = File(project.dir, vf)
            if (!f.exists()) continue
            val start = project.clipStartMs(tr)
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(f))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start)
                        .setEndPositionMs(start + maxOf(1000L, project.masterLengthMs))
                        .build()
                )
                .build()
            val p = ExoPlayer.Builder(this).build()
            p.volume = 0f
            p.setVideoTextureView(tiles[i].texture)
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    videoSizes[i] = videoSize
                    applyCrop(i)
                }
            })
            p.setMediaItem(item)
            p.playWhenReady = false
            p.prepare()
            players[i] = p
        }
    }

    /** Recorta o vídeo para preencher o quadro, como no vídeo final. */
    private fun applyCrop(i: Int) {
        val tv = tiles.getOrNull(i)?.texture ?: return
        val vs = videoSizes[i] ?: return
        val vw = tv.width.toFloat(); val vh = tv.height.toFloat()
        if (vw <= 0 || vh <= 0 || vs.width <= 0 || vs.height <= 0) return
        val w = vs.width * vs.pixelWidthHeightRatio
        val h = vs.height.toFloat()
        val s = maxOf(vw / w, vh / h)
        val m = Matrix()
        m.setScale(w * s / vw, h * s / vh, vw / 2, vh / 2)
        tv.setTransform(m)
        tv.invalidate()
    }

    /** Inicia os vídeos das outras vozes exatamente quando a música começa no fone. */
    private fun schedulePlayers(t0: Long, except: Int) {
        for (i in players.indices) {
            if (i == except) continue
            val p = players[i] ?: continue
            val delayMs = (t0 - System.nanoTime()) / 1_000_000
            if (delayMs > 0) {
                p.seekTo(0)
                main.postDelayed({ if (mode == Mode.PLAYING || mode == Mode.RECORDING) p.play() }, delayMs)
            } else {
                p.seekTo(-delayMs)
                p.play()
            }
        }
    }

    private fun resetPlayers() {
        players.forEach { it?.pause(); it?.seekTo(0) }
    }

    // ---------------------------------------------------------------- gravação

    private fun onRecPressed() {
        when (mode) {
            Mode.RECORDING -> { stopRecording(); return }
            Mode.IDLE -> {}
            else -> return
        }
        if (!cameraReady) { permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)); return }
        val target = selected
        if (project.tracks[target] != null && armed != target) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Regravar a Voz ${target + 1}?")
                .setMessage("A gravação atual desta voz será substituída.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Regravar") { _, _ -> armed = target; refreshTiles(); checkBeforeRecording(target) }
                .show()
            return
        }
        checkBeforeRecording(target)
    }

    private fun checkBeforeRecording(target: Int) {
        val route = AudioRoute.current(this)
        val needsIsolation = project.hasOthersVisible(target) || project.clickDuring
        when {
            route == Route.BLUETOOTH && needsIsolation -> warn(
                "Fone Bluetooth conectado",
                "Fones Bluetooth atrasam o som de forma variável (150 a 300 ms) e a gravação ficará fora de sincronia. Use fone com fio (P2 ou USB-C).",
                "Gravar assim mesmo"
            ) { checkCalibration(target, route) }
            route == Route.SPEAKER && needsIsolation -> warn(
                "Sem fone de ouvido",
                "O som das outras vozes (e do metrônomo) vai sair pelo alto-falante e entrar na gravação desta voz. Conecte um fone com fio.",
                "Gravar assim mesmo"
            ) { checkCalibration(target, route) }
            else -> checkCalibration(target, route)
        }
    }

    private fun checkCalibration(target: Int, route: Route) {
        if (!calib.isSet && project.hasOthersVisible(target)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Calibração recomendada")
                .setMessage("Calibrar leva 10 segundos e garante que esta voz fique exatamente no tempo das outras.")
                .setNegativeButton("Gravar sem calibrar") { _, _ -> prepareRecording(target) }
                .setPositiveButton("Calibrar agora") { _, _ -> showCalibrationIntro() }
                .show()
            return
        }
        if (calib.route != null && calib.route != route.name && route != Route.SPEAKER) {
            status("Atenção: a calibração foi feita com ${Route.valueOf(calib.route!!).label}. Se notar atraso, calibre de novo.")
        }
        prepareRecording(target)
    }

    private fun Project.hasOthersVisible(except: Int) =
        (0 until voiceCount).any { it != except && tracks[it] != null }

    private fun prepareRecording(target: Int) {
        mode = Mode.SAVING // bloqueia botões enquanto prepara
        recTarget = target
        updateButtons()
        status("Preparando…")
        val sr = project.sampleRate
        val others = (0 until project.voiceCount).filter { it != target && project.tracks[it] != null }
        val length = project.masterLength
        Thread {
            // As vozes anteriores tocam no fone já com os efeitos escolhidos.
            val mix = if (others.isEmpty()) null else try {
                project.mixEngine(others, length).renderAll()
            } catch (e: Exception) { null }
            main.post { beginRecording(target, mix) }
        }.start()
    }

    private fun beginRecording(target: Int, mix: ShortArray?) {
        val sr = project.sampleRate
        val beats = if (project.countInOn) project.countInBeats else 0
        val c = SessionSource.contentStartFor(sr, project.bpm, beats)
        val source = SessionSource(sr, mix?.let { ShortContent(it) }, c, project.bpm, beats, project.clickDuring)
        val stopAt = if (mix != null) (c + mix.size + sr * 3 / 2).toLong() else 0L

        mode = Mode.RECORDING
        recT0 = null
        recCountIn = beats
        recSpbNanos = (60_000_000_000L / project.bpm)
        audioDone = false; videoDone = false; videoOk = false
        resetPlayers()
        refreshTiles()
        updateButtons()
        status("Iniciando câmera…")

        val engineStarter = {
            startEngine(sr, source, c, stopAt)
        }
        recUsingCamera = camera.start(tmpVideo,
            onStarted = { if (mode == Mode.RECORDING && engine == null) engineStarter() },
            onFinalized = { ok, err -> onVideoFinalized(ok, err) })
        if (!recUsingCamera) {
            videoDone = true
            status("Câmera indisponível: gravando só o áudio.")
            engineStarter()
        }
    }

    private fun startEngine(sr: Int, source: PlaybackSource, c: Int, stopAt: Long) {
        val e = DuplexEngine(this, sr, source, c, record = true, stopAtPlaybackFrame = stopAt,
            listener = object : DuplexEngine.Listener {
                override fun onContentStart(t0Nanos: Long) {
                    recT0 = t0Nanos
                    schedulePlayers(t0Nanos, recTarget)
                }
                override fun onAutoStop() { if (mode == Mode.RECORDING) stopRecording() }
                override fun onError(message: String) {
                    status(message)
                    if (mode == Mode.RECORDING) stopRecording()
                }
            })
        try {
            e.start()
            engine = e
        } catch (ex: Exception) {
            status("Erro ao iniciar o áudio: ${ex.message}")
            camera.stop()
            mode = Mode.IDLE
            refreshTiles(); updateButtons()
        }
    }

    private fun stopRecording() {
        if (mode != Mode.RECORDING) return
        val e = engine
        engine = null
        mode = Mode.SAVING
        resetPlayers()
        updateButtons()
        status("Salvando a Voz ${recTarget + 1}…")
        if (recUsingCamera) camera.stop()
        if (e == null) { audioDone = true; savedLength = 0; maybeFinishSave(); return }

        val sr = project.sampleRate
        val target = recTarget
        val masterLen = if (project.hasOthersVisible(target)) project.masterLength else 0
        Thread {
            val cap = e.stop()
            val m = cap.method
            val calibrated = calib.hasMethod(m)
            var aligned = cap.aligned(m, if (calibrated) calib.extraFrames(m, sr) else 0.0)
            if (masterLen > 0) aligned = aligned.copyOf(masterLen)
            var len = aligned.size
            if (len >= sr / 2) Wav.write(tmpAudio, aligned, len, sr) else len = 0
            val note = when {
                m != "ts" -> " (aparelho sem carimbo de tempo de áudio: precisão depende da calibração)"
                !calibrated -> " (sem calibração)"
                else -> ""
            }
            main.post {
                savedLength = len
                savedT0 = cap.t0Nanos
                savedMethodNote = note
                audioDone = true
                maybeFinishSave()
            }
        }.start()
    }

    private fun onVideoFinalized(ok: Boolean, err: String?) {
        videoDone = true
        videoOk = ok
        if (!ok && err != null) status("Vídeo não foi salvo ($err)")
        if (mode == Mode.RECORDING) {
            // A câmera parou sozinha: encerra a gravação também.
            stopRecording()
        } else maybeFinishSave()
    }

    private fun maybeFinishSave() {
        if (mode != Mode.SAVING || !audioDone || !videoDone) return
        val target = recTarget
        mode = Mode.IDLE
        if (savedLength <= 0) {
            tmpVideo.delete(); tmpAudio.delete()
            status("Gravação muito curta, nada foi salvo.")
        } else {
            val old = project.tracks[target]
            val audio = project.audioFile(target)
            audio.delete(); tmpAudio.renameTo(audio)
            var videoName: String? = null
            if (videoOk && tmpVideo.exists()) {
                val vf = project.videoFile(target)
                vf.delete(); tmpVideo.renameTo(vf)
                videoName = vf.name
            }
            val t0 = savedT0
            val offsetMs = if (t0 != null) (camera.videoStartNanos - t0) / 1e6 else -1000.0
            project.tracks[target] = Track(
                audio.name, videoName, offsetMs, savedLength,
                nudgeMs = 0, volume = old?.volume ?: 1f
            ).also { t -> old?.let { t.fx.copyFrom(it.fx) } }
            project.save()
            armed = -1
            selected = firstEmptyOr(target)
            val secs = savedLength / project.sampleRate
            status("Voz ${target + 1} gravada (${secs / 60}:${"%02d".format(secs % 60)})$savedMethodNote." +
                if (project.recordedCount() < project.voiceCount) " Próxima: Voz ${selected + 1}." else " Todas as vozes gravadas!")
        }
        rebuildPlayers()
        refreshTiles()
        updateButtons()
    }

    // ---------------------------------------------------------------- ouvir tudo

    private fun startPlaying() {
        if (mode != Mode.IDLE || project.recordedCount() == 0) return
        mode = Mode.PLAYING
        updateButtons(); refreshTiles()
        status("Preparando…")
        val sr = project.sampleRate
        Thread {
            val mixer = project.mixEngine(project.visibleRecorded())
            main.post {
                if (mode != Mode.PLAYING) return@post
                val c = sr / 2
                val e = DuplexEngine(this, sr, SessionSource(sr, LiveMixContent(mixer), c, project.bpm, 0, false), c,
                    record = false, stopAtPlaybackFrame = (c + mixer.length + sr / 2).toLong(),
                    listener = object : DuplexEngine.Listener {
                        override fun onContentStart(t0Nanos: Long) {
                            recT0 = t0Nanos
                            schedulePlayers(t0Nanos, -1)
                        }
                        override fun onAutoStop() { stopPlaying() }
                        override fun onError(message: String) { status(message); stopPlaying() }
                    })
                try {
                    recT0 = null
                    e.start(); engine = e
                    status("Tocando todas as vozes")
                } catch (ex: Exception) {
                    status("Erro ao tocar: ${ex.message}"); mode = Mode.IDLE; updateButtons()
                }
            }
        }.start()
    }

    private fun stopPlaying() {
        if (mode != Mode.PLAYING) return
        val e = engine
        engine = null
        mode = Mode.IDLE
        if (e != null) Thread { e.stop() }.start()
        resetPlayers()
        updateButtons(); refreshTiles()
        status("Pronto.")
    }

    // ---------------------------------------------------------------- calibração

    private fun showCalibrationIntro() {
        if (mode != Mode.IDLE) return
        val route = AudioRoute.current(this)
        if (route == Route.BLUETOOTH) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Use fone com fio")
                .setMessage("Com fone Bluetooth o atraso muda o tempo todo e não dá para compensar. Conecte um fone com fio e tente de novo.")
                .setPositiveButton("OK", null).show()
            return
        }
        val msg = if (route == Route.WIRED)
            "1. Coloque o volume de mídia em uns 70%.\n2. Encoste um dos fones no microfone do celular (geralmente um furinho na borda de baixo).\n3. Fique em silêncio por 10 segundos.\n\nO app toca 8 cliques e mede o atraso exato. Faça isso uma vez com o fone que você vai usar."
        else
            "Nenhum fone conectado: a medição será feita pelo alto-falante. Para gravar com fone, conecte-o e calibre de novo com ele.\n\nColoque o volume em uns 70% e fique em silêncio por 10 segundos."
        MaterialAlertDialogBuilder(this)
            .setTitle("Calibrar atraso")
            .setMessage(msg)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Começar") { _, _ -> runCalibration(route) }
            .show()
    }

    private fun runCalibration(route: Route) {
        mode = Mode.CALIBRATING
        updateButtons(); refreshTiles()
        status("Calibrando… silêncio por favor")
        val run = CalibrationRun(this, project.sampleRate) { out ->
            calibration = null
            mode = Mode.IDLE
            updateButtons(); refreshTiles()
            if (out.ok) {
                calib.save(out.tsMs, out.headMs, route, out.spreadMs)
                updateLatencyText()
                status("Calibrado.")
                MaterialAlertDialogBuilder(this)
                    .setTitle("Calibração concluída")
                    .setMessage("Ajuste aplicado: ${fmtMs(out.tsMs ?: out.headMs ?: 0.0)}\nVariação entre os cliques: ${"%.1f".format(out.spreadMs)} ms (${out.found} de 8 cliques)\n\nAs próximas gravações já saem alinhadas.")
                    .setPositiveButton("OK", null).show()
            } else {
                status("Calibração falhou.")
                MaterialAlertDialogBuilder(this)
                    .setTitle("Não deu certo")
                    .setMessage(out.message)
                    .setNegativeButton("Fechar", null)
                    .setPositiveButton("Tentar de novo") { _, _ -> runCalibration(route) }
                    .show()
            }
        }
        calibration = run
        try { run.start() } catch (e: Exception) {
            calibration = null; mode = Mode.IDLE; updateButtons()
            status("Erro ao calibrar: ${e.message}")
        }
    }

    private fun fmtMs(v: Double) = "%+.1f ms".format(v)

    private fun updateLatencyText() {
        txtLatency.text = when {
            !calib.isSet -> "Não calibrado. Toque em Calibrar antes de gravar a segunda voz."
            else -> {
                val r = calib.route?.let { runCatching { Route.valueOf(it).label }.getOrNull() } ?: ""
                "Calibrado com $r · ajuste ${fmtMs(calib.tsMs ?: calib.headMs ?: 0.0)}"
            }
        }
    }

    // ---------------------------------------------------------------- exportação

    private fun startExport() {
        if (mode != Mode.IDLE) return
        if ((0 until project.voiceCount).none { project.tracks[it]?.videoFile != null }) {
            status("Grave pelo menos uma voz antes de exportar."); return
        }
        mode = Mode.EXPORTING
        releasePlayers()
        updateButtons(); refreshTiles()
        val ex = Exporter(this, project)
        exporter = ex
        ex.start(object : Exporter.Listener {
            override fun onProgress(percent: Int) {
                status("Gerando vídeo… $percent%")
                meter.progress = percent
            }
            override fun onDone(uri: Uri) {
                exporter = null
                finishExport()
                status("Vídeo salvo na galeria (Filmes › MultiVozes).")
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Vídeo pronto")
                    .setMessage("Salvo na galeria, pasta Filmes › MultiVozes.")
                    .setNegativeButton("Fechar", null)
                    .setNeutralButton("Compartilhar") { _, _ ->
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Compartilhar vídeo"))
                    }
                    .setPositiveButton("Assistir") { _, _ ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (_: Exception) { status("Abra o vídeo pela galeria.") }
                    }
                    .show()
            }
            override fun onError(message: String) {
                exporter = null
                finishExport()
                status("Não foi possível gerar o vídeo.")
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Erro na exportação")
                    .setMessage(message)
                    .setPositiveButton("OK", null).show()
            }
        })
    }

    private fun cancelExport() {
        exporter?.cancel(); exporter = null
        finishExport()
        status("Exportação cancelada.")
    }

    private fun finishExport() {
        mode = Mode.IDLE
        meter.progress = 0
        rebuildPlayers()
        updateButtons(); refreshTiles()
    }

    // ---------------------------------------------------------------- diálogos

    private fun warn(title: String, msg: String, go: String, onGo: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title).setMessage(msg)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(go) { _, _ -> onGo() }
            .show()
    }

    private fun confirmNewProject() {
        if (mode != Mode.IDLE) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Começar do zero?")
            .setMessage("Todas as vozes gravadas neste projeto serão apagadas. Vídeos já exportados continuam na galeria.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar tudo") { _, _ ->
                releasePlayers()
                project.clearAll()
                project.sampleRate = AudioRoute.nativeSampleRate(this)
                project.save()
                selected = 0; armed = -1
                buildStage(); updateButtons()
                status(welcomeText())
            }
            .show()
    }

    private fun showTileMenu(i: Int) {
        val tr = project.tracks[i]
        if (tr == null) { onTileTap(i); return }
        val box = column()
        val volLabel = label("")
        val vol = Slider(this).apply {
            valueFrom = 0f; valueTo = 150f; stepSize = 5f
            value = (tr.volume * 100).roundToInt().coerceIn(0, 150).toFloat().let { (it / 5).roundToInt() * 5f }
        }
        fun updVol() { volLabel.text = "Volume: ${vol.value.roundToInt()}%" }
        vol.addOnChangeListener { _, _, _ -> updVol() }; updVol()
        val nudgeLabel = label("")
        val nudge = Slider(this).apply {
            valueFrom = -80f; valueTo = 80f; stepSize = 1f
            value = tr.nudgeMs.coerceIn(-80, 80).toFloat()
        }
        fun updNudge() {
            val v = nudge.value.roundToInt()
            nudgeLabel.text = "Ajuste fino de tempo: ${if (v > 0) "+" else ""}$v ms  (+ atrasa, − adianta)"
        }
        nudge.addOnChangeListener { _, _, _ -> updNudge() }; updNudge()
        box.addView(volLabel); box.addView(vol)
        box.addView(nudgeLabel); box.addView(nudge)
        box.addView(label("Use o ajuste fino só se, ouvindo, esta voz parecer levemente fora do tempo. O vídeo acompanha o ajuste.").apply { alpha = 0.7f })

        MaterialAlertDialogBuilder(this)
            .setTitle("Voz ${i + 1}")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                tr.volume = vol.value / 100f
                tr.nudgeMs = nudge.value.roundToInt()
                project.save()
                rebuildPlayers(); refreshTiles()
            }
            .setNeutralButton("Apagar") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Apagar a Voz ${i + 1}?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Apagar") { _, _ ->
                        players[i]?.release(); players[i] = null
                        project.deleteTrack(i)
                        selected = i; armed = -1
                        rebuildPlayers(); refreshTiles(); updateButtons()
                        status("Voz ${i + 1} apagada.")
                    }.show()
            }
            .setNegativeButton("Regravar") { _, _ ->
                selected = i; armed = i
                refreshTiles()
                status("Voz ${i + 1} pronta para regravar. Toque no botão vermelho.")
            }
            .show()
    }

    private fun showSettings() {
        if (mode != Mode.IDLE) return
        val box = column()

        box.addView(label("Número de vozes"))
        val voices = toggle(listOf("2", "3", "4"), project.voiceCount - 2)
        box.addView(voices.first)

        box.addView(label("Formato do vídeo"))
        val fmt = toggle(listOf("Vertical (celular)", "Horizontal"), if (project.vertical) 0 else 1)
        box.addView(fmt.first)

        val bpmLabel = label("")
        val bpm = Slider(this).apply { valueFrom = 30f; valueTo = 240f; stepSize = 1f; value = project.bpm.coerceIn(30, 240).toFloat() }
        fun updBpm() { bpmLabel.text = "Andamento do metrônomo: ${bpm.value.roundToInt()} BPM" }
        bpm.addOnChangeListener { _, _, _ -> updBpm() }; updBpm()
        box.addView(bpmLabel); box.addView(bpm)

        val countSw = switch("Contagem antes de começar", project.countInOn)
        box.addView(countSw)
        val beatsLabel = label("")
        val beats = Slider(this).apply { valueFrom = 1f; valueTo = 8f; stepSize = 1f; value = project.countInBeats.coerceIn(1, 8).toFloat() }
        fun updBeats() { beatsLabel.text = "Tempos de contagem: ${beats.value.roundToInt()}" }
        beats.addOnChangeListener { _, _, _ -> updBeats() }; updBeats()
        box.addView(beatsLabel); box.addView(beats)

        val clickSw = switch("Metrônomo durante a gravação", project.clickDuring)
        box.addView(clickSw)

        box.addView(label("Câmera"))
        val cam = toggle(listOf("Frontal", "Traseira"), if (project.useFrontCamera) 0 else 1)
        box.addView(cam.first)

        val vdLabel = label("")
        val vd = Slider(this).apply { valueFrom = -200f; valueTo = 200f; stepSize = 10f; value = (project.videoDelayMs / 10 * 10).coerceIn(-200, 200).toFloat() }
        fun updVd() {
            val v = vd.value.roundToInt()
            vdLabel.text = "Sincronia imagem × som: ${if (v > 0) "+" else ""}$v ms  (+ atrasa a imagem)"
        }
        vd.addOnChangeListener { _, _, _ -> updVd() }; updVd()
        box.addView(vdLabel); box.addView(vd)

        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Ajustes")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("OK") { _, _ ->
                val newCount = voices.second() + 2
                val newVertical = fmt.second() == 0
                val layoutChanged = newCount != project.voiceCount || newVertical != project.vertical
                project.voiceCount = newCount
                project.vertical = newVertical
                project.bpm = bpm.value.roundToInt()
                project.countInOn = countSw.isChecked
                project.countInBeats = beats.value.roundToInt()
                project.clickDuring = clickSw.isChecked
                val newVd = vd.value.roundToInt()
                val vdChanged = newVd != project.videoDelayMs
                project.videoDelayMs = newVd
                project.useFrontCamera = cam.second() == 0
                project.save()
                camera.switchCamera(project.useFrontCamera)
                if (selected >= project.voiceCount) selected = firstEmptyOr(0)
                if (layoutChanged) buildStage() else if (vdChanged) { rebuildPlayers(); refreshTiles() }
                updateButtons()
            }
            .show()
    }

    // ---------------------------------------------------------------- efeitos

    private fun showEffects() {
        if (mode != Mode.IDLE) return
        val recorded = project.visibleRecorded()
        if (recorded.isEmpty()) { status("Grave uma voz para usar os efeitos."); return }
        val root = column()
        root.addView(label("Voz ou sala:").apply { setPadding(0, dp(4), 0, dp(4)) })
        val tabs = toggle(recorded.map { "${it + 1}" } + "Sala", 0)
        root.addView(tabs.first)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(panel)
        val play = MaterialButton(this)
        play.setOnClickListener { if (mode == Mode.PLAYING) stopPlaying() else startPlaying() }
        root.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        root.addView(label("Pode mexer enquanto ouve: o som muda na hora.").apply { alpha = 0.7f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) })
        fxPlayButton = play
        updateButtons()

        fun show(tab: Int) {
            panel.removeAllViews()
            if (tab < recorded.size) voicePanel(panel, recorded[tab], recorded) else roomPanel(panel)
        }
        (tabs.first as MaterialButtonToggleGroup).addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) show(tabs.second())
        }
        show(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Efeitos")
            .setView(android.widget.ScrollView(this).apply { addView(root) })
            .setPositiveButton("Fechar", null)
            .setOnDismissListener {
                fxPlayButton = null
                if (mode == Mode.PLAYING) stopPlaying()
                project.save()
                refreshTiles(); updateButtons()
            }
            .show()
    }

    private fun voicePanel(panel: LinearLayout, i: Int, all: List<Int>) {
        val t = project.tracks[i] ?: return
        val fx = t.fx
        panel.addView(label("Voz ${i + 1}").apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(0, dp(14), 0, 0) })
        addSlider(panel, 0f, 150f, 5f, t.volume * 100, { "Volume: ${it.roundToInt()}%" }) { t.volume = it / 100f }
        addSlider(panel, 0f, 100f, 1f, fx.reverb * 100, { "Reverb: ${it.roundToInt()}%" }) { fx.reverb = it / 100f }
        addSlider(panel, -12f, 12f, 0.5f, fx.bassDb, { "Graves: ${dbText(it)}" }) { fx.bassDb = it }
        addSlider(panel, -12f, 12f, 0.5f, fx.trebleDb, { "Agudos: ${dbText(it)}" }) { fx.trebleDb = it }
        addSlider(panel, 0f, 100f, 1f, fx.echo * 100, { "Eco: ${it.roundToInt()}%" }) { fx.echo = it / 100f }
        val echoTime = addSlider(panel, 60f, 900f, 10f, fx.echoMs.toFloat(), { "Tempo do eco: ${it.roundToInt()} ms" }) { fx.echoMs = it.roundToInt() }
        addSlider(panel, 0f, 80f, 1f, fx.echoFeedback * 100, { "Repetições do eco: ${it.roundToInt()}%" }) { fx.echoFeedback = it / 100f }

        val beatMs = (60000 / project.bpm).coerceIn(60, 900)
        val sync = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Eco no tempo da música (1 tempo = $beatMs ms)"
            isAllCaps = false
            setOnClickListener { echoTime.value = snap(beatMs.toFloat(), 60f, 900f, 10f) }
        }
        panel.addView(sync)
        if (all.size > 1) {
            panel.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Usar estes efeitos em todas as vozes"
                isAllCaps = false
                setOnClickListener {
                    for (j in all) if (j != i) project.tracks[j]?.fx?.copyFrom(fx)
                    status("Efeitos da Voz ${i + 1} copiados para as outras.")
                }
            })
        }
    }

    private fun roomPanel(panel: LinearLayout) {
        val room = project.room
        panel.addView(label("Sala do reverb").apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(0, dp(14), 0, 0) })
        panel.addView(label("Todas as vozes usam a mesma sala, como se tocassem no mesmo lugar. A quantidade de reverb de cada voz fica na aba dela.").apply { alpha = 0.7f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((key, name) in listOf("sala" to "Sala", "igreja" to "Igreja", "catedral" to "Catedral")) {
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = name
                isAllCaps = false
                setOnClickListener {
                    room.preset(key)
                    panel.removeAllViews()
                    roomPanel(panel)
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
        panel.addView(row)
        addSlider(panel, 0f, 100f, 1f, room.size * 100, { "Tamanho: ${it.roundToInt()}%" }) { room.size = it / 100f }
        addSlider(panel, 0f, 100f, 1f, room.brightness * 100, { "Brilho: ${it.roundToInt()}%" }) { room.brightness = it / 100f }
        addSlider(panel, 0f, 120f, 5f, room.preDelayMs.toFloat(), { "Pré-atraso: ${it.roundToInt()} ms" }) { room.preDelayMs = it.roundToInt() }
    }

    private fun dbText(v: Float) = if (kotlin.math.abs(v) < 0.05f) "0 dB" else "%+.1f dB".format(v)

    private fun snap(v: Float, from: Float, to: Float, step: Float): Float {
        val k = ((v.coerceIn(from, to) - from) / step).roundToInt()
        return (from + k * step).coerceIn(from, to)
    }

    private fun addSlider(
        parent: LinearLayout, from: Float, to: Float, step: Float, value: Float,
        text: (Float) -> String, onChange: (Float) -> Unit
    ): Slider {
        val v0 = snap(value, from, to, step)
        val l = label(text(v0))
        val s = Slider(this).apply {
            valueFrom = from; valueTo = to; stepSize = step; this.value = v0
        }
        s.addOnChangeListener { _, v, _ -> l.text = text(v); onChange(v) }
        parent.addView(l)
        parent.addView(s)
        return s
    }

    // pequenos construtores de interface
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), dp(4))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun switch(text: String, checked: Boolean) = MaterialSwitch(this).apply {
        this.text = text
        isChecked = checked
        setPadding(0, dp(8), 0, 0)
    }

    /** Grupo de botões de escolha única. Devolve a view e uma função que lê a opção marcada. */
    private fun toggle(options: List<String>, checkedIndex: Int): Pair<View, () -> Int> {
        val g = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val ids = options.map { text ->
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.id = View.generateViewId()
            b.text = text
            b.isAllCaps = false
            g.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            b.id
        }
        g.check(ids[checkedIndex.coerceIn(0, ids.size - 1)])
        return g to { ids.indexOf(g.checkedButtonId).coerceAtLeast(0) }
    }

    // ---------------------------------------------------------------- estado da tela

    private fun updateButtons() {
        val idle = mode == Mode.IDLE
        btnRec.recording = mode == Mode.RECORDING
        btnRec.isEnabled = idle || mode == Mode.RECORDING
        btnPlay.isEnabled = (idle && project.recordedCount() > 0) || mode == Mode.PLAYING
        btnPlay.text = if (mode == Mode.PLAYING) "Parar" else "Ouvir"
        btnExport.isEnabled = (idle && project.recordedCount() > 0) || mode == Mode.EXPORTING
        btnExport.text = if (mode == Mode.EXPORTING) "Cancelar" else "Exportar vídeo"
        btnNew.isEnabled = idle
        btnCalib.isEnabled = idle
        btnSettings.isEnabled = idle
        btnFx.isEnabled = idle && project.recordedCount() > 0
        fxPlayButton?.let {
            it.isEnabled = idle || mode == Mode.PLAYING
            it.text = if (mode == Mode.PLAYING) "■  Parar" else "▶  Ouvir com efeitos"
        }
    }

    private fun status(text: String) { txtStatus.text = text }

    private fun welcomeText() = when {
        project.recordedCount() == 0 -> "Escolha a voz e toque no botão vermelho. Use fone com fio."
        else -> "Voz ${selected + 1} selecionada."
    }

    private val ticker = object : Runnable {
        override fun run() {
            val e = engine
            when (mode) {
                Mode.RECORDING -> {
                    meter.progress = ((e?.level ?: 0f) * 100).roundToInt()
                    val t0 = recT0
                    val now = System.nanoTime()
                    if (e == null) {
                        // aguardando câmera
                    } else if (t0 == null || now < t0) {
                        if (t0 != null && recCountIn > 0) {
                            val left = ceil((t0 - now).toDouble() / recSpbNanos).toInt()
                            status("Voz ${recTarget + 1}: prepare-se… $left")
                        } else status("Voz ${recTarget + 1}: prepare-se…")
                    } else {
                        val s = ((now - t0) / 1_000_000_000L).toInt()
                        val total = if (project.hasOthersVisible(recTarget)) project.masterLength / project.sampleRate else -1
                        val clock = "${s / 60}:${"%02d".format(s % 60)}" + if (total > 0) " / ${total / 60}:${"%02d".format(total % 60)}" else ""
                        status("● Gravando Voz ${recTarget + 1}   $clock" + if (total <= 0) "\nToque no quadrado para parar" else "")
                    }
                }
                Mode.CALIBRATING -> meter.progress = ((calibration?.level ?: 0f) * 100).roundToInt()
                Mode.EXPORTING -> {}
                else -> meter.progress = 0
            }
            main.postDelayed(this, 100)
        }
    }
}
