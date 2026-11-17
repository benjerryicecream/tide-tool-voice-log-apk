package com.chase.tidetoolvoicelog.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chase.tidetoolvoicelog.App
import com.chase.tidetoolvoicelog.R
import com.chase.tidetoolvoicelog.data.Draft
import com.chase.tidetoolvoicelog.data.Lines
import com.chase.tidetoolvoicelog.media.Importer
import com.chase.tidetoolvoicelog.media.TranscriptionRunner
import com.chase.tidetoolvoicelog.util.Fmt
import com.chase.tidetoolvoicelog.util.Phone
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SaveActivity : AppCompatActivity() {

    private lateinit var draft: Draft
    private lateinit var screen: Screen
    private var jobEdit: TextInputEditText? = null
    private var transcriptEdit: TextInputEditText? = null
    private var notesEdit: TextInputEditText? = null
    private var lineSpinner: Spinner? = null
    private var matchHost: LinearLayout? = null
    private var line = Lines.BUSINESS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = Draft.pending
        if (pending == null) {
            finish()
            return
        }
        draft = pending
        screen = Screen(this)
        setContentView(screen.root)
        build()
    }

    private fun build() {
        screen.title(if (draft.kind == "call") "Save call recording" else "Save voice note", back = true)

        // who called
        if (draft.kind == "call") {
            matchHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            screen.body.addView(matchHost)
            renderMatch()
        }

        screen.sectionLabel("Client or job")
        val initial = draft.client?.name
            ?: draft.contactName.takeIf { it.isNotBlank() }
            ?: ""
        jobEdit = screen.field("Who is this for?", initial)

        // quick chips for recent jobs
        val recent = App.repo.jobs().take(6)
        if (recent.isNotEmpty()) {
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                removeAllViews()
            }
            val scrollWrap = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }
            recent.forEach { j ->
                val chip = MaterialButton(chips.context, null, android.R.attr.borderlessButtonStyle).apply {
                    text = j.name
                    textSize = 13f
                    isAllCaps = false
                    setTextColor(color(R.color.ink2))
                    backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.surface2))
                    cornerRadius = dp(20)
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minHeight = 0
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnClickListener {
                        jobEdit?.setText(j.name)
                        jobEdit?.setSelection(j.name.length)
                    }
                }
                chips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                })
            }
            screen.body.addView(scrollWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        screen.sectionLabel("When")
        screen.body.addView(screen.text(Fmt.whenFull(draft.createdAt), 16f, R.color.ink2))
        if (draft.kind == "call" && draft.sourceFile.isNotBlank()) {
            screen.body.addView(screen.muted(draft.sourceFile, 12.5f))
        }

        if (draft.kind == "call") {
            screen.sectionLabel("Line")
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@SaveActivity, android.R.layout.simple_spinner_dropdown_item, Lines.ALL)
                setSelection(Lines.ALL.indexOf(line).coerceAtLeast(0))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        line = Lines.ALL[position]
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
                backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.ink2))
            }
            screen.body.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            screen.body.addView(
                screen.muted("The recording does not say which of the two lines took the call. Change it if you know."),
            )
            lineSpinner = spinner
        }

        screen.sectionLabel("Transcript")
        val tHint = when {
            draft.transcript.isNotBlank() -> "From live speech - check the words before saving."
            draft.kind == "call" -> "Not transcribed yet. This is transcribed on the device after saving."
            else -> "Not transcribed yet. You can type what you said, or transcribe it later."
        }
        transcriptEdit = screen.field("Transcript", draft.transcript, multiline = true)
        screen.body.addView(screen.muted(tHint, 12.5f))
        if (draft.liveNote.isNotBlank()) screen.body.addView(screen.muted(draft.liveNote, 12.5f))

        screen.sectionLabel("Notes")
        notesEdit = screen.field("What you saw, what you quoted, what is next", "", multiline = true)

        screen.bottomBar {
            val save = MaterialButton(context).apply {
                text = "Save to log"
                textSize = 17f
                isAllCaps = false
                setTextColor(context.color(R.color.ink_on_teal))
                backgroundTintList = android.content.res.ColorStateList.valueOf(context.color(R.color.teal))
                cornerRadius = context.dp(14)
                insetTop = 0
                insetBottom = 0
                minHeight = context.dp(58)
                setOnClickListener { commit() }
            }
            addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        jobEdit?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun renderMatch() {
        val host = matchHost ?: return
        host.removeAllViews()
        val client = draft.client

        val card = androidx.appcompat.widget.LinearLayoutCompat(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = dp(14).toFloat()
            bg.setColor(color(if (client != null) R.color.teal_dim else R.color.surface2))
            background = bg
        }

        if (client != null) {
            card.addView(screen.text("Phone book: ${client.name} - ${client.phone}", 14f, R.color.ink))
        } else if (draft.phoneDigits.isNotBlank()) {
            card.addView(screen.text("${Phone.pretty(draft.phoneDigits)} is not in the phone book.", 14f, R.color.sand))
            val add = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Add as client"
                textSize = 14f
                isAllCaps = false
                setTextColor(color(R.color.sand))
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                setPadding(0, dp(8), 0, 0)
                setOnClickListener { addClientDialog() }
            }
            card.addView(add)
        } else if (draft.contactName.isNotBlank()) {
            card.addView(screen.text("\"${draft.contactName}\" came from the file name and is not in the phone book.", 14f, R.color.sand))
            val add = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Add as client"
                textSize = 14f
                isAllCaps = false
                setTextColor(color(R.color.sand))
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                setPadding(0, dp(8), 0, 0)
                setOnClickListener { addClientDialog() }
            }
            card.addView(add)
        } else {
            return
        }

        host.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    private fun addClientDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val nameEdit = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Name"
            setText(draft.contactName.ifBlank { jobEdit?.text?.toString().orEmpty() })
        }
        val phoneEdit = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Phone"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(Phone.pretty(draft.phoneDigits))
        }
        box.addView(nameEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(phoneEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        AlertDialog.Builder(this)
            .setTitle("Add a client")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val c = App.repo.addClient(nameEdit.text.toString(), phoneEdit.text.toString())
                if (c != null) {
                    draft = draft.copy(client = c)
                    jobEdit?.setText(c.name)
                    renderMatch()
                    toast("Added ${c.name}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun commit() {
        val jobName = jobEdit?.text?.toString()?.trim().orEmpty()
        if (jobName.isEmpty()) {
            toast("Add a client or job name first.")
            jobEdit?.requestFocus()
            return
        }
        try {
            val rec = Importer.commit(
                draft,
                jobName,
                line,
                transcriptEdit?.text?.toString().orEmpty(),
                notesEdit?.text?.toString().orEmpty()
            )
            Draft.pending = null
            if (rec.isCall && rec.transcript.isBlank()) {
                TranscriptionRunner.enqueue(this, rec.id)
                toast("Saved. Transcribing on the device - the speech model downloads once.")
            } else {
                toast("Saved to $jobName")
            }
            finish()
        } catch (e: Exception) {
            toast("Could not save: ${e.message}")
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
