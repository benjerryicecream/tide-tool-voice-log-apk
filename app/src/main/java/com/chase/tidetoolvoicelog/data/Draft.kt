package com.chase.tidetoolvoicelog.data

/**
 * A recording that exists as a file but has not been filed yet: what the Save
 * screen works on. Held in memory between the import step and the form, which is
 * fine because it is only ever alive while that screen is open.
 */
data class Draft(
    val kind: String,
    val audioPath: String,
    val mime: String,
    val durationMs: Long,
    val createdAt: Long,
    val sourceFile: String,
    val line: String,
    val client: Client?,
    val phoneDigits: String,
    val contactName: String,
    val transcript: String,
    val liveNote: String,
    val importedBy: String,
    /** where the file came from, for the Diagnostics-free plain message */
    val origin: String
) {
    companion object {
        /** Simple hand-off between the import step and SaveActivity. */
        var pending: Draft? = null
    }
}
