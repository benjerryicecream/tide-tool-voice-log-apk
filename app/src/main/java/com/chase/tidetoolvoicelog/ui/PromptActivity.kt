package com.chase.tidetoolvoicelog.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.chase.tidetoolvoicelog.R
import com.chase.tidetoolvoicelog.call.CallPrompt
import com.chase.tidetoolvoicelog.call.PromptReceiver
import com.google.android.material.button.MaterialButton

/**
 * The screen a locked phone shows when the business line rings. It only ever
 * tells the user what to do - the tap on Samsung's Record button is his.
 */
class PromptActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val caller = intent.getStringExtra(EXTRA_CALLER).orEmpty()

        val screen = Screen(this)
        setContentView(screen.root)
        screen.title("Business call")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(40), 0, 0)
        }
        val big = screen.textView("Business call", 30f, R.color.teal, true).apply { gravity = Gravity.CENTER }
        box.addView(big)
        val who = screen.textView(caller.ifBlank { "2nd line is ringing" }, 20f, R.color.ink, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(who)
        val what = screen.textView(
            "Answer, then tap Record in the Samsung Phone app. Recording is announced to the caller.",
            16f, R.color.ink2, false
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        box.addView(what)
        screen.body.addView(box)

        screen.bottomBar {
            val open = MaterialButton(context).apply {
                text = "Open Phone"
                textSize = 17f
                isAllCaps = false
                setTextColor(context.color(R.color.ink_on_teal))
                backgroundTintList = android.content.res.ColorStateList.valueOf(context.color(R.color.teal))
                cornerRadius = context.dp(14)
                insetTop = 0
                insetBottom = 0
                minHeight = context.dp(60)
                setOnClickListener {
                    sendBroadcast(Intent(this@PromptActivity, PromptReceiver::class.java).setAction(PromptReceiver.ACTION_OPEN_PHONE))
                    finish()
                }
            }
            val dismiss = MaterialButton(context).apply {
                text = "Dismiss"
                textSize = 15f
                isAllCaps = false
                setTextColor(context.color(R.color.ink))
                backgroundTintList = android.content.res.ColorStateList.valueOf(context.color(R.color.surface2))
                cornerRadius = context.dp(14)
                insetTop = 0
                insetBottom = 0
                minHeight = context.dp(60)
                setOnClickListener {
                    CallPrompt.dismiss(this@PromptActivity)
                    finish()
                }
            }
            addView(open, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { marginEnd = context.dp(8) })
            addView(dismiss, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    override fun onPause() {
        super.onPause()
        // The prompt stays as a notification after this screen goes away.
        finish()
    }

    companion object {
        const val EXTRA_CALLER = "caller"
    }
}
