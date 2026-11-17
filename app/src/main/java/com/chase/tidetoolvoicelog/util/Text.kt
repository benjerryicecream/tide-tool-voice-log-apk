package com.chase.tidetoolvoicelog.util

import com.chase.tidetoolvoicelog.data.Lines
import com.chase.tidetoolvoicelog.data.Recording
import com.chase.tidetoolvoicelog.data.Task
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Phone numbers: same tolerant matching as the web app. */
object Phone {
    /** Digits only, leading US country code dropped: "+1 (808) 555-1234" -> "8085551234". */
    fun digits(raw: String?): String {
        val d = (raw ?: "").filter { it.isDigit() }
        return if (d.length == 11 && d.startsWith("1")) d.substring(1) else d
    }

    fun pretty(raw: String?): String {
        val d = digits(raw)
        return if (d.length == 10) {
            "(" + d.substring(0, 3) + ") " + d.substring(3, 6) + "-" + d.substring(6)
        } else {
            (raw ?: "").trim().replace(Regex("\\s+"), " ")
        }
    }
}

/** What a call file name says about the call. */
data class CallFileInfo(
    val samsung: Boolean,
    val fileName: String,
    val dateMs: Long?,
    val tail: String,
    val phoneDigits: String,
    val contactName: String
)

object CallFiles {
    private val SAMSUNG = Regex(
        "^Call[_\\-\\s]?(\\d{4})(\\d{2})(\\d{2})[_\\-\\s](\\d{2})(\\d{2})(\\d{2})(?:[_\\-\\s](.*))?$",
        RegexOption.IGNORE_CASE
    )
    private val PHONEISH = Regex("^[\\d\\s()+.\\-]+$")
    private val ANY_PHONE = Regex("(?:\\+?1[\\s.\\-]?)?\\(?(\\d{3})\\)?[\\s.\\-]?(\\d{3})[\\s.\\-]?(\\d{4})")

    /**
     * Samsung writes Call_YYYYMMDD_HHMMSS_<number or contact name>.m4a.
     * When the number is already in the phone's own contacts it writes the name instead.
     */
    fun parse(fileName: String): CallFileInfo {
        val base = fileName.replace(Regex("\\.[a-z0-9]+$", RegexOption.IGNORE_CASE), "")
        var samsung = false
        var dateMs: Long? = null
        var tail = base

        val m = SAMSUNG.find(base)
        if (m != null) {
            samsung = true
            val g = m.groupValues
            val cal = Calendar.getInstance()
            cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            cal.set(Calendar.MILLISECOND, 0)
            dateMs = cal.timeInMillis
            tail = g[7].trim()
        }

        var phone = ""
        var contact = ""
        if (tail.isNotEmpty() && PHONEISH.matches(tail) && Phone.digits(tail).length >= 7) {
            phone = Phone.digits(tail)
        } else if (samsung && tail.isNotEmpty() && !PHONEISH.matches(tail)) {
            contact = tail.replace("_", " ").replace(Regex("\\s+"), " ").trim()
        }

        if (phone.isEmpty()) {
            val n = ANY_PHONE.find(fileName)
            if (n != null) {
                val g = n.groupValues
                phone = g[1] + g[2] + g[3]
            }
        }

        return CallFileInfo(samsung, fileName, dateMs, tail, phone, contact)
    }
}

object Fmt {
    private val stampFmt = SimpleDateFormat("EEE, MMM d - h:mm a", Locale.US)
    private val fullFmt = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US)
    private val dayFmt = SimpleDateFormat("MMM d", Locale.US)

    fun whenShort(ms: Long): String = stampFmt.format(Date(ms))
    fun whenFull(ms: Long): String = fullFmt.format(Date(ms))

    fun day(ms: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        ) return "Today"
        val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (y.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            y.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        ) return "Yesterday"
        return dayFmt.format(Date(ms))
    }

    /** 0:07 - 4:31 - 1:02:09 */
    fun duration(ms: Long): String {
        val s = Math.max(0, Math.round(ms / 1000.0))
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> (n / 1024).toString() + " KB"
        n < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", n / 1073741824.0)
    }
}

