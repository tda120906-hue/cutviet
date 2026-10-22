package com.cutviet.editor.media.dubbing

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import com.cutviet.editor.model.DubTime
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stretches a separated stem (16-bit PCM WAV with a 44-byte header, as [com.cutviet.editor.media.dsp.WavWriter]
 * writes) where the video plays slower, so background sounds stay in step with the picture. The
 * slow parts go through Sonic (the same time-stretch Media3 uses for speed changes: pitch kept);
 * everything else is copied as is.
 */
object StemStretcher {
    /** [originMs]: source time of the stem's first sample. Returns the new length in ms, or null. */
    fun stretch(input: File, output: File, originMs: Long, slowdowns: List<DubTime.Slowdown>, rate: Int, channels: Int): Long? = runCatching {
        val frameBytes = 2 * channels
        val tmp = File(output.path + ".tmp")
        var written = 0L
        RandomAccessFile(input, "r").use { raf ->
            val totalFrames = (raf.length() - 44) / frameBytes
            fun frameAt(ms: Long) = ((ms - originMs) * rate / 1000).coerceIn(0, totalFrames)
            BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { out ->
                out.write(ByteArray(44))
                val buf = ByteArray(frameBytes * 8192)
                fun copy(from: Long, to: Long, sink: (ByteArray, Int) -> Unit) {
                    raf.seek(44 + from * frameBytes)
                    var left = (to - from) * frameBytes
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                        if (n <= 0) break
                        sink(buf, n)
                        left -= n
                    }
                }
                var at = 0L
                for (s in slowdowns) {
                    val a = frameAt(s.startMs)
                    val b = frameAt(s.endMs)
                    if (b <= a || a < at) continue
                    copy(at, a) { bytes, n -> out.write(bytes, 0, n); written += n }
                    val sonic = SonicAudioProcessor()
                    sonic.setSpeed(s.factor)
                    sonic.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
                    sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
                    val drain = {
                        val o = sonic.getOutput()
                        if (o.hasRemaining()) {
                            val bytes = ByteArray(o.remaining())
                            o.get(bytes)
                            out.write(bytes)
                            written += bytes.size
                        }
                    }
                    copy(a, b) { bytes, n ->
                        sonic.queueInput(ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.nativeOrder()))
                        drain()
                    }
                    sonic.queueEndOfStream()
                    var guard = 0
                    while (!sonic.isEnded() && guard++ < 10_000) drain()
                    drain()
                    at = b
                }
                copy(at, totalFrames) { bytes, n -> out.write(bytes, 0, n); written += n }
            }
        }
        written -= written % frameBytes
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + written).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        h.putInt(rate).putInt(rate * frameBytes).putShort(frameBytes.toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(written.toInt())
        RandomAccessFile(tmp, "rw").use { it.seek(0); it.write(h.array()); it.setLength(44 + written) }
        output.delete()
        check(tmp.renameTo(output))
        written / frameBytes * 1000 / rate
    }.onFailure { DubLog.w(DubbingPipeline.TAG, "stretching ${input.name} failed", it) }.getOrNull()
}
