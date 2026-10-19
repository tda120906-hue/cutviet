package com.cutviet.editor.media.dubbing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the audio track of a video or audio file into the raw input whisper.cpp wants: mono,
 * 16 kHz, little-endian float32. It streams to a file, so an hour of audio never sits in the heap.
 */
object AudioPcmDecoder {
    const val SAMPLE_RATE = 16_000

    /** Returns the number of samples written, or throws if the file has no decodable audio. */
    fun decode(context: Context, uri: Uri, startMs: Long, endMs: Long, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw StepUnavailableException("Video không có âm thanh")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val startUs = startMs * 1000
            val endUs = if (endMs > startMs) endMs * 1000 else Long.MAX_VALUE
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val resampler = BoxResampler(rate, SAMPLE_RATE)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var written = 0L
            val span = (endUs.coerceAtMost(startUs + 36_000_000_000L) - startUs).coerceAtLeast(1)

            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                val chunk = ByteBuffer.allocate(4 * 4096).order(ByteOrder.LITTLE_ENDIAN)
                fun emit(v: Float) {
                    chunk.putFloat(v)
                    written++
                    if (!chunk.hasRemaining()) { os.write(chunk.array(), 0, chunk.position()); chunk.clear() }
                }
                loop@ while (true) {
                    if (cancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
                    if (!inputDone) {
                        val i = dec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = dec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0 || ex.sampleTime > endUs) {
                                dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = dec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                            resampler.setInputRate(rate)
                        }
                        o >= 0 -> {
                            val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames = info.size / (bytesPerSample * channels)
                            // Drop the part of the first buffers that lies before the start.
                            val skip = if (info.presentationTimeUs < startUs)
                                ((startUs - info.presentationTimeUs) * rate / 1_000_000).toInt().coerceIn(0, frames) else 0
                            for (fIdx in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) {
                                    sum += if (bytesPerSample == 4) buf.float else buf.short / 32768f
                                }
                                if (fIdx >= skip) resampler.push(sum / channels, ::emit)
                            }
                            dec.releaseOutputBuffer(o, false)
                            if (info.presentationTimeUs > startUs) {
                                progress(((info.presentationTimeUs - startUs).toFloat() / span).coerceIn(0f, 1f))
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || info.presentationTimeUs > endUs) break@loop
                        }
                    }
                }
                if (chunk.position() > 0) os.write(chunk.array(), 0, chunk.position())
            }
            if (written == 0L) throw StepUnavailableException("Không giải mã được âm thanh của video")
            return written
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    /**
     * Streaming sample-rate converter: each output sample is the average of the input samples in
     * its time span, which also filters out what 16 kHz cannot hold. When upsampling (rare: 8 kHz
     * sources) a span can be empty and the last value is repeated.
     */
    class BoxResampler(inRate: Int, private val outRate: Int) {
        private var ratio = inRate.toDouble() / outRate
        private var inIndex = 0L
        private var outIndex = 0L
        private var sum = 0.0
        private var count = 0
        private var last = 0f

        fun setInputRate(rate: Int) {
            // Keep the time already produced and continue at the new rate.
            val newRatio = rate.toDouble() / outRate
            inIndex = (outIndex * newRatio).toLong()
            ratio = newRatio
        }

        fun push(v: Float, emit: (Float) -> Unit) {
            sum += v
            count++
            inIndex++
            while (inIndex >= (outIndex + 1) * ratio) {
                last = if (count > 0) (sum / count).toFloat() else last
                emit(last)
                outIndex++
                sum = 0.0
                count = 0
            }
        }
    }
}
