package com.marcos.multivozes

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV mono 16 bits, usado para guardar cada voz já alinhada. */
object Wav {

    fun write(file: File, data: ShortArray, length: Int, sampleRate: Int) {
        val dataBytes = length * 2
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + dataBytes)
            h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16)
            h.putShort(1) // PCM
            h.putShort(1) // mono
            h.putInt(sampleRate)
            h.putInt(sampleRate * 2)
            h.putShort(2)
            h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataBytes)
            out.write(h.array())
            val chunk = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < length) {
                chunk.clear()
                val end = minOf(length, i + 4096)
                while (i < end) { chunk.putShort(data[i]); i++ }
                out.write(chunk.array(), 0, chunk.position())
            }
        }
    }

    /** Lê o WAV gravado por [write]. Devolve as amostras e a taxa. */
    fun read(file: File): Pair<ShortArray, Int> {
        RandomAccessFile(file, "r").use { raf ->
            val all = ByteArray(raf.length().toInt())
            raf.readFully(all)
            val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            var sr = 48000
            var pos = 12
            while (pos + 8 <= all.size) {
                val id = String(all, pos, 4)
                val size = bb.getInt(pos + 4)
                if (id == "fmt ") {
                    sr = bb.getInt(pos + 12)
                } else if (id == "data") {
                    val n = minOf(size, all.size - pos - 8) / 2
                    val out = ShortArray(n)
                    bb.position(pos + 8)
                    bb.asShortBuffer().get(out)
                    return out to sr
                }
                pos += 8 + size + (size and 1)
            }
            return ShortArray(0) to sr
        }
    }
}
