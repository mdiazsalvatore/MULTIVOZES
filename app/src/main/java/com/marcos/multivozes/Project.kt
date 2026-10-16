package com.marcos.multivozes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uma voz gravada. O áudio já está alinhado: amostra 0 = início da música. */
class Track(
    var audioFile: String,
    var videoFile: String?,
    /** Instante em que o vídeo começou, relativo ao início da música (normalmente negativo). */
    var videoOffsetMs: Double,
    var lengthFrames: Int,
    var nudgeMs: Int = 0,
    @Volatile var volume: Float = 1f
) {
    val fx = VoiceFx()

    fun toJson() = JSONObject().apply {
        put("audio", audioFile)
        put("video", videoFile ?: JSONObject.NULL)
        put("videoOffsetMs", videoOffsetMs)
        put("length", lengthFrames)
        put("nudgeMs", nudgeMs)
        put("volume", volume.toDouble())
        put("fx", JSONObject().apply {
            put("reverb", fx.reverb.toDouble())
            put("bass", fx.bassDb.toDouble())
            put("treble", fx.trebleDb.toDouble())
            put("echo", fx.echo.toDouble())
            put("echoMs", fx.echoMs)
            put("echoFb", fx.echoFeedback.toDouble())
        })
    }

    companion object {
        fun fromJson(o: JSONObject) = Track(
            o.getString("audio"),
            if (o.isNull("video")) null else o.getString("video"),
            o.getDouble("videoOffsetMs"),
            o.getInt("length"),
            o.optInt("nudgeMs", 0),
            o.optDouble("volume", 1.0).toFloat()
        ).also { t ->
            o.optJSONObject("fx")?.let { f ->
                t.fx.reverb = f.optDouble("reverb", 0.0).toFloat()
                t.fx.bassDb = f.optDouble("bass", 0.0).toFloat()
                t.fx.trebleDb = f.optDouble("treble", 0.0).toFloat()
                t.fx.echo = f.optDouble("echo", 0.0).toFloat()
                t.fx.echoMs = f.optInt("echoMs", 320)
                t.fx.echoFeedback = f.optDouble("echoFb", 0.35).toFloat()
            }
        }
    }
}

class Project(val dir: File) {
    var voiceCount = 4
    var vertical = true
    var bpm = 80
    var countInOn = true
    var countInBeats = 4
    var clickDuring = false
    /** Atraso do vídeo em relação ao som, ajuste global (ms). */
    var videoDelayMs = 0
    var useFrontCamera = true
    var sampleRate = 48000
    val tracks = arrayOfNulls<Track>(4)
    val room = RoomFx().apply { preset("igreja") }

    private val file get() = File(dir, "projeto.json")

    val masterLength: Int get() = tracks.filterNotNull().maxOfOrNull { it.lengthFrames } ?: 0
    fun recordedCount() = tracks.count { it != null }
    fun hasOthers(except: Int) = tracks.indices.any { it != except && tracks[it] != null }

    fun audioFile(i: Int) = File(dir, "voz${i + 1}.wav")
    fun videoFile(i: Int) = File(dir, "voz${i + 1}.mp4")

    fun load() {
        dir.mkdirs()
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            voiceCount = o.optInt("voiceCount", 4).coerceIn(2, 4)
            vertical = o.optBoolean("vertical", true)
            bpm = o.optInt("bpm", 80)
            countInOn = o.optBoolean("countInOn", true)
            countInBeats = o.optInt("countInBeats", 4)
            clickDuring = o.optBoolean("clickDuring", false)
            videoDelayMs = o.optInt("videoDelayMs", 0)
            useFrontCamera = o.optBoolean("front", true)
            sampleRate = o.optInt("sampleRate", 48000)
            o.optJSONObject("room")?.let { r ->
                room.size = r.optDouble("size", room.size.toDouble()).toFloat()
                room.brightness = r.optDouble("bright", room.brightness.toDouble()).toFloat()
                room.preDelayMs = r.optInt("pre", room.preDelayMs)
            }
            val arr = o.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until minOf(4, arr.length())) {
                tracks[i] = if (arr.isNull(i)) null else Track.fromJson(arr.getJSONObject(i))
                    .takeIf { File(dir, it.audioFile).exists() }
            }
        } catch (_: Exception) {
        }
    }

    fun save() {
        dir.mkdirs()
        val o = JSONObject().apply {
            put("voiceCount", voiceCount)
            put("vertical", vertical)
            put("bpm", bpm)
            put("countInOn", countInOn)
            put("countInBeats", countInBeats)
            put("clickDuring", clickDuring)
            put("videoDelayMs", videoDelayMs)
            put("front", useFrontCamera)
            put("sampleRate", sampleRate)
            put("room", JSONObject().apply {
                put("size", room.size.toDouble())
                put("bright", room.brightness.toDouble())
                put("pre", room.preDelayMs)
            })
            put("tracks", JSONArray().apply { tracks.forEach { put(it?.toJson() ?: JSONObject.NULL) } })
        }
        val tmp = File(dir, "projeto.json.tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(file)
    }

    fun deleteTrack(i: Int) {
        tracks[i] = null
        audioFile(i).delete()
        videoFile(i).delete()
        save()
    }

    fun clearAll() {
        for (i in 0 until 4) {
            tracks[i] = null
            audioFile(i).delete()
            videoFile(i).delete()
        }
        save()
    }

    /** Onde começar a cortar o vídeo para que o instante 0 coincida com o início da música. */
    fun clipStartMs(t: Track): Long {
        val v = -t.videoOffsetMs - t.nudgeMs - videoDelayMs
        return maxOf(0.0, v).toLong()
    }

    val masterLengthMs: Long get() = masterLength * 1000L / sampleRate

    /**
     * Mixador com as vozes [indices], seus volumes, ajustes e efeitos.
     * Lê os WAVs do disco: chamar fora da thread principal.
     */
    fun mixEngine(indices: List<Int>, length: Int = masterLength): MixEngine {
        val sr = sampleRate
        val parts = indices.mapNotNull { i ->
            val t = tracks[i] ?: return@mapNotNull null
            val samples = try { Wav.read(File(dir, t.audioFile)).first } catch (_: Exception) { return@mapNotNull null }
            MixEngine.Part(samples, { t.volume }, { t.nudgeMs * sr / 1000 }, t.fx)
        }
        return MixEngine(sr, parts, room, length)
    }

    fun visibleRecorded(): List<Int> = (0 until voiceCount).filter { tracks[it] != null }
}
