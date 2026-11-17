package com.chase.tidetoolvoicelog.ui

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chase.tidetoolvoicelog.App
import com.chase.tidetoolvoicelog.R
import com.chase.tidetoolvoicelog.ai.Summarizer
import com.chase.tidetoolvoicelog.data.Lines
import com.chase.tidetoolvoicelog.data.Recording
import com.chase.tidetoolvoicelog.data.TS_DONE
import com.chase.tidetoolvoicelog.data.TS_FAILED
import com.chase.tidetoolvoicelog.data.TS_PENDING
import com.chase.tidetoolvoicelog.data.TS_WORKING
import com.chase.tidetoolvoicelog.media.TranscriptionRunner
import com.chase.tidetoolvoicelog.util.Fmt
import com.chase.tidetoolvoicelog.util.TextExport
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DetailActivity : AppCompatActivity() {

    private lateinit var screen: Screen
    private lateinit var recId: String
    private var rec: Recording? = null
    private var player: MediaPlayer? = null
    private var playing = false
    private lateinit var playButton: MaterialButton
    private lateinit var statusHost: LinearLayout
    private lateinit var taskHost: LinearLayout
    private var transcriptEdit: TextInputEditText? = null
    private var notesEdit: TextInputEditText? = null
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private val onTranscribed: (String) -> Unit = { changedId ->
        if (changedId == recId) {
            rec = App.repo.recording(recId)
            refreshStatus()
            rec?.let { r ->
                if (transcriptEdit?.text?.toString()?.isBlank() == true && r.transcript.isNotBlank()) {
                    transcriptEdit?.setText(r.transcript)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val r = App.repo.recording(recId)
        if (r == null) {
            finish()
            return
        }
        rec = r
        screen = Screen(this)
        setContentView(screen.root)
        build(r)
        TranscriptionRunner.addListener(onTranscribed)
    }

    override fun onDestroy() {
        TranscriptionRunner.removeListener(onTranscribed)
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        persistText()
    }

    private fun build(r: Recording) {
        screen.title(if (r.isCall) "Call recording" else "Voice note", back = true)
        screen.action("Export") { export() }

        screen.body.addView(screen.textView(r.jobName, 12.5f, R.color.teal, true))
        val whenLine = buildString {
            append(Fmt.whenFull(r.createdAt)).append(" - ").append(Fmt.duration(r.durationMs))
            if (r.isCall) append(" - ").append(Lines.short(r.line))
        }
        screen.body.addView(screen.text(whenLine, 14f, R.color.muted))
        if (r.clientPhone.isNotBlank()) {
            screen.body.addView(screen.text("Phone book: ${r.clientName} - ${r.clientPhone}", 13.5f, R.color.muted))
        }

        // player
        val playerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(6))
        }
        playButton = MaterialButton(this).apply {
            text = "Play"
            isAllCaps = false
            textSize = 15f
            setTextColor(color(R.color.ink_on_teal))
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.teal))
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(52)
            setOnClickListener { togglePlay() }
        }
        playerRow.addView(playButton, LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        val file = File(r.audioPath)
        playerRow.addView(
            screen.text(if (file.exists()) Fmt.bytes(file.length()) else "audio missing", 12.5f, R.color.muted),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        screen.body.addView(playerRow)
        if (!file.exists()) {
            screen.body.addView(screen.muted("The audio for this recording is not on this device any more. The transcript and notes are still here."))
        }

        statusHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.body.addView(statusHost)
        refreshStatus()

        if (r.isCall) {
            screen.sectionLabel("Line")
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@DetailActivity, android.R.layout.simple_spinner_dropdown_item, Lines.ALL)
                setSelection(Lines.ALL.indexOf(r.line.ifBlank { Lines.BUSINESS }).coerceAtLeast(0))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, i: Long) {
                        val value = Lines.ALL[pos]
                        if (value != rec?.line) {
                            rec?.let { App.repo.updateRecording(it.copy(line = value)) }
                            rec = App.repo.recording(recId)
                        }
                    }

                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
            }
            screen.body.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }

        screen.sectionLabel("Transcript")
        transcriptEdit = screen.field("Transcript", r.transcript, multiline = true).also { edit ->
            edit.addTextChangedListener(debounced { persistText() })
        }

        screen.sectionLabel("Notes")
        notesEdit = screen.field("What you saw, what you quoted, what is next", r.notes, multiline = true).also { edit ->
            edit.addTextChangedListener(debounced { persistText() })
        }

        screen.sectionLabel("Action items")
        taskHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.body.addView(taskHost)
        renderTasks()

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val input = TextInputEditText(this).apply {
            hint = "Add an action item"
            textSize = 16f
            setTextColor(color(R.color.ink))
        }
        val inputWrap = com.google.android.material.textfield.TextInputLayout(this).apply {
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = color(R.color.surface)
            boxStrokeColor = color(R.color.line)
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
            addView(input)
        }
        val addBtn = MaterialButton(this).apply {
            text = "+"
            textSize = 20f
            setTextColor(color(R.color.teal))
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.surface2))
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            minWidth = dp(56)
            minHeight = dp(56)
            setOnClickListener {
                val t = input.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    App.repo.addTask(recId, t)
                    input.setText("")
                    renderTasks()
                }
            }
        }
        addRow.addView(inputWrap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        addRow.addView(addBtn)
        screen.body.addView(addRow)

        screen.gap(10)
        screen.primary("Summarize") { summarize() }
        if (r.transcript.isBlank()) {
            screen.secondary("Transcribe on this device") {
                TranscriptionRunner.enqueue(this, recId)
                toast("Transcribing. The speech model downloads the first time.")
                refreshStatus()
            }
        }
        screen.danger("Delete this recording") { confirmDelete() }
    }

    /* -------------------------------------------------------------- status */

    private fun refreshStatus() {
        val r = rec ?: return
        statusHost.removeAllViews()
        val msg: String? = when {
            r.transcriptStatus == TS_WORKING ->
                if (r.transcriptError.startsWith("Downloading")) "Downloading the speech model (once, about 40 MB). The audio is already saved."
                else "Transcribing on this device. Nothing is uploaded."
            r.transcriptStatus == TS_FAILED -> r.transcriptError.ifBlank { "Transcription failed. The audio is kept." }
            (r.transcriptStatus == TS_PENDING || r.transcriptStatus == "none") && r.transcript.isBlank() ->
                if (r.isCall) "Not transcribed yet. The audio is saved on this device."
                else "No transcript. The audio is saved - type a summary, or transcribe it on the device."
            else -> null
        }
        if (msg != null) {
            val tv = screen.text(msg, 13.5f, if (r.transcriptStatus == TS_FAILED) R.color.coral else R.color.sand)
            tv.setPadding(0, dp(8), 0, dp(8))
            statusHost.addView(tv)
        }
    }

    private fun renderTasks() {
        taskHost.removeAllViews()
        val tasks = App.repo.tasks(recId)
        if (tasks.isEmpty()) {
            taskHost.addView(screen.muted("No action items yet."))
            return
        }
        tasks.forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val check = CheckBox(this).apply {
                isChecked = t.done
                buttonTintList = android.content.res.ColorStateList.valueOf(color(R.color.teal))
                setOnCheckedChangeListener { _, checked -> App.repo.setTaskDone(t.id, checked) }
            }
            val label = screen.textView(t.text, 15.5f, if (t.done) R.color.muted else R.color.ink, false)
            if (t.done) label.paintFlags = label.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            val del = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "x"
                textSize = 14f
                setTextColor(color(R.color.muted))
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                setPadding(dp(10), 0, 0, 0)
                setOnClickListener {
                    App.repo.deleteTask(t.id)
                    renderTasks()
                }
            }
            row.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(del)
            taskHost.addView(row)
        }
    }

    /* --------------------------------------------------------------- audio */

    private fun togglePlay() {
        val r = rec ?: return
        if (playing) {
            player?.pause()
            playing = false
            playButton.text = "Play"
            return
        }
        try {
            if (player == null) {
                player = MediaPlayer().apply {
                    setDataSource(r.audioPath)
                    prepare()
                    setOnCompletionListener {
                        playing = false
                        playButton.text = "Play"
                    }
                }
            }
            player?.start()
            playing = true
            playButton.text = "Pause"
        } catch (e: Exception) {
            toast("Could not play that recording: ${e.message}")
        }
    }

    /* --------------------------------------------------------------- edits */

    private fun debounced(action: () -> Unit): TextWatcher {
        var token = 0
        return object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                token++
                val mine = token
                ui.postDelayed({ if (mine == token) action() }, 600)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
    }

    private fun persistText() {
        val r = rec ?: return
        val t = transcriptEdit?.text?.toString().orEmpty()
        val n = notesEdit?.text?.toString().orEmpty()
        if (t == r.transcript && n == r.notes) return
        val status = when {
            t.isNotBlank() -> TS_DONE
            r.transcriptStatus == TS_DONE -> TS_PENDING
            else -> r.transcriptStatus
        }
        val updated = r.copy(
            transcript = t,
            notes = n,
            transcriptStatus = status,
            transcriptSource = if (t.isNotBlank() && r.transcriptSource.isBlank()) "typed" else r.transcriptSource
        )
        App.repo.updateRecording(updated)
        rec = updated
    }

    /* -------------------------------------------------------------- actions */

    private fun summarize() {
        val r = rec ?: return
        if (r.transcript.isBlank()) {
            toast("There is no transcript to summarize yet.")
            return
        }
        if (!App.prefs.aiReady) {
            AlertDialog.Builder(this)
                .setTitle("AI summary is off")
                .setMessage("Add your own OpenAI-compatible base URL, key and model in Settings. The app works fine without it.")
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        toast("Summarizing...")
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    Summarizer(App.prefs).summarize(r.jobName, Fmt.whenFull(r.createdAt), r.transcript, r.notes)
                }
                val fresh = App.repo.recording(recId) ?: return@launch
                App.repo.updateRecording(fresh.copy(summary = text))
                rec = App.repo.recording(recId)
                toast("Summary saved")
                recreate()
            } catch (e: Exception) {
                toast(e.message ?: "Summary failed")
            }
        }
    }

    private fun export() {
        val r = rec ?: return
        try {
            val text = TextExport.entryText(r.jobName, r, App.repo.tasks(recId))
            val name = "tide-tool-" + r.jobName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30) + "-" +
                java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(r.createdAt)) + ".txt"
            com.chase.tidetoolvoicelog.data.Saver.saveToDownloads(this, name, "text/plain", text)
            toast("Exported to Downloads/TideTool")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this recording?")
            .setMessage("The audio, transcript and notes are removed from this phone. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                player?.release()
                player = null
                App.repo.deleteRecording(recId)
                toast("Recording deleted")
                finish()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ID = "recording_id"
    }
}
