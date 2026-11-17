package com.chase.tidetoolvoicelog.ui

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chase.tidetoolvoicelog.App
import com.chase.tidetoolvoicelog.R
import com.chase.tidetoolvoicelog.call.LineId
import com.chase.tidetoolvoicelog.data.Lines
import com.google.android.material.button.MaterialButton

class SetupActivity : AppCompatActivity() {

    private lateinit var screen: Screen
    private lateinit var listHost: LinearLayout

    private val requestPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
    }

    private val requestRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = Screen(this)
        setContentView(screen.root)
        build()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun build() {
        screen.title("Setup", back = true)
        screen.body.addView(
            screen.text(
                "The app never records a call, never taps anything for you, and never blocks a call. " +
                    "It watches which line is ringing, tells you to tap Record in Samsung Phone, and files the recording afterwards.",
                14f, R.color.ink2
            )
        )
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.body.addView(listHost)
    }

    private fun refresh() {
        listHost.removeAllViews()

        val roleManager = getSystemService(RoleManager::class.java)
        val hasRole = roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

        item(
            "Call screening role",
            "Lets the app see a call before it rings, which is the only moment a prompt is useful.",
            hasRole
        ) {
            if (roleManager == null) {
                toast("This phone has no call screening role.")
            } else {
                try {
                    requestRole.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                } catch (e: Exception) {
                    toast("Could not open the role dialog: ${e.message}")
                }
            }
        }

        item(
            "Phone state",
            "Needed to read which phone account the call came in on, and to take the prompt away when the call ends.",
            granted(Manifest.permission.READ_PHONE_STATE)
        ) {
            requestPerms.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
        }

        item(
            "Contacts",
            "So the prompt can show the client's name instead of just a number.",
            granted(Manifest.permission.READ_CONTACTS)
        ) {
            requestPerms.launch(arrayOf(Manifest.permission.READ_CONTACTS))
        }

        item(
            "Notifications",
            "The prompt is a notification. Without this it cannot appear at all.",
            granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            requestPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }

        item(
            "Full-screen prompt",
            "Android 14+ needs this special access before a prompt can take over the lock screen. Without it you still get a heads-up notification while the phone is unlocked.",
            hasFullScreen()
        ) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } else {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(Uri.parse("package:$packageName")))
                }
            } catch (e: Exception) {
                toast("Open Settings  Apps  Tide & Tool  Allow full-screen notifications.")
            }
        }

        screen.sectionLabel("Which account is the business line?")
        screen.body.addView(
            screen.muted(
                "Android does not say which SIM slot a call arrived on. Pick the Verizon eSIM here once; " +
                    "the prompt only fires for this account. Everything the app sees is on the Diagnostics screen.",
                13f
            )
        )

        val accounts = LineId.accounts(this)
        if (accounts.isEmpty()) {
            screen.body.addView(screen.muted("No call-capable accounts visible. Grant phone state first.", 13f))
        } else {
            val pinned = App.prefs.businessAccountId
            accounts.forEach { a ->
                val selected = a.handleId == pinned
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    setOnClickListener {
                        App.prefs.businessAccountId = a.handleId
                        App.prefs.businessAccountLabel = a.label
                        toast("Business line set to ${a.label}")
                        refresh()
                    }
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.cornerRadius = dp(12).toFloat()
                    bg.setColor(color(if (selected) R.color.teal_dim else R.color.surface))
                    background = bg
                }
                row.addView(screen.text((if (selected) "OK " else "") + a.label, 15f, if (selected) R.color.teal else R.color.ink))
                row.addView(screen.text("id=${a.handleId} - sub=${a.subText} - ${a.slotText}", 12f, R.color.muted))
                listHost.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })
            }

            val guess = LineId.suggestBusiness(this)
            if (pinned.isEmpty() && guess != null) {
                screen.body.addView(screen.muted("Looks like ${guess.label} is the second line."))
                val use = MaterialButton(this)
                use.text = "Use ${guess.label}"
                use.isAllCaps = false
                use.setTextColor(color(R.color.ink_on_teal))
                use.backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.teal))
                use.cornerRadius = dp(14)
                use.insetTop = 0
                use.insetBottom = 0
                use.minHeight = dp(52)
                use.setOnClickListener {
                    App.prefs.businessAccountId = guess.handleId
                    App.prefs.businessAccountLabel = guess.label
                    toast("Business line set to ${guess.label}")
                    refresh()
                }
                screen.body.addView(use, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                })
            }
        }

        screen.secondary("Diagnostics (last call)") { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
    }

    private fun item(title: String, why: String, ok: Boolean, onFix: () -> Unit) {
        screen.card(listHost) {
            val head = LinearLayout(this@SetupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(screen.textView(title, 15.5f, R.color.ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(pill(this@SetupActivity, if (ok) "ready" else "needed", if (ok) R.color.teal else R.color.sand))
            addView(head)
            addView(screen.muted(why, 13f).apply { setPadding(0, dp(6), 0, dp(4)) })
            if (!ok) {
                val b = MaterialButton(this@SetupActivity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Set up"
                    textSize = 14f
                    isAllCaps = false
                    setTextColor(color(R.color.teal))
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    setPadding(0, dp(6), 0, 0)
                    setOnClickListener { onFix() }
                }
                addView(b)
            }
        }
    }

    private fun granted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun hasFullScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        return nm.canUseFullScreenIntent()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
