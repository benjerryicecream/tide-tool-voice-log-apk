package com.chase.tidetoolvoicelog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chase.tidetoolvoicelog.App
import com.chase.tidetoolvoicelog.R
import com.chase.tidetoolvoicelog.data.Draft
import com.chase.tidetoolvoicelog.data.Recording
import com.chase.tidetoolvoicelog.data.TS_FAILED
import com.chase.tidetoolvoicelog.data.TS_WORKING
import com.chase.tidetoolvoicelog.media.CallFilesFinder
import com.chase.tidetoolvoicelog.media.Importer
import com.chase.tidetoolvoicelog.util.Fmt
import com.chase.tidetoolvoicelog.data.Lines
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var screen: Screen
    private lateinit var listHost: LinearLayout
    private var query = ""
    private var search: android.widget.EditText? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImportFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = Screen(this)
        setContentView(screen.root)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        renderLog()
        maybeOfferNewRecording()
    }

    /* --------------------------------------------------------------- screen */

    private fun buildUi() {
        screen.title("Tide & Tool", "VOICE WORK LOG")
        screen.action("Menu") { showMenu() }

        search = screen.field("Search jobs, transcripts, notes", query).also { edit ->
            edit.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    renderLog()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }

        listHost = screen.host()

        screen.bottomBar {
            val record = MaterialButton(context).apply {
                text = "Record voice note"
                textSize = 16f
                isAllCaps = false
                setTextColor(context.color(R.color.ink_on_teal))
                backgroundTintList = android.content.res.ColorStateList.valueOf(context.color(R.color.teal))
                cornerRadius = context.dp(14)
                insetTop = 0
                insetBottom = 0
                minHeight = context.dp(58)
                setOnClickListener { startActivity(Intent(this@MainActivity, RecorderActivity::class.java)) }
            }
            val import = MaterialButton(context).apply {
                text = "Import call"
                textSize = 15f
                isAllCaps = false
                setTextColor(context.color(R.color.ink))
                backgroundTintList = android.content.res.ColorStateList.valueOf(context.color(R.color.surface2))
                cornerRadius = context.dp(14)
                insetTop = 0
                insetBottom = 0
                minHeight = context.dp(58)
                setOnClickListener { showImportSheet() }
            }
            addView(record, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f).apply { marginEnd = context.dp(8) })
            addView(import, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun showMenu() {
        val items = arrayOf(
            "Setup & permissions",
            "Client phone book",
            "Settings",
            "Diagnostics (last call)",
            "Check for new Samsung recordings"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, SetupActivity::class.java))
                    1 -> startActivity(Intent(this, PhonebookActivity::class.java))
                    2 -> startActivity(Intent(this, SettingsActivity::class.java))
                    3 -> startActivity(Intent(this, DiagnosticsActivity::class.java))
                    4 -> checkForNewRecordings(manual = true)
                }
            }
            .show()
    }

    /* ------------------------------------------------------------------ log */

    private fun renderLog() {
        listHost.removeAllViews()
        val groups = App.repo.groups(query)

        if (App.repo.allRecordings().isEmpty()) {
            listHost.addView(screen.muted("No recordings yet.", 15f))
            listHost.addView(screen.muted("Tap Record voice note at the bottom and talk, or import a Samsung call recording.", 13.5f))
            return
        }

        if (groups.isEmpty()) {
            listHost.addView(screen.muted("Nothing matches \"$query\".", 15f))
            return
        }

        groups.forEach { group ->
            screen.card(listHost) {
                val head = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                head.addView(
                    screen.textView(group.jobName, 16f, R.color.ink, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                head.addView(pill(this@MainActivity, group.recordings.size.toString(), R.color.teal), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = context.dp(8) })
                val export = MaterialButton(this@MainActivity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Export"
                    textSize = 13f
                    isAllCaps = false
                    setTextColor(context.color(R.color.teal))
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minHeight = 0
                    setPadding(context.dp(6), context.dp(4), context.dp(2), context.dp(4))
                    setOnClickListener { exportJob(group.jobId, group.jobName) }
                }
                head.addView(export)
                addView(head)

                group.recordings.forEach { rec ->
                    addView(entryRow(rec))
                }
            }
        }
    }

    private fun entryRow(rec: Recording): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(10), 0, context.dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, rec.id))
            }
        }

        val title = when {
            rec.transcript.isNotBlank() -> rec.transcript
            rec.notes.isNotBlank() -> rec.notes
            rec.transcriptStatus == TS_WORKING -> "Transcribing on this device..."
            else -> "Not transcribed yet"
        }
        row.addView(screen.textView(title.take(160), 15.5f, R.color.ink, false))

        val meta = buildString {
            append(Fmt.whenShort(rec.createdAt))
            append(" - ").append(Fmt.duration(rec.durationMs))
            if (rec.isCall) append(" - ").append(Lines.short(rec.line))
            if (rec.transcriptStatus == TS_FAILED) append(" - needs a summary")
        }
        val metaView = screen.textView(meta, 12.5f, R.color.muted, false)
        row.addView(metaView)

        // thin separator
        row.addView(android.view.View(this).apply { setBackgroundColor(color(R.color.line)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(10) })

        return row
    }

    /* --------------------------------------------------------------- import */

    private fun showImportSheet() {
        val options = arrayOf(
            "Find new Samsung recordings",
            "Pick an audio file"
        )
        AlertDialog.Builder(this)
            .setTitle("Import a call recording")
            .setItems(options) { _, which ->
                if (which == 0) checkForNewRecordings(manual = true) else pickAudio.launch(arrayOf("audio/*"))
            }
            .show()
    }

    private fun hasAudioRead(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioRead() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        requestPermissions(arrayOf(perm), 91)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 91 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            checkForNewRecordings(manual = true)
        } else if (requestCode == 91) {
            toast("Without access to audio files the app cannot see Samsung's recordings. You can still pick one by hand.")
        }
    }

    private fun checkForNewRecordings(manual: Boolean) {
        if (!hasAudioRead()) {
            requestAudioRead()
            return
        }
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { CallFilesFinder.recent(this@MainActivity) }
                android.util.Log.i("Scan", "query ok, " + found.size + " file(s)")
                val known = App.repo.allRecordings().map { it.sourceFile }.toSet()
                val fresh = found.filter { it.displayName !in known }
                android.util.Log.i("Scan", "fresh=" + fresh.size + " (known=" + known.size + ")")
                if (fresh.isEmpty()) {
                    if (manual) toast("No new Samsung call recordings found.")
                } else {
                    offerImport(fresh)
                }
            } catch (e: Exception) {
                android.util.Log.e("Scan", "scan failed", e)
                toast("Could not look for recordings: " + e.message)
            }
        }
    }

    private fun maybeOfferNewRecording() {
        if (!App.prefs.promptIsFresh()) return
        if (!hasAudioRead()) return
        scope.launch {
            val found = withContext(Dispatchers.IO) { CallFilesFinder.recent(this@MainActivity) }
            val known = App.repo.allRecordings().map { it.sourceFile }.toSet()
            val fresh = found.filter { it.displayName !in known }
            if (fresh.isNotEmpty() && !isFinishing) offerImport(fresh)
        }
    }

    private fun offerImport(found: List<CallFilesFinder.Found>) {
        App.prefs.clearPrompt()
        val first = found.first()
        val matched = when {
            first.info.phoneDigits.isNotEmpty() -> App.repo.findClientByPhone(first.info.phoneDigits)
            first.info.contactName.isNotEmpty() -> App.repo.findClientByName(first.info.contactName)
            else -> null
        }
        val who = matched?.name ?: first.info.contactName.takeIf { it.isNotBlank() }
            ?: first.info.phoneDigits.takeIf { it.isNotBlank() }?.let { com.chase.tidetoolvoicelog.util.Phone.pretty(it) }
            ?: first.displayName

        AlertDialog.Builder(this)
            .setTitle("New recording found")
            .setMessage("$who\n${first.displayName}\n\nFile it under \"${matched?.name ?: who}\" on the 2nd line?")
            .setPositiveButton("Import") { _, _ ->
                scope.launch {
                    val draft = withContext(Dispatchers.IO) { Importer.prepareFromFound(this@MainActivity, first, "prompt") }
                    openSave(draft)
                }
            }
            .setNeutralButton("Not now") { d, _ -> d.dismiss() }
            .setNegativeButton("Discard prompt") { _, _ ->
                App.prefs.clearPrompt()
            }
            .show()
    }

    private fun startImportFromUri(uri: Uri) {
        scope.launch {
            try {
                val name = queryDisplayName(uri) ?: "call-${System.currentTimeMillis()}.m4a"
                val draft = withContext(Dispatchers.IO) { Importer.prepareFromUri(this@MainActivity, uri, name) }
                openSave(draft)
            } catch (e: Exception) {
                toast("Could not read that file: ${e.message}")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun openSave(draft: Draft) {
        Draft.pending = draft
        startActivity(Intent(this, SaveActivity::class.java))
    }

    private fun exportJob(jobId: String, jobName: String) {
        try {
            val recs = App.repo.recordingsForJob(jobId)
            val text = com.chase.tidetoolvoicelog.util.TextExport.jobText(jobName, recs) { App.repo.tasks(it) }
            val name = "tide-tool-" + jobName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40) + "-" +
                java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".txt"
            com.chase.tidetoolvoicelog.data.Saver.saveToDownloads(this, name, "text/plain", text)
            toast("Exported \"$jobName\" to Downloads/TideTool")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
