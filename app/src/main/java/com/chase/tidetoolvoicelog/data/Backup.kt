package com.chase.tidetoolvoicelog.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Writes exports where the user can find them: Downloads/TideTool, no permission needed on 10+. */
object Saver {

    fun saveToDownloads(context: Context, fileName: String, mime: String, text: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TideTool")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("could not create the export file")
        resolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("could not write the export file")
        return uri
    }
}

/**
 * Backup file: jobs, clients, recordings, tasks - and optionally the audio as
 * base64. Including audio is the only way to move recordings to a new phone
 * without a cable, so it is offered, but it is off by default because a log with
 * an hour of audio is already 30+ MB before base64 makes it a third bigger.
 */
object Backup {

    private const val APP_TAG = "tide-tool-voice-log"
    private const val AUDIO_LIMIT_BYTES = 250L * 1024 * 1024

    data class ExportResult(val uri: Uri, val recordings: Int, val withAudio: Int, val bytes: Long)
    data class ImportResult(val jobs: Int, val recordings: Int, val clients: Int, val audio: Int)

    fun export(context: Context, repo: Repo, includeAudio: Boolean, fileName: String): ExportResult {
        val jobs = repo.jobs()
        val clients = repo.clients()
        val recordings = repo.allRecordings()
        val tasks = repo.allTasks()

        val audioBytes = recordings.mapNotNull { r ->
            if (r.audioPath.isEmpty()) null else File(r.audioPath).takeIf { it.exists() }?.length()
        }.sum()
        if (includeAudio && audioBytes > AUDIO_LIMIT_BYTES) {
            throw IOException("the audio is ${audioBytes / 1048576} MB - too big for one backup file. Export without audio, or export job by job.")
        }

        val root = JSONObject()
        root.put("app", APP_TAG)
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())

        val settings = JSONObject()
        settings.put("aiBaseUrl", context.getSharedPreferences("tidetool", Context.MODE_PRIVATE).getString("ai.base", ""))
        settings.put("aiModel", context.getSharedPreferences("tidetool", Context.MODE_PRIVATE).getString("ai.model", ""))
        // never the key
        root.put("settings", settings)

        root.put("jobs", JSONArray().apply {
            jobs.forEach { j ->
                put(JSONObject().apply {
                    put("id", j.id); put("name", j.name)
                    put("createdAt", j.createdAt); put("updatedAt", j.updatedAt)
                })
            }
        })

        root.put("clients", JSONArray().apply {
            clients.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("name", c.name); put("phone", c.phone)
                    put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
                })
            }
        })

        root.put("recordings", JSONArray().apply {
            recordings.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id); put("jobId", r.jobId); put("jobName", r.jobName)
                    put("kind", r.kind); put("createdAt", r.createdAt); put("durationMs", r.durationMs)
                    put("mime", r.mime); put("line", r.line)
                    put("clientId", r.clientId); put("clientName", r.clientName); put("clientPhone", r.clientPhone)
                    put("sourceFile", r.sourceFile)
                    put("transcript", r.transcript); put("transcriptStatus", r.transcriptStatus)
                    put("transcriptSource", r.transcriptSource); put("transcriptError", r.transcriptError)
                    put("notes", r.notes); put("summary", r.summary); put("importedBy", r.importedBy)
                })
            }
        })

        root.put("tasks", JSONArray().apply {
            tasks.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id); put("recordingId", t.recordingId); put("text", t.text)
                    put("done", t.done); put("createdAt", t.createdAt)
                })
            }
        })

        var withAudio = 0
        if (includeAudio) {
            val audio = JSONObject()
            recordings.forEach { r ->
                val f = if (r.audioPath.isEmpty()) null else File(r.audioPath)
                if (f != null && f.exists()) {
                    audio.put(r.id, android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
                    withAudio++
                }
            }
            root.put("audio", audio)
        }

        val text = root.toString()
        val uri = Saver.saveToDownloads(context, fileName, "application/json", text)
        return ExportResult(uri, recordings.size, withAudio, text.toByteArray().size.toLong())
    }

    /** Replaces the log with the file's contents. Audio is restored when the file carries it. */
    fun import(context: Context, repo: Repo, text: String): ImportResult {
        val root = JSONObject(text.replace("\uFEFF", ""))
        if (root.optString("app") != APP_TAG) throw IOException("that file is not a Tide & Tool backup")

        val jobsArr = root.optJSONArray("jobs") ?: JSONArray()
        val clientsArr = root.optJSONArray("clients") ?: JSONArray()
        val recsArr = root.optJSONArray("recordings") ?: JSONArray()
        val tasksArr = root.optJSONArray("tasks") ?: JSONArray()
        val audioObj = root.optJSONObject("audio")

        val jobs = ArrayList<Job>()
        for (i in 0 until jobsArr.length()) {
            val o = jobsArr.getJSONObject(i)
            jobs.add(
                Job(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("name"),
                    o.optLong("createdAt", System.currentTimeMillis()),
                    o.optLong("updatedAt", System.currentTimeMillis())
                )
            )
        }

        val clients = ArrayList<Client>()
        for (i in 0 until clientsArr.length()) {
            val o = clientsArr.getJSONObject(i)
            clients.add(
                Client(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("name"),
                    com.chase.tidetoolvoicelog.util.Phone.pretty(o.optString("phone")),
                    o.optLong("createdAt", System.currentTimeMillis()),
                    o.optLong("updatedAt", System.currentTimeMillis())
                )
            )
        }

        val dir = repo.audioDir()
        val recordings = ArrayList<Recording>()
        var restoredAudio = 0
        for (i in 0 until recsArr.length()) {
            val o = recsArr.getJSONObject(i)
            val id = o.optString("id", UUID.randomUUID().toString())
            var path = ""
            val b64 = audioObj?.optString(id).orEmpty()
            if (b64.isNotEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                    val f = File(dir, "import-$id.m4a")
                    f.writeBytes(bytes)
                    path = f.absolutePath
                    restoredAudio++
                } catch (e: Exception) {
                    path = ""
                }
            }
            recordings.add(
                Recording(
                    id = id,
                    jobId = o.optString("jobId"),
                    jobName = o.optString("jobName"),
                    kind = o.optString("kind", KIND_VOICE),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    durationMs = o.optLong("durationMs"),
                    audioPath = path,
                    mime = o.optString("mime", "audio/mp4"),
                    line = o.optString("line"),
                    clientId = o.optString("clientId"),
                    clientName = o.optString("clientName"),
                    clientPhone = o.optString("clientPhone"),
                    sourceFile = o.optString("sourceFile"),
                    transcript = o.optString("transcript"),
                    transcriptStatus = o.optString("transcriptStatus", TS_NONE),
                    transcriptSource = o.optString("transcriptSource"),
                    transcriptError = o.optString("transcriptError"),
                    notes = o.optString("notes"),
                    summary = o.optString("summary"),
                    importedBy = o.optString("importedBy", "manual")
                )
            )
        }

        val tasks = ArrayList<Task>()
        for (i in 0 until tasksArr.length()) {
            val o = tasksArr.getJSONObject(i)
            tasks.add(
                Task(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("recordingId"),
                    o.optString("text"),
                    o.optBoolean("done"),
                    o.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }

        repo.replaceAll(jobs, clients, recordings, tasks)
        return ImportResult(jobs.size, recordings.size, clients.size, restoredAudio)
    }
}