/** Plain-text export, same shape as the web app's, plus the line tag. */
object TextExport {

    fun jobText(jobName: String, recsNewestFirst: List<Recording>, tasks: (String) -> List<Task>): String {
        val sb = StringBuilder()
        sb.append("TIDE & TOOL HANDYMAN SERVICES - WORK LOG\r\n")
        sb.append("Client / job: ").append(jobName).append("\r\n")
        sb.append("Exported: ").append(Fmt.whenFull(System.currentTimeMillis())).append("\r\n")
        sb.append("Entries: ").append(recsNewestFirst.size).append("\r\n\r\n")

        recsNewestFirst.reversed().forEachIndexed { i, r ->
            val kind = if (r.isCall) "CALL RECORDING" else "VOICE NOTE"
            sb.append("================================================\r\n")
            sb.append("[").append(i + 1).append("] ").append(kind).append(" - ")
                .append(Fmt.whenFull(r.createdAt)).append(" - ").append(Fmt.duration(r.durationMs)).append("\r\n")
            sb.append("================================================\r\n")
            if (r.isCall) sb.append("Line: ").append(r.line.ifBlank { Lines.BUSINESS }).append("\r\n")
            if (r.clientPhone.isNotBlank()) sb.append("Client phone: ").append(r.clientPhone).append("\r\n")
            sb.append("\r\nTRANSCRIPT\r\n")
            sb.append(if (r.transcript.isBlank()) "(not transcribed yet)" else r.transcript.trim()).append("\r\n")
            sb.append("\r\nNOTES\r\n")
            sb.append(if (r.notes.isBlank()) "(none)" else r.notes.trim()).append("\r\n")
            if (r.summary.isNotBlank()) {
                sb.append("\r\nAI SUMMARY\r\n").append(r.summary.trim()).append("\r\n")
            }
            sb.append("\r\nACTION ITEMS\r\n")
            val items = tasks(r.id)
            if (items.isEmpty()) sb.append("(none)\r\n") else {
                items.forEach { sb.append(if (it.done) "[x] " else "[ ] ").append(it.text).append("\r\n") }
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    fun entryText(jobName: String, r: Recording, tasks: List<Task>): String {
        val sb = StringBuilder()
        sb.append("TIDE & TOOL HANDYMAN SERVICES - WORK LOG\r\n")
        sb.append("Client / job: ").append(jobName).append("\r\n")
        sb.append("Type: ").append(if (r.isCall) "Call recording" else "Voice note").append("\r\n")
        sb.append("Recorded: ").append(Fmt.whenFull(r.createdAt)).append("\r\n")
        sb.append("Length: ").append(Fmt.duration(r.durationMs)).append("\r\n")
        if (r.isCall) sb.append("Line: ").append(r.line.ifBlank { Lines.BUSINESS }).append("\r\n")
        if (r.clientPhone.isNotBlank()) sb.append("Client phone: ").append(r.clientPhone).append("\r\n")
        sb.append("\r\nTRANSCRIPT\r\n")
        sb.append(if (r.transcript.isBlank()) "(not transcribed yet)" else r.transcript.trim()).append("\r\n")
        sb.append("\r\nNOTES\r\n")
        sb.append(if (r.notes.isBlank()) "(none)" else r.notes.trim()).append("\r\n")
        if (r.summary.isNotBlank()) sb.append("\r\nAI SUMMARY\r\n").append(r.summary.trim()).append("\r\n")
        sb.append("\r\nACTION ITEMS\r\n")
        if (tasks.isEmpty()) sb.append("(none)\r\n") else tasks.forEach {
            sb.append(if (it.done) "[x] " else "[ ] ").append(it.text).append("\r\n")
        }
        return sb.toString()
    }
}
