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
    var volume: Float = 1f
) {
    fun toJson() = JSONObject().apply {
        put("audio", audioFile)
        put("video", videoFile ?: JSONObject.NULL)
        put("videoOffsetMs", videoOffsetMs)
        put("length", lengthFrames)
        put("nudgeMs", nudgeMs)
        put("volume", volume.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = Track(
            o.getString("audio"),
            if (o.isNull("video")) null else o.getString("video"),
            o.getDouble("videoOffsetMs"),
            o.getInt("length"),
            o.optInt("nudgeMs", 0),
            o.optDouble("volume", 1.0).toFloat()
        )
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
}
