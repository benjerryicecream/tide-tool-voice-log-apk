package com.chase.tidetoolvoicelog.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.chase.tidetoolvoicelog.App
import com.chase.tidetoolvoicelog.data.Draft
import com.chase.tidetoolvoicelog.data.KIND_CALL
import com.chase.tidetoolvoicelog.data.Lines
import com.chase.tidetoolvoicelog.data.Recording
import com.chase.tidetoolvoicelog.data.TS_NONE
import com.chase.tidetoolvoicelog.data.TS_PENDING
import com.chase.tidetoolvoicelog.util.CallFiles
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Turns a file on the phone into a recording the app owns.
 *
 * The audio is copied into app-private storage first, so a recording is never
 * lost to a permission that expires or a file the user later moves.
 */
object Importer {

    fun durationOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Copy + parse + match. Does not touch the database. */
    fun prepareFromUri(context: Context, uri: Uri, displayName: String): Draft {
        val file = CallFilesFinder.copyInto(context, uri, displayName, App.repo.audioDir())
        return draftFor(context, file, displayName, importedBy = "manual", origin = "picked from the phone")
    }

    fun prepareFromFound(context: Context, found: CallFilesFinder.Found, importedBy: String): Draft {
        val file = CallFilesFinder.copyInto(context, found.uri, found.displayName, App.repo.audioDir())
        return draftFor(context, file, found.displayName, importedBy = importedBy, origin = "Samsung recording")
    }

    fun draftFor(context: Context, file: File, displayName: String, importedBy: String, origin: String): Draft {
        val info = CallFiles.parse(displayName)
        val client = when {
            info.phoneDigits.isNotEmpty() -> App.repo.findClientByPhone(info.phoneDigits)
            info.contactName.isNotEmpty() -> App.repo.findClientByName(info.contactName)
            else -> null
        }
        val createdAt = info.dateMs ?: file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        return Draft(
            kind = KIND_CALL,
            audioPath = file.absolutePath,
            mime = "audio/mp4",
            durationMs = durationOf(file),
            createdAt = createdAt,
            sourceFile = displayName,
            line = Lines.BUSINESS,
            client = client,
            phoneDigits = info.phoneDigits,
            contactName = info.contactName,
            transcript = "",
            liveNote = "",
            importedBy = importedBy,
            origin = origin
        )
    }

    /** Files a call recording. Nothing is transcribed here - that happens after. */
    fun commit(
        draft: Draft,
        jobName: String,
        line: String,
        transcript: String,
        notes: String
    ): Recording {
        val repo = App.repo
        val job = repo.ensureJob(jobName)
        val client = draft.client
            ?: repo.findClientByName(jobName)
            ?: if (draft.phoneDigits.isNotEmpty()) repo.findClientByPhone(draft.phoneDigits) else null

        val rec = Recording(
            id = UUID.randomUUID().toString(),
            jobId = job.id,
            jobName = job.name,
            kind = draft.kind,
            createdAt = draft.createdAt,
            durationMs = draft.durationMs,
            audioPath = draft.audioPath,
            mime = draft.mime,
            line = if (draft.kind == KIND_CALL) line else "",
            clientId = client?.id ?: "",
            clientName = client?.name ?: "",
            clientPhone = client?.phone ?: "",
            sourceFile = draft.sourceFile,
            transcript = transcript.trim(),
            transcriptStatus = if (transcript.isNotBlank()) "done" else if (draft.kind == KIND_CALL) TS_PENDING else TS_NONE,
            transcriptSource = if (transcript.isNotBlank()) "typed" else "",
            transcriptError = "",
            notes = notes.trim(),
            summary = "",
            importedBy = draft.importedBy
        )
        repo.insertRecording(rec)
        return rec
    }

    /** Used by the auto-import path, where there is no form to fill in. */
    fun autoImport(context: Context, found: CallFilesFinder.Found): Recording {
        val draft = prepareFromFound(context, found, "prompt")
        val jobName = draft.client?.name?.takeIf { it.isNotBlank() }
            ?: draft.contactName.takeIf { it.isNotBlank() }
            ?: com.chase.tidetoolvoicelog.util.Phone.pretty(draft.phoneDigits).takeIf { it.isNotBlank() }
            ?: "Unsorted calls"
        return commit(draft, jobName, Lines.BUSINESS, "", "")
    }
}
