package com.chase.tidetoolvoicelog.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chase.tidetoolvoicelog.App
import com.chase.tidetoolvoicelog.R
import com.chase.tidetoolvoicelog.util.Phone
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class PhonebookActivity : AppCompatActivity() {

    private lateinit var screen: Screen
    private lateinit var listHost: LinearLayout
    private var editingId: String? = null
    private var nameEdit: TextInputEditText? = null
    private var phoneEdit: TextInputEditText? = null
    private lateinit var saveButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = Screen(this)
        setContentView(screen.root)
        build()
        renderList()
    }

    private fun build() {
        screen.title("Phone book", back = true)
        screen.body.addView(screen.muted("Names and numbers used to work out who an imported call is from. Stays on this device."))

        screen.sectionLabel("Name")
        nameEdit = screen.field("e.g. Ray Kalani")
        screen.sectionLabel("Phone")
        phoneEdit = screen.field("(808) 555-1234", numeric = true)

        saveButton = screen.primary("Add client") { save() }
        screen.secondary("Cancel edit") {
            editingId = null
            nameEdit?.setText("")
            phoneEdit?.setText("")
            saveButton.text = "Add client"
        }

        screen.sectionLabel("Clients")
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.body.addView(listHost)
    }

    private fun renderList() {
        listHost.removeAllViews()
        val clients = App.repo.clients()
        if (clients.isEmpty()) {
            listHost.addView(screen.muted("No clients yet. Add a name and a number, or add one straight from an imported call."))
            return
        }
        clients.forEach { c ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(screen.textView(c.name, 15.5f, R.color.ink, true))
            textCol.addView(screen.text(if (c.phone.isBlank()) "no number" else c.phone, 13f, R.color.muted))
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Edit"
                textSize = 13f
                isAllCaps = false
                setTextColor(color(R.color.teal))
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    editingId = c.id
                    nameEdit?.setText(c.name)
                    phoneEdit?.setText(c.phone)
                    saveButton.text = "Save changes"
                }
            })
            row.addView(MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Remove"
                textSize = 13f
                isAllCaps = false
                setTextColor(color(R.color.coral))
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                setPadding(dp(8), dp(4), 0, dp(4))
                setOnClickListener { confirmRemove(c.id, c.name) }
            })
            listHost.addView(row)
            listHost.addView(android.view.View(this).apply { setBackgroundColor(color(R.color.line)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
    }

    private fun save() {
        val name = nameEdit?.text?.toString()?.trim().orEmpty()
        val phone = phoneEdit?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toast("A name is needed - the number alone cannot be matched from a file name.")
            return
        }
        val existing = editingId
        if (existing != null) {
            App.repo.updateClient(existing, name, phone)
            toast("Client updated")
        } else {
            val c = App.repo.addClient(name, phone)
            toast(if (c?.phone.isNullOrBlank()) "Added ${c?.name}" else "Added ${c?.name} - ${c?.phone}")
        }
        editingId = null
        nameEdit?.setText("")
        phoneEdit?.setText("")
        saveButton.text = "Add client"
        renderList()
    }

    private fun confirmRemove(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove $name?")
            .setMessage("Recordings already saved keep their name and number.")
            .setPositiveButton("Remove") { _, _ ->
                App.repo.deleteClient(id)
                if (editingId == id) {
                    editingId = null
                    nameEdit?.setText("")
                    phoneEdit?.setText("")
                    saveButton.text = "Add client"
                }
                renderList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
