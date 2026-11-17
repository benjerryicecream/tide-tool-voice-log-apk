package com.chase.tidetoolvoicelog.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.chase.tidetoolvoicelog.util.Phone
import java.io.File
import java.util.UUID

private class Db(context: Context) : SQLiteOpenHelper(context, "tidetool.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE jobs (
                 id TEXT PRIMARY KEY,
                 name TEXT NOT NULL,
                 created_at INTEGER NOT NULL,
                 updated_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE clients (
                 id TEXT PRIMARY KEY,
                 name TEXT NOT NULL,
                 phone TEXT NOT NULL DEFAULT '',
                 created_at INTEGER NOT NULL,
                 updated_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE recordings (
                 id TEXT PRIMARY KEY,
                 job_id TEXT NOT NULL,
                 job_name TEXT NOT NULL,
                 kind TEXT NOT NULL,
                 created_at INTEGER NOT NULL,
                 duration_ms INTEGER NOT NULL DEFAULT 0,
                 audio_path TEXT NOT NULL DEFAULT '',
                 mime TEXT NOT NULL DEFAULT '',
                 line TEXT NOT NULL DEFAULT '',
                 client_id TEXT NOT NULL DEFAULT '',
                 client_name TEXT NOT NULL DEFAULT '',
                 client_phone TEXT NOT NULL DEFAULT '',
                 source_file TEXT NOT NULL DEFAULT '',
                 transcript TEXT NOT NULL DEFAULT '',
                 transcript_status TEXT NOT NULL DEFAULT 'none',
                 transcript_source TEXT NOT NULL DEFAULT '',
                 transcript_error TEXT NOT NULL DEFAULT '',
                 notes TEXT NOT NULL DEFAULT '',
                 summary TEXT NOT NULL DEFAULT '',
                 imported_by TEXT NOT NULL DEFAULT 'manual')"""
        )
        db.execSQL(
            """CREATE TABLE tasks (
                 id TEXT PRIMARY KEY,
                 recording_id TEXT NOT NULL,
                 text TEXT NOT NULL,
                 done INTEGER NOT NULL DEFAULT 0,
                 created_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_rec_job ON recordings(job_id, created_at)")
        db.execSQL("CREATE INDEX idx_task_rec ON tasks(recording_id, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 - nothing to migrate yet
    }
}

class Repo(context: Context) {

    private val app = context.applicationContext
    private val helper = Db(app)

    /* ------------------------------------------------------------------ jobs */

    fun jobs(): List<Job> {
        val out = ArrayList<Job>()
        helper.readableDatabase.rawQuery(
            "SELECT id, name, created_at, updated_at FROM jobs ORDER BY updated_at DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(Job(c.str(0), c.str(1), c.long(2), c.long(3)))
        }
        return out
    }

    fun job(id: String): Job? {
        helper.readableDatabase.rawQuery(
            "SELECT id, name, created_at, updated_at FROM jobs WHERE id = ?", arrayOf(id)
        ).use { c -> return if (c.moveToFirst()) Job(c.str(0), c.str(1), c.long(2), c.long(3)) else null }
    }

    /** Reuses a job with the same name (case/space-insensitive), like the web app. */
    fun ensureJob(name: String): Job {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        val now = System.currentTimeMillis()
        helper.readableDatabase.rawQuery("SELECT id, name, created_at, updated_at FROM jobs", null).use { c ->
            while (c.moveToNext()) {
                val existing = Job(c.str(0), c.str(1), c.long(2), c.long(3))
                if (existing.name.equals(clean, ignoreCase = true)) return existing
            }
        }
        val job = Job(UUID.randomUUID().toString(), clean, now, now)
        val v = ContentValues().apply {
            put("id", job.id)
            put("name", job.name)
            put("created_at", job.createdAt)
            put("updated_at", job.updatedAt)
        }
        helper.writableDatabase.insert("jobs", null, v)
        return job
    }

    private fun touchJob(jobId: String) {
        val v = ContentValues().apply { put("updated_at", System.currentTimeMillis()) }
        helper.writableDatabase.update("jobs", v, "id = ?", arrayOf(jobId))
    }

    /* --------------------------------------------------------------- clients */

    fun clients(): List<Client> = queryClients("SELECT * FROM clients ORDER BY name COLLATE NOCASE ASC")

    private fun queryClients(sql: String, args: Array<String>? = null): List<Client> {
        val out = ArrayList<Client>()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(Client(c.strByName("id"), c.strByName("name"), c.strByName("phone"),
                    c.longByName("created_at"), c.longByName("updated_at")))
            }
        }
        return out
    }

    fun client(id: String): Client? = queryClients("SELECT * FROM clients WHERE id = ?", arrayOf(id)).firstOrNull()

    fun findClientByPhone(raw: String?): Client? {
        val d = Phone.digits(raw)
        if (d.isEmpty()) return null
        return clients().firstOrNull { Phone.digits(it.phone) == d }
    }

    fun findClientByName(name: String?): Client? {
        val n = (name ?: "").trim()
        if (n.isEmpty()) return null
        return clients().firstOrNull { it.name.equals(n, ignoreCase = true) }
    }

    /** Adds, or updates the entry that already owns this number / name. */
    fun addClient(name: String, phone: String): Client? {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        val pretty = Phone.pretty(phone)
        if (clean.isEmpty() && pretty.isEmpty()) return null
        val existing = findClientByPhone(phone) ?: if (clean.isNotEmpty()) findClientByName(clean) else null
        val now = System.currentTimeMillis()
        if (existing != null) {
            val v = ContentValues().apply {
                if (clean.isNotEmpty()) put("name", clean)
                if (pretty.isNotEmpty()) put("phone", pretty)
                put("updated_at", now)
            }
            helper.writableDatabase.update("clients", v, "id = ?", arrayOf(existing.id))
            return client(existing.id)
        }
        val c = Client(UUID.randomUUID().toString(), clean.ifEmpty { pretty }, pretty, now, now)
        val v = ContentValues().apply {
            put("id", c.id); put("name", c.name); put("phone", c.phone)
            put("created_at", c.createdAt); put("updated_at", c.updatedAt)
        }
        helper.writableDatabase.insert("clients", null, v)
        return c
    }

    fun updateClient(id: String, name: String, phone: String) {
        val v = ContentValues().apply {
            put("name", name.trim().replace(Regex("\\s+"), " "))
            put("phone", Phone.pretty(phone))
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("clients", v, "id = ?", arrayOf(id))
    }

    fun deleteClient(id: String) {
        helper.writableDatabase.delete("clients", "id = ?", arrayOf(id))
    }

    /* ------------------------------------------------------------ recordings */

    fun insertRecording(r: Recording) {
        helper.writableDatabase.insert("recordings", null, recValues(r))
        touchJob(r.jobId)
    }

    fun updateRecording(r: Recording) {
        helper.writableDatabase.update("recordings", recValues(r), "id = ?", arrayOf(r.id))
    }

    private fun recValues(r: Recording) = ContentValues().apply {
        put("id", r.id)
        put("job_id", r.jobId)
        put("job_name", r.jobName)
        put("kind", r.kind)
        put("created_at", r.createdAt)
        put("duration_ms", r.durationMs)
        put("audio_path", r.audioPath)
        put("mime", r.mime)
        put("line", r.line)
        put("client_id", r.clientId)
        put("client_name", r.clientName)
        put("client_phone", r.clientPhone)
        put("source_file", r.sourceFile)
        put("transcript", r.transcript)
        put("transcript_status", r.transcriptStatus)
        put("transcript_source", r.transcriptSource)
        put("transcript_error", r.transcriptError)
        put("notes", r.notes)
        put("summary", r.summary)
        put("imported_by", r.importedBy)
    }

    private fun cursorToRec(c: Cursor): Recording = Recording(
        id = c.strByName("id"),
        jobId = c.strByName("job_id"),
        jobName = c.strByName("job_name"),
        kind = c.strByName("kind"),
        createdAt = c.longByName("created_at"),
        durationMs = c.longByName("duration_ms"),
        audioPath = c.strByName("audio_path"),
        mime = c.strByName("mime"),
        line = c.strByName("line"),
        clientId = c.strByName("client_id"),
        clientName = c.strByName("client_name"),
        clientPhone = c.strByName("client_phone"),
        sourceFile = c.strByName("source_file"),
        transcript = c.strByName("transcript"),
        transcriptStatus = c.strByName("transcript_status"),
        transcriptSource = c.strByName("transcript_source"),
        transcriptError = c.strByName("transcript_error"),
        notes = c.strByName("notes"),
        summary = c.strByName("summary"),
        importedBy = c.strByName("imported_by")
    )

    fun recording(id: String): Recording? {
        helper.readableDatabase.rawQuery("SELECT * FROM recordings WHERE id = ?", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) cursorToRec(c) else null
        }
    }

    fun allRecordings(): List<Recording> {
        val out = ArrayList<Recording>()
        helper.readableDatabase.rawQuery("SELECT * FROM recordings ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext()) out.add(cursorToRec(c))
        }
        return out
    }

    fun recordingsForJob(jobId: String): List<Recording> {
        val out = ArrayList<Recording>()
        helper.readableDatabase.rawQuery(
            "SELECT * FROM recordings WHERE job_id = ? ORDER BY created_at DESC", arrayOf(jobId)
        ).use { c -> while (c.moveToNext()) out.add(cursorToRec(c)) }
        return out
    }

    fun deleteRecording(id: String) {
        val r = recording(id)
        helper.writableDatabase.delete("tasks", "recording_id = ?", arrayOf(id))
        helper.writableDatabase.delete("recordings", "id = ?", arrayOf(id))
        if (r != null && r.audioPath.isNotEmpty()) runCatching { File(r.audioPath).delete() }
    }

    /** Grouped by job, newest activity first, optionally filtered by a search string. */
    fun groups(query: String): List<JobGroup> {
        val q = query.trim().lowercase()
        val all = allRecordings()
        val order = ArrayList<String>()
        val byJob = LinkedHashMap<String, MutableList<Recording>>()

        all.forEach { r ->
            if (!byJob.containsKey(r.jobId)) {
                byJob[r.jobId] = ArrayList()
                order.add(r.jobId)
            }
            byJob[r.jobId]!!.add(r)
        }

        if (q.isEmpty()) {
            return order.map { id ->
                val list = byJob[id]!!
                JobGroup(id, list.first().jobName, list)
            }
        }

        val out = ArrayList<JobGroup>()
        order.forEach { id ->
            val list = byJob[id]!!
            val jobHit = list.first().jobName.lowercase().contains(q)
            val hits = list.filter { r ->
                jobHit ||
                    r.transcript.lowercase().contains(q) ||
                    r.notes.lowercase().contains(q) ||
                    r.summary.lowercase().contains(q) ||
                    r.clientPhone.lowercase().contains(q) ||
                    tasks(r.id).any { it.text.lowercase().contains(q) }
            }
            if (hits.isNotEmpty()) out.add(JobGroup(id, list.first().jobName, hits))
        }
        return out
    }

    fun counts(): Triple<Int, Int, Long> {
        var recs = 0
        var jobs = 0
        var bytes = 0L
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM recordings", null).use { if (it.moveToFirst()) recs = it.getInt(0) }
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM jobs", null).use { if (it.moveToFirst()) jobs = it.getInt(0) }
        allRecordings().forEach { r -> if (r.audioPath.isNotEmpty()) bytes += File(r.audioPath).length() }
        return Triple(recs, jobs, bytes)
    }

    /* ----------------------------------------------------------------- tasks */

    fun tasks(recordingId: String): List<Task> {
        val out = ArrayList<Task>()
        helper.readableDatabase.rawQuery(
            "SELECT id, recording_id, text, done, created_at FROM tasks WHERE recording_id = ? ORDER BY created_at ASC",
            arrayOf(recordingId)
        ).use { c ->
            while (c.moveToNext()) out.add(Task(c.str(0), c.str(1), c.str(2), c.getInt(3) != 0, c.long(4)))
        }
        return out
    }

    fun addTask(recordingId: String, text: String): Task {
        val t = Task(UUID.randomUUID().toString(), recordingId, text.trim(), false, System.currentTimeMillis())
        val v = ContentValues().apply {
            put("id", t.id); put("recording_id", t.recordingId); put("text", t.text)
            put("done", if (t.done) 1 else 0); put("created_at", t.createdAt)
        }
        helper.writableDatabase.insert("tasks", null, v)
        return t
    }

    fun setTaskDone(id: String, done: Boolean) {
        val v = ContentValues().apply { put("done", if (done) 1 else 0) }
        helper.writableDatabase.update("tasks", v, "id = ?", arrayOf(id))
    }

    fun deleteTask(id: String) {
        helper.writableDatabase.delete("tasks", "id = ?", arrayOf(id))
    }

    /** Rows left mid-transcription by a crash go back to 'pending'. */
    fun resetStaleTranscriptions() {
        val v = ContentValues().apply {
            put("transcript_status", TS_PENDING)
            put("transcript_error", "")
        }
        helper.writableDatabase.update("recordings", v, "transcript_status = ?", arrayOf(TS_WORKING))
    }

    /** For the backup file. */
    fun allTasks(): List<Task> {
        val out = ArrayList<Task>()
        helper.readableDatabase.rawQuery(
            "SELECT id, recording_id, text, done, created_at FROM tasks ORDER BY created_at ASC", null
        ).use { c ->
            while (c.moveToNext()) out.add(Task(c.str(0), c.str(1), c.str(2), c.getInt(3) != 0, c.long(4)))
        }
        return out
    }

    fun replaceAll(jobs: List<Job>, clients: List<Client>, recordings: List<Recording>, tasks: List<Task>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("tasks", null, null)
            db.delete("recordings", null, null)
            db.delete("clients", null, null)
            db.delete("jobs", null, null)
            jobs.forEach { j ->
                db.insert("jobs", null, ContentValues().apply {
                    put("id", j.id); put("name", j.name)
                    put("created_at", j.createdAt); put("updated_at", j.updatedAt)
                })
            }
            clients.forEach { c ->
                db.insert("clients", null, ContentValues().apply {
                    put("id", c.id); put("name", c.name); put("phone", c.phone)
                    put("created_at", c.createdAt); put("updated_at", c.updatedAt)
                })
            }
            recordings.forEach { db.insert("recordings", null, recValues(it)) }
            tasks.forEach { t ->
                db.insert("tasks", null, ContentValues().apply {
                    put("id", t.id); put("recording_id", t.recordingId); put("text", t.text)
                    put("done", if (t.done) 1 else 0); put("created_at", t.createdAt)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Where recorded audio lives: app-private, no permission needed, gone with uninstall. */
    fun audioDir(): File {
        val d = File(app.filesDir, "audio")
        if (!d.exists()) d.mkdirs()
        return d
    }
}

private fun Cursor.str(i: Int): String = if (isNull(i)) "" else getString(i)
private fun Cursor.long(i: Int): Long = if (isNull(i)) 0L else getLong(i)
private fun Cursor.strByName(name: String): String = str(getColumnIndexOrThrow(name))
private fun Cursor.longByName(name: String): Long = long(getColumnIndexOrThrow(name))
