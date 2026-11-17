package com.chase.tidetoolvoicelog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chase.tidetoolvoicelog.R
import com.chase.tidetoolvoicelog.data.Draft
import com.chase.tidetoolvoicelog.data.KIND_VOICE
import com.chase.tidetoolvoicelog.media.LiveSpeech
import com.chase.tidetoolvoicelog.media.VoiceRecorder
import com.chase.tidetoolvoicelog.util.Fmt
import com.google.android.material.button.MaterialButton

class RecorderActivity : AppCompatActivity() {

    private lateinit var screen: Screen
    private lateinit var recorder: VoiceRecorder
    private var liveSpeech: LiveSpeech? = null

    private lateinit var timerView: TextView
    private lateinit var statusView: TextView
    private lateinit var liveView: TextView
    private lateinit var button: MaterialButton

    private var running = false
    private var startedAt = 0L
    private var liveNote = ""
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            timerView.text = Fmt.duration(System.currentTimeMillis() - startedAt)
            handler.postDelayed(this, 250)
        }
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else {
            statusView.text = "Microphone permission denied. Allow it and tap again."
            toast("Microphone permission is needed to record.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = VoiceRecorder(this)
        screen = Screen(this)
        setContentView(screen.root)
        buildUi()
    }

    private fun buildUi() {
        screen.title("Voice note", back = true)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(28), 0, 0)
        }
        timerView = screen.textView("0:00", 52f, R.color.ink, true).apply { gravity = Gravity.CENTER }
        box.addView(timerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusView = screen.muted("Tap the button and start talking.").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        liveView = screen.textView("", 17f, R.color.ink, false).apply {
            setPadding(0, dp(18), 0, 0)
        }
        box.addView(liveView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        screen.body.addView(box)

        screen.bottomBar {
            button = MaterialButton(context).apply {
                text = "Start recording"
                textSize = 18f
                isAllCaps = false
                setTextColor(context.color(R.color.ink_on_teal))
                backgroundTintList = android.content.res.ColorStateList.valueOf(context.color(R.color.teal))
                cornerRadius = context.dp(16)
                insetTop = 0
                insetBottom = 0
                minHeight = context.dp(72)
                setOnClickListener { if (running) stopAndSave() else askMic() }
            }
            addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun askMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun begin() {
        try {
            recorder.start()
        } catch (e: Exception) {
            statusView.text = "Could not start recording: ${e.message}"
            return
        }
        running = true
        startedAt = System.currentTimeMillis()
        liveNote = ""
        timerView.text = "0:00"
        button.text = "Stop and save"
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.coral))
        button.setTextColor(color(R.color.ink))
        statusView.text = "Recording. Talk normally - the phone can stay in your pocket."
        liveView.text = ""
        handler.post(tick)

        liveSpeech = LiveSpeech(
            this,
            onText = { finals, interim ->
                val shown = if (interim.isBlank()) finals else "$finals $interim"
                liveView.text = shown.trim()
                if (finals.isNotBlank() || interim.isNotBlank()) statusView.text = "Live transcript:"
            },
            onNote = { note ->
                liveNote = note
                statusView.text = note
            }
        ).also { it.start() }
    }

    private fun stopAndSave() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)

        val finals = liveSpeech?.stop().orEmpty()
        liveSpeech = null

        val result = recorder.stop()
        if (result == null) {
            toast("Nothing was recorded.")
            finish()
            return
        }
        val (file, duration) = result

        Draft.pending = Draft(
            kind = KIND_VOICE,
            audioPath = file.absolutePath,
            mime = "audio/mp4",
            durationMs = duration,
            createdAt = System.currentTimeMillis(),
            sourceFile = "",
            line = "",
            client = null,
            phoneDigits = "",
            contactName = "",
            transcript = finals,
            liveNote = liveNote,
            importedBy = "manual",
            origin = "recorded here"
        )
        startActivity(Intent(this, SaveActivity::class.java))
        finish()
    }

    override fun onBackPressed() {
        if (running) {
            recorder.cancel()
            liveSpeech?.cancel()
            running = false
            handler.removeCallbacks(tick)
        }
        super.onBackPressed()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
