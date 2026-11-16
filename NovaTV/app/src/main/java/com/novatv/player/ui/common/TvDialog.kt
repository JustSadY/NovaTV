package com.novatv.player.ui.common

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.novatv.player.R

/** Remote-friendly dialogs with the app's dark styling. */
object TvDialog {

    fun options(
        context: Context,
        title: String,
        options: List<String>,
        selected: Int = -1,
        onPick: (Int) -> Unit,
    ): Dialog {
        val (dialog, root) = create(context, title)
        val container = root.findViewById<LinearLayout>(R.id.dlg_options)
        root.findViewById<ScrollView>(R.id.dlg_options_scroll).apply {
            visibility = View.VISIBLE
            // Limit height so long lists scroll inside the dialog.
            val maxH = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
            post { if (height > maxH) layoutParams = layoutParams.apply { height = maxH } }
        }
        var focusTarget: View? = null
        options.forEachIndexed { i, label ->
            val row = LayoutInflater.from(context).inflate(R.layout.item_menu, container, false) as TextView
            row.text = if (i == selected) "✓  $label" else label
            if (i == selected) row.setTextColor(ContextCompat.getColor(context, R.color.accent_light))
            row.setOnClickListener {
                dialog.dismiss()
                onPick(i)
            }
            row.setOnFocusChangeListener { v, has ->
                (v as TextView).setTextColor(
                    ContextCompat.getColor(context, if (has) R.color.text_on_accent else if (i == selected) R.color.accent_light else R.color.text_primary)
                )
            }
            container.addView(row)
            if (i == selected || focusTarget == null) focusTarget = row
        }
        dialog.show()
        focusTarget?.post { focusTarget?.requestFocus() }
        return dialog
    }

    fun message(context: Context, title: String, message: CharSequence): Dialog =
        confirm(context, title, message, context.getString(R.string.ok), null, {})

    fun confirm(
        context: Context,
        title: String,
        message: CharSequence,
        positive: String,
        negative: String?,
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null,
    ): Dialog {
        val (dialog, root) = create(context, title)
        root.findViewById<ScrollView>(R.id.dlg_message_scroll).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.dlg_message).text = message
        val buttons = root.findViewById<LinearLayout>(R.id.dlg_buttons)
        buttons.visibility = View.VISIBLE
        val positiveBtn = addButton(buttons, positive) {
            dialog.dismiss()
            onPositive()
        }
        if (negative != null) {
            addButton(buttons, negative) {
                dialog.dismiss()
                onNegative?.invoke()
            }
        }
        dialog.show()
        positiveBtn.post { positiveBtn.requestFocus() }
        return dialog
    }

    fun input(
        context: Context,
        title: String,
        initial: String,
        hint: String = "",
        numeric: Boolean = false,
        onDone: (String) -> Unit,
    ): Dialog {
        val (dialog, root) = create(context, title)
        val edit = root.findViewById<EditText>(R.id.dlg_input)
        edit.visibility = View.VISIBLE
        edit.hint = hint
        edit.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        edit.setText(initial)
        edit.setSelection(edit.text.length)
        val buttons = root.findViewById<LinearLayout>(R.id.dlg_buttons)
        buttons.visibility = View.VISIBLE
        val submit = {
            dialog.dismiss()
            onDone(edit.text.toString().trim())
        }
        addButton(buttons, context.getString(R.string.save)) { submit() }
        addButton(buttons, context.getString(R.string.cancel)) { dialog.dismiss() }
        edit.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submit()
                true
            } else {
                false
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edit.post {
            edit.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
        return dialog
    }

    /**
     * Waits for one remote button. Keys for which [accept] is false (navigation, OK, BACK...) keep
     * working for the dialog's own buttons; the first other button is reported with its key code
     * and hardware scan code once it is released.
     */
    fun captureKey(
        context: Context,
        title: String,
        message: CharSequence,
        resetLabel: String,
        accept: (keyCode: Int, scanCode: Int) -> Boolean,
        onReset: () -> Unit,
        onKey: (keyCode: Int, scanCode: Int) -> Unit,
    ): Dialog {
        val (dialog, root) = create(context, title)
        root.findViewById<ScrollView>(R.id.dlg_message_scroll).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.dlg_message).text = message
        val buttons = root.findViewById<LinearLayout>(R.id.dlg_buttons)
        buttons.visibility = View.VISIBLE
        val cancelBtn = addButton(buttons, context.getString(R.string.cancel)) { dialog.dismiss() }
        addButton(buttons, resetLabel) {
            dialog.dismiss()
            onReset()
        }
        var downCode = -1
        var downScan = 0
        dialog.setOnKeyListener { _, keyCode, event ->
            if (!accept(keyCode, event.scanCode)) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                downCode = keyCode
                downScan = event.scanCode
            } else if (event.action == KeyEvent.ACTION_UP && keyCode == downCode && event.scanCode == downScan) {
                dialog.dismiss()
                onKey(keyCode, event.scanCode)
            }
            true
        }
        dialog.show()
        cancelBtn.post { cancelBtn.requestFocus() }
        return dialog
    }

    private fun create(context: Context, title: String): Pair<Dialog, View> {
        val dialog = Dialog(context, R.style.NovaDialog)
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_tv, null)
        root.findViewById<TextView>(R.id.dlg_title).text = title
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        return dialog to root
    }

    private fun addButton(container: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val ctx = container.context
        val btn = TextView(ctx, null, 0, R.style.NovaButton)
        btn.text = label
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            ctx.resources.getDimensionPixelSize(R.dimen.dialog_button_height)
        )
        if (container.childCount > 0) lp.marginStart = ctx.resources.getDimensionPixelSize(R.dimen.dialog_button_gap)
        btn.layoutParams = lp
        btn.setOnClickListener { onClick() }
        container.addView(btn)
        return btn
    }
}
