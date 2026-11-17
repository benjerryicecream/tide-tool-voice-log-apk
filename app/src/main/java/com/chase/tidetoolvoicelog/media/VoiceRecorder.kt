package com.chase.tidetoolvoicelog.media

import android.content.Context
import android.media.MediaRecorder
import com.chase.tidetoolvoicelog.App
import java.io.File
import java.util.UUID

/**
 * Job-site voice notes. AAC in an MPEG-4 container, written straight into
 * app-private storage - no permission, nothing shared, gone on uninstall.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): File {
        val dir = App.repo.audioDir()
        val file = File(dir, "note-" + UUID.randomUUID().toString() + ".m4a")

        @Suppress("DEPRECATION")
        val mr = MediaRecorder()
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioChannels(1)
        mr.setAudioSamplingRate(44100)
        mr.setAudioEncodingBitRate(64000)
        mr.setOutputFile(file.absolutePath)
        mr.prepare()
        mr.start()

        recorder = mr
        target = file
        startedAt = System.currentTimeMillis()
        return file
    }

    /** @return file + duration, or null when nothing was captured. */
    fun stop(): Pair<File, Long>? {
        val mr = recorder ?: return null
        val file = target
        recorder = null
        target = null

        val duration = System.currentTimeMillis() - startedAt
        try {
            mr.stop()
        } catch (e: RuntimeException) {
            // stop() throws when no frames were written at all
            runCatching { file?.delete() }
            return null
        } finally {
            runCatching { mr.release() }
        }
        if (file == null || !file.exists() || file.length() < 1024) {
            runCatching { file?.delete() }
            return null
        }
        return file to duration
    }

    fun cancel() {
        val mr = recorder
        recorder = null
        val file = target
        target = null
        if (mr != null) {
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        runCatching { file?.delete() }
    }
}
