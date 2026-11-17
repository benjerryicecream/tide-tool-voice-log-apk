package com.chase.tidetoolvoicelog.data

/** A client/job grouping. The web app has one "client or job" field, so a job is named after the client. */
data class Job(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** Phone book entry. Number is stored pretty, matching happens on digits. */
data class Client(
    val id: String,
    val name: String,
    val phone: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class Task(
    val id: String,
    val recordingId: String,
    val text: String,
    val done: Boolean,
    val createdAt: Long
)

const val KIND_VOICE = "voice"
const val KIND_CALL = "call"

const val TS_NONE = "none"
const val TS_PENDING = "pending"
const val TS_WORKING = "working"
const val TS_DONE = "done"
const val TS_FAILED = "failed"

/** The two lines, and what the app calls them. The recording itself never says which one rang. */
object Lines {
    const val BUSINESS = "2nd line (Verizon business)"
    const val PERSONAL = "1st line"
    const val UNKNOWN = "Unknown"
    val ALL = listOf(BUSINESS, PERSONAL, UNKNOWN)

    fun short(value: String?): String = when (value) {
        BUSINESS -> "2nd line"
        PERSONAL -> "1st line"
        UNKNOWN -> "line unknown"
        else -> value ?: ""
    }
}

data class Recording(
    val id: String,
    val jobId: String,
    val jobName: String,
    val kind: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioPath: String,
    val mime: String,
    val line: String,
    val clientId: String,
    val clientName: String,
    val clientPhone: String,
    val sourceFile: String,
    val transcript: String,
    val transcriptStatus: String,
    val transcriptSource: String,
    val transcriptError: String,
    val notes: String,
    val summary: String,
    /** "prompt" when the app saw the call come in on the business line, "manual" otherwise. */
    val importedBy: String
) {
    val isCall: Boolean get() = kind == KIND_CALL
}

/** One job with its recordings, newest first - what the log list renders. */
data class JobGroup(
    val jobId: String,
    val jobName: String,
    val recordings: List<Recording>
)
