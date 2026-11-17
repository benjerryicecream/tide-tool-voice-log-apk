package com.chase.tidetoolvoicelog.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chase.tidetoolvoicelog.App
import com.chase.tidetoolvoicelog.R
import com.chase.tidetoolvoicelog.data.Backup
import com.chase.tidetoolvoicelog.util.Fmt
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var base: TextInputEditText
    private lateinit var key: TextInputEditText
    private lateinit var model: TextInputEditText
    private lateinit var autoImport: MaterialSwitch
    private val scope = CoroutineScope(Dispatchers.Main)

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = Screen(this)
        setContentView(screen.root)
        screen.title("Settings", back = true)

        screen.sectionLabel("AI summary (optional)")
        screen.body.addView(
            screen.muted(
                "The app works completely without this. If you fill it in, the Summarize button on a recording " +
                    "sends that recording's transcript to this endpoint with your key. Nothing else is ever sent.",
                13f
            )
        )
        base = screen.field("Base URL", App.prefs.aiBaseUrl)
        key = screen.field("API key", App.prefs.aiKey, password = true)
        model = screen.field("Model", App.prefs.aiModel)
        screen.body.addView(screen.muted("Stored on this phone only. Anyone holding the unlocked phone can read it.", 12.5f))
        screen.secondary("Save settings") {
            App.prefs.aiBaseUrl = base.text?.toString().orEmpty()
            App.prefs.aiKey = key.text?.toString().orEmpty()
            App.prefs.aiModel = model.text?.toString().orEmpty()
            toast(if (App.prefs.aiReady) "AI summary is on" else "Saved - AI summary is off")
        }

        screen.sectionLabel("After a call")
        val sw = MaterialSwitch(this).apply {
            text = "Import a new Samsung recording automatically"
            textSize = 15f
            isChecked = App.prefs.autoImport
            setOnCheckedChangeListener { _, checked -> App.prefs.autoImport = checked }
        }
        screen.body.addView(sw)
        screen.body.addView(
            screen.muted(
                "When this is off you get a \"new recording found\" prompt instead, and you decide. " +
                    "Either way the file is copied into the app before anything else happens.",
                12.5f
            )
        )
        autoImport = sw

        screen.sectionLabel("Backup")
        screen.body.addView(
            screen.muted(
                "Recordings live in this app's private storage. The JSON keeps jobs, clients, transcripts, notes and action items. " +
                    "Audio is only included if you ask for it, because it makes the file large.",
                13f
            )
        )
        screen.secondary("Export log (.json, no audio)") { exportBackup(false) }
        screen.secondary("Export log (.json, with audio)") { exportBackup(true) }
        screen.secondary("Import a backup") { pickBackup.launch(arrayOf("application/json", "text/plain", "*/*")) }

        screen.sectionLabel("On this phone")
        val (recs, jobs, bytes) = App.repo.counts()
        screen.body.addView(screen.text("$recs recordings - $jobs jobs - ${Fmt.bytes(bytes)} of audio", 14f, R.color.ink2))
        val clients = App.repo.clients().size
        screen.body.addView(screen.text("$clients clients in the phone book", 14f, R.color.ink2))

        screen.sectionLabel("About")
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }
        screen.body.addView(screen.muted("Tide & Tool Voice Log $version - debug build - package $packageName", 12.5f))
        screen.body.addView(
            screen.muted(
                "The app does not record calls and cannot. Samsung's own recorder is the only capture route on this phone, " +
                    "and its Record button is always yours to tap.",
                12.5f
            )
        )
    }

    private fun exportBackup(withAudio: Boolean) {
        scope.launch {
            try {
                val name = "tide-tool-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date()) + if (withAudio) "-with-audio" else "" + ".json"
                val result = withContext(Dispatchers.IO) {
                    Backup.export(this@SettingsActivity, App.repo, withAudio, name)
                }
                toast("Backup written to Downloads/TideTool (${Fmt.bytes(result.bytes)}, ${result.recordings} recordings)")
            } catch (e: Exception) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Backup not written")
                    .setMessage(e.message ?: "Unknown error")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun importBackup(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Replace the log with this backup?")
            .setMessage("The current recordings, clients and notes will be replaced by the file's contents.")
            .setPositiveButton("Replace") { _, _ ->
                scope.launch {
                    try {
                        val text = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                ?: throw IllegalStateException("could not read that file")
                        }
                        val res = withContext(Dispatchers.IO) { Backup.import(this@SettingsActivity, App.repo, text) }
                        toast("Imported ${res.recordings} recordings, ${res.clients} clients, ${res.audio} with audio")
                    } catch (e: Exception) {
                        toast("Import failed: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
