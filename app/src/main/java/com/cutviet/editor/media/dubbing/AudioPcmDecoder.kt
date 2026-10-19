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
import com.cutviet.editor.media.dsp.SincResampler

/**
 * Decodes the audio track of a video or audio file to raw little-endian float32 frames at a chosen
 * rate and channel count (mono 16 kHz for Whisper, stereo 44.1 kHz for voice separation), only
 * between [startMs] and [endMs]. It streams to a file, so an hour of audio never sits in the heap.
 */
object AudioPcmDecoder {
    const val SAMPLE_RATE = 16_000

    /** Returns the number of frames written, or throws if the file has no decodable audio. */
    fun decode(
        context: Context, uri: Uri, startMs: Long, endMs: Long, out: File,
        progress: (Float) -> Unit, cancelled: () -> Boolean,
        outRate: Int = SAMPLE_RATE, outChannels: Int = 1,
    ): Long {
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
            var resampler: SincResampler? = null
            var framesIn = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var written = 0L
            val span = (endUs.coerceAtMost(startUs + 36_000_000_000L) - startUs).coerceAtLeast(1)

            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                val chunk = ByteBuffer.allocate(4 * 4096 * outChannels).order(ByteOrder.LITTLE_ENDIAN)
                val emit: (FloatArray) -> Unit = { frame ->
                    for (c in 0 until outChannels) chunk.putFloat(frame[c])
                    written++
                    if (!chunk.hasRemaining()) { os.write(chunk.array(), 0, chunk.position()); chunk.clear() }
                }
                var mixed = FloatArray(4096 * outChannels)
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
                            // Decoders report the real format before the first output buffer.
                            if (resampler == null) resampler = SincResampler(rate, outRate, outChannels)
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
                            val rs = resampler ?: SincResampler(rate, outRate, outChannels).also { resampler = it }
                            if (mixed.size < frames * outChannels) mixed = FloatArray(frames * outChannels)
                            val raw = FloatArray(channels)
                            var n = 0
                            for (fIdx in 0 until frames) {
                                for (c in 0 until channels) raw[c] = if (bytesPerSample == 4) buf.float else buf.short / 32768f
                                if (fIdx < skip) continue
                                downmix(raw, channels, mixed, n * outChannels, outChannels)
                                n++
                            }
                            dec.releaseOutputBuffer(o, false)
                            framesIn += n
                            rs.push(mixed, n, emit)
                            if (info.presentationTimeUs > startUs) {
                                progress(((info.presentationTimeUs - startUs).toFloat() / span).coerceIn(0f, 1f))
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || info.presentationTimeUs > endUs) break@loop
                        }
                    }
                }
                resampler?.finish(framesIn, emit)
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
     * Mixes a frame of [inCh] channels down to mono or stereo. Surround (5.1 order L R C LFE Ls Rs)
     * folds the centre and surrounds into left and right; the LFE is left out.
     */
    fun downmix(raw: FloatArray, inCh: Int, out: FloatArray, at: Int, outCh: Int) {
        if (outCh == 1) {
            var sum = 0f
            for (c in 0 until inCh) sum += raw[c]
            out[at] = sum / inCh
            return
        }
        when {
            inCh == 1 -> { out[at] = raw[0]; out[at + 1] = raw[0] }
            inCh >= 6 -> {
                out[at] = (raw[0] + 0.707f * raw[2] + 0.707f * raw[4]) / 2.414f
                out[at + 1] = (raw[1] + 0.707f * raw[2] + 0.707f * raw[5]) / 2.414f
            }
            else -> { out[at] = raw[0]; out[at + 1] = raw[1] }
        }
    }
}
