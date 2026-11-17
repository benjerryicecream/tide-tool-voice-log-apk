package com.chase.tidetoolvoicelog.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decoding: MediaExtractor + MediaCodec do the container/codec work, then a
 * cheap box-average resample brings it to the 16 kHz mono that Vosk wants.
 */
object Pcm {

    fun decodeTo16kMono(file: File): ShortArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IOException("no audio track in that file")
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!

        // Opus/Vorbis inside MP4 make Samsung's decoder take the process down with a
        // native SIGSEGV, which no try/catch can stop. Refuse them instead of dying.
        val decodable = setOf(
            "audio/mp4a-latm", "audio/aac", "audio/amr", "audio/3gpp",
            "audio/raw", "audio/mpeg", "audio/flac", "audio/x-flac"
        )
        if (mime !in decodable) {
            extractor.release()
            throw IOException(
                "this file is $mime, which Android's decoder cannot handle safely. " +
                    "The audio is kept - add a summary by hand."
            )
        }

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        // Some decoders hand out an input buffer smaller than a single access unit unless
        // they are told the maximum up front; sizing that hint is what stops the memcpy
        // from running off the end of the codec buffer.
        if (!format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
        }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        android.util.Log.i(
            "Pcm",
            "track=" + mime + " rate=" + sampleRate + " ch=" + channels +
                " maxInput=" + (if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else -1)
        )

        // readSampleData() writes into a buffer we own and size ourselves; handing it the
        // codec's input buffer overflows whenever the sample is bigger than that buffer.
        val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        val sampleBuffer = java.nio.ByteBuffer.allocateDirect(if (maxInput > 0) maxInput else 256 * 1024)

        val parts = ArrayList<ShortArray>()
        var total = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val codecBuffer = codec.getInputBuffer(inIndex)
                        sampleBuffer.clear()
                        val size = extractor.readSampleData(sampleBuffer, 0)
                        if (size < 0 || codecBuffer == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codecBuffer.clear()
                            if (size > codecBuffer.capacity()) {
                                android.util.Log.w("Pcm", "sample " + size + " > codec buffer " + codecBuffer.capacity() + ", skipping")
                            } else {
                                sampleBuffer.position(0)
                                sampleBuffer.limit(size)
                                codecBuffer.put(sampleBuffer)
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            }
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                            parts.add(shorts)
                            total += shorts.size
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        if (total == 0) throw IOException("that file decoded to nothing")

        val all = ShortArray(total)
        var at = 0
        parts.forEach {
            System.arraycopy(it, 0, all, at, it.size)
            at += it.size
        }
        return toMono16k(all, max(1, channels), sampleRate)
    }

    private fun toMono16k(pcm: ShortArray, channels: Int, sampleRate: Int): ShortArray {
        val frames = pcm.size / channels
        if (frames == 0) return ShortArray(0)

        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var v = 0f
            for (c in 0 until channels) v += pcm[i * channels + c] / 32768f
            mono[i] = v / channels
        }
        if (sampleRate == 16000) {
            return ShortArray(frames) { (mono[it] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
        }

        val step = sampleRate.toDouble() / 16000.0
        val window = max(1, Math.floor(step).toInt())
        val outLen = (frames / step).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val start = (i * step).toInt().coerceIn(0, frames - 1)
            var sum = 0f
            var count = 0
            for (k in 0 until window) {
                val idx = start + k
                if (idx < frames) {
                    sum += mono[idx]
                    count++
                }
            }
            val v = if (count > 0) sum / count else 0f
            out[i] = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

/**
 * On-device transcription with Vosk (Kaldi) and the small English model.
 *
 * Chosen because it builds without the NDK, runs on the CPU in a couple of
 * hundred MB, and keeps every byte on the phone. The model is downloaded once
 * (~40 MB) the first time a file is transcribed; after that it is offline.
 */
class Transcriber(private val context: Context) {

    companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

        @Volatile
        private var model: Model? = null
    }

    fun modelDir(): File = File(context.filesDir, MODEL_NAME)

    fun isModelReady(): Boolean = File(modelDir(), "am/final.mdl").exists()

    private fun loadModel(): Model {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            val dir = modelDir()
            if (!File(dir, "am/final.mdl").exists()) throw IOException("speech model not downloaded yet")
            val m = Model(dir.absolutePath)
            model = m
            return m
        }
    }

    /** Downloads and unpacks the model. Progress is 0..100. */
    suspend fun downloadModel(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val zip = File(context.filesDir, "$MODEL_NAME.zip")
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) throw IOException("model download failed (HTTP ${conn.responseCode})")
            val length = conn.contentLength.toLong()
            var written = 0L
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (length > 0) {
                            val pct = (written * 100 / length).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        withContext(Dispatchers.Main) { onProgress(100) }
        unzip(zip, context.filesDir, "$MODEL_NAME/")
        zip.delete()
    }

    private fun unzip(zip: File, into: File, stripPrefix: String) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (!name.startsWith(stripPrefix)) {
                    zis.closeEntry()
                    continue
                }
                val rel = name.removePrefix(stripPrefix)
                if (rel.isEmpty()) {
                    zis.closeEntry()
                    continue
                }
                val out = File(into, "$MODEL_NAME/$rel")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
            }
        }
    }

    /** Blocking; call from a background dispatcher. Returns "" when nothing was recognised. */
    suspend fun transcribe(file: File, onProgress: (Int) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val pcm = Pcm.decodeTo16kMono(file)
        if (pcm.isEmpty()) throw IOException("no audio in that file")
        val m = loadModel()
        val recognizer = Recognizer(m, 16000.0f)
        val text = StringBuilder()
        try {
            val chunk = 16_000 // one second
            var offset = 0
            var lastPct = -1
            while (offset < pcm.size) {
                val n = minOf(chunk, pcm.size - offset)
                val part = pcm.copyOfRange(offset, offset + n)
                if (recognizer.acceptWaveForm(part, n)) {
                    val piece = JSONObject(recognizer.result).optString("text")
                    if (piece.isNotBlank()) {
                        if (text.isNotEmpty()) text.append(' ')
                        text.append(piece.trim())
                    }
                }
                offset += n
                val pct = (offset * 100 / pcm.size)
                if (pct != lastPct) {
                    lastPct = pct
                    withContext(Dispatchers.Main) { onProgress(pct) }
                }
            }
            val tail = JSONObject(recognizer.finalResult).optString("text")
            if (tail.isNotBlank()) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(tail.trim())
            }
        } finally {
            runCatching { recognizer.close() }
        }
        text.toString().trim()
    }
}
