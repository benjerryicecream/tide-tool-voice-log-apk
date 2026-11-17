package com.chase.tidetoolvoicelog.media

import android.content.Context
import com.chase.tidetoolvoicelog.App
import com.chase.tidetoolvoicelog.data.TS_DONE
import com.chase.tidetoolvoicelog.data.TS_FAILED
import com.chase.tidetoolvoicelog.data.TS_PENDING
import com.chase.tidetoolvoicelog.data.TS_WORKING
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transcribes imported call files on the device, one at a time.
 *
 * Whatever happens, the audio stays: on failure the recording keeps its file and
 * the screen offers a manual summary instead.
 */
object TranscriptionRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<String>()
    private var running = false
    private val listeners = LinkedHashSet<(String) -> Unit>()

    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged(id: String) {
        scope.launch { withContext(Dispatchers.Main) { listeners.toList().forEach { it(id) } } }
    }

    fun enqueue(context: Context, recordingId: String) {
        synchronized(this) {
            if (!queue.contains(recordingId)) queue.addLast(recordingId)
        }
        pump(context.applicationContext)
    }

    private fun pump(context: Context) {
        synchronized(this) {
            if (running) return
            val next = queue.removeFirstOrNull() ?: return
            running = true
            scope.launch {
                try {
                    transcribeOne(context, next)
                } finally {
                    synchronized(this@TranscriptionRunner) { running = false }
                    pump(context)
                }
            }
        }
    }

    private suspend fun transcribeOne(context: Context, id: String) {
        val repo = App.repo
        val rec = repo.recording(id) ?: return
        if (rec.transcriptStatus == TS_DONE && rec.transcript.isNotBlank()) return
        val file = File(rec.audioPath)
        if (!file.exists()) {
            repo.updateRecording(rec.copy(transcriptStatus = TS_FAILED, transcriptError = "The audio file is missing on this device."))
            notifyChanged(id)
            return
        }

        repo.updateRecording(rec.copy(transcriptStatus = TS_WORKING, transcriptError = ""))
        notifyChanged(id)

        val transcriber = Transcriber(context)
        try {
            if (!transcriber.isModelReady()) {
                // First run downloads ~40 MB. Status text carries the progress.
                repo.updateRecording(rec.copy(transcriptError = "Downloading the speech model (once)..."))
                notifyChanged(id)
                transcriber.downloadModel { }
            }
            val text = transcriber.transcribe(file) { /* progress is coarse; status text is enough */ }
            val fresh = repo.recording(id) ?: return
            if (text.isBlank()) {
                repo.updateRecording(
                    fresh.copy(
                        transcriptStatus = TS_FAILED,
                        transcriptError = "No speech was recognised in this file. The audio is kept - add a summary by hand."
                    )
                )
            } else {
                repo.updateRecording(
                    fresh.copy(
                        transcript = text,
                        transcriptStatus = TS_DONE,
                        transcriptSource = "vosk",
                        transcriptError = ""
                    )
                )
            }
        } catch (e: Exception) {
            val fresh = repo.recording(id) ?: return
            repo.updateRecording(
                fresh.copy(
                    transcriptStatus = TS_FAILED,
                    transcriptError = e.message ?: "Transcription failed. The audio is kept."
                )
            )
        }
        notifyChanged(id)
    }

    /** Re-runs a failed or never-run transcription. */
    fun retry(context: Context, id: String) {
        val rec = App.repo.recording(id) ?: return
        if (rec.transcriptStatus == TS_FAILED || rec.transcriptStatus == TS_PENDING) {
            enqueue(context, id)
        }
    }
}
