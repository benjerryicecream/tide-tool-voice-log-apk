package com.chase.tidetoolvoicelog.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.chase.tidetoolvoicelog.App
import com.chase.tidetoolvoicelog.R
import com.chase.tidetoolvoicelog.call.LineId
import com.chase.tidetoolvoicelog.util.Fmt

/**
 * The honest window into the 2nd-line inference: what the last incoming call
 * looked like, what the app concluded, and every account it could see.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = Screen(this)
        setContentView(screen.root)
        screen.title("Diagnostics", back = true)

        val last = App.prefs.lastCall()
        if (last == null) {
            screen.body.addView(screen.muted("No incoming call seen yet. Make a call to either line and come back."))
        } else {
            screen.card {
                addView(screen.textView("Last incoming call", 15f, R.color.ink, true))
                addView(screen.text(Fmt.whenFull(last.at), 13.5f, R.color.muted).apply { setPadding(0, dp(4), 0, 0) })
                addView(screen.text("Caller: ${last.contactName.ifBlank { last.number.ifBlank { "unknown" } }}", 15f, R.color.ink).apply { setPadding(0, dp(10), 0, 0) })
                addView(screen.text("Verdict: ${last.verdict}", 14f, if (last.prompted) R.color.teal else R.color.sand).apply { setPadding(0, dp(6), 0, 0) })
                addView(screen.text("Prompted: ${if (last.prompted) "yes" else "no"}", 14f, R.color.ink2).apply { setPadding(0, dp(6), 0, 0) })
            }

            screen.card {
                addView(screen.textView("What Android gave us", 15f, R.color.ink, true))
                addView(screen.text("Account handle id: ${last.accountId.ifBlank { "-" }}", 14f, R.color.ink2).apply { setPadding(0, dp(8), 0, 0) })
                addView(screen.text("Account label: ${last.accountLabel.ifBlank { "-" }}", 14f, R.color.ink2).apply { setPadding(0, dp(4), 0, 0) })
                addView(screen.text("Subscription id: ${if (last.subscriptionId >= 0) last.subscriptionId.toString() else "unknown"}", 14f, R.color.ink2).apply { setPadding(0, dp(4), 0, 0) })
                addView(screen.text("SIM slot: ${if (last.slotIndex >= 0) "SIM ${last.slotIndex + 1}" else "unknown"}", 14f, R.color.ink2).apply { setPadding(0, dp(4), 0, 0) })
                addView(screen.text("Number: ${last.number.ifBlank { "-" }}", 14f, R.color.ink2).apply { setPadding(0, dp(4), 0, 0) })
            }

            screen.card {
                addView(screen.textView("Accounts on this phone", 15f, R.color.ink, true))
                addView(screen.muted(last.otherAccounts.ifBlank { LineId.describe(LineId.accounts(this@DiagnosticsActivity)) }, 12.5f).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }

        screen.sectionLabel("Pinned business line")
        val id = App.prefs.businessAccountId
        val label = App.prefs.businessAccountLabel
        screen.body.addView(
            screen.text(
                if (id.isBlank()) "Not set - the prompt will never fire until you pick it in Setup."
                else "$label (id=$id)",
                14f, if (id.isBlank()) R.color.sand else R.color.ink
            )
        )
        screen.body.addView(screen.muted("The prompt only fires when the call's account handle matches this id.", 12.5f))
    }
}
