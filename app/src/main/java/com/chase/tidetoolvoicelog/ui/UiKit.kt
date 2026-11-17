package com.chase.tidetoolvoicelog.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chase.tidetoolvoicelog.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

fun Context.dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)

/**
 * Screens are built in code: a header, a scrolling body, and an optional fixed
 * bottom bar for the thumb. Big text, big targets - this is used with dirty hands.
 */
class Screen(private val act: Activity) {

    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(act.color(R.color.bg))
    }

    private val headerRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(16), act.dp(14), act.dp(12), act.dp(10))
    }

    private val titleBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    val body = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(96))
    }

    private val scroll = ScrollView(act).apply {
        isFillViewport = true
        addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val bottomBar = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(18))
        setBackgroundColor(act.color(R.color.bg))
    }

    init {
        headerRow.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomBar.visibility = View.GONE

        // draw under the system bars, but keep the header and the bottom bar out of them
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, 0)
            bottomBar.setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(18) + bars.bottom)
            insets
        }
    }

    fun title(text: String, subtitle: String? = null, back: Boolean = false) {
        titleBox.removeAllViews()
        titleBox.addView(textView(text, 22f, R.color.ink, true))
        if (!subtitle.isNullOrBlank()) {
            titleBox.addView(textView(subtitle, 12f, R.color.muted, false).apply {
                letterSpacing = 0.12f
                setPadding(0, act.dp(2), 0, 0)
            })
        }
        if (back) {
            headerRow.removeAllViews()
            val b = MaterialButton(act, null, android.R.attr.borderlessButtonStyle).apply {
                setText("< Back")
                setTextColor(act.color(R.color.teal))
                textSize = 15f
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minHeight = 0
                setPadding(act.dp(4), act.dp(8), act.dp(10), act.dp(8))
                setOnClickListener { act.finish() }
            }
            headerRow.addView(b)
            headerRow.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    fun action(label: String, onClick: () -> Unit) {
        val b = MaterialButton(act, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            setTextColor(act.color(R.color.teal))
            textSize = 14f
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minHeight = 0
            setPadding(act.dp(8), act.dp(8), act.dp(4), act.dp(8))
            setOnClickListener { onClick() }
        }
        headerRow.addView(b)
    }

    fun bottomBar(build: LinearLayout.() -> Unit) {
        bottomBar.visibility = View.VISIBLE
        bottomBar.removeAllViews()
        bottomBar.build()
    }

    /* ------------------------------------------------------------ builders */

    fun textView(s: String, size: Float, colorId: Int, bold: Boolean): TextView =
        TextView(act).apply {
            text = s
            textSize = size
            setTextColor(act.color(colorId))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(act.dp(2).toFloat(), 1f)
        }

    fun text(s: String, size: Float = 16f, colorId: Int = R.color.ink): TextView =
        textView(s, size, colorId, false)

    fun muted(s: String, size: Float = 13.5f): TextView = textView(s, size, R.color.muted, false)

    fun sectionLabel(s: String): TextView =
        textView(s.uppercase(), 11.5f, R.color.muted, true).apply {
            letterSpacing = 0.1f
            setPadding(0, act.dp(12), 0, act.dp(6))
        }

    fun card(parent: LinearLayout? = null, build: LinearLayout.() -> Unit): MaterialCardView {
        val card = MaterialCardView(act)
        card.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(act.color(R.color.surface)))
        card.setStrokeColor(android.content.res.ColorStateList.valueOf(act.color(R.color.line)))
        card.strokeWidth = act.dp(1)
        card.radius = act.dp(18).toFloat()
        card.cardElevation = 0f
        card.useCompatPadding = false
        val inner = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(14))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        inner.build()
        card.addView(inner)
        (parent ?: body).addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = act.dp(12)
        })
        return card
    }

    fun field(
        hint: String,
        value: String = "",
        multiline: Boolean = false,
        numeric: Boolean = false,
        password: Boolean = false
    ): TextInputEditText {
        val layout = TextInputLayout(act).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeColor = act.color(R.color.line)
            boxBackgroundColor = act.color(R.color.surface)
            setBoxCornerRadii(act.dp(14).toFloat(), act.dp(14).toFloat(), act.dp(14).toFloat(), act.dp(14).toFloat())
            setPadding(0, act.dp(2), 0, act.dp(10))
            defaultHintTextColor = android.content.res.ColorStateList.valueOf(act.color(R.color.muted))
        }
        val edit = TextInputEditText(act).apply {
            setText(value)
            textSize = 17f
            setTextColor(act.color(R.color.ink))
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                gravity = Gravity.TOP
            } else setInputType(InputType.TYPE_CLASS_TEXT)
            if (numeric) inputType = InputType.TYPE_CLASS_PHONE
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(edit)
        body.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return edit
    }

    fun primary(label: String, onClick: () -> Unit): MaterialButton = button(label, R.color.teal, R.color.ink_on_teal, onClick)

    fun secondary(label: String, onClick: () -> Unit): MaterialButton = button(label, R.color.surface2, R.color.ink, onClick)

    fun danger(label: String, onClick: () -> Unit): MaterialButton = button(label, R.color.surface2, R.color.coral, onClick)

    private fun button(label: String, bg: Int, fg: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(act).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setTextColor(act.color(fg))
            backgroundTintList = android.content.res.ColorStateList.valueOf(act.color(bg))
            cornerRadius = act.dp(14)
            minHeight = act.dp(56)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
            body.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = act.dp(8)
            })
        }

    fun gap(px: Int = 12) {
        body.addView(View(act), LinearLayout.LayoutParams(1, act.dp(px)))
    }

    /** An empty container you can clear and refill (the log list, a task list...). */
    fun host(): LinearLayout {
        val h = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        body.addView(h, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return h
    }

    fun divider() {
        body.addView(View(act).apply { setBackgroundColor(act.color(R.color.line)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = act.dp(10)
                bottomMargin = act.dp(10)
            })
    }

    /** A tappable row inside a card. */
    fun row(title: String, sub: String?, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, act.dp(10), 0, act.dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(textView(title, 16f, R.color.ink, false))
        if (!sub.isNullOrBlank()) row.addView(textView(sub, 13f, R.color.muted, false).apply { setPadding(0, act.dp(3), 0, 0) })
        body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }
}

/** Small helper for coloured pills like "2nd line" or "needs summary". */
fun pill(context: Context, label: String, colorId: Int): TextView =
    TextView(context).apply {
        text = label
        textSize = 11.5f
        val c = context.color(colorId)
        setTextColor(c)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = context.dp(20).toFloat()
        bg.setColor(Color.argb(30, Color.red(c), Color.green(c), Color.blue(c)))
        background = bg
    }
